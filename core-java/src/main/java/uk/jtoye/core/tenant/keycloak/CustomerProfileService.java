package uk.jtoye.core.tenant.keycloak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;
import uk.jtoye.core.exception.CustomerProfileUnavailableException;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.util.Optional;

/**
 * A signed-in customer changes their display name (SW-JRD-07 / SW-SAM-11, owner ruling 2026-11-04,
 * persona close-out).
 *
 * <p>There is no profile table and none is added: a customer's name lives only in the customer realm
 * (Keycloak, {@code jtoye-customers}). So the change goes through the admin seam this package already
 * uses for erasure ({@link CustomerAccountDeletionService}), inert unless configured.
 *
 * <h2>Whose account</h2>
 * The caller passes the VERIFIED token's subject and email ({@code CustomerJwtVerifier.verifiedIdentity});
 * nothing in the request body names an account. The subject is the Keycloak user id, so it is read by id
 * (no search, no substring match), and the stored email must still equal the token's, ignoring case, or
 * the answer is a 404 and nothing is written: the same wrong-account guard the erasure path has. Only the
 * customer realm is touched, never the vendor realm list.
 *
 * <h2>What is written</h2>
 * The whole representation is read and written back with only {@code firstName}/{@code lastName}
 * replaced, because Keycloak requires the full rep on update. The display name is split at its first
 * space: the first word is the first name, the rest the last name (empty for a single word).
 *
 * <h2>Log hygiene</h2>
 * The name and the address are personal data: neither is logged, nor placed in an exception message.
 */
@Service
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    static final int MAX_NAME_LENGTH = 100;

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;

    public CustomerProfileService(KeycloakAdminClient keycloakAdminClient, KeycloakAdminProperties properties) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
    }

    /**
     * @param subject       the verified token's subject (the Keycloak user id)
     * @param verifiedEmail the verified token's email
     * @param displayName   the requested name, untrusted
     * @return the name as saved (trimmed, whitespace collapsed)
     * @throws IllegalArgumentException              a blank, over-long or control-character name (400)
     * @throws ResourceNotFoundException             no such account, or one whose email is not the token's (404)
     * @throws CustomerProfileUnavailableException   the admin seam is off, or Keycloak refused/failed (503)
     */
    public String updateDisplayName(String subject, String verifiedEmail, String displayName) {
        String name = normalise(displayName);
        if (subject == null || subject.isBlank() || verifiedEmail == null || verifiedEmail.isBlank()) {
            throw new IllegalArgumentException("A verified customer is required");
        }
        if (!properties.configured()) {
            log.warn("event=customer_profile_update_skipped reason=not_configured");
            throw new CustomerProfileUnavailableException(null);
        }
        String realm = properties.getCustomerRealm();
        try {
            String token = keycloakAdminClient.obtainAdminToken();
            Optional<ObjectNode> found = keycloakAdminClient.getUser(realm, subject, token);
            ObjectNode rep = found.orElseThrow(() -> new ResourceNotFoundException("Account not found"));
            String stored = rep.path("email").asString("");
            if (!stored.trim().equalsIgnoreCase(verifiedEmail.trim())) {
                throw new ResourceNotFoundException("Account not found");
            }
            int space = name.indexOf(' ');
            ObjectNode updated = rep.deepCopy();
            updated.put("firstName", space < 0 ? name : name.substring(0, space));
            updated.put("lastName", space < 0 ? "" : name.substring(space + 1));
            keycloakAdminClient.updateUser(realm, updated, token);
            log.info("event=customer_profile_update realm={} result=UPDATED", realm);
            return name;
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // Class only: an exception message could carry the address or the name.
            log.error("event=customer_profile_update_failed realm={} error={}", realm, e.getClass().getName());
            throw new CustomerProfileUnavailableException(e);
        }
    }

    /** Trim, collapse runs of whitespace, 1..100 characters, no control characters. */
    static String normalise(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("A name is required");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new IllegalArgumentException("The name can't contain control characters");
            }
        }
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The name can be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }
}
