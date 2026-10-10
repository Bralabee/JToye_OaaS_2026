package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.exception.ShopClosedException;
import uk.jtoye.core.shop.HoursSource;
import uk.jtoye.core.shop.OpeningSchedule;
import uk.jtoye.core.shop.OpeningSchedule.TimeWindow;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.storefront.ShopAvailabilityPolicy.Availability;
import uk.jtoye.core.storefront.ShopAvailabilityPolicy.Reason;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 37-20 (D-15, D-25): {@link ShopAvailabilityPolicy} decides, once, whether a shop takes storefront
 * orders now. Every case runs on a FIXED clock whose instant is given in UTC, and the expectations are UK
 * local times, so a policy that evaluated in UTC (or in the JVM's zone) reds the British Summer Time cases
 * and the two clock-change cases. 2026-10-12 is a Monday in BST; 2026-03-29 and 2026-10-25 are the last
 * Sundays of March and October, when the UK clocks change.
 */
class ShopAvailabilityPolicyTest {

    /** The policy at a fixed instant, deliberately on a UTC clock: the zone must come from the policy. */
    private static ShopAvailabilityPolicy at(String utcInstant) {
        return new ShopAvailabilityPolicy(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
    }

    private static TimeWindow w(String open, String close) {
        return TimeWindow.fromJson(open, close);
    }

    /** A shop with a structured schedule given as day key -> windows; days not given are closed. */
    private static Shop structured(Map<String, List<TimeWindow>> days) {
        Shop shop = new Shop();
        shop.setName("Mama Put");
        shop.setHoursSource(HoursSource.STRUCTURED);
        Map<String, List<TimeWindow>> json = new LinkedHashMap<>();
        for (String key : OpeningSchedule.DAY_KEYS) {
            json.put(key, days.getOrDefault(key, List.of()));
        }
        shop.setOpeningSchedule(json);
        return shop;
    }

    private static Shop legacy() {
        Shop shop = new Shop();
        shop.setName("Legacy Grill");
        shop.setHoursSource(HoursSource.LEGACY_ALWAYS_OPEN);
        return shop;
    }

    private static ZonedDateTime uk(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(ShopAvailabilityPolicy.UK_ZONE);
    }

    @Nested
    @DisplayName("structured windows, in UK time")
    class Windows {

        @Test
        @DisplayName("Mon 12:00 BST with mon 09:00-17:00 is open")
        void insideWindow_open() {
            Availability a = at("2026-10-12T11:00:00Z").evaluate(structured(Map.of("mon", List.of(w("09:00", "17:00")))));
            assertThat(a.openNow()).isTrue();
            assertThat(a.reason()).isNull();
            assertThat(a.nextOpenAt()).isNull();
        }

        @Test
        @DisplayName("Mon 09:30 BST (08:30 UTC) with mon 09:00-17:00 is open: the window is UK time, not UTC")
        void bstMorning_openInUkTime() {
            Availability a = at("2026-10-12T08:30:00Z").evaluate(structured(Map.of("mon", List.of(w("09:00", "17:00")))));
            assertThat(a.openNow()).as("09:30 BST is inside 09:00-17:00").isTrue();
        }

        @Test
        @DisplayName("Mon 16:30 BST (15:30 UTC) is open and Mon 17:30 BST (16:30 UTC) is CLOSED_NOW: the close is UK time")
        void bstEvening_closeIsUkTime() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00"))));
            assertThat(at("2026-10-12T15:30:00Z").evaluate(shop).openNow()).isTrue();
            Availability after = at("2026-10-12T16:30:00Z").evaluate(shop);
            assertThat(after.openNow()).as("17:30 BST is after the 17:00 close").isFalse();
            assertThat(after.reason()).isEqualTo(Reason.CLOSED_NOW);
        }

        @Test
        @DisplayName("Mon 18:00 with mon and tue 09:00-17:00 is CLOSED_NOW, next open Tue 09:00 UK")
        void afterClose_closedNow_nextOpenTomorrow() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00")), "tue", List.of(w("09:00", "17:00"))));
            Availability a = at("2026-10-12T17:00:00Z").evaluate(shop);
            assertThat(a.openNow()).isFalse();
            assertThat(a.reason()).isEqualTo(Reason.CLOSED_NOW);
            assertThat(a.nextOpenAt()).isEqualTo(uk("2026-10-13T09:00"));
        }

        @Test
        @DisplayName("before opening today: CLOSED_NOW, next open later today")
        void beforeOpen_closedNow_nextOpenToday() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "12:00"), w("17:00", "22:00"))));
            Availability a = at("2026-10-12T13:00:00Z").evaluate(shop); // 14:00 BST, between the two windows
            assertThat(a.reason()).isEqualTo(Reason.CLOSED_NOW);
            assertThat(a.nextOpenAt()).isEqualTo(uk("2026-10-12T17:00"));
        }

        @Test
        @DisplayName("Fri 23:30 and Sat 01:30 with fri 18:00-02:00 are open (overnight carried past midnight); Sat 02:30 is CLOSED_TODAY")
        void overnightWindow() {
            Shop shop = structured(Map.of("fri", List.of(w("18:00", "02:00"))));
            assertThat(at("2026-10-16T22:30:00Z").evaluate(shop).openNow()).as("Fri 23:30 BST").isTrue();
            assertThat(at("2026-10-17T00:30:00Z").evaluate(shop).openNow()).as("Sat 01:30 BST").isTrue();
            Availability late = at("2026-10-17T01:30:00Z").evaluate(shop); // Sat 02:30 BST
            assertThat(late.openNow()).isFalse();
            assertThat(late.reason()).as("Saturday has no window of its own").isEqualTo(Reason.CLOSED_TODAY);
            assertThat(late.nextOpenAt()).isEqualTo(uk("2026-10-23T18:00"));
        }

        @Test
        @DisplayName("a closed day is CLOSED_TODAY with the next opening day")
        void closedDay_closedToday() {
            Shop shop = structured(Map.of("tue", List.of(w("09:00", "17:00"))));
            Availability a = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(a.reason()).isEqualTo(Reason.CLOSED_TODAY);
            assertThat(a.nextOpenAt()).isEqualTo(uk("2026-10-13T09:00"));
        }

        @Test
        @DisplayName("the 7-day horizon: a shop open one hour a week, just after that hour, next opens exactly 7 days on")
        void sevenDayHorizon() {
            Shop shop = structured(Map.of("wed", List.of(w("10:00", "11:00"))));
            Availability a = at("2026-10-14T11:00:00Z").evaluate(shop); // Wed 12:00 BST
            assertThat(a.reason()).isEqualTo(Reason.CLOSED_NOW);
            assertThat(a.nextOpenAt()).isEqualTo(uk("2026-10-21T10:00"));
        }

        @Test
        @DisplayName("all days [] -> NO_HOURS (D-15: empty is closed), no next opening")
        void allDaysClosed_noHours() {
            Availability a = at("2026-10-12T11:00:00Z").evaluate(structured(Map.of()));
            assertThat(a.openNow()).isFalse();
            assertThat(a.reason()).isEqualTo(Reason.NO_HOURS);
            assertThat(a.nextOpenAt()).isNull();
        }

        @Test
        @DisplayName("a shop the application wrote with no schedule and no source (created after deploy) is NO_HOURS")
        void unclassifiedNoSchedule_noHours() {
            Shop shop = new Shop();
            shop.setName("New Shop");
            Availability a = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(a.openNow()).isFalse();
            assertThat(a.reason()).isEqualTo(Reason.NO_HOURS);
        }

        @Test
        @DisplayName("00:00-00:00 is a 24-hour window: open at any time of any day it is set")
        void twentyFourHours() {
            Map<String, List<TimeWindow>> all = new LinkedHashMap<>();
            OpeningSchedule.DAY_KEYS.forEach(k -> all.put(k, List.of(w("00:00", "00:00"))));
            Shop shop = structured(all);
            assertThat(at("2026-10-12T23:59:00Z").evaluate(shop).openNow()).isTrue();
            assertThat(at("2026-10-13T00:00:00Z").evaluate(shop).openNow()).isTrue();
            assertThat(at("2026-10-16T12:34:00Z").evaluate(shop).openNow()).isTrue();
        }

        @Test
        @DisplayName("37-22: the seeded all-day week (00:00-00:00 every day) is open at 00:00, 12:00 and 23:59 UK time, every day, the autumn clock change included")
        void allDayWindow_openAtMidnightNoonAndLastMinuteEveryDay() {
            Map<String, List<TimeWindow>> all = new LinkedHashMap<>();
            OpeningSchedule.DAY_KEYS.forEach(k -> all.put(k, List.of(w("00:00", "00:00"))));
            Shop shop = structured(all);
            // Monday 19 .. Sunday 25 October 2026: BST until 02:00 on the 25th, GMT after.
            for (int day = 19; day <= 25; day++) {
                for (String time : List.of("00:00", "12:00", "23:59")) {
                    ZonedDateTime local = uk(String.format("2026-10-%02dT%s", day, time));
                    Availability a = new ShopAvailabilityPolicy(Clock.fixed(local.toInstant(), ZoneOffset.UTC))
                            .evaluate(shop);
                    assertThat(a.openNow()).as("%s %s", local.getDayOfWeek(), time).isTrue();
                    assertThat(a.reason()).as("%s %s", local.getDayOfWeek(), time).isNull();
                }
            }
        }

        @Test
        @DisplayName("MIGRATED_FROM_TEXT is evaluated against its windows, as its text was before deploy")
        void migratedFromText_evaluated() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00"))));
            shop.setHoursSource(HoursSource.MIGRATED_FROM_TEXT);
            assertThat(at("2026-10-12T11:00:00Z").evaluate(shop).openNow()).isTrue();
            assertThat(at("2026-10-12T17:00:00Z").evaluate(shop).reason()).isEqualTo(Reason.CLOSED_NOW);
        }
    }

    @Nested
    @DisplayName("UK clock changes (no exception, UK local times)")
    class ClockChanges {

        @Test
        @DisplayName("last Sunday of March: 01:30 UTC is 02:30 BST, inside sun 02:00-03:00; a window opening in the skipped hour opens at 02:30 BST")
        void springForward() {
            Shop shop = structured(Map.of("sun", List.of(w("02:00", "03:00"))));
            assertThat(at("2026-03-29T01:30:00Z").evaluate(shop).openNow()).as("02:30 BST").isTrue();

            Shop gap = structured(Map.of("sun", List.of(w("01:30", "04:00"))));
            Availability before = at("2026-03-29T00:45:00Z").evaluate(gap); // 00:45 GMT
            assertThat(before.reason()).isEqualTo(Reason.CLOSED_NOW);
            assertThat(before.nextOpenAt().toInstant())
                    .as("01:30 does not exist that night; the window opens when the clock reads 02:30 BST")
                    .isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
            assertThat(at("2026-03-29T01:45:00Z").evaluate(gap).openNow()).as("02:45 BST").isTrue();
        }

        @Test
        @DisplayName("last Sunday of October: 01:30 happens twice and is inside sun 01:00-02:00 both times; next opening after the change is in GMT")
        void fallBack() {
            Shop shop = structured(Map.of("sun", List.of(w("01:00", "02:00"), w("09:00", "17:00"))));
            assertThat(at("2026-10-25T00:30:00Z").evaluate(shop).openNow()).as("01:30 BST").isTrue();
            assertThat(at("2026-10-25T01:30:00Z").evaluate(shop).openNow()).as("01:30 GMT").isTrue();

            Availability after = at("2026-10-25T02:30:00Z").evaluate(shop); // 02:30 GMT
            assertThat(after.reason()).isEqualTo(Reason.CLOSED_NOW);
            assertThat(after.nextOpenAt().toOffsetDateTime())
                    .isEqualTo(OffsetDateTime.parse("2026-10-25T09:00:00Z"));
        }

        @Test
        @DisplayName("Saturday night BST, next opening Sunday after the change: reported in GMT")
        void nextOpenAcrossTheChange() {
            Shop shop = structured(Map.of("sun", List.of(w("09:00", "17:00"))));
            Availability a = at("2026-10-24T22:00:00Z").evaluate(shop); // Sat 23:00 BST
            assertThat(a.reason()).isEqualTo(Reason.CLOSED_TODAY);
            assertThat(a.nextOpenAt().toOffsetDateTime()).isEqualTo(OffsetDateTime.parse("2026-10-25T09:00:00Z"));
        }
    }

    @Nested
    @DisplayName("D-25: shops V80 could not give structured hours")
    class Legacy {

        @Test
        @DisplayName("LEGACY_ALWAYS_OPEN with no schedule is open at any time")
        void legacyAlwaysOpen() {
            assertThat(at("2026-10-12T03:00:00Z").evaluate(legacy()).openNow()).isTrue();
            assertThat(at("2026-10-17T23:59:00Z").evaluate(legacy()).openNow()).isTrue();
        }

        @Test
        @DisplayName("NEEDS_REVIEW: a day whose text could not be read stays OPEN, exactly as before deploy (owner ruling 2026-10-10)")
        void needsReview_unreadableDayStaysOpen() {
            Shop shop = structured(Map.of());
            shop.setHoursSource(HoursSource.NEEDS_REVIEW);
            shop.setOpeningHours(Map.of("mon", "ask at the counter"));
            Availability a = at("2026-10-12T03:00:00Z").evaluate(shop);
            assertThat(a.openNow()).as("Monday's text was unreadable: the pre-D-15 fail-open stands").isTrue();
        }

        @Test
        @DisplayName("NEEDS_REVIEW: a day the vendor wrote as 'closed' (or left out) stays closed, and a read window is enforced, as before deploy")
        void needsReview_readDaysAsBefore() {
            Shop shop = structured(Map.of("tue", List.of(w("09:00", "17:00"))));
            shop.setHoursSource(HoursSource.NEEDS_REVIEW);
            Map<String, String> text = new LinkedHashMap<>();
            text.put("mon", "Closed");
            text.put("tue", "09:00 - 17:00 (lunch 1-2)");
            text.put("wed", "by appointment");
            shop.setOpeningHours(text);

            Availability monday = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(monday.reason()).as("Monday is closed in the vendor's own words").isEqualTo(Reason.CLOSED_TODAY);
            assertThat(at("2026-10-13T11:00:00Z").evaluate(shop).openNow()).as("Tue 12:00 inside the read window").isTrue();
            assertThat(at("2026-10-13T17:00:00Z").evaluate(shop).reason()).as("Tue 18:00").isEqualTo(Reason.CLOSED_NOW);
            assertThat(at("2026-10-14T02:00:00Z").evaluate(shop).openNow()).as("Wednesday's text was unreadable").isTrue();
            assertThat(at("2026-10-15T11:00:00Z").evaluate(shop).reason()).as("Thursday was left out").isEqualTo(Reason.CLOSED_TODAY);
        }
    }

    @Nested
    @DisplayName("pause")
    class Pause {

        @Test
        @DisplayName("paused until 10 minutes from now: PAUSED, reopensAt the pause end")
        void timedPause() {
            Shop shop = legacy();
            OffsetDateTime until = OffsetDateTime.parse("2026-10-12T11:10:00Z");
            shop.setOrdersPausedUntil(until);
            Availability a = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(a.openNow()).isFalse();
            assertThat(a.reason()).isEqualTo(Reason.PAUSED);
            assertThat(a.pausedUntil()).isEqualTo(until);
            assertThat(a.nextOpenAt().toInstant()).isEqualTo(until.toInstant());
        }

        @Test
        @DisplayName("paused indefinitely: PAUSED with no reopening time, even inside opening hours")
        void indefinitePause() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00"))));
            shop.setOrdersPausedIndefinitely(true);
            Availability a = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(a.reason()).isEqualTo(Reason.PAUSED);
            assertThat(a.pausedIndefinitely()).isTrue();
            assertThat(a.nextOpenAt()).isNull();
        }

        @Test
        @DisplayName("a pause that ends after closing time reopens at the next window, not at the pause end")
        void pauseEndingAfterClose_reopensAtNextWindow() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00")), "tue", List.of(w("09:00", "17:00"))));
            shop.setOrdersPausedUntil(OffsetDateTime.parse("2026-10-12T16:20:00Z")); // 17:20 BST
            Availability a = at("2026-10-12T15:50:00Z").evaluate(shop); // 16:50 BST
            assertThat(a.reason()).isEqualTo(Reason.PAUSED);
            assertThat(a.nextOpenAt()).isEqualTo(uk("2026-10-13T09:00"));
        }

        @Test
        @DisplayName("an ended timed pause is no pause: the hours decide")
        void endedPause() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00"))));
            shop.setOrdersPausedUntil(OffsetDateTime.parse("2026-10-12T10:59:00Z"));
            Availability a = at("2026-10-12T11:00:00Z").evaluate(shop);
            assertThat(a.openNow()).isTrue();
            assertThat(a.pausedUntil()).isNull();
        }
    }

    @Nested
    @DisplayName("requireTakingOrders")
    class Require {

        @Test
        @DisplayName("closed: ShopClosedException with the reason, the reopening instant and the human sentence")
        void closedThrows() {
            Shop shop = structured(Map.of("mon", List.of(w("09:00", "17:00")), "tue", List.of(w("09:00", "17:00"))));
            assertThatThrownBy(() -> at("2026-10-12T17:00:00Z").requireTakingOrders(shop))
                    .isInstanceOfSatisfying(ShopClosedException.class, ex -> {
                        assertThat(ex.getReason()).isEqualTo("CLOSED_NOW");
                        assertThat(ex.getReopensAt().toInstant()).isEqualTo(Instant.parse("2026-10-13T08:00:00Z"));
                        assertThat(ex.getMessage())
                                .isEqualTo("Mama Put isn't taking orders (closed right now). Reopens on Tuesday at 09:00.");
                    });
        }

        @Test
        @DisplayName("no hours: no reopening time is set")
        void noHoursThrows() {
            assertThatThrownBy(() -> at("2026-10-12T11:00:00Z").requireTakingOrders(structured(Map.of())))
                    .isInstanceOfSatisfying(ShopClosedException.class, ex -> {
                        assertThat(ex.getReason()).isEqualTo("NO_HOURS");
                        assertThat(ex.getReopensAt()).isNull();
                        assertThat(ex.getMessage()).endsWith("No reopening time is set.");
                    });
        }

        @Test
        @DisplayName("open: nothing is thrown")
        void openPasses() {
            at("2026-10-12T11:00:00Z").requireTakingOrders(structured(Map.of("mon", List.of(w("09:00", "17:00")))));
        }
    }

}
