package uk.jtoye.core.shop;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A shop's structured opening hours (Phase 37-19, D-15): for each day Monday to Sunday, a list of
 * {@link TimeWindow}s, where an empty list means closed that day.
 */
public final class OpeningSchedule {

    private OpeningSchedule() {
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

        private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

        public TimeWindow {
            Objects.requireNonNull(open, "open");
            Objects.requireNonNull(close, "close");
            if (open.getSecond() != 0 || open.getNano() != 0 || close.getSecond() != 0 || close.getNano() != 0) {
                throw new IllegalArgumentException("Opening times are whole minutes: " + open + " - " + close);
            }
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
