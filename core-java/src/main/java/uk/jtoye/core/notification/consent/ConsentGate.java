package uk.jtoye.core.notification.consent;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RED skeleton (TDD) — replaced in the GREEN commit. The may-we-send gate.
 */
@Component
public class ConsentGate {

    public ConsentGate(SuppressionService suppressionService) {
        // wired in GREEN
    }

    public boolean allows(UUID tenantId, String recipient, NotificationCategory category) {
        throw new UnsupportedOperationException("not implemented");
    }
}
