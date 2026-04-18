# Roadmap: J'Toye OaaS

Multi-tenant UK retail SaaS for food vendors — shops, products, orders, customers, marketing, kitchen fulfilment.

## Milestones

- ✅ **v2.0 Tier 3 Enhancements** — Phases 1-8 (shipped 2026-04-10, PR #27)
- ✅ **v2.1 Post-Audit Hardening + Storefront Completion** — Phases 9-11 (shipped 2026-04-16, archived 2026-04-18)
- 📋 **v2.2 TBD** — run `/gsd-new-milestone` to scope

## Phases

<details>
<summary>✅ v2.0 Tier 3 Enhancements (Phases 1-8) — SHIPPED 2026-04-10</summary>

- [x] Phase 1: API Versioning — Backend (1/1 plans) — completed 2026-04-07
- [x] Phase 2: API Versioning — Edge & Frontend (1/1 plans) — completed 2026-04-08
- [x] Phase 3: Vendor Marketing Backend (2/2 plans) — completed 2026-04-08
- [x] Phase 4: Vendor Dashboard UI (1/1 plans) — completed 2026-04-08
- [x] Phase 5: KDS Security & WebSocket Foundation (1/1 plans) — completed 2026-04-08
- [x] Phase 6: KDS Event Pipeline (1/1 plans) — completed 2026-04-08
- [x] Phase 7: Kitchen Display UI (1/1 plans) — completed 2026-04-09
- [x] Phase 8: Test Coverage Closure (2/2 plans) — completed 2026-04-09

v2.0 shipped before `/gsd-complete-milestone` was adopted — no archive files. Source of truth: PR #27 (commit `955e641`).

</details>

<details>
<summary>✅ v2.1 Post-Audit Hardening + Storefront Completion (Phases 9-11) — SHIPPED 2026-04-16</summary>

- [x] Phase 9: Repository Secrets + Alerting (3/3 plans) — completed 2026-04-15
- [x] Phase 10: Storefront Marketing Render + Missing Customer Routes (3/3 plans) — completed 2026-04-16
- [x] Phase 11: STOMP Broker Relay for Horizontal Scale (3/3 plans) — completed 2026-04-16

Archived: `milestones/v2.1-ROADMAP.md` | `milestones/v2.1-REQUIREMENTS.md` | `milestones/v2.1-MILESTONE-AUDIT.md`

</details>

### 📋 Planned: v2.2 (not yet scoped)

Run `/gsd-new-milestone` to scope the next milestone. Likely candidates from the v2.1 deferred list:

- 14 P2 items from HANDOFF.md deep-audit (stock race, K8s NetworkPolicies, K8s Sealed Secrets, CSP headers, Grafana JVM/DB/business dashboards, Alertmanager inhibition rules, etc.)
- SECR-08 — Keycloak realm-export hardcoded dev secrets
- `/public/orders?email=` enumeration risk (auth-wall or rate-limit)
- Alert runbook completion (9 stubs)
- Phase 11 VALIDATION.md closure

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. API Versioning — Backend | v2.0 | 1/1 | Complete | 2026-04-07 |
| 2. API Versioning — Edge & Frontend | v2.0 | 1/1 | Complete | 2026-04-08 |
| 3. Vendor Marketing Backend | v2.0 | 2/2 | Complete | 2026-04-08 |
| 4. Vendor Dashboard UI | v2.0 | 1/1 | Complete | 2026-04-08 |
| 5. KDS Security & WebSocket Foundation | v2.0 | 1/1 | Complete | 2026-04-08 |
| 6. KDS Event Pipeline | v2.0 | 1/1 | Complete | 2026-04-08 |
| 7. Kitchen Display UI | v2.0 | 1/1 | Complete | 2026-04-09 |
| 8. Test Coverage Closure | v2.0 | 2/2 | Complete | 2026-04-09 |
| 9. Repository Secrets + Alerting | v2.1 | 3/3 | Complete | 2026-04-15 |
| 10. Storefront Marketing + Missing Customer Routes | v2.1 | 3/3 | Complete | 2026-04-16 |
| 11. STOMP Broker Relay for Horizontal Scale | v2.1 | 3/3 | Complete | 2026-04-16 |
