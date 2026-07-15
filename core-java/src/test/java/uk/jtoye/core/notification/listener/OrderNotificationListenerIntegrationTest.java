package uk.jtoye.core.notification.listener;

import org.junit.jupiter.api.Assumptions;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStateChangeListener;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.MailhogAssertions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMMS-02 order-audience landing proof: the NEW {@link OrderNotificationListener}
 * emails the VENDOR ({@code tenants.contact_email}) IN ADDITION to the untouched
 * legacy {@code OrderStateChangeListener → EmailNotificationService} customer
 * email — with NO duplicate customer email. Both messages land in Mailhog.
 *
 * <p>Mirrors {@code OrderStateChangeListenerIdempotencyIntegrationTest}: real
 * Testcontainers Postgres (Flyway schema + RLS), NOSUPERUSER role-downgrade so
 * the listeners' tenant GUC preamble is load-bearing, and the listener methods
 * are invoked directly (the broker is not needed to prove the consumer logic).
 * Email lands in the dev/E2E Mailhog ({@code :1025} SMTP / {@code :8025} API).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderNotificationListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Park the outbox schedules — this class drives the listeners directly.
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        // Route email at the dev Mailhog and turn the channel ON (test profile disables it).
        registry.add("notification.email.enabled", () -> "true");
        registry.add("spring.mail.host", () -> System.getProperty("mailhog.smtp-host", "localhost"));
        registry.add("spring.mail.port", () -> System.getProperty("mailhog.smtp-port", "1025"));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        // Configure the unsubscribe signer so the one-click URL + RFC 8058 header are exercised.
        registry.add("notification.unsubscribe.signing-secret", () -> "integration-test-secret");
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static boolean downgraded = false;

    @Autowired private OrderNotificationListener vendorListener;
    @Autowired private OrderStateChangeListener legacyListener;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final MailhogAssertions mailhog = MailhogAssertions.atDefault();
    private TransactionTemplate txTemplate;

    private UUID tenantId;
    private UUID shopId;
    private UUID orderId;
    private String vendorEmail;
    private String customerEmail;

    @BeforeEach
    void seed() {
        Assumptions.assumeTrue(mailhog.isReachable(),
                "Mailhog (:8025) not reachable — skipping the email-landing proof (dev/E2E stack not up)");
        mailhog.clear();

        txTemplate = new TransactionTemplate(transactionManager);
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        vendorEmail = "vendor-" + UUID.randomUUID() + "@shop.test";
        customerEmail = "customer-" + UUID.randomUUID() + "@buyer.test";

        // tenants has no RLS (V2/V48) — insertable regardless of role. contact_email = the vendor recipient (D-04).
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, contact_email, created_at) VALUES (?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                tenantId, "Tenant OrderNotif " + tenantId.toString().substring(0, 8), vendorEmail);

        if (!downgraded) {
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }

        inTenantTx(() -> {
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                            + "VALUES (?, ?, ?, ?, true, 0, now())",
                    shopId, tenantId, "OrderNotif Shop " + shopId.toString().substring(0, 8),
                    "ordnotif-shop-" + shopId.toString().substring(0, 8));
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                            + "item_count, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', 'Order Customer', ?, "
                            + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                    orderId, tenantId, shopId, "ORD-NOTIF-" + orderId.toString().substring(0, 8), customerEmail);
        });
    }

    private void inTenantTx(Runnable work) {
        TenantContext.set(tenantId);
        try {
            txTemplate.executeWithoutResult(status -> work.run());
        } finally {
            TenantContext.clear();
        }
    }

    private OrderStateChangeEvent event(String number) {
        return new OrderStateChangeEvent(orderId, tenantId, number,
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
    }

    @Test
    @DisplayName("the NEW order path emails the VENDOR only — no customer email (no duplicate with the legacy path)")
    void newVendorPath_isVendorOnly() {
        vendorListener.handleOrderNotification(event("ORD-VENDOR-ONLY"));

        mailhog.awaitMessage(vendorEmail, "an update", TIMEOUT);
        // The new path is vendor-only; after the vendor mail lands (synchronous send),
        // there must be no customer mail from this path.
        mailhog.assertNoMessageTo(customerEmail);
    }

    @Test
    @DisplayName("COMMS-02: customer (legacy path) + vendor (new path) both land; customer is NOT duplicated")
    void orderAudience_customerAndVendor_noDuplicate() {
        OrderStateChangeEvent event = event("ORD-BOTH-AUD");

        // Legacy path → customer email ("Confirmed"); new path → vendor email ("an update").
        legacyListener.handleOrderStateChange(event);
        vendorListener.handleOrderNotification(event);

        mailhog.awaitMessage(customerEmail, "Confirmed", TIMEOUT);
        mailhog.awaitMessage(vendorEmail, "an update", TIMEOUT);

        assertThat(mailhog.messagesTo(customerEmail, null))
                .as("the customer receives EXACTLY ONE order email (legacy path only — no duplicate)")
                .hasSize(1);
    }
}
