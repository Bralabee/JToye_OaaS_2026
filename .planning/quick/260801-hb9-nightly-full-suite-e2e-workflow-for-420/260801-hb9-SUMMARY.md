---
id: 260801-hb9
slug: nightly-full-suite-e2e-workflow-for-420
date: 2026-08-01
status: complete
refs: ["#420", "#404"]
---

# Quick Task 260801-hb9 — Summary

Closes the **CI-coverage half** of #420. The skip half landed earlier in #423.

## What shipped

| file | change |
|---|---|
| `.github/workflows/e2e-nightly.yml` | **new** — nightly (02:00 UTC) + `workflow_dispatch` job running all 126 Playwright tests against a real compose stack |
| `scripts/check-e2e-skip-budget.sh` | header comment narrowed: the stack is absent from the **per-PR** runner, not from CI as such — the nightly job now stands it up and calls this gate |

Before: CI ran `public-layout.spec.ts` only — **2 of 126**. After: the other 124 run
nightly against Postgres + Keycloak + core-java + MinIO + RabbitMQ + the real frontend.

## Verification — both directions on every assertion

| assertion | pass direction | fail direction |
|---|---|---|
| YAML is valid + actionlint clean | `actionlint` rc=0 on the real file | rc=**1** on a copy with a `with-typo-key` step key, naming line 215 |
| no `continue-on-error` | key-shaped `grep -nE '^[[:space:]]*continue-on-error[[:space:]]*:'` rc=**1** (absent) | rc=**0** on a copy with the skip-budget step muted |
| service list omits ollama only | `docker compose config --services` = 14; listed = 12; `comm` diff = exactly `ollama`, `ollama-init` | listed-but-absent set is empty (a typo'd service would abort `up`) |
| the wait loop actually polls | 3 iterations then normal completion, rc=0 | — (see note) |
| repo gates unaffected | **19/19 rc=0** after the change | — |

**A naive `grep -c continue-on-error` returned 1, not 0** — the header comment names the
string it forbids, so the grep fires on its own definition. That is the documented
"doc rule that must name the token it forbids" trap; the key-shaped pattern above is the
honest form, and it was falsified before being trusted.

**A suspected `set -e` bug was disproved by test, not by argument.** I believed
`[ a ] && [ b ] && exit 0` inside a `while` body would trip `set -e` on the first poll.
It does not: a non-final command in an `&&` list is exempt. A 6-line repro settled it in
seconds; reasoning alone would have produced a wrong "fix".

## What is NOT proven, stated plainly

**The nightly job has never run.** It cannot be proven green from a feature branch:
`schedule` only fires on the default branch, and a `workflow_dispatch` run needs the
workflow merged first. The deliverable is a correct, fail-closed, lint-clean workflow
plus a manual trigger so the first run can be watched deliberately.

**Expect the first run to need adjustment.** Realistic first-run risks, in order:
1. **Disk.** Four built images + eight pulled on a ~14 GB runner. A reclaim step is
   included; if it still ENOSPCs, drop `mcp-server`/`edge-go` from `SERVICES` — no spec
   drives them directly.
2. **Bring-up time.** The 600 s deadline covers Keycloak import + 60 Flyway migrations +
   `DemoDataSeeder`. Generous locally; unmeasured on a 4-vCPU runner.
3. **Rate limiting.** Compose widens the public limiter to 600/min for exactly this
   reason (#409), and that default is baked into the compose file, so it carries over.

## Design decisions (do not re-litigate without reading)

- **Nightly, not per-PR** — ~20 min build + ~20 min suite is a tax no single change should
  pay. The per-PR `frontend-e2e` job stays stack-free deliberately; the moment it needs a
  backend, the cheap layout gate is lost.
- **ollama/ollama-init excluded** — `gemma3:12b` is ~8 GB on a ~14 GB runner, and `ollama`
  reserves an nvidia device no GitHub runner has. `core-java.depends_on` never references
  it and no spec asserts on image analysis.
- **Secrets generated per-run, not stored** — all 16 `REQUIRED_VARS` are throwaway for a
  ~40-minute stack. No repository secret is consumed, so the workflow cannot leak one.
  `verify-env.sh` runs *before* the 20-minute build, so a malformed `.env` fails fast.
- **`|| true` on the Playwright step is not a swallowed failure** — a suite with real
  failures must still emit a report for the gate and the artifact upload. The next step
  re-derives the verdict *from the report* and exits non-zero on `failed > 0`.

## Follow-ups

- Trigger the first run via `workflow_dispatch` once merged, and watch it.
- The 8 declared skips are untouched: 4 need Stripe test keys or a scaled stack — an
  environment decision (HANDOFF §2.2), not CI work.
