package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 37-19: the four V80 columns round-trip through the real {@link Shop} mapping and Envers.
 *
 * <p>The stepwise migration test is plain JDBC, so it proves the SQL and nothing about the entity. This
 * class proves the other half: Hibernate writes {@code opening_schedule} in the declared
 * {@code {"open":"HH:MM","close":"HH:MM"}} shape (read back as raw JSON, not through the same mapper),
 * reads it back as {@link OpeningSchedule.TimeWindow}s (not as maps, which a mapping that dropped the
 * element type would produce), stores {@code hours_source} as its name, and the Envers revision of the
 * insert lands in {@code shops_aud} with the mirrored columns (a missing mirror throws at commit).
 *
 * <p>Not {@code @Transactional}: Envers writes the audit row at commit, which a rolled-back test
 * transaction never reaches.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopOpeningScheduleMappingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("opening_schedule, hours_source and the pause fields persist, reload as TimeWindows, and are audited")
    void scheduleSourceAndPauseRoundTripThroughJpaAndEnvers() throws Exception {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "V80 mapping " + tenant);
        TenantContext.set(tenant);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Map<String, List<OpeningSchedule.TimeWindow>> schedule = new LinkedHashMap<>();
        for (String day : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            schedule.put(day, List.of());
        }
        schedule.put("mon", List.of(window("09:00", "12:00"), window("13:00", "17:30")));
        schedule.put("fri", List.of(window("18:00", "02:00")));
        OffsetDateTime pausedUntil = OffsetDateTime.of(2026, 10, 9, 18, 40, 0, 0, ZoneOffset.UTC);

        UUID shopId = tx.execute(status -> {
            Shop shop = new Shop();
            shop.setTenantId(tenant);
            shop.setName("V80 mapping shop");
            shop.setSlug("v80-mapping-" + UUID.randomUUID());
            shop.setOpeningSchedule(schedule);
            shop.setHoursSource(HoursSource.STRUCTURED);
            shop.setOrdersPausedUntil(pausedUntil);
            shop.setOrdersPausedIndefinitely(true);
            return shopRepository.saveAndFlush(shop).getId();
        });

        // Raw column content, read with SQL rather than the mapper that wrote it.
        String rawJson = jdbc.queryForObject("SELECT opening_schedule::text FROM shops WHERE id = ?", String.class, shopId);
        JsonNode stored = JsonMapper.builder().build().readTree(rawJson);
        JsonNode expected = JsonMapper.builder().build().readTree("""
                {"mon":[{"open":"09:00","close":"12:00"},{"open":"13:00","close":"17:30"}],
                 "tue":[],"wed":[],"thu":[],"fri":[{"open":"18:00","close":"02:00"}],"sat":[],"sun":[]}""");
        assertThat(stored).as("stored opening_schedule JSON").isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT hours_source FROM shops WHERE id = ?", String.class, shopId))
                .isEqualTo("STRUCTURED");
        assertThat(jdbc.queryForObject("SELECT orders_paused_indefinitely FROM shops WHERE id = ?", Boolean.class, shopId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT orders_paused_until FROM shops WHERE id = ?", OffsetDateTime.class, shopId)
                .toInstant()).isEqualTo(pausedUntil.toInstant());

        // Reload through JPA in a fresh transaction: the elements must come back as TimeWindows.
        tx.executeWithoutResult(status -> {
            Shop reloaded = shopRepository.findById(shopId).orElseThrow();
            assertThat(reloaded.getOpeningSchedule()).isEqualTo(schedule);
            OpeningSchedule.TimeWindow friday = reloaded.getOpeningSchedule().get("fri").get(0);
            assertThat(friday.open()).isEqualTo(LocalTime.of(18, 0));
            assertThat(friday.overnight()).as("18:00 - 02:00 closes the next day").isTrue();
            assertThat(reloaded.getHoursSource()).isEqualTo(HoursSource.STRUCTURED);
            assertThat(reloaded.getOrdersPausedIndefinitely()).isTrue();
            assertThat(reloaded.getOrdersPausedUntil().toInstant()).isEqualTo(pausedUntil.toInstant());
        });

        // Envers: the insert revision carries the mirrored columns.
        Map<String, Object> aud = jdbc.queryForMap(
                "SELECT hours_source, opening_schedule::text AS schedule, orders_paused_indefinitely "
                        + "FROM shops_aud WHERE id = ? ORDER BY rev DESC LIMIT 1", shopId);
        assertThat(aud.get("hours_source")).isEqualTo("STRUCTURED");
        assertThat(JsonMapper.builder().build().readTree((String) aud.get("schedule"))).isEqualTo(expected);
        assertThat(aud.get("orders_paused_indefinitely")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("a shop saved without any of the new fields is unclassified and not paused (no default classification)")
    void aShopSavedWithoutTheNewFieldsIsUnclassifiedAndNotPaused() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "V80 default " + tenant);
        TenantContext.set(tenant);
        UUID shopId = new TransactionTemplate(transactionManager).execute(status -> {
            Shop shop = new Shop();
            shop.setTenantId(tenant);
            shop.setName("V80 default shop");
            shop.setSlug("v80-default-" + UUID.randomUUID());
            return shopRepository.saveAndFlush(shop).getId();
        });
        Map<String, Object> row = jdbc.queryForMap("SELECT hours_source, opening_schedule, orders_paused_until, "
                + "orders_paused_indefinitely FROM shops WHERE id = ?", shopId);
        assertThat(row.get("hours_source")).as("never defaulted: NULL = not classified").isNull();
        assertThat(row.get("opening_schedule")).isNull();
        assertThat(row.get("orders_paused_until")).isNull();
        assertThat(row.get("orders_paused_indefinitely")).isEqualTo(Boolean.FALSE);
    }

    private static OpeningSchedule.TimeWindow window(String open, String close) {
        return new OpeningSchedule.TimeWindow(LocalTime.parse(open), LocalTime.parse(close));
    }
}
