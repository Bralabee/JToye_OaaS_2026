package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.exception.LastGroupAdminException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.dto.DirectoryEntryDto;
import uk.jtoye.core.security.access.dto.StaffMemberDto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * GROUP_ADMIN-only staff-management backend (Phase 23, VSA-04) — the write side of
 * the vendor-scoped access model. Lists the login-populated {@code user_directory}
 * grant-target picker + the tenant's current {@code shop_staff} grants, grants a
 * {@code (user, shop|null, role)} idempotently, and revokes a grant, all behind the
 * single {@link ShopAccessService#requireGroupAdmin()} gate (D-10 typed 403 for a
 * non-GROUP_ADMIN caller; the realm-admin implicit-GROUP_ADMIN bridge passes).
 *
 * <p><strong>Invariants</strong>:
 * <ul>
 *   <li><strong>Idempotent grant (agent-readiness)</strong> — the write is the
 *       race-safe {@link ShopStaffRepository#insertGrantIfAbsent} (ON CONFLICT DO
 *       NOTHING on {@code uq_shop_staff_tenant_user_shop}); a retried/duplicate
 *       grant replays the canonical row as a typed 200, never an untyped
 *       unique-constraint 500.</li>
 *   <li><strong>Last-GROUP_ADMIN lockout guard (D-11)</strong> — a revoke of the
 *       final tenant-wide GROUP_ADMIN, or a grant that would downgrade the sole
 *       GROUP_ADMIN's tenant-wide slot, is blocked with a
 *       {@link LastGroupAdminException} (RFC 7807 409). The realm-admin bridge is a
 *       recovery backstop, but a non-realm-admin group could otherwise self-lock.</li>
 *   <li><strong>Immediate effect (D-05)</strong> — after the DB write COMMITS,
 *       {@link ShopAccessService#evictMembership(UUID)} runs so the target's very
 *       next request re-resolves from {@code shop_staff} with no stale window.</li>
 * </ul>
 *
 * <p>Grant targets come from {@code user_directory} (D-09) — invitations / account
 * creation stay in Keycloak, out of scope. No Keycloak enumeration.
 */
@Service
@Transactional
public class StaffManagementService {

    private static final Logger log = LoggerFactory.getLogger(StaffManagementService.class);

    private final ShopStaffRepository shopStaffRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final ShopAccessService shopAccessService;

    public StaffManagementService(ShopStaffRepository shopStaffRepository,
                                  UserDirectoryRepository userDirectoryRepository,
                                  ShopAccessService shopAccessService) {
        this.shopStaffRepository = shopStaffRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.shopAccessService = shopAccessService;
    }

    /** The GET /api/v1/staff body: the grant-target picker + the current grants. */
    public record StaffListResponse(List<DirectoryEntryDto> directory, List<StaffMemberDto> grants) {
    }

    /**
     * Result of a grant: the canonical {@link StaffMemberDto} plus whether it was a
     * fresh insert ({@code true} → controller emits 201) or an idempotent replay of
     * an existing grant ({@code false} → 200). Carrying the flag keeps HTTP-status
     * selection out of the service while letting a duplicate grant be a typed replay.
     */
    public record GrantResult(StaffMemberDto member, boolean created) {
    }

    /**
     * List the login-populated directory (grant-target picker, D-09) + all current
     * {@code shop_staff} grants for the tenant. GROUP_ADMIN only.
     */
    @Transactional(readOnly = true)
    public StaffListResponse list() {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();
        List<DirectoryEntryDto> directory = userDirectoryRepository.findByTenantId(tenantId).stream()
                .map(DirectoryEntryDto::from)
                .toList();
        List<StaffMemberDto> grants = shopStaffRepository.findByTenantId(tenantId).stream()
                .map(StaffMemberDto::from)
                .toList();
        return new StaffListResponse(directory, grants);
    }

    /**
     * Grant {@code (userId, shopId|null, role)} idempotently. GROUP_ADMIN only.
     *
     * <p>Guards first (D-11): a tenant-wide grant that would downgrade the sole
     * GROUP_ADMIN's tenant-wide slot to a lesser role is rejected with 409. Then the
     * race-safe {@link ShopStaffRepository#insertGrantIfAbsent} runs — a concurrent
     * or retried duplicate is a no-op (never a 500). The canonical row is re-selected
     * and returned; on commit the target's membership cache is evicted (D-05).
     *
     * @return {@link GrantResult} with {@code created=true} for a fresh insert,
     *         {@code false} for an idempotent replay of the existing grant.
     */
    public GrantResult grant(UUID userId, UUID shopId, ShopRole role) {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();

        // GROUP_ADMIN is inherently tenant-wide (a NULL shop). A shop-scoped
        // GROUP_ADMIN row would not confer tenant-wide admin (resolveMembership only
        // treats a NULL-shop GROUP_ADMIN row as such) AND would corrupt the
        // countByTenantIdAndRole(GROUP_ADMIN) last-admin guard. Reject it. (Rule 2)
        if (role == ShopRole.GROUP_ADMIN && shopId != null) {
            throw new IllegalArgumentException(
                    "GROUP_ADMIN is a tenant-wide role; shopId must be null for a GROUP_ADMIN grant");
        }

        // D-11 downgrade guard: refuse to change the sole GROUP_ADMIN's tenant-wide
        // slot to a lesser role (the insert-only path would in any case no-op via ON
        // CONFLICT, replaying the GROUP_ADMIN row — surfacing that as a misleading
        // "granted SHOP_MANAGER" success is worse than a clear 409).
        if (shopId == null && role != ShopRole.GROUP_ADMIN && wouldDowngradeLastGroupAdmin(tenantId, userId)) {
            throw new LastGroupAdminException(
                    "Cannot downgrade the last GROUP_ADMIN in this tenant — grant another GROUP_ADMIN first");
        }

        int inserted = shopStaffRepository.insertGrantIfAbsent(
                UUID.randomUUID(), tenantId, userId, shopId, role.name(), currentCallerSub());

        // Re-select the canonical row (a fresh insert OR the pre-existing one on an
        // idempotent replay) so the response is a stable typed DTO either way.
        ShopStaff canonical = shopStaffRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .filter(r -> Objects.equals(r.getShopId(), shopId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Grant row not found after insert for user " + userId + " shop " + shopId));

        evictAfterCommit(userId);
        if (inserted > 0) {
            log.info("Granted {} to user {} (shop {}) in tenant {}", role, userId, shopId, tenantId);
        } else {
            log.debug("Idempotent grant replay for user {} (shop {}) in tenant {}", userId, shopId, tenantId);
        }
        return new GrantResult(StaffMemberDto.from(canonical), inserted > 0);
    }

    /**
     * Revoke the grant identified by {@code id}. GROUP_ADMIN only. Revoking the final
     * tenant-wide GROUP_ADMIN is blocked with a 409 (D-11); otherwise the row is
     * deleted and the target's membership cache is evicted after commit (D-05) so the
     * target immediately receives the typed shop-access 403 on their next request.
     */
    public void revoke(UUID id) {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();

        // RLS scopes this find to the caller's tenant; a cross-tenant id is a 404.
        ShopStaff row = shopStaffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Staff grant not found: " + id));

        if (row.getRole() == ShopRole.GROUP_ADMIN
                && shopStaffRepository.countByTenantIdAndRole(tenantId, ShopRole.GROUP_ADMIN) <= 1) {
            throw new LastGroupAdminException(
                    "Cannot revoke the last GROUP_ADMIN in this tenant — grant another GROUP_ADMIN first");
        }

        UUID targetUserId = row.getUserId();
        if (targetUserId.equals(currentCallerSub())) {
            log.warn("GROUP_ADMIN {} is revoking their OWN grant {} (self-downgrade) in tenant {}",
                    targetUserId, id, tenantId);
        }

        shopStaffRepository.delete(row);
        evictAfterCommit(targetUserId);
        log.info("Revoked grant {} ({} shop {}) from user {} in tenant {}",
                id, row.getRole(), row.getShopId(), targetUserId, tenantId);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * True when {@code userId} currently holds the tenant's ONLY tenant-wide
     * GROUP_ADMIN grant — so downgrading/removing it would lock the tenant out of
     * staff management (D-11).
     */
    private boolean wouldDowngradeLastGroupAdmin(UUID tenantId, UUID userId) {
        if (shopStaffRepository.countByTenantIdAndRole(tenantId, ShopRole.GROUP_ADMIN) > 1) {
            return false;
        }
        return shopStaffRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .anyMatch(r -> r.getShopId() == null && r.getRole() == ShopRole.GROUP_ADMIN);
    }

    /**
     * Evict the target's membership cache AFTER the current transaction commits
     * (D-05 / RESEARCH §4 caveat) so a re-resolve cannot race the just-written row.
     * Falls back to an inline evict when no transaction synchronization is active
     * (the eviction is a no-op in the test profile — no cache manager).
     */
    private void evictAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    shopAccessService.evictMembership(userId);
                }
            });
        } else {
            shopAccessService.evictMembership(userId);
        }
    }

    private UUID currentTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }

    /** The GROUP_ADMIN performing the write (created_by); {@code null} for a non-UUID/system principal. */
    private UUID currentCallerSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            if (sub != null) {
                try {
                    return UUID.fromString(sub);
                } catch (IllegalArgumentException ignored) {
                    // machine/service token — no vendor-user created_by
                }
            }
        }
        return null;
    }
}
