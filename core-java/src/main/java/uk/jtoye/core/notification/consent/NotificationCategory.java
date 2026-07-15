package uk.jtoye.core.notification.consent;

/**
 * The per-category consent axis for outbound notifications (Phase 22, COMMS-03,
 * decision D-03). A category is a <em>consent</em> concept, so it is owned here
 * in the {@code notification.consent} package and consumed by the 22-04 dispatch
 * layer (the may-we-send gate keys suppression + marketing opt-in on it).
 *
 * <p>Consent posture per category (see {@link ConsentGate}):
 * <ul>
 *   <li>{@link #ORDERS}, {@link #ONBOARDING}, {@link #FINANCIAL} — transactional.
 *       Default-<em>on</em> under legitimate interest; a recipient is emailed
 *       UNLESS they hold a per-category suppression row (one-click unsubscribe).</li>
 *   <li>{@link #MARKETING} — requires <em>explicit</em> opt-in. Absent an opt-in
 *       row the send is refused; a suppression row also refuses it.</li>
 * </ul>
 *
 * <p>Names are persisted verbatim into {@code notification_suppression.category}
 * ({@code VARCHAR(16)}); every constant name here MUST stay {@code <= 16} chars.
 */
public enum NotificationCategory {
    /** Order lifecycle (received / confirmed / preparing / ready / completed / cancelled). Transactional. */
    ORDERS,
    /** Vendor onboarding lifecycle (stall / verification / rejection). Transactional. */
    ONBOARDING,
    /** Payment + refund events. Transactional. */
    FINANCIAL,
    /** Marketing / promotional email. Requires explicit opt-in. */
    MARKETING
}
