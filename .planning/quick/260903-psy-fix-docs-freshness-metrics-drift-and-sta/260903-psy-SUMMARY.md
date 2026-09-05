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

## Gate blind spot — CLOSED in follow-up `26adae32`

`scripts/check-doc-versions.sh` read Gin from `edge-go/go.mod` but never the Go
directive, which is why 119 version claims passed while every doc said 1.26.

`go_version()` + a `Go` SPECS row now close it. The row reports MAJOR.MINOR, and
that normalisation is the whole trick: go.mod carries three parts (`go 1.27.0`)
while every consumer carries two — the docs (`Go 1.27`, `Go 1.27+`), the
Dockerfile tag (`golang:1.27-alpine`, written `Go: 1.27-alpine` in stack lists)
and the `actions/setup-go` pin. One row covers every form; a three-part actual
would have failed each two-part claim. Unlike the Gradle row the floor form IS
matched, since `Go 1.27+` reduces to 1.27.

**It found a real defect on its first run**, which is the only reason to trust it:
`STACK.md` still carried the superseded number inside a note *about* the stale
prose. The row is total over its form, so a sentence naming the old version fails
the rule it describes — the "a doc rule must not name the token it forbids" shape.
The note now states the fact without writing the number, and says why.

### Proof bracket — clean → 3 arms → clean, restores verified by content hash

Run AFTER committing, so the restore target was a committed state.

| Step | Expect | Got |
|---|---|---|
| opening clean | rc=0 | rc=0 |
| arm A — `CLAUDE.md` plain form `Go 1.27` → 1.26 | rc=1 | rc=1, `DRIFT Go doc=1.26 actual=1.27` under `== CLAUDE.md ==` |
| arm B — `AGENTS.md` list form `Go: 1.27-alpine` → 1.26 | rc=1 | rc=1, same row (proves the `:?` form is matched, not just the plain one) |
| arm C — newly-added doc's `JDK 25` → 21 | rc=1 | rc=1, `DRIFT Java doc=21 actual=25` (proves the new DOCS entry is really read) |
| closing clean | rc=0 | rc=0 |

Restores confirmed by sha256 against pre-arm baselines (`7ab6068d5ed4a518`,
`c50f46ca662429bc`, `22f4271a8a9b76e4`), never by `git diff --stat`.

## Adjacent stale claims — FIXED in follow-up `26adae32`

- `docs/architecture/ESSENTIAL_ARCHITECTURE.md` — "JDK 21 (JDK 25 breaks Gradle
  8.10)" → "JDK 25 (Gradle 9.7.1 wrapper — JDK 25 requires Gradle >= 9.1)", and
  "Next.js 16.2.12" → 16.3.2. A THIRD stale claim surfaced only once the doc was
  put under the gate: its opening summary called the core JDK 21. Three wrong
  claims in the doc a reader is most likely to treat as authoritative, under a
  heading reading "The stack (fixed — do not migrate without a decision)".
  The file now sits in the gate's `DOCS` list: coverage 119 claims/6 docs → 147/7.
- `.github/workflows/base-image-freshness.yml:9` — `eclipse-temurin:21-jre-alpine`
  → `25-jre-alpine` (`core-java/Dockerfile:27`).

### Still NOT fixed, deliberately

- `docs/architecture/ARCHITECTURE.md:265` — "63 migrations" sits in a row under
  the header "Reality (measured 2026-08-19)", the same dated-measurement class as
  the Go references left alone above. Correct as of its stated date.
- `docs/architecture/ARCHITECTURE.md` is NOT in the gate's `DOCS` list, because it
  deliberately contains historical version rows that a total-over-form rule would
  flag. Bringing it in needs a per-row exclusion first, not a one-word edit.
