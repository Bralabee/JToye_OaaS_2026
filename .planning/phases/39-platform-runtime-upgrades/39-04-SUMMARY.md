---
phase: 39-platform-runtime-upgrades
plan: 04
subsystem: infra
tags: [keycloak, oidc, oauth2, dependency-horizons, ops]

requires:
  - phase: 39-platform-runtime-upgrades
    provides: Plan 39-01 horizon baseline and DEFERRED-27 inventory
provides:
  - Keycloak upgraded from 24.0.5 (EOL 2024-06-10) to 26.8.1 (cycle 26.8, eol: false) across all 3 compose files
  - Hostname v2 configuration (`KC_HOSTNAME: http://localhost:8085`) and `KC_LEGACY_OBSERVABILITY_INTERFACE: "true"` preserving split-horizon issuer and port 8080 health/metrics endpoints
  - Final `DEFERRED-27` exemption deleted from `infra/dependency-horizons.yaml` (`active-exemptions=0`)
affects: [phase-39]

tech-stack:
  added: []
  patterns:
    - "Keycloak 26 Hostname v2 split-horizon configuration (`KC_HOSTNAME: http://localhost:8085` + `KC_HOSTNAME_STRICT: false`) with `KC_LEGACY_OBSERVABILITY_INTERFACE: true` so `/health/ready` and `/metrics` remain on port 8080"

key-files:
  created:
    - .planning/phases/39-platform-runtime-upgrades/39-04-SUMMARY.md
    - .planning/phases/39-platform-runtime-upgrades/evidence/39-04-keycloak26-auth.txt
  modified:
    - docker-compose.full-stack.yml
    - infra/docker-compose.yml
    - infra/docker-compose.hostnet.yml
    - infra/keycloak/realm-export.template.json
    - infra/dependency-horizons.yaml
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/codebase/CONCERNS.md
    - docs/architecture/ESSENTIAL_ARCHITECTURE.md
    - docs/architecture/SYSTEM_DESIGN_V2.md
    - docs/guides/DEPLOYMENT_GUIDE.md
    - docs/AI_CONTEXT.md

key-decisions:
  - "Replaced the deprecated Hostname v1 `KC_HOSTNAME: localhost` + `KC_HOSTNAME_PORT: 8085` pair with Hostname v2 `KC_HOSTNAME: http://localhost:8085` so Keycloak 26.8.1 emits `issuer: http://localhost:8085/realms/...` instead of ignoring `KC_HOSTNAME_PORT` and emitting `:8080`."
  - "Enabled `KC_LEGACY_OBSERVABILITY_INTERFACE: true` in `docker-compose.full-stack.yml` so `/health/ready` and `/metrics` remain on port 8080, preserving the compose healthcheck and Prometheus scrape target without breaking changes."
  - "Removed the final `DEFERRED-27` exemption on `keycloak` in `infra/dependency-horizons.yaml`, bringing `active-exemptions` across the entire repository to `0`."

patterns-established: []

requirements-completed: [OPS-HORIZON-04]

duration: 12min
completed: 2026-10-16
---

# Phase 39 Plan 04: Keycloak 26.8.1 Upgrade Summary

**Upgraded Keycloak from `24.0.5` (EOL `2024-06-10`) to `26.8.1` (cycle `26.8`, `eol: false`), configured Hostname v2 (`KC_HOSTNAME: http://localhost:8085`) and `KC_LEGACY_OBSERVABILITY_INTERFACE: "true"`, verified dual-realm import (`jtoye-dev` + `jtoye-customers`), managed `tenant_id` attribute retention, and live OIDC token issuance, and retired the final `DEFERRED-27` exemption (`active-exemptions=0`).**

## Accomplishments

- **Compose & Realm Template Upgrade**:
  - Updated `quay.io/keycloak/keycloak:24.0.5` -> `quay.io/keycloak/keycloak:26.8.1` across [`docker-compose.full-stack.yml:145`](../../../docker-compose.full-stack.yml#L145), [`infra/docker-compose.yml:71`](../../../infra/docker-compose.yml#L71), and [`infra/docker-compose.hostnet.yml:26`](../../../infra/docker-compose.hostnet.yml#L26).
  - Updated `keycloak` environment in [`docker-compose.full-stack.yml:160-162`](../../../docker-compose.full-stack.yml#L160-L162) to `KC_HOSTNAME: http://localhost:8085`, `KC_LEGACY_OBSERVABILITY_INTERFACE: "true"`, and `KC_HOSTNAME_STRICT: "false"`.
  - Updated `"keycloakVersion"` in [`infra/keycloak/realm-export.template.json:2270`](../../../infra/keycloak/realm-export.template.json#L2270) to `"26.8.1"`.
- **Dependency Horizon Manifest (`active-exemptions=0`)**:
  - Updated the `keycloak` entry in [`infra/dependency-horizons.yaml:113-132`](../../../infra/dependency-horizons.yaml#L113-L132) to `pin: "quay.io/keycloak/keycloak:26.8.1"`, `eol_cycle: "26.8"`, `eol_date: "false"`, expanded `sites:` to all three compose files, and deleted the last `DEFERRED-27` exemption (`violations=0`, `active-exemptions=0`).
- **Live Realm Import, Managed `tenant_id` & OIDC Verification**:
  - Booted `quay.io/keycloak/keycloak:26.8.1` (`Keycloak 26.8.1`, `JVM: 21.0.12.1`) with rendered `jtoye-dev` and `jtoye-customers` realms, verified `/health/ready` (`UP`) and `/metrics` (`143` metric families) on port `8080`, verified split-horizon issuer (`http://localhost:8085/realms/jtoye-dev` and `http://localhost:8085/realms/jtoye-customers`), verified Admin API user creation preserves managed `tenant_id` attributes (`HTTP 201`), verified live OIDC token minting for both service accounts (`integration-orders-rw`) and vendor users (`admin-user`), and confirmed `KeycloakAdminClientTest` passes (`rc=0`).
  - Full proof recorded in [`evidence/39-04-keycloak26-auth.txt`](evidence/39-04-keycloak26-auth.txt).
