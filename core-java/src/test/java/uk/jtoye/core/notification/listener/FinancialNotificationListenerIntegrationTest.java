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
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.MailhogAssertions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMMS-02 landing proof for the financial families. Refund had NO consumer
 * (its {@code order.refunded} key matched no binding — discarded); payment was
 * audit-only. Now the {@link FinancialNotificationListener} emails BOTH the
 * customer (order email) and the vendor ({@code tenants.contact_email}) for each,
 * landing two messages in Mailhog per event. Also asserts no
 * {@code payment_event_outbox} row is poisoned (Pitfall 3 — consumers only).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class FinancialNotificationListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        registry.add("notification.email.enabled", () -> "true");
        registry.add("spring.mail.host", () -> System.getProperty("mailhog.smtp-host", "localhost"));
        registry.add("spring.mail.port", () -> System.getProperty("mailhog.smtp-port", "1025"));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("notification.unsubscribe.signing-secret", () -> "integration-test-secret");
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static boolean downgraded = false;

    @Autowired private FinancialNotificationListener listener;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final MailhogAssertions mailhog = MailhogAssertions.atDefault();
    private TransactionTemplate txTemplate;

    private UUID tenantId;
    private UUID shopId;
    private UUID orderId;
    private String vendorEmail;
    private String customerEmail;
    private String orderNumber;

    @BeforeEach
    void seed() {
        Assumptions.assumeTrue(mailhog.isReachable(),
                "Mailhog (:8025) not reachable — skipping the financial landing proof");
        mailhog.clear();

        txTemplate = new TransactionTemplate(transactionManager);
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        vendorEmail = "fin-vendor-" + UUID.randomUUID() + "@shop.test";
        customerEmail = "fin-customer-" + UUID.randomUUID() + "@buyer.test";
        orderNumber = "ORD-FIN-" + orderId.toString().substring(0, 8);

        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, contact_email, created_at) VALUES (?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                tenantId, "Tenant FinNotif " + tenantId.toString().substring(0, 8), vendorEmail);

        if (!downgraded) {
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }

        inTenantTx(() -> {
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                            + "VALUES (?, ?, ?, ?, true, 0, now())",
                    shopId, tenantId, "FinNotif Shop " + shopId.toString().substring(0, 8),
                    "finnotif-shop-" + shopId.toString().substring(0, 8));
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                            + "item_count, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'COMPLETED', 'Fin Customer', ?, "
                            + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                    orderId, tenantId, shopId, orderNumber, customerEmail);
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

    private int poisonRowCount() {
        TenantContext.set(tenantId);
        try {
            Integer n = txTemplate.execute(status -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM payment_event_outbox WHERE poison = true", Integer.class));
            return n == null ? -1 : n;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("a refund event lands BOTH a customer and a vendor email (refund had NO consumer before)")
    void refundEvent_landsCustomerAndVendor() {
        RefundEvent event = new RefundEvent(
                UUID.randomUUID(), orderId, tenantId, orderNumber, "re_1",
                500, "GBP", RefundEvent.RefundEventType.REFUND_SUCCEEDED, "SUCCEEDED", null,
                OffsetDateTime.now());

        listener.handleRefundNotification(event);

        mailhog.awaitMessage(customerEmail, "Refund processed", TIMEOUT);
        mailhog.awaitMessage(vendorEmail, "Refund processed", TIMEOUT);
        assertThat(mailhog.messagesTo(customerEmail, "Refund processed")).hasSize(1);
        assertThat(mailhog.messagesTo(vendorEmail, "Refund processed")).hasSize(1);
        assertThat(poisonRowCount()).as("no payment_event_outbox row poisoned (consumers only)").isZero();
    }

    @Test
    @DisplayName("a payment event lands BOTH a customer and a vendor email (payment was audit-only before)")
    void paymentEvent_landsCustomerAndVendor() {
        PaymentEvent event = new PaymentEvent(
                orderId, tenantId, orderNumber, "pi_1", 1200, "GBP",
                PaymentEvent.PaymentEventType.SUCCEEDED, null, OffsetDateTime.now());

        listener.handlePaymentNotification(event);

        mailhog.awaitMessage(customerEmail, "Payment received", TIMEOUT);
        mailhog.awaitMessage(vendorEmail, "Payment received", TIMEOUT);
        assertThat(mailhog.messagesTo(customerEmail, "Payment received")).hasSize(1);
        assertThat(mailhog.messagesTo(vendorEmail, "Payment received")).hasSize(1);
    }
}
