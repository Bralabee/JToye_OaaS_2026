package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-27 (D-18, RWO-041 / UXT-041): one customer identity cannot flood a shop with open cash
 * orders, proven through the real storefront endpoint with the typed 429 and the facts read back by SQL.
 *
 * <p>The persona run placed many fake cash orders in seconds with throwaway details (UXT-041). Every arm
 * here goes through {@code POST /api/v1/public/shops/{slug}/orders}, the door the flood came through.
 * Real Postgres (Testcontainers): the counts are partial-index queries over the live rows, and the
 * idempotency reservation a refusal must roll back lives in {@code INSERT ... ON CONFLICT}. The class is
 * NOT {@code @Transactional}; each request commits. The test profile has no Stripe key, so every
 * storefront order takes the cash (COD) branch and is exactly the kind of order the cap is about.
 *
 * <p>The caps in force are the application defaults ({@code jtoye.storefront.cash-caps.per-email} 3).
 * An "open" order is PENDING, CONFIRMED, PREPARING or READY; completing one frees a slot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CashOrderCapIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    /**
     * The customer-realm token verifier, stubbed: the IT has no Keycloak. The real bean verifies the
     * signature, issuer, expiry and email_verified (CustomerJwtVerifierTest covers that); what is under
     * test here is what the storefront RECORDS from its answer. {@code VERIFIED_TOKEN} proves
     * {@code VERIFIED_EMAIL}; {@code OTHER_TOKEN} proves another address; any other token is refused
     * with the verifier's uniform 401, exactly as the real bean refuses.
     */
    @MockitoBean CustomerJwtVerifier customerJwtVerifier;

    private static final String VERIFIED_TOKEN = "verified-customer-token";
    private static final String OTHER_TOKEN = "another-customers-token";
    private static final String VERIFIED_EMAIL = "Verified.Buyer@Example.com";

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003727");
    private static final String SHOP_SLUG = "shop-37-27-cash-caps";
    private static final String OTHER_SHOP_SLUG = "shop-37-27-other";
    private static final String STOREFRONT_ENDPOINT = "storefront.orders.create";
    private static final String PROBLEM_TYPE = "https://jtoye.uk/errors/order-cap-exceeded";
    private static final String PROBLEM_CODE = "ORDER_CAP_EXCEEDED";
    private static final String RATE_LIMITED_TYPE = "https://jtoye.uk/errors/rate-limited";
    private static final int PER_EMAIL_CAP = 3;
    private static final int PER_PHONE_CAP = 3;

    private UUID shopId;

    /**
     * 37-28 (D-27): the socket peer every storefront request in an arm is sent from. MockMvc's default is
     * 127.0.0.1 for every request, so across a class run that one "client" held more than per-ip (5)
     * open cash orders and the IP axis refused the email and phone arms here (measured: 7 of 12 red
     * the moment the axis existed). Each arm draws its own documentation-range address (RFC 5737), so
     * only the axis an arm is about can be its refusal — Pitfall 9 for the third axis.
     */
    private static final java.util.concurrent.atomic.AtomicInteger ARM_PEER = new java.util.concurrent.atomic.AtomicInteger();
    private String armPeer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        armPeer = "203.0.113." + (1 + ARM_PEER.getAndIncrement() % 250);
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-27 Cash Caps Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "37-27 Cash Caps Shop");
        // doThrow/doReturn, not when(): a when() on a specific token would INVOKE the mock while the
        // anyString() throw-stub is live and throw during the stubbing itself (measured: 12 / 12 VOID).
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"))
                .when(customerJwtVerifier).verifiedEmail(anyString());
        doReturn(VERIFIED_EMAIL).when(customerJwtVerifier).verifiedEmail(VERIFIED_TOKEN);
        doReturn("someone.else@example.com").when(customerJwtVerifier).verifiedEmail(OTHER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // The per-email cap: three open cash orders are the most one address may hold at a shop; the fourth
    // is a typed 429 that writes nothing and keeps no reservation; completing one frees the slot and the
    // SAME Idempotency-Key then succeeds (the reservation rolled back with the refusal, T-37-67 shape).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-041: a fourth open cash order for the same email (case-folded) at a shop is refused 429 order-cap-exceeded axis EMAIL, writes nothing and no reservation; after one completes the same key is 201")
    void fourthOpenCashOrderForTheSameEmail_isRefusedTyped_andTheSameKeySucceedsOnceOneCompletes() throws Exception {
        UUID jollof = seedProduct("SKU-3727-EMAIL", "Jollof Rice", 1000L);
        String mixedCaseEmail = "Cap.Buyer." + UUID.randomUUID() + "@Example.com";
        String foldedEmail = mixedCaseEmail.toLowerCase(java.util.Locale.ROOT);
        String phone = uniquePhone();

        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "email-open-" + i + "-" + UUID.randomUUID()),
                    storefrontBody(mixedCaseEmail, phone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as("order " + i + " of " + PER_EMAIL_CAP + ": " + responseText(created))
                    .isEqualTo(201);
        }
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).as("three open cash orders are held").isEqualTo(PER_EMAIL_CAP);

        // The fourth, with the SAME address spelled differently: trimmed, lower-cased, it is the same person.
        String fourthKey = "email-fourth-" + UUID.randomUUID();
        MvcResult refused = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", fourthKey),
                storefrontBody(foldedEmail, phone, List.of(line(jollof, 1))));
        JsonNode problem = assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("axis").asString()).as(text).isEqualTo("EMAIL");
        assertThat(problem.path("type").asString()).as("Pitfall 8: a cap is not time-based and must never carry the auto-retried type")
                .isNotEqualTo(RATE_LIMITED_TYPE);
        assertThat(refused.getResponse().getHeader("Retry-After")).as("no Retry-After: nothing elapses, an order completes").isNull();
        assertThat(problem.path("detail").asString()).as(text)
                .isEqualTo("This customer has reached the limit of open cash orders (EMAIL). Try again once one is completed.");

        assertThat(countOrdersByFoldedEmail(foldedEmail)).as("the refused order was not written").isEqualTo(PER_EMAIL_CAP);
        assertThat(countReservationsForKey(fourthKey))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        // One of the three is completed (the kitchen finished it); the slot is free and the client retries the same key.
        completeOneOpenOrder(foldedEmail);
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP - 1);

        MvcResult accepted = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", fourthKey),
                storefrontBody(foldedEmail, phone, List.of(line(jollof, 1))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP + 1);
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP);
    }

    // ------------------------------------------------------------------
    // The per-phone cap, with the number normalised: "07700 900123" and "+44 7700 900123" are one phone.
    // The email is fresh on every order, so only the phone axis can refuse the fourth.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-041: a fourth open cash order for the same phone spelled +44 instead of 0 at a shop is refused 429 order-cap-exceeded axis PHONE; the third (at the cap) is 201")
    void fourthOpenCashOrderForTheSamePhone_normalised_isRefusedOnPhone() throws Exception {
        UUID suya = seedProduct("SKU-3727-PHONE", "Suya", 900L);
        String digits = sixRandomDigits();
        String localSpelling = "07700 " + digits;          // as a UK customer types it
        String internationalSpelling = "+44 7700 " + digits; // the same number, as a phone shows it
        String tag = UUID.randomUUID().toString();

        for (int i = 1; i <= PER_PHONE_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "phone-open-" + i + "-" + tag),
                    storefrontBody("phone-" + i + "-" + tag + "@example.com", localSpelling, List.of(line(suya, 1))));
            assertThat(created.getResponse().getStatus()).as("order " + i + " of " + PER_PHONE_CAP + " (the cap itself is allowed): "
                    + responseText(created)).isEqualTo(201);
        }

        String fourthEmail = "phone-4-" + tag + "@example.com";
        MvcResult refused = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "phone-fourth-" + tag),
                storefrontBody(fourthEmail, internationalSpelling, List.of(line(suya, 1))));
        JsonNode problem = assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE);
        assertThat(problem.path("axis").asString()).as(responseText(refused)).isEqualTo("PHONE");
        assertThat(countOrdersByFoldedEmail(fourthEmail)).as("the refused order was not written").isZero();
    }

    // ------------------------------------------------------------------
    // Normalisation is exact, not a prefix: a different number that shares eleven of twelve digits is a
    // different customer. The false-positive side of the phone axis.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: a different phone number sharing a prefix with three open cash orders is not counted (201)")
    void aDifferentPhoneSharingAPrefix_isNotCounted() throws Exception {
        UUID suya = seedProduct("SKU-3727-PREFIX", "Suya", 900L);
        String tag = UUID.randomUUID().toString();
        int base = java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000, 999_998);
        String held = "07700 " + base;
        String neighbour = "07700 " + (base + 1);

        for (int i = 1; i <= PER_PHONE_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "prefix-" + i + "-" + tag),
                    storefrontBody("prefix-" + i + "-" + tag + "@example.com", held, List.of(line(suya, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }
        MvcResult accepted = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "prefix-4-" + tag),
                storefrontBody("prefix-4-" + tag + "@example.com", neighbour, List.of(line(suya, 1))));
        assertThat(accepted.getResponse().getStatus()).as("a neighbouring number is not this customer: " + responseText(accepted))
                .isEqualTo(201);
    }

    // ------------------------------------------------------------------
    // The cap is per SHOP: the same identity at another shop of the tenant is a different queue.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: an identity holding the cap at one shop can still place a cash order at another shop (201)")
    void sameIdentityAtAnotherShop_isNotCapped() throws Exception {
        UUID otherShopId = seedShopIdempotent(OTHER_SHOP_SLUG, "37-27 Other Shop");
        UUID jollof = seedProduct("SKU-3727-SHOPA", "Jollof Rice", 1000L);
        UUID moinMoin = seedProductAt(otherShopId, "SKU-3727-SHOPB", "Moin Moin", 700L);
        String tag = UUID.randomUUID().toString();
        String email = "shops-" + tag + "@example.com";
        String phone = uniquePhone();

        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "shopa-" + i + "-" + tag),
                    storefrontBody(email, phone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }
        MvcResult refusedHere = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "shopa-4-" + tag),
                storefrontBody(email, phone, List.of(line(jollof, 1))));
        assertProblem(refusedHere, 429, PROBLEM_TYPE, PROBLEM_CODE);

        MvcResult acceptedThere = perform(post(ordersUrl(OTHER_SHOP_SLUG)).header("Idempotency-Key", "shopb-1-" + tag),
                storefrontBody(email, phone, List.of(line(moinMoin, 1))));
        assertThat(acceptedThere.getResponse().getStatus()).as("another shop, its own cap: " + responseText(acceptedThere)).isEqualTo(201);
        assertThat(countOrdersByFoldedEmail(email)).isEqualTo(PER_EMAIL_CAP + 1);
    }

    // ------------------------------------------------------------------
    // Card orders are outside the cap. The test profile has no Stripe key, so a card order cannot be
    // PLACED here (the unit test covers that branch: PublicStorefrontServiceTest); what the IT can prove
    // is the other half of the exemption — open CARD orders held by the identity do not COUNT, because
    // the cash predicate (payment_status = 'NONE') excludes them, so a cash order beside three open card
    // orders is 201 while a cash order beside three open cash orders is 429.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: three open CARD orders for an identity do not count towards its cash cap (a cash order is still 201); three open cash orders do (429)")
    void openCardOrders_doNotCountTowardsTheCashCap() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CARD", "Jollof Rice", 1000L);
        String tag = UUID.randomUUID().toString();
        String email = "card-" + tag + "@example.com";
        String phone = uniquePhone();

        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            seedOpenCardOrder(email, phone);
        }
        assertThat(countOpenOrdersByFoldedEmail(email)).as("three open CARD orders are held").isEqualTo(PER_EMAIL_CAP);

        MvcResult accepted = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "card-cash-1-" + tag),
                storefrontBody(email, phone, List.of(line(jollof, 1))));
        assertThat(accepted.getResponse().getStatus()).as("card orders are outside the cap: " + responseText(accepted)).isEqualTo(201);

        // Control arm, same identity shape: three open CASH orders DO count.
        String cashEmail = "cash-" + tag + "@example.com";
        String cashPhone = uniquePhone();
        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "cash-" + i + "-" + tag),
                    storefrontBody(cashEmail, cashPhone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }
        MvcResult refused = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "cash-4-" + tag),
                storefrontBody(cashEmail, cashPhone, List.of(line(jollof, 1))));
        assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE);
    }

    // ------------------------------------------------------------------
    // Completed, cancelled and voided orders are not open: an identity with three of each and no open
    // order places a cash order (201). The boundary from the other side.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: COMPLETED, CANCELLED and VOIDED cash orders hold no slot (a cash order beside nine of them is 201)")
    void closedOrders_holdNoSlot() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CLOSED", "Jollof Rice", 1000L);
        String tag = UUID.randomUUID().toString();
        String email = "closed-" + tag + "@example.com";
        String phone = uniquePhone();

        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "closed-" + i + "-" + tag),
                    storefrontBody(email, phone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }
        inTenant(() -> {
            List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM orders WHERE tenant_id = ? AND customer_email = ? ORDER BY created_at",
                    UUID.class, TENANT_ID, email);
            assertThat(ids).hasSize(PER_EMAIL_CAP);
            jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED' WHERE tenant_id = ? AND id = ?", TENANT_ID, ids.get(0));
            jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE tenant_id = ? AND id = ?", TENANT_ID, ids.get(1));
            // A void keeps the status (D-10): CONFIRMED but voided must not hold a slot.
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', voided_at = now(), voided_by = 'test', void_reason = 'fake' "
                    + "WHERE tenant_id = ? AND id = ?", TENANT_ID, ids.get(2));
            return null;
        });

        MvcResult accepted = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "closed-4-" + tag),
                storefrontBody(email, phone, List.of(line(jollof, 1))));
        assertThat(accepted.getResponse().getStatus()).as("no open order, no slot held: " + responseText(accepted)).isEqualTo(201);
    }

    // ------------------------------------------------------------------
    // contact_verified (D-18, UI-SPEC D1 / F20): a STORED fact about who placed the order, written on
    // every storefront order — true only when the request carried a verified customer token for the
    // SAME email (case-insensitive), false otherwise — and left NULL ("not recorded") by the vendor
    // writer. Read back by SQL and on both order DTOs, so the kitchen badge (37-30) derives nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: a guest order with no X-Customer-Token records contact_verified = false, and both order DTOs carry contactVerified false")
    void guestOrderWithoutToken_recordsContactVerifiedFalse() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-NONE", "Jollof Rice", 1000L);
        String email = "cv-none-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl(SHOP_SLUG)), storefrontBody(email, uniquePhone(), List.of(line(jollof, 1))));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        assertThat(contactVerifiedByEmail(email)).as("stored: a guest with no token is an unverified contact").isFalse();
        UUID orderId = orderIdByEmail(email);
        assertThat(readOrderDto(orderId).path("contactVerified").asBoolean(true)).as("OrderDto.contactVerified").isFalse();
        assertThat(readOrderDetailDto(orderId).path("contactVerified").asBoolean(true)).as("OrderDetailDto.contactVerified").isFalse();
    }

    @Test
    @DisplayName("D-18: a guest order with a verified customer token for the SAME email (case-insensitive) records contact_verified = true on both DTOs")
    void guestOrderWithVerifiedTokenForTheSameEmail_recordsContactVerifiedTrue() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-TRUE", "Jollof Rice", 1000L);
        // The token proves "Verified.Buyer@Example.com"; the customer typed it lower-case at checkout.
        String email = VERIFIED_EMAIL.toLowerCase(java.util.Locale.ROOT);

        MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("X-Customer-Token", VERIFIED_TOKEN),
                storefrontBody(email, uniquePhone(), List.of(line(jollof, 1))));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        assertThat(contactVerifiedByEmail(email)).as("stored: the token proved this address").isTrue();
        UUID orderId = orderIdByEmail(email);
        assertThat(readOrderDto(orderId).path("contactVerified").asBoolean(false)).as("OrderDto.contactVerified").isTrue();
        assertThat(readOrderDetailDto(orderId).path("contactVerified").asBoolean(false)).as("OrderDetailDto.contactVerified").isTrue();
    }

    @Test
    @DisplayName("37-31 H4: six signed-in cash orders sharing one proxy peer IP all succeed (201) with client_ip_digest = NULL, while a fourth order for one verified email is still refused 429 on EMAIL")
    void verifiedContactCashOrders_sharingOneProxyPeer_skipIpCapAndStoreNullDigest_whileEmailCapRemainsEnforced() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-PROXY", "Jollof Rice", 1000L);
        String tag = UUID.randomUUID().toString();
        String firstVerifiedEmail = null;
        String firstVerifiedToken = null;
        String firstVerifiedPhone = null;

        // Place 6 open cash orders (> per-ip cap of 5) from the SAME socket peer (armPeer, simulating the
        // Next.js /api/customer-orders proxy container), each authenticated by its own customer token.
        for (int i = 1; i <= 6; i++) {
            String email = "verified-proxy-" + i + "-" + tag + "@example.com";
            String token = "verified-proxy-token-" + i + "-" + tag;
            String phone = uniquePhone();
            doReturn(email).when(customerJwtVerifier).verifiedEmail(token);
            if (i == 1) {
                firstVerifiedEmail = email;
                firstVerifiedToken = token;
                firstVerifiedPhone = phone;
            }

            MvcResult created = perform(
                    post(ordersUrl(SHOP_SLUG))
                            .header("X-Customer-Token", token)
                            .header("Idempotency-Key", "cv-proxy-" + i + "-" + tag),
                    storefrontBody(email, phone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus())
                    .as("signed-in order " + i + " of 6 from shared proxy peer must not trip the 5-per-IP cap: " + responseText(created))
                    .isEqualTo(201);
            assertThat(contactVerifiedByEmail(email)).isTrue();
            UUID orderId = orderIdByEmail(email);
            String storedDigest = inTenant(() -> jdbcTemplate.queryForObject(
                    "SELECT client_ip_digest FROM orders WHERE tenant_id = ? AND id = ?", String.class, TENANT_ID, orderId));
            assertThat(storedDigest).as("verified-contact order must not record the proxy peer IP digest").isNull();
        }

        // The per-email cap (3) still bounds each verified customer: placing orders 2 and 3 for the first
        // verified customer succeeds, and the 4th is refused 429 on EMAIL.
        for (int i = 2; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(
                    post(ordersUrl(SHOP_SLUG))
                            .header("X-Customer-Token", firstVerifiedToken)
                            .header("Idempotency-Key", "cv-proxy-repeat-" + i + "-" + tag),
                    storefrontBody(firstVerifiedEmail, firstVerifiedPhone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }
        MvcResult fourthForSameVerifiedEmail = perform(
                post(ordersUrl(SHOP_SLUG))
                        .header("X-Customer-Token", firstVerifiedToken)
                        .header("Idempotency-Key", "cv-proxy-repeat-4-" + tag),
                storefrontBody(firstVerifiedEmail, firstVerifiedPhone, List.of(line(jollof, 1))));
        JsonNode problem = assertProblem(fourthForSameVerifiedEmail, 429, PROBLEM_TYPE, PROBLEM_CODE);
        assertThat(problem.path("axis").asString()).isEqualTo("EMAIL");
    }


    @Test
    @DisplayName("T-37-69: a verified token for ANOTHER email does not verify this order's contact (false), and it is still placed (201)")
    void guestOrderWithATokenForAnotherEmail_recordsContactVerifiedFalse() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-OTHER", "Jollof Rice", 1000L);
        String email = "cv-other-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("X-Customer-Token", OTHER_TOKEN),
                storefrontBody(email, uniquePhone(), List.of(line(jollof, 1))));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        assertThat(contactVerifiedByEmail(email)).as("a token for someone else proves nothing about this contact").isFalse();
    }

    @Test
    @DisplayName("D-18: an INVALID X-Customer-Token is ignored — the order is still a guest order (201, never 401) with contact_verified = false")
    void guestOrderWithAnInvalidToken_isStillPlacedAsAGuest_andRecordsFalse() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-BAD", "Jollof Rice", 1000L);
        String email = "cv-bad-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("X-Customer-Token", "expired-or-forged"),
                storefrontBody(email, uniquePhone(), List.of(line(jollof, 1))));
        assertThat(created.getResponse().getStatus()).as("a bad token must not turn checkout into a 401: " + responseText(created))
                .isEqualTo(201);

        assertThat(contactVerifiedByEmail(email)).isFalse();
    }

    @Test
    @DisplayName("D-18: a vendor order (POST /api/v1/orders) leaves contact_verified NULL — not recorded — and the DTOs omit contactVerified")
    void vendorOrder_leavesContactVerifiedNull() throws Exception {
        UUID jollof = seedProduct("SKU-3727-CV-VENDOR", "Jollof Rice", 1000L);
        String email = "cv-vendor-" + UUID.randomUUID() + "@example.com";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("customerName", "Phone Order Customer");
        body.put("customerEmail", email);
        body.put("customerPhone", uniquePhone());
        body.put("items", List.of(line(jollof, 1)));
        MvcResult created = perform(post("/api/v1/orders").with(vendorJwt()).header("Idempotency-Key", "vendor-" + UUID.randomUUID()), body);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        assertThat(contactVerifiedByEmail(email)).as("the vendor is the writer: nothing to verify, NULL not false").isNull();
        UUID orderId = orderIdByEmail(email);
        assertThat(readOrderDto(orderId).has("contactVerified")).as("absent (NON_NULL) when not recorded, so the badge renders nothing").isFalse();
        assertThat(readOrderDetailDto(orderId).has("contactVerified")).isFalse();
    }

    // ------------------------------------------------------------------
    // V81 shape: the three D-18 columns, their audit mirrors, the CHECK, and the partial cap indexes. The
    // pseudonymous IP digest has NO audit mirror (D-27, T-37-70): a keyed digest of the client address
    // must never enter append-only history that erasure then has to chase.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("V81: orders gains contact_verified, cancel_reason_code (+CHECK) and client_ip_digest; orders_aud mirrors the first two and NOT the digest; the three partial cap indexes exist")
    void v81_columnsMirrorsAndIndexes_haveTheDeclaredShape() {
        Map<String, String> orders = columnTypes("orders");
        assertThat(orders).as("orders columns").containsEntry("contact_verified", "boolean")
                .containsEntry("cancel_reason_code", "character varying")
                .containsEntry("client_ip_digest", "character");
        assertThat(columnLength("orders", "cancel_reason_code")).isEqualTo(32);
        assertThat(columnLength("orders", "client_ip_digest")).isEqualTo(64);
        assertThat(columnDefault("orders", "contact_verified")).as("NULL means not recorded: no DEFAULT (the V63 rule)").isNull();

        Map<String, String> aud = columnTypes("orders_aud");
        assertThat(aud).as("orders_aud mirrors").containsEntry("contact_verified", "boolean")
                .containsEntry("cancel_reason_code", "character varying");
        assertThat(aud).as("D-27: the digest never enters audit history").doesNotContainKey("client_ip_digest");

        String check = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_orders_cancel_reason_code'", String.class);
        assertThat(check).as("every CancelReason value is in the CHECK now (the V43 lesson)")
                .contains("SUSPECTED_FAKE").contains("CANNOT_FULFIL").contains("CUSTOMER_REQUEST").contains("OTHER");

        for (String index : List.of("idx_orders_open_cash_email", "idx_orders_open_cash_phone", "idx_orders_open_cash_ip")) {
            String def = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = ?", String.class, index);
            assertThat(def).as(index + " is partial on the open statuses and the cash predicate")
                    .contains("WHERE").contains("PENDING").contains("CONFIRMED").contains("PREPARING").contains("READY")
                    .contains("payment_status");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'idx_orders_open_cash_email'", String.class))
                .contains("lower(");
    }

    // ---- HTTP helpers ----

    /** A realm-admin token for this tenant (an implicit GROUP_ADMIN, D-03) with both order scopes. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor vendorJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"),
                        new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    private JsonNode readOrderDto(UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId).with(vendorJwt())).andReturn();
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readOrderDetailDto(UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId + "/detail").with(vendorJwt())).andReturn();
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private String ordersUrl(String slug) {
        return "/api/v1/public/shops/" + slug + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr(armPeer); return r; })
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> line(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    /** A storefront body acknowledging the CURRENT allergen set of its products (D-05), as the checkout does. */
    private Map<String, Object> storefrontBody(String email, String phone, List<Map<String, Object>> items) {
        List<UUID> productIds = items.stream().map(i -> (UUID) i.get("productId")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Cash Cap Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", phone);
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        body.put("acknowledgedAllergenMask", GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds));
        // 37-32 (D-16): every line carries the unit price the customer saw, and the body the total.
        body.put("expectedTotalPennies", GuestOrderAcknowledgements.expectCurrentPrices(jdbcTemplate, TENANT_ID, "COLLECTION", items));
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    /**
     * A phone no other arm is likely to draw: six random digits behind a UK mobile prefix (one in a
     * million per pair). The caps count per identity across the whole class run — the shop and its
     * open orders persist between arms — so every arm must mint its own contact details (RESEARCH
     * Pitfall 9: unique details, never a raised default). A three-digit suffix collided in a group run.
     */
    private static String uniquePhone() {
        return "07700 " + sixRandomDigits();
    }

    private static String sixRandomDigits() {
        return String.format(java.util.Locale.ROOT, "%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    // ---- SQL read helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrdersByFoldedEmail(String foldedEmail) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND lower(customer_email) = ?",
                Long.class, TENANT_ID, foldedEmail)));
    }

    private long countOpenOrdersByFoldedEmail(String foldedEmail) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND lower(customer_email) = ? "
                        + "AND status IN ('PENDING','CONFIRMED','PREPARING','READY')",
                Long.class, TENANT_ID, foldedEmail)));
    }

    private void completeOneOpenOrder(String foldedEmail) {
        inTenant(() -> {
            UUID id = jdbcTemplate.queryForObject(
                    "SELECT id FROM orders WHERE tenant_id = ? AND lower(customer_email) = ? "
                            + "AND status IN ('PENDING','CONFIRMED','PREPARING','READY') ORDER BY created_at LIMIT 1",
                    UUID.class, TENANT_ID, foldedEmail);
            int updated = jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED' WHERE tenant_id = ? AND id = ?", TENANT_ID, id);
            assertThat(updated).as("exactly one order completed").isEqualTo(1);
            return null;
        });
    }

    /**
     * An open CARD order as the card branch leaves it before the webhook: DRAFT is not open, so the
     * fixture is a CONFIRMED order with payment_status CAPTURED — the state a paid card order sits in
     * on the kitchen board. Written by SQL because the test profile has no Stripe key.
     */
    private void seedOpenCardOrder(String email, String phone) {
        inTenant(() -> {
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "customer_phone, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                            + "fulfilment_type, total_amount_pennies, item_count, payment_status, payment_method, "
                            + "created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', 'Card Buyer', ?, ?, 1000, 'STANDARD', 166, 0, "
                            + "'COLLECTION', 1000, 1, 'CAPTURED', 'card', now(), now(), 0)",
                    UUID.randomUUID(), TENANT_ID, shopId, "ORD-CARD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    email, phone);
            return null;
        });
    }

    /** The stored fact itself, read as a nullable Boolean: NULL and false are different answers. */
    private Boolean contactVerifiedByEmail(String email) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT contact_verified FROM orders WHERE tenant_id = ? AND customer_email = ?", Boolean.class, TENANT_ID, email));
    }

    private UUID orderIdByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE tenant_id = ? AND customer_email = ?", UUID.class, TENANT_ID, email)));
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, STOREFRONT_ENDPOINT, key)));
    }

    private Map<String, String> columnTypes(String table) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                rs -> { types.put(rs.getString(1), rs.getString(2)); }, table);
        assertThat(types).as("the table " + table + " exists and has columns").isNotEmpty();
        return types;
    }

    private Integer columnLength(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    // ---- Idempotent seed helpers (the OrderLimitsIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug, String name) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No hours and LEGACY_ALWAYS_OPEN (37-20, D-25): the shop takes orders at any time, and no
            // minimum order, so the only refusal an arm can meet is the one under test.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix) at the test shop, with untracked stock. */
    private UUID seedProduct(String skuPrefix, String title, long pricePennies) {
        return seedProductAt(shopId, skuPrefix, title, pricePennies);
    }

    private UUID seedProductAt(UUID atShopId, String skuPrefix, String title, long pricePennies) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", pricePennies, atShopId);
            return productId;
        });
    }
}
