package uk.jtoye.core.notification.dispatch;

import uk.jtoye.core.notification.NotificationProperties;
import uk.jtoye.core.notification.consent.ConsentGate;
import uk.jtoye.core.notification.consent.UnsubscribeTokenService;
import uk.jtoye.core.notification.template.EmailTemplateRenderer;

import java.util.UUID;

/**
 * TEMPORARY RED-phase stub — replaced by the GREEN implementation in the same task.
 */
public class NotificationDispatchService {

    public NotificationDispatchService(RecipientResolver recipientResolver,
                                       ConsentGate consentGate,
                                       EmailTemplateRenderer templateRenderer,
                                       UnsubscribeTokenService unsubscribeTokenService,
                                       NotificationProperties notificationProperties,
                                       EmailChannel emailChannel,
                                       WhatsAppSmsChannel whatsAppSmsChannel) {
    }

    public void dispatch(String eventType, UUID tenantId, Object payload) {
    }
}
