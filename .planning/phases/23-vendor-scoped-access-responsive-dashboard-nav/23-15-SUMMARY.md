---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 15
subsystem: docs
tags: [phase-gate, docs-freshness, openapi-snapshot, metrics, requirements-reconcile, anti-false-green, vendor-scoped-access]
gap_closure: true

# Dependency graph
requires:
  - phase: 23-08..23-14
    provides: "the gap-closure code + suites whose counts/proofs this plan reconciles into the record"
  - phase: 23-16
    provides: "the now-green full :core-java:integrationTest task (331/0) that lets the count reconcile + requirement closure proceed over a genuinely green suite (anti-false-green)"
provides:
  - "Both known-red Phase 23 CI gates are green: OpenApiSnapshotTest (check mode, 4 /api/v1/staff endpoints incl. /me) + docs-freshness (total 1573 / schema 57, EXIT 0)"
  - "docs/metrics.json + CLAUDE.md + AGENTS.md reconciled to the shipped gap-closure state"
  - "REQUIREMENTS.md VSA-02 + VSA-04 marked Complete, each backed by a named green automated proof"
  - "23-VALIDATION.md gap-closure proof table (23-08..23-16, one automated command per proof) + nyquist_compliant true"
  - "23-CONTEXT.md D-04/D-05/D-12 dated revision notes (appended); deferred-items.md — every conscious deferral recorded"
affects: [phase-23-pr, gsd-secure-phase-23, gsd-verify-work]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Phase-gate reconcile: regenerate the OpenAPI snapshot + docs-freshness manifest ONLY over a genuinely green full suite; the reconcile is meaningless (a green-by-construction lie) over a red one"
    - "Anti-false-green requirement closure: a requirement is marked Complete only when every sub-gap is closed AND a named automated command proves it green in 23-VALIDATION.md"

key-files:
  created:
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/23-15-SUMMARY.md
  modified:
    - docs/api/openapi-snapshot.json          # Task 1 (adc1c58, prior session)
    - docs/metrics.json                        # Task 2
    - CLAUDE.md                                # Task 2
    - AGENTS.md                                # Task 2
    - .planning/REQUIREMENTS.md                # Task 3
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/23-VALIDATION.md   # Task 3
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/23-CONTEXT.md      # Task 3
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/deferred-items.md  # Task 3

key-decisions:
  - "schema_version moved 56 → 57 — V57 (shop_staff.grant_source) shipped in 23-14 (user ACCEPTED the Task-0 checkpoint), confirmed by the migration file + 23-14-SUMMARY"
  - "The pre-existing RegExp.test( counting quirk (manifest 365 vs jest 360) was deliberately NOT fixed — fixing it would shift the committed baseline (its own change, per SCOPE BOUNDARY)"
  - "VSA-02 + VSA-04 marked Complete only now — anti-false-green held them PENDING across 23-08..23-16 until every sub-gap closed and the full integrationTest went green"
  - "Playwright vendor-authenticated E2E recorded as an env-deferred manual check (no E2E_VENDOR_PASSWORD), NOT claimed as a pass; the Java+Jest green suite is the load-bearing anti-false-green proof"
  - "AGENTS.md schema narrative (frozen at V37, pre-existing) left untouched per SCOPE BOUNDARY; only the line-15 count prose (the fcq sync contract) reconciled — staleness logged in deferred-items"

requirements-completed: [VSA-02, VSA-04]

# Metrics
duration: ~30min (Tasks 2-3 continuation; Task 1 shipped in a prior session as adc1c58)
completed: 2026-07-21
---

# Phase 23 Plan 15: Phase-Gate Reconcile (OpenAPI + docs-freshness + planning record) Summary

**Closed the two known-red Phase 23 CI gates and reconciled the planning record to what shipped: the OpenAPI snapshot now carries all four `/api/v1/staff` endpoints (incl. `/me`), `docs/metrics.json` + CLAUDE.md + AGENTS.md moved 1511 → 1573 / schema 56 → 57 over a genuinely green suite, and VSA-02 + VSA-04 are marked Complete — each backed by a named green automated proof, with every conscious deferral recorded rather than dropped.**

## Suite Basis (run BEFORE the reconcile — anti-false-green)

The reconcile is meaningless over a red suite, so the suite was confirmed green first:

- `./gradlew :core-java:test --rerun-tasks` → **BUILD SUCCESSFUL in 46s** (genuine re-run, 0 failures; the initial run was UP-TO-DATE cached, so it was forced).
- `./gradlew :core-java:integrationTest` → **331 tests, 0 failed, 1 skipped** — proven by 23-16 (`82ce129`); the working tree is unchanged since, and Task 1's snapshot regen (`adc1c58`) predates that run, so `OpenApiSnapshotTest` check-mode already passed within the 331. The 33-minute task was not re-run (nothing changed to invalidate it).
- `cd frontend && npm run build` → **clean** (Next.js/tsc); `npx jest` → **360 passed / 360 total** across 56 suites.
- `cd frontend && npx playwright test` (vendor-authenticated) → **environmentally deferred** — `E2E_VENDOR_PASSWORD` is not available in the session (a documented limitation carried since 23-05 / 23-07 / 23-13) and port-3000 needs a frontend rebuild. `docs-freshness` counts static `test()` blocks by grep (never runs Playwright), so the count reconcile is unaffected. The Java + Jest green suite is the load-bearing anti-false-green proof.

## Accomplishments

### Task 1 — OpenAPI snapshot (shipped earlier this plan as `adc1c58`)
`docs/api/openapi-snapshot.json` carries all four staff routes: `GET /api/v1/staff`, `GET /api/v1/staff/me` (new in 23-12), `POST /api/v1/staff/grant`, `DELETE /api/v1/staff/{id}` (`grep -c "api/v1/staff"` → 4, `/me` → 1). `OpenApiSnapshotTest` passes in check mode. Verified once here (grep) — not re-run.

### Task 2 — docs-freshness / metrics reconcile (`49bed3c`)
- `scripts/docs-freshness.sh --write` moved `docs/metrics.json`: **total 1511 → 1573**, **schema_version 56 → 57**, `java_test_methods` 1010 → 1064 (files 175 → 180), `jest_blocks` 357 → 365. Go / Playwright / MCP counts unchanged.
- CLAUDE.md **and** AGENTS.md line-15 count prose reconciled to the new manifest (the 260715-fcq sync contract).
- CLAUDE.md schema narrative updated: V57/V52 [Phase 23] now described as shipped, V53 still RESERVED for Phase 24 (was the stale "V56 / V52-V53 reserved").
- `scripts/docs-freshness.sh` (check mode) → **EXIT 0** ("total logical invocations: 1573").
- The pre-existing `RegExp.test(` quirk (manifest 365 vs `npx jest` 360 — a ~5 delta) was left untouched; the gate stays self-consistent (manifest == computed).

### Task 3 — planning-record reconcile (`d317e44`)
- **REQUIREMENTS.md:** VSA-02 and VSA-04 status → **Complete**, each with its gap-closure plan list + named green proofs; the `- [x]` checkboxes (already set) are now genuinely correct.
- **23-VALIDATION.md:** all per-requirement rows → ✅ green; added a **Gap-Closure Proofs** table (23-08..23-16) with one automated command + file per proof (`grep -c` for the plan IDs → 10, ≥7 required); `nyquist_compliant` + `wave_0_complete` → true; Approval → validated; the live vendor-auth Playwright recorded in Manual-Only as env-deferred.
- **23-CONTEXT.md:** dated revision notes **appended** (originals preserved) to D-04 (fail-closed + JIT provenance), D-05 (membership cache made real + post-commit eviction), D-12 (strict-scoping now genuinely tightens) — user ACCEPTED at the 23-14 Task-0 checkpoint.
- **deferred-items.md:** every conscious deferral recorded with a one-line reason (see below) + the RESOLVED items (OpenAPI regen, count bump, CSV per-row handling).

## Task Commits

1. **Task 1: Regenerate OpenAPI snapshot** — `adc1c58` (prior session; verified here)
2. **Task 2: docs-freshness / metrics + CLAUDE.md/AGENTS.md reconcile** — `49bed3c`
3. **Task 3: planning-record reconcile (REQUIREMENTS/VALIDATION/CONTEXT/deferred)** — `d317e44`

## Requirement Closure (anti-false-green)

| Req | Closed by | Named green proof |
|-----|-----------|-------------------|
| **VSA-02** (app-layer enforcement) | 23-08 (CR-03/CR-04 fail-closed), 23-10 (CR-01 cache-bypass), 23-11 (CR-02 STOMP gate), 23-14 (CR-07 strict-scoping) | `*ShopAccessFailClosedIntegrationTest` (7/7), `*ShopAccessCacheBypassIntegrationTest` (5/5), `*ShopAccessEnforcementIntegrationTest`, `*TenantChannelInterceptorTest`, `*StrictScopingTighteningIntegrationTest` (5/5) — all inside the green 331/0 |
| **VSA-04** (staff management) | 23-09 (CR-05/CR-06 + last-GA-409), 23-12 (WR-05/CR-08 backend/WR-10), 23-13 (CR-08 frontend), 23-14 (JIT provenance) | `*StaffManagementIntegrationTest` (19/19), `*GdprErasureIntegrationTest`, dashboard/hooks jest (360/360) |

Both were held PENDING across 23-08..23-16 precisely because a sub-gap or the full suite was still red; they are closed only now that every sub-gap is fixed and `integrationTest` is 331/0.

## Deviations from Plan

**1. [Scope — honesty fix, within Task 2 files] CLAUDE.md schema narrative updated beyond the bare count prose.**
- The plan scoped Task 2 to "prose counts", but `docs/metrics.json` schema_version moved to 57 and CLAUDE.md still read "Current schema version: V56 … V52/V53 RESERVED" — both now false. Left stale, it would contradict the plan's own objective ("documentation tells the truth"). Updated the CLAUDE.md schema opening to describe V52 + V57 as shipped and V53 as still reserved for Phase 24. Additive, factual (from 23-14-SUMMARY + the migration file), rest of the paragraph untouched. Committed in `49bed3c`.

**2. [Discovery — out of scope, logged not fixed] AGENTS.md schema narrative frozen at V37.**
- AGENTS.md is not line-aligned with CLAUDE.md and its schema prose (line ~107) reads "Current schema version: V37" — long unmaintained, unrelated to Phase 23, and outside the fcq count-sync contract (which syncs line-15 only). Reconstructing V38→V57 is its own doc task. Left untouched per SCOPE BOUNDARY; logged in deferred-items so it is not silently dropped.

No production code changed in this plan. No package installs. No architectural (Rule 4) decisions.

## Deferred Items (all recorded in deferred-items.md, each with a reason)

- **WR-04** — products/marketing screens narrow client-side over a single server-paginated page (wrong counts / false empty / unreachable rows). Highest-priority: a genuine user-visible correctness defect, but NOT a security bypass (set already grant-scoped server-side). Needs new `?shopId=` API surface + gating + two screen reworks → its own plan.
- **WR-03** — post-revocation SSE window, bounded at 5 min by `SSE_TIMEOUT` (23-11).
- **IN-01** — `fetchMyShops` hard-codes `size=200` (23-13).
- **`asSystem()` ThreadLocal marker** for the retained, non-externally-reachable `auth == null` bypass (23-08).
- **`@PreAuthorize` scope backstop on `StaffController`** → issue #206 (23-08).
- **T-23-08-06** — `@Async`/`@Scheduled`/`@RabbitListener` without SecurityContext propagation — tracked residual, no gated service currently reached from such a path (23-08).
- **Bulk-revoke of JIT rows** in the staff screen — convenience, not a boundary (23-14).
- **Vendor-authenticated Playwright E2E (live run)** — env-blocked (no `E2E_VENDOR_PASSWORD` + frontend rebuild); run at the phase PR.
- **AGENTS.md schema narrative** stale at V37 (pre-existing, out of count-sync scope).

*Not deferred — RESOLVED: CSV whole-batch abort. 23-10 (WR-07) delivers per-row 400 + batch-continues, so there is no inconsistency to carry.*

## Threat Flags

None. This plan touches only generated artifacts (from annotations, no credentials) and planning docs; no new network endpoint, auth path, or schema surface. Per T-23-15-04, if gitleaks/GitGuardian flags anything in the snapshot diff, investigate before merging (GitGuardian is not a required check).

## Next Phase Readiness

- **Both known-red CI gates are green** — `OpenApiSnapshotTest` (check) + `docs-freshness` (EXIT 0).
- **Recommended next actions (post-execution routing, not this plan's tasks):**
  1. `/gsd:secure-phase 23` → `SECURITY.md` (load-bearing given the eight criticals this gap-closure wave addressed).
  2. `/gsd:verify-work` → re-verify VSA-02 + VSA-04 against the closed state.
  3. At the phase PR: rebuild the frontend + supply `E2E_VENDOR_PASSWORD`, then run the live vendor-authenticated Playwright pass over `/dashboard/staff` + `dashboard-mobile.spec` 375px.

## Self-Check: PASSED

- Files verified present (8): `docs/api/openapi-snapshot.json`, `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `.planning/REQUIREMENTS.md`, `23-VALIDATION.md`, `23-CONTEXT.md`, `deferred-items.md` — all FOUND.
- Commits verified in git: `adc1c58` (Task 1), `49bed3c` (Task 2), `d317e44` (Task 3) — all FOUND.
- Gate evidence: `scripts/docs-freshness.sh` → EXIT 0 (total 1573); `grep -c "api/v1/staff" docs/api/openapi-snapshot.json` → 4; Task 3 verify grep (23-08..23-14 in 23-VALIDATION.md) → 10 (≥7).
- No tracked files deleted across the three commits.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-21*
