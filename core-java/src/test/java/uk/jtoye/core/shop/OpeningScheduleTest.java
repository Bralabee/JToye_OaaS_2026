package uk.jtoye.core.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.shop.OpeningSchedule.LegacyClassification;
import uk.jtoye.core.shop.OpeningSchedule.TimeWindow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 37-19 (D-15, D-25): the Java reading of legacy free-text hours, held to the SAME fixture list the V80
 * migration is held to ({@link OpeningScheduleFixtures}), plus the value type 37-20's policy builds on.
 */
class OpeningScheduleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<OpeningScheduleFixtures.Fixture> fixtures() {
        return OpeningScheduleFixtures.ALL.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("fromLegacy classifies every shared fixture exactly as the V80 migration must")
    void fromLegacyClassifiesLikeTheMigration(OpeningScheduleFixtures.Fixture fixture) {
        LegacyClassification result = OpeningSchedule.fromLegacy(fixture.hours());

        assertThat(result.source()).as("hours_source").isEqualTo(fixture.expectedSource());
        if (fixture.expectedScheduleJson() == null) {
            assertThat(result.schedule()).as("a legacy always-open shop has no schedule").isNull();
        } else {
            assertThat(result.schedule()).as("schedule").isNotNull();
            assertThat(tree(result.schedule())).as("opening_schedule JSON")
                    .isEqualTo(JSON.readTree(fixture.expectedScheduleJson()));
        }
    }

    @Test
    @DisplayName("a window whose close is at or before its open is overnight")
    void overnightDetection() {
        assertThat(window("18:00", "02:00").overnight()).isTrue();
        assertThat(window("00:00", "00:00").overnight()).as("00:00 - 00:00 is the whole day").isTrue();
        assertThat(window("09:00", "17:00").overnight()).isFalse();
        assertThat(window("00:00", "23:59").overnight()).isFalse();
    }

    @Test
    @DisplayName("toJson and fromJson round-trip, through text and through both Jackson lines")
    void toJsonFromJsonRoundTrip() throws Exception {
        Map<DayOfWeek, List<TimeWindow>> days = new EnumMap<>(DayOfWeek.class);
        days.put(DayOfWeek.MONDAY, List.of(window("09:00", "12:00"), window("13:00", "17:30")));
        days.put(DayOfWeek.FRIDAY, List.of(window("18:00", "02:00")));
        OpeningSchedule schedule = OpeningSchedule.of(days);

        Map<String, List<TimeWindow>> json = schedule.toJson();
        assertThat(json.keySet()).as("all seven days, Monday first").containsExactlyElementsOf(OpeningSchedule.DAY_KEYS);
        assertThat(json.get("tue")).as("a day with no windows is closed").isEmpty();
        assertThat(OpeningSchedule.fromJson(json)).isEqualTo(schedule);

        TypeReference<Map<String, List<TimeWindow>>> type3 = new TypeReference<>() { };
        String text = JSON.writeValueAsString(json);
        assertThat(JSON.readTree(text)).isEqualTo(JSON.readTree(
                "{\"mon\":[{\"open\":\"09:00\",\"close\":\"12:00\"},{\"open\":\"13:00\",\"close\":\"17:30\"}],"
                        + "\"tue\":[],\"wed\":[],\"thu\":[],\"fri\":[{\"open\":\"18:00\",\"close\":\"02:00\"}],"
                        + "\"sat\":[],\"sun\":[]}"));
        assertThat(OpeningSchedule.fromJson(JSON.readValue(text, type3))).as("Jackson 3").isEqualTo(schedule);

        com.fasterxml.jackson.databind.ObjectMapper jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        String text2 = jackson2.writeValueAsString(json);
        assertThat(JSON.readTree(text2)).as("Jackson 2 writes the same shape").isEqualTo(JSON.readTree(text));
        Map<String, List<TimeWindow>> read2 = jackson2.readValue(text2,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<TimeWindow>>>() { });
        assertThat(OpeningSchedule.fromJson(read2)).as("Jackson 2").isEqualTo(schedule);
    }

    @Test
    @DisplayName("fromJson: null stays null, a missing day is closed, an unknown day is refused")
    void fromJsonEdges() {
        assertThat(OpeningSchedule.fromJson(null)).isNull();

        Map<String, List<TimeWindow>> partial = new LinkedHashMap<>();
        partial.put("wed", List.of(window("10:00", "14:00")));
        OpeningSchedule schedule = OpeningSchedule.fromJson(partial);
        assertThat(schedule.windows(DayOfWeek.WEDNESDAY)).containsExactly(window("10:00", "14:00"));
        assertThat(schedule.windows(DayOfWeek.MONDAY)).isEmpty();
        assertThat(schedule.hasAnyWindow()).isTrue();
        assertThat(OpeningSchedule.closedEveryDay().hasAnyWindow()).isFalse();

        Map<String, List<TimeWindow>> unknown = Map.of("monday", List.of(window("10:00", "14:00")));
        assertThatThrownBy(() -> OpeningSchedule.fromJson(unknown))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("monday");
    }

    @Test
    @DisplayName("validation: at most three windows a day")
    void atMostThreeWindowsADay() {
        OpeningSchedule three = OpeningSchedule.of(Map.of(DayOfWeek.MONDAY,
                List.of(window("06:00", "08:00"), window("12:00", "14:00"), window("18:00", "22:00"))));
        assertThat(three.validationErrors()).isEmpty();

        OpeningSchedule four = OpeningSchedule.of(Map.of(DayOfWeek.MONDAY, List.of(window("06:00", "08:00"),
                window("09:00", "10:00"), window("12:00", "14:00"), window("18:00", "22:00"))));
        assertThat(four.validationErrors()).singleElement().asString().contains("mon").contains("3");
    }

    @Test
    @DisplayName("validation: windows may touch but not overlap, within a day and across midnight")
    void windowsMayTouchButNotOverlap() {
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.MONDAY,
                List.of(window("09:00", "12:00"), window("12:00", "14:00")))).validationErrors())
                .as("touching windows").isEmpty();
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.MONDAY,
                List.of(window("09:00", "12:00"), window("11:00", "14:00")))).validationErrors())
                .as("overlapping windows").singleElement().asString().contains("mon").contains("overlap");
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.MONDAY,
                List.of(window("22:00", "02:00"), window("01:00", "03:00")))).validationErrors())
                .as("Monday 01:00 is Monday morning, not the night after: no overlap").isEmpty();
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.MONDAY, List.of(window("22:00", "02:00")),
                DayOfWeek.TUESDAY, List.of(window("01:00", "05:00")))).validationErrors())
                .as("Monday night runs into Tuesday 01:00").singleElement().asString().contains("mon").contains("tue");
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.SUNDAY, List.of(window("23:00", "01:00")),
                DayOfWeek.MONDAY, List.of(window("00:30", "05:00")))).validationErrors())
                .as("Sunday night runs into Monday").singleElement().asString().contains("sun").contains("mon");
        assertThat(OpeningSchedule.of(Map.of(DayOfWeek.MONDAY, List.of(window("22:00", "02:00")),
                DayOfWeek.TUESDAY, List.of(window("02:00", "05:00")))).validationErrors())
                .as("touching across midnight").isEmpty();
    }

    @Test
    @DisplayName("a window is whole minutes")
    void windowIsWholeMinutes() {
        assertThatThrownBy(() -> new TimeWindow(LocalTime.of(9, 0, 30), LocalTime.of(17, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeWindow.fromJson("9:00", "17:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeWindow.fromJson("24:00", "02:00")).isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode tree(OpeningSchedule schedule) {
        return JSON.readTree(JSON.writeValueAsString(schedule.toJson()));
    }

    private static TimeWindow window(String open, String close) {
        return new TimeWindow(LocalTime.parse(open), LocalTime.parse(close));
    }
}
