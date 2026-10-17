package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V87 / ADR-0007 (GAP-GTM-02) integration proof for {@code shop_fulfilment_slots}:
 * <ul>
 *   <li>Tenant read/write isolation under {@code ENABLE + FORCE ROW LEVEL SECURITY} using a
 *       {@code NOSUPERUSER NOBYPASSRLS} role ({@code rls_test_role}).</li>
 *   <li>Anonymous storefront SELECT policy ({@code shop_fulfilment_slots_public_read}) exposing
 *       only {@code active = true} slots belonging to {@code published = true} shops when
 *       {@code current_tenant_id() IS NULL}.</li>
 *   <li>Single-statement atomic capacity reservation and release on {@link ShopFulfilmentSlotRepository}.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ScheduledFulfilmentSlotRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopFulfilmentSlotRepository slotRepository;

    private static final String RLS_TEST_ROLE = "rls_test_role";

    private UUID tenantA;
    private UUID tenantB;
    private UUID publishedShopA;
    private UUID unpublishedShopB;
    private UUID activeSlotA;
    private UUID inactiveSlotA;
    private UUID activeSlotUnpublishedB;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "  END IF; " +
                "  GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        publishedShopA = UUID.randomUUID();
        unpublishedShopB = UUID.randomUUID();
        activeSlotA = UUID.randomUUID();
        inactiveSlotA = UUID.randomUUID();
        activeSlotUnpublishedB = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "tenant-a-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "tenant-b-" + tenantB);

        TenantContext.set(tenantA);
        try {
            seedShop(publishedShopA, tenantA, "slot-shop-a-" + publishedShopA.toString().substring(0, 8), true);
            seedSlot(activeSlotA, tenantA, publishedShopA, LocalDate.of(2026, 10, 24), 2, 5, true);
            seedSlot(inactiveSlotA, tenantA, publishedShopA, LocalDate.of(2026, 10, 25), 10, 20, false);
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(tenantB);
        try {
            seedShop(unpublishedShopB, tenantB, "slot-shop-b-" + unpublishedShopB.toString().substring(0, 8), false);
            seedSlot(activeSlotUnpublishedB, tenantB, unpublishedShopB, LocalDate.of(2026, 10, 24), 10, 20, true);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    @Test
    @DisplayName("V87 RLS: Tenant A sees only its own slots and cannot see Tenant B's slots")
    void tenantAOnlySeesOwnSlots() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        Integer ownCount = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots WHERE tenant_id = ?", Integer.class, tenantA);
        Integer otherCount = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots WHERE tenant_id = ?", Integer.class, tenantB);
        Integer totalVisible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots", Integer.class);

        assertThat(ownCount).isEqualTo(2);
        assertThat(otherCount).isZero();
        assertThat(totalVisible).isEqualTo(2);
    }

    @Test
    @DisplayName("V87 RLS: Cross-tenant INSERT on shop_fulfilment_slots is blocked under NOSUPERUSER")
    void crossTenantInsertIsBlocked() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shop_fulfilment_slots " +
                        "(id, tenant_id, shop_id, service_date, window_start, window_end, cutoff_at, max_orders, max_units, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 10, 20, true)",
                UUID.randomUUID(), tenantB, unpublishedShopB,
                LocalDate.of(2026, 10, 31), LocalTime.of(12, 0), LocalTime.of(16, 0),
                OffsetDateTime.parse("2026-10-29T22:00:00Z")))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    @Test
    @DisplayName("V87 RLS: Cross-tenant UPDATE on shop_fulfilment_slots matches 0 rows under NOSUPERUSER")
    void crossTenantUpdateMatchesZeroRows() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        int updated = jdbc.update(
                "UPDATE shop_fulfilment_slots SET reserved_orders = 99 WHERE id = ?",
                activeSlotUnpublishedB);
        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("V87 RLS: Anonymous storefront (current_tenant_id() IS NULL) sees only active slots of published shops")
    void anonymousStorefrontSeesOnlyActiveSlotsOnPublishedShops() {
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        dropSuperuserForTransaction();

        Integer activePublishedVisible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots WHERE id = ?", Integer.class, activeSlotA);
        Integer inactivePublishedVisible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots WHERE id = ?", Integer.class, inactiveSlotA);
        Integer activeUnpublishedVisible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_fulfilment_slots WHERE id = ?", Integer.class, activeSlotUnpublishedB);

        assertThat(activePublishedVisible).isEqualTo(1);
        assertThat(inactivePublishedVisible).isZero();
        assertThat(activeUnpublishedVisible).isZero();
    }

    @Test
    @DisplayName("V87 Atomic Capacity: tryReserveCapacity enforces max_orders and max_units and releaseCapacity restores")
    void atomicCapacityReservationEnforcesCapsAndReleasesCleanly() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        OffsetDateTime beforeCutoff = OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC);

        // activeSlotA has maxOrders = 2, maxUnits = 5
        int r1 = slotRepository.tryReserveCapacity(activeSlotA, publishedShopA, 3, beforeCutoff);
        assertThat(r1).isEqualTo(1);

        // Next order asking for 3 units would exceed maxUnits (3 + 3 = 6 > 5) -> 0 rows updated
        int rExceedUnits = slotRepository.tryReserveCapacity(activeSlotA, publishedShopA, 3, beforeCutoff);
        assertThat(rExceedUnits).isZero();

        // Next order asking for 2 units fits (3 + 2 = 5 <= 5, orders = 2 <= 2) -> 1 row updated
        int r2 = slotRepository.tryReserveCapacity(activeSlotA, publishedShopA, 2, beforeCutoff);
        assertThat(r2).isEqualTo(1);

        // Slot now has 2/2 orders and 5/5 units; even 1 more unit is rejected
        int rExceedOrders = slotRepository.tryReserveCapacity(activeSlotA, publishedShopA, 1, beforeCutoff);
        assertThat(rExceedOrders).isZero();

        // Release the 2-unit order -> capacity opens for 1 order of up to 2 units
        int released = slotRepository.releaseCapacity(activeSlotA, tenantA, 2, beforeCutoff);
        assertThat(released).isEqualTo(1);

        int r3 = slotRepository.tryReserveCapacity(activeSlotA, publishedShopA, 2, beforeCutoff);
        assertThat(r3).isEqualTo(1);
    }

    @Test
    @DisplayName("V87 CHECK constraint: ck_shops_fulfilment_timing_mode rejects unknown modes")
    void shopFulfilmentTimingModeCheckRejectsUnknownMode() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, fulfilment_timing_mode, deposit_bps) " +
                        "VALUES (?, ?, 'Bad Mode Shop', ?, 'Test Address', false, 0, 'HYBRID', 10000)",
                UUID.randomUUID(), tenantA, "bad-mode-" + UUID.randomUUID().toString().substring(0, 8)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_shops_fulfilment_timing_mode");
    }

    @Test
    @DisplayName("V87 CHECK constraint: ck_shops_deposit_bps bounds deposit_bps between 1000 and 10000")
    void shopDepositBpsCheckRejectsOutOfRangeValue() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, fulfilment_timing_mode, deposit_bps) " +
                        "VALUES (?, ?, 'Bad Deposit Shop', ?, 'Test Address', false, 0, 'IMMEDIATE_ONLY', 500)",
                UUID.randomUUID(), tenantA, "bad-dep-" + UUID.randomUUID().toString().substring(0, 8)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_shops_deposit_bps");
    }

    private void seedShop(UUID id, UUID tenantId, String slug, boolean published) {
        jdbc.update(
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, fulfilment_timing_mode, deposit_bps) " +
                        "VALUES (?, ?, ?, ?, 'Test Address', ?, 0, 'HYBRID_IMMEDIATE_AND_SLOTS', 10000)",
                id, tenantId, "Shop " + slug, slug, published);
    }

    private void seedSlot(UUID id, UUID tenantId, UUID shopId, LocalDate serviceDate,
                          int maxOrders, int maxUnits, boolean active) {
        jdbc.update(
                "INSERT INTO shop_fulfilment_slots " +
                        "(id, tenant_id, shop_id, service_date, window_start, window_end, cutoff_at, " +
                        " max_orders, max_units, reserved_orders, reserved_units, active, version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 0, NOW(), NOW())",
                id, tenantId, shopId, serviceDate,
                LocalTime.of(14, 0), LocalTime.of(18, 0),
                OffsetDateTime.of(2026, 10, 22, 22, 0, 0, 0, ZoneOffset.UTC),
                maxOrders, maxUnits, active);
    }
}
