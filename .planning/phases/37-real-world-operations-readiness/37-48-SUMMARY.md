---
phase: 37-real-world-operations-readiness
plan: 48
subsystem: api
tags: [shops, fulfilment, delivery-fee, collection-only, slug, v83, flyway, openapi, rfc7807, jest, testcontainers, uxt-023, uxt-028, rwo-023, rwo-028]
status: complete

requires:
  - phase: 37-47
    provides: "the 37-E gate: suites green on the current head, the OpenAPI snapshot procedure, V82/V84 applied"
  - phase: 37-20
    provides: "the V80 shops columns + shops_aud mirror shape, ShopDto NON_NULL additive-field rule, ShopService schedule writes"
  - phase: 37-21
    provides: "the shop dialog and the slug re-send workaround (removed here) with its jest cases"
provides:
  - "V83: shops.offers_delivery / offers_collection BOOLEAN NOT NULL DEFAULT true, ck_shops_offers_some, nullable shops_aud mirrors"
  - "FulfilmentPolicy.requireOffered(shop, type) on BOTH writers -> 422 https://jtoye.uk/errors/fulfilment-not-offered (code FULFILMENT_NOT_OFFERED, fulfilmentType, offered)"
  - "ShopDto + CreateShopRequest: deliveryFeePennies, freeDeliveryThresholdPennies, offersDelivery, offersCollection; PublicShopDto: offersDelivery, offersCollection"
  - "Slug-keep rule: an update without a slug keeps it; an explicit slug is shape-validated (400), reserved (422) and taken-checked (409 https://jtoye.uk/errors/shop-slug-taken, code SHOP_SLUG_TAKEN)"
  - "Shop dialog: the 37-21 workaround removed; UI-SPEC G2 web-address row with 'Change web address', slug sent only when edited, inline role=alert refusal"
affects: [37-49, 37-50, phase-37-uat, phase-39-slug-aliases, mcp-create-order]

actuals:
  tokens: 36585      # chars/4 over the ADDED lines of source, tests and docs (146,338 chars); excludes .planning (RED evidence JSON) and the regenerated OpenAPI snapshot. All added lines incl. those: 486,424 chars (~121,600)
  tasks: 3
  commits: 7         # MEASURED: git rev-list --count f665ac90..HEAD before the SUMMARY commit
plan_head_before: f665ac900a3cc2d297ce3cc05ed65186910abc89
plan_head_after: 34ce292931e48769733a3c5fcbb743c18ce1ca14

tech-stack:
  added: []
  patterns:
    - "A request field whose explicit null means something (clear) records key presence in its setter (@JsonIgnore flag); the IGNORE-null mapper keeps absent = keep"
    - "A business rule that spans two partial-update fields is checked on the MERGED entity state in the service, not only on the request"
    - "A uniqueness pre-check query runs AFTER restoring the managed entity's field: Hibernate auto-flushes before a query, so a mapper-copied value hits the unique index inside the check"
    - "A unique index the pre-check cannot see under RLS (another tenant's unpublished row) is translated at saveAndFlush by index name; other violations propagate unchanged"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V83__shop_fulfilment_offered.sql
    - core-java/src/main/java/uk/jtoye/core/exception/FulfilmentNotOfferedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/ShopSlugTakenException.java
    - core-java/src/test/java/uk/jtoye/core/order/FulfilmentOfferedIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task1b.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task3.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task3b.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-48-red-task3c.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/Shop.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/ShopDto.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java
    - core-java/src/main/java/uk/jtoye/core/order/FulfilmentPolicy.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/test/java/uk/jtoye/core/order/FulfilmentPolicyTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java
    - frontend/app/dashboard/shops/page.tsx
    - frontend/components/dashboard/shops/__tests__/opening-schedule-editor.test.tsx
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "37-48: requireOffered reads a null flag as OFFERED (the V83 default), never as a refusal; the refusal names the offered types as a typed member"
  - "37-48: the vendor path's COLLECTION fallback is checked too (after resolve), so a delivery-only shop refuses a vendor/API/MCP order that sends no type, 422 naming DELIVERY"
  - "37-48: an explicit freeDeliveryThresholdPennies null clears the threshold; an absent key keeps it (BE-02 partial update). 37-49's emptied form field must send null"
  - "37-48: neither type offered is a 400 errors.fulfilmentOffered on the request (both false) AND on the merged state (one false over a stored false); ck_shops_offers_some is the last line"
  - "37-48: a taken slug is a typed 409 on update AND create; the unique-index backstop covers a slug the RLS-scoped pre-check cannot see"
  - "37-48: the slug @Pattern still accepts blank (keep on update, generate on create) so existing clients sending an empty slug do not start failing"
  - "37-48: item (c) was built to UI-SPEC G2 (Web address row + Change web address), so 37-49 Task 2 verifies and extends it rather than building a second control"

patterns-established:
  - "Presence-tracking setter for clearable nullable fields on a partial-update request"
  - "Restore the managed entity field before a uniqueness query (auto-flush)"

requirements-completed: [RWO-023, RWO-028]

coverage:
  - id: D1
    description: "A collection-only (or delivery-only) shop refuses the unoffered type on the storefront AND the vendor/REST/MCP writer with 422 fulfilment-not-offered, writes nothing and rolls the idempotency reservation back"
    requirement: RWO-023
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/FulfilmentOfferedIntegrationTest.java (Task 1 arms, 5/0; vendor-path break arm reds the 2 vendor arms)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/order/FulfilmentPolicyTest.java (14/0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Vendors set the delivery fee, free-delivery threshold and offered types through the shop API; the storefront charges them (2500 -> 2799, 3000 -> 3000 by SQL) and exposes offersDelivery/offersCollection; neither offered and negative values are 400"
    requirement: RWO-023
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/FulfilmentOfferedIntegrationTest.java (11/0; explicit-null and merged-check break arms red)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Editing a shop keeps its slug; an explicit slug is validated (400 shape, 422 reserved, 409 shop-slug-taken) and applied only when it differs"
    requirement: RWO-028
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java (12/0)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java (24/0, the idx_shops_slug backstop arms)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The shop dialog sends no slug on an ordinary save; the G2 web-address row reveals a slugified input with the amber warning and shows a refused address inline"
    requirement: RWO-028
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/shops/__tests__/opening-schedule-editor.test.tsx (30/30)"
        status: pass
    human_judgment: true
    rationale: "jest proves the request bodies and the DOM; the visual fit of the web-address row at 375/768/1280 (UI-SPEC G2 overflow assumption) and the live shop edit are owed at 37-49/37-50"
  - id: D5
    description: "OpenAPI snapshot regenerated for the new fields; full suites green on the head"
    verification:
      - kind: integration
        ref: "./gradlew :core-java:cleanTest :core-java:test :core-java:cleanIntegrationTest :core-java:integrationTest (unit 1827/0, integration 1242/0, OpenApiSnapshotTest 1/0)"
        status: pass
      - kind: unit
        ref: "npx jest --ci (2801 passed, 240 suites); tsc --noEmit rc 0; npm run lint 0 errors"
        status: pass
    human_judgment: false

duration: 51min
completed: 2026-10-14
---

# Phase 37 Plan 48: Fees, offered fulfilment types and stable slugs Summary

**V83 lets a shop declare `offers_delivery` / `offers_collection`, and `FulfilmentPolicy.requireOffered` refuses an unoffered type on both order writers with a typed 422 `fulfilment-not-offered`. Vendors now set the delivery fee, the free-delivery threshold and the offered types through the shop API, and the storefront charges them: a 2500 basket pays 2799 and a 3000 basket pays 3000, read by SQL. A shop edit no longer moves its web address. Before this plan an ordinary save changed `keep-address-kitchen-019835a3` to `-3f4afb31`; now the slug changes only when the vendor sends one on purpose, and a taken slug is a typed 409 `shop-slug-taken`.**

## Performance

- **Duration:** 51 min
- **Started:** 2026-10-14T10:42:51Z
- **Completed:** 2026-10-14T11:34:03Z
- **Tasks:** 3 (all TDD; Task 1 is the tracer)
- **Files modified:** 32 (16 source and test files, 6 RED evidence files, the snapshot, metrics, 3 prose docs, deferred-items, WINDOWS)

## Accomplishments
- **UXT-023, offered types (Task 1, tracer).** V83 adds the two flags with DEFAULT true. Every shop already took both types, so the default makes no new claim. `ck_shops_offers_some` refuses a shop that offers neither, and `shops_aud` gets the mirrors. `requireOffered` runs right after `resolve` on the storefront path, inside the reserved idempotency work, so the reservation rolls back. It also runs on `OrderService.createOrder`, so the vendor path's COLLECTION fallback is checked too.
- **UXT-023, the shop API (Task 2).** Four fields were added to `CreateShopRequest` and `ShopDto`. The `ShopDto` fields are `NON_NULL`, so the golden cache bytes stay the same. Fees and thresholds are `@Min(0)`. A new shop defaults to fee 0 and both types. An explicit `null` threshold clears it; an absent key keeps it. "Neither offered" is a 400 `errors.fulfilmentOffered`, whether both flags are sent false or one false lands on a stored false. `PublicShopDto` exposes the flags.
- **UXT-028, stable slugs (Task 3).** The plan's items (a) to (e) are all done:
  - (a) The wrong "if name changed" comment is replaced. The code regenerated the slug on every save.
  - (b) The 37-21 dialog workaround and its three jest cases are removed.
  - (c) The UI-SPEC G2 web-address control is in the edit dialog.
  - (d) `existsBySlugAndIdNot` and `ShopSlugTakenException` give a 409 on update and create, with an `idx_shops_slug` backstop.
  - (e) The slug has `@Size(max = 100)` and a `@Pattern` that still accepts a blank slug.
  - Old-slug 301 aliases stay deferred to Phase 39.
- **Full suites green on the head:**
  - unit 1827/0 and integration 1242/0 (1 skipped each)
  - jest 2801/240, `tsc --noEmit` rc 0, lint 0 errors (29 old warnings, none in these files)
  - `docs-freshness` and `check-doc-metrics` rc 0 (both rc 1 before the update)
  - static gates rc 0: no-create-extension (84 migrations), doc-citations (46, with PyYAML), claims, edge-core-contract, handoff-contract, gate-enforcement, doc-versions, ssr-coverage, layout-width, object-store-residue, test-block-counter

## Task Commits

1. **Task 1: A collection-only shop refuses delivery orders on every writer (tracer)**
   - `e6f7f455` (test, RED): IT 5 tests, 3 failed (201 where 422 was expected); unit 14, 2 failed
   - `1434b557` (feat, GREEN): IT 5/0, FulfilmentPolicyTest 14/0
   - The tracer gate (end-of-phase mode, automated verify) was re-run: green. The plan's break arm followed (below).
2. **Task 2: Vendors set fees, threshold and offered types through the shop API**
   - `dce0914a` (test, RED): 11 tests, 6 failed; the shop API ignored all four fields
   - `dff76a80` (feat, GREEN): 11/0
3. **Task 3: Editing a shop keeps its web address**
   - `b4db4994` (test, RED): IT 12 tests, 4 failed; unit 24, 1 failed; jest 30, 5 failed
   - `38a23238` (feat, GREEN): IT 12/0 + OpenApiSnapshotTest 1/0, unit 24/0, jest 30/30
4. **Counts and the migration list:** `34ce2929` (docs). Totals moved from 5918 to 5945. V83 is shipped, so there are now 84 migrations.

**Plan metadata:** the commit containing this SUMMARY (docs).

## TDD Gate Compliance

Each task has a `test(37-48)` RED commit before its `feat(37-48)` GREEN commit. No REFACTOR commit was needed. Every RED record passed `gsd_run check tdd-red-evidence` with `RED_EVIDENCE_OK` / `target_test_failed`. Each record also passed `gitleaks dir … -c .gitleaks.toml` with rc 0. A positive control showed gitleaks can fire: rc 1 on a high-entropy `secret_token`.

| Record | Target | Semantic assessment |
|---|---|---|
| `37-48-red-task1.json` | vendor DELIVERY to a collection-only shop | 201 instead of 422: the UXT-023 defect. The 2 control arms passed, so the fixture and V83 work |
| `37-48-red-task1b.json` | `requireOffered` refuses DELIVERY | nothing thrown (the no-op skeleton); the accept arms passed |
| `37-48-red-task2.json` | PUT fee 299 / threshold 3000 | 200, but the fields were absent from the DTO and stored as `[0, NULL]` |
| `37-48-red-task3.json` | update without a slug keeps it | slug changed from `keep-address-kitchen-019835a3` to `-3f4afb31` |
| `37-48-red-task3b.json` | `idx_shops_slug` violation becomes `ShopSlugTakenException` | the `DataIntegrityViolationException` propagated |
| `37-48-red-task3c.json` | hours save sends no slug (TAP) | body carried `slug: s-structured` (the 37-21 workaround) |

## Fail directions (break arms, each restored by `cp` and verified by sha256)

| Arm | Break | Result | Restore |
|---|---|---|---|
| Task 1 (plan acceptance) | `requireOffered` removed from `OrderService` only | rc 1. Exactly the 2 vendor arms failed; the storefront arm stayed green | `3491aefc…` both sides; `git diff --quiet` clean |
| Task 2 | explicit-null clear and merged check removed from `updateShop` | rc 1. Exactly 2 arms failed: "explicit null clears" read `[299, 3000]`, and the merged arm got **409 "Duplicate Entry"** from `ck_shops_offers_some` | `324033a5…` both sides; clean |
| Task 3 GREEN (found, not planned) | first GREEN without restoring the slug before the taken query | the taken arm still read `errors/duplicate`: the query auto-flushed the slug the mapper had copied | fixed in `38a23238` (Deviation 2) |
| tsc | `const editedSlug: number = …` in `page.tsx` | rc 2 (TS2322, TS2367) | `2846d53d…` both sides |
| docs gates | the tree before `--write` | `docs-freshness` rc 1; `check-doc-metrics` rc 1 (12 FAILs) | rc 0 / rc 0 after |

## Files Created/Modified
- `V83__shop_fulfilment_offered.sql`: the two flags, the CHECK, comments and the `_aud` mirrors. No UPDATE and no tenant loop.
- `FulfilmentPolicy.java`: `requireOffered`, where a null flag counts as offered.
- `OrderService.java` and `PublicStorefrontService.java`: the call on both writers. `toPublicShopDto` maps the flags.
- `FulfilmentNotOfferedException.java` and `ShopSlugTakenException.java`, with their `GlobalExceptionHandler` mappings: 422 and 409, each with a code and typed members.
- `Shop.java`, `ShopDto.java`, `CreateShopRequest.java`, `PublicShopDto.java`: the new fields, validation and the presence flag.
- `ShopService.java`: create defaults, the explicit-null clear, the merged offered check, the slug-keep rule, the taken check and the unique-index translation.
- `ShopRepository.java`: `existsBySlugAndIdNot` and `existsBySlug`.
- `frontend/app/dashboard/shops/page.tsx`: workaround removed; the G2 web-address row, `slugifyAsTyped`/`finalSlug`, and the inline refusal.
- Tests: `FulfilmentOfferedIntegrationTest` (new, 11), `FulfilmentPolicyTest` (+4), `ShopControllerIntegrationTest` (+6), `ShopServiceTest` (+2), `opening-schedule-editor.test.tsx` (-3, +7).
- `docs/api/openapi-snapshot.json`: the new fields are additive; the request `slug` gains `pattern` and `maxLength`.
- `docs/metrics.json`, `README.md`, `CLAUDE.md` and `AGENTS.md`: counts updated, and V83 described as shipped.

## Decisions Made
See `key-decisions` in the frontmatter. The two that matter most downstream:
- **An explicit null clears the threshold.** 37-49's emptied "Free delivery over" field must send `null`, not leave the key out.
- **The G2 control already exists.** 37-49 Task 2 extends it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The RED commits carry V83 and compile-only skeletons**
- **Found during:** Task 1 RED (and Task 3 RED)
- **Issue:** The IT fixtures set `offers_delivery` / `offers_collection`, and the unit tests call `requireOffered` and read the exception getters. Without the column, RED would be a fixture failure; without the method, a compile failure. Both count as INVALID_RED.
- **Fix:** The Task 1 RED commit includes V83, the `Shop` fields, `FulfilmentNotOfferedException` and a no-op `requireOffered`. The Task 3 RED commit includes the `ShopSlugTakenException` data class. No handler and no call site was added before GREEN, and every RED failed on its planned assertion.
- **Commits:** e6f7f455, b4db4994

**2. [Rule 1 - Bug] The taken-slug check tripped the unique index inside its own query**
- **Found during:** Task 3 GREEN
- **Issue:** The mapper had already copied the requested slug onto the managed shop. Hibernate auto-flushes before `existsBySlugAndIdNot`, so the update hit `idx_shops_slug` inside the check and surfaced as the generic `duplicate` (measured: IT 12 tests, 1 failed).
- **Fix:** The kept slug is put back on the entity before the query; the requested slug is applied only after the checks pass.
- **Files modified:** ShopService.java
- **Verification:** ShopControllerIntegrationTest 12/0
- **Committed in:** 38a23238

**3. [Rule 2 - Missing critical] "Neither offered" is also checked on the merged state**
- **Found during:** Task 2
- **Issue:** The plan named a request-level cross-field rule. With a partial update, one false sent to a shop whose other flag is false reaches `ck_shops_offers_some`, which surfaced as a misleading 409 "Duplicate Entry" (measured with the check removed).
- **Fix:** `ShopService.requireSomeFulfilmentOffered` runs on create and update and gives a typed 400 under the same `fulfilmentOffered` key.
- **Committed in:** dff76a80

**4. [Rule 2 - Missing critical] An explicit null clears the free-delivery threshold**
- **Found during:** Task 2
- **Issue:** The IGNORE-null mapper (BE-02) meant a stored threshold could never be removed, which contradicts "an absent threshold means no free-delivery threshold" for a vendor who stops offering free delivery.
- **Fix:** The setter records key presence (`@JsonIgnore` flag). An explicit `null` clears the threshold; an absent key keeps it. Both directions are pinned by an IT arm, and its break arm is recorded.
- **Committed in:** dff76a80

**5. [Rule 2 - Missing critical] Taken-slug coverage beyond the plan's single repository method**
- **Found during:** Task 3
- **Issue:** `existsBySlugAndIdNot` is RLS-scoped. It cannot see another tenant's unpublished shop, so that collision would still be the generic `duplicate`. An explicit taken slug on create had no typed refusal either.
- **Fix:**
  - `existsBySlug` on create;
  - `saveTranslatingSlugConflict` translates only an `idx_shops_slug` violation, and other violations propagate unchanged (unit arm and its control in `ShopServiceTest`, which is not in the plan's file list).
- **Committed in:** b4db4994 (tests), 38a23238

**6. [Rule 3 - Blocking] Doc metrics and migration prose**
- **Found during:** verification
- **Issue:** `docs-freshness` rc 1 (2777 to 2800 Java, 2797 to 2801 jest), and `check-doc-metrics` gave 12 FAILs. CLAUDE.md and AGENTS.md still said "V83 reserved".
- **Fix:** Regenerated with `--write`, updated the prose counts, and described V83 as shipped (84 migrations).
- **Committed in:** 34ce2929

**7. [Scope note] Item (c) was built to UI-SPEC G2**
- The plan asked for an "optional Storefront address field". It was built as the UI-SPEC G2 contract: a "Web address" row, a "Change web address" link and a "New web address" input. That keeps it from being built twice in two shapes, because 37-49 Task 2 specifies the same control.
- The slug `@Pattern` deliberately accepts blank/whitespace, so a client sending `""` keeps working: it keeps the slug on update and generates one on create.

---

**Total deviations:** 6 auto-fixed (1 bug, 3 missing-critical, 2 blocking) plus 1 scope note.
**Impact on plan:** All were needed for correct behaviour on the paths the plan names. There was no new table, no new dependency and no RLS change.

## Issues Encountered
- RED evidence for jest needed a TAP reporter. A minimal one was written in the scratchpad, as 37-46 did. The `jest-junit` module is not installed.
- The `scripts/check-doc-citations.sh` YAML half is VOID in a plain shell (no PyYAML). It was run under `conda run -n engineering-doctrine`: PASS, 46 citations.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: info-disclosure (low, pre-existing) | core-java/src/main/java/uk/jtoye/core/shop/ShopService.java | A 409 `shop-slug-taken` confirms that a slug exists, including another tenant's UNPUBLISHED shop through the `idx_shops_slug` backstop. This is no wider than before: the same collision was already a 409 `duplicate`. Slugs are global public identifiers. |

T-37-115 is mitigated: `requireOffered` runs on both writers, and the vendor-path break arm is recorded. T-37-116 is mitigated: `@Min(0)` gives a typed 400 (IT arm). T-37-SC: no package was installed.

## Known Stubs
None. Removing the no-op `requireOffered` skeleton was verified: `rg -uu 'RED skeleton|BREAK ARM'` over core-java/src and the frontend app/components finds only an unrelated pre-existing test comment. The positive control is the RED commit's copy, which has 2 hits.

## User Setup Required
None.

## Next Phase Readiness
- **37-49 can start.** The API surface it consumes exists:
  - `ShopDto` / `CreateShopRequest` fee, threshold, `offersDelivery` and `offersCollection`;
  - `PublicShopDto` `offersDelivery` / `offersCollection` (always present, booleans);
  - 422 `fulfilment-not-offered` with `fulfilmentType` and `offered[]`, and detail "This shop doesn't offer delivery. Choose collection to continue.";
  - 409 `shop-slug-taken` with `slug`.
  - The edit dialog's G2 web-address control already exists in `page.tsx`. 37-49 Task 2 should verify it and add the create-dialog preview and the 375px overflow check.
  - The emptied threshold field must send `null`.
  - `frontend/types/api.ts` and `types/storefront.ts` were NOT changed here.
- **37-50 owes the live proof** (WINDOWS #44): rebuild ALL images; V83 applies out of order after V84; the fee, the collection-only refusal on both writers and the slug kept on edit, live.
- **The MCP `create_order` tool** now gets a 422 `fulfilment-not-offered` from a shop that does not offer the type. It needs no code change, because the problem passes through as typed.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-14*

## Self-Check: PASSED

All 5 key created files exist; all 8 commits (e6f7f455, 1434b557, dce0914a, dff76a80, b4db4994, 38a23238, 34ce2929 and the SUMMARY commit) are ancestors of HEAD; 3 test(37-48) and 3 feat(37-48) commits, each RED before its GREEN; gitleaks over f665ac90..HEAD rc 0.
