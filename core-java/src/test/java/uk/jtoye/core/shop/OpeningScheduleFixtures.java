package uk.jtoye.core.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE list of legacy free-text hours both parsers are held to (Phase 37-19). {@code OpeningScheduleTest}
 * runs every fixture through the Java parser ({@link OpeningSchedule#fromLegacy}), and
 * {@code V80OpeningScheduleBackfillIntegrationTest} seeds every fixture as a shop in two tenants, applies V80
 * under the RLS-bound migrator, and asserts the SQL classification equals both the expectation here AND the
 * Java parser's answer. So the migration (which classifies every existing shop) and the application (37-20's
 * policy reads the same rules) cannot disagree on any input listed here.
 *
 * <p>The rule each fixture pins, in one place: a day whose whole entry (spaces trimmed) is one
 * {@code HH:MM - HH:MM} window becomes that window; {@code closed} in any case, a missing day or a JSON null
 * becomes {@code []}; anything else is NEEDS_REVIEW, keeping the FIRST window the storefront already finds in
 * the text if there is one (so the day renders as it does today) and {@code []} otherwise. A close of
 * {@code 24:00} means midnight. NULL or {@code {}} is LEGACY_ALWAYS_OPEN with no schedule (D-25).
 */
public final class OpeningScheduleFixtures {

    private OpeningScheduleFixtures() {
    }

    /** One legacy input, as the entity reads it ({@code Map<String, String>}), and its classification. */
    public record Fixture(String name, Map<String, String> hours, HoursSource expectedSource,
                          String expectedScheduleJson) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * An input only the database can hold (a {@code jsonb} that is not an object of strings: the entity
     * cannot even load such a row), so it is classified by the SQL alone.
     */
    public record RawFixture(String name, String rawJson, HoursSource expectedSource, String expectedScheduleJson) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static final List<Fixture> ALL = List.of(
            // The five <feature> cases of the plan.
            new Fixture("NULL hours", null, HoursSource.LEGACY_ALWAYS_OPEN, null),
            new Fixture("{} hours", hours(), HoursSource.LEGACY_ALWAYS_OPEN, null),
            new Fixture("every day 09:00 - 17:00",
                    hours("mon", "09:00 - 17:00", "tue", "09:00 - 17:00", "wed", "09:00 - 17:00",
                            "thu", "09:00 - 17:00", "fri", "09:00 - 17:00", "sat", "09:00 - 17:00", "sun", "09:00 - 17:00"),
                    HoursSource.MIGRATED_FROM_TEXT,
                    schedule("mon", "09:00-17:00", "tue", "09:00-17:00", "wed", "09:00-17:00", "thu", "09:00-17:00",
                            "fri", "09:00-17:00", "sat", "09:00-17:00", "sun", "09:00-17:00")),
            new Fixture("mon closed, tue 10:00-22:00, other days missing",
                    hours("mon", "closed", "tue", "10:00-22:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("tue", "10:00-22:00")),
            new Fixture("unparseable free text", hours("mon", "Lunch and dinner"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("overnight friday 18:00 - 02:00", hours("fri", "18:00 - 02:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("fri", "18:00-02:00")),

            // Edges.
            new Fixture("HH.MM separators", hours("mon", "09.00 - 17.00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("closed in other cases, padded", hours("mon", "  CLOSED  ", "tue", "Closed\t"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule()),
            new Fixture("two windows in one entry keep the first, for review",
                    hours("mon", "12:00 - 14:00, 18:00 - 22:00"),
                    HoursSource.NEEDS_REVIEW, schedule("mon", "12:00-14:00")),
            new Fixture("a trailing note keeps the window, for review",
                    hours("mon", "09:00 - 17:00 (kitchen closes 16:30)"),
                    HoursSource.NEEDS_REVIEW, schedule("mon", "09:00-17:00")),
            new Fixture("one good day and one bad day", hours("mon", "09:00 - 17:00", "tue", "by appointment"),
                    HoursSource.NEEDS_REVIEW, schedule("mon", "09:00-17:00")),
            new Fixture("close at 24:00 is midnight", hours("sat", "18:00 - 24:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("sat", "18:00-00:00")),
            new Fixture("00:00 - 24:00 is the whole day", hours("sun", "00:00 - 24:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("sun", "00:00-00:00")),
            new Fixture("out-of-range times", hours("mon", "25:00 - 26:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("open at 24:00 is out of range", hours("mon", "24:00 - 02:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("zero-length window", hours("mon", "09:00 - 09:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("single-digit hour", hours("mon", "9:00 - 17:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("unknown day key", hours("monday", "09:00 - 17:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("blank entry", hours("mon", "   "),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("tab separators", hours("mon", "09:00\t-\t17:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("mon", "09:00-17:00")),
            new Fixture("vertical tab and form feed are spaces, and are trimmed", hours("mon", "\f09:00\u000B-\r17:00\n"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("mon", "09:00-17:00")),
            new Fixture("no-break spaces are not spaces", hours("mon", "09:00 - 17:00"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("padded exact window", hours("mon", "  09:00-17:00  "),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("mon", "09:00-17:00")),
            new Fixture("non-ASCII digits", hours("mon", "٠٩:٠٠ - ١٧:٠٠"),
                    HoursSource.NEEDS_REVIEW, schedule()),
            new Fixture("a JSON null day is a missing day", hours("mon", null, "tue", "10:00 - 16:00"),
                    HoursSource.MIGRATED_FROM_TEXT, schedule("tue", "10:00-16:00"))
    );

    public static final List<RawFixture> SQL_ONLY = List.of(
            new RawFixture("jsonb null literal", "null", HoursSource.LEGACY_ALWAYS_OPEN, null),
            new RawFixture("an array instead of an object", "[\"09:00 - 17:00\"]", HoursSource.NEEDS_REVIEW, schedule()),
            new RawFixture("a string instead of an object", "\"09:00 - 17:00\"", HoursSource.NEEDS_REVIEW, schedule()),
            new RawFixture("an array day value", "{\"mon\": [\"09:00 - 17:00\"]}", HoursSource.NEEDS_REVIEW, schedule()),
            new RawFixture("an object day value", "{\"mon\": {\"open\": \"09:00\"}}", HoursSource.NEEDS_REVIEW, schedule()),
            new RawFixture("a number day value", "{\"mon\": 9, \"tue\": \"10:00 - 16:00\"}", HoursSource.NEEDS_REVIEW,
                    schedule("tue", "10:00-16:00"))
    );

    /** Day/value pairs; a null value is kept (a JSON null), which {@code Map.of} would refuse. */
    static Map<String, String> hours(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * The expected {@code opening_schedule} JSON: all seven days, each listed day with its comma-separated
     * {@code HH:MM-HH:MM} windows, every other day {@code []}.
     */
    static String schedule(String... dayWindows) {
        Map<String, String> given = new LinkedHashMap<>();
        for (int i = 0; i < dayWindows.length; i += 2) {
            given.put(dayWindows[i], dayWindows[i + 1]);
        }
        List<String> days = new ArrayList<>();
        for (String day : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            List<String> windows = new ArrayList<>();
            String spec = given.get(day);
            if (spec != null) {
                for (String window : Arrays.asList(spec.split(","))) {
                    String[] times = window.split("-");
                    windows.add("{\"open\":\"" + times[0] + "\",\"close\":\"" + times[1] + "\"}");
                }
            }
            days.add("\"" + day + "\":[" + String.join(",", windows) + "]");
        }
        return "{" + String.join(",", days) + "}";
    }
}
