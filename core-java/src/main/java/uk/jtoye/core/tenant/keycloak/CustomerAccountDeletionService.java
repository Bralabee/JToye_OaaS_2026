package uk.jtoye.core.tenant.keycloak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deletes an erased data subject's CUSTOMER sign-in account (Phase 31.1, D-03, issue #777).
 *
 * <p>After an ERASURE request's data erasure has committed in every tenant, the DSAR worker calls
 * {@link #deleteCustomerAccount(String)} with the address the subject verified. Without this, the
 * person could still sign in with their customer-realm account and see their (now anonymised)
 * orders. The shape is the #102/V49 vendor deprovisioning one ({@link KeycloakDeprovisionService}):
 * after commit, best-effort, non-throwing, inert unless the admin seam is configured.
 *
 * <h2>The two guards against deleting the wrong account (T-31.1-36)</h2>
 *
 * <ol>
 *   <li><b>One realm, and only one.</b> Every call uses
 *       {@link KeycloakAdminProperties#getCustomerRealm()} ({@code jtoye.keycloak.admin.customer-realm},
 *       default {@code jtoye-customers}). The vendor realm list ({@code jtoye.keycloak.admin.realms})
 *       is never read here: it exists for tenant offboarding, and a vendor who shares the address
 *       with an erased customer must keep their account.</li>
 *   <li><b>Exact address, checked twice.</b> The search is {@code exact=true}, and each returned user
 *       is deleted only when its stored email equals the verified address ignoring case. A server
 *       that ignored {@code exact} (or a future change that dropped it) would hand back substring
 *       matches such as {@code grace@x.test.evil}; the second check refuses them here.</li>
 * </ol>
 *
 * <h2>Never throws, never logs the address</h2>
 *
 * Any failure becomes {@link AccountDeletionResult#FAILED}, logged at ERROR with the realm and the
 * exception CLASS only: the address is personal data (ASVS V7) and an exception message could carry
 * it. The worker decides what an outcome means for the request (retry, park, complete).
 */
@Service
public class CustomerAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAccountDeletionService.class);

    /** Outcome of one deletion attempt, recorded by the worker as {@code account_deletion_status}. */
    public enum AccountDeletionResult {
        /** Every customer-realm account whose email equals the address is gone (deleted, or a 404). */
        DELETED,
        /** The customer realm holds no account for the address. */
        NONE_FOUND,
        /** The admin seam is switched off in this runtime; nothing was attempted. */
        NOT_CONFIGURED,
        /** The attempt failed; the account may still exist. */
        FAILED
    }

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;

    /** Guards the not-configured WARN so every sweep does not repeat it. */
    private final AtomicBoolean warnedOnce = new AtomicBoolean(false);

    public CustomerAccountDeletionService(KeycloakAdminClient keycloakAdminClient,
                                          KeycloakAdminProperties properties) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
    }

    /**
     * Delete the customer-realm account(s) for a verified subject address.
     *
     * @param verifiedEmail the address the subject proved control of (decrypted from the DSAR row)
     * @return the outcome; never {@code null}, never thrown
     */
    public AccountDeletionResult deleteCustomerAccount(String verifiedEmail) {
        if (!properties.configured()) {
            if (warnedOnce.compareAndSet(false, true)) {
                log.warn("event=dsar_account_deletion_skipped reason=not_configured "
                        + "(set jtoye.keycloak.admin.enabled=true + base-url + password)");
            }
            return AccountDeletionResult.NOT_CONFIGURED;
        }
        String realm = properties.getCustomerRealm();
        if (verifiedEmail == null || verifiedEmail.isBlank()) {
            log.error("event=dsar_account_deletion_failed realm={} reason=no_address", realm);
            return AccountDeletionResult.FAILED;
        }
        // Keycloak stores emails lower-cased; the DSAR digest normalises the same way, so the identity
        // being erased is the address ignoring case.
        String address = verifiedEmail.trim().toLowerCase(Locale.ROOT);
        try {
            String token = keycloakAdminClient.obtainAdminToken();
            List<CustomerRealmUser> matches = keycloakAdminClient.findUsersByEmail(realm, address, token)
                    .stream()
                    .filter(u -> u.id() != null && u.email() != null && u.email().trim().equalsIgnoreCase(address))
                    .toList();
            if (matches.isEmpty()) {
                log.info("event=dsar_account_deletion realm={} result=NONE_FOUND", realm);
                return AccountDeletionResult.NONE_FOUND;
            }
            for (CustomerRealmUser user : matches) {
                keycloakAdminClient.deleteUser(realm, user.id(), token);
            }
            log.info("event=dsar_account_deletion realm={} result=DELETED accounts={}", realm, matches.size());
            return AccountDeletionResult.DELETED;
        } catch (Exception e) {
            log.error("event=dsar_account_deletion_failed realm={} error={}", realm, e.getClass().getName());
            return AccountDeletionResult.FAILED;
        }
    }
}
