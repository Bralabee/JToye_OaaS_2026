---
phase: 18-vendor-onboarding-first-slice
plan: 04
subsystem: onboarding
tags: [vendor-onboarding, gate-chain, companies-house, webclient, http-basic, circuit-breaker, resilience4j, business-verified, fail-closed]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice (plan 01)
    provides: OnboardingProperties.getCompaniesHouse() (base-url + redacted api-key), companies-house resilience4j breaker, VendorOnboarding.getCompanyNumber(), GateType.BUSINESS_VERIFIED
  - phase: 18-vendor-onboarding-first-slice (plan 02)
    provides: OnboardingGate interface + GateResult factories + GateChainRunner List<OnboardingGate> auto-registry
provides:
  - CompaniesHouseClient — WebClient GET /company/{number} with HTTP Basic (key as username), @CircuitBreaker(name=companies-house), 404->empty, fail-closed when key unconfigured
  - CompanyProfile — lenient record projection of company_number + company_status
  - CompaniesHouseGate — BUSINESS_VERIFIED gate (auto, mandatory); active->PASSED, sole-trader/404->WAIVED, dissolved->FAILED, failure/unconfigured->MANUAL_REVIEW; auto-registers by being a @Component bean
affects: [18-05-allergen-gate-and-go-live, 18-06-phase-closure]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Test-friendly WebClient client: production ctor builds the WebClient from config (baseUrl + Basic auth); a package-private ctor takes a WebClient.Builder so a unit test wires an in-memory ExchangeFunction stub while the class still owns baseUrl + the Basic auth header (no WireMock/MockWebServer, no new dependency)"
    - "exchangeToMono status routing: 404 -> Optional.empty (release body), other error -> createException()/error (breaker counts it, gate -> MANUAL_REVIEW), else parse body"
    - "Fail-closed compliance gate: an unconfigured API key throws BEFORE any network call so a MANDATORY gate degrades to MANUAL_REVIEW (human), never a silent WAIVE (auto-pass) or a doomed authenticated-as-nobody call"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/client/CompaniesHouseClient.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/client/CompanyProfile.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/gate/CompaniesHouseGate.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/client/CompaniesHouseClientTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/gate/CompaniesHouseGateTest.java
  modified: []

key-decisions:
  - "Only company_status == 'active' (case-insensitive) yields PASSED; a present-but-non-active status (dissolved etc.) -> FAILED; a blank/missing status or any client failure -> MANUAL_REVIEW (threat T-18-04-T: never treat a garbled/error response as a pass)"
  - "A sole trader (null/blank company number) short-circuits to WAIVED WITHOUT calling the client; a 404 (no record) also -> WAIVED — both keep sole traders onboardable rather than hard-failing them (research §6 human-fallback rule)"
  - "Fail closed on an unconfigured API key (Rule 2, unplanned but defensive): lookup() throws before any network call so BUSINESS_VERIFIED degrades to MANUAL_REVIEW rather than auto-WAIVING a mandatory gate or leaking a doomed 401 call — also keeps profile=test onboarding flows hermetic"
  - "No new gradle dependency: unit-tested with an in-memory ExchangeFunction stub + Mockito, both already on the classpath (threat T-18-04-SC — package-legitimacy gate not triggered)"

patterns-established:
  - "Concrete OnboardingGate slice: a client (external API + circuit breaker) + a gate (result mapping) + two pure unit tests; the gate plugs into GateChainRunner purely by being a @Component"

requirements-completed: [VOB-02]

# Metrics
duration: 14min
completed: 2026-07-11
---

# Phase 18 Plan 04: Companies House BUSINESS_VERIFIED Gate Summary

**The `BUSINESS_VERIFIED` onboarding gate: a circuit-broken `CompaniesHouseClient` that does an HTTP Basic (API key as username, empty password) `GET /company/{number}` lookup, and a `CompaniesHouseGate` that PASSES `active` companies (evidence + company number as external_ref), WAIVES sole traders / no-record, FAILS a dissolved company, and degrades to MANUAL_REVIEW on any client failure or an unconfigured key — auto-registering into the 18-02 gate chain purely by being a `@Component`, with zero runner edits and no new dependency.**

## Performance

- **Duration:** ~14 min
- **Completed:** 2026-07-11
- **Tasks:** 2 (both TDD)
- **Files:** 5 created (3 main, 2 test); **13 new Java `@Test` methods across 2 files** (6 client + 7 gate)

## Accomplishments
- **`CompaniesHouseClient`** (`@Component`) — builds a `WebClient` at `onboarding.companies-house.base-url` and sets HTTP Basic auth with the API key as the **username** and an empty password (research §6). `lookup(number)` does `GET /company/{number}` via `exchangeToMono`: a **404 returns `Optional.empty()`** (no record — the gate WAIVES), a non-404 error status propagates a typed `WebClientResponseException` (the breaker counts it, the gate → MANUAL_REVIEW), and a 2xx body parses to `CompanyProfile`. Guarded by `@CircuitBreaker(name = "companies-house")` and an explicit block timeout. **The API key is never logged** — only the company number + resulting status are.
- **`CompanyProfile`** — a lenient (`@JsonIgnoreProperties(ignoreUnknown = true)`) record mapping just `company_number` + `company_status`, tolerating the rest of the large provider payload.
- **`CompaniesHouseGate`** (`@Component implements OnboardingGate`) — `type()=BUSINESS_VERIFIED`, `isAutomatic()=true`, `mandatory()=true`. Mapping: null/blank company number → **WAIVED** (sole trader, client never called); `active` → **PASSED** (evidence `{company_status, company_number}`, external_ref = number); empty Optional (404) → **WAIVED**; a present non-active status → **FAILED** (reason names the status); a blank status or any client exception → **MANUAL_REVIEW**. Being a `@Component` is all it takes to auto-plug into the 18-02 `GateChainRunner List<OnboardingGate>` registry — **zero runner edits**.
- **Fail-closed hardening** — an unconfigured API key (the default in profile=`test`) makes `lookup()` throw **before any network call**, so a MANDATORY compliance gate degrades to MANUAL_REVIEW (human review) rather than silently WAIVING (auto-pass) or firing a doomed authenticated-as-nobody request. This also keeps profile=test onboarding flows hermetic (no real egress).
- **No new dependency** — both test classes use only what was already on the classpath: an in-memory `ExchangeFunction` stub (client) and Mockito (gate). No WireMock / MockWebServer.

## Task Commits

1. **Task 1 (RED):** `236ab6f` `test(18-04): add failing Companies House client test (RED)`
2. **Task 1 (GREEN):** `cea201a` `feat(18-04): Companies House client — HTTP Basic lookup + circuit breaker (GREEN)`
3. **Task 2 (RED):** `bd41767` `test(18-04): add failing CompaniesHouseGate mapping test (RED)`
4. **Task 2 (GREEN):** `0c1b131` `feat(18-04): CompaniesHouseGate — active->PASSED, sole-trader/404->WAIVED (GREEN)`
5. **Deviation (Rule 2):** `55d0893` `fix(18-04): Companies House client fails closed when API key unconfigured`

## Decisions Made
- **Only `active` passes; garbled/error → MANUAL_REVIEW, never a silent pass** (threat T-18-04-T). A blank/missing `company_status` is treated as inconclusive (MANUAL_REVIEW), not FAILED.
- **Sole traders & no-record → WAIVED, never FAILED** — the industry human-fallback rule (research §6); blank company number short-circuits without a client call.
- **Fail closed on an unconfigured key** (see Deviations) — a mandatory compliance gate must never auto-pass because a credential was forgotten.

## Deviations from Plan

### Adjustments (not scope changes)

**1. [Rule 2 - Defensive correctness] Client fails closed when the API key is unconfigured**
- **Found during:** Task 1 (analysing how the new mandatory gate behaves in profile=test, where `COMPANIES_HOUSE_API_KEY` is empty).
- **Issue:** With an empty key the plan-spec flow would fire a real, doomed `GET` that returns 401, leaking the request to the provider and (worse) offering no safe outcome for a *mandatory* gate if a key is simply forgotten in production.
- **Fix:** `lookup()` throws `IllegalStateException` before any network call when no key is configured; the gate's existing `catch` maps it to MANUAL_REVIEW (fail closed to human review). Added a 6th client unit test asserting no request is exchanged.
- **Files:** `CompaniesHouseClient.java`, `CompaniesHouseClientTest.java`. **Committed in:** `55d0893`.

**2. [Rule 3 - Adjustment] Gradle invocation matches the multi-project layout**
- The plan's `cd core-java && ./gradlew test ... -x checkstyleMain` does not apply here: the wrapper is at the repo root (multi-project build), there is no `checkstyleMain` task, and the build dir is `build-local`. Ran `./gradlew :core-java:test --tests '...CompaniesHouseClientTest' --tests '...CompaniesHouseGateTest'` from the repo root — same correction waves 1-2 documented. Invocation only; no file change.

## Integration Test Impact — HANDOFF TO 18-06 (closure)

Adding the **first automatic, mandatory `BUSINESS_VERIFIED` gate bean** changes the runtime behaviour of the 18-02 Testcontainers test `OnboardingSubmitIntegrationTest`. Confirmed by running `:core-java:integrationTest --tests 'uk.jtoye.core.onboarding.OnboardingSubmitIntegrationTest'`:

| Test | Result with this slice | Why |
|------|------------------------|-----|
| `createThenSubmitReadsBackVerifyingWithSubmittedAt` | **PASS** (now hermetic) | submit materialises a BUSINESS_VERIFIED PENDING row; async recompute → MANUAL_REVIEW (key unconfigured, fail-closed, no egress) → stays VERIFYING |
| `resubmitFromVerifyingReturns400` | **PASS** (now hermetic) | same — stays VERIFYING; 2nd submit is an illegal transition → 400 |
| `autoApproveTrueDrivesFullyPassingOnboardingToApproved` | **FAIL** | the test seeds a BUSINESS_VERIFIED **PASSED** row and calls `runAndRecompute`; the runner re-evaluates *every* automatic gate, so the real gate overwrites the seeded PASSED with MANUAL_REVIEW → never reaches APPROVED |
| `autoApproveFalseHaltsAtPendingApproval` | **FAIL** | same overwrite → never reaches PENDING_APPROVAL |

**Root cause:** `GateChainRunner.runAndRecompute` re-evaluates *all* automatic gates unconditionally, clobbering rows already in a terminal state. That was invisible while the registry was empty (18-02 shipped zero gate beans); it surfaces the instant a real automatic gate exists. **This is not fixable from within this plan's file scope** — the fix lives in shared files (`GateChainRunner.java` or `OnboardingSubmitIntegrationTest.java`), and the parallel-wave isolation rule (18-03/18-04/18-05 must not edit shared files) means the three concrete-gate slices all trigger this identically. It is a phase-level reconciliation for **18-06 closure**.

**Recommended 18-06 fix (pick one):**
1. **Runner (preferred, also a latent-bug fix):** in `runAndRecompute` step 1, only (re)evaluate a gate whose row is still `PENDING` — never clobber a terminal PASSED/FAILED/WAIVED row (this also protects future webhook-set gates like `AGREEMENT_SIGNED`). This makes the seed-based tests green again *and* is the correct semantics.
2. **Test:** add `@MockBean CompaniesHouseGate` (and the FHRS / allergen gates) to `OnboardingSubmitIntegrationTest`, or seed the gate rows the concrete gates own so recompute reproduces PASSED.

> Note: I deliberately did **not** create a shared `deferred-items.md` — three parallel worktrees writing it would add/add-conflict at merge. This SUMMARY (plan-scoped) is the reliable record for the closure agent.

## TDD Gate Compliance
- **Task 1** (`tdd="true"`): RED `236ab6f` (`test(...)`, compile-failed before `CompaniesHouseClient`/`CompanyProfile` existed) → GREEN `cea201a` (`feat(...)`).
- **Task 2** (`tdd="true"`): RED `bd41767` (`test(...)`, compile-failed before `CompaniesHouseGate` existed) → GREEN `0c1b131` (`feat(...)`).
- No unexpected early-green: both RED runs failed to compile (the SUT did not yet exist). No REFACTOR commits needed. RED preceded GREEN in git history for both tasks.

## Known Stubs
None. Both files are fully wired: the client makes a real circuit-broken HTTP call (proven with an in-memory exchange stub) and the gate maps all six outcomes (proven with Mockito).

## Threat Flags
None beyond the plan's `<threat_model>`. All four listed mitigations are implemented and tested:
- **T-18-04-I** (key leak / SSRF): base URL from config only; company number is a URL path var; the key is sourced from `OnboardingProperties` (redacted `toString`) and used only for the Basic auth header — never logged.
- **T-18-04-T** (non-active/garbled treated as pass): only `active` → PASSED; blank status / error → MANUAL_REVIEW; dissolved → FAILED (unit-proven).
- **T-18-04-D** (slow/erroring API blocks the chain): explicit block timeout + `@CircuitBreaker(name = "companies-house")`; runs on the `@Async` worker; degrades to MANUAL_REVIEW.
- **T-18-04-SC** (new package installs): none — in-memory `ExchangeFunction` stub + Mockito only; package-legitimacy gate not triggered.

## Next Phase Readiness
- The `BUSINESS_VERIFIED` gate is live and auto-registered — a submitted marketplace/white-label onboarding now materialises + evaluates it automatically.
- **18-05** wires the vendor go-live endpoint and the allergen gate; it can rely on the gate chain now containing a real automatic gate.
- **18-06 (closure)** must (a) apply the runner/test reconciliation above so `OnboardingSubmitIntegrationTest` is green with concrete gates present, and (b) reconcile `docs/metrics.json` via `scripts/docs-freshness.sh --write` — this plan adds **13 Java `@Test` methods across 2 files** (schema head unchanged at V43).

## Self-Check: PASSED
- All 5 created files verified present on disk (3 main + 2 test).
- All 5 commits (`236ab6f`, `cea201a`, `bd41767`, `0c1b131`, `55d0893`) verified in git history.
- `./gradlew :core-java:test --tests '...CompaniesHouseClientTest' --tests '...CompaniesHouseGateTest'` green (6 + 7 = 13 tests, 0 failures); full `:core-java:test` suite green (no context-wiring regression from the new beans).
- Grep confirms `@CircuitBreaker(name = "companies-house")` present, `getApiKey()` used only for the Basic auth header (never a log line), and zero `wiremock`/`mockwebserver` references.
- Integration impact on 18-02's seed-based tests confirmed empirically and handed off to 18-06 above (out of this plan's file scope).

---
*Phase: 18-vendor-onboarding-first-slice*
*Completed: 2026-07-11*
