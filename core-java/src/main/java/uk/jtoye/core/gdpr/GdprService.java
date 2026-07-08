package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.storage.StorageService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public GdprService(CustomerRepository customerRepository,
                       OrderRepository orderRepository,
                       ReviewRepository reviewRepository,
                       StorageService storageService,
                       ErasureRecordRepository erasureRecordRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.storageService = storageService;
        this.erasureRecordRepository = erasureRecordRepository;
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
        List<Review> reviews = reviewRepository.findByCustomerEmail(customer.getEmail());

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
     *   <li><b>S3 cleanup</b> — physically deletes each review photo from S3/MinIO via
     *       {@link StorageService#delete} (idempotent, WARN-and-continue) before nulling
     *       the URLs.</li>
     *   <li><b>Audit scrub</b> — scrubs pre-erasure PII from the append-only Envers
     *       {@code orders_aud}/{@code customers_aud} history via tenant-scoped native
     *       UPDATEs (deliberate Article-17 exception; Envers stays enabled).</li>
     *   <li><b>Durable record</b> — persists exactly one PII-free {@link ErasureRecord}
     *       (SHA-256 email hash, never plaintext) as proof the erasure occurred.</li>
     * </ol>
     * Records are anonymised rather than deleted to preserve financial audit trails.
     */
    public GdprController.ErasureResponse eraseCustomerData(UUID customerId) {
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
            order.setUpdatedAt(OffsetDateTime.now());
        }
        int ordersAnonymised = ordersById.size();
        orderRepository.saveAll(new ArrayList<>(ordersById.values()));

        // Anonymise PII on reviews AND physically delete their S3/MinIO photos.
        List<Review> reviews = reviewRepository.findByCustomerEmail(originalEmail);
        int reviewsAnonymised = 0;
        int photosDeleted = 0;
        for (Review review : reviews) {
            List<String> photoUrls = review.getPhotoUrls();
            if (photoUrls != null) {
                for (String url : photoUrls) {
                    storageService.delete(url);
                    photosDeleted++;
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

        // Durable, PII-free proof of erasure — SHA-256 hex of the email, never plaintext.
        String subjectEmailSha256 = sha256Hex(originalEmail);
        String erasedBy = resolveErasedBy();
        OffsetDateTime erasedAt = OffsetDateTime.now();
        ErasureRecord record = erasureRecordRepository.save(new ErasureRecord(
                tenantId, customerId, subjectEmailSha256,
                ordersAnonymised, reviewsAnonymised, audRowsScrubbed, photosDeleted,
                erasedBy, erasedAt));

        log.info("GDPR erasure for customer {} — {} orders, {} reviews anonymised, "
                        + "{} audit rows scrubbed, {} photos deleted; record {}",
                customerId, ordersAnonymised, reviewsAnonymised, audRowsScrubbed, photosDeleted,
                record.getId());

        return new GdprController.ErasureResponse(
                customerId,
                erasedAt,
                ordersAnonymised,
                reviewsAnonymised,
                audRowsScrubbed,
                photosDeleted,
                record.getId()
        );
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
