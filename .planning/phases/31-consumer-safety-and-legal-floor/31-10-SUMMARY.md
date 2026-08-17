---
phase: 31-consumer-safety-and-legal-floor
plan: 10
subsystem: database
tags: [allergen, food-safety, natashas-law, snapshot, flyway, envers, mapstruct, jpa, rls, openapi]

# Dependency graph
requires:
  - phase: 31-consumer-safety-and-legal-floor
    provides: "31-04's AllergenCatalog (the 14 UK FSA bits) and OrderAllergenAggregator (declared union + a separate advisory reconciliation flag list)"
  - phase: 18-vendor-onboarding
    provides: "V41 products.allergen_spans and IngredientMarkupParser — the markup substrate the reconciliation reads through 31-04"
provides:
  - "V63 — order_items.allergen_mask + allergen_flag_mask with their order_items_aud Envers mirrors; nullable, no backfill"
  - "A write-time allergen snapshot on BOTH order write paths, so a post-order vendor edit can no longer rewrite what a customer acknowledged"
  - "OrderAllergenSnapshot — one place that both captures the per-line snapshot and rebuilds the order-level view, so the checkout and the kitchen display cannot disagree"
  - "OrderDetailDto.allergenMask/allergenNames/allergenFlags and OrderItemDto.allergenMask/allergenNames, with 'not recorded' distinguishable from 'nothing declared' on the wire"
  - "OrderAllergenFlagDto — the published shape of an advisory reconciliation line (productName, allergenBit, allergenName)"
affects: [31-13, 31-14, 31-15, 31-18]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Write-time snapshot of safety-relevant vendor data onto the order line, beside the existing productName snapshot — the order records what was true when it was placed"
    - "Three-state nullable columns: NULL is 'not recorded', 0 is 'nothing declared', and the two are kept distinct all the way to the wire"
    - "Advisory-vs-authoritative kept apart in STORAGE, not only in memory: two independent integer columns, so a heuristic structurally cannot widen a declaration"
    - "MapStruct @AfterMapping for a value derived from a collection the entity owns but does not expose as a property"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V63__order_item_allergen_snapshot.sql
    - core-java/src/main/java/uk/jtoye/core/order/OrderAllergenSnapshot.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderAllergenFlagDto.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderAllergenSnapshotIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderItem.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderItemDto.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - frontend/types/api.ts
    - docs/api/openapi-snapshot.json
    - docs/metrics.json

key-decisions:
  - "Snapshot at write time, not a live join to Product — proven by mutating the product after the order and re-reading the ORDER, with the live-join arm run to show the assertion can fail"
  - "The reconciliation flags are stored as a SECOND integer bitmask per line, not JSONB or TEXT[] — per line a flag is exactly an allergen bit, the product is the row itself, and the allergen NAME is resolved from the parity-gated catalogue rather than frozen as prose"
  - "No backfill of historic rows: inventing a mask from today's product rows would fabricate a record of what a past customer was shown"
  - "A partially-recorded order reports NOT RECORDED, because a union over only the recorded lines is silently incomplete — under-declaration is the direction that injures someone"
  - "The snapshot is captured on BOTH order write paths, not only the storefront; the vendor/API/MCP path feeds the same kitchen display"
  - "The order-level aggregate is on OrderDetailDto and deliberately NOT on OrderDto — measured: deriving it on the list DTO costs one extra SELECT per row"
  - "Both the mask AND the server-resolved names are on the wire: names so the two consumer surfaces cannot disagree about wording, the mask so a client that already holds the table needs no round trip"

patterns-established:
  - "Three-state safety data: null / 0 / non-zero are three different statements, asserted with TWO fixtures rather than by inspection, so a future change that collapses them fails a test"
  - "Measure before deviating: when a plan instruction would cost performance, probe it (Hibernate statistics + Hibernate.isInitialized) and record the number rather than arguing from structure"
  - "A migration comment must not spell out the literal its own verification greps for — the doc rule that fires on its own definition"

requirements-completed: [LGL-03]

# Metrics
duration: 125min
completed: 2026-08-16
---

# Phase 31 Plan 10: Order Allergen Snapshot and DTO Exposure Summary

**Every order line now records the allergen mask and the advisory reconciliation flags that were true when the order was placed, mirrored into `order_items_aud`, and the order DTO carries the rebuilt aggregate plus per-item masks to the checkout and the kitchen display — so a vendor editing a product after the fact can no longer change what a customer is recorded as having acknowledged.**

## Performance

- **Duration:** ~70 min of work + ~55 min waiting on the full suite under 4-way Docker contention
- **Started:** 2026-08-16T13:20Z
- **Completed:** 2026-08-16T14:26Z
- **Tasks:** 2 (Task 1 TDD)
- **Files created:** 4 · **Files modified:** 10 · **Files deleted:** 0

## Accomplishments

- **The order is now a record, not a view onto today's product.** Before this plan `order_items`
  held no allergen data at all, so the only way to feed the checkout panel and the kitchen ticket
  was a read-time join back to `products`. The live-join arm below shows exactly what that costs:
  after a vendor edit the same order reads **512** where it read **65**, silently.
- **Declared and flagged are separated in STORAGE, not just in memory.** 31-04 made the
  reconciliation structurally incapable of widening a declaration inside one pure function; V63
  carries that property into the schema as two independent columns, so the invariant survives
  persistence rather than being re-established on each read.
- **The two write paths were closed, not one.** The plan scoped the storefront. `OrderService.createOrder`
  — the vendor / API / MCP route — feeds the same kitchen display, and leaving it out would have
  shipped every order placed that way with no allergen data at all.
- **"Not recorded" and "nothing declared" are different values end to end**, asserted with two
  fixtures. A historic ticket can never claim to be allergen-free.
- **A plan instruction was measured before it was deviated from.** Putting the aggregate on the
  list DTO was probed rather than argued about: 7 orders, 7 extra prepared statements.

## Task Commits

1. **Task 1 (RED): failing tests for the order-item allergen snapshot** — `07e96a82` (test)
2. **Task 1 (GREEN): V63, the entity, and the write-time capture on both paths** — `0217c839` (feat)
3. **Task 2: the aggregate, the flags and the per-item masks on the order DTO** — `ec64fae1` (feat)
4. **Derived manifest regeneration for V63 + the new test file** — `a31a8951` (chore)

No REFACTOR commit was needed — neither implementation required cleanup after passing.

**TDD gate compliance:** Task 1 shows `test(31-10)` at `07e96a82` before `feat(31-10)` at
`0217c839` in `git log`. Verified by name, not inferred.

## THE WIRE CONTRACT — 31-14 and 31-15 must not re-derive this

### `OrderDetailDto` (GET `/api/v1/orders/{id}/detail`, and every ticket on the kitchen board)

```java
private Integer allergenMask;                     // null = NOT RECORDED
private List<String> allergenNames;               // null = NOT RECORDED, [] = nothing declared
private List<OrderAllergenFlagDto> allergenFlags; // null = NOT RECORDED, [] = nothing flagged
```

### `OrderItemDto` (a record — the two are appended after `createdAt`)

```java
Integer allergenMask,        // null = NOT RECORDED
List<String> allergenNames   // null = NOT RECORDED, [] = nothing declared
```

### `OrderAllergenFlagDto` — `uk.jtoye.core.order.dto`

```java
public record OrderAllergenFlagDto(String productName, int allergenBit, String allergenName) {}
```

### On the wire

A recorded order that declares Gluten and whose second line emphasises an undeclared milk:

```json
{
  "allergenMask": 1,
  "allergenNames": ["Gluten"],
  "allergenFlags": [
    { "productName": "Chocolate Tart", "allergenBit": 6, "allergenName": "Milk" }
  ],
  "items": [
    { "productName": "Sourdough",      "allergenMask": 1, "allergenNames": ["Gluten"] },
    { "productName": "Chocolate Tart", "allergenMask": 0, "allergenNames": [] }
  ]
}
```

**"Not recorded" is `null` on every one of those fields, at BOTH levels, together.**

```json
{ "allergenMask": null, "allergenNames": null, "allergenFlags": null,
  "items": [ { "allergenMask": null, "allergenNames": null } ] }
```

**"Nothing declared" is `0` with empty lists**, never null:

```json
{ "allergenMask": 0, "allergenNames": [], "allergenFlags": [] }
```

### TypeScript (`frontend/types/api.ts`)

```ts
export interface OrderAllergenFlag { productName: string; allergenBit: number; allergenName: string }

// on OrderItem
allergenMask?: number | null
allergenNames?: string[] | null

// on OrderDetail
allergenMask?: number | null
allergenNames?: string[] | null
allergenFlags?: OrderAllergenFlag[] | null
```

**Do not write `allergenMask ?? 0` or `allergenNames ?? []`.** That collapse is the whole defect:
it makes a pre-V63 ticket claim to be allergen-free. Branch on `=== null` (or `== null`, which also
catches an absent field) FIRST, then on `.length === 0`.

### Rules the UI plans inherit

- **The aggregate is on `OrderDetail`, not on `Order`.** The list type deliberately has none — see
  the deviation below. A surface that needs allergens must read a detail response.
- `allergenNames` is empty-not-null when recorded-and-empty, matching 31-04's `declaredNames()`
  contract, so **S3 still renders its panel** (UI-SPEC S3 "Empty set") and **S4 renders nothing**
  (UI-SPEC S4 "No-allergen orders"). The THIRD state — null — is new here and belongs to neither:
  S4 must render no banner AND must not imply the order is allergen-free.
- `allergenFlags` is **never** merged into `allergenNames`. Rendering a flag as a declared allergen
  defeats the separation the whole design rests on.
- Flags are emitted in line order, ascending by allergen bit, deduplicated per
  (product name, allergen) — matching 31-04's emission contract exactly.

## Mask versus names: both, and why

The plan asked for one and a reason. The answer is both, because they answer different questions:

- **`allergenNames` is resolved server-side** from `AllergenCatalog`. The kitchen display and the
  checkout are two independently-built surfaces; if each mapped bits to words itself they could
  disagree about wording on a safety label. Resolving once server-side makes agreement structural.
- **`allergenMask` rides along** because the frontend already holds the table
  (`ALLERGENS` / `getAllergenNames` in `frontend/types/api.ts`, held to the Java copy by 31-04's
  parity gate), and because a bit is a stable machine identity that an agent or a future client can
  key on without parsing prose. It costs four bytes.
- `OrderAllergenFlagDto` carries `allergenBit` for the same reason it carries `allergenName`.

**Storage is two integers, not JSONB or a text array.** Per LINE a reconciliation flag is exactly
"an allergen bit" — the product is the row itself — so a bitmask preserves both halves of "which
product, which allergen" with no encoding. The allergen NAME is deliberately **not** persisted:
that would create a second, ungated copy of the parity-checked table, one row per order line,
frozen at write time. The bit is the fact; the name is a label.

## Files Created/Modified

- `V63__order_item_allergen_snapshot.sql` — two nullable INTs on `order_items` plus their
  `order_items_aud` mirrors, two column comments, no backfill, no index, no extension.
- `OrderAllergenSnapshot.java` — `capture(...)` for the write paths, `viewOf(...)` /
  `namesOf(...)` / `isRecorded(...)` for the read path. The one place the order-level view is
  derived.
- `OrderAllergenFlagDto.java` — the published shape of an advisory line.
- `OrderAllergenSnapshotIntegrationTest.java` — 8 Testcontainers cases.
- `OrderItem.java` — two boxed `Integer` fields (boxed precisely so "not recorded" has a value).
- `OrderMapper.java` — `@AfterMapping` for the order-level view; an expression mapping for the
  per-line names.
- `OrderService.java` / `PublicStorefrontService.java` — the capture, beside the existing
  `productName` snapshot. Both edits are purely additive.
- `OrderDetailDto.java` / `OrderItemDto.java` — the new fields. `OrderDto.java` — a recorded
  decision *not* to carry them, with the measurement.
- `frontend/types/api.ts` — types only; the `ALLERGENS` block is provably untouched.
- `docs/api/openapi-snapshot.json` — regenerated, **+41 insertions, 0 deletions**.
- `docs/metrics.json` — regenerated. See "Issues Encountered".

## Decisions Made

1. **Snapshot, not live join.** The plan resolved this and this plan proved it rather than assuming
   it — see arm (a).
2. **Two integer columns rather than JSONB.** Rationale above and in the migration header.
3. **No backfill.** Historic rows stay NULL. Stated in the migration comment, with an explicit
   instruction not to add a `DEFAULT 0` later, because that would retroactively claim every
   historic order was allergen-free.
4. **A partially-recorded order reads as NOT RECORDED.** Unioning only the recorded lines yields a
   set that is silently incomplete but looks complete. Under-declaration is the injury direction.
5. **The aggregator is asked about one line at a time.** `aggregate()` flattens flags into an
   order-level list keyed by product NAME; attributing that back to lines by name would
   mis-attribute whenever two lines share a title (the same product ordered twice). Asking the
   same shared function per line keeps attribution exact with one implementation of the rules. The
   order-level union is then rebuilt from the stored lines, so both consumers read one computation.
6. **`OrderItem.allergenMask` is `Integer`, not `int`.** The "not recorded" state needs a
   representation; an unboxed field would silently mean 0.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] The snapshot is captured on BOTH order write paths**
- **Found during:** Task 1
- **Issue:** The plan scoped the capture to `PublicStorefrontService.createGuestOrder`. There is a
  second `new OrderItem(...)` site — `OrderService.createOrder:167`, the vendor / dashboard / API /
  MCP route — and orders created there reach exactly the same kitchen display. Snapshotting only
  the storefront would have left every such order reading "not recorded", so the KDS would
  correctly render no banner: under-declaration on the one surface that can injure someone,
  produced by an omission rather than a decision.
- **Fix:** `OrderAllergenSnapshot.capture(...)` at both sites, from the same shared helper.
- **Files modified:** `OrderService.java`, `PublicStorefrontService.java`
- **Verification:** Both call sites use the identical helper; the storefront path is asserted by all
  8 integration cases. Purely additive — `git diff --numstat` shows **0** deleted lines in
  `PublicStorefrontService.java` across the whole plan.
- **Committed in:** `0217c839`

**2. [Rule 1 - Bug] The order-level aggregate is on `OrderDetailDto`, NOT on `OrderDto`**
- **Found during:** Task 2
- **Issue:** The plan says "Add to `OrderDto` the order-level aggregate". `OrderDto` is the LIST
  DTO and carries no items by design (`OrderMapper`'s own class comment says so). The aggregate is
  derived from the lines, so populating it there means loading the very collection that DTO exists
  to avoid — on four paged endpoints.
- **Measurement, not an argument** (temporary probe, Hibernate statistics, Testcontainers Postgres,
  since removed): after `orderRepository.findAll(PageRequest)` the items collection is
  **`initialised=false`**, and touching it for **7 orders cost 7 additional prepared statements** —
  one SELECT per row. That is the same 1+N shape `getKitchenBoard` was rewritten to remove (#564).
  `hibernate.default_batch_fetch_size` is not set in any profile, so nothing dampens it.
- **Why not just emit null on the list path:** null on these fields *means* NOT RECORDED, so a list
  that merely did not load its lines would be indistinguishable from an order whose allergens were
  never recorded — precisely the collapse this plan forbids. Absent is honest; a fabricated null is
  not.
- **Fix:** the three fields live on `OrderDetailDto` — which is what BOTH declared consumers read
  (`getKitchenBoard`, which already batch-fetches lines, and `/orders/{id}/detail`) — and `OrderDto`
  carries a comment recording the measurement and the reasoning. Per-item masks are on
  `OrderItemDto` as planned.
- **Cost on the chosen DTO: zero extra queries.** `toDetailDto` already maps
  `@Mapping(target = "items", source = "items")`, so the line collection is loaded on that path
  whether or not the aggregate is derived. All three `toDetailDto` call sites were checked
  (`OrderService:226`, `:358` — an empty page — and `:375`, the batch-fetched board). The aggregate
  is free exactly where it is needed and expensive exactly where it was not.
- **Files modified:** `OrderDto.java`, `OrderDetailDto.java`, `OrderMapper.java`
- **Consequence to carry forward:** the MCP `read-orders` tool is a pass-through
  (`JSON.stringify(res.body)`), so its **`orderId` detail** call gains the allergen fields with no
  MCP change at all; its **`shopId` list** call hits `/orders/shop/{shopId}` → `OrderDto` and
  therefore does not. That is a real and deliberate asymmetry, recorded rather than glossed.
- **Committed in:** `ec64fae1`

**3. [Rule 3 - Blocking] Files outside the plan's declared list**
- **Found during:** Tasks 1 and 2
- **Issue:** The wiring the plan asks for cannot be expressed in the declared files alone.
- **Fix:** added `OrderAllergenSnapshot.java` (the shared capture/view seam — putting it in 31-04's
  `OrderAllergenAggregator` would couple that deliberately pure, entity-free class to JPA and to
  the DTO layer, which its own javadoc forbids), `OrderAllergenFlagDto.java` (the published flag
  shape), `OrderMapper.java` (the plan's `read_first` anticipates this even though its file list
  omits it), `OrderDetailDto.java` (per deviation 2), `docs/api/openapi-snapshot.json` (the plan
  requires regeneration) and `docs/metrics.json` (the plan's Task 2 `done` requires it).
- **Verification:** 14 files changed, **0 deleted**. None of 31-09's files (`DsarFanoutWorker.java`,
  `GdprService.java`, `application.yml`) was touched; `STATE.md`, `ROADMAP.md`, `README.md`,
  `CLAUDE.md` and `AGENTS.md` were not touched.
- **Committed in:** `0217c839`, `ec64fae1`, `a31a8951`

**4. [Rule 1 - Bug] The migration comment named the literal its own verification forbids**
- **Found during:** Task 1
- **Issue:** The V63 header originally read "NO CREATE EXTENSION (...)". The plan's own
  `<automated>` limb asserts `grep -cF 'CREATE EXTENSION' V63…sql` is **0**. Measured: the count was
  **1**, from the comment. A doc rule that must name the token it forbids fires on its own
  definition — a known vacuous/false-positive shape in this project's proof standards.
- **Fix:** the comment states the invariant without spelling the statement, and says why.
- **Verification:** count is now **0** (`rc=1`, no match) with a positive control on the same
  instrument (`ALTER TABLE` → **4**), so the zero is real absence and not a broken search.
- **Committed in:** `0217c839`

**5. [Rule 3 - Blocking] `frontend/node_modules` absent in the worktree**
- **Found during:** Task 2
- **Issue:** the parity test and the typecheck gate both need it.
- **Fix:** symlinked the main checkout's tree for `jest` (works); **Turbopack rejected the symlink**
  for `npm run build` with `Symlink [project]/node_modules is invalid, it points out of the
  filesystem root` — exactly as the standing note predicts — so the symlink was replaced with a
  hardlink copy (`cp -al`). No package installed, no `package.json` or lockfile touched.
- **Verification:** the main checkout's tree is intact by content and by count — sentinel
  `git hash-object node_modules/next/package.json` = `9fac749bbd3ec126aa0001715ccfaf6f26d9198a`
  before **and** after, top-level dir count **551** before and after.
- **Committed in:** n/a — `node_modules` is gitignored (`frontend/.gitignore:4`), `git status` clean.

---

**Total deviations:** 5 auto-fixed (1 missing-critical, 2 bug, 2 blocking). No Rule 4 checkpoint was
reached. Deviations 1 and 2 both move in the same direction: refusing to ship a surface that reports
less than it knows, or reports more than it loaded.

## Falsifiable evidence — every arm, both directions, real output

Committed before every break arm; every restore verified by `git hash-object` against the baseline,
never by `git diff --stat`. Counts read from `core-java/build-local/test-results`, never from a
build verdict.

### Pre-change arm — the criteria could fail before any code existed

| Check | Result |
|---|---|
| `rg -uu -l "allergen_mask\|allergenMask" core-java/src/main/java/uk/jtoye/core/order/` | no output, **zero files** |
| **Positive control, same instrument** — `rg -uu -l "AllergenCatalog" core-java/src/main/java/` | **2 files** — the search was live, so the zero is real absence |
| `./gradlew :core-java:integrationTest --tests '*OrderAllergenSnapshot*' --rerun-tasks` | `tests="6" skipped="0" failures="6" errors="0"` — 4 with `bad SQL grammar [SELECT allergen_mask, allergen_flag_mask FROM order_items …]`, 1 with the `order_items_aud` column list empty |

### Break arms

| Arm | Break applied | Measured failure | Restored (hash) |
|---|---|---|---|
| **(a) THE ARM THAT JUSTIFIES THE MIGRATION** | the read replaced with a live join: `SELECT p.allergen_mask … FROM order_items oi JOIN products p ON p.id = oi.product_id` | **2 of 6 failed.** `theSnapshotIsImmutableToALaterProductEdit` → `expected: 65 / but was: 512`. AND, unplanned, `aPreMigrationRowReadsAsNotRecordedNotAsNoAllergens` → under a live join there is **no "not recorded" state at all**: the join always yields the product's current mask, so a historic order silently claims a set it never had | `d86c1e80` ✓ |
| **(b) Envers mirror** | the two `order_items_aud` `ALTER`s removed from V63 | **6 of 6 failed** with `SQLGrammarException: could not execute batch [Batch entry 0 insert into order_items_aud (revtype,allergen_fla…]` → `ERROR: column "allergen_flag_mask" of relation "order_items_aud" does not exist`. A **runtime** failure on order creation, exactly as the migration header claims — not a build failure | `6adc9449` ✓ |
| **(c) backfill / `set_config`** | **N/A, stated rather than silently substituted** — V63 adds no backfill, so the plan's arm has nothing to break. See the substitute below | — |
| **(c′) SUBSTITUTE, strictly stronger** | the historic-row fixture's `UPDATE … WHERE order_id = ?` bound to a random UUID, simulating exactly what an RLS-filtered UPDATE looks like to the caller: success, zero rows | **1 of 6 failed**: `[the simulated pre-V63 row must actually have been written] expected: 1 / but was: 0`. The row-count assertion is live, so the recurring V25/V44/V57 trap would be caught here rather than reported as success | `d86c1e80` ✓ |
| **(d) MapStruct mapping** | `@Mapping(target = "allergenMask", ignore = true)` added to `toItemDto` | **exactly 1 of 8 failed** — `theOrderDtoCarriesTheAggregateTheFlagsAndThePerItemMask` → `AssertionError: Expecting actual not to be null`. A missing MapStruct mapping is silent at runtime and surfaces only as a null field, which is why it is asserted rather than assumed | `cbc20946` ✓ |
| **(e) the frontend typecheck gate** | `export const __breakArmProbe: number = "this is not a number"` added to `frontend/types/api.ts` | `Running TypeScript … Failed to type check. ./types/api.ts:262:14 Type error: Type 'string' is not assignable to type 'number'.` — **non-zero exit**. Run because the clean build printed no verdict line I had filtered for, and a gate never seen to fail is not evidence | `7ea5409a` ✓ |
| **(f) the extension gate** | `CREATE EXTENSION IF NOT EXISTS cube;` appended to V63 | `FAIL: 2 migration line(s) create a PostgreSQL extension`, **rc=1**, both offending lines named | `6adc9449` ✓ |

Arm (a) is the one that matters: it is the only evidence that the immutability assertion is capable
of failing, and it produced a second finding the plan did not anticipate — a live join destroys the
"not recorded" state as well as the immutability property.

### Measurement (not a break arm) — the OrderDto N+1 probe

Temporary test, Hibernate statistics enabled via `@DynamicPropertySource`, since removed and the
file restored to `d86c1e80`:

```
PROBE N+1: orders=7 itemsCollectionInitialisedAfterFindAll=false
           statementsToTouchAllItemCollections=7 (lines seen=7)
```

Exactly one SELECT per row. This is the whole basis of deviation 2.

### Closing clean arm — run LAST, after every restore

| Gate | Result |
|---|---|
| `OrderAllergenSnapshotIntegrationTest` | `tests="8" skipped="0" failures="0" errors="0"` |
| **Full `:core-java:test`** | `files=151 tests=1136 failures=0 errors=0 skipped=1` (tallied from XML; the tally script VOIDs at exit 2 on an empty result set — verified against a nonexistent dir) |
| **Full `:core-java:integrationTest`** | `files=129 tests=585 failures=0 errors=0 skipped=1` |
| ↳ `OpenApiSnapshotTest` | `tests="1" … failures="0"` — **`apiDocsMatchCommittedSnapshot()` ran and passed**, confirmed by name in the XML, so the regenerated snapshot provably matches live responses |
| ↳ `RlsContractTest` | `tests="7" … failures="0"` — the schema walk is still satisfied after the columns were added |
| ↳ `OrderAllergenSnapshotIntegrationTest` | `tests="8" … failures="0"` |
| ↳ the single skip | the pre-existing `@Disabled` one-shot bootstrap `FinancialSummaryGoldenFileTest.captureGoldenOnce()`; its real assertion `getSummaryOutputMatchesCommittedGolden()` **ran** — named in the XML, not inferred from the class count |
| `FULL_SUITE_EXIT` (`./gradlew :core-java:test :core-java:integrationTest --rerun-tasks`) | **0** — captured on its own statement, and corroborated by the XML tally above rather than believed on its own |
| **Full frontend Jest** | `JEST_EXIT=0` — **103 suites, 976 tests, all passed**, 2 snapshots. Cross-checks the regenerated manifest's `jest_blocks=976` / `jest_files=103` |
| `npx jest __tests__/allergen-table-parity.test.ts --ci` | **5 passed**, including both positive controls (`the Java extraction found exactly 14 pairs`) |
| `npm run build` (the declared typecheck gate) | `BUILD_EXIT=0`, `✓ Compiled successfully`, `Running TypeScript …` — and proven capable of failing by arm (e) |
| `bash scripts/check-no-create-extension.sh` | `PASS`, **rc=0**, `scanned: 63 migration file(s)` — the 62→63 move is itself the control that the gate saw V63 |

### Grep and diff limbs (the plan's `<automated>` blocks, plus controls)

| Limb | Result |
|---|---|
| `grep -cF 'order_items_aud' V63…sql` ≥ 1 | **5** — PASS |
| `grep -cF 'CREATE EXTENSION' V63…sql` == 0 | **0** — PASS (see deviation 4: this was **1** before the fix) |
| `grep -cF 'List<String> allergenWarnings = new ArrayList<>()' PublicStorefrontService.java` ≥ 1 | **1** — PASS |
| `git diff -U0 <base> HEAD -- frontend/types/api.ts \| grep -cE '^[-+].*(ALLERGENS\|hasAllergen\|getAllergenNames)'` == 0 | **0** — PASS |
| **Vacuity control for the above** | the same diff has **40** `+/-` lines, so the zero is real, not an empty diff |
| **Strengthening of the seam limb** | `git diff --numstat <base> HEAD -- PublicStorefrontService.java` → **0 lines deleted** across the whole plan. The plan's grep proves one line survived; this proves nothing anywhere in the file was removed |
| No file deletions across the plan | `git diff --diff-filter=D --name-only <base> HEAD` → **empty**, against **14** files changed (the vacuity control) |

`grep -q` was never used in a pipeline; every zero-count limb uses `out=$(… || true)` with a
here-string test, because `cmd | grep -q X` inverts on match under `pipefail`.

## Issues Encountered

**1. Reading `test-results/integrationTest/*.xml` mid-run returns a STALE file.** Gradle buffers
into `test-results/integrationTest/binary/` during the run and converts to XML only when the task
completes. Polling the XML directory 13 minutes into the full suite showed exactly **one** file —
timestamped from a *previous* targeted run — which reads as "the suite has barely started" or, worse,
could be tallied as a result. Progress must be read from `binary/` growth; results only after the
terminating marker. Recorded because it is a live instance of "reading a stale artifact directory".

**2. The two docs gates point in opposite directions, and no worktree agent can close both.**
Measured on this tree, both ways:

| Gate | Before `--write` | After `--write` |
|---|---|---|
| `scripts/docs-freshness.sh` (tree → manifest) | **FAIL** — stored 2892 / schema 62, computed 2900 / schema 63 | **PASS** |
| `scripts/check-doc-metrics.sh` (prose → manifest) | **PASS** — 37/37 claims matched | **FAIL** — 13 claims across README, CLAUDE and AGENTS |

The delta is exactly and only this plan's: `java_test_methods 1686→1694` (+8, this plan's 8 `@Test`
methods), `java_test_files 267→268`, `schema_version 62→63`, `total_logical_invocations 2892→2900`.
Nothing else moved. The prose was deliberately not touched — those files are outside this plan's
declared set, 31-18 owns them, and the correct total is unknowable from inside one worktree while
sibling wave-2 agents add tests in parallel. **Required from the wave owner, in ONE commit on the
merged tree:**

```
scripts/docs-freshness.sh --write
# then update java_test_methods / java_test_files / schema_version /
# total_logical_invocations in README.md, CLAUDE.md and AGENTS.md
scripts/check-doc-metrics.sh   # must return to PASS
```

**3. `products.ingredients_text` is `NOT NULL` (V1).** The first RED run failed one case on a
`DataIntegrityViolationException` rather than on the intended assertion. "No ingredients text" is
therefore the empty string, which is also the real shape a vendor who fills in nothing produces.

**4. A raw NUL byte was written into a Java source file.** Using a NUL as the dedup-key separator
put an actual `0x00` into `OrderAllergenSnapshot.java` (visible only under `cat -A` as `^@`), and
the Edit tool then could not match the line. Replaced with a named `KEY_SEPARATOR = "\0"` constant.
The separator choice itself is deliberate and the comment now states the correct reason: PostgreSQL
text cannot hold a NUL byte, so no product title can forge a colliding key — a printable separator
could not make that claim.

**5. Heavy Testcontainers contention.** Four sibling wave-2 agents share the Docker daemon, so the
full integration suite ran far slower than its recorded 911s baseline. No test outcome changed; only
wall clock.

## Known Stubs

None. Every field added is populated by real code on both write paths and read by a real mapper.
`OrderAllergenSnapshot.viewOf` has no placeholder branch: the `NOT_RECORDED` constant is a modelled
state with two test fixtures pinning it, not a hardcoded empty return feeding a UI. The UI that
consumes these fields is 31-14 and 31-15 by plan design, not a stub here.

## Threat Flags

None. No network endpoint, auth path or file access was introduced. The schema change is additive
columns on an existing FORCE-RLS table, inheriting its policies unchanged; no policy was created,
altered or dropped, and `RlsContractTest`'s schema walk is unaffected.

The plan's own register is covered: **T-31-10-01** (post-order edit) by arm (a); **T-31-10-02**
(fabricated history) by the no-backfill decision plus the two-fixture DTO assertion;
**T-31-10-03** (Envers) by arm (b); **T-31-10-04** (zero-row UPDATE) by having no backfill at all,
with arm (c′) proving the row-count discipline is live where an UPDATE does occur;
**T-31-10-05** (Article 9) — measured below, and stated precisely because the loose version of this
claim is false; **T-31-10-06** (contract drift) by regenerating the
OpenAPI snapshot rather than excluding the test; **T-31-10-07** (table drift) by the untouched-diff
limb with its vacuity control plus a full re-run of 31-04's parity gate.

### T-31-10-05 (D-01, Article 9) — measured, with the imprecise version corrected

The first form of this claim was going to be "no reference to `allergenRestrictions` anywhere in
this plan's changed files". **That is false**: `frontend/types/api.ts` is a changed file and it
carries the pre-existing `Customer.allergenRestrictions` field, which this plan neither added nor
reads. The true, narrower claims — each measured:

| Check | Result |
|---|---|
| `rg -uu -n 'allergenRestrictions\|allergen_restrictions'` over `core-java/.../order/`, `PublicStorefrontService.java`, `V63…sql` and the new test | **no output — zero hits** |
| **Positive control, same instrument and same files** — `rg -uu -c 'allergen'` | `PublicStorefrontService.java:10`, `OrderAllergenSnapshot.java:14` — live, so the zero is real absence |
| **Pattern control** — `rg -uu -l 'allergenRestrictions\|allergen_restrictions' core-java/src frontend mcp-server/src` | **20 files** — the pattern itself matches real code, so it is not a typo that could never fire |
| `git diff <base> HEAD -- frontend/types/api.ts \| grep -icE 'allergenRestrictions'` | **0** — no line this plan added or removed mentions it |

Structurally as well as textually: `OrderAllergenSnapshot.capture` takes
`(OrderItem, String, Integer, String)` and `viewOf` takes `Collection<OrderItem>`; neither has a
parameter a consumer allergen mask could enter through.

**A note on the instrument itself.** The first attempt at this check ran inside a `bash script.sh`
subprocess using `xargs rg` and returned **rc=127 with zero hits** — `rg` is a shell function with
no system binary behind it, so under `xargs` (and in a non-interactive subshell that never sourced
the profile) it dies silently and "not found" is indistinguishable from "clean". Both controls in
that script returned 0 and correctly condemned the run. The numbers above come from direct
invocations where the control fires.

## Cross-cutting quality dimensions

- **Web performance:** the one risk this plan carried was the list-DTO N+1, which was **measured**
  and avoided (deviation 2). No user-facing page markup changed here.
- **SEO:** **N/A** — no public/unauthenticated surface was built or reworked.
- **AI agent-readiness:** no new mutating endpoint, so no new Idempotency-Key contract is owed;
  errors are unchanged; the OpenAPI snapshot was regenerated so the published contract matches live
  responses (+41 lines, 0 deletions, purely additive). The MCP surface gains the capability with no
  code change on the detail path and, deliberately, not on the list path — recorded above.
- **Security:** no new trust boundary; the register is covered above.
- **Falsifiable evidence:** six break arms plus a pre-change arm, both directions recorded, one arm
  declared N/A explicitly and replaced with a stronger substitute rather than silently dropped.
- **Runtime parity:** **not applicable to this plan and deliberately not claimed.** This is a
  worktree with no rebuilt stack; the delivered-runtime half of the contract belongs to the phase
  owner after merge. V63 will apply on the next core-java start.

## User Setup Required

None — no external service configuration, no new dependency. V63 applies automatically on startup.

## Next Phase Readiness

**Ready to consume:**

- **31-15 (KDS, S4)** — read `allergenNames` and `allergenFlags` off `OrderDetail`, and
  `items[].allergenNames` for the per-item badge. The kitchen board already batch-fetches lines, so
  these arrive with no extra query. Handle **three** states, not two: null (render no banner, and do
  not imply allergen-free), empty (render nothing — UI-SPEC S4), non-empty (render the banner).
- **31-14 (checkout, S3)** — the type shapes above; `OrderAllergenFlag` is exported from
  `frontend/types/api.ts`. Note the panel is PRE-submit, so its data comes from the basket, not from
  an order that does not exist yet; these types are the shape to match.
- **31-13** — the conformance statement can now say the platform *records* the allergen set at the
  moment of order, and does not revise it afterwards. It still checks only emphasised runs against
  31-04's fixed synonym list, and still never anything about the person ordering.
- **31-18** — owns the prose reconciliation in Issues Encountered item 2 (`schema_version` 62→63).

**Explicitly NOT done here, by plan design:** no UI, no change to the `allergenWarnings` seam
(provably untouched), no read of `Customer.allergenRestrictions`, and no change to the order list
endpoints' fetch strategy.

## Self-Check: PASSED

Run after writing this summary. Real output:

```
=== files claimed created/modified ===
FOUND: core-java/src/main/resources/db/migration/V63__order_item_allergen_snapshot.sql
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderAllergenSnapshot.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/dto/OrderAllergenFlagDto.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/OrderAllergenSnapshotIntegrationTest.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderItem.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderService.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/dto/OrderItemDto.java
FOUND: core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
FOUND: frontend/types/api.ts
FOUND: docs/api/openapi-snapshot.json
FOUND: docs/metrics.json
FOUND: .planning/phases/31-consumer-safety-and-legal-floor/31-10-SUMMARY.md
=== commits claimed ===
FOUND: 07e96a82 test(31-10): failing tests for the order-item allergen snapshot
FOUND: 0217c839 feat(31-10): V63 order-line allergen snapshot, captured on both write paths
FOUND: ec64fae1 feat(31-10): expose the allergen aggregate and per-item masks on the order DTO
FOUND: a31a8951 chore(31-10): regenerate docs/metrics.json for V63 and the new test file
=== TDD gate order (test before feat) ===
07e96a82 test(31-10): failing tests for the order-item allergen snapshot
0217c839 feat(31-10): V63 order-line allergen snapshot, captured on both write paths
ec64fae1 feat(31-10): expose the allergen aggregate and per-item masks on the order DTO
=== files this plan must NOT have touched ===
UNTOUCHED: .planning/STATE.md
UNTOUCHED: .planning/ROADMAP.md
UNTOUCHED: README.md
UNTOUCHED: CLAUDE.md
UNTOUCHED: AGENTS.md
UNTOUCHED: core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
UNTOUCHED: core-java/src/main/resources/application.yml
=== vacuity control for the UNTOUCHED block ===
total files changed vs base: 14
=== deletions across the plan ===
deleted files: 0
=== worktree clean ===
uncommitted entries: 1
SELF-CHECK: PASSED
```

The `UNTOUCHED` lines carry a vacuity control (`total files changed vs base: 14`) because
`git diff --name-only <base> HEAD -- <path> | grep -c .` returns 0 both when a file is untouched
and when the diff instrument is broken. Fourteen is the correct count: 4 created, 10 modified. The
single uncommitted entry is this summary, committed immediately after the check ran.

The `gdpr/GdprService.java` and `application.yml` rows are the sibling-isolation check — 31-09 owns
those and neither was touched from this worktree.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
