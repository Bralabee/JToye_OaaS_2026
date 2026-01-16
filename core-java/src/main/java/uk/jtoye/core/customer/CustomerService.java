package uk.jtoye.core.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerController.UpdateCustomerRequest;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for customer management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 *
 * Note: Customers are NOT cached as they change frequently and contain
 * privacy-sensitive data. Following the caching strategy from AI_CONTEXT.md.
 */
@Service
@Transactional
public class CustomerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
    }

    /**
     * Create a new customer.
     * Automatically assigns tenant from context.
     * Validates required fields (name, email).
     * Email must be unique per tenant (enforced by database constraint).
     */
    public CustomerDto createCustomer(CreateCustomerRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating customer for tenant {}: name={}, email={}",
                tenantId, request.name(), request.email());

        // Create customer entity
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
        customer.setUpdatedAt(OffsetDateTime.now());

        // Save customer
        customer = customerRepository.save(customer);

        log.info("Created customer {} with email '{}', allergen restrictions: {}",
                customer.getId(), customer.getEmail(), customer.getAllergenRestrictions());

        return customerMapper.toDto(customer);
    }

    /**
     * Get customer by ID (tenant-scoped).
     * Not cached due to privacy concerns and frequent updates.
     */
    @Transactional(readOnly = true)
    public Optional<CustomerDto> getCustomerById(UUID customerId) {
        log.debug("Fetching customer by ID: {}", customerId);
        return customerRepository.findById(customerId)
                .map(customerMapper::toDto);
    }

    /**
     * Get all customers (tenant-scoped, pageable).
     * Returns customers sorted by creation date (newest first by default).
     */
    @Transactional(readOnly = true)
    public Page<CustomerDto> getAllCustomers(Pageable pageable) {
        log.debug("Fetching all customers with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        return customerRepository.findAll(pageable)
                .map(customerMapper::toDto);
    }

    /**
     * Update an existing customer (tenant-scoped).
     * RLS ensures we can only update customers belonging to our tenant.
     * Automatically updates the updatedAt timestamp.
     */
    public CustomerDto updateCustomer(UUID customerId, UpdateCustomerRequest request) {
        log.debug("Updating customer {}: name={}, email={}",
                customerId, request.name(), request.email());

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        // Update customer fields
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
        customer.setUpdatedAt(OffsetDateTime.now());

        // Save with flush to ensure immediate persistence
        customer = customerRepository.saveAndFlush(customer);

        log.info("Updated customer {} with email '{}', allergen restrictions: {}",
                customer.getId(), customer.getEmail(), customer.getAllergenRestrictions());

        return customerMapper.toDto(customer);
    }

    /**
     * Delete customer by ID (tenant-scoped).
     * RLS ensures we can only delete customers belonging to our tenant.
     * Note: This may fail if customer has associated orders (foreign key constraint).
     */
    public void deleteCustomer(UUID customerId) {
        log.debug("Deleting customer {}", customerId);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        customerRepository.delete(customer);

        log.info("Deleted customer {} with email '{}'", customer.getId(), customer.getEmail());
    }
}
