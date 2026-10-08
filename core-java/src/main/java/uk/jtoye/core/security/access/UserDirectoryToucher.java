package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records a signed-in vendor user in {@code user_directory} (D-09, Phase 37-05) — on READ requests as
 * well as writes, so the Staff page can list everyone who has signed in to the tenant, including the
 * ones who have no access yet.
 *
 * <p>Until 37-05 the upsert ran inside {@code ShopAccessService.onRequest()} and only on
 * write-capable transactions (a failed INSERT in a read-only transaction would abort the read that
 * triggered it). A user who had only ever read therefore never appeared in the list, and under D-06
 * an ungranted user can do little BUT read. This component runs the upsert in its OWN transaction:
 *
 * <ul>
 *   <li><strong>Separate transaction ({@code REQUIRES_NEW})</strong>, so it works under a read-only
 *       caller and a failure cannot poison the caller's transaction (PostgreSQL aborts a whole
 *       transaction on any statement error).</li>
 *   <li><strong>Best-effort (T-37-11)</strong>: any failure, including a commit that the database
 *       turned into a rollback, is logged at WARN and swallowed. The request that triggered the touch
 *       never fails because of it. The transaction is driven by a {@link TransactionTemplate} inside
 *       the {@code try}, not by a {@code @Transactional} annotation on this method: with the
 *       annotation, a statement failure marks the transaction rollback-only and the proxy then
 *       throws {@code UnexpectedRollbackException} at commit, AFTER any catch in the method body,
 *       straight into the caller.</li>
 *   <li><strong>Throttled</strong> by {@code jtoye.access.directory-upsert-interval} twice: the SQL
 *       itself updates only a row older than the interval (unchanged from Phase 23), and this
 *       instance skips the round trip entirely for a (tenant, user) it wrote within the interval.
 *       The in-process skip is what keeps a second pooled connection from being checked out on every
 *       gated call of every request.</li>
 *   <li><strong>Never a grant</strong>: it writes only the directory row. JIT provisioning stays in
 *       {@code ShopAccessService.onRequest()} and stays off under strict scoping (D-06).</li>
 * </ul>
 *
 * <p>The tenant GUC for the new transaction is pinned by {@code TenantSetLocalAspect} before the
 * repository call, from the request's {@code TenantContext}; the directory row is written through
 * the FORCE RLS policy, never around it.
 */
@Component
public class UserDirectoryToucher {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryToucher.class);

    /** {@code user_directory.email} is VARCHAR(320). */
    static final int EMAIL_MAX = 320;
    /** {@code user_directory.display_name} is VARCHAR(255). */
    static final int DISPLAY_NAME_MAX = 255;
    /** Prune the in-process throttle map when it grows past this many entries. */
    private static final int THROTTLE_PRUNE_AT = 10_000;

    private final UserDirectoryRepository userDirectoryRepository;
    private final TransactionTemplate requiresNew;

    @Value("${jtoye.access.directory-upsert-interval:PT1H}")
    private Duration directoryUpsertInterval;

    /** (tenant, user) → when this instance last wrote the row successfully. */
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

    public UserDirectoryToucher(UserDirectoryRepository userDirectoryRepository,
                                PlatformTransactionManager transactionManager) {
        this.userDirectoryRepository = userDirectoryRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Record that {@code userId} signed in to {@code tenantId}. Best-effort: returns normally on any
     * failure.
     *
     * @param realmAdmin whether the token carries the realm {@code admin} role (stamped from 37-05
     *                   Task 2 on; recorded in the throttle key so a newly observed admin role is
     *                   never skipped)
     */
    public void touch(UUID tenantId, UUID userId, String email, String displayName, boolean realmAdmin) {
        if (tenantId == null || userId == null) {
            return;
        }
        String key = tenantId + ":" + userId + ":" + realmAdmin;
        Instant now = Instant.now();
        Instant previous = lastTouched.get(key);
        if (previous != null && previous.isAfter(now.minus(directoryUpsertInterval))) {
            return;
        }
        String safeEmail = email != null && email.length() <= EMAIL_MAX ? email : null;
        String safeName = displayName != null && displayName.length() > DISPLAY_NAME_MAX
                ? displayName.substring(0, DISPLAY_NAME_MAX)
                : displayName;
        OffsetDateTime cutoff = OffsetDateTime.now().minus(directoryUpsertInterval);
        try {
            requiresNew.executeWithoutResult(status ->
                    userDirectoryRepository.upsertSeen(tenantId, userId, safeEmail, safeName, cutoff));
            pruneIfLarge(now);
            lastTouched.put(key, now);
        } catch (RuntimeException ex) {
            // SQL errors can carry the failed row's PII (the email); log the type, not the message.
            log.warn("Directory upsert skipped (best-effort) for sub {} tenant {}: {}",
                    userId, tenantId, ex.getClass().getSimpleName());
        }
    }

    private void pruneIfLarge(Instant now) {
        if (lastTouched.size() < THROTTLE_PRUNE_AT) {
            return;
        }
        Instant stale = now.minus(directoryUpsertInterval);
        lastTouched.entrySet().removeIf(e -> e.getValue().isBefore(stale));
    }
}
