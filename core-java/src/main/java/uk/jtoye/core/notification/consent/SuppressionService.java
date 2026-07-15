package uk.jtoye.core.notification.consent;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RED skeleton (TDD) — replaced in the GREEN commit. Reads/writes consent state
 * (suppression + marketing opt-in).
 */
@Service
public class SuppressionService {

    public SuppressionService(NotificationSuppressionRepository suppressionRepository,
                              MarketingOptInRepository marketingOptInRepository) {
        // wired in GREEN
    }

    public boolean isSuppressed(UUID tenantId, String recipient, NotificationCategory category) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean hasMarketingOptIn(UUID tenantId, String recipient) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean suppress(UUID tenantId, String recipient, NotificationCategory category) {
        throw new UnsupportedOperationException("not implemented");
    }
}
