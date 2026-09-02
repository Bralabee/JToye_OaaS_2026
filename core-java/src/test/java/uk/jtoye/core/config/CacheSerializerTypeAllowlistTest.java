package uk.jtoye.core.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.media.MediaAssetDto;
import uk.jtoye.core.media.MediaAssetStatus;
import uk.jtoye.core.product.AllergenSpan;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.shop.dto.ShopDto;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA-council 20260902-134741 SEC-4 (adjudication A6) — the Redis cache value serializer must
 * (1) still round-trip every shape the cache actually stores and (2) refuse to instantiate a type
 * outside {@link CacheConfig#CACHE_TYPE_ID_PREFIXES} that a stored entry names.
 *
 * <p>The ORDER of those two is the whole design of this class. The finding was fixed by replacing
 * Jackson's {@code LaissezFaireSubTypeValidator} with a {@code BasicPolymorphicTypeValidator}
 * allowlist, and a too-narrow allowlist does not fail loudly: it throws on the cache GET, which
 * {@link RedisCacheErrorHandler#handleCacheGetError} WARN-logs and swallows, so the request silently
 * falls through to the database on every call. A green refusal arm over a dead cache is the exact
 * failure this project records as {@code trap_structural_green_over_dead_feature}. So the round-trip
 * arms come first, are fully populated (every field non-null, every collection non-empty), and go
 * through {@link CacheConfig#jsonRedisSerializer()} — the production serializer, not a mirror.
 *
 * <p>Plain unit test: no Spring context, no Docker. This is not the liveness proof — that is the
 * post-rebuild check that {@code jtoye.cache.errors} stays 0 under a read-after-write of the
 * products / shops / shopMembership regions, which no unit test can stand in for.
 */
class CacheSerializerTypeAllowlistTest {

    private final GenericJackson2JsonRedisSerializer serializer = CacheConfig.jsonRedisSerializer();

    /**
     * UTC on purpose: the JSR-310 deserializer adjusts to the context zone by default, so a +01:00
     * fixture would come back as the same instant at Z and the field-equality assertion would fail
     * for a reason unrelated to the allowlist.
     */
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-02T09:15:00Z");

    private static ProductDto fullyPopulatedProduct() {
        ProductDto p = new ProductDto();
        p.setId(UUID.randomUUID());
        p.setSku("SKU-SEC4-001");
        p.setTitle("Jollof Rice");
        p.setIngredientsText("Rice, tomatoes, peppers, groundnut oil");
        p.setAllergenMask(0b0000_0000_0010_0000);
        p.setPricePennies(899L);                 // Long — the java.lang. case (A6)
        p.setVatRate(VatRate.ZERO);              // enum — uk.jtoye.
        p.setCreatedAt(CREATED_AT);              // java.time.
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
        s.setLatitude(51.4736);                  // Double — a Jackson natural type, no id
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

    // ---- round-trip arms FIRST (the liveness half of the fix) --------------------------------

    @Test
    void productDtoRoundTripsThroughTheProductionSerializerWithEveryFieldIntact() {
        ProductDto original = fullyPopulatedProduct();

        Object back = serializer.deserialize(serializer.serialize(original));

        assertThat(back).as("the cached value comes back as the concrete DTO, not a Map")
                .isInstanceOf(ProductDto.class);
        assertThat(back).usingRecursiveComparison()
                .as("Long, OffsetDateTime, enum, List<String>, List<record> and nested media all survive")
                .isEqualTo(original);
    }

    @Test
    void shopDtoRoundTripsThroughTheProductionSerializerWithEveryFieldIntact() {
        ShopDto original = fullyPopulatedShop();

        Object back = serializer.deserialize(serializer.serialize(original));

        assertThat(back).isInstanceOf(ShopDto.class);
        assertThat(back).usingRecursiveComparison()
                .as("Map<String,String>, Double, Long, OffsetDateTime and UUID all survive")
                .isEqualTo(original);
    }

    /**
     * Makes A6 executable: {@code Long} is NOT a Jackson natural type, so under
     * {@code DefaultTyping.EVERYTHING} it is stored WITH a {@code java.lang.Long} id. This is the
     * measured reason {@code java.lang.} sits in the allowlist; drop the prefix and the two
     * round-trip arms above fail (measured in the break arm, recorded in the SEC-4 report).
     */
    @Test
    void aLongFieldIsStoredWithAnExplicitJavaLangTypeIdWhichIsWhyJavaLangIsAllowlisted() {
        String json = new String(serializer.serialize(fullyPopulatedProduct()), StandardCharsets.UTF_8);

        assertThat(json).contains("\"java.lang.Long\"");
        assertThat(json).as("the DTO itself is stored by class name").contains("\"uk.jtoye.core.product.dto.ProductDto\"");
        assertThat(json).as("dates are ISO-8601 with an explicit id, not epoch arrays")
                .contains("\"java.time.OffsetDateTime\"").contains("2026-09-02T09:15:00Z");
    }

    // ---- refusal arm (the security half) -----------------------------------------------------

    /**
     * The arm that flipped: on the pre-fix serializer {@code java.net.URI} INSTANTIATES from this
     * payload (measured against the running artifact's own jackson-databind). It is deliberately a
     * type Jackson's internal denylist does NOT cover, so the only thing that can refuse it is the
     * allowlist — a denylisted gadget would pass this test on the old code too.
     *
     * <p>Top-level on purpose: the nominal base is {@code java.lang.Object}, which is exactly where an
     * {@code allowIfBaseType} allowlist would have degraded to laissez-faire (see the
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
     * BOUNDARY CONTROL, not load-bearing, labelled as such: this gadget base is refused on the old
     * code as well (by Jackson's internal denylist), so the test cannot distinguish the two forms.
     * It is here to show the allowlist did not REPLACE that denylist — the two compose — and to
     * name the failure if a future edit allowlists {@code org.springframework.} wholesale.
     */
    @Test
    void aKnownGadgetBaseStaysRefused() {
        byte[] gadget = "[\"org.springframework.beans.factory.config.PropertyPathFactoryBean\",{}]"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.deserialize(gadget))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(JsonMappingException.class);
    }

    /**
     * The documented RESIDUAL, so nobody reads the refusal arm as "only the three cached shapes can
     * be instantiated". The allowlist is prefix-based ({@code java.util.} is needed for UUID, the
     * List/Map implementations and {@code ImmutableCollections$*}), so a JDK collection the cache
     * never stores is still constructible. That is a strict narrowing from "any class on the
     * classpath", and it is the trade recorded in adjudication A6.
     */
    @Test
    void aJdkCollectionInsideTheAllowlistStillDeserialisesTheDocumentedResidual() {
        byte[] treeMap = "{\"@class\":\"java.util.TreeMap\",\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);

        Object back = serializer.deserialize(treeMap);

        assertThat(back).isInstanceOf(java.util.TreeMap.class);
    }
}
