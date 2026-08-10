---
phase: 28-security-triage-the-dev-prod-boundary
plan: 10
subsystem: security / credential rotation + identity realm
tags: [SEC-04, SEC-02, "#552", D-02, D-12, keycloak, realm-import, postgres, rls, grafana, rotation, falsifiability]

# Dependency graph
requires:
  - phase: 28-05
    provides: "the D-12 realm-template payload (unused public client removed) that this plan's single import carries"
  - phase: 28-07
    provides: "jtoye_runtime role + DB_MIGRATION_USER/PASSWORD split (identifies DB_PASSWORD as the runtime-role password, DB_MIGRATION_PASSWORD as the out-of-scope migrator sibling)"
  - phase: 28-08
    provides: "the live app already running as jtoye_runtime; the products isolation arm (0/47/4/51) re-run here post-rotation"
  - phase: 28-09
    provides: "the anonymous storefront GET arm re-run post-rotation; the deferred core-java rebuild (48969b0f) absorbed here"
provides:
  - "All six confirmed local credentials rotated on the RUNNING stack, each with a superseded-fails/current-succeeds arm in the same run"
  - "ONE Keycloak import carrying both D-02 rotation and the D-12 audience decision (11 -> 10 clients on the running realm)"
  - "docs/runbooks/credential-rotation.md — the per-surface rotation procedure Phase 29's staging secrets follow"
  - "infra/keycloak/README.md realm-import provenance note (2026-08-10, D-02 + D-12)"
affects: [28-11, 29]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Rotation acceptance = superseded value REFUSED and current value ACCEPTED in the same run — a one-directional check cannot tell a rotated credential from an un-rotated one"
    - "Read the credential off the RUNNING service, never the compose file: DB via host->published-port scram (the 127.0.0.1 trust rule makes an in-container check vacuous), Keycloak via a token request, Grafana via a live login"
    - "One realm import event carries two payloads (rotation + D-12); the realm is Postgres-backed so a template edit alone is inert"

key-files:
  created:
    - docs/runbooks/credential-rotation.md
  modified:
    - infra/keycloak/README.md

key-decisions:
  - "Owner chose option include-runtime-password (2026-08-10): rotate the six inferred keys AND the jtoye_runtime password; DB_PASSWORD IS the jtoye_runtime password after 28-08, so the set is six .env keys"
  - "DB_MIGRATION_PASSWORD (the jtoye_app migrator) is explicitly OUT of scope — confirmed by reading .env key names"
  - "#552 left OPEN with ZERO unmet arms: all rotation is proven on the live dev stack but lives only on the unmerged phase branch; 28-11 upgrades the B1 triage row (OPEN-TRACKED -> FIXED) and owns closure"
  - "The DB arm is asserted over the host->published-port scram path, not in-container 127.0.0.1 (which pg_hba trust-authenticates, accepting any password)"

patterns-established:
  - "A DB-credential arm must run over a scram-authenticated path with positive AND negative controls; an in-container loopback psql is a vacuous pass under host all all 127.0.0.1/32 trust"
  - "Recover a genuinely-live superseded secret from the running consumer's env (printenv) or via kcadm when .env has already been overwritten, so the superseded arm uses the real prior value"

requirements-completed: [SEC-04, SEC-02]

# Metrics
duration: ~20min
completed: 2026-08-10
---

# Phase 28 Plan 10: Credential rotation + the D-02/D-12 realm import Summary

**All six confirmed local credentials rotated on the running stack — each proven by a superseded-fails/current-succeeds pair in the same session — with one Keycloak import carrying both the four rotated client secrets and the D-12 audience decision (11 -> 10 clients), the app re-proven healthy and RLS-subject on its new DB password, and a runbook Phase 29 follows.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-08-10T08:41:12Z
- **Completed:** 2026-08-10T09:00:43Z
- **Tasks:** 2 executed (Task 1 checkpoint resolved by owner before this session; Tasks 2–3 here)
- **Files modified:** 2 tracked (`infra/keycloak/README.md`, `docs/runbooks/credential-rotation.md`); plus live-stack state and gitignored `.env`

## Task 1 — Owner's decision (recorded with its date)

**Answer (2026-08-10): option `include-runtime-password`.** Rotate the six inferred keys AND the
`jtoye_runtime` runtime-role password — seven credentials as the owner counted them, but
`DB_PASSWORD` in the current `.env` **is** the `jtoye_runtime` password after plan 28-08, so the
surface set is exactly six `.env` keys. Confirmed by reading `.env` key NAMES (never a value):
`DB_PASSWORD` and `DB_MIGRATION_PASSWORD` are distinct keys; the migrator sibling
(`DB_MIGRATION_PASSWORD` = `jtoye_app`) is **out of scope**.

**The final rotated set, by KEY NAME (no value anywhere):**

1. `DB_PASSWORD` — the `jtoye_runtime` runtime DB-role password
2. `KEYCLOAK_CLIENT_SECRET` — realm client `core-api`
3. `EDGE_API_CLIENT_SECRET` — realm client `edge-api`
4. `INTEGRATION_CATALOG_RO_SECRET` — realm client `integration-catalog-ro`
5. `INTEGRATION_ORDERS_RW_SECRET` — realm client `integration-orders-rw`
6. `GRAFANA_ADMIN_PASSWORD` — monitoring-UI admin

No credential was rotated before the gate closed: the owner's answer predates every rotation
commit and the first live mutation. **Handoff to 28-11:** carry this decision + date into
`docs/security/PENTEST-TRIAGE.md`'s B1 row.

## Task Commits

1. **Task 2: Rotate every confirmed credential, proven on the running service** — the rotation itself mutates live DB/realm/Grafana state + gitignored `.env` (no tracked file); its tracked deliverable is the README provenance note — `3fcd7b2f` (docs)
2. **Task 3: The rotation runbook** — `26f50ca4` (docs)

**Plan metadata (this SUMMARY):** committed separately (SUMMARY only — STATE.md/ROADMAP.md owned by the orchestrator).

## Per-surface acceptance — both directions, same run

### DB role password (`DB_PASSWORD` / `jtoye_runtime`)
- **Instrument:** host `psql -h 127.0.0.1 -p 5433` (scram). The in-container `127.0.0.1` path is `trust` per pg_hba and accepted BOTH values — vacuous; discarded. Instrument validated: valid→rc0, random→rc2.
- Control: superseded value valid **before** rotation rc=0. `ALTER ROLE jtoye_runtime` rc=0.
- **SUPERSEDED old → rc=2** (`password authentication failed`); **CURRENT new → rc=0 / "1"**.
- core-java `up -d --force-recreate --no-deps core-java` → **healthy**; `pg_stat_activity`: **jtoye_runtime = 8**, jtoye_app = 0; validator logged `OWNS no public tables` + `VALIDATION PASSED`.
- `seed-order-metric.sh` rc=0 (recreate reset NoOrdersCreated).

### Four Keycloak client secrets (D-02) — via `client_credentials` token requests
| Client | BEFORE import old/new | AFTER import old/new |
|---|---|---|
| core-api | 200 / 401 | **401 / 200** |
| edge-api | 200 / 401 | **401 / 200** |
| integration-catalog-ro | 200 / 401 | **401 / 200** |
| integration-orders-rw | 200 / 401 | **401 / 200** |

Clients rotated = 4; clients verified both-direction = 4. The frontend (only runtime consumer of
`KEYCLOAK_CLIENT_SECRET`, for NextAuth) was force-recreated and carries the new secret; edge-go /
mcp-server hold none.

### The ONE import (D-02 + D-12, exactly one event)
- `kc.sh import --file /opt/keycloak/data/import/realm-export.json --override true`, server stopped, invoked **2026-08-10T08:47:53Z**, rc=0, log `Realm 'jtoye-dev' imported`, then restarted → healthy.
- **D-12 on the running realm:** client count **11 → 10**; `test-client` present before, **absent after** (removed by this same import). The staged `realm-export.template.json` was **not re-edited** (`git diff --name-only` does not list it).

### Monitoring-UI admin (`GRAFANA_ADMIN_PASSWORD`)
- Config-only-no-op trap defeated: reset against the RUNNING instance (`grafana-cli admin reset-admin-password`, "Admin password changed successfully"), because a `.env`/compose edit alone is silently ignored after first-user creation.
- Control: old login **200 before reset**. After: **SUPERSEDED old → 401**, **CURRENT new → 200**.
- `scripts/check-infra-exposure.sh` **C1/C2/C3 all PASS** (C3 = random credential rejected, the discrimination control) + D pass.
- **VOID fail-direction:** with Grafana stopped the script exits **rc=2** (`cannot measure C`); restarted → C1/C2/C3 pass again. "Cannot measure" is not read as "measured fine".

## Post-rotation whole-stack verification
- **`check-runtime-freshness.sh` rc=0**, 4/4 built services **FRESH** (after rebuilding core-java to absorb 28-09's deferred `48969b0f`).
- **28-08 isolation arm re-run** (as `jtoye_runtime`, new password): no-GUC **0**, tenant A **47**, tenant B **4**, superuser control **51**; **47+4=51** — the leading 0 is RLS, not a blind instrument.
- **28-09 anonymous storefront GET re-run:** `/api/v1/public/shops` → **200 / 1923 bytes**; MinIO storefront object → **200 / 90541 bytes**.

## Value-leak scan (T-28-53) — no credential value in any artifact
- **0** leaks across all six rotated values in tracked files (`git grep`), the on-disk worktree (real `command grep`, excluding the legitimate gitignored `.env`/`realm-export.json`), AND the commit range `a2844610..HEAD`.
- **Positive control:** the scanner matched a known value written to a scratch file OUTSIDE the repo (rc=0), then deleted — proving the grep can fire.
- Commit messages read back with `git log --format=%B`: subjects intact, no backtick execution, no number-after-keyword.

## Deviations from Plan

### Auto-fixed / instrument corrections

**1. [Rule 1 — Instrument defect] The in-container DB check was vacuous under pg_hba `127.0.0.1/32 trust`**
- **Found during:** Task 2 (DB surface). A `psql -h 127.0.0.1` run inside the postgres container accepted BOTH the old and a never-applied new password — a false pass.
- **Fix:** assert the DB credential over the host→published-port `5433` path, which pg_hba routes through `scram-sha-256`, with a positive control (valid→rc0) and a negative control (random→rc2) proving the instrument discriminates before it was trusted.
- **Committed in:** live-stack arm (no tracked file); recorded in the runbook §1 + §7 (`26f50ca4`).

**2. [Rule 3 — Blocking] `ALTER ROLE … PASSWORD :'var'` does not interpolate under `psql -c`**
- **Found during:** Task 2 (DB surface). The first ALTER raised a syntax error and silently did nothing, while `.env` had already been rewritten — the two drifted out of sync.
- **Fix:** embed the hex value directly in the ALTER (`[0-9a-f]`, SQL-safe); recovered the genuinely-live superseded value from the running core-java's env (`printenv DB_PASSWORD`) to re-align `.env` ↔ role and re-prove both directions cleanly.
- **Committed in:** live-stack arm; recorded in runbook §4 + §7.

**3. [Rule 3 — Blocking, plan-directed] core-java runtime-freshness DRIFT from a sibling plan's deferral**
- **Found during:** Task 2 post-rotation freshness. core-java's image predated commit `48969b0f` (28-09's comment-only core-java change, deliberately deferred to phase close-out).
- **Fix:** `up -d --build --no-deps core-java` (the plan's own acceptance requires a `--build`); freshness then rc=0, all 4 FRESH; app re-verified healthy + RLS-subject + metric re-seeded.
- **Committed in:** live-stack rebuild; recorded in runbook §7.

---

**Total deviations:** 3 (1 instrument, 2 blocking). **Impact:** none on scope — all were necessary to make the arms falsifiable and the stack current; no scope creep.

## Issues Encountered
- **`check-infra-exposure.sh` overall rc=1** because assertion **B** flags a cohabiting FOREIGN compose project (`asao-*`, OlaJay's stack, up 20h before this plan) publishing on `0.0.0.0`. All eight flagged bindings are `asao-postgres`/`asao-rabbitmq`/`asao-redis`; **zero jtoye services fail B** (loopback or app-tier exempt). Out of scope — the neighboring stack was not touched. The credential arm C1/C2/C3 (this plan's concern) passes.
- **`python3` and in-container `127.0.0.1` psql** both bit early; `jq`/`awk` and host psql were used instead. No functional impact.

## User Setup Required
None — all values flow through the machine-local, gitignored `.env`; no tracked config edit is required, and no external service account changed.

## Next Phase Readiness
- **28-11:** upgrade `docs/security/PENTEST-TRIAGE.md` B1 row (OPEN-TRACKED → FIXED) with the owner's decision + date; own the `#552` closure at phase close-out/merge. Reconcile `docs/metrics.json` — **this plan added 0 test invocations** (two docs commits, no Java/Jest/Playwright/Go tests), so the manifest is untouched by 28-10.
- **#552 stays OPEN with ZERO unmet arms:** every surface has both-direction proof on the live dev stack, but the remediation lives only on the unmerged `phase/28-security-triage` branch, so closure belongs at merge (with the B1 triage row upgraded in the same motion — number before keyword).
- **Phase 29:** `docs/runbooks/credential-rotation.md` §6 documents the staging path via `k8s/base/secrets-template.yaml.example` + `docs/runbooks/sealed-secrets.md`.

## Threat Model Outcomes
| Threat ID | Disposition | Evidence |
|---|---|---|
| T-28-50 credentials from the pentest still accepted | **mitigated** | all six rotated, superseded→refused / current→accepted in the same run |
| T-28-51 Grafana silently keeps the old admin password | **mitigated** | reset against the running instance; C1/C2/C3 pass incl. C3 random-reject; old→401 |
| T-28-52 Keycloak silently keeps old client secrets | **mitigated** | `kc.sh import --override true` + restart; a token request per client, old→401 / new→200 |
| T-28-53 a rotated value reaches the public repo | **mitigated** | value-grep 0 across tracked/worktree/commit-range, scanner proven able to match |
| T-28-54 rotating an inferred set while a real credential stays live | **mitigated** | Task 1 owner gate closed before any rotation; final list recorded by name |
| T-28-55 verifying against a pre-rotation image | **mitigated** | `up -d --build` + `check-runtime-freshness.sh` rc=0 per service |
| T-28-56 the phase's earlier live measurements invalidated and never re-taken | **mitigated** | 28-08 isolation arm + 28-09 anonymous GET re-run post-rotation |

Cross-cutting: web-perf **N/A**; SEO **N/A**; agent-readiness **relevant** — the machine clients' (`integration-*`) token requests are the acceptance arm and remain least-privilege. Falsifiable evidence: a two-direction arm per credential, a VOID arm on the monitoring gate, a DB instrument validated with positive+negative controls, and a value-grep proven able to match.

## Known Stubs
None. All rotation is applied to the live running services and proven both directions; nothing is placeholdered.

## Self-Check: PASSED

| Claim | Verification | Result |
|---|---|---|
| `docs/runbooks/credential-rotation.md` (199 lines) | `test -f` | FOUND |
| `infra/keycloak/README.md` provenance note | `test -f` + in diff | FOUND |
| `28-10-SUMMARY.md` | `test -f` | FOUND |
| Task 2 commit `3fcd7b2f` | `git rev-parse --verify` | FOUND |
| Task 3 commit `26f50ca4` | `git rev-parse --verify` | FOUND |
| `realm-export.template.json` untouched | named-path `git diff` empty | OK |
| `STATE.md` / `ROADMAP.md` untouched | named-path `git diff` empty | OK |
| value-leak across tracked/worktree/commit-range | 0, scanner proven able to match | OK |

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*
