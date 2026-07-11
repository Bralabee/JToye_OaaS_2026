---
phase: 18-vendor-onboarding-first-slice
plan: 03
subsystem: onboarding
tags: [vendor-onboarding, fhrs, food-hygiene, gate, webclient, circuit-breaker, resilience4j, manual-review, tdd]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice (plan 01)
    provides: OnboardingProperties.getFhrs() (base-url/min-rating=2/api-version=2) + fhrs resilience4j circuit breaker + GateType.FOOD_HYGIENE_RATING/GateStatus
  - phase: 18-vendor-onboarding-first-slice (plan 02)
    provides: OnboardingGate interface + GateResult record + GateChainRunner List<OnboardingGate> registry (adds a gate by adding a bean, zero runner edits)
provides:
  - FhrsClient — WebClient FSA /Establishments lookup with mandatory x-api-version:2 header + @CircuitBreaker(name=fhrs) + 5s block timeout
  - FhrsEstablishment — lenient-Jackson record mapping FSA FHRSID/RatingValue/SchemeType
  - FhrsGate — @Component OnboardingGate (FOOD_HYGIENE_RATING, automatic, mandatory) mapping rating -> PASSED/FAILED/MANUAL_REVIEW against config-driven min-rating
  - The first concrete gate bean — the GateChainRunner registry is now non-empty
affects: [18-06-phase-closure]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "In-memory WebClient testing: build the client over a stub ExchangeFunction (Mono.just(cannedResponse)) to capture the outgoing ClientRequest + assert headers/params and feed canned JSON — zero new HTTP-mock dependency"
    - "Constructor-injected WebClient.Builder (auto-configured bean) rather than the static WebClient.builder() so the client is unit-testable over a stub exchange function"
    - "Never-hard-fail-on-ambiguity: a fuzzy/no/multi match, unparseable rating, non-pass FHIS word, or client outage degrades to MANUAL_REVIEW; only an explicit numeric rating >= min-rating or FHIS Pass yields PASSED"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/client/FhrsEstablishment.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/client/FhrsClient.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/gate/FhrsGate.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/client/FhrsClientTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/gate/FhrsGateTest.java
  modified: []

key-decisions:
  - "FHRS establishment id (FSA FHRSID, a JSON number) mapped to a String component via @JsonProperty — the gate uses it as GateResult.externalRef (String); Jackson coerces the scalar number to String"
  - "Constructor-inject WebClient.Builder (Spring auto-config) instead of ImageAnalysisService's static WebClient.builder() — same house WebClient usage but keeps FhrsClient unit-testable over an ExchangeFunction stub with no container"
  - "Non-pass FHIS words (Improvement Required / Awaiting) map to MANUAL_REVIEW, not FAILED — Scotland's non-numeric scheme is never used to hard-reject a vendor on a single lookup"

patterns-established:
  - "Concrete OnboardingGate slice: external-API client (versioned header + circuit breaker) + a mapping gate + two pure-unit tests (ExchangeFunction stub for the client, Mockito for the gate), auto-registered by adding the bean"

requirements-completed: [VOB-02]

# Metrics
duration: 4min
completed: 2026-07-11
---

# Phase 18 Plan 03: Vendor Onboarding — FHRS Food-Hygiene Gate Summary

**The `FOOD_HYGIENE_RATING` gate end to end: an `FhrsClient` that queries the free FSA FHRS Open Data API with the mandatory `x-api-version: 2` header and an `fhrs` circuit breaker, and an `FhrsGate` (`@Component implements OnboardingGate`) that maps the result to PASSED / FAILED / MANUAL_REVIEW against the config-driven `onboarding.fhrs.min-rating` (2) — auto-plugging into the 18-02 `GateChainRunner` registry with zero runner edits, and never hard-failing a vendor on a fuzzy/absent match or an FSA outage.**

## Performance

- **Duration:** ~4 min (first RED commit 02:33 → last GREEN commit 02:37, 2026-07-11)
- **Tasks:** 2 (both TDD)
- **Files:** 5 created, 0 modified; 13 new Java `@Test` methods across 2 files (5 client + 8 gate)

## Accomplishments
- **`FhrsClient`** performs `GET /Establishments?name=&address=` on the FSA API with the mandatory `x-api-version: 2` header (omitting it makes the API silently return no data — `docs/vendor-onboarding-research.md` §6), an explicit 5s `block()` timeout, and `@CircuitBreaker(name = "fhrs")`. On a 5xx / open circuit / timeout the exception **propagates** (never swallowed into a silent pass) so the gate can degrade to MANUAL_REVIEW. `FhrsEstablishment` is a lenient-Jackson record capturing only `FHRSID` / `RatingValue` / `SchemeType`.
- **`FhrsGate`** (`@Component implements OnboardingGate`): `FOOD_HYGIENE_RATING`, `isAutomatic()=true`, `mandatory()=true`. Resolves the Shop by `onboarding.getShopId()` (RLS-scoped `findById`), looks it up, then maps:
  - exactly one FHRS match, numeric rating `>= props.getFhrs().getMinRating()` → **PASSED** (evidence `{fhrs_rating, establishment_id, scheme}`, externalRef = FHRSID);
  - exactly one FHRS match, numeric rating `< min-rating` → **FAILED** (reason names the rating + threshold);
  - exactly one Scotland FHIS match with `RatingValue == "Pass"` → **PASSED** (scheme `FHIS`);
  - zero / ambiguous multi-match / unparseable rating / non-pass FHIS / client failure → **MANUAL_REVIEW** (never FAILED on ambiguity).
- **Config-driven threshold proven:** the mapping reads `props.getFhrs().getMinRating()` (never a literal 2) — a test raises min-rating to 4 and a rating-3 establishment then FAILs.
- **Auto-registration:** adding the `@Component` alone makes it discoverable — the `GateChainRunner` `List<OnboardingGate>` registry is now non-empty (first concrete gate) with **no edit to the runner**.
- **Zero new dependency:** the client test builds a WebClient over an in-memory `ExchangeFunction` stub (captures the `ClientRequest` to assert the header + params, returns canned FHRS/FHIS/empty/5xx bodies); the gate test is pure Mockito. No WireMock / MockWebServer.

## Task Commits

1. **Task 1 (RED):** `4638aad` `test(18-03): add failing FhrsClient WebClient header + parsing test (RED)`
2. **Task 1 (GREEN):** `adf1dc3` `feat(18-03): FhrsClient FSA lookup with x-api-version:2 + fhrs circuit breaker (GREEN)`
3. **Task 2 (RED):** `b1b5c95` `test(18-03): add failing FhrsGate rating-to-status mapping test (RED)`
4. **Task 2 (GREEN):** `5176e1f` `feat(18-03): FhrsGate maps FHRS/FHIS rating to PASSED/FAILED/MANUAL_REVIEW (GREEN)`

## Decisions Made
- **`FHRSID` (JSON number) → String component** via `@JsonProperty("FHRSID")` — the gate uses it as `GateResult.externalRef` (String); Jackson's default scalar coercion handles number→String.
- **Constructor-injected `WebClient.Builder`** (Spring auto-configured bean) rather than `ImageAnalysisService`'s static `WebClient.builder()` — identical house WebClient usage (baseUrl + blocking `.block()` with timeout against an external API) but keeps `FhrsClient` unit-testable over an `ExchangeFunction` stub with no Spring container and no new dependency.
- **Non-pass FHIS words → MANUAL_REVIEW, not FAILED** — Scotland's FHIS is a Pass/Improvement scheme; a non-`Pass` value is inconclusive for auto-verification and routes to a human, consistent with the never-hard-fail-on-ambiguity rule.

## Deviations from Plan

### Adjustments (not scope changes)

**1. [Rule 3 - Adjustment] Gradle invocation matches the multi-project layout**
- **Found during:** Task 1/2 verification.
- **Issue:** The plan's `cd core-java && ./gradlew test ... -x checkstyleMain` does not work here — the wrapper is at the repo root (multi-project build) and there is no `checkstyleMain` task; the `test` task also excludes `@Tag("testcontainers")`.
- **Fix:** Ran `./gradlew :core-java:test --tests 'uk.jtoye.core.onboarding.client.FhrsClientTest' --tests 'uk.jtoye.core.onboarding.gate.FhrsGateTest'` from the repo root, without `-x checkstyleMain` (same correction waves 1–2 documented).
- **Files modified:** none (invocation only).

**2. [Rule 2 - Test hygiene] Reworded a test comment so the no-WireMock grep returns 0**
- **Found during:** Task 1 acceptance grep.
- **Issue:** The acceptance criterion is `grep -rin "wiremock|mockwebserver" ... returns 0`, but a descriptive comment in `FhrsClientTest` literally named "WireMock / MockWebServer", tripping the grep.
- **Fix:** Reworded the comment to "No extra HTTP-mock library is added — the in-memory ExchangeFunction stub … is sufficient." Grep now returns 0. **Committed in:** `adf1dc3`.

---

**Total deviations:** 2 (1 invocation fix, 1 test-comment rewording). **Impact:** No scope change — both plan tasks delivered exactly as specified; all acceptance criteria met.

## Cross-Cutting Impact — Handoff for 18-06 (Closure) — READ THIS

**Adding the first mandatory gate bean changes the runtime gate set seen by the wave-2 `OnboardingSubmitIntegrationTest` (a `@Tag("testcontainers")` test, NOT run by `:core-java:test`).** I deliberately did **not** modify that test — it is a shared wave-2 file, and the parallel wave-3 siblings (18-04 `CompaniesHouseGate` → `BUSINESS_VERIFIED`, 18-05 allergen gate → `ALLERGEN_DATA_COMPLETE`) invalidate the *same* test independently, so editing it here would collide with them and violate the zero-file-overlap parallel invariant. **This is a systemic wave-3 → wave-2 interaction that must be reconciled centrally in the 18-06 closure plan**, not per-gate.

What breaks and why (confirmed by static analysis + the code, not run here to avoid a real external FSA call):
- On `submit()`, `GateChainRunner.materialise` now inserts a PENDING **mandatory** `FOOD_HYGIENE_RATING` row (from `FhrsGate`), and the async `runAndRecompute` calls `FhrsGate.evaluate` → `FhrsClient.lookup("shop-<uuid>", "Test Address")`.
- The `test` profile has **no** `onboarding.fhrs.base-url` override, so the lookup hits the real `https://api.ratings.food.gov.uk` (or fails fast without network). Either way the seeded fixture shop won't match → the FHRS row resolves to **MANUAL_REVIEW**.
- `autoApproveTrueDrivesFullyPassingOnboardingToApproved` and `autoApproveFalseHaltsAtPendingApproval` each seed only a `BUSINESS_VERIFIED` PASSED row and then assert APPROVED / PENDING_APPROVAL. With an extra mandatory FHRS row stuck at MANUAL_REVIEW, `allPassed` is false and neither GATES_PASSED nor GATE_FAILED fires → the onboarding stays `VERIFYING` → both tests **time out and fail**. (The other two tests still assert only `VERIFYING`, so they pass — but incur an unwanted real external HTTP call on the async thread.)

**Recommended closure fix (18-06):** isolate `OnboardingSubmitIntegrationTest` from the now-live gate beans — e.g. `@MockBean FhrsClient` (and the 18-04/18-05 clients) returning a passing establishment, OR seed a PASSED/WAIVED row for **every** materialised mandatory gate, OR add a `test`-profile `onboarding.fhrs.base-url` pointing at a stub. This also removes the external-network call from the suite. This handoff is the closure plan's to own alongside the `docs/metrics.json` reconciliation the wave-1/wave-2 summaries already flagged.

## Threat Flags
None beyond the plan's `<threat_model>` — all four listed mitigations are implemented:
- **T-18-03-I (SSRF):** base URL comes only from `OnboardingProperties` (config, not request); vendor input is confined to URL-encoded query params, never the host/scheme.
- **T-18-03-T (tamper/empty→pass):** the mapping defaults unknown/empty/ambiguous to MANUAL_REVIEW; only an explicit numeric rating `>= min-rating` or FHIS `Pass` yields PASSED.
- **T-18-03-D (DoS):** explicit 5s WebClient timeout + `@CircuitBreaker(name = "fhrs")`; runs on the `@Async` gate worker so submit is not blocked; failure degrades to MANUAL_REVIEW.
- **T-18-03-SC (package install):** no new Gradle dependency — WebClient (spring-webflux) + the existing test toolkit only; the client test uses an in-memory ExchangeFunction stub.

## Next Phase Readiness
- The FHRS gate is live and auto-registered; on submit it materialises + (async) evaluates with no runner edits.
- **18-06 (closure) MUST** (a) reconcile `OnboardingSubmitIntegrationTest` with the now-populated gate registry (see the Cross-Cutting Impact handoff above), and (b) run `scripts/docs-freshness.sh --write` to fold this plan's **13 new Java `@Test` methods** (across 2 new files) into `docs/metrics.json` (schema head unchanged at V43) so the `docs-freshness` CI gate stays green.
- `FHRS_BASE_URL` / `FHRS_MIN_RATING` / `FHRS_API_VERSION` are already `${ENV:default}` in `application.yml`; no user setup is required for the default (free, no-key) FSA API.

## Self-Check: PASSED
- All 5 created files verified present on disk.
- All 4 task commits (`4638aad`, `adf1dc3`, `b1b5c95`, `5176e1f`) verified in git history.
- `./gradlew :core-java:test --tests FhrsClientTest --tests FhrsGateTest` green; full `:core-java:test` (unit, non-testcontainers) green — no unit regression.
- Acceptance greps: `x-api-version` present, `@CircuitBreaker(name = "fhrs")` present, `GateType.FOOD_HYGIENE_RATING` + `implements OnboardingGate` present, no `wiremock`/`mockwebserver` in build or tests.
- No stubs. The only cross-cutting concern (integration-test reconciliation) is documented as an explicit 18-06 handoff, deliberately not fixed here to preserve the parallel zero-file-overlap invariant.

---
*Phase: 18-vendor-onboarding-first-slice*
*Completed: 2026-07-11*
