package uk.jtoye.core.shop;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shop's structured opening hours (Phase 37-19, D-15): for each day Monday to Sunday, a list of
 * {@link TimeWindow}s, where an empty list means closed that day. Immutable; every day is always present.
 *
 * <p>Also the ONE reading of the legacy free-text {@code shops.opening_hours} ({@link #fromLegacy}). The V80
 * migration classifies every existing shop with the same rules in SQL, and the two are held equal on one
 * shared fixture list ({@code OpeningScheduleFixtures}, asserted by {@code OpeningScheduleTest} and, against the
 * real migration, by {@code V80OpeningScheduleBackfillIntegrationTest}). Change a rule here and the migration
 * must change with it, or that test reds.
 */
public final class OpeningSchedule {

    /** The day keys, Monday first, as stored in {@code opening_hours} and {@code opening_schedule}. */
    public static final List<String> DAY_KEYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    /** The most windows one day may hold (37-20 validates a vendor save against it). */
    public static final int MAX_WINDOWS_PER_DAY = 3;

    /**
     * The same pattern as {@code PublicStorefrontService.HOURS_PATTERN} (and the client's
     * {@code parseHoursRange}). Java's {@code \d} and {@code \s} are ASCII-only by default, which is what V80's
     * {@code [0-9]} and {@code [ \t\n\r\f\v]} spell out explicitly.
     */
    public static final Pattern HOURS_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})\\s*-\\s*(\\d{2}):(\\d{2})");

    /** Exactly the characters {@code \s} matches, trimmed from both ends of an entry (V80: the same btrim set). */
    private static final String SPACE_CHARS = " \t\n\u000B\f\r";

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final Map<DayOfWeek, List<TimeWindow>> days;

    private OpeningSchedule(Map<DayOfWeek, List<TimeWindow>> given) {
        EnumMap<DayOfWeek, List<TimeWindow>> all = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            all.put(day, List.copyOf(given.getOrDefault(day, List.of())));
        }
        this.days = Collections.unmodifiableMap(all);
    }

    /** A schedule from per-day windows; a day not given is closed. */
    public static OpeningSchedule of(Map<DayOfWeek, List<TimeWindow>> days) {
        return new OpeningSchedule(Objects.requireNonNull(days, "days"));
    }

    /** No windows on any day. */
    public static OpeningSchedule closedEveryDay() {
        return new OpeningSchedule(Map.of());
    }

    /** The windows of one day, in the order given; empty when closed. */
    public List<TimeWindow> windows(DayOfWeek day) {
        return days.get(day);
    }

    /** Every day, Monday first. */
    public Map<DayOfWeek, List<TimeWindow>> days() {
        return days;
    }

    /** True when at least one day has a window. */
    public boolean hasAnyWindow() {
        return days.values().stream().anyMatch(windows -> !windows.isEmpty());
    }

    /** The persisted shape ({@code Shop.openingSchedule}): all seven day keys, Monday first. */
    public Map<String, List<TimeWindow>> toJson() {
        Map<String, List<TimeWindow>> json = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            json.put(DAY_KEYS.get(day.ordinal()), days.get(day));
        }
        return json;
    }

    /**
     * Reads the persisted shape. NULL stays NULL (no structured schedule); a missing day or a null list is
     * closed; a key that is not {@code mon}..{@code sun} is refused rather than silently dropped.
     */
    public static OpeningSchedule fromJson(Map<String, List<TimeWindow>> json) {
        if (json == null) {
            return null;
        }
        Map<DayOfWeek, List<TimeWindow>> days = new EnumMap<>(DayOfWeek.class);
        for (Map.Entry<String, List<TimeWindow>> entry : json.entrySet()) {
            int index = DAY_KEYS.indexOf(entry.getKey());
            if (index < 0) {
                throw new IllegalArgumentException("Unknown day in opening schedule: " + entry.getKey());
            }
            days.put(DayOfWeek.of(index + 1), entry.getValue() == null ? List.of() : entry.getValue());
        }
        return new OpeningSchedule(days);
    }

    /**
     * What is wrong with this schedule as a vendor's statement, in plain words; empty when it is valid. A day
     * holds at most {@link #MAX_WINDOWS_PER_DAY} windows, and no two windows overlap: not within a day, and not
     * an overnight window with the next day's first hours (Sunday night runs into Monday). Windows may touch.
     */
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            String key = DAY_KEYS.get(day.ordinal());
            List<TimeWindow> windows = days.get(day);
            if (windows.size() > MAX_WINDOWS_PER_DAY) {
                errors.add(key + ": at most " + MAX_WINDOWS_PER_DAY + " opening windows a day (" + windows.size() + " given)");
            }
            for (int i = 0; i < windows.size(); i++) {
                for (int j = i + 1; j < windows.size(); j++) {
                    TimeWindow a = windows.get(i);
                    TimeWindow b = windows.get(j);
                    if (a.startMinute() < b.endMinute() && b.startMinute() < a.endMinute()) {
                        errors.add(key + ": windows " + a + " and " + b + " overlap");
                    }
                }
            }
            DayOfWeek next = day.plus(1);
            for (TimeWindow window : windows) {
                int spillEnd = window.endMinute() - MINUTES_PER_DAY;
                for (TimeWindow tomorrow : days.get(next)) {
                    if (spillEnd > 0 && tomorrow.startMinute() < spillEnd) {
                        errors.add(key + ": " + window + " runs into " + DAY_KEYS.get(next.ordinal()) + " " + tomorrow);
                    }
                }
            }
        }
        return errors;
    }

    /**
     * Reads a shop's legacy free-text hours exactly as V80 does:
     * <ul>
     *   <li>NULL or no entries: {@link HoursSource#LEGACY_ALWAYS_OPEN}, no schedule (D-25).</li>
     *   <li>Per day: a missing entry, a JSON null, or {@code closed} in any case (spaces trimmed) is closed; an
     *       entry that is exactly one {@code HH:MM - HH:MM} window (spaces trimmed) is that window, a close of
     *       {@code 24:00} meaning midnight.</li>
     *   <li>Anything else marks the shop {@link HoursSource#NEEDS_REVIEW}, and the day keeps what the storefront
     *       already shows for it: the FIRST window found in the text if it is a real time (a note after it, or a
     *       second window, is not understood), otherwise closed. A key that is not {@code mon}..{@code sun} is
     *       not understood either.</li>
     *   <li>Every entry understood: {@link HoursSource#MIGRATED_FROM_TEXT}.</li>
     * </ul>
     */
    public static LegacyClassification fromLegacy(Map<String, String> hours) {
        if (hours == null || hours.isEmpty()) {
            return new LegacyClassification(HoursSource.LEGACY_ALWAYS_OPEN, null);
        }
        boolean review = hours.keySet().stream().anyMatch(key -> !DAY_KEYS.contains(key));
        Map<DayOfWeek, List<TimeWindow>> days = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            String entry = hours.get(DAY_KEYS.get(day.ordinal()));
            if (entry == null || trim(entry).toLowerCase(Locale.ROOT).equals("closed")) {
                continue;
            }
            Matcher found = HOURS_PATTERN.matcher(entry);
            TimeWindow window = found.find() ? windowOf(found) : null;
            if (window == null) {
                review = true;
                continue;
            }
            days.put(day, List.of(window));
            if (!HOURS_PATTERN.matcher(trim(entry)).matches()) {
                review = true;
            }
        }
        return new LegacyClassification(review ? HoursSource.NEEDS_REVIEW : HoursSource.MIGRATED_FROM_TEXT,
                new OpeningSchedule(days));
    }

    /** The window a pattern match names, or null when it is not a real window (out of range, zero length). */
    private static TimeWindow windowOf(Matcher m) {
        int openHour = Integer.parseInt(m.group(1));
        int openMinute = Integer.parseInt(m.group(2));
        int closeHour = Integer.parseInt(m.group(3));
        int closeMinute = Integer.parseInt(m.group(4));
        if (openHour == closeHour && openMinute == closeMinute) {
            return null; // "09:00 - 09:00": both parsers read it as never open today
        }
        if (openHour > 23 || openMinute > 59 || closeMinute > 59 || closeHour > 24 || (closeHour == 24 && closeMinute != 0)) {
            return null;
        }
        return new TimeWindow(LocalTime.of(openHour, openMinute), LocalTime.of(closeHour % 24, closeMinute));
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && SPACE_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && SPACE_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof OpeningSchedule schedule && days.equals(schedule.days));
    }

    @Override
    public int hashCode() {
        return days.hashCode();
    }

    @Override
    public String toString() {
        return "OpeningSchedule" + toJson();
    }

    /** The result of reading a shop's legacy free-text hours: its source, and its schedule (null when legacy). */
    public record LegacyClassification(HoursSource source, OpeningSchedule schedule) {
    }

    /**
     * One opening window, in UK local time. A window whose close is at or before its open is an overnight
     * window that closes the next day ({@code 18:00 - 02:00}).
     *
     * <p>Persisted in {@code shops.opening_schedule} as {@code {"open": "HH:MM", "close": "HH:MM"}}. The JSON
     * form is declared here with the annotations Jackson 2 and Jackson 3 share, rather than left to whichever
     * {@code java.time} handling the JSON mapper in use happens to have, so the stored shape cannot change
     * with the mapper.
     */
    public record TimeWindow(LocalTime open, LocalTime close) {

        // STRICT: SMART resolution would read "24:00" as 00:00 (measured in RED), and a stored window is HH:MM.
        private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm")
                .withResolverStyle(ResolverStyle.STRICT);

        public TimeWindow {
            Objects.requireNonNull(open, "open");
            Objects.requireNonNull(close, "close");
            if (open.getSecond() != 0 || open.getNano() != 0 || close.getSecond() != 0 || close.getNano() != 0) {
                throw new IllegalArgumentException("Opening times are whole minutes: " + open + " - " + close);
            }
        }

        /** Minutes after the day's midnight the window opens. */
        int startMinute() {
            return open.getHour() * 60 + open.getMinute();
        }

        /** Minutes after the day's midnight the window closes; past 1440 when it closes the next day. */
        int endMinute() {
            int end = close.getHour() * 60 + close.getMinute();
            return overnight() ? end + MINUTES_PER_DAY : end;
        }

        @Override
        public String toString() {
            return HH_MM.format(open) + "-" + HH_MM.format(close);
        }

        /** True when the window closes on the next day: its close is at or before its open. */
        public boolean overnight() {
            return !close.isAfter(open);
        }

        @JsonCreator
        public static TimeWindow fromJson(@JsonProperty("open") String open, @JsonProperty("close") String close) {
            return new TimeWindow(parseTime(open, "open"), parseTime(close, "close"));
        }

        @JsonValue
        public Map<String, String> toJson() {
            Map<String, String> json = new LinkedHashMap<>();
            json.put("open", HH_MM.format(open));
            json.put("close", HH_MM.format(close));
            return json;
        }

        private static LocalTime parseTime(String value, String field) {
            if (value == null) {
                throw new IllegalArgumentException("Opening window " + field + " time is missing");
            }
            try {
                return LocalTime.parse(value, HH_MM);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Opening window " + field + " time must be HH:MM: " + value, e);
            }
        }
    }
}
