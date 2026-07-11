---
phase: 18-vendor-onboarding-first-slice
plan: 05
subsystem: onboarding
tags: [vendor-onboarding, allergen-gate, natashas-law, ppds, go-live, shop-published, sole-writer, state-machine, testcontainers, rls]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice (plan 01)
    provides: V43 schema, GateType.ALLERGEN_DATA_COMPLETE, VendorOnboarding/Gate entities + repos
  - phase: 18-vendor-onboarding-first-slice (plan 02)
    provides: OnboardingGate registry + GateResult + GateChainRunner, VendorOnboardingService canonical transition (sole Shop.published writer), GO_LIVE guard requiring a PASSED ALLERGEN_DATA_COMPLETE row, OnboardingController
  - phase: milestone-2 (PPDS/Natasha's Law, V41)
    provides: Product.allergenSpans/shelfLifeDays/durabilityType + ProductLabelService.validatePpdsData (the authoritative completeness rule this gate mirrors)
provides:
  - AllergenCompletenessGate — automatic, mandatory ALLERGEN_DATA_COMPLETE gate over the V41 product fields (auto-registers into the GateChainRunner registry via @Component)
  - POST /api/v1/onboarding/go-live -> VendorOnboardingService.goLive() (guarded publish, thin delegation)
  - Shop.published sole-writer hardening: createShop + updateShop can no longer publish from request input (T-18-05-T closed)
  - OnboardingGoLiveIntegrationTest (Testcontainers) — go-live block/allow + updateShop sole-writer regression
affects: [18-06-phase-closure]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Compliance gate reuses the authoritative label rule: the allergen predicate mirrors ProductLabelService.validatePpdsData (durability + shelf life + derivable ingredients) rather than inventing a second, contradictory completeness rule"
    - "Sole-writer hardening by snapshot/restore: updateShop captures published before the MapStruct field-copy and restores it after, so a request body can never flip published outside the guarded GO_LIVE side effect"
    - "Guarded action endpoint as thin delegation: go-live is one canonical transition call; the state-machine guard (18-02) enforces the gates, a veto surfaces as 400"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGate.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGateTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGoLiveIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitIntegrationTest.java

key-decisions:
  - "Allergen completeness reads the SAME V41 fields the PPDS label code hard-requires (durabilityType + shelfLifeDays) plus a present ingredientsText (from which the label re-parses the emphasised allergen runs). An empty allergen span set is compliant for allergen-free food — the gate does NOT enforce a 'must declare an allergen' rule the label never enforces (avoids a contradictory second rule)."
  - "Sole-writer hardening lives in ShopService (the plan's files_modified), NOT ShopMapper: createShop forces published=false unconditionally and updateShop snapshot/restores published — closing both request-driven publish bypasses without a mapper change."
  - "Reconciled the sibling-wave 18-02 OnboardingSubmitIntegrationTest (a directly-caused breakage): registering a mandatory automatic gate changes its async-recompute outcome. Seeded a fully-labelled product so the allergen gate PASSES and relaxed the post-submit re-read to submitted_at presence."

patterns-established:
  - "Concrete OnboardingGate bean (auto, mandatory) computed from domain data, aligned to an existing authoritative validator instead of a duplicated rule"
  - "Testcontainers proof of a guarded publish action + a sole-writer regression, seeding gate rows directly to isolate from the async gate chain / gate beans"

requirements-completed: [VOB-03, VOB-01]

# Metrics
duration: ~35min
completed: 2026-07-11
---

# Phase 18 Plan 05: Allergen Gate + Guarded Go-Live Summary

**The `ALLERGEN_DATA_COMPLETE` gate (computed from the V41 product fields, aligned to the authoritative PPDS label rule) plus the guarded `POST /onboarding/go-live` endpoint that publishes the shop only through the state-machine GO_LIVE side effect — and `Shop.published` hardened so create/update can no longer publish from request input, proven on real Postgres.**

## Performance
- **Duration:** ~35 min (incl. cross-plan interaction analysis)
- **Completed:** 2026-07-11
- **Tasks:** 2 (1 TDD)
- **Files:** 7 (3 created, 4 modified); 10 new Java `@Test` methods across 2 files

## Accomplishments
- **`AllergenCompletenessGate`** (`@Component implements OnboardingGate`): `type()=ALLERGEN_DATA_COMPLETE`, `isAutomatic()=true`, `mandatory()=true`. Loads the shop's catalogue (RLS-scoped `ProductRepository.findByShopId`) and PASSES with `{"products_checked":N}` evidence when every product is allergen-complete; FAILS naming up to 10 offending SKUs otherwise; an empty or no-shop onboarding FAILS ("nothing to publish"). Auto-plugs into the 18-02 `GateChainRunner` registry with zero runner edits.
- **Label-aligned predicate (no contradictory rule):** a product is complete when it has a `durabilityType`, a `shelfLifeDays`, and a present `ingredientsText` — the SAME fields `ProductLabelService.validatePpdsData` hard-requires (422 without them), with allergen runs derived by re-parsing `ingredientsText` at render time. An allergen-free product (empty span set) is compliant, exactly as the label renderer allows. The alignment is cited in a code comment.
- **`POST /api/v1/onboarding/go-live`** → `VendorOnboardingService.goLive()`: thin delegation, tenant resolved server-side. Fires `GO_LIVE` through the single canonical `transition()`; the 18-02 guard requires all mandatory gates PASSED/WAIVED **and** a PASSED `ALLERGEN_DATA_COMPLETE` row, so a veto surfaces as `InvalidStateTransitionException` → HTTP 400. The transition side effect stamps `went_live_at` and flips `Shop.published=true` via `ShopService.setPublished`.
- **`Shop.published` sole-writer hardening (T-18-05-T):** audit found a real bypass — `CreateShopRequest` carries a `published` field and `ShopMapper.toEntity`/`updateEntity` copy it. Fixed inside `ShopService`: `createShop` now forces `setPublished(false)` unconditionally (a shop is never born live), and `updateShop` snapshots `published` before the mapper copy and restores it after, so a request body cannot publish a shop. `setPublished(...true)` is now reachable ONLY from the GO_LIVE/REINSTATE side effect.
- **Testcontainers proof** (`OnboardingGoLiveIntegrationTest`, real Postgres 15): (1) go-live with the allergen gate PENDING → 400 and the shop stays unpublished + onboarding stays APPROVED; (2) all mandatory + allergen gates PASSED → onboarding `LIVE` and `shops.published == Boolean.TRUE`; (3) a direct `updateShop` with `published=true` does NOT publish the shop (sole-writer regression). Boolean-safe assertions throughout (N4).

## Task Commits
1. **Task 1 (RED):** `769edd0` `test(18-05): add failing AllergenCompletenessGate unit test (RED)`
2. **Task 1 (GREEN):** `2fe0428` `feat(18-05): AllergenCompletenessGate over V41 product fields (GREEN)`
3. **Task 2:** `4a26561` `feat(18-05): guarded go-live endpoint + Shop.published sole-writer hardening`

## Decisions Made
- **Predicate aligned to the label, not to the plan's looser prose.** The plan's `<behavior>` said "a non-empty allergen span set derivable from ingredients"; the authoritative `ProductLabelService.validatePpdsData` does NOT require a non-empty span set (an allergen-free product is compliant, and the renderer re-parses `ingredientsText`). Following the acceptance criterion ("reads the same V41 fields the PPDS label code uses — no duplicate/contradictory rule"), the gate requires durability + shelf-life + a present ingredients list (spans derivable), not a mandatory allergen declaration. The "missing allergen spans → FAILED" test case is realised as a product with a blank ingredients list (spans not derivable).
- **Hardening in `ShopService`, not `ShopMapper`.** `ShopMapper` is outside the plan's `files_modified`; the snapshot/restore in `updateShop` + the unconditional `setPublished(false)` in `createShop` close the bypass without touching the mapper and keep the change local to the audited service.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Directly-caused test breakage] Reconciled the 18-02 `OnboardingSubmitIntegrationTest`**
- **Found during:** Task 2 (running the full onboarding integration suite).
- **Issue:** Registering a mandatory **automatic** gate bean changes the async-recompute outcome of the pre-existing 18-02 submit test. Its shop had no products, so the new allergen gate evaluated to FAILED → the recompute fired `GATE_FAILED` (VERIFYING → ACTION_REQUIRED), breaking `createThenSubmitReadsBackVerifyingWithSubmittedAt` and both auto-approve scenarios (which then early-returned from a non-VERIFYING state and timed out).
- **Fix:** Seeded one fully-labelled product in the test's `@BeforeEach` (V41 `durability_type`/`shelf_life_days`/`ingredients_text`) so the allergen gate PASSES, and relaxed the post-submit re-read to assert `submitted_at` presence + "left DRAFT" instead of a pinned transient VERIFYING (the async recompute may now advance the onboarding). The synchronous submit-response assertion (VERIFYING) is unchanged.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitIntegrationTest.java`.
- **Verification:** all 4 tests green (Testcontainers).
- **Committed in:** `4a26561`.

**2. [Rule 2 - Security/correctness] Closed a genuine `Shop.published` publish bypass in `createShop`**
- **Found during:** Task 2 (sole-writer audit).
- **Issue:** `createShop` only reset `published` when null (`if (getPublished() == null) setPublished(false)`). Because `ShopMapper.toEntity` copies `CreateShopRequest.published`, a `POST /shops {"published": true}` would create an already-live shop — a second publish path bypassing the GO_LIVE gate (beyond the `updateShop` path the plan named).
- **Fix:** `createShop` now forces `setPublished(false)` unconditionally.
- **Files modified:** `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java`.
- **Committed in:** `4a26561`.

**3. [Rule 3 - Adjustment] Gradle invocation for the multi-project build**
- Verified via `./gradlew :core-java:test` (unit) and `:core-java:integrationTest` (the `@Tag("testcontainers")` task) from the repo root, without the plan's non-existent `-x checkstyleMain` flag — same correction waves 1–2 documented.

---

**Total deviations:** 3 (1 directly-caused sibling-test reconciliation, 1 additional security bypass closed, 1 invocation fix). **Impact:** No scope change — all plan artifacts delivered; the create-path fix and the test reconciliation strengthen the sole-writer invariant and keep the suite green.

## Merge Note for 18-06 (closure)
`OnboardingSubmitIntegrationTest` was reconciled for THIS worktree (allergen gate only). After 18-03 (FhrsGate → FOOD_HYGIENE_RATING) and 18-04 (CompaniesHouseGate → BUSINESS_VERIFIED) also merge, that test needs a further look:
- FHRS/CH gates degrade to `MANUAL_REVIEW` on an un-stubbed client, which leaves the onboarding in `VERIFYING` (my relaxed "left DRAFT" assertion still holds).
- **But** `CompaniesHouseGate` materialises a `BUSINESS_VERIFIED` PENDING row on submit, which will collide (`UNIQUE(onboarding_id, gate_type)`) with the manual `BUSINESS_VERIFIED` seed in this test's `createSubmitAndSeedPassedMandatoryGate`. 18-06 should either mock the FHRS/CH client beans (as its new `VendorOnboardingEndToEndIntegrationTest` does) or retire the now-superseded auto-approve scenarios from `OnboardingSubmitIntegrationTest`. This interaction is inherent to three parallel gate beans landing on a wave-2 test written for zero gates; it is not specific to plan 05.

## Threat Flags
None beyond the plan's `<threat_model>`. All three mitigations are implemented and tested:
- **T-18-05-T** (publish bypass): `setPublished(...true)` reachable only from the GO_LIVE/REINSTATE side effect; `createShop`/`updateShop` cannot publish from request input; regression-tested (`updateShopCannotPublish...`).
- **T-18-05-E** (go-live with unmet gates): GO_LIVE guard requires mandatory + ALLERGEN PASSED; veto → 400; proven (`goLiveBlockedWhileAllergenGateNotPassed...`).
- **T-18-05-I** (Natasha's Law): the allergen gate FAILS unless every product carries the required V41 data; go-live blocked until PASSED.

## Known Stubs
None. The gate computes from live catalogue data; the go-live endpoint drives the real state machine + publish side effect. No new gradle dependency (T-18-05-SC — package-legitimacy gate not triggered).

## Next Phase Readiness
- All three gate types now have concrete beans (`BUSINESS_VERIFIED`, `FOOD_HYGIENE_RATING` from siblings; `ALLERGEN_DATA_COMPLETE` here). Go-live + its `published=true` side effect + the sole-writer regression are in place for 18-06's cross-gate end-to-end proof.
- **Note for 18-06 (docs-freshness):** this plan adds **10 Java `@Test` methods across 2 files** (7 gate unit + 3 Testcontainers go-live) and **no new controller class** (`OnboardingController` already counted in 18-02; go-live is a new method on it). No schema change (V43 head unchanged). Reconcile `docs/metrics.json` via `scripts/docs-freshness.sh --write` in closure. See the Merge Note above re: `OnboardingSubmitIntegrationTest`.

## Self-Check: PASSED
- All 3 created files verified present on disk.
- All 3 task commits (`769edd0`, `2fe0428`, `4a26561`) verified in git history.
- Full onboarding suite green: `gate.AllergenCompletenessGateTest`(7), `OnboardingGoLiveIntegrationTest`(3), `OnboardingSubmitIntegrationTest`(4), plus 18-01/18-02 tests (Persistence 3, RLS 3, GateChainRunner 7, OnboardingProperties 4, StateMachine 13) — 0 failures. `ShopServiceTest`(17) + `ShopControllerIntegrationTest`(6) green (hardening no-regression).
- AC greps: no caller-driven `published=true` path in `core/shop`; `GateType.ALLERGEN_DATA_COMPLETE` present + `implements OnboardingGate`.

---
*Phase: 18-vendor-onboarding-first-slice*
*Completed: 2026-07-11*
