---
gsd_state_version: 1.0
milestone: v2.2
milestone_name: production-hardening-vendor-order-ops
status: in-progress
stopped_at: Phase 16 COMPLETE — DOC-01 shipped on branch `feature/phase-16-go-edge-openapi`. 5 commits (aa6e292, 1d95bb3, 36a29fc, 197243b + metadata). swaggo/swag-annotated Gin handlers emit a Swagger 2.0 spec at `edge-go/docs/swagger.json` (4 routes, 7 definitions, BearerAuth); edge serves `/openapi.json` (embedded via `docs.SwaggerInfo.ReadDoc()` — no filesystem dep, scratch-Dockerfile-friendly) + Swagger UI at `/docs/*` with `/docs → 301 /docs/index.html` convenience redirect. Handlers refactored from anonymous closures to `edgeHandlers` struct methods (handlers.go) so swaggo can parse doc comments; behaviour byte-identical and all pre-existing edge tests pass. Four new `TestOpenAPISpec_*` tests in `openapi_test.go` cover spec validity, path-set equality (stricter than count), security definition, and freshness (regenerate-and-diff). CI gate: installs `swag@v1.16.3` before `go test` so freshness test runs in CI + runs `@seriousme/openapi-schema-validator validate-api` (npm) for spec validity — verified exits non-zero on bogus spec. Swagger 2.0 (not OpenAPI 3.0) is an explicit tradeoff: swaggo v1 emits 2.0, v2 is alpha; npm validator accepts both — v2.3 upgrade path. swaggo deps pinned at `swag v1.16.3 / gin-swagger v1.6.0 / files v1.0.1` so `go` directive stays at 1.22 per CLAUDE.md (newer versions pull x/crypto v0.36+ which requires Go 1.23). Phase 15 DRAFT-ONLY complete, cluster-admin rollout pending. Phase 14 ready for PR; Phase 13 ready for PR; Phase 12 Task 12-02-07 human gate still pending.
last_updated: "2026-04-19T00:35:00Z"
last_activity: 2026-04-19
progress:
  total_phases: 6
  completed_phases: 3
  total_plans: 10
  completed_plans: 7
  percent: 70
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Milestone v2.2 — 8 P2 security/quality items from deep-audit + Work Order E (vendor order detail + Stripe refund flow)

## Current Position

Phase: 16 — Go Edge OpenAPI (COMPLETE — ready for PR)
Plan: 16-01 COMPLETE — 5 atomic commits on `feature/phase-16-go-edge-openapi`; SUMMARY.md at .planning/phases/16-go-edge-openapi/16-01-SUMMARY.md
Status: Phase 16 COMPLETE — DOC-01 shipped. swaggo-annotated Gin handlers in `edge-go/cmd/edge/` (4 business routes: /health, /ready, /api/v1/sync/batch, /api/v1/webhooks/whatsapp) emit a Swagger 2.0 spec committed at `edge-go/docs/swagger.json` (11.6KB, 7 response-type definitions, BearerAuth security scheme). Edge gateway serves `GET /openapi.json` (embedded spec via `docs.SwaggerInfo.ReadDoc()` — no filesystem read, scratch-Dockerfile-friendly) + interactive Swagger UI at `GET /docs/*any` (swaggo/gin-swagger + swaggo/files) + `GET /docs → 301 /docs/index.html` convenience redirect. Handlers refactored from anonymous closures to top-level methods on `edgeHandlers` struct (handlers.go) so swaggo can parse doc comments; behaviour byte-identical, all pre-existing edge tests pass unchanged. Named response types (HealthResponse, ReadyResponse, ComponentHealth, SyncBatchRequest, SyncBatchResponse, WebhookAck, ErrorResponse) in types.go back the {object} schema refs. 4 new in-process tests in openapi_test.go: TestOpenAPISpec_IsValidJSON (top-level keys), TestOpenAPISpec_AllRoutesDocumented (path-set equality — stricter than count so typo'd @Router is caught), TestOpenAPISpec_HasSecurityDefinition, TestOpenAPISpec_Fresh (regenerate-and-diff — fails on drift with regenerate message). CI gate in ci-cd.yaml: installs swag@v1.16.3 before `go test` so freshness test runs in CI + runs `@seriousme/openapi-schema-validator validate-api` (npm, binary `validate-api`) for spec validity (verified exit-non-zero on bogus spec). OpenAPI 3.0 caveat: swaggo/swag v1 emits Swagger 2.0; swag v2 is alpha; the npm validator accepts both — explicit tradeoff documented in 16-01-SUMMARY.md + 16-RESEARCH.md; v2.3 upgrade to swag v2 (OpenAPI 3.1) when stable. Pinned swaggo at `swag v1.16.3 / gin-swagger v1.6.0 / files v1.0.1` because newer versions pull x/crypto v0.36+ which would bump `go` directive to 1.23 — edge-go stays on Go 1.22 per CLAUDE.md. Smoke-tested on port 18081: /openapi.json 200 (11604 bytes), /docs/index.html 200, /docs 301 → /docs/index.html, /health 200. Phase 15 DRAFT-ONLY COMPLETE (cluster rollout pending); Phase 14 ready for PR; Phase 13 ready for PR; Phase 12 Task 12-02-07 human gate still pending.
Last activity: 2026-04-19 — Completed plan 16-01 on branch `feature/phase-16-go-edge-openapi`: commits aa6e292 (swaggo deps + route inventory), 1d95bb3 (swaggo annotations on all 4 routes + handler refactor), 36a29fc (generated spec + gin-swagger wiring + /docs redirect), 197243b (openapi tests + CI gate) + metadata commit for SUMMARY.md + CHANGELOG + ROADMAP + REQUIREMENTS + STATE.

Progress: [███████░░░] 70% (7/10 plans complete; 3/6 milestone-v2.2 phases complete — phases 12-17)

## Performance Metrics

**Velocity:**

- Total plans completed (M2): 10 + Milestone v2.2: 5
- Average duration: —
- Total execution time: — hours

**By Phase (milestone 2 history):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 1 | - | - |
| 2 | 1 | - | - |
| 3 | 2 | - | - |
| 4 | 1 | - | - |
| 5 | 1 | - | - |
| 6 | 1 | - | - |
| 7 | 1 | - | - |
| 8 | 2 | - | - |
| 11 | 3 | - | - |

**Milestone v2.2 (executing):**

| Phase | Plan | Duration | Tasks | Files | Tests added |
|-------|------|----------|-------|-------|-------------|
| 12    | 01   | ~90min   | 4     | 6     | 8 Java      |
| 12    | 02   | ~5min    | 6     | 7     | 8 Jest + 3 Playwright |
| 13    | 01   | ~45min   | 5     | 8     | 10 Java (6 integration + 4 unit) |
| 14    | 01   | ~20min   | 5     | 17    | 8 Java (5 StockService unit + 1 Concurrent integration + 2 StockDecrementLocation + 1 Handler + 2 refactored OrderService) |
| 14    | 02   | ~40min   | 3     | 12    | 6 Java (1 Golden-file + 1 QueryPlan + 1 QueryCount + 1 CrossTenant + 2 rewritten GetSummary) + committed 1k-row JSON fixture |
| 15    | 01   | ~60min   | 6     | 14    | Offline validator (k8s/scripts/validate-networkpolicies.py: 6 manifests, 13 podSelector refs resolved against workload labels). No code-level tests — phase is infra-docs-only. |
| 16    | 01   | ~2h      | 5     | 13    | 4 Go (TestOpenAPISpec_IsValidJSON + TestOpenAPISpec_AllRoutesDocumented + TestOpenAPISpec_HasSecurityDefinition + TestOpenAPISpec_Fresh). Plus npm validator gate in CI. |

**Recent Trend:**

- Last plan: 16-01 Go Edge OpenAPI (DOC-01) — 5 atomic commits adding swaggo/swag annotations to 4 Gin handlers, generating a Swagger 2.0 spec at `edge-go/docs/swagger.json` (4 paths, 7 definitions, BearerAuth), serving `/openapi.json` + Swagger UI at `/docs` + 301 redirect from bare `/docs`. CI gate installs `swag@v1.16.3` before `go test` so in-process `TestOpenAPISpec_Fresh` (regenerate-and-diff) runs on every PR + runs `@seriousme/openapi-schema-validator validate-api` (npm) for spec validity. Handler refactor from anonymous closures to `edgeHandlers` struct methods is behaviour-preserving — all pre-existing Go tests pass. Swagger 2.0 (not OpenAPI 3.0) is explicit tradeoff: swaggo v1 emits 2.0, swag v2 alpha, npm validator accepts both; v2.3 upgrade path. Pinned swaggo at older versions (swag v1.16.3 / gin-swagger v1.6.0 / files v1.0.1) so edge-go stays on Go 1.22 per CLAUDE.md.
- Trend: milestone v2.2 execution continues green; 7/10 plans complete (phases 13 + 14 + 16 complete, 15 implementation-complete, 12 operationally complete). Branches ready for PR: feature/phase-13-guest-tracking-tenant-validation, feature/phase-14-stock-race-summary-aggregation, feature/phase-15-k8s-networkpolicies-sealed-secrets, feature/phase-16-go-edge-openapi. Phase 12 Task 12-02-07 staging-observation gate still pending. Only Phase 17 (vendor order detail + Stripe refund) remains to close out v2.2.

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [M2 Roadmap]: API versioning first — changes every URL, doing later means double rework
- [M2 Roadmap]: KDS split into 3 phases (security, pipeline, UI) — highest complexity feature, security must be proven before UI
- [M2 Roadmap]: Test coverage has no dependencies — can parallel any phase
- [M3 Scope]: Work Orders A+B+C only — A ships in 2 days as a safety net, B/C each ~1 week. Deferring D–O to keep the milestone bounded at ~2.5 weeks
- [M3 Scope]: Skip research — state-of-codebase doc is already research-grade with file:line evidence; phase-level research will cover framework-specific pitfalls (StompBrokerRelay, Alertmanager)
- [M3 Scope]: STOMP broker behind `stomp.broker.mode` config flag — keeps local dev on in-memory, staging/prod on RabbitMQ relay
- [M3 Roadmap]: Phase 9 (SECR) ships first as standalone safety net — no dependencies, 2 days, closes credential-exposure hole before B/C start
- [M3 Roadmap]: Phase 10 (STFR) is independent of 9 and 11 — can run in parallel with either
- [M3 Roadmap]: Phase 11 (STMP) depends on Phase 9 — STMP-05 reuses the Alertmanager + Slack route from SECR-04/SECR-05
- [M3 Roadmap]: One phase per work order (no splitting) — task breakdown fits cleanly, preserves audit traceability

### Pending Todos

- **Plan 12-02 Task 07 manual gate (human-verify):** after ≥1-week staging observation of Report-Only CSP, flip header key in `frontend/next.config.mjs` from `Content-Security-Policy-Report-Only` to `Content-Security-Policy` (enforce), regenerate header snapshot via `npm test -- __tests__/header-snapshot.test.ts -u`, commit both files in one PR. Verification steps (Stripe 3DS, NextAuth signin, CSP-no-violations Playwright spec against staging) documented in 12-02-PLAN.md Task 07 + 12-02-SUMMARY.md
- Backfill `status: complete` frontmatter on the 5 quick-task SUMMARY.md files (Deferred Items below) during an early v2.2 housekeeping pass
- Commit `frontend/.env.local.example` placeholder hardening change (block-secrets hook prevents Claude from staging it — needs a manual commit outside Claude)
- Advance to next Phase 13+ plan now that Phase 12 operational work (both plans) is complete
- **Phase 15 cluster-admin rollout (4 steps):** (1) `helm install sealed-secrets-controller sealed-secrets/sealed-secrets -n kube-system`; (2) `kubeseal --fetch-cert > k8s/certs/<env>/sealed-secrets-pub.pem` per cluster; (3) `./k8s/scripts/seal-secrets.sh --cert <cert> --namespace jtoye-production --input <plaintext> --output k8s/production/sealed-secrets/`; (4) `kubectl apply -k k8s/staging/` + functional verification (frontend cannot nc postgres, frontend can wget core-java). Full details: `.planning/phases/15-k8s-networkpolicies-sealed-secrets/15-01-SUMMARY.md` + `docs/runbooks/sealed-secrets.md`.

### Blockers/Concerns

- Port conflicts in dev env (frontend 3100 because MCP server holds 3000; Postgres 5432 shared with unrelated `dealflow_*` containers) — E2E smoke tests may need those containers stopped first
- Stripe refund API (VOPS-02) requires phase-level research into idempotency keys + webhook `charge.refunded` handling — treat as a design-gate before writing the controller
- K8s Sealed Secrets (INF-02) requires an operator install in the cluster + key rotation policy — not just a manifest change
- `/public/orders?email=` enumeration risk (deferred from v2.1) — still open; not in v2.2 scope but should be noted as a known vulnerability

## Deferred Items

Items acknowledged and deferred at milestone v2.1 close on 2026-04-18:

| Category | Item | Status |
|----------|------|--------|
| quick_task | 260414-fe3-frontend-security-and-tests | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-inf-infrastructure-hardening | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-j9c-edge-go-security-hardening-batch-phase-1 | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-jkp-java-core-data-integrity-batch-phase-2-o | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-ltc-low-touch-cleanup | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |

All 5 are deep-audit P1 quick tasks that shipped in PR #40 on 2026-04-16. Work is done; only tooling metadata is missing. Consider adding `status: complete` frontmatter during v2.2 planning cleanup.

## Session Continuity

Last session: 2026-04-18T21:00:00Z
Stopped at: Phase 15 DRAFTING COMPLETE — branch `feature/phase-15-k8s-networkpolicies-sealed-secrets` has 6 atomic commits (69710e7, 1ec1187, 5ac74b2, a3755b5, f59a0fb + metadata commit for SUMMARY + CHANGELOG + ROADMAP + REQUIREMENTS + STATE) + 15-01-SUMMARY.md ready for PR to main. Both INF-01 (NetworkPolicies) and INF-02 (Sealed Secrets) drafted. Cluster-admin operator install + first kubeseal conversion is a 4-step rollout checklist documented in SUMMARY + runbook — cannot be done from this environment. Also pending: Phase 12 Task 12-02-07 (post-merge staging CSP enforce-cutover), Phase 13 PR, Phase 14 PR.
Resume file: .planning/phases/15-k8s-networkpolicies-sealed-secrets/15-01-SUMMARY.md
