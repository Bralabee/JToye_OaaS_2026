---
phase: 37-real-world-operations-readiness
plan: 39
subsystem: api
tags: [products, stock-tracking, partial-update, patch, mapstruct, jackson, rfc7807, openapi, integrators, uxt-016, rwo-016]
status: complete

requires:
  - phase: 37-38
    provides: "the 37-C gate; origin/main merged back, so 37-E starts on a current tree"
  - phase: QA-council 20260829 (API-1)
    provides: "ProductMapper.updateEntity bean-level IGNORE, with quantityInStock as the one SET_TO_NULL exception this plan removes"
provides:
  - "CreateProductRequest.trackInventory: null keeps stock and tracking, false stops tracking and clears the count, true requires quantityInStock"
  - "quantityInStock mapped IGNORE-null like every other field on PUT"
  - "PATCH /api/v1/products/{id} (ProductPatchRequest: available, quantityInStock, trackInventory), SCOPE_catalog:write + SHOP_MANAGER on the product's shop, any other property refused by name"
  - "InvalidRequestFieldsException: a reusable typed 400 (errors/validation, errors keyed by field) for rules bean validation cannot express"
  - "The dashboard product form sends trackInventory on every create and update"
affects: [37-40, 37-41, 37-47, mcp-server, 37.x-stock-tool, phase-37-uat]

actuals:
  tokens: 15600
  tasks: 3
  commits: 8
plan_head_before: 4e9761532b5d29dca8913b3cab2d5b5b283e1ef6
plan_head_after: f97107b2c0d73f7ecd1983c214634aa2f28e0f52

tech-stack:
  added: []
  patterns:
    - "A narrow PATCH body refuses unknown properties with a @JsonAnySetter capture plus a service-side typed 400 naming each one; @JsonIgnoreProperties(ignoreUnknown = false) is the Jackson default and does not override Spring Boot's app-wide FAIL_ON_UNKNOWN_PROPERTIES=false (measured, break arm B2)"
    - "A stated intent field (trackInventory) instead of an explicit null, so absent and null can both mean keep on a partial update"
    - "PATCH sets entity fields one by one and never routes through the PUT mapper, so a body field cannot reach allergen data"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/product/dto/ProductPatchRequest.java
    - core-java/src/main/java/uk/jtoye/core/exception/InvalidRequestFieldsException.java
    - frontend/app/dashboard/products/__tests__/product-track-inventory.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-39-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-39-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-39-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductController.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductPartialUpdateIntegrationTest.java
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/products/__tests__/product-vat-rate.test.tsx
    - frontend/types/api.ts
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-39: trackInventory false WITH a quantityInStock is refused (typed 400 naming quantityInStock) rather than silently dropping the quantity, on create, PUT and PATCH alike"
  - "37-39: unknown PATCH properties are refused through a @JsonAnySetter capture, not @JsonIgnoreProperties(ignoreUnknown = false): measured, the annotation alone answered 200 and silently ignored allergenMask under Boot's app-wide setting"
  - "37-39: PATCH checks the shop gate first, then the body (unknown properties, the trackInventory rule), so a manager of another shop always gets the 403 whatever the body says; nothing is applied unless every check passes"
  - "37-39: no MCP stock tool in this plan (integrators use REST PATCH); a stock/availability MCP tool is a 37.x candidate, not part of UXT-016"

patterns-established:
  - "Field-keyed typed 400 for cross-field and unknown-property rules: throw InvalidRequestFieldsException(Map field -> message); the handler emits the bean-validation shape"

requirements-completed: [RWO-016]

coverage:
  - id: D1
    description: "A PUT that does not mention stock keeps tracking and the count; trackInventory false clears; true needs a quantity; false with a quantity is refused; a quantity with no trackInventory still sets the count"
    requirement: "RWO-016"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductPartialUpdateIntegrationTest.java#putWithoutStockFieldsKeepsTrackingAndCount (and the five other PUT arms)"
        status: pass
    human_judgment: false
  - id: D2
    description: "PATCH /api/v1/products/{id} changes only available, quantityInStock and trackInventory; allergen mask, spans, may-contain mask and ingredients are unchanged by SQL; any other property is a 400 naming it; another shop's manager and a token without catalog:write get 403; repeat PATCH is idempotent"
    requirement: "RWO-016"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductPartialUpdateIntegrationTest.java#patchAvailabilityLeavesAllergenDataUnchanged (and the six other PATCH arms)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The dashboard product form sends trackInventory false + null when unticked and true + the quantity when ticked, on create and update; the form markup is unchanged"
    requirement: "RWO-016"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/products/__tests__/product-track-inventory.test.tsx (6 tests)"
        status: pass
      - kind: unit
        ref: "frontend/app/dashboard/products/__tests__/product-vat-rate.test.tsx#turning stock tracking off sends trackInventory false and quantityInStock null"
        status: pass
    human_judgment: false
  - id: D4
    description: "The committed OpenAPI contract carries the PATCH operation, ProductPatchRequest (additionalProperties false) and CreateProductRequest.trackInventory"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java#apiDocsMatchCommittedSnapshot"
        status: pass
    human_judgment: false
  - id: D5
    description: "Test-count manifest and prose counts move with the new arms (5742 -> 5760)"
    verification:
      - kind: other
        ref: "scripts/docs-freshness.sh and scripts/check-doc-metrics.sh (rc 1 before, rc 0 after)"
        status: pass
    human_judgment: false

duration: 51min
completed: 2026-10-12
---

# Phase 37 Plan 39: Explicit stock-tracking semantics and a narrow PATCH Summary

**A PUT that does not mention stock no longer turns stock tracking off: `CreateProductRequest.trackInventory` states the intent (null keeps, false clears, true needs a quantity), `quantityInStock` is IGNORE-null like every other field, and `PATCH /api/v1/products/{id}` lets an EPOS change availability and stock with every other property refused by name, so it cannot reach allergen data. The dashboard form sends `trackInventory` in the same change.**

## Performance

- **Duration:** 51 min
- **Started:** 2026-10-12T22:08:30Z
- **Completed:** 2026-10-12T23:00:13Z
- **Tasks:** 3 (3 TDD cycles: RED then GREEN each; Task 1 a tracer)
- **Files modified:** 20 (17 code/docs, 3 evidence records)

## Accomplishments

- **UXT-016's repro fails before and passes after.** A product tracking 12, PUT with only the required fields and a new title: before, HTTP 200 and `quantity_in_stock` NULL by SQL (tracking off); after, 12.
- **`trackInventory` on create and PUT.** `ProductService.validateStockTracking` runs before any mapping, so a refusal changes nothing: true without a quantity, or false with one, is a 400 `errors/validation` with `errors.quantityInStock`. `applyStockTracking` clears the count after the mapper when the value is false. `ProductMapper.updateEntity` lost its `SET_TO_NULL` override, and the comment above it now states the new rule.
- **`PATCH /api/v1/products/{id}`.** The body is `ProductPatchRequest`: `available`, `quantityInStock` (`@Min 0`) and `trackInventory`. It is gated by `SCOPE_catalog:write` and SHOP_MANAGER on the product's own shop, the same gate as PUT. Any other property is captured by a `@JsonAnySetter` and refused as a 400 naming it, before anything is applied. Fields are set one by one; the PUT mapper is never called and the spans are not re-parsed. Values are absolute, so repeating a PATCH is idempotent. The endpoint stays outside the Idempotency-Key adopters, so the fingerprint golden is untouched. All of 200/400/401/403/404/409 are documented, and the snapshot shows `additionalProperties: false`.
- **The dashboard form** sends `trackInventory` with every create and update. The markup diff is empty; only the payload changed.
- **Test counts:** `ProductPartialUpdateIntegrationTest` 5 → 17 arms, plus the new 6-test jest file; manifest and prose 5742 → 5760.

## Task Commits

1. **Task 1 (tracer): A PUT without stock keeps tracking on.** `f3080ee7` (test, RED), `9ce3da21` (feat, GREEN). The tracer gate re-ran verify on the committed tree: 10/0.
2. **Task 2: A stock/availability PATCH that cannot touch allergens.** `01d51270` (test, RED), `59dd3b82` (feat, GREEN + OpenAPI).
3. **Task 3: The dashboard form says what it means.** `1e7254ac` (test, RED), `6ddee703` (feat, GREEN), `e82c3f72` (test: the older stock-off arm tightened, see Deviation 5).
4. **Metrics:** `f97107b2` (docs: 5742 → 5760).

**Plan metadata:** this SUMMARY commit.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction evidence |
|------|------------------------|-------|-------------------------|
| 1 | `f3080ee7`, `evidence/37-39-red-task1.json` RED_EVIDENCE_OK (JUnit: 10 tests, 3 failed) | `9ce3da21`: 10/0 | the pre-fix tree itself (title-only PUT: 200 and NULL by SQL) |
| 2 | `01d51270`, `evidence/37-39-red-task2.json` RED_EVIDENCE_OK (17 tests, 7 failed on 405) | `59dd3b82`: 17/0, OpenAPI 1/0 | break arms B1, B2 and B3 below |
| 3 | `1e7254ac`, `evidence/37-39-red-task3.json` RED_EVIDENCE_OK (TAP: 6 tests, 5 failed) | `6ddee703`: 98/98 in the folder | fail arm F1 below |

**RED semantic assessments.**
- **Task 1.** The target arm failed at its planned assertion (`stockOf` expected 12, actual null), which is the defect itself. The two typed-400 arms got 200. The seven preservation and legacy arms passed, so the harness can pass.
- **Task 2.** All seven PATCH arms failed on their first status assertion: 405, because no PATCH route existed. That is the intended reason, not a setup fault. The same run's OpenAPI red is Task 1's schema delta; I diffed it and found nothing else.
- **Task 3.** The suite loaded, and every submit arm failed on `body.trackInventory` (received `undefined`).

## Verification (both directions)

| Check | Fail direction (real output) | Pass direction (real output) |
|-------|------------------------------|------------------------------|
| UXT-016 PUT (Task 1 verify) | pre-fix tree: 10 tests, 3 failed; title-only PUT 200 and NULL; true-without-qty and false+qty both 200 | 10/0, fresh XML (cleanIntegrationTest forced; the task executed, not UP-TO-DATE) |
| **B1** (plan's arm): PATCH bound to `CreateProductRequest` without `@Valid` and routed through `updateProduct`/`updateEntity` | 17 tests, 3 failed. `{allergenMask:0}` gave 200 and the declared mask became **0**. available + ingredientsText + mayContainMask gave 200, available false, spans re-parsed to `[]`. `quantityInStock:-1` was accepted. The `{available:false}` arm stayed green: IGNORE alone preserves *omitted* allergen fields, so only the refusal protects against *sent* ones | restored by sha256 (3/3 OK) |
| **B2** (the plan's "confirm the global setting does not override it"): `@JsonIgnoreProperties(ignoreUnknown = false)` with the any-setter removed | 17 tests, 2 failed: `{allergenMask:0}` and the mixed body answered **200** with the property silently ignored. Under Boot's app-wide setting the annotation does not refuse anything | restored by sha256 |
| **B3**: shop gate removed from `patchProduct` | 17 tests, 1 failed: a manager of shop B patched shop A's product, 200 | restored by sha256 |
| closing clean run after the arms | n/a | 17/0, OpenApiSnapshotTest 1/0 |
| OpenApiSnapshotTest | red during Task 2 RED (Task 1's `trackInventory` delta, diffed) | 1/0 after `updateOpenApiSnapshot` |
| **F1** (plan's arm): form omits `trackInventory` | track-inventory file: 5 failed; old vat-rate stock-off arm: still passed (see Deviation 5); after tightening: 1 failed | restored by sha256; 6/6 and 7/7 |
| frontend tsc (full program, tests included) | `trackInventory?: number` gives rc 2 with `TS2322 page.tsx(501,9)` | rc 0 |
| docs-freshness.sh / check-doc-metrics.sh | rc 1 (2668/2748/235/5742 vs computed) / rc 1 (13 stale claims) | rc 0 / rc 0 (37 claims match) |

**Suites on the final tree (f97107b2):**
- core-java unit: 1783 tests, 0 failures, 0 errors, 1 skipped (207 classes)
- core-java FULL integrationTest: 1166 tests, 0 failures, 0 errors, 1 skipped (204 classes, run 22:30–22:59Z, BUILD SUCCESSFUL in 29m 19s)
- jest: 2754 passed in 236 suites (full run)
- `npm run build`: rc 0, TypeScript ran (7.0 s)
- eslint on the four changed frontend files: rc 0

## Files Created/Modified

- `core-java/.../product/dto/CreateProductRequest.java`: `trackInventory` and its contract; the `quantityInStock` description now says omitted = keep.
- `core-java/.../product/ProductMapper.java`: the `quantityInStock` `SET_TO_NULL` override is removed and the comment rewritten.
- `core-java/.../product/ProductService.java`: `validateStockTracking`/`applyStockTracking` on create and PUT; `patchProduct`.
- `core-java/.../product/dto/ProductPatchRequest.java` (new): the narrow body with the any-setter capture.
- `core-java/.../product/ProductController.java`: `@PatchMapping("/{id}")` with the full `@ApiResponses`.
- `core-java/.../exception/InvalidRequestFieldsException.java` (new), `common/GlobalExceptionHandler.java`: the typed 400 in the bean-validation shape.
- `core-java/src/test/.../ProductPartialUpdateIntegrationTest.java`: 17 arms through MockMvc with the real `ShopAccessService`, strict scoping ON, two shops.
- `frontend/app/dashboard/products/page.tsx`, `frontend/types/api.ts`: the payload and its type.
- `frontend/app/dashboard/products/__tests__/product-track-inventory.test.tsx` (new), `product-vat-rate.test.tsx`: the form's contract.
- `docs/api/openapi-snapshot.json`, `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`.

## Decisions Made

- **False plus a quantity is refused, not resolved.** Dropping the quantity silently would be a "say ≠ data" response. The dashboard never sends that pair.
- **Unknown PATCH properties go through an any-setter.** B2 shows that the plan's annotation-based approach answers 200 and ignores the property.
- **Order of checks in PATCH is gate, then body.** A foreign-shop caller always gets 403, and nothing is applied unless every check passes.
- **`InvalidRequestFieldsException` is generic** (field → message), so later 37-E plans can reuse the typed 400 shape.
- **No MCP tool.** That stays a recorded 37.x candidate, per the plan's probe notes.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The old integration arm encoded the defect**
- **Found during:** Task 1
- **Issue:** `explicitNullQuantityInStockClearsTracking` asserted that a PUT omitting `quantityInStock` clears tracking, which is UXT-016 itself.
- **Fix:** Replaced it with the `trackInventory: false` arm. The class moved from service-level calls with a mocked `ShopAccessService` (class-level `@Transactional`) to MockMvc with the real gate. I dropped the class-level transaction because the directory touch commits in its own transaction. The legacy QA-council arms call the service as a realm admin through `asGroupAdmin`, which sets and clears the tenant and security context around each call.
- **Files modified:** ProductPartialUpdateIntegrationTest.java
- **Committed in:** f3080ee7

**2. [Rule 2 - Missing critical] The plan's unknown-property mechanism does not refuse anything here**
- **Found during:** Task 2
- **Issue:** The plan asked for a record with `@JsonIgnoreProperties(ignoreUnknown = false)`. Arm B2 measured `{allergenMask:0}` getting 200, with the property silently ignored, under Spring Boot's app-wide `FAIL_ON_UNKNOWN_PROPERTIES=false`. Even where that annotation does fail a request, the generic `errors/unreadable-request` does not name the property, and the behaviour requires that it does.
- **Fix:** `ProductPatchRequest` is a class with a `@JsonAnySetter` capture. `patchProduct` refuses every captured name with an `errors/validation` 400 keyed by property.
- **Files modified:** ProductPatchRequest.java, ProductService.java
- **Committed in:** 59dd3b82

**3. [Rule 2 - Missing critical] A contradictory `trackInventory: false` + `quantityInStock` is refused**
- **Found during:** Task 1
- **Issue:** The plan does not say what false with a quantity means. Applying either value would silently discard the other.
- **Fix:** Typed 400 naming `quantityInStock`, on create, PUT and PATCH. The create path validates the same rule, so all three writers agree.
- **Committed in:** 9ce3da21

**4. [Rule 2 - Missing critical] A typed 400 type for cross-field rules**
- **Found during:** Task 1
- **Issue:** The plan called for a "typed 400". The available `IllegalArgumentException` maps to `errors/invalid-argument` with no field, so an integrator could not tell which field to fix.
- **Fix:** `InvalidRequestFieldsException` plus its handler, in the bean-validation shape. The handler and the new file were not in `files_modified`.
- **Committed in:** 9ce3da21

**5. [Rule 1 - Bug] An existing frontend arm could no longer fail on the regression it names**
- **Found during:** Task 3, fail arm F1
- **Issue:** The `product-vat-rate` arm "turning stock tracking off still sends quantityInStock null (it must not silently regress)" stayed green with `trackInventory` removed from the form. Once the server keeps the stock when `trackInventory` is absent, a null quantity alone no longer stops tracking.
- **Fix:** The arm also asserts `trackInventory: false`. It now gives 7/7 on the tree and 1 failed under F1.
- **Committed in:** e82c3f72

**6. [Rule 3 - Blocking] The docs-freshness loop was red on the new arms**
- **Found during:** overall verification
- **Issue:** `docs-freshness.sh` rc 1 (`java_test_methods` 2668 → 2680, `jest_blocks` 2748 → 2754, `jest_files` 235 → 236) and `check-doc-metrics.sh` rc 1 on 13 claims. CI fails the build on either. This follows the 37-33 and 37-37 precedent.
- **Fix:** `--write`, plus the prose counts in README, CLAUDE.md and AGENTS.md. Both gates now rc 0.
- **Committed in:** f97107b2

**7. [File list] `types/api.ts` instead of `lib/products-api.ts`**
- `CreateProductRequest`'s TypeScript type lives in `frontend/types/api.ts`. `lib/products-api.ts` has no product PUT/POST and needed no change, so it is untouched.
- The plan calls the checkbox "Track stock"; the real label is "Track inventory", unchanged.

---

**Total deviations:** 6 auto-fixed (2 Rule 1, 3 Rule 2, 1 Rule 3) plus 1 file-list correction.
**Impact on plan:** All necessary for correctness. Deviation 2 means the plan's stated mechanism would have shipped a PATCH that answers 200 to an allergen field. No scope creep: no new endpoint beyond the planned PATCH, no migration, no MCP tool.

## Issues Encountered

- **Intermediate states, all closed within this plan.** Between `9ce3da21` and `6ddee703`, unticking "Track inventory" in the dashboard would not have stopped tracking (the server keeps stock without `trackInventory`). Between `9ce3da21` and `59dd3b82`, `OpenApiSnapshotTest` was red on the Task 1 schema delta. Both close inside the plan; the PR ships the whole set.
- **Python hook.** The `block-base-python` hook refuses `/usr/bin/python3` in this project (as HANDOFF warns), so the RED records were built with node.
- **`git stash list`.** I ran it once by reflex while inspecting metrics. It is read-only and this is the main checkout, not a worktree; nothing was stashed or popped.

## Threat Flags

None beyond the plan's register. T-37-91 is mitigated (IGNORE-null mapping, explicit `trackInventory`, IT). T-37-92 is mitigated (a narrow DTO that refuses unknown properties by name; SQL-unchanged assertions; B1 shows the alternative overwrites the allergen mask). T-37-SC: no package installed.

## User Setup Required

None. No external service configuration required.

## Next Phase Readiness

- **For the orchestrator:**
  - RWO-016 can be ticked.
  - The compose runtime is still the 37-C build cut at d060d0af and is BEHIND the branch. A live `check-openapi-snapshot-fresh.sh` against it will be RED (no PATCH route, no `trackInventory`) until the 37-E gate rebuilds.
  - Nothing here was proven against the running stack.
- **37-E follow-ons:** `InvalidRequestFieldsException` is available for field-keyed typed 400s. A stock/availability MCP tool remains a 37.x candidate.
- Ready for 37-40.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- 7/7 key files FOUND on disk (2 new main classes, the new jest file, 3 RED records, this SUMMARY).
- 9/9 commits FOUND as ancestors of HEAD (f3080ee7 9ce3da21 01d51270 59dd3b82 1e7254ac 6ddee703 e82c3f72 f97107b2 366e008e).
- `gsd_run check evaluation-scope --plan 37-39 --commits-only`: status resolved, source plan-subjects, commits on this branch.
- Acceptance criteria re-run: Task 1 RED then GREEN (recorded); Task 2 SQL-unchanged arm plus B1 (unknown-property arm RED under the updateEntity mapping); Task 3 F1 (the form omitting trackInventory fails the test). All recorded above with both directions.
