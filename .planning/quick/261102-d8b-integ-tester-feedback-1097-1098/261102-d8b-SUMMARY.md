---
phase: quick
plan: 261102-d8b
subsystem: integration (landing + storefront search)
tags: [integration, bundle, landing, search, v89]
status: complete
issue: none (external tester report 2026-10-30)
requires: []
provides:
  - "integ/tester-feedback-1097-1098: #1097 (f34dbe01) and #1098 (ff715677) merged with --no-ff onto origin/main 94c65ce0"
  - "HANDOFF.md RESUME HERE bullet for the tester-feedback run and its owner steps"
affects: []
tech-stack:
  added: []
  patterns: ["integration PR per the 2026-10-20 bundling ruling (precedent #1022)"]
key-files:
  created:
    - .planning/quick/261102-d8b-integ-tester-feedback-1097-1098/261102-d8b-PLAN.md
    - .planning/quick/261102-d8b-integ-tester-feedback-1097-1098/261102-d8b-SUMMARY.md
    - .planning/quick/261102-d8b-integ-tester-feedback-1097-1098/evidence/
  modified:
    - docs/CHANGELOG.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - HANDOFF.md
decisions:
  - "No CHANGELOG entry for the integration PR itself: #1022's squash added only its two source PRs' entries"
  - "Count conflicts resolved by taking #1098's side and regenerating, never by hand-merging numbers"
metrics:
  completed: 2026-11-02
plan_head_before: 94c65ce0
---

# Quick 261102-d8b: integrate #1097 and #1098

## Merges

| Order | Source | Branch | Review-Record head | Conflicts |
|---|---|---|---|---|
| 1 | #1097 | `feat/landing-fold-order-footer-chips` | `f34dbe0115d646ead6deb80e587eb4d47a0de0b3` | `docs/CHANGELOG.md` only |
| 2 | #1098 | `feat/shop-search-matches-dishes` | `ff7156774eaff33b0c02feb047c06a9a608ef3e1` | `docs/CHANGELOG.md`, `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md` |

Base: `origin/main` at `94c65ce0` (#1096's squash).

## Resolutions

- `docs/CHANGELOG.md`: both sides kept each time. Top of `[Unreleased]` now reads #1098, #1097,
  #1096, #1095, #1094, #1092, ...
- `docs/metrics.json`: after merge 1, `docs-freshness.sh` was already OK (7007) and nothing was
  edited. After merge 2, #1098's side was taken and `docs-freshness.sh --write` regenerated it:
  Java 3183 methods / 437 files, Jest 3464 blocks / 292 files, schema 89, total 7026.
- `README.md`, `CLAUDE.md`, `AGENTS.md`: #1098's side taken, then the counts `check-doc-metrics.sh`
  named were edited (total 7020 to 7026 in all three, Jest 3458 to 3464 in CLAUDE.md and AGENTS.md).
  `Current schema version: V89` and README's `**V89**` come from #1098 unchanged.

## File set

`git diff --name-only origin/main...HEAD` (99 files before this record) equals the union of the file
lists `gh pr view` returns for #1097 (86) and #1098 (18): `comm` shows nothing on either side. The
overlap is the five count and changelog files. List in `evidence/merged-file-list.txt`.

## Proof on the merged tree

| Check | Result | Evidence |
|---|---|---|
| Jest `app/__tests__/landing.test.tsx` + `components/public/__tests__` | 9 suites, 126 tests, 0 failed, rc 0 | `evidence/jest-landing-public.txt` |
| `npm run lint` | rc 0, 0 errors (26 warnings) | `evidence/npm-run-lint.txt` |
| `npm run build` | rc 0, compiled, 7/7 static pages | `evidence/next-build.txt` |
| `:core-java:integrationTest` (four classes, results dir cleared first) | rc 0; ShopSearchMatchesDishes 7/7, ProductsPublicReadRls 12/12, ProductSearchFts 17/17, RlsContractTest 7/7; 0 skipped | `evidence/gradle-integrationTest.txt`, `evidence/integrationTest-results.txt` |
| `check-changelog-contract.sh` | rc 0, 104 PRs cited | `evidence/gate-check-changelog-contract.txt` |
| `docs-freshness.sh` | rc 0, 7026 | `evidence/gate-docs-freshness.txt` |
| `check-doc-metrics.sh` | rc 0, 37/37 | `evidence/gate-check-doc-metrics.txt` |
| `check-claims.sh` | rc 0, 56 claims | `evidence/gate-check-claims.txt` |
| `check-handoff-contract.sh` | rc 0, 0 behind | `evidence/gate-check-handoff-contract.txt` |
| `check-branch-behind-base.sh` | rc 0, 0 behind `94c65ce0` | `evidence/gate-check-branch-behind-base.txt` |

Fail-first evidence lives in the source PRs' own records (`261102-q7c`, `261102-k7d`) and is not
repeated here.

## Not run here

The full `:core-java:test` and `:core-java:integrationTest`, the full Jest suite and Playwright: CI
runs them on the PR. No `docker compose` command was run; the local Compose stack is stale against main.
