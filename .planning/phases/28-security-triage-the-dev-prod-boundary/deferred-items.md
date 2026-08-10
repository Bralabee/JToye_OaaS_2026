# Phase 28 — deferred items (out of scope for the plan that logged them)

## DI-28-01 — `check-doc-citations.sh` rc=1: phase citation-line-drift (must fix before the phase PR merges)

**Discovered by:** plan 28-11 (phase finisher), during the close-out gate sweep, 2026-08-10.
**Owner:** the plans that edited the cited source files — **28-07** (`core-java/src/main/resources/application.yml`,
`docker-compose.full-stack.yml`) and **28-09** (`docker-compose.full-stack.yml`). NOT 28-11: none of
this plan's seven `files_modified` is involved, and the citing docs below were not edited by the phase.

**What:** `bash scripts/check-doc-citations.sh` exits **1** naming 6 C-3 line-drift citations. The
phase's edits pushed lines down in the cited source files (verified: `docker-compose.full-stack.yml:644`
was `image: mailhog/mailhog:v1.0.1` on `origin/main`, now `start_period: 30s`), so codebase-doc
citations that resolved on `origin/main` now point at the wrong line:

| Citing doc:line | Cites | Now reads |
|---|---|---|
| `.planning/codebase/STACK.md:184` | `docker-compose.full-stack.yml:644` | `start_period: 30s` |
| `.planning/codebase/INTEGRATIONS.md:22` | `docker-compose.full-stack.yml:620-622` | a comment line |
| `.planning/codebase/INTEGRATIONS.md:46` | `docker-compose.full-stack.yml:534-548` | a comment line |
| `.planning/codebase/INTEGRATIONS.md:130` | `docker-compose.full-stack.yml:644` | `start_period: 30s` |
| `k8s/LOCAL.md:539` | `core-java/src/main/resources/application.yml:386` | shifted |
| `k8s/LOCAL.md:1539` | `core-java/src/main/resources/application.yml:385-388` | shifted |

**Why deferred here:** the citing docs (`.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md`,
`k8s/LOCAL.md`) and the cited source files are outside plan 28-11's declared `files_modified` (the seven
close-out docs). The SCOPE BOUNDARY rule requires logging out-of-scope discoveries rather than fixing
them inside an unrelated plan.

**Severity:** `check-doc-citations.sh` is wired into CI, so `main` goes red at merge if this is not
repaired first. **Fix before opening / merging the phase PR:** re-point the six citations at the lines
their content moved to (or drop the exact line and cite the file), then `bash scripts/check-doc-citations.sh`
rc=0 with a fail-direction shown.

## Standing / environmental (recognised, not this phase's defects)

- **`check-infra-exposure.sh` rc=1** — all 8 flagged `0.0.0.0` bindings are the cohabiting FOREIGN
  compose project `asao-*` (OlaJay's rabbitmq/redis/postgres, up before this phase). **Zero jtoye
  services fail B.** Environmental; not this repo's or this plan's defect (28-10 recorded the same).
- **`check-e2e-skip-budget.sh` rc=2 (VOID)** — the documented once-per-merge staleness detector; the
  report is re-earned by re-running the Playwright suite (needs the runtime — deferred to the
  orchestrator's Task-3 rebuild + E2E run, not touched by this docs plan).
