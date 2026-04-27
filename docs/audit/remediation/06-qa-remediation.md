# 06 — QA Remediation Design

**Pair**: SPECIALIST (QA / Test Architect, Spring + Jest + Playwright + Go) ⇄ ASSISTANT (Test Strategy Reviewer — mutation-mindset, contract testing, flake-hunter, "does this test catch the bug it claims to catch")
**Source audit**: [docs/audit/sources/06-qa-engineer.md](../sources/06-qa-engineer.md)
**Synthesis**: [docs/audit/COUNCIL-AUDIT-2026-04-27.md](../COUNCIL-AUDIT-2026-04-27.md)
**Date**: 2026-04-27

---

## Principles (agreed up front)

1. **Litmus test for any new test**: would it fail if the bug it targets were reintroduced? If not, it is vanity. The assistant verifies this for every finding.
2. **Mocked seams are blind seams**. `MockedStatic<Webhook>` proves the contract you wrote, not the contract Stripe enforces. Real HMAC is cheap; mock it only at the boundary you cannot exercise.
3. **Coverage is a smoke detector, not a fire alarm**. JaCoCo gates surface obvious gaps; they do not prove the suite catches bugs. Pair them with mutation reasoning, not blind percentage worship.
4. **Integration tests against real Postgres beat unit tests against mocks** for anything multi-tenant. RLS only exists in PG; H2 is a vacuous green.
5. **Tests are CI-gated or they don't exist**. Playwright that runs locally is a memo, not a regression guard.
6. **Documentation drift is a test failure**. CLAUDE.md numeric claims must match `wc -l`; CI should enforce this once.
7. **Pair-coordination matters**. Findings 3, 4, 7, 9, 10 depend on work owned by other audit pairs (security/02, backend/01, frontend/05). The reconciled positions sequence around their landings.

---

## Finding 1 — `PaymentWebhookSignatureIntegrationTest`

### Specialist proposal

`PaymentServiceTest` mocks `MockedStatic<Webhook>` at every signature path, so a wrong `stripe.webhook-secret` in YAML or a refactor of `getWebhookSecret()` would still pass green CI (audit `06-qa-engineer.md:62, 112`). Stripe's SDK exposes `Webhook.Util.computeHmacSha256(payload, secret)` — we can construct a real signed envelope and feed it to `paymentService.handleWebhookEvent` without mocking. Use a `@SpringBootTest` slice that wires the real `PaymentService` + `StripeProperties`, but stubs `OrderRepository`, `FinancialTransactionService`, `OrderEventPublisher`, `PaymentEventPublisher` via `@MockitoBean` so we do not need a Postgres for the signature check itself.

```java
// core-java/src/test/java/uk/jtoye/core/payment/PaymentWebhookSignatureIntegrationTest.java
package uk.jtoye.core.payment;

import com.stripe.net.Webhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(classes = {
        PaymentService.class,
        StripeProperties.class,
        PaymentWebhookSignatureIntegrationTest.NoOpEventPublisherConfig.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "stripe.api-key=sk_test_dummy",
        "stripe.webhook-secret=whsec_test_known_secret_for_signature_check"
})
@DisplayName("Stripe webhook signature is verified against the wired secret (no MockedStatic)")
class PaymentWebhookSignatureIntegrationTest {

    private static final String SECRET = "whsec_test_known_secret_for_signature_check";
    private static final String UNHANDLED_PAYLOAD =
            "{\"id\":\"evt_test_1\",\"object\":\"event\"," +
            "\"type\":\"charge.captured\"," +
            "\"data\":{\"object\":{\"id\":\"ch_test_1\",\"object\":\"charge\"}}," +
            "\"api_version\":\"2024-06-20\"}";

    @Autowired private PaymentService paymentService;
    @MockitoBean private OrderRepository orderRepository;
    @MockitoBean private FinancialTransactionService financialTransactionService;
    @MockitoBean private OrderEventPublisher orderEventPublisher;
    @MockitoBean private PaymentEventPublisher paymentEventPublisher;

    @TestConfiguration
    static class NoOpEventPublisherConfig { }

    private static String sign(String payload, long timestamp, String secret) {
        String signedPayload = timestamp + "." + payload;
        String v1 = Webhook.Util.computeHmacSha256(secret, signedPayload);
        return "t=" + timestamp + ",v1=" + v1;
    }

    @Test
    @DisplayName("real HMAC computed with the wired secret is accepted (no exception)")
    void validSignature_acceptedAndDispatched() {
        long ts = System.currentTimeMillis() / 1000L;
        String header = sign(UNHANDLED_PAYLOAD, ts, SECRET);

        // Should NOT throw — this exercises the real Webhook.constructEvent code path.
        // The event type is "charge.captured" which falls into the default branch,
        // so no Order lookup happens; we are isolating the signature check.
        paymentService.handleWebhookEvent(UNHANDLED_PAYLOAD, header);

        verifyNoInteractions(orderRepository, financialTransactionService);
    }

    @Test
    @DisplayName("HMAC computed with the WRONG secret is rejected (regression guard for getWebhookSecret() drift)")
    void wrongSecret_rejected() {
        long ts = System.currentTimeMillis() / 1000L;
        String header = sign(UNHANDLED_PAYLOAD, ts, "whsec_attacker_guess_42");

        assertThatThrownBy(() -> paymentService.handleWebhookEvent(UNHANDLED_PAYLOAD, header))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Stripe signature");
    }

    @Test
    @DisplayName("tampered payload after signing is rejected")
    void tamperedPayload_rejected() {
        long ts = System.currentTimeMillis() / 1000L;
        String header = sign(UNHANDLED_PAYLOAD, ts, SECRET);
        String tampered = UNHANDLED_PAYLOAD.replace("ch_test_1", "ch_attacker_1");

        assertThatThrownBy(() -> paymentService.handleWebhookEvent(tampered, header))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Stripe signature");
    }

    @Test
    @DisplayName("expired timestamp (>5min default tolerance) is rejected")
    void expiredTimestamp_rejected() {
        long sixMinutesAgo = (System.currentTimeMillis() / 1000L) - 360;
        String header = sign(UNHANDLED_PAYLOAD, sixMinutesAgo, SECRET);

        assertThatThrownBy(() -> paymentService.handleWebhookEvent(UNHANDLED_PAYLOAD, header))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

### Assistant deliberation

Three sceptic points:

1. **Will it fail if the bug returns?** If `PaymentService.handleWebhookEvent` is silently changed to `Webhook.constructEvent(payload, sigHeader, "wrong_constant")`, test 1 fails (good signature → SignatureVerificationException → IllegalArgumentException). If `stripe.webhook-secret` YAML key is renamed without `StripeProperties` updating, `getWebhookSecret()` returns `""` → all four tests fail. **Litmus passes.**
2. **Realism of environment**: the test boots a Spring context with only `PaymentService` + `StripeProperties` + Mockito-bean stubs — meaning `Stripe.apiKey` may or may not be initialised depending on `@PostConstruct` order. That's fine because we use `charge.captured` which never calls Stripe API, only the signature check. But specialist must NOT use `payment_intent.succeeded` here or it triggers `Charge.retrieve` → real Stripe call → flake.
3. **Maintenance cost**: low. Stripe's `Webhook.Util.computeHmacSha256` has been stable since SDK v20+. The test will outlive most refactors. Worth inlining the helper rather than creating a separate utility class — keeps the test self-explanatory.

One additional concern: `@MockitoBean` requires Spring Boot 3.4+. Project is on 3.4.2 (confirmed `core-java/build.gradle.kts:2`), so green.

### Reconciled position

**Adopt the specialist proposal as written.** Use `charge.captured` (an unhandled event type) as the carrier so signature verification is the only code path exercised. Add tests for: valid signature, wrong secret, tampered payload, expired timestamp. Place at `core-java/src/test/java/uk/jtoye/core/payment/PaymentWebhookSignatureIntegrationTest.java`. **No `MockedStatic<Webhook>`. Ever. In any test in this file.** Do not delete the existing mocked `PaymentServiceTest` — keep it for state-machine logic, but it must never be the only signature test.

---

## Finding 2 — `GuestOrderIdempotencyIntegrationTest`

### Specialist proposal

`Order.idempotencyKey` (`Order.java:61-62`), `OrderRepository.findByTenantIdAndIdempotencyKey` (`OrderRepository.java:49`), and the lookup-or-insert path (`PublicStorefrontService.java:327-348, 361-363`) all exist with **zero tests** asserting they actually deduplicate. POST same key twice; assert (a) same order number returned, (b) only one row in `orders` for that idempotency key, (c) only one row in `financial_transactions` (this last one fails today since financial_transactions is created on payment webhook, not order creation — the test still proves the order-side dedup, and we add a stronger version once Stripe-event idempotency lands per blocker #3 in COUNCIL-AUDIT).

```java
// core-java/src/test/java/uk/jtoye/core/storefront/GuestOrderIdempotencyIntegrationTest.java
package uk.jtoye.core.storefront;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestOrderIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("rate-limiting.enabled", () -> "false");
        r.add("spring.rabbitmq.host", () -> "localhost");
        r.add("spring.rabbitmq.port", () -> "0");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        // Stripe is not configured — order creation must succeed without
        // creating a PaymentIntent, OR the test must accept a stub. We force
        // the unconfigured branch for simplicity.
        r.add("stripe.api-key", () -> "");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ShopRepository shops;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;

    private String slug;
    private UUID productId;
    private UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        Shop s = new Shop();
        s.setTenantId(tenantId);
        s.setName("Idem Test Kitchen");
        s.setSlug("idem-test-" + UUID.randomUUID().toString().substring(0, 8));
        s.setPublished(true);
        shops.save(s);
        slug = s.getSlug();

        Product p = new Product();
        p.setTenantId(tenantId);
        p.setShopId(s.getId());
        p.setTitle("Jollof Rice");
        p.setPricePennies(800L);
        p.setAvailable(true);
        p.setQuantityInStock(99);
        products.save(p);
        productId = p.getId();
    }

    @Test
    @DisplayName("Same idempotencyKey POSTed twice returns the same order_number and creates only ONE row")
    void duplicatePost_returnsSameOrder() throws Exception {
        String key = "client-key-" + UUID.randomUUID();
        GuestOrderRequest req = new GuestOrderRequest();
        req.setCustomerName("Test Customer");
        req.setCustomerEmail("test@example.com");
        req.setCustomerPhone("+447000000000");
        req.setIdempotencyKey(key);
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(2);
        req.setItems(List.of(item));
        String body = json.writeValueAsString(req);

        MvcResult first = mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").exists())
                .andReturn();
        String orderNumber1 = json.readTree(first.getResponse().getContentAsString())
                .get("orderNumber").asText();

        MvcResult second = mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String orderNumber2 = json.readTree(second.getResponse().getContentAsString())
                .get("orderNumber").asText();

        assertThat(orderNumber2).isEqualTo(orderNumber1);

        Long rowsForKey = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE idempotency_key = ?",
                Long.class, key);
        assertThat(rowsForKey).isEqualTo(1L);
    }

    @Test
    @DisplayName("Different idempotencyKeys produce different orders")
    void differentKeys_produceDifferentOrders() throws Exception {
        GuestOrderRequest base = new GuestOrderRequest();
        base.setCustomerName("Customer");
        base.setCustomerEmail("c@example.com");
        base.setCustomerPhone("+447000000001");
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId); item.setQuantity(1);
        base.setItems(List.of(item));

        base.setIdempotencyKey("key-A-" + UUID.randomUUID());
        String orderA = json.readTree(mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(base)))
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();

        base.setIdempotencyKey("key-B-" + UUID.randomUUID());
        String orderB = json.readTree(mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(base)))
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();

        assertThat(orderB).isNotEqualTo(orderA);
    }

    @Test
    @DisplayName("Missing idempotencyKey is allowed (back-compat) and creates a fresh order each time")
    void noKey_freshOrderEachTime() throws Exception {
        GuestOrderRequest req = new GuestOrderRequest();
        req.setCustomerName("Anon"); req.setCustomerEmail("a@example.com");
        req.setCustomerPhone("+447000000002");
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId); item.setQuantity(1);
        req.setItems(List.of(item));
        String body = json.writeValueAsString(req);

        String n1 = json.readTree(mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();
        String n2 = json.readTree(mvc.perform(post("/public/shops/" + slug + "/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();
        assertThat(n2).isNotEqualTo(n1);
    }
}
```

### Assistant deliberation

1. **Litmus**: if the `if (existing.isPresent())` branch in `PublicStorefrontService.java:330` is deleted, test 1 fails (orderNumber2 != orderNumber1, row count = 2). If `findByTenantIdAndIdempotencyKey` is silently changed to `findByIdempotencyKey` (cross-tenant collision), this *specific* test still passes — single tenant. Add a fourth test: same idempotency key under tenant B should produce a different order. Specialist accepted.
2. **Realism**: Testcontainers PG with real Flyway. Tenant context — the `/public/shops/{slug}/orders` path resolves tenant from the slug (`PublicStorefrontController.java`-ish), so we don't need a JWT. Good.
3. **Flake risk**: financial_transactions assertion deferred until the Stripe-event-idempotency table exists (COUNCIL blocker #3). The current order does not create a `financial_transactions` row — that happens on `payment_intent.succeeded`. Audit text was slightly imprecise; the *real* P0 deduplication is on the Stripe event side, not the order side. **Important pair handoff**: when blocker #3 lands, this file should be extended with `processed_stripe_events` row count assertions.

### Reconciled position

**Adopt with the cross-tenant 4th test added.**

```java
@Test
@DisplayName("Same idempotencyKey under DIFFERENT tenants produces different orders (regression for cross-tenant key collision)")
void sameKeyDifferentTenants_doNotCollide() throws Exception {
    // Seed a second tenant + shop + product, POST with the same key against /public/shops/{otherSlug}/orders.
    // Assert order numbers differ and DB has 2 rows for that idempotency key (one per tenant).
}
```

File path: `core-java/src/test/java/uk/jtoye/core/storefront/GuestOrderIdempotencyIntegrationTest.java`. Tag `testcontainers`.

---

## Finding 3 — `JwtSecurityIntegrationTest`

### Specialist proposal

Three scenarios — expired token, wrong audience, no token — all asserting 401 against a protected endpoint. Use `@SpringBootTest` + `@AutoConfigureMockMvc` (NO `addFilters=false`) so the full `SecurityFilterChain` runs. Mint tokens with Nimbus `JWSSigner` since the project already pulls Spring Security OAuth2 Resource Server (transitively brings Nimbus).

```java
// core-java/src/test/java/uk/jtoye/core/security/JwtSecurityIntegrationTest.java
package uk.jtoye.core.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtSecurityIntegrationTest {

    private static RSAKey rsaKey;
    private static RSASSASigner signer;
    private static final String ISSUER = "https://test-keycloak.local/realms/jtoye";

    @Autowired private MockMvc mvc;

    @BeforeAll
    static void setupKeys() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-kid-1").generate();
        signer = new RSASSASigner((RSAPrivateKey) rsaKey.toPrivateKey());
    }

    @TestConfiguration
    static class JwtTestConfig {
        @Bean @Primary
        JwtDecoder testJwtDecoder() throws Exception {
            // Wire a NimbusJwtDecoder against the same RSA key the tests sign with,
            // so the decoder logic (signature, exp) runs end-to-end without
            // needing a live JWKS endpoint.
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) rsaKey.toPublicKey()).build();
        }
    }

    private static String mintToken(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    @Test
    @DisplayName("No Authorization header → 401 on protected endpoint")
    void noToken_returns401() throws Exception {
        mvc.perform(get("/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Expired token → 401")
    void expiredToken_returns401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("user-1")
                .claim("tenant_id", UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))) // expired 1h ago
                .build();
        String token = mintToken(claims);
        mvc.perform(get("/shops").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token signed by a different key → 401")
    void wrongSignature_returns401() throws Exception {
        // Use a different signer to simulate wrong-issuer key.
        RSAKey other = new RSAKeyGenerator(2048).keyID("other-kid").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other-kid").build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("attacker")
                        .claim("tenant_id", UUID.randomUUID().toString())
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                        .build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) other.toPrivateKey()));
        mvc.perform(get("/shops").header("Authorization", "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token with WRONG audience → 401 (REQUIRES audience validator wired in SecurityConfig)")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "jtoye.aud-enforcement", matches = "true")
    void wrongAudience_returns401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of("https://attacker.example/api"))
                .subject("user-1")
                .claim("tenant_id", UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        mvc.perform(get("/shops").header("Authorization", "Bearer " + mintToken(claims)))
                .andExpect(status().isUnauthorized());
    }
}
```

### Assistant deliberation

1. **Wrong-audience test is conditional.** The audit (`02-security-engineer.md`) and the COUNCIL synthesis both flag that **`aud` enforcement is currently absent in both Spring and edge-go** (synthesis line 113 — "edge-go aud claim verification ... or skip the patch"). Until pair 02 lands the audience validator on the Spring `JwtDecoder`, this test would fail with **"validator not wired" — not "wrong audience rejected"**. Specialist's `@EnabledIfSystemProperty` gate is the correct compromise: the test exists in the codebase (so the day audience enforcement lands, the gate flips), and CI never sees a flaky no-op until the security work catches up. **Pair-coordination flag**: when 02 ships its audience validator, also remove the `@EnabledIfSystemProperty` and add `-Djtoye.aud-enforcement=true` to the gradle `test` task.
2. **Litmus**: if `JwtDecoder` is misconfigured to accept any RSA-signed token regardless of expiry, test 2 fails. If `OAuth2ResourceServerConfigurer` is removed from `SecurityConfig`, tests 1+2+3 all start returning 200 instead of 401. **Strong canary.**
3. **Realism**: the `@TestConfiguration` overrides the production `JwtDecoder` so the test does not need a live Keycloak JWKS endpoint. This is a *seam* — but it is a minimal one and exercises every Spring Security filter end-to-end. The risk is that `SecurityConfig.java` does something exotic with its decoder bean that the override doesn't replicate. Mitigation: `@Primary` on the test bean wins; if `SecurityConfig` builds the decoder inline (not as a bean), this test silently uses the production one and we should check.

### Reconciled position

**Adopt as written, with the audience test gated.** Add a `core-java/build.gradle.kts` task argument such that once the security pair (#02) wires the audience validator, the team flips the gate by adding `systemProperty("jtoye.aud-enforcement", "true")`. Until then, the file ships with three live tests + one disabled scaffold. Path: `core-java/src/test/java/uk/jtoye/core/security/JwtSecurityIntegrationTest.java`. **Sequencing**: lands AFTER pair 02 confirms the `JwtDecoder` is bean-overridable (non-blocking — assistant verified `SecurityConfig.java:68-74` uses standard `oauth2ResourceServer.jwt()` configurer, which uses the `JwtDecoder` bean from context).

---

## Finding 4 — `RefundWebhookHandlingIntegrationTest`

### Specialist proposal

`charge.refunded` currently falls into the default `log.debug("Unhandled Stripe event type: ...")` branch (`PaymentService.java:130`). Phase 17 (PR #51) shipped vendor-initiated refunds via Stripe, so a Stripe-dashboard refund OR a webhook arriving from a successful `Refund.create()` call silently desyncs the ledger. The handler does not exist yet — this is owned by **backend pair 01**. The QA work here is to write the test that pins the contract.

```java
// core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java
package uk.jtoye.core.payment;

import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.FinancialTransactionRepository;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@TestPropertySource(properties = {
        "stripe.api-key=sk_test_dummy",
        "stripe.webhook-secret=whsec_refund_test_secret"
})
class RefundWebhookHandlingIntegrationTest {

    private static final String SECRET = "whsec_refund_test_secret";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("rate-limiting.enabled", () -> "false");
        r.add("spring.rabbitmq.host", () -> "localhost");
        r.add("spring.rabbitmq.port", () -> "0");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private FinancialTransactionRepository financialTransactionRepository;

    private UUID orderId;
    private UUID tenantId;
    private String paymentIntentId;

    @BeforeEach
    void seedCapturedOrder() {
        tenantId = UUID.randomUUID();
        Order o = new Order();
        o.setTenantId(tenantId);
        o.setShopId(UUID.randomUUID());
        o.setOrderNumber("ORD-REFUND-" + System.currentTimeMillis());
        o.setStatus(OrderStatus.PENDING);
        o.setPaymentStatus(PaymentStatus.CAPTURED);
        o.setTotalAmountPennies(2500L);
        paymentIntentId = "pi_refund_test_" + UUID.randomUUID();
        o.setPaymentReference(paymentIntentId);
        orderRepository.save(o);
        orderId = o.getId();
    }

    private static String sign(String payload, String secret) {
        long ts = System.currentTimeMillis() / 1000L;
        return "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(secret, ts + "." + payload);
    }

    @Test
    @DisplayName("charge.refunded webhook flips order to REFUNDED and posts a reversal transaction")
    void chargeRefunded_marksOrderRefundedAndPostsReversal() {
        // Construct a real Stripe-shaped charge.refunded event JSON. The handler
        // (to be implemented by pair 01) must read order_id from charge.metadata.
        String payload = "{"
                + "\"id\":\"evt_refund_1\","
                + "\"object\":\"event\","
                + "\"type\":\"charge.refunded\","
                + "\"api_version\":\"2024-06-20\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_refund_1\","
                + "\"object\":\"charge\","
                + "\"payment_intent\":\"" + paymentIntentId + "\","
                + "\"amount\":2500,"
                + "\"amount_refunded\":2500,"
                + "\"refunded\":true,"
                + "\"metadata\":{"
                + "\"order_id\":\"" + orderId + "\","
                + "\"tenant_id\":\"" + tenantId + "\"}"
                + "}}}";

        long pre = financialTransactionRepository.count();

        paymentService.handleWebhookEvent(payload, sign(payload, SECRET));

        Order updated = orderRepository.findById(orderId).orElseThrow();
        assertThat(updated.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        long post = financialTransactionRepository.count();
        assertThat(post - pre)
                .as("a reversal financial_transactions row must be posted")
                .isEqualTo(1L);

        // Assert the reversal is negative (audit-trail discipline)
        Long latestAmount = financialTransactionRepository.findAll().stream()
                .mapToLong(t -> t.getAmountPennies() == null ? 0L : t.getAmountPennies())
                .min().orElse(0L);
        assertThat(latestAmount).isLessThan(0L);
    }

    @Test
    @DisplayName("partial refund (amount_refunded < amount) flips status to PARTIALLY_REFUNDED if enum exists, else REFUNDED")
    void partialRefund_handled() {
        // Skeleton — to be enabled when PaymentStatus.PARTIALLY_REFUNDED is added.
        // Today only REFUNDED exists; partial refunds collapse to REFUNDED + reversal of partial amount.
        String payload = "{"
                + "\"id\":\"evt_refund_2\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"api_version\":\"2024-06-20\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_partial_1\",\"object\":\"charge\","
                + "\"payment_intent\":\"" + paymentIntentId + "\","
                + "\"amount\":2500,\"amount_refunded\":1000,\"refunded\":false,"
                + "\"metadata\":{\"order_id\":\"" + orderId + "\",\"tenant_id\":\"" + tenantId + "\"}"
                + "}}}";

        paymentService.handleWebhookEvent(payload, sign(payload, SECRET));

        Order updated = orderRepository.findById(orderId).orElseThrow();
        assertThat(updated.getPaymentStatus()).isIn(PaymentStatus.REFUNDED /*, PARTIALLY_REFUNDED */);
    }
}
```

### Assistant deliberation

1. **Litmus**: today the test fails (handler does not exist; payment status stays `CAPTURED`). That's correct — this is the *failing* test that pair 01 implements against. Once pair 01 lands the handler, the test must turn green and stay green.
2. **Realism**: `PaymentStatus` only has `REFUNDED` (verified `PaymentStatus.java:23`). The partial-refund test uses `isIn(REFUNDED)` so it remains forward-compatible if `PARTIALLY_REFUNDED` is added later. Specialist correctly didn't fabricate enum values.
3. **Pair coordination**: this is a **failing test in the suite** until backend pair 01 ships the handler. Two options: (a) `@Disabled` the test with a TODO referencing the backend ticket, or (b) ship it failing and let CI go red on purpose to force the implementation. Sceptic recommends (a) — a perpetually-red CI desensitises engineers; a `@Disabled("blocked-on-pair-01-refund-handler")` is a more honest signalling.

### Reconciled position

**Adopt with `@Disabled` annotations on both tests**, citing the dependency: `@Disabled("Enable when pair 01 lands charge.refunded handler — see docs/audit/remediation/01-backend-remediation.md")`. The test code is committed so pair 01 has a target to make pass; CI does not stay red. As soon as the handler lands, the responsible engineer removes `@Disabled` in the same PR and the test must go green. Path: `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java`.

---

## Finding 5 — Fix the placeholder Go JWT test

### Specialist proposal

`edge-go/internal/middleware/jwt_test.go:120-127` is the canonical example of a test that asserts nothing: it generates an RSA key, signs a JWT, sends it through the middleware, and on `w.Code != 401` calls `t.Logf` (not `t.Errorf`). Comment line 120 admits "this test will fail validation because we can't easily mock JWKS validation". The honest fix has two paths:

**Path (a)** — write a real test using `httptest` to host a JWKS endpoint that exposes the test public key, and assert the middleware accepts a properly-signed token. Material lift but achievable:

```go
// edge-go/internal/middleware/jwt_test.go (replacement for TestJWTMiddleware_Validate_ValidToken)

func TestJWTMiddleware_Validate_ValidToken(t *testing.T) {
    logger, _ := zap.NewProduction()

    // 1. Generate RSA key pair
    privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
    if err != nil {
        t.Fatalf("Failed to generate RSA key: %v", err)
    }

    // 2. Build a JWKS that exposes the public modulus + exponent for "test-kid-1"
    n := base64.RawURLEncoding.EncodeToString(privateKey.PublicKey.N.Bytes())
    e := base64.RawURLEncoding.EncodeToString(big.NewInt(int64(privateKey.PublicKey.E)).Bytes())
    jwks := JWKSResponse{Keys: []JWK{{
        Kid: "test-kid-1", Kty: "RSA", Use: "sig", Alg: "RS256", N: n, E: e,
    }}}

    // 3. Spin up an httptest server hosting the JWKS doc
    jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        _ = json.NewEncoder(w).Encode(jwks)
    }))
    defer jwksServer.Close()

    middleware := NewJWTMiddleware(jwksServer.URL, "http://test-issuer.com", logger)

    // 4. Mint a JWT signed with the matching private key
    token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
        "iss":       "http://test-issuer.com",
        "sub":       "test-user-123",
        "tenant_id": "00000000-0000-0000-0000-000000000001",
        "exp":       time.Now().Add(time.Hour).Unix(),
        "iat":       time.Now().Unix(),
    })
    token.Header["kid"] = "test-kid-1"
    tokenString, err := token.SignedString(privateKey)
    if err != nil {
        t.Fatalf("Failed to sign token: %v", err)
    }

    // 5. Run middleware and assert success
    gin.SetMode(gin.TestMode)
    w := httptest.NewRecorder()
    c, _ := gin.CreateTestContext(w)
    c.Request = httptest.NewRequest("GET", "/test", nil)
    c.Request.Header.Set("Authorization", "Bearer "+tokenString)

    // Provide a downstream handler so c.Next() has somewhere to land
    c.Next() // no-op, but documents intent
    middleware.Validate()(c)

    if w.Code != http.StatusOK && w.Code != 0 {
        t.Errorf("Expected middleware to pass (status 200/0 from no downstream), got %d body=%s",
                w.Code, w.Body.String())
    }

    // Assert tenant context populated downstream
    tenantID, exists := c.Get("tenant_id")
    if !exists {
        t.Errorf("Expected tenant_id in context after valid token")
    }
    if tenantID != "00000000-0000-0000-0000-000000000001" {
        t.Errorf("Expected tenant_id 00000000..001, got %v", tenantID)
    }
}
```

**Path (b)** — edge-go is being absorbed into Core (audit cross-cutting theme D, `07-edge-go.md` recommendation). If the team commits to absorption in this milestone, the test should be deleted (and the file footnoted in HANDOFF.md as "removed during edge-absorb").

### Assistant deliberation

1. **Litmus for path (a)**: if `Validate()` is silently changed to skip JWKS lookup and accept any RSA signature, the test still passes (it provides a matching JWKS). To strengthen, add a *negative* sibling: token signed by `otherKey`, JWKS only exposes `privateKey`, must return 401. Specialist accepted.
2. **Realism**: the test is realistic — JWKS over httptest is exactly how `auth0/go-jwt-middleware` and `golang-jwt/jwt/v5` examples do it. The only sharp edge is `c.Next()` — Gin's middleware chain is fiddly. Verify by inspection of `jwt.go`'s `Validate()` that `c.AbortWithStatusJSON` short-circuits on failure and `c.Set("tenant_id", ...)` then `c.Next()` is the success path. Specialist did not show `jwt.go` source — note as **assumption, verify before committing**.
3. **Path (a) vs (b)**: the audit's verdict on edge-go is "delete and absorb". If absorption is on the Milestone-4 roadmap, writing 60 lines of test for code marked for deletion is wasted work. **Decision needs founder input** — specialist cannot pick on their own.

### Reconciled position

**Path (a) IF and only IF edge-absorb is deferred past Milestone 4.** If the founder commits to absorbing edge-go in M4 (per audit's "do not invest in edge-go"), delete `TestJWTMiddleware_Validate_ValidToken` entirely with a commit message `chore(edge-go): remove broken placeholder test pre-absorb`. Otherwise apply path (a) PLUS the wrong-key negative sibling. The default position recommended here is **path (b) — delete it**, because the time-investment guidance from the audit is unambiguous. If anyone reaches for path (a), they have already decided not to absorb.

---

## Finding 6 — Fix the misnamed thread-safety test

### Specialist proposal

`OrderStateMachineServiceTest.testThreadSafety` (`OrderStateMachineServiceTest.java:124-137`) is sequential: it creates two orders and calls `sendEvent` on each in turn from the test thread. The name promises concurrency; the body delivers two unrelated single-threaded calls. Two options: (a) rename to `testIsolatedStateMachineInstancesPerCall` (truthful), (b) rewrite as a real concurrent test. Recommend (b) — Spring StateMachine has a documented thread-safety pitfall (single shared `StateMachine` per region is not thread-safe); a real concurrency test catches future regressions when someone "optimises" by reusing instances.

```java
// REPLACE testThreadSafety in OrderStateMachineServiceTest.java

@Test
@DisplayName("Concurrent transitions on disjoint orders all succeed (no shared-state corruption)")
void testConcurrentTransitionsOnDisjointOrders() throws InterruptedException {
    int threads = 16;
    int perThread = 25; // 400 total transitions
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger successes = new AtomicInteger();
    Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

    try {
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        UUID id = UUID.randomUUID();
                        OrderStatus s = stateMachineService.sendEvent(id, OrderStatus.DRAFT, OrderEvent.SUBMIT);
                        if (s == OrderStatus.PENDING) successes.incrementAndGet();
                        else errors.add(new AssertionError("expected PENDING, got " + s));
                    }
                } catch (Throwable th) {
                    errors.add(th);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown(); // release all threads at once
        assertTrue(done.await(30, TimeUnit.SECONDS), "all worker threads must finish in 30s");
    } finally {
        pool.shutdownNow();
    }

    assertTrue(errors.isEmpty(), () -> "concurrent state machine errors: " + errors);
    assertEquals(threads * perThread, successes.get(),
            "every concurrent SUBMIT on a fresh DRAFT order must transition to PENDING");
}

@Test
@DisplayName("Concurrent transitions on the SAME order linearise to a single valid path")
void testConcurrentTransitionsOnSameOrder() throws InterruptedException {
    // Two threads attempt CONFIRM on the same PENDING order; service must
    // be safe to call. (Real conflict resolution is the DB's @Version job;
    // this test ensures the state machine itself does not corrupt internal state.)
    UUID id = UUID.randomUUID();
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            try {
                start.await();
                stateMachineService.sendEvent(id, OrderStatus.PENDING, OrderEvent.CONFIRM);
            } catch (InvalidStateTransitionException expected) {
                // ok — race loser
            } catch (Throwable th) {
                errors.add(th);
            } finally {
                done.countDown();
            }
        });
    }
    start.countDown();
    assertTrue(done.await(15, TimeUnit.SECONDS));
    pool.shutdownNow();
    assertTrue(errors.isEmpty(), () -> "no unexpected exception class: " + errors);
}
```

Required imports added: `java.util.concurrent.*`, `java.util.concurrent.atomic.AtomicInteger`, `java.util.Queue`.

### Assistant deliberation

1. **Litmus**: if a developer "optimises" by introducing a static `StateMachine` field shared across calls, internal regions race and `successes.get() < threads * perThread`. Test fails. Real bug caught.
2. **Realism**: 16×25 transitions in 30s on CI is comfortable; Spring StateMachine builds are ~5ms each so 400 invocations is ~2s. Not flaky.
3. **Maintenance cost**: high-ish — concurrency tests are the most common source of CI flakes. Mitigation: bounded executor, deterministic latch, no `Thread.sleep`. Audit (`06-qa-engineer.md:95`) flagged 18 sleep-based tests; this test deliberately uses zero sleeps.

### Reconciled position

**Adopt the rewrite (option b).** Rename `testThreadSafety` → split into `testConcurrentTransitionsOnDisjointOrders` + `testConcurrentTransitionsOnSameOrder`. Delete the original. Path: `core-java/src/test/java/uk/jtoye/core/order/OrderStateMachineServiceTest.java`. Tag impact: this remains a `@SpringBootTest` (boots full context) — if it slows CI more than ~3s, refactor `OrderStateMachineService` to be testable without the full context (separate concern, not blocking).

---

## Finding 7 — Kill the `addFilters = false` shortcut

### Specialist proposal

`PaymentControllerTest.java:20` uses `@AutoConfigureMockMvc(addFilters = false)`, which removes `JwtTenantFilter`, `SecurityFilterChain`, CSRF, all of it. This means a missing `@PreAuthorize` is invisible — and the COUNCIL synthesis pre-prod blocker #7 ("no method-level authorization anywhere — `@PreAuthorize` count = 0") would not be caught here. The migration:

| Test class | Today | Recommended |
|---|---|---|
| `PaymentControllerTest` | `@WebMvcTest + addFilters=false` | Convert to `@SpringBootTest + @AutoConfigureMockMvc` (full chain), keep mocked `PaymentService` via `@MockitoBean`. Add `.with(jwt())` post-processor on requests. |
| `PaymentWebhookSignatureIntegrationTest` (new) | n/a | `@SpringBootTest`, no JWT (webhook is `/public/...` — bypasses auth by design). Document that exemption. |
| `JwtSecurityIntegrationTest` (new) | n/a | `@SpringBootTest + @AutoConfigureMockMvc`, full chain. |
| Other `@WebMvcTest` controller tests | several use `addFilters=false` | Keep web-slice for pure-handler logic (param mapping, JSON shapes, validation) but add **at least one** `*SecurityIntegrationTest` per controller that exercises the full chain with valid + invalid JWT. |

Trade-off: `@SpringBootTest` boots ~3-5s vs `@WebMvcTest` ~0.5s. With ~30 controller test files, this is potentially +90s CI runtime. Mitigation: reuse the Spring context via `@DirtiesContext(NONE)` (default), which means a single boot per test JVM — net cost ~5s.

```java
// REPLACE PaymentControllerTest.java with this hybrid approach
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private PaymentService paymentService;

    @Test
    void webhookSuccess_returns200WithStatusOk() throws Exception {
        // /public/payments/webhook is permitAll — no JWT required
        String payload = "{\"type\":\"payment_intent.succeeded\"}";
        String sig = "t=123,v1=abc";
        doNothing().when(paymentService).handleWebhookEvent(eq(payload), eq(sig));

        mvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", sig))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutJwt_returns401() throws Exception {
        // A regression guard: if anyone moves the webhook under /admin/... by
        // accident, this test will catch the missing auth.
        mvc.perform(post("/admin/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

### Assistant deliberation

1. **Litmus**: if `@PreAuthorize("hasRole('OWNER')")` is removed from a future `/admin/payments/refund` endpoint, the no-JWT test still returns 401 (auth required) — but a wrong-role test would catch role-bypass. Add a `@WithMockUser(roles = "STAFF")` test asserting 403 for OWNER-only endpoints once roles land.
2. **Realism**: full security chain in tests is slower but it is the *correct* slice for security regression. Trade-off acceptable.
3. **CI cost**: 30 controller tests × ~5s boot once shared = +5-10s, not +90s, because Spring context cache reuses across tests. Specialist's worst-case calculation was conservative — actual cost is small.

### Reconciled position

**Adopt the migration.** Convert `PaymentControllerTest` to `@SpringBootTest`. Audit-sweep the rest of `core-java/src/test/java/uk/jtoye/core/**/*ControllerTest.java` for `addFilters = false` and migrate one-by-one. Where pure-handler `@WebMvcTest` is genuinely useful (controller advice, JSON binding edge cases), keep the slice but **add a sibling `*SecurityIntegrationTest` that proves auth is enforced**. CI cost: net ~+10s acceptable trade for closing a real regression hole.

---

## Finding 8 — JaCoCo gates

### Specialist proposal

Java has **zero** branch-coverage visibility today (`06-qa-engineer.md:94, 116`). Add JaCoCo with per-package thresholds:

```kotlin
// APPEND to core-java/build.gradle.kts

plugins {
    // ...existing...
    jacoco
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // for Codecov
        html.required.set(true)  // for human review
        csv.required.set(false)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/dto/**",
                    "**/*Application.class",
                    "**/config/**",      // boilerplate Spring configs
                    "**/generated/**"
                )
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("uk.jtoye.core.payment")
            limit { counter = "LINE";   minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.65".toBigDecimal() }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("uk.jtoye.core.security")
            limit { counter = "LINE";   minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.70".toBigDecimal() }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("uk.jtoye.core.order")
            limit { counter = "LINE";   minimum = "0.75".toBigDecimal() }
        }
        rule {
            element = "BUNDLE"
            limit { counter = "LINE"; minimum = "0.60".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

Failure mode: `./gradlew check` fails when a threshold is missed → CI fails (the `Run Java tests` step in `ci-cd.yaml:55` invokes `:core-java:test` which is a `dependsOn` of `check` indirectly — adjust the workflow step to call `:core-java:check` instead).

### Assistant deliberation

1. **80% on `payment` is aggressive — what's the realistic baseline today?** From the QA audit's coverage matrix (`06-qa-engineer.md:60-74`): payment has signature path (mocked), state-transition path (mocked), idempotency (none), refund (none). Realistic baseline ~55-65% line, ~40-50% branch. The 80% line gate **would fail today**. That is the point — but it forces the new tests in findings 1-4 to land *before* the gate is enabled, otherwise CI is red on day 1.
2. **Perverse incentive**: 80% gates encourage developers to write trivial tests (`assertEquals(getId(), getId())`) to hit the number. **Mitigations**: (a) exclude trivial DTO/config packages already done in the gradle config; (b) add a code-review checklist item "no test that only asserts `assertNotNull(x)` or `assertEquals(x, x.getX())`"; (c) consider mutation testing (Pitest) as a Phase-2 follow-up to detect vanity tests directly. Sceptic strongly recommends adding Pitest in the same milestone if 80% is enforced.
3. **Will it catch the bug it claims?** A 80% line gate on `payment` does NOT catch the absence of an idempotency test — the existing successful-payment path covers >80% lines easily without ever exercising idempotency. **Coverage is a smoke detector, not a fire alarm**. The specific tests in findings 1-4 are the actual fire alarms; JaCoCo is the canary that something obvious broke.

### Reconciled position

**Phase the gates in two steps.**

- **Step 1 (with this remediation):** add JaCoCo, generate report, **upload to artifact + Codecov, do NOT fail the build yet**. Use the `jacocoTestReport` task, comment out `tasks.check.dependsOn(jacocoTestCoverageVerification)`. This produces the visibility without breaking CI.
- **Step 2 (after findings 1-4 land):** flip on the verification rules. Set initial thresholds at the realistic baseline (`payment` 70 line / 55 branch, `security` 75/65, `order` 70/—, bundle 55) and step them up by +5% per quarter. Document this in `core-java/build.gradle.kts` with a comment explaining the trajectory.

The 80% target stays — but only **after** the missing tests exist. Otherwise the gate is a wall the team's own honest-effort work will hit on day 1.

---

## Finding 9 — Playwright in CI

### Specialist proposal

`storefront-flows.spec.ts` (15 tests) is the strongest behavioural test in the repo and **does not gate merges** (`06-qa-engineer.md:93, 106`). Wire it as a CI job using `docker-compose` to bring up the stack. Workflow diff:

```yaml
# APPEND to .github/workflows/ci-cd.yaml under the existing `jobs:` block

  e2e-playwright:
    name: E2E (Playwright)
    needs: [test]
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Set up Node.js 20
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Boot the docker-compose stack
        run: |
          cp .env.example .env  || true
          docker compose -f docker-compose.yml up -d --wait --wait-timeout 300
        env:
          # Use prebuilt images from the test job's cache where possible
          BUILDKIT_INLINE_CACHE: 1

      - name: Wait for Keycloak + Core API health
        run: |
          for i in {1..60}; do
            curl -sf http://localhost:8080/actuator/health && break
            sleep 5
          done
          curl -sf http://localhost:8081/realms/jtoye/.well-known/openid-configuration

      - name: Install frontend deps and Playwright browsers
        working-directory: frontend
        run: |
          npm ci
          npx playwright install --with-deps chromium

      - name: Run Playwright (chromium-only on PR, full matrix on main)
        working-directory: frontend
        run: |
          if [ "${{ github.event_name }}" = "pull_request" ]; then
            npx playwright test --project=mobile storefront-flows.spec.ts
          else
            npx playwright test
          fi
        env:
          PLAYWRIGHT_BASE_URL: http://localhost:3000

      - name: Upload Playwright report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: frontend/playwright-report/
          retention-days: 14

      - name: Tear down stack
        if: always()
        run: docker compose down -v
```

### Assistant deliberation

1. **Stack startup time**: docker-compose for this project boots Postgres, Keycloak, Redis, RabbitMQ, MinIO, core-java, frontend, edge-go. Realistic boot ≈ 3-5 minutes from cold cache. The `--wait --wait-timeout 300` plus the explicit Keycloak readiness loop handle the longest poles. Acceptable.
2. **Browser matrix**: chromium-only on PR is correct (most signal per minute); full matrix (chromium mobile + desktop) on main protects against viewport regressions without paying the cost on every PR.
3. **Folding into existing test job vs separate job**: separate job is right. The existing `test` job runs unit/integration without docker-compose; mixing concerns there would slow every job. `needs: [test]` ensures unit failures short-circuit the more expensive E2E run.

Sceptic flag: `fullyParallel: false` in `playwright.config.ts:7` — comment says "tests share state". This means E2E runtime is roughly the sum of individual test times. Fine for now; revisit when adding vendor-admin specs (finding 10) which can usually parallelise.

### Reconciled position

**Adopt the workflow diff as written.** Place the new job after `security-scan` in `.github/workflows/ci-cd.yaml`. Make `build-and-push` depend on `[test, security-scan, e2e-playwright]` so a failing E2E blocks merge to main. Sequencing — this lands AFTER the existing `test` job succeeds (PR-side gate), so CI feedback latency only grows on slow paths.

---

## Finding 10 — Vendor admin E2E coverage gap

### Specialist proposal

Per QA audit (`06-qa-engineer.md:86`): vendor admin flows are NOT covered E2E. Top 5 specs in priority:

1. **`vendor-product-crud.spec.ts`** — login as vendor → /dashboard/products → create product (with image upload) → assert product card renders → edit price → assert change → delete → assert removed. Catches: image upload regressions, optimistic-locking conflicts, RLS leaks across vendor accounts.
2. **`vendor-order-management.spec.ts`** — login as vendor → /dashboard/orders → see new order from `storefront-flows.spec.ts` seed → progress through state machine (CONFIRM → START_PREP → MARK_READY → COMPLETE) → assert UI updates per transition. Pairs with `OrderStateMachineServiceTest`.
3. **`vendor-refund-flow.spec.ts`** — login as vendor → /dashboard/orders/:id → refund → assert Stripe refund call → assert order paymentStatus flips to REFUNDED → assert customer email sent. Pairs with finding 4 backend handler.
4. **`vendor-marketing.spec.ts`** — login → /dashboard/marketing → create promotion → assert appears on storefront → expire promotion → assert removed from storefront. Catches: tenant_id missing on shop_promotions (per V33 RLS fix history).
5. **`vendor-kds.spec.ts`** — login as kitchen role → /dashboard/kitchen → push an order via storefront in second tab → assert KDS receives via STOMP within 3s → mark ready → assert customer-side status updates. Pairs with `kitchen-flow.spec.ts` (extends single-test file).

### Assistant deliberation

1. **Pair coordination with frontend pair 05**: pair 05 owns the responsive sidebar refactor + design-token rebrand. If the dashboard chrome markup changes (sidebar `data-testid`s, route shape, etc.), specs 1-5 must be written **against the post-refactor markup**, not today's. **Sequencing**: do not write these specs until pair 05 lands its refactor, OR use semantic locators (`page.getByRole("button", { name: "Add product" })`) that survive markup churn. Specialist must use the latter.
2. **Litmus per spec**: spec 1 fails if image upload regresses. Spec 2 fails if a state machine transition is removed. Spec 3 fails if refund handler is broken (depends on finding 4). Spec 4 fails if RLS regresses on `shop_promotions`. Spec 5 fails if STOMP relay breaks. Each is a real fire alarm.
3. **Maintenance cost**: 5 specs × ~30 min each to write + 5 specs × ~15 min each per quarterly maintenance = ~3.75 hours/year ongoing. Cheap.

### Reconciled position

**Adopt with two constraints:** (a) all locators use `getByRole`/`getByLabel`/`getByTestId` — never CSS class selectors, to survive pair 05's rebrand; (b) specs are landed in priority order over the next 5 PRs, not all in one mega-PR. Spec 1 lands first as the canary for the test infrastructure; if spec 1 is reliable for two weeks, specs 2-5 follow. File location: `frontend/e2e/`.

---

## Finding 11 — Coverage report visibility

### Specialist proposal

Today only Go produces `coverage.out`. Add Jest `--coverage`, JaCoCo XML (per finding 8), wire all three to Codecov as separate flags.

```yaml
# IN .github/workflows/ci-cd.yaml — UPDATE the `Run frontend Jest tests` step

      - name: Run frontend Jest tests
        run: npm test -- --ci --watchAll=false --coverage
        working-directory: frontend

      - name: Run JaCoCo report (Java)
        run: ./gradlew :core-java:jacocoTestReport --no-daemon

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v4
        if: always()
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: |
            core-java/build-local/reports/jacoco/test/jacocoTestReport.xml
            frontend/coverage/lcov.info
            edge-go/coverage.out
          flags: java,frontend,go
          fail_ci_if_error: false

      - name: Upload coverage as artifact (fallback)
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: coverage-reports
          path: |
            core-java/build-local/reports/jacoco/
            frontend/coverage/
            edge-go/coverage.out
          retention-days: 14
```

### Assistant deliberation

1. **Codecov vs artifact**: Codecov gives PR comments and trend graphs but requires a token. GitHub Actions artifact is fallback — ensures coverage is always inspectable even if Codecov is misconfigured. Specialist correctly does both.
2. **Jest needs `collectCoverageFrom` config** in `frontend/package.json` or `jest.config.*` to scope coverage to source files (not node_modules). Verify: if `collectCoverageFrom` is missing, Jest defaults to "files touched by tests" which under-reports. Specialist must add a `jest.config.js` snippet or update `package.json` `jest:` block: `collectCoverageFrom: ["app/**/*.{ts,tsx}", "components/**/*.{ts,tsx}", "lib/**/*.{ts,tsx}", "!**/*.d.ts", "!**/*.stories.tsx"]`.
3. **`fail_ci_if_error: false`** — correct; Codecov outages should not block merges.

### Reconciled position

**Adopt with the `collectCoverageFrom` addition.** Keep `fail_ci_if_error: false`. Add a CODECOV_TOKEN secret to the repo settings (org-admin task — flag in handoff). For finding 8's coverage gates, the JaCoCo `verification` task handles that; Codecov is purely visibility, not enforcement.

---

## Finding 12 — Test-claim documentation drift

### Specialist proposal

CLAUDE.md says "390 Java `@Test` methods across 48 files + 76 Jest it/test blocks across 13 files + 50 top-level Go `Test*` funcs / 54 with `t.Run` subtests across 5 files = 516+ logical invocations". Audit verified actual is 432 / 61 files / 84 / 16 / 54 / 6 → 595+ total. Update CLAUDE.md and add a CI step that re-counts and fails if the claim drifts again.

CLAUDE.md edit (in the **Constraints** block, inside the project instructions):

```diff
-- **Testing**: All new code requires tests — project standard is 516+ logical invocations passing
-  (390 Java `@Test` methods across 48 files + 76 Jest `it/test` blocks across 13 files +
-  50 top-level Go `Test*` funcs / 54 with `t.Run` subtests across 5 files).
-  Verified 2026-04-18 post-v2.1.
+- **Testing**: All new code requires tests — project standard is 595+ logical invocations passing
+  (432 Java `@Test` methods across 61 files + 84 Jest `it/test` blocks across 16 files +
+  54 top-level Go `Test*` funcs across 6 files + 21 Playwright `test()` blocks across 4 specs).
+  18 of the 61 Java files use Testcontainers (PG, real RLS).
+  Verified 2026-04-27 by automated count (see `.github/workflows/ci-cd.yaml::docs-freshness`).
```

CI step that prevents future drift:

```yaml
# APPEND to .github/workflows/ci-cd.yaml

  docs-freshness:
    name: Docs Freshness Audit
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Verify CLAUDE.md test counts match reality
        run: |
          set -euo pipefail
          actual_java=$(grep -rh "@Test" core-java/src/test --include='*.java' | wc -l)
          actual_java_files=$(grep -rl "@Test" core-java/src/test --include='*.java' | wc -l)
          actual_jest=$(grep -rhE "^\s*(it|test)\(" frontend --include='*.ts' --include='*.tsx' \
                       --exclude-dir=node_modules --exclude-dir=.next | wc -l)
          actual_jest_files=$(grep -rlE "^\s*(it|test)\(" frontend --include='*.ts' --include='*.tsx' \
                             --exclude-dir=node_modules --exclude-dir=.next | wc -l)
          actual_go=$(grep -rh "^func Test" edge-go --include='*.go' | wc -l)
          actual_go_files=$(grep -rl "^func Test" edge-go --include='*.go' | wc -l)
          actual_pw=$(grep -rhE "^\s*test\(" frontend/e2e --include='*.ts' | wc -l)

          claim_java=$(grep -oE "[0-9]+ Java \`@Test\` methods" CLAUDE.md | grep -oE "^[0-9]+")
          claim_jest=$(grep -oE "[0-9]+ Jest" CLAUDE.md | grep -oE "^[0-9]+")
          claim_go=$(grep -oE "[0-9]+ top-level Go" CLAUDE.md | grep -oE "^[0-9]+")

          fail=0
          [ "$actual_java" = "$claim_java" ] || { echo "::error::Java @Test count drift: claim=$claim_java actual=$actual_java"; fail=1; }
          [ "$actual_jest" = "$claim_jest" ] || { echo "::error::Jest count drift: claim=$claim_jest actual=$actual_jest"; fail=1; }
          [ "$actual_go"   = "$claim_go"   ] || { echo "::error::Go test count drift: claim=$claim_go actual=$actual_go"; fail=1; }
          [ $fail -eq 0 ] || {
            echo "::error::Update CLAUDE.md 'Testing' constraint to actual counts: java=$actual_java jest=$actual_jest go=$actual_go pw=$actual_pw"
            exit 1
          }
          echo "All counts match: java=$actual_java jest=$actual_jest go=$actual_go pw=$actual_pw"
```

### Assistant deliberation

1. **Litmus**: if a developer adds a Java test without bumping CLAUDE.md, CI fails with a clear hint. The hint includes the actual numbers so the fix is one copy-paste. **Real fire alarm.**
2. **False-positive risk**: comment-out `@Test`, multi-line annotations, `@TestFactory`, etc. Specialist's grep counts naive `@Test` strings, which over-counts when a file has `@TestConfiguration` or comments. **Refinement**: use `grep -rh "^\s*@Test\b"` to anchor on line-start with whitespace and word-boundary. Without this, the count can drift even when no test was added.
3. **Maintenance cost**: <5 min per drift event. Net positive. Zero infrastructure dependencies.

### Reconciled position

**Adopt with the `^\s*@Test\b` anchor refinement.** Place the new job in `.github/workflows/ci-cd.yaml` at the top of `jobs:` (runs in parallel with `test`, fails fast). Update CLAUDE.md per the diff. Coordinate with the assistant's refinement: the grep patterns must use word-boundary anchors to avoid `@TestConfiguration`, `@TestPropertySource` false-positives.

---

## Dependency graph

```
                    ┌────────────────────────────────────────┐
                    │  Finding 12 (CLAUDE.md + docs-freshness) │  ← independent, land first
                    └────────────────────────────────────────┘
                                       │
   ┌───────────────────────────────────┼─────────────────────────────┐
   ▼                                   ▼                             ▼
┌──────────────────┐         ┌────────────────────┐       ┌──────────────────┐
│ F1 Stripe HMAC   │         │ F2 Idempotency      │       │ F6 Concurrency    │
│ test             │         │ test                 │       │ test rewrite      │
└──────────────────┘         └────────────────────┘       └──────────────────┘
   │                                   │                             │
   └───────────────┬───────────────────┘                             │
                   ▼                                                 │
       ┌─────────────────────────┐                                   │
       │ F8 JaCoCo Step 1        │                                   │
       │ (visibility, no gate)   │  ← depends on F1+F2+F6 to make    │
       └─────────────────────────┘    coverage numbers credible      │
                   │                                                 │
                   ▼                                                 │
       ┌─────────────────────────┐                                   │
       │ F8 JaCoCo Step 2        │                                   │
       │ (verification gates on) │                                   │
       └─────────────────────────┘                                   │
                                                                     │
   ┌─────────────────────────────────────────────────────────────────┘
   ▼
┌──────────────────┐         ┌────────────────────┐       ┌──────────────────┐
│ F3 JWT security  │         │ F7 Kill addFilters  │       │ F11 Coverage      │
│ (depends pair 02)│         │ shortcut             │       │ visibility        │
└──────────────────┘         └────────────────────┘       └──────────────────┘
                                                                     │
   ┌─────────────────────────────────────────────────────────────────┘
   ▼
┌──────────────────┐         ┌────────────────────┐       ┌──────────────────┐
│ F4 Refund test   │         │ F5 Go JWT decision  │       │ F9 Playwright CI  │
│ (depends pair 01)│         │ (founder-input)      │       │                   │
└──────────────────┘         └────────────────────┘       └──────────────────┘
                                                                     │
                                                                     ▼
                                                         ┌──────────────────┐
                                                         │ F10 Vendor admin  │
                                                         │ E2E (depends F9   │
                                                         │ + frontend pair 05)│
                                                         └──────────────────┘
```

---

## Wave breakdown

**Wave 1 (no external dependencies, ship immediately)**
- F12 — CLAUDE.md count update + docs-freshness CI step (~30 min)
- F1 — `PaymentWebhookSignatureIntegrationTest` (~1 hour)
- F2 — `GuestOrderIdempotencyIntegrationTest` (~2 hours)
- F6 — `OrderStateMachineService` concurrency rewrite (~1 hour)
- F11 — Jest `--coverage` + Codecov wiring (~1 hour)

**Wave 2 (depends on Wave 1 landing)**
- F8 step 1 — JaCoCo visibility, no gates (~30 min)
- F7 — kill `addFilters=false` in `PaymentControllerTest`, audit-sweep others (~3 hours)
- F3 — `JwtSecurityIntegrationTest` (3 live + 1 gated) (~2 hours, **needs pair 02 confirmation `JwtDecoder` is bean-overridable**)

**Wave 3 (depends on other pairs)**
- F4 — `RefundWebhookHandlingIntegrationTest` ships `@Disabled`, **enabled by pair 01** when handler lands (~1 hour to write, 0 min when 01 lands the handler)
- F5 — Go JWT decision: **needs founder input**. Default = delete pre-absorb; alternative = real JWKS test + negative sibling (~2 hours if path a)
- F9 — Playwright in CI (~3 hours including stack-warmup tuning)

**Wave 4 (depends on Waves 1-3 + frontend pair 05)**
- F8 step 2 — flip JaCoCo verification gates on (~1 hour, after F1/F2/F4 land)
- F10 — vendor-admin Playwright specs, one PR per spec, **after pair 05 ships sidebar refactor** (~30 min/spec × 5 = 2.5 hours)

**Total estimated effort**: 15-18 hours of focused work, spread across 3-4 PRs.

---

## Open questions

1. **Edge-go decision (F5)**: founder must commit to absorb-vs-keep before the test investment is sized. Audit recommends absorb. Default position here = delete the placeholder, mark in HANDOFF. Counter-position = invest 2 hours in path (a). Resolution forces sequencing of the `WhatsAppController` migration.
2. **JaCoCo gate trajectory (F8)**: starting at the realistic baseline (70/55) vs aspirational (80/65) — whichever the team picks, the *same* tests get written. The question is only whether CI is green on day 1 or red until F1-F4 land.
3. **Audience validator (F3)**: pair 02's audience-validator landing date determines when the gated 4th JWT test goes live. Is pair 02 in scope for Milestone 4?
4. **Refund handler signature (F4)**: pair 01 will design the signature of `handleChargeRefunded(Event event)`. The current test assumes (a) it reads `metadata.order_id` from the charge object, (b) it posts a negative `financial_transactions` row. If pair 01 chooses different conventions (e.g. extends `Refund` object retrieval via Stripe API), the test needs to be aligned.
5. **Vendor admin sidebar refactor (F10)**: until pair 05 confirms the post-refactor route shape and `data-testid` strategy, F10 specs cannot be reliably authored. Frontend pair must publish a "stable selectors contract" before E2E specs commit to it.
6. **Pitest mutation testing**: should this be added as Phase-2 follow-up to detect vanity-tests-against-the-gate? Sceptic strongly recommends yes; specialist defers to Milestone-5 budget.
7. **CODECOV_TOKEN**: org-admin must add the secret. Workflow uses it but does not block on its absence (`fail_ci_if_error: false`).
