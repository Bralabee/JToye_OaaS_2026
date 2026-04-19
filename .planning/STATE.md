---
gsd_state_version: 1.0
milestone: v2.2
milestone_name: production-hardening-vendor-order-ops
status: in-progress
stopped_at: Phase 14 COMPLETE — CQ-01 + CQ-02 shipped on branch `feature/phase-14-stock-race-summary-aggregation`. Plan 14-01 (6 commits ec89443, c062f3a, ad02c98, fe27915, 20ebf24, c77fbdd) + Plan 14-02 (3 commits 635cc22, 06964ac, 83fa33a). getSummary now issues 2 JPQL aggregate queries with SUM/CASE WHEN + GROUP BY vatRate, replacing findAll()+4-stream-reduce. Output parity pinned by 1k-row golden fixture; index usability pinned by EXPLAIN ANALYZE with enable_seqscan=off; query count pinned to exactly 2 prepared statements; cross-tenant disjointness pinned via raw-SQL + reflection-based @Query inspection. Branch ready for PR to main. Phase 12 Task 12-02-07 human gate still pending.
last_updated: "2026-04-19T00:55:00Z"
last_activity: 2026-04-19
progress:
  total_phases: 6
  completed_phases: 1
  total_plans: 11
  completed_plans: 5
  percent: 45
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Milestone v2.2 — 8 P2 security/quality items from deep-audit + Work Order E (vendor order detail + Stripe refund flow)

## Current Position

Phase: 14 — Stock Race Fix + Summary Aggregation (COMPLETE — both plans shipped)
Plan: 14-02 COMPLETE — 3 atomic commits on `feature/phase-14-stock-race-summary-aggregation`; SUMMARY.md at .planning/phases/14-stock-race-fix-summary-aggregation/14-02-SUMMARY.md
Status: Phase 14 COMPLETE — CQ-02 getSummary DB aggregation shipped. FinancialTransactionService.getSummary() rewritten with 2 JPQL constructor-expression queries (scalar aggregate + GROUP BY vatRate) replacing findAll()+4-stream-reduce. FinancialAggregateRow + FinancialVatRow DTOs added. Output parity pinned by FinancialSummaryGoldenFileTest against a committed 1000-row deterministic golden fixture (byte-for-byte VAT math via SQL CASE WHEN mirroring entity.calculateVatAmount). Query count pinned to exactly 2 prepared statements by FinancialSummaryQueryCountTest (Hibernate Statistics getPrepareStatementCount — the RED→GREEN delta). Index usability pinned by FinancialSummaryQueryPlanTest using EXPLAIN ANALYZE with enable_seqscan=off at 10k tenant-A + 2k decoy-tenant rows. Cross-tenant regression pinned by FinancialSummaryCrossTenantIsolationTest (raw-SQL per-tenant disjointness + reflection-based JPQL no-explicit-tenant-WHERE assertion). Full :core-java:test sweep: 415 tests / 37 pre-existing RabbitMQ PLAIN auth failures (all unchanged) / 1 skipped / zero new regressions. Branch ready for PR to main.
Last activity: 2026-04-19 — Completed plan 14-02 on branch `feature/phase-14-stock-race-summary-aggregation`: commits 635cc22 (RED tests), 06964ac (GREEN — DTOs + JPQL + service rewrite), 83fa33a (regression pin — cross-tenant + CHANGELOG)

Progress: [████▌░░░░░] 45% (5/11 plans complete; 1/6 milestone-v2.2 phases complete — phases 12-17)

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

**Recent Trend:**

- Last plan: 14-02 getSummary DB Aggregation (CQ-02) — 3 atomic commits (RED→GREEN→pin); 2 JPQL constructor-expression queries replace findAll()+reduce; COALESCE(SUM(...), 0L) empty-safety; qualified-enum literals in JPQL CASE WHEN; Hibernate Statistics query-count pin; EXPLAIN ANALYZE + enable_seqscan=off index-usability pin (deviation — planner legitimately prefers Seq Scan at 12k total rows / 145 pages Testcontainers scale); reflection-based JPQL @Query inspection for no-explicit-tenant-WHERE (STRIDE T-14-04 mitigation); superuser-RLS-bypass environmental caveat documented in deferred-items.md §2.
- Trend: milestone v2.2 execution continues green; 5/11 plans complete (Phase 14 COMPLETE — both plans shipped); branch `feature/phase-14-stock-race-summary-aggregation` ready for PR to main; Phase 13 still ready for PR; Phase 12 Task 12-02-07 staging-observation gate still pending

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

Last session: 2026-04-19T00:55:00Z
Stopped at: Phase 14 COMPLETE — branch `feature/phase-14-stock-race-summary-aggregation` has 9 commits total (14-01: ec89443, c062f3a, ad02c98, fe27915, 20ebf24, c77fbdd, 98176a5; 14-02: 635cc22, 06964ac, 83fa33a) + 14-02-SUMMARY.md ready for PR to main. Both CQ-01 (stock race) and CQ-02 (getSummary DB aggregation) shipped. Phase 12 Task 12-02-07 (post-merge staging CSP enforce-cutover) and Phase 13 PR still pending.
Resume file: .planning/phases/14-stock-race-fix-summary-aggregation/14-02-SUMMARY.md
