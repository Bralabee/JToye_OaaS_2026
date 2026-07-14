---
gsd_state_version: 1.0
milestone: v2.3
milestone_name: vendor-ops-ai-interleaved
status: verifying
stopped_at: Phase 21 context gathered
last_updated: "2026-07-14T12:54:36.253Z"
last_activity: 2026-07-14
progress:
  total_phases: 6
  completed_phases: 1
  total_plans: 5
  completed_plans: 5
  percent: 17
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-07-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Phase 21 — onboarding-blocker-ux

## Current Position

Phase: 21 (onboarding-blocker-ux) — EXECUTING
Plan: 5 of 5
Status: Phase complete — ready for verification
Last activity: 2026-07-14

Progress: [██████████] 100%

## Milestone v2.3 Phase Map

| Phase | Name | Requirements | Migration | Est. plans |
|-------|------|--------------|-----------|-----------|
| 21 | Onboarding Blocker UX | ONBD-01..05 | none | 4 |
| 22 | Vendor-Scoped Access + Responsive Dashboard Nav | VSA-01..04, MOBL-01 | V52 shop_staff | 3 |
| 23 | Image Architecture — CoW Assets + Safe Upload Pipeline | IMG-01..04 | V53 media_asset | 3 |
| 24 | Outbound Webhooks | AI-01 | (subscription table) | 2 |
| 25 | Mutating MCP Tools | AI-02 | none | 2 |
| 26 | Local-K8s Overlay + Verified Breakage Fixes | INFRA-01, INFRA-02 | none | 2 |

Execution order: 21 → 22 → 23 → 24 → 25 → 26 (locked). Hard dependency: 22 before 23 (V52 precedes V53).

## Performance Metrics

Full v2.0–v2.2 execution history (phases 1–20, quick-task ledger, per-plan durations) is preserved in `milestones/v2.2-ROADMAP.md`, git history, and MEMORY.md. v2.3 velocity starts fresh below.

**Velocity (v2.3):**

- Total plans completed: 1 / ~16 estimated
- Average duration: ~15m
- Total execution time: ~0.25 hours

**By Phase (v2.3):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 1/5 | ~15m | ~15m |
| 22 | 0/3 | - | - |
| 23 | 0/3 | - | - |
| 24 | 0/2 | - | - |
| 25 | 0/2 | - | - |
| 26 | 0/2 | - | - |

*Updated after each plan completion*
| Phase 21 P02 | 25min | 2 tasks | 9 files |
| Phase 21 P03 | 21min | 3 tasks | 8 files |
| Phase 21 P04 | 18min | 3 tasks | 7 files |
| Phase 21 P05 | 45min | 3 tasks | 2 files |

## Accumulated Context

### Roadmap Evolution

- 2026-07-14 — Milestone v2.3 (Vendor Ops + AI interleaved) roadmap created. 6 phases (21–26) continue numbering from v2.2's Phase 20. Derived from 18 requirements across 6 categories in REQUIREMENTS.md; scope locked by user 2026-07-14. MOBL-01 folded into Phase 22 (pairs with the VSA-03 shop-switcher, avoids a one-requirement phase). AI track split into two phases (24 webhooks / 25 mutating MCP — independent surfaces, `fine` granularity). Infrastructure kept as a standalone durable phase (26). Migration ordering enforced: V52 `shop_staff` (Phase 22) precedes V53 `media_asset` (Phase 23).

### Decisions

Decisions are logged in PROJECT.md Key Decisions table. Recent decisions affecting current work:

- [v2.3 Scope]: Vendor Ops + AI interleaved, thinnest/highest-pain first — onboarding (zero-migration) leads, then vendor-scoped access, image architecture, AI track, infra. Locked by user 2026-07-14; do not re-litigate.
- [v2.3 Roadmap]: MOBL-01 folded into Phase 22 — the responsive nav pairs with the shop-context switcher (same dashboard-nav surface).
- [v2.3 Roadmap]: AI track split 24/25 — outbound webhooks and mutating MCP are independent deliverables (issues #205 vs #204) on separately-shipped infra.
- [v2.3 Constraint]: onboarding-blocker path is zero-migration (`WITHDRAWN` already in V43 CHECK); derive "in review" at the DTO layer, no `IN_REVIEW` state migration.
- [Phase 21]: 21-01: POST /onboarding/withdraw reuses the already-wired WITHDRAW state-machine transitions (no SM change) via the canonical transition() path; terminal source -> RFC 7807 400; WITHDRAW never touches Shop.published.
- [Phase 21]: 21-01: company-number correction is POST /onboarding/company-number — a data edit firing NO state-machine event, gated to DRAFT/ACTION_REQUIRED (else RFC 7807 400), reusing create's @Size(32)+@Pattern verbatim; blank/whitespace = sole trader (null).
- [Phase 21]: 21-02: manual-review stall notification writes an onboarding.events row to the shared V46 outbox; exchange bean + producer + flusher dispatch shipped atomically (Pitfall 1) so the shared flusher never poison-casts it; unbound topic exchange (Phase 24 #205 delivers); emit only on MANUAL_REVIEW park, at-least-once; SM untouched, zero migrations.
- [Phase 21]: 21-03: vendor OnboardingDto derives reviewPending = VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING at the single toDto site (D-03) and now carries rejectionReason (D-09); hand-built record (not MapStruct), zero migration.
- [Phase 21]: 21-03: admin gate-resolve (POST /onboarding/admin/{id}/gates/{gateType}/resolve, PASS|WAIVE|FAIL+reason) writes ONLY the gate row (Envers-audited) then kickGateChainAfterCommit — the existing recompute advances the SM (GATES_PASSED/GATE_FAILED); never writes status/published, never runs recompute inline (CR-01). Interim resolver = tenant's own admin (D-01).
- [Phase 21]: 21-03: admin review queue is a NEW GET /onboarding/admin/reviews (VERIFYING + MANUAL_REVIEW) — the /pending approve/reject contract is untouched (Incremental Betterment, A4). ONBD-03/05 NOT marked complete: the user-visible vendor-UI halves land in 21-04.
- [Phase ?]: 21-04: onboarding support channel + review SLA config-injected via frontend NEXT_PUBLIC_* (A1); resolveSupportChannel keeps mailto out of the component (GLOBAL_RULE_6)
- [Phase ?]: 21-04: admin review-pending queue is a separate section (parallel GET /reviews) with a PASS/WAIVE/FAIL gate-resolve dialog; approve/reject queue preserved (A4)

### Pending Todos

- After v2.3 work pauses/completes: re-count the remediation backlog (`gh issue list --label remediation --state all`) — HANDOFF Step 2.
- Then (LAST): run the comprehensive QA audit with the upgraded charter (lifecycle dead-end sweep + role-spanning journey matrix) — HANDOFF Step 3. Rebuild ALL containers first.

### Blockers/Concerns

- **RULE 0 — one runtime at a time on local**: compose and the minikube `jtoye` cluster share one dev Postgres. Never run compose `core-java`/`edge-go` AND cluster core/edge writers at once. Compose is canonical; cluster is STOPPED at handoff.
- **Rebuild-all rule**: after ANY code change, rebuild ALL containers before E2E/QA. Cluster core is a pre-V51 image tag — re-tag + `minikube image load` fresh images before any k8s redeploy.
- **Phase 23 vision provider**: content-relevance gate (IMG-03 stage 6) needs Ollama (host :11434 conflict) or a hosted model — ships behind an advisory-default flag; the pipeline is not blocked on it.
- **Phase 26 netpol caveat**: minikube's default CNI does NOT enforce NetworkPolicies — local is not proof for netpol behaviour (needs policy-enforcing CNI or AKS).

## Session Continuity

Last session: 2026-07-14T12:54:17.269Z
Stopped at: Phase 21 context gathered
Resume file: None
