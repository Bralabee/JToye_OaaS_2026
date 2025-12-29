package uk.jtoye.core.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Customer entity.
 * All queries are automatically tenant-scoped via RLS policies.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Find customer by email (tenant-scoped).
     * Email is unique per tenant.
     */
    Optional<Customer> findByEmail(String email);

    /**
     * Check if customer with email exists (tenant-scoped).
     */
    boolean existsByEmail(String email);

    /**
     * Find customer by phone (tenant-scoped).
     */
    Optional<Customer> findByPhone(String phone);
}
