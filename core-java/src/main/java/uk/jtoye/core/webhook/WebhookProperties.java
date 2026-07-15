package uk.jtoye.core.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Grouped configuration for the webhook delivery engine (COMMS-05, D-07), bound
 * from the {@code webhook.*} keys in {@code application.yml}. No literals live in
 * code paths — attempt count, backoff schedule, auto-pause threshold, retention
 * window, egress timeout and signature tolerance are all injected here so a value
 * can be overridden per environment via {@code ${ENV:default}} without a redeploy
 * (GLOBAL_RULE_6).
 *
 * <p>Mirrors {@code KeycloakAdminProperties}: a plain grouped
 * {@code @ConfigurationProperties} component with nested typed sections. There is
 * no secret here (the per-subscription signing secret lives on
 * {@code webhook_subscription}), so no masking is required.
 */
@Component
@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    private final Delivery delivery = new Delivery();
    private final Envelope envelope = new Envelope();

    public Delivery getDelivery() {
        return delivery;
    }

    public Envelope getEnvelope() {
        return envelope;
    }

    /** Delivery-worker + retention tunables. */
    public static class Delivery {
        /** {@code @Scheduled} worker tick (ms). */
        private long intervalMs = 5000;
        /** Max due rows claimed per tenant per tick. */
        private int batchSize = 50;
        /** Attempts before a delivery flips FAILED. */
        private int maxAttempts = 8;
        /** Exponential-backoff base (ms). */
        private long backoffBaseMs = 1000;
        /** Exponential-backoff cap (ms) — default 1 hour. */
        private long backoffCapMs = 3_600_000;
        /** Consecutive-failure count at which a subscription auto-pauses. */
        private int autoPauseThreshold = 10;
        /** webhook_delivery retention window (days) — rows older than this are pruned. */
        private int retentionDays = 30;
        /** Retention-cleanup tick (ms) — default daily. */
        private long retentionIntervalMs = 86_400_000;
        /** Per-POST egress timeout (seconds). */
        private int timeoutSeconds = 10;
        /** Receiver clock-skew tolerance documented for vendors (seconds). */
        private long signatureToleranceSeconds = 300;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffBaseMs() {
            return backoffBaseMs;
        }

        public void setBackoffBaseMs(long backoffBaseMs) {
            this.backoffBaseMs = backoffBaseMs;
        }

        public long getBackoffCapMs() {
            return backoffCapMs;
        }

        public void setBackoffCapMs(long backoffCapMs) {
            this.backoffCapMs = backoffCapMs;
        }

        public int getAutoPauseThreshold() {
            return autoPauseThreshold;
        }

        public void setAutoPauseThreshold(int autoPauseThreshold) {
            this.autoPauseThreshold = autoPauseThreshold;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public long getRetentionIntervalMs() {
            return retentionIntervalMs;
        }

        public void setRetentionIntervalMs(long retentionIntervalMs) {
            this.retentionIntervalMs = retentionIntervalMs;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getSignatureToleranceSeconds() {
            return signatureToleranceSeconds;
        }

        public void setSignatureToleranceSeconds(long signatureToleranceSeconds) {
            this.signatureToleranceSeconds = signatureToleranceSeconds;
        }
    }

    /** Versioned event-envelope settings (D-05). */
    public static class Envelope {
        /** Envelope schema version stamped on every delivery. */
        private String version = "1";

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
