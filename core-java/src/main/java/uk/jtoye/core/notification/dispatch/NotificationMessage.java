package uk.jtoye.core.notification.dispatch;

import uk.jtoye.core.notification.template.RenderedEmail;

import java.util.UUID;

/**
 * A resolved recipient plus pre-rendered content, ready to hand to any
 * {@link NotificationChannel}. Plan 22-04 builds these from V46 outbox events
 * (resolve recipient per D-04, render per event, attach the unsubscribe URL);
 * the channels only transport what they are given.
 *
 * <p><b>No {@code NotificationCategory} field</b> — category is a CONSENT concept
 * owned by plan 22-02 ({@code notification.consent.NotificationCategory}).
 * Dispatch (22-04) applies the category via the consent gate + the pre-built
 * {@link #unsubscribeUrl}, so this contract does not depend on 22-02 (both Wave
 * 1, parallel-safe).
 *
 * <p><b>Two unsubscribe URLs, not one (issue #516).</b> The footer link is
 * clicked by a human (a browser GET) and must reach the frontend's branded
 * confirmation page; the RFC 8058 {@code List-Unsubscribe} target is POSTed by a
 * mail provider and must reach the API controller. The app and the API are
 * different Services behind the ingress, so one URL cannot be both — carrying a
 * single field is what let the app origin be composed with the API's path and
 * 404 in every environment.
 *
 * @param tenantId            owning tenant (for logging / channel routing)
 * @param recipient           resolved destination: email address or phone number
 * @param eventType           source event type, e.g. {@code "order.ready"}
 * @param email               rendered email content; nullable for non-email channels
 * @param plainSummary        short plain body for whatsapp / sms
 * @param unsubscribeUrl      the CLICKABLE unsubscribe URL (browser GET → frontend page);
 *                            null when the feature is unconfigured
 * @param oneClickUnsubscribeUrl the RFC 8058 {@code List-Unsubscribe} POST target (→ API);
 *                            null when no POST-capable origin is configured, in which case
 *                            no one-click capability is advertised
 */
public record NotificationMessage(
        UUID tenantId,
        String recipient,
        String eventType,
        RenderedEmail email,
        String plainSummary,
        String unsubscribeUrl,
        String oneClickUnsubscribeUrl) {
}
