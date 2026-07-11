# HANDOFF — Phase 18: Vendor Onboarding — First Slice (MVP)

**Written:** 2026-07-10 · **By:** Claude (planning session) · **For:** any agent (Claude / Cursor / Antigravity / human)
**Status:** ✅ PLANNED, ❌ NOT BUILT. No application code written this session. Nothing runs yet.

> ⚠️ **This is a PLANNING handoff.** The deliverables so far are research docs + a design doc (both merged to `main`) + 6 executable plan files on a **local-only** feature branch. The next step turns plans into code (`/gsd:execute-phase 18`) — and that first slice is **backend-only** (no UI).

---

## 1. Current goal

Ship the thinnest end-to-end vendor-onboarding slice: a `vendor_onboarding` aggregate + state machine (mirroring the Order state machine) that **owns `Shop.published`**, gated on three automated checks — Companies House (business is active), FSA FHRS (`min-rating=2`), and allergen-completeness (V41 fields). "Without manual review" is delivered via an `auto-approve` toggle.

**User story (phase goal):** *"As a food vendor, I want to auto-verify my business and hygiene rating at signup, so that my shop goes live without manual review."*

---

## 2. Environment / git state

- **Branch:** `feature/phase-18-vendor-onboarding-mvp` — **7 ahead / 2 behind `origin/main`**. Pushed? see §7 (push at wrap-up).
- **The 2 commits behind origin/main:** `e144138` (design doc #167) + `1d90e84` (k8s pg-backup #168, unrelated). The design-doc *content* was surgically brought onto this branch (commit `18c9114`), so plans' references resolve; the branch commit graph is still behind. **Before opening a PR, reconcile with `origin/main`** (merge or rebase) — verified conflict-free (no Phase-18 files exist on main; k8s #168 doesn't overlap).
- **No app code, no tests run.** There is nothing to compile yet — these are plan documents only.
- **My commits on this branch (newest first):**
  - `b22b417` fix(18): wire `onboarding.auto-approve` (HIGH-1) + guard-bean test wiring + Boolean assertions
  - `18c9114` docs(18): bring design doc onto branch (resolves broken plan refs, HIGH-2)
  - `e5e582d` docs(18): 6 MVP plans, waves 1-4
  - `b3dcd3a` docs(18): CONTEXT.md from design doc (PRD express path)
  - `1e99cb9` plan(18): add Phase 18 (MVP mode) to ROADMAP.md
- **Already on `main` (merged this session):**
  - `docs/vendor-onboarding-research.md` (PR #165) — cited UK legal/market research.
  - `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md` (PR #167) — the design contract this phase implements. **Read this first.**
  - Research report artifact (visual): https://claude.ai/code/artifact/08b0a561-b44c-4e96-8459-685645645406

### 🚨 CONCURRENT SESSION — DO NOT TOUCH
Another agent/session has **uncommitted WIP** in the working tree (a "business model decision guide" + vendor-pitch + operator-facing frontend). **None of it is mine. Do not stage, commit, stash, or revert it.** Stage only your own files (`git add <specific paths>`), never `git add -A`.
- Modified (theirs): `README.md`, `docs/DOCUMENTATION_INDEX.md`, `docs/metrics.json`, `frontend/Dockerfile`
- Untracked (theirs): `HANDOFF.md` (root — **theirs, not this handoff**), `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`, `frontend/app/business-model-guide/`, `frontend/app/for-operators/`, `frontend/components/marketing/`, `frontend/public/`, `.planning/quick/260710-business-model-decision-guide/`, `.planning/quick/260710-vendor-pitch-and-guide-audit/`

---

## 3. The plan (what to build) — 6 plans, 4 waves

Location: `.planning/phases/18-vendor-onboarding-first-slice/18-0{1..6}-PLAN.md` (+ `18-CONTEXT.md` = locked decisions, `18-HANDOFF.md` = this file).

| Plan | Objective | Wave |
|---|---|---|
| 18-01 | Flyway **V43** migration (`vendor_onboarding` + `vendor_onboarding_gate` + 2 `_aud` mirrors, tenant-scoped **FORCE RLS**), enums, audited entities + repos, `OnboardingProperties` config | 1 |
| 18-02 | Onboarding state machine (**sole writer of `Shop.published`**) + service + gate registry/runner + create/submit/status API + **auto-approve wiring** | 2 |
| 18-03 | `FOOD_HYGIENE_RATING` gate: `FhrsClient` (`x-api-version: 2` header + circuit breaker) + `FhrsGate` (min-rating=2, MANUAL_REVIEW fallback) | 3 (parallel) |
| 18-04 | `BUSINESS_VERIFIED` gate: `CompaniesHouseClient` (HTTP Basic + CB) + gate (active→PASSED, sole-trader→WAIVED) | 3 (parallel) |
| 18-05 | `ALLERGEN_DATA_COMPLETE` gate (V41 fields) + `POST /onboarding/go-live` + `Shop.published` sole-writer hardening + regression test | 3 (parallel) |
| 18-06 | Cross-gate **fully-automatic** E2E proof + `docs/metrics.json` reconcile (docs-freshness) + REQUIREMENTS/ROADMAP/CHANGELOG closure | 4 |

**Requirements:** planner minted **VOB-01..05** (mapped to the 5 ROADMAP success criteria); 18-06 registers them in `REQUIREMENTS.md` during execution (they are NOT there yet).

---

## 4. Key decisions (with rationale)

- **FHRS threshold `min-rating = 2`** (Deliveroo/Uber parity, permissive), env-overridable via `FHRS_MIN_RATING`. Decided by the user. (Design doc §9.)
- **Backend-first slice; UI deferred.** Slice 1 is state machine + gates + migration; no frontend (UI gate skipped via `--skip-ui`). An onboarding UI is a later slice (see design doc `<deferred>`). *If the priority is something visible, plan a UI slice instead — see §6.*
- **Auto-approve wiring (HIGH-1 fix):** `GateChainRunner.runAndRecompute` reads `OnboardingProperties.isAutoApprove()`; when all mandatory gates PASSED/WAIVED and the flag is `true`, it auto-fires `APPROVE` (→ `APPROVED`) so "without manual review" is reachable end-to-end. **Default `false`** (design doc §9 open decision), but the capability exists and 18-06 proves both toggle states. The APPROVE guard still applies (auto-approve can't bypass gate checks).
- **Mirror existing patterns (don't invent):** Order state-machine triad (`OrderStateMachineConfig/Service`, `OrderService.transitionOrder`), V36 migration+RLS+`_aud` template, `Product.java` entity conventions (hand-written getters, no Lombok), `StripeProperties` config pattern, `PaymentService` `@CircuitBreaker`.
- **No new dependencies:** client tests use in-memory `WebClient` `ExchangeFunction` stubs (WireMock is NOT on the classpath) → no gradle install → the supply-chain/package-legitimacy CI gate never trips.

### Correctness landmines already baked into the plans — respect them during execution
1. **`@Async` + `ThreadLocal` tenant propagation:** `TenantContext` is a plain `ThreadLocal` with **no `TaskDecorator`**. The async gate runner runs on a pool thread with **no tenant set** → RLS will DENY its writes. 18-02 mandates `runAndRecompute(id, tenantId)` re-establishing `TenantContext` (and thus the `app.current_tenant_id` GUC via `TenantSetLocalAspect`) in a **try/finally**. If tests fail with RLS/permission errors on async gate writes, this is why.
2. **FHRS API needs header `x-api-version: 2`** or it returns no data.
3. **No/ambiguous verification match → `MANUAL_REVIEW`**, never hard-fail.
4. **`Shop.published` is a nullable `Boolean`** (not primitive) — keep null-safe assertions.
5. **`gate_type` CHECK pre-lists all 8 future gate types** (only 3 implemented) to avoid a slice-2 CHECK-rewrite migration (the V36 `REFUNDED` landmine).

---

## 5. Failed approaches (don't repeat)

- **`deep-research` Workflow harness died** with `StructuredOutput retry cap (5) exceeded` on its very first structured-output agent. Pivoted to **4 parallel `general-purpose` Agents** (one per research angle) → worked well. If you need deep research here again, prefer the parallel-agent approach over that workflow.
- **Branch was cut from a stale local `main`** (predating the #167 merge) → the design doc was missing on the branch → plan refs broke (plan-checker HIGH-2). Fixed by bringing the file in. **Lesson: `git fetch && reconcile with origin/main` before branching/planning.**

---

## 6. Remaining work / next steps

**To BUILD the backend slice (verified by tests + live API, NOT a screen):**
```
git fetch origin
git checkout feature/phase-18-vendor-onboarding-mvp
# (recommended: reconcile with origin/main first — expected conflict-free)
/gsd:execute-phase 18        # fresh context recommended; Wave 3 (18-03/04/05) parallelizable
```
Expected outcome: creates `core-java/.../db/migration/V43__*.sql`, package `uk.jtoye.core.onboarding` (entities, enums, state machine, service, gates, clients, `OnboardingProperties`, controller), and tests. Then run:
```
cd core-java && ./gradlew test integrationTest    # expect new onboarding tests GREEN
```
and confirm `docs/metrics.json` was bumped (18-06) so the `docs-freshness` CI gate stays green (project standard was **775** logical invocations as of this session — re-check `CLAUDE.md`).

**If the priority is something VISIBLE (the user asked "why no frontend?"):** slice 1 has no UI by design. Options: (a) run `/gsd:ui-phase 18` then add an onboarding UI plan, or (b) plan a separate follow-on phase for a vendor-onboarding signup page. The design doc §7 (API surface) lists the endpoints a UI would call (`POST /onboarding`, `GET /onboarding/me`, `POST /onboarding/go-live`).

**Deferred (NOT in this slice):** Stripe Connect (KYC/payments), e-signature agreements, `MENU_MINIMUM` gate, admin approval UI, `@Scheduled` FHRS compliance-monitor, marketplace-vs-white-label branching, in-house couriers (worker-status/RTW/transport-hygiene). See design doc §9 open decisions.

---

## 7. Resume checklist for the next agent
- [ ] `git checkout feature/phase-18-vendor-onboarding-mvp` (local only unless pushed — check `git ls-remote --heads origin feature/phase-18-vendor-onboarding-mvp`).
- [ ] Read `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md` (design contract) + `18-CONTEXT.md` (locked decisions) before touching code.
- [ ] Do **not** stage the concurrent session's WIP (§2). Stage only Phase-18 / your own files.
- [ ] Reconcile branch with `origin/main` before any PR.
- [ ] Build via `/gsd:execute-phase 18`; watch for the `@Async` tenant-propagation landmine (§4).
