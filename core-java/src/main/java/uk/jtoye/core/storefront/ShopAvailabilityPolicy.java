package uk.jtoye.core.storefront;

import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.ShopClosedException;
import uk.jtoye.core.shop.Shop;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

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
 * <p>Vendor, REST and MCP orders ({@code OrderService.createOrder}) never call this: a phone order taken by
 * staff while the storefront is paused is legitimate (recorded reason, 37-20).
 */
@Component
public class ShopAvailabilityPolicy {

    /** Opening hours and pause times are UK local times. */
    public static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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
        if (Boolean.TRUE.equals(shop.getOrdersPausedIndefinitely())) {
            return new Availability(false, Reason.PAUSED, null, null, true);
        }
        OffsetDateTime pausedUntil = shop.getOrdersPausedUntil();
        if (pausedUntil != null && pausedUntil.toInstant().isAfter(now.toInstant())) {
            return new Availability(false, Reason.PAUSED, pausedUntil.atZoneSameInstant(UK_ZONE), pausedUntil, false);
        }
        return new Availability(true, null, null, null, false);
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
