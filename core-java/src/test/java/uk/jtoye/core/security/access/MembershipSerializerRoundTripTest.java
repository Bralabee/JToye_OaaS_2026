package uk.jtoye.core.security.access;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import uk.jtoye.core.config.CacheConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WR-01 (plan 23-14, Task 3) — {@link Membership} must survive a round-trip through the SAME
 * Redis value serializer {@code CacheConfig} uses, with its {@code Map<UUID, ShopRole>} and its
 * new Task-2 provenance fields intact. The membership cache only genuinely engages once the
 * {@code @Cacheable resolveMembership} is reached through the bean proxy; the moment it does, the
 * cached record is serialized to Redis and back, so this is now a load-bearing contract rather
 * than dead config.
 *
 * <p>This is a plain unit test (no Spring context, no Docker) that uses the PRODUCTION serializer
 * itself, {@link CacheConfig#jsonRedisSerializer()}. It used to hold a hand-kept copy of that
 * construction; SEC-4 (QA-council 20260902) replaced the mapper's laissez-faire type validator
 * with an allowlist, and a copy would have stayed green over a validator change that broke every
 * membership cache read — because it tested the copy, not the bean. Now a change to the serializer
 * that would break the membership round-trip fails HERE, fast, against the real thing.
 */
class MembershipSerializerRoundTripTest {

    /** The production serializer, not a mirror — see the class Javadoc. */
    private GenericJackson2JsonRedisSerializer serializer() {
        return CacheConfig.jsonRedisSerializer();
    }

    @Test
    void scopedMembershipRoundTripsWithMapIntact() {
        UUID shopA = UUID.randomUUID();
        UUID shopB = UUID.randomUUID();
        Map<UUID, ShopRole> perShop = new LinkedHashMap<>();
        perShop.put(shopA, ShopRole.SHOP_MANAGER);
        perShop.put(shopB, ShopRole.STAFF);
        // A scoped user with an operator-provenance shape: not a group admin.
        Membership original = new Membership(false, false, Map.copyOf(perShop));

        GenericJackson2JsonRedisSerializer serializer = serializer();
        Object back = serializer.deserialize(serializer.serialize(original));

        assertThat(back)
                .as("the cached value deserializes back to the concrete Membership type")
                .isInstanceOf(Membership.class);
        Membership restored = (Membership) back;
        assertThat(restored.isGroupAdmin()).isFalse();
        assertThat(restored.groupAdminFromJit()).isFalse();
        assertThat(restored.perShopRole())
                .as("the UUID→ShopRole map survives the round-trip with both entries and types intact")
                .containsExactlyInAnyOrderEntriesOf(perShop);
        assertThat(restored.perShopRole().get(shopA)).isEqualTo(ShopRole.SHOP_MANAGER);
        assertThat(restored.perShopRole().get(shopB)).isEqualTo(ShopRole.STAFF);
    }

    @Test
    void jitGroupAdminMembershipRoundTripsWithProvenanceFlag() {
        // The Task-2 fields must survive too — a JIT-sourced GROUP_ADMIN with no per-shop grants.
        Membership original = new Membership(true, true, Map.of());

        GenericJackson2JsonRedisSerializer serializer = serializer();
        Membership restored = (Membership) serializer.deserialize(serializer.serialize(original));

        assertThat(restored.isGroupAdmin()).as("groupAdmin flag survives").isTrue();
        assertThat(restored.groupAdminFromJit()).as("the JIT-provenance flag survives (Task 2 field)").isTrue();
        assertThat(restored.perShopRole()).as("the empty map survives without loss").isEmpty();
    }

    @Test
    void operatorGroupAdminMembershipRoundTrips() {
        Membership original = new Membership(true, false, Map.of());

        GenericJackson2JsonRedisSerializer serializer = serializer();
        Membership restored = (Membership) serializer.deserialize(serializer.serialize(original));

        assertThat(restored.isGroupAdmin()).isTrue();
        assertThat(restored.groupAdminFromJit())
                .as("an operator GROUP_ADMIN round-trips as NOT JIT-sourced")
                .isFalse();
    }
}
