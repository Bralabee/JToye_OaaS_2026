---
quick_id: 260803-383
slug: 442-port-isolation-metrics-openapi
date: 2026-08-03
issues: ["#442"]
branch: fix/442-port-isolation-metrics-openapi
---

# Quick: #442 (SEC-02/F-M7) — port isolation, not authentication

## Re-verified before planning; one filed claim is FALSIFIED

Following the SEC-01/A1 precedent. Full evidence posted as a comment on #442.

| filed claim | verdict |
|---|---|
| metrics unauthenticated **in prod** | **FALSIFIED.** `application-prod.yml` sets `management.server.port`; the k8s `Service` (`k8s/base/core-java-deployment.yaml:556-569`) publishes **only 9090**. Documented at `SecurityConfig:143-153`, proven both directions by `ManagementPortMetricsIntegrationTest`. **No change.** |
| **OpenAPI** unauthenticated in all profiles | **TRUE — the only surface that reaches production.** `/v3/api-docs/**`, `/swagger-ui/**` are `permitAll` with no profile condition, on the published app port |
| **edge** metrics unauthenticated | **TRUE.** `/metrics` registered ahead of the JWT group on the single published 8080 |
| *(not in the finding)* **staging is the weakest profile** | **TRUE.** `application-staging.yml` sets **no** `management.server.port`, so actuator rides the published app port; exposure adds `metrics,env,configprops`; `health.show-details: always` |

## Why authentication is the WRONG fix

`infra/monitoring/prometheus/prometheus.yml.tmpl` defines **no** `basic_auth` and **no**
`authorization` for the `core-java` or `edge-go` jobs. Authenticating those endpoints takes the whole
Phase 27 alerting layer down **silently** — the issue's own acceptance criteria warn about exactly
this. The fix is **port isolation**, the approach prod already uses.

## Tasks

### T1 — gate the OpenAPI/Swagger matchers to non-prod (`SecurityConfig`)

`isProd` already exists in the method (used for the actuator comment and HSTS). Move the api-docs /
swagger-ui matchers behind it: `permitAll` when not prod, fall through to
`anyRequest().authenticated()` in prod.

No legitimate consumer breaks: the breaking-change gate builds the document from a Gradle task
(`generateOpenApiSpec` → `OpenApiSnapshotTest`, Testcontainers, `test` profile), never by fetching a
running prod instance. Verified before editing.

### T2 — align staging with prod (`application-staging.yml`)

Add `management.server.port` (env-overridable, never hardcoded) so actuator leaves the published app
port, and set `health.show-details: when-authorized` to match prod. Exposure list left wide
deliberately — once the surface is off the public port, `env`/`configprops` are a debugging
convenience on an internal port, and narrowing them is a separate judgement call.

### T3 — edge-go second listener for `/metrics`

New `EDGE_MANAGEMENT_PORT`. **Unset ⇒ current behaviour exactly** (metrics on the main router), so
dev, compose and the alert gates are untouched. When set, `/metrics` is served **only** on the
management listener and is absent from the main router.

Mirrors `management.server.port` semantics deliberately, so one mental model covers both runtimes.
`/health` and `/ready` stay on the main port — the kubelet probes target them there
(`k8s/base/edge-go-deployment.yaml`), and moving them would break rollouts.

## Acceptance criteria

Falsification-first — each observed FAILING before it is trusted.

- [ ] AC-1: under `prod`, the OpenAPI document requires authentication; under `dev` it does not.
      Asserted by a test that FAILS on the current tree.
- [ ] AC-2: under a staging-shaped config, actuator is not served on the app port.
- [ ] AC-3: with `EDGE_MANAGEMENT_PORT` set, `/metrics` answers on the management listener and is
      **absent** (404) from the main port; with it unset, `/metrics` answers on the main port exactly
      as today.
- [ ] AC-4: **the scrape still works.** `check-alert-liveness.sh`, `check-alert-metrics.sh` and
      `check-alert-rules.sh` all rc=0 against the running dev stack — proven by a real scrape, not by
      config inspection. This is the criterion that catches a blinded alerting layer.
- [ ] AC-5: prod metrics claim recorded as **falsified / already mitigated**, never as "fixed".
- [ ] AC-6: no regression — core-java unit suite, edge-go `go test`, both docs gates.

## Out of scope

- #440 (F-H2, the tenant-override header advertised in the OpenAPI doc) — related but separately filed.
- NetworkPolicy enforcement: memory records Calico is not installed on the local minikube, so any
  policy added here would be unenforced locally and unproven.
