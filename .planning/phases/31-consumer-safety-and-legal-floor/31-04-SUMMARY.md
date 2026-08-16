---
phase: 31-consumer-safety-and-legal-floor
plan: 04
subsystem: api
tags: [allergen, food-safety, ppds, natashas-law, fsa, java, jest, cross-language-parity, pure-function]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding
    provides: "V41 PPDS/Natasha's-Law columns (products.allergen_spans) and IngredientMarkupParser — the markup parsing substrate reused here rather than forked"
provides:
  - "AllergenCatalog — the 14 UK FSA allergen bits with names, mask helpers, and span-text to bit resolution (Java had no allergen table at all since 2026-07-30)"
  - "OrderAllergenAggregator — a pure function returning an order's declared allergen union AND a separate advisory reconciliation flag list"
  - "A cross-language parity gate that makes the Java and TypeScript allergen tables incapable of drifting silently"
affects: [31-10, 31-13, 31-14, 31-15]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pure static aggregation class in the IngredientMarkupParser mould — no Spring stereotype, no repository, no EntityManager"
    - "Cross-language constant-table parity test reading BOTH source files off disk behind a positive control"
    - "Advisory-vs-authoritative output separation: a heuristic result is carried beside the declaration, never merged into it"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/product/AllergenCatalog.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderAllergenAggregator.java
    - core-java/src/test/java/uk/jtoye/core/product/AllergenCatalogTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderAllergenAggregatorTest.java
    - frontend/__tests__/allergen-table-parity.test.ts
  modified:
    - docs/metrics.json

key-decisions:
  - "The reconciliation flag is advisory and structurally separate from the declared mask — a text heuristic never rewrites a vendor's legally operative allergen declaration"
  - "Reconciliation is per item against the ITEM's own mask, never the order union, so a correctly-declaring product cannot excuse a mis-declaring one"
  - "Only EMPHASISED (**...**) runs are candidates for reconciliation — treating every unmarked word as a candidate would fire on nearly every product"
  - "The synonym list is explicit and hand-written with plurals spelled out; no stemming, no regex derivation from the allergen names"
  - "Where the two failure modes conflict the resolver errs toward over-flagging, because the output is advisory and the under-flag direction is the one that injures someone"
  - "resolveBits (plural) added alongside the planned resolveBit so a span naming two allergens flags both — returning only the first would under-declare"
  - "Word-boundary matching implemented as a single character scan over a finite map, not a regex, so no pathological vendor string can trigger backtracking"

patterns-established:
  - "Constant-table parity: when a safety-relevant table must exist in two languages, gate it with a test that reads both files from disk and carries a positive control asserting the expected row count before comparing"
  - "Dangerous-direction testing: assert both halves of a must-not-happen invariant in a SINGLE test so a later change cannot satisfy one and quietly drop the other"

requirements-completed: [LGL-03]

# Metrics
duration: 62min
completed: 2026-08-16
---

# Phase 31 Plan 04: Allergen Catalogue and Order Aggregation Summary

**The 14 UK FSA allergen bits now exist in Java with a conservative span-text resolver, and an order's declared allergen union is computed alongside a separate advisory flag naming any product whose ingredients text emphasises an allergen its declared mask omits — the QA council's A11Y-02 case, which nothing on the tree reconciled before.**

## Performance

- **Duration:** ~62 min
- **Started:** 2026-08-16T12:04Z
- **Completed:** 2026-08-16T13:06Z
- **Tasks:** 2 (both TDD)
- **Files created:** 5 · **Files modified:** 1 (generated manifest)

## Accomplishments

- **Java has an allergen table again.** `PublicStorefrontService.ALLERGEN_NAMES` was deleted on
  2026-07-30, leaving the 14-bit table only in `frontend/types/api.ts`. D-04 puts the aggregate on
  the backend-fed KDS, so a server-side copy was unavoidable. It is held to the TypeScript copy by a
  parity test that reads both files off disk.
- **Span text resolves to an allergen bit.** `AllergenSpan` carries offsets and nothing else, so this
  mapping existed in neither language. It now resolves the repo's own real fixture
  (`"yoghurt (milk)"` → Milk) while rejecting a spice (`"cardamom"`) and a word that merely contains
  a synonym (`"eggplant"`, `"nutmeg"`, `"butternut squash"`).
- **The reconciliation exists and provably cannot widen a declaration.** The most dangerous failure
  mode — folding the heuristic into the declared mask — is asserted in a single test covering both
  halves, and that test was observed going RED against a deliberately-widened implementation.
- **PPDS output proven unchanged, not asserted unchanged.** `ProductLabelServiceTest` (9/9) and
  `ProductLabelGoldenFileTest` are green with zero edits to their expectations, and the regression
  arm was itself falsified: breaking `ProductLabelService`'s emphasis run turns both suites red, so
  their green is evidence rather than a test that passes on anything.

## Task Commits

1. **Task 1 (RED): failing tests for the Java allergen catalogue and cross-language parity** — `27571aed` (test)
2. **Task 1 (GREEN): AllergenCatalog — the 14 UK FSA bits and span-text bit resolution** — `4b29e106` (feat)
3. **Task 2 (RED): failing tests for order-level allergen aggregation and reconciliation** — `ce82d0a9` (test)
4. **Task 2 (GREEN): OrderAllergenAggregator — declared union plus an advisory reconciliation flag** — `c80a58e0` (feat)
5. **Manifest regeneration for the new test files** — `f079c924` (chore)

No REFACTOR commit was needed on either task — neither implementation required cleanup after passing.

**TDD gate compliance:** both tasks show `test(...)` before `feat(...)` in `git log`. Verified.

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/product/AllergenCatalog.java` — the 14 bit/name pairs, mask
  helpers, and the conservative synonym resolver.
- `core-java/src/main/java/uk/jtoye/core/order/OrderAllergenAggregator.java` — pure static
  aggregation returning the declared union and the advisory flags as two independent fields.
- `core-java/src/test/java/uk/jtoye/core/product/AllergenCatalogTest.java` — 21 cases.
- `core-java/src/test/java/uk/jtoye/core/order/OrderAllergenAggregatorTest.java` — 17 cases.
- `frontend/__tests__/allergen-table-parity.test.ts` — 5 cases, two of them positive controls.
- `docs/metrics.json` — regenerated (2807 → 2850). See "Issues Encountered" — this needs one
  post-wave reconciliation commit that only the wave owner can author.

## API surface for 31-10, 31-13, 31-14 and 31-15

Wire against these exactly; do not re-derive them.

```java
// uk.jtoye.core.product.AllergenCatalog   (final class, private ctor, all static)
public static final int BIT_COUNT = 14;
public record Allergen(int bit, String name) {}

public static List<Allergen>  allergens();                 // 14, bit order, immutable
public static String          nameFor(int bit);            // throws IllegalArgumentException outside 0..13
public static boolean         hasAllergen(int mask, int bit);  // false outside 0..13
public static List<Integer>   bitsFor(int mask);           // ascending, deduped, empty (never null) for 0
public static List<String>    namesFor(int mask);          // bit order, deduped, empty (never null) for 0
public static List<Integer>   resolveBits(String spanText); // ALL allergens the span names, ascending
public static Optional<Integer> resolveBit(String spanText); // lowest bit resolveBits found
```

```java
// uk.jtoye.core.order.OrderAllergenAggregator   (final class, private ctor, all static)
public record ItemAllergens(String productName, int declaredMask, String ingredientsText) {}
public record ReconciliationFlag(String productName, int allergenBit, String allergenName) {}
public record OrderAllergens(int declaredMask,
                             List<Integer> declaredBits,
                             List<String> declaredNames,
                             List<ReconciliationFlag> flags) {}

public static OrderAllergens aggregate(Collection<ItemAllergens> items);  // null-tolerant, never throws
```

**Contract notes the UI plans must honour:**

- `declaredNames()` is what S3's chips and S4's banner render. It is **empty, not null**, when the
  order declares nothing — S3 still renders the panel with the honest copy (UI-SPEC S3 "Empty set"),
  S4 renders nothing at all (UI-SPEC S4 "No-allergen orders").
- `flags()` is the "Check" line. It is **never** merged into `declaredNames()`. Rendering a flag as
  if it were a declared allergen would defeat the separation this plan is built on.
- Flags are emitted in item order and deduplicated per (product, allergen).
- Nothing in either signature accepts a consumer allergen mask. D-01 holds structurally, not by
  convention.

## The final synonym list (for 31-13's conformance statement and the privacy notice)

31-13 must describe what the platform does and does not check. It checks **only** the emphasised
`**...**` runs in the vendor's own ingredients text, against this fixed list, and never against
anything about the person ordering.

| Bit | Allergen | Terms |
|---|---|---|
| 0 | Gluten | gluten, wheat, barley, rye, oat, oats, spelt, kamut, semolina, couscous, durum, farro, malt, seitan, breadcrumb, breadcrumbs, bulgur, bulghur, freekeh, triticale |
| 1 | Crustaceans | crustacean, crustaceans, prawn, prawns, shrimp, shrimps, crab, crabs, lobster, lobsters, crayfish, langoustine, langoustines, scampi, krill |
| 2 | Eggs | egg, eggs, albumen, ovalbumin, mayonnaise, mayo, meringue |
| 3 | Fish | fish, anchovy, anchovies, cod, salmon, tuna, haddock, mackerel, sardine, sardines, pollock, tilapia, trout, herring |
| 4 | Peanuts | peanut, peanuts, groundnut, groundnuts, arachis |
| 5 | Soybeans | soy, soya, soybean, soybeans, tofu, edamame, miso, tempeh |
| 6 | Milk | milk, dairy, butter, buttermilk, cheese, cheeses, cream, yoghurt, yogurt, whey, casein, caseinate, lactose, ghee |
| 7 | Nuts | nut, nuts, almond, almonds, hazelnut, hazelnuts, walnut, walnuts, cashew, cashews, pecan, pecans, pistachio, pistachios, macadamia, macadamias, praline, marzipan, frangipane |
| 8 | Celery | celery, celeriac |
| 9 | Mustard | mustard |
| 10 | Sesame | sesame, tahini, tahina, benne |
| 11 | Sulphites | sulphite, sulphites, sulfite, sulfites, sulphur, sulfur, e220, e221, e222, e223, e224, e226, e227, e228 |
| 12 | Lupin | lupin, lupins, lupine |
| 13 | Molluscs | mollusc, molluscs, mollusk, mollusks, mussel, mussels, oyster, oysters, squid, calamari, octopus, snail, snails, clam, clams, scallop, scallops, whelk, whelks, cuttlefish |

**Stated limitations — 31-13 must not overclaim past these:**

- Matching is whole-word and case-insensitive over that fixed list. It does no stemming and
  understands no language other than these literal terms.
- It reads **only emphasised runs**. An allergen present in the ingredients text but not marked up
  by the vendor is not flagged (asserted by `unmarkedIngredientTextIsNotFlagged`).
- It errs toward over-flagging. Two known over-flags are accepted and documented in the class:
  `"cocoa butter"` and `"coconut milk"` both resolve to Milk though neither contains dairy.
- It is **advisory**. It is not an allergen test, not a laboratory result, and never modifies the
  vendor's declaration.

## Decisions Made

1. **`resolveBits` added alongside the planned `resolveBit`.** The plan specified only the singular
   form. A vendor may emphasise a run naming two allergens (`**milk and egg**`); resolving only the
   first would under-declare, which is the direction that injures someone. `resolveBit` is retained
   with the specified signature and is defined as the lowest bit `resolveBits` found, so the two
   agree by construction. Logged as a Rule 2 deviation below.
2. **Reconciliation reads emphasised runs only.** Flagging every unmarked word would fire on almost
   every product; a flag that fires on everything is ignored, which is threat T-31-04-04.
3. **Per-item, not per-order, mask comparison.** Comparing against the order union would let a second
   product that correctly declares milk silently excuse a first product that did not.
4. **A duplicate-term guard in the synonym builder.** A term claimed by two different bits now throws
   at class load. Without it a copy-paste slip would make the reconciliation name the *wrong*
   allergen, which is worse than naming none.
5. **`hasAllergen` returns false outside bits 0..13** rather than reading an arbitrary bit. Documented
   as the one deliberate divergence from the TypeScript helper, which is unguarded; the two are
   identical across the entire catalogue range, which is the range that exists.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added `resolveBits` so a span naming two allergens flags both**
- **Found during:** Task 1 (AllergenCatalog)
- **Issue:** The plan specified only `resolveBit` returning `Optional<Integer>`. A single emphasised
  run can legitimately name two allergens (`**milk and egg**`), and a single-valued resolver would
  silently drop the second — under-declaration on the one surface that can injure someone.
- **Fix:** Added `resolveBits(String) -> List<Integer>` (ascending, deduplicated) as the primary
  resolver; `resolveBit` is retained with the planned signature and returns the lowest bit found.
  `OrderAllergenAggregator` uses `resolveBits`. Purely additive — every behaviour the plan specified
  for `resolveBit` still holds and is still asserted.
- **Files modified:** `AllergenCatalog.java`, `AllergenCatalogTest.java`, `OrderAllergenAggregatorTest.java`
- **Verification:** `aSpanNamingTwoAllergensFlagsBoth` and `resolveBitsReturnsAllNamedAllergens`; the
  latter also pins `resolveBit` to `resolveBits.get(0)`.
- **Committed in:** `4b29e106` / `c80a58e0`

**2. [Rule 2 - Missing Critical] Duplicate-synonym guard in the table builder**
- **Found during:** Task 1
- **Issue:** A `Map` built from 140-odd literals silently overwrites a repeated key. If a term were
  listed under two allergens, the reconciliation would confidently name the wrong one.
- **Fix:** `put(...)` uses `putIfAbsent` and throws when an existing term maps to a different bit.
- **Files modified:** `AllergenCatalog.java`
- **Verification:** Class loads (all 38 Java tests run), so no term is doubly claimed today; the guard
  fails loudly at class-load if one is added later.
- **Committed in:** `4b29e106`

**3. [Rule 2 - Missing Critical] Bounds clamp on span offsets before `substring`**
- **Found during:** Task 2
- **Issue:** `IngredientMarkupParser` is the only producer of these offsets, but this is
  vendor-derived data on a safety path and an `IndexOutOfBoundsException` here means the allergen
  panel does not render at all.
- **Fix:** Offsets outside `plainText` are skipped rather than dereferenced.
- **Files modified:** `OrderAllergenAggregator.java`
- **Verification:** `danglingMarkupIsFailSoft`, `absentIngredientsTextIsFailSoft`, `nullInputIsFailSoft`.
- **Committed in:** `c80a58e0`

**4. [Rule 3 - Blocking] `frontend/node_modules` absent in the worktree**
- **Found during:** Task 1 (running the parity test)
- **Issue:** `npx jest` fetched a standalone jest and died with `Cannot find module 'next/jest'`.
- **Fix:** Symlinked the main checkout's existing `frontend/node_modules` into the worktree for the
  duration of the run and **removed the symlink before returning**. No package was installed, no
  `package.json` or lockfile was touched, and the main checkout's `node_modules` was verified intact
  afterwards.
- **Files modified:** none (the symlink is gitignored and no longer exists)
- **Verification:** `git status --short` clean; `ls -d .../frontend/node_modules` on the main checkout
  still resolves.
- **Committed in:** n/a — nothing to commit

---

**Total deviations:** 4 auto-fixed (3 missing-critical, 1 blocking). All three Rule-2 additions are
correctness requirements on a safety path, and all are additive — no behaviour the plan specified was
removed or weakened. No architectural change; no Rule 4 checkpoint was reached.

## Falsifiable evidence — every arm, both directions, real output

Clean → arms → clean, per the proof standard. **Committed before every break arm**; every restore
verified by `git hash-object` against `git rev-parse HEAD:<path>`, never by `git diff --stat`.

### Pre-change arm (the criteria were capable of failing before any code existed)

| Check | Result |
|---|---|
| `rg -uu -l "AllergenCatalog\|OrderAllergenAggregator" core-java/src frontend` | `rc=1`, zero files |
| **Positive control on the same instrument** — `rg -uu -l "IngredientMarkupParser" core-java/src frontend` | **7 files** — the search was live, so the zero is real absence, not a broken tool |
| `./gradlew :core-java:test --tests '*AllergenCatalogTest*' --rerun-tasks` | `AllergenCatalogTest.java:40: error: cannot find symbol`, **60 errors**, BUILD FAILED |
| `npx jest __tests__/allergen-table-parity.test.ts` | **4 failed, 1 passed** — `VOID: Java allergen table not found at .../AllergenCatalog.java`. The single pass is the TypeScript-side positive control, which is correct: that table already existed |
| `./gradlew :core-java:test --tests '*OrderAllergenAggregatorTest*' --rerun-tasks` | `OrderAllergenAggregatorTest.java:5: error: package uk.jtoye.core.order.OrderAllergenAggregator does not exist` |

### Task 1 break arms

| Arm | Break applied | Measured failure | Restored |
|---|---|---|---|
| (a) | bit 13 `Molluscs` → `Mollusks` in the Java table | parity **2 failed, 3 passed**; diff names the pair: `- "13=Molluscs"` / `+ "13=Mollusks"` | hash `030699b4` ✓ |
| (b) | deleted the `"almonds"` synonym | `resolveBit("almonds") -> bit 7 (Nuts) via a declared synonym FAILED`; **21 completed, 1 failed** — exactly one targeted case | hash `030699b4` ✓ |
| (c) | parity test's Java regex `Allergen` → `AllergenEntry` (extracts zero) | **positive control fires**: `POSITIVE CONTROL: the Java extraction found exactly 14 pairs ✕ — Expected: 14, Received: 0`. The comparison test **also** refused to run vacuously (its restated control fired with the same 14/0). 4 failed, 1 passed | hash `a39bd54a` ✓ |

Arm (c) is the one that matters most for instrument validity: without the control, both extractions
returning `[]` would have compared equal and the gate would have reported PASS having compared nothing.

### Task 2 break arms

| Arm | Break applied | Measured failure | Restored |
|---|---|---|---|
| (a) **the dangerous direction** | OR the resolved bit into `declaredMask` | `THE DANGEROUS DIRECTION: a flag NEVER widens the declared set — both halves in one test FAILED`; **17 completed, 1 failed** | hash `df5c23e4` ✓ |
| (b) | union replaced by "the first item's mask" | `two items declaring {0,6} and {6,10} aggregate to exactly {0,6,10} FAILED` and `item ordering does not affect the declared set FAILED`; **17 completed, 2 failed** | hash `df5c23e4` ✓ |
| (c) | dropped the `IngredientMarkupParser.parse` call (no spans) | **17 completed, 7 failed**, including `A11Y-02 verbatim: mask 0 with **milk** in the text -> exactly ONE flag naming the product and Milk FAILED` | hash `df5c23e4` ✓ |
| (d) **regression-arm validity** (from the plan's control table) | `ProductLabelService` emphasis run `true` → `false` | `ProductLabelGoldenFileTest > renderModelMatchesCommittedGolden() FAILED` and `ProductLabelServiceTest > buildRenderModel - emits an inline emphasised 'milk' run ... FAILED`; **11 completed, 2 failed, 1 skipped** | hash `d68de6dc` ✓ |

Arm (d) was run because a regression arm observed only passing proves nothing. It shows the PPDS
suites are a live instrument, so their green below is evidence.

### Closing clean arm — run LAST, after every restore

`git status --short` was empty before this ran, so the restores are proven by an independent second
signal as well as by content hash.

| Suite | Result (read from `build-local/test-results`, **not** from "BUILD SUCCESSFUL") |
|---|---|
| `AllergenCatalogTest` | `tests="21" skipped="0" failures="0" errors="0"` |
| `OrderAllergenAggregatorTest` | `tests="17" skipped="0" failures="0" errors="0"` |
| `ProductLabelServiceTest` | `tests="9" skipped="0" failures="0" errors="0"` |
| `ProductLabelGoldenFileTest` | `tests="2" skipped="1" failures="0" errors="0"` |
| `IngredientMarkupParserTest` | `tests="11" skipped="0" failures="0" errors="0"` |
| **Full `:core-java:test`** | **151 result files, 1136 tests, 0 failures, 0 errors, 1 skipped** |
| **Full frontend Jest** | **100 suites, 949 tests, all passed** |

The single skip is the pre-existing `@Disabled` one-shot bootstrap `captureGoldenOnce()`
(`ProductLabelGoldenFileTest.java:130`). The real assertion `renderModelMatchesCommittedGolden()`
**ran and passed** — confirmed by name in the XML, not inferred from the class-level count.

### Grep and diff limbs (the plan's `<automated>` blocks, plus two strengthenings)

| Limb | Result |
|---|---|
| `AllergenCatalog.java` contains `Molluscs` | count=2, PASS |
| `AllergenCatalog.java` has no `Customer` / `allergenRestrictions` (D-01) | count=0, PASS |
| `OrderAllergenAggregator.java` has no `allergenRestrictions` / `@Service` / `@Component` / `@Autowired` | count=0, PASS |
| `OrderAllergenAggregator.java` has no `Customer` either (from `acceptance_criteria`) | count=0, PASS |
| `git diff --name-only -- 'core-java/src/test/**/ProductLabel*'` (plan verbatim) | 0, PASS |
| **Strengthened:** same pathspec `git diff --name-only <base> HEAD` — unchanged across the WHOLE plan, not just the working tree | 0, PASS |
| **Vacuity control for the above:** `git ls-files -- <same pathspec>` | **2 tracked files** — so the two zeros above are real, not a pathspec that matches nothing |

The vacuity control was added because `git diff --name-only <pathspec> | wc -l` returns 0 both when
nothing changed and when the pathspec matches no file at all. The plan's limb alone could not tell
those apart.

## Issues Encountered

**The two docs-freshness gates now point in opposite directions, and no worktree agent can close
both.** This needs one commit from the wave owner.

Measured, both halves:

| Gate | Before `--write` | After `--write` |
|---|---|---|
| `scripts/docs-freshness.sh` (tree → manifest) | FAIL — stored 2807, computed 2850 | **PASS** (2850) |
| `scripts/check-doc-metrics.sh` (prose → manifest) | **PASS** — 37/37 claims matched | FAIL — 9 claims across `CLAUDE.md` and `AGENTS.md` still quote 1633 / 264 / 944 / 99 / 2807 |

The manifest delta is exactly and only this plan's, measured rather than assumed:
`java_test_methods 1633→1671` (+38 = `grep -c "@Test"` gives 21 + 17 on the two new files),
`java_test_files 264→266`, `jest_blocks 944→949` (+5, `grep -cE "^  it\("`), `jest_files 99→100`.

I committed the regenerated manifest and deliberately did **not** touch `CLAUDE.md`, `AGENTS.md` or
`README.md`. Two reasons: those files are outside this plan's `files_modified`, and — decisively —
the correct total cannot be known from inside one worktree while four sibling wave-1 agents are
adding tests in parallel. Any number written into the prose here is wrong the moment they merge.
`docs/metrics.json` is *derived*, so a merge conflict on it self-heals by re-running the script and
is caught by `docs-freshness.sh` itself; the prose docs are *authored*, where a bad conflict
resolution silently loses someone's text.

**Required after all wave-1 worktrees merge, in ONE commit** (the same one-commit constraint
`31-VALIDATION.md` records for the `check-gate-enforcement.sh` double bind):

```
scripts/docs-freshness.sh --write
# then update java_test_methods / java_test_files / jest_blocks / jest_files /
# total_logical_invocations in CLAUDE.md, AGENTS.md and README.md to match
scripts/check-doc-metrics.sh   # must return to PASS
```

## Known Stubs

None. Both classes are complete pure logic with no placeholder values, no hardcoded empty returns
feeding a UI, and no TODO markers. `OrderAllergenAggregator` intentionally has no caller yet — 31-10
supplies the DTO and persistence wiring and 31-14/31-15 the UI — which is the plan's declared scope,
not a stub.

## Threat Flags

None. No network endpoint, auth path, file access or schema change was introduced. The plan's own
threat register is fully covered: T-31-04-01 (widening) by break arm (a); T-31-04-02 (Article 9 data)
by the grep limbs; T-31-04-03 (pathological input) by the regex-free scan plus
`resolverIsFailSoftOnPathologicalInput`; T-31-04-04 (flag fires on everything) by the required
negative cases; T-31-04-05 (table drift) by the parity gate and its break arm (c).

## User Setup Required

None — no external service configuration, no new dependency, no migration.

## Next Phase Readiness

**Ready to consume:**

- 31-10 can wire `OrderAllergenAggregator.aggregate` behind its DTO. Note the research recommendation
  it must still settle: snapshot the mask onto `order_items` at write time rather than live-joining
  `Product`, or a post-order vendor edit silently rewrites what the consumer acknowledged. This class
  is indifferent to the choice — it takes `ItemAllergens` values, not entities.
- 31-14 (checkout S3) and 31-15 (KDS S4) can render `declaredNames()` and `flags()`. The empty-set
  behaviours differ between the two surfaces by design (S3 renders the panel, S4 renders nothing) and
  both are supported: `declaredNames()` is empty-not-null.
- 31-13 has the full synonym list and its stated limitations above, ready to quote in the
  conformance statement and privacy notice without re-deriving them.

**Explicitly NOT done here, by plan design:** no Flyway migration (31-10 owns the schema and this
plan authored none), no DTO change, no UI, and no edit to `PublicStorefrontService` or
`ProductLabelService`.

**One carried item:** the `check-doc-metrics.sh` reconciliation described under Issues Encountered.
It is a wave-level action, not a defect in this plan.

## Self-Check: PASSED

Run after writing this summary. Real output:

```
=== files ===
FOUND: core-java/src/main/java/uk/jtoye/core/product/AllergenCatalog.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderAllergenAggregator.java
FOUND: core-java/src/test/java/uk/jtoye/core/product/AllergenCatalogTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/OrderAllergenAggregatorTest.java
FOUND: frontend/__tests__/allergen-table-parity.test.ts
FOUND: docs/metrics.json
FOUND: .planning/phases/31-consumer-safety-and-legal-floor/31-04-SUMMARY.md
=== commits ===
FOUND: 27571aed  test(31-04): failing tests for the Java allergen catalogue and cross-language parity
FOUND: 4b29e106  feat(31-04): AllergenCatalog — the 14 UK FSA bits and span-text bit resolution
FOUND: ce82d0a9  test(31-04): failing tests for order-level allergen aggregation and reconciliation
FOUND: c80a58e0  feat(31-04): OrderAllergenAggregator — declared union plus an advisory reconciliation flag
FOUND: f079c924  chore(31-04): regenerate docs/metrics.json for the two new test files
FOUND: 26df779a  docs(31-04): complete the allergen catalogue and order aggregation plan
=== files-not-claimed-modified must be untouched vs base ===
UNTOUCHED: core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
UNTOUCHED: core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
UNTOUCHED: .planning/STATE.md
UNTOUCHED: .planning/ROADMAP.md
=== vacuity control for the untouched check ===
total files changed vs base: 7
=== no deletions across the plan ===
deleted files: 0
=== TDD gate order ===
27571aed test(31-04): failing tests for the Java allergen catalogue and cross-language parity
4b29e106 feat(31-04): AllergenCatalog — the 14 UK FSA bits and span-text bit resolution
ce82d0a9 test(31-04): failing tests for order-level allergen aggregation and reconciliation
c80a58e0 feat(31-04): OrderAllergenAggregator — declared union plus an advisory reconciliation flag
SELF-CHECK: PASSED
```

The `UNTOUCHED` lines carry a vacuity control (`total files changed vs base: 7`) because
`git diff --name-only <base> HEAD -- <path> | wc -l` returns 0 both when a file is untouched and
when the diff instrument is broken. Seven changed files is the correct count for this plan: five
created, `docs/metrics.json` modified, and this summary.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
