package uk.jtoye.core.shop;

/**
 * Where a shop's structured opening schedule ({@code shops.opening_schedule}) came from (Phase 37-19,
 * D-15 and D-25). Stored as text in {@code shops.hours_source}, constrained by {@code ck_shops_hours_source}.
 *
 * <p>NULL in the column means "not classified": a shop the application wrote after V80 and before its vendor
 * saved a structured schedule. It is deliberately not given a default, so that state stays distinguishable
 * from every value below.
 */
public enum HoursSource {

    /** The vendor saved a structured schedule. The only value the application writes (37-20). */
    STRUCTURED,

    /** V80 converted the shop's free-text hours, and every day was understood. */
    MIGRATED_FROM_TEXT,

    /**
     * V80 converted the shop's free-text hours, but at least one entry was not understood. Each such day is
     * stored as the storefront already renders it, and the original text stays in {@code opening_hours} so
     * the vendor can be shown what needs fixing. Written only by V80.
     */
    NEEDS_REVIEW,

    /**
     * The shop had no hours at all (NULL or {@code {}}) when V80 ran, so it has always taken orders at any
     * time. D-25 keeps it that way until its vendor saves hours. This records where the hours came from, not
     * a statement by the vendor that the shop is open around the clock, which is why it is written ONLY by
     * V80 and never by the application (the V63/V66 rule: a fabricated statement must stay distinguishable
     * from a real one).
     */
    LEGACY_ALWAYS_OPEN
}
