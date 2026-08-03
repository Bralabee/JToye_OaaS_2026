package uk.jtoye.core.shop;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #390 — {@code DELETE}/{@code PUT} on a promotion or announcement row that is not there
 * must answer a typed 404, never a 5xx and never a 409 "retry me".
 *
 * <p><strong>What actually produced the reported 500.</strong> Both services already guard the
 * <em>simple</em> absent-id case with {@code findById().orElseThrow(ResourceNotFoundException)},
 * so a random UUID was already a 404 (the four {@code unknownId} arms below are regression
 * guards, and are honestly reported as passing before the fix). The 5xx in the issue's log came
 * from the OTHER shape: the row was visible when the service READ it and gone when the service
 * WROTE it, so Hibernate's row-count check failed at flush —
 * {@code "Batch update returned unexpected row count from update [0] ... delete from
 * shop_announcements where id=?"} — surfacing as {@code ObjectOptimisticLockingFailureException}.
 * That window is reached by ordinary concurrent vendor activity (two staff deleting the same
 * promotion, a double-click, a retry after a slow response), which is why the issue calls it a
 * double-delete race rather than a malformed request.
 *
 * <p><strong>How the race is made deterministic.</strong> A real second request cannot be
 * scheduled into that window reliably, so the concurrent deleter is triggered FROM the window.
 * All four service methods call {@link ShopAccessService#require} between the {@code findById} and
 * the write, so {@code require} is a {@link MockitoSpyBean} that runs the REAL access check (the
 * authorization decision is unchanged) and then deletes the row on a SEPARATE, auto-committing JDBC
 * connection — deliberately not the transaction-bound one, so it is a genuinely independent
 * committed transaction. The services themselves are untouched and run their own real transaction
 * through the real controller, so the database sees exactly production's statement sequence: our
 * SELECT, someone else's committed DELETE, our write.
 *
 * <p><strong>Falsifiability.</strong> Run against the unfixed tree, the four {@code vanishes} arms
 * answered <b>500</b> ({@code Status expected:<404> but was:<500>}), with
 * {@code GlobalExceptionHandler}'s {@code Exception.class} catch-all logging the
 * {@code ObjectOptimisticLockingFailureException} — the issue's log, reproduced. They are the arms
 * that make this class evidence rather than decoration. The two positive controls (a live row still
 * deletes / updates) are the control arm: without them "answer 404 to everything" would pass this
 * suite. The four {@code unknownId} arms and the two repeat-delete arms passed BEFORE the fix as
 * well; they are honest regression guards, not proof of it.
 *
 * <p>Harness mirrors {@link uk.jtoye.core.security.access.CrossTenantAuthzIntegrationTest}: real
 * Postgres 15 + the Flyway-managed RLS schema, NOT {@code @Transactional} (seeded rows must
 * commit, and each service call must own its transaction exactly as it does in production), and
 * fresh random tenants/shops per test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MarketingMissingRowStatusIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @MockitoSpyBean private ShopAccessService shopAccessService;

    private static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";

    // ------------------------------------------------------------------
    // Regression guards: a genuinely absent id (already correct before the fix)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /promotions/{unknown id} is a typed 404")
    void deletePromotion_unknownId_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        mockMvc.perform(delete("/api/v1/promotions/" + UUID.randomUUID()).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    @Test
    @DisplayName("PUT /promotions/{unknown id} is a typed 404")
    void updatePromotion_unknownId_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        mockMvc.perform(put("/api/v1/promotions/" + UUID.randomUUID())
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(promotionJson(shop)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    @Test
    @DisplayName("DELETE /announcements/{unknown id} is a typed 404")
    void deleteAnnouncement_unknownId_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        mockMvc.perform(delete("/api/v1/announcements/" + UUID.randomUUID()).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    @Test
    @DisplayName("PUT /announcements/{unknown id} is a typed 404")
    void updateAnnouncement_unknownId_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        mockMvc.perform(put("/api/v1/announcements/" + UUID.randomUUID())
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announcementJson(shop)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    // ------------------------------------------------------------------
    // The issue's first acceptance criterion: delete, then delete again
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE a promotion twice: 204 then a typed 404")
    void deletePromotion_repeated_is204Then404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID promotion = seedPromotion(tenant, shop);

        mockMvc.perform(delete("/api/v1/promotions/" + promotion).with(vendor(tenant)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/promotions/" + promotion).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    @Test
    @DisplayName("DELETE an announcement twice: 204 then a typed 404")
    void deleteAnnouncement_repeated_is204Then404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID announcement = seedAnnouncement(tenant, shop);

        mockMvc.perform(delete("/api/v1/announcements/" + announcement).with(vendor(tenant)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/announcements/" + announcement).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));
    }

    // ------------------------------------------------------------------
    // The defect: the row vanishes between the service's read and its write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /promotions/{id} when the row vanishes mid-transaction is a typed 404, not a 5xx/409")
    void deletePromotion_rowVanishesMidTransaction_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID promotion = seedPromotion(tenant, shop);
        deleteFromUnderneath("shop_promotions", promotion);

        mockMvc.perform(delete("/api/v1/promotions/" + promotion).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));

        assertThat(rowExists("shop_promotions", promotion))
                .as("the concurrent deleter really did remove the row (the arm is not vacuous)")
                .isFalse();
    }

    @Test
    @DisplayName("PUT /promotions/{id} when the row vanishes mid-transaction is a typed 404, not a 5xx/409")
    void updatePromotion_rowVanishesMidTransaction_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID promotion = seedPromotion(tenant, shop);
        deleteFromUnderneath("shop_promotions", promotion);

        mockMvc.perform(put("/api/v1/promotions/" + promotion)
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(promotionJson(shop)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));

        assertThat(rowExists("shop_promotions", promotion)).isFalse();
    }

    @Test
    @DisplayName("DELETE /announcements/{id} when the row vanishes mid-transaction is a typed 404, not a 5xx/409")
    void deleteAnnouncement_rowVanishesMidTransaction_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID announcement = seedAnnouncement(tenant, shop);
        deleteFromUnderneath("shop_announcements", announcement);

        mockMvc.perform(delete("/api/v1/announcements/" + announcement).with(vendor(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));

        assertThat(rowExists("shop_announcements", announcement)).isFalse();
    }

    @Test
    @DisplayName("PUT /announcements/{id} when the row vanishes mid-transaction is a typed 404, not a 5xx/409")
    void updateAnnouncement_rowVanishesMidTransaction_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID announcement = seedAnnouncement(tenant, shop);
        deleteFromUnderneath("shop_announcements", announcement);

        mockMvc.perform(put("/api/v1/announcements/" + announcement)
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announcementJson(shop)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE));

        assertThat(rowExists("shop_announcements", announcement)).isFalse();
    }

    // ------------------------------------------------------------------
    // CONTROL ARM — without these, "answer 404 to everything" passes the suite
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CONTROL: a live promotion still deletes (204) and is really gone")
    void deletePromotion_liveRow_stillSucceeds() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID promotion = seedPromotion(tenant, shop);

        mockMvc.perform(delete("/api/v1/promotions/" + promotion).with(vendor(tenant)))
                .andExpect(status().isNoContent());

        assertThat(rowExists("shop_promotions", promotion)).isFalse();
    }

    @Test
    @DisplayName("CONTROL: a live announcement still updates (200) and the new title is persisted")
    void updateAnnouncement_liveRow_stillSucceeds() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID announcement = seedAnnouncement(tenant, shop);

        mockMvc.perform(put("/api/v1/announcements/" + announcement)
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announcementJson(shop)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated notice"));

        assertThat(jdbc.queryForObject(
                "SELECT title FROM shop_announcements WHERE id = ?", String.class, announcement))
                .isEqualTo("Updated notice");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Arm the concurrent deleter. {@code ShopAccessService.require} is the call every one of the
     * four service methods makes between loading the row and writing it, so running the REAL check
     * and then removing the row on a separate auto-committing connection lands the delete precisely
     * in the production race window. The connection is taken straight from the {@link DataSource},
     * NOT through {@code DataSourceUtils}, so it is not the request's transaction-bound connection
     * and its DELETE is committed independently — a second actor, not a self-inflicted state.
     */
    private void deleteFromUnderneath(String table, UUID id) {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                connection.setAutoCommit(true);
                statement.setObject(1, id);
                statement.executeUpdate();
            }
            return result;
        }).when(shopAccessService).require(any(UUID.class), any(ShopRole.class));
    }

    /** A day-one vendor: UUID subject (the 23-08 fail-closed gate) + tenant claim, no realm role. */
    private static RequestPostProcessor vendor(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenant.toString()));
    }

    private UUID seedTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "missing-row-tenant-" + tenant);
        return tenant;
    }

    private UUID seedShop(UUID tenant) {
        UUID shop = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shop, tenant, "shop-" + shop, "slug-" + shop, "1 Test Street, London");
        return shop;
    }

    private UUID seedPromotion(UUID tenant, UUID shop) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_percent, "
                        + "  valid_from, valid_until, active, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '30 days', true, NOW())",
                id, tenant, shop, "10% off", 10);
        return id;
    }

    private UUID seedAnnouncement(UUID tenant, UUID shop) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_announcements (id, tenant_id, shop_id, title, active, created_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW())",
                id, tenant, shop, "Store notice");
        return id;
    }

    private boolean rowExists(String table, UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
        return n != null && n > 0;
    }

    private static String promotionJson(UUID shop) {
        return "{\"label\":\"Updated promo\",\"discountType\":\"PERCENTAGE\",\"discountPercent\":15,"
                + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2030-01-01T00:00:00Z\","
                + "\"active\":true,\"shopId\":\"" + shop + "\"}";
    }

    private static String announcementJson(UUID shop) {
        return "{\"title\":\"Updated notice\",\"body\":\"We are open\",\"active\":true,"
                + "\"shopId\":\"" + shop + "\"}";
    }
}
