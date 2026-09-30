package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.security.access.UserDirectoryRepository;
import uk.jtoye.core.storage.StorageService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GdprServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private ErasureRecordRepository erasureRecordRepository;
    @Mock
    private UserDirectoryRepository userDirectoryRepository;

    @InjectMocks
    private GdprService gdprService;

    private UUID customerId;
    private UUID tenantId;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        customer = new Customer("Jane Doe", "jane@example.com");
        customer.setPhone("+447700900000");
        customer.setAllergenRestrictions(5);
        customer.setNotes("Prefers extra sauce");
        setId(customer, "id", customerId);
        customer.setTenantId(tenantId);
    }

    @Test
    @DisplayName("Export: returns customer, orders, and reviews")
    void exportCustomerData_returnsFullExport() {
        Order order = new Order();
        order.setOrderNumber("ORD-001");
        order.setStatus(OrderStatus.COMPLETED);
        order.setCustomerName("Jane Doe");
        order.setCustomerEmail("jane@example.com");

        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setFoodRating(5);
        review.setComment("Great food!");

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of(order));
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of(review));

        var result = gdprService.exportCustomerData(customerId);

        assertNotNull(result);
        assertEquals(customerId, result.customerId());
        assertEquals("Jane Doe", result.customer().name());
        assertEquals("jane@example.com", result.customer().email());
        assertEquals("+447700900000", result.customer().phone());
        assertEquals(1, result.orders().size());
        assertEquals("ORD-001", result.orders().get(0).orderNumber());
        assertEquals(1, result.reviews().size());
        assertEquals(5, result.reviews().get(0).foodRating());
    }

    @Test
    @DisplayName("Export: throws when customer not found")
    void exportCustomerData_throwsWhenNotFound() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> gdprService.exportCustomerData(customerId));
    }

    @Test
    @DisplayName("Erasure: reaches guest orders by email, deletes stored photos, scrubs _aud, persists PII-free record")
    void eraseCustomerData_anonymisesAllPii() {
        // A customer-linked order (found via customer_id).
        Order linkedOrder = new Order();
        setId(linkedOrder, "id", UUID.randomUUID());
        linkedOrder.setCustomerId(customerId);
        linkedOrder.setCustomerName("Jane Doe");
        linkedOrder.setCustomerEmail("jane@example.com");
        linkedOrder.setCustomerPhone("+447700900000");
        linkedOrder.setNotes("Special request");

        // A GUEST order — customer_id NULL, only reachable by the email sweep.
        Order guestOrder = new Order();
        setId(guestOrder, "id", UUID.randomUUID());
        guestOrder.setCustomerId(null);
        guestOrder.setCustomerName("Guest Jane");
        guestOrder.setCustomerEmail("jane@example.com");
        guestOrder.setCustomerPhone("+447700900222");
        guestOrder.setNotes("Leave at door");

        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setComment("Great!");
        review.setPhotoUrls(new ArrayList<>(List.of(
                "https://cdn.example.com/1/reviews/a/photo1.jpg",
                "https://cdn.example.com/1/reviews/a/photo2.jpg")));

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of(linkedOrder));
        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("jane@example.com"))
                .thenReturn(List.of(guestOrder));
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of(review));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), eq("jane@example.com"), eq("[REDACTED]")))
                .thenReturn(3);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]")))
                .thenReturn(1);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // Both photos are really removed by the store (WR-02: only true results are counted).
        when(storageService.delete("https://cdn.example.com/1/reviews/a/photo1.jpg")).thenReturn(true);
        when(storageService.delete("https://cdn.example.com/1/reviews/a/photo2.jpg")).thenReturn(true);

        var result = gdprService.eraseCustomerData(customerId);

        assertNotNull(result);
        assertEquals(customerId, result.customerId());
        // Merged distinct set = linked + guest = 2.
        assertEquals(2, result.ordersAnonymised(), "guest order must be reached by the email sweep");
        assertEquals(1, result.reviewsAnonymised());
        assertEquals(4, result.auditRowsScrubbed(), "3 orders_aud + 1 customers_aud rows scrubbed");
        assertEquals(2, result.photosDeleted());
        assertNotNull(result.recordId());

        // Customer PII anonymised.
        assertEquals("[REDACTED]", customer.getName());
        assertNull(customer.getPhone());
        assertNull(customer.getNotes());
        assertEquals(0, customer.getAllergenRestrictions());

        // Linked order PII anonymised.
        assertEquals("[REDACTED]", linkedOrder.getCustomerName());
        assertNull(linkedOrder.getCustomerEmail());
        assertNull(linkedOrder.getCustomerPhone());
        assertNull(linkedOrder.getNotes());

        // Guest order PII anonymised (the reachability fix).
        assertEquals("[REDACTED]", guestOrder.getCustomerName());
        assertNull(guestOrder.getCustomerEmail());
        assertNull(guestOrder.getCustomerPhone());
        assertNull(guestOrder.getNotes());

        // Review PII anonymised + photos physically deleted from storage.
        assertEquals("[REDACTED]", review.getCustomerName());
        assertNull(review.getComment());
        assertNull(review.getPhotoUrls());
        verify(storageService).delete("https://cdn.example.com/1/reviews/a/photo1.jpg");
        verify(storageService).delete("https://cdn.example.com/1/reviews/a/photo2.jpg");

        // Native tenant-scoped _aud scrub invoked with the customer's tenant + original email.
        verify(orderRepository).scrubOrdersAudit(tenantId, customerId, "jane@example.com", "[REDACTED]");
        verify(customerRepository).scrubCustomerAudit(tenantId, customerId, "[REDACTED]");

        // Exactly one durable, PII-free erasure record persisted.
        ArgumentCaptor<ErasureRecord> captor = ArgumentCaptor.forClass(ErasureRecord.class);
        verify(erasureRecordRepository, times(1)).save(captor.capture());
        ErasureRecord saved = captor.getValue();
        assertEquals(tenantId, saved.getTenantId());
        assertEquals(customerId, saved.getSubjectCustomerId());
        assertEquals(2, saved.getOrdersAnonymised());
        assertEquals(1, saved.getReviewsAnonymised());
        assertEquals(4, saved.getAudRowsScrubbed());
        assertEquals(2, saved.getPhotosDeleted());
        assertNotNull(saved.getSubjectEmailSha256());
        assertEquals(64, saved.getSubjectEmailSha256().length(), "SHA-256 hex is 64 chars");
        assertNotEquals("jane@example.com", saved.getSubjectEmailSha256(), "must never store plaintext email");
        assertTrue(saved.getSubjectEmailSha256().matches("[0-9a-f]{64}"), "lowercase hex digest");
    }

    @Test
    @DisplayName("Erasure (WR-02): a photo the store did NOT delete is never counted as deleted in the record")
    void eraseCustomerData_countsOnlyPhotosTheStoreActuallyDeleted() {
        // Review photo URLs are client-supplied: one is the tenant's own stored photo, one is
        // another tenant's URL (refused by the D-09 guard), one is external. Only the first is
        // a deletion, and the Article 17 record must say 1, not 3.
        String own = "http://localhost:10000/devstoreaccount1/jtoye-images/" + tenantId + "/reviews/r/own.webp";
        String foreign = "http://localhost:10000/devstoreaccount1/jtoye-images/" + UUID.randomUUID() + "/reviews/r/x.webp";
        String external = "https://cdn.example.com/elsewhere.jpg";
        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setPhotoUrls(new ArrayList<>(List.of(own, foreign, external)));

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("jane@example.com")).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of(review));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), any(), eq("[REDACTED]"))).thenReturn(0);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]"))).thenReturn(0);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(storageService.delete(own)).thenReturn(true);
        when(storageService.delete(foreign)).thenReturn(false);
        when(storageService.delete(external)).thenReturn(false);

        var result = gdprService.eraseCustomerData(customerId);

        // Every URL was attempted, and every URL is cleared from the review either way.
        verify(storageService).delete(own);
        verify(storageService).delete(foreign);
        verify(storageService).delete(external);
        assertNull(review.getPhotoUrls());
        // ...but only the real removal is counted, in the response AND in the durable record.
        assertEquals(1, result.photosDeleted());
        ArgumentCaptor<ErasureRecord> captor = ArgumentCaptor.forClass(ErasureRecord.class);
        verify(erasureRecordRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getPhotosDeleted(),
                "the Article 17 record must not claim the refused and external photos were erased");
    }

    @Test
    @DisplayName("Erasure: throws when customer not found")
    void eraseCustomerData_throwsWhenNotFound() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> gdprService.eraseCustomerData(customerId));
    }

    @Test
    @DisplayName("Erasure: handles customer with no orders or reviews")
    void eraseCustomerData_noOrdersOrReviews() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("jane@example.com")).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), any(), eq("[REDACTED]"))).thenReturn(0);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]"))).thenReturn(0);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = gdprService.eraseCustomerData(customerId);

        assertEquals(0, result.ordersAnonymised());
        assertEquals(0, result.reviewsAnonymised());
        assertEquals(0, result.auditRowsScrubbed());
        assertEquals(0, result.photosDeleted());
        assertEquals("[REDACTED]", customer.getName());
        verify(storageService, never()).delete(any());
        verify(erasureRecordRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Export: includes allergen restrictions")
    void exportCustomerData_includesAllergenData() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());

        var result = gdprService.exportCustomerData(customerId);

        assertEquals(5, result.customer().allergenRestrictions());
    }

    @Test
    @DisplayName("#764: export and erase look reviews up in the CUSTOMER'S tenant, never by email alone")
    void exportAndErase_scopeTheReviewLookupToTheCustomersTenant() {
        // Another tenant's PUBLISHED review under the same email is visible through RLS, so a
        // lookup that dropped the tenant would return it. Stub it under the WRONG tenant: a
        // correctly scoped call never matches this stub and gets Mockito's empty default.
        UUID otherTenant = UUID.randomUUID();
        Review foreign = new Review();
        foreign.setCustomerEmail("jane@example.com");
        foreign.setCustomerName("Jane in another tenant");
        foreign.setComment("not yours to erase");
        lenient().when(reviewRepository.findByTenantIdAndCustomerEmail(otherTenant, "jane@example.com"))
                .thenReturn(List.of(foreign));

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());

        var export = gdprService.exportCustomerData(customerId);
        assertEquals(0, export.reviews().size(), "the export must not carry another tenant's review");

        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("jane@example.com")).thenReturn(List.of());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), any(), eq("[REDACTED]"))).thenReturn(0);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]"))).thenReturn(0);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var erased = gdprService.eraseCustomerData(customerId);

        assertEquals(0, erased.reviewsAnonymised(), "the erasure must not reach another tenant's review");
        assertEquals("Jane in another tenant", foreign.getCustomerName());
        assertEquals("not yours to erase", foreign.getComment());
        verify(reviewRepository, times(2)).findByTenantIdAndCustomerEmail(tenantId, "jane@example.com");
        verify(reviewRepository, never()).findByTenantIdAndCustomerEmail(eq(otherTenant), any());
    }

    // Assign a JPA @GeneratedValue id in a unit test (no setter on the entity).
    private static void setId(Object entity, String field, UUID value) {
        try {
            Field f = entity.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
