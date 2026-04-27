---
gsd_state_version: 1.0
milestone: v2.2
milestone_name: production-hardening-vendor-order-ops
status: executing
stopped_at: "Phase 16.1-02 COMPLETE — branch feature/phase-16.1-pre-prod-hardening has 2 atomic commits (f182088 fix(16.1-02): scope OrderSseService broadcasts per tenant; bb5ffd6 test(16.1-02): tenant-isolation regression suite) closing AUDIT-W0-01 cross-tenant SSE leak. 11/11 OrderSseService* tests green. 16.1-02-SUMMARY.md created. Next plan: 16.1-03."
last_updated: "2026-04-27T23:23:52.625Z"
last_activity: 2026-04-27
progress:
  total_phases: 7
  completed_phases: 3
  total_plans: 11
  completed_plans: 9
  percent: 82
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Phase 16.1 — Pre-prod Hardening (Wave 0 council audit fixes)

## Current Position

Phase: 16.1 (Pre-prod Hardening (Wave 0 council audit fixes)) — EXECUTING
Plan: 3 of 6
Status: Ready to execute
Last activity: 2026-04-27

Progress: [████████░░] 82%

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
| Phase 16.1 P01 | 2min | 1 tasks | 1 files |
| Phase 16.1 P02 | 4min | 2 tasks | 3 files |

## Accumulated Context

### Roadmap Evolution

- Phase 16.1 inserted after Phase 16: Pre-prod Hardening — Wave 0 council audit fixes (5 confirmed pre-prod blockers): OrderSseService cross-tenant leak, Customer-orders IDOR, Stripe webhook idempotency, reviews_tenant_write RLS rewrite, FORCE RLS on 9 tables. Must land before Phase 17 Stripe refund work. (URGENT)

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
- [Phase 16.1]: Bundled three audit-finding fixes (AUDIT-W0-03 Stripe idempotency, AUDIT-W0-04 reviews_tenant_write rewrite, AUDIT-W0-05 FORCE RLS on 9 tables) into a single V35 Flyway migration. — Partial application would leave the DB in an unsafe state where idempotency exists but FORCE RLS does not. Atomic deploy is required per phase 16.1 LOCKED CONTEXT decisions.
- [Phase 16.1]: Fail-closed at OrderSseService.subscribe() — throw IllegalStateException when TenantContext is unset, rather than silently attaching a tenant-less emitter — LOCKED in 16.1-CONTEXT.md Item 1: silent fallback to a default bucket would mask a misconfigured request pipeline (JwtTenantFilter not populating context) and could re-introduce the cross-tenant leak this plan exists to close.
- [Phase 16.1]: Filter SSE broadcasts at the service layer (per-tenant ConcurrentHashMap routed by event.tenantId()), not via @PreAuthorize on OrderController — Broadcasts run on the RabbitMQ consumer thread off-request — Spring SecurityContext is not propagated there, so controller-level annotation cannot enforce tenant scoping at broadcast time. Filtering inside OrderSseService is the correct layer.

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

Last session: 2026-04-27T23:23:52.616Z
Stopped at: Phase 16.1-02 COMPLETE — branch feature/phase-16.1-pre-prod-hardening has 2 atomic commits (f182088 fix(16.1-02): scope OrderSseService broadcasts per tenant; bb5ffd6 test(16.1-02): tenant-isolation regression suite) closing AUDIT-W0-01 cross-tenant SSE leak. 11/11 OrderSseService* tests green. 16.1-02-SUMMARY.md created. Next plan: 16.1-03.
Resume file: None
