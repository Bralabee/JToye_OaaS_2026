---
gsd_state_version: 1.0
milestone: v2.2
milestone_name: production-hardening-vendor-order-ops
status: in-progress
stopped_at: Phase 15 DRAFTING COMPLETE — INF-01 + INF-02 drafted on branch `feature/phase-15-k8s-networkpolicies-sealed-secrets`. 6 commits (69710e7, 1ec1187, 5ac74b2, a3755b5, f59a0fb + metadata commit). NetworkPolicies (default-deny baseline + 4 tier allow-lists + pg-backup + placeholder) wired into `k8s/base/kustomization.yaml` — inherited by `k8s/staging/` + `k8s/production/` overlays (actual layout, not `k8s/overlays/*` as ROADMAP originally said). Offline validator passes: 6 files, 13 podSelector refs, all resolve. SealedSecrets is DRAFT-ONLY — docs/runbooks/sealed-secrets.md (11-section runbook), k8s/scripts/seal-secrets.sh (batch kubeseal with yq multi-doc split), secrets-template.yaml flagged LEGACY. Cluster-admin rollout pending (4-step checklist in 15-01-SUMMARY.md). Phase 14 ready for PR; Phase 13 ready for PR; Phase 12 Task 12-02-07 human gate still pending.
last_updated: "2026-04-18T21:00:00Z"
last_activity: 2026-04-18
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 10
  completed_plans: 6
  percent: 60
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Milestone v2.2 — 8 P2 security/quality items from deep-audit + Work Order E (vendor order detail + Stripe refund flow)

## Current Position

Phase: 15 — K8s NetworkPolicies + Sealed Secrets (DRAFTING COMPLETE — cluster rollout pending)
Plan: 15-01 DRAFTING COMPLETE — 6 atomic commits on `feature/phase-15-k8s-networkpolicies-sealed-secrets`; SUMMARY.md at .planning/phases/15-k8s-networkpolicies-sealed-secrets/15-01-SUMMARY.md
Status: Phase 15 DRAFT-ONLY COMPLETE — INF-01 + INF-02 shipped as documentation + manifests + scripts, with the cluster-admin operator install + first kubeseal conversion flagged as a 4-step rollout checklist in the SUMMARY. NetworkPolicies: 6 manifests under k8s/base/networkpolicies/ (default-deny baseline + frontend/core-java/edge-go/datastores allow-lists + inert observability placeholder) wired into k8s/base/kustomization.yaml so both staging + production overlays inherit them automatically. Egress rules scope public 443 via ipBlock 0.0.0.0/0 with RFC1918 in except[] (SSRF defense + Stripe-CIDR volatility accepted — rationale in README + research). Offline validator k8s/scripts/validate-networkpolicies.py passes: 6 files, 13 podSelector matchLabels references, all resolve to real workload labels. Sealed Secrets: docs/runbooks/sealed-secrets.md is an 11-section operational runbook (helm install, public-key fetch, interactive + batch conversion, overlay wiring, dev/local .env fallback, 30-day automatic + emergency key rotation with full re-seal, rollback on decryption failure, mandatory off-cluster key backup, cheatsheet). k8s/scripts/seal-secrets.sh batches plaintext-Secret → SealedSecret with yq multi-doc split + kubeseal + namespace override + kind validation. k8s/base/secrets-template.yaml flagged LEGACY via new header pointing to runbook. ROADMAP traceability corrected: actual layout is k8s/staging + k8s/production (flat), not k8s/overlays/*. Cluster rollout requires cluster-admin access not available in this environment.
Last activity: 2026-04-18 — Completed plan 15-01 on branch `feature/phase-15-k8s-networkpolicies-sealed-secrets`: commits 69710e7 (research), 1ec1187 (6 NetworkPolicy manifests + kustomization wiring), 5ac74b2 (offline validator script), a3755b5 (sealed-secrets runbook + seal-secrets.sh), f59a0fb (secrets-template.yaml legacy header) + metadata commit for SUMMARY.md + CHANGELOG + ROADMAP + REQUIREMENTS + STATE.

Progress: [██████░░░░] 60% (6/10 plans complete; 2/6 milestone-v2.2 phases complete — phases 12-17)

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

**Recent Trend:**

- Last plan: 15-01 K8s NetworkPolicies + Sealed Secrets (INF-01 + INF-02) — 6 atomic commits drafting 6 NetworkPolicy manifests (default-deny + 4 tier allow-lists + pg-backup + observability placeholder) wired into base kustomization, offline Python validator, sealed-secrets operational runbook with emergency key rotation + re-seal + off-cluster key-backup procedure, batch `kubeseal` conversion script with yq multi-doc split, and LEGACY flag header on secrets-template.yaml. Cluster-admin operator install + first conversion remains a 4-step checklist in the SUMMARY — phase is implementation-complete but not operationally rolled out. Stripe-CIDR egress tradeoff (0.0.0.0/0:443 with RFC1918 in except[]) documented with defense-in-depth egress-proxy option flagged as v2.3+ work.
- Trend: milestone v2.2 execution continues green; 6/10 plans complete (phases 13 + 14 complete, 15 implementation-complete, 12 operationally complete). Multiple branches ready for PR: feature/phase-13-guest-tracking-tenant-validation, feature/phase-14-stock-race-summary-aggregation, feature/phase-15-k8s-networkpolicies-sealed-secrets. Phase 12 Task 12-02-07 staging-observation gate still pending.

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
