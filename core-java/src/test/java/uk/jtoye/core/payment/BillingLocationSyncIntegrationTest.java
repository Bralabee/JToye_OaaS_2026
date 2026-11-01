package uk.jtoye.core.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionItemUpdateParams;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 30 Plan 30-25 Task 3 (PAY-02 / D-04, T-30-98, T-30-99, T-30-100):
 * Testcontainers integration test proving after-commit per-location Stripe
 * subscription quantity sync on shop create and delete under {@code NOSUPERUSER NOBYPASSRLS}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class BillingLocationSyncIntegrationTest {

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername(DOWNGRADED_APP_ROLE)
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("stripe.api-key", () -> "sk_test_billing_sync_it");
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopRepository shopRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        txTemplate = new TransactionTemplate(transactionManager);
        if (DOWNGRADED.compareAndSet(false, true)) {
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER NOBYPASSRLS");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: app role must be NOSUPERUSER so FORCE RLS is genuinely enforced")
                .isFalse();

        tenantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, status, billing_plan_mode, subscription_status, "
                        + "stripe_customer_id, stripe_subscription_id, created_at) "
                        + "VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, now())",
                tenantId,
                "Subscribed Tenant " + tenantId.toString().substring(0, 8),
                BillingPlanMode.SAAS_HYBRID_STANDARD.name(),
                SubscriptionStatus.ACTIVE.name(),
                "cus_it_" + tenantId.toString().substring(0, 8),
                "sub_it_" + tenantId.toString().substring(0, 8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "owner@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private long countLocationsInTenant() {
        TenantContext.set(tenantId);
        try {
            Long count = txTemplate.execute(status -> shopRepository.countByTenantId(tenantId));
            return count != null ? count : 0L;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("30-25: createShop (1 -> 2) and deleteShop (2 -> 1) update Stripe SubscriptionItem quantity after commit")
    void createAndDeleteShop_updatesStripeSubscriptionItemQuantityAfterCommit() throws Exception {
        // Seed 1 existing shop for the tenant
        UUID existingShopId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            txTemplate.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                            + "VALUES (?, ?, 'First Shop', ?, true, 0, now())",
                    existingShopId, tenantId, "first-shop-" + existingShopId.toString().substring(0, 8)));
        } finally {
            TenantContext.clear();
        }
        assertThat(countLocationsInTenant()).isEqualTo(1L);

        String subId = "sub_it_" + tenantId.toString().substring(0, 8);
        AtomicLong currentStripeQty = new AtomicLong(1L);
        SubscriptionItem mockItem = mock(SubscriptionItem.class);
        when(mockItem.getId()).thenReturn("si_it_01");
        when(mockItem.getQuantity()).thenAnswer(inv -> currentStripeQty.get());
        when(mockItem.update(any(SubscriptionItemUpdateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> {
                    SubscriptionItemUpdateParams p = inv.getArgument(0);
                    currentStripeQty.set(p.getQuantity());
                    return mockItem;
                });

        SubscriptionItemCollection itemCollection = new SubscriptionItemCollection();
        itemCollection.setData(List.of(mockItem));
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getItems()).thenReturn(itemCollection);

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.when(() -> Subscription.retrieve(subId)).thenReturn(mockSub);

            // 1. Create a second shop -> after commit, Stripe SubscriptionItem quantity updates 1 -> 2
            String createResponse = mockMvc.perform(post("/api/v1/shops")
                            .with(adminJwt())
                            .header("X-Tenant-Id", tenantId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(Map.of("name", "Second Shop"))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            UUID secondShopId = UUID.fromString(jsonMapper.readTree(createResponse).path("id").asString());

            assertThat(countLocationsInTenant()).isEqualTo(2L);
            assertThat(currentStripeQty.get()).isEqualTo(2L);

            // 2. Delete the second shop -> after commit, Stripe SubscriptionItem quantity updates 2 -> 1
            mockMvc.perform(delete("/api/v1/shops/{id}", secondShopId)
                            .with(adminJwt())
                            .header("X-Tenant-Id", tenantId.toString()))
                    .andExpect(status().isNoContent());

            assertThat(countLocationsInTenant()).isEqualTo(1L);
            assertThat(currentStripeQty.get()).isEqualTo(1L);

            ArgumentCaptor<SubscriptionItemUpdateParams> paramsCaptor =
                    ArgumentCaptor.forClass(SubscriptionItemUpdateParams.class);
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            verify(mockItem, times(2)).update(paramsCaptor.capture(), optsCaptor.capture());

            assertThat(paramsCaptor.getAllValues())
                    .extracting(SubscriptionItemUpdateParams::getQuantity)
                    .containsExactly(2L, 1L);
            assertThat(optsCaptor.getAllValues().get(0).getIdempotencyKey())
                    .isEqualTo("billing-qty-si_it_01-2-shop-create-" + secondShopId);
            assertThat(optsCaptor.getAllValues().get(1).getIdempotencyKey())
                    .isEqualTo("billing-qty-si_it_01-1-shop-delete-" + secondShopId);
        }
    }

    @Test
    @DisplayName("30-25 (T-30-99/T-30-100): Stripe failure during after-commit sync does NOT roll back the committed shop and increments jtoye.billing.location_sync.failed")
    void createShop_whenStripeThrowsAfterCommit_commitsShopAndIncrementsFailedCounter() throws Exception {
        String subId = "sub_it_" + tenantId.toString().substring(0, 8);
        double failedBefore = meterRegistry.counter(BillingLocationSync.FAILED_METRIC).count();

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.when(() -> Subscription.retrieve(subId))
                    .thenThrow(new ApiConnectionException("Simulated Stripe outage on afterCommit"));

            String createResponse = mockMvc.perform(post("/api/v1/shops")
                            .with(adminJwt())
                            .header("X-Tenant-Id", tenantId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(Map.of("name", "Resilient Shop"))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            UUID createdShopId = UUID.fromString(jsonMapper.readTree(createResponse).path("id").asString());

            // Shop transaction committed and remains persisted in PostgreSQL despite Stripe outage
            assertThat(countLocationsInTenant())
                    .as("Shop write must stay committed when Stripe fails in afterCommit")
                    .isEqualTo(1L);
            TenantContext.set(tenantId);
            try {
                var persistedShop = txTemplate.execute(s -> shopRepository.findByIdAndTenantId(createdShopId, tenantId));
                assertThat(persistedShop).isPresent();
            } finally {
                TenantContext.clear();
            }

            double failedAfter = meterRegistry.counter(BillingLocationSync.FAILED_METRIC).count();
            assertThat(failedAfter - failedBefore)
                    .as("jtoye.billing.location_sync.failed counter must increment by 1 on Stripe failure")
                    .isEqualTo(1.0);
        }
    }
}
