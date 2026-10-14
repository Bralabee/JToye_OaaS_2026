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
import uk.jtoye.core.config.CacheConfig;
import uk.jtoye.core.config.DatabaseConfigurationValidator;
import uk.jtoye.core.config.TenantCacheEvictor;
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
 * <p>A pre-37-G entry is planted beside it under {@code v4:shops::…} (37-G review, plan 37-50 finding
 * 2.9): a ShopDto the {@code v4} image cached, written by the production serializer, with a third name
 * and none of the four 37-48 fields. Those bytes READ cleanly into today's ShopDto (the fields come back
 * null), so no error would ever reveal it; only the {@code v5} key keeps it from being served.
 *
 * <p>Asserted: the first call serves the DATABASE name, fee and offered types with the error counter
 * unchanged; Redis then holds the {@code v5:shops::…} key with the tenant segment intact (T-38-25);
 * the shop is renamed behind the cache's back and the second call still returns the first name, so it
 * was served from the v5 key; both old entries are untouched; the counter stays 0 throughout. A second
 * arm (review WR-01, widened by the 37-G review) proves the converse direction for EVICTION: an eviction
 * removes the Boot-3.5 and the {@code v4} entries as well as the v5 one, for the evicting tenant only.
 *
 * <p>Wiring follows {@code RedisFaultInjectionIntegrationTest}: the {@code dev} profile so
 * {@code CacheConfig} ({@code @Profile("!test")}) loads, real Postgres and Redis containers, and the
 * superuser validator neutralised (this proves cache format isolation, not RLS).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
// 31.1-07 (D-19): a dev-profile context never reads the test profile, so it carries its own per-run
// random DSAR key (never a literal); without one DsarCipher refuses to start the context.
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}",
        // 37-28: ClientIpDigester refuses to start without a 64-hex key, and the dev profile has none
        // (CLIENT_IP_HMAC_KEY is a runtime secret); a per-run random key, the DSAR precedent above.
        "jtoye.storefront.client-ip-hmac-key=${random.value}${random.value}"})
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
    @Autowired private TenantCacheEvictor tenantCacheEvictor;

    /** See RedisFaultInjectionIntegrationTest: the Testcontainers role is a superuser. */
    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    /** The 38-01 golden ids, so the planted value is exactly what Boot 3.5 cached for this shop. */
    private static final UUID TENANT = GoldenSamples.TENANT_ID;
    private static final UUID SHOP = GoldenSamples.SHOP_ID;
    private static final String DB_NAME = "Database Kitchen";
    private static final String KEY_SUFFIX = "tenant:" + TENANT + ":getShopById:" + SHOP;
    private static final String BOOT35_KEY = "shops::" + KEY_SUFFIX;
    /** The key a pre-37-G (v4) pod cached this shop under, and the name its planted entry carries. */
    private static final String PRE37G_KEY = "v4:shops::" + KEY_SUFFIX;
    private static final String PRE37G_NAME = "Pre-37-G Cached Kitchen";
    /** The current key. A literal, not the constant: reverting the bump must red this test. */
    private static final String BOOT4_KEY = "v5:shops::" + KEY_SUFFIX;
    private static final long DB_FEE = 299L;

    private byte[] boot35Value;
    private byte[] pre37gValue;

    @BeforeEach
    void seed() {
        boot35Value = InFlightFixtures.bytes("cache/shops-ShopDto.bin");
        assertThat(new String(boot35Value, StandardCharsets.UTF_8))
                .as("the planted value names a different shop name than the database row")
                .contains("\"name\":\"Mama Put\"").doesNotContain(DB_NAME);

        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Cache Format Tenant");
        jdbcTemplate.update("DELETE FROM shops WHERE id = ?", SHOP);
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies) VALUES (?, ?, ?, ?, true, ?)",
                SHOP, TENANT, DB_NAME, "cache-format-isolation", DB_FEE);

        // What a pre-37-G (v4) pod cached for this shop: the production serializer over a ShopDto with
        // none of the four 37-48 fields set (NON_NULL, so they are absent from the bytes, exactly as the
        // v4 DTO, which had no such fields, wrote them).
        ShopDto pre37g = new ShopDto();
        pre37g.setId(SHOP);
        pre37g.setTenantId(TENANT);
        pre37g.setName(PRE37G_NAME);
        pre37g.setSlug("cache-format-isolation");
        pre37gValue = CacheConfig.jsonRedisSerializer().serialize(pre37g);
        assertThat(new String(pre37gValue, StandardCharsets.UTF_8))
                .as("the planted pre-37-G value carries none of the four 37-48 fields")
                .contains(PRE37G_NAME).doesNotContain("deliveryFeePennies").doesNotContain("freeDeliveryThresholdPennies")
                .doesNotContain("offersDelivery").doesNotContain("offersCollection");

        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            c.serverCommands().flushAll();
            c.stringCommands().set(BOOT35_KEY.getBytes(StandardCharsets.UTF_8), boot35Value);
            c.stringCommands().set(PRE37G_KEY.getBytes(StandardCharsets.UTF_8), pre37gValue);
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

        // 1. First call: the old-format entry sits under the Boot-3.5 key and the pre-37-G entry under
        //    the v4 key. Neither may be read.
        Optional<ShopDto> first = readShop();
        assertThat(first).as("the shop is served").isPresent();
        assertThat(first.get().getName()).as("served from the DATABASE, not the Boot-3.5 or the pre-37-G v4 entry")
                .isEqualTo(DB_NAME);
        assertThat(first.get().getDeliveryFeePennies())
                .as("the 37-48 fee is the database's, not the null a pre-37-G entry reads back as").isEqualTo(DB_FEE);
        assertThat(first.get().getOffersDelivery()).as("37-48 offersDelivery present").isTrue();
        assertThat(first.get().getOffersCollection()).as("37-48 offersCollection present").isTrue();
        assertThat(cacheErrors() - errorsBefore)
                .as("no cache GET error: the Boot-3.5 entry was never read (jtoye.cache.errors unchanged)")
                .isZero();

        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            List<String> keys = scanKeys(c);
            System.out.println("38-09 SCAN after first call: " + keys);
            assertThat(keys).as("the entry was written under the v5 key, tenant segment intact; "
                    + "the Boot-3.5 and pre-37-G v4 entries are still there, untouched")
                    .containsExactlyInAnyOrder(BOOT4_KEY, BOOT35_KEY, PRE37G_KEY);
            assertThat(sha256(c.stringCommands().get(BOOT35_KEY.getBytes(StandardCharsets.UTF_8))))
                    .as("the Boot-3.5 entry is byte-identical to the planted fixture")
                    .isEqualTo(sha256(boot35Value));
            assertThat(sha256(c.stringCommands().get(PRE37G_KEY.getBytes(StandardCharsets.UTF_8))))
                    .as("the pre-37-G v4 entry is byte-identical to what was planted")
                    .isEqualTo(sha256(pre37gValue));
            Long ttl = c.keyCommands().ttl(BOOT4_KEY.getBytes(StandardCharsets.UTF_8));
            System.out.println("38-09 TTL of " + BOOT4_KEY + ": " + ttl + "s");
            assertThat(ttl).as("the shops TTL (15 min) applies to the v5 entry").isBetween(1L, 900L);
        }

        // 2. Rename the shop behind the cache's back. A second call that still returns the first
        //    name was served from Redis, i.e. from the v5 key: a read-after-write cache hit.
        jdbcTemplate.update("UPDATE shops SET name = ? WHERE id = ?", "Renamed Behind The Cache", SHOP);
        Optional<ShopDto> second = readShop();
        assertThat(second).isPresent();
        assertThat(second.get().getName()).as("the second call is a cache hit on the v5 key")
                .isEqualTo(DB_NAME);
        assertThat(second.get().getId()).isEqualTo(SHOP);

        assertThat(cacheErrors() - errorsBefore).as("jtoye.cache.errors stays 0 across both calls").isZero();
        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            System.out.println("38-09 SCAN after second call: " + scanKeys(c));
        }
    }

    /**
     * Phase 38 review WR-01: an eviction on a Boot-4 pod must reach the Boot-3.5 generation too.
     * Without it, a write handled by a Boot-4 pod during the rolling deploy (or before a rollback) left
     * the {@code shops::…} entry that Boot-3.5 pods read in place until its TTL — for
     * {@code shopMembership}, a revoked grant still honoured.
     *
     * <p>Asserted on a real Redis, through the one funnel every eviction in main code uses
     * ({@link TenantCacheEvictor}): the v5 entry AND the planted Boot-3.5 and pre-37-G v4 entries for
     * this tenant's shop are gone (37-G review: a v4 pod still running during the deploy, or after a
     * rollback, must not keep serving what a v5 write evicted), while ANOTHER tenant's entries for the
     * same shop id — legacy, v4 and v5 — survive, so the extra evictions do not widen the tenant scope. {@code BOOT35_KEY} here is the literal key the
     * 38-01 golden value lives under, an anchor independent of
     * {@code CacheConfig.legacyBoot35CacheKey}.
     */
    @Test
    void anEvictionOnABoot4PodAlsoRemovesTheBoot35EntryForThatTenantOnly() throws Exception {
        UUID otherTenant = UUID.fromString("0ddba11d-0000-4000-8000-000000000038");
        String otherSuffix = "tenant:" + otherTenant + ":getShopById:" + SHOP;
        String otherBoot35Key = "shops::" + otherSuffix;
        String otherBoot4Key = "v5:shops::" + otherSuffix;
        String otherPre37gKey = "v4:shops::" + otherSuffix;

        // A v5 entry for TENANT via the real @Cacheable path (the Boot-3.5 and v4 ones are planted by
        // seed()), plus the other tenant's three entries.
        assertThat(readShop()).isPresent();
        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            c.stringCommands().set(otherBoot35Key.getBytes(StandardCharsets.UTF_8), boot35Value);
            c.stringCommands().set(otherBoot4Key.getBytes(StandardCharsets.UTF_8),
                    "other-tenant-v5".getBytes(StandardCharsets.UTF_8));
            c.stringCommands().set(otherPre37gKey.getBytes(StandardCharsets.UTF_8), pre37gValue);
            List<String> before = scanKeys(c);
            System.out.println("WR-01 SCAN before eviction: " + before);
            assertThat(before).as("instrument: all six entries exist before the eviction")
                    .containsExactlyInAnyOrder(BOOT4_KEY, BOOT35_KEY, PRE37G_KEY,
                            otherBoot4Key, otherBoot35Key, otherPre37gKey);
        }

        TenantContext.set(TENANT);
        try {
            tenantCacheEvictor.evictEntity("shops", "getShopById", SHOP);
        } finally {
            TenantContext.clear();
        }

        try (RedisConnection c = redisConnectionFactory.getConnection()) {
            // BOTH deletes are synchronous (evictIfPresent), so both are checked IMMEDIATELY, with no
            // wait. This real-Redis check is NOT the guard against a regression to the fire-and-forget
            // RedisCache.evict (PR #898 review round 1): on a local Redis the async DEL usually wins
            // the race, and with evict restored this test stayed green in 4 runs of 4. The
            // deterministic guard is TenantCacheEvictorTest's verifyNoMoreInteractions (red on evict).
            List<String> after = scanKeys(c);
            System.out.println("WR-01 SCAN immediately after eviction: " + after);
            assertThat(after).as("this tenant's v5, pre-37-G v4 AND Boot-3.5 entries are gone as soon as "
                    + "evictEntity returns; the other tenant's entries for the same shop id are untouched")
                    .containsExactlyInAnyOrder(otherBoot4Key, otherBoot35Key, otherPre37gKey);
            assertThat(sha256(c.stringCommands().get(otherBoot35Key.getBytes(StandardCharsets.UTF_8))))
                    .as("the other tenant's Boot-3.5 entry is byte-identical to what was planted")
                    .isEqualTo(sha256(boot35Value));
        }
    }
}
