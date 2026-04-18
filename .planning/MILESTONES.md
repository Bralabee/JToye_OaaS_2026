# Shipped Milestones

Chronological record of shipped versions. Full archives in `milestones/v*`.

---

## v2.1 — Post-Audit Hardening + Storefront Completion (2026-04-18)

**Status:** ✅ SHIPPED
**Shipped:** 2026-04-16 (phases 9, 10, 11 merged via PRs #37, #38, #39, plus P1 deep-audit pass in PR #40)
**Archived:** 2026-04-18
**Phases:** 9, 10, 11 (3 phases)
**Plans:** 9
**Requirements:** 18/18 v1 complete (SECR ×7, STFR ×6, STMP ×5)
**Tag:** v2.1

### What Shipped

Three Work Orders from the 2026-04-14 state-of-codebase audit:

- **Work Order A (SECR)** — Alertmanager deployed with email receiver (Mailhog in dev, SMTP in prod), 10 existing Prometheus alert rules now route to operators instead of firing into the void. Gitleaks CI enforces secret hygiene going forward. Re-scoped mid-flight (`.env` was never committed; audit-doc claim verified false).
- **Work Order B (STFR)** — Storefront finally renders vendor promotions and announcements; `/shop/[slug]/cart` and `/shop/orders` routes shipped (previously 404'd); full browse→cart→Stripe checkout flow covered by Playwright e2e.
- **Work Order C (STMP)** — `core-java` can now run with 2+ replicas without losing kitchen WebSocket broadcasts. `StompBrokerRelay` behind `stomp.broker.mode` flag, verified two-replica broadcast, StompBrokerLag alert + Grafana dashboard.

After the three phases merged, a P1 deep-audit pass (PR #40) added 4 alerts, redis-exporter, error boundaries, STOMP tenant validation hardening, JWT-in-CONNECT-headers, and Go edge gateway test coverage (21 → 57 passing).

### Stats

- Phases: 3 | Plans: 9 | Requirements: 18/18
- Tests: 516+ logical invocations across 66 files (390 Java @Test methods + 76 Jest it/test blocks + 50 top-level Go Test funcs / 54 with t.Run subtests) — verified at milestone close 2026-04-18. PR #40 commit message stated "474+" using a different counting method (class-level Java + total Go runs including table iterations); the 516/390/76/50 numbers are the canonical measure going forward.
- Git: `9008b3a..9e491d5` on `main` (PRs #37, #38, #39, #40)
- Audit: `milestones/v2.1-MILESTONE-AUDIT.md` — initial `gaps_found` (2 missing VERIFICATION.md), remediated to `passed` on 2026-04-18.

### Known Deferred Items at Close

- **SECR-08** (Keycloak realm-export dev secrets) — allowlisted, deferred to v2.2+.
- **`/public/orders?email=` enumeration risk** — deferred (Pitfall 5 of plan 10-03).
- **9 alert runbook stubs** in `docs/runbooks/alerts.md` — only ServiceDown filled.
- **Phase 11 VALIDATION.md draft** — `nyquist_compliant: false`.
- **14 P2 deep-audit items** — see HANDOFF.md (stock race, K8s policies, sealed-secrets, CSP headers, Grafana dashboards, etc.).
- **5 quick-task metadata entries** — work shipped in PR #40, SUMMARY.md files lack `status: complete` frontmatter.

### Archives

- `milestones/v2.1-ROADMAP.md` — phase details + milestone summary
- `milestones/v2.1-REQUIREMENTS.md` — all 18 requirements with commit-level traceability
- `milestones/v2.1-MILESTONE-AUDIT.md` — audit report + remediation log

---

## v2.0 — Tier 3 Enhancements (2026-04-10)

**Status:** ✅ SHIPPED (not archived via /gsd-complete-milestone)
**Shipped:** 2026-04-10 (PR #27, commit `955e641`)
**Phases:** 1–8
**Requirements:** APIV ×5, VMKT ×5, KDS ×8, TEST ×4

Tier 3 milestone: API /v1/ versioning (phases 1-2), vendor marketing (phases 3-4), kitchen display system with WebSocket (phases 5-7), test coverage closure (phase 8). Test suite grew from ~267 → 310+ passing. Shipped before the formal `/gsd-complete-milestone` workflow was used — no archive files exist; roadmap source lives in git history at PR #27.
