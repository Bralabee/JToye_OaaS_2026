package uk.jtoye.core.finance;

import uk.jtoye.core.finance.dto.FinanceScopeDto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * What a finance read is filtered on (Phase 37-11, RWO-027 / UXT-027): one shop or every shop, and an
 * inclusive range of UK days. One scope is threaded through the summary, the transaction list and the CSV
 * export, so all three answer for exactly the same rows, and its {@link #toDto() echo} is what each response
 * reports back.
 *
 * <p>The authorisation decision is NOT made here: the controller has already run
 * {@code ShopAccessService.require(shopId, SHOP_MANAGER)} for a shop, or {@code requireGroupAdmin()} for every
 * shop, before a scope reaches the service.
 *
 * @param shopId   the shop, or null for every shop of the tenant (rows with no shop included)
 * @param shopName the shop's name, resolved under the caller's tenant; null when {@code shopId} is null
 * @param from     first UK day included, or null for an open start
 * @param to       last UK day included, or null for an open end
 */
public record FinanceScope(UUID shopId, String shopName, LocalDate from, LocalDate to) {

    /** UK calendar days: a day is midnight to midnight in Europe/London, BST included. */
    public static final ZoneId UK = ZoneId.of("Europe/London");

    /**
     * Bound when a range end is open. Non-null sentinels rather than nullable binds: PostgreSQL cannot type
     * an untyped NULL parameter. No ledger row predates 1970 or postdates 9999.
     */
    static final OffsetDateTime OPEN_START = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime OPEN_END = OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

    /** Bound to {@code shop_id} when every shop is read; never matched, because {@code allShops} is true. */
    static final UUID NO_SHOP = new UUID(0L, 0L);

    public FinanceScope {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from (" + from + ") must not be after to (" + to + ")");
        }
    }

    /** Every shop of the tenant, all time: the pre-37-11 tenant-wide view. */
    public static FinanceScope tenantWide() {
        return new FinanceScope(null, null, null, null);
    }

    public boolean allShops() {
        return shopId == null;
    }

    /** True when the scope filters nothing, so the unscoped CQ-02 aggregates answer it exactly. */
    public boolean isUnfiltered() {
        return shopId == null && from == null && to == null;
    }

    /** The shop to bind: the scope's shop, or a placeholder that the {@code allShops} flag short-circuits. */
    UUID shopIdOrPlaceholder() {
        return shopId != null ? shopId : NO_SHOP;
    }

    /** Inclusive lower bound: the start of the first UK day. */
    OffsetDateTime fromInclusive() {
        return from != null ? from.atStartOfDay(UK).toOffsetDateTime() : OPEN_START;
    }

    /** Exclusive upper bound: the start of the UK day after the last one. */
    OffsetDateTime toExclusive() {
        return to != null ? to.plusDays(1).atStartOfDay(UK).toOffsetDateTime() : OPEN_END;
    }

    public FinanceScopeDto toDto() {
        return new FinanceScopeDto(shopId, shopName, from, to);
    }
}
