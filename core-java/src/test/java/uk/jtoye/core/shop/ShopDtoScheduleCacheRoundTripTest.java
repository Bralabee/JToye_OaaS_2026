package uk.jtoye.core.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import uk.jtoye.core.config.CacheConfig;
import uk.jtoye.core.shop.OpeningSchedule.TimeWindow;
import uk.jtoye.core.shop.dto.ShopDto;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 37-20: {@link ShopDto} is the "shops" cache value (Redis, 15 min). 37-20 adds the schedule, its source and
 * the pause to it, and a cache value that cannot be read back is a SILENT failure here:
 * {@code RedisCacheErrorHandler} swallows a GET error, so every read becomes a cache miss and nothing reds.
 *
 * <p>This round-trips a ShopDto carrying every new field through THE production serializer
 * ({@link CacheConfig#jsonRedisSerializer()}, polymorphic typing behind the SEC-4 allowlist), with the schedule in
 * each runtime shape the code can produce: {@code OpeningSchedule.toJson()} (JDK immutable lists of 0 to 3
 * windows, after a vendor save) and plain {@code ArrayList}s (Hibernate's read of the jsonb column).
 */
class ShopDtoScheduleCacheRoundTripTest {

    private static TimeWindow w(String open, String close) {
        return TimeWindow.fromJson(open, close);
    }

    private static ShopDto dto(Map<String, List<TimeWindow>> schedule) {
        ShopDto dto = new ShopDto();
        dto.setId(UUID.fromString("38010000-0000-4000-8000-00000000a002"));
        dto.setTenantId(UUID.fromString("38010000-0000-4000-8000-00000000a001"));
        dto.setName("Mama Put");
        dto.setHoursSource(HoursSource.NEEDS_REVIEW);
        Map<String, String> text = new LinkedHashMap<>();
        text.put("mon", "09:00 - 17:00");
        text.put("wed", "ask at the counter");
        dto.setLegacyOpeningHours(text);
        dto.setOpeningHours(text);
        dto.setOpeningSchedule(schedule);
        dto.setOrdersPausedUntil(OffsetDateTime.parse("2026-10-12T19:40:00+01:00"));
        dto.setOrdersPausedIndefinitely(false);
        return dto;
    }

    private static ShopDto roundTrip(ShopDto dto) {
        GenericJacksonJsonRedisSerializer serializer = CacheConfig.jsonRedisSerializer();
        byte[] bytes = serializer.serialize(dto);
        Object back = serializer.deserialize(bytes);
        assertThat(back).as("cached bytes: %s", new String(bytes, StandardCharsets.UTF_8)).isInstanceOf(ShopDto.class);
        return (ShopDto) back;
    }

    private static void assertSame(ShopDto expected, ShopDto actual) {
        assertThat(actual.getOpeningSchedule()).isEqualTo(expected.getOpeningSchedule());
        assertThat(actual.getHoursSource()).isEqualTo(expected.getHoursSource());
        assertThat(actual.getLegacyOpeningHours()).isEqualTo(expected.getLegacyOpeningHours());
        assertThat(actual.getOrdersPausedUntil().toInstant()).isEqualTo(expected.getOrdersPausedUntil().toInstant());
        assertThat(actual.getOrdersPausedIndefinitely()).isFalse();
    }

    @Test
    @DisplayName("a ShopDto with a vendor-saved schedule (JDK immutable lists of 0..3 windows) and a pause reads back from the cache serializer")
    void savedScheduleShape_roundTrips() {
        Map<DayOfWeek, List<TimeWindow>> days = new EnumMap<>(DayOfWeek.class);
        days.put(DayOfWeek.MONDAY, List.of(w("09:00", "17:00")));
        days.put(DayOfWeek.TUESDAY, List.of(w("09:00", "12:00"), w("17:00", "22:00")));
        days.put(DayOfWeek.FRIDAY, List.of(w("06:00", "07:00"), w("12:00", "14:00"), w("18:00", "02:00")));
        ShopDto dto = dto(OpeningSchedule.of(days).toJson());
        assertSame(dto, roundTrip(dto));
    }

    @Test
    @DisplayName("a ShopDto whose schedule was read from the database (ArrayLists) reads back from the cache serializer")
    void databaseShape_roundTrips() {
        Map<String, List<TimeWindow>> schedule = new LinkedHashMap<>();
        for (String key : OpeningSchedule.DAY_KEYS) {
            schedule.put(key, new ArrayList<>());
        }
        schedule.get("sat").add(w("10:00", "16:00"));
        ShopDto dto = dto(schedule);
        assertSame(dto, roundTrip(dto));
    }
}
