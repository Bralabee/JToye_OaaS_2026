---
gsd_state_version: 1.0
milestone: v2.3
milestone_name: vendor-ops-ai-interleaved
status: ready_to_plan
stopped_at: Phase 22 complete (7/7) — ready to discuss Phase 23
last_updated: 2026-07-15T08:42:33.970Z
last_activity: 2026-07-15
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 12
  completed_plans: 12
  percent: 33
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-07-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Phase 23 — vendor scoped access + responsive dashboard nav

## Current Position

Phase: 23
Plan: Not started
Status: Ready to plan
Last activity: 2026-07-15 - Completed quick task 260715-fcq (docs reconciliation to v2.3 / 1401 / V56)

Progress: [██████████] 100%

## Milestone v2.3 Phase Map

| Phase | Name | Requirements | Migration | Est. plans |
|-------|------|--------------|-----------|-----------|
| 21 | Onboarding Blocker UX | ONBD-01..05 | none | 4 |
| 22 | Notifications & Comms | COMMS-01..07 (absorbs AI-01 #205, #208) | Comms tables (post-V53, out-of-order) | ~5 |
| 23 | Vendor-Scoped Access + Responsive Dashboard Nav | VSA-01..04, MOBL-01 | V52 shop_staff | 3 |
| 24 | Image Architecture — CoW Assets + Safe Upload Pipeline | IMG-01..04 | V53 media_asset | 3 |
| 25 | Mutating MCP Tools | AI-02 | none | 2 |
| 26 | Local-K8s Overlay + Verified Breakage Fixes | INFRA-01, INFRA-02 | none | 2 |

Execution order: 21 → 22 → 23 → 24 → 25 → 26 (locked; Comms inserted at 22 on 2026-07-14, absorbing the former standalone Outbound Webhooks). Hard dependency: 23 before 24 (V52 `shop_staff` precedes V53 `media_asset`).

## Performance Metrics

Full v2.0–v2.2 execution history (phases 1–20, quick-task ledger, per-plan durations) is preserved in `milestones/v2.2-ROADMAP.md`, git history, and MEMORY.md. v2.3 velocity starts fresh below.

**Velocity (v2.3):**

- Total plans completed: 13 / ~16 estimated
- Average duration: ~15m
- Total execution time: ~0.25 hours

**By Phase (v2.3):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 5 | - | - |
| 22 | 7 | - | - |
| 23 | 0/3 | - | - |
| 24 | 0/3 | - | - |
| 25 | 0/2 | - | - |
| 26 | 0/2 | - | - |

*Updated after each plan completion*
| Phase 21 P02 | 25min | 2 tasks | 9 files |
| Phase 21 P03 | 21min | 3 tasks | 8 files |
| Phase 21 P04 | 18min | 3 tasks | 7 files |
| Phase 21 P05 | 45min | 3 tasks | 2 files |
| Phase 22 P01 | 11min | 3 tasks | 13 files |
| Phase 22 P02 | 20m | 3 tasks | 14 files |
| Phase 22 P03 | 50m | 3 tasks | 13 files |
| Phase 22 P04 | 16min | 3 tasks | 11 files |
| Phase 22 P05 | 32min | 3 tasks | 15 files |
| Phase 22 P06 | 12min | 3 tasks | 10 files |
| Phase 22 P07 | 13min | 3 tasks | 10 files |

## Accumulated Context

### Roadmap Evolution

- 2026-07-14 — **Phase 22 "Notifications & Comms" inserted** ahead of the original order (was Vendor-Scoped Access). Absorbs the former standalone Outbound Webhooks (#205) + WhatsApp (#208). Vendor-Scoped Access → 23, Image → 24; Mutating MCP (25) + K8s (26) unchanged. Scout found order-lifecycle email already works (`EmailNotificationService` + `OrderStateChangeListener`) — so the phase is extend+govern+add-channels, not build-first-consumer. SPEC written (7 reqs COMMS-01..07, ambiguity 0.16). Decided by user; roadmap-slot + 6 spec answers logged in `22-SPEC.md`.
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
- [Phase 22]: 22-01: Order-email path frozen (Pitfall 5 path A) — EmailNotificationService + its SimpleMailMessage test untouched; all NEW events ride the MimeMessageHelper multipart EmailChannel. — Guarantees zero regression to the one working channel (Incremental Betterment).
- [Phase 22]: 22-01: NotificationChannel seam owns NO consent category (22-02 owns NotificationCategory); RecipientRole {CUSTOMER,VENDOR} is the audience axis. Keeps 22-01/02/03 parallel-safe. — Decoupled contract so Wave-1 plans do not share a type.
- [Phase 22]: 22-01: Marked only COMMS-07 complete; COMMS-02 left pending (shared with 22-04's dispatch, which delivers its Mailhog/recipient acceptance). — Avoids a false-green — COMMS-02 acceptance is unmet until 22-04.
- [Phase 22]: 22-03: webhook_subscription (V55) FORCE-RLS via current_tenant_id(); plaintext signing_secret returned once on create+rotate, never on GET/list; rotate regenerates via SecureRandom.
- [Phase 22]: 22-03: WebhookSubscriptionController mounts /api/v1/webhooks hard-coded (webhook pkg NOT in WebConfig.API_V1_PACKAGES; RefundController precedent) — keeps change inside webhook/*, no WebConfig edit.
- [Phase 22]: 22-03: vendor target_url HTTPS-only + SSRF-blocked (loopback/RFC1918/link-local/169.254.169.254/IPv6-ULA) via WebhookUrlValidator at create; toggle webhook.target.block-private-ranges default ON; RFC 7807 400.
- [Phase 22]: 22-03: OpenAPI snapshot regen DEFERRED to phase gate — committed snapshot already stale for Phase 21 + 22-02; webhook-only partial regen impossible, out-of-scope per SCOPE BOUNDARY (deferred-items.md).
- [Phase 22]: 22-04: order-audience wired additively — the new order.notifications path is VENDOR-ONLY so the untouched legacy customer path is not duplicated (COMMS-02 = customer + vendor, no double-email; Pitfall 5 path A)
- [Phase 22]: 22-04: bound the Phase-21 dead onboarding.events exchange + a refund.notifications queue on order.refunded + a second payment.notifications queue — each its OWN durable queue (never steals from an incumbent consumer); PaymentEventOutboxFlusher untouched (Pitfall 3, consumers only)
- [Phase 22]: 22-04: first-deploy onboarding-stall backlog re-delivery ACCEPTED with no cutoff (RESEARCH A5) — genuine unresolved stalls, ConsentGate still applies
- [Phase ?]: 22-06: webhook management UI in lib/webhooks-api.ts wrapping the default apiClient (api-client.ts untouched); grouped event-type checkboxes map one-per-backend-enum-family; once-only SecretRevealDialog blocks backdrop/Esc/X; cards below sm + Table at sm+ (375px); replay carries a secure Idempotency-Key
- [Phase ?]: 22-07: Public /unsubscribe is a server page.tsx (exports metadata.robots noindex,nofollow) wrapping a Suspense'd use-client content module; token/email sent to the API but never rendered into meta/body (PII-safe); route sitemap-excluded + link-graph-allowlisted (email-only entry).
- [Phase ?]: 22-07: Phase-gate docs reconcile = docs-freshness.sh --write (schema 56, total 1388) + gradle updateOpenApiSnapshot (+14 endpoints, 0 removed) -> docs-freshness EXIT=0; whole-repo artifacts reconciled once at the last plan.
- [Phase ?]: 22-07: Authenticated E2E = real Keycloak login + Playwright route() stubs (dashboard-mobile pattern); unsubscribe-flow 6/6 live green; webhook dashboard specs need E2E_VENDOR_PASSWORD for a live authenticated run (env creds unknown).

### Pending Todos

- After v2.3 work pauses/completes: re-count the remediation backlog (`gh issue list --label remediation --state all`) — HANDOFF Step 2.
- Then (LAST): run the comprehensive QA audit with the upgraded charter (lifecycle dead-end sweep + role-spanning journey matrix) — HANDOFF Step 3. Rebuild ALL containers first.

### Blockers/Concerns

- **RULE 0 — one runtime at a time on local**: compose and the minikube `jtoye` cluster share one dev Postgres. Never run compose `core-java`/`edge-go` AND cluster core/edge writers at once. Compose is canonical; cluster is STOPPED at handoff.
- **Rebuild-all rule**: after ANY code change, rebuild ALL containers before E2E/QA. Cluster core is a pre-V51 image tag — re-tag + `minikube image load` fresh images before any k8s redeploy.
- **Phase 23 vision provider**: content-relevance gate (IMG-03 stage 6) needs Ollama (host :11434 conflict) or a hosted model — ships behind an advisory-default flag; the pipeline is not blocked on it.
- **Phase 26 netpol caveat**: minikube's default CNI does NOT enforce NetworkPolicies — local is not proof for netpol behaviour (needs policy-enforcing CNI or AKS).

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260715-fcq | Reconcile stale docs to current state (milestone identity → v2.3; test count 1257→1401; schema V51→V56; incl. AGENTS.md mirror) | 2026-07-15 | aed0929 | [260715-fcq-reconcile-stale-project-docs-to-current-](./quick/260715-fcq-reconcile-stale-project-docs-to-current-/) |

## Session Continuity

Last session: 2026-07-15T04:56:35.210Z
Stopped at: Completed 22-04-PLAN.md
Resume file: None
