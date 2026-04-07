package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GdprServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ReviewRepository reviewRepository;

    @InjectMocks
    private GdprService gdprService;

    private UUID customerId;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        customer = new Customer("Jane Doe", "jane@example.com");
        customer.setPhone("+447700900000");
        customer.setAllergenRestrictions(5);
        customer.setNotes("Prefers extra sauce");
        // Use reflection to set id since there's no setter
        try {
            var idField = Customer.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(customer, customerId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        customer.setTenantId(UUID.randomUUID());
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
        when(reviewRepository.findByCustomerEmail("jane@example.com")).thenReturn(List.of(review));

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
    @DisplayName("Erasure: anonymises customer, orders, and reviews")
    void eraseCustomerData_anonymisesAllPii() {
        Order order = new Order();
        order.setCustomerName("Jane Doe");
        order.setCustomerEmail("jane@example.com");
        order.setCustomerPhone("+447700900000");
        order.setNotes("Special request");

        Review review = new Review();
        review.setCustomerEmail("jane@example.com");
        review.setCustomerName("Jane Doe");
        review.setComment("Great!");

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of(order));
        when(reviewRepository.findByCustomerEmail("jane@example.com")).thenReturn(List.of(review));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        var result = gdprService.eraseCustomerData(customerId);

        assertNotNull(result);
        assertEquals(customerId, result.customerId());
        assertEquals(1, result.ordersAnonymised());
        assertEquals(1, result.reviewsAnonymised());

        // Verify customer PII anonymised
        assertEquals("[REDACTED]", customer.getName());
        assertNull(customer.getPhone());
        assertNull(customer.getNotes());
        assertEquals(0, customer.getAllergenRestrictions());

        // Verify order PII anonymised
        assertEquals("[REDACTED]", order.getCustomerName());
        assertNull(order.getCustomerEmail());
        assertNull(order.getCustomerPhone());
        assertNull(order.getNotes());

        // Verify review PII anonymised
        assertEquals("[REDACTED]", review.getCustomerName());
        assertNull(review.getComment());
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
        when(reviewRepository.findByCustomerEmail("jane@example.com")).thenReturn(List.of());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        var result = gdprService.eraseCustomerData(customerId);

        assertEquals(0, result.ordersAnonymised());
        assertEquals(0, result.reviewsAnonymised());
        assertEquals("[REDACTED]", customer.getName());
    }

    @Test
    @DisplayName("Export: includes allergen restrictions")
    void exportCustomerData_includesAllergenData() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(orderRepository.findByCustomerId(customerId)).thenReturn(List.of());
        when(reviewRepository.findByCustomerEmail("jane@example.com")).thenReturn(List.of());

        var result = gdprService.exportCustomerData(customerId);

        assertEquals(5, result.customer().allergenRestrictions());
    }
}
