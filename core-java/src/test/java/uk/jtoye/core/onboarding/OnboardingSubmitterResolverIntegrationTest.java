package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INT-4 (QA council 20260902-134741) on real Postgres 15 under a NOSUPERUSER role, so FORCE
 * RLS actually decides what the fallback can see. Proves the whole chain the fallback
 * recipient depends on, end to end through the real HTTP path:
 *
 * <ul>
 *   <li>a SUBMIT with a JWT records the subject on the Envers revision
 *       ({@code TenantRevisionListener}) AND refreshes the caller's {@code user_directory}
 *       row from the JWT email ({@code VendorOnboardingService.recordSubmitterInDirectory});</li>
 *   <li>{@link OnboardingSubmitterResolver} joins the two under the owning tenant's GUC;</li>
 *   <li>a foreign tenant's GUC sees NOTHING (the aud row and the directory row are both
 *       tenant-filtered) — the address never crosses the tenant wall;</li>
 *   <li>a revision whose subject has no directory row resolves to empty — fail-closed, never
 *       another user's address.</li>
 * </ul>
 *
 * <p>The external gate clients are {@code @MockBean}ed exactly as in
 * {@code OnboardingSubmitIntegrationTest}, so nothing leaves the JVM. Not
 * {@code @Transactional}: the after-commit recompute runs on another thread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingSubmitterResolverIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The stall notification is irrelevant here; keep the outbox flusher out of the way.
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
    }

    private static boolean downgraded = false;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OnboardingSubmitterResolver resolver;

    @MockBean private FhrsClient fhrsClient;
    @MockBean private CompaniesHouseClient companiesHouseClient;

    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA;

    @BeforeEach
    void seed() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        shopA = UUID.randomUUID();
        // NO contact_email on either tenant — the exact runtime condition INT-4 measured.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", tenantA, "A-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", tenantB, "B-" + tenantB);

        if (!downgraded) {
            // RLS must bite for the negative arm to mean anything: drop superuser on the app role.
            jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }

        inTenantTx(tenantA, () -> {
            jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                            + "VALUES (?, ?, ?, ?, ?, false, 0)",
                    shopA, tenantA, "shop-" + shopA, "slug-" + shopA.toString().substring(0, 8), "1 Test Street");
            return null;
        });

        when(fhrsClient.lookup(any(), any())).thenReturn(List.of());
        when(companiesHouseClient.lookup(any()))
                .thenThrow(new IllegalStateException("Companies House API key not configured (test)"));
    }

    private static RequestPostProcessor vendorJwt(UUID tenant, UUID subject, String email) {
        return jwt()
                .jwt(j -> j.subject(subject.toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", email)
                        .claim("preferred_username", "owner-" + subject.toString().substring(0, 8))
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** Run {@code work} in a transaction pinned to {@code tenant} (TenantSetLocalAspect applies the GUC). */
    private <T> T inTenantTx(UUID tenant, Supplier<T> work) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID createAndSubmit(UUID subject, String email) throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shopA);
        req.setCompanyNumber("12345678");
        String created = mockMvc.perform(post("/api/v1/onboarding")
                        .with(vendorJwt(tenantA, subject, email))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID onboardingId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/api/v1/onboarding/submit").with(vendorJwt(tenantA, subject, email)))
                .andExpect(status().isOk());
        return onboardingId;
    }

    @Test
    @DisplayName("submit with a JWT -> the resolver finds the submitter's email under the owning tenant (Envers subject + refreshed directory row)")
    void submitRecordsTheSubmitter_andTheResolverFindsTheirEmail() throws Exception {
        UUID subject = UUID.randomUUID();
        String email = "owner-" + subject.toString().substring(0, 8) + "@shop.test";

        UUID onboardingId = createAndSubmit(subject, email);

        Optional<String> found = inTenantTx(tenantA, () -> resolver.submitterEmail(onboardingId, tenantA));
        assertThat(found).contains(email);

        // Both links checked by content under the tenant GUC (an unpinned read would be RLS-blind).
        Integer requestRevisions = inTenantTx(tenantA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_aud a JOIN revinfo r ON r.rev = a.rev "
                        + "WHERE a.id = ? AND a.status = 'VERIFYING' AND r.user_id = ?",
                Integer.class, onboardingId, subject.toString()));
        assertThat(requestRevisions).as("Envers recorded the submitting subject on the VERIFYING revision").isEqualTo(1);
        String directoryEmail = inTenantTx(tenantA, () -> jdbc.queryForObject(
                "SELECT email FROM user_directory WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantA, subject));
        assertThat(directoryEmail).as("submit refreshed the caller's directory row from the JWT").isEqualTo(email);
    }

    @Test
    @DisplayName("RLS: a foreign tenant's GUC resolves NOTHING for the same onboarding id")
    void foreignTenantCannotResolveTheSubmitter() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID onboardingId = createAndSubmit(subject, "owner-" + subject.toString().substring(0, 8) + "@shop.test");

        // Control: visible to the owner.
        assertThat(inTenantTx(tenantA, () -> resolver.submitterEmail(onboardingId, tenantA))).isPresent();
        // Under tenant B both the aud row and the directory row are filtered out.
        assertThat(inTenantTx(tenantB, () -> resolver.submitterEmail(onboardingId, tenantB))).isEmpty();
    }

    @Test
    @DisplayName("fail-closed: a VERIFYING revision whose subject has no directory row resolves to empty")
    void subjectWithoutDirectoryRow_resolvesEmpty() {
        UUID onboardingId = UUID.randomUUID();
        UUID strangerSubject = UUID.randomUUID();
        inTenantTx(tenantA, () -> {
            jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, shop_id, model, status) "
                    + "VALUES (?, ?, ?, 'MARKETPLACE', 'VERIFYING')", onboardingId, tenantA, shopA);
            Integer rev = jdbc.queryForObject(
                    "INSERT INTO revinfo (rev, revtstmp, tenant_id, user_id) "
                            + "VALUES (nextval('revinfo_seq'), (extract(epoch from now()) * 1000)::bigint, ?, ?) RETURNING rev",
                    Integer.class, tenantA, strangerSubject.toString());
            jdbc.update("INSERT INTO vendor_onboarding_aud (id, rev, revtype, tenant_id, shop_id, model, status) "
                    + "VALUES (?, ?, 0, ?, ?, 'MARKETPLACE', 'VERIFYING')", onboardingId, rev, tenantA, shopA);
            return null;
        });

        assertThat(inTenantTx(tenantA, () -> resolver.submitterEmail(onboardingId, tenantA))).isEmpty();
    }
}
