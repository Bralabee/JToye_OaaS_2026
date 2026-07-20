package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers proof (Issue #84 [P1-2]) that the extended Article-17 erasure is
 * COMPLETE against a real Postgres 15 with Flyway V42 applied:
 *
 * <ul>
 *   <li><b>Guest-order reachability</b> — a guest storefront order (customer_id NULL,
 *       PII only in the denormalised columns) is anonymised via the email sweep. This
 *       is the core acceptance criterion: a customer_id-only walk would never touch it.</li>
 *   <li><b>_aud scrub</b> — the pre-erasure PII that Envers wrote to orders_aud /
 *       customers_aud is gone (no plaintext email / guest name survives).</li>
 *   <li><b>Durable record</b> — exactly one PII-free erasure_records row is persisted.</li>
 * </ul>
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, so it bypasses even
 * FORCE RLS — the scrub UPDATE and the erasure_records INSERT run here without needing
 * the V42 policies; those policies exist for the production NOSUPERUSER app role.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GdprErasureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final UUID TENANT_A =
            UUID.fromString("00000000-0000-0000-0000-000000084001");
    private static final UUID SHOP_A =
            UUID.fromString("00000000-0000-0000-0000-000000084002");
    private static final String SUBJECT_EMAIL = "subject@example.com";
    private static final String SUBJECT_NAME = "Guest Subject";

    @Autowired GdprService gdprService;
    @Autowired CustomerRepository customerRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // SUPERUSER bootstrap role bypasses FORCE RLS, so direct seeding needs no GUC.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Issue 84 Erasure Tenant");
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, 0) ON CONFLICT (id) DO NOTHING",
                SHOP_A, TENANT_A, "Erasure Test Shop", "erasure-test-shop-84");
        // The aspect only emits the tenant GUC inside a transaction; set it for the
        // seeded repository writes and the erasure transaction below.
        TenantContext.set(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Erasure reaches guest-order PII, scrubs _aud, and persists one durable record")
    void erasureReachesGuestPiiAndScrubsAudit() {
        // 1. A customer whose email is shared by a guest order.
        Customer customer = new Customer("Subject Person", SUBJECT_EMAIL);
        customer.setTenantId(TENANT_A);
        customer.setPhone("+447700900111");
        UUID customerId = customerRepository.saveAndFlush(customer).getId();

        // 2. A GUEST order — customer_id NULL, PII only in the denormalised columns.
        //    Committing this write makes Envers persist an orders_aud row holding the PII.
        Order guest = new Order();
        guest.setTenantId(TENANT_A);
        guest.setShopId(SHOP_A);
        guest.setOrderNumber("ORD-GUEST-84");
        guest.setCustomerId(null);
        guest.setCustomerName(SUBJECT_NAME);
        guest.setCustomerEmail(SUBJECT_EMAIL);
        guest.setCustomerPhone("+447700900111");
        guest.setNotes("Leave at the door");
        UUID guestOrderId = orderRepository.saveAndFlush(guest).getId();

        // Pre-condition: the guest order's PII is present in the audit history.
        assertThat(auditRowsWithGuestEmail()).isGreaterThan(0);

        // 3. Erase the subject.
        GdprController.ErasureResponse response = gdprService.eraseCustomerData(customerId);

        // AC1: guest order was reached (email sweep), not just customer_id-linked rows.
        assertThat(response.ordersAnonymised()).isEqualTo(1);
        assertThat(response.recordId()).isNotNull();

        // 5. LIVE guest-order row is anonymised — the core reachability proof.
        Order erased = orderRepository.findById(guestOrderId).orElseThrow();
        assertThat(erased.getCustomerName()).isEqualTo("[REDACTED]");
        assertThat(erased.getCustomerEmail()).isNull();
        assertThat(erased.getCustomerPhone()).isNull();
        assertThat(erased.getNotes()).isNull();

        // 6. _aud scrub: no plaintext PII survives in the audit history.
        assertThat(auditRowsWithGuestEmail())
                .as("orders_aud must retain no row with the subject's email")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_aud WHERE tenant_id = ? AND customer_name = ?",
                Long.class, TENANT_A, SUBJECT_NAME))
                .as("orders_aud must retain no row with the guest's name")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customers_aud WHERE tenant_id = ? AND email = ?",
                Long.class, TENANT_A, SUBJECT_EMAIL))
                .as("customers_aud must retain no row with the subject's email")
                .isZero();

        // 7. Durable, PII-free record persisted exactly once.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM erasure_records WHERE subject_customer_id = ?",
                Long.class, customerId))
                .isEqualTo(1L);
        String hash = jdbcTemplate.queryForObject(
                "SELECT subject_email_sha256 FROM erasure_records WHERE subject_customer_id = ?",
                String.class, customerId);
        assertThat(hash).isNotNull().hasSize(64);
        assertThat(hash).isNotEqualTo(SUBJECT_EMAIL);
    }

    @Test
    @DisplayName("Erasure scrubs the delivery address (PII) from both orders and orders_aud")
    void erasureScrubsDeliveryAddress() {
        // Distinct subject so this test's email sweep can't touch the other test's
        // rows (the class is NOT @Transactional — data persists across methods).
        final String addrEmail = "address-subject@example.com";

        Customer customer = new Customer("Address Subject", addrEmail);
        customer.setTenantId(TENANT_A);
        customer.setPhone("+447700900222");
        UUID customerId = customerRepository.saveAndFlush(customer).getId();

        // A DELIVERY guest order carrying a full UK address (PII, V45). Committing
        // it makes Envers persist an orders_aud row holding the address.
        Order guest = new Order();
        guest.setTenantId(TENANT_A);
        guest.setShopId(SHOP_A);
        guest.setOrderNumber("ORD-GUEST-ADDR-19");
        guest.setCustomerId(null);
        guest.setCustomerName("Address Subject");
        guest.setCustomerEmail(addrEmail);
        guest.setCustomerPhone("+447700900222");
        guest.setAddressLine1("221B Baker Street");
        guest.setAddressLine2("Marylebone");
        guest.setAddressCity("London");
        guest.setAddressPostcode("NW1 6XE");
        UUID guestOrderId = orderRepository.saveAndFlush(guest).getId();

        // Pre-condition: the address is present in the audit history.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_aud WHERE id = ? AND address_postcode = ?",
                Long.class, guestOrderId, "NW1 6XE"))
                .as("orders_aud must initially hold the delivery address")
                .isGreaterThan(0);

        // Erase the subject.
        gdprService.eraseCustomerData(customerId);

        // Live row: every address column is NULL.
        Order erased = orderRepository.findById(guestOrderId).orElseThrow();
        assertThat(erased.getAddressLine1()).isNull();
        assertThat(erased.getAddressLine2()).isNull();
        assertThat(erased.getAddressCity()).isNull();
        assertThat(erased.getAddressPostcode()).isNull();

        // orders_aud: NO surviving row retains any address fragment for this order.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_aud WHERE id = ? AND ("
                        + "address_line1 IS NOT NULL OR address_line2 IS NOT NULL "
                        + "OR address_city IS NOT NULL OR address_postcode IS NOT NULL)",
                Long.class, guestOrderId))
                .as("orders_aud must retain no address PII after erasure")
                .isZero();
    }

    /**
     * WR-10 (Phase 23): the {@code user_directory} grant-target cache carries staff email
     * PII introduced by V52 — before it, this data did not exist in the platform, so
     * {@code GdprService} never touched it. Erasure must now reach it. The table is keyed
     * {@code (tenant_id, user_id)} — a vendor-staff identity space with no natural
     * {@code Customer} join — so erasure matches on {@code tenant_id + email} (mirroring the
     * guest-order email sweep). This test proves the subject's tenant-A directory row is
     * removed while a same-email row in ANOTHER tenant survives (tenant-scoped, isolation
     * intact). Pre-fix (RED): the erasure ignored {@code user_directory}, so the row survived.
     */
    @Test
    @DisplayName("Erasure removes the subject's user_directory row for the tenant, leaving other tenants' rows")
    void erasureRemovesUserDirectoryRowTenantScoped() {
        final String dirEmail = "directory-subject@example.com";
        final UUID tenantAUser = UUID.randomUUID();
        final UUID otherTenant = UUID.fromString("00000000-0000-0000-0000-000000084999");
        final UUID otherTenantUser = UUID.randomUUID();

        // A customer whose email also appears as a staff user_directory row in tenant A.
        Customer customer = new Customer("Directory Subject", dirEmail);
        customer.setTenantId(TENANT_A);
        UUID customerId = customerRepository.saveAndFlush(customer).getId();

        // Seed the tenant-A directory row (the WR-10 PII) + a same-email row in ANOTHER
        // tenant that must survive (cross-tenant isolation). SUPERUSER bypasses FORCE RLS.
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                + "ON CONFLICT (id) DO NOTHING", otherTenant, "Other Directory Tenant");
        jdbcTemplate.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                + "VALUES (?, ?, ?, ?, now())", TENANT_A, tenantAUser, dirEmail, "Directory Subject");
        jdbcTemplate.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                + "VALUES (?, ?, ?, ?, now())", otherTenant, otherTenantUser, dirEmail, "Same Email Other Tenant");

        assertThat(directoryRows(TENANT_A, dirEmail))
                .as("the tenant-A directory row exists pre-erasure").isEqualTo(1L);

        // Erase the subject.
        gdprService.eraseCustomerData(customerId);

        assertThat(directoryRows(TENANT_A, dirEmail))
                .as("the subject's tenant-A user_directory row is erased")
                .isZero();
        assertThat(directoryRows(otherTenant, dirEmail))
                .as("another tenant's same-email directory row is untouched (isolation intact)")
                .isEqualTo(1L);
    }

    /**
     * WR-10 accounting balance: erasing a subject who has NO {@code user_directory} row is
     * the normal case (a storefront customer is usually not a staff user) and MUST NOT be
     * treated as a failure — the erasure completes and returns its durable record as usual.
     */
    @Test
    @DisplayName("Erasure with no matching user_directory row still completes and balances")
    void erasureWithNoDirectoryRowStillBalances() {
        final String noDirEmail = "no-directory-subject@example.com";
        Customer customer = new Customer("No Directory Subject", noDirEmail);
        customer.setTenantId(TENANT_A);
        UUID customerId = customerRepository.saveAndFlush(customer).getId();

        assertThat(directoryRows(TENANT_A, noDirEmail)).as("no directory row exists").isZero();

        GdprController.ErasureResponse response = gdprService.eraseCustomerData(customerId);
        assertThat(response.recordId())
                .as("erasure completes and persists a durable record even with zero directory matches")
                .isNotNull();
    }

    private Long directoryRows(UUID tenant, String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_directory WHERE tenant_id = ? AND email = ?",
                Long.class, tenant, email);
    }

    private Long auditRowsWithGuestEmail() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_aud WHERE tenant_id = ? AND customer_email = ?",
                Long.class, TENANT_A, SUBJECT_EMAIL);
    }
}
