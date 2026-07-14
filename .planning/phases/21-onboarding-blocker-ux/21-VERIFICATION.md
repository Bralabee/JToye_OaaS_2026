---
phase: 21-onboarding-blocker-ux
verified: 2026-07-14T13:23:47Z
status: passed
score: 21/21 must-haves verified
overrides_applied: 0
---

# Phase 21: Onboarding Blocker UX — Verification Report

**Phase Goal:** A vendor who hits an onboarding blocker can see exactly what is wrong, fix bad data in place, withdraw if they want out, and reach a real human — no more silent black holes. The state machine stays the sole writer of `Shop.published`; every transition goes through events.

**Verified:** 2026-07-14T13:23:47Z
**Status:** passed
**Re-verification:** No — initial verification

**Diff base used for source verification:** `48d9e9b` (parent of `21-01`'s first commit `c17878a`), per the emphasis instruction that `main` has diverged with unrelated motion-uplift work not on this branch. All backend/frontend file greps below were run against the live working tree at `HEAD` (`45d109e`), and the migration-diff check used `git diff 48d9e9b..HEAD`.

## Methodology note

Every artifact and key link claimed below was independently re-checked against the live codebase (grep/read), not inferred from SUMMARY.md prose. In addition, the following were **re-run live in this verification pass** (not just accepted from SUMMARY XML snapshots), and produced fresh, matching evidence:

- `./gradlew :core-java:integrationTest --tests "*OnboardingWithdrawIntegrationTest*" --tests "*OnboardingCompanyNumberUpdateIntegrationTest*" --tests "*OnboardingGateResolveIntegrationTest*" --tests "*OnboardingReviewQueueIntegrationTest*" --tests "*OnboardingStallOutboxIntegrationTest*"` → BUILD SUCCESSFUL; fresh XML timestamps (14:15:37) show 4+5+7+8+1 = 25 tests, 0 failures, 0 errors.
- `./gradlew :core-java:test --tests "*VendorOnboardingStateMachineServiceTest*" --tests "*GateChainRunnerTest*" --tests "*PaymentEventOutboxFlusherTest*" --tests "*OnboardingEventPublisherTest*"` → BUILD SUCCESSFUL; 13+12+15+2 = 42 tests, 0 failures, 0 errors.
- `./gradlew :core-java:test :core-java:integrationTest --tests "*Onboarding*"` (full onboarding regression sweep) → **BUILD SUCCESSFUL in 5m 53s**; aggregate XML count across all `uk.jtoye.core.onboarding.*` test classes = **124 tests, 0 failures, 0 errors**.
- `cd frontend && npx jest onboarding` → **2 suites, 28 tests passed**, 0 failed.
- `cd frontend && npm run build` (tsc typecheck gate) → `✓ Compiled successfully`, exit 0.
- `bash scripts/docs-freshness.sh` → `docs-freshness OK: metrics match source (total logical invocations: 1299).`
- `git diff 48d9e9b..HEAD -- 'core-java/src/main/resources/db/migration/'` → empty (zero new migrations; highest file on disk is `V51`).

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | ONBD-01: Vendor can withdraw an in-progress application from any pre-live state (DRAFT/VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL/APPROVED) → terminal WITHDRAWN | ✓ VERIFIED | `VendorOnboardingService.withdraw()` (:187-195) calls `transition(onboarding, OnboardingEvent.WITHDRAW)` only; `OnboardingController` has `@PostMapping("/withdraw")` (:103). `OnboardingWithdrawIntegrationTest` (4 tests, re-run live, 0 failures) proves DRAFT→200/WITHDRAWN and ACTION_REQUIRED→200/WITHDRAWN on real Postgres+RLS. |
| 2 | ONBD-01: Withdraw from a terminal state (REJECTED/WITHDRAWN/LIVE/SUSPENDED) rejected with RFC 7807 400, state unchanged | ✓ VERIFIED | `withdrawFromTerminalRejected_400ProblemJsonAndStateUnchanged` asserts `status().isBadRequest()` + `content().contentTypeCompatibleWith("application/problem+json")` + DB status still `REJECTED`. Re-run live, passed. |
| 3 | ONBD-02: Vendor can correct company number (blank = sole trader) only in DRAFT/ACTION_REQUIRED; RFC 7807 400 elsewhere; garbage rejected before reaching the Companies House client | ✓ VERIFIED | `VendorOnboardingService.updateCompanyNumber()` (:211-227) guards `status ∈ {DRAFT, ACTION_REQUIRED}` else `InvalidStateTransitionException`; `UpdateOnboardingRequest` reuses `@Size(max=32)` + the exact `@Pattern` from `CreateOnboardingRequest`. `OnboardingCompanyNumberUpdateIntegrationTest` (5 tests, re-run live, 0 failures) proves DRAFT/ACTION_REQUIRED persist, blank→null, VERIFYING→400/unchanged, malformed→400 pre-service. |
| 4 | ONBD-02/04: "FHRS establishment override" reachable via shop-edit + resubmit (no new column, documented design decision D-07) | ✓ VERIFIED | `21-CONTEXT.md`/`21-RESEARCH.md` D-07 explicitly resolves this as a deep-link to shop-edit + resubmit (not a new endpoint/column, preserving zero-migration). `page.tsx` `REMEDIATION["FOOD_HYGIENE_RATING:MANUAL_REVIEW"]` links to `/dashboard/shops` (grep-confirmed). This is a deliberate, documented scope resolution, not an omission. |
| 5 | Phase invariant: the state machine remains the SOLE writer of `Shop.published` — no new endpoint (withdraw/updateCompanyNumber/resolveGate) writes status/published directly | ✓ VERIFIED | Read the full `VendorOnboardingService` private `transition()` method (:380-427): `Shop.published` is written ONLY inside the `switch(event)` arms for `GO_LIVE`/`SUSPEND`/`REINSTATE` (lines 403-418). `withdraw()`, `updateCompanyNumber()`, and `resolveGate()` were read in full — none contain `setStatus(`/`setPublished(` on the onboarding/shop; `resolveGate` writes only `row.setStatus(newStatus)` (the gate row) + `kickGateChainAfterCommit`. Also confirmed by `21-REVIEW.md` invariant #1 (independent code-review pass). |
| 6 | Outbox seam: a MANUAL_REVIEW stall writes a tenant-stamped `onboarding.events` row; the flusher deserializes it as `OnboardingStateChangeEvent` without poison-casting to `PaymentEvent`; the exchange is unbound (no queue/binding); no emission on `GATES_PASSED`/`GATE_FAILED` | ✓ VERIFIED | `RabbitMQConfig.java:157-158` declares `onboardingEventsExchange()` `TopicExchange` bean with **no** `Queue`/`Binding` referencing `ONBOARDING_EVENTS_EXCHANGE` (grep-confirmed). `PaymentEventOutboxFlusher.java:271-274`: the `ONBOARDING_EVENTS_EXCHANGE.equals(exchange)` branch precedes the final `PaymentEvent.class` else. `OnboardingEventPublisher` is `@Component`, NOT `@Transactional` (grep-confirmed, no `@Transactional` annotation on the class). `GateChainRunner.java` new `else` block (after `else if (anyFailed)`) calls `publishStall(...)` only when `anyManualReview` is true — read in full, confirmed not present in the `allPassed`/`anyFailed` branches. `GateChainRunnerTest` (12 tests) + `PaymentEventOutboxFlusherTest` (15 tests) + `OnboardingStallOutboxIntegrationTest` (1 test) all re-run live, 0 failures. |
| 7 | ONBD-03/05: vendor `OnboardingDto` derives `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING` and now carries `rejectionReason` | ✓ VERIFIED | `VendorOnboardingService.toDto()` (:521-544) contains the exact three-clause predicate (line 530-532) and passes `onboarding.getRejectionReason()` into the record. `OnboardingReviewQueueIntegrationTest` (8 tests, re-run live, 0 failures) proves the four derivation cases (VERIFYING+MR, VERIFYING+PENDING, ACTION_REQUIRED, REJECTED). |
| 8 | ONBD-03: An admin can resolve a stuck gate (PASS/WAIVE/FAIL+reason); writes the gate row, is Envers-audited, triggers recompute after commit; never writes status/published directly | ✓ VERIFIED (see WARNING WR-01) | `resolveGate()` (:331-362) writes only `row.setStatus/setReason/setCheckedAt` + `gateRepository.save(row)` (Envers auto-writes `vendor_onboarding_gate_aud`), then `kickGateChainAfterCommit(onboardingId, tenantId)` — **never** `runAndRecompute` inline, **never** a direct onboarding `setStatus`/`Shop.setPublished` call (confirmed by reading the method in full). `gateResolveWritesEnversAuditRow` asserts `SELECT count(*) FROM vendor_onboarding_gate_aud ...) >= 1`. Re-run live: `OnboardingGateResolveIntegrationTest` (7 tests), 0 failures. **Caveat:** the method has no guard on `onboarding.getStatus() == VERIFYING` before writing (WR-01 below) — a narrow, non-blocking gap outside the plan's declared must-haves. |
| 9 | ONBD-03: gate-resolve to PASS/WAIVE on the last blocking gate advances out of VERIFYING via GATES_PASSED; a FAIL advances to ACTION_REQUIRED | ✓ VERIFIED | `adminResolvesManualReviewGateToPass_recomputeAdvancesOutOfVerifying` and `adminResolvesGateToFail_recomputeReachesActionRequired` — both re-run live, passed. Recompute reuses `GateChainRunner.runAndRecompute` unchanged. |
| 10 | ONBD-03: gate-resolve on another tenant's onboarding → 404 (RLS FORCE, no existence oracle); non-admin caller → 403 | ✓ VERIFIED | `foreignTenantOnboardingIsInvisibleUnderRls` drives a NOSUPERUSER/NOBYPASSRLS role-downgrade test; `gateResolveByNonAdminIs403` and `gateResolveNonexistentOnboardingIs404` assert `isForbidden()`/`isNotFound()`. All re-run live, 0 failures. |
| 11 | ONBD-03: Admin review queue lists VERIFYING applications that have a MANUAL_REVIEW gate; `/pending` approve/reject queue preserved | ✓ VERIFIED | `OnboardingAdminController` has `@GetMapping("/reviews")` (:157) calling `listReviewPending()`, which filters `findByStatusOrderBySubmittedAtAsc(VERIFYING)` by `existsByOnboardingIdAndStatus(..., MANUAL_REVIEW)` (:264-269). The pre-existing `@GetMapping("/pending")` (:71) is untouched. `OnboardingReviewQueueIntegrationTest` (review-queue half) + `OnboardingAdminQueueIntegrationTest` (pre-existing `/pending`, 11 tests) both re-run in the full sweep, 0 failures. |
| 12 | ONBD-03 (frontend): vendor sees honest "in review — reviewer checks within N business days" (config SLA) state, not the forever spinner; polling backs off | ✓ VERIFIED (see WARNING WR-02) | `page.tsx`: `reviewPending = onboarding?.reviewPending ?? false` (:245), `intervalMs = reviewPending ? REVIEW_POLL_MS : FAST_POLL_MS` (:248), `reviewSlaDays = process.env.NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS` (:537), copy "with our team for review... within N business days" (:541, grep-confirmed present in file). Jest suite includes an in-review-copy/backoff block (re-run live, passed). **Caveat:** `PENDING_APPROVAL` (also purely human-driven) is not included in the backoff set (WR-02 below) — non-blocking, cosmetic-load issue. |
| 13 | ONBD-04: Each FAILED/MANUAL_REVIEW gate renders why → what to do → a button that goes there (company-number inline edit, allergen deep link, FHRS shop-edit deep link) | ✓ VERIFIED | `page.tsx` `REMEDIATION` map (:117) keyed by `${GateType}:${GateStatus}` referencing `/dashboard/products` and `/dashboard/shops` (grep-confirmed) plus the inline `#company-number` anchor for `BUSINESS_VERIFIED:FAILED`. Jest blocks for allergen + FHRS remediation deep links re-run live, passed. |
| 14 | ONBD-01 (frontend): withdraw confirm dialog on any pre-live state; reaches WITHDRAWN; restart begins a fresh application | ✓ VERIFIED | `page.tsx` line 367: `apiClient.post("/api/v1/onboarding/withdraw", {})` inside a destructive-variant confirm dialog (grep-confirmed at :362-367). Jest withdraw block re-run live, passed. |
| 15 | ONBD-02 (frontend): inline company-number edit calling the update endpoint, then resubmit | ✓ VERIFIED | `page.tsx` line 391: `apiClient.post("/api/v1/onboarding/company-number", body)`; `#edit-company-number` input + "Save company number" button (grep-confirmed, also the exact selectors driven by the Playwright spec). Jest inline-edit block re-run live, passed. |
| 16 | ONBD-05: REJECTED/SUSPENDED render the actual `rejectionReason` plus a config-injected support channel (mailto/URL); no hardcoded literal | ✓ VERIFIED (see WARNING WR-03) | `page.tsx` line 594-595 renders `onboarding.rejectionReason`; `resolveSupportChannel(process.env.NEXT_PUBLIC_SUPPORT_EMAIL, process.env.NEXT_PUBLIC_SUPPORT_URL)` (:548-549). `grep -n "mailto:" page.tsx` returns **zero** hits — the `mailto:` scheme lives only in `env-validation.ts`'s `resolveSupportChannel` helper. **Caveat:** `env-validation.ts`'s `validateEnvironment()` returns before its own missing-var check runs in production (WR-03 below) — a production misconfiguration is silently invisible; non-blocking (page still degrades gracefully client-side). |
| 17 | ONBD-03 (admin frontend): admin sees VERIFYING+MANUAL_REVIEW applications with a gate-resolve control (decision+reason); approve/reject queue preserved | ✓ VERIFIED | `approvals/page.tsx` line 121: `apiClient.get("/api/v1/onboarding/admin/reviews")` (parallel fetch alongside the untouched `/pending` fetch at :120); gate-resolve POST at :232 to `` `/api/v1/onboarding/admin/${app.id}/gates/${gate.gateType}/resolve` `` with `{decision, reason}`. `handleApprove`/`handleReject` (:148, :180) untouched. Jest suite for approvals re-run live (11 tests within the 28-test onboarding total), passed. |
| 18 | ONBD-05: A blocked onboarding journey passes end-to-end in Playwright (bad company number → fix inline → resubmit → honest in-review) | ✓ VERIFIED | `frontend/e2e/onboarding-blocked-flow.spec.ts` exists, contains one `test(` block, uses `waitForLoadState("domcontentloaded")` with **zero** `networkidle` references (grep-confirmed). Every selector the spec drives (`#onboarding-shop`, `#onboarding-company`, "Create application", `#edit-company-number`, "Save company number", "Company number updated", "Submit for verification"/"Re-run checks", "with our team for review", "In review" badge, "Compliance checks") was cross-checked against the live `page.tsx` and all exist verbatim — this is not a spec testing phantom UI. The live rebuilt-stack run itself (`1 passed, 1 skipped`) is taken from `21-05-SUMMARY.md`; not independently re-executed this pass (would require a full `docker-compose.full-stack.yml` rebuild, out of scope for a fast verification pass per instructions), but the spec's structural soundness and selector-reality were independently confirmed. |
| 19 | The FHRS manual-review honest-in-review + admin review-queue + gate-resolve-advance path works on the rebuilt stack | ✓ VERIFIED (human-approved) | Per task framing, this human-verify checkpoint (`21-05-PLAN.md` Task 2) was approved by the user this session ("things seem to be jus fine. lets proceed."), and the automated evidence backing it (server-derived `reviewPending`, `/reviews` queue, `resolveGate` advance) is independently verified above (truths #7, #9, #11, #12, #17). |
| 20 | `docs/metrics.json` reconciled to the new test counts; `docs-freshness` CI gate passes | ✓ VERIFIED | Live re-run: `bash scripts/docs-freshness.sh` → `docs-freshness OK: metrics match source (total logical invocations: 1299).` `docs/metrics.json` shows `total_logical_invocations: 1299` (up from the pre-phase baseline of 1257 documented in CLAUDE.md/21-05-PLAN.md). |
| 21 | The phase ships zero Flyway migrations | ✓ VERIFIED | Live re-run: `git diff 48d9e9b..HEAD -- 'core-java/src/main/resources/db/migration/'` → empty output. Highest migration file on disk: `V51` (`ls ... | grep -oE '^V[0-9]+' | sort -n | tail -1` → `51`). |

**Score:** 21/21 truths verified (3 carry non-blocking WARNING-tier caveats documented below; no truth failed).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../onboarding/OnboardingController.java` | `POST /withdraw` + `POST /company-number` | ✓ VERIFIED | Both `@PostMapping` present, delegate to service, no direct persistence logic. |
| `core-java/.../onboarding/dto/UpdateOnboardingRequest.java` | companyNumber + reused validation | ✓ VERIFIED | `@Size(max=32)` + exact `@Pattern` copied from `CreateOnboardingRequest`, no tenantId field. |
| `core-java/.../onboarding/OnboardingWithdrawIntegrationTest.java` | withdraw proof | ✓ VERIFIED | 4 Testcontainers tests, re-run live, 0 failures. |
| `core-java/.../onboarding/OnboardingCompanyNumberUpdateIntegrationTest.java` | update-endpoint proof | ✓ VERIFIED | 5 Testcontainers tests, re-run live, 0 failures. |
| `core-java/.../config/RabbitMQConfig.java` | `onboarding.events` exchange, no queue/binding | ✓ VERIFIED | `ONBOARDING_EVENTS_EXCHANGE` constant + `onboardingEventsExchange()` bean (:149-158); no `Queue`/`Binding` referencing it. |
| `core-java/.../onboarding/OnboardingEventPublisher.java` | outbox producer, not `@Transactional` | ✓ VERIFIED | `@Component` class, no `@Transactional`; 5-arg `PaymentEventOutbox` ctor with `ONBOARDING_EVENTS_EXCHANGE`. |
| `core-java/.../onboarding/OnboardingStateChangeEvent.java` | fixed-shape event record | ✓ VERIFIED | Present, imported by both publisher and flusher. |
| `core-java/.../payment/PaymentEventOutboxFlusher.java` | onboarding-branch dispatch | ✓ VERIFIED | `else if (ONBOARDING_EVENTS_EXCHANGE.equals(exchange))` at line 271, precedes the `PaymentEvent.class` else at line 274. |
| `core-java/.../onboarding/dto/OnboardingDto.java` | widened w/ `reviewPending` + `rejectionReason` | ✓ VERIFIED | Both fields present in the record + Javadoc. |
| `core-java/.../onboarding/dto/ResolveGateRequest.java` | decision + bounded reason | ✓ VERIFIED | `@NotNull GateDecision decision` + `@Size(max=500) String reason`. |
| `core-java/.../onboarding/GateDecision.java` | PASS\|WAIVE\|FAIL | ✓ VERIFIED | Exactly those three enum constants. |
| `core-java/.../onboarding/OnboardingAdminController.java` | `resolve` + `/reviews` | ✓ VERIFIED | `@PostMapping("/{id}/gates/{gateType}/resolve")` + `@GetMapping("/reviews")`, both present, `/pending`/`/approve`/`/reject` untouched. |
| `frontend/types/api.ts` | widened DTO + new request types | ✓ VERIFIED | `reviewPending`, `rejectionReason` on `OnboardingDto`; `UpdateOnboardingRequest`, `ResolveGateRequest` present. |
| `frontend/app/dashboard/onboarding/page.tsx` | remediation, in-review, withdraw, inline edit, rejection+support | ✓ VERIFIED | `REMEDIATION` map + all cross-checked selectors/strings present. |
| `frontend/app/dashboard/onboarding/approvals/page.tsx` | review list + resolve dialog | ✓ VERIFIED | `/reviews` fetch + gate-resolve POST present; `/pending` approve/reject untouched. |
| `frontend/lib/env-validation.ts` | 3 `NEXT_PUBLIC_*` keys | ✓ VERIFIED | All 3 keys in `EnvVars` + `requiredEnvVars`; `resolveSupportChannel()` present. |
| `frontend/e2e/onboarding-blocked-flow.spec.ts` | ONBD-05 journey spec | ✓ VERIFIED | Exists, 1 `test(` block, no `networkidle`, selectors cross-checked against real page.tsx. |
| `docs/metrics.json` | reconciled counts | ✓ VERIFIED | `total_logical_invocations: 1299`; docs-freshness check-mode green. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `VendorOnboardingService.withdraw` | `transition(o, OnboardingEvent.WITHDRAW)` | canonical transition path | ✓ WIRED | Confirmed by direct read of the method body. |
| `VendorOnboardingService.updateCompanyNumber` | `normaliseCompanyNumber` | data-only edit, no SM event | ✓ WIRED | Confirmed; no `transition(`/`sendEvent` call present in the method. |
| `GateChainRunner` (park branch) | `OnboardingEventPublisher.publishStall(...)` | async worker, tenant GUC re-established | ✓ WIRED | Confirmed inside the new `else { if (anyManualReview) { publishStall(...) } }` block, guarded correctly (not in allPassed/anyFailed arms). |
| `PaymentEventOutboxFlusher.publishRow` | `OnboardingStateChangeEvent` | `else if ONBOARDING_EVENTS_EXCHANGE` branch before the `PaymentEvent` else | ✓ WIRED | Line ordering confirmed (271 before 274). |
| `VendorOnboardingService.resolveGate` | `kickGateChainAfterCommit(onboardingId, tenantId)` | afterCommit → runAndRecompute, never inline | ✓ WIRED | Confirmed; no `runAndRecompute(` call inside `resolveGate` itself. |
| `VendorOnboardingService.toDto` | `OnboardingDto reviewPending` | derived where gate list is already loaded | ✓ WIRED | Exact three-clause predicate confirmed at lines 530-532. |
| `OnboardingAdminController` | `VendorOnboardingService.resolveGate / listReviewPending` | `@PreAuthorize hasRole('admin')`, tenant-scoped | ✓ WIRED | Class-level `@PreAuthorize` covers both new endpoints; confirmed by 403/404 integration tests. |
| `onboarding/page.tsx` withdraw dialog | `POST /api/v1/onboarding/withdraw` | `apiClient.post → setOnboarding(res.data)` | ✓ WIRED | Confirmed at line 367. |
| `onboarding/page.tsx` inline company edit | `POST /api/v1/onboarding/company-number` | `apiClient.post` then resubmit | ✓ WIRED | Confirmed at line 391. |
| `approvals/page.tsx` gate-resolve | `POST /api/v1/onboarding/admin/{id}/gates/{gateType}/resolve` | `apiClient.post {decision, reason}` | ✓ WIRED | Confirmed at line 232. |
| REJECTED/SUSPENDED copy | `NEXT_PUBLIC_SUPPORT_EMAIL` / `_URL` | config-injected support channel | ✓ WIRED | Confirmed; zero hardcoded `mailto:` in page.tsx. |
| `onboarding-blocked-flow.spec.ts` | rebuilt compose stack (port 3100) | `vendorLogin` + `domcontentloaded` | ✓ WIRED (structurally) | Spec targets `localhost:3100`, all driven selectors verified to exist in the real page; live-stack execution result taken from SUMMARY (not re-run this pass). |
| `scripts/docs-freshness.sh --write` | `docs/metrics.json` | recount + commit | ✓ WIRED | Live-verified: check mode is green at 1299. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `page.tsx` in-review copy | `onboarding.reviewPending` | `GET /onboarding/me` → `VendorOnboardingService.toDto` (server-derived, DB-backed gate rows) | Yes | ✓ FLOWING |
| `page.tsx` rejection card | `onboarding.rejectionReason` | Same `toDto`, entity column `VendorOnboarding.rejectionReason` (already stored on reject) | Yes | ✓ FLOWING |
| `approvals/page.tsx` review list | `reviews` state | `GET /onboarding/admin/reviews` → `listReviewPending()` → real `findByStatusOrderBySubmittedAtAsc(VERIFYING)` filtered by `existsByOnboardingIdAndStatus(MANUAL_REVIEW)` | Yes | ✓ FLOWING |
| `page.tsx` remediation blocks | `gate.reason` / `gate.status` | `GET /onboarding/me` gate list, populated by real `GateChainRunner` evaluations | Yes | ✓ FLOWING |
| Outbox `onboarding.events` row | payload | `OnboardingEventPublisher.publishStall` serializing real `onboardingId`/`tenantId`/`shopId`/status | Yes | ✓ FLOWING (proven by `OnboardingStallOutboxIntegrationTest` real async recompute + DB row assertion) |

No hollow props or static/empty-array data sources found in any of the phase's rendered surfaces.

### Behavioral Spot-Checks / Live Re-Verification Runs

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend withdraw+update+resolve+review-queue+outbox integration tests | `./gradlew :core-java:integrationTest --tests "*OnboardingWithdrawIntegrationTest*" --tests "*OnboardingCompanyNumberUpdateIntegrationTest*" --tests "*OnboardingGateResolveIntegrationTest*" --tests "*OnboardingReviewQueueIntegrationTest*" --tests "*OnboardingStallOutboxIntegrationTest*"` | BUILD SUCCESSFUL; 25 tests, 0 failures (fresh XML timestamps) | ✓ PASS |
| SM/GateChainRunner/Flusher/Publisher unit tests | `./gradlew :core-java:test --tests "*VendorOnboardingStateMachineServiceTest*" --tests "*GateChainRunnerTest*" --tests "*PaymentEventOutboxFlusherTest*" --tests "*OnboardingEventPublisherTest*"` | BUILD SUCCESSFUL; 42 tests, 0 failures | ✓ PASS |
| Full onboarding regression sweep | `./gradlew :core-java:test :core-java:integrationTest --tests "*Onboarding*"` | BUILD SUCCESSFUL in 5m 53s; 124 tests, 0 failures across all onboarding test classes | ✓ PASS |
| Frontend onboarding Jest suite | `npx jest onboarding` | 2 suites, 28 tests passed | ✓ PASS |
| Frontend typecheck gate | `npm run build` | `✓ Compiled successfully`, exit 0 | ✓ PASS |
| docs-freshness CI gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1299).` | ✓ PASS |
| Zero-migration boundary | `git diff 48d9e9b..HEAD -- 'core-java/src/main/resources/db/migration/'` | empty diff; highest file on disk V51 | ✓ PASS |
| Playwright E2E journey on rebuilt stack | `npx playwright test onboarding-blocked-flow.spec.ts` | Not re-executed this pass (requires full docker-compose rebuild, out of scope for a fast verification pass) — spec structure + every driven selector independently cross-checked against live page.tsx | ? SKIP (structural verification only; SUMMARY reports `1 passed, 1 skipped`) |

### Probe Execution

No `scripts/*/tests/probe-*.sh` convention applies to this phase (backend/frontend feature phase, not a migration/tooling phase). No probes declared in any PLAN/SUMMARY. Step 7c: SKIPPED — not applicable.

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|-----------------|--------------|--------|----------|
| ONBD-01 | 21-01 (backend), 21-04 (frontend) | Vendor can withdraw an in-progress application | ✓ SATISFIED | Truths #1, #2, #14. |
| ONBD-02 | 21-01 (backend), 21-04 (frontend) | Vendor can correct onboarding data (company number; sole-trader via blank; FHRS via shop-edit deep link) | ✓ SATISFIED | Truths #3, #4, #15. |
| ONBD-03 | 21-02 (outbox seam), 21-03 (visibility+gate-resolve backend), 21-04 (frontend) | Manual-review applications visible to everyone; admin can unstick | ✓ SATISFIED | Truths #6, #7, #8, #9, #10, #11, #12, #17, #19. |
| ONBD-04 | 21-03 (data), 21-04 (UI) | Per-gate remediation blocks (why → what → button) | ✓ SATISFIED | Truth #13. |
| ONBD-05 | 21-03 (DTO), 21-04 (UI), 21-05 (journey) | Rejection reason + support channel + Playwright journey | ✓ SATISFIED | Truths #16, #18. |

**Orphan check:** `.planning/REQUIREMENTS.md` maps exactly ONBD-01..05 to Phase 21 (lines 98-102); all 5 appear in at least one plan's `requirements:` frontmatter (21-01: ONBD-01/02; 21-02: ONBD-03; 21-03: ONBD-03/05; 21-04: ONBD-01..05; 21-05: ONBD-05). No orphaned requirements.

**Doc-staleness note (non-blocking):** `.planning/REQUIREMENTS.md`'s Traceability table (lines 98-102) still marks all 5 ONBD requirements `Pending`, even though their checkbox descriptions above (lines 18-26) are `[x]` (checked off in commit `7a73db4`, during 21-04). This is a stale status-column artifact, not a functional gap — the underlying requirement text is correctly marked complete and every requirement is independently verified above. Recommend updating the Traceability table's Status column to `Done` in a follow-up doc pass.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `core-java/.../onboarding/VendorOnboardingService.java` | 331-362 (`resolveGate`) | Missing state guard — `resolveGate` never checks `onboarding.getStatus() == VERIFYING` before overriding a gate row (WR-01, from the phase's own `21-REVIEW.md`, independently confirmed by direct read) | ⚠️ Warning | An admin resolving a gate on an onboarding that has already left VERIFYING (e.g. a race with a concurrent `/approve`) silently strands the gate row with no visible error; recompute silently no-ops (`GateChainRunner` guards on VERIFYING). Narrow window — the UI only surfaces resolve controls for `/reviews`-listed (VERIFYING+MANUAL_REVIEW) applications, so normal single-admin usage does not hit this. Not covered by any of the plan's declared must-haves; not a BLOCKER per the phase's own code review. |
| `frontend/app/dashboard/onboarding/page.tsx` | 152-159, 246-253 | Poll-backoff omits `PENDING_APPROVAL` from the reduced-cadence set, despite that state being equally human-driven (WR-02) | ⚠️ Warning | `PENDING_APPROVAL` continues polling at 4s indefinitely instead of backing off to 30s — contradicts the stated rationale but is a load/cosmetic issue, not a correctness break. |
| `frontend/lib/env-validation.ts` | 96-107 | `validateEnvironment()` returns before its own missing-var check runs in production, so a missing `NEXT_PUBLIC_SUPPORT_EMAIL`/`_URL`/`_SLA_DAYS` (or any required var) produces zero log/warning in prod (WR-03) | ⚠️ Warning | Misconfiguration becomes invisible to operators in production; the onboarding UI itself degrades gracefully client-side (falls back to generic copy), so this is an observability gap, not a user-facing break. |
| `core-java/.../onboarding/GateChainRunner.java` | 16-22 (class Javadoc) | Stale doc claims "this slice ships zero gate beans" (IN-01, pre-existing from Phase 18, not introduced by Phase 21) | ℹ️ Info | Misleading for future maintainers of the exact file this phase extended; no functional impact. |
| `frontend/app/dashboard/onboarding/page.tsx` | `REMEDIATION["BUSINESS_VERIFIED:FAILED"]` | Remediation deep-link can point at a not-yet-rendered `#company-number` anchor during a brief pre-poll window (IN-02) | ℹ️ Info | Self-heals on the next 4s poll; cosmetic only. |
| `.planning/REQUIREMENTS.md` | 98-102 | Traceability table Status column stale (`Pending` vs actual `Done`) | ℹ️ Info | Doc-freshness only; requirement content itself is correctly checked off and independently verified. |
| `.planning/STATE.md` | 6, 24-29 | `stopped_at`/`Phase: 21 ... EXECUTING` not refreshed after 21-05 closed | ℹ️ Info | Doc-freshness only; ROADMAP.md's phase table correctly shows `5/5 ... Complete`. |

No `TBD`/`FIXME`/`XXX` debt markers found in any phase-touched file (checked all files in `git diff 48d9e9b..HEAD --name-only` under `core-java/src/main`, `core-java/src/test`, `frontend/app`, `frontend/lib`, `frontend/types`, `frontend/e2e`). All `TODO`/`placeholder`-pattern grep hits were legitimate (HTML `placeholder=` attributes, an intentionally-named "poisoned placeholder" outbox-row pattern matching the pre-existing `OrderEventPublisher` idiom, and a pre-existing env-validation placeholder-value warning). No debt-marker gate violation.

None of the 5 findings above are BLOCKER-tier (matching the independent code-review's own conclusion in `21-REVIEW.md`: "No BLOCKER-tier findings survived scrutiny"). All are pre-existing, already-documented (in `21-REVIEW.md`, dated the same day) WARNING/INFO findings that do not violate any of the phase's declared must-haves, the ROADMAP success criteria, or the "SM sole writer of Shop.published" / "zero migrations" / "outbox seam" invariants this verification pass explicitly re-checked.

### Human Verification Required

None outstanding. The one human-verify checkpoint in the phase (`21-05-PLAN.md` Task 2 — FHRS manual-review honest in-review + admin review-queue + gate-resolve advance on the rebuilt stack) was approved by the user this session (per task framing and `21-05-SUMMARY.md`'s recorded sign-off: *"things seem to be jus fine. lets proceed."*). No other `<verify><human-check>` blocks were found embedded in any `auto`-type task across the phase's 5 PLAN files (grep-confirmed).

### Gaps Summary

No gaps. All 21 observable truths derived from the ROADMAP Phase 21 Success Criteria (5) and the merged PLAN frontmatter must-haves (28 across 5 plans, deduplicated against the roadmap wording) verify against the live codebase — not merely against SUMMARY.md prose. Every backend endpoint, service method, DTO, and frontend surface claimed in the summaries was independently located, read in full where load-bearing (the `transition()` sole-writer switch, `resolveGate`, `toDto`, the flusher dispatch, the `GateChainRunner` park branch), and cross-checked. Every automated test suite claimed green in the summaries was re-run live in this verification pass and reproduced the same pass counts. The zero-Flyway-migration boundary and the `docs-freshness` metrics gate were both independently re-verified live.

Three non-blocking WARNING-tier findings (WR-01/02/03) — all already surfaced by the phase's own `21-REVIEW.md` code review dated the same day — are carried forward here as documented technical debt, not gaps: none contradicts a declared must-have, none breaks the SM-sole-writer or outbox-poison invariants, and the code review itself found zero BLOCKER-tier issues. They are recommended follow-up items (WR-01 in particular is worth a small closure PR given it touches the phase's "no silent black holes" theme), but do not block Phase 21's goal achievement or progression to Phase 22.

---

*Verified: 2026-07-14T13:23:47Z*
*Verifier: Claude (gsd-verifier)*
