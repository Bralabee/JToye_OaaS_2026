---
phase: 37-real-world-operations-readiness
plan: 11
subsystem: api
tags: [finance, ledger, flyway, rls, envers, csv, shop-access, cors, spring-boot]

requires:
  - phase: 37-10
    provides: "V78 entry_kind SALE|REVERSAL|MANUAL, createReversal, netted SALE - REVERSAL aggregates, voidOrder"
  - phase: 23
    provides: "ShopAccessService.require / requireGroupAdmin, shop_staff grants, strict scoping"
provides:
  - "V79: financial_transactions.shop_id UUID NULL REFERENCES shops(id) (+ _aud), idx_fin_tx_tenant_shop_created (tenant_id, shop_id, created_at), per-tenant set_config backfill from orders.shop_id"
  - "Writers carry the shop: completion and card settlement pass order.shopId; createReversal copies the sale's shop; MANUAL rows never carry one"
  - "GET /api/v1/financial-transactions and /summary take shopId, from, to (inclusive UK days): shopId -> require(shopId, SHOP_MANAGER), none -> requireGroupAdmin(); GET /{id} and POST stay Group admin"
  - "FinanceScope (internal) and FinanceScopeDto(shopId, shopName, from, to) echoed on the summary (scope) and the list (FinanceTransactionPage.scope)"
  - "GET /api/v1/financial-transactions/export.csv: RFC 4180, same gate and scope, formula-safe text cells, Content-Disposition jtoye-finance-{slug|all-shops}-{from|start}-{to|today}.csv, X-Finance-Scope echo"
  - "CORS: Content-Disposition and X-Finance-Scope in the shipped exposed list and the mandatory floor"
  - "FinancialTransactionDto.shopId; CreateTransactionRequest.shopId (honoured only with an orderId)"
affects: [37-12, 37-15, dashboard-finance, finance-export, cors]

actuals:
  tokens: 26742
  tasks: 3
  commits: 7
plan_head_before: 4fcb54f2ff3ed0574f34533882ef18fd9acf9d21
plan_head_after: 14c83fd5a6c2e12a8087d18f95e9b6eb9ac9d6f8

tech-stack:
  added: []
  patterns:
    - "One FinanceScope threaded through summary, list and export, built by one authorise() that gates first"
    - "Scoped JPQL binds only non-null parameters (allShops flag + wide sentinels); the unfiltered scope keeps the original CQ-02 queries"
    - "CSV formula neutralisation applies to free-text cells only; generated numeric cells stay numbers"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V79__financial_transactions_shop_id.sql
    - core-java/src/main/java/uk/jtoye/core/finance/FinanceScope.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinanceCsvExporter.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinanceScopeDto.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinanceTransactionPage.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinanceExportRow.java
    - core-java/src/test/java/uk/jtoye/core/finance/FinanceShopScopeIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/finance/LedgerShopBackfillMigrationIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionRepository.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionMapper.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/CreateTransactionRequest.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinancialSummaryDto.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinancialTransactionDto.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/config/CorsConfig.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/config/CorsExposedHeadersTest.java
    - core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java
    - docs/api/openapi-snapshot.json

key-decisions:
  - "37-11: finance gate per method: shopId present -> require(shopId, SHOP_MANAGER) and filter by it; absent -> requireGroupAdmin(); GET /{id} and POST manual stay Group admin; STAFF refused everywhere (P2-TUN-15)"
  - "37-11: shop_id is nullable (add-alongside). A MANUAL row never carries a shop, even if the request names one, so it shows only in the tenant-wide view; per-shop totals need not sum to the tenant total"
  - "37-11: scoped aggregates bind non-null parameters only (allShops flag, 1970/9999 sentinels); an unfiltered scope still runs the original CQ-02 queries, so the 1k golden and the 2-statement count are untouched"
  - "37-11: the CSV neutralises = + - @ (and tab/CR) in text cells only; amounts stay numbers. Content-Disposition and X-Finance-Scope join the CORS shipped list and floor because the dashboard downloads cross-origin"
  - "37-11: the scope's shop is read through ShopService.getShopById, never a controller-injected repository (issue #501 gate)"

patterns-established:
  - "A finance read is authorised and scoped in one place (authorise), and every response echoes the scope it used"

requirements-completed: []

coverage:
  - id: D1
    description: "UXT-027: the manager of shop A reads shop A's summary and list (200, A only, scope echoed); shop B and the tenant view are 403 shop-access-denied; STAFF refused with and without a shop; GET /{id} and POST stay Group admin"
    requirement: "RWO-027"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/finance/FinanceShopScopeIntegrationTest.java#managerOfA_seesOwnShopSummary, #managerOfA_isRefusedShopB_andTheTenantView, #staffOfA_isRefused, #managerOfA_listsOwnShopRows, #singleRowAndManualEntry_stayGroupAdmin"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java#staffIsRefusedTheLedgerWithAndWithoutAShop, #shopManagerReadsOwnShop_butIsRefusedTheTenantView"
        status: pass
    human_judgment: false
  - id: D2
    description: "Group admin (realm admin or tenant-wide OPERATOR grant) reads tenant totals including the MANUAL row with scope.shopId null; shopId=B reads B only; from/to filter by UK day and are echoed"
    requirement: "RWO-027"
    verification:
      - kind: integration
        ref: "FinanceShopScopeIntegrationTest#realmAdmin_readsTenantTotals, #realmAdmin_withShopB_seesShopBOnly, #operatorGroupAdmin_readsTenantTotals, #dateRange_filtersAndIsEchoed"
        status: pass
    human_judgment: false
  - id: D3
    description: "Writers: completing an order at A writes a SALE with shop A; the void's REVERSAL copies it, and A's scoped summary nets to zero"
    requirement: "RWO-027"
    verification:
      - kind: integration
        ref: "FinanceShopScopeIntegrationTest#writers_carryTheOrdersShop"
        status: pass
    human_judgment: false
  - id: D4
    description: "V79 backfills existing order-linked rows (SALE and REVERSAL) to their order's shop in BOTH tenants under a NOSUPERUSER NOBYPASSRLS migrator; MANUAL and dangling rows stay NULL; _aud mirror, FK and index present; bare-UPDATE break arm red"
    requirement: "RWO-027"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/finance/LedgerShopBackfillMigrationIntegrationTest.java#v79AttributesExistingLedgerRowsToTheirOrdersShopAcrossTenantsUnderRls"
        status: pass
    human_judgment: false
  - id: D5
    description: "CSV export of exactly the scope: text/csv, header row, UK date-times, SALE + REVERSAL rows, signed pounds and VAT, scoped filename, X-Finance-Scope; empty range is header-only 200; same gate as the summary; = + - @ cells neutralised by exact text"
    requirement: "RWO-027"
    verification:
      - kind: integration
        ref: "FinanceShopScopeIntegrationTest#managerOfA_exportsOwnShopCsv, #emptyRange_exportsHeaderOnly, #export_isGatedLikeTheSummary, #tenantWideExport_neutralisesFormulaCells"
        status: pass
    human_judgment: false
  - id: D6
    description: "The export's Content-Disposition and X-Finance-Scope are exposed to cross-origin script, from the shipped default and from the floor when an operator list omits them"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/config/CorsExposedHeadersTest.java#financeExportHeadersAreInTheFloor, #theShippedDefaultAlreadySatisfiesTheFloor"
        status: pass
    human_judgment: false
  - id: D7
    description: "Goods kept: 1k financial-summary golden unchanged and green; CQ-02 two-statement count, cross-tenant isolation and query plan green; OpenAPI snapshot regenerated (additive) and matching"
    verification:
      - kind: integration
        ref: "uk.jtoye.core.finance.FinancialSummaryGoldenFileTest, FinancialSummaryQueryCountTest, FinancialSummaryCrossTenantIsolationTest, FinancialSummaryQueryPlanTest, uk.jtoye.core.integration.OpenApiSnapshotTest"
        status: pass
      - kind: other
        ref: "git diff 4fcb54f2..HEAD -- core-java/src/test/resources/fixtures/financial-summary-1k.golden.json (0 bytes)"
        status: pass
    human_judgment: false
  - id: D8
    description: "The running service exposes the scoped reads, the export and V79 (runtime parity); the dashboard's scope banner, Export CSV button and reversal rows"
    verification: []
    human_judgment: true
    rationale: "Runtime parity needs the rebuilt compose stack, which this phase does in 37-15; the finance UI (banner, export button, empty states) is 37-12. Neither is this plan's to prove."

duration: 1h 0m
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 11: Per-shop ledger scope and export Summary

**Every ledger row now knows its shop (V79, backfilled per tenant under RLS), so a shop manager reads their own shop's summary, list and CSV export behind `require(shopId, SHOP_MANAGER)` while the tenant-wide view stays Group-admin only and STAFF stays refused; every response echoes the scope it filtered on, and the CSV neutralises spreadsheet formulas.**

## Performance

- **Duration:** 1h 0m (of which about 28 min was the full suite)
- **Started:** 2026-10-08T23:09:30Z
- **Completed:** 2026-10-09T00:09:00Z
- **Tasks:** 3 (Task 1 tracer, Tasks 2 and 3 auto, all tdd)
- **Files modified:** 24 source/test/doc files + 4 RED evidence records

## Accomplishments

- UXT-027's repro now fails before and passes after: the manager of shop A was refused with a generic 403 (`errors/forbidden`) that the dashboard rendered as "No financial data yet". The same request now returns 200 with shop A's figures only, and `scope.shopId` and `scope.shopName` name what was filtered.
- The tenant ledger is no wider than before. Without a shopId the caller needs Group admin; a manager naming another shop gets 403 `shop-access-denied`; STAFF is refused on every endpoint (goods P2-TUN-15).
- V79 attributes every existing order-linked row, SALE and REVERSAL alike, to its order's shop in every tenant. This is proven under a NOSUPERUSER NOBYPASSRLS migrator, and a bare UPDATE reports success while backfilling nothing.
- Roadmap SC-2: the figures shown can be exported as CSV for exactly that scope and range. A voided order shows as its SALE and REVERSAL netting to zero, and vendor text cannot run as a spreadsheet formula.

## Task Commits

1. **Task 1: A shop manager sees their own shop's summary** (tracer, tdd)
   - `8033716c` test(37-11): add failing shop-scope arms for per-shop finance (UXT-027) (RED)
   - `27b236c0` feat(37-11): per-shop ledger scope behind SHOP_MANAGER, Group admin for the tenant view (V79) (GREEN)
   - Tracer gate: `HUMAN_VERIFY_MODE` end-of-phase with an automated-only verify. The tracer verify was re-run fresh after GREEN (10/0) and passed, so expansion continued.
2. **Task 2: The backfill is proven across tenants under the RLS-bound role** (tdd)
   - `05964aac` test(37-11): prove the V79 shop backfill across two tenants under the RLS-bound migrator
   - `c1aa0e15` test(37-11): record the bare-UPDATE break arm of the V79 backfill (RED_EVIDENCE_OK)
3. **Task 3: CSV export of exactly the scope shown, and the published contract** (tdd)
   - `534645e4` test(37-11): add failing arms for the scoped CSV export and its exposed headers (RED)
   - `2b188d31` feat(37-11): CSV export of exactly the finance scope shown, with formula-safe cells (GREEN)
4. **Post-suite fix**
   - `14c83fd5` fix(37-11): resolve the finance scope's shop through ShopService, not a controller repository

No REFACTOR commits were needed. All 7 commits in `4fcb54f2..14c83fd5` belong to this plan.

**Plan metadata:** see the docs(37-11) commit that adds this file.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `8033716c` (`evidence/37-11-red-shop-scope.json`, RED_EVIDENCE_OK) | `27b236c0` | not needed |
| 2 | `c1aa0e15` (`evidence/37-11-red-stepwise-v79.json`, RED_EVIDENCE_OK, break-arm RED) | V79 shipped in `27b236c0` | not needed |
| 3 | `534645e4` (`evidence/37-11-red-export.json` and `evidence/37-11-red-cors-export-headers.json`, both RED_EVIDENCE_OK) | `2b188d31` | not needed |

- **Task 1 RED.** Target: "UXT-027: the manager of shop A sees shop A's figures only, and the response names the scope it filtered on". Actual: `HTTP 403 type https://jtoye.uk/errors/forbidden`, expected 200. 8 of 10 arms were red: the manager's summary and list (403), the operator GROUP_ADMIN with no realm role (403), the cross-shop arm (type `forbidden` instead of `shop-access-denied`), no scope object, shopId=B ignored (4900 instead of 3000), from/to ignored (14800 instead of 9900), and the SALE's shop_id null. The STAFF and GET-by-id/POST refusal arms passed, as they must before and after. **Semantic assessment:** the target ran and failed on the planned assertion for the intended reason, the class-level admin gate with no shop scope anywhere. This was not a fixture fault: the same context completed orders and served the realm admin 200, and shop_id is read through `to_jsonb`, so a missing column reads as null.
- **Task 2 RED is a break-arm RED, not a pre-implementation RED.** Plan order put V79 in Task 1 (the tracer needed the column), so Task 2's test was green on arrival. The plan names Task 2's fail direction as the bare-UPDATE arm, and that run is the RED record. Target: `v79AttributesExistingLedgerRowsToTheirOrdersShopAcrossTenantsUnderRls()`. Actual: `[tenant …: SALE of the order at shop X -> X] expected <X> but was: null`, after the migrate assertions (V79 found, exactly one migration executed) had passed. The broken migration reported success.
- **Task 3 RED.** Export target: `HTTP 400 type-mismatch "Invalid value for parameter 'id'"`, expected 200. With no export route, `export.csv` falls through to `GET /{id}`. All four export arms failed on that missing route, the ten Task 1 arms passed, and FinancialTransactionControllerIntegrationTest passed 8/0. CORS target: the exposed list lacked `content-disposition` and `x-finance-scope`, with 14 other arms green. The first CORS record named the method instead of the JUnit display name, and the classifier returned `INVALID_RED / no_target_test_failure`. Correcting the name gave `RED_EVIDENCE_OK`, the same trap 37-10 hit.

## Acceptance criteria, both directions

| Criterion | Pass direction | Fail direction |
|-----------|----------------|----------------|
| T1: RED (manager 403 / tenant totals) then GREEN; STAFF stays 403 before and after | GREEN 10/0 (`27b236c0`), STAFF arm green | RED 8/10 at `8033716c`, with the STAFF arm green in the same run |
| T1: `grep -n "hasRole('admin')"` no longer shows the class-level gate | Literal grep still matches line 38, the new javadoc naming the replaced gate. The criterion as written cannot pass while the doc names the token, so the stronger anchored form `grep -nE '^\s*@PreAuthorize'` is used and recorded: rc=1, empty on the working tree | Anchored form on `8033716c`: rc=0, `40:@PreAuthorize("hasRole('admin')")` |
| T1: GROUP_ADMIN gate asserted by test for the tenant-wide reads | `operatorGroupAdmin_readsTenantTotals` (tenant-wide OPERATOR GROUP_ADMIN with no realm role: 200 summary, 3 rows in list) plus the manager's tenant-view 403 | Break arm (SHOP_MANAGER `require` removed): the cross-shop and STAFF arms went red, `HTTP 200 … expected 403` |
| T2: green with the loop; bare-UPDATE break arm red and restored; V79 content hash before and after | 1/0 on the committed V79 | Bare UPDATE: red as above. V79 sha256 `4ddef47ca5b6fd8a` before, `c21134b70287b6c3` armed, `4ddef47ca5b6fd8a` after |
| T3: export RED then GREEN; formula cell asserted by exact text | 14/0 at `2b188d31`; the cell `"'=HYPERLINK(""http://evil.example"",""click"")"` is matched exactly | Neutralisation disabled: the formula arm went red with the raw `"=HYPERLINK(""http…` in the body |
| T3: FinancialTransactionControllerIntegrationTest keeps every refusal arm (count recorded) | Before (`4fcb54f2`): 2 refusal assertions (both 400). After: 5 (both 400 kept, plus 3 new 403). Both original arms still present by name | The same count on a copy with one 400 arm deleted: 4 |
| T3 export filter is the scope (not every shop) | own-shop export: 3 rows, all shop A | `exportForScope(true …)` arm: the own-shop export arm went red, listing shop B and MANUAL rows |
| OpenAPI snapshot matches the source | `OpenApiSnapshotTest` 1/0 | Snapshot reverted to the `4fcb54f2` version: red, "no longer matches the reviewed snapshot" |
| No Flyway placeholder in V79 | `rg -uu '\$\{'` rc=1, empty | The same pattern on a scratch file with one: rc=0, 1 hit |
| 1k golden unchanged | `git diff 4fcb54f2..HEAD -- …golden.json`: 0 bytes | The same range form on the controller: 16616 bytes (the range form can see change) |
| XML summariser can report failures | the green runs above | fed the captured RED report: `failures="8"` and each failing name |

The break arms ran as two bracketed runs, each clean, then arms, then clean, with the restore target committed first. Run A (Tasks 1 and 2): 4 reds predicted, and exactly 4 red (stepwise, cross-shop, STAFF, writers). After `git checkout HEAD --`, all three content hashes equalled the pre-arm hashes, 0 `BREAK-ARM` tokens remained, and the closing run went 11/0. Run B (Task 3): 3 reds predicted, and exactly 3 red (own-shop export, formula, OpenAPI). Hashes were restored, 0 tokens remained, and the closing run went 15/0.

## Verification

- Plan `<verification>` 1: FinanceShopScopeIntegrationTest went RED and then GREEN (14/0). LedgerShopBackfillMigrationIntegrationTest is 1/0, with its break arm recorded.
- Plan `<verification>` 2: FinancialTransactionControllerIntegrationTest 8/0 and OpenApiSnapshotTest 1/0.
- Task 3 `<verify>` exactly as written, all reports fresh: FinanceShopScope 14/0, LedgerShopBackfill 1/0, LedgerEntryKindMigration 1/0, LedgerSingleEntry 3/0, FinancialSummaryCrossTenantIsolation 2/0, Golden 1 pass + 1 skipped (`@Disabled` capture), QueryCount 1/0, QueryPlan 1/0, FinancialTransactionController 8/0, OpenApiSnapshot 1/0.
- Neighbour sweep after Task 1: OrderVoid 22/0, RoleBasedAccess 6/0, LocationHeaderContract 7/0, TypedNotFoundBody 13/0, and unit finance/payment/order 307/0.
- **Full suites** (after the fix, all reports fresh, 0 stale of 188 + 186): `:core-java:test` **1616 tests, 0 failures, 0 errors, 1 skipped**. `:core-java:integrationTest` **987 tests, 0 failures, 0 errors, 1 skipped**. Against the 37-10 baseline (1615 + 970) that is +1 unit (the CORS floor arm) and +17 integration (14 scope/export arms, 1 stepwise test and 2 controller gate arms). The deltas reconcile exactly.
- Success criterion: UXT-027's repro fails before (403 forbidden) and passes after (200, A only), and the export carries exactly that scope (the own-shop export lists 3 rows, all shop A, with the scoped filename).

## Decisions Made

- **Per-method gate.** With a shopId the caller needs `require(shopId, SHOP_MANAGER)` and only that shop's rows are read. Without one the caller needs `requireGroupAdmin()` and gets the whole tenant, NULL-shop rows included. `GET /{id}` and `POST` are Group admin. A Group admin naming a foreign shop gets the existing non-disclosing 404 (FC-1).
- **A MANUAL row never carries a shop**, even if a request names one. `CreateTransactionRequest.shopId` is honoured only beside an orderId. This keeps the "NULL-shop rows appear only tenant-wide" truth enforced in the service, not by convention.
- **Non-null scoped binds.** `allShops` plus 1970/9999 sentinels are used rather than `:p IS NULL OR …`, which PostgreSQL cannot type for an untyped NULL. An unfiltered scope still runs the two original CQ-02 queries, so the golden, the 2-statement count and the reflection pin (no tenant predicate) all hold unchanged. The scoped twins also carry no tenant predicate: RLS supplies it, and the shop gate has already proven the shop belongs to the caller's tenant.
- **List scope echo** uses `FinanceTransactionPage`, a `PageImpl` subclass adding only `scope`, so `content`, `totalElements` and `totalPages`, which the dashboard reads, are unchanged. The OpenAPI page schema keeps all 11 members and gains `scope`.
- **CSV format.** The header is `date,reference,order_number,entry_kind,amount,vat_rate,vat_amount,shop`. Dates are `dd/MM/yyyy HH:mm` in Europe/London (BST measured: 23:37Z printed as `09/10/2026 00:37`). Amounts are signed pounds. `vat_rate` is the enum name. The filename uses `start` for an open start and today's UK date for an open end. `X-Finance-Scope` is ASCII (`shopId=…; from=…; to=…`); the shop's name stays in the summary's scope because a header cannot carry arbitrary text.
- **Formula neutralisation** covers `= + - @` and also tab and CR (OWASP's list). It applies to TEXT cells only: a REVERSAL's `-15.00` must stay a number.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The controller injected `ShopRepository` (issue #501 class)**
- **Found during:** the full suite after Task 3 (`ControllerRepositoryInjectionGateTest` 2 red).
- **Issue:** the shop-name lookup ran from the controller with no transaction, so `TenantSetLocalAspect` never pinned the tenant GUC. Under FORCE RLS an UNPUBLISHED shop would be invisible, and a manager's own shop would return 404 in production. Every superuser IT stayed green.
- **Fix:** the shop is read through `ShopService.getShopById`, which is transactional, tenant-predicated and STAFF-floored beneath the SHOP_MANAGER check already passed.
- **Verification:** the gate is 7/0, the fail direction being the full-run red on `2b188d31`. The scope ITs and OpenAPI stay green, and the full suites are green.
- **Committed in:** `14c83fd5`

**2. [Rule 2 - Missing critical] CORS exposure of the export's headers**
- **Found during:** Task 3 design.
- **Issue:** the dashboard calls the API cross-origin (`NEXT_PUBLIC_API_URL`, no rewrite). Without an exposure the browser hides `Content-Disposition` and `X-Finance-Scope`, so the download cannot carry the scope-named filename and the scope cannot be read back (say ≠ data).
- **Fix:** both names were added to the shipped `CORS_EXPOSED_HEADERS` default and to `MANDATORY_EXPOSED_HEADERS`. The exact-list floor test was updated, and a new arm asserts both names on the actual and preflight responses when the operator list omits them.
- **Files modified:** `CorsConfig.java`, `application.yml`, `CorsExposedHeadersTest.java` (not in the plan's file list).
- **Committed in:** `534645e4` (RED), `2b188d31` (GREEN)

**3. [Rule 1 - Unfalsifiable criterion] The `hasRole('admin')` grep**
- The criterion as written still matches: the new javadoc names the replaced gate, a doc rule naming its own token. The anchored `^\s*@PreAuthorize` form was run in both directions and recorded alongside it (table above).

**4. [Rule 3 - Blocking] Test-side adjustments**
- `FinancialTransactionServiceTest`'s mapper stub builds `FinancialTransactionDto` positionally and gained the `shopId` argument.
- FinanceShopScopeIntegrationTest clears `financial_transactions` per test. The Testcontainers superuser bypasses RLS, and the tenant-wide aggregates rely on RLS for their tenant predicate, so the first RED run summed another test's rows (19700 instead of 9900). This is the same move FinancialTransactionControllerIntegrationTest already makes.
- A JUnit-XML summariser was written in bash/xmllint after the machine's base-python guard refused a Python script file.

---

**Total deviations:** 4 (2 Rule 1, 1 Rule 2, 1 Rule 3). **Impact:** #1 would have shipped a production-only 404 for unpublished shops; #2 is required for the export's scope to be readable by the dashboard. No scope creep beyond the finance surface and the CORS list it needs.

## Issues Encountered

- Plan ordering made Task 2's RED a break-arm RED (see TDD Gate Compliance). It is recorded, not hidden.
- `docs/metrics.json` drift: V79 moves `schema_version` to 79 and the new tests move the counts. This is already tracked as Phase 37 deferred item 1 (`scripts/docs-freshness.sh --write` at the phase PR), as with 37-08..37-10.
- The plan's suggested invariant ("sum of shop totals + the NULL-shop bucket == tenant total") is shown by the fixture's numbers (1200 + 3000 + 700 = 4900 in `realmAdmin_readsTenantTotals`) but is not asserted as a separate invariant test.

## User Setup Required

None. An operator who overrides `CORS_EXPOSED_HEADERS` gets the two new names from the floor automatically.

## Threat Flags

None beyond the plan's register:
- **T-37-30** is mitigated by `require(shopId, SHOP_MANAGER)` plus the shop filter and Group admin for no shopId, with cross-shop, tenant-view and STAFF 403 arms on summary, list and export.
- **T-37-31** is mitigated by apostrophe neutralisation, with the exact-text test and its break arm.
- **T-37-32** is mitigated by the tenant loop, the two-tenant stepwise test and the bare-UPDATE break arm.
- **T-37-SC:** no package was installed.

The CORS exposure adds two response-header names readable by already-allowed origins and carries no data beyond what the response body already holds.

## Next Phase Readiness

- 37-12 (UI) has what it needs:
  - `?shopId=&from=&to=` on summary and list
  - `scope` on both responses, for the banner
  - `export.csv`, with the filename in an exposed `Content-Disposition`
  - `entryKind` and `shopId` on each row, for the reversal badges
  - `shop-access-denied` on refusal, for the "Choose a shop" and "Admin access required" states
- Runtime parity (OpenAPI live check, image freshness, V79 applied on the compose DB) is owed to 37-15's rebuild. The runtime is behind the branch until then.
- RWO-027 is listed here as this plan's contribution. The REQUIREMENTS.md checkbox stays open for 37-12 and 37-15 (`requirements.ready-ids` 0/1).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

All 8 key created files and 4 RED evidence records exist. Commits 8033716c, 27b236c0, 05964aac, c1aa0e15, 534645e4, 2b188d31 and 14c83fd5 are ancestors of HEAD. `evaluation-scope` resolved 7 commits. The must-have artifacts contain their markers: V79 contains `set_config('app.current_tenant_id'` (2), FinanceShopScopeIntegrationTest contains `shopId` (23), and FinancialTransactionController contains `SHOP_MANAGER` (12).
