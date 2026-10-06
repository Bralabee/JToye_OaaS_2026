package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.AfterEach;
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
import uk.jtoye.core.media.MediaAssetRepository;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.UserDirectoryRepository;
import uk.jtoye.core.storage.StorageService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    // #771: the catalogue reference check. Unstubbed it answers 0 ("not referenced").
    @Mock
    private MediaAssetRepository mediaAssetRepository;
    // A mocked manager lets TransactionTemplate run its callback: getTransaction returns null and
    // commit(null) is a no-op, so the post-commit count write executes inline in these tests.
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private GdprService gdprService;

    private UUID customerId;
    private UUID tenantId;
    private UUID orderId;
    private Customer customer;

    /** The public base URL the StorageService stub parses, as StorageService.publicKeyOf does. */
    private static final String BASE = "http://localhost:10000/devstoreaccount1/jtoye-images";

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
        orderId = UUID.randomUUID();
        P1 = photoUrl("p1.webp");
        P2 = photoUrl("p2.webp");
        P3 = photoUrl("p3.webp");
        // #771: the erasure classifies each URL through StorageService.publicKeyOf. The stub mirrors
        // the real parse (strip BASE + "/", else empty), so an unstubbed mock cannot hand back an
        // empty Optional that would silently make every URL "external".
        lenient().when(storageService.publicKeyOf(anyString())).thenAnswer(i -> {
            String url = i.getArgument(0);
            return url.startsWith(BASE + "/") ? Optional.of(url.substring(BASE.length() + 1)) : Optional.empty();
        });
        TenantContext.clear();
    }

    /** A photo under the subject review's own order path: the only shape erasure may delete (#771). */
    private String photoUrl(String name) {
        return BASE + "/" + tenantId + "/reviews/" + orderId + "/" + name;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
        review.setOrderId(orderId);
        String photo1 = photoUrl("photo1.jpg");
        String photo2 = photoUrl("photo2.jpg");
        review.setPhotoUrls(new ArrayList<>(List.of(photo1, photo2)));

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of(linkedOrder));
        when(orderRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com"))
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
        when(storageService.delete(photo1)).thenReturn(true);
        when(storageService.delete(photo2)).thenReturn(true);

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
        verify(storageService).delete(photo1);
        verify(storageService).delete(photo2);

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
        // The record is written INSIDE the erasure transaction, before any photo is deleted, so it
        // carries 0 at commit; the true count is written once, after the deletions (#764).
        assertEquals(0, saved.getPhotosDeleted());
        verify(erasureRecordRepository).recordPhotosDeleted(saved.getId(), tenantId, 2);
        assertNotNull(saved.getSubjectEmailSha256());
        assertEquals(64, saved.getSubjectEmailSha256().length(), "SHA-256 hex is 64 chars");
        assertNotEquals("jane@example.com", saved.getSubjectEmailSha256(), "must never store plaintext email");
        assertTrue(saved.getSubjectEmailSha256().matches("[0-9a-f]{64}"), "lowercase hex digest");
    }

    @Test
    @DisplayName("Erasure (WR-02): a photo the store did NOT delete is never counted as deleted in the record")
    void eraseCustomerData_countsOnlyPhotosTheStoreActuallyDeleted() {
        // Review photo URLs are client-supplied: one is the review's own photo, one is another
        // tenant's URL, one is external. Only the first is a deletion, and the Article 17 record
        // must say 1, not 3. Since #771 the foreign and external URLs never even reach delete:
        // the erasure allow-list keeps them (D-09 at delete stays as a further layer).
        String own = photoUrl("own.webp");
        String foreign = BASE + "/" + UUID.randomUUID() + "/reviews/" + orderId + "/x.webp";
        String external = "https://cdn.example.com/elsewhere.jpg";
        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setOrderId(orderId);
        review.setPhotoUrls(new ArrayList<>(List.of(own, foreign, external)));

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(orderRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of(review));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), any(), eq("[REDACTED]"))).thenReturn(0);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]"))).thenReturn(0);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(storageService.delete(own)).thenReturn(true);

        var result = gdprService.eraseCustomerData(customerId);

        // Only the review's own photo is attempted (#771), and every URL is cleared from the review.
        verify(storageService).delete(own);
        verify(storageService, never()).delete(foreign);
        verify(storageService, never()).delete(external);
        assertNull(review.getPhotoUrls());
        // ...but only the real removal is counted, in the response AND in the durable record.
        assertEquals(1, result.photosDeleted());
        ArgumentCaptor<ErasureRecord> captor = ArgumentCaptor.forClass(ErasureRecord.class);
        verify(erasureRecordRepository).save(captor.capture());
        // The Article 17 record must not claim the refused and external photos were erased: the
        // count written after the deletions is 1, never 3.
        verify(erasureRecordRepository).recordPhotosDeleted(captor.getValue().getId(), tenantId, 1);
        verify(erasureRecordRepository, never()).recordPhotosDeleted(any(), any(), eq(3));
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
        when(orderRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());
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

        when(orderRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());
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

    // ---- #764: photo deletion runs only after the erasure commits ------------------------------

    // Under the subject review's own order path (set per test in setUp), so they pass the #771
    // erasure allow-list and these tests keep exercising the post-commit step.
    private String P1;
    private String P2;
    private String P3;

    /** A subject with one review carrying {@code urls}; every repository call the erasure makes is stubbed. */
    private Review stubErasureWithOneReview(String... urls) {
        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setComment("Great!");
        review.setOrderId(orderId);
        review.setPhotoUrls(new ArrayList<>(List.of(urls)));
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(orderRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCustomerEmail(tenantId, "jane@example.com")).thenReturn(List.of(review));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.scrubOrdersAudit(eq(tenantId), eq(customerId), any(), eq("[REDACTED]"))).thenReturn(0);
        when(customerRepository.scrubCustomerAudit(eq(tenantId), eq(customerId), eq("[REDACTED]"))).thenReturn(0);
        when(erasureRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return review;
    }

    private ErasureRecord savedRecord() {
        ArgumentCaptor<ErasureRecord> captor = ArgumentCaptor.forClass(ErasureRecord.class);
        verify(erasureRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private static void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    @Test
    @DisplayName("#764: inside a transaction, the erasure deletes NO photo and records 0 until commit")
    void erase_insideATransaction_defersEveryPhotoDelete() {
        stubErasureWithOneReview(P1, P2);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var outcome = gdprService.eraseCustomerData(customerId);

            verify(storageService, never()).delete(any());
            assertEquals(0, savedRecord().getPhotosDeleted(), "nothing has been deleted at commit time");
            assertFalse(outcome.photos().isSettled(), "the tally is not settled before commit");
            IllegalStateException e = assertThrows(IllegalStateException.class, outcome::photosDeleted);
            assertTrue(e.getMessage().contains("after the enclosing transaction commits"), e.getMessage());
            assertThrows(IllegalStateException.class, outcome::toResponse);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("#764: afterCommit deletes each photo under the erasing tenant and writes the TRUE count once")
    void erase_afterCommit_deletesUnderTheTenantAndRecordsTheTrueCountOnce() {
        stubErasureWithOneReview(P1, P2, P3);
        List<Object> tenantsSeenAtDelete = new ArrayList<>();
        when(storageService.delete(any())).thenAnswer(i -> {
            tenantsSeenAtDelete.add(TenantContext.get().orElse(null));
            String url = i.getArgument(0);
            return !P2.equals(url); // WR-02: true, false, true
        });
        TransactionSynchronizationManager.initSynchronization();
        try {
            var outcome = gdprService.eraseCustomerData(customerId);
            verify(storageService, never()).delete(any());

            fireAfterCommit();

            verify(storageService).delete(P1);
            verify(storageService).delete(P2);
            verify(storageService).delete(P3);
            assertEquals(List.of(tenantId, tenantId, tenantId), tenantsSeenAtDelete,
                    "D-09 reads TenantContext: every delete must see the erasing tenant");
            assertEquals(2, outcome.photosDeleted(), "WR-02: only true results are counted");
            assertEquals(1, outcome.photos().notDeleted());
            assertEquals(2, outcome.toResponse().photosDeleted());
            UUID recordId = savedRecord().getId();
            verify(erasureRecordRepository, times(1)).recordPhotosDeleted(recordId, tenantId, 2);
            verify(transactionManager).getTransaction(argThat(def ->
                    def.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("#764: when no photo was actually deleted, the record's count is never written")
    void erase_afterCommit_withNothingDeleted_neverWritesTheCount() {
        stubErasureWithOneReview(P1, P2);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var outcome = gdprService.eraseCustomerData(customerId);
            fireAfterCommit();

            verify(storageService, times(2)).delete(any());
            assertEquals(0, outcome.photosDeleted());
            verify(erasureRecordRepository, never()).recordPhotosDeleted(any(), any(), anyInt());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("#764: a rolled-back erasure deletes no photo")
    void erase_rolledBack_deletesNothing() {
        stubErasureWithOneReview(P1, P2);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var outcome = gdprService.eraseCustomerData(customerId);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            verify(storageService, never()).delete(any());
            verify(erasureRecordRepository, never()).recordPhotosDeleted(any(), any(), anyInt());
            assertThrows(IllegalStateException.class, outcome::photosDeleted);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("#764: the photo step restores the caller's TenantContext (a prior value, or none)")
    void erase_photoStep_restoresTheCallersTenantContext() {
        stubErasureWithOneReview(P1);
        UUID prior = UUID.randomUUID();
        TenantContext.set(prior);
        gdprService.eraseCustomerData(customerId);
        assertEquals(Optional.of(prior), TenantContext.get(), "a prior tenant is put back");

        TenantContext.clear();
        gdprService.eraseCustomerData(customerId);
        assertEquals(Optional.empty(), TenantContext.get(), "no prior tenant stays no tenant");
    }

    @Test
    @DisplayName("#764: a failing count write never escapes the post-commit hook, and the tally stays truthful")
    void erase_afterCommit_countWriteFailure_isContainedAndTallySettled() {
        stubErasureWithOneReview(P1, P2);
        when(storageService.delete(any())).thenReturn(true);
        when(erasureRecordRepository.recordPhotosDeleted(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("simulated count-write failure"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            var outcome = gdprService.eraseCustomerData(customerId);
            assertDoesNotThrow(GdprServiceTest::fireAfterCommit);
            assertEquals(2, outcome.photosDeleted(), "the photos WERE deleted; the tally says so");
            assertEquals(Optional.empty(), TenantContext.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- #771: erasure deletes only a photo under the review's own order path ------------------

    @Test
    @DisplayName("#771: only a photo under the review's own order path ever reaches delete")
    void erase_onlyTheReviewsOwnOrderPathPhotoReachesDelete() {
        String own = photoUrl("own.webp");
        String productShaped = BASE + "/" + tenantId + "/products/" + UUID.randomUUID() + "/p.webp";
        String otherOrder = BASE + "/" + tenantId + "/reviews/" + UUID.randomUUID() + "/p.webp";
        String external = "https://cdn.example.com/elsewhere.jpg";
        Review review = stubErasureWithOneReview(own, productShaped, otherOrder, external);
        when(storageService.delete(own)).thenReturn(true);

        var outcome = gdprService.eraseCustomerData(customerId); // inline: no synchronization active

        verify(storageService, times(1)).delete(any());
        verify(storageService).delete(own);
        assertNull(review.getPhotoUrls(), "every URL is detached from the review, kept or not");
        assertEquals(1, outcome.photosDeleted());
        UUID recordId = savedRecord().getId();
        verify(erasureRecordRepository).recordPhotosDeleted(recordId, tenantId, 1);
    }

    @Test
    @DisplayName("#771: an own-order review photo the catalogue references is retained")
    void erase_ownOrderPhotoTheCatalogueReferences_isRetained() {
        String shared = photoUrl("shared.webp");
        String own = photoUrl("own.webp");
        String sharedKey = tenantId + "/reviews/" + orderId + "/shared.webp";
        stubErasureWithOneReview(shared, own);
        when(mediaAssetRepository.countCatalogueReferences(any(), anyString())).thenReturn(0L);
        when(mediaAssetRepository.countCatalogueReferences(tenantId, sharedKey)).thenReturn(1L);
        when(storageService.delete(own)).thenReturn(true);

        var outcome = gdprService.eraseCustomerData(customerId); // inline: no synchronization active

        verify(storageService, never()).delete(shared);
        verify(storageService, times(1)).delete(own);
        assertEquals(1, outcome.photosDeleted());
        // The reference check runs under the ERASING tenant, for each key that passed the allow-list.
        verify(mediaAssetRepository).countCatalogueReferences(tenantId, sharedKey);
        verify(mediaAssetRepository, never()).countCatalogueReferences(argThat(t -> !tenantId.equals(t)), anyString());
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
