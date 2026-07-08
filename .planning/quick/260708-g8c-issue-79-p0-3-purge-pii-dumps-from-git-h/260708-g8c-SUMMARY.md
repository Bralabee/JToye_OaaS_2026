---
phase: 260708-g8c-issue-79-p0-3-purge-pii-dumps
plan: 01
subsystem: security / infra / ci
tags: [gdpr, pii, backups, ci-guard, gitignore, p0-3]
requires: []
provides:
  - "Off-tree db-dump storage; backups/ untracked and gone from working tree"
  - "Hardened backup.sh default BACKUP_DIR off-tree"
  - "pii-guard CI workflow rejecting tracked dumps"
  - "UK GDPR Art 33/34 breach-assessment record for P0-3"
affects:
  - infra/backups/backup.sh
  - .gitignore
  - .github/workflows/pii-guard.yml
  - docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md
tech-stack:
  added: []
  patterns: ["zero-tolerance CI ls-files guard", "off-tree backup default"]
key-files:
  created:
    - .github/workflows/pii-guard.yml
    - docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md
  modified:
    - infra/backups/backup.sh
    - .gitignore
  deleted-from-index:
    - backups/jtoye_jtoye_20251231_121414.sql.gz
decisions:
  - "Kept the exact .gitignore line `backups/` as the plan specifies (its verify enforces `grep -qxF 'backups/'`), accepting that this pattern also matches infra/backups/ at any depth; the already-tracked backup.sh is unaffected and the pii-guard uses an anchored ^backups/ grep."
metrics:
  duration: ~4min
  completed: 2026-07-08
requirements: [P0-3]
---

# Phase 260708-g8c Plan 01: Purge PII DB Dumps from Public Repo (P0-3) Summary

Closed the repo-side half of Issue #79 [P0-3]: untracked and relocated all 147 db dump/error files off-tree, redirected the nightly backup default off-tree, added a permanent zero-tolerance pii-guard CI workflow, and recorded the UK GDPR Art 33/34 breach assessment — all with zero real PII touched or printed.

## What Was Built

- **Task 1 (chore, `80bbd0a`):** `git rm --cached` on the tracked dump; created `~/jtoye-db-backups` (mode 700) and moved all 147 `backups/*.sql.gz` files off-tree via `mv` (never `cat`/`echo`); removed the now-empty `backups/` dir. Changed `backup.sh` default `BACKUP_DIR` to `$HOME/jtoye-db-backups` (env override preserved) and updated the `show_usage` help annotation. Broadened `.gitignore` from `backups/*.sql.gz` to `backups/`.
- **Task 2 (ci, `f02bf61`):** New `.github/workflows/pii-guard.yml` mirroring the `gitleaks.yml` fast-fail shape (`push` + `pull_request` on main + `workflow_dispatch`, `permissions: contents: read`, `actions/checkout@v4`). One `pii-guard` job runs `git ls-files | grep -E '^backups/|\.sql\.gz$'`; a match emits `::error::` annotations and `exit 1`, a clean tree prints OK and passes. Zero tolerance, no allowlist.
- **Task 3 (docs, `d162e82`):** `docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md` (115 lines) with all 8 required sections — incident summary, exposure window, synthetic-data-only characterization, UK GDPR Art 33/34 no-notification conclusion (recorded per Art 33(5)/5(2)), N/A credential-rotation, residual-risk (dangling blobs + GitHub Support GC), remediation log traced to P0-3, and the broken-backup-cron side-finding flagged as a separate operational follow-up. Contains no real-provider email addresses and no personal names (synthetic domains referenced as bare domains only).

## Commits

| Task | Type | Commit | Message |
| ---- | ---- | ------ | ------- |
| 1 | chore | `80bbd0a` | untrack and relocate db dumps off-tree; default backup dir to ~/jtoye-db-backups [P0-3] |
| 2 | ci | `f02bf61` | add pii-guard rejecting tracked db dumps and .sql.gz [P0-3] |
| 3 | docs | `d162e82` | PII exposure GDPR assessment for public-repo db dumps [P0-3] |

## Verification (actual output)

**Task 1** — `test ! -e backups && test -z "$(git ls-files backups/)" && [147 off-tree files] && grep default off-tree && grep -qxF 'backups/' .gitignore`:
```
PASS
```

**Task 2** — YAML parse (run in `aims_data_platform` conda env because base-python is hook-blocked) + grep gate:
```
YAML VALID
OK: guard logic passes against clean tree
```

**Task 3** — file exists, no real-provider email regex match, Art 33/34 cited:
```
PASS
---line count---
115
---P0-3 present---
3
```

**Plan-level rollup:**
```
PASS: no backups/ dir
PASS: nothing tracked (git ls-files gate)
count=147 mode=700
PASS: backup.sh default + gitignore
PASS: no trailers (no Co-Authored-By/Signed-off-by/Generated-with)
PASS: docs/metrics.json untouched
Changed: A pii-guard.yml | M .gitignore | D backups/...121414.sql.gz | A PII-EXPOSURE-ASSESSMENT | M backup.sh
```

## Deviations from Plan

**None affecting behavior.** One notable observation documented as a decision:

- **`.gitignore` pattern scope:** The plan mandates the exact line `backups/` (and its Task-1 verify enforces `grep -qxF 'backups/'`). Because git treats a pattern whose only separator is trailing as matching at *any* depth, `backups/` also matches `infra/backups/`. This is harmless here: `infra/backups/backup.sh` is already tracked (git never ignores tracked files — confirmed via `git check-ignore`), it was staged and committed normally, and the pii-guard uses an anchored `^backups/` grep so it will not false-positive on `infra/backups/`. Kept the literal `backups/` as specified rather than deviating to `/backups/`, since the plan and its verify require the exact line. Minor side effect: a *new* untracked file under `infra/backups/` would need `git add -f`.

## Environment Note

- Base-conda `python3` is blocked by the `block-base-python` hook, so the Task 2 YAML-parse verify was run via `conda run`/activate under `aims_data_platform` (which has PyYAML). Result was `YAML VALID`; guard grep gate run separately (no python) passed.

## Out of Scope (per plan)

- Git-history rewrite (git-filter-repo + force-push) and GitHub Support GC request — handled by the orchestrator post-merge.
- `docs/metrics.json` untouched (no test-count change); ROADMAP.md not updated; no push/PR opened.

## Known Stubs

None.

## Self-Check: PASSED

- `.github/workflows/pii-guard.yml` — FOUND
- `docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md` — FOUND
- `infra/backups/backup.sh` off-tree default — FOUND
- `.gitignore` `backups/` — FOUND
- `backups/` working-tree dir — ABSENT (as required)
- `~/jtoye-db-backups` — 147 files, mode 700
- Commit `80bbd0a` — FOUND
- Commit `f02bf61` — FOUND
- Commit `d162e82` — FOUND
