---
quick_id: 260802-shv
slug: fix-alertmanager-smoke-test-fail-open
date: 2026-08-02
status: complete
---

# SUMMARY — alertmanager smoke test no longer fails open

## What was wrong

`infra/monitoring/scripts/smoke-test-alertmanager.sh` gated Test 2 (the real
`ServiceDown` path) on `docker ps ... | grep -q '^jtoye-core-java$'`, and on no-match
logged `PASS (synthetic only)` and `exit 0`.

The pattern **could not match**. `docker-compose.full-stack.yml:184` removed
`container_name` from `core-java` so it can be `--scale`d; the container is named by
compose as `jtoye_oaas_2026-core-java-1`. So the fail-open branch was not an edge
case — it was the *only* branch ever taken.

**Test 2 had been dead and reporting green since `container_name` was removed.** Test 1
still ran, but it POSTs straight to the Alertmanager API and therefore never exercises
Prometheus rule evaluation at all. The half of the smoke test that proves
`ServiceDown` actually fires and routes was proving nothing.

## What changed

| File | Change |
|------|--------|
| `infra/monitoring/scripts/smoke-test-alertmanager.sh` | Resolve the container via `docker compose ps -q "${CORE_SERVICE}"`; new `resolve_core_container()`; running-state assertion; fail closed at exit **5 (VOID)**; `ALLOW_SYNTHETIC_ONLY=1` opt-in reporting `PARTIAL`; `COMPOSE_FILE`/`CORE_SERVICE`/`CORE_CONTAINER` config vars; exit-code header updated |
| `infra/monitoring/README.md` | Line 56 named the unmatchable `jtoye-core-java`; now documents compose resolution, the override vars, and the fail-closed contract |

Additive per the Incremental Betterment Doctrine — the synthetic-only run is preserved,
it just has to be requested rather than silently defaulted into.

## Evidence — falsified in both directions

Precondition for every arm: full stack up, `core-java` = `/jtoye_oaas_2026-core-java-1`,
state `running`, health `healthy`.

| Arm | Setup | Result | Verdict |
|-----|-------|--------|---------|
| **A** — defect live | **original** gate, core-java running+healthy | logged "not running", `PASS (synthetic only)`, **exit 0** | ✅ defect reproduced |
| **B** — fixed, happy | new gate, defaults | `Resolved core-java -> /jtoye_oaas_2026-core-java-1 … state=running`, **exit 0** | ✅ |
| **C** — fixed, break | `CORE_SERVICE=does-not-exist` | `VOID — no container resolved …`, **exit 5** | ✅ fails closed |
| **D** — fixed, opt-out | unresolvable + `ALLOW_SYNTHETIC_ONLY=1` | `PARTIAL — … real ServiceDown path was NOT exercised`, **exit 0** | ✅ |
| **E** — fixed, stopped | `CORE_CONTAINER=<an exited container>` | `VOID — container … state is 'exited'`, **exit 5** | ✅ |

Arm A is load-bearing: it proves the defect was live on the running stack, not
theoretical. Arms C and E are the fail-direction proof the previous code never had.

Arms B–E ran against a harness built by `sed`-extracting the **actual shipped lines**
(`24,42p` + `109,165p`) rather than a paraphrase, truncated immediately before
`docker stop` so the live stack was never disturbed (16 containers running before and
after).

Other checks: `sh -n` rc=0, `dash -n` rc=0, executable bit preserved,
`docs-freshness.sh` / `check-doc-metrics.sh` / `check-doc-citations.sh` all rc=0.

## Evidence gaps — stated, not papered over

1. **The full end-to-end alert flow was NOT run.** It requires stopping `core-java` for
   ~4.5 min (`for: 2m` + `group_wait` + delivery) on a live 4-day-old stack, and `.env`
   sets `ALERTMANAGER_SLACK_WEBHOOK_URL`, so a real outbound Slack message could fire.
   What is proven is the **gate**: it now resolves the right container and fails closed.
   What is *not* proven is that `ServiceDown` still fires and routes end-to-end — that
   has been unproven since `container_name` was removed and remains so. Running
   `./infra/monitoring/scripts/smoke-test-alertmanager.sh` with the stack up will now
   genuinely test it, which it would not have done before.
2. **`shellcheck` is not installed on this machine**, so that check was VOID, not clean.
   `sh -n` and `dash -n` both passed.

## Follow-up worth considering

`docs/ops/terminal-states.yaml`, `infra/dependency-horizons.yaml`, `scripts/verify-env.sh`,
`scripts/k8s-local-secrets.sh` and `infra/load-testing/baseline.sh:381` also hardcode
container names. `verify-env.sh` and `k8s-local-secrets.sh` use `jtoye-postgres`, which
*does* still exist (postgres keeps its `container_name`), so they work today — but they
carry the same latent coupling this bug came from. Not fixed here; out of scope.
