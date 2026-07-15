package uk.jtoye.core.notification.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RED skeleton (TDD) — replaced in the GREEN commit. Stateless HMAC unsubscribe
 * token service.
 */
@Service
public class UnsubscribeTokenService {

    @SuppressWarnings("unused")
    private final String signingSecret;

    public UnsubscribeTokenService(@Value("${notification.unsubscribe.signing-secret:}") String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public String tokenFor(UUID tenantId, String email, NotificationCategory category) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean verify(UUID tenantId, String email, NotificationCategory category, String token) {
        throw new UnsupportedOperationException("not implemented");
    }
}
