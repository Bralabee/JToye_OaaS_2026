# 27-00 SUMMARY — the operational spine

**Branch:** `feature/27-00-ops-spine` · **Executed:** 2026-07-27 · **Tasks 0–6 complete.**

Plan 27-00 builds the spine the rest of Phase 27 hangs off: a register of terminal failure
states, two executable detection gates, a dependency-horizon manifest with its own gate, and the
first honest load baseline this repo has ever had.

---

## What shipped

| Task | Deliverable | State |
|---|---|---|
| 0 | Seven RED baselines B-1..B-7, each with a paired control | frozen evidence |
| 1 | core-java scrape port **injected**, not hardcoded (`prometheus.yml.tmpl` + entrypoint) | done |
| 2 | `docs/ops/terminal-states.yaml` — **16 rows**, two above the contract | done |
| 3 | `scripts/check-terminal-states.sh` — three cross-references (X-1/X-2/X-3) | **rc=1, correct** |
| 4 | `scripts/check-alert-liveness.sh` + `docs/runbooks/terminal-states.md` + additive Slack transport | **rc=1, correct** |
| 5 | `infra/dependency-horizons.yaml` (**27 rows**) + `scripts/check-dependency-horizons.sh` | rc=0 |
| 6 | `infra/load-testing/{baseline.sh,budget.yaml,README.md,baselines/}` | rc=0 |

Both `rc=1` gates are **designed to be red on this tree** and turn green when 27-03 lands the
four missing runbook sections. They are not regressions and must not be "fixed" by weakening the
assertion.

### Gate state at close

```
docs-freshness              rc=0   (1765, unchanged — this plan adds no counted tests)
check-runtime-freshness     rc=0
check-branch-behind-base    rc=0   (9 ahead, 0 behind origin/main)
check-dependency-horizons   rc=0   (27 rows, 6 active exemptions, 8 UNKNOWN, all dated)
check-terminal-states       rc=1   (4 X-3 violations — 27-03 owns them)
check-alert-liveness        rc=1   (8 live detection defects — all owned or deferred)
```

---

## The headline findings

**One EOL dependency was believed. Six were measured.** `rabbitmq/3.12` (2024-02-21),
`prometheus/2.48` (2023-12-28), `grafana/10.2` (2024-07-24), `keycloak/24.0` (2024-06-10),
`nodejs/20` (2026-04-30), `alpine-linux/3.20` (2026-04-01). That gap between recall and
measurement is the entire argument for the mechanism. AC-5.1 was run in the required order —
empty exemption list first, six failures recorded, exemptions added afterwards.

**The load-testing script in this repo could never have produced a number.** Three independent
reasons, each sufficient on its own: no HTTP load tool was installed on the host at all (B-5); it
requests its token with `client_id=core-api` and no client secret, but `core-api` is a
confidential client so Keycloak rejects every attempt; and it asserts no status code, so an
unauthenticated flood would have read as throughput anyway. **Measured: 12,156 req/s, every
response a 401.**

**27-04's blocking number now exists.** `media.process` sustains **~82 messages/sec/consumer**
(findings F-7/F-8 established this figure existed nowhere in the repo). Prefetch and
`concurrentConsumers` can be derived rather than guessed.

**The platform rate-limits itself before the application saturates.** Bucket4j at 100 req/min per
tenant with a burst of 20, and both arm-A endpoints share **one** bucket. Any throughput number
above ~120 req/min/tenant is measuring the limiter. Recorded in the artifact and README so it is
never quoted as an application ceiling.

---

## Defects found by EXECUTING the criteria, not by reading them

Seven, across both tasks. Every one was invisible to a passing exit code.

**In the horizon gate (§B of `AC-5-ARMS.md`):**

1. **H-5 matched pins inside comments** — a fail-open. `mcp-server/Dockerfile:2` is prose naming
   `node:20-alpine`; `edge-go/Dockerfile:29` is `# ... (scratch = minimal image)`. The drift
   check would have reported a pin present after its real `FROM` line was deleted.
2. **Multi-site rows invented drift** — `node` (×4) and `ollama` (×2) compared every site against
   the first match.
3. **A required rule was never implemented** — a row with `sites: []` and any kind other than
   `out_of_repo` is invisible to both H-1 and H-5. AC-5.16's break exposed it; now fails H-1.

**In the load harness (§B of `AC-6-ARMS.md`):**

4. **The credential guard made AC-6.1 unobservable.** Copied from `load-test.sh:28`, it aborted
   with exit 1 before the tool check ran, so a host with no `hey` exited on the password instead
   of the tool. A missing credential is now VOID (2) — "could not measure" must never share an
   exit code with "measured and failed".
5. **`hey` 0.1.5 prints a literal double percent** (`95%% in 0.0056 secs`). A `95%`-anchored
   pattern extracted nothing, the p95 shipped as `0.0`, and `grep -c 'p95'` still passed. This is
   AC-6.5's own failure mode, found in the wild.
6. **`hey -h` was being recorded as the tool version** (`flag needs an argument: -h`).
7. **The break arms overwrote the deliverable** — artifacts are named `<date>-<sha>.md`, so the
   401 and 1-request runs clobbered the committed baseline, and AC-6.5 then reported the real
   artifact as failing. It was not; it was another run wearing its filename.

---

## Criteria that could not fail as written

Four, added to the plan-defect register (P-1..P-6 were recorded in Tasks 0–4).

| # | Criterion | Why it could not fail | Resolution |
|---|---|---|---|
| **P-7** | **AC-5.17** expects exit 1 from `eol_source: vendor` on rabbitmq 4.3 | Exits **0**, and 0 is CORRECT: vendor `2026-11-30` is 126 days out, the window is 90. Satisfying it meant widening the window until an unbreached row failed | matched pair at one 150-day window (only `eol_source` differs) **plus a control at the default 90d**, proving the exit came from the override and not the window |
| **P-8** | **AC-6.7** expects `grep -c '^[-+]'` = 0 on the `load-test.sh` diff | Returns **2** — the diff HEADER (`--- a/…`, `+++ b/…`). Can never be 0 for any modified file; "fixing" it meant reverting the required pointer | header excluded; corrected form returns 0 with a `--stat` control proving the diff is real |
| **P-9** | **AC-5.10**'s control | Both arms exit 1 — the exemption is still present and STALE, so the control fails for an unrelated reason (same shape as P-3) | exemption removed in **both** arms, leaving `vendor_eol` as the only difference |
| **P-10** | **AC-6.12**'s break (`docker stop jtoye-prometheus`) | **rc=0** — prometheus is not a *built* service and the gate scopes to built services. The named break is vacuous | see the finding below; the gate's VOID branch was proven to fire by other means |

---

## FINDING — a fail-open in a pre-existing gate, FOUND AND FIXED

`scripts/check-runtime-freshness.sh` VOIDs (exit 2) only when **zero** built services are
verifiable (`:431`). Stop one of four and the stopped, unproven service is reported as
`1 unverified` **inside a PASS** (`:445`):

```
docker stop jtoye-prometheus            -> rc=0  (not a built service; out of scope entirely)
docker stop <core-java>                 -> rc=0  PASS: 3 ... match (1 unverified)
--compose-file with no running container -> rc=2  VOID, not passing        <- the branch works
```

This sits against the project's own standing rule that these gates "fail closed … on a stopped
stack — 'found nothing' is never 'clean'". Per-service, it does not.

**FIXED** (user-directed, 2026-07-27, as a deliberate scope extension). The gate now VOIDs when
**any** built service is unverifiable, not only when all are. Drift still takes precedence — a
runtime *known* stale is a stronger statement than one that could not be evaluated — so exit 1
still wins over exit 2 there.

Falsified in both directions, plus the control that the arm is measuring the fix and not
something else:

```
1. correct, fully-running tree      -> rc=0  PASS: 4 ... (0 unverified)
2. docker stop jtoye-mcp-server     -> rc=2  PARSE ERROR: 1 of 4 built service(s) could not be
                                             verified — 3 checked ... VOID, not passing
3. CONTROL: VERIFIED=3, SKIPPED=1, so the OLD condition (VERIFIED==0) was FALSE
                                    -> the old code took the PASS branch and exited 0 here
4. restored                         -> rc=0
```

Arm 1 is the one that mattered most: a "fix" that turns a **correct** tree red is the
outage-causing shape this project has been bitten by, so it was checked *before* the change
(a healthy stack has 4 built services and 0 skipped) and confirmed after. No bypass flag was
added — a `--allow-unverified` switch is how a check earns a `|| true`; a deliberate subset run
scopes itself with `--compose-file`. The runtime half is not wired into CI, so no workflow
changes behaviour.

---

## Falsification totals

| | arms | matched |
|---|---|---|
| AC-5.1 .. AC-5.17 | 24 | 24 |
| AC-6.1 .. AC-6.13 | 21 | 20 (AC-6.12's break is unfalsifiable as written — §C) |

Every arm ran through `baselines/runcheck.sh`, which captures `$?` on the same line as the call
and exits 1 when the observed code differs from the asserted one — so an arm that failed to break
could not be recorded as a pass.

---

## What this hands to the rest of Phase 27

- **27-02** reads `infra/dependency-horizons.yaml` instead of maintaining its own: the rabbitmq
  row records that **4.3 is the only viable target** (4.1 EOL 2026-01-30 past, 4.2 EOL 2026-07-31,
  4.3 vendor horizon 2026-11-30), and the `eol_source` field its AC-10 Break 3 needs now exists
  and is proven to work in both directions. The `rabbitmq-k8s` row is seeded empty for it to fill.
- **27-04** gets ~82 msg/s/consumer and a harness it can **parameterise** rather than fork
  (`QUEUES` / `PAYLOAD`), proven by running arm B against `payment.events` from the command line
  with no script edit.
- **27-03** turns both red gates green by writing four runbook sections.

**Scheduled reds, stated in advance:** the horizon gate turns amber ~**2026-09-01** and red
**2026-12-01** on rabbitmq 4.3's vendor horizon, with no code change. Intended, documented in the
manifest header, and not to be diagnosed as a broken gate.
