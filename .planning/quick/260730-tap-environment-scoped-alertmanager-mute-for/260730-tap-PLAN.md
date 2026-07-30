---
quick_id: 260730-tap
description: environment-scoped Alertmanager mute for NoOrdersCreated
date: 2026-07-30
branch: fix/alertmanager-scoped-mute
status: in-progress
must_haves:
  truths:
    - "A muted alertname produces NO email notification attempt (notifications_total{integration=email} flat)."
    - "An unmuted alertname of the SAME severity still produces one (the mute is scoped, not global)."
    - "With ALERTMANAGER_MUTE_ALERTNAMES unset, the rendered config is byte-identical to the pre-change render."
    - "The NoOrdersCreated rule in alerts.yml is unchanged — this is a notification mute, never a rule deletion."
    - "No k8s overlay sets the mute variable."
  artifacts:
    - infra/monitoring/alertmanager/alertmanager.yml.tmpl
    - infra/monitoring/alertmanager/entrypoint.sh
    - infra/monitoring/docker-compose.monitoring.yml
    - .env.example
    - scripts/check-alert-mute.sh
    - docs/runbooks/alerts.md
  key_links:
    - infra/monitoring/alertmanager/entrypoint.sh (renders the template; owns the placeholder contract)
    - scripts/check-alert-liveness.sh:435 (L-3 probe posts severity=info/service=platform — the mute must not match it)
    - infra/monitoring/prometheus/alerts.yml:187 (NoOrdersCreated — must stay byte-unchanged)
---

# Quick Task 260730-tap: environment-scoped Alertmanager mute for NoOrdersCreated

## Problem

`NoOrdersCreated` fires ~30 minutes after the last order. On a quiet local stack that is
permanent noise, and the current remedy — `FORCE=1 scripts/seed-order-metric.sh` — writes a
**real order row into the dev database** every time it is used to buy silence. The alert is
correct and meaningful in production; only the local environment wants it quiet.

Measured before this change:

```
curl :9091/api/v1/rules?type=alert   -> NoOrdersCreated state=inactive health=ok
increase(...[30m])                   -> 1.008   (the seeded probe order, ageing out)
curl :9093/api/v2/silences           -> []
docker exec jtoye-alertmanager cat /etc/alertmanager/alertmanager.yml
                                     -> 1629 bytes, sha256 f9b5b39fc2036d07
```

## Constraints discovered before planning

1. **The two child routes share one YAML key.** `entrypoint.sh` renders
   `__SLACK_ROUTE_BLOCK__` as the whole `  routes:` mapping key. A second block emitting
   `routes:` is a duplicate key and `amtool check-config` rejects it. The blocks must be
   composed into one.
2. **The matcher must key on `alertname`, never `severity`.** `check-alert-liveness.sh:435`
   posts its L-3 transport probe with `severity:"info", service:"platform"`. A
   `severity="info"` mute swallows the probe and turns L-3 red — and L-3's own failure text
   names "an active silence, an inhibit rule" as the cause, so it would read as a transport
   fault.
3. **The env var must be added to `docker-compose.monitoring.yml`.** Without it the
   entrypoint change is completely inert — the exact failure recorded in that file's own
   comment at lines 93-96, which happened once already with the Slack vars.
4. **The rendered config exists only inside the container.** Only `.tmpl` and `entrypoint.sh`
   are bind-mounted (compose lines 101-102). The gate must read via `docker exec`, never
   `docker cp` and never the host `.tmpl` — reading the host proves nothing about what
   Alertmanager loaded.

## Tasks

### Task 1 — compose the child-route blocks and add the mute

**files:** `infra/monitoring/alertmanager/alertmanager.yml.tmpl`,
`infra/monitoring/alertmanager/entrypoint.sh`

**action:**
- Rename `__SLACK_ROUTE_BLOCK__` → `__CHILD_ROUTES_BLOCK__` and `__SLACK_RECEIVER_BLOCK__`
  → `__EXTRA_RECEIVERS_BLOCK__` in the template.
- In the entrypoint, build the mute route (first, no `continue`) and the Slack route
  (second, `continue: true`) into one `routes:` emission; emit the `routes:` header only
  when at least one child exists.
- Add `ALERTMANAGER_MUTE_ALERTNAMES` (comma-separated, default empty). Validate each name
  against `^[A-Za-z][A-Za-z0-9_]*$` and FATAL on anything else — that is the file's existing
  idiom (`amtool check-config` and the unrendered-placeholder check both exit 1), and an
  Alertmanager that starts with a config the operator did not intend is worse than one that
  refuses to start on a typo made seconds ago.
- Mute receiver is `mute-null`: a receiver with a name and no integrations.

**verify:** unconfigured render is byte-identical to the captured baseline
(`sha256 f9b5b39fc2036d07`); configured render passes `amtool check-config`.

**done:** both directions rendered and diffed.

### Task 2 — wire the variable through compose and `.env.example`

**files:** `infra/monitoring/docker-compose.monitoring.yml`, `.env.example`

**action:** add `ALERTMANAGER_MUTE_ALERTNAMES=${ALERTMANAGER_MUTE_ALERTNAMES:-}` to the
alertmanager `environment:` block, and a documented empty default to `.env.example`
alongside the other `ALERTMANAGER_*` vars.

**verify:** `docker exec jtoye-alertmanager env | grep ALERTMANAGER_MUTE_ALERTNAMES` returns
the variable after recreate.

**done:** variable observable inside the container.

### Task 3 — `scripts/check-alert-mute.sh`

**files:** `scripts/check-alert-mute.sh`

**action:** new gate, mirroring the `violation()` / `void()` idiom of `check-alert-rules.sh`.
Assertions:

- **M-1** every mute matcher in the rendered config is `alertname`-shaped
- **M-2** no mute matcher references `severity`, `component` or `service` (constraint 2)
- **M-3** every muted alertname is on the written `MUTE_ALLOWLIST`
- **M-4** every muted alertname still exists as a rule in `alerts.yml` (a mute for a
  deleted rule is stale config)
- **M-5** no file under `k8s/` sets `ALERTMANAGER_MUTE_ALERTNAMES`
- **M-6** functional: post a synthetic **muted** alert and assert
  `alertmanager_notifications_total{integration="email"}` does NOT move; post a synthetic
  **unmuted** alert and assert it DOES. Unique per-run alertnames — `group_by` is
  `['alertname','service']` with `group_interval: 5m`, so a constant alertname is silently
  undispatched on re-runs.

Exit 2 (VOID) on missing `docker`/`jq`, a stopped container, or empty output. Reads the
rendered config with `docker exec`, never `docker cp` (constraint 4).

**verify:** run the fail direction for each assertion; record real output both ways.

**done:** gate green on a correct tree and red on each deliberately broken input.

### Task 4 — runbook

**files:** `docs/runbooks/alerts.md`

**action:** replace the `## NoOrdersCreated` TODO stub with a real entry: what it means, why
it is blind after a rebuild, the two different symptoms and their two different fixes, and
how to confirm the mute is absent outside local.

**verify:** `scripts/check-alert-rules.sh` S-4 still passes.

**done:** stub replaced.

## Deviation from the original sketch

The original plan named a `docs/ops/terminal-states.yaml` row. **Dropped deliberately.** That
register's schema is for states where work has permanently stopped (`what_stops`, `owner`,
`operator_action`, `detection`); a notification mute is not one, and forcing a row in would
be filling a register with something it does not describe. The gate-enforced home for this is
`scripts/check-alert-mute.sh` plus the runbook entry.

## Out of scope

- Changing the `NoOrdersCreated` rule itself.
- Any change to `seed-order-metric.sh` — it stays the correct tool for the *gate* being red.
- Alertmanager silences via the API (ephemeral, separate mechanism).
