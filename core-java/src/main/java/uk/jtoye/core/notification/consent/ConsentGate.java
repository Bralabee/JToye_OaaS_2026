package uk.jtoye.core.notification.consent;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The may-we-send gate (COMMS-03, D-03) — the single decision point 22-04's
 * dispatch consults before every send.
 *
 * <ul>
 *   <li>A per-category suppression row always refuses the send (an explicit
 *       one-click unsubscribe wins for every category).</li>
 *   <li>Transactional categories (ORDERS / ONBOARDING / FINANCIAL) are
 *       otherwise allowed — default-on under legitimate interest.</li>
 *   <li>{@link NotificationCategory#MARKETING} is refused unless an explicit
 *       marketing opt-in exists (PECR).</li>
 * </ul>
 */
@Component
public class ConsentGate {

    private final SuppressionService suppressionService;

    public ConsentGate(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    /**
     * @return {@code true} if the recipient may be sent this category, else
     *         {@code false}.
     */
    public boolean allows(UUID tenantId, String recipient, NotificationCategory category) {
        if (suppressionService.isSuppressed(tenantId, recipient, category)) {
            return false;
        }
        if (category == NotificationCategory.MARKETING) {
            return suppressionService.hasMarketingOptIn(tenantId, recipient);
        }
        return true;
    }
}
