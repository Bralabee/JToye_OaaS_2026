# Plan 39-03 Summary — Observability Stack Upgrade (Prometheus 3.13 LTS, Grafana 13.2, Alertmanager 0.34, Exporters)

**Status:** COMPLETE  
**Date:** 2026-10-16  
**Requirements Closed:** `OPS-HORIZON-03`

## Changes Delivered

1. **Upgraded all 5 observability images in `infra/monitoring/docker-compose.monitoring.yml`**:
   - `prom/prometheus:v2.48.0` → `prom/prometheus:v3.13.4` (`3.13` LTS, EOL `2027-07-31`; removed obsolete `/usr/share/prometheus/console_libraries` and `/usr/share/prometheus/consoles` flags). Selected `3.13` LTS over short-term `3.15` (`lts=false`, EOL `2026-11-06`, 21 days away).
   - `grafana/grafana:10.2.2` → `grafana/grafana:13.2.4` (`13.2` cycle, EOL `2027-05-18`; removed obsolete `GF_INSTALL_PLUGINS=grafana-piechart-panel`).
   - `prom/alertmanager:v0.27.0` → `prom/alertmanager:v0.34.1`.
   - `oliver006/redis_exporter:v1.58.0` → `oliver006/redis_exporter:v1.94.0`.
   - `prometheuscommunity/postgres-exporter:v0.15.0` → `prometheuscommunity/postgres-exporter:v0.20.1`.

2. **Retired `DEFERRED-27` exemptions on `prometheus` and `grafana` in `infra/dependency-horizons.yaml`**:
   - Active horizon exemptions across the entire repository reduced from `5` (`alpine`, `rabbitmq`, `prometheus`, `grafana`, `keycloak`) to `1` (`keycloak`, owned by `39-04-PLAN.md`).
   - Refreshed `H-5` line numbers via `scripts/check-dependency-horizons.sh --refresh` (`pin-not-at-site=0`).

3. **Gate & Documentation Synchronisation**:
   - `scripts/check-alert-rules.sh`: updated default `PROM_IMAGE` to `prom/prometheus:v3.13.4`.
   - `docs/ops/terminal-states.yaml`: updated `TS-13` locator line number (`182` → `179`) to match `postgres-exporter` in `infra/monitoring/docker-compose.monitoring.yml`.
   - Updated version claims and citations in `infra/monitoring/README.md`, `k8s/DEPLOYMENT.md`, `.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md`, `.planning/codebase/CONCERNS.md`, and `.planning/ROADMAP.md` (`6/10 plans complete`).

## Verification Evidence

- Hermetic `prom/prometheus:v3.13.4` entrypoint render + `promtool check config` (`SUCCESS: 1 rule files found`) + `promtool check rules` (`SUCCESS: 19 rules found`): `rc=0`.
- Hermetic `prom/alertmanager:v0.34.1` 3-arm entrypoint render (`unconfigured`, `mute_only`, `slack_and_mute`) + `amtool check-config`: `rc=0`.
- Hermetic `grafana/grafana:13.2.4` `/run.sh` boot, `/api/health` (`version: 13.2.4`, `database: ok`), and `/api/search` (`stomp-relay-dashboard` provisioned): `rc=0`.
- Fail-before-pass break arms recorded for both `scripts/check-alert-rules.sh` (`rc=1` on malformed PromQL) and `scripts/check-dependency-horizons.sh` (`rc=1` on `prometheus/3.15` short-term cycle).
- Full output recorded in `.planning/phases/39-platform-runtime-upgrades/evidence/39-03-observability-stack.txt`.
