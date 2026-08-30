---
phase: quick-260830-l8g
plan: 01
subsystem: infra-db
tags: [provisioning, flyway, sec-04, fresh-volume]
status: complete
requires: []
provides:
  - "00-create-db.sql creates jtoye_app with DB_MIGRATION_PASSWORD (DB_PASSWORD fallback on unset/empty)"
  - "compose passes DB_MIGRATION_PASSWORD into the postgres init environment (no :? guard — absence is supported)"
  - "e2e-nightly DERIVED comment corrected: equality is a simplification, not a requirement"
affects: [infra/db/init/00-create-db.sql, docker-compose.full-stack.yml, .github/workflows/e2e-nightly.yml]
key-files:
  created: []
  modified:
    - infra/db/init/00-create-db.sql
    - docker-compose.full-stack.yml
    - .github/workflows/e2e-nightly.yml
decisions:
  - "Fallback semantics via psql: \\set migration_password '' then \\getenv (unset env leaves the var unchanged, so the pre-set empty is the sentinel), folded at the use site with coalesce(nullif(...,''), app_password) — covers unset AND set-but-empty"
  - "The nightly keeps DB_MIGRATION_PASSWORD = DB_PASSWORD deliberately: that lane now exercises the single-credential FALLBACK path while the local dev .env (differing values) exercises the split path; splitting the nightly's credentials was deferred on purpose — it is #683's confirming-instrument night"
metrics:
  duration: "~45 min (14:20–15:05Z, 2026-08-30)"
  completed: "2026-08-30"
---

# Quick Task 260830-l8g: Close #684 — fresh volume provisions its own migrator credential Summary

`infra/db/init/00-create-db.sql` now creates `jtoye_app` (the owner/MIGRATOR since
SEC-04/#552) with `DB_MIGRATION_PASSWORD`, falling back to `DB_PASSWORD` when unset or
empty; compose passes the variable into the postgres init environment; the stale
comments describing the pre-split world ("core-java connects as jtoye_app with
DB_PASSWORD") are corrected at both sites, and the nightly's must-be-equal workaround
comment now records the truth. Close condition met: a real `down -v` fresh-volume cycle
with DIFFERING credentials booted unattended to healthy with V64 applied.

## Commits

| Task | Commit | Files |
| ---- | ------ | ----- |
| fix (3 sites) | `c5d2b275` | 00-create-db.sql, docker-compose.full-stack.yml, e2e-nightly.yml |
| arms + fresh-volume cycle | (no code delta) | — |

## THE INSTRUMENT LIED FIRST — record this before the results

The first ARM 1 run authenticated `jtoye_app` with BOTH passwords on BOTH file versions.
Cause, read from `pg_hba.conf`: **in-container loopback is `trust`** in the postgres
image (`host all all 127.0.0.1/32 trust`), so `docker exec … psql -h 127.0.0.1` proves
nothing about any password — a garbage password returned rc=0. The scram rule
(`host all all all scram-sha-256`) applies only to non-loopback connections, so every
auth probe was moved to a second container over a docker network, and the instrument was
then shown able to fail (garbage password → 28P01) before any result below was trusted.

## ARM 1 — isolated throwaway containers, network-path auth, all directions

DB_PASSWORD=pwA, DB_MIGRATION_PASSWORD=pwB (different), garbage control on every run:

| arm | jtoye_app/GARBAGE | jtoye_app/pwA | jtoye_app/pwB | jtoye_runtime/pwA |
|---|---|---|---|---|
| PRE-fix file (HEAD) | 28P01 | **OK** | **28P01** ← the defect | OK |
| POST-fix file | 28P01 | **28P01** | **OK** ← the fix | OK |
| POST-fix, var unset | 28P01 | OK (fallback) | 28P01 | OK |
| POST-fix, var EMPTY | 28P01 | OK (fallback) | 28P01 | OK |

The pre-fix row is #684 reproduced exactly: Flyway presents the migration credential and
gets 28P01. `jtoye_runtime` (the application's credential) is unaffected in every arm.

## ARM 2 — the close condition: real `down -v`, differing credentials, unattended boot

Local `.env` credentials confirmed DIFFERENT by digest (4e0e7338 vs 4de0e5f6) before the
cycle. `docker compose down -v` (all volumes removed, 0 remaining) → `up -d`, nothing
touched by hand:

- core-java **healthy, RestartCount 0** (the pre-fix behaviour was a crash-loop),
  **0** `28P01`/`password authentication failed` lines in its logs
- Flyway head **V64**, 64/64 migrations successful (version read numerically —
  the TEXT-sort trap)
- role credentials proven by content over the scram path on the fresh cluster:
  `jtoye_app` authenticates with the migration credential and REJECTS `DB_PASSWORD`;
  `jtoye_runtime` authenticates with `DB_PASSWORD`
- `postcode_centroid` = 1,748,230 rows — V64's TRUNCATE grant also worked unattended on
  this cycle (#647's fix re-confirmed on a genuinely fresh volume)
- environment handed back whole: `seed-e2e-fixtures.sh` rc=0 (the new onboarding section
  reported its no-row branch on a real stack), `seed-order-metric.sh` rc=0, E2E smoke
  **45/45 passed (2.1m)** across onboarding-blocked-flow + storefront-flows — Keycloak
  realm import, vendor login, the full ONBD-05 journey, checkout with a real order and
  the Mailhog confirmation email all green on the fresh volume
- `check-runtime-freshness` PASS 4/4, `check-alert-metrics` PASS 19 rules / 25 selectors

## Deviations from plan

**1. [Rule 1 — vacuous instrument] The planned in-container auth probe could not fail.**
Found on the first ARM 1 run (both passwords "worked" everywhere); root-caused to the
image's loopback `trust` rule; instrument rebuilt onto the network/scram path and armed
with a garbage-password control. No result was recorded from the vacuous instrument.

## Self-check: PASSED

Commit `c5d2b275` on `feature/684-fresh-volume-migrator-credential`; all three modified
files present; stack running healthy on the fresh volume with all fixtures re-seeded.
