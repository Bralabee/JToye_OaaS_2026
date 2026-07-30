---
quick_id: 260730-tap
description: environment-scoped Alertmanager mute for NoOrdersCreated
date: 2026-07-30
branch: fix/alertmanager-scoped-mute
status: complete
commits:
  - 14e15439 feat(alerts): environment-scoped notification mute, composed into one child-routes block
  - c748fbae feat(alerts): wire ALERTMANAGER_MUTE_ALERTNAMES through compose and .env.example
  - 34c32ca9 feat(alerts): add scripts/check-alert-mute.sh
  - 19e43ddd fix(alerts): M-2 could not fire, and fill in the NoOrdersCreated runbook
  - 5454f15b fix(docs): repair two line citations my compose edit shifted
---

# Quick Task 260730-tap — SUMMARY

## What shipped

`ALERTMANAGER_MUTE_ALERTNAMES` — a comma-separated, **empty-by-default** notification mute,
rendered into the Alertmanager config at container start. Set it locally to stop `NoOrdersCreated`
paging a quiet dev stack; it is absent everywhere else and a gate fails the build if any k8s
manifest sets it.

It withholds the **notification only**. The rule keeps evaluating; the alert still shows as firing
in Prometheus and in the Alertmanager UI. `alerts.yml` is byte-unchanged.

## Evidence

### Render arms — all inside `prom/alertmanager:v0.27.0`, so real `amtool` ran

| arm | result |
|---|---|
| unconfigured | **1629 bytes, sha `f9b5b39f`** — byte-identical to the live baseline captured from the running container *before* the change (AC-4.8 preserved) |
| mute only | `amtool SUCCESS`, 2 receivers, mute route present |
| mute + Slack | `amtool SUCCESS`, 3 receivers, mute **first** (no `continue`), Slack second (`continue: true`) |
| Slack only | **2088 bytes, sha `e059decf`** — byte-identical to the same arm rendered from the **pre-change** files (`diff` rc=0), so the existing feature is provably unregressed |
| malformed value (`severity="critical"`) | **FATAL**, no file rendered |

### Gate arms — every assertion shown to FAIL before being trusted

| assertion | break arm | observed |
|---|---|---|
| M-1 | (fired during development — see below) | `M-1 mute matcher 'severity="info"' does not key on alertname` |
| M-2 | hand-written config with `severity="info"` matcher | fires with the L-3 explanation |
| M-3 | mute `KeycloakDown` (real rule, not allowlisted) | `M-3 'KeycloakDown' is muted but is not on MUTE_ALLOWLIST` |
| M-4 | mute `NoSuchRuleXyz` | `M-4 ... no such rule exists in alerts.yml — stale mute config` |
| M-5 | plant the var in `k8s/base/kustomization.yaml` | `FAIL: M-5 ... sets ALERTMANAGER_MUTE_ALERTNAMES`, rc=1 |
| M-6 | mute route ordered **after** a consuming catch-all | `M-6 muted alert 'NoOrdersCreated' routed to email-default` |

Clean direction, live, after every arm:

```
  M-5   no k8s manifest sets ALERTMANAGER_MUTE_ALERTNAMES
  M-1..M-4  muted: NoOrdersCreated (alertname-only, allowlisted, rules present)
  M-6   muted 'NoOrdersCreated' -> mute-null (no email) · control 'CheckAlertMuteControl…' -> email-default · notifications_total{email} 0 -> 1
check-alert-mute: PASS
```

Verified at the **sink**, not only at the counter — Mailhog search: control `total=2`
(firing + resolved), muted `total=0`.

### The M-6 arm is the point of the whole gate

On the misordered-route config, **M-1 through M-4 all passed** — the mute block was structurally
perfect — while the alert routed straight to `email-default`. Only the functional assertion caught
it. That is Proof Standard #5 reproduced deliberately rather than argued.

## Two defects found in my own instruments

1. **The M-1 awk scan never terminated.** It exited only on a sibling `- receiver:` line, which does
   not exist in a mute-only render, so it ran to end of file and reported the *receivers* section as
   mute matchers (`name: email-default does not key on alertname`). Caught only because the gate was
   actually run against a live config. Fixed with explicit route- and `matchers:`-key boundaries.

2. **M-2 was incapable of firing.** It ran per-matcher *after* M-1 with a `continue` between them. In
   Alertmanager's `matchers:` list form a forbidden label is always its own entry, so M-1 rejected it
   first and M-2 was never reached. Measured against the severity arm: the gate reported only the
   M-1 message — true, and the wrong diagnosis. M-2 now scans the whole matcher set first. Both fire.

3. **My compose edit broke a citation elsewhere.** Adding 11 lines shifted `postgres-exporter` from
   line 137 to 148 and `check-doc-citations` went red. Baseline established rather than assumed
   (rc=0 from the main checkout, rc=1 from this branch, same moment). A second stale range in
   `INTEGRATIONS.md` the gate did **not** catch — it verifies a range's first line, which had not
   moved — recorded because it marks the edge of what that gate proves.

## Gate status

11/11 static gates rc=0 on the branch tree, including `check-doc-citations` and
`check-branch-behind-base`. 4/4 live gates rc=0 from the **main checkout**
(`check-runtime-freshness`, `check-container-config-drift`, `check-alert-metrics`,
`check-alert-liveness` — L-3 green, so the throwaway test containers disturbed nothing).

## Deviation from plan

The plan named a `docs/ops/terminal-states.yaml` row. **Dropped deliberately** — that register's
schema describes work that has permanently stopped (`what_stops`, `owner`, `operator_action`); a
notification mute is not that, and forcing a row in would fill a register with something it does not
describe. The gate-enforced home is `scripts/check-alert-mute.sh` plus the runbook entry.

## NOT done — needs the merge first

**The live stack is not yet muted.** The running Alertmanager mounts the *main checkout's*
template and entrypoint, not this branch's, so the mute cannot take effect until this merges.
After merge:

```bash
echo 'ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated' >> .env    # .env is gitignored, local only
docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate alertmanager
docker logs jtoye-alertmanager 2>&1 | grep 'mute ACTIVE'
bash scripts/check-alert-mute.sh
```
