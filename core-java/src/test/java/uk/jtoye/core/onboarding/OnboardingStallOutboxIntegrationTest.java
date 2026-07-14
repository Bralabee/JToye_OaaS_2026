package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Testcontainers (real Postgres 15) proof of the manual-review stall notification
 * seam (Phase 21 / D-01, plan 21-02 Task 2).
 *
 * <p>Seeds an onboarding parked in {@code VERIFYING} with one mandatory gate at
 * MANUAL_REVIEW and the rest PASSED/WAIVED, then invokes the REAL
 * {@code @Async @Transactional} {@link GateChainRunner#runAndRecompute} (the
 * Spring proxy — the recompute worker re-establishes the tenant GUC on its own
 * thread/connection). The recompute leaves the SM parked exactly as before and
 * writes a single {@code onboarding.events} row to the shared V46 outbox,
 * stamped with the seeded {@code tenant_id} (threat T-21-02-01: proven by the
 * tenant-scoped count). A second, unrelated tenant sees zero such rows.
 *
 * <p>The two external gate clients are mocked only to keep context startup
 * deterministic — no gate is (re)evaluated here because every seeded gate row is
 * already terminal (non-PENDING), so the runner's evaluation loop skips them.
 * The class is intentionally NOT {@code @Transactional}: the seeded rows must be
 * committed to be visible to the async worker's separate connection.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingStallOutboxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GateChainRunner gateChainRunner;
    @Autowired private VendorOnboardingRepository onboardingRepository;
    @Autowired private VendorOnboardingGateRepository gateRepository;

    // Mocked only so the FHRS / Companies House gate beans construct cleanly and
    // no real network call is ever possible; the seeded terminal gate rows mean
    // neither client is invoked by the recompute.
    @MockBean private FhrsClient fhrsClient;
    @MockBean private CompaniesHouseClient companiesHouseClient;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantAndShop() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "Mama's Kitchen " + shopId.toString().substring(0, 8),
                "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
    }

    @Test
    void manualReviewStall_writesTenantStampedOnboardingEventsOutboxRow() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        // One mandatory gate needs a human decision; the others are green ->
        // recompute stays in VERIFYING (the stall park case).
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.WAIVED);

        // Real @Async proxy: the worker re-establishes the tenant GUC and writes
        // the outbox row in its own transaction/connection.
        gateChainRunner.runAndRecompute(onboardingId, tenantId);

        awaitStallRow();

        // Exactly the stall notification, tenant-stamped, with the fixed shape.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM payment_event_outbox "
                        + "WHERE exchange = 'onboarding.events' AND tenant_id = ?",
                Integer.class, tenantId);
        assertThat(count).as("tenant-scoped onboarding.events row count").isGreaterThanOrEqualTo(1);

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM payment_event_outbox "
                        + "WHERE exchange = 'onboarding.events' AND tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId);
        assertThat(eventType).isEqualTo("ONBOARDING_STALLED");

        String routingKey = jdbc.queryForObject(
                "SELECT routing_key FROM payment_event_outbox "
                        + "WHERE exchange = 'onboarding.events' AND tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId);
        assertThat(routingKey).isEqualTo("onboarding.state.manual_review");

        // Payload is a well-formed OnboardingStateChangeEvent carrying the seeded
        // tenant + shop and a fixed, human-readable reason (no provider text).
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_event_outbox "
                        + "WHERE exchange = 'onboarding.events' AND tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId);
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(json.get("shopId").asText()).isEqualTo(shopId.toString());
        assertThat(json.get("onboardingId").asText()).isEqualTo(onboardingId.toString());
        assertThat(json.get("status").asText()).isEqualTo("VERIFYING");
        assertThat(json.get("reason").asText()).isEqualTo("One or more checks need a manual review");

        // No leakage: an unrelated tenant has no such row.
        UUID otherTenant = UUID.randomUUID();
        Integer otherCount = jdbc.queryForObject(
                "SELECT count(*) FROM payment_event_outbox "
                        + "WHERE exchange = 'onboarding.events' AND tenant_id = ?",
                Integer.class, otherTenant);
        assertThat(otherCount).isZero();
    }

    /** Poll the outbox until the stall row lands (async worker) or a bounded deadline lapses. */
    private void awaitStallRow() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM payment_event_outbox "
                            + "WHERE exchange = 'onboarding.events' AND tenant_id = ?",
                    Integer.class, tenantId);
            if (count != null && count >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out awaiting the onboarding.events stall row for tenant " + tenantId);
    }

    private UUID seedOnboarding(OnboardingState state) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        return onboardingRepository.saveAndFlush(onboarding).getId();
    }

    private void seedGate(UUID onboardingId, GateType type, GateStatus gateStatus) {
        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(onboardingId);
        gate.setGateType(type);
        gate.setStatus(gateStatus);
        gate.setMandatory(true);
        gateRepository.saveAndFlush(gate);
    }
}
