# Phase 39: Platform Runtime Upgrades - Context

**Gathered:** 2026-10-16
**Status:** Ready for execution (Wave 1 planned)

<domain>
## Phase Boundary

Phase 38 moved `core-java` to Spring Boot 4.1.1 (Spring Framework 7.0.9, Hibernate 7, Flyway 12,
Lettuce 7, Jackson 3). Every other platform runtime that is past end-of-life, inside the 90-day
horizon warning window, or multiple majors behind is governed by [`infra/dependency-horizons.yaml`](../../../infra/dependency-horizons.yaml)
and enforced on every PR by [`scripts/check-dependency-horizons.sh`](../../../scripts/check-dependency-horizons.sh)
(`Operational Contracts` CI job).

**In scope:**
1. **Track 39-A (Immediate October/November 2026 Horizon Gates):**
   - `rabbitmq-k8s` (`manual_review` expires **2026-10-26**, 10 days from plan time) — live re-check
     against `https://www.rabbitmq.com/release-information`, [`ADR-0002`](../../../docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md)
     and [`docs/runbooks/rabbitmq-broker-upgrade.md`](../../../docs/runbooks/rabbitmq-broker-upgrade.md),
     plus refreshing the 6 drifted `H-5` line-number sites (`--refresh`).
   - `alpine` (`alpine:3.20`, EOL `2026-04-01`, `DEFERRED-27` expires **2026-11-30**) → `alpine:3.24`
     (`3.24.2`, EOL `2028-06-01`), retiring the `DEFERRED-27` exemption.
   - `rabbitmq` compose (`rabbitmq:4.3.4-management-alpine`, `#724` expires **2026-11-30**) →
     `rabbitmq:4.3.6-management-alpine` with vendor EOL updated to the vendor's published
     `2027-01-31` (`31 Jan 2027`, measured `2026-10-16`).
2. **Track 39-B (Observability Stack — coupled `DEFERRED-27` & `manual_review` rows):**
   - `prometheus` (`v2.48.0`, EOL `2023-12-28`, `DEFERRED-27` expires `2026-12-31`) → **`v3.13.4` (3.13 LTS, EOL `2027-07-31`)**.
   - `grafana` (`10.2.2`, EOL `2024-07-24`, `DEFERRED-27` expires `2026-12-31`) → **`13.2.4`** (cycle `13.2`, EOL `2027-05-18`).
   - `alertmanager` (`v0.27.0`) → **`v0.34.1`**, `redis-exporter` (`v1.58.0`) → **`v1.94.0`**,
     `postgres-exporter` (`v0.15.0`) → **`v0.20.1`**.
3. **Track 39-C (Identity Provider — `DEFERRED-27` expires `2026-12-31`):**
   - `keycloak` (`24.0.5`, EOL `2024-06-10`) → **`26.8.1`** (cycle `26.8`, `eol: false`), including
     Postgres-backed realm re-import (`--override true`), `tenant_id` unmanaged-attribute verification,
     JWT split-horizon check across `core-java` and `edge-go`, and full Playwright auth proof.
4. **Track 39-D (Database Major Upgrade):**
   - `postgres` (`15-alpine`) + `jtoye-pg-backup` (`15-blob`) + Testcontainers (`postgres:15-alpine`) →
     **PostgreSQL `18.6`** (`postgres:18-alpine` / `postgres:18-bookworm`, EOL `2030-11-14`), proven via
     populated dev database dump/restore drill, `check-postgres-major-parity.sh`, and NOSUPERUSER RLS suite.
5. **Track 39-E (Cache & Styling Majors):**
   - `redis` (`7-alpine` / `7.4.8`) → **`8.10-alpine`** (`8.10.2`, `eol: false`; licence accepted by
     owner `2026-10-04`).
   - `tailwindcss` (`3.4.1`) → **`4.3.x`** config rewrite, proven at `390 / 768 / 1280 / 1920` across
     storefront, dashboard, and marketing surfaces.
6. **Track 39-F (Owner-ruled `quick 261010-ppb` non-runtime deferrals):**
   - Option C per-shop customer narrowing (migration **`V85__customer_origin_shop_scope.sql`**:
     nullable `customers.origin_shop_id` + `customers_aud` mirror, GUC-pinned per-tenant backfill,
     non-Group-admin predicates on `CustomerService` and `OrderService.createOrder`/`updateOrder`,
     404 on refused ID, NOSUPERUSER proof).
   - Old-slug 301 aliases (migration **`V86__shop_slug_alias.sql`**: `shop_slug_alias` table,
     RLS-exempt by addition in `RlsContractTest.EXEMPT_TABLES`, storefront 301 redirect).
7. **Track 39-G (Library-level dependency horizons & Dependabot PR `#904`–`#915` disposition):**
   - Triage and execution of the 8 open Dependabot PRs (`#904`, `#905`, `#907`, `#908`, `#909`,
     `#910`, `#911`, `#915`) with synchronized `check-doc-versions.sh` updates and paired Stripe JS
     major bumps (`@stripe/stripe-js@10.0.0` + `@stripe/react-stripe-js@7.0.0`).

**Not in scope:** Java 25 (current LTS to 2031-09-30), Go 1.27, Gradle 9.7.1, React 19, Next.js 16,
Spring Boot 4.1.1 (completed in Phase 38).
</domain>

<evidence>
## Live Upstream Measurements (2026-10-16)

All measurements below were executed live on `2026-10-16` from the `jtoye-ops` conda environment
against `https://endoflife.date/api/*.json`, `https://www.rabbitmq.com/release-information`,
Docker Hub (`hub.docker.com/v2/repositories/library/rabbitmq/tags`), and the GitHub Releases API:

| Component | Current Pin | Measured Upstream Latest (`2026-10-16`) | Target Pin for Phase 39 | Measured Horizon / Finding |
|---|---|---|---|---|
| `rabbitmq-k8s` | `unknown` (`out_of_repo`) | `4.3.6` (`2026-09-16`) | `unknown` (`manual_review` refreshed) | Still zero `rabbitmq` workload manifests under `k8s/`; ADR-0002 remains `Proposed`. `manual_review` expires **2026-10-26**. |
| `rabbitmq` (compose) | `rabbitmq:4.3.4-management-alpine` | `4.3.6-management-alpine` | `rabbitmq:4.3.6-management-alpine` | **Critical finding:** Docker Hub and GitHub `rabbitmq/rabbitmq-server` have **zero `4.4*` releases** as of `2026-10-16`. However, `www.rabbitmq.com/release-information` now publishes **End of Community Support for 4.3 = `31 Jan 2027` (`2027-01-31`)** (extended from `2026-11-30`). `2027-01-31` is 107 days out (`> 90` warn window until `2026-11-02`). |
| `alpine` | `alpine:3.20` | `3.24.2` (cycle `3.24`) | `alpine:3.24` | `3.20` EOL `2026-04-01`; `3.24` EOL `2028-06-01`. Retires `DEFERRED-27` before `2026-11-30`. |
| `prometheus` | `prom/prometheus:v2.48.0` | `3.15.0` (non-LTS) / **`3.13.4` (LTS)** | `prom/prometheus:v3.13.4` | **Critical trap:** `ROADMAP.md` drafted `3.15`, but `endoflife.date` reports `3.15` (`lts: false`) has `eol: "2026-11-06"` (only 21 days away — inside `HORIZON_WARN_DAYS=90`, which immediately FAILS `check-dependency-horizons.sh` H-3!). Cycle **`3.13`** (`v3.13.4`) is the **Prometheus 3.x LTS** (`lts: true`, `eol: "2027-07-31"`). |
| `grafana` | `grafana/grafana:10.2.2` | `13.2.4` (cycle `13.2`) | `grafana/grafana:13.2.4` | Cycle `13.2` EOL `2027-05-18` (214 days out, well clear of the 90-day warn window). |
| `alertmanager` | `prom/alertmanager:v0.27.0` | `v0.34.1` (`2026-09-17`) | `prom/alertmanager:v0.34.1` | Not on `endoflife.date`; `manual_review` refreshed. |
| `redis-exporter` | `oliver006/redis_exporter:v1.58.0` | `v1.94.0` (`2026-10-15`) | `oliver006/redis_exporter:v1.94.0` | Not on `endoflife.date`; `manual_review` refreshed. |
| `postgres-exporter` | `prometheuscommunity/postgres-exporter:v0.15.0` | `v0.20.1` (`2026-07-08`) | `prometheuscommunity/postgres-exporter:v0.20.1` | Not on `endoflife.date`; `manual_review` refreshed. |
| `keycloak` | `quay.io/keycloak/keycloak:24.0.5` | `26.8.1` (cycle `26.8`) | `quay.io/keycloak/keycloak:26.8.1` | Cycle `26.8` has `eol: false` (`26.7` went EOL `2026-10-01`). Retires `DEFERRED-27` before `2026-12-31`. |
| `postgres` | `postgres:15-alpine` | `18.6` (cycle `18`) | `postgres:18-alpine` | Cycle `18` EOL `2030-11-14`. Moves in lockstep with `jtoye-pg-backup:18-blob` (`postgres:18-bookworm`) and Testcontainers. |
| `redis` | `redis:7-alpine` (`7.4.8`) | `8.10.2` (cycle `8.10`) | `redis:8.10-alpine` | Cycle `8.10` has `eol: false`. Owner accepted Redis 8 licence (`2026-10-04`). |
| `node` | `node:24-alpine` (`24.22.0`) | `26.12.0` (LTS starts `2026-10-28`) | Stay on `node:24-alpine` until after `2026-10-28` | `nodejs/24` is active LTS to `2028-04-30`; `nodejs/26` `lts` field is `"2026-10-28"` (still Current for 12 more days). |
</evidence>

<decisions>
## Implementation Decisions

- **D-01 — Split Track 39-A into a zero-rebuild horizon-manifest plan (`39-01`) and a runtime compose plan (`39-02`).**
  - `39-01` refreshes `rabbitmq-k8s` (`manual_review` expiring `2026-10-26`), refreshes the upstream check notes on `azurite`/`alertmanager`/`redis-exporter`/`postgres-exporter`, and runs `check-dependency-horizons.sh --refresh` to clear the 6 `H-5` line-drift notes. It touches ZERO runtime containers and can merge immediately while Phase 37 owns the shared compose stack.
  - `39-02` upgrades `alpine:3.20` → `alpine:3.24` and `rabbitmq:4.3.4-management-alpine` → `rabbitmq:4.3.6-management-alpine` (with `vendor_eol: "2027-01-31"` per the vendor's updated support table), and runs when the compose mutex is free.
- **D-02 — Target Prometheus `3.13` LTS (`prom/prometheus:v3.13.4`), NOT `3.15`.**
  - Prometheus `3.15` is a short-term cycle whose EOL (`2026-11-06`) is already inside the 90-day horizon window (`HORIZON_WARN_DAYS=90`), so pinning `v3.15.0` immediately fails `check-dependency-horizons.sh` H-3 without an exemption. Cycle `3.13` (`v3.13.4`) is the official Prometheus 3.x LTS (`lts: true`, EOL `2027-07-31`).
- **D-03 — RabbitMQ 4.3 vendor EOL update (`2027-01-31`) and 90-day window re-entry (`2026-11-02`).**
  - Because `www.rabbitmq.com/release-information` moved 4.3 End of Community Support from `2026-11-30` to `31 Jan 2027` (`2027-01-31`) and RabbitMQ `4.4` has not yet been released upstream as of `2026-10-16`, `39-02` bumps compose to `4.3.6-management-alpine` and updates `vendor_eol` to `"2027-01-31"`. Note that `2027-01-31` will re-enter the 90-day warn window on `2026-11-02`; if `4.4.0` has shipped by then, upgrade to `4.4`; otherwise re-open `#724` with an exemption expiring `2027-01-31`.
- **D-04 — Reserve Flyway `V85` and `V86` for Track 39-F (`quick 261010-ppb` deferrals).**
  - Phase 37's highest Flyway version is `V84` (`V84__order_items_guest_tracking_rls.sql`).
  - Track `39-F` (`39-08-PLAN.md` and `39-09-PLAN.md`) is kept as an independent code/schema track in Phase 39 (no compose upgrade coupling): `V85__customer_origin_shop_scope.sql` for Option C per-shop customer narrowing, and `V86__shop_slug_alias.sql` for old-slug 301 redirects.
- **D-05 — Keep Node 24 LTS (`node:24-alpine`), do not jump to Node 26 before `2026-10-28`.**
  - `nodejs/24` is supported until `2028-04-30`. `nodejs/26` does not enter LTS until `2026-10-28`.
- **D-06 — Group Dependabot PRs `#904`–`#915` into Track 39-G (`39-10-PLAN.md`) with doc-version synchronization.**
  - Dependabot PRs `#904`, `#905`, `#907`, and `#915` fail `docs-freshness` because [`scripts/check-doc-versions.sh`](../../../scripts/check-doc-versions.sh) enforces lockstep version strings in `CLAUDE.md`, `AGENTS.md`, and `README.md`.
  - Dependabot PRs `#909` (`@stripe/react-stripe-js` 7.0.0) and `#910` (`@stripe/stripe-js` 10.0.0) are coupled major upgrades that fail when tested separately and must be combined in a single branch.
</decisions>
