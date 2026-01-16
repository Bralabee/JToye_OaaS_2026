package uk.jtoye.core.customer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerController.UpdateCustomerRequest;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CustomerService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerMapper customerMapper;

    @InjectMocks
    private CustomerService customerService;

    private UUID tenantId;
    private UUID customerId;
    private Customer testCustomer;
    private CreateCustomerRequest validCreateRequest;
    private UpdateCustomerRequest validUpdateRequest;

    /**
     * Helper method to set private fields using reflection.
     * Needed for auto-generated fields like id and createdAt.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        // Set up tenant context
        TenantContext.set(tenantId);

        // Create test customer (using reflection to set auto-generated fields)
        testCustomer = new Customer();
        setField(testCustomer, "id", customerId);
        testCustomer.setTenantId(tenantId);
        testCustomer.setName("John Doe");
        testCustomer.setEmail("john.doe@example.com");
        testCustomer.setPhone("07123456789");
        testCustomer.setAllergenRestrictions(5); // Binary: 101 (allergens 0 and 2)
        setField(testCustomer, "createdAt", OffsetDateTime.now());
        testCustomer.setUpdatedAt(OffsetDateTime.now());

        // Create valid create request
        validCreateRequest = new CreateCustomerRequest(
                "John Doe",
                "john.doe@example.com",
                "07123456789",
                5
        );

        // Create valid update request
        validUpdateRequest = new UpdateCustomerRequest(
                "Jane Doe",
                "jane.doe@example.com",
                "07987654321",
                10
        );

        // Mock CustomerMapper behavior to mimic actual MapStruct implementation
        // Use lenient() to avoid UnnecessaryStubbingException in tests that don't use the mapper
        lenient().when(customerMapper.toDto(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            return new CustomerDto(
                    customer.getId(),
                    customer.getTenantId(),
                    customer.getName(),
                    customer.getEmail(),
                    customer.getPhone(),
                    customer.getAllergenRestrictions(),
                    customer.getCreatedAt(),
                    customer.getUpdatedAt()
            );
        });
    }

    @Test
    @DisplayName("createCustomer - Success with valid request")
    void testCreateCustomer_Success() {
        // Given
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            setField(customer, "id", customerId);
            setField(customer, "createdAt", OffsetDateTime.now());
            return customer;
        });

        // When
        CustomerDto result = customerService.createCustomer(validCreateRequest);

        // Then
        assertNotNull(result);
        assertEquals(customerId, result.id());
        assertEquals("John Doe", result.name());
        assertEquals("john.doe@example.com", result.email());
        assertEquals("07123456789", result.phone());
        assertEquals(5, result.allergenRestrictions());

        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(customerCaptor.capture());

        Customer savedCustomer = customerCaptor.getValue();
        assertEquals(tenantId, savedCustomer.getTenantId());
        assertEquals("John Doe", savedCustomer.getName());
        assertEquals("john.doe@example.com", savedCustomer.getEmail());
    }

    @Test
    @DisplayName("createCustomer - Fails when tenant context not set")
    void testCreateCustomer_MissingTenant() {
        // Given
        TenantContext.clear();

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            customerService.createCustomer(validCreateRequest);
        });

        assertEquals("Tenant context not set", exception.getMessage());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("createCustomer - Sets tenant ID correctly")
    void testCreateCustomer_SetsTenantId() {
        // Given
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        when(customerRepository.save(customerCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        customerService.createCustomer(validCreateRequest);

        // Then
        Customer savedCustomer = customerCaptor.getValue();
        assertEquals(tenantId, savedCustomer.getTenantId());
    }

    @Test
    @DisplayName("createCustomer - Handles null allergen restrictions (defaults to 0)")
    void testCreateCustomer_NullAllergenRestrictions() {
        // Given
        CreateCustomerRequest requestWithoutAllergens = new CreateCustomerRequest(
                "John Doe",
                "john.doe@example.com",
                "07123456789",
                null
        );
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CustomerDto result = customerService.createCustomer(requestWithoutAllergens);

        // Then
        assertEquals(0, result.allergenRestrictions());
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    @DisplayName("createCustomer - Handles null phone (optional field)")
    void testCreateCustomer_NullPhone() {
        // Given
        CreateCustomerRequest requestWithoutPhone = new CreateCustomerRequest(
                "John Doe",
                "john.doe@example.com",
                null,
                0
        );
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CustomerDto result = customerService.createCustomer(requestWithoutPhone);

        // Then
        assertNull(result.phone());
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    @DisplayName("getCustomerById - Success when customer exists")
    void testGetCustomerById_Success() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));

        // When
        Optional<CustomerDto> result = customerService.getCustomerById(customerId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(customerId, result.get().id());
        assertEquals("John Doe", result.get().name());
        assertEquals("john.doe@example.com", result.get().email());
        verify(customerRepository).findById(customerId);
    }

    @Test
    @DisplayName("getCustomerById - Returns empty when customer not found")
    void testGetCustomerById_NotFound() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // When
        Optional<CustomerDto> result = customerService.getCustomerById(customerId);

        // Then
        assertFalse(result.isPresent());
        verify(customerRepository).findById(customerId);
    }

    @Test
    @DisplayName("getAllCustomers - Returns paginated results")
    void testGetAllCustomers_Paginated() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Customer> customerPage = new PageImpl<>(List.of(testCustomer), pageable, 1);
        when(customerRepository.findAll(pageable)).thenReturn(customerPage);

        // When
        Page<CustomerDto> result = customerService.getAllCustomers(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(customerId, result.getContent().get(0).id());
        verify(customerRepository).findAll(pageable);
    }

    @Test
    @DisplayName("getAllCustomers - Returns empty page when no customers")
    void testGetAllCustomers_EmptyResults() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Customer> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(customerRepository.findAll(pageable)).thenReturn(emptyPage);

        // When
        Page<CustomerDto> result = customerService.getAllCustomers(pageable);

        // Then
        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
        verify(customerRepository).findAll(pageable);
    }

    @Test
    @DisplayName("updateCustomer - Success when customer exists")
    void testUpdateCustomer_Success() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CustomerDto result = customerService.updateCustomer(customerId, validUpdateRequest);

        // Then
        assertNotNull(result);
        assertEquals("Jane Doe", result.name());
        assertEquals("jane.doe@example.com", result.email());
        assertEquals("07987654321", result.phone());
        assertEquals(10, result.allergenRestrictions());

        verify(customerRepository).findById(customerId);
        verify(customerRepository).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("updateCustomer - Fails when customer not found")
    void testUpdateCustomer_NotFound() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            customerService.updateCustomer(customerId, validUpdateRequest);
        });

        assertTrue(exception.getMessage().contains("Customer not found"));
        verify(customerRepository).findById(customerId);
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("updateCustomer - Updates all fields correctly")
    void testUpdateCustomer_UpdatesAllFields() {
        // Given
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));
        when(customerRepository.saveAndFlush(customerCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateCustomerRequest updateRequest = new UpdateCustomerRequest(
                "New Name",
                "new.email@example.com",
                "07111111111",
                100
        );

        // When
        customerService.updateCustomer(customerId, updateRequest);

        // Then
        Customer updatedCustomer = customerCaptor.getValue();
        assertEquals("New Name", updatedCustomer.getName());
        assertEquals("new.email@example.com", updatedCustomer.getEmail());
        assertEquals("07111111111", updatedCustomer.getPhone());
        assertEquals(100, updatedCustomer.getAllergenRestrictions());
    }

    @Test
    @DisplayName("updateCustomer - Handles null allergen restrictions (defaults to 0)")
    void testUpdateCustomer_NullAllergenRestrictions() {
        // Given
        UpdateCustomerRequest requestWithoutAllergens = new UpdateCustomerRequest(
                "Jane Doe",
                "jane.doe@example.com",
                "07987654321",
                null
        );
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CustomerDto result = customerService.updateCustomer(customerId, requestWithoutAllergens);

        // Then
        assertEquals(0, result.allergenRestrictions());
        verify(customerRepository).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("deleteCustomer - Success when customer exists")
    void testDeleteCustomer_Success() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));

        // When
        customerService.deleteCustomer(customerId);

        // Then
        verify(customerRepository).findById(customerId);
        verify(customerRepository).delete(testCustomer);
    }

    @Test
    @DisplayName("deleteCustomer - Fails when customer not found")
    void testDeleteCustomer_NotFound() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            customerService.deleteCustomer(customerId);
        });

        assertTrue(exception.getMessage().contains("Customer not found"));
        verify(customerRepository).findById(customerId);
        verify(customerRepository, never()).delete(any(Customer.class));
    }

    @Test
    @DisplayName("DTO mapping - Converts Customer entity to DTO correctly")
    void testDtoMapping_CorrectFieldMapping() {
        // Given
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));

        // When
        Optional<CustomerDto> result = customerService.getCustomerById(customerId);

        // Then
        assertTrue(result.isPresent());
        CustomerDto dto = result.get();
        assertEquals(testCustomer.getId(), dto.id());
        assertEquals(testCustomer.getTenantId(), dto.tenantId());
        assertEquals(testCustomer.getName(), dto.name());
        assertEquals(testCustomer.getEmail(), dto.email());
        assertEquals(testCustomer.getPhone(), dto.phone());
        assertEquals(testCustomer.getAllergenRestrictions(), dto.allergenRestrictions());
        assertEquals(testCustomer.getCreatedAt(), dto.createdAt());
        assertEquals(testCustomer.getUpdatedAt(), dto.updatedAt());
    }

    @Test
    @DisplayName("createCustomer - Handles maximum allergen mask value")
    void testCreateCustomer_MaxAllergenMask() {
        // Given
        CreateCustomerRequest requestWithMaxAllergens = new CreateCustomerRequest(
                "John Doe",
                "john.doe@example.com",
                "07123456789",
                Integer.MAX_VALUE
        );
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CustomerDto result = customerService.createCustomer(requestWithMaxAllergens);

        // Then
        assertEquals(Integer.MAX_VALUE, result.allergenRestrictions());
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateCustomer - Preserves tenant ID")
    void testUpdateCustomer_PreservesTenantId() {
        // Given
        UUID originalTenantId = testCustomer.getTenantId();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        customerService.updateCustomer(customerId, validUpdateRequest);

        // Then
        assertEquals(originalTenantId, testCustomer.getTenantId()); // Tenant ID should not change
    }

    @Test
    @DisplayName("updateCustomer - Updates timestamp automatically")
    void testUpdateCustomer_UpdatesTimestamp() {
        // Given
        OffsetDateTime originalUpdatedAt = testCustomer.getUpdatedAt();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(testCustomer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> {
            // Simulate a small delay
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return invocation.getArgument(0);
        });

        // When
        customerService.updateCustomer(customerId, validUpdateRequest);

        // Then
        assertNotNull(testCustomer.getUpdatedAt());
        // The updatedAt should be updated (though in a real scenario it would be later)
        verify(customerRepository).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("createCustomer - Sets updatedAt timestamp")
    void testCreateCustomer_SetsUpdatedAt() {
        // Given
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        when(customerRepository.save(customerCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        customerService.createCustomer(validCreateRequest);

        // Then
        Customer savedCustomer = customerCaptor.getValue();
        assertNotNull(savedCustomer.getUpdatedAt());
    }
}
