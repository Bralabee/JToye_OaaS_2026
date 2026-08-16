package uk.jtoye.core.product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The UK FSA 14 allergens: the bit-to-name table, mask helpers, and resolution of
 * emphasised ingredients-text to an allergen bit.
 *
 * <p><b>Why this exists in Java at all.</b> The table lived only in TypeScript
 * ({@code frontend/types/api.ts}) after the old {@code PublicStorefrontService.ALLERGEN_NAMES}
 * was deleted on 2026-07-30. Phase 31 D-04 puts the order-level allergen aggregate on the
 * kitchen display, which is fed from the backend, so a server-side copy is unavoidable.
 * Two copies of a safety table are a drift hazard, so
 * {@code frontend/__tests__/allergen-table-parity.test.ts} reads BOTH files off disk and
 * compares all 14 pairs, with a positive control asserting 14 were extracted from each
 * before comparing. Keep the {@code new Allergen(bit, "Name")} shape below — that gate
 * matches on the declaration, not on a comment.
 *
 * <h2>Privacy boundary (Phase 31 D-01 / D-02)</h2>
 * <p>Nothing here reads, imports, accepts or infers a consumer's stored allergen profile.
 * That profile is Article 9 special-category health data and the platform's recorded
 * decision is not to process it. Everything in this class operates on the VENDOR's own
 * declared product data. A signature here that accepted a consumer's mask would be wrong
 * by design, and the constraint is asserted by grep in this plan's verification because a
 * compiler cannot express it.
 *
 * <h2>The resolver, and which way it errs</h2>
 * <p>{@link #resolveBits(String)} maps the text of an emphasised span (the
 * {@code **allergen**} run that {@link IngredientMarkupParser} extracts) onto allergen
 * bits, through the <b>explicit, hand-written, deliberately conservative</b> synonym list
 * below. It is not derived from the allergen names by regex and does no stemming: plural
 * and spelling variants are written out one by one, so the list can be read and audited
 * as prose by someone who is not a programmer.
 *
 * <p>Matching is on <b>whole words</b> within the span text, lower-cased. That is why
 * {@code "yoghurt (milk)"} — the only real emphasised span in this repository's own
 * fixtures — resolves, while {@code "eggplant"} and {@code "nutmeg"} do not: a bare
 * {@code contains} would flag both, and a flag that fires on ordinary ingredients is
 * ignored by the kitchen, which is the same outcome as no flag at all.
 *
 * <p><b>Error direction, stated deliberately:</b> where the two failure modes conflict,
 * this resolver errs toward <b>over</b>-flagging, never under-flagging. Its output is an
 * advisory "Check" line, so a false positive costs a kitchen one question; a false
 * negative is an undeclared allergen reaching someone who is allergic to it. Two known
 * over-flags follow directly from that choice and are accepted: {@code "cocoa butter"}
 * and {@code "coconut milk"} both resolve to Milk although neither contains dairy. Both
 * remain advisory and neither can widen a declared mask — see
 * {@code uk.jtoye.core.order.OrderAllergenAggregator}.
 *
 * <p>The matcher is a single left-to-right character scan over a finite map. There is no
 * regular expression and therefore no backtracking behaviour a pathological vendor string
 * could exploit, and — like {@link IngredientMarkupParser} — it never throws on vendor
 * input.
 */
public final class AllergenCatalog {

    /** The number of allergens on the UK FSA list. Bits are 0..13 inclusive. */
    public static final int BIT_COUNT = 14;

    private AllergenCatalog() {
    }

    /** One entry of the UK FSA table: the bit position and the name shown to a human. */
    public record Allergen(int bit, String name) {
    }

    /**
     * The 14 UK FSA allergens in bit order. This list is the Java half of a two-copy
     * table; the other half is {@code ALLERGENS} in {@code frontend/types/api.ts} and the
     * two are held together by a parity test that reads both files from disk.
     */
    private static final List<Allergen> ALLERGENS = List.of(
            new Allergen(0, "Gluten"),
            new Allergen(1, "Crustaceans"),
            new Allergen(2, "Eggs"),
            new Allergen(3, "Fish"),
            new Allergen(4, "Peanuts"),
            new Allergen(5, "Soybeans"),
            new Allergen(6, "Milk"),
            new Allergen(7, "Nuts"),
            new Allergen(8, "Celery"),
            new Allergen(9, "Mustard"),
            new Allergen(10, "Sesame"),
            new Allergen(11, "Sulphites"),
            new Allergen(12, "Lupin"),
            new Allergen(13, "Molluscs"));

    /**
     * Terms that name an allergen in UK ingredients prose, lower-case, one whole word
     * each. Hand-written and conservative on purpose: generic culinary words that would
     * match most products are deliberately absent (there is no {@code flour}, because
     * rice and gram flour are gluten-free; no {@code bread}; no {@code sauce}).
     *
     * <p>Plural and spelling variants are listed individually rather than stemmed, so
     * that adding a term is an explicit, reviewable act and no rule can silently widen
     * the list. Every one of the 14 names below resolves from its own lower-cased name —
     * asserted in {@code AllergenCatalogTest}, so an allergen cannot sit in the table
     * with no way to reach it.
     */
    private static final Map<String, Integer> SYNONYMS = buildSynonyms();

    private static Map<String, Integer> buildSynonyms() {
        Map<String, Integer> m = new LinkedHashMap<>();

        // 0 — Gluten (cereals containing gluten)
        put(m, 0, "gluten", "wheat", "barley", "rye", "oat", "oats", "spelt", "kamut",
                "semolina", "couscous", "durum", "farro", "malt", "seitan", "breadcrumb",
                "breadcrumbs", "bulgur", "bulghur", "freekeh", "triticale");

        // 1 — Crustaceans
        put(m, 1, "crustacean", "crustaceans", "prawn", "prawns", "shrimp", "shrimps",
                "crab", "crabs", "lobster", "lobsters", "crayfish", "langoustine",
                "langoustines", "scampi", "krill");

        // 2 — Eggs
        put(m, 2, "egg", "eggs", "albumen", "ovalbumin", "mayonnaise", "mayo", "meringue");

        // 3 — Fish
        put(m, 3, "fish", "anchovy", "anchovies", "cod", "salmon", "tuna", "haddock",
                "mackerel", "sardine", "sardines", "pollock", "tilapia", "trout", "herring");

        // 4 — Peanuts (listed separately from tree nuts by the FSA)
        put(m, 4, "peanut", "peanuts", "groundnut", "groundnuts", "arachis");

        // 5 — Soybeans
        put(m, 5, "soy", "soya", "soybean", "soybeans", "tofu", "edamame", "miso", "tempeh");

        // 6 — Milk
        put(m, 6, "milk", "dairy", "butter", "buttermilk", "cheese", "cheeses", "cream",
                "yoghurt", "yogurt", "whey", "casein", "caseinate", "lactose", "ghee");

        // 7 — Nuts (tree nuts)
        put(m, 7, "nut", "nuts", "almond", "almonds", "hazelnut", "hazelnuts", "walnut",
                "walnuts", "cashew", "cashews", "pecan", "pecans", "pistachio", "pistachios",
                "macadamia", "macadamias", "praline", "marzipan", "frangipane");

        // 8 — Celery
        put(m, 8, "celery", "celeriac");

        // 9 — Mustard
        put(m, 9, "mustard");

        // 10 — Sesame
        put(m, 10, "sesame", "tahini", "tahina", "benne");

        // 11 — Sulphites (including the E-numbers a label may print instead of a word)
        put(m, 11, "sulphite", "sulphites", "sulfite", "sulfites", "sulphur", "sulfur",
                "e220", "e221", "e222", "e223", "e224", "e226", "e227", "e228");

        // 12 — Lupin
        put(m, 12, "lupin", "lupins", "lupine");

        // 13 — Molluscs
        put(m, 13, "mollusc", "molluscs", "mollusk", "mollusks", "mussel", "mussels",
                "oyster", "oysters", "squid", "calamari", "octopus", "snail", "snails",
                "clam", "clams", "scallop", "scallops", "whelk", "whelks", "cuttlefish");

        return Map.copyOf(m);
    }

    /**
     * Register terms against a bit, refusing a term that already names a different
     * allergen. Without this a copy-paste slip would silently reassign a term and the
     * reconciliation would name the wrong allergen — worse than naming none.
     */
    private static void put(Map<String, Integer> m, int bit, String... terms) {
        for (String term : terms) {
            Integer existing = m.putIfAbsent(term, bit);
            if (existing != null && existing != bit) {
                throw new IllegalStateException(
                        "allergen synonym '" + term + "' is claimed by both bit " + existing
                                + " and bit " + bit);
            }
        }
    }

    // ------------------------------------------------------------------- the table

    /** The 14 UK FSA allergens in bit order. Immutable. */
    public static List<Allergen> allergens() {
        return ALLERGENS;
    }

    /**
     * The human-readable name for an allergen bit.
     *
     * @throws IllegalArgumentException if {@code bit} is outside 0..13 — there is no
     *                                  fifteenth allergen, and inventing a name for one
     *                                  would put an unlabelled warning in front of a
     *                                  consumer
     */
    public static String nameFor(int bit) {
        if (bit < 0 || bit >= BIT_COUNT) {
            throw new IllegalArgumentException(
                    "allergen bit out of range 0.." + (BIT_COUNT - 1) + ": " + bit);
        }
        return ALLERGENS.get(bit).name();
    }

    /**
     * Whether {@code mask} declares the allergen at {@code bit}. Identical to the
     * TypeScript {@code (mask & (1 << bit)) !== 0} for every bit in the catalogue.
     *
     * <p>Outside 0..13 this returns {@code false} rather than reading an arbitrary bit:
     * those positions name no allergen, so "does this mask declare it" has no true answer.
     */
    public static boolean hasAllergen(int mask, int bit) {
        if (bit < 0 || bit >= BIT_COUNT) {
            return false;
        }
        return (mask & (1 << bit)) != 0;
    }

    /** The bits set in {@code mask}, ascending, deduplicated. Empty (never null) for 0. */
    public static List<Integer> bitsFor(int mask) {
        List<Integer> bits = new ArrayList<>();
        for (int bit = 0; bit < BIT_COUNT; bit++) {
            if (hasAllergen(mask, bit)) {
                bits.add(bit);
            }
        }
        return List.copyOf(bits);
    }

    /**
     * The allergen names declared by {@code mask}, in bit order, deduplicated. Empty
     * (never null) for a mask of 0 — an empty allergen set is a value the checkout panel
     * still has to render honestly, not an absence.
     */
    public static List<String> namesFor(int mask) {
        List<String> names = new ArrayList<>();
        for (int bit = 0; bit < BIT_COUNT; bit++) {
            if (hasAllergen(mask, bit)) {
                names.add(ALLERGENS.get(bit).name());
            }
        }
        return List.copyOf(names);
    }

    // ---------------------------------------------------------------- the resolver

    /**
     * Every allergen named by {@code spanText}, ascending by bit and deduplicated.
     *
     * <p>{@code spanText} is the text of one emphasised run — that is,
     * {@code plainText.substring(span.start(), span.end())} over an
     * {@link IngredientMarkupParser.ParsedIngredients}. Matching is whole-word and
     * case-insensitive over the explicit synonym list; punctuation and whitespace are
     * word separators, so {@code "yoghurt (milk)"} resolves and {@code "eggplant"} does
     * not.
     *
     * <p>Returns every match rather than the first because a vendor may emphasise a run
     * that names two allergens ({@code **milk and egg**}); returning only one would
     * under-declare, which is the direction that injures someone.
     *
     * <p>Fail-soft: {@code null}, blank and punctuation-only input yield an empty list,
     * and no input throws.
     */
    public static List<Integer> resolveBits(String spanText) {
        if (spanText == null || spanText.isBlank()) {
            return List.of();
        }

        boolean[] found = new boolean[BIT_COUNT];
        StringBuilder word = new StringBuilder();
        String text = spanText.trim();

        for (int i = 0, n = text.length(); i <= n; i++) {
            // The i == n pass flushes the trailing word without duplicating the lookup.
            char c = i < n ? text.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
                continue;
            }
            if (word.length() > 0) {
                Integer bit = SYNONYMS.get(word.toString());
                if (bit != null) {
                    found[bit] = true;
                }
                word.setLength(0);
            }
        }

        List<Integer> bits = new ArrayList<>();
        for (int bit = 0; bit < BIT_COUNT; bit++) {
            if (found[bit]) {
                bits.add(bit);
            }
        }
        return List.copyOf(bits);
    }

    /**
     * The lowest-numbered allergen bit named by {@code spanText}, or empty when the span
     * names none. Convenience over {@link #resolveBits(String)}; callers that must not
     * under-declare (the order reconciliation is one) should use {@code resolveBits}.
     */
    public static Optional<Integer> resolveBit(String spanText) {
        List<Integer> bits = resolveBits(spanText);
        return bits.isEmpty() ? Optional.empty() : Optional.of(bits.get(0));
    }
}
