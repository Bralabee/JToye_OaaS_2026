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
class OrderEmailReadyByAndCancelReasonIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
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

    // ---- #825: the order emails quote the estimated ready-by time when the shop has a prep time -----

    /** 2030-06-15 17:45 UTC is 18:45 in London (BST) and not today. */
    static final OffsetDateTime SUMMER_ESTIMATE = OffsetDateTime.parse("2030-06-15T17:45:00Z");
    /** 2030-01-15 17:45 UTC is 17:45 in London (GMT). */
    static final OffsetDateTime WINTER_ESTIMATE = OffsetDateTime.parse("2030-01-15T17:45:00Z");

    @Test
    @DisplayName("#825: the order-received email quotes the estimate in UK time (BST), labelled as an estimate")
    void orderReceivedQuotesTheEstimate() throws Exception {
        Fixture f = seedOrder("PENDING", null, SUMMER_ESTIMATE, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.DRAFT, OrderStatus.PENDING));

        String body = body(awaitSingleSend());
        assertThat(body)
                .contains("Estimated ready by 18:45 on 15 June")
                .contains("This is an estimate, not a promise.");
    }

    @Test
    @DisplayName("#825: the accepted (confirmed) email quotes the RESTAMPED estimate, in GMT in winter")
    void confirmedEmailQuotesTheRestampedEstimate() throws Exception {
        Fixture f = seedOrder("CONFIRMED", null, WINTER_ESTIMATE, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CONFIRMED));

        String body = body(awaitSingleSend());
        assertThat(body)
                .contains("Estimated ready by 17:45 on 15 January")
                .contains("This is an estimate, not a promise.");
    }

    @Test
    @DisplayName("#825: a shop with no prep time (no estimate on the order) gets no estimate line, no 'null' and no blank placeholder")
    void noEstimateMeansNoLine() throws Exception {
        Fixture f = seedOrder("PENDING", null, null, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.DRAFT, OrderStatus.PENDING));

        String body = body(awaitSingleSend());
        assertThat(body).doesNotContain("Estimated ready").doesNotContain("estimate").doesNotContain("null");
    }

    @Test
    @DisplayName("#825: a status email that is not about waiting (preparing) does not repeat the estimate")
    void preparingEmailDoesNotQuoteIt() throws Exception {
        Fixture f = seedOrder("PREPARING", null, SUMMER_ESTIMATE, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.CONFIRMED, OrderStatus.PREPARING));

        assertThat(body(awaitSingleSend())).doesNotContain("Estimated ready");
    }

    // ---- #820: the cancellation email says the reason CATEGORY and the shop's phone ---------------

    @Test
    @DisplayName("#820: a shop cancel with CANNOT_FULFIL says so in plain English and gives the shop's phone; never the enum name")
    void cancelledEmailGivesTheReasonCategoryAndThePhone() throws Exception {
        Fixture f = seedOrder("CANCELLED", "CANNOT_FULFIL", null, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CANCELLED));

        String body = body(awaitSingleSend());
        assertThat(body)
                .contains("The shop could not make or deliver this order.")
                .contains("Call " + SHOP_NAME + " on 0121 496 0000")
                .doesNotContain("CANNOT_FULFIL");
    }

    @Test
    @DisplayName("#820: every reason has its own plain-English line, none of them says 'fake', and none leaks its code")
    void everyReasonHasItsOwnPlainEnglish() throws Exception {
        java.util.Map<String, String> expected = java.util.Map.of(
                "SUSPECTED_FAKE", "The shop could not verify this order.",
                "CANNOT_FULFIL", "The shop could not make or deliver this order.",
                "CUSTOMER_REQUEST", "This order was cancelled at your request.",
                "OTHER", "The shop cancelled this order for another reason.");
        for (var entry : expected.entrySet()) {
            Mockito.reset(mailSender);
            when(mailSender.createMimeMessage())
                    .thenAnswer(inv -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
            Fixture f = seedOrder("CANCELLED", entry.getKey(), null, "0121 496 0000");

            listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CANCELLED));

            String body = body(awaitSingleSend());
            assertThat(body).as(entry.getKey()).contains(entry.getValue()).doesNotContain(entry.getKey());
            assertThat(body.toLowerCase(java.util.Locale.ROOT)).as(entry.getKey()).doesNotContain("fake");
        }
    }

    @Test
    @DisplayName("#820: a cancel with no recorded reason names no reason (no 'null'), but still gives the shop's phone")
    void cancelledWithoutAReasonStillGivesThePhone() throws Exception {
        Fixture f = seedOrder("CANCELLED", null, null, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CANCELLED));

        String body = body(awaitSingleSend());
        assertThat(body)
                .contains("has been cancelled")
                .contains("Call " + SHOP_NAME + " on 0121 496 0000")
                .doesNotContain("null")
                .doesNotContain("The shop could not")
                .doesNotContain("at your request");
    }

    @Test
    @DisplayName("#820: a shop with no phone gets the old 'contact the shop directly' sentence and no phone line")
    void cancelledShopWithoutPhone() throws Exception {
        Fixture f = seedOrder("CANCELLED", "OTHER", null, null);

        listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CANCELLED));

        String body = body(awaitSingleSend());
        assertThat(body)
                .contains("The shop cancelled this order for another reason.")
                .contains("please contact " + SHOP_NAME + " directly")
                .doesNotContain("Call " + SHOP_NAME + " on")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("#820: only a CANCELLED email carries a cancellation reason: the confirmed email of an order never says one")
    void onlyTheCancelledEmailCarriesTheReason() throws Exception {
        Fixture f = seedOrder("CONFIRMED", null, null, "0121 496 0000");

        listener.handleOrderStateChange(event(f, OrderStatus.PENDING, OrderStatus.CONFIRMED));

        assertThat(body(awaitSingleSend())).doesNotContain("could not make or deliver").doesNotContain("Call " + SHOP_NAME);
    }

    // ---- helpers ------------------------------------------------------------------------------

    static String body(Object sent) throws Exception {
        return (String) reparse((MimeMessage) sent).getContent();
    }

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

    Fixture seedOrder(String status, String cancelReasonCode, OffsetDateTime estimatedReadyAt, String shopPhone) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "820-email-" + tenant);
        UUID shop = UUID.randomUUID();
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, "
                        + "email, phone) VALUES (?::uuid, ?::uuid, ?, ?, 'Premises Address', true, 0, ?, ?)",
                shop.toString(), tenant.toString(), SHOP_NAME, "email-820-" + shop, SHOP_EMAIL, shopPhone));
        UUID order = UUID.randomUUID();
        String number = "ORD-820-" + order.toString().substring(0, 8);
        inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, placed_via, fulfilment_type, cancel_reason_code, estimated_ready_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'Persona Customer', ?, 1000, 'STANDARD', "
                        + "167, 1000, 0, 1, 'STOREFRONT', 'COLLECTION', ?, ?, now(), now())",
                order.toString(), tenant.toString(), shop.toString(), number, status, CUSTOMER_EMAIL,
                cancelReasonCode, estimatedReadyAt));
        return new Fixture(tenant, shop, order, number);
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
