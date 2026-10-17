package uk.jtoye.core.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerController.UpdateCustomerRequest;
import uk.jtoye.core.exception.ResourceInUseException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.SqlStateExtractor;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for customer management operations.
 * All operations are automatically tenant-scoped via RLS policies, and narrowed
 * to granted shops for non-{@code allShops} callers (Phase 39-08, Option C).
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
    private final ShopAccessService shopAccessService;

    public CustomerService(CustomerRepository customerRepository,
                           CustomerMapper customerMapper,
                           ShopAccessService shopAccessService) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.shopAccessService = shopAccessService;
    }

    private boolean hasAllShopsAccess() {
        return shopAccessService.isGroupAdmin() || shopAccessService.hasAllShopsAccess();
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

        UUID originShopId = request.originShopId();
        if (originShopId != null) {
            shopAccessService.require(originShopId, ShopRole.SHOP_MANAGER);
        } else if (!hasAllShopsAccess()) {
            originShopId = shopAccessService.defaultManagedShopId().orElse(null);
        }

        log.debug("Creating customer for tenant {}: name={}, email={}, originShopId={}",
                tenantId, request.name(), request.email(), originShopId);

        // Create customer entity
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setOriginShopId(originShopId);
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
        customer.setUpdatedAt(OffsetDateTime.now());

        // Save customer. QA-council cluster P1 (API-3 rider): saveAndFlush, not save — createdAt
        // is a @CreationTimestamp generated at FLUSH time, so a bare save() leaves it null in
        // memory until commit and the DTO built below would report createdAt: null even though
        // the row persists with a real timestamp.
        customer = customerRepository.saveAndFlush(customer);

        log.info("Created customer {} with email '{}', allergen restrictions: {}",
                customer.getId(), customer.getEmail(), customer.getAllergenRestrictions());

        return customerMapper.toDto(customer);
    }

    /**
     * Get customer by ID (tenant-scoped, and narrowed to granted shops for non-allShops callers).
     * Not cached due to privacy concerns and frequent updates.
     */
    @Transactional(readOnly = true)
    public Optional<CustomerDto> getCustomerById(UUID customerId) {
        log.debug("Fetching customer by ID: {}", customerId);
        if (hasAllShopsAccess()) {
            return customerRepository.findById(customerId)
                    .map(customerMapper::toDto);
        }
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Optional.empty();
        }
        return customerRepository.findByIdAndTenantScopedInGrantSet(customerId, tenantId, granted)
                .map(customerMapper::toDto);
    }

    /**
     * Get all customers (tenant-scoped, pageable, narrowed to granted shops for non-allShops callers).
     * Returns customers sorted by creation date (newest first by default).
     */
    @Transactional(readOnly = true)
    public Page<CustomerDto> getAllCustomers(Pageable pageable) {
        log.debug("Fetching all customers with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        if (hasAllShopsAccess()) {
            return customerRepository.findAll(pageable)
                    .map(customerMapper::toDto);
        }
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return customerRepository.findTenantScopedInGrantSet(tenantId, granted, pageable)
                .map(customerMapper::toDto);
    }

    /**
     * Update an existing customer (tenant-scoped, narrowed to granted shops for non-allShops callers).
     * RLS ensures we can only update customers belonging to our tenant; Option C narrowing returns
     * 404 when the customer ID is outside a non-allShops caller's grant set.
     * Automatically updates the updatedAt timestamp.
     */
    public CustomerDto updateCustomer(UUID customerId, UpdateCustomerRequest request) {
        log.debug("Updating customer {}: name={}, email={}",
                customerId, request.name(), request.email());

        Customer customer = findScopedCustomerOrThrow(customerId);

        // Update customer fields
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
        customer.setUpdatedAt(OffsetDateTime.now());

        // Save with flush to ensure immediate persistence
        try {
            customer = customerRepository.saveAndFlush(customer);
        } catch (OptimisticLockingFailureException ex) {
            throw vanishedMidTransaction(customerId, ex);
        }

        log.info("Updated customer {} with email '{}', allergen restrictions: {}",
                customer.getId(), customer.getEmail(), customer.getAllergenRestrictions());

        return customerMapper.toDto(customer);
    }

    private Customer findScopedCustomerOrThrow(UUID customerId) {
        if (hasAllShopsAccess()) {
            return customerRepository.findById(customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
        }
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        return customerRepository.findByIdAndTenantScopedInGrantSet(customerId, tenantId, granted)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
    }

    /**
     * Delete customer by ID (tenant-scoped).
     * RLS ensures we can only delete customers belonging to our tenant.
     * Note: This fails with a typed 409 ({@link uk.jtoye.core.exception.ResourceInUseException})
     * if the customer has associated orders (foreign key constraint {@code fk_orders_customer}) —
     * QA-council cluster P2 (API-2/FE-4).
     */
    public void deleteCustomer(UUID customerId) {
        log.debug("Deleting customer {}", customerId);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        try {
            customerRepository.delete(customer);
            // Issue #486: flush HERE rather than letting the DELETE go at commit. Without it the
            // row-count failure is raised after this method returns, outside any catch we could
            // write, and the request ends as an untyped error from the transaction boundary.
            customerRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            throw vanishedMidTransaction(customerId, ex);
        } catch (DataIntegrityViolationException ex) {
            // QA-council cluster P2 (API-2/FE-4): a genuine 23503 (still-referenced-by-an-order)
            // used to surface as the blanket 409 "Duplicate Entry" from GlobalExceptionHandler's
            // generic DataIntegrityViolationException mapping — misleading, since nothing was
            // duplicated. Translate it to the typed, distinct 409 here.
            if ("23503".equals(SqlStateExtractor.sqlState(ex).orElse(null))) {
                throw new ResourceInUseException(
                        "Customer cannot be deleted: still referenced by an existing order",
                        SqlStateExtractor.constraintName(ex).orElse(null));
            }
            throw ex;
        }

        log.info("Deleted customer {} with email '{}'", customer.getId(), customer.getEmail());
    }

    /**
     * Issue #486 — the same defect class as #390 (promotions/announcements), at the customer
     * endpoints. The row was there when we read it and gone when we wrote it, so Hibernate's
     * row-count check failed ({@code Batch update returned unexpected row count from update [0]
     * ... delete from customers where id=?}). That is a missing resource, not a server fault and
     * not a conflict worth retrying: a client that re-reads finds nothing and a client that
     * retries gets the same answer forever.
     *
     * <p>Reachable by ordinary concurrent vendor activity — two staff deleting the same customer,
     * a double-click, a retry after a slow response, a GDPR erasure landing mid-edit.
     *
     * <p><strong>Why 404 is unconditionally right here.</strong> {@link Customer} carries no JPA
     * {@code @Version}, so the statement's predicate is {@code id = ?} alone. Zero affected rows
     * can therefore mean exactly one thing — no such row is visible to this transaction. It cannot
     * mean "stale version", which is the case {@code GlobalExceptionHandler}'s 409 handler exists
     * for; that handler stays in place for {@code @Version}-carrying entities ({@code Product},
     * {@code Shop}, {@code Order}, {@code MediaAsset}) and is not weakened here.
     *
     * <p>The message is byte-identical to the absent-at-read-time 404 above, so a caller cannot
     * distinguish the two and no timing information leaks. The provider message names the table
     * and is logged, never returned (same reasoning as the 409 handler).
     */
    private ResourceNotFoundException vanishedMidTransaction(UUID customerId,
                                                             OptimisticLockingFailureException ex) {
        log.info("Customer {} was removed by another transaction before this write landed: {}",
                customerId, ex.getMessage());
        return new ResourceNotFoundException("Customer not found: " + customerId);
    }
}
