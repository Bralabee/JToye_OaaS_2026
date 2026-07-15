package uk.jtoye.core.notification.dispatch;

/**
 * Provider abstraction for a single notification transport (email / webhook /
 * whatsapp). Phase 22 introduces this seam so the dispatch orchestration
 * (22-04) and every future channel program against one contract rather than a
 * concrete sender.
 *
 * <p><b>Non-throwing contract:</b> {@link #deliver(NotificationMessage)} MUST
 * never propagate an exception — a transport failure (SMTP outage, an inert
 * off-by-default channel, a malformed message) is swallowed and logged, exactly
 * like the working {@code EmailNotificationService} order path. This keeps one
 * failing channel from blocking the others when the dispatcher fans a single
 * event out to email + webhook + whatsapp.
 *
 * <p>Deliberately owns NO consent concept — {@code NotificationCategory} is a
 * governance type owned by plan 22-02. Dispatch applies the category via a
 * consent gate + the pre-built {@code unsubscribeUrl} carried on the message,
 * keeping this contract decoupled so 22-01 and 22-02 stay parallel-safe.
 */
public interface NotificationChannel {

    /** Stable channel identifier: {@code "email"} | {@code "webhook"} | {@code "whatsapp"}. */
    String name();

    /** Config-gated availability. When false the dispatcher skips the channel. */
    boolean enabled();

    /**
     * Deliver the message over this transport. Never throws — implementations
     * swallow and log delivery failures.
     */
    void deliver(NotificationMessage message);
}
