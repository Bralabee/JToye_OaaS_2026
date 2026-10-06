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
import uk.jtoye.core.gdpr.DsarAccessExportService.IssuedToken;
import uk.jtoye.core.gdpr.DsarAccessExportService.TenantSection;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService.AccountDeletionResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
 *   <li><b>Both request types</b> (31.1-16). {@code ERASURE} anonymises; {@code ACCESS} (Article 15,
 *       D-01) collects the subject's data in every tenant through the SAME per-tenant wrapper, stores
 *       one encrypted document behind a single-use token and emails the verified address a link.
 *       Until 31.1-16 ACCESS was only counted and logged, because an Article 15 answer must name the
 *       vendors (Article 15(1)(c)) and that needed a delivery channel decided deliberately; D-01
 *       decided it.</li>
 * </ul>
 *
 * <h2>An erasure is complete only when the sign-in account is gone (31.1-11, D-03)</h2>
 *
 * After every tenant's erasure has committed, the worker decrypts the verified address (D-19) and
 * deletes the subject's customer-realm Keycloak account through
 * {@link CustomerAccountDeletionService}. The request becomes {@code COMPLETED} only when that is
 * confirmed ({@code DELETED}) or no account exists ({@code NONE_FOUND}); then, and only after that
 * commit, {@link DsarOutcomeMailer} tells the subject. Anything else — an outage, an error, the admin
 * seam switched off — records {@code OUTSTANDING} or {@code NOT_CONFIGURED} and releases the request;
 * the erasure is never rolled back, and the retry re-runs a harmless erasure before trying again.
 *
 * <h2>An access request is complete only when the subject has the link (31.1-16, D-01)</h2>
 *
 * Every tenant is collected first (one failure releases the request: a partial Article 15 answer is
 * not an answer); then the address is decrypted, the customer-realm account looked up, the document
 * built ONCE, stored encrypted, and the link emailed. {@code COMPLETED} — with the address ciphertext
 * dropped in the same statement — is written only after the export row has committed AND the mailer
 * reports the message accepted. A failed send releases the request; the retry builds a fresh export
 * and a fresh token, replacing the old row, so a link that may never have arrived is never left live.
 *
 * <h2>The erasure outcome tells the subject nothing about which vendors held their data</h2>
 *
 * The row records a COUNT of tenants erased, never their identities (T-31-09-05). "Which of your
 * vendors holds this person's address" is exactly what the tenant wall exists to withhold, and
 * 31-05's opaque 202 would be worthless if the completion path handed the answer back. The ACCESS
 * export is the one place vendors ARE named, because Article 15(1)(c) requires it — and it is
 * delivered only to the verified address, behind a single-use, expiring link.
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
                    ORDER BY received_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?)
            RETURNING id, request_type, subject_email_sha256, process_attempts, subject_email_ciphertext
            """;

    private final GdprService gdprService;
    private final DsarCipher dsarCipher;
    private final CustomerAccountDeletionService accountDeletionService;
    private final DsarOutcomeMailer outcomeMailer;
    private final DsarAccessExportService accessExportService;
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
                            DsarCipher dsarCipher,
                            CustomerAccountDeletionService accountDeletionService,
                            DsarOutcomeMailer outcomeMailer,
                            DsarAccessExportService accessExportService,
                            JdbcTemplate jdbcTemplate,
                            EntityManager entityManager,
                            PlatformTransactionManager transactionManager) {
        this.gdprService = gdprService;
        this.dsarCipher = dsarCipher;
        this.accountDeletionService = accountDeletionService;
        this.outcomeMailer = outcomeMailer;
        this.accessExportService = accessExportService;
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
        if (claimed.isEmpty()) {
            return;
        }

        List<UUID> tenantIds = listTenantIds();
        for (Map<String, Object> request : claimed) {
            UUID requestId = (UUID) request.get("id");
            String subjectDigest = (String) request.get("subject_email_sha256");
            int attempts = ((Number) request.get("process_attempts")).intValue();
            byte[] ciphertext = (byte[]) request.get("subject_email_ciphertext");
            String requestType = (String) request.get("request_type");
            if ("ACCESS".equals(requestType)) {
                executeAccess(requestId, subjectDigest, ciphertext, attempts, tenantIds);
            } else {
                executeOne(requestId, subjectDigest, ciphertext, attempts, tenantIds);
            }
        }
    }

    // ---- ACCESS (Article 15, 31.1-16, D-01) ----------------------------------------------------------

    /**
     * Collect in every tenant, then build, store, send — and complete only when the send succeeded.
     * Never throws: anything unexpected releases the request for retry.
     */
    private void executeAccess(UUID requestId, String subjectDigest, byte[] ciphertext, int attempts,
                               List<UUID> tenantIds) {
        List<TenantSection> sections = new ArrayList<>();
        int tenantsFailed = 0;
        for (UUID tenantId : tenantIds) {
            try {
                // The SAME wrapper as the erasure: one tenant, one transaction, GUC pinned, thread
                // cleared. The export can only contain a tenant's rows because this loop pinned it.
                Optional<TenantSection> section = inPinnedTenant(tenantId,
                        () -> accessExportService.collectForTenant(tenantId, subjectDigest));
                if (section != null) {
                    section.ifPresent(sections::add);
                }
            } catch (Exception e) {
                // A partial Article 15 answer is not an answer: the request is released below and the
                // whole collection is retried. The tenant id makes the failure actionable.
                tenantsFailed++;
                log.error("event=dsar_access_tenant_failed request={} tenant={} error={}",
                        requestId, tenantId, e.getClass().getName());
            }
        }
        if (tenantsFailed > 0) {
            releaseAccess(requestId, "%d tenant(s) failed during collection".formatted(tenantsFailed), attempts);
            return;
        }
        if (ciphertext == null) {
            // Without the address there is nowhere to send the link; retrying will not produce one, so
            // the request runs out its attempts and is parked FAILED, loudly.
            log.error("event=dsar_access_failed request={} reason=no_subject_address", requestId);
            releaseAccess(requestId, "no subject address on the request", attempts);
            return;
        }

        String address;
        IssuedToken issued;
        try {
            address = dsarCipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, requestId, ciphertext);
            byte[] document = accessExportService.buildDocument(address, sections, OffsetDateTime.now());
            // Stored and COMMITTED before the email goes: a link must never point at nothing.
            issued = accessExportService.storeAndIssueToken(requestId, document);
        } catch (RuntimeException e) {
            log.error("event=dsar_access_failed request={} stage=build_or_store error={}", requestId,
                    e.getClass().getName());
            releaseAccess(requestId, "the export could not be built or stored", attempts);
            return;
        }

        boolean sent = outcomeMailer.sendAccessExportReady(address, issued.token(),
                accessExportService.exportLinkTtlHours());
        if (!sent) {
            // The link is the fulfilment. Not sent = not fulfilled: the next sweep builds a fresh
            // export and a fresh token, replacing this row, so this token dies unused.
            releaseAccess(requestId, "the download link email was not sent", attempts);
            return;
        }
        completeAccess(requestId, sections.size(), tenantIds.size());
    }

    /**
     * The subject has the link: COMPLETED, and the encrypted address dropped in the same statement
     * (the V70 CHECK refuses a terminal row that keeps it).
     */
    private void completeAccess(UUID requestId, int tenantsHolding, int tenantsScanned) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'COMPLETED', completed_at = NOW(), "
                                + "last_error = NULL, subject_email_ciphertext = NULL WHERE id = ?",
                        requestId));
        // The count is recorded in the log only; the vendor names are in the export the subject holds.
        log.info("event=dsar_access_completed request={} tenantsHolding={} tenantsScanned={}",
                requestId, tenantsHolding, tenantsScanned);
    }

    /**
     * Back to {@code VERIFIED} with the address KEPT (the retry needs it), or {@code FAILED} after
     * {@code max-process-attempts} — then the address is dropped like every terminal state, and any
     * export prepared for a link that never reached the subject is deleted with it: nobody holds a
     * working token for it, and a stored copy of a subject's data that no one can collect is exactly
     * what this design exists not to leave behind.
     */
    private void releaseAccess(UUID requestId, String reason, int attempts) {
        boolean exhausted = attempts >= maxProcessAttempts;
        String error = reason + "; attempt " + attempts + " of " + maxProcessAttempts;
        transactionTemplate.executeWithoutResult(status -> {
            if (exhausted) {
                jdbcTemplate.update("DELETE FROM dsar_access_export WHERE dsar_request_id = ?", requestId);
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'FAILED', completed_at = NOW(), "
                                + "last_error = ?, subject_email_ciphertext = NULL WHERE id = ?",
                        error, requestId);
            } else {
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'VERIFIED', claimed_at = NULL, "
                                + "last_error = ? WHERE id = ?", error, requestId);
            }
        });
        if (exhausted) {
            log.error("event=dsar_access_exhausted request={} {} — this request will NOT be retried again "
                    + "and a data subject's statutory right of access is unsatisfied", requestId, error);
        } else {
            log.warn("event=dsar_access_released_for_retry request={} {}", requestId, error);
        }
    }

    // ---- ERASURE (Article 17) --------------------------------------------------------------------

    private void executeOne(UUID requestId, String subjectDigest, byte[] ciphertext, int attempts,
                            List<UUID> tenantIds) {
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

        if (tenantsFailed > 0) {
            release(requestId, tenantsErased, tenantsFailed, attempts);
            return;
        }

        // D-03: every tenant's erasure has COMMITTED (each ran in its own transaction above). Only now
        // is the sign-in account deleted, so a Keycloak failure can never roll an erasure back.
        AccountStep account = deleteSubjectAccount(requestId, ciphertext);
        if (account.result() == AccountDeletionResult.DELETED
                || account.result() == AccountDeletionResult.NONE_FOUND) {
            complete(requestId, tenantsErased, tenantIds.size(), account.result());
            // After the COMPLETED UPDATE has committed, never before: a subject is told "done" only
            // about a request the database already records as done. The address is the one decrypted
            // above, held in memory only; the row no longer carries it.
            outcomeMailer.sendErasureCompleted(account.address(), account.result());
            return;
        }
        releaseAccountOutstanding(requestId, tenantsErased, account.result(), attempts);
    }

    /** The outcome of the account step, and the decrypted address the completion email goes to. */
    private record AccountStep(AccountDeletionResult result, String address) {
    }

    /**
     * Decrypt the verified address (D-19) and delete the subject's customer-realm account. Never
     * throws: anything unexpected is an unconfirmed deletion, not a reason to abandon the sweep.
     * Nothing logged here carries the address.
     */
    private AccountStep deleteSubjectAccount(UUID requestId, byte[] ciphertext) {
        if (ciphertext == null) {
            // Without the address the account cannot be found, so the deletion cannot be confirmed.
            // Retrying will not produce one; the request runs out its attempts and is parked FAILED.
            log.error("event=dsar_account_deletion_failed request={} reason=no_subject_address", requestId);
            return new AccountStep(AccountDeletionResult.FAILED, null);
        }
        String address = null;
        try {
            address = dsarCipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, requestId, ciphertext);
            AccountDeletionResult result = accountDeletionService.deleteCustomerAccount(address);
            return new AccountStep(result == null ? AccountDeletionResult.FAILED : result, address);
        } catch (RuntimeException e) {
            log.error("event=dsar_account_deletion_failed request={} error={}", requestId,
                    e.getClass().getName());
            return new AccountStep(AccountDeletionResult.FAILED, address);
        }
    }

    /**
     * The data is erased but the sign-in account is not confirmed gone: the request goes back to
     * {@code VERIFIED} with the ciphertext KEPT (the retry needs the address), recording
     * {@code OUTSTANDING} — or {@code NOT_CONFIGURED} when the admin seam is off in this runtime,
     * which is still not complete. The next sweep re-runs the erasure, which now matches nothing and
     * writes no second record, and then the deletion.
     *
     * <p>After {@code max-process-attempts} the request is parked {@code FAILED}, the ciphertext is
     * dropped like every other terminal state, and an ERROR is logged: a data subject's statutory
     * right is then unsatisfied and somebody has to act.
     */
    private void releaseAccountOutstanding(UUID requestId, int tenantsErased, AccountDeletionResult account,
                                           int attempts) {
        String accountStatus = account == AccountDeletionResult.NOT_CONFIGURED ? "NOT_CONFIGURED" : "OUTSTANDING";
        boolean exhausted = attempts >= maxProcessAttempts;
        String error = "sign-in account deletion " + accountStatus + "; " + tenantsErased
                + " tenant(s) erased; attempt " + attempts + " of " + maxProcessAttempts;

        transactionTemplate.executeWithoutResult(status -> {
            if (exhausted) {
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'FAILED', completed_at = NOW(), last_error = ?, "
                                + "subject_email_ciphertext = NULL, account_deletion_status = ?, "
                                + "account_deletion_attempts = account_deletion_attempts + 1 WHERE id = ?",
                        error, accountStatus, requestId);
            } else {
                jdbcTemplate.update(
                        "UPDATE dsar_request SET status = 'VERIFIED', claimed_at = NULL, last_error = ?, "
                                + "account_deletion_status = ?, "
                                + "account_deletion_attempts = account_deletion_attempts + 1 WHERE id = ?",
                        error, accountStatus, requestId);
            }
        });

        if (exhausted) {
            log.error("event=dsar_account_deletion_exhausted request={} {} — the personal data is erased "
                    + "but the customer sign-in account may still exist; this request will NOT be retried "
                    + "again", requestId, error);
        } else {
            log.warn("event=dsar_account_deletion_outstanding request={} {}", requestId, error);
        }
    }

    /**
     * One tenant, one transaction, GUC pinned inside it, thread left clean on every path.
     */
    private int eraseForTenant(UUID tenantId, String subjectDigest) {
        Integer erased = inPinnedTenant(tenantId, () -> gdprService.eraseSubjectByDigest(tenantId, subjectDigest));
        return erased == null ? 0 : erased;
    }

    /**
     * The per-tenant unit of work for BOTH request types: one tenant, one transaction, the GUC pinned
     * inside it, system authority declared, and the thread left clean on every path.
     */
    private <T> T inPinnedTenant(UUID tenantId, Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return transactionTemplate.execute(status -> {
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
                return SystemPrincipal.asSystem(work);
            });
        } finally {
            // ALWAYS, on every path. These are pooled threads; a stale tenant left on a returned
            // thread is a cross-tenant read waiting to happen on an unrelated request.
            TenantContext.clear();
        }
    }

    private void complete(UUID requestId, int tenantsErased, int tenantsScanned,
                          AccountDeletionResult account) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        // D-19 / V70: the encrypted address is destroyed in the SAME statement that
                        // makes the row terminal — the CHECK refuses a COMPLETED row that keeps it.
                        // D-03 / V72: the account outcome is recorded in that same statement.
                        "UPDATE dsar_request SET status = 'COMPLETED', completed_at = NOW(), "
                                + "last_error = NULL, subject_email_ciphertext = NULL, "
                                + "account_deletion_status = ?, "
                                + "account_deletion_attempts = account_deletion_attempts + 1 WHERE id = ?",
                        account.name(), requestId));
        // A request from somebody no tenant holds is SATISFIED, not stuck — tenantsErased may
        // legitimately be zero. The count is recorded in the log and nowhere the subject can read
        // it (T-31-09-05).
        log.info("event=dsar_fanout_completed request={} tenantsErased={} tenantsScanned={} account={}",
                requestId, tenantsErased, tenantsScanned, account);
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
                                + "last_error = ?, subject_email_ciphertext = NULL WHERE id = ?",
                        error, requestId);
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
