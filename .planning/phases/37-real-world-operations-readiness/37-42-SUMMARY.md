---
phase: 37-real-world-operations-readiness
plan: 42
subsystem: api
tags: [orders, mcp, agent-readiness, kitchen, outbox, products, allergens, openapi, idempotency, uxt-036, uxt-040, rwo-036, rwo-040]
status: complete

requires:
  - phase: 37-40
    provides: "OrderService.createOrder refuses shop-less products; the MockMvc + real access-gate IT pattern"
  - phase: 37-11 / 37-21 (D-11)
    provides: "the kitchen board's New lane reads PENDING; the vendor accepts every order"
  - phase: 31.1-01
    provides: "route (a): NON_NULL on new DTO fields keeps the Boot-3.5 golden wire form and the idempotency fingerprints"
provides:
  - "CreateOrderRequest.submit (Boolean, NON_NULL): true creates the order and runs the existing SUBMIT transition (DRAFT -> PENDING, the same ORDER_STATE_CHANGED outbox row the storefront cash path writes) in the create's transaction; absent/false is the unchanged dashboard DRAFT"
  - "MCP create_order always sends submit: true (not a tool input); its description states the order is created PENDING in the kitchen's New lane; no submit_order tool"
  - "ProductDto.allergens: the declared allergens in words from AllergenCatalog.namesFor(allergenMask), catalogue order, NON_NULL"
  - "GET /api/v1/products?availableOnly=true filtered in the query for every read scope (shop narrow, Group admin, scoped grant set)"
  - "MCP list_products takes shopId (uuid) and availableOnly (boolean), forwarded through the allow-listed query string only"
affects: [37-E gate, mcp-server, dashboard-orders, kitchen-board, phase-37-uat]

actuals:
  tokens: 20106
  tasks: 3
  commits: 10
plan_head_before: 784149aa3a37e18b5ac26b25005fcb1e76dc3c23
plan_head_after: 5882822dd1268876178abfce15df1f4c8c8083b5

tech-stack:
  added: []
  patterns:
    - "Submit-on-create through the shared transition: the create calls the same private transitionOrder(SUBMIT) the dashboard's Submit uses, inside its own transaction, so state machine, shop gate, outbox row and idempotent replay come for free"
    - "A tool-decided body field is set AFTER the args spread and kept out of the Zod shape, so an agent cannot override it and it is not published as an input"
    - "MCP tool tests go through a real in-memory round trip (Client + InMemoryTransport) whenever the SDK's Zod parse is the thing under test; a handler-only test cannot see a stripped key"
    - "A query-side page filter is proven with a page the post-filter would leave empty (newer non-matching rows sorted first, size 1); totalElements alone is not evidence because PageImpl recomputes it on a short page"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/order/OrderCreateSubmitIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductDtoAllergenNamesTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductListAvailableOnlyIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-42-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-42-red-task1-mcp.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-42-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-42-red-task2-it.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-42-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/dto/CreateOrderRequest.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductController.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductControllerShopFilterTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductControllerTest.java
    - mcp-server/src/tools/create-order.ts
    - mcp-server/src/tools/create-order.test.ts
    - mcp-server/src/tools/list-products.ts
    - mcp-server/src/tools/list-products.test.ts
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .gitleaks.toml

key-decisions:
  - "37-42: submit is NOT an MCP tool input. The tool sets submit: true after the args spread, so an agent cannot ask for a DRAFT nobody sees; the published input schema does not list it"
  - "37-42: an agent order stops at PENDING. The vendor still accepts it in the New lane; nothing confirms automatically (D-11, T-37-98)"
  - "37-42: ProductDto.allergens is null for a null mask (not recorded) and [] for mask 0 (declared none); may-contain and the reconciliation warnings are never folded in (D-16)"
  - "37-42: availableOnly is a separate service method per scope (getAvailableProducts, getAvailableProductsByShop), so the existing list methods and their callers are untouched; a non-boolean value is a 400"

patterns-established:
  - "Submit-on-create: an optional NON_NULL flag on an idempotency-fingerprinted request that reuses the existing transition inside the create transaction"

requirements-completed: [RWO-036, RWO-040]

coverage:
  - id: D1
    description: "POST /api/v1/orders with submit:true answers 201 PENDING, stores PENDING, records one DRAFT -> PENDING ORDER_STATE_CHANGED outbox row carrying the shop, and GET /orders/kitchen lists the order"
    requirement: "RWO-036"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderCreateSubmitIntegrationTest.java#submitTrueLandsPendingOnTheKitchenBoard"
        status: pass
    human_judgment: false
  - id: D2
    description: "An agent order is never CONFIRMED by the create; an idempotent replay is one order and one event; absent or false submit is the unchanged dashboard DRAFT with no event and off the board"
    requirement: "RWO-036"
    verification:
      - kind: integration
        ref: "OrderCreateSubmitIntegrationTest#submitTrueStopsAtPendingForTheVendorToAccept, #replayWithTheSameKeyIsOneOrderAndOneEvent, #absentSubmitStaysDraft, #explicitFalseStaysDraft"
        status: pass
    human_judgment: false
  - id: D3
    description: "The orders.create idempotency fingerprint of a request without submit is unchanged (fixtures untouched); dropping NON_NULL reds it"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java (15/0; break arm B1 reds request hash orders.create)"
        status: pass
    human_judgment: false
  - id: D4
    description: "MCP create_order always sends submit: true, its description states the PENDING result and the New lane, the real server offers no submit_order tool, and a submit:false argument cannot make a DRAFT"
    requirement: "RWO-036"
    verification:
      - kind: unit
        ref: "mcp-server/src/tools/create-order.test.ts (25/25)"
        status: pass
    human_judgment: false
  - id: D5
    description: "ProductDto.allergens names the declared allergens in catalogue order from AllergenCatalog (65 -> [Gluten, Milk], 0 -> []), beside the unchanged mask, never including may-contain"
    requirement: "RWO-040"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductDtoAllergenNamesTest.java (5/5); AllergenCatalogTest parity green"
        status: pass
    human_judgment: false
  - id: D6
    description: "GET /api/v1/products?shopId=A&availableOnly=true is only A's products on sale with allergens named; the Group-admin and scoped-manager lists drop sold-out rows; the page itself is filtered in the query; without the flag every list is unchanged"
    requirement: "RWO-040"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductListAvailableOnlyIntegrationTest.java (6/6; break arms B4, B5, B6)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductControllerShopFilterTest.java (4 routing arms)"
        status: pass
    human_judgment: false
  - id: D7
    description: "MCP list_products forwards shopId and availableOnly through the real SDK parse, refuses a non-UUID shopId and a non-boolean availableOnly before core, and drops unknown keys"
    requirement: "RWO-040"
    verification:
      - kind: unit
        ref: "mcp-server/src/tools/list-products.test.ts (10/10; fail arm F1: shopId removed from the Zod shape reds 4 arms)"
        status: pass
    human_judgment: false
  - id: D8
    description: "The committed OpenAPI contract carries CreateOrderRequest.submit, ProductDto.allergens and the availableOnly parameter (additive only)"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java (1/0; the pre-plan snapshot reds it)"
        status: pass
    human_judgment: false
  - id: D9
    description: "The running stack serves this: a live MCP create_order lands on the kitchen board's New lane and a live list_products for one shop returns only its products on sale with allergen names"
    verification: []
    human_judgment: true
    rationale: "Nothing here was proven against the running stack. The compose stack still runs the 37-C images cut at d060d0af and is BEHIND the branch; the 37-E gate must rebuild every image (core-java and mcp-server included) and replay both through the MCP server."

duration: 57min
completed: 2026-10-13
---

# Phase 37 Plan 42: Agent orders reach the kitchen; agents read one shop's menu Summary

**An order created through MCP `create_order` is now PENDING and appears in the kitchen's New lane. `CreateOrderRequest.submit` runs the dashboard's own SUBMIT transition inside the create's transaction and writes the same DRAFT -> PENDING outbox row a storefront cash order writes. The dashboard's DRAFT-then-Submit flow is unchanged. `list_products` takes a `shopId` and `availableOnly`, which core applies in the query. Every `ProductDto` carries `allergens` in words (`[Gluten, Milk]` for mask 65), taken from the one Java catalogue.**

## Performance

- **Duration:** 57 min
- **Started:** 2026-10-13T00:48:44Z
- **Completed:** 2026-10-13T01:45:33Z
- **Tasks:** 3. Each was a TDD cycle (RED then GREEN), and Task 1 was a tracer.
- **Files modified:** 27: 7 main, 7 test, 5 RED records, the OpenAPI snapshot, metrics, README, CLAUDE.md, AGENTS.md, `.gitleaks.toml`, and the 2 MCP tools.

## Accomplishments

- **UXT-036 repro fails before and passes after.**
  - Before: a vendor `POST /api/v1/orders` with `submit: true` answered 201 **DRAFT**. Spring dropped the unknown property, no event was recorded, and the kitchen board never listed the order.
  - After: 201 **PENDING**, with one `order.state.pending` outbox row (DRAFT -> PENDING, carrying the shop) and the order on `GET /orders/kitchen`.
  - It never goes past PENDING. A replay with the same Idempotency-Key returns the same PENDING order and writes no second event.
- **One domain path (D-13).** `createOrder` calls the private `transitionOrder(orderId, SUBMIT)` that `submitOrder` uses: state machine, STAFF gate, save, outbox. Nothing is copied. Because it runs in the same transaction, a failed transition rolls the whole create back.
- **MCP `create_order`** sends `submit: true` on every call. That value is set after the args spread and is not in the input schema, so an agent cannot ask for a DRAFT. The description says "The order is created as PENDING and appears in the kitchen's New lane, where the vendor accepts it". There is no `submit_order` tool; this is asserted against the real `buildServer`.
- **UXT-040 repro fails before and passes after.**
  - Before: `list_products {shopId}` reached core as `/api/v1/products` with no query, because the SDK's Zod parse stripped the key, so the agent got the whole tenant's catalogue. Core had no `availableOnly`, and allergens came only as an integer.
  - After: `/api/v1/products?shopId=…&availableOnly=true` returns only that shop's products on sale, each with `allergens`.
- **`availableOnly` is applied in the query for every read scope.** That covers the shop narrow, the Group admin's tenant list, and the scoped user's grant set. The scoped query keeps the explicit tenant predicate and the parentheses that matter. Without the flag (or with `false`), every list is unchanged.
- **Contract published.** The OpenAPI diff is additive only. Test counts went from 5785 to 5814.

## Task Commits

1. **Task 1 (tracer): an agent-created order lands PENDING on the kitchen board.**
   - `af1bc1a8` (chore: gitleaks content allowlist for the RED record)
   - `f7e4fdf9` (test, RED)
   - `de7fcfd3` (feat, GREEN)
   - Tracer gate (end-of-phase mode, automated-only verify): I re-ran the verify on the committed tree. IT 5/0, golden 15/0, vitest 25/0. Then expanded.
2. **Task 2: products name their allergens and filter to what is on sale.**
   - `5696f018` (test, RED)
   - `375cabf5` (feat, GREEN, with the OpenAPI snapshot)
   - `29f11f71` (test: the query-side page arm; see Deviation 3)
3. **Task 3: list_products takes a shop and an on-sale filter.**
   - `0430cdf3` (test, RED)
   - `59c9581a` (test: the RED record re-captured; see Deviation 4)
   - `b2adff81` (feat, GREEN)
4. **Metrics:** `5882822d` (docs: 5785 → 5814).

**Plan metadata:** this SUMMARY commit.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction evidence |
|------|------------------------|-------|-------------------------|
| 1 | `f7e4fdf9`: `37-42-red-task1.json` RED_EVIDENCE_OK (JUnit: 5 tests, 3 failed) and `37-42-red-task1-mcp.json` RED_EVIDENCE_OK (TAP: 25 tests, 3 failed) | `de7fcfd3`: IT 5/0, golden 15/0, vitest 25/0 | B1, B2 |
| 2 | `5696f018`: `37-42-red-task2.json` RED_EVIDENCE_OK (JUnit: 5 tests, 5 failed) and `37-42-red-task2-it.json` RED_EVIDENCE_OK (JUnit: 5 tests, 3 failed) | `375cabf5`: product unit 116/0, IT 6/0 | B3, B4, B5, B6, B7 |
| 3 | `0430cdf3`, re-recorded in `59c9581a`: `37-42-red-task3.json` RED_EVIDENCE_OK (vitest JUnit: 10 tests, 6 failed) | `b2adff81`: list-products 10/10, mcp-server 70/70, tsc rc 0 | F1 (the plan's arm) |

**RED semantic assessments.**
- **Task 1.** The target's first planned assertion failed: expected PENDING, was DRAFT. That is the defect itself. The T-37-98 arm and the replay arm failed on the same status. The two DRAFT controls passed. The vitest arms failed because `submit` was undefined in the body and the description lacked the sentence.
- **Task 2.** The unit test reads the serialised JSON, so it compiles on the pre-fix tree. All 5 tests failed because there was no `allergens` property. In the IT, the target got `[aOnSale, aSoldOut]` where only `[aOnSale]` was expected. The Group-admin and scoped-manager arms failed on their filtered assertions (lines 170 and 186) after their unfiltered controls in the same method had passed.
- **Task 3.** The round-trip target saw core called with no query: the Zod strip, which is the plan's own fail arm. The non-UUID and non-boolean arms saw the value stripped instead of refused. The 4 existing arms passed.

## Verification (both directions)

Each arm ran on a committed tree: restore by `cp` from a scratch copy, then `git diff --quiet HEAD` (rc 0) after each.

| Arm | Break | Fail direction (real output) | Pass direction |
|-----|-------|------------------------------|----------------|
| B1 | `@JsonInclude(NON_NULL)` removed from `submit` | `IdempotencyFingerprintGoldenTest > request hash "orders.create" FAILED` (15 tests, 1 failed) | restored, sha256 `f4b95287…` equal; 15/0 |
| B2 | submit sets PENDING and saves directly, without the shared transition | 5 tests, 2 failed: "the same DRAFT -> PENDING state change … expected: 1L but was: 0L" (UXT-036 and replay arms). Stored status was still PENDING, so the outbox assertion is what proves the event path | restored, `core-java == HEAD` |
| B3 | `NON_NULL` removed from `ProductDto.allergens` | `Jackson3WireContractTest`: 12 tests, 2 failed (records keep their bytes; fixtures tree-equal). This is the golden ProductDto sample | restored; 12/0 and `CacheSerializerTypeAllowlistTest` 16/0 |
| B4 | the shop narrow calls `findByShopId` for `availableOnly` | 5 tests, 1 failed: the UXT-040 target | restored |
| B5 | the scoped query loses its parentheses (`shopId IN … OR shopId IS NULL AND available`) | 5 tests, 1 failed: the scoped-manager arm | restored |
| **B6** | post-filter: `findByShopId`, then a Java filter into a `PageImpl` carrying the unfiltered total | **stayed GREEN (5/5).** On a short page `PageImpl` recomputes the total to 1, so "totalElements counts only those" could not fail. Strengthened (`29f11f71`). Re-run on the new arm: 6 tests, 1 failed (content `[]` where `[aOnSale]` was expected) | restored; 6/0 |
| B7 | pre-plan OpenAPI snapshot put back | `OpenApiSnapshotTest` FAILED ("no longer matches the reviewed snapshot") | restored; 1/0 |
| **F1** (plan's arm) | `shopId` removed from the `list_products` Zod shape | 10 tests, 4 failed: the round trip, the non-UUID refusal, the unknown-keys arm, and the schema arm. The handler-only forwarding arm stayed green, because only the SDK's parse strips | restored; 10/10 |
| gitleaks allowlist | old config / new config / `37-43-` prefix / non-UUID tail | rc 1 (6 hits) / rc 0 / rc 1 (6) / rc 1 (6) | n/a |
| docs-freshness / check-doc-metrics | test counts moved | rc 1 (2696/388/61/5785 vs computed) / rc 1 (13 stale claims) | rc 0 / rc 0 (37 claims match) |
| Root pin guard | run from /tmp | FATAL actual-capture, rc 1 | PIN OK before every commit |

**Suites on the final code tree `5882822d`:**
- **core-java unit, FULL:** 1796 tests, 0 failures, 0 errors, 1 skipped, 208 classes. Before: 1787 / 207 (37-40).
- **core-java integrationTest, FULL:** 1189 tests, 0 failures, 0 errors, 1 skipped, 208 classes. BUILD SUCCESSFUL in 31 min 53 s (01:13Z–01:45Z). Before: 1178 / 206 (37-40's full run; 37-41 changed no core-java).
- **mcp-server:** vitest 70/70 in 8 files (before: 61); `tsc` rc 0.
- `scripts/check-edge-core-contract.sh` rc 0 against the regenerated snapshot.
- **Not run:**
  - jest, Playwright, go and the frontend build. No frontend or edge file changed.
  - `scripts/openapi-gate.sh` was not run, because the full Gradle run held the build directory. `OpenApiSnapshotTest` asserts the same equality.
  - No live check. The stack is behind the branch (coverage D9).

## Files Created/Modified

- `order/dto/CreateOrderRequest.java`: `submit`, with `NON_NULL` and an `@Schema` stating the PENDING result.
- `order/OrderService.java`: the submit branch after the save.
- `product/dto/ProductDto.java`: `allergens`, with `NON_NULL` and an `@ArraySchema`.
- `product/ProductMapper.java`: `fillAllergenNames` (`@AfterMapping`); `allergens` is ignored on `toDto`.
- `product/ProductRepository.java`: `findByShopIdAndAvailableTrue` and `findAvailableTenantScopedInGrantSetOrTenantWide`.
- `product/ProductService.java`: `getAvailableProducts` and `getAvailableProductsByShop`. Both share a private body with the existing methods.
- `product/ProductController.java`: the `availableOnly` parameter and its routing.
- `mcp-server/src/tools/create-order.ts`: `submit: true` and the new description sentence.
- `mcp-server/src/tools/list-products.ts`: the `shopId` and `availableOnly` schema keys, their allow-listed forwarding, and the description.
- Tests:
  - New: `OrderCreateSubmitIntegrationTest` (5), `ProductDtoAllergenNamesTest` (5), `ProductListAvailableOnlyIntegrationTest` (6).
  - Extended: `ProductControllerShopFilterTest` (+4), `create-order.test.ts` (+3), `list-products.test.ts` (+6).
  - Adapted: `ProductControllerTest` (the `list()` signature).
- `docs/api/openapi-snapshot.json`, `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`, `.gitleaks.toml`.

## Decisions Made

- **`submit` is the tool's decision, not the agent's.** It is set after the spread and is not listed in the schema. An agent passing `submit: false` still gets a PENDING order, as the round-trip arm shows.
- **PENDING is the end of it.** Accepting still belongs to the vendor (D-11, T-37-98). The IT asserts that no confirmation event is written.
- **A null mask gives no `allergens`; mask 0 gives `[]`.** This keeps "not recorded" apart from "declared none", following the V63/V66 rule.
- **Separate service methods for `availableOnly`.** The existing list methods, their mocks and their callers (including `ShopScopedListGateTest`) are unchanged. The controller routes on the flag.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] gitleaks content allowlist for the 37-42 RED record**
- **Found during:** Task 1 RED
- **Issue:** The unmodified JUnit report carries `IdempotencyService`'s DEBUG line with `key=37-42-<uuid>`. Gitleaks flags it as generic-api-key (6 hits), and the pre-push P-3 gate would refuse it. The report must stay unmodified, so trimming the line was not an option.
- **Fix:** A content-anchored `[[allowlists]]` block in the 37-40 shape (`^37-42-<uuid>$`, regexTarget secret), measured both ways (see the table above).
- **Committed in:** af1bc1a8

**2. [Rule 2 - Missing critical] An integration test for the query-side filter, plus controller routing arms**
- **Found during:** Task 2
- **Issue:** The plan's Task 2 verify runs only unit `product.*` and `OpenApiSnapshotTest`, and neither can show that `availableOnly` filters at the database. The scoped user's new JPQL is also the kind of query that leaks when its parentheses slip (B5).
- **Fix:** Added `ProductListAvailableOnlyIntegrationTest`, which uses the real filter chain, the real `ShopAccessService` and strict scoping on, and covers all three read scopes. Also added four routing arms in `ProductControllerShopFilterTest`. `ProductControllerTest` follows the new `list()` signature; without that change the test sources would not compile.
- **Committed in:** 5696f018, 375cabf5

**3. [Falsifiability] "Counted in the query" could not fail as first written**
- **Found during:** Task 2, arm B6
- **Issue:** A Java post-filter over one fetched page passed every arm, because `PageImpl` recomputes the total from the content on a short page.
- **Fix:** A new arm adds two newer sold-out products ahead of the on-sale one and reads page 0 with size 1. A query filter returns the on-sale product; a post-filter returns `[]`. B6 now reds that arm. Both directions are recorded above, and the original `totalElements` assertions are kept.
- **Committed in:** 29f11f71

**4. [Process] The Task 3 RED commit preceded a valid RED record**
- **Found during:** Task 3 RED
- **Issue:** My command committed `0430cdf3` without gating on the `tdd-red-evidence` verdict, and that verdict was `INVALID_RED (invalid_record)`. `list-products.ts` uses the real pino logger, which writes JSON lines to stdout ahead of the TAP stream, and the `tap-flat` reporter ignores `--outputFile`.
- **Fix:** No production file had changed. I confirmed `mcp-server == HEAD` (the RED tree) and re-ran the same test file with vitest's JUnit reporter writing its own file. The result was 10 tests, 6 failed, RED_EVIDENCE_OK on the round-trip target. GREEN (`b2adff81`) started only after that.
- **Committed in:** 59c9581a

**5. [Rule 3 - Blocking] Test-count drift**
- **Fix:** `docs-freshness.sh --write` plus the prose counts, as in 37-39, 37-40 and 37-41.
- **Committed in:** 5882822d

**6. [File list]**
- Not in `files_modified`, and added: `ProductRepository.java` (the two queries), `ProductListAvailableOnlyIntegrationTest.java`, `ProductControllerShopFilterTest.java`, `ProductControllerTest.java`, the metrics and prose files, and `.gitleaks.toml`.
- `CreateOrderRequest.submit` reached the OpenAPI snapshot in Task 2's commit, as the plan placed it. So `OpenApiSnapshotTest` was red from `de7fcfd3` until `375cabf5`. That intermediate state closes inside the plan.

---

**Total deviations:** 4 auto-fixed (1 Rule 2, 3 Rule 3/process), 1 falsifiability strengthening, and 1 file-list note.
**Impact on plan:** All were needed to prove the plan's claims or to keep the commits gate-clean. The scope did not grow: there is no new endpoint, no migration, no new MCP tool, and no frontend or edge change.

## Issues Encountered

- **Superuser-run ITs see every tenant.** The Testcontainers role bypasses RLS, so a Group admin's tenant-wide `findAll` in the new IT also returned rows that earlier test methods had seeded under their own tenants. That arm asserts over its own six rows, plus "every returned row is on sale". The shop-narrow and scoped arms are tenant-local and assert exact sets.

## Known Stubs

None.

## Threat Flags

None beyond the register:
- **T-37-98 (mitigated):** the create moves the order only to PENDING. The IT asserts PENDING and no `order.state.confirmed` row.
- **T-37-99 (mitigated):** `shopId` is `z.string().uuid()` and `availableOnly` is `z.boolean()`. Both are appended to the allow-listed `URLSearchParams` only. Refusal and unknown-key arms run through the real SDK parse.
- **T-37-100 (mitigated):** `NON_NULL` on `submit`. The golden passes with fixtures untouched, and B1 proves the annotation is load-bearing.
- **T-37-SC:** nothing was installed.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- **For the orchestrator:**
  - RWO-036 and RWO-040 can be ticked.
  - The compose runtime is still the 37-C build at d060d0af and is BEHIND the branch. A live MCP `create_order` will still produce a DRAFT, and `check-openapi-snapshot-fresh.sh` will be RED, until the 37-E gate rebuilds core-java and mcp-server (coverage D9).
  - HANDOFF's 37-23 note "MCP `create_order` DRAFT (expected until 37-42)" is resolved in code.
- **For the 37-E gate's live run:**
  1. Through the MCP server, `create_order` should give 201 PENDING, with the order on the kitchen board's New lane and the vendor's Accept moving it to CONFIRMED.
  2. `list_products {shopId, availableOnly: true}` should return that shop's on-sale products with `allergens`.
  3. The dashboard's "New order" should still create a DRAFT.
- **Behaviour change integrators will see:** `ProductDto` gains `allergens` (additive); `GET /products` accepts `availableOnly`; `POST /orders` accepts `submit`. A request without either new parameter behaves exactly as before.
- Ready for 37-43.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*

## Self-Check: PASSED

- 8/8 key created files FOUND on disk: the 3 new test classes and the 5 RED records.
- 10/10 commits FOUND as ancestors of HEAD: af1bc1a8 f7e4fdf9 de7fcfd3 5696f018 375cabf5 29f11f71 0430cdf3 59c9581a b2adff81 5882822d.
- `gsd_run check evaluation-scope --plan 37-42 --commits-only`: status resolved, source plan-subjects, commits on this branch.
- All five RED records re-checked: RED_EVIDENCE_OK.
- Must-haves:
  - `OrderCreateSubmitIntegrationTest` contains PENDING (13 occurrences).
  - `create-order.ts` contains `submit: true` (2).
  - The key link pattern `submit` is in the `coreFetch` body (`const body = { ...fields, submit: true }`).
- TDD gate: three `test(37-42)` RED commits, each before its `feat(37-42)`. No refactor commit was needed.
- Acceptance criteria re-run:
  - Task 1: RED then GREEN, the kitchen-board and outbox arms (B2), fixtures untouched (B1).
  - Task 2: `ProductDtoAllergenNamesTest` RED then GREEN, and `AllergenCatalogTest` parity green.
  - Task 3: the Zod-strip fail arm F1 is recorded.
