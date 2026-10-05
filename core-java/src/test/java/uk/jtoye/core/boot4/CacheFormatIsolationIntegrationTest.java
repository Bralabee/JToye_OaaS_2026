package uk.jtoye.core.boot4;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.config.DatabaseConfigurationValidator;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 38 (38-09, D-01 hazard): a cache entry written by a Boot-3.5 pod is never read by a Boot-4
 * pod — on a real Redis, through the real {@code @Cacheable} path, with no operator flush.
 *
 * <p>The Boot-3.5 entry is the 38-01 golden {@code shops-ShopDto} value, byte for byte, planted under
 * the key a Boot-3.5 pod used for that shop ({@code shops::tenant:{tid}:getShopById:{id}}). The shop
 * exists in Postgres under the same id with a DIFFERENT name, so which source answered is visible in
 * the result. The Jackson-3 serializer cannot read the old bytes (CacheSerializerTypeAllowlistTest
 * measures that), so if the old key were read the GET would fail, {@link
 * uk.jtoye.core.config.RedisCacheErrorHandler} would swallow it and count it in
 * {@code jtoye.cache.errors}, and every request would pay that until the entry expired.
 *
 * <p>Asserted: the first call serves the DATABASE name with the error counter unchanged; Redis then
 * holds the {@code v4:shops::…} key with the tenant segment intact (T-38-25); the shop is renamed
 * behind the cache's back and the second call still returns the first name, so it was served from
 * the v4 key; the old entry is untouched; the counter stays 0 throughout.
 *
 * <p>Wiring follows {@code RedisFaultInjectionIntegrationTest}: the {@code dev} profile so
 * {@code CacheConfig} ({@code @Profile("!test")}) loads, real Postgres and Redis containers, and the
 * superuser validator neutralised (this proves cache format isolation, not RLS).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = "storage.blob.validate-on-startup=false")
@Tag("testcontainers")
@uk.jtoye.core.testsupport.AsSystemHarness
class CacheFormatIsolationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.timeout", () -> "1500ms");
    }

    @Autowired private ShopService shopService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private MeterRegistry meterRegistry;

    /** See RedisFaultInjectionIntegrationTest: the Testcontainers role is a superuser. */
    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    /** The 38-01 golden ids, so the planted value is exactly what Boot 3.5 cached for this shop. */
    private static final UUID TENANT = GoldenSamples.TENANT_ID;
    private static final UUID SHOP = GoldenSamples.SHOP_ID;
    private static final String DB_NAME = "Database Kitchen";
    private static final String KEY_SUFFIX = "tenant:" + TENANT + ":getShopById:" + SHOP;
    private static final String BOOT35_KEY = "shops::" + KEY_SUFFIX;
    private static final String BOOT4_KEY = "v4:shops::" + KEY_SUFFIX;

    private byte[] boot35Value;

    @BeforeEach
    void seed() {
        boot35Value = InFlightFixtures.bytes("cache/shops-ShopDto.bin");
        assertThat(new String(boot35Value, StandardCharsets.UTF_8))
                .as("the planted value names a different shop name than the database row")
                .contains("\"name\":\"Mama Put\"").doesNotContain(DB_NAME);

        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Cache Format Tenant");
        jdbcTemplate.update("DELETE FROM shops WHERE id = ?", SHOP);
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies) VALUES (?, ?, ?, ?, true, 0)",
                SHOP, TENANT, DB_NAME, "cache-format-isolation");

        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            c.serverCommands().flushAll();
            c.stringCommands().set(BOOT35_KEY.getBytes(StandardCharsets.UTF_8), boot35Value);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private double cacheErrors() {
        return meterRegistry.find("jtoye.cache.errors").counters().stream().mapToDouble(Counter::count).sum();
    }

    private Optional<ShopDto> readShop() {
        TenantContext.set(TENANT);
        try {
            return shopService.getShopById(SHOP);
        } finally {
            TenantContext.clear();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Every key in Redis, by SCAN (never KEYS), printed for the evidence record. */
    private List<String> scanKeys(RedisConnection c) {
        List<String> keys = new ArrayList<>();
        try (Cursor<byte[]> cursor = c.keyCommands().scan(ScanOptions.scanOptions().match("*").count(100).build())) {
            cursor.forEachRemaining(k -> keys.add(new String(k, StandardCharsets.UTF_8)));
        }
        keys.sort(String::compareTo);
        return keys;
    }

    @Test
    void aBoot35CacheEntryIsNeverReadAndTheBoot4EntryServesTheReadAfterWrite() throws Exception {
        double errorsBefore = cacheErrors();

        // 1. First call: the old-format entry sits under the Boot-3.5 key. It must not be read.
        Optional<ShopDto> first = readShop();
        assertThat(first).as("the shop is served").isPresent();
        assertThat(first.get().getName()).as("served from the DATABASE, not the Boot-3.5 entry")
                .isEqualTo(DB_NAME);
        assertThat(cacheErrors() - errorsBefore)
                .as("no cache GET error: the Boot-3.5 entry was never read (jtoye.cache.errors unchanged)")
                .isZero();

        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            List<String> keys = scanKeys(c);
            System.out.println("38-09 SCAN after first call: " + keys);
            assertThat(keys).as("the Boot-4 entry was written under the versioned key, tenant segment intact; "
                    + "the Boot-3.5 entry is still there, untouched")
                    .containsExactlyInAnyOrder(BOOT4_KEY, BOOT35_KEY);
            assertThat(sha256(c.stringCommands().get(BOOT35_KEY.getBytes(StandardCharsets.UTF_8))))
                    .as("the Boot-3.5 entry is byte-identical to the planted fixture")
                    .isEqualTo(sha256(boot35Value));
            Long ttl = c.keyCommands().ttl(BOOT4_KEY.getBytes(StandardCharsets.UTF_8));
            System.out.println("38-09 TTL of " + BOOT4_KEY + ": " + ttl + "s");
            assertThat(ttl).as("the shops TTL (15 min) applies to the v4 entry").isBetween(1L, 900L);
        }

        // 2. Rename the shop behind the cache's back. A second call that still returns the first
        //    name was served from Redis, i.e. from the v4 key: a read-after-write cache hit.
        jdbcTemplate.update("UPDATE shops SET name = ? WHERE id = ?", "Renamed Behind The Cache", SHOP);
        Optional<ShopDto> second = readShop();
        assertThat(second).isPresent();
        assertThat(second.get().getName()).as("the second call is a cache hit on the v4 key")
                .isEqualTo(DB_NAME);
        assertThat(second.get().getId()).isEqualTo(SHOP);

        assertThat(cacheErrors() - errorsBefore).as("jtoye.cache.errors stays 0 across both calls").isZero();
        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            System.out.println("38-09 SCAN after second call: " + scanKeys(c));
        }
    }
}
