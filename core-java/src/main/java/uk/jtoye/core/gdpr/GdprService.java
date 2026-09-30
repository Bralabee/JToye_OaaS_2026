package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewPhotoKeys;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.UserDirectoryRepository;
import uk.jtoye.core.storage.StorageService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service implementing UK GDPR data subject rights:
 * - Article 20: Right to Data Portability (export)
 * - Article 17: Right to Erasure (anonymisation)
 *
 * Erasure anonymises PII rather than deleting records, preserving
 * referential integrity for financial/order audit trails.
 */
@Service
@Transactional
public class GdprService {
    private static final Logger log = LoggerFactory.getLogger(GdprService.class);

    private static final String ANONYMISED = "[REDACTED]";
    private static final String ANONYMISED_EMAIL = "redacted@erased.invalid";

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final StorageService storageService;
    private final ErasureRecordRepository erasureRecordRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    /**
     * A NEW transaction for the post-commit photo-count write. Inside an {@code afterCommit} hook the
     * erasure's transaction has committed but its synchronization is still active, so a
     * default-propagation write would join that dead transaction and be silently lost — the same
     * reason {@code KeycloakDeprovisionService.deprovision} is {@code REQUIRES_NEW}.
     */
    private final TransactionTemplate postCommitTransaction;

    public GdprService(CustomerRepository customerRepository,
                       OrderRepository orderRepository,
                       ReviewRepository reviewRepository,
                       StorageService storageService,
                       ErasureRecordRepository erasureRecordRepository,
                       UserDirectoryRepository userDirectoryRepository,
                       PlatformTransactionManager transactionManager) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.storageService = storageService;
        this.erasureRecordRepository = erasureRecordRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.postCommitTransaction = new TransactionTemplate(transactionManager);
        this.postCommitTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Export all personal data held for a customer (Article 20).
     * Returns structured data suitable for JSON download.
     */
    @Transactional(readOnly = true)
    public GdprController.DataExportResponse exportCustomerData(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        List<Order> orders = orderRepository.findByCustomerId(customerId);
        List<Review> reviews = reviewRepository.findByTenantIdAndCustomerEmail(
                customer.getTenantId(), customer.getEmail());

        var customerData = new GdprController.CustomerExport(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAllergenRestrictions(),
                customer.getNotes(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );

        var orderExports = orders.stream().map(o -> new GdprController.OrderExport(
                o.getId(),
                o.getOrderNumber(),
                o.getStatus().name(),
                o.getCustomerName(),
                o.getCustomerEmail(),
                o.getSubtotalPennies(),
                o.getVatAmountPennies(),
                o.getDeliveryFeePennies(),
                o.getTotalAmountPennies(),
                o.getPaymentMethod(),
                o.getNotes(),
                o.getCreatedAt()
        )).toList();

        var reviewExports = reviews.stream().map(r -> new GdprController.ReviewExport(
                r.getId(),
                r.getFoodRating(),
                r.getDeliveryRating(),
                r.getComment(),
                r.getCreatedAt()
        )).toList();

        log.info("GDPR data export for customer {} — {} orders, {} reviews",
                customerId, orderExports.size(), reviewExports.size());

        return new GdprController.DataExportResponse(
                customerId,
                OffsetDateTime.now(),
                customerData,
                orderExports,
                reviewExports
        );
    }

    /**
     * Erase (anonymise) all personal data for a customer (Article 17).
     *
     * <p>Completeness (Issue #84 [P1-2]):
     * <ol>
     *   <li><b>Guest reachability</b> — anonymises BOTH customer_id-linked orders AND
     *       guest storefront orders (customer_id NULL) that share the subject's email,
     *       de-duplicated by order id. The email sweep is the line that reaches guest
     *       orders which a customer_id-only walk misses.</li>
     *   <li><b>Only the review's own photos, never the catalogue (#771)</b> — review photo URLs
     *       are client-supplied and were never validated, so a customer could name the shop's
     *       product, gallery, logo, banner or media images, or another customer's review photo, and
     *       have this erasure delete them. Every URL is still detached from the review, but only a
     *       URL whose key is {@code <erasing tenant>/reviews/<that review's orderId>/<plain name>}
     *       ({@link ReviewPhotoKeys}, an allow-list, so it fails closed) is ever scheduled for
     *       deletion. Everything else is RETAINED — not deleted, not counted — and the retained
     *       count is logged at WARN with the record id. This holds for rows written before
     *       creation-time validation existed, because it does not depend on it.</li>
     *   <li><b>Photo cleanup, only after commit (#764)</b> — the eligible review photo URLs are
     *       collected in the transaction; each photo is physically deleted from Azure Blob via
     *       {@link StorageService#delete} (idempotent, WARN-and-continue) only once the erasure has
     *       COMMITTED. Object storage is not transactional, so deleting inside the transaction
     *       destroyed the photos of every erasure that then rolled back. Only a photo that call
     *       actually removed is counted (code review WR-02): review photo URLs are
     *       client-supplied, so an external URL, another tenant's URL (refused by D-09), an
     *       already-absent object or a failed delete is NOT a deletion and is never recorded as
     *       one. Those are counted separately and logged at WARN, because the {@link ErasureRecord}
     *       is the Article 17 evidence row and must not claim an erasure that did not happen. The
     *       record is written in the transaction with a count of 0 (true at commit) and the real
     *       count is written onto it once, after the deletions, in its own transaction.</li>
     *   <li><b>Audit scrub</b> — scrubs pre-erasure PII from the append-only Envers
     *       {@code orders_aud}/{@code customers_aud} history via tenant-scoped native
     *       UPDATEs (deliberate Article-17 exception; Envers stays enabled).</li>
     *   <li><b>Staff directory (WR-10, Phase 23)</b> — removes the subject's
     *       {@code user_directory} rows for the tenant, matched by {@code tenant_id + email}
     *       (that table is keyed by vendor-staff {@code user_id}, with no {@code Customer}
     *       join). Zero matches is the normal case and never a failure; there is no
     *       {@code _aud} mirror to scrub (D-09).</li>
     *   <li><b>Reviews in this tenant only (#764)</b> — the review lookup carries an explicit
     *       tenant predicate because {@code reviews_tenant_read} shows PUBLISHED reviews across
     *       tenants, so an email-only lookup would reach another tenant's review.</li>
     *   <li><b>Durable record</b> — persists exactly one PII-free {@link ErasureRecord}
     *       (SHA-256 email hash, never plaintext) as proof the erasure occurred.</li>
     * </ol>
     * Records are anonymised rather than deleted to preserve financial audit trails.
     *
     * @return the outcome; its photo count is readable only once the photo step has run, i.e. after
     *         the transaction this call runs in has committed — see {@link ErasureOutcome}
     */
    public ErasureOutcome eraseCustomerData(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        // Capture up front — tenantId drives the native _aud scrub WHERE clauses
        // (explicit tenant scoping, not just RLS), and the email is needed for the
        // guest-order sweep + the durable-record hash before we overwrite it.
        String originalEmail = customer.getEmail();
        UUID tenantId = customer.getTenantId();

        // Anonymise customer record
        customer.setName(ANONYMISED);
        customer.setEmail(ANONYMISED_EMAIL + "." + customerId.toString().substring(0, 8));
        customer.setPhone(null);
        customer.setNotes(null);
        customer.setAllergenRestrictions(0);
        customer.setUpdatedAt(OffsetDateTime.now());
        customerRepository.save(customer);

        // Order sweep: merge customer_id-linked orders with email-matched guest orders,
        // de-duplicated by order id so an order reachable both ways is counted once.
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        for (Order order : orderRepository.findByCustomerId(customerId)) {
            ordersById.put(order.getId(), order);
        }
        for (Order order : orderRepository.findByCustomerEmailOrderByCreatedAtDesc(originalEmail)) {
            ordersById.put(order.getId(), order);
        }
        for (Order order : ordersById.values()) {
            order.setCustomerName(ANONYMISED);
            order.setCustomerEmail(null);
            order.setCustomerPhone(null);
            order.setNotes(null);
            // Delivery address is PII (V45) — Article-17 erasure must null it on
            // the live row too; the matching orders_aud scrub runs below.
            order.setAddressLine1(null);
            order.setAddressLine2(null);
            order.setAddressCity(null);
            order.setAddressPostcode(null);
            order.setUpdatedAt(OffsetDateTime.now());
        }
        int ordersAnonymised = ordersById.size();
        orderRepository.saveAll(new ArrayList<>(ordersById.values()));

        // Anonymise PII on this tenant's reviews and COLLECT their photo URLs. Nothing is deleted
        // from storage here: object storage cannot roll back, so the deletion waits for commit.
        List<Review> reviews = reviewRepository.findByTenantIdAndCustomerEmail(tenantId, originalEmail);
        int reviewsAnonymised = 0;
        List<String> photoUrlsToDelete = new ArrayList<>();
        int photoUrlsDetached = 0;
        int retainedNotReviewPhoto = 0;
        int retainedCatalogueReferenced = 0;
        for (Review review : reviews) {
            List<String> photoUrls = review.getPhotoUrls();
            if (photoUrls != null) {
                for (String url : photoUrls) {
                    if (url == null) {
                        continue;
                    }
                    photoUrlsDetached++;
                    // #771: only a photo under THIS review's own order path, in the erasing tenant,
                    // is ever deleted. The key comes from the same parse delete(url) acts on.
                    Optional<String> key = storageService.publicKeyOf(url);
                    if (key.isEmpty() || !ReviewPhotoKeys.isReviewPhotoKey(key.get(), tenantId, review.getOrderId())) {
                        retainedNotReviewPhoto++;
                        continue;
                    }
                    photoUrlsToDelete.add(url);
                }
            }
            review.setCustomerName(ANONYMISED);
            review.setCustomerEmail(ANONYMISED_EMAIL);
            review.setComment(null);
            review.setPhotoUrls(null);
            reviewsAnonymised++;
        }
        reviewRepository.saveAll(reviews);

        // Scrub pre-erasure PII from the Envers audit history. @Modifying(flushAutomatically)
        // flushes the live-entity changes above first, so the post-erasure audit rows are
        // already redacted; these tenant-scoped UPDATEs then scrub the pre-erasure rows.
        int audRowsScrubbed = orderRepository.scrubOrdersAudit(tenantId, customerId, originalEmail, ANONYMISED)
                + customerRepository.scrubCustomerAudit(tenantId, customerId, ANONYMISED);

        // WR-10 (Phase 23): the user_directory grant-target cache carries staff email PII
        // introduced by V52 — before it, this data did not exist in the platform. It is keyed
        // (tenant_id, user_id), a vendor-staff identity space with no natural Customer join,
        // so match on tenant_id + email (mirroring the guest-order email sweep above). Zero
        // matches is the NORMAL case (a storefront customer is usually not a staff user) and
        // is NOT an erasure failure — the ErasureRecord accounting (orders/reviews/aud/photos)
        // is unaffected. No _aud mirror exists (D-09), so a straight tenant-scoped DELETE is
        // the complete erasure — there is no audit history to scrub.
        int directoryRowsErased = userDirectoryRepository.deleteByTenantIdAndEmail(tenantId, originalEmail);

        // Durable, PII-free proof of erasure — SHA-256 hex of the email, never plaintext. Written
        // HERE, atomically with the anonymisation, so the evidence row can never be lost while the
        // data was erased. photos_deleted is 0, which is literally true at commit: no photo has
        // been deleted yet. The post-commit step writes the real count once.
        String subjectEmailSha256 = sha256Hex(originalEmail);
        String erasedBy = resolveErasedBy();
        OffsetDateTime erasedAt = OffsetDateTime.now();
        ErasureRecord record = erasureRecordRepository.save(new ErasureRecord(
                tenantId, customerId, subjectEmailSha256,
                ordersAnonymised, reviewsAnonymised, audRowsScrubbed, 0,
                erasedBy, erasedAt));

        log.info("GDPR erasure for customer {} — {} orders, {} reviews anonymised, "
                        + "{} audit rows scrubbed, {} review photo URL(s) detached, {} eligible for deletion "
                        + "after commit, {} retained as not this review's photo, {} retained as referenced by "
                        + "the catalogue, {} directory rows erased; record {}",
                customerId, ordersAnonymised, reviewsAnonymised, audRowsScrubbed, photoUrlsDetached,
                photoUrlsToDelete.size(), retainedNotReviewPhoto, retainedCatalogueReferenced,
                directoryRowsErased, record.getId());
        if (retainedNotReviewPhoto > 0 || retainedCatalogueReferenced > 0) {
            // #771: detached from the review but NOT deleted, and never counted in the record. No URL
            // is logged — the URLs are client-supplied.
            log.warn("GDPR erasure record {} — {} review photo URL(s) retained as not this review's photo "
                            + "and {} retained as referenced by the catalogue; detached, not deleted (#771)",
                    record.getId(), retainedNotReviewPhoto, retainedCatalogueReferenced);
        }

        PhotoErasureTally photos = new PhotoErasureTally();
        schedulePhotoErasure(tenantId, customerId, record.getId(), List.copyOf(photoUrlsToDelete), photos);

        return new ErasureOutcome(customerId, erasedAt, ordersAnonymised, reviewsAnonymised,
                audRowsScrubbed, record.getId(), photos);
    }

    /**
     * Run the photo step when the surrounding transaction COMMITS, or inline when there is no
     * transaction synchronization at all (the {@code TenantCacheEvictor} idiom). A rollback never
     * reaches {@code afterCommit}, so a failed erasure deletes nothing.
     */
    private void schedulePhotoErasure(UUID tenantId, UUID customerId, UUID recordId,
                                      List<String> urls, PhotoErasureTally tally) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    erasePhotosAfterCommit(tenantId, customerId, recordId, urls, tally);
                }
            });
        } else {
            erasePhotosAfterCommit(tenantId, customerId, recordId, urls, tally);
        }
    }

    /**
     * Delete the erased reviews' photos and record, once, how many the store really removed.
     *
     * <p>The erasure HAS committed when this runs, so nothing here may surface as a failed erasure
     * (the {@code TenantLifecycleService} offboard-hook precedent): every failure is logged at ERROR
     * with the record id and swallowed. The durable count can then under-claim, never over-claim
     * (WR-02). Logs carry counts and ids only — no email and no URL (URLs are client-supplied).
     */
    private void erasePhotosAfterCommit(UUID tenantId, UUID customerId, UUID recordId,
                                        List<String> urls, PhotoErasureTally tally) {
        Optional<UUID> callerTenant = TenantContext.get();
        int deleted = 0;
        int notDeleted = 0;
        try {
            // D-09 in StorageService.delete compares each key's tenant with TenantContext and fails
            // closed without one, so pin the ERASING tenant explicitly rather than trusting whatever
            // the committing thread happens to carry.
            TenantContext.set(tenantId);
            for (String url : urls) {
                // WR-02: count what the store actually removed, not what was attempted.
                if (storageService.delete(url)) {
                    deleted++;
                } else {
                    notDeleted++;
                }
            }
            if (deleted > 0) {
                final int count = deleted;
                // REQUIRES_NEW (see postCommitTransaction). TenantContext is still set inside this
                // template, so TenantSetLocalAspect pins the tenant GUC for the UPDATE.
                Integer updated = postCommitTransaction.execute(status ->
                        erasureRecordRepository.recordPhotosDeleted(recordId, tenantId, count));
                if (updated == null || updated != 1) {
                    log.error("GDPR erasure record {} — the post-commit photo count ({} deleted, {} not deleted) "
                                    + "updated {} rows, expected 1; the record under-claims",
                            recordId, deleted, notDeleted, updated);
                }
            }
            log.info("GDPR erasure for customer {} — {} review photo(s) deleted after commit; record {}",
                    customerId, deleted, recordId);
            if (notDeleted > 0) {
                // The URLs are nulled on the review either way; this says how many stored photos the
                // record does NOT vouch for (external, refused cross-tenant, already absent, or failed).
                log.warn("GDPR erasure for customer {} — {} review photo URL(s) were NOT deleted from storage "
                                + "(external, refused by the tenant guard, already absent, or a store failure) "
                                + "and are not counted in record {}",
                        customerId, notDeleted, recordId);
            }
        } catch (RuntimeException e) {
            log.error("GDPR erasure record {} — post-commit photo step failed after {} deletion(s): {}",
                    recordId, deleted, e.getClass().getName());
        } finally {
            tally.settle(deleted, notDeleted);
            if (callerTenant.isPresent()) {
                TenantContext.set(callerTenant.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * Erase every customer in ONE tenant whose address matches a DSAR subject digest
     * (Phase 31, plan 31-09 — {@link DsarFanoutWorker}'s per-tenant unit of work).
     *
     * <p><b>This is a lookup, not a second erasure routine.</b> The erasure itself is
     * {@link #eraseCustomerData(UUID)} above, unchanged and unbypassed — which matters because
     * V42's tenant-scoped UPDATE policies on {@code orders_aud}/{@code customers_aud} were written
     * for exactly that routine, and a parallel implementation would diverge from the policies that
     * permit its audit scrub. All this method adds is the step {@code eraseCustomerData} cannot do:
     * it is keyed by {@code customerId}, and a data subject arrives as a hash.
     *
     * <p><b>Why a scan rather than an indexed lookup.</b> The plaintext address is never stored on
     * {@code dsar_request} (V62), so there is nothing to pass to {@code findByEmail} — matching is
     * digest to digest. The comparison is performed in Java through {@link DsarSubjectDigest}, the
     * single implementation the public intake also uses, so agreement between the two sides is
     * structural rather than a written rule two files can drift away from. The alternative — a
     * server-side {@code encode(sha256(convert_to(lower(btrim(email)), 'UTF8')), 'hex')} — was
     * rejected on measurement, not taste: {@code btrim} and {@code String.trim()} strip different
     * character sets, and {@code lower()} follows the database collation while
     * {@code toLowerCase(Locale.ROOT)} does not. A divergence there matches NOTHING and reports
     * success, which is the failure mode the whole DSAR path is built to avoid.
     *
     * <p><b>Tenant scoping.</b> The projection carries an explicit {@code tenant_id} predicate AND
     * runs under FORCE row-level security with the GUC pinned by the caller. The fan-out's reach
     * comes from iterating tenants, never from a query that ignores the wall.
     *
     * <p>Each erasure's review photos are deleted when the caller's per-tenant transaction COMMITS
     * (#764), not when this method returns — which is why the outcomes are not read here.
     *
     * @param tenantId           the tenant currently pinned by the caller
     * @param subjectEmailSha256 the subject digest from {@code dsar_request}
     * @return how many customers were erased in this tenant — usually 0 or 1, since
     *         {@code uq_customers_tenant_email} makes an address unique per tenant
     */
    public int eraseSubjectByDigest(UUID tenantId, String subjectEmailSha256) {
        int erased = 0;
        for (Object[] row : customerRepository.findIdAndEmailByTenantId(tenantId)) {
            String email = (String) row[1];
            if (email == null || !DsarSubjectDigest.of(email).equals(subjectEmailSha256)) {
                continue;
            }
            eraseCustomerData((UUID) row[0]);
            erased++;
        }
        if (erased > 0) {
            // The subject digest is one-way and the tenant id is not personal data; neither the
            // address nor any name is logged.
            log.info("DSAR fan-out erased {} customer(s) for tenant {}", erased, tenantId);
        }
        return erased;
    }

    /**
     * What an erasure did. Accessor names match {@link GdprController.ErasureResponse}, and
     * {@link #toResponse()} builds that unchanged API record.
     *
     * <p>The photo count lives in {@link PhotoErasureTally}, which is settled only when the photo step
     * has run — after the transaction commits. The admin controller calls {@link #toResponse()} after
     * the proxied transactional call returns, so it is settled there. A caller still INSIDE an
     * enclosing transaction gets an {@link IllegalStateException} rather than a false 0.
     */
    public record ErasureOutcome(
            UUID customerId,
            OffsetDateTime erasedAt,
            int ordersAnonymised,
            int reviewsAnonymised,
            int auditRowsScrubbed,
            UUID recordId,
            PhotoErasureTally photos
    ) {
        /** Photos the store really removed (WR-02); throws until the post-commit step has run. */
        public int photosDeleted() {
            return photos.deleted();
        }

        /** The unchanged API response; throws until the post-commit photo step has run. */
        public GdprController.ErasureResponse toResponse() {
            return new GdprController.ErasureResponse(customerId, erasedAt, ordersAnonymised,
                    reviewsAnonymised, auditRowsScrubbed, photosDeleted(), recordId);
        }
    }

    /**
     * The WR-02 photo tally of one erasure, settled exactly once by the post-commit photo step.
     * Reading it before then throws: the only honest answer before commit is "not yet known".
     */
    public static final class PhotoErasureTally {
        private volatile boolean settled;
        private volatile int deleted;
        private volatile int notDeleted;

        public PhotoErasureTally() {
        }

        public void settle(int deleted, int notDeleted) {
            if (settled) {
                throw new IllegalStateException("photo erasure tally already settled");
            }
            this.deleted = deleted;
            this.notDeleted = notDeleted;
            this.settled = true;
        }

        public boolean isSettled() {
            return settled;
        }

        public int deleted() {
            requireSettled();
            return deleted;
        }

        public int notDeleted() {
            requireSettled();
            return notDeleted;
        }

        private void requireSettled() {
            if (!settled) {
                throw new IllegalStateException("review photo deletion runs after the enclosing transaction "
                        + "commits, so the count is not known yet; read the durable erasure record instead");
            }
        }
    }

    /**
     * Resolve the acting principal for the durable record; falls back to "system"
     * when no authentication is present (e.g. an internal/batch invocation).
     */
    private String resolveErasedBy() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "system";
    }

    /** Lowercase hex SHA-256 of the input — a one-way digest, never reversible to PII. */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
