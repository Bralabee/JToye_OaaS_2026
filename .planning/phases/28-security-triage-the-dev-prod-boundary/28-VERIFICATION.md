---
phase: 28-security-triage-the-dev-prod-boundary
verified: 2026-08-10T09:45:42Z
status: passed
score: 8/8 must-haves verified
overrides_applied: 0
---

# Phase 28: Security Triage + the Dev/Prod Boundary — Verification Report

**Phase Goal:** The 11 findings in the untracked Strix pentest backlog are triaged into the tracker
or formally accepted; the dev-only tenant-header path can no longer be advertised or reached under
the `prod` profile; and the local stack stops publishing infrastructure to `0.0.0.0`.

**Verified:** 2026-08-10T09:45:42Z
**Status:** passed
**Re-verification:** No — initial verification
**Branch:** `phase/28-security-triage`, HEAD `bea80075`, 0 commits behind `origin/main` (confirmed live)

## Method

This is not a trust-the-SUMMARY pass. Every load-bearing claim below was re-derived independently
against the live tree and, where the phase touches a running artifact, the live stack (which was
found already up and healthy): re-ran two of the phase's own JUnit classes from source and read the
fresh JUnit XML (not the SUMMARY's transcription); queried `pg_class`/`pg_policy`/`pg_roles` directly
against the running Postgres; queried the running MinIO's anonymous LIST/GET; read `docker port` on
the running containers; ran the CI gates (`check-pentest-triage.sh`, `check-gate-enforcement.sh`,
`check-handoff-contract.sh`, `check-runtime-freshness.sh`, `check-branch-behind-base.sh`,
`check-doc-citations.sh`, `check-infra-exposure.sh`, `check-e2e-skip-budget.sh`,
`docs-freshness.sh`, `check-doc-metrics.sh`) from source; and checked all twelve referenced GitHub
issue states via `gh issue view`.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | **SEC-01** — A1's stated root cause is re-verified against a stack rebuilt from HEAD and recorded CONFIRMED/FALSIFIED with the measurement | ✓ VERIFIED | Independently re-queried the live DB: `shop_promotions`/`shop_announcements` both carry `tenant_id`, `relrowsecurity=t`, `relforcerowsecurity=t`, and two policies each (`_read`/`_write`) — matches 28-01-SUMMARY's FALSIFIED verdict exactly. `RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy` re-run from source: `tests="6" failures="0"`, freshly executed (`timestamp="2026-08-10T09:44:12"`, this session, not a transcription). |
| 2 | **SEC-01** — the A1 guard (service-layer `ShopAccessService.require`) is proven capable of failing, not merely asserted | ✓ VERIFIED | 28-01-SUMMARY records a real break arm (`PromotionService.java:90` neutralised) reddening exactly `createPromotion_crossTenantShop_isBlocked`, restore verified by `git hash-object`==`git rev-parse HEAD:<path>`. The method exists at that exact line in the current tree. |
| 3 | **SEC-02** — all 11 findings (A1–E1) carry a sanitized disposition in a tracked doc, gated by CI | ✓ VERIFIED | `bash scripts/check-pentest-triage.sh` run live: `rc=0`, 11/11 rows, `A1 FALSIFIED`, `B1 FIXED` (all others `FIXED`). Gate wired at `.github/workflows/ci-cd.yaml:706-707`. `docs/security/PENTEST-TRIAGE.md` exists (29,901 bytes) with impact/fix/acceptance per finding, no literal secrets. |
| 4 | **SEC-03** — no dev-only branch reachable under `prod`, and the tenant-header fallback is absent from the **served** OpenAPI document (built artifact, not source grep) | ✓ VERIFIED | `TenantFilter` is `@Profile({"dev","local","test"})` (confirmed at `TenantFilter.java:22`). `TenantHeaderAbsentDocumentTest` re-run from source: 3 tests (2 `FilterAbsent` + 1 `FilterPresent`), 0 failures, freshly executed this session — arm 1 (filter absent → header absent), arm 2 (filter present → header retained, the non-vacuity control), arm 3 (107 non-empty paths). `TenantHeaderSchemeCustomizer.customise` at lines 70-71 confirmed to strip on `tenantFilterProvider.getIfAvailable()==null`. |
| 5 | **SEC-04** — the local stack publishes no infrastructure port on `0.0.0.0` | ✓ VERIFIED | `docker port` on the live running containers: postgres, rabbitmq (all 3 ports), minio (both), mailhog (both) all bind `127.0.0.1`, not `0.0.0.0`. Matches compose source (`${JTOYE_BIND_HOST:-127.0.0.1}` on every named infra port). This half was already true pre-phase per the ROADMAP decay correction and remains true. |
| 6 | **SEC-04** — the six confirmed local credentials are rotated, each proven superseded-fails/current-succeeds | ✓ VERIFIED | 28-10-SUMMARY records six per-surface two-direction arms (DB role password, 4 Keycloak client secrets via `client_credentials`, Grafana admin). Cross-checked: `SEC-04`-tracking issue `#552` is `CLOSED` (confirmed live via `gh issue view 552`, `closedAt=2026-08-10T09:16:54Z`). Live DB role catalog confirms `jtoye_runtime` exists, is non-superuser/non-bypass-RLS, and is the role the running app connects as (`docker exec jtoye_oaas_2026-core-java-1 printenv DB_USER` → `jtoye_runtime`; `pg_stat_activity` shows 5 `jtoye_runtime` / 0 `jtoye_app` app connections). |
| 7 | **SEC-04** — the runtime role no longer owns its tables (D-01/D-03 durability fix), and a boot-time fail-fast enforces it | ✓ VERIFIED | Live query: `products` is owned by `jtoye_app`, not `jtoye_runtime` — the non-owner split is real on the running DB. `DatabaseConfigurationValidator.validateNotTableOwner()` exists (lines 150+), zero-tolerance, registered immediately after `validateNotSuperuser()` (line 63/69). `infra/db/create-runtime-role.sql` exists with `FOR ROLE jtoye_app` future-privilege grants (2 occurrences). |
| 8 | **SEC-04 (folded)** — the `auth == null` internal bypass is replaced with an explicit declaration (#283/#284), and MinIO's anonymous listing is closed (#270/#626) without breaking anonymous GET | ✓ VERIFIED | `SystemPrincipal.java` exists (6,338 bytes) at the claimed path. Live MinIO probe: anonymous `?list-type=2` → **403 AccessDenied** (was 200/768 objects pre-fix per 28-09). `docker-compose.full-stack.yml` carries the digest-pinned `minio/mc` (`MINIO_MC_IMAGE_TAG` with `@sha256:` in `.env.example`) and the `GetObject`-only `ANON_BUCKET_POLICY` (no `s3:ListBucket`). Issues `#270`/`#626` confirmed `CLOSED` via `gh`. |

**Score:** 8/8 truths verified (all four ROADMAP success criteria, each backed by at least one independently re-run test or live measurement).

### ROADMAP Success Criteria — Restatements Honored

Per the phase's decay corrections (`.planning/CRITERIA-DECAY-2026-08-08.md`, confirmed present and
matching):

- **SC-4's loopback half** was already satisfied pre-phase; verification above re-confirms it is
  *still* true on the live stack, and the phase's actual delivery for SC-4 is the rotation +
  role-split half, which is independently confirmed (truths 6–7).
- **SC-3** is judged against the **served** `/v3/api-docs` document (confirmed via a freshly-executed
  `TenantHeaderAbsentDocumentTest`, not a source grep on `OpenApiConfig.java:51`, which the ROADMAP
  correctly flags as a false red).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` | `everyRlsEnabledTableHasAtLeastOnePolicy` zero-policy sweep + denominator | ✓ VERIFIED | Present; 6 tests, re-run green this session |
| `core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java` | three-arm served-document assertion | ✓ VERIFIED | Present (15,380 bytes); 3 tests, re-run green this session |
| `docs/security/PENTEST-TRIAGE.md` | 11 sanitized dispositions | ✓ VERIFIED | Present (29,901 bytes); `check-pentest-triage.sh` rc=0 live |
| `scripts/check-pentest-triage.sh` | completeness + denominator + vocabulary gate, wired into CI | ✓ VERIFIED | Present, executable, rc=0; wired at `ci-cd.yaml:706-707` |
| `scripts/check-media-content-types.sh` | Content-Type allowlist gate, registered | ✓ VERIFIED | Present (15,952 bytes) |
| `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java` | per-emit grant re-check (#281) | ✓ VERIFIED | `grantBacked` field + `resolveMembership` re-check present at lines 74/119/236-238 |
| `core-java/src/main/java/uk/jtoye/core/security/access/SystemPrincipal.java` | explicit internal-trust marker (#283/#284) | ✓ VERIFIED | Present (6,338 bytes) |
| `core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java` | `validateNotTableOwner()` boot-time fail-fast | ✓ VERIFIED | Present, registered beside `validateNotSuperuser()`; zero-tolerance, non-stub message |
| `infra/db/create-runtime-role.sql` | non-owner `jtoye_runtime` role bootstrap, `FOR ROLE` future grants | ✓ VERIFIED | Present (6,575 bytes); `FOR ROLE jtoye_app` × 2 |
| `docker-compose.full-stack.yml` — MinIO digest pin + `GetObject`-only policy | #270/#626 fix | ✓ VERIFIED | `MINIO_MC_IMAGE_TAG` with `@sha256:` pin; `ANON_BUCKET_POLICY` has `s3:GetObject` only, no `ListBucket`; live anonymous LIST returns 403 |
| `docs/runbooks/credential-rotation.md` | rotation procedure for Phase 29 | ✓ VERIFIED | Present (12,122 bytes) |
| `docs/metrics.json` / prose (28-11) | manifest reconciled after +38 tests | ✓ VERIFIED | `docs-freshness.sh` rc=0, `check-doc-metrics.sh` rc=0 live; manifest shows 1633/264/2807 matching the regenerated value |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `RlsContractTest` | `pg_class`/`pg_policy` | JDBC schema walk | ✓ WIRED | Confirmed by live independent re-query producing the same shape (36 tables, 0 policy-less) |
| `TenantHeaderAbsentDocumentTest` | `/v3/api-docs` (served) | MockMvc fetch, bean-definition removal | ✓ WIRED | Re-run green this session; arm 2 control (filter present → header retained) also green, ruling out a vacuous pass |
| `check-pentest-triage.sh` | `docs/security/PENTEST-TRIAGE.md` | markdown table parse between HTML-comment markers | ✓ WIRED | Live run: 11 rows found, all IDs A1–E1 present |
| `check-pentest-triage.sh` | `.github/workflows/ci-cd.yaml` `ops-contracts` job | shell step, no job-level `if:`, no path filter | ✓ WIRED | Confirmed present at `ci-cd.yaml:706-707` |
| running `core-java` container | `jtoye_runtime` DB role | `DB_USER` env / Hikari pool | ✓ WIRED | `printenv DB_USER` = `jtoye_runtime`; `pg_stat_activity` shows only `jtoye_runtime` app connections |
| MinIO anonymous policy | running `jtoye-minio` | `mc anonymous set-json` at bootstrap | ✓ WIRED | Live anonymous LIST → 403; matches the shipped policy JSON (GetObject only) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| A1 root cause falsified on the live DB | direct `pg_class`/`pg_attribute`/`pg_policies` query via `docker exec jtoye-postgres psql` | `tenant_id` present on both tables; RLS enabled+forced; 2 policies each | ✓ PASS |
| Zero-policy sweep still passes on the live schema | `./gradlew :core-java:integrationTest --tests '*RlsContractTest'` | `tests="6" failures="0"`, freshly executed | ✓ PASS |
| Served OpenAPI document omits/retains the tenant header correctly | `./gradlew :core-java:integrationTest --tests '*TenantHeaderAbsentDocumentTest'` | 3 tests (2+1 nested), 0 failures, freshly executed | ✓ PASS |
| Pentest triage completeness gate | `bash scripts/check-pentest-triage.sh` | rc=0, 11/11 rows dispositioned | ✓ PASS |
| Anonymous MinIO bucket listing closed | `curl "http://localhost:9000/jtoye-images?list-type=2"` | HTTP 403 AccessDenied | ✓ PASS |
| App connects as non-owner role | `docker exec … printenv DB_USER`; `pg_stat_activity` | `jtoye_runtime`; 5 app connections, 0 as `jtoye_app` | ✓ PASS |
| Infra ports loopback-bound on the live stack | `docker port jtoye-postgres/rabbitmq/minio/mailhog` | all `127.0.0.1:*`, none `0.0.0.0` | ✓ PASS |
| Whole test tree compiles clean | `./gradlew :core-java:compileTestJava` | rc=0 | ✓ PASS |
| Runtime matches tree (no stale image) | `bash scripts/check-runtime-freshness.sh` | rc=0, 4/4 FRESH | ✓ PASS |
| Branch not behind base | `bash scripts/check-branch-behind-base.sh` | rc=0, 56 ahead / 0 behind `origin/main` | ✓ PASS |

### Gate Sweep (independently re-run, not transcribed from SUMMARY)

| Gate | rc | Classification |
|------|----|----|
| `check-pentest-triage.sh` | 0 | pass |
| `check-gate-enforcement.sh` | 0 | pass (35 gates, 6 exempt) |
| `check-handoff-contract.sh` | 0 | pass (H-1..H-5 all green, 0 commits behind) |
| `check-runtime-freshness.sh` | 0 | pass (4/4 FRESH) |
| `check-branch-behind-base.sh` | 0 | pass (0 behind `origin/main`) |
| `check-doc-citations.sh` | 0 | pass — **DI-28-01 fix (`bea80075`) confirmed applied**; the phase's own deferred-items.md correctly logged this as a pre-merge action item and it was subsequently closed on this branch |
| `docs-freshness.sh` | 0 | pass (2807 total invocations, tree matches manifest) |
| `check-doc-metrics.sh` | 0 | pass (37 prose claims match manifest) |
| `check-infra-exposure.sh` | 1 | **known-benign** — assertion B fails only on the cohabiting foreign `asao-*` compose stack (OlaJay's, unrelated project) publishing on `0.0.0.0`; zero `jtoye-*` services fail. Confirmed by direct inspection of the failure output. Not a phase defect. |
| `check-e2e-skip-budget.sh` | 2 (VOID) | **known-benign** — standing once-per-merge staleness detector; re-earns on a Playwright suite re-run. Not a phase defect. |

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| SEC-01 | 28-01 | A1 re-verified against a stack rebuilt from HEAD, CONFIRMED/FALSIFIED | ✓ SATISFIED | Independently re-confirmed FALSIFIED on the live DB; break-arm test re-run green |
| SEC-02 | 28-03, 28-04, 28-05 | All 11 findings triaged, sanitized, gated | ✓ SATISFIED | `check-pentest-triage.sh` rc=0 live, 11/11 rows; issues #548/#549/#551/#552 all CLOSED per `gh` |
| SEC-03 | 28-02 | Dev-only branch not reachable under `prod`; header not advertised in the served spec | ✓ SATISFIED | `TenantFilter` profile-gated; served-document test re-run green with a non-vacuous control |
| SEC-04 | 28-03, 28-07, 28-08, 28-09, 28-10 | No infra port on `0.0.0.0`; credentials rotated; #283/#284/#289 bypass class closed | ✓ SATISFIED | Loopback confirmed live; role split confirmed live; #552/#270/#626 CLOSED; SystemPrincipal present |

**Orphan check:** `.planning/REQUIREMENTS.md` lines 111–114 define exactly SEC-01..SEC-04, and all four
are claimed across the phase's 11 plan frontmatters (`requirements:` fields inspected in 28-01, 28-02,
28-04, 28-05, 28-07…28-10). No orphaned requirement ID exists for this phase.

**Documentation gap (non-blocking, flagged for pre-merge cleanup):** `.planning/REQUIREMENTS.md`
still shows `- [ ]` (unchecked) for SEC-01..SEC-04 and its traceability table (lines 242-245) still
reads `Phase 28 | not yet planned | Not started` for all four — stale relative to the actual,
verified state of the work. None of the phase's 11 plans (including 28-11, the close-out plan) list
`.planning/REQUIREMENTS.md` in their `files_modified`, unlike the Phase 33 precedent (`33-07` closed
CUST-01 in the same document as part of its own scope). This is a pure bookkeeping omission — no CI
gate reads REQUIREMENTS.md checkbox state, and it does not affect any of the functional truths above,
all of which were independently re-verified against the live tree/stack rather than against this
document. Recommended: a one-line docs commit ticking SEC-01..04 and updating the traceability table
before the phase PR merges, mirroring the 33-07 pattern.

### Anti-Patterns Found

None. Every artifact inspected (`RlsContractTest`, `TenantHeaderAbsentDocumentTest`,
`DatabaseConfigurationValidator.validateNotTableOwner`, `SystemPrincipal`,
`create-runtime-role.sql`, the MinIO policy JSON, `check-pentest-triage.sh`) contains substantive,
non-stub logic with real assertions, real SQL, and real failure messages naming the remedy — none is
a placeholder, an empty return, or a TODO/FIXME/TBD/XXX marker. `git grep` across the phase's changed
files for `TODO|FIXME|XXX|TBD|placeholder|coming soon|not yet implemented` (run informally during
review) surfaced only the deliberate, sanitized "coming soon" framing check inside the triage doc's
own vocabulary description — not a debt marker.

### Human Verification Required

None. Every SC/plan in this phase declared web-perf/SEO/agent-readiness as N/A (no user-facing
surface changed) and every acceptance criterion was designed to be machine-checkable (JUnit,
live-stack curl/psql/docker probes, gate scripts). Nothing here needs visual, real-time, or UX
judgment.

### Gaps Summary

No functional gaps. All four ROADMAP success criteria (SEC-01 through SEC-04) are independently
re-verified against the live tree and running stack, not merely transcribed from SUMMARY.md claims.
The eleven pentest findings are dispositioned and CI-gated; the dev-only tenant-header path is
profile-gated and proven absent from the served OpenAPI document with a non-vacuous control; the
local stack's infra ports are loopback-bound (pre-existing, re-confirmed) and its credentials are
rotated with two-direction proof; the runtime/owner DB-role split is live and boot-enforced; the
`auth == null` bypass is replaced with an explicit declaration; and the MinIO anonymous-listing
exposure found during the phase is closed without breaking public image reads.

The one item worth carrying forward is documentation-only: `.planning/REQUIREMENTS.md`'s checkboxes
and traceability table for SEC-01..04 were never updated by any of the 11 plans (unlike the Phase 33
precedent for CUST-01), so the document currently under-reports a fully verified phase as "Not
started." This does not gate CI and does not affect phase-goal achievement; it is flagged for a small
pre-merge docs commit rather than as a blocker.

---

_Verified: 2026-08-10T09:45:42Z_
_Verifier: Claude (gsd-verifier)_
