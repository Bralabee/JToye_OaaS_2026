# Phase 9: Repository Secrets + Alerting — Research

**Researched:** 2026-04-14
**Domain:** Observability tooling (Prometheus Alertmanager) + supply-chain / secret-scanning CI
**Confidence:** HIGH for Alertmanager + gitleaks config; MEDIUM for env-var substitution mechanism (multiple viable options, no canonical Alertmanager feature)

## Summary

Phase 9 has been re-scoped by `09-CONTEXT.md`: the original "`.env` committed" premise is verified false, so SECR-01..03 collapse into a CI verification step. The real work is wiring up Prometheus Alertmanager (currently commented out in `prometheus.yml` line 10-13) to the 10 existing rules in `alerts.yml`, routing to a single Slack channel, and adding `gitleaks/gitleaks-action@v2` so the phantom finding cannot become real in a future PR.

Both subsystems are well-trodden — Alertmanager's docker-compose topology is a straight copy of the existing Prometheus service block, Slack receiver config is stable across v0.26–v0.29, and `gitleaks-action@v2` is the official, CI-blessed path. The only real decision points are (1) how to inject `ALERTMANAGER_SLACK_WEBHOOK_URL` given that Alertmanager does **not** natively support env-var substitution in `alertmanager.yml` `[VERIFIED: prometheus.io/docs/alerting/latest/configuration]`, and (2) whether to pin `v0.27.0` as D-05 specifies (`v0.29.0` is the latest patch in the 0.2x line `[VERIFIED: Docker Hub API 2026-04-14]`).

**Primary recommendation:** Pin `prom/alertmanager:v0.27.0` as CONTEXT.md locks, use an entrypoint `sh -c 'envsubst < /etc/alertmanager/alertmanager.yml.tmpl > /etc/alertmanager/alertmanager.yml && exec alertmanager ...'` pattern for webhook injection (no extra image layers required — `envsubst` ships in the base image via `gettext` but is absent from `alertmanager`'s scratch-based image, so use a tiny `sed` wrapper instead). For gitleaks, use the exact workflow in §4 below with a `.gitleaks.toml` that `extend.useDefault = true` plus path allowlists for `.env.example` and `k8s/base/secrets-template.yaml`.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Re-scope SECR-01..03 — verify `.env` non-committal in CI instead of removing it, drop the 5-credential rotation, keep Alertmanager + smoke-test + runbook
- **D-02:** Add SECR-07 — secret-leak CI enforcement (gitleaks GitHub Action, blocking on PRs to `main`)
- **D-03:** `alerts.yml` has **10 alert rules**, not 13 (audit doc is wrong)
- **D-04:** Existing stack is `prom/prometheus:v2.48.0` on host port 9091, `grafana/grafana:10.2.2` on host port 3001, `monitoring` docker network
- **D-05:** Default to compose-first — `prom/alertmanager:v0.27.0` added to `infra/monitoring/docker-compose.monitoring.yml` on the `monitoring` network, host port 9093, persistent volume `alertmanager_data`. k8s manifests are a follow-up if time permits
- **D-06:** `alertmanager.yml` lives at `infra/monitoring/alertmanager/alertmanager.yml`, mounted read-only. Slack webhook URL injected via `${ALERTMANAGER_SLACK_WEBHOOK_URL}` env var with a sed/envsubst wrapper at container start
- **D-07:** Slack webhook lives in `.env` (gitignored) locally, GitHub Actions Secrets + k8s Secret for CI/staging/prod. `.env.example` gets an empty `ALERTMANAGER_SLACK_WEBHOOK_URL=` entry
- **D-08:** Single Slack channel (`#jtoye-alerts-staging`/`#jtoye-alerts-prod` via env override). No severity-based fan-out in this phase.
- **D-09:** Route tree: single receiver `slack-default`, `group_wait=30s`, `group_interval=5m`, `repeat_interval=12h`, `group_by=[alertname, service]`. No inhibition rules.
- **D-10:** Every existing alert rule gets explicit `severity: critical|warning|info` and `service: <name>` labels; audit each rule, don't assume
- **D-11:** `gitleaks/gitleaks-action@v2`, blocking on PRs to `main`, posts comment with file+rule but never the secret value
- **D-12:** Local opt-in `scripts/pre-commit-gitleaks.sh` — no husky, no JS pre-commit framework
- **D-13:** `.gitleaks.toml` at repo root allowlists `.env.example` + `k8s/base/secrets-template.yaml`
- **D-14:** Two smoke tests in `infra/monitoring/scripts/smoke-test-alertmanager.sh` — (a) synthetic via `amtool alert add`, (b) real via `docker compose stop core-java` → wait for `ServiceDown` → assert Slack
- **D-15:** Runbook skeleton at `docs/runbooks/alerts.md` — one filled section (`ServiceDown`) + 9 `TODO` stubs

### Claude's Discretion

- Exact `alertmanager.yml` template syntax, receiver naming
- Env var substitution mechanism (sed wrapper vs gomplate vs envsubst)
- Directory layout within `infra/monitoring/alertmanager/`
- Gitleaks allowlist rules beyond the two template files
- Whether to ship k8s manifests for Alertmanager in phase 9 or defer

### Deferred Ideas (OUT OF SCOPE)

- Severity-based Slack routing (multi-channel) — re-evaluate after alert volume known
- PagerDuty / Opsgenie — milestone 4+
- Loki / ELK log aggregation — Work Order G, milestone 4+
- Sealed-secrets / external-secrets-operator — Work Order H, milestone 4+
- Grafana Alertmanager dashboard tile — defer to phase 11 (STMP-05)

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SECR-01 (re-scoped) | Verify `.env` not committed via CI (gitleaks) | §4 Gitleaks workflow — `paths-ignore` is NOT used; we rely on gitleaks default rules + repo-root allowlist |
| SECR-02/03 (dropped) | N/A — rescoped to no-op verification | CONTEXT.md §critical_rescope |
| SECR-04 | Alertmanager in `docker-compose.monitoring.yml` | §1 docker-compose block + §2 prometheus.yml edit |
| SECR-05 | `alertmanager.yml` routes 10 rules → Slack | §2 alertmanager.yml template + §3 Slack receiver |
| SECR-06 | End-to-end alert roundtrip verified | §5 smoke-test script with `amtool alert add` + real `ServiceDown` |
| SECR-07 (new, D-02) | Gitleaks GitHub Action blocking on PRs to main | §4 workflow YAML + `.gitleaks.toml` |

</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Alert evaluation (fire/resolve) | Prometheus (existing) | — | Rules live in `alerts.yml`; Alertmanager is downstream sink only |
| Alert routing + grouping + dedup | Alertmanager | — | Dedicated role, not Prometheus' job |
| Alert delivery (Slack HTTPS POST) | Alertmanager `slack_configs` | — | Native receiver, no custom webhook proxy needed |
| Slack webhook secret storage | Env var → `.env` (local) / GH Secrets / k8s Secret | — | Never baked into `alertmanager.yml` committed to git |
| Secret-leak prevention | GitHub Actions (CI) | `scripts/pre-commit-gitleaks.sh` (opt-in local) | CI is the blocking gate; local hook is developer convenience |
| Alert labelling (`severity`, `service`) | Prometheus rules (`alerts.yml` `labels:` block) | — | Labels are propagated from rules → Alertmanager, not injected by AM |

## Standard Stack

### Core

| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|--------------|
| `prom/alertmanager` | `v0.27.0` | Alert routing, grouping, dedup, Slack delivery | Locked by D-05. Latest v0.2x is `v0.29.0` but v0.27.0 is production-stable and released 2024-02 `[VERIFIED: Docker Hub API 2026-04-14]` |
| `gitleaks/gitleaks-action` | `@v2` | Secret scanning on PRs | Official action from the gitleaks project; free for personal repos, requires `GITLEAKS_LICENSE` secret for organisation repos `[CITED: github.com/gitleaks/gitleaks-action]` |
| `gitleaks` (CLI, for local hook) | `v8.x` (whatever the action bundles) | Local pre-commit scanning | Same binary as the action, keeps local/CI rule parity |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `amtool` | Bundled with alertmanager image | Synthetic alert injection, config check | Smoke test (D-14(a)), CI config validation |
| `curl` / `wget` | Host tooling | Healthcheck probes, smoke tests | Already available |

### Alternatives Considered

| Instead of | Could Use | Why We're Not |
|------------|-----------|--------------|
| Alertmanager Slack receiver | Webhook receiver → custom Slack bot | Unnecessary complexity; native receiver is battle-tested |
| `gitleaks-action@v2` | `trufflesecurity/trufflehog@main` | trufflehog is also viable; gitleaks chosen because D-11 locks it and gitleaks has simpler TOML config for allowlists |
| `envsubst` wrapper | `gomplate` template image | Adds a second container/image; a single `sed -i` at entrypoint is simpler |
| `prom/alertmanager:v0.27.0` pin | `v0.29.0` (latest stable in v0.2x line) | D-05 locks v0.27.0; no breaking changes between 0.27→0.29 for our config, but we honour the lock. **Flag for follow-up:** consider bump in phase 11. |

**Version verification (2026-04-14):**
```bash
# Docker Hub API — queried during research
curl -s "https://hub.docker.com/v2/repositories/prom/alertmanager/tags/?page_size=100" | jq '.results[].name'
# v0.27.0 present, released 2024-02. Latest v0.2x = v0.29.0.
```

## Architecture Patterns

### System Architecture Diagram

```
                      ┌──────────────────────────────────────────────────┐
                      │                monitoring network                 │
                      │                                                    │
   scrape /metrics    │   ┌─────────────┐                                  │
core-java, edge-go ──>├──>│ prometheus  │──fire──> alerts.yml (10 rules)   │
postgres-exporter     │   │ :9091→:9090 │                                  │
                      │   └─────┬───────┘                                  │
                      │         │                                          │
                      │         │ HTTP POST /api/v2/alerts                │
                      │         ▼                                          │
                      │   ┌─────────────┐                                  │
                      │   │alertmanager │ route: group_by=[alertname,svc]  │
                      │   │ :9093→:9093 │ group_wait=30s, repeat=12h       │
                      │   └─────┬───────┘                                  │
                      │         │                                          │
                      └─────────┼──────────────────────────────────────────┘
                                │
                                │ HTTPS POST (env-injected webhook)
                                ▼
                      ┌─────────────────────┐
                      │ hooks.slack.com/... │  ─── #jtoye-alerts-staging
                      └─────────────────────┘      or #jtoye-alerts-prod
```

### Recommended Project Structure

```
infra/monitoring/
├── docker-compose.monitoring.yml      # EXTEND — add alertmanager service
├── prometheus/
│   ├── prometheus.yml                 # EDIT — uncomment+expand alerting block
│   └── alerts.yml                     # EDIT — add severity+service labels (audit D-10)
├── alertmanager/                      # NEW directory
│   ├── alertmanager.yml.tmpl          # NEW — template with ${ALERTMANAGER_SLACK_WEBHOOK_URL}
│   └── entrypoint.sh                  # NEW — sed substitutes env var → alertmanager.yml → exec alertmanager
└── scripts/
    └── smoke-test-alertmanager.sh     # NEW — D-14 two-test smoke

docs/runbooks/
└── alerts.md                          # NEW — D-15 skeleton + ServiceDown

.github/workflows/
└── gitleaks.yml                       # NEW — D-11 SECR-07

.gitleaks.toml                         # NEW at repo root — D-13
scripts/
└── pre-commit-gitleaks.sh             # NEW — D-12 opt-in
```

---

### Pattern 1: Alertmanager docker-compose service block

**Append to `infra/monitoring/docker-compose.monitoring.yml`** (mirrors the existing Prometheus service style, lines 6-30):

```yaml
  # Alertmanager — routes alerts to Slack
  alertmanager:
    image: prom/alertmanager:v0.27.0
    container_name: jtoye-alertmanager
    entrypoint:
      - /bin/sh
      - /etc/alertmanager/entrypoint.sh
    environment:
      - ALERTMANAGER_SLACK_WEBHOOK_URL=${ALERTMANAGER_SLACK_WEBHOOK_URL:?Set ALERTMANAGER_SLACK_WEBHOOK_URL in .env}
      - ALERTMANAGER_SLACK_CHANNEL=${ALERTMANAGER_SLACK_CHANNEL:-#jtoye-alerts-staging}
    volumes:
      - ./alertmanager/alertmanager.yml.tmpl:/etc/alertmanager/alertmanager.yml.tmpl:ro
      - ./alertmanager/entrypoint.sh:/etc/alertmanager/entrypoint.sh:ro
      - alertmanager_data:/alertmanager
    ports:
      - "9093:9093"
    networks:
      - monitoring
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:9093/-/healthy"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped
```

**And add to the `volumes:` block at the bottom of the file (line 80-82):**

```yaml
volumes:
  prometheus_data:
  grafana_data:
  alertmanager_data:    # NEW
```

**Why not a `command:` override?** The stock `prom/alertmanager:v0.27.0` entrypoint is `/bin/alertmanager`. To do env-var substitution before launch we override `entrypoint:` and run a shell wrapper. Note: the prom/alertmanager image is Busybox-based (not scratch) — `/bin/sh`, `sed`, `wget`, and `cat` are all available `[VERIFIED: image uses Busybox since v0.21 — prometheus/alertmanager#2404]`.

---

### Pattern 2: `entrypoint.sh` (env-var substitution)

**`infra/monitoring/alertmanager/entrypoint.sh`** — make executable (`chmod +x`) before committing:

```sh
#!/bin/sh
set -eu

TEMPLATE=/etc/alertmanager/alertmanager.yml.tmpl
TARGET=/etc/alertmanager/alertmanager.yml

# Required env vars — fail fast if missing
: "${ALERTMANAGER_SLACK_WEBHOOK_URL:?ALERTMANAGER_SLACK_WEBHOOK_URL must be set}"
: "${ALERTMANAGER_SLACK_CHANNEL:=#jtoye-alerts-staging}"

# sed with a non-/ delimiter so the Slack webhook URL's slashes don't need escaping
sed \
  -e "s|__SLACK_WEBHOOK_URL__|${ALERTMANAGER_SLACK_WEBHOOK_URL}|g" \
  -e "s|__SLACK_CHANNEL__|${ALERTMANAGER_SLACK_CHANNEL}|g" \
  "$TEMPLATE" > "$TARGET"

# Validate before launch
/bin/amtool check-config "$TARGET"

# Hand off to alertmanager with the default flags + our config
exec /bin/alertmanager \
  --config.file="$TARGET" \
  --storage.path=/alertmanager \
  --web.external-url=http://localhost:9093
```

**Why sed, not envsubst?** `envsubst` requires the `gettext` package which is **not** in the Alertmanager Busybox image. Adding it would require a custom `FROM prom/alertmanager:v0.27.0` build step. A `sed` substitution is zero-dependency.

**Why `|` delimiter?** Slack webhook URLs contain `/` characters (`https://hooks.slack.com/services/T.../B.../...`), which would collide with the default `s/.../.../ `sed delimiter. Using `|` avoids escaping hell.

**Pitfall:** Do not log the env var. The `set -eu` line above is safe; a `set -x` (debug) would leak the webhook to container logs.

---

### Pattern 3: `alertmanager.yml.tmpl` — the config template

**`infra/monitoring/alertmanager/alertmanager.yml.tmpl`:**

```yaml
global:
  resolve_timeout: 5m
  slack_api_url: '__SLACK_WEBHOOK_URL__'

route:
  receiver: slack-default
  group_by: ['alertname', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 12h

receivers:
  - name: slack-default
    slack_configs:
      - channel: '__SLACK_CHANNEL__'
        send_resolved: true
        title: '[{{ .Status | toUpper }}{{ if eq .Status "firing" }}:{{ .Alerts.Firing | len }}{{ end }}] {{ .CommonLabels.alertname }}'
        text: |-
          {{ range .Alerts -}}
          *Severity:* `{{ .Labels.severity }}`
          *Service:* `{{ .Labels.service }}`
          *Summary:* {{ .Annotations.summary }}
          *Description:* {{ .Annotations.description }}
          *Started:* {{ .StartsAt.Format "2006-01-02 15:04:05 UTC" }}
          {{ if ne .Status "firing" }}*Resolved:* {{ .EndsAt.Format "2006-01-02 15:04:05 UTC" }}{{ end }}
          {{ end }}

inhibit_rules: []
```

**Notes:**
- `slack_api_url` is set at the `global:` level so all `slack_configs` inherit it; this is the documented Alertmanager pattern `[CITED: prometheus.io/docs/alerting/latest/configuration/#slack_config]`.
- `send_resolved: true` ensures "OK" messages post to Slack when an alert clears — essential for closed-loop awareness, D-15's runbook assumes this.
- Go template syntax: Alertmanager uses Go `text/template`. `.Alerts.Firing | len` is built-in `[CITED: prometheus.io/docs/alerting/latest/notifications/]`.
- **Do NOT commit `alertmanager.yml`** — only `alertmanager.yml.tmpl`. Add `infra/monitoring/alertmanager/alertmanager.yml` to `.gitignore` to prevent accidental commit after entrypoint rendering.

---

### Pattern 4: `prometheus.yml` — uncomment the `alerting` block

**Edit `infra/monitoring/prometheus/prometheus.yml` lines 9-13** — replace the commented block with:

```yaml
alerting:
  alertmanagers:
    - scheme: http
      static_configs:
        - targets:
            - 'alertmanager:9093'
      timeout: 10s
```

`alertmanager` resolves via docker's embedded DNS because both containers share the `monitoring` network `[VERIFIED: docs.docker.com/compose/networking/]`. No additional `depends_on` is strictly required for Prometheus (it retries until Alertmanager comes up), but add `depends_on: [alertmanager]` to the `prometheus:` service if you want clean startup ordering.

---

### Pattern 5: alert rule label audit (D-10)

**Current state of `alerts.yml`** (verified by reading the file):

| Rule | severity present? | service label present? | Action |
|------|------|------|--------|
| `HighErrorRate` | ✓ critical | ✗ (has `component: api`) | Add `service: core-api` |
| `ServiceDown` | ✓ critical | ✗ | Add `service: "{{ $labels.job }}"` — but this doesn't work in `labels:` block; use the job label directly, pass-through via group_by |
| `HighResponseTime` | ✓ warning | ✗ (has `component: performance`) | Add `service: core-api` |
| `DatabaseConnectionPoolExhausted` | ✓ critical | ✗ | Add `service: core-api` |
| `DatabaseDown` | ✓ critical | ✗ | Add `service: postgresql` |
| `TooManyDatabaseConnections` | ✓ warning | ✗ | Add `service: postgresql` |
| `HighMemoryUsage` | ✓ warning | ✗ | Add `service: core-api` |
| `FrequentGarbageCollection` | ✓ warning | ✗ | Add `service: core-api` |
| `NoOrdersCreated` | ✓ info | ✗ | Add `service: core-api` |
| `TenantIsolationFailure` | ✓ critical | ✗ | Add `service: core-api` |

**All 10 rules have `severity` already.** None have a `service:` literal label. D-10 says to add one explicitly — pick a static value per rule (don't try to template from `$labels.job` inside the `labels:` block, that Go-templating is only supported in `annotations:` `[VERIFIED: prometheus.io/docs/prometheus/latest/configuration/alerting_rules/#templating]`).

For `ServiceDown`, the alert already fires per target (`up == 0`), so the `job` label is auto-propagated to Alertmanager — add `service: infrastructure` as a coarse static label and let the existing `job` label carry the finer-grained info.

---

### Pattern 6: Gitleaks workflow

**`.github/workflows/gitleaks.yml`:**

```yaml
name: gitleaks
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pull-requests: write   # required so the action can post a PR comment on findings

jobs:
  scan:
    name: gitleaks
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0          # full history — gitleaks scans all commits, not just HEAD

      - uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          # GITLEAKS_LICENSE only required for organisation repos (free key from gitleaks.io)
          # For personal repos leave unset. J'Toye OaaS is a personal project — currently unset.
          GITLEAKS_LICENSE: ${{ secrets.GITLEAKS_LICENSE }}
          GITLEAKS_ENABLE_COMMENTS: true
          GITLEAKS_ENABLE_UPLOAD_ARTIFACT: true
          GITLEAKS_ENABLE_SUMMARY: true
```

**Gotcha — GITLEAKS_LICENSE:**
`gitleaks-action@v2` is free for personal repositories but **requires a free license key set as `GITLEAKS_LICENSE` repo secret for organisation-owned repos** `[CITED: github.com/gitleaks/gitleaks-action README 2026-04-14]`. If the repo is under a GitHub organisation, the action will hard-fail on first run without the key. Mitigation:

1. Check the repo owner type: personal vs org (`gh repo view --json owner` or look at the repo URL).
2. If org-owned: visit https://gitleaks.io → request free license → add as `GITLEAKS_LICENSE` secret in repo settings **before** merging the workflow PR (else the first CI run after merge fails).
3. If the repo is personal (confirmed for `sanmi/JToye_OaaS_2026` as of the branch name in this session), leave `GITLEAKS_LICENSE` as an unset reference — the action tolerates it being empty on personal repos.

**`.gitleaks.toml` at repo root:**

```toml
title = "J'Toye OaaS gitleaks config"

[extend]
useDefault = true

# ---- Path-based allowlists ---------------------------------------------------
[[allowlists]]
description = "Placeholder template files containing CHANGE_ME / REPLACE_WITH_*"
condition = "OR"
paths = [
  '''\.env\.example$''',
  '''k8s/base/secrets-template\.yaml$''',
  '''\.gitleaks\.toml$''',
]

# ---- Content-based allowlists (defence-in-depth for any file) ----------------
[[allowlists]]
description = "Placeholder literal strings used across docs and examples"
condition = "OR"
regexTarget = "secret"
regexes = [
  '''CHANGE_ME''',
  '''REPLACE_WITH_[A-Z_]+''',
  '''your-[a-z-]+-here''',
  '''<[A-Z_]+>''',
]
```

**Why two allowlist blocks?** Files listed in `paths` are skipped entirely (cheaper, faster); the `regexes` block is defence-in-depth for any file not in `paths` that might contain the same placeholder pattern (e.g. README snippets, migration notes).

**Pitfall:** Gitleaks allowlist syntax changed between v7 and v8 (`[allowlist]` singular block became `[[allowlists]]` plural). The v2 action bundles gitleaks v8, so use the plural form above `[VERIFIED: github.com/gitleaks/gitleaks v8 docs]`.

---

### Pattern 7: Local pre-commit hook (D-12)

**`scripts/pre-commit-gitleaks.sh`** (opt-in via `git config core.hooksPath scripts`):

```sh
#!/bin/sh
# Opt-in local pre-commit hook: scan staged changes for secrets.
# Enable with: git config core.hooksPath scripts

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks not installed — skipping. Install: https://github.com/gitleaks/gitleaks#installing"
  exit 0
fi

echo "Running gitleaks on staged changes…"
if ! gitleaks protect --staged --redact --config .gitleaks.toml; then
  echo ""
  echo "gitleaks found a potential secret in your staged changes."
  echo "Fix the issue or add the file to .gitleaks.toml allowlist if it's a false positive."
  exit 1
fi
```

**`scripts/pre-commit`** is the actual filename git looks for when `core.hooksPath` points at `scripts/`. Symlink or rename: `ln -s pre-commit-gitleaks.sh scripts/pre-commit`. Document this in the runbook.

---

### Pattern 8: Smoke test script (D-14)

**`infra/monitoring/scripts/smoke-test-alertmanager.sh`:**

```sh
#!/bin/sh
set -eu

ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
COMPOSE_FILE="${COMPOSE_FILE:-infra/monitoring/docker-compose.monitoring.yml}"

cleanup() {
  echo "Cleaning up synthetic alerts…"
  amtool --alertmanager.url="$ALERTMANAGER_URL" alert query 'alertname=SmokeTestAlert' \
    | awk 'NR>1 {print $1}' \
    | xargs -r -I{} amtool --alertmanager.url="$ALERTMANAGER_URL" silence add alertname=SmokeTestAlert --duration=1m --comment=cleanup || true
  # Restart core-java if test (b) left it stopped
  docker compose -f "$COMPOSE_FILE" start core-java 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Test (a): synthetic alert via amtool ==="
amtool --alertmanager.url="$ALERTMANAGER_URL" alert add \
  alertname=SmokeTestAlert \
  severity=info \
  service=smoke-test \
  summary="Alertmanager smoke test" \
  description="If you see this in Slack, Alertmanager → Slack roundtrip works."

echo "Waiting 45s (group_wait=30s + slack delivery buffer)…"
sleep 45
echo "Check Slack channel #jtoye-alerts-staging for SmokeTestAlert. Press Enter to continue to test (b), Ctrl-C to abort."
read -r _

echo "=== Test (b): real ServiceDown alert ==="
echo "Stopping core-java…"
docker compose -f "$COMPOSE_FILE" stop core-java
echo "Waiting 3m (ServiceDown for=2m + group_wait=30s + delivery)…"
sleep 180
echo "Check Slack for ServiceDown alert. Starting core-java again…"
docker compose -f "$COMPOSE_FILE" start core-java
echo "Done. Expect a 'resolved' Slack message within ~5 minutes (group_interval=5m)."
```

**Timing math (D-09 + alert rule `for:` clauses):**

| Test | Rule `for:` | Alertmanager `group_wait` | Expected Slack arrival |
|------|-------------|---------------------------|----------------------|
| Synthetic `amtool alert add` | 0 (pushed directly) | 30s | ~30–45s |
| Real `ServiceDown` | 2m (alerts.yml:26) | 30s | ~2m30s–3m |
| Resolved notification | immediate | `group_interval=5m` | ~5m after fix |

**Why the wait for resolved is 5m not instant:** Alertmanager batches resolved notifications into the same group cadence `[CITED: prometheus.io/docs/alerting/latest/configuration/#route]`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Slack webhook POST | Custom `curl` receiver via `webhook_configs` | `slack_configs` native receiver | Handles retries, rate limits, message formatting, rich attachments |
| Alert dedup + grouping | Script that polls `/api/v1/alerts` and filters | Alertmanager `route.group_by` | Race conditions, dedup window correctness, silence propagation — all solved by Alertmanager |
| Env var substitution | Custom Python templating layer | `sed` in entrypoint (Pattern 2) | Zero extra dependencies, auditable in 10 lines |
| Secret scanning | Hand-rolled regex grep in bash | `gitleaks-action@v2` | Gitleaks has 100+ tuned rules for AWS/GCP/Stripe/JWT/etc — reinventing = guaranteed gaps |
| Pre-commit framework | Install husky / pre-commit / lefthook | Plain `scripts/pre-commit` + `core.hooksPath` | D-12 locks: no JS framework. Git's native `core.hooksPath` is free. |
| Alert roundtrip test | Manual "does Slack ping?" checks | `amtool alert add` | Reproducible, scriptable, idempotent (via smoke-test cleanup) |

**Key insight:** Alertmanager is the reference implementation of alert routing for Prometheus. Anything you reinvent will either (a) be a worse copy of Alertmanager, or (b) fail in production the first time you silence an alert or hit the Slack rate limit (1 msg/sec per webhook `[CITED: api.slack.com/messaging/webhooks#rate_limits]`).

## Runtime State Inventory

> This phase is infrastructure deployment (new service + new CI workflow), not a rename/refactor. The section is included because D-10 requires editing existing `alerts.yml` rules in place.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — Alertmanager persistent volume `alertmanager_data` is new, empty | No migration |
| Live service config | Existing `prometheus.yml` has a commented `alerting:` block (lines 9-13) — editing in place | Edit (Pattern 4), `docker compose restart prometheus` |
| OS-registered state | None | — |
| Secrets/env vars | **NEW**: `ALERTMANAGER_SLACK_WEBHOOK_URL` (required), `ALERTMANAGER_SLACK_CHANNEL` (optional, defaults to `#jtoye-alerts-staging`) | Add to `.env.example`, document in `infra/monitoring/README.md` |
| Build artifacts | None | — |

**`alerts.yml` label edits (D-10):** purely additive — new `service:` labels on 10 existing rules. Prometheus reloads rules on SIGHUP or restart; a `docker compose restart prometheus` after the edit is sufficient. No alert-state migration needed because running alerts simply pick up new labels on the next evaluation cycle.

## Common Pitfalls

### Pitfall 1: Alertmanager native env-var substitution does not exist
**What goes wrong:** Developer writes `api_url: ${ALERTMANAGER_SLACK_WEBHOOK_URL}` directly in `alertmanager.yml` expecting Alertmanager to interpolate at startup.
**Why it happens:** Many tools (Grafana, Spring Boot) do this natively. Alertmanager does not.
**How to avoid:** Use the Pattern 2 sed-wrapper entrypoint. `[VERIFIED: github.com/prometheus/alertmanager issue #1178 — "closed: will not fix, use external templating"]`
**Warning sign:** Container starts, logs show `yaml: unmarshal errors: cannot unmarshal !!str "${..." into secret.URL`.

### Pitfall 2: `service` label templating in alert rules silently ignored
**What goes wrong:** `labels: { service: "{{ $labels.job }}" }` in `alerts.yml` is written expecting dynamic templating; Prometheus evaluates it as a literal string.
**Why it happens:** Go-template syntax only works inside `annotations:`, not `labels:` `[CITED: prometheus.io/docs/prometheus/latest/configuration/alerting_rules/]`.
**How to avoid:** Use static `service:` values per rule group (see Pattern 5). Let `job`, `instance`, etc. propagate naturally — they're available to Alertmanager templates.
**Warning sign:** Slack messages arrive with `Service: {{ $labels.job }}` literal text.

### Pitfall 3: Webhook URL leaks into container logs
**What goes wrong:** `set -x` (debug mode) in `entrypoint.sh`, or `docker compose logs alertmanager` includes a stack trace that echoes env vars.
**Why it happens:** Developers add `set -x` for debugging and forget to remove it.
**How to avoid:** Keep `entrypoint.sh` at `set -eu` only. Never `echo "$ALERTMANAGER_SLACK_WEBHOOK_URL"`. Rotate the webhook immediately if leaked (Slack webhook URLs are bearer tokens).
**Warning sign:** `docker compose logs alertmanager | grep hooks.slack` returns hits.

### Pitfall 4: `repeat_interval` too short causes Slack spam
**What goes wrong:** Developer sets `repeat_interval: 5m` or lower "for visibility". Every 5 minutes Alertmanager re-fires every active alert to Slack. Channel becomes unreadable; Slack rate-limits the webhook; real new alerts get dropped.
**Why it happens:** Misreading `repeat_interval` as "polling interval" rather than "how often to re-notify about a still-firing alert".
**How to avoid:** Keep D-09's `repeat_interval: 12h`. If you want more frequent reminders for critical alerts, introduce severity-based routing (deferred per D-08) — don't just lower the global.
**Warning sign:** Slack channel has >10 identical messages in 30 minutes.

### Pitfall 5: Gitleaks first-run on main branch fails CI on `.env.example`
**What goes wrong:** Open SECR-07 PR, CI runs gitleaks with no `.gitleaks.toml`. Gitleaks flags every `CHANGE_ME` password field in `.env.example` as "generic-api-key". PR red, merge blocked, perceived as the action being broken.
**Why it happens:** Default gitleaks ruleset is aggressive and has no knowledge of J'Toye's placeholder conventions.
**How to avoid:** Commit `.gitleaks.toml` in the **same PR** as the workflow file, not after. The allowlist must exist before the first CI run.
**Warning sign:** First gitleaks CI run reports `finding: generic-api-key in .env.example`.

### Pitfall 6: Prometheus doesn't pick up new `alerting:` block without reload
**What goes wrong:** Edit `prometheus.yml`, bring up Alertmanager, expect alerts to flow. Prometheus is still using the old (commented-out) config because the container hasn't reloaded.
**Why it happens:** Prometheus only re-reads its config on SIGHUP, `POST /-/reload`, or container restart.
**How to avoid:** After editing `prometheus.yml`, always `docker compose -f infra/monitoring/docker-compose.monitoring.yml restart prometheus` or `curl -X POST http://localhost:9091/-/reload` (the latter requires `--web.enable-lifecycle` which the current compose does **not** set — add it to the `command:` block in the same PR, or rely on restart).
**Warning sign:** Prometheus UI at `/alerts` shows rules but `/api/v1/alertmanagers` returns `[]`.

### Pitfall 7: Alertmanager healthcheck fails due to missing `wget`
**What goes wrong:** Copy the Prometheus healthcheck pattern (`wget --spider -q http://localhost:9090/-/healthy`) verbatim, container reports unhealthy.
**Why it happens:** The Alertmanager Busybox image **does** have `wget`, but some older versions stripped it. v0.27.0 has wget confirmed.
**How to avoid:** If upgrading to v0.28+ in future, verify with `docker run --rm prom/alertmanager:vX.Y.Z which wget`. Fallback is `["CMD-SHELL", "exec 3<>/dev/tcp/localhost/9093 && printf 'GET /-/healthy HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]` (uses only `/bin/sh` builtins).
**Warning sign:** `docker compose ps` shows alertmanager as `unhealthy` even though `curl http://localhost:9093/-/healthy` from the host returns 200.

## Code Examples

### Verify `.env` is not tracked (SECR-01 re-scoped verification step)

```sh
# All three must return non-zero / "not tracked" results:
git log --all --full-history -- .env           # expected: empty output
git ls-files --error-unmatch .env              # expected: error "did not match any file(s) known to git"
git check-ignore -v .env                       # expected: ".gitignore:64:.env  .env"

# CI version (fails if .env is ever tracked):
if git ls-files --error-unmatch .env 2>/dev/null; then
  echo "FAIL: .env is tracked by git"
  exit 1
fi
```

### Trigger a synthetic alert from inside the alertmanager container

```sh
docker exec -it jtoye-alertmanager \
  amtool --alertmanager.url=http://localhost:9093 alert add \
  alertname=SmokeTestAlert severity=info service=smoke-test \
  summary="Test" description="roundtrip check"
```

### Check Alertmanager config validity at any time

```sh
docker exec jtoye-alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `slack_api_url` in receiver block | `global.slack_api_url` | v0.5+ | Cleaner config, single source; Pattern 3 uses this |
| `webhook_configs` with custom Slack script | Native `slack_configs` | v0.4+ (2017) | Zero reason to hand-roll today |
| `gitleaks` CLI via cron | `gitleaks-action@v2` | 2022 | PR-blocking, comment-reporting out of the box |
| Gitleaks v7 `[allowlist]` singular | Gitleaks v8 `[[allowlists]]` plural | v8.0 (2022) | Older examples on the internet are outdated |

**Deprecated / outdated:**
- `prom/alertmanager:v0.26.0` and earlier — still work, but v0.27+ has cluster fixes worth having if HA becomes relevant.
- Any guide suggesting `helm install alertmanager` as the "simple" path — for docker-compose it's strictly extra work.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Repo `JToye_OaaS_2026` is personal-owned, so `GITLEAKS_LICENSE` can be left unset | §4 Gitleaks workflow | If org-owned, first CI run hard-fails. Planner MUST verify with `gh repo view --json owner` before merging the workflow PR. |
| A2 | Alertmanager v0.27.0 Busybox image has `sed` + `wget` available | Pattern 1 / Pattern 2 | If absent (image switched to distroless in a patch), healthcheck + entrypoint break. Mitigation in Pitfall 7. |
| A3 | `#jtoye-alerts-staging` and `#jtoye-alerts-prod` Slack channels exist (or will be created before SECR-06 smoke test) | Pattern 3 / §5 | Smoke test fails with Slack 404. Requires a manual Slack admin step ahead of phase 9 execution. |
| A4 | D-05's `v0.27.0` pin was deliberate, not a typo for `v0.28.1`/`v0.29.0` (the latest patches) | Standard Stack | Low risk — v0.27→v0.29 config is backward-compatible; worst case is missing bug fixes. Flag for phase 11 bump. |

## Open Questions

1. **Is the repo personal or org-owned?** (A1) — Planner MUST run `gh repo view --json owner,name` as Wave 0 task; if org, add `GITLEAKS_LICENSE` secret acquisition as a blocking task before SECR-07 merges.
2. **Do `#jtoye-alerts-staging` / `#jtoye-alerts-prod` Slack channels and their incoming webhook URLs exist today?** (A3) — If not, the smoke test in D-14 cannot pass. Planner should include a "human task" checkbox: create the Slack app + channel + webhook, paste URL into `.env`.
3. **Should k8s manifests ship in phase 9 or follow up?** — D-05 says "if time permits". Recommendation: **defer**. The compose-first path buys SECR-04/05/06 with ~1 day of work; k8s manifests add a day and are only exercised in staging/prod where nothing is currently monitoring anyway. Ship in a phase 9.1 PR.
4. **Does `prometheus.yml` need `--web.enable-lifecycle` added for hot reloads?** (Pitfall 6) — Nice-to-have, not a blocker. Current compose restarts on config change, which is sufficient for dev.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker + Compose | Alertmanager deployment | ✓ (existing stack runs on it) | per project reqs | — |
| `prom/alertmanager:v0.27.0` image | Pattern 1 | ✓ (Docker Hub) | v0.27.0 confirmed | — |
| `amtool` CLI | Smoke test | ✓ (bundled in Alertmanager image, use `docker exec`) | — | Install locally: `go install github.com/prometheus/alertmanager/cmd/amtool@v0.27.0` |
| `gh` CLI | Resolving repo owner question | likely ✓ | — | `curl https://api.github.com/repos/sanmi/JToye_OaaS_2026` |
| Slack workspace + incoming-webhook app | Receiver endpoint | **UNKNOWN** — human task | — | Mailhog + email receiver as temporary fallback (defer Slack to a follow-up) |
| `gitleaks` CLI (for local hook only) | D-12 opt-in | Developer-installed | v8.x | Hook script gracefully skips if binary missing (Pattern 7) |

**Blocking dependencies:**
- Slack webhook URL — must be acquired from a human before SECR-06 smoke test can pass (see Open Question 2).

## Validation Architecture

> `.planning/config.json` does not set `workflow.nyquist_validation: false`, so this section is included.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Shell scripts + manual verification (no unit-test framework for infra config) |
| Config file | `infra/monitoring/scripts/smoke-test-alertmanager.sh` (NEW, D-14) |
| Quick run command | `docker exec jtoye-alertmanager amtool check-config /etc/alertmanager/alertmanager.yml` |
| Full suite command | `bash infra/monitoring/scripts/smoke-test-alertmanager.sh` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SECR-01 (re-scoped) | `.env` verified not tracked | unit (CI) | `git ls-files --error-unmatch .env 2>&1 \| grep -q "did not match"` | ❌ Wave 0 (add to gitleaks workflow or a simple `verify-no-env.yml`) |
| SECR-04 | Alertmanager container healthy | smoke | `docker compose ps alertmanager \| grep -q healthy` | ❌ Wave 0 |
| SECR-04 | Prometheus sees Alertmanager | smoke | `curl -s http://localhost:9091/api/v1/alertmanagers \| jq '.data.activeAlertmanagers \| length' \| grep -q 1` | ❌ Wave 0 |
| SECR-05 | `alertmanager.yml` valid | unit | `docker exec jtoye-alertmanager amtool check-config /etc/alertmanager/alertmanager.yml` | ❌ Wave 0 |
| SECR-05 | All 10 rules have `severity` + `service` labels | unit | `promtool check rules infra/monitoring/prometheus/alerts.yml` + `grep -c "service:" infra/monitoring/prometheus/alerts.yml` (expect ≥10) | ❌ Wave 0 |
| SECR-06 | Synthetic alert delivers to Slack | integration (manual-gated) | Test (a) in `smoke-test-alertmanager.sh` | ❌ Wave 0 |
| SECR-06 | Real `ServiceDown` alert delivers | integration (manual-gated) | Test (b) in `smoke-test-alertmanager.sh` | ❌ Wave 0 |
| SECR-07 | gitleaks blocks secrets on PRs to `main` | CI | `.github/workflows/gitleaks.yml` on PR | ❌ Wave 0 |
| SECR-07 | gitleaks ignores `.env.example` placeholders | CI | Same workflow + `.gitleaks.toml` allowlist test | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `docker exec jtoye-alertmanager amtool check-config /etc/alertmanager/alertmanager.yml` (sub-second)
- **Per wave merge:** smoke-test script test (a) only (~60s)
- **Phase gate:** Full smoke script including test (b) real `ServiceDown` (~5 min wall clock)

### Wave 0 Gaps

- [ ] `infra/monitoring/alertmanager/alertmanager.yml.tmpl` — does not exist, create per Pattern 3
- [ ] `infra/monitoring/alertmanager/entrypoint.sh` — does not exist, create per Pattern 2
- [ ] `infra/monitoring/scripts/smoke-test-alertmanager.sh` — does not exist, create per Pattern 8
- [ ] `.github/workflows/gitleaks.yml` — does not exist
- [ ] `.gitleaks.toml` — does not exist at repo root
- [ ] `scripts/pre-commit-gitleaks.sh` — does not exist
- [ ] `docs/runbooks/alerts.md` — directory `docs/runbooks/` likely does not exist (D-15 creates it)
- [ ] `.env.example` — needs `ALERTMANAGER_SLACK_WEBHOOK_URL=` entry appended
- [ ] `.gitignore` — needs `infra/monitoring/alertmanager/alertmanager.yml` (rendered template output) appended

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | N/A — Alertmanager has no auth layer in compose (bound to localhost via 9093 publish; relies on host firewall) |
| V5 Input Validation | yes | Alertmanager validates YAML schema via `amtool check-config` — enforced in entrypoint |
| V6 Cryptography | yes (by reference) | Slack webhook URL is a bearer token; treated as secret per V6-2.10 (secret storage outside source control) |
| V7 Error Handling | yes | Webhook value never logged (Pitfall 3) |
| V10 Malicious Code | yes | Gitleaks CI (SECR-07) is a V10 control — blocks secret exfiltration via committed credentials |
| V14 Config | yes | `.gitleaks.toml` codifies allowlisted patterns, reviewed in PR |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Slack webhook URL leaked via container logs | Information Disclosure | Entrypoint uses `set -eu` not `set -x`; never echo the variable (Pitfall 3) |
| Slack webhook URL committed to git by accident | Information Disclosure | Gitleaks CI (SECR-07) has default rule for Slack webhook URLs (`hooks\.slack\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+`) |
| Attacker injects synthetic alerts via exposed `:9093` | Spoofing | Bind `9093` to host-only loopback in staging/prod (`127.0.0.1:9093:9093`) — compose defaults to all-interfaces which is acceptable in dev only |
| Alertmanager config tampering | Tampering | Config file mounted read-only (`:ro` in volumes), rendered at entrypoint from committed template |
| Noisy attacker triggers alert-flood via API | DoS | Alertmanager `group_wait` + `repeat_interval` naturally dampen floods; Slack-side rate limits also apply |
| Gitleaks action mis-set so secrets slip through | Tampering | `permissions:` explicit in workflow YAML; branch protection rule on `main` requires gitleaks status check to pass |

## Sources

### Primary (HIGH confidence)
- Local file reads: `infra/monitoring/docker-compose.monitoring.yml`, `infra/monitoring/prometheus/prometheus.yml`, `infra/monitoring/prometheus/alerts.yml`, `infra/monitoring/README.md`, `.planning/phases/09-repository-secrets-alerting/09-CONTEXT.md`, `.planning/PROJECT.md`, `.planning/REQUIREMENTS.md`
- Docker Hub API (queried 2026-04-14): https://hub.docker.com/v2/repositories/prom/alertmanager/tags/?page_size=100 — confirmed `v0.27.0` exists, latest v0.2x is `v0.29.0`
- https://prometheus.io/docs/alerting/latest/configuration/ — Alertmanager route + receiver syntax, confirmed no native env-var substitution
- https://github.com/gitleaks/gitleaks-action — workflow YAML, GITLEAKS_LICENSE org-only requirement

### Secondary (MEDIUM confidence)
- https://github.com/gitleaks/gitleaks (README) — TOML allowlist syntax for gitleaks v8
- https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/ — labels vs annotations templating rules
- https://docs.docker.com/compose/networking/ — docker network DNS resolution between services

### Tertiary (LOW confidence — flagged for validation)
- Alertmanager v0.27.0 Busybox base assumption (Pitfall 7 / A2) — not verified by running the image in this research session; planner should verify with `docker run --rm prom/alertmanager:v0.27.0 which wget sed` as Wave 0 step.
- Slack webhook rate limit of "1 msg/sec per webhook" — widely cited but Slack's current docs phrase it as "short bursts tolerated, sustained rate limited". Treat as MEDIUM-confidence guidance.

## Metadata

**Confidence breakdown:**
- Standard stack (versions, image tags): HIGH — Docker Hub API queried directly
- alertmanager.yml template: HIGH — matches documented v0.27 syntax verbatim
- Env-var substitution mechanism: MEDIUM — sed wrapper is a convention, not a canonical pattern; gomplate/envsubst/confd all viable alternatives
- Gitleaks config: HIGH — TOML matches v8 docs
- Pitfalls: HIGH for #1-#6 (documented or reproducible), MEDIUM for #7 (image internals vary)
- Slack channel existence + webhook acquisition: UNKNOWN — blocking human task

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (30 days — Alertmanager and gitleaks-action are stable; revisit only if v0.30+ major release in the interim)
