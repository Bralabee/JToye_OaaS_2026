package uk.jtoye.core.notification.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.notification.WhatsAppProperties;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * INERT-by-default WhatsApp/SMS channel scaffold (COMMS-07). Live provider send
 * is out of scope this phase (#208 scaffold-only) — the value here is the seam:
 * a third {@link NotificationChannel} the dispatcher can fan to without any
 * special-casing, that is safe to ship OFF.
 *
 * <p><b>Fail-closed, never-throws (STRIDE T-22-01-03):</b> when
 * {@link WhatsAppProperties#configured()} is false (the default, and also the
 * case when the flag is flipped on without credentials), {@link #deliver} logs a
 * single WARN and returns — it never throws and never blocks the email/webhook
 * channels sharing the same dispatch fan-out. The one-time WARN is guarded by an
 * {@link AtomicBoolean} so repeated events don't spam the log, mirroring
 * {@code KeycloakDeprovisionService}'s inert-by-default pattern.
 */
@Component
public class WhatsAppSmsChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSmsChannel.class);

    private final WhatsAppProperties properties;

    /** Guards the not-configured WARN so repeated events don't spam the log. */
    private final AtomicBoolean warnedOnce = new AtomicBoolean(false);

    public WhatsAppSmsChannel(WhatsAppProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "whatsapp";
    }

    @Override
    public boolean enabled() {
        return properties.configured();
    }

    @Override
    public void deliver(NotificationMessage message) {
        if (!properties.configured()) {
            if (warnedOnce.compareAndSet(false, true)) {
                log.warn("event=whatsapp_skipped reason=not_configured "
                        + "(feature inert: set jtoye.whatsapp.enabled=true + account-sid + auth-token + from-number)");
            }
            // Never throws, never blocks the other channels.
            return;
        }

        // Configured, but live send is out of scope this phase — a logged no-op.
        String eventType = message == null ? "?" : message.eventType();
        log.info("event=whatsapp_would_send eventType={} (live send out of scope this phase, #208 scaffold)",
                eventType);
    }
}
