---
phase: 37-real-world-operations-readiness
plan: 10
subsystem: api
tags: [orders, ledger, flyway, rls, envers, void, rfc7807, spring-boot]

requires:
  - phase: 37-08
    provides: "invite accept path (wave predecessor); no code dependency"
  - phase: 31.1
    provides: "V73 order column/_aud shape, V57 tenant-loop backfill pattern and its stepwise Flyway test"
provides:
  - "DELETE /api/v1/orders/{id} refuses any order past PENDING: 409 order-not-deletable (ORDER_NOT_DELETABLE, orderStatus, orderNumber)"
  - "POST /api/v1/orders/{id}/void {reason}: Group-admin only, keeps the order, records voidedAt/voidedBy/voidReason, writes one REVERSAL of the SALE in the same transaction"
  - "V78: orders(+_aud) void triple with ck_orders_void_triple; financial_transactions(+_aud) entry_kind SALE|REVERSAL|MANUAL and reverses_transaction_id; uq_fin_tx_tenant_order = (tenant_id, order_id, entry_kind) WHERE order_id IS NOT NULL"
  - "EntryKind enum; FinancialTransactionService.createReversal / findSaleForOrder; FinancialTransactionRepository.findByOrderIdAndEntryKind"
  - "Summary revenue = SALE - REVERSAL; MANUAL negatives stay expenses"
  - "Problem types order-not-deletable, order-already-voided (voidedBy, voidedAt), order-not-voidable"
  - "Kitchen board excludes voided orders; a voided order cannot transition again"
affects: [37-11, 37-12, 37-15, finance-csv-export, kitchen-board, mcp-read-orders]

actuals:
  tokens: 26029
  tasks: 3
  commits: 6
plan_head_before: 495c8180b87efde36ced03a37ec399a972d9f55e
plan_head_after: 50860c858a8c516e826308ac8ced643a1377d71d

tech-stack:
  added: []
  patterns:
    - "Ledger entry kinds: one append-only table, a reversal is an ordinary row naming its sale; idempotency keyed by (order, kind)"
    - "A problem property never shadows an RFC 7807 member: the order's status is orderStatus, status stays the HTTP code"
    - "Stepwise Flyway proof for a FORCE-RLS backfill: migrate to N-1 as superuser, seed two tenants, apply N as a NOSUPERUSER NOBYPASSRLS owner"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V78__order_void_ledger_reversal.sql
    - core-java/src/main/java/uk/jtoye/core/finance/EntryKind.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/VoidOrderRequest.java
    - core-java/src/main/java/uk/jtoye/core/exception/OrderNotDeletableException.java
    - core-java/src/main/java/uk/jtoye/core/exception/OrderAlreadyVoidedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/OrderNotVoidableException.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderVoidIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/finance/LedgerEntryKindMigrationIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderController.java
    - core-java/src/main/java/uk/jtoye/core/order/Order.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionRepository.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionMapper.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/FinancialTransactionDto.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java
    - docs/api/openapi-snapshot.json

key-decisions:
  - "D-10 ledger representation: entry-kind, owner ruling 2026-10-08"
  - "37-10: a void is columns on orders (voided_at/by/reason), not a VOIDED status (CONTEXT Q6); a reversal copies the sale's vat_rate, no VAT computation changes (CONTEXT Q2)"
  - "37-10: createTransaction writes SALE when the request carries an order and MANUAL when it does not, the same rule V78's backfill applies to existing rows; entry_kind keeps DEFAULT 'SALE' only as the raw-SQL backstop"
  - "37-10: the order-not-deletable 409 names the order's status as orderStatus; a property named status serialised a second \"status\" key beside RFC 7807's numeric 409"
  - "37-10: a voided order cannot transition again (400 invalid-state-transition), so completion can never write a SALE after the void that nothing reverses"
  - "37-10: ck_fin_tx_reversal_link: a REVERSAL always names its sale and no other kind names one; reverses_transaction_id is a FK to financial_transactions(id)"
  - "37-10: void returns OrderDetailDto (like the allergy-note acknowledgement); no MCP void tool in this plan, recorded"

patterns-established:
  - "Entry-kind ledger: aggregates branch on kind before sign; unique (tenant, order, kind) is the race backstop for every per-order writer"
  - "Problem-detail property naming: never reuse type/title/status/detail/instance as a property name"

requirements-completed: [RWO-018]

coverage:
  - id: D1
    description: "DELETE of a CONFIRMED-or-later order is a typed 409 order-not-deletable naming the order's status; DRAFT/PENDING still delete (204); the shop gate runs first; no ledger row is left pointing at a 404"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderVoidIntegrationTest.java#completedOrderWithLedgerRow_deleteIsRefused_andNothingDangles, #postPendingOrder_deleteIsRefused (5), #draftOrPendingOrder_stillDeletes (2), #staffOnlyUser_getsShopAccess403_notTheStatus"
        status: pass
    human_judgment: false
  - id: D2
    description: "Group admin voids a COMPLETED order: 200, order still reads 200 with voidedAt/voidedBy/voidReason and unchanged status, exactly one REVERSAL (-1200, STANDARD, names the sale), revenue drops by the sale and expenses do not move"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "OrderVoidIntegrationTest#completedOrder_voidedByGroupAdmin_reversesTheSale"
        status: pass
    human_judgment: false
  - id: D3
    description: "Void is idempotent (same reason -> first void, no second reversal) and a different reason is 409 order-already-voided with voidedBy/voidedAt"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "OrderVoidIntegrationTest#sameReasonAgain_returnsTheExistingVoid_andWritesNothing, #differentReason_isAlreadyVoided409"
        status: pass
    human_judgment: false
  - id: D4
    description: "Void authorisation and validation: SHOP_MANAGER 403, missing orders:write 403, blank/missing/over-long reason 400, DRAFT/PENDING 409 order-not-voidable; nothing written in each"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "OrderVoidIntegrationTest#shopManager_cannotVoid, #voidWithoutWriteScope_is403, #blankReason_is400 (3), #overlongReason_is400, #draftOrPending_isNotVoidable (2)"
        status: pass
    human_judgment: false
  - id: D5
    description: "A CONFIRMED order with no SALE voids with no ledger row and leaves the kitchen board (positive control stays); a voided order cannot be completed"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "OrderVoidIntegrationTest#confirmedOrderWithoutSale_voids_andLeavesTheKitchenBoard, #voidedOrder_cannotBeCompleted"
        status: pass
    human_judgment: false
  - id: D6
    description: "V78 classifies existing rows SALE/MANUAL in BOTH tenants under a NOSUPERUSER NOBYPASSRLS migrator; index includes entry_kind; second SALE/REVERSAL and unknown kind refused; _aud mirrors and ck_orders_void_triple present"
    requirement: "RWO-018"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/finance/LedgerEntryKindMigrationIntegrationTest.java#v78ClassifiesExistingLedgerRowsAcrossTenantsUnderRls"
        status: pass
    human_judgment: false
  - id: D7
    description: "Ledger service: fast path keyed by SALE (no two-row failure once a REVERSAL exists), SALE/MANUAL kinds on write, createReversal writes the negated sale and is idempotent"
    requirement: "RWO-018"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java (24/0)"
        status: pass
    human_judgment: false
  - id: D8
    description: "1k financial-summary golden unchanged and green (goods P2-KEM-22 ledger VAT correct)"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/finance/FinancialSummaryGoldenFileTest.java#getSummaryOutputMatchesCommittedGolden"
        status: pass
      - kind: other
        ref: "git diff 495c8180..HEAD -- core-java/src/test/resources/fixtures/financial-summary-1k.golden.json (empty)"
        status: pass
    human_judgment: false
  - id: D9
    description: "OpenAPI snapshot regenerated and matching the source tree"
    verification:
      - kind: integration
        ref: "uk.jtoye.core.integration.OpenApiSnapshotTest"
        status: pass
    human_judgment: false
  - id: D10
    description: "The running service exposes the void endpoint and V78 (runtime parity); the dashboard void UI"
    verification: []
    human_judgment: true
    rationale: "Runtime parity (check-openapi-snapshot-fresh.sh, check-runtime-freshness.sh) needs the rebuilt compose stack, which this phase does in 37-15; the void UI is 37-12. Neither is this plan's to prove."

duration: 47min (continuation, Task 3 + verification) after Task 1 in the earlier session
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 10: Delete guard and void with a reversing ledger entry Summary

**A COMPLETED order can no longer be deleted from under its ledger row: DELETE past PENDING is a typed 409, and a Group admin instead voids it with a reason, which keeps the order, records who/when/why and writes one REVERSAL of its SALE (V78 entry kinds, revenue = SALE - REVERSAL), proven on a two-tenant database under the RLS-bound migrator.**

## Performance

- **Duration:** Task 1 in the earlier session (RED run 2026-10-08T16:58Z, GREEN cb339d0f); continuation 47 min (2026-10-08T22:18Z to 23:05Z), of which ~28 min was the full suite
- **Started:** 2026-10-08T16:58:07Z (Task 1 RED run)
- **Completed:** 2026-10-08T23:05:00Z
- **Tasks:** 3 (Task 1 tracer, Task 2 owner checkpoint, Task 3)
- **Files modified:** 23 source/test/doc files + 4 RED evidence records

## Accomplishments

- UXT-018's repro (COMPLETED order deleted, ledger row left pointing at a 404) now fails before and passes after: 204 plus 1 dangling row became 409 order-not-deletable with nothing dangling.
- `POST /api/v1/orders/{id}/void` gives the Group admin the alternative the 409 names: the order stays, the void is recorded, and the sale is reversed in the same transaction, so the order nets to zero in the ledger and the summary.
- V78 replaces the one-row-per-order ledger invariant with one SALE and at most one REVERSAL per order, enforced by the database, and classifies every existing row through a per-tenant loop that is proven against the FORCE-RLS bare-UPDATE trap.
- Found and fixed a malformed problem body from Task 1: `order-not-deletable` carried two `status` keys.

## Task Commits

1. **Task 1: A confirmed-or-later order can no longer be deleted** (tracer, tdd)
   - `89717aee` test(37-10): add failing delete-guard arms for UXT-018 (RED)
   - `cb339d0f` feat(37-10): refuse deleting an order past PENDING with a typed 409 (GREEN)
2. **Task 2: checkpoint:decision (blocking-human), ledger representation.** Owner answered **entry-kind** on 2026-10-08. No commit.
3. **Task 3: Void with a reversing ledger entry, V78 and netted aggregates** (tdd)
   - `02e252d0` test(37-10): add failing tests for the void, the reversing ledger entry and V78 (RED)
   - `50860c85` feat(37-10): void an order with a reversing ledger entry (V78, entry kinds, netted revenue) (GREEN)
   - No REFACTOR commit (nothing to clean up after GREEN).

Interleaved in the measured range but not this plan's: `d3a6f3e6` chore(gitleaks) for 37-09, and `8e9cd423` docs(handoff) at the checkpoint. That is why `commits: 6` is measured over `495c8180..50860c85`, of which 4 are 37-10's.

**Plan metadata:** see the docs(37-10) commit that adds this file.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `89717aee` (37-10-red-delete-guard.json, RED_EVIDENCE_OK) | `cb339d0f` | not needed |
| 3 | `02e252d0` (three records below, each RED_EVIDENCE_OK) | `50860c85` | not needed |

**Task 3 RED evidence**, each passed through `gsd_run check tdd-red-evidence` with verdict `RED_EVIDENCE_OK`:

- `evidence/37-10-red-void.json`. Target: "D-10 void: a Group admin voids a COMPLETED order …". Actual: `HTTP 404 Resource not found` for `/api/v1/orders/{id}/void`, expected 200. 12 of 21 arms red, all on the missing endpoint; the nine Task 1 arms green in the same context. **Semantic assessment:** the target ran and failed on the planned status assertion because the behaviour is absent. It was not a context or fixture fault, and the SQL helpers read the future columns through `to_jsonb`/`SELECT *` so a missing column could not masquerade as an error.
- `evidence/37-10-red-stepwise-v78.json`. Target: `LedgerEntryKindMigrationIntegrationTest#v78ClassifiesExistingLedgerRowsAcrossTenantsUnderRls()`. Actual: `[V78__order_void_ledger_reversal.sql must be on the classpath] Expecting value to be true but was false`, after migrate-to-V77, the two-tenant seed and the migrator provisioning had all run. The first record named the method without the `()` JUnit emits, and the classifier returned `INVALID_RED / no_target_test_failure`. Correcting the name gave `RED_EVIDENCE_OK`.
- `evidence/37-10-red-ledger-fast-path.json`. Target: "createTransaction for an order whose SALE exists returns that SALE …". Actual: `assertDoesNotThrow` failed on `IncorrectResultSizeDataAccessException: expected 1, actual 2`, which is exactly what the unkeyed finder does once a REVERSAL exists. The other new unit tests were red on their own assertions.
- RED skeleton (the 37-08 precedent): compile-only and inert at runtime. It added `EntryKind`, `@Transient` entity members, a default finder delegating to the old one, and `createReversal` returning null. GREEN replaced every one.

## Acceptance criteria, both directions

| Criterion | Pass direction | Fail direction |
|-----------|----------------|----------------|
| Task 1 break arm: remove the guard → COMPLETED arm reds | 22/0 at `50860c85` | Re-run here (the earlier record could not be found): guard disabled → all 6 post-PENDING delete arms red, COMPLETED arm `HTTP 204 \| ledger rows now pointing at a missing order: 1, expected 409` |
| Stepwise break arm: bare UPDATE instead of the tenant loop → MANUAL assertions red under the RLS-bound role | `LedgerEntryKindMigrationIntegrationTest` 1/0 | `[tenant1 order-less row -> MANUAL] expected "MANUAL" but was "SALE"` |
| `git diff 495c8180..HEAD -- …financial-summary-1k.golden.json` prints nothing | 0 bytes, before and after the arms | Scratch edit (`24449800` → `24449801`): the worktree form printed 539 bytes. The **range form itself** was exercised against an unreferenced `commit-tree` object holding the edit (`7759e7d5`, HEAD never moved): 539 bytes. Restored by content (sha256 `18c02a36…` before and after). |
| Revenue = SALE − REVERSAL (aggregate) | void arm green | Aggregate reverted to the pure sign rule → void arm red on "revenue drops by the voided sale" |
| Voided order cannot transition | arm green | guard disabled → `HTTP 200` on `/complete`, expected 400 |
| Kitchen board excludes voided | arm green, positive control present | predicate removed via `@Query` → `Expecting [voided, kept] not to contain [voided]` |
| No `${` placeholder in V78 | `rg -uu` rc=1, empty | the same pattern on a scratch file holding one placeholder: rc=0, count 1 |

The break arms ran as one bracketed run: clean, then arms, then clean. Before: 4 files hash-matched HEAD. Arms: exactly the 10 predicted reds (9 in `OrderVoidIntegrationTest`, 1 stepwise) and no others. After: `git checkout HEAD --`, the 4 hashes matched the pre-arm hashes, and zero `BREAK-ARM` tokens remained. The closing functional clean arm was the full suite below.

## Verification

- `<verify>` 1, exactly as written (plus `uk.jtoye.core.finance.*`), with fresh XMLs: `OrderVoidIntegrationTest` 22/0, `LedgerEntryKindMigrationIntegrationTest` 1/0, `FinancialTransactionControllerIntegrationTest` 6/0, `OpenApiSnapshotTest` 1/0, `FinancialSummaryGoldenFileTest` 1 pass + 1 skipped (`@Disabled` capture), `FinancialSummaryCrossTenantIsolationTest` 2/0, `FinancialSummaryQueryCountTest` 1/0, `FinancialSummaryQueryPlanTest` 1/0, `LedgerSingleEntryIntegrationTest` 3/0.
- `<verify>` 2 as written: `FinancialTransactionServiceTest` 24/0, `VatCalculatorTest` 12/0, and the whole `uk.jtoye.core.order.*` unit set green (`OrderServiceTest` 38/0).
- **Full suite after the arms**, all reports fresh (0 stale of 188 + 184): `:core-java:test` **1615 tests, 0 failures, 0 errors, 1 skipped**; `:core-java:integrationTest` **970 tests, 0 failures, 0 errors, 1 skipped**. Against cb339d0f (1610 + 956) that is +5 unit (the new ledger unit tests) and +14 integration (13 void arms + the stepwise test), so the deltas reconcile exactly.

## Decisions Made

- **D-10 ledger representation: entry-kind, owner ruling 2026-10-08.** One ledger table: `entry_kind` SALE | REVERSAL | MANUAL plus `reverses_transaction_id`, and the unique index becomes `(tenant_id, order_id, entry_kind) WHERE order_id IS NOT NULL`.
- SALE iff the request carries an order and MANUAL otherwise. This matches V78's backfill, and the plan's "createTransaction writes SALE" read literally would have labelled admin rows as sales.
- A same-reason replay also calls the idempotent `createReversal`, so a replay can complete a first void whose reversal was missing, and never writes a second.
- Void returns `OrderDetailDto`, mirroring the allergy-note acknowledgement. There is no MCP void tool: a void is a Group-admin decision recorded against a person (agent-readiness reason recorded on the endpoint).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `order-not-deletable` serialised two `status` keys**
- **Found during:** Task 3 (adding a numeric-status assertion to the new 409s)
- **Issue:** Task 1 set a ProblemDetail property named `status`. The measured body was `…"status":409,…,"status":"COMPLETED",…`. A last-wins parser loses the HTTP code, and a strict parser rejects the document. This breaks the RFC 7807 contract that agent-readiness relies on.
- **Fix:** the property is now `orderStatus`, so `status` stays the 409. Updated the Task 1 test assertions, the exception javadoc and the DELETE `@ApiResponse`. No consumer read the old property (`rg -uu` over frontend, MCP and later plans: only 37-12 references this family, and it reads `order-already-voided`).
- **Verification:** the new assertion reds on the old handler (the body quoted above), then goes green.
- **Committed in:** `50860c85`

**2. [Rule 2 - Missing critical] A voided order could still be transitioned, and completing it would write an unreversed SALE**
- **Found during:** Task 3 design
- **Issue:** a void is columns, not a status, so a voided CONFIRMED or READY cash order could still go to COMPLETED. Completion writes the order's SALE, and nothing would ever reverse it, so the books would show revenue for a voided order.
- **Fix:** `transitionOrder` refuses a voided order with 400 invalid-state-transition.
- **Verification:** `voidedOrder_cannotBeCompleted` is green, and its break arm reds.
- **Committed in:** `50860c85`

**3. [Rule 2 - Missing critical] Ledger listing could not tell a reversal from an expense**
- **Issue:** `FinancialTransactionDto` carried no kind, so `GET /financial-transactions` would show the void's -1200 row looking exactly like an expense: the same misreport the aggregate fix prevents.
- **Fix:** added `entryKind`, `orderId` and `reversesTransactionId` (additive). The 37-11 CSV export can use them.
- **Committed in:** `50860c85`

**4. [Rule 2 - Missing critical] Additive integrity constraints in V78**
- `ck_fin_tx_reversal_link` (a REVERSAL names its sale and nothing else names one) and a FK on `reverses_transaction_id`. They change no existing row, and the stepwise test asserts the index and kind CHECK functionally.
- Added `OrderNotVoidableException` (409 `order-not-voidable`) for the plan's "DRAFT/PENDING -> 409 (delete them instead)", because the plan named no exception type for it.

**5. [Rule 1 - Unfalsifiable criterion] `<verify>` 2 cannot see the 1k golden**
- **Issue:** `:core-java:test --tests 'uk.jtoye.core.finance.*'` "including the 1k golden" is vacuous: `FinancialSummaryGoldenFileTest` is `@Tag("testcontainers")`, which `tasks.test` excludes. That command runs only the two unit classes.
- **Fix:** ran it as written and **also** ran the golden and every finance Testcontainers suite under `integrationTest` (listed above), plus the golden-diff criterion in both directions.

**6. [Rule 3 - Blocking] RED record tooling**
- A Python record builder was refused by the machine's base-python hook, so the records were built with `jq --rawfile` instead (no Python involved). The test file's lenient stub of the deleted `findByOrderId` stopped compiling at GREEN and was removed. The SALE-keyed stub plus `never().save` still fails if any other finder is consulted.

---

**Total deviations:** 6 (2 Rule 1, 3 Rule 2, 1 Rule 3). **Impact:** each is required for a correct, machine-readable financial record. No scope creep beyond the order/ledger files this plan owns.

## Issues Encountered

- The Task 1 break-arm record could not be found (commit message, HANDOFF and STATE all searched), so it was re-run here; see the table above.
- `docs/metrics.json` drift: V78 moves `schema_version` to 78 and the new tests move the counts. This is already tracked as Phase 37 deferred item 1 (`scripts/docs-freshness.sh --write` at the phase PR), and earlier 37 plans left it the same way. The CLAUDE.md "Current schema version: V75" prose belongs to the same pass.
- Refunds still do not touch the ledger (RESEARCH 37-B.4). This is out of scope and recorded for the 37-B gate.

## User Setup Required

None. No external service configuration required.

## Threat Flags

None beyond the plan's register. T-37-27 (repudiation) is mitigated by the 409 guard, the reason plus who/when, and the `_aud` mirrors. T-37-28 (elevation) is mitigated by `requireGroupAdmin` + `SCOPE_orders:write`, with SHOP_MANAGER 403 and no-scope 403 arms. T-37-29 (tampering by a silent backfill) is mitigated by the tenant loop plus the two-tenant stepwise test and its bare-UPDATE break arm. The new endpoint is inside the declared order-mutation boundary.

## Next Phase Readiness

- 37-11 (CSV export) can list both rows by `entryKind`. 37-12 (UI) has the endpoint, the DTO void fields and the `order-already-voided` `voidedBy`/`voidedAt`.
- Runtime parity (OpenAPI live check, image freshness) is owed to 37-15's rebuild. The runtime is behind the branch until then.
- RWO-018 is listed here as this plan's contribution. The REQUIREMENTS.md checkbox stays open for 37-12 and 37-15, per the phase's shared-requirement precedent (RWO-003/004).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

All 12 key files and 4 RED evidence records exist; commits 89717aee, cb339d0f, 02e252d0, 50860c85 are ancestors of HEAD; the must-have artifacts contain their markers (V78: uq_fin_tx_tenant_order; OrderVoidIntegrationTest: order-not-deletable; FinancialTransactionService: REVERSAL).
