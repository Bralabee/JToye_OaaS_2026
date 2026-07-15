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
 * @param tenantId       owning tenant (for logging / channel routing)
 * @param recipient      resolved destination: email address or phone number
 * @param eventType      source event type, e.g. {@code "order.ready"}
 * @param email          rendered email content; nullable for non-email channels
 * @param plainSummary   short plain body for whatsapp / sms
 * @param unsubscribeUrl one-click unsubscribe URL (RFC 8058 header + footer link)
 */
public record NotificationMessage(
        UUID tenantId,
        String recipient,
        String eventType,
        RenderedEmail email,
        String plainSummary,
        String unsubscribeUrl) {
}
