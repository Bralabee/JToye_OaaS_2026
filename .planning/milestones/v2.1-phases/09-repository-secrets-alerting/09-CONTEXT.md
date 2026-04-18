# Phase 9: Repository Secrets + Alerting — Context

**Gathered:** 2026-04-15
**Status:** Ready for planning (re-scoped from audit doc)

<domain>
## Phase Boundary

Deploy Prometheus Alertmanager alongside the existing Prometheus + Grafana stack in `infra/monitoring/docker-compose.monitoring.yml`, wire it to the existing alert rules in `infra/monitoring/prometheus/alerts.yml`, route to Slack, validate a full alert-to-Slack roundtrip end-to-end, and add secret-leak CI enforcement to prevent the (currently phantom) credential-exposure finding from becoming real.

**NOT in scope:** sealed-secrets / external-secrets-operator (Work Order H, milestone 4+), full secrets-management refactor, Loki/ELK log pipeline, multi-channel paging (PagerDuty/Opsgenie).

</domain>

<critical_rescope>
## Critical Re-Scope — Audit Doc Was Wrong

**`.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md §9 Blocker 5 and §11 Work Order A claim `.env` is committed to git. Verified three ways — that claim is false:**

1. `git log --all --full-history -- .env` → no history
2. `git ls-files --error-unmatch .env` → "did not match any file(s)"
3. `git check-ignore -v .env` → matched by `.gitignore:64:.env`

**`.env.example` uses `CHANGE_ME` placeholders for every password (verified lines 7, 13, 18, 22, 25, 33, 35, 41, 51, 84, 86). `k8s/base/secrets-template.yaml` uses `REPLACE_WITH_*` placeholders. No real secrets leak through committed files.**

**Consequence:**
- SECR-01 (`git rm --cached .env`) — no-op, becomes a verification step instead
- SECR-02 (rotate 5 committed credentials) — unnecessary, becomes out-of-scope
- SECR-03 (push rotations to GitHub/k8s Secrets) — unnecessary
- SECR-04..06 (Alertmanager + Slack + smoke test) — unchanged, still required
- **New requirement added via this re-scope:** add a secret-leak CI check (gitleaks or equivalent) to prevent a future `.env` drift from making the original finding real

**The planner MUST update `.planning/REQUIREMENTS.md` as part of phase 9 execution to reflect this re-scope, and MUST add a short note to `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md` flagging §9 Blocker 5 + §11 Work Order A as "verified incorrect during phase 9 planning" rather than silently dropping them.**

</critical_rescope>

<decisions>
## Implementation Decisions

### Scope re-shape (locked by user 2026-04-15)
- **D-01:** Re-scope SECR-01..03 — verify `.env` non-committal in CI instead of removing it, drop the 5-credential rotation, keep Alertmanager + smoke-test + runbook
- **D-02:** Add a new requirement (SECR-07) — secret-leak CI enforcement (gitleaks GitHub Action, blocking on PRs to `main`)

### Alert surface (verified 2026-04-15)
- **D-03:** Existing alert rules file is `infra/monitoring/prometheus/alerts.yml` with **10 alert rules** (verified by `grep -c "alert:"`), not 13 as the audit doc claims. SECR-05 references "10 alert rules", not 13.
- **D-04:** Existing Prometheus image is `prom/prometheus:v2.48.0` on host port 9091; Grafana is `grafana/grafana:10.2.2` on host port 3001. Alertmanager deploys into the same compose file on the same `monitoring` network.

### Alertmanager topology + config (Claude's discretion within guardrails)
- **D-05:** Default to **compose-first** deployment — `prom/alertmanager:v0.27.0` added to `infra/monitoring/docker-compose.monitoring.yml` on the `monitoring` network, exposed on host port 9093, with persistent volume `alertmanager_data`. k8s deployment follows the same topology in `k8s/base/` if time permits; otherwise ship k8s in a follow-up PR.
- **D-06:** `alertmanager.yml` lives at `infra/monitoring/alertmanager/alertmanager.yml`, mounted read-only into the container. Slack webhook URL injected via env var `${ALERTMANAGER_SLACK_WEBHOOK_URL}` substituted in alertmanager.yml at container start (use `amtool config check` or a simple sed pass in an entrypoint wrapper).
- **D-07:** The Slack webhook URL itself lives in `.env` (gitignored) for local dev and in GitHub Actions Secrets + k8s Secret for CI/staging/prod. `.env.example` gets a `ALERTMANAGER_SLACK_WEBHOOK_URL=` placeholder entry.

### Slack routing + severity policy (Claude's discretion)
- **D-08:** **Start simple: single channel** (`#jtoye-alerts-staging` for dev/staging, `#jtoye-alerts-prod` for prod via env-var override). No severity split yet — add severity labels to alert rules but don't fan them out to multiple channels in phase 9. Severity-based routing can be a follow-up once alert volume is known.
- **D-09:** Alertmanager `route` tree — single receiver `slack-default`, `group_wait=30s`, `group_interval=5m`, `repeat_interval=12h`, `group_by=[alertname, service]`. No inhibition rules in phase 9.
- **D-10:** Every existing alert rule in `alerts.yml` gets explicit `severity: critical|warning|info` and `service: <name>` labels added during this phase if missing. Audit each rule — do not assume they already have labels.

### Secret-leak CI enforcement (Claude's discretion)
- **D-11:** Use `gitleaks/gitleaks-action@v2` (the official, free, well-maintained action). Blocking on any PR that touches `main`. Failure posts a comment with the offending file + rule name but never exposes the secret value.
- **D-12:** Add a local `pre-commit` hook file at `scripts/pre-commit-gitleaks.sh` that developers can opt into with `git config core.hooksPath scripts` — not enforced globally, just available. Phase 9 does NOT install husky or any JS pre-commit framework.
- **D-13:** `.gitleaks.toml` at repo root lists `.env.example` + `k8s/base/secrets-template.yaml` as allowlisted (their `CHANGE_ME` / `REPLACE_WITH_*` placeholders will otherwise false-positive).

### Smoke-test + runbook (Claude's discretion)
- **D-14:** Smoke test for SECR-06 is **two tests** — (a) synthetic alert using `amtool alert add` posting to Alertmanager directly, assert Slack message within 60 s; (b) real integration test by `docker compose stop core-java` → wait for `ServiceDown` rule → assert Slack message. Both captured in a shell script at `infra/monitoring/scripts/smoke-test-alertmanager.sh` with an idempotent cleanup step.
- **D-15:** Runbook lives at `docs/runbooks/alerts.md` — single file, one section per alert rule, each section has: trigger condition, expected impact, first-response steps, escalation. Phase 9 writes the file skeleton + fills in one section (ServiceDown) as the example; the other 9 sections are stub headings with `TODO` so future oncall PRs can fill them without touching the structure.

### Claude's Discretion (explicit)
- Exact alertmanager.yml template syntax, receiver naming, env var substitution mechanism (sed wrapper vs gomplate)
- Directory layout within `infra/monitoring/alertmanager/`
- Gitleaks config allowlist rules beyond the two template files
- Whether to ship k8s manifests for Alertmanager in this phase or defer to follow-up

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents (researcher, planner) MUST read these before planning:**

### Source document (mandatory)
- `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md` §9 (Blocker 5) + §11 (Work Order A) — original scope; **note the re-scope in `<critical_rescope>` above invalidates the `.env`-committed premise**

### Requirements
- `.planning/REQUIREMENTS.md` SECR-01..06 (to be updated during phase 9 execution per D-01, D-02)
- `.planning/PROJECT.md` — Current Milestone section

### Existing infrastructure (must be read before adding Alertmanager)
- `infra/monitoring/docker-compose.monitoring.yml` — existing Prometheus + Grafana topology
- `infra/monitoring/prometheus/prometheus.yml` — needs `alerting.alertmanagers` block added
- `infra/monitoring/prometheus/alerts.yml` — 10 existing alert rules; severity/service labels to audit
- `infra/monitoring/README.md` — existing monitoring overview (document Alertmanager addition here)
- `k8s/base/secrets-template.yaml` — pattern for k8s Secret resources if Alertmanager ships to k8s in phase 9
- `.env.example` — add `ALERTMANAGER_SLACK_WEBHOOK_URL=` placeholder
- `.gitignore` — already correctly excludes `.env` (line 64) — no change needed, just verify

### External docs (consult via Context7 during research if needed)
- Prometheus Alertmanager docs — `alertmanager.yml` route tree, receiver config, Slack integration
- Gitleaks action docs — `gitleaks/gitleaks-action@v2` inputs, allowlist syntax

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `infra/monitoring/docker-compose.monitoring.yml` — existing compose file to extend in place (don't create a new file)
- `infra/monitoring/prometheus/alerts.yml` — 10 existing rules to label + route
- `.gitignore:64` — already excludes `.env` correctly; no rewrite needed

### Established Patterns
- Env vars flow through `.env` → `docker-compose` → container (see Grafana `GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:?Set GRAFANA_ADMIN_PASSWORD in .env}` pattern at line 35)
- k8s secrets pattern is `stringData` placeholders in `k8s/base/secrets-template.yaml` — follow this for Alertmanager webhook if k8s ships in phase 9
- Services are named `jtoye-<component>` (`jtoye-prometheus`, `jtoye-grafana`) — use `jtoye-alertmanager`
- Host port convention uses offset ports (9091 for Prom, 3001 for Grafana) — Alertmanager gets 9093 (its default)

### Integration Points
- `infra/monitoring/prometheus/prometheus.yml` — add `alerting.alertmanagers` block pointing at `http://alertmanager:9093`
- `infra/monitoring/README.md` — document Alertmanager in the existing monitoring overview
- `.github/workflows/` — add gitleaks workflow (or extend existing security workflow if one exists)
- `docs/runbooks/` — may not exist yet; phase 9 creates it with `alerts.md`

</code_context>

<specifics>
## Specific Ideas

- The audit doc stays as a historical artifact — phase 9 adds a short "verified incorrect" footnote but does not rewrite §9/§11
- The runbook file is a skeleton + one example section; future oncall PRs fill in the other 9 sections. This is deliberate — don't front-load work that will rot before being read.
- The gitleaks allowlist for `.env.example` + `secrets-template.yaml` is critical — without it the very first PR with a `CHANGE_ME` string fails CI

</specifics>

<deferred>
## Deferred Ideas

- **Severity-based Slack routing (#alerts-critical vs #alerts-warning)** — re-evaluate once phase 9 alert volume is known, not in this phase
- **PagerDuty / Opsgenie integration** — out of scope, milestone 4+
- **Loki / ELK log aggregation alongside metrics alerts** — Work Order G, milestone 4+
- **Sealed-secrets / external-secrets-operator** — Work Order H, milestone 4+
- **Grafana dashboard for Alertmanager itself** — nice-to-have, defer to phase 11 (STMP-05 adds Grafana tiles anyway)

</deferred>

---

*Phase: 09-repository-secrets-alerting*
*Context gathered: 2026-04-15*
