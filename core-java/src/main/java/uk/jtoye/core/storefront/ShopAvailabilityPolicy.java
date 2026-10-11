package uk.jtoye.core.storefront;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.ShopClosedException;
import uk.jtoye.core.shop.HoursSource;
import uk.jtoye.core.shop.OpeningSchedule;
import uk.jtoye.core.shop.OpeningSchedule.TimeWindow;
import uk.jtoye.core.shop.Shop;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ONE decision on whether a shop takes storefront orders now (Phase 37-20, D-15 and D-25). The
 * storefront checkout calls {@link #requireTakingOrders} before any write, and every public read of a
 * shop carries {@link #evaluate}'s answer, so no client computes open or closed for itself.
 *
 * <p>Reads {@link Shop} fields only, and the current time from the injected {@link Clock}, always
 * evaluated in UK local time ({@link #UK_ZONE}) whatever the clock's own zone is.
 *
 * <p>A pause wins over everything: {@code orders_paused_indefinitely}, or {@code orders_paused_until} in
 * the future. A timed pause that has ended is simply no longer a pause, so expiry needs no clean-up job.
 *
 * <p>Then the hours, by where they came from ({@code hours_source}, V80):
 * <ul>
 *   <li>{@link HoursSource#LEGACY_ALWAYS_OPEN}: open at any time (D-25: the shop had no hours when V80 ran and
 *       has always taken orders at any time; it does until its vendor saves hours).</li>
 *   <li>{@link HoursSource#NEEDS_REVIEW}: owner ruling 2026-10-10, honouring D-25 literally. A day whose text
 *       V80 could not read stays OPEN all day, exactly as the pre-D-15 server failed open on it, until the
 *       vendor saves structured hours. The days V80 did read keep their windows, and a day the vendor wrote as
 *       {@code closed} (or left out) stays closed, as before deploy.</li>
 *   <li>{@link HoursSource#STRUCTURED}, {@link HoursSource#MIGRATED_FROM_TEXT}, and a shop with no source (one
 *       the application created after deploy): open only inside a window. Empty or no windows are CLOSED
 *       (D-15, reversing the old server's fail-open).</li>
 * </ul>
 *
 * <p>A window is evaluated in UK wall-clock time: today's windows, plus yesterday's overnight window carried
 * past midnight. The next opening is searched over the next 7 days (so a shop open one hour a week is found);
 * a window that opens in the hour the clocks skip opens when the clock reaches it.
 *
 * <p>Vendor, REST and MCP orders ({@code OrderService.createOrder}) never call this: a phone order taken by
 * staff while the storefront is paused is legitimate (recorded reason, 37-20).
 */
@Component
public class ShopAvailabilityPolicy {

    /** Opening hours and pause times are UK local times. */
    public static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    private static final Logger log = LoggerFactory.getLogger(ShopAvailabilityPolicy.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** How far ahead the next opening is searched: today and the 7 days after it. */
    static final int HORIZON_DAYS = 7;

    /** A whole day: a window whose close equals its open is a 24-hour (overnight) window. */
    private static final TimeWindow WHOLE_DAY = new TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

    /** The characters V80 and {@code OpeningSchedule.fromLegacy} trim from a legacy entry. */
    private static final String SPACE_CHARS = " \t\n\u000B\f\r";

    /** Why a shop is not taking orders now. The names are the {@code reason} of the shop-closed problem. */
    public enum Reason {
        /** The vendor paused storefront orders. */
        PAUSED,
        /** The shop opens today, but not at this time. */
        CLOSED_NOW,
        /** The shop has no opening window today. */
        CLOSED_TODAY,
        /** The shop has no opening window on any day. */
        NO_HOURS
    }

    /**
     * The policy's answer for one shop at one moment.
     *
     * @param openNow            whether a storefront order is accepted now
     * @param reason             why not, when {@code openNow} is false; null when open
     * @param nextOpenAt         when the shop next takes orders, in UK time; null when open now or unknown
     * @param pausedUntil        the end of a timed pause still in force; null otherwise
     * @param pausedIndefinitely whether the vendor paused until they reopen
     */
    public record Availability(boolean openNow, Reason reason, ZonedDateTime nextOpenAt,
                               OffsetDateTime pausedUntil, boolean pausedIndefinitely) {
    }

    private final Clock clock;

    public ShopAvailabilityPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Whether {@code shop} takes storefront orders now, and if not, why and until when. */
    public Availability evaluate(Shop shop) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(UK_ZONE);
        OpeningSchedule hours = effectiveHours(shop);
        if (Boolean.TRUE.equals(shop.getOrdersPausedIndefinitely())) {
            return new Availability(false, Reason.PAUSED, null, null, true);
        }
        OffsetDateTime pausedUntil = shop.getOrdersPausedUntil();
        if (pausedUntil != null && pausedUntil.toInstant().isAfter(now.toInstant())) {
            // Orders come back at the pause end if the shop is open then, otherwise at its next window.
            return new Availability(false, Reason.PAUSED,
                    openAtOrAfter(hours, pausedUntil.atZoneSameInstant(UK_ZONE)), pausedUntil, false);
        }
        if (hours == null || isOpen(hours, now)) {
            return new Availability(true, null, null, null, false);
        }
        if (!hours.hasAnyWindow()) {
            return new Availability(false, Reason.NO_HOURS, null, null, false);
        }
        Reason reason = hours.windows(now.getDayOfWeek()).isEmpty() ? Reason.CLOSED_TODAY : Reason.CLOSED_NOW;
        return new Availability(false, reason, nextOpening(hours, now), null, false);
    }

    /**
     * The hours the shop is held to, or {@code null} for "open at any time" (LEGACY_ALWAYS_OPEN). See the
     * class comment for the per-source rules.
     */
    private static OpeningSchedule effectiveHours(Shop shop) {
        HoursSource source = shop.getHoursSource();
        if (source == HoursSource.LEGACY_ALWAYS_OPEN) {
            return null;
        }
        OpeningSchedule schedule;
        try {
            schedule = shop.getOpeningSchedule() == null
                    ? OpeningSchedule.closedEveryDay()
                    : OpeningSchedule.fromJson(shop.getOpeningSchedule());
        } catch (IllegalArgumentException e) {
            // Not a shape the application or V80 writes. Fail CLOSED (D-15) and say so, rather than guess.
            log.warn("Shop {} has an unreadable opening_schedule; treating it as closed: {}", shop.getId(), e.getMessage());
            schedule = OpeningSchedule.closedEveryDay();
        }
        if (source == HoursSource.NEEDS_REVIEW) {
            return withUnreadableDaysOpen(schedule, shop.getOpeningHours());
        }
        return schedule;
    }

    /**
     * D-25 as the owner ruled it (2026-10-10): a NEEDS_REVIEW day that V80 left closed only because it could not
     * read the vendor's text is open all day, as the pre-D-15 server's fail-open had it. A day left closed
     * because the vendor wrote {@code closed}, or wrote nothing, stays closed.
     */
    private static OpeningSchedule withUnreadableDaysOpen(OpeningSchedule schedule, Map<String, String> legacyText) {
        if (legacyText == null) {
            return schedule;
        }
        Map<DayOfWeek, List<TimeWindow>> days = new EnumMap<>(schedule.days());
        for (DayOfWeek day : DayOfWeek.values()) {
            String entry = legacyText.get(OpeningSchedule.DAY_KEYS.get(day.ordinal()));
            if (days.get(day).isEmpty() && entry != null && !trim(entry).equalsIgnoreCase("closed")) {
                days.put(day, List.of(WHOLE_DAY));
            }
        }
        return OpeningSchedule.of(days);
    }

    /** Whether {@code hours} has a window covering {@code at}, in UK wall-clock time. */
    static boolean isOpen(OpeningSchedule hours, ZonedDateTime at) {
        LocalTime time = at.toLocalTime();
        for (TimeWindow window : hours.windows(at.getDayOfWeek())) {
            boolean started = !time.isBefore(window.open());
            if (window.overnight() ? started : started && time.isBefore(window.close())) {
                return true;
            }
        }
        for (TimeWindow window : hours.windows(at.getDayOfWeek().minus(1))) {
            if (window.overnight() && time.isBefore(window.close())) {
                return true; // yesterday's overnight window, carried past midnight
            }
        }
        return false;
    }

    /** {@code from} itself when the shop is open then (or always open), otherwise its next opening. */
    private static ZonedDateTime openAtOrAfter(OpeningSchedule hours, ZonedDateTime from) {
        if (hours == null || isOpen(hours, from)) {
            return from;
        }
        return nextOpening(hours, from);
    }

    /** The first window opening after {@code from}, today or in the next {@link #HORIZON_DAYS} days; else null. */
    static ZonedDateTime nextOpening(OpeningSchedule hours, ZonedDateTime from) {
        ZonedDateTime next = null;
        LocalDate today = from.toLocalDate();
        for (int offset = 0; offset <= HORIZON_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            for (TimeWindow window : hours.windows(date.getDayOfWeek())) {
                // ZonedDateTime.of moves a time in the skipped spring hour forward by the gap, and takes the
                // earlier offset in the repeated autumn hour, so neither clock change throws.
                ZonedDateTime opens = ZonedDateTime.of(date, window.open(), UK_ZONE);
                if (opens.isAfter(from) && (next == null || opens.isBefore(next))) {
                    next = opens;
                }
            }
        }
        return next;
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

    /**
     * Refuses a storefront order for a shop that is not taking orders now.
     *
     * @throws ShopClosedException (409 shop-closed) naming the reason and, when known, when orders reopen
     */
    public void requireTakingOrders(Shop shop) {
        Availability availability = evaluate(shop);
        if (availability.openNow()) {
            return;
        }
        ZonedDateTime reopens = availability.nextOpenAt();
        throw new ShopClosedException(shop.getName(), availability.reason().name(),
                reopens == null ? null : reopens.toOffsetDateTime(), detail(shop.getName(), availability));
    }

    /**
     * The human sentence (UI-SPEC § API problem details, goods P2-CHA-14):
     * "{Shop} isn't taking orders ({reason}). {Reopens at {time} / No reopening time is set.}"
     */
    private String detail(String shopName, Availability availability) {
        String why = switch (availability.reason()) {
            case PAUSED -> "paused";
            case CLOSED_NOW -> "closed right now";
            case CLOSED_TODAY -> "closed today";
            case NO_HOURS -> "no opening hours are set";
        };
        return shopName + " isn't taking orders (" + why + "). " + reopening(availability.nextOpenAt());
    }

    private String reopening(ZonedDateTime reopens) {
        if (reopens == null) {
            return "No reopening time is set.";
        }
        ZonedDateTime local = reopens.withZoneSameInstant(UK_ZONE);
        if (local.toLocalDate().equals(ZonedDateTime.now(clock).withZoneSameInstant(UK_ZONE).toLocalDate())) {
            return "Reopens at " + HH_MM.format(local) + ".";
        }
        return "Reopens on " + local.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.UK)
                + " at " + HH_MM.format(local) + ".";
    }
}
