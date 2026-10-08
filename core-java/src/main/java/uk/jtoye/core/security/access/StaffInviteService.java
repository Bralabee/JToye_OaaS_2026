package uk.jtoye.core.security.access;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.dto.CreateStaffInviteRequest;
import uk.jtoye.core.security.access.dto.StaffInviteDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Issues staff invitations (D-07, Phase 37-07): a Group admin invites a person by email with the exact
 * grant they will receive. Accepting one is 37-08.
 *
 * <p><b>Who may.</b> Every method opens with {@link ShopAccessService#requireGroupAdmin()}: anyone else
 * gets the typed 403 {@code shop-access-denied} (T-37-15). The issuer must also be an identifiable
 * person (a UUID {@code sub}), because the invitation records who issued it; a declared machine client
 * is refused with the same 403 (an agent must not mint human staff, RESEARCH 37-B.3).
 *
 * <p><b>The token (T-37-14).</b> 32 bytes from {@link SecureRandom}, unpadded base64url, copied from
 * {@code DsarAccessExportService.storeAndIssueToken}. Only its SHA-256 is stored. The readable token
 * exists in this method's frame and in the link handed to
 * {@link EmailNotificationService#sendStaffInvite} AFTER the transaction commits, so a rolled-back issue
 * never emails a link to a row that does not exist. It is never returned, never logged.
 *
 * <p><b>Idempotent per (email, shop, role).</b> While an invitation for the same address, shop and role
 * is open (not accepted, not revoked, not expired), issuing it again returns that invitation with
 * {@code created=false} and sends nothing. When the only live row for the triple has expired, a new one
 * is issued and the expired one is revoked, so a triple never has two live rows.
 *
 * <p><b>Logging (T-37-17).</b> Log lines carry ids and the role only — never the address, the token or
 * the digest. Exception messages never quote the address either.
 */
@Service
@Transactional
public class StaffInviteService {

    private static final Logger log = LoggerFactory.getLogger(StaffInviteService.class);

    static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StaffInviteRepository staffInviteRepository;
    private final ShopRepository shopRepository;
    private final TenantRepository tenantRepository;
    private final ShopAccessService shopAccessService;
    private final EmailNotificationService emailNotificationService;
    private final Clock clock;

    @Value("${jtoye.staff.invite.ttl-hours:72}")
    private long ttlHours;

    /** The FRONTEND's public accept page, never core's own origin (31.1 PGC-839). */
    @Value("${jtoye.staff.invite.accept-base-url:http://localhost:3000/invite}")
    private String acceptBaseUrl;

    public StaffInviteService(StaffInviteRepository staffInviteRepository,
                              ShopRepository shopRepository,
                              TenantRepository tenantRepository,
                              ShopAccessService shopAccessService,
                              EmailNotificationService emailNotificationService,
                              Clock clock) {
        this.staffInviteRepository = staffInviteRepository;
        this.shopRepository = shopRepository;
        this.tenantRepository = tenantRepository;
        this.shopAccessService = shopAccessService;
        this.emailNotificationService = emailNotificationService;
        this.clock = clock;
    }

    /** The invitation, and whether this call created it (201) or replayed an open one (200). */
    public record IssueResult(StaffInviteDto invite, boolean created) {
    }

    /**
     * Invite {@code request.email()} to {@code request.role()} on {@code request.shopId()} (null = all
     * shops). Group admin only.
     *
     * @throws IllegalArgumentException  400: a shop-scoped GROUP_ADMIN, or an address that is not one
     *                                   valid email address
     * @throws ResourceNotFoundException 404: the shop is not a shop of this business (the same body for
     *                                   another tenant's shop and for one that does not exist)
     */
    public IssueResult issue(CreateStaffInviteRequest request) {
        shopAccessService.requireGroupAdmin();
        UUID issuer = requireIssuer();
        UUID tenantId = currentTenantId();
        String email = normaliseEmail(request.email());
        ShopRole role = request.role();
        UUID shopId = request.shopId();
        Shop shop = validateGrant(tenantId, shopId, role);
        OffsetDateTime now = now();

        for (StaffInvite live : staffInviteRepository
                .findByTenantIdAndEmailNormalisedAndAcceptedAtIsNullAndRevokedAtIsNull(tenantId, email)) {
            if (live.offers(email, shopId, role) && live.statusAt(now) == StaffInvite.Status.OPEN) {
                log.debug("event=staff_invite_replayed invite={} tenant={}", live.getId(), tenantId);
                return new IssueResult(StaffInviteDto.from(live, now), false);
            }
        }
        StaffInvite created = issueFresh(tenantId, email, shop, role, issuer, now);
        return new IssueResult(StaffInviteDto.from(created, now), true);
    }

    /**
     * Every invitation of the business, newest first, each with its status computed now. Group admin
     * only. Cancelled, re-sent and accepted invitations stay listed: the row is the record of who
     * invited whom, and the client decides what to show.
     */
    @Transactional(readOnly = true)
    public List<StaffInviteDto> list() {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();
        OffsetDateTime now = now();
        return staffInviteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(invite -> StaffInviteDto.from(invite, now))
                .toList();
    }

    /**
     * Cancel an invitation: its link stops working. Group admin only. Repeatable: cancelling a
     * cancelled invitation changes nothing (the first who and when are kept), and an accepted one is
     * final, so cancelling it changes nothing either.
     *
     * @throws ResourceNotFoundException 404 when no invitation of this business has that id
     */
    public void cancel(UUID inviteId) {
        shopAccessService.requireGroupAdmin();
        UUID caller = requireIssuer();
        StaffInvite invite = findInTenant(inviteId);
        if (invite.getAcceptedAt() != null || invite.getRevokedAt() != null) {
            return;
        }
        invite.revoke(caller, now());
        log.info("event=staff_invite_cancelled invite={} tenant={}", invite.getId(), invite.getTenantId());
    }

    /**
     * Send an invitation again: the old row is revoked (its link dies) and a new invitation for the same
     * address, shop and role is issued and emailed after commit, in one transaction. Group admin only.
     * Deliberately not idempotent: every call kills the previous link.
     *
     * @throws ResourceNotFoundException 404 when no invitation of this business has that id, or its shop
     *                                   is no longer a shop of this business
     * @throws IllegalArgumentException  400 when the invitation was already accepted
     */
    public StaffInviteDto resend(UUID inviteId) {
        shopAccessService.requireGroupAdmin();
        UUID issuer = requireIssuer();
        StaffInvite old = findInTenant(inviteId);
        if (old.getAcceptedAt() != null) {
            throw new IllegalArgumentException("This invitation has already been accepted, so it cannot be sent again");
        }
        Shop shop = validateGrant(old.getTenantId(), old.getShopId(), old.getRole());
        OffsetDateTime now = now();
        old.revoke(issuer, now);
        StaffInvite fresh = issueFresh(old.getTenantId(), old.getEmailNormalised(), shop, old.getRole(), issuer, now);
        log.info("event=staff_invite_resent old={} new={} tenant={}", old.getId(), fresh.getId(), old.getTenantId());
        return StaffInviteDto.from(fresh, now);
    }

    // ---- internals -------------------------------------------------------------------------------

    /** The invitation by id, within the caller's business only; the same 404 for a foreign or unknown id. */
    private StaffInvite findInTenant(UUID inviteId) {
        return staffInviteRepository.findByIdAndTenantId(inviteId, currentTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
    }

    /**
     * Revoke every live row for the triple, store a new digest, and email the link after commit. The
     * only place a readable token is created.
     */
    private StaffInvite issueFresh(UUID tenantId, String email, Shop shop, ShopRole role, UUID issuer,
                                   OffsetDateTime now) {
        UUID shopId = shop == null ? null : shop.getId();
        for (StaffInvite live : staffInviteRepository
                .findByTenantIdAndEmailNormalisedAndAcceptedAtIsNullAndRevokedAtIsNull(tenantId, email)) {
            if (live.offers(email, shopId, role)) {
                live.revoke(issuer, now);
            }
        }
        byte[] raw = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expiresAt = now.plusHours(ttlHours);
        StaffInvite invite = staffInviteRepository.saveAndFlush(new StaffInvite(UUID.randomUUID(), tenantId,
                email, shopId, role, sha256Hex(token), expiresAt, issuer, now));

        // 37-08: the token rides in the URL FRAGMENT, which a browser never sends to a server (the V75
        // DSAR-link rule): no request line, access log, APM span or Referer ever carries it. The accept
        // page reads it client-side and POSTs it in a body.
        String link = stripTrailingSlash(acceptBaseUrl) + "#token=" + tenantId + "." + token;
        String inviterName = currentCallerName();
        String businessName = tenantRepository.findById(tenantId).map(Tenant::getName).orElse(null);
        String roleLabel = roleLabel(role);
        String shopLabel = shop == null ? "all shops" : shop.getName();
        afterCommit(() -> emailNotificationService.sendStaffInvite(email, inviterName, businessName, roleLabel,
                shopLabel, link, expiresAt));
        log.info("event=staff_invite_issued invite={} tenant={} role={} allShops={}",
                invite.getId(), tenantId, role, shopId == null);
        return invite;
    }

    /** The grant rules {@code StaffManagementService.grant()} applies, in the same order and shape. */
    private Shop validateGrant(UUID tenantId, UUID shopId, ShopRole role) {
        if (role == ShopRole.GROUP_ADMIN && shopId != null) {
            throw new IllegalArgumentException(
                    "GROUP_ADMIN is a tenant-wide role; shopId must be null for a GROUP_ADMIN invitation");
        }
        if (shopId == null) {
            return null;
        }
        // A foreign key check bypasses RLS, so the tenant-scoped finder is the real check. Another
        // tenant's shop and a shop that does not exist get the same body (no id echoed): not an
        // existence oracle (T-23-12-03).
        return shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found in this business"));
    }

    /** Trim + lower-case, then exactly one bare, strictly-parsed address. The message never quotes it. */
    static String normaliseEmail(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        try {
            InternetAddress parsed = new InternetAddress(email, true);
            parsed.validate();
            if (!email.equals(parsed.getAddress()) || email.length() > 320 || email.indexOf('@') < 1) {
                throw new AddressException("not a bare address");
            }
        } catch (AddressException e) {
            throw new IllegalArgumentException("email must be one valid email address");
        }
        return email;
    }

    static String roleLabel(ShopRole role) {
        return switch (role) {
            case STAFF -> "Staff";
            case SHOP_MANAGER -> "Shop manager";
            case GROUP_ADMIN -> "Group admin";
        };
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stripTrailingSlash(String base) {
        String b = base == null ? "" : base.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /** Microsecond precision: what PostgreSQL stores, so a value read back compares equal. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Run {@code action} once the current transaction commits; nothing is sent for a rolled-back issue.
     * Without an active synchronization (never the case behind this {@code @Transactional} service) it
     * would be unsafe to send, so the action is dropped with a WARN rather than run early.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("event=staff_invite_email_dropped reason=no_transaction_synchronization");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** The issuer recorded in {@code created_by}: a UUID-subject person, or the typed 403. */
    private UUID requireIssuer() {
        return shopAccessService.currentVendorUserId()
                .orElseThrow(() -> new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN));
    }

    /** The inviter's display name from the token ({@code name}, else {@code preferred_username}). */
    private static String currentCallerName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            return jwt.getClaimAsString("preferred_username");
        }
        return null;
    }

    private static UUID currentTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }
}
