---
phase: 37-real-world-operations-readiness
plan: 19
subsystem: database
tags: [flyway, postgres, rls, jsonb, opening-hours, envers, jackson, testcontainers]

requires:
  - phase: 37-15
    provides: "37-B gate passed; the branch and stack state 37-C/D build on"
provides:
  - "V80: shops.opening_schedule JSONB, hours_source VARCHAR(24) (CHECK lists STRUCTURED, MIGRATED_FROM_TEXT, NEEDS_REVIEW, LEGACY_ALWAYS_OPEN), orders_paused_until TIMESTAMPTZ, orders_paused_indefinitely BOOLEAN NOT NULL DEFAULT false, all four mirrored on shops_aud"
  - "ck_shops_legacy_always_open_no_schedule: a LEGACY_ALWAYS_OPEN shop cannot carry a schedule"
  - "Tenant-loop backfill classifying every existing shop (NULL/{} -> LEGACY_ALWAYS_OPEN, D-25; text -> MIGRATED_FROM_TEXT or NEEDS_REVIEW), with a per-tenant unclassified guard"
  - "uk.jtoye.core.shop.OpeningSchedule (immutable per-day windows, fromLegacy, fromJson/toJson, validationErrors) + TimeWindow (overnight(), STRICT HH:MM JSON)"
  - "uk.jtoye.core.shop.HoursSource enum; Shop maps openingSchedule, hoursSource, ordersPausedUntil, ordersPausedIndefinitely"
  - "OpeningScheduleFixtures: the one shared fixture list both parsers are held to"
affects: [37-20, 37-21, storefront, shop-availability-policy]

actuals:
  tokens: 20622
  tasks: 2
  commits: 5
plan_head_before: a75403cd0c67968a9ec903690dc087eb7831afee
plan_head_after: 20cd1e472412fe8ad8d91e26a6e2f719ab242a2f

tech-stack:
  added: []
  patterns:
    - "Java/SQL parity over one shared fixture list: the unit test checks the Java parser against the expectations, the stepwise IT checks the migrated rows against the expectations AND the Java parser"
    - "A JSON element type declares its own shape with the annotations Jackson 2 and 3 share (@JsonCreator/@JsonValue), so the stored shape does not depend on the mapper Hibernate picks"
    - "Stepwise migration test that applies the migration once, lazily, from inside the first test, so a missing migration is an assertion failure and not a fixture error"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V80__shop_opening_schedule_pause.sql
    - core-java/src/main/java/uk/jtoye/core/shop/HoursSource.java
    - core-java/src/main/java/uk/jtoye/core/shop/OpeningSchedule.java
    - core-java/src/test/java/uk/jtoye/core/shop/OpeningScheduleFixtures.java
    - core-java/src/test/java/uk/jtoye/core/shop/OpeningScheduleTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopOpeningScheduleMappingIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-19-red-legacy-always-open.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-19-red-parser-unit.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-19-red-parity-stepwise.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/Shop.java

key-decisions:
  - "hours_source is nullable with no default: NULL = not classified (a shop the application writes before its vendor saves a structured schedule); a default would claim a classification nobody made, and NOT NULL would break every existing shop write before 37-20 owns the write path"
  - "A day entry that contains a window plus text we do not understand ('12:00 - 14:00, 18:00 - 22:00', a trailing note) keeps the FIRST window (what both parsers render today) and marks the shop NEEDS_REVIEW, so a dropped second window is surfaced to the vendor instead of silently migrated"
  - "A key that is not mon..sun, and an array/object day value, mark the shop NEEDS_REVIEW (both parsers read such a shop as closed that day today; the flag surfaces it)"
  - "A close of 24:00 is midnight (00:00, overnight close); 00:00 - 24:00 is the whole day; an open of 24:00, out-of-range times and a zero-length window (09:00 - 09:00, never open under either parser) are not windows"
  - "The SQL pattern spells PublicStorefrontService.HOURS_PATTERN's classes out as [0-9] and [ \\t\\n\\r\\f\\v] (Java's are ASCII-only by default)"

patterns-established:
  - "Parity fixture list: OpeningScheduleFixtures is the single source both OpeningScheduleTest and the V80 IT read; a rule change in one parser reds the other's parity assertion"

requirements-completed: []

coverage:
  - id: D1
    description: "V80 adds the four columns and their shops_aud mirrors, the hours_source CHECK with every value, and the legacy-no-schedule CHECK"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java#V80 adds the four columns to shops AND shops_aud, and the CHECK lists every hours_source value"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every existing NULL/{}-hours shop becomes LEGACY_ALWAYS_OPEN with no schedule, in both tenants, under the RLS-bound migrator (D-25: no shop changes whether it takes orders at deploy)"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java#D-25: a shop with NULL or {} opening hours becomes LEGACY_ALWAYS_OPEN with no schedule, in both tenants"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java#every seeded shop in both tenants is classified: none is left NULL"
        status: pass
    human_judgment: false
  - id: D3
    description: "Text hours become structured windows, MIGRATED_FROM_TEXT or NEEDS_REVIEW, and the SQL classification equals the Java OpeningSchedule.fromLegacy reading on all 25 shared fixtures in both tenants, plus 6 database-only jsonb shapes"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java#V80 classifies each shared fixture as expected AND exactly as OpeningSchedule.fromLegacy does, in both tenants"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/V80OpeningScheduleBackfillIntegrationTest.java#V80 classifies jsonb the entity cannot load (not an object of strings) without inventing hours"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/shop/OpeningScheduleTest.java#fromLegacy classifies every shared fixture exactly as the V80 migration must"
        status: pass
    human_judgment: false
  - id: D4
    description: "OpeningSchedule value type for 37-20: overnight detection, toJson/fromJson round-trip, at most 3 windows a day, no overlap within a day or across midnight, whole-minute STRICT HH:MM windows"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/shop/OpeningScheduleTest.java (6 non-fixture methods)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The Shop entity maps the four columns; the schedule persists in the declared JSON shape, reloads as TimeWindows, and the Envers revision carries the mirrored columns; an unset shop stays unclassified and not paused"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/ShopOpeningScheduleMappingIntegrationTest.java#opening_schedule, hours_source and the pause fields persist, reload as TimeWindows, and are audited"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/shop/ShopOpeningScheduleMappingIntegrationTest.java#a shop saved without any of the new fields is unclassified and not paused (no default classification)"
        status: pass
    human_judgment: false

duration: 47min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 19: Structured opening hours and pause data model Summary

**V80 gives every shop a structured `opening_schedule`, an `hours_source` flag and pause columns. It migrates free-text hours in a tenant loop that works under FORCE RLS: empty-hours shops become LEGACY_ALWAYS_OPEN (D-25), and anything not understood becomes NEEDS_REVIEW. A Java `OpeningSchedule` parser is proven equal to the SQL on one shared list of 25 fixtures, in two tenants.**

## Performance

- **Duration:** 47 min
- **Started:** 2026-10-09T12:08:37Z
- **Completed:** 2026-10-09T12:55:57Z
- **Tasks:** 2 of 2
- **Files:** 11 code and test files (10 created, 1 modified) plus 3 RED evidence records

## Accomplishments

- **V80** (`V80__shop_opening_schedule_pause.sql`), in four steps:
  - Adds `opening_schedule` (JSONB), `hours_source` (VARCHAR(24)), `orders_paused_until` (TIMESTAMPTZ) and `orders_paused_indefinitely` (BOOLEAN NOT NULL DEFAULT false), each mirrored on `shops_aud`.
  - `ck_shops_hours_source` lists all four values now, so no later migration has to rewrite it (the V43 lesson).
  - `ck_shops_legacy_always_open_no_schedule` makes a legacy shop that carries a schedule unstorable.
  - `opening_hours` is kept, unchanged, so it can be dual-read for one release.
- **The backfill** walks `tenants` and sets the GUC (`set_config`) per tenant. Every statement is also filtered on the pinned tenant. It raises an error if any shop in a tenant is left unclassified, then resets the GUC and prints a count per class.
- **The classification rule** (written into the V80 header, into `OpeningSchedule.fromLegacy` Javadoc, and into the fixture class):
  - NULL or `{}` becomes LEGACY_ALWAYS_OPEN with no schedule.
  - Per day, a missing day, a JSON null, or `closed` (any case, spaces trimmed) becomes `[]`.
  - An entry that is exactly one window becomes that window. A close of 24:00 means midnight.
  - Anything else marks the shop NEEDS_REVIEW. That day keeps the first real window the storefront already shows, or `[]` if there is none.
  - Unknown keys, array or object values, and a jsonb that is not an object also mark the shop NEEDS_REVIEW.
- **`OpeningSchedule` and `TimeWindow`:** an immutable per-day value type with `fromLegacy`, `fromJson`/`toJson` and `validationErrors` (at most 3 windows a day; no overlap within a day, or from an overnight window into the next day's hours, Sunday into Monday included). `TimeWindow` has `overnight() = !close.isAfter(open)` and an explicit `{"open","close"}` HH:MM JSON shape with STRICT parsing.
- **Shop mapping and Envers:** proven by a separate Spring IT that reads the raw stored JSON, reloads the entity through JPA and reads the `shops_aud` revision.

## Task Commits

1. **Task 1 (tracer): empty-hours shops become legacy always-open in every tenant**
   - RED `5616a20c` (test): stepwise two-tenant test. V80 absent, RED_EVIDENCE_OK.
   - GREEN `36c5e26f` (feat): V80 columns, CHECKs and comments; the NULL/{} branch of the loop; the Shop fields; HoursSource; TimeWindow; the mapping IT.
   - ⚡ Tracer verified end-to-end, then expanded. The tracer `<verify>` was re-run on the committed tree after the break arms and gave V80 IT 2/2, RlsContractTest 7/7 and mapping IT 2/2.
2. **Task 2: text hours become structured windows, and Java parses exactly like the migration**
   - RED `16b94047` (test): shared fixtures, unit test, IT parity tests, and a compile stub that gives deliberately wrong answers. Unit 27/30 and IT 28/33 red, RED_EVIDENCE_OK for both.
   - GREEN `9a07f70c` (feat): the full SQL classification, the `OpeningSchedule` parser and value type, STRICT TimeWindow parsing, and the vertical-tab/form-feed fixture.
   - Follow-up `20cd1e47` (test): dropped the Jackson 2 round-trip that the project's Jackson-line gate refuses (see Deviations).

No REFACTOR commit was made; nothing needed cleanup after GREEN.

## TDD Gate Compliance

| Task | RED | GREEN | Evidence record | Classifier |
|------|-----|-------|-----------------|------------|
| 1 | `5616a20c` | `36c5e26f` | `evidence/37-19-red-legacy-always-open.json` | RED_EVIDENCE_OK |
| 2 (unit) | `16b94047` | `9a07f70c` | `evidence/37-19-red-parser-unit.json` | RED_EVIDENCE_OK |
| 2 (IT) | `16b94047` | `9a07f70c` | `evidence/37-19-red-parity-stepwise.json` | RED_EVIDENCE_OK |

How each RED was judged:

- **Task 1:** the target failed on `[V80__shop_opening_schedule_pause.sql must be on the classpath]`. The @BeforeAll fixture ran fully in that run (migrate to V79, provision the NOSUPERUSER migrator, seed two tenants). An earlier attempt failed on a fixture fault (`delivery_fee_pennies` is NOT NULL); it was rejected as INVALID and is not the RED. The first classifier run returned `no_target_test_failure` because the record named the method, while the JUnit XML names the test by its display name. The record was re-targeted to the display name, which gave RED_EVIDENCE_OK.
- **Task 2, unit:** 27 of 30 cases were red on their planned assertions, because the stub classifies every input as LEGACY. The red `a window is whole minutes` case found a real defect in the Task 1 code: `DateTimeFormatter` SMART resolution reads `24:00` as 00:00. GREEN switched to STRICT.
- **Task 2, IT:** 28 of 33 were red on `SQL hours_source expected MIGRATED_FROM_TEXT/NEEDS_REVIEW but was null`. The Task 1 tests stayed green in the same run, which shows the fixture was sound.
- **RED stub:** the Task 2 RED commit includes a compile stub in main source, `OpeningSchedule`'s API with deliberately wrong answers. Without it the RED would have been a compile error, which counts as INVALID_RED.

## Acceptance criteria and break arms (both directions recorded)

| Criterion | Fail direction (broken input) | Pass direction (real tree) |
|---|---|---|
| T1 bare-UPDATE arm red under the RLS-bound role | Loop replaced by `UPDATE shops SET hours_source='LEGACY_ALWAYS_OPEN' WHERE …` with no GUC. The migration *reported success* (columns test green, `migrationsExecuted=1`), then: `[tenant A (published), shop NULL hours: hours_source] expected "LEGACY_ALWAYS_OPEN" but was: null` (2 tests, 1 failure). | 2/2 green. Restored by sha256 (`7ce46f23…` OK). |
| T1 `rg -uu -n '\$\{' V80` prints nothing, rc 1 | A scratch copy with an appended `-- ${x}`: rc=0, prints the line (run against both the Task 1 and the final file). | Final V80: rc=1, empty. |
| T1 (added) `_aud` mirror is load-bearing | `shops_aud.hours_source` line deleted: the mapping IT went 2/2 red, `InvalidDataAccessResourceUsageException … insert into public.shops_aud (…hours_source…)`. | 2/2 green. Restored by sha256. |
| T1 (added) declared JSON shape is load-bearing | `TimeWindow.toJson` key changed to `opens`: the mapping IT red, `Could not deserialize string to java type: Map<String, List<TimeWindow>>`. This fails at flush, because Hibernate deserialises its own snapshot, so the raw-JSON assertion is never reached in this arm. | Green. Restored by sha256. |
| T2 Java accepts `HH.MM` while SQL does not, so parity reds | Java pattern `(\d{2})[:.](\d{2})…`. IT 34 tests, 1 failure: `[tenant A, HH.MM separators: SQL and Java hours_source agree] expected "MIGRATED_FROM_TEXT" but was "NEEDS_REVIEW"`. Unit 31, 1 failure on the same fixture's expectation. | IT 34/34, unit 31/31. Restored by sha256. |
| T2 (added) GUC-less loop on the final V80 | `PERFORM set_config(… t.id …)` removed. The migration *aborted*: `ERROR: V80: a shop of tenant … was left unclassified` (P0001), 34/34 red. Tenant A's published shops stay visible through `shops_public_read` while the UPDATE policy hides them, so the per-tenant guard sees them. This shape fails loudly; the T1 arm shows the silent shape. | 34/34 green. Restored by sha256. |
| T2 (added) are the explicit ASCII classes in the SQL load-bearing? | SQL pattern rewritten as `(\d{2}):(\d{2})\s*-\s*…` (PostgreSQL's own classes): **stayed green 34/34.** On the `postgres:15` locale, `\d`/`\s` already exclude the non-ASCII digits and the no-break space, so on this database the explicit classes are a defensive measure, not a proven necessity. The NBSP and non-ASCII-digit fixtures remain the parity guard on any locale where they differ. | n/a |
| T2 IT TEST xml ≥ 6 tests | n/a | 34 tests, 0 failures. |

Every arm ran clean → break → clean. Each restore was checked by content hash, and `git diff --quiet HEAD` was CLEAN after each one. The closing clean run is the tracer re-run (T1) and the final GREEN runs and full suites (T2).

## Plan-level verification

1. Both the stepwise backfill test and the OpeningSchedule parity test went RED then GREEN, with break arms (above).
2. RlsContractTest is 7/7 green in the Task 1 GREEN run, the tracer re-run and the Task 2 GREEN run. No policy changed.
3. Full core-java suites after the last commit (`20cd1e47`):
   - Unit: **1655 tests / 0 failures / 0 errors / 1 skipped**, against a baseline of 1624 (+31 OpeningScheduleTest).
   - Integration: **1054 / 0 / 0 / 1 skipped**, against a baseline of 1018 (+34 V80 IT, +2 mapping IT).
   - Both were fresh `clean*` runs, with BUILD SUCCESSFUL (integration took 25m 39s).
4. The V80 NOTICE on the two-tenant fixture database read `LEGACY_ALWAYS_OPEN=10, MIGRATED_FROM_TEXT=20, NEEDS_REVIEW=36`. That matches the fixture arithmetic: per tenant, 5 legacy, 10 migrated and 18 needing review, total 33.

## Decisions Made

See `key-decisions` in the frontmatter. In short:

- `hours_source` is nullable with no default.
- A window followed by text we don't understand keeps the first window and is flagged.
- Unknown keys and non-string values are flagged.
- 24:00 as a close means midnight.
- A zero-length window or an out-of-range time is not a window.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing verification] Added a JPA/Envers round-trip IT for the new Shop mapping**
- **Found during:** Task 1
- **Issue:** The plan's tests are pure JDBC. A Shop mapping that wrote LocalTime arrays, or that lost the List element type, would pass every planned test and break 37-20.
- **Fix:** `ShopOpeningScheduleMappingIntegrationTest` covers the raw stored JSON, the reload as TimeWindows, the Envers `shops_aud` row, and an unset shop staying NULL and not paused. TimeWindow declares its JSON shape with the annotations Jackson 2 and 3 share.
- **Verification:** 2/2 green. Two break arms (missing `_aud` mirror, renamed JSON key) were red.
- **Commit:** `36c5e26f`

**2. [Rule 2 - Correctness] NEEDS_REVIEW also covers a window followed by text we don't understand, unknown day keys, and non-string values**
- **Found during:** Task 2
- **Issue:** The plan reads "regex-match → one window". That would silently migrate `12:00 - 14:00, 18:00 - 22:00` as a clean single window, dropping the vendor's dinner service without telling them. D-15 requires that "any shop whose hours cannot be parsed is surfaced to its vendor".
- **Fix:** Such a day keeps the first window, which is what both parsers render today, so behaviour is unchanged, and the shop is marked NEEDS_REVIEW. Unknown keys and array or object values are flagged the same way.
- **Commit:** `9a07f70c`

**3. [Rule 1 - Bug] TimeWindow JSON parsing accepted 24:00**
- **Found during:** Task 2 RED
- **Issue:** SMART resolution read "24:00" as 00:00 in `TimeWindow.fromJson`.
- **Fix:** Switched to `ResolverStyle.STRICT`.
- **Commit:** `9a07f70c`

**4. [Rule 3 - Blocking] The Jackson-line gate refused the Jackson 2 round-trip in OpeningScheduleTest**
- **Found during:** the full unit suite
- **Issue:** `JacksonLineContractTest` keeps test files that use Jackson 2 to a closed list, and its message says the list "is not widened to make a test pass".
- **Fix:** Removed the Jackson 2 part. It was redundant: the mapping IT exercises the mapper Hibernate actually uses.
- **Commit:** `20cd1e47`

**5. [Scope addition] Extra fixtures and a constraint**
- Added 19 edge fixtures beyond the plan's five cases, plus 6 database-only shapes.
- Added `ck_shops_legacy_always_open_no_schedule`.

**Total deviations:** 4 auto-fixed (2 Rule 2, 1 Rule 1, 1 Rule 3) plus 1 scope addition. **Impact:** none of them changes any shop's behaviour at deploy; all of them tighten proof or surfacing.

## Issues Encountered

- A first RED run failed on a fixture fault (`delivery_fee_pennies` is NOT NULL with no default). It was rejected as INVALID_RED and the seeds were fixed.
- The classifier needs the JUnit display name as `targetTest`, not the method name.

## Runtime / stack

**The stack was not touched.** core-java was not rebuilt. The running image does not contain V80, and does not contain the quick task's customer gate either (WINDOWS #30). Nothing in this plan is user-visible until 37-20/37-21. The next plan that rebuilds core-java applies V80 to the dev database: the RAISE NOTICE then prints the real per-class counts, which are worth recording at that point.

## User Setup Required

None.

## Next Phase Readiness (for 37-20)

- **Inputs for 37-20:** `Shop.getOpeningSchedule()` is a `Map<String, List<TimeWindow>>`. Use `OpeningSchedule.fromJson(...)` to get the value type, and `validationErrors()` to validate a vendor save. `overnight()` follows the plan, so a window with close equal to open is read as a 24-hour window. Text migration never produces such a window, except from `00:00 - 24:00`.
- **Tension with D-25, for 37-20 and the owner to settle:** D-25's literal text says shops whose hours are "empty or unparseable" become legacy always-open. This plan, as written and checked, gives unparseable days `[]` and the NEEDS_REVIEW flag, which matches what the storefront renders today. 37-20's plan then closes such days on the server (they were fail-open). The migration does not lock in either reading: NEEDS_REVIEW is its own flag and the original text is kept. Honouring D-25 literally would be a one-line policy choice in 37-20 (treat NEEDS_REVIEW days as open).
- **RWO-022 is not marked complete.** `requirements.ready-ids` returned 0/1, because 37-20 also declares it.

## Self-Check: PASSED

- Files: all 10 created files and `Shop.java` exist (checked with `[ -f ]`).
- Commits: `5616a20c`, `36c5e26f`, `16b94047`, `9a07f70c`, `20cd1e47` are all ancestors of HEAD. `git rev-list --count a75403cd..HEAD` = 5.
- Acceptance criteria and plan verification were re-run as recorded above.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*
