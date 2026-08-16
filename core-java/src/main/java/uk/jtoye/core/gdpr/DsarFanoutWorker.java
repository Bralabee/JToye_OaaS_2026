package uk.jtoye.core.gdpr;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes lodged data-subject requests across every tenant, in the background, so that
 * <b>no human ever holds cross-tenant read</b> (Phase 31, D-17, requirement LGL-01).
 *
 * <h2>The conflict this class resolves</h2>
 *
 * A single cross-tenant point of contact for UK GDPR requests appears to require the cross-tenant
 * operator identity this project has refused twice — most recently and explicitly in Phase 33's
 * D-2. D-17's resolution is that the reach belongs to a scheduled job and to nothing else:
 * {@code ShopAccessService} already records the standing rule that a request thread never enters
 * {@code SystemPrincipal.asSystem}; only background entry points do. 31-05 built the request half
 * (lodge a row and stop). This is the background half, and it is the half that makes the published
 * single point of contact real rather than a promise.
 *
 * <h2>Where the cross-tenant reach actually comes from — read this before editing the loop</h2>
 *
 * <b>NOT from {@code asSystem}.</b> {@code SystemPrincipal} is explicit that its marker "is an
 * AUTHORISATION declaration, not a tenancy escape … it says nothing whatsoever about which tenant's
 * rows it can see, and it cannot be used to reach another tenant's data." Misreading it as a
 * tenancy escape is the single most dangerous mistake available in this file.
 *
 * <p>The reach comes from <b>iterating tenants and pinning {@code app.current_tenant_id}</b>, which
 * is per-tenant by construction — one tenant is visible at a time, and that IS the control. Anyone
 * who "simplifies" the loop away, or hoists the transaction outside it, has not tidied this class;
 * they have deleted its only safety property.
 *
 * <h2>Two hazards this repository has already measured, both silent</h2>
 *
 * <ol>
 *   <li><b>One transaction per tenant, NEVER one spanning all of them.</b> The RLS GUC is
 *       transaction-local ({@code set_config(..., true)}). {@code ScheduledCleanupService} records
 *       the measured failure: under a single transaction, tenant A's deferred cascade flushed AFTER
 *       the GUC had switched to tenant B, FORCE RLS filtered those rows to zero, a
 *       {@code StaleStateException} followed, and the whole job rolled back having done nothing.</li>
 *   <li><b>{@link TransactionTemplate}, not {@code @Transactional} on a private method.</b> Spring
 *       self-invocation bypasses the proxy, so no transaction starts at all and the work runs with
 *       a NULL tenant — which, under FORCE RLS, quietly matches zero rows and reports success. This
 *       class carries no {@code @Transactional} annotation anywhere, and a grep asserts it.</li>
 * </ol>
 *
 * <p>Structurally a clone of {@code WebhookRetentionCleanup} (tenant loop, own
 * {@code TransactionTemplate}, {@code pinTenantGuc}, per-tenant {@code try/catch}, and
 * {@code TenantContext.clear()} in a {@code finally}), which is already the house move —
 * {@code MediaQuarantineRetentionSweep} cloned the same shape. The differences are the outer loop
 * over claimed requests and the {@code asSystem} declaration, and both are explained where they
 * appear.
 *
 * <h2>What it will and will not touch</h2>
 *
 * <ul>
 *   <li><b>{@code VERIFIED} only.</b> A {@code PENDING_VERIFICATION} row is never actioned. An
 *       unverified erasure request is a destructive action anybody on the internet could aim at
 *       anybody else (T-31-05-02), so control of the address is proven first —
 *       {@link DsarVerificationService} owns that transition.</li>
 *   <li><b>{@code ERASURE} only, in this plan.</b> See {@link #outstandingAccessRequests()} for the
 *       reason, which is this plan's own threat register rather than an oversight.</li>
 * </ul>
 *
 * <h2>The outcome tells the subject nothing about which vendors held their data</h2>
 *
 * The row records a COUNT of tenants erased, never their identities (T-31-09-05). "Which of your
 * vendors holds this person's address" is exactly what the tenant wall exists to withhold, and
 * 31-05's opaque 202 would be worthless if the completion path handed the answer back.
 */
@Component
public class DsarFanoutWorker {

    private static final Logger log = LoggerFactory.getLogger(DsarFanoutWorker.class);

    /**
     * Claim in ONE statement, so two schedulers cannot both take the same request. The predicate
     * lives in the WHERE clause rather than in a read-then-write the application referees:
     * {@code FOR UPDATE SKIP LOCKED} steps over rows another sweep is holding uncommitted, and the
     * {@code status = 'VERIFIED'} test excludes the ones it has already committed. This is the
     * {@code media_event_outbox} claim idiom, unchanged.
     *
     * <p>{@code process_attempts} is incremented here, on the claim — mirroring
     * {@code media_asset.process_attempts} (V60), which exists so a sweep can tell "never attempted"
     * from "attempted and stalled" instead of guessing from age.
     */
    private static final String CLAIM_SQL = """
            UPDATE dsar_request
               SET status = 'IN_PROGRESS',
                   claimed_at = NOW(),
                   process_attempts = process_attempts + 1
             WHERE id IN (
                   SELECT id
                     FROM dsar_request
                    WHERE status = 'VERIFIED'
                      AND completed_at IS NULL
                      AND request_type = 'ERASURE'
                    ORDER BY received_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?)
            RETURNING id, subject_email_sha256, process_attempts
            """;

    private final GdprService gdprService;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    @Value("${jtoye.gdpr.dsar.claim-batch-size:25}")
    private int claimBatchSize;

    /**
     * How many times a request may be attempted before it is parked as FAILED. Without a cap a
     * permanently failing tenant would re-claim the same request forever; with one, the failure
     * becomes a loud, countable state instead of an infinite quiet retry.
     */
    @Value("${jtoye.gdpr.dsar.max-process-attempts:5}")
    private int maxProcessAttempts;

    public DsarFanoutWorker(GdprService gdprService,
                            JdbcTemplate jdbcTemplate,
                            EntityManager entityManager,
                            PlatformTransactionManager transactionManager) {
        this.gdprService = gdprService;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        // Built here rather than annotating a method: see hazard 2 in the class javadoc.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The scheduled entry point — and the only place in {@code src/main/java} that declares system
     * authority, which is precisely what D-17 asks for.
     *
     * <p>{@code fixedDelayString} with an inline default so the worker still runs when the key is
     * absent, in the {@code webhook.delivery.retention-interval-ms} shape.
     */
    @Scheduled(fixedDelayString = "${jtoye.gdpr.dsar.fanout-interval-ms:300000}")
    public void executeLodgedRequests() {
        List<Map<String, Object>> claimed = claim();
        long outstandingAccess = outstandingAccessRequests();
        if (outstandingAccess > 0) {
            log.warn("event=dsar_access_requests_outstanding count={} — ACCESS delivery is not "
                    + "implemented in plan 31-09; these rows are counted here rather than left "
                    + "invisible", outstandingAccess);
        }
        if (claimed.isEmpty()) {
            return;
        }

        List<UUID> tenantIds = listTenantIds();
        for (Map<String, Object> request : claimed) {
            UUID requestId = (UUID) request.get("id");
            String subjectDigest = (String) request.get("subject_email_sha256");
            int attempts = ((Number) request.get("process_attempts")).intValue();
            executeOne(requestId, subjectDigest, attempts, tenantIds);
        }
    }

    private void executeOne(UUID requestId, String subjectDigest, int attempts, List<UUID> tenantIds) {
        int tenantsErased = 0;
        int tenantsFailed = 0;

        for (UUID tenantId : tenantIds) {
            try {
                if (eraseForTenant(tenantId, subjectDigest) > 0) {
                    tenantsErased++;
                }
            } catch (Exception e) {
                // One tenant's failure must never abort the sweep for the others: the subject has
                // one statutory right against the controller, and a broken vendor must not cost
                // them the erasures that CAN be performed. Logged with the tenant id so the failure
                // is actionable, and the request is released for retry below rather than completed.
                tenantsFailed++;
                log.error("event=dsar_fanout_tenant_failed request={} tenant={} — continuing: {}",
                        requestId, tenantId, e.getMessage());
            }
        }

        if (tenantsFailed == 0) {
            complete(requestId, tenantsErased, tenantIds.size());
            return;
        }
        release(requestId, tenantsErased, tenantsFailed, attempts);
    }

    /**
     * One tenant, one transaction, GUC pinned inside it, thread left clean on every path.
     */
    private int eraseForTenant(UUID tenantId, String subjectDigest) {
        TenantContext.set(tenantId);
        try {
            Integer erased = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);

                // WHAT THIS WRAP DOES AND DOES NOT DO — the comment that stops the next reader
                // from "simplifying" the loop away.
                //
                // DOES: declare that this thread is internal system work, so it may pass the
                //       shop-scope gate (SystemPrincipal / #283). Only background entry points may
                //       declare this; a request thread never does.
                // DOES NOT: grant any cross-tenant read whatsoever. SystemPrincipal is explicit
                //       that the marker "says nothing about which tenant's rows it can see, and it
                //       cannot be used to reach another tenant's data".
                //
                // The reach is the SURROUNDING LOOP plus the GUC pinned two lines above — one
                // tenant at a time, under FORCE row-level security, exactly like every other
                // caller. Delete the loop or the pin and this worker sees nothing; delete this
                // wrap and it may be refused at the gate. They are different controls.
                return SystemPrincipal.asSystem(
                        () -> gdprService.eraseSubjectByDigest(tenantId, subjectDigest));
            });
            return erased == null ? 0 : erased;
        } finally {
            // ALWAYS, on every path. These are pooled threads; a stale tenant left on a returned
            // thread is a cross-tenant read waiting to happen on an unrelated request.
            TenantContext.clear();
        }
    }

    private void complete(UUID requestId, int tenantsErased, int tenantsScanned) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'COMPLETED', completed_at = NOW(), "
                                + "last_error = NULL WHERE id = ?",
                        requestId));
        // A request from somebody no tenant holds is SATISFIED, not stuck — tenantsErased may
        // legitimately be zero. The count is recorded in the log and nowhere the subject can read
        // it (T-31-09-05).
        log.info("event=dsar_fanout_completed request={} tenantsErased={} tenantsScanned={}",
                requestId, tenantsErased, tenantsScanned);
    }

    /**
     * A partially-failed request goes back to {@code VERIFIED} with {@code completed_at} still NULL,
     * so the next sweep retries the tenants that failed. Marking it complete would silently drop
     * those erasures for good, which is the failure this whole phase exists to stop shipping.
     *
     * <p>Retrying a tenant that already succeeded is harmless: the anonymised address no longer
     * hashes to the subject digest, so the second pass matches nothing and writes no second
     * {@code erasure_record}.
     */
    private void release(UUID requestId, int tenantsErased, int tenantsFailed, int attempts) {
        boolean exhausted = attempts >= maxProcessAttempts;
        String error = tenantsFailed + " tenant(s) failed; " + tenantsErased + " erased; attempt "
                + attempts + " of " + maxProcessAttempts;

        transactionTemplate.executeWithoutResult(status -> {
            if (exhausted) {
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'FAILED', completed_at = NOW(), "
                                + "last_error = ? WHERE id = ?", error, requestId);
            } else {
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'VERIFIED', claimed_at = NULL, "
                                + "last_error = ? WHERE id = ?", error, requestId);
            }
        });

        if (exhausted) {
            log.error("event=dsar_fanout_exhausted request={} {} — this request will NOT be retried "
                    + "again and a data subject's statutory right is unsatisfied", requestId, error);
        } else {
            log.warn("event=dsar_fanout_released_for_retry request={} {}", requestId, error);
        }
    }

    /**
     * How many verified ACCESS requests are waiting, counted every sweep so the backlog is visible
     * rather than silent.
     *
     * <p><b>Why ACCESS is not executed here.</b> This plan's own threat register lists
     * "telling the subject which tenants held their data" as T-31-09-05, to be mitigated by
     * recording a count and never per-tenant detail. An Article 15 response cannot honour that: UK
     * GDPR Article 15(1)(c) obliges the controller to name the recipients, and an order history
     * stripped of the vendor is neither useful nor compliant. Executing ACCESS therefore needs a
     * delivery channel decided deliberately — a one-time expiring download rather than a mailed
     * copy, which needs a table this plan does not own (V63 belongs to 31-10). Rather than resolve
     * that unilaterally, or leave the rows to rot invisibly, the backlog is counted and logged.
     */
    private long outstandingAccessRequests() {
        Long n = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dsar_request "
                        + "WHERE status = 'VERIFIED' AND completed_at IS NULL "
                        + "AND request_type = 'ACCESS'",
                Long.class));
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> claim() {
        return transactionTemplate.execute(status ->
                jdbcTemplate.queryForList(CLAIM_SQL, claimBatchSize));
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
    }

    /**
     * Pin the tenant for the CURRENT transaction. {@code set_config(..., true)} is transaction-local
     * — which is exactly why each tenant needs its own transaction (hazard 1 above).
     */
    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
