package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ShopHasProductsException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #833 review round 1, finding 3: the live-product count and the shop DELETE were not atomic. A product
 * created into the shop between the two committed, the DELETE ran, and ON DELETE SET NULL orphaned it:
 * the exact defect #833 fixes.
 *
 * The race is made deterministic with a row lock instead of mocks (see the test). Not transactional on
 * purpose, so both sides really commit. The database now enforces the rule (V90 makes products.shop_id
 * ON DELETE RESTRICT) and the 23503 is mapped to the same typed 409.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopDeleteRaceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopService shopService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoBean private ShopAccessService shopAccessService;

    private UUID tenant;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", tenant, "test-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, false, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private void insertProductOnOwnConnection(UUID shopId, UUID productId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO products (id, tenant_id, sku, title, ingredients_text, shop_id, available) "
                             + "VALUES (?, ?, ?, 'Raced dish', 'Yam (100%)', ?, true)")) {
            c.setAutoCommit(true);
            ps.setObject(1, productId);
            ps.setObject(2, tenant);
            ps.setString(3, "RACE-" + productId.toString().substring(0, 8));
            ps.setObject(4, shopId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aProductCreatedBetweenTheCountAndTheDeleteIsNeverOrphaned() throws Exception {
        UUID shop = seedShop();
        UUID raced = UUID.randomUUID();

        // Deterministic race, no mocks. Connection L holds FOR NO KEY UPDATE on the shop row: that blocks
        // a DELETE of it, but not the FOR KEY SHARE an FK-checked product INSERT takes. So the service
        // counts 0 live products, then its DELETE waits on L; meanwhile a product is created and committed
        // on another connection; then L lets go and the DELETE runs.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement ps = lock.prepareStatement(
                    "SELECT id FROM shops WHERE id = ? FOR NO KEY UPDATE")) {
                ps.setObject(1, shop);
                ps.executeQuery();
            }
            Future<Throwable> deletion = pool.submit(() -> {
                TenantContext.set(tenant);
                try {
                    shopService.deleteShop(shop);
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    TenantContext.clear();
                }
            });
            long deadline = System.currentTimeMillis() + 15_000;
            while (waitingOnLock() == 0) {
                assertThat(System.currentTimeMillis()).as("the delete reached its blocked DELETE").isLessThan(deadline);
                Thread.sleep(50);
            }
            insertProductOnOwnConnection(shop, raced);   // the concurrent create commits
            lock.rollback();                              // L lets go: the blocked DELETE now runs

            Throwable thrown = deletion.get(30, TimeUnit.SECONDS);
            assertThat(thrown)
                    .as("the database refuses the delete and the service answers the typed 409")
                    .isInstanceOf(ShopHasProductsException.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shops WHERE id = ?", Integer.class, shop))
                .as("the shop is kept").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shop_id FROM products WHERE id = ?", UUID.class, raced))
                .as("and the product still belongs to it, never shop_id NULL").isEqualTo(shop);
    }

    private int waitingOnLock() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND state = 'active'",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void archivedProductsNeverBlockTheDeleteAndAreDetachedNotLost() {
        UUID shop = seedShop();
        UUID archived = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text, shop_id, available, archived_at) "
                + "VALUES (?, ?, ?, 'Old dish', 'Yam (100%)', ?, false, now())",
                archived, tenant, "ARCH-" + archived.toString().substring(0, 8), shop);

        shopService.deleteShop(shop);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shops WHERE id = ?", Integer.class, shop)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ? AND shop_id IS NULL", Integer.class, archived))
                .as("the archived row stays for order history, detached from the deleted shop").isEqualTo(1);
    }
}
