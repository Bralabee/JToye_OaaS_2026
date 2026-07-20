package uk.jtoye.core.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * End-to-end proof of the STOMP shop gate (23-11 / CR-02) through the REAL
 * {@link ShopAccessService} against real Postgres — the case a mocked gate cannot prove:
 *
 * <ul>
 *   <li><b>Day-one preservation</b> (the reason this whole gate is dangerous to over-tighten):
 *       an ungranted user under strict-scoping OFF must STILL be able to subscribe to any shop's
 *       kitchen feed, or every existing KDS client breaks. A mock could always return true here;
 *       only the real service's implicit-GROUP_ADMIN ladder proves it.</li>
 *   <li><b>CR-02 closure end-to-end</b>: under strict-scoping ON, a user granted only shop A is
 *       permitted shop A and rejected from an ungranted shop B — through the actual interceptor,
 *       the actual {@code canAccessShop}, and a real {@code shop_staff} read.</li>
 * </ul>
 *
 * <p>The interceptor is constructed directly (mock {@link JwtDecoder} — CONNECT is not exercised
 * here; the SUBSCRIBE path reads the identity off the session principal we set) with the wired
 * {@code ShopAccessService} bean. Strict scoping is toggled on the proxy-unwrapped bean via
 * {@link ReflectionTestUtils}, mirroring {@code ShopAccessEnforcementIntegrationTest}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class TenantChannelInterceptorShopGateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private ShopService shopService;
    @Autowired private JdbcTemplate jdbc;

    private TenantChannelInterceptor interceptor;
    private ShopAccessService targetService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        interceptor = new TenantChannelInterceptor(mock(JwtDecoder.class), shopAccessService);
    }

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void ungrantedUserUnderStrictScopingOffCanStillSubscribe() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID ungranted = UUID.randomUUID();  // ZERO shop_staff rows
        UUID anyShop = UUID.randomUUID();

        setStrictScoping(false);  // day-one posture

        Message<?> subscribe = subscribe(
                "/topic/kitchen/" + tenant + "/" + anyShop, tenant, jwt(ungranted));

        assertThatCode(() -> interceptor.preSend(subscribe, mock(MessageChannel.class)))
                .as("day-one preservation: an ungranted user under strict-scoping OFF is NOT broken")
                .doesNotThrowAnyException();
        assertThat(TenantContext.get()).as("no leaked tenant on the pooled inbound thread").isEmpty();
    }

    @Test
    void scopedUserPermittedGrantedShopDeniedUngrantedShopEndToEnd() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID user = UUID.randomUUID();
        UUID shopA = seedShop(tenant, "Shop A");  // real shops row (shop_staff FK)
        UUID shopB = UUID.randomUUID();  // no grant, no shops row needed (gate reads shop_staff)
        grantShopStaff(tenant, user, shopA, "STAFF");

        setStrictScoping(true);  // genuinely confine the scoped user

        // Granted shop A → permitted.
        assertThatCode(() -> interceptor.preSend(
                subscribe("/topic/kitchen/" + tenant + "/" + shopA, tenant, jwt(user)),
                mock(MessageChannel.class)))
                .as("a STAFF grant on shop A permits subscribing to shop A's kitchen feed")
                .doesNotThrowAnyException();

        // Ungranted shop B → rejected (CR-02 closed on the real transport).
        assertThatThrownBy(() -> interceptor.preSend(
                subscribe("/topic/kitchen/" + tenant + "/" + shopB, tenant, jwt(user)),
                mock(MessageChannel.class)))
                .as("the same STAFF user is denied an ungranted shop B — the KDS leak is closed")
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Shop-scoped subscription denied");

        assertThat(TenantContext.get()).as("no leaked tenant after a denied subscribe").isEmpty();
    }

    // --- helpers ---

    private void ensureTenant(UUID tenant) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "STOMP Gate Test Tenant " + tenant);
    }

    /** Create a real {@code shops} row (shop_staff.shop_id FK target) as a realm-admin. */
    private UUID seedShop(UUID tenant, String name) {
        return asRealmAdmin(tenant, () -> {
            CreateShopRequest req = new CreateShopRequest();
            req.setName(name);
            req.setAddress("1 Test Street, London");
            return shopService.createShop(req).getId();
        });
    }

    private <T> T asRealmAdmin(UUID tenant, Supplier<T> action) {
        authenticate(UUID.randomUUID(), true);
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(UUID sub, boolean realmAdmin) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("name", "Test User " + sub)
                .build();
        List<GrantedAuthority> authorities = realmAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, userId, shopId, role);
    }

    private Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private Message<?> subscribe(String destination, UUID sessionTenant, Jwt subscriber) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        accessor.setUser(new JwtAuthenticationToken(subscriber));
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("tenantId", sessionTenant);
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }
}
