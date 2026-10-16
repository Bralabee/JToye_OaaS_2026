# Phase 39: Platform Runtime Upgrades - Research

**Date:** 2026-10-16
**Scope:** Tracks `39-A` through `39-G` (all platform runtime upgrades, `quick 261010-ppb` deferrals, and Dependabot `#904`–`#915` triage)

---

## 1. Track 39-A: Immediate Deadlines (`2026-10-26` & `2026-11-30`)

### 1.1 `rabbitmq-k8s` (`manual_review` expires `2026-10-26`)
- **Manifest site:** [`infra/dependency-horizons.yaml:549-566`](../../../infra/dependency-horizons.yaml#L549-L566)
- **Measured state (`2026-10-16`):**
  - `rg -uu -n 'image:.*rabbitmq' k8s/` returns zero matches (`rc=1`). The only RabbitMQ references under `k8s/` remain `k8s/base/configmap.yaml` (`SPRING_RABBITMQ_HOST: rabbitmq.jtoye-infrastructure.svc.cluster.local`), `k8s/local/rabbitmq.yaml` (local-k8s overlay only), and `rabbitmq-credentials` Secret references.
  - [`docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md`](../../../docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md) remains `Status: Proposed` (amended `2026-09-28` for Phase 36 Azure Blob backups, hosting decision still awaiting owner sign-off).
  - [`docs/runbooks/rabbitmq-broker-upgrade.md`](../../../docs/runbooks/rabbitmq-broker-upgrade.md) documents the rolling `3.12 → 3.13 → 4.0 → 4.1 → 4.2 → 4.3` k8s upgrade chain and feature-flag procedure.
  - Vendor release page (`https://www.rabbitmq.com/release-information`, fetched `2026-10-16`) lists `4.3.6` (`16 Sept 2026`) as the latest release and extends **4.3 End of Community Support to `31 Jan 2027` (`2027-01-31`)** (End of Commercial Support `30 Apr 2028`).
- **Action (`39-01-PLAN.md`):**
  - Update `rabbitmq-k8s.manual_review.last_checked` to `"2026-10-16"` and `expires` to `"2027-01-16"` (90-day review window, aligned with the vendor's `2027-01-31` 4.3 community support end).
  - Update the 4 other `manual_review` rows (`azurite`, `alertmanager`, `redis-exporter`, `postgres-exporter`) with their `2026-10-16` measured upstream tags (`v3.37.0`, `v0.34.1`, `v1.94.0`, `v0.20.1`).
  - Run `bash scripts/check-dependency-horizons.sh --refresh` to resolve all 6 `H-5 pin-not-at-site` advisory `NOTE`s (`azurite`, `ollama` x2, `mailhog`, `go-ci-setup` x2).

### 1.2 `alpine` (`DEFERRED-27` expires `2026-11-30`) & Compose `rabbitmq` (`#724` expires `2026-11-30`)
- **`alpine` pin site:** [`docker-compose.full-stack.yml:96`](../../../docker-compose.full-stack.yml#L96) (`image: alpine:3.20`).
  - Used by the `seed-init` helper container (runs POSIX `sh` / `wget` / `nc` against local stack).
  - `endoflife.date/api/alpine-linux.json` (`2026-10-16`): cycle `3.24` (`latest: "3.24.2"`, `eol: "2028-06-01"`).
  - Upgrading `alpine:3.20` → `alpine:3.24` retires the `DEFERRED-27` exemption on `alpine`.
- **`rabbitmq` compose pin site:** [`docker-compose.full-stack.yml:224`](../../../docker-compose.full-stack.yml#L224) (`image: rabbitmq:4.3.4-management-alpine`).
  - Also check [`k8s/local/rabbitmq.yaml`](../../../k8s/local/rabbitmq.yaml) for local-k8s parity.
  - Upstream measurement (`2026-10-16`): neither GitHub (`rabbitmq/rabbitmq-server`) nor Docker Hub (`library/rabbitmq`) has a `4.4*` release; `4.3.6` (`2026-09-16`) is the newest release, and `https://www.rabbitmq.com/release-information` now lists **End of Community Support for 4.3 as `31 Jan 2027` (`2027-01-31`)**.
  - Because `2027-01-31` is 107 days from `2026-10-16` (`> 90` days `HORIZON_WARN_DAYS`), updating `vendor_eol: "2027-01-31"` and bumping the pin to `rabbitmq:4.3.6-management-alpine` makes `rabbitmq/4.3` in-window (`> 90` days), which means `H-4` requires removing the `#724` exemption until `2026-11-02` (89 days before `2027-01-31`), when either `4.4` is adopted or `#724` is re-dated to `2027-01-31`.

---

## 2. Track 39-B: Observability Stack (`39-03-PLAN.md`)

All 5 monitoring images live in [`infra/monitoring/docker-compose.monitoring.yml`](../../../infra/monitoring/docker-compose.monitoring.yml):

| ID | File : Line | Current Pin | Target Pin | EOL / Source (`2026-10-16`) |
|---|---|---|---|---|
| `prometheus` | `docker-compose.monitoring.yml:35` | `prom/prometheus:v2.48.0` | `prom/prometheus:v3.13.4` | Cycle `3.13` LTS (`lts: true`, `eol: "2027-07-31"`). **Do NOT use `3.15`** (`eol: "2026-11-06"`, fails `H-3` immediately). |
| `grafana` | `docker-compose.monitoring.yml:84` | `grafana/grafana:10.2.2` | `grafana/grafana:13.2.4` | Cycle `13.2` (`eol: "2027-05-18"`). |
| `alertmanager` | `docker-compose.monitoring.yml:115` | `prom/alertmanager:v0.27.0` | `prom/alertmanager:v0.34.1` | GitHub release `v0.34.1` (`2026-09-17`). |
| `redis-exporter` | `docker-compose.monitoring.yml:163` | `oliver006/redis_exporter:v1.58.0` | `oliver006/redis_exporter:v1.94.0` | GitHub release `v1.94.0` (`2026-10-15`). |
| `postgres-exporter` | `docker-compose.monitoring.yml:183` | `prometheuscommunity/postgres-exporter:v0.15.0` | `prometheuscommunity/postgres-exporter:v0.20.1` | GitHub release `v0.20.1` (`2026-07-08`). |

### Breaking-Change Hazards in Prometheus 3.x & Grafana 13.x:
1. **Prometheus 3.x CLI flags & config:**
   - `--enable-feature=remote-write-receiver` replaces legacy remote-write flags if used; verify `infra/monitoring/prometheus/prometheus.yml` and alert rules under `infra/monitoring/prometheus/alerts/` with `promtool check config` and `promtool check rules` inside the `v3.13.4` image.
   - UTF-8 metric/label names are enabled by default in Prometheus 3.x; verify Micrometer `/actuator/prometheus` scrape compatibility with Spring Boot 4.1.1.
2. **Grafana 10.2 → 13.2:**
   - AngularJS plugins were removed in Grafana 11+; verify provisioned dashboards under `infra/monitoring/grafana/dashboards/` use only React-based panel types (`timeseries`, `stat`, `gauge`, `table`, `barchart`).

---

## 3. Track 39-C: Keycloak 26 (`39-04-PLAN.md`)

- **Pin site:** [`docker-compose.full-stack.yml:145`](../../../docker-compose.full-stack.yml#L145) (`quay.io/keycloak/keycloak:24.0.5`) → `quay.io/keycloak/keycloak:26.8.1` (cycle `26.8`, `eol: false`).
- **Known Repo Traps:**
  1. **Unmanaged user-profile attributes (`tenant_id`):** In Keycloak 24+, `declarative-user-profile` strips unmanaged custom attributes (`tenant_id`) on Admin API user creation unless the realm's `attributes` schema declares `tenant_id`. Verify `infra/keycloak/realm-export.json` and `realm-export-customers.json` on `26.8.1` via `kc.sh import --override true`.
  2. **Hostname v2 options in Keycloak 25/26:** Keycloak 25+ replaced legacy `KC_HOSTNAME_STRICT_BACKCHANNEL` / `KC_HOSTNAME_URL` flags with Hostname v2 (`KC_HOSTNAME`, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`). Inspect `docker-compose.full-stack.yml:145-190` and `k8s/` Keycloak variables so `start-dev` does not reject removed CLI/env options, while preserving the split-horizon JWT `iss` (`http://localhost:8085/realms/jtoye-dev`) and internal JWKS URL (`http://keycloak:8085/...`) required by `core-java` and `edge-go`.
  3. **Admin REST API serialization:** Phase 38 (`38-08`) moved `KeycloakAdminClient` to Jackson 3 (`tools.jackson`); verify live offboarding (`setUserEnabled`), staff invite creation/acceptance (`37-08`), and DSAR customer account deletion (`31.1-11`) against running `26.8.1`.

---

## 4. Track 39-D: PostgreSQL 18 (`39-05-PLAN.md`)

- **Declared parity sites ([`scripts/gates/postgres-major-parity.conf`](../../../scripts/gates/postgres-major-parity.conf)):**
  1. `docker-compose.full-stack.yml:43` (`image: postgres:15-alpine` → `postgres:18-alpine`)
  2. `infra/docker-compose.yml` (`image: postgres:15-alpine` → `postgres:18-alpine`)
  3. `.github/workflows/ci-cd.yaml` (`image: postgres:15-alpine` → `postgres:18-alpine`)
  4. `infra/backups/Dockerfile` (`FROM postgres:15-bookworm` → `FROM postgres:18-bookworm` and `-t ghcr.io/bralabee/jtoye-pg-backup:18-blob`)
  5. `k8s/base/pg-backup-cronjob.yaml:54` (`ghcr.io/bralabee/jtoye-pg-backup:18-blob`)
  6. `infra/dependency-horizons.yaml` (`postgres` and `jtoye-pg-backup` rows)
- **Undeclared Parity Gap Discovered in Research:**
  - **204 Java integration test files** under `core-java/src/test/java/` hardcode `new PostgreSQLContainer<>("postgres:15")` or `"postgres:15-alpine"`.
  - `scripts/gates/postgres-major-parity.conf` currently has **zero** rows watching `core-java/src/test/`.
  - `39-05-PLAN.md` must migrate the Testcontainers Postgres image to `postgres:18-alpine` (ideally via a shared test constant + mechanical sweep) and add a `SERVER` row in `postgres-major-parity.conf` so Testcontainers can never silently remain on an old Postgres major.
- **Data Migration & PGDATA Layout Hazard in PostgreSQL 18:**
  - PostgreSQL 18 Docker images changed the container data-volume layout (`/var/lib/postgresql` vs `/var/lib/postgresql/data` with major-version subdirectories) and enabled data checksums by default on `initdb`.
  - An in-place mount of a PG 15 volume into `postgres:18-alpine` fails compatibility checks. The upgrade procedure must run `pg_dumpall` / `pg_dump -Fc` before stopping PG 15, recreate the volume on `postgres:18-alpine`, restore, and verify row counts per table + all `pg_policies` rows + a `pg-backup` restore drill.

---

## 5. Track 39-E: Redis 8 (`39-06-PLAN.md`) & Tailwind CSS 4 (`39-07-PLAN.md`)

- **Redis 8 (`39-06-PLAN.md`):**
  - Pin site: [`docker-compose.full-stack.yml:200`](../../../docker-compose.full-stack.yml#L200) (`redis:7-alpine` → `redis:8.10-alpine`) and `infra/docker-compose.yml`.
  - Verify `redis-server --version` inside the running container (`8.10.2`), Lettuce 7 connection pooling (`core-java`), Spring Cache (`products`, `shops`, `shopMembership` regions), and `RateLimitInterceptor` / `IdempotencyService`.
- **Tailwind CSS 4 (`39-07-PLAN.md`):**
  - Pin site: `frontend/package.json` (`tailwindcss: 3.4.1` → `4.3.x`, `@tailwindcss/postcss`).
  - Tailwind 4 replaces `tailwind.config.ts` JS configuration with CSS-first `@theme` directives in `globals.css`, changes default border colors (`currentColor`), ring widths (`3px` → `1px`), and container query syntax (note: Phase 37-F/37-H uses `@container` from `@tailwindcss/container-queries`, which is built into Tailwind 4 core!).
  - Must verify visual parity at `390 / 768 / 1280 / 1920` across storefront, dashboard, and marketing surfaces against pre-upgrade baseline screenshots.

---

## 6. Track 39-F: Non-Runtime Deferrals from `quick 261010-ppb` (`39-08` & `39-09`)

- **`39-08-PLAN.md` — Option C Per-Shop Customer Narrowing (Flyway `V85`):**
  - `V85__customer_origin_shop_scope.sql`: adds nullable `customers.origin_shop_id UUID REFERENCES shops(id)` and `customers_aud.origin_shop_id UUID`, plus index `idx_customers_tenant_origin_shop`.
  - Per-tenant GUC-pinned backfill from `orders` (`set_config('app.current_tenant_id', ...)` loop; bare `UPDATE` under `FORCE RLS` matches 0 rows).
  - Non-Group-admin predicate (`origin_shop_id IN (:grantedShopIds) OR EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.shop_id IN (:grantedShopIds))`) enforced on `CustomerService` (list, by-id, PUT, DELETE) and `OrderService.createOrder` / `updateOrder` customer lookups; refused ID returns `404`.
  - Proven under `NOSUPERUSER NOBYPASSRLS` in Testcontainers.
- **`39-09-PLAN.md` — Old-Slug 301 Aliases (Flyway `V86`):**
  - `V86__shop_slug_alias.sql`: `shop_slug_alias(old_slug PRIMARY KEY, shop_id REFERENCES shops(id) ON DELETE CASCADE, tenant_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL)`.
  - Exempted by addition in `RlsContractTest.EXEMPT_TABLES` (resolved on anonymous storefront before tenant context exists, like `V62`/`V75`).
  - When a shop's `slug` is deliberately changed in `ShopService.updateShop`, the previous slug is recorded in `shop_slug_alias` (and deleted if claimed as a primary slug later); storefront `/shop/[slug]` returns HTTP `301` to the current canonical `/shop/{currentSlug}`.

---

## 7. Track 39-G: Library Horizons & Dependabot PRs `#904`–`#915` (`39-10-PLAN.md`)

Full per-PR root-cause analysis and execution sequence is recorded in [`39-DEPENDABOT-TRIAGE.md`](./39-DEPENDABOT-TRIAGE.md).

---

## 8. Complete Phase 39 Plan Architecture

| Plan | Wave | Track | Title | Requires Compose Mutex? |
|---|---|---|---|---|
| `39-01-PLAN.md` | 1 | `39-A` | Disarm `2026-10-26` `rabbitmq-k8s` horizon gate, refresh `manual_review` rows (`2026-10-16`), and `--refresh` 6 drifted `H-5` pin sites | **No** |
| `39-02-PLAN.md` | 1 | `39-A` | Upgrade `alpine:3.20` → `alpine:3.24` (`DEFERRED-27` retired) and compose `rabbitmq:4.3.4` → `4.3.6` (`vendor_eol: 2027-01-31`) | Yes (`seed-init`, `rabbitmq`) |
| `39-03-PLAN.md` | 2 | `39-B` | Observability stack upgrade: Prometheus `v3.13.4` LTS, Grafana `13.2.4`, Alertmanager `v0.34.1`, redis-exporter `v1.94.0`, postgres-exporter `v0.20.1` | Yes (monitoring compose) |
| `39-04-PLAN.md` | 2 | `39-C` | Keycloak `24.0.5` → `26.8.1` upgrade, Hostname v2 alignment, realm `--override true` re-import, and live Playwright auth rehearsal | Yes (`keycloak`, `core-java`, `edge-go`) |
| `39-05-PLAN.md` | 3 | `39-D` | PostgreSQL `15` → `18.6` upgrade across compose, `pg-backup:18-blob`, CI service, and all 204 Testcontainers suites + restore drill | Yes (`postgres`) |
| `39-06-PLAN.md` | 3 | `39-E` | Redis `7.4` → `8.10-alpine` upgrade across compose and k8s with Lettuce 7 & cache parity proof | Yes (`redis`) |
| `39-07-PLAN.md` | 4 | `39-E` | Tailwind CSS `3.4.1` → `4.3` migration with 4-viewport (`390/768/1280/1920`) visual regression proof | Yes (`frontend`) |
| `39-08-PLAN.md` | 1 | `39-F` | Option C per-shop customer narrowing (`V85__customer_origin_shop_scope.sql`, GUC backfill, 404 on refused ID, NOSUPERUSER IT) | No (Testcontainers IT first) |
| `39-09-PLAN.md` | 2 | `39-F` | Old-slug 301 aliases (`V86__shop_slug_alias.sql`, `RlsContractTest` exemption, `/shop/[slug]` 301 redirect) | No (Testcontainers + Jest first) |
| `39-10-PLAN.md` | 1 | `39-G` | Dependabot PRs `#904`–`#915` consolidated roll-up with `check-doc-versions.sh` alignment and paired Stripe JS major upgrade | No |
