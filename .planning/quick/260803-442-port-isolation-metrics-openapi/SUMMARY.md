---
quick_id: 260803-383
slug: 442-port-isolation-metrics-openapi
status: complete
date: 2026-08-03
issues: ["#442"]
branch: fix/442-port-isolation-metrics-openapi
---

# Summary — #442 (SEC-02/F-M7): port isolation, not authentication

## The finding was largely wrong about where the exposure is

Re-verified before implementing, per the SEC-01/A1 precedent. **Two of the three filed claims are
falsified**, and the profile that was actually exposed is one the finding never mentions.

| filed claim | verdict |
|---|---|
| metrics unauthenticated in **prod** | **FALSIFIED.** prod binds actuator to a separate `management.server.port`; the k8s Service publishes only the app port. Already proven both directions by `ManagementPortMetricsIntegrationTest`. **No change made.** |
| **OpenAPI** unauthenticated in prod | **FALSIFIED for the default config.** `application-prod.yml` sets `springdoc.api-docs.enabled=${SWAGGER_ENABLED:false}` — the document does not exist in prod unless someone turns it on. Gated anyway as defence in depth, **not** recorded as closing a live hole. |
| **edge** metrics unauthenticated | **TRUE.** `/metrics` served ahead of the JWT group on the single published port. |
| *(unreported)* **staging** | **TRUE, and the real one.** No `management.server.port`, so metrics/env/configprops rode the published app port; `health.show-details: always`; **and springdoc explicitly enabled**, so the full API surface was anonymous on a deployed environment. |

So the issue's premise — *"the one Group B finding that reaches prod"* — does not survive contact with
the config. What reaches a deployed environment is **staging** and the **edge gateway**.

## Why authentication was rejected

`prometheus.yml.tmpl` declares no `basic_auth` and no `authorization` for either job. Authenticating
these endpoints would have blinded the Phase 27 alerting layer silently — the failure the issue's own
criteria warn about. The fix is port isolation, mirroring what prod already does.

## Two defects in my own work, both caught by the break arm

**1. The first test could not fail.** `OpenApiProdProfileGatingTest` asserted "not 200" and passed
*identically with the fix reverted*, because springdoc is off in prod so everything 404s. An
already-true assertion. Fixed by forcing springdoc on in the test, leaving the security gate as the
only possible cause of a non-200.

**2. `!isProd` left staging wide open.** Staging enables Swagger deliberately, so gating only prod
left the API surface anonymous there while looking fixed. Now gated on a local-development allowlist
**and** the absence of a deployed profile — both halves needed:

- allowlist alone is defeated by this repo's prod-test idiom (`@ActiveProfiles({"prod","test"})`),
  which would have made those tests silently exercise the dev path;
- deployed-check alone leaves an unrecognised profile (e.g. `qa`) permitAll.

`test` must stay permitted or `OpenApiSnapshotTest` breaks the breaking-change gate.

Proven necessary: with `!isProd` restored, **staging fails while prod still passes** — testing prod
alone would have shipped the gap.

## Evidence

| arm | result |
|---|---|
| edge-go, `EDGE_MANAGEMENT_PORT` unset | `/metrics` **200** on app port, no management listener — dev byte-identical |
| edge-go, set | `/metrics` **404** on app port, **200** with **42** metric families on management port, `/health` still 200 |
| break: OpenAPI matcher unconditional | **2 failures**, control still passing |
| break: staging management port removed | **1 failure** — and only `prometheus`; `configprops` passed in *both* arms (never permitAll), so it is labelled NOT load-bearing rather than counted as proof |
| break: `!isProd` restored | staging fails, prod passes |
| closing clean arm | **9 tests / 0 failures** across 4 classes incl. `OpenApiSnapshotTest` |

**AC-4 on the delivered runtime** (rebuilt, 4/4 FRESH): `up{job=~"core-java|edge-go"}` both **1**,
`check-alert-liveness` / `check-alert-metrics` / `check-alert-rules` all rc=0. Unit suite 870/0,
`go test` ok, 18/19 gates green (`check-e2e-skip-budget` VOID is pre-existing).

## Not done

- k8s wiring for `EDGE_MANAGEMENT_PORT` is deliberately **not** added: `k8s/` ships no monitoring
  manifests (DPLY-03), so nothing scrapes edge there yet. Setting a port with no scraper would be
  configuration theatre. The seam exists; wiring belongs with the monitoring work.
- Staging's wide exposure list (`env`, `configprops`) left as-is — defensible now that it is off the
  public port, and narrowing it is a separate judgement call.
