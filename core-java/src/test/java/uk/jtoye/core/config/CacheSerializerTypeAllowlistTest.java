package uk.jtoye.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.boot4.GoldenSamples;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.media.MediaAssetDto;
import uk.jtoye.core.media.MediaAssetStatus;
import uk.jtoye.core.product.AllergenSpan;
import uk.jtoye.core.product.dto.ProductAllergenWarning;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.access.Membership;
import uk.jtoye.core.shop.dto.ShopDto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * QA-council 20260902-134741 SEC-4 (adjudication A6), carried to Jackson 3 in Phase 38 (38-09) —
 * the Redis cache value serializer must (1) still round-trip every shape the cache actually stores
 * and (2) refuse to instantiate a type outside {@link CacheConfig#CACHE_TYPE_ID_PREFIXES} that a
 * stored entry names.
 *
 * <p>The ORDER of those two is the whole design of this class. The finding was fixed by replacing
 * Jackson's permissive validator with a {@code BasicPolymorphicTypeValidator} allowlist, and a
 * too-narrow allowlist does not fail loudly: it throws on the cache GET, which
 * {@link RedisCacheErrorHandler#handleCacheGetError} WARN-logs and swallows, so the request silently
 * falls through to the database on every call. A green refusal arm over a dead cache is the exact
 * failure this project records as {@code trap_structural_green_over_dead_feature}. So the round-trip
 * arms come first, are fully populated (every field non-null, every collection non-empty), and go
 * through {@link CacheConfig#jsonRedisSerializer()} — the production serializer, not a mirror.
 *
 * <p>Phase 38 re-derived the allowlist from the bytes the Jackson-3 serializer writes, because its
 * typing scheme differs from the Jackson-2 one: {@link #theTypeIdsInTheBytesAreExactlyTheReDerivedSet}
 * pins every id it writes, and {@link #everyAllowlistPrefixCoversAnObservedTypeIdAndEveryIdIsCovered}
 * ties the prefix list to those ids in both directions, so a prefix added without an observed id
 * fails here as surely as a missing one fails the round-trips.
 *
 * <p>Plain unit test: no Spring context, no Docker. This is not the liveness proof — that is the
 * read-after-write against a real Redis in {@code CacheFormatIsolationIntegrationTest}, and on the
 * rebuilt runtime the check that {@code jtoye.cache.errors} stays 0.
 */
class CacheSerializerTypeAllowlistTest {

    private final RedisSerializer<Object> serializer = CacheConfig.jsonRedisSerializer();

    /** Reads the stored bytes as a plain tree (no typing) so the type ids can be listed. */
    private static final JsonMapper PLAIN = JsonMapper.builder().build();

    /** A JVM binary class name: dotted lower-case package, then a capitalised simple name. */
    private static final Pattern CLASS_NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*\\.[A-Z][\\w$]*");

    /**
     * UTC on purpose: the java.time deserializer adjusts to the context zone by default, so a +01:00
     * fixture would come back as the same instant at Z. The golden ShopDto sample IS at +01:00, so
     * its round-trip compares OffsetDateTime by instant ({@link #BY_INSTANT}).
     */
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-02T09:15:00Z");

    private static final Comparator<OffsetDateTime> BY_INSTANT = Comparator.comparing(OffsetDateTime::toInstant);

    /**
     * A cached-DTO-shaped holder of a money-precise member. No cached DTO carries a
     * {@code BigDecimal} today; this shows what one would write if it did (PR #726 review).
     */
    public record MoneyField(BigDecimal amount) {
    }

    private static ProductDto fullyPopulatedProduct() {
        ProductDto p = new ProductDto();
        p.setId(UUID.randomUUID());
        p.setSku("SKU-SEC4-001");
        p.setTitle("Jollof Rice");
        p.setIngredientsText("Rice, tomatoes, peppers, groundnut oil");
        p.setAllergenMask(0b0000_0000_0010_0000);
        p.setPricePennies(899L);                 // Long
        p.setVatRate(VatRate.ZERO);              // enum
        p.setCreatedAt(CREATED_AT);              // OffsetDateTime
        p.setDescription("Party-size portion");
        p.setImageUrl("https://cdn.example/jollof.webp");
        p.setCategory("Mains");
        p.setDisplayOrder(3);
        p.setAvailable(Boolean.TRUE);
        p.setFeatured(Boolean.FALSE);
        p.setPreparationTimeMinutes(25);
        p.setDietaryTags("halal,gluten-free");
        p.setShopId(UUID.randomUUID());
        p.setQuantityInStock(12);
        p.setAdditionalImageUrls(List.of("https://cdn.example/1.webp", "https://cdn.example/2.webp"));
        p.setShelfLifeDays(2);
        p.setDurabilityType("USE_BY");
        p.setAllergenSpans(List.of(new AllergenSpan(0, 4), new AllergenSpan(24, 33)));
        p.setMedia(List.of(new MediaAssetDto(UUID.randomUUID(), MediaAssetStatus.ACTIVE, false, null,
                "https://cdn.example/a.webp", "https://cdn.example/a-thumb.webp", 800, 600, false, false)));
        // 31.1-06 (#787): the derived warning list, as ProductMapper builds it (an ArrayList of a
        // record). A by-id product read is cached, so a product whose ingredients name an
        // undeclared allergen must come back from Redis with its warning intact.
        p.setAllergenWarnings(new ArrayList<>(List.of(
                ProductAllergenWarning.undeclaredIngredientAllergen(6, "Milk"))));
        return p;
    }

    private static ShopDto fullyPopulatedShop() {
        ShopDto s = new ShopDto();
        s.setId(UUID.randomUUID());
        s.setTenantId(UUID.randomUUID());
        s.setName("Mama Put");
        s.setAddress("12 Rye Lane, Peckham");
        s.setCreatedAt(CREATED_AT);
        s.setSlug("mama-put");
        s.setDescription("West African kitchen");
        s.setLogoUrl("https://cdn.example/logo.webp");
        s.setBannerUrl("https://cdn.example/banner.webp");
        s.setPhone("+44 20 7946 0000");
        s.setEmail("hello@example.com");
        s.setLatitude(51.4736);
        s.setLongitude(-0.0693);
        Map<String, String> hours = new LinkedHashMap<>();
        hours.put("mon", "10:00-22:00");
        hours.put("sun", "closed");
        s.setOpeningHours(hours);                // java.util. map
        s.setDeliveryInfo("Delivery within 3 miles");
        s.setMinimumOrderPennies(1500L);         // Long again
        s.setPublished(Boolean.TRUE);
        s.setTags("nigerian,halal");
        return s;
    }

    private String json(Object value) {
        return new String(serializer.serialize(value), StandardCharsets.UTF_8);
    }

    /** Every type id in a stored value: {@code @class} properties and {@code ["<class>", value]} wrappers. */
    private static void collectTypeIds(JsonNode node, Set<String> ids) {
        if (node.isObject()) {
            JsonNode cls = node.get("@class");
            if (cls != null && cls.isString()) {
                ids.add(cls.stringValue());
            }
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                collectTypeIds(e.getValue(), ids);
            }
        } else if (node.isArray()) {
            if (node.size() == 2 && node.get(0).isString() && CLASS_NAME.matcher(node.get(0).stringValue()).matches()) {
                ids.add(node.get(0).stringValue());
                collectTypeIds(node.get(1), ids);
            } else {
                for (JsonNode element : node.values()) {
                    collectTypeIds(element, ids);
                }
            }
        }
    }

    private Set<String> typeIdsWrittenFor(Object... samples) {
        Set<String> ids = new TreeSet<>();
        for (Object sample : samples) {
            collectTypeIds(PLAIN.readTree(serializer.serialize(sample)), ids);
        }
        return ids;
    }

    /**
     * The derivation inputs: the 38-01 golden samples, which carry the collection RUNTIME classes
     * production builds (MapStruct ArrayList / LinkedHashMap, {@code Stream.toList}, {@code Map.copyOf}),
     * the empty-grant membership ({@code Map.of()}), and the two money samples.
     */
    private static Object[] derivationSamples() {
        return new Object[]{GoldenSamples.productDto(), GoldenSamples.shopDto(), GoldenSamples.membership(),
                new Membership(true, true, Map.of()), new BigDecimal("19.99"),
                new MoneyField(new BigDecimal("19.99"))};
    }

    // ---- the serializer itself ---------------------------------------------------------------

    @Test
    void theProductionSerializerIsTheJackson3GenericSerializer() {
        assertThat(CacheConfig.jsonRedisSerializer())
                .as("Phase 38: the cache value serializer is Spring Data Redis 4's Jackson-3 serializer")
                .isInstanceOf(GenericJacksonJsonRedisSerializer.class);
    }

    // ---- round-trip arms FIRST (the liveness half of the fix) --------------------------------

    @Test
    void productDtoRoundTripsThroughTheProductionSerializerWithEveryFieldIntact() {
        ProductDto original = fullyPopulatedProduct();

        Object back = serializer.deserialize(serializer.serialize(original));

        assertThat(back).as("the cached value comes back as the concrete DTO, not a Map")
                .isInstanceOf(ProductDto.class);
        assertThat(back).usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class)
                .as("Long, OffsetDateTime, enum, List<String>, List<record> and nested media all survive")
                .isEqualTo(original);
    }

    @Test
    void shopDtoRoundTripsThroughTheProductionSerializerWithEveryFieldIntact() {
        ShopDto original = fullyPopulatedShop();

        Object back = serializer.deserialize(serializer.serialize(original));

        assertThat(back).isInstanceOf(ShopDto.class);
        assertThat(back).usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class)
                .as("Map<String,String>, Double, Long, OffsetDateTime and UUID all survive")
                .isEqualTo(original);
    }

    /**
     * The 38-01 golden samples, whose collections are the runtime classes production builds
     * ({@code ImmutableCollections$ListN} media, MapStruct {@code ArrayList}/{@code LinkedHashMap})
     * and whose ShopDto is at +01:00 — compared by instant.
     */
    @Test
    void theGoldenSamplesRoundTripWithProductionCollectionTypes() {
        Object product = serializer.deserialize(serializer.serialize(GoldenSamples.productDto()));
        Object shop = serializer.deserialize(serializer.serialize(GoldenSamples.shopDto()));
        Object membership = serializer.deserialize(serializer.serialize(GoldenSamples.membership()));

        assertThat(product).isInstanceOf(ProductDto.class).usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class).isEqualTo(GoldenSamples.productDto());
        assertThat(shop).isInstanceOf(ShopDto.class).usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class).isEqualTo(GoldenSamples.shopDto());
        assertThat(membership).as("Membership is a record: value equality").isEqualTo(GoldenSamples.membership());
    }

    /**
     * PR #726 review low (c): a {@code BigDecimal} is not a final JDK type, so the serializer stores it
     * WITH an explicit {@code java.math.BigDecimal} id — top-level and as a member of a cached
     * DTO-shaped value — and without {@code java.math.} the READ is refused, which
     * {@link RedisCacheErrorHandler#handleCacheGetError} swallows into a permanent silent miss the
     * moment any cached DTO grows a money-precise field.
     */
    @Test
    void aBigDecimalRoundTripsThroughTheProductionSerializer() {
        BigDecimal original = new BigDecimal("19.99");
        String json = json(original);
        assertThat(json).as("stored with an explicit id, which is why java.math. must be allowlisted")
                .contains("\"java.math.BigDecimal\"");

        Object back = serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));

        assertThat(back).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) back).isEqualByComparingTo(original);

        MoneyField holder = new MoneyField(new BigDecimal("12.50"));
        assertThat(json(holder)).as("a BigDecimal MEMBER carries the id too")
                .contains("[\"java.math.BigDecimal\",12.50]");
        assertThat(serializer.deserialize(serializer.serialize(holder))).isEqualTo(holder);
    }

    // ---- the re-derivation, pinned -----------------------------------------------------------

    /**
     * Phase 38 re-derived the type ids from the LIVE Jackson-3 bytes. The serializer types every
     * non-final value: DTO classes and records by {@code @class}, collections and maps by their
     * runtime class, and {@code BigDecimal}. Final JDK types ({@code Long}, {@code UUID},
     * {@code OffsetDateTime}, {@code String}) and enums are written bare, so the Jackson-2 ids
     * {@code java.lang.Long}, {@code java.util.UUID} and {@code java.time.OffsetDateTime} are gone.
     */
    @Test
    void theTypeIdsInTheBytesAreExactlyTheReDerivedSet() {
        assertThat(typeIdsWrittenFor(derivationSamples())).containsExactlyInAnyOrder(
                "uk.jtoye.core.product.dto.ProductDto",
                "uk.jtoye.core.product.AllergenSpan",
                "uk.jtoye.core.media.MediaAssetDto",
                "uk.jtoye.core.shop.dto.ShopDto",
                "uk.jtoye.core.security.access.Membership",
                "uk.jtoye.core.config.CacheSerializerTypeAllowlistTest$MoneyField",
                "java.util.ArrayList",
                "java.util.ImmutableCollections$ListN",
                "java.util.LinkedHashMap",
                "java.util.ImmutableCollections$Map1",
                "java.util.ImmutableCollections$MapN",
                "java.math.BigDecimal");

        String product = json(fullyPopulatedProduct());
        assertThat(product).as("Long and OffsetDateTime members are written bare")
                .contains("\"pricePennies\":899")
                .contains("\"createdAt\":\"2026-09-02T09:15:00Z\"")
                .doesNotContain("java.lang.", "java.time.", "java.util.UUID");
    }

    /**
     * The allowlist and the observed ids, tied in both directions: every id the serializer writes
     * for the derivation samples is covered by a prefix (else the round-trips die silently), and
     * every prefix covers at least one observed id (else it was added without evidence).
     */
    @Test
    void everyAllowlistPrefixCoversAnObservedTypeIdAndEveryIdIsCovered() {
        Set<String> ids = typeIdsWrittenFor(derivationSamples());

        assertThat(ids).allSatisfy(id -> assertThat(CacheConfig.CACHE_TYPE_ID_PREFIXES)
                .as("a prefix covering %s", id).anySatisfy(prefix -> assertThat(id).startsWith(prefix)));
        Set<String> usedPrefixes = CacheConfig.CACHE_TYPE_ID_PREFIXES.stream()
                .filter(prefix -> ids.stream().anyMatch(id -> id.startsWith(prefix)))
                .collect(Collectors.toSet());
        assertThat(usedPrefixes).as("no allowlist prefix without an observed type id")
                .containsExactlyInAnyOrderElementsOf(CacheConfig.CACHE_TYPE_ID_PREFIXES);
    }

    // ---- refusal arm (the security half) -----------------------------------------------------

    /**
     * The arm that flipped in SEC-4: on the pre-fix serializer {@code java.net.URI} INSTANTIATED from
     * this payload. It is deliberately a type Jackson's internal denylist does NOT cover, so the only
     * thing that can refuse it is the allowlist — a denylisted gadget would pass this test on the old
     * code too.
     *
     * <p>Top-level on purpose: the nominal base is {@code java.lang.Object}, which is exactly where a
     * base-type matcher would have degraded to the permissive validator (see the
     * {@code CACHE_TYPE_ID_PREFIXES} Javadoc).
     */
    @Test
    void aTypeOutsideTheAllowlistIsRefusedEvenUnderTheObjectBase() {
        byte[] hostile = "[\"java.net.URI\",\"https://attacker.example/\"]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.deserialize(hostile))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidTypeIdException.class)
                .rootCause()
                .hasMessageContaining("java.net.URI");
    }

    /**
     * BOUNDARY CONTROL, not load-bearing, labelled as such: this gadget base is outside the allowlist
     * AND on Jackson's internal denylist, so it cannot distinguish the two. It is here to name the
     * failure if a future edit allowlists {@code org.springframework.} wholesale.
     */
    @Test
    void aKnownGadgetBaseStaysRefused() {
        byte[] gadget = "[\"org.springframework.beans.factory.config.PropertyPathFactoryBean\",{}]"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.deserialize(gadget))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidTypeIdException.class)
                .rootCause()
                .hasMessageContaining("PropertyPathFactoryBean");
    }

    /**
     * The re-derivation NARROWED the allowlist: {@code java.lang.} and {@code java.time.} carried no
     * observed id under Jackson 3, so they were dropped, and a stored id under either is now refused —
     * including the Jackson-2 {@code ["java.lang.Long", 899]} wrapper itself.
     */
    @Test
    void theDroppedJackson2PrefixesAreNowRefused() {
        byte[] jackson2Long = "[\"java.lang.Long\",899]".getBytes(StandardCharsets.UTF_8);
        byte[] jackson2Date = "[\"java.time.OffsetDateTime\",\"2026-09-02T09:15:00Z\"]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.deserialize(jackson2Long))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidTypeIdException.class)
                .rootCause().hasMessageContaining("java.lang.Long");
        assertThatThrownBy(() -> serializer.deserialize(jackson2Date))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidTypeIdException.class)
                .rootCause().hasMessageContaining("java.time.OffsetDateTime");
    }

    // ---- versioned cache keys (Phase 38 Task 2) ----------------------------------------------

    private static RedisCacheManager productionCacheManager() {
        ObjectProvider<MeterRegistry> noRegistry = new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class);
        RedisCacheManager manager = (RedisCacheManager) new CacheConfig(noRegistry)
                .cacheManager(mock(RedisConnectionFactory.class));
        manager.afterPropertiesSet();    // loads the configured regions, as the container does
        return manager;
    }

    /**
     * Jackson-2-era entries live under {@code {region}::…}. Every Boot-4 region reads and writes under
     * {@code v4:{region}::…} instead, so an old entry is never read: it expires by its TTL. The prefix
     * sits on the DEFAULT configuration, so a region added later inherits it without anyone
     * remembering to. The TTLs are pinned too: they are what bounds how long an old entry survives.
     */
    @Test
    void everyRegionReadsAndWritesUnderTheVersionedKeyPrefixAndANewRegionInheritsIt() {
        RedisCacheManager manager = productionCacheManager();
        Map<String, java.time.Duration> ttls = Map.of(
                "products", java.time.Duration.ofMinutes(10),
                "shops", java.time.Duration.ofMinutes(15),
                "shopMembership", java.time.Duration.ofMinutes(5));

        for (Map.Entry<String, java.time.Duration> region : ttls.entrySet()) {
            RedisCacheConfiguration config = ((RedisCache) manager.getCache(region.getKey())).getCacheConfiguration();
            assertThat(config.getKeyPrefixFor(region.getKey())).as("key prefix of %s", region.getKey())
                    .isEqualTo("v4:" + region.getKey() + "::");
            assertThat(config.getTtlFunction().getTimeToLive("k", "v")).as("TTL of %s", region.getKey())
                    .isEqualTo(region.getValue());
        }
        RedisCache later = (RedisCache) manager.getCache("aRegionAddedLater");
        assertThat(later.getCacheConfiguration().getKeyPrefixFor("aRegionAddedLater"))
                .as("a region nobody configured inherits the prefix from the defaults")
                .isEqualTo("v4:aRegionAddedLater::");
    }

    /** The cache family of the 38-01 MANIFEST: exactly the files this class feeds below. */
    private static Set<String> manifestCacheFixtures() throws Exception {
        try (var in = CacheSerializerTypeAllowlistTest.class.getClassLoader()
                .getResourceAsStream("jackson2-golden/MANIFEST.tsv")) {
            assertThat(in).as("the 38-01 MANIFEST is on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(line -> line.split("\t")[0])
                    .filter(path -> path.startsWith("cache/"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void theCacheFixturesBelowAreExactlyTheManifestCacheFamily() throws Exception {
        assertThat(manifestCacheFixtures()).containsExactly(
                "cache/products-ProductDto.bin", "cache/shopMembership-Membership.bin", "cache/shops-ShopDto.bin");
    }

    /**
     * Why the prefix is needed, measured rather than assumed: every value the Boot-3.5 serializer
     * cached is UNREADABLE by the Jackson-3 one. Its {@code ["java.util.UUID", "…"]} and
     * {@code ["…ShopRole", "…"]} wrappers sit where a bare value is now expected. Read under the old
     * key, each would be a GET error that {@link RedisCacheErrorHandler} swallows and counts on every
     * request until the entry expired.
     */
    @ParameterizedTest
    @ValueSource(strings = {"products-ProductDto", "shops-ShopDto", "shopMembership-Membership"})
    void aJackson2EraCacheValueIsUnreadableByTheJackson3Serializer(String fixture) throws Exception {
        byte[] jackson2Bytes;
        try (var in = CacheSerializerTypeAllowlistTest.class.getClassLoader()
                .getResourceAsStream("jackson2-golden/cache/" + fixture + ".bin")) {
            assertThat(in).as("fixture %s present", fixture).isNotNull();
            jackson2Bytes = in.readAllBytes();
        }

        assertThatThrownBy(() -> serializer.deserialize(jackson2Bytes))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(MismatchedInputException.class)
                .rootCause().hasMessageContaining("from Array value");
    }

    /**
     * The documented RESIDUAL, so nobody reads the refusal arm as "only the three cached shapes can
     * be instantiated". The allowlist is prefix-based ({@code java.util.} is needed for the List/Map
     * implementations and {@code ImmutableCollections$*}), so a JDK collection the cache never stores
     * is still constructible. That is a strict narrowing from "any class on the classpath", and it is
     * the trade recorded in adjudication A6.
     */
    @Test
    void aJdkCollectionInsideTheAllowlistStillDeserialisesTheDocumentedResidual() {
        byte[] treeMap = "{\"@class\":\"java.util.TreeMap\",\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);

        Object back = serializer.deserialize(treeMap);

        assertThat(back).isInstanceOf(java.util.TreeMap.class);
    }
}
