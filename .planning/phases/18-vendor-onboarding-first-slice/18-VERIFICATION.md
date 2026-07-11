---
phase: 18-vendor-onboarding-first-slice
verified: 2026-07-11T04:55:22Z
status: human_needed
score: 18/18 must-haves verified
overrides_applied: 0
mvp_mode: true
user_story: "As a food vendor, I want to auto-verify my business and hygiene rating at signup, so that my shop goes live without manual review."
human_verification:
  - test: "Live-browser walkthrough of /dashboard/onboarding: create application (model + shop select + optional company number) as a signed-in vendor with no existing onboarding."
    expected: "Create form renders per 18-UI-SPEC (segmented model toggle, shop <select>, optional companyNumber input with helper text); submitting creates a DRAFT and transitions into the status view."
    why_human: "Only Jest-tested (jsdom, mocked apiClient); never rendered in a real browser against the live core-java API."
  - test: "Drive a submitted onboarding through VERIFYING with real (or stubbed-green) gate evaluation and observe the 4s poll in the Network tab."
    expected: "GET /api/v1/onboarding/me fires every ~4s while status=VERIFYING, gate rows show 'Checking…' with a spinning Loader2, and polling stops the instant the status leaves VERIFYING (no dangling interval, confirmed via React DevTools / no repeated requests after the state settles)."
    why_human: "Jest fake-timers prove the interval logic in isolation; real browser timing/render-cycle interaction (React 19 strict effects, double-invoke in dev) is unverified."
  - test: "From APPROVED, click 'Go live', confirm the dialog, and separately force a guard-veto (e.g. blank a product's allergen data after approval, then attempt go-live) to see the 400 path."
    expected: "Happy path: dialog copy matches spec ('Go live?' / 'This publishes your storefront...' / confirm 'Go live' / cancel 'Not yet'), confirming POSTs /go-live and renders the LIVE state. Veto path: destructive toast 'Not ready to go live yet' / 'Every check below must pass first…' fires, the gate breakdown stays visible, and the page does not crash or unmount."
    why_human: "This is the headline user-facing moment of the phase (publish action) and a security-relevant client behavior (T-18-07-02); code review already found two real, only-visible-in-browser cosmetic bugs nearby (IN-02 dynamic Tailwind hover classes never generated, IN-06 wrong terminal-state banner copy) — both signal the surface has not been visually inspected."
  - test: "Verify entry surfaces on the real running stack: /for-operators shows 'Start your application' in the existing orange marketing button style (not restyled); the dashboard sidebar shows a 'Go live' item; the dashboard home shows the amber/blue banner and hides it once LIVE."
    expected: "CTA color/shape matches the page's existing primary treatment (per A5); sidebar item highlights on the active route; banner variant matches state (amber not-started, blue in-progress, none when LIVE) and does not visually clash with the stats grid."
    why_human: "Visual/layout fidelity to the LOCKED 18-UI-SPEC and cross-page visual consistency cannot be confirmed from Jest snapshots alone."
  - test: "Confirm the intended production value of onboarding.auto-approve and the plan for a vendor who lands at PENDING_APPROVAL under the shipped default."
    expected: "A documented decision: either ONBOARDING_AUTO_APPROVE=true is set for the target environment, or an admin-approve queue/endpoint ships before real vendors reach PENDING_APPROVAL (today there is no endpoint that can move an onboarding out of PENDING_APPROVAL other than the auto-approve recompute)."
    why_human: "This is a product/operations decision, not a code defect — see 'Operational Note' in Gaps Summary below."
---

# Phase 18: Vendor Onboarding — First Slice Verification Report

**Phase Goal (User Story, MVP mode):** As a food vendor, I want to auto-verify my business and hygiene rating at signup, so that my shop goes live without manual review.
**Verified:** 2026-07-11T04:55:22Z
**Status:** human_needed
**Re-verification:** No — initial verification

## User Flow Coverage (MVP mode)

| Step | Expected | Evidence in codebase | Status |
|------|----------|----------------------|--------|
| Vendor discovers the flow | `/for-operators` shows a "Start your application" CTA routing to `/dashboard/onboarding` | `frontend/components/marketing/operator-pitch.tsx:72` — `<a href="/dashboard/onboarding">Start your application</a>`, existing orange button treatment; test `operator-pitch.test.tsx` asserts text + href | ✓ VERIFIED (Jest only, see human_verification) |
| Vendor creates an application ("at signup") | Signed-in vendor with no onboarding sees a create form (model, shop, optional company number) and can submit it | `frontend/app/dashboard/onboarding/page.tsx` (564 lines) 404-branch renders the Card form; `VendorOnboardingService.createOnboarding` validates shop ownership (`shopRepository.findByIdAndTenantId`, CR-02 fix) and persists a DRAFT row | ✓ VERIFIED |
| Business auto-verified | On submit, `BUSINESS_VERIFIED` gate calls Companies House (HTTP Basic, key-as-username) and maps `active`→PASSED, sole-trader/404→WAIVED, other→FAILED, failure→MANUAL_REVIEW | `CompaniesHouseClient.java:67` (`setBasicAuth`), `CompaniesHouseGate.java` mapping; `CompaniesHouseGateTest` (7 tests) | ✓ VERIFIED |
| Hygiene rating auto-verified | `FOOD_HYGIENE_RATING` gate calls FSA FHRS with mandatory `x-api-version:2`, threshold `min-rating=2` (config-driven), Scotland FHIS `Pass`→PASSED, ambiguous/no-match→MANUAL_REVIEW (never FAILED) | `FhrsClient.java:66` (`x-api-version` header), `FhrsGate.java` mapping reading `properties.getFhrs().getMinRating()`; `FhrsGateTest` (8 tests) | ✓ VERIFIED |
| Allergen data checked before publish | `ALLERGEN_DATA_COMPLETE` gate blocks GO_LIVE until every product carries V41 durability/shelf-life/ingredients data; re-checked fresh at GO_LIVE/REINSTATE (TOCTOU closed, WR-03) | `AllergenCompletenessGate.java`; `VendorOnboardingService.refreshAllergenGate` called from `transition()` before `sendEvent` on GO_LIVE/REINSTATE | ✓ VERIFIED |
| Shop goes live **without manual review** (capability) | With `onboarding.auto-approve=true`, a fully-passing onboarding auto-advances `PENDING_APPROVAL→APPROVED` via the real submit→async-recompute path (no admin/service APPROVE call), then vendor-triggered go-live publishes the shop | `GateChainRunner.runAndRecompute` (auto-APPROVE branch, WR-01-hardened); `VendorOnboardingEndToEndIntegrationTest.fullyAutomaticPath_allGatesGreen_autoApprovesThenGoLivePublishes` — asserts `approvedAt` non-null with **no direct `runAndRecompute` call** (relies solely on `submit()`'s `afterCommit` kick, CR-01) then `shops.published == TRUE` | ✓ VERIFIED (capability). **See Operational Note — default config does NOT deliver this outcome out of the box.** |
| Vendor sees live status without guessing | Overall-state badge + 3-gate breakdown, 4s poll while VERIFYING, guard-veto (400) surfaced as a destructive toast without crashing | `frontend/app/dashboard/onboarding/page.tsx` status view + polling `useEffect`; `page.test.tsx` — 11 `it()` blocks covering empty/create/DRAFT/resubmit/poll/unmount-cleanup/APPROVED-go-live/guard-veto-400/LIVE/load-error | ✓ VERIFIED (Jest only, see human_verification) |

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Tenant-scoped `vendor_onboarding` aggregate + state machine persists under RLS (Flyway V43); state machine is sole writer of `Shop.published` | ✓ VERIFIED | `V43__vendor_onboarding.sql` (4 tables, ENABLE+FORCE RLS, `_aud` mirrors); `VendorOnboardingRlsIntegrationTest` (3 tests, NOSUPERUSER); `ShopService.createShop` forces `setPublished(false)`, `updateShop` snapshot/restores `published`, `setPublished(...true)` reachable only from `VendorOnboardingService.transition` GO_LIVE/REINSTATE branch |
| 2 | `BUSINESS_VERIFIED` + `FOOD_HYGIENE_RATING` gates run automatically on submit, recording pass/fail + evidence; MANUAL_REVIEW fallback (never hard-fail on ambiguity) | ✓ VERIFIED | `CompaniesHouseGate`/`FhrsGate` mapping tables (see User Flow Coverage row above); `CompaniesHouseGateTest`(7) + `FhrsGateTest`(8) + `CompaniesHouseClientTest`(6) + `FhrsClientTest`(5) |
| 3 | `ALLERGEN_DATA_COMPLETE` gate blocks GO_LIVE until every product carries required V41 allergen data | ✓ VERIFIED | `AllergenCompletenessGate.java` (predicate aligned to `ProductLabelService.validatePpdsData` per code comment); `AllergenCompletenessGateTest`(7); `OnboardingGoLiveIntegrationTest` blocks go-live at 400 while allergen gate not PASSED |
| 4 | FHRS threshold + both provider API base URLs injected via `onboarding.*` config (`${ENV:default}`), never literal | ✓ VERIFIED | `application.yml:193-201` (`onboarding.fhrs.min-rating`, `onboarding.companies-house.base-url`, etc., all `${ENV:default}`); `OnboardingProperties` binds them; `OnboardingPropertiesTest` |
| 5 | Tests added + `docs/metrics.json` bumped, `docs-freshness` gate green | ✓ VERIFIED | `docs/metrics.json` = 690 Java test methods / 113 files, schema 43, 15 controllers, jest 130/22, total 918; `bash scripts/docs-freshness.sh` run during this verification → `docs-freshness OK: metrics match source (total logical invocations: 918).` |
| 6 | `onboarding.auto-approve` toggle: true → auto-advances `PENDING_APPROVAL→APPROVED` (no manual call); false → halts at `PENDING_APPROVAL`, go-live rejected | ✓ VERIFIED | `VendorOnboardingEndToEndIntegrationTest` — both toggle states asserted end-to-end on real Postgres |
| 7 | The fully-automatic path is proven via the **real** submit→async-recompute path, not a direct test-only `runAndRecompute` call (CR-01: async kick was firing inside the uncommitted submit transaction, causing a permanent-VERIFYING race in production) | ✓ VERIFIED | `VendorOnboardingService.kickGateChainAfterCommit` registers an `afterCommit` `TransactionSynchronization`; `VendorOnboardingEndToEndIntegrationTest` comments + code confirm no direct `runAndRecompute` call — relies solely on `submit()`'s deferred kick |
| 8 | Cross-tenant shop binding is blocked at onboarding creation (CR-02: unvalidated `shopId` let a tenant bind to — and spoof compliance evidence against — another tenant's shop) | ✓ VERIFIED | `VendorOnboardingService.createOnboarding` calls `shopRepository.findByIdAndTenantId(shopId, tenantId)` → 404 on foreign/missing shop; `FhrsGate` also reads the shop tenant-scoped (defence-in-depth); `OnboardingCreateCrossTenantIntegrationTest` (3 tests) |
| 9 | ACTION_REQUIRED has a real recovery path — not a UI dead end (CR-03: no RESUBMIT endpoint existed; the shipped "Re-run checks" button always 400'd) | ✓ VERIFIED | `POST /api/v1/onboarding/resubmit` (`OnboardingController.resubmit`) fires RESUBMIT and resets FAILED/MANUAL_REVIEW gate rows to PENDING; UI "Re-run checks" now calls `/resubmit` (confirmed in `page.tsx:249`); `OnboardingResubmitIntegrationTest` (2 tests); `page.test.tsx` asserts the button hits `/resubmit` not `/submit` |
| 10 | A vetoed auto-APPROVE does not roll back already-committed gate evidence (WR-01) | ✓ VERIFIED | `GateChainRunner.runAndRecompute` wraps the APPROVE call in `catch (InvalidStateTransitionException)`, logs WARN, lets GATES_PASSED stand; `GateChainRunnerTest.recomputeSwallowsVetoedAutoApprove` |
| 11 | `companyNumber` validated at the API boundary, not left to surface as a misleading 409 (WR-02) | ✓ VERIFIED | `CreateOnboardingRequest` carries `@Size(max=32)` + `@Pattern`; service trims/uppercases, blank→null; `OnboardingCompanyNumberValidationIntegrationTest` |
| 12 | GO_LIVE/REINSTATE re-evaluates the allergen gate against current data before the guard runs, closing a TOCTOU (WR-03) | ✓ VERIFIED | `VendorOnboardingService.transition` calls `refreshAllergenGate(onboarding)` before `sendEvent` for GO_LIVE/REINSTATE; `OnboardingGoLiveIntegrationTest` includes a stale-row TOCTOU test |
| 13 | `/dashboard/onboarding` is a single stateful page: create form → live status (badge + 3-gate breakdown) → go-live, with 4s polling while VERIFYING that self-clears | ✓ VERIFIED (Jest) | `page.tsx` (564 lines); `page.test.tsx` (288 lines, 11 `it()` blocks incl. fake-timer poll + unmount-cleanup tests) |
| 14 | Go-live guard-veto (400) surfaces a destructive toast and keeps the gate breakdown visible (no crash) | ✓ VERIFIED (Jest) | `page.tsx` go-live handler catch branch; `page.test.tsx` `"surfaces a destructive toast on a go-live 400 guard veto and keeps the gate breakdown visible"` |
| 15 | `/for-operators` shows a "Start your application" CTA to `/dashboard/onboarding` | ✓ VERIFIED | `operator-pitch.tsx:72`; `operator-pitch.test.tsx` |
| 16 | Sidebar shows a "Go live" nav item → `/dashboard/onboarding` | ✓ VERIFIED | `sidebar.tsx:33` (`{ name: "Go live", href: "/dashboard/onboarding", icon: Rocket }`); `dashboard-shell.test.tsx` |
| 17 | Dashboard home shows an incomplete-onboarding banner until LIVE | ✓ VERIFIED | `frontend/app/dashboard/page.tsx` `onboardingBannerContent` + fetch wired into the page; `app/dashboard/__tests__/page.test.tsx` | 
| 18 | VOB-01..05 registered in REQUIREMENTS.md and mapped to phase-18 plans | ✓ VERIFIED | `.planning/REQUIREMENTS.md` lines 66-70 (all `[x]` DONE) + coverage table lines 147-151 |

**Score:** 18/18 truths verified (all backend + frontend must-haves hold in the codebase, cross-checked against the code review's Fix Round 1 commits — not just SUMMARY claims).

### Code Review Cross-Check (18-REVIEW.md)

The phase went through a standard-depth code review (58 files) that found **3 Critical + 3 Warning** issues before this verification. All six were independently re-verified in the actual code (not taken on the SUMMARY's word):

| ID | Issue | Fix commit | Verified in code |
|----|-------|-----------|-------------------|
| CR-01 | Async gate run fired inside the uncommitted submit transaction — race left onboarding permanently stuck in VERIFYING | `2abfe38` | ✓ `kickGateChainAfterCommit` + `afterCommit` sync confirmed; e2e test relies solely on it |
| CR-02 | `createOnboarding` accepted an unvalidated `shopId` — cross-tenant shop binding + hygiene-evidence spoofing | `a12b617` | ✓ `findByIdAndTenantId` guard + tenant-scoped `FhrsGate` read confirmed |
| CR-03 | ACTION_REQUIRED was a dead end; UI's "Re-run checks" button always 400'd | `c20fbf0` | ✓ `/onboarding/resubmit` endpoint + UI wiring confirmed |
| WR-01 | Vetoed auto-APPROVE silently rolled back all committed gate evidence | `e1e41a3` | ✓ targeted catch confirmed in `GateChainRunner` |
| WR-02 | `companyNumber` unvalidated — over-length input surfaced as misleading 409 | `4a15b24` | ✓ `@Size`/`@Pattern` + normalisation confirmed |
| WR-03 | GO_LIVE/REINSTATE guard trusted a stale allergen row (TOCTOU) | `c118823` | ✓ `refreshAllergenGate` called pre-`sendEvent` confirmed |

**10 Info-level findings (IN-01..IN-10) remain deliberately deferred** (documented in 18-REVIEW.md as out of Fix Round 1 scope). Independently spot-checked two of them still present as described (non-blocking):
- IN-06 (dashboard banner copy wrong for SUSPENDED/REJECTED/WITHDRAWN — still shows "Finish setting up your shop to go live.") — confirmed present at `frontend/app/dashboard/page.tsx:39-44`.
- IN-03 (CHANGELOG test-baseline prose, 873, is now further stale — current metrics.json is 918, not even the 907 the finding cited) — confirmed present at `docs/CHANGELOG.md:12`.
Neither blocks the phase goal; both are cosmetic/documentation-prose drift.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../db/migration/V43__vendor_onboarding.sql` | 2 tables + 2 `_aud` mirrors, FORCE RLS | ✓ VERIFIED | Confirmed via 18-01 plan/summary + RlsContractTest sweep (dynamic, no EXEMPT_TABLES entry for onboarding tables) |
| `VendorOnboardingStateMachineConfig.java` / `...Service.java` | 10 events / 9 states, guard-veto-via-unchanged-state detection | ✓ VERIFIED | Read in full; APPROVE/GO_LIVE/REINSTATE guards confirmed |
| `VendorOnboardingService.java` | create/submit/resubmit/goLive/getMyOnboarding + canonical `transition()` | ✓ VERIFIED | Read in full (320 lines) |
| `GateChainRunner.java` | registry + async recompute, auto-approve consumption, PENDING-only re-evaluation | ✓ VERIFIED | Read in full (199 lines) |
| `OnboardingController.java` | create/submit/resubmit/go-live/me | ✓ VERIFIED | Read in full (129 lines) |
| `FhrsClient.java` / `FhrsGate.java` | x-api-version:2, circuit breaker, min-rating mapping | ✓ VERIFIED | grep-confirmed |
| `CompaniesHouseClient.java` / `CompaniesHouseGate.java` | HTTP Basic key-as-username, circuit breaker, active/WAIVED/FAILED/MANUAL_REVIEW mapping | ✓ VERIFIED | grep-confirmed |
| `AllergenCompletenessGate.java` | V41-field predicate | ✓ VERIFIED | Present, referenced by TOCTOU fix |
| `frontend/app/dashboard/onboarding/page.tsx` | Single stateful page, ≥180 lines | ✓ VERIFIED | 564 lines |
| `frontend/app/dashboard/onboarding/__tests__/page.test.tsx` | ≥80 lines Jest coverage | ✓ VERIFIED | 288 lines, 11 `it()` blocks |
| `frontend/types/api.ts` | `OnboardingDto`/`GateDto`/`CreateOnboardingRequest` | ✓ VERIFIED | Present |
| `frontend/components/dashboard/sidebar.tsx` | "Go live" nav item | ✓ VERIFIED | Present |
| `frontend/components/marketing/operator-pitch.tsx` | "Start your application" CTA | ✓ VERIFIED | Present |
| `frontend/app/dashboard/page.tsx` | Incomplete-onboarding banner | ✓ VERIFIED | Present (with IN-06 cosmetic caveat) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `OnboardingController` | `VendorOnboardingService` | thin delegation | ✓ WIRED | All 5 endpoints delegate directly |
| `VendorOnboardingService` (GO_LIVE/SUSPEND/REINSTATE) | `ShopService.setPublished` | state-machine side effect | ✓ WIRED | Only call site of `setPublished(...true)` |
| `GateChainRunner.runAndRecompute` | `OnboardingProperties.isAutoApprove()` | consumed post-GATES_PASSED | ✓ WIRED | grep confirms + e2e test proves both toggle states |
| `submit()` | `GateChainRunner.runAndRecompute` | `afterCommit` transaction sync (CR-01) | ✓ WIRED | Deferred dispatch confirmed; e2e test relies on it exclusively |
| `FhrsGate` / `CompaniesHouseGate` / `AllergenCompletenessGate` | `GateChainRunner` `List<OnboardingGate>` registry | `@Component` auto-registration | ✓ WIRED | 3 gate rows materialise on submit (asserted by e2e test `assertAllThreeGatesPassed`) |
| `frontend/.../onboarding/page.tsx` | `/api/v1/onboarding/me`, `/onboarding`, `/submit`, `/resubmit`, `/go-live` | `apiClient` calls | ✓ WIRED | grep-confirmed in page.tsx; exercised by Jest |
| `frontend/components/dashboard/sidebar.tsx` | `/dashboard/onboarding` | nav array item | ✓ WIRED | Confirmed |
| `frontend/components/marketing/operator-pitch.tsx` | `/dashboard/onboarding` | `<a href>` | ✓ WIRED | Confirmed |
| `frontend/app/dashboard/page.tsx` | `/api/v1/onboarding/me` | non-critical fetch (swallows errors) | ✓ WIRED | Confirmed |

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|-----------------|-------------|--------|----------|
| VOB-01 | 18-01, 18-02, 18-05 | Tenant-scoped aggregate + state machine under RLS, sole `Shop.published` writer, auto-approve capability | ✓ SATISFIED | Truths 1, 6, 7 above; REQUIREMENTS.md `[x]` |
| VOB-02 | 18-03, 18-04 | Automatic BUSINESS_VERIFIED + FOOD_HYGIENE_RATING gates, MANUAL_REVIEW fallback | ✓ SATISFIED | Truth 2 above |
| VOB-03 | 18-05 | ALLERGEN_DATA_COMPLETE blocks GO_LIVE | ✓ SATISFIED | Truth 3 above, incl. WR-03 TOCTOU close |
| VOB-04 | 18-01 | Config-driven thresholds/URLs, redacted secret | ✓ SATISFIED | Truth 4 above |
| VOB-05 | 18-01..18-06 | Test coverage + docs-freshness green | ✓ SATISFIED | Truth 5 above; verified independently (`docs-freshness OK: ... 918`) |

No orphaned requirements found — REQUIREMENTS.md's VOB section maps 1:1 to the 5 IDs declared across plan frontmatter.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `frontend/app/dashboard/onboarding/page.tsx` | 405, 538 | Dynamically composed `` hover:${...} `` Tailwind class never generated (IN-02, deferred) | ℹ️ Info | Cosmetic — hover-color suppression silently no-ops; does not affect functionality |
| `frontend/app/dashboard/page.tsx` | 39-44 | Banner copy wrong for SUSPENDED/REJECTED/WITHDRAWN terminal states (IN-06, deferred) | ℹ️ Info | Misleading "Start onboarding" CTA shown to a suspended/rejected vendor |
| `docs/CHANGELOG.md` | 12 | Test-baseline prose (873) stale vs. current `docs/metrics.json` (918) (IN-03, deferred) | ℹ️ Info | Prose drift only; CI gate checks metrics.json directly, unaffected |
| `.planning/ROADMAP.md` | 189-197 | Phase 18 detail section still says "Plans: 6 plans" and lists only 18-01..18-06; the Progress table (line 231) correctly shows 7/7 and the phase is marked complete at line 61 | ℹ️ Info | Documentation-only inconsistency within ROADMAP.md itself; does not affect functional verification |
| `core-java/.../onboarding/OnboardingGate.java` | 24 | `mandatory()` hardcoded per-gate, ignores `OnboardingModel` (IN-09, deferred) | ℹ️ Info | Fine for this slice (3 gates apply to both models); will force an interface change for slice-2 model-dependent gates (documented in review) |

No 🛑 Blocker anti-patterns and no unresolved `TBD`/`FIXME`/`XXX` debt markers found in the files touched by this phase.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| docs-freshness gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 918).` | ✓ PASS |
| RESUBMIT transition modeled | `grep RESUBMIT VendorOnboardingStateMachineConfig.java` | ACTION_REQUIRED→VERIFYING transition present | ✓ PASS |
| GO_LIVE/REINSTATE guard requires allergen PASSED | Read `goLiveGuard()` | Confirmed `allergenComplete` check ANDed with mandatory-gates check | ✓ PASS |
| Sole-writer invariant | `grep -n "setPublished" ShopService.java` | Only call site of `setPublished(...true)` is the state-machine side effect; create/update audited and hardened | ✓ PASS |
| Onboarding-scoped test count | `grep -rc "@Test" core-java/.../onboarding/**` | 89 `@Test` methods across 16 files | ✓ PASS (informational) |

Per the task's explicit instruction, the full gradle/npm gates were **not** re-run in this verification session (already proven green this hour on this exact tree per the provided empirical-state context); `docs-freshness.sh` (a fast, deterministic check) was re-run independently and passed.

### Probe Execution

No `scripts/*/tests/probe-*.sh` probes declared or discovered for this phase (not a migration/tooling phase in that sense). Skipped.

## Operational Note (not a code defect — needs an explicit product/ops decision)

`onboarding.auto-approve` defaults to `false` (`application.yml:194` — `${ONBOARDING_AUTO_APPROVE:false}`) and no override exists anywhere in the repo (checked docker-compose, `.env*`, k8s manifests via grep — none set it). This is a **deliberate, documented** MVP scope decision (`18-CONTEXT.md` "Open decisions (design doc §9): auto-approve vs human gate"; "admin approval queue + UI" explicitly listed as deferred to slice 2+), and the registered requirement (VOB-01) is worded as a **capability** ("auto-approve auto-advancing... so a fully-passing onboarding **can** reach live"), which is fully proven.

However, as shipped there is **no endpoint at all** that can move an onboarding out of `PENDING_APPROVAL` other than the auto-approve recompute. Under the default config, a vendor who completes every check successfully will land at `PENDING_APPROVAL` and have **zero path forward** — not "awaiting manual review" (no reviewer UI/endpoint exists), but a permanent dead end until either (a) `ONBOARDING_AUTO_APPROVE=true` is set for the deployment, or (b) an admin-approve endpoint ships. This does not fail any of the phase's registered must-haves, but it means the literal deployed-default behavior does not yet deliver "my shop goes live" for any path other than the toggle. Flagged in `human_verification` above for an explicit decision before real vendors reach this flow.

## Human Verification Required

See the `human_verification` items in the frontmatter above (5 items): live-browser walkthrough of create → submit → poll → go-live → guard-veto, visual fidelity of the three entry surfaces against the LOCKED UI-SPEC, and the auto-approve production-config decision. These are escalated rather than treated as gaps because:
1. The task's own verification context explicitly flags live-browser Playwright validation as the orchestrator's post-merge step, not yet run, and asks that UI-only gaps be routed to human_needed rather than gaps_found.
2. The code review already surfaced two real, browser-only-visible bugs adjacent to this exact surface (IN-02, IN-06) — evidence the surface has not yet been visually inspected end-to-end, which raises (not lowers) the value of a human pass.
3. The auto-approve default-config question is a product decision, not something a verifier should decide unilaterally.

## Gaps Summary

No must-have truth is FAILED. All 18 merged truths (5 ROADMAP success criteria + 13 plan-level must-haves spanning the state machine, three gates, auto-approve wiring, all six Fix-Round-1 review defects, and the UI slice) are VERIFIED against the actual code — not SUMMARY claims. The phase is functionally complete and internally consistent (docs-freshness green at 918, RlsContractTest sweep covers the new tables, requirements traceability clean).

Status is `human_needed` rather than `passed` solely because (a) a materially new, multi-step, security-relevant user flow has only been verified via jsdom/Jest and never rendered in a real browser, and (b) an operational default-config question (auto-approve) needs an explicit human answer before real vendors hit a dead end. Neither is a code-quality regression; both are appropriately escalated rather than blocking.

---

_Verified: 2026-07-11T04:55:22Z_
_Verifier: Claude (gsd-verifier)_
