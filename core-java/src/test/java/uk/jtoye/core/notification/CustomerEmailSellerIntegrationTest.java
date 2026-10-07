package uk.jtoye.core.notification;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * #789 (durable medium, CCR 2013 reg 16) and #785 (D-08: the email is the customer's record of
 * the order's allergens), proven through the REAL {@link OrderStateChangeListener} on Postgres 15
 * with every migration applied and the connection role downgraded to NOSUPERUSER, so FORCE
 * row-level security on {@code shops}, {@code trader_identity}, {@code orders} and
 * {@code order_items} is genuinely enforced.
 *
 * <p><b>Why the mail sender is mocked rather than Mailhog.</b> The existing
 * {@code OrderNotificationListenerIntegrationTest} SKIPS when Mailhog is unreachable, so on a
 * machine without the compose stack it proves nothing while reporting green. This class captures
 * the {@link MimeMessage} the service builds, serialises it to RFC 5322 bytes and parses it back —
 * the same bytes an SMTP server would receive — and never skips. The Mailhog landing capture is
 * owed to 31.1-30, which rebuilds the shared stack.
 *
 * <p><b>Why through the listener.</b> The send is {@code @Async}: its thread carries no
 * TenantContext and no tenant GUC, so a seller lookup made there reads ZERO rows under FORCE RLS
 * and would send a seller block with nothing in it (31.1-RESEARCH Pitfall 8). The listener is the
 * only place the tenant is pinned, so that is where the context must be built, and only a test
 * that runs the listener under real RLS can tell the two apart.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class CustomerEmailSellerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String PLATFORM_FROM = "orders@platform.jtoye.test";
    static final String REGISTERED_OFFICE = "1 Fixture Lane, Testtown, TT1 1TT";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        // The test profile turns the channel off; this class is about what it sends.
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> PLATFORM_FROM);
        registry.add("notification.email.tracking-base-url", () -> "https://shop.jtoye.test");
        registry.add("jtoye.platform.registered-office", () -> REGISTERED_OFFICE);
        // The sender is a mock (no SMTP server); Boot's mail health contributor needs a real one.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    static final String SHOP_NAME = "Mama Adé's Kitchen";
    static final String SHOP_EMAIL = "kitchen@x.example.com";
    static final String CUSTOMER_EMAIL = "customer-789@buyer.example.com";
    static final String ALLERGY_NOTE = "My son has a severe sesame allergy";

    /** Text the 31.1-24 seller block shows (frontend/components/storefront/seller-block.tsx). */
    static final String PLATFORM_NOT_SELLER =
            "J'Toye is the ordering platform. Your contract for this order is with the seller named here.";
    static final String CANCELLATION =
            "Your order is freshly prepared food, which is liable to deteriorate rapidly, so the 14-day right "
                    + "to cancel under the Consumer Contracts Regulations 2013 (regulation 28(1)(c)) does not apply. "
                    + "This does not affect your rights if the food is faulty or not as described.";

    @MockitoBean private JavaMailSender mailSender;

    @Autowired private OrderStateChangeListener listener;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        Mockito.reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername()).isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses RLS")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1 (tracer): the order-received email names the seller ------------------------------

    @Test
    @DisplayName("#789/#785: the order-received email comes From '{shop} via J'Toye', Reply-To the shop, "
            + "and names the seller, the platform, the cancellation position and the allergen record")
    void orderReceivedEmailNamesTheSeller() throws Exception {
        Fixture f = seedStorefrontOrder(SHOP_EMAIL, "STOREFRONT", 65);

        listener.handleOrderStateChange(event(f, OrderStatus.DRAFT, OrderStatus.PENDING));

        Object sent = awaitSingleSend();
        assertThat(sent)
                .as("the order email is a MimeMessage, so it can carry the shop's From name and Reply-To")
                .isInstanceOf(MimeMessage.class);
        MimeMessage msg = reparse((MimeMessage) sent);

        InternetAddress from = (InternetAddress) msg.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(PLATFORM_FROM);
        assertThat(from.getPersonal()).isEqualTo(SHOP_NAME + " via J'Toye");
        assertThat(addresses(msg.getReplyTo())).containsExactly(SHOP_EMAIL);
        assertThat(addresses(msg.getRecipients(Message.RecipientType.TO))).containsExactly(CUSTOMER_EMAIL);
        assertThat(msg.getSubject()).isEqualTo("Order " + f.orderNumber + " — Received");

        String body = (String) msg.getContent();
        assertThat(body)
                .contains("Who you are buying from")
                .contains("Seller: Mama Ade Foods Ltd (Registered company)")
                .contains("Company number: 09876543")
                .contains("VAT number: GB123456789")
                .contains("Address: 12 Market Street, Birmingham, B1 1AA")
                .contains("Email: " + SHOP_EMAIL)
                .contains("Phone: 0121 496 0000")
                .contains(PLATFORM_NOT_SELLER)
                .contains(CANCELLATION)
                .contains("You confirmed you had read: Gluten, Milk")
                .contains("Recorded on your order: Gluten, Milk")
                .contains("Check — Puff Puff: the ingredients list mentions Eggs")
                .contains("J'Toye Digital Ltd")
                .contains("16471464")
                .contains("Registered office: " + REGISTERED_OFFICE)
                .contains("https://shop.jtoye.test/track?order=" + f.orderNumber);
        assertThat(body)
                .as("the seller is the shop's legal entity; J'Toye no longer signs as if it were the seller")
                .doesNotContain("— J'Toye")
                .doesNotContain("We've received");
        assertThat(body)
                .as("31.1-13: a customer surface never echoes the allergy note text")
                .doesNotContain(ALLERGY_NOTE);
    }

    // ---- helpers ------------------------------------------------------------------------------

    record Fixture(UUID tenantId, UUID shopId, UUID orderId, String orderNumber) {
    }

    OrderStateChangeEvent event(Fixture f, OrderStatus from, OrderStatus to) {
        return new OrderStateChangeEvent(f.orderId, f.tenantId, f.orderNumber, from, to, OffsetDateTime.now());
    }

    /** Waits for the @Async send, then lets any second send land before asserting there was one. */
    Object awaitSingleSend() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<Invocation> sends = sends();
        while (sends.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            sends = sends();
        }
        assertThat(sends).as("the listener sent the customer email (waited 10 s for the @Async send)").isNotEmpty();
        Thread.sleep(500);
        sends = sends();
        assertThat(sends).as("exactly one customer email per transition").hasSize(1);
        return sends.get(0).getArgument(0);
    }

    List<Invocation> sends() {
        return Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .toList();
    }

    /** The bytes an SMTP server would receive, parsed back: headers are read as a recipient sees them. */
    static MimeMessage reparse(MimeMessage built) throws Exception {
        built.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        built.writeTo(out);
        return new MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
                new ByteArrayInputStream(out.toByteArray()));
    }

    static List<String> addresses(Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return java.util.Arrays.stream(addresses).map(a -> ((InternetAddress) a).getAddress()).toList();
    }

    Fixture seedStorefrontOrder(String shopEmail, String placedVia, Integer ackMask) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "789-email-" + tenant);
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                        + "address_line2, address_city, address_postcode, vat_number) "
                        + "VALUES (?::uuid, ?::uuid, ?, 'COMPANY', '12 Market Street', NULL, 'Birmingham', "
                        + "'B1 1AA', 'GB123456789')",
                UUID.randomUUID().toString(), tenant.toString(), "Mama Ade Foods Ltd"));
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO vendor_onboarding (id, tenant_id, model, company_number) "
                        + "VALUES (?::uuid, ?::uuid, 'MARKETPLACE', '09876543')",
                UUID.randomUUID().toString(), tenant.toString()));
        UUID shop = UUID.randomUUID();
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, "
                        + "email, phone) VALUES (?::uuid, ?::uuid, ?, ?, 'Premises Address', true, 0, ?, ?)",
                shop.toString(), tenant.toString(), SHOP_NAME, "email-789-" + shop, shopEmail, "0121 496 0000"));
        UUID order = UUID.randomUUID();
        String number = "ORD-789-" + order.toString().substring(0, 8);
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, placed_via, allergen_ack_mask, allergen_ack_at, allergy_note, fulfilment_type, "
                        + "created_at, updated_at) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'PENDING', 'Persona Customer', ?, 1000, 'STANDARD', "
                        + "167, 1000, 0, 2, ?, ?, CASE WHEN ?::int IS NULL THEN NULL ELSE now() END, ?, 'COLLECTION', "
                        + "now(), now())",
                order.toString(), tenant.toString(), shop.toString(), number, CUSTOMER_EMAIL, placedVia,
                ackMask, ackMask, ALLERGY_NOTE));
        seedLine(tenant, order, "Jollof Rice", 65, 0);
        // Puff Puff declares nothing, but its emphasised ingredients name EGGS: an advisory flag (bit 2).
        seedLine(tenant, order, "Puff Puff", 0, 1 << 2);
        return new Fixture(tenant, shop, order, number);
    }

    void seedLine(UUID tenant, UUID order, String name, int mask, int flagMask) {
        UUID product = UUID.randomUUID();
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?::uuid, ?::uuid, ?, ?, 'fixture')",
                product.toString(), tenant.toString(), "SKU-" + product, name));
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO order_items (id, tenant_id, order_id, product_id, product_name, quantity, "
                        + "unit_price_pennies, total_price_pennies, allergen_mask, allergen_flag_mask) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 1, 500, 500, ?, ?)",
                UUID.randomUUID().toString(), tenant.toString(), order.toString(), product.toString(), name,
                mask, flagMask));
    }

    interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T inTenant(UUID tenant, ConnectionWork<T> work) {
        return new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                    return work.run(connection);
                }));
    }

    static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
