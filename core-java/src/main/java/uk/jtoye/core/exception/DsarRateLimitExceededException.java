package uk.jtoye.core.exception;

/**
 * The DSAR intake's own IP-keyed bucket refused a request (Phase 31, threat T-31-05-02).
 *
 * <p>Distinct from the platform limiter's 429, and deliberately so: the platform bucket is a
 * throughput control, whereas this one bounds an <em>unverified erasure request</em> — a
 * destructive action anybody on the internet can aim at anybody else. A machine client that wants
 * to tell "I am going too fast" from "this specific destructive endpoint is protected" can branch
 * on the stable type rather than on prose.
 *
 * <p>Carries the wait in seconds so the caller gets an integer rather than having to mine one out
 * of an English sentence — the same defect #409/#410 recorded on the platform 429.
 */
public class DsarRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public DsarRateLimitExceededException(long retryAfterSeconds) {
        super("Too many data-subject requests from this client. Please try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
