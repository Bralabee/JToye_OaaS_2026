---
quick_id: 260903-psy
slug: fix-docs-freshness-metrics-drift-and-sta
date: 2026-09-03
status: in-progress
---

# Fix docs-freshness metrics drift and stale Go version strings

## Why

Two independent stale-doc defects, found while refreshing `.planning/codebase/`:

1. **Metrics drift (gate RED).** `scripts/docs-freshness.sh` exits 1 on this branch.
   The QA-remediation work added tests without regenerating `docs/metrics.json`.
2. **Go version (gate BLIND).** `edge-go/go.mod` has been on `go 1.27.0` since the
   dependabot bump `5c1bb364` (#674), merged to main, but prose still says 1.26.

## The coupling that makes part 1 non-trivial

`scripts/check-doc-metrics.sh` currently PASSES (37 claims, 3 docs) *because* the
prose matches the stale manifest. Regenerating `docs/metrics.json` alone will flip
it RED. Both halves must move together.

## Tasks

- [ ] T1 Observe fail direction of gate 1 (already: rc=1 before any change)
- [ ] T2 Regenerate `docs/metrics.json` via `scripts/docs-freshness.sh --write`
- [ ] T3 Observe gate 2 go RED — proves it can fail, and proves the coupling is real
- [ ] T4 Update the prose claims in README.md / AGENTS.md / CLAUDE.md
- [ ] T5 Both gates exit 0
- [ ] T6 Go 1.26 -> 1.27 in factual stack statements only
- [ ] T7 Verify with control-armed searches; commit

## Explicitly NOT changed

- `.github/workflows/ci-cd.yaml:130` — "MEASURED with Go 1.26" is a HISTORICAL
  record of when a measurement was taken. Rewriting it would falsify the record.
- `~/.claude/agents/oaas-edge-go.md` — lives in the dotfiles repo, which merges
  only on the primary machine under its own policy. Flag, do not edit from here.

## Known gate blind spot

`scripts/check-doc-versions.sh` reads Gin from `edge-go/go.mod` but never the Go
version itself, so nothing prevents the 1.26 prose regressing. Offer, do not
silently add, a rule.
