---
quick_id: 260903-psy
slug: fix-docs-freshness-metrics-drift-and-sta
date: 2026-09-03
status: complete
---

# Summary — metrics drift + stale Go version strings

## Outcome

Nine doc gates green, three of which were observed RED first.

| Gate | Before | After |
|---|---|---|
| `docs-freshness.sh` | **rc=1** | rc=0 |
| `check-doc-metrics.sh` | rc=0 (green on a stale manifest), then **rc=1** after regen | rc=0 |
| `check-doc-citations.sh` | **rc=1** (7 violations) | rc=0 |
| `check-doc-versions.sh` / `check-claims.sh` / `check-project-version.sh` / `check-changelog-contract.sh` / `check-no-measured-placeholders.sh` / `check-handoff-contract.sh` | rc=0 | rc=0 |

## Part 1 — metrics drift

`docs/metrics.json` regenerated: 3572 -> 3912 logical invocations
(java_test_methods 1730->1869, java_test_files 275->295, jest_blocks 1583->1779,
jest_files 146->169, mcp_test_blocks 48->53, schema_version 64->66).
go_test_funcs 84, playwright 127/27 and mcp_test_files 8 were already correct.

22 prose claims across README.md, AGENTS.md and CLAUDE.md updated to match.
The schema bump was NOT a bare number change: V65 and V66 were given real ledger
entries in CLAUDE.md (canonical) and abbreviated ones in AGENTS.md (pointer),
written from the migrations' own headers.

**The coupling was proven, not assumed.** `check-doc-metrics.sh` passed at the
start only because the prose agreed with the stale manifest. Regenerating the
manifest flipped it to rc=1 with 22 named failures — that observed failure is
what makes its final green trustworthy.

## Part 2 — Go version strings

31 replacements across 14 files (`Go 1.26`->`1.27`, `1.26-alpine`->`1.27-alpine`,
`go 1.26.0`->`go 1.27.0`). Ground truth: `edge-go/go.mod:3` is `go 1.27.0`,
`edge-go/Dockerfile` builds on `golang:1.27-alpine`, and all three
`actions/setup-go` pins are `'1.27'`. Merged to main on 2026-08-30 in `5c1bb364` (#674).

### Deliberately NOT changed — 9 surviving references, each dated and checked

- `.github/workflows/ci-cd.yaml:130`, `scripts/check-go-coverage.sh:22,55` —
  dated MEASUREMENTS ("MEASURED ... 2026-08-28, Go 1.26"). Rewriting a
  measurement falsifies when it was taken.
- `docs/CHANGELOG.md:2089,2091,2780` — append-only history.
- `docs/architecture/ARCHITECTURE.md:265,268` — a table whose own header says
  "Reality (measured 2026-08-19)". Go moved on 2026-08-30, so 1.26 was CORRECT
  on that date.
- `infra/load-testing/baseline.sh:136` — "go 1.26.5 is present on this host".
  The host actually runs go1.26.7, still 1.26.x. Writing 1.27 there would have
  been a fabrication, not a fix.

## Found and fixed beyond the brief

`check-doc-citations.sh` was **already red on this branch** and is wired into CI
at `.github/workflows/ci-cd.yaml:928`, so the branch was failing before this task.
7 violations: 4 were mine (bad citations the codebase-map refresh introduced in
STACK.md / INTEGRATIONS.md), 3 were pre-existing line drift in `k8s/LOCAL.md`
(`configmap.yaml:145`->`166`, `application.yml:442`->`456` x2 — the claimed
content had simply moved). All 7 fixed.

## Known gate blind spot (NOT closed — needs a decision)

`scripts/check-doc-versions.sh` reads Gin from `edge-go/go.mod` but never the Go
version itself, which is why 119 version claims passed while every doc said 1.26.
Nothing prevents this regressing. A rule would close it; not added unasked.

## Adjacent stale claims found, NOT fixed (out of scope)

- `docs/architecture/ESSENTIAL_ARCHITECTURE.md:78` — "JDK 21 (JDK 25 breaks
  Gradle 8.10)" and "Next.js 16.2.12". The tree is JDK 25 / Gradle 9.7.1 /
  Next 16.3.2. Only the Go clause on that line was corrected.
- `.github/workflows/base-image-freshness.yml:9` — still names
  `eclipse-temurin:21-jre-alpine`; `core-java/Dockerfile:27` is `25-jre-alpine`.
- `docs/architecture/ARCHITECTURE.md:265` — "63 migrations" in a dated row.
