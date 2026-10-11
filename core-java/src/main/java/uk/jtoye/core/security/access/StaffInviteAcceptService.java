package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.exception.StaffInviteAccountServiceUnavailableException;
import uk.jtoye.core.exception.StaffInviteEmailTakenException;
import uk.jtoye.core.exception.StaffInvitePasswordRejectedException;
import uk.jtoye.core.exception.StaffInviteUnavailableException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.dto.AcceptStaffInviteRequest;
import uk.jtoye.core.security.access.dto.StaffInviteAcceptedDto;
import uk.jtoye.core.security.access.dto.StaffInvitePreviewDto;
import uk.jtoye.core.security.access.dto.StaffInvitePreviewDto.AccountState;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminException;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminProperties;
import uk.jtoye.core.tenant.keycloak.KeycloakUserExistsException;
import uk.jtoye.core.tenant.keycloak.KeycloakUserRejectedException;
import uk.jtoye.core.tenant.keycloak.VendorRealmUser;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turns a staff-invitation link into an account that holds EXACTLY the invited grant (37-08; D-07, D-26;
 * RWO-004, RWO-003).
 *
 * <h2>Anonymous, one pinned tenant</h2>
 *
 * The caller holds nothing but the link's reference {@code {tenantId}.{token}}, sent in a JSON body (the
 * link carries it in the URL fragment, which a browser never sends to a server). Every operation pins the
 * tenant NAMED IN THE LINK ({@link TenantContext} plus the transaction-local GUC, in its own
 * {@link TransactionTemplate}, cleared in a {@code finally}) and reads the invitation by the SHA-256 of the
 * token UNDER FORCE RLS with an explicit tenant predicate as well. A token presented with any other tenant
 * id finds nothing. There is no RLS exemption and no cross-tenant read.
 *
 * <p><b>No system authority is declared.</b> This is a REQUEST thread, and the house rule
 * ({@code SystemPrincipal}, {@code ShopAccessService}) is that only background entry points declare. The
 * accept path needs no shop-scope gate: it writes the directory row and the grant through repositories
 * inside the pinned tenant, so FORCE RLS is the wall, exactly as for every other caller.
 *
 * <h2>Single use, under concurrency</h2>
 *
 * The claim is a conditional {@code UPDATE ... WHERE accepted_at IS NULL AND revoked_at IS NULL AND
 * expires_at > now()} that must touch exactly one row. It takes the row lock, so a second accept of the
 * same link blocks on it until the first commits, then matches nothing and is refused. Keycloak is called
 * AFTER the claim and inside the same transaction: if Keycloak refuses the password, the exception rolls
 * the claim back and the invitation stays open for another try.
 *
 * <p>A Keycloak user is not transactional. If it is created and the database then fails to commit, the
 * invitation stays open and the next accept finds the address as an account of this business
 * (EXISTS_HERE) and grants it: the flow heals itself rather than stranding the person.
 *
 * <h2>The grant is the invitation's, never the request's</h2>
 *
 * Role, shop and grantor ({@code created_by}) come from the {@code staff_invite} row. The request carries
 * only the reference, names and a password; nothing in it can widen what is written.
 *
 * <h2>The password (T-37-22)</h2>
 *
 * Read into a {@code char[]}, handed to Keycloak, zeroed in a {@code finally}. Never logged, stored or
 * echoed by core-java; a Keycloak refusal carries Keycloak's message only.
 */
@Service
public class StaffInviteAcceptService {

    private static final Logger log = LoggerFactory.getLogger(StaffInviteAcceptService.class);

    /** {tenantId}.{token}: a UUID, a dot, and 32 random bytes as unpadded base64url (43 characters). */
    private static final Pattern REF = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.([A-Za-z0-9_-]{43})");
    /** Keycloak's default person-name validator refuses these; refusing them here gives a clear 400. */
    private static final Pattern PROHIBITED_NAME = Pattern.compile("[<>&\"$%!#?§;*~/\\\\|^=\\[\\]{}()\\p{Cntrl}]");

    static final int MAX_NAME = 255;
    static final int MAX_PASSWORD = 256;

    private static final String CLAIM_SQL = """
            UPDATE staff_invite
               SET version = version + 1
             WHERE id = ? AND tenant_id = ?
               AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
            """;
    private static final String MARK_ACCEPTED_SQL = """
            UPDATE staff_invite
               SET accepted_at = now(), accepted_user_id = ?, version = version + 1
             WHERE id = ? AND tenant_id = ?
               AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
            """;
    private static final String DIRECTORY_UPSERT_SQL = """
            INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (tenant_id, user_id) DO UPDATE
               SET email = EXCLUDED.email,
                   display_name = COALESCE(EXCLUDED.display_name, user_directory.display_name)
            """;

    private final StaffInviteRepository staffInviteRepository;
    private final ShopRepository shopRepository;
    private final TenantRepository tenantRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final ShopStaffRepository shopStaffRepository;
    private final ShopAccessService shopAccessService;
    private final KeycloakAdminClient keycloak;
    private final KeycloakAdminProperties keycloakProperties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public StaffInviteAcceptService(StaffInviteRepository staffInviteRepository,
                                    ShopRepository shopRepository,
                                    TenantRepository tenantRepository,
                                    UserDirectoryRepository userDirectoryRepository,
                                    ShopStaffRepository shopStaffRepository,
                                    ShopAccessService shopAccessService,
                                    KeycloakAdminClient keycloak,
                                    KeycloakAdminProperties keycloakProperties,
                                    JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.staffInviteRepository = staffInviteRepository;
        this.shopRepository = shopRepository;
        this.tenantRepository = tenantRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.shopStaffRepository = shopStaffRepository;
        this.shopAccessService = shopAccessService;
        this.keycloak = keycloak;
        this.keycloakProperties = keycloakProperties;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * What the link offers: business, role, shop (null = all shops), inviter, the invited address, and
     * which account path accepting will take. Every unusable link is the same refusal.
     */
    public StaffInvitePreviewDto preview(String ref) {
        InviteRef link = parse(ref);
        return inTenant(link.tenantId(), () -> {
            Offer offer = openOffer(link);
            String token = keycloakToken();
            Account account = classify(lookUp(offer.email(), token), offer.email(), link.tenantId());
            return new StaffInvitePreviewDto(offer.businessName(), offer.invite().getRole(), offer.shopName(),
                    inviterName(offer.invite()), offer.email(), account.state());
        });
    }

    /**
     * Accept the invitation: NEW creates the Keycloak user (with the admin-only {@code tenant_id}), then
     * the directory row and the invited OPERATOR grant are written and the invitation is marked accepted,
     * all in one transaction in the pinned tenant. EXISTS_HERE grants the existing user of this business
     * with no password. OTHER_BUSINESS is refused and nothing is written.
     */
    public StaffInviteAcceptedDto accept(AcceptStaffInviteRequest request) {
        char[] password = request == null ? null : request.password();
        try {
            InviteRef link = parse(request == null ? null : request.ref());
            return inTenant(link.tenantId(), () -> acceptInTenant(link, request));
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    // ---- the accept, inside the pinned tenant's transaction -------------------------------------

    private StaffInviteAcceptedDto acceptInTenant(InviteRef link, AcceptStaffInviteRequest request) {
        Offer offer = openOffer(link);
        StaffInvite invite = offer.invite();
        UUID tenantId = link.tenantId();
        String token = keycloakToken();
        Account account = classify(lookUp(offer.email(), token), offer.email(), tenantId);
        if (account.state() == AccountState.OTHER_BUSINESS) {
            throw emailInOtherBusiness();
        }
        NewAccount details = account.state() == AccountState.NEW ? validateNewAccount(request) : null;

        // The claim: the row lock that makes the link single-use under concurrency.
        if (jdbc.update(CLAIM_SQL, invite.getId(), tenantId) != 1) {
            throw unavailable();
        }

        String userId = account.userId();
        if (details != null) {
            userId = createAccount(offer.email(), details, request.password(), tenantId, token);
        }
        UUID user = UUID.fromString(userId);

        jdbc.update(DIRECTORY_UPSERT_SQL, tenantId, user, offer.email(),
                details == null ? null : details.displayName());
        writeGrant(tenantId, user, invite);
        if (jdbc.update(MARK_ACCEPTED_SQL, user, invite.getId(), tenantId) != 1) {
            throw unavailable();
        }
        shopAccessService.evictMembershipAfterCommit(user);
        log.info("event=staff_invite_accepted invite={} tenant={} user={} role={} allShops={} accountState={}",
                invite.getId(), tenantId, user, invite.getRole(), invite.getShopId() == null, account.state());
        return new StaffInviteAcceptedDto(offer.businessName(), invite.getRole(), offer.shopName());
    }

    /** Create the vendor-realm user; a concurrent create of the same address resolves to that account. */
    private String createAccount(String email, NewAccount details, char[] password, UUID tenantId, String token) {
        try {
            return keycloak.createUser(keycloakProperties.getVendorRealm(), email, details.firstName(),
                    details.lastName(), password, tenantId, token);
        } catch (KeycloakUserExistsException exists) {
            Account again = classify(lookUp(email, token), email, tenantId);
            if (again.state() == AccountState.EXISTS_HERE) {
                return again.userId();
            }
            throw emailInOtherBusiness();
        } catch (KeycloakUserRejectedException rejected) {
            throw passwordRejected(rejected.getKeycloakMessage());
        } catch (KeycloakAdminException down) {
            throw new StaffInviteAccountServiceUnavailableException(down);
        }
    }

    /**
     * Write exactly the invited grant as an OPERATOR grant by the inviter. An existing row for the same
     * (user, shop) takes the invited role (a de-honoured JIT row becomes the real grant), except an
     * OPERATOR tenant-wide GROUP_ADMIN, which a link never downgrades.
     */
    private void writeGrant(UUID tenantId, UUID user, StaffInvite invite) {
        ShopStaff existing = shopStaffRepository.findByTenantIdAndUserId(tenantId, user).stream()
                .filter(row -> Objects.equals(row.getShopId(), invite.getShopId()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            ShopStaff row = new ShopStaff();
            row.setTenantId(tenantId);
            row.setUserId(user);
            row.setShopId(invite.getShopId());
            row.setRole(invite.getRole());
            row.setGrantSource(GrantSource.OPERATOR);
            row.setCreatedBy(invite.getCreatedBy());
            shopStaffRepository.saveAndFlush(row);
            return;
        }
        if (existing.getGrantSource() == GrantSource.OPERATOR && existing.getRole() == ShopRole.GROUP_ADMIN) {
            return;
        }
        existing.setRole(invite.getRole());
        existing.setGrantSource(GrantSource.OPERATOR);
        existing.setCreatedBy(invite.getCreatedBy());
        shopStaffRepository.saveAndFlush(existing);
    }

    // ---- reading the invitation ------------------------------------------------------------------

    /** The open invitation the link names, its business and its shop, or the one refusal. */
    private Offer openOffer(InviteRef link) {
        StaffInvite invite = staffInviteRepository
                .findByTenantIdAndTokenSha256(link.tenantId(), StaffInviteService.sha256Hex(link.token()))
                .orElseThrow(this::unavailable);
        if (invite.statusAt(OffsetDateTime.now(clock)) != StaffInvite.Status.OPEN) {
            throw unavailable();
        }
        Tenant tenant = tenantRepository.findById(link.tenantId())
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .orElseThrow(this::unavailable);
        String shopName = null;
        if (invite.getShopId() != null) {
            Shop shop = shopRepository.findByIdAndTenantId(invite.getShopId(), link.tenantId())
                    .orElseThrow(this::unavailable);
            shopName = shop.getName();
        }
        return new Offer(invite, tenant.getName(), shopName, invite.getEmailNormalised());
    }

    private String inviterName(StaffInvite invite) {
        return userDirectoryRepository.findById(new UserDirectoryId(invite.getTenantId(), invite.getCreatedBy()))
                .map(UserDirectory::getDisplayName)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }

    // ---- Keycloak --------------------------------------------------------------------------------

    private String keycloakToken() {
        if (!keycloakProperties.configured()) {
            throw new StaffInviteAccountServiceUnavailableException(null);
        }
        try {
            return keycloak.obtainAdminToken();
        } catch (KeycloakAdminException down) {
            throw new StaffInviteAccountServiceUnavailableException(down);
        }
    }

    private List<VendorRealmUser> lookUp(String email, String token) {
        try {
            return keycloak.findVendorUsersByEmail(keycloakProperties.getVendorRealm(), email, token);
        } catch (KeycloakAdminException down) {
            throw new StaffInviteAccountServiceUnavailableException(down);
        }
    }

    /**
     * NEW when no vendor-realm account has the address; EXISTS_HERE when the account's {@code tenant_id}
     * is this business; OTHER_BUSINESS otherwise, including an account with no tenant at all (one user,
     * one tenant: an account is never re-homed by a link).
     */
    static Account classify(List<VendorRealmUser> users, String email, UUID tenantId) {
        List<VendorRealmUser> matches = users.stream()
                .filter(u -> u.email() != null && u.email().equalsIgnoreCase(email) && u.id() != null)
                .toList();
        if (matches.isEmpty()) {
            return new Account(AccountState.NEW, null);
        }
        if (matches.size() == 1 && tenantId.toString().equalsIgnoreCase(matches.get(0).tenantId())) {
            return new Account(AccountState.EXISTS_HERE, matches.get(0).id());
        }
        return new Account(AccountState.OTHER_BUSINESS, null);
    }

    // ---- validation --------------------------------------------------------------------------------

    /** Names and password for a NEW account; every message is constant and never quotes the input. */
    private static NewAccount validateNewAccount(AcceptStaffInviteRequest request) {
        String first = request.firstName() == null ? "" : request.firstName().strip();
        String last = request.lastName() == null ? "" : request.lastName().strip();
        char[] password = request.password();
        if (first.isEmpty() || last.isEmpty()) {
            throw new IllegalArgumentException("Enter your first name and last name");
        }
        if (first.length() > MAX_NAME || last.length() > MAX_NAME) {
            throw new IllegalArgumentException("First name and last name must be 255 characters or fewer");
        }
        if (PROHIBITED_NAME.matcher(first).find() || PROHIBITED_NAME.matcher(last).find()) {
            throw new IllegalArgumentException(
                    "First name and last name can't contain symbols such as < > & / or brackets");
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Choose a password");
        }
        if (password.length > MAX_PASSWORD) {
            throw new IllegalArgumentException("The password must be 256 characters or fewer");
        }
        String display = (first + " " + last).strip();
        return new NewAccount(first, last, display.length() > MAX_NAME ? display.substring(0, MAX_NAME) : display);
    }

    /** {tenantId}.{token}, or the one refusal. Nothing about a malformed reference is echoed. */
    static InviteRef parse(String ref) {
        if (ref == null || ref.length() > 80) {
            throw unavailableStatic();
        }
        var m = REF.matcher(ref);
        if (!m.matches()) {
            throw unavailableStatic();
        }
        return new InviteRef(UUID.fromString(m.group(1)), m.group(2));
    }

    // ---- the pinned tenant -------------------------------------------------------------------------

    /**
     * One tenant, one transaction: {@link TenantContext} set, the GUC pinned inside the transaction, and the
     * thread left exactly as it was found on every path (it is a pooled request thread).
     */
    private <T> T inTenant(UUID tenantId, Supplier<T> work) {
        Optional<UUID> previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return transactionTemplate.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class,
                        tenantId.toString());
                return work.get();
            });
        } finally {
            TenantContext.clear();
            previous.ifPresent(TenantContext::set);
        }
    }

    // ---- refusals -------------------------------------------------------------------------------

    private RuntimeException unavailable() {
        return unavailableStatic();
    }

    private static RuntimeException unavailableStatic() {
        return new StaffInviteUnavailableException();
    }

    private static RuntimeException emailInOtherBusiness() {
        return new StaffInviteEmailTakenException();
    }

    private static RuntimeException passwordRejected(String keycloakMessage) {
        return new StaffInvitePasswordRejectedException(keycloakMessage);
    }

    // ---- value types -------------------------------------------------------------------------------

    record InviteRef(UUID tenantId, String token) {
        @Override
        public String toString() {
            return "InviteRef[tenantId=" + tenantId + ", token=<redacted>]";
        }
    }

    record Account(AccountState state, String userId) {
    }

    private record Offer(StaffInvite invite, String businessName, String shopName, String email) {
    }

    private record NewAccount(String firstName, String lastName, String displayName) {
    }
}
