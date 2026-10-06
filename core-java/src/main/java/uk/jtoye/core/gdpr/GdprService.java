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
import uk.jtoye.core.media.MediaAssetRepository;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    /** #771: the catalogue reference check — an object a live catalogue row points at is never deleted. */
    private final MediaAssetRepository mediaAssetRepository;
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
                       MediaAssetRepository mediaAssetRepository,
                       PlatformTransactionManager transactionManager) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.storageService = storageService;
        this.erasureRecordRepository = erasureRecordRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.mediaAssetRepository = mediaAssetRepository;
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
                o.getAllergyNote(),
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
     *       deletion, and then only when no product, shop or media asset row in the tenant still
     *       references that object ({@link MediaAssetRepository#countCatalogueReferences}, an
     *       independent second layer: a vendor may point the catalogue at any URL). Everything else
     *       is RETAINED — not deleted, not counted — and both retained counts are logged at WARN
     *       with the record id. This holds for rows written before creation-time validation
     *       existed, because it does not depend on it.</li>
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
        return eraseCustomer(customer, Set.of());
    }

    /**
     * Anonymise one {@code customers} row, then everything else of the subject's in its tenant through
     * {@link #anonymiseSubjectInTenant}. {@code additionalSpellings} are further stored spellings of the
     * same address (#777: the DSAR fan-out finds them by digest); the admin erasure passes none, so its
     * behaviour is exactly what it was.
     */
    private ErasureOutcome eraseCustomer(Customer customer, Set<String> additionalSpellings) {
        UUID customerId = customer.getId();
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

        // The customer's own address first, so its orders_aud rows are scrubbed by the original
        // (customer_id OR email) statement and are not counted again under another spelling.
        Set<String> spellings = new LinkedHashSet<>();
        spellings.add(originalEmail);
        spellings.addAll(additionalSpellings);
        // The record hash stays the RAW email, as every admin erasure has always written it; changing it
        // would silently change the meaning of historic rows (see the 31.1-02 summary observation).
        return anonymiseSubjectInTenant(tenantId, customerId, spellings, sha256Hex(originalEmail));
    }

    /**
     * The one anonymisation routine behind BOTH Article-17 entry points (#777, D-02): the admin
     * {@link #eraseCustomerData(UUID)} and the DSAR fan-out's {@link #eraseSubjectByDigest}. V42's
     * tenant-scoped UPDATE policies on {@code orders_aud}/{@code customers_aud} were written for this
     * routine, so a second implementation would drift from the policies that permit its audit scrub.
     *
     * <p>Only PII columns change. Order rows, their items, amounts, VAT fields and the
     * {@code financial_transactions} ledger are kept: tax records have their own retention (D-02).
     *
     * <p>Every address lookup carries an explicit {@code tenant_id} predicate as well as running under the
     * pinned tenant's FORCE row-level security (#764). An order reachable both by {@code customer_id} and
     * by an address, or by two spellings, is updated once.
     *
     * <p>No {@code @Transactional} here: a private method is not proxied. The caller's transaction (the
     * class-level one for the admin path, the worker's per-tenant {@code TransactionTemplate} for the
     * fan-out) is the boundary.
     *
     * @param customerIdOrNull the erased customer, or {@code null} for a guest subject — then a record is
     *                         written only if an order or a review actually changed
     * @param emailSpellings   every stored spelling of the subject's address to sweep, exact-match each
     * @param recordDigest     the {@code subject_email_sha256} for the evidence row
     * @return the outcome; for a guest pass that changed nothing, {@code recordId} is {@code null} and no
     *         record was written
     */
    private ErasureOutcome anonymiseSubjectInTenant(UUID tenantId, UUID customerIdOrNull,
                                                    Set<String> emailSpellings, String recordDigest) {
        // Order sweep: merge customer_id-linked orders with email-matched guest orders,
        // de-duplicated by order id so an order reachable both ways is counted once.
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        if (customerIdOrNull != null) {
            for (Order order : orderRepository.findByCustomerId(customerIdOrNull)) {
                ordersById.put(order.getId(), order);
            }
        }
        for (String spelling : emailSpellings) {
            for (Order order : orderRepository.findByTenantIdAndCustomerEmail(tenantId, spelling)) {
                ordersById.put(order.getId(), order);
            }
        }
        for (Order order : ordersById.values()) {
            order.setCustomerName(ANONYMISED);
            order.setCustomerEmail(null);
            order.setCustomerPhone(null);
            order.setNotes(null);
            // D-15 / T-31.1-47: the allergy note is the subject's own text and may be health data.
            // Erased here and in orders_aud (scrubOrdersAudit*). The acknowledgement who/when stay:
            // they record a member of staff reading it, not data about the subject.
            order.setAllergyNote(null);
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
        Map<UUID, Review> reviewsById = new LinkedHashMap<>();
        for (String spelling : emailSpellings) {
            for (Review review : reviewRepository.findByTenantIdAndCustomerEmail(tenantId, spelling)) {
                reviewsById.put(review.getId(), review);
            }
        }
        List<Review> reviews = new ArrayList<>(reviewsById.values());
        if (customerIdOrNull == null && ordersAnonymised == 0 && reviews.isEmpty()) {
            // A guest pass that matched no row changes nothing and leaves no evidence row: there is no
            // erasure to evidence, and the fan-out must not count this tenant as erased (D-02).
            PhotoErasureTally none = new PhotoErasureTally();
            none.settle(0, 0);
            return new ErasureOutcome(null, OffsetDateTime.now(), 0, 0, 0, null, none);
        }
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
                    // Second, independent layer: an object the tenant's catalogue still references is
                    // catalogue content, whatever path it lives under. Deleting it would break a live
                    // product or shop image — the #771 harm — so it is kept.
                    if (mediaAssetRepository.countCatalogueReferences(tenantId, key.get()) > 0) {
                        retainedCatalogueReferenced++;
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
        // already redacted; these tenant-scoped UPDATEs then scrub the pre-erasure rows. For a
        // customer, the first spelling is their own address and goes through the original
        // (customer_id OR email) statement; that nulls customer_email on every row it scrubs, so a
        // further spelling's by-address scrub cannot count the same row twice.
        int audRowsScrubbed = 0;
        boolean customersOwnSpelling = customerIdOrNull != null;
        for (String spelling : emailSpellings) {
            audRowsScrubbed += customersOwnSpelling
                    ? orderRepository.scrubOrdersAudit(tenantId, customerIdOrNull, spelling, ANONYMISED)
                    : orderRepository.scrubOrdersAuditByEmail(tenantId, spelling, ANONYMISED);
            customersOwnSpelling = false;
        }
        if (customerIdOrNull != null) {
            audRowsScrubbed += customerRepository.scrubCustomerAudit(tenantId, customerIdOrNull, ANONYMISED);
        }

        // WR-10 (Phase 23): the user_directory grant-target cache carries staff email PII
        // introduced by V52 — before it, this data did not exist in the platform. It is keyed
        // (tenant_id, user_id), a vendor-staff identity space with no natural Customer join,
        // so match on tenant_id + email (mirroring the guest-order email sweep above). Zero
        // matches is the NORMAL case (a storefront customer is usually not a staff user) and
        // is NOT an erasure failure — the ErasureRecord accounting (orders/reviews/aud/photos)
        // is unaffected. No _aud mirror exists (D-09), so a straight tenant-scoped DELETE is
        // the complete erasure — there is no audit history to scrub.
        int directoryRowsErased = 0;
        for (String spelling : emailSpellings) {
            directoryRowsErased += userDirectoryRepository.deleteByTenantIdAndEmail(tenantId, spelling);
        }

        // Durable, PII-free proof of erasure — SHA-256 hex of the email, never plaintext. Written
        // HERE, atomically with the anonymisation, so the evidence row can never be lost while the
        // data was erased. photos_deleted is 0, which is literally true at commit: no photo has
        // been deleted yet. The post-commit step writes the real count once.
        String erasedBy = resolveErasedBy();
        OffsetDateTime erasedAt = OffsetDateTime.now();
        ErasureRecord record = erasureRecordRepository.save(new ErasureRecord(
                tenantId, customerIdOrNull, recordDigest,
                ordersAnonymised, reviewsAnonymised, audRowsScrubbed, 0,
                erasedBy, erasedAt));

        // "customer null" is a guest subject (#777); no address and no name is ever logged.
        log.info("GDPR erasure for customer {} — {} orders, {} reviews anonymised, "
                        + "{} audit rows scrubbed, {} review photo URL(s) detached, {} eligible for deletion "
                        + "after commit, {} retained as not this review's photo, {} retained as referenced by "
                        + "the catalogue, {} directory rows erased; record {}",
                customerIdOrNull, ordersAnonymised, reviewsAnonymised, audRowsScrubbed, photoUrlsDetached,
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
        schedulePhotoErasure(tenantId, customerIdOrNull, record.getId(), List.copyOf(photoUrlsToDelete), photos);

        return new ErasureOutcome(customerIdOrNull, erasedAt, ordersAnonymised, reviewsAnonymised,
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
     * Who the subject of a DSAR digest is in ONE tenant: every {@code customers} row and every stored
     * spelling of an address on {@code customers}, {@code orders} and {@code reviews} whose
     * {@link DsarSubjectDigest} equals the digest (#777, D-02). Read-only; also the lookup the ACCESS
     * export (31.1-16) reuses, so both rights find exactly the same rows.
     *
     * <p><b>Why a scan rather than an indexed lookup.</b> The plaintext address is never stored on
     * {@code dsar_request} (V62), so there is nothing to pass to an email finder — matching is digest to
     * digest. The comparison is performed in Java through {@link DsarSubjectDigest}, the single
     * implementation the public intake also uses, so agreement between the two sides is structural
     * rather than a written rule two files can drift away from. The alternative — a server-side
     * {@code encode(sha256(convert_to(lower(btrim(email)), 'UTF8')), 'hex')} — was rejected on
     * measurement, not taste: {@code btrim} and {@code String.trim()} strip different character sets,
     * and {@code lower()} follows the database collation while {@code toLowerCase(Locale.ROOT)} does
     * not. A divergence there matches NOTHING and reports success, which is the failure mode the whole
     * DSAR path is built to avoid.
     *
     * <p><b>Why every spelling.</b> The finders are exact-match and nothing normalises a stored address,
     * so "Grace@x" on one guest order and " grace@X " on another are two strings for one subject, and
     * each must be swept.
     *
     * <p><b>Tenant scoping.</b> Every projection carries an explicit {@code tenant_id} predicate AND runs
     * under FORCE row-level security with the GUC pinned by the caller. The fan-out's reach comes from
     * iterating tenants, never from a query that ignores the wall. The sets are sorted, so the result does
     * not depend on the order rows come back in.
     *
     * @param tenantId      the tenant currently pinned by the caller
     * @param subjectDigest the subject digest from {@code dsar_request}
     */
    public SubjectMatch matchSubjectInTenant(UUID tenantId, String subjectDigest) {
        Set<UUID> customerIds = new TreeSet<>();
        Set<String> spellings = new TreeSet<>();
        for (Object[] row : customerRepository.findIdAndEmailByTenantId(tenantId)) {
            String email = (String) row[1];
            if (matchesDigest(email, subjectDigest)) {
                customerIds.add((UUID) row[0]);
                spellings.add(email);
            }
        }
        for (String email : orderRepository.findDistinctCustomerEmailsByTenantId(tenantId)) {
            if (matchesDigest(email, subjectDigest)) {
                spellings.add(email);
            }
        }
        for (String email : reviewRepository.findDistinctCustomerEmailsByTenantId(tenantId)) {
            if (matchesDigest(email, subjectDigest)) {
                spellings.add(email);
            }
        }
        return new SubjectMatch(Collections.unmodifiableSet(customerIds), Collections.unmodifiableSet(spellings));
    }

    private static boolean matchesDigest(String email, String subjectDigest) {
        return email != null && DsarSubjectDigest.of(email).equals(subjectDigest);
    }

    /**
     * A DSAR subject in one tenant: the matched {@code customers} ids and every stored spelling of the
     * address (customers, orders and reviews together). Both sets are sorted and unmodifiable.
     */
    public record SubjectMatch(Set<UUID> customerIds, Set<String> emailSpellings) {
        public boolean isEmpty() {
            return customerIds.isEmpty() && emailSpellings.isEmpty();
        }
    }

    /**
     * Erase a DSAR subject in ONE tenant (Phase 31, plan 31-09 — {@link DsarFanoutWorker}'s per-tenant
     * unit of work; #777 extended it from {@code customers} to every storefront subject).
     *
     * <p><b>No second erasure routine.</b> The subject is found by {@link #matchSubjectInTenant}, and
     * everything is anonymised through {@link #anonymiseSubjectInTenant}, the routine the admin
     * {@link #eraseCustomerData(UUID)} uses.
     * <ul>
     *   <li><b>A subject with a {@code customers} row</b> is erased as a customer, exactly as the admin
     *       path does it — record carries the customer id. The first matched customer's erasure also
     *       sweeps every other stored spelling, so the tenant gets ONE record and no order is updated
     *       twice.</li>
     *   <li><b>A subject with no {@code customers} row</b> (every storefront guest — guest checkout never
     *       creates one) is erased by address: order PII, review authorship and their {@code _aud}
     *       history. The record carries a NULL customer id (V68) and the DSAR digest, and is written
     *       ONLY when an order or a review actually changed.</li>
     * </ul>
     * Order rows, items, amounts, VAT fields and the ledger are kept (D-02).
     *
     * <p>Re-running is harmless: the anonymised addresses no longer hash to the digest, so a second
     * sweep matches nothing and writes no second record.
     *
     * <p>Each erasure's review photos are deleted when the caller's per-tenant transaction COMMITS
     * (#764), not when this method returns — which is why the outcomes are not read here.
     *
     * @param tenantId           the tenant currently pinned by the caller
     * @param subjectEmailSha256 the subject digest from {@code dsar_request}
     * @return how many erasure records were written in this tenant — 0 when the tenant holds nothing for
     *         the subject, so the worker's {@code tenantsErased} counts only real erasures
     */
    public int eraseSubjectByDigest(UUID tenantId, String subjectEmailSha256) {
        SubjectMatch match = matchSubjectInTenant(tenantId, subjectEmailSha256);
        int records = 0;
        Set<String> unswept = match.emailSpellings();
        for (UUID customerId : match.customerIds()) {
            Optional<Customer> customer = customerRepository.findById(customerId);
            if (customer.isEmpty()) {
                continue;
            }
            eraseCustomer(customer.get(), unswept);
            unswept = Set.of();
            records++;
        }
        if (!unswept.isEmpty()) {
            ErasureOutcome guest = anonymiseSubjectInTenant(tenantId, null, unswept, subjectEmailSha256);
            if (guest.recordId() != null) {
                records++;
            }
        }
        if (records > 0) {
            // The subject digest is one-way and the tenant id is not personal data; neither the
            // address nor any name is logged.
            log.info("DSAR fan-out wrote {} erasure record(s) for tenant {} ({} customer row(s) matched)",
                    records, tenantId, match.customerIds().size());
        }
        return records;
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
