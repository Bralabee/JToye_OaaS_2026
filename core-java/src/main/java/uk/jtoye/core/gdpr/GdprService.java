package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;

import java.time.OffsetDateTime;
import java.util.List;
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

    public GdprService(CustomerRepository customerRepository,
                       OrderRepository orderRepository,
                       ReviewRepository reviewRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
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
     * Anonymises customer record, order PII, and review PII.
     * Preserves records for financial audit trail with PII stripped.
     */
    public GdprController.ErasureResponse eraseCustomerData(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        String originalEmail = customer.getEmail();

        // Anonymise customer record
        customer.setName(ANONYMISED);
        customer.setEmail(ANONYMISED_EMAIL + "." + customerId.toString().substring(0, 8));
        customer.setPhone(null);
        customer.setNotes(null);
        customer.setAllergenRestrictions(0);
        customer.setUpdatedAt(OffsetDateTime.now());
        customerRepository.save(customer);

        // Anonymise PII on orders
        List<Order> orders = orderRepository.findByCustomerId(customerId);
        int ordersAnonymised = 0;
        for (Order order : orders) {
            order.setCustomerName(ANONYMISED);
            order.setCustomerEmail(null);
            order.setCustomerPhone(null);
            order.setNotes(null);
            order.setUpdatedAt(OffsetDateTime.now());
            ordersAnonymised++;
        }
        orderRepository.saveAll(orders);

        // Anonymise PII on reviews
        List<Review> reviews = reviewRepository.findByCustomerEmail(originalEmail);
        int reviewsAnonymised = 0;
        for (Review review : reviews) {
            review.setCustomerName(ANONYMISED);
            review.setCustomerEmail(ANONYMISED_EMAIL);
            review.setComment(null);
            review.setPhotoUrls(null);
            reviewsAnonymised++;
        }
        reviewRepository.saveAll(reviews);

        log.info("GDPR erasure for customer {} — {} orders, {} reviews anonymised",
                customerId, ordersAnonymised, reviewsAnonymised);

        return new GdprController.ErasureResponse(
                customerId,
                OffsetDateTime.now(),
                ordersAnonymised,
                reviewsAnonymised
        );
    }
}
