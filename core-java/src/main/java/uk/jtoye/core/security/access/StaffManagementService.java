package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 *   <li><strong>Idempotent, audited grant (agent-readiness + WR-02)</strong> — the
 *       write goes through the Hibernate session so Envers records BOTH the create and
 *       any role change in {@code shop_staff_aud} (not revokes alone). A fresh grant is
 *       an ADD revision; re-granting the SAME role is a no-change replay (no write, no
 *       revision); re-granting a DIFFERENT role APPLIES the change (a downgrade genuinely
 *       takes effect — CR-05, no longer a silent no-op reported as success). A concurrent
 *       duplicate insert is caught and replayed as a typed 200, never an untyped
 *       unique-constraint 500 (T-23-09-04).</li>
 *   <li><strong>Last-GROUP_ADMIN lockout guard (D-11, race-safe)</strong> — a revoke of
 *       the final tenant-wide GROUP_ADMIN, or a grant that would downgrade the sole
 *       GROUP_ADMIN's tenant-wide slot, is blocked with a
 *       {@link LastGroupAdminException} (RFC 7807 409). The check-then-act is serialized
 *       by a {@code PESSIMISTIC_WRITE} lock over the tenant's GROUP_ADMIN rows
 *       ({@link ShopStaffRepository#lockTenantGroupAdmins}) so two concurrent writes
 *       cannot race the tenant to zero GROUP_ADMINs (CR-06). The realm-admin bridge is a
 *       recovery backstop, but a non-realm-admin group could otherwise self-lock. Now
 *       that downgrades genuinely apply, this guard is LOAD-BEARING on the grant path.</li>
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

    /**
     * IN-03: the 409 message for a revoke of the tenant's last GROUP_ADMIN. Extracted to
     * a named constant so the wording cannot drift; kept DISTINCT from the downgrade
     * variant so plan 23-13 can map each precisely to its frontend copy (IN-02).
     */
    static final String MSG_REVOKE_LAST_GROUP_ADMIN =
            "Cannot revoke the last GROUP_ADMIN in this tenant — grant another GROUP_ADMIN first";

    /** IN-03: the 409 message for a downgrade of the tenant's last GROUP_ADMIN (grant path). */
    static final String MSG_DOWNGRADE_LAST_GROUP_ADMIN =
            "Cannot downgrade the last GROUP_ADMIN in this tenant — grant another GROUP_ADMIN first";

    private final ShopStaffRepository shopStaffRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final ShopAccessService shopAccessService;
    /**
     * Self-reference (lazy {@link ObjectProvider} to avoid a construction cycle) used to
     * invoke {@link #persistNewGrant} through the bean proxy so its
     * {@code REQUIRES_NEW} propagation actually takes effect.
     */
    private final ObjectProvider<StaffManagementService> selfProvider;

    public StaffManagementService(ShopStaffRepository shopStaffRepository,
                                  UserDirectoryRepository userDirectoryRepository,
                                  ShopAccessService shopAccessService,
                                  ObjectProvider<StaffManagementService> selfProvider) {
        this.shopStaffRepository = shopStaffRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.shopAccessService = shopAccessService;
        this.selfProvider = selfProvider;
    }

    /** The GET /api/v1/staff body: the grant-target picker + the current grants. */
    public record StaffListResponse(List<DirectoryEntryDto> directory, List<StaffMemberDto> grants) {
    }

    /**
     * Result of a grant: the canonical {@link StaffMemberDto} plus whether it was a
     * fresh insert ({@code true} → controller emits 201) or an idempotent replay /
     * in-place role change of an existing grant ({@code false} → 200). Carrying the
     * flag keeps HTTP-status selection out of the service while letting a duplicate
     * grant be a typed replay.
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
     * Grant {@code (userId, shopId|null, role)}. GROUP_ADMIN only.
     *
     * <p>The write goes through the Hibernate session (not a native
     * {@code ON CONFLICT DO NOTHING} insert) so Envers observes it (WR-02):
     * <ul>
     *   <li>absent → a new grant is inserted (Envers ADD revision), race-safely — a
     *       concurrent duplicate is caught and replayed as a typed 200; {@code created=true};</li>
     *   <li>present with the SAME role → an idempotent no-change replay ({@code created=false},
     *       no write, no revision);</li>
     *   <li>present with a DIFFERENT role → the role is UPDATED and audited
     *       ({@code created=false}). This is the CR-05 fix: a downgrade genuinely takes
     *       effect instead of silently no-opping while the API reports success.</li>
     * </ul>
     *
     * <p>Guards run first, unchanged in order: a shop-scoped GROUP_ADMIN grant is
     * rejected (400), and the D-11 downgrade guard blocks a change that would strip the
     * sole tenant-wide GROUP_ADMIN (409). The D-11 guard is now LOAD-BEARING — it
     * previously fronted a write that could not change a role anyway; now that downgrades
     * apply, it is the only thing preventing the last GROUP_ADMIN being downgraded.
     *
     * @return {@link GrantResult} with {@code created=true} for a fresh insert,
     *         {@code false} for an idempotent replay or an in-place role change.
     */
    public GrantResult grant(UUID userId, UUID shopId, ShopRole role) {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();

        // GROUP_ADMIN is inherently tenant-wide (a NULL shop). A shop-scoped GROUP_ADMIN
        // row would not confer tenant-wide admin (resolveMembership only treats a
        // NULL-shop GROUP_ADMIN row as such) AND would corrupt the
        // countByTenantIdAndRole(GROUP_ADMIN) last-admin guard. Reject it. (Rule 2)
        if (role == ShopRole.GROUP_ADMIN && shopId != null) {
            throw new IllegalArgumentException(
                    "GROUP_ADMIN is a tenant-wide role; shopId must be null for a GROUP_ADMIN grant");
        }

        ShopStaff existing = findCanonicalGrant(tenantId, userId, shopId);

        if (existing == null) {
            return insertNewGrantRaceSafe(tenantId, userId, shopId, role);
        }

        if (existing.getRole() == role) {
            // No-change replay: do NOT write, do NOT bump an Envers revision.
            log.debug("Idempotent grant replay for user {} (shop {}) in tenant {}", userId, shopId, tenantId);
            return new GrantResult(StaffMemberDto.from(existing), false);
        }

        // A genuine role change. The D-11 downgrade guard MUST fire before the update
        // now that a downgrade actually applies (CR-05). Serialize the check-then-act
        // with a row lock over the tenant's GROUP_ADMIN rows (CR-06) so a concurrent
        // downgrade cannot race the sole GROUP_ADMIN to a lesser role.
        if (shopId == null && role != ShopRole.GROUP_ADMIN) {
            shopStaffRepository.lockTenantGroupAdmins(tenantId);
            if (wouldDowngradeLastGroupAdmin(tenantId, userId)) {
                throw new LastGroupAdminException(MSG_DOWNGRADE_LAST_GROUP_ADMIN);
            }
        }

        existing.setRole(role);
        shopStaffRepository.saveAndFlush(existing); // session write → Envers MOD revision (WR-02)
        evictAfterCommit(userId);
        log.info("Changed role to {} for user {} (shop {}) in tenant {}", role, userId, shopId, tenantId);
        return new GrantResult(StaffMemberDto.from(existing), false);
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

        if (row.getRole() == ShopRole.GROUP_ADMIN) {
            // Serialize the check-then-act (CR-06): lock the tenant's GROUP_ADMIN rows
            // BEFORE counting so a concurrent revoke blocks, then re-reads the true
            // post-commit count and 409s rather than racing the tenant to zero admins.
            shopStaffRepository.lockTenantGroupAdmins(tenantId);
            if (shopStaffRepository.countByTenantIdAndRole(tenantId, ShopRole.GROUP_ADMIN) <= 1) {
                throw new LastGroupAdminException(MSG_REVOKE_LAST_GROUP_ADMIN);
            }
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

    /** The tenant's canonical grant for {@code (userId, shopId)}, or {@code null} if none. */
    private ShopStaff findCanonicalGrant(UUID tenantId, UUID userId, UUID shopId) {
        return shopStaffRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .filter(r -> Objects.equals(r.getShopId(), shopId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Insert a brand-new grant through the session (so Envers records the ADD revision —
     * WR-02) inside a {@code REQUIRES_NEW} transaction. A concurrent duplicate insert
     * surfacing as a unique-index {@link DataIntegrityViolationException} then fails ONLY
     * the inner transaction (leaving the caller's transaction un-poisoned — Postgres
     * aborts the whole transaction on any statement error) and is replayed as a typed
     * idempotent 200 rather than an untyped 500 (agent-readiness contract, T-23-09-04).
     */
    private GrantResult insertNewGrantRaceSafe(UUID tenantId, UUID userId, UUID shopId, ShopRole role) {
        try {
            ShopStaff created = selfProvider.getObject()
                    .persistNewGrant(tenantId, userId, shopId, role, currentCallerSub());
            evictAfterCommit(userId);
            log.info("Granted {} to user {} (shop {}) in tenant {}", role, userId, shopId, tenantId);
            return new GrantResult(StaffMemberDto.from(created), true);
        } catch (DataIntegrityViolationException raced) {
            ShopStaff canonical = findCanonicalGrant(tenantId, userId, shopId);
            if (canonical == null) {
                throw new IllegalStateException(
                        "Grant row not found after a concurrent insert for user " + userId + " shop " + shopId,
                        raced);
            }
            log.debug("Concurrent duplicate grant replayed for user {} (shop {}) in tenant {}",
                    userId, shopId, tenantId);
            return new GrantResult(StaffMemberDto.from(canonical), false);
        }
    }

    /**
     * The session insert, isolated in its OWN transaction ({@code REQUIRES_NEW}) so a
     * unique-index violation on a concurrent duplicate does not poison the caller's
     * transaction. MUST be invoked through the bean proxy ({@code selfProvider}) for the
     * propagation to take effect. Envers observes this session write → ADD revision.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShopStaff persistNewGrant(UUID tenantId, UUID userId, UUID shopId, ShopRole role, UUID createdBy) {
        ShopStaff row = new ShopStaff();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setShopId(shopId);
        row.setRole(role);
        row.setCreatedBy(createdBy);
        return shopStaffRepository.saveAndFlush(row);
    }

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
