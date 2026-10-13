---
phase: 37-real-world-operations-readiness
plan: 41
subsystem: api
tags: [edge-go, sync, pass-through, rfc7807, problem-json, circuit-breaker, contract-gate, swag, openapi, integrators, uxt-017, uxt-037, rwo-017, rwo-037]
status: complete

requires:
  - phase: 37-40
    provides: "core's sync contract: BatchSyncResponse {status SUCCESS|PARTIAL|REJECTED, processedCount, results[SyncItemResult]}, HTTP 200 for a partial batch, typed 400/401/403 problem+json refusals, and the OpenAPI snapshot carrying all of it"
  - phase: 37-25
    provides: "evidence/go-test-json-to-tap.sh (Go RED evidence), the edge's own problem+json 429 and the newRouter seam"
  - phase: "issue #337"
    provides: "the edge-core contract gate (EdgeCoreCalls + TestEdgeCoreContract) and the coreBatchSyncResponse wire type"
provides:
  - "core.SyncItemResult and BatchSyncResponse.Results: core's per-item results reach the integrator through the edge"
  - "core.CoreProblemError: a core 4xx application/problem+json comes back typed (status, content type, Retry-After, bytes, bounded at 64 KiB) and outside the circuit breaker"
  - "SyncBatch handler relays a core problem verbatim and answers core's own success status (200, or 202 from an older core)"
  - "contract gate check 6: PassThrough / RelaysProblems / EdgeRoute manifest fields; a pass-through answer must be decoded in full, published in full with enums, and every core 4xx must be problem+json and documented on the edge route"
  - "edge swag docs: SyncBatchResponse with the status enum and results, SyncItemResult with the outcome and code enums, CoreProblem for the relayed 403, 200 documented"
affects: [37-E gate, edge-go, phase-37-uat, integrators]

actuals:
  tokens: 19592
  tasks: 2
  commits: 5
plan_head_before: e528a1ed5bfd12a37bcf6649ab7c6a97f7e642e4
plan_head_after: 5681f154cca7ca8eefd396595f71e54bd09c28d8

tech-stack:
  added: []
  patterns:
    - "A typed 4xx refusal from a downstream is returned OUTSIDE the circuit breaker (the Execute func returns nil error and the problem is surfaced after it), so a caller's own refusals can never open the breaker for every tenant"
    - "Pass-through manifest: a client call whose answer the edge hands to its own callers declares PassThrough/RelaysProblems/EdgeRoute, and the contract gate then checks decode completeness, published completeness (with enums) and relayed-4xx documentation against both specs"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-41-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-41-red-task2.json
  modified:
    - edge-go/internal/core/client.go
    - edge-go/internal/core/client_test.go
    - edge-go/internal/core/contract.go
    - edge-go/internal/core/contract_test.go
    - edge-go/cmd/edge/handlers.go
    - edge-go/cmd/edge/handlers_test.go
    - edge-go/cmd/edge/types.go
    - edge-go/docs/docs.go
    - edge-go/docs/swagger.json
    - edge-go/docs/swagger.yaml
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "37-41: a core 4xx problem is returned outside the circuit breaker; before, every non-2xx counted as a failure, so 10 refusals of one integrator opened the breaker for every tenant for 60 s (measured RED: core reached 10 times, then 'circuit breaker is open')"
  - "37-41: the edge answers core's own success status (200 from a 37-40 core, 202 from an older one) instead of a fixed 202; a partially refused batch is 200 and the truth is in status and results"
  - "37-41: core's Retry-After is relayed with the problem, so a relayed 429 still tells the integrator how long to wait"
  - "37-41: only a 4xx whose media type parses as application/problem+json is relayed, at most 64 KiB; a 4xx of any other type, an oversized body and every 5xx stay the edge's generic 502 with no core body (T-37-96)"
  - "37-41: the edge keeps its published snake_case processed_count; the gate compares core and edge names case- and underscore-insensitively and requires every core enum value in the published enum"

patterns-established:
  - "Relay, do not translate: a downstream's typed refusal reaches the edge's caller with its status, content type, Retry-After and bytes unchanged"

requirements-completed: [RWO-017, RWO-037]

coverage:
  - id: D1
    description: "A core 200 PARTIAL reaches the integrator through the edge as 200 with status, processed_count and core's results array unchanged"
    requirement: "RWO-037"
    verification:
      - kind: unit
        ref: "edge-go/cmd/edge/handlers_test.go#TestSyncBatch_PassesCoresPerItemResultsThrough"
        status: pass
      - kind: unit
        ref: "edge-go/internal/core/client_test.go#TestClient_SyncBatch_PassesPerItemResultsThrough"
        status: pass
    human_judgment: false
  - id: D2
    description: "A core 403 application/problem+json reaches the integrator as 403 with the identical content type and byte-identical body (extension members included); a core 429 keeps Retry-After"
    requirement: "RWO-017"
    verification:
      - kind: unit
        ref: "edge-go/cmd/edge/handlers_test.go#TestSyncBatch_RelaysCoreProblemVerbatim"
        status: pass
      - kind: unit
        ref: "edge-go/cmd/edge/handlers_test.go#TestSyncBatch_RelaysCoreRateLimitWithRetryAfter"
        status: pass
      - kind: unit
        ref: "edge-go/internal/core/client_test.go#TestClient_SyncBatch_CoreProblemIsReturnedTyped"
        status: pass
    human_judgment: false
  - id: D3
    description: "Core unreachable and core 5xx stay the existing 502 with no core body; a non-problem 4xx and an oversized problem are not relayed; a run of core 403 problems no longer opens the circuit breaker"
    verification:
      - kind: unit
        ref: "edge-go/cmd/edge/handlers_test.go#TestSyncBatch_CoreFailuresStay502"
        status: pass
      - kind: unit
        ref: "edge-go/internal/core/client_test.go#TestClient_SyncBatch_OnlyClientProblemsAreTyped, #TestClient_SyncBatch_OversizedProblemIsNotRelayed, #TestClient_SyncBatch_ProblemRefusalsDoNotOpenTheBreaker"
        status: pass
    human_judgment: false
  - id: D4
    description: "The edge-core contract gate fails when the edge would drop a field core declares for the sync response, when the edge's published spec omits a property, an enum value or a relayed 4xx, or when core declares a non-problem 4xx; it is VOID when nothing declares the relay"
    verification:
      - kind: unit
        ref: "edge-go/internal/core/contract_test.go#TestEdgeCoreContract/pass-through_calls_deliver_what_core_declares,_and_the_edge_publishes_it (RED 9351a440; GREEN 498709dc; arms A1-A7 each red)"
        status: pass
      - kind: other
        ref: "scripts/check-edge-core-contract.sh (rc 0)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The edge's published OpenAPI (swag) documents 200 with SyncBatchResponse {status enum, processed_count, results[SyncItemResult with outcome and code enums]}, 403 as CoreProblem, and how core's 400/401 arrive"
    verification:
      - kind: unit
        ref: "edge-go/cmd/edge/openapi_test.go#TestOpenAPISpec_Fresh (green; arm F1 red on stale docs)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The running edge serves the pass-through (a live sync batch through the edge returns core's per-item results and relays a typed 403)"
    verification: []
    human_judgment: true
    rationale: "Nothing here ran against the live stack: the compose runtime is still the 37-C build cut at d060d0af, BEHIND the branch (no 37-39..37-41 change in it). The 37-E gate must rebuild every image and send a mixed batch and an ungranted-shop batch through the edge."

duration: 11min
completed: 2026-10-13
---

# Phase 37 Plan 41: Edge pass-through of the sync contract Summary

**The edge now hands integrators what core says about a sync batch. Core's `status` (SUCCESS, PARTIAL or REJECTED), its processed count and its one-result-per-item `results` arrive on core's own 200. A core 4xx `application/problem+json` is relayed with the same status, content type, `Retry-After` and bytes, outside the circuit breaker. Before, it became an untyped 502. The contract gate now fails if the edge would drop or leave unpublished anything core declares for this call.**

## Performance

- **Duration:** 11 min
- **Started:** 2026-10-13T00:17:02Z
- **Completed:** 2026-10-13T00:27:47Z
- **Tasks:** 2 (2 TDD cycles: RED then GREEN each; Task 1 a tracer)
- **Files modified:** 15, plus 2 RED evidence records (7 edge-go source and test files, 3 generated docs files, metrics, README, CLAUDE.md, AGENTS.md, deferred-items)

## Accomplishments

- **Pitfall 7 repro: RED before, GREEN after.** Before, against a stub core:
  - a core 200 PARTIAL came back to the integrator as **202 with `results: null`**;
  - a core 403 `shop-access-denied` problem became **502 `{"error":"failed to sync with core API"}`**;
  - a core 429 became 502 and lost `Retry-After`.

  After, the same requests return 200 with core's results, 403 with core's exact bytes, and 429 with `Retry-After: 7`.
- **A breaker defect found and fixed along the way.** Every non-2xx counted as a breaker failure. After 15 core 403s, core had been reached only 10 times: the breaker had opened, and a healthy batch from any tenant then failed. Core problems are now returned outside the breaker. Outages, open breakers and 5xx still trip it as before.
- **The edge-core contract gate checks the reverse direction for pass-through calls.** Before, it only asserted that the edge reads a subset of core's response, so dropping `results` would have stayed green. With `PassThrough`, `RelaysProblems` and `EdgeRoute` on `Client.SyncBatch`, the gate now requires all of the following:
  - every core property is decoded, recursively;
  - every core property is published in `edge-go/docs/swagger.json`, with every enum value;
  - every core 4xx for the operation is problem+json and documented on the edge route.
- **The published edge contract matches what the edge returns.**
  - `SyncBatchResponse` has the status enum and `results`.
  - `SyncItemResult` has the outcome enum and the ten 37-40 codes.
  - 200 is documented, and 202 is kept for an older core.
  - 403 is documented as `CoreProblem`, and the 400/401 descriptions explain how core's own refusals arrive.

## Task Commits

1. **Task 1 (tracer): per-item results and core refusals reach the integrator through the edge.** `6793441e` (test, RED), `72cf4bab` (feat, GREEN). Tracer gate (`human_verify_mode` end-of-phase, automated-only verify): verify re-run on the committed tree, rc 0, then expanded.
2. **Task 2: the contract gate and published docs know the new shape.** `9351a440` (test, RED), `498709dc` (feat, GREEN).
3. **Metrics:** `5681f154` (docs: 5776 → 5785).

**Plan metadata:** this SUMMARY commit (with the deferred-items entry).

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | REFACTOR |
|------|------------------------|-------|----------|
| 1 | `6793441e`, `evidence/37-41-red-task1.json`: RED_EVIDENCE_OK, target_test_failed, 12 tests / 6 failed (target `TestSyncBatch_RelaysCoreProblemVerbatim`) | `72cf4bab`: 8 target tests pass, `go test ./internal/core/ ./cmd/edge/` ok/ok | none needed |
| 2 | `9351a440`, `evidence/37-41-red-task2.json`: RED_EVIDENCE_OK, target_test_failed, 5 tests / 2 failed (target `TestEdgeCoreContract/pass-through_calls_deliver_what_core_declares,_and_the_edge_publishes_it`) | `498709dc`: `go test ./...` ok, vet and gofmt clean | none needed |

**RED semantic assessments.**
- **Task 1.** Every target test ran and failed on its planned assertion, for the intended reason:
  - the client mapped any non-200/202 to an untyped error counted by the breaker, and decoded only `status` and `processedCount`;
  - the handler always answered 202 or 502;
  - `HTTPStatus` read 0, and the error was `*errors.errorString`.

  There was no compile, load or fixture fault: the stub core was reached and answered. Following the 37-25 convention, the RED commit carries compile scaffolding only: `SyncItemResult`, `BatchSyncResponse.Results`/`HTTPStatus` and `CoreProblemError` are declared but never populated or returned. Six guard arms were already green and stayed green: the two 502 arms, and the untyped 4xx and 5xx problem arms.
- **Task 2.** The new subtest failed, for the intended reason: the published edge spec still described `{status, processed_count}` under 202, with no `results`, no status enum and no 403. The other three subtests of the same test passed, so the harness can pass. The decode-completeness half of that subtest was not RED, because Task 1 had already added `results` to `coreBatchSyncResponse`. Its fail direction is the plan's acceptance arm, A1 below.

The evidence format is the real `go test -json` stream, rendered to TAP by the committed `evidence/go-test-json-to-tap.sh` (37-25), with the raw stream kept in `rawOutput`.

## Verification (both directions)

Every arm ran on the committed tree, in the order clean, arm, restore by `cp`, sha256 match, then clean again. Each run ended with `edge-go == HEAD` (git diff empty).

| Arm | Break | Fail direction (real output) | Pass direction |
|-----|-------|------------------------------|----------------|
| B1 | core problem returned INSIDE the breaker (`return nil, problem`) | `ProblemRefusalsDoNotOpenTheBreaker` FAIL: "circuit breaker is open; core was reached 10 times" | restored, rc 0 |
| B2 | `isClientProblem` upper bound 499 → 599 | **stayed green (rc 0): not a break.** The pre-existing `>= 500` branch runs first, so the bound is a second line of defence | — |
| B2' | the `>= 500` branch deleted AND the bound widened | `OnlyClientProblemsAreTyped/5xx_problem+json` FAIL; `CoreFailuresStay502/core_500_problem` FAIL: "got 500, want 502", "a core 5xx body leaked … secret internals" | restored, rc 0 |
| B3 | size bound removed | `OversizedProblemIsNotRelayed` FAIL: "a 65537-byte problem body was relayed; the bound is 65536" | restored, rc 0 |
| B4 | handler writes `application/json` | `RelaysCoreProblemVerbatim` FAIL: content type `application/json` | restored, rc 0 |
| B5 | `Retry-After` relay removed | `RelaysCoreRateLimitWithRetryAfter` FAIL: Retry-After "" | restored, rc 0 |
| B6 | `Results` not copied in the client | client and handler pass-through tests FAIL: "per-item results were not passed through" | restored, rc 0 |
| **A1** (plan's arm) | `Results` removed from `coreBatchSyncResponse` | contract FAIL: `core declares "results", but coreBatchSyncResponse has no field tagged json:"results"` | restored, rc 0 |
| A2 | `results[].message` not decoded (`json:"-"`) | contract FAIL: `.results[]: core declares "message", but SyncItemResult has no field …` | restored |
| A3 | `results` removed from the published edge type, docs regenerated | contract FAIL: `core declares "results", which the edge's published spec does not document (documents: [processed_count status])` | restored |
| A4 | 403 annotation removed, docs regenerated | contract FAIL: `core answers 403 … but the edge's post /api/v1/sync/batch does not document 403` | restored |
| A5 | `PARTIAL` removed from the published enum, docs regenerated | contract FAIL: `core can answer PARTIAL, which the edge's published enum does not list (lists: [SUCCESS REJECTED])` | restored |
| A6 | `PassThrough`/`RelaysProblems` set false | contract FAIL: `VOID: no call is declared PassThrough or RelaysProblems` | restored |
| A7 | core snapshot's 403 content type changed to `application/json` (temporary) | contract FAIL: `core declares 403 … as [application/json], not application/problem+json — … would reach the integrator as a 502` | restored by sha256 |
| F1 | an enum tag edited, docs NOT regenerated | `TestOpenAPISpec_Fresh` FAIL: "docs/swagger.json is stale" (proves the 0.03 s freshness test really runs swag) | restored |
| docs-freshness / check-doc-metrics | test counts moved | rc 1 (Go 103 vs computed 112) / rc 1 (7 stale claims) | rc 0 / rc 0 (37 claims match) |
| Root pin guard | run from /tmp | FATAL, stage actual-capture, rc 1 | PIN OK before every commit |

**Suites and gates on HEAD 5681f154:**
- `go -C edge-go test -count=1 -v ./...`: 135 runs, 135 pass, 0 fail, 0 skip, rc 0. Before (on e528a1ed): 121 runs.
- `go vet ./...` clean; `gofmt -l edge-go` empty.
- `scripts/check-edge-core-contract.sh` rc 0.
- `scripts/check-doc-citations.sh` rc 0 (29 verified, 7 uncheckable). Its `docs/ops/terminal-states.yaml` source VOIDs because its extractor needs python and this shell's python shim refuses it. That is an environment issue, not caused by this plan.
- **Not run:** core-java, jest, Playwright, vitest. No file of theirs changed. There was also no live check: the stack is behind the branch.

## Files Created/Modified

- `edge-go/internal/core/client.go`: `SyncItemResult`, `BatchSyncResponse.Results` and `HTTPStatus`, `CoreProblemError`, `isClientProblem`, the 64 KiB bound, the problem returned outside the breaker, and `results` decoded.
- `edge-go/cmd/edge/handlers.go`: relays the problem (status, content type, Retry-After, bytes), answers with core's success status, and has the updated swag annotations.
- `edge-go/cmd/edge/types.go`: `SyncBatchResponse` (enum, results), `SyncItemResult` (enums) and `CoreProblem`.
- `edge-go/internal/core/contract.go`: check 6 in the header, the `PassThrough`, `RelaysProblems` and `EdgeRoute` fields, and the SyncBatch entry declaring them.
- `edge-go/internal/core/contract_test.go`: the new subtest plus `loadEdgeSpec`, `checkRelay`, `complete`, `published`, `resolveEdgeRef` and `normWire`.
- Tests: `client_test.go` (+5 top-level), `handlers_test.go` (+4 top-level).
- `edge-go/docs/{docs.go,swagger.json,swagger.yaml}`: regenerated with swag v1.16.3, the CI pin. The diff covers only the sync route and the three new definitions.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: 5776 → 5785, Go 103 → 112.
- `deferred-items.md`: one stale citation, found and logged.

## Decisions Made

- **Problems go outside the breaker.** The plan did not mention the breaker. Without this change, relaying 403s would still leave one integrator's refusals able to shed every tenant's sync for 60 s.
- **The edge answers with core's success status.** The plan's feature case says "edge 200". A fixed 202 would misdescribe a 37-40 core, and the 202 tests with a 202 stub core stay true.
- **`Retry-After` is relayed.** A relayed 429 body says "wait Retry-After seconds", so the header has to come with it.
- **Problem bodies are capped at 64 KiB.** Core's problems are a few hundred bytes. The edge does not buffer or vouch for anything larger, and falls back to the generic 502.
- **The published snake_case `processed_count` is kept** (a published contract, #337). The gate compares names case- and underscore-insensitively.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The circuit breaker counted core's deliberate refusals as outages**
- **Found during:** Task 1 RED (`ProblemRefusalsDoNotOpenTheBreaker` was written to pin the relay's behaviour, and showed the breaker opening after 10 refusals).
- **Fix:** the problem is captured inside `Execute`, which returns a nil error, and is returned after it.
- **Verification:** RED, then GREEN, then arm B1 red again.
- **Committed in:** 72cf4bab

**2. [Rule 2 - Missing critical] `Retry-After` relay and the 64 KiB body bound**
- **Found during:** Task 1
- **Issue:** a relayed 429 without `Retry-After` gives the integrator no wait time. An unbounded `io.ReadAll` of a downstream refusal is a memory risk (T-37-96 family).
- **Fix:** `CoreProblemError.RetryAfter` is written by the handler. Reads use `io.LimitReader(…, 64 KiB + 1)`, and anything over the bound becomes the generic error.
- **Verification:** RED, GREEN, then arms B3 and B5.
- **Committed in:** 6793441e (tests), 72cf4bab

**3. [Rule 3 - Blocking] `types.go` moved from Task 1's commit to Task 2's**
- **Issue:** the plan lists `types.go` under Task 1 and the docs under Task 2. Any change to a swag type or annotation without regenerating makes `TestOpenAPISpec_Fresh`, which is inside Task 1's verify package `./cmd/edge/`, fail.
- **Fix:** the swag types, the annotations and the regenerated docs land together in 498709dc, so every commit stays green.

**4. [Rule 3 - Blocking] Metrics drift from the new tests**
- **Fix:** `docs-freshness.sh --write` plus the prose counts, as in 37-39 and 37-40.
- **Committed in:** 5681f154

**5. [Plan criterion] The `key_links` pattern `Results` does not occur in `handlers.go`**
- The handler forwards the whole `BatchSyncResponse` (`c.JSON(status, resp)`), so the literal never appears.
- Adding a comment to satisfy the grep would make it vacuous, so I did not. The link is proven behaviourally instead: `TestSyncBatch_PassesCoresPerItemResultsThrough` passes, and arm B6 (results not copied) reds it.
- `contains: "application/problem+json"` in `client.go`: 3 occurrences.

**6. [Falsifiability] Arm B2 was not a break**
- Widening `isClientProblem` to 5xx alone stays green, because the `>= 500` branch runs first.
- This is recorded as such. The stronger arm B2', which deletes that branch as well, proves the T-37-96 guards can fail.

---

**Total deviations:** 4 auto-fixed (1 Rule 1, 1 Rule 2, 2 Rule 3) plus 2 criterion notes.
**Impact on plan:** all were needed for correctness or to keep each commit green. No package or Go module was added (T-37-SC). No new endpoint.

## Issues Encountered

- One compound command carried a stray python token. The `block-base-python` hook refused the whole command before anything ran (`git diff --stat` afterwards was empty), and it was re-issued with node only.
- A record-building one-liner hit shell quoting. It was moved into a scratch node script; nothing was written by the failed attempt.

## Known Stubs

None.

## Threat Flags

None beyond the register:
- **T-37-96 (mitigated):** only a 4xx whose media type parses as `application/problem+json` is relayed, and only up to 64 KiB. A 5xx body never leaves the edge. Proven by `CoreFailuresStay502/core_500_problem` and arm B2'.
- **T-37-97 (mitigated):** status and results pass through, and the contract gate's check 6 holds the edge's published contract to core's. Arms A1 to A7 prove it.
- **T-37-SC:** nothing was installed.
- **New behaviour to note for review:** `Retry-After` from core is copied to the edge response. It is a header core already sends to its own callers.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- **For the orchestrator:**
  - RWO-017 and RWO-037 can be ticked for the edge half. The core half was 37-40.
  - Coverage D6 (live) is human or gate work. The compose runtime is still the 37-C build (d060d0af), behind the branch.
  - The 37-E gate must rebuild every image, then send through the edge (`:8089` path `/api/v1/sync/batch`) a mixed batch (expect 200 PARTIAL with results) and a batch naming an ungranted shop (expect 200 with a REJECTED `SHOP_ACCESS_DENIED` item), plus a token without `catalog:write` (expect core's 403 problem, byte-identical).
- **Behaviour change integrators will see through the edge:**
  - 200 instead of 202 for a processed batch.
  - A `results` array.
  - Core's 400, 401 and 403 problems instead of a 502.
  - Core's 429 with `Retry-After`.
- **Open item logged:** `k8s/scripts/check-env-contract.sh:210` cites stale `handlers.go` lines. This was pre-existing; see `deferred-items.md`.
- Ready for 37-42.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*

## Self-Check: PASSED

- 12/12 key files FOUND on disk (7 edge source/test files, 3 docs files, 2 RED records).
- 5/5 commits FOUND as ancestors of HEAD (6793441e 72cf4bab 9351a440 498709dc 5681f154). `gsd_run check evaluation-scope --plan 37-41 --commits-only`: status resolved, source plan-subjects, rc 0.
- TDD gate: 2 `test(37-41)` commits each before their `feat(37-41)`; no refactor needed.
- Acceptance criteria re-run: Task 1 RED then GREEN, with the 403 arm comparing body bytes (`bytes.Equal`); Task 2's contract test shown red with Results removed from the struct (A1).
- Plan verification: `go test ./...` green, with the RED records and the contract break arm recorded; vet and gofmt clean.
- Must-haves: `client.go` contains `application/problem+json`. The `Results` key-link pattern is absent from `handlers.go` by construction (Deviation 5) and proven behaviourally.
