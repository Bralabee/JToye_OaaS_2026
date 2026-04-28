# Handoff: Phase 16.1 Shipped — Phase 17 Unblocked

**Generated:** 2026-04-28
**Branch:** `main` will be the resume point once PR #56 merges; currently work sits on `feature/phase-16.1-pre-prod-hardening` (PR #56 open and READY-TO-MERGE).
**Main tip (pre-merge):** `bd13864 docs(audit): add 20-doc council audit + 8-pair remediation plan (#55)`
**Phase 16.1 tip:** `2112a03 docs(16.1): verifier confirms phase goal achieved (READY-TO-MERGE)`

---

## What just shipped (PR #56)

**Phase 16.1 — Pre-prod Hardening (Wave 0 council audit fixes)** — closes the 5 confirmed pre-prod blockers from the 2026-04-27 council audit:

| AUDIT-W0 | Fix | Files |
|---|---|---|
| 01 | `OrderSseService` cross-tenant leak — per-tenant `ConcurrentHashMap<UUID, ...>` routing + fail-closed subscribe | `OrderSseService.java` (rewrite) + `OrderSseServiceTenantIsolationTest` |
| 02 | `/public/orders` IDOR — `verify` param now mandatory; missing/blank → HTTP 400 | `PublicStorefrontController.java`, `GlobalExceptionHandler.java` (400 mapping) + `PublicStorefrontControllerIdorTest` |
| 03 | Stripe webhook idempotency — TOCTOU-safe `INSERT INTO processed_stripe_events ... ON CONFLICT DO NOTHING` BEFORE the dispatch switch | `PaymentService.java`, V35 migration + `StripeWebhookIdempotencyIntegrationTest` |
| 04 | `reviews_tenant_write` policy rewrite — V35 drops legacy policy, recreates with canonical `app.current_tenant_id` GUC + `EXISTS (orders WHERE customer_email=...)` ownership proof | V35 migration + `ReviewsRlsPolicyIntegrationTest` |
| 05 | `FORCE ROW LEVEL SECURITY` on `reviews`, `shop_promotions`, `shop_announcements`, all 6 `_aud` tables | V35 migration |

Plus **`RlsContractTest`** — schema-walk drift guard: walks `pg_class` and asserts BOTH `relrowsecurity` AND `relforcerowsecurity` are true for every public-schema relation (with documented EXEMPT_TABLES).

**Production scope:** 4 Java files + 1 Flyway migration (V35). 22 atomic commits with TDD ordering. 19 new `@Test` methods across 5 new test classes.

**CI on PR #56**: Run Tests PASS (2m26s, full suite), Security Scan + gitleaks + GitGuardian PASS. Trivy/Build/Deploy correctly skip on non-main branches.

---

## Next session — start here

### Once PR #56 merges to main

1. **Verify state** (<30s):
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
   git checkout main && git pull
   git log --oneline -3            # tip should be the squash-merged Phase 16.1 commit
   ```

2. **Phase 17 is now unblocked** — Vendor Order Detail + Stripe Refund Flow (VOPS-01..03). Research landed in PR #51. Implementation pending.
   ```bash
   git checkout -b feature/phase-17-implementation
   /gsd-plan-phase 17
   ```

   **Critical Phase 17 inputs:**
   - The `processed_stripe_events` table + idempotency guard from Phase 16.1 is the foundation — the new `charge.refunded` and `refund.updated` webhook handlers MUST reuse the same TOCTOU-safe `INSERT ... ON CONFLICT` pattern in `PaymentService.handleWebhookEvent`. Currently those events fall into the `default -> log.debug` branch (line 152 of PaymentService.java).
   - The QA audit (`docs/audit/sources/06-qa-engineer.md`) explicitly flagged that Phase 17 must include `RefundWebhookHandlingIntegrationTest`. Add this to the plan upfront, not as follow-up.
   - The `reviews_tenant_write` rewrite from Phase 16.1 changed the canonical RLS GUC to `app.current_tenant_id` — if Phase 17 adds new RLS policies for `refunds`, mirror this naming exactly, not the legacy `app.tenant_id`.
   - `RlsContractTest` will catch any new tables in Phase 17 that lack `FORCE RLS` — add `ALTER TABLE refunds FORCE ROW LEVEL SECURITY` upfront.

### Other Wave 0 items still NOT closed (separate phases)

The council audit (`docs/audit/REMEDIATION-PLAN-2026-04-27.md`) listed 8 specialist remediation pairs. Phase 16.1 closed pairs 1-3 (backend F1/F2, security F1, database F1/F2/F11). The remaining 5 are NOT yet scoped:

- **DevOps F1/F3/F13** — prod actuator exposure flip, MDC tenantId, git rm backup file. Could ship as `/gsd-quick` or a small phase.
- **Frontend F1-F5** — `--primary` design token rebrand, dashboard responsive rebuild. ⚠ Memory rule: any UI refresh requires `/gsd-sketch` first per `feedback_design_direction.md`.
- **Edge-go absorb** — Wave 2+, blocks on founder decisions called out in REMEDIATION-PLAN.
- **Commercial track** — door-knock 30 vendors per the commercial critic agent. Not a code track.

Founder decisions still pending (per remediation plan §"Founder decisions blocking the plan"): (1) approve edge-go absorb? (2) where does prod K8s live? (3) founder personal runway / day-job status. Answer these three and the rest of the sequencing locks itself.

---

## Environment state

- **Repo**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026`
- **Branch**: `feature/phase-16.1-pre-prod-hardening` (PR #56 open, READY-TO-MERGE pending review)
- **Open PRs**: #56 (Phase 16.1)
- **Workdir**: `.idea/gradle.xml` modified (pre-existing IDE noise, not touched by any plan; safe to leave or `git checkout`)
- **Dev port**: 3100 (MCP holds 3000) — `npm run dev` script bakes in `NEXTAUTH_URL=http://localhost:3100`
- **docker-compose**: unchanged this session
- **STATE.md**: phase 16.1 marked COMPLETE, Current Position advanced to Phase 17
- **CHANGELOG.md**: Phase 16.1 entry added under `[Unreleased]`
- **REQUIREMENTS.md**: AUDIT-W0-01..05 registered with traceability rows, status COMPLETE

---

## Key decisions (this session)

| Decision | Rationale |
|---|---|
| Insert as Phase 16.1 (decimal), not Phase 17.5 or new milestone | Pre-prod fixes must precede Phase 17 (Stripe refund) since Phase 17 depends on the idempotency guard + V35 patterns. Decimal numbering preserves Phase 17 slot. |
| Single V35 migration for items 3, 4, 5 | Atomic deployment — partial state where idempotency exists but FORCE RLS doesn't would be unsafe. |
| `JdbcTemplate` (not JPA) for Stripe dedup | TOCTOU-safe atomic `INSERT ... ON CONFLICT DO NOTHING RETURNING` is one statement. JPA's `existsByEventId` + `saveAndFlush` is two statements with a race. Locked in CONTEXT.md per remediation/01 F2 guidance. |
| Schema-walk `RlsContractTest` (not hardcoded list) | Future-proofs against new tables landing without FORCE RLS. EXEMPT_TABLES is a documented allowlist. |
| `SET LOCAL ROLE rls_test_role` in Testcontainers RLS tests | Postgres container creates the test user as SUPERUSER which bypasses RLS regardless of FORCE/NOBYPASSRLS. Without role-drop, every assertion would silently pass. |
| Sequential plan execution (not parallel worktrees) | Java/Gradle daemon contention + Testcontainers Postgres-per-container was a real risk. Sequential kept the run clean and CI logs readable. |

---

## References

- Council audit: `docs/audit/COUNCIL-AUDIT-2026-04-27.md` (10-agent stack + market review)
- Remediation plan: `docs/audit/REMEDIATION-PLAN-2026-04-27.md` (12-week, 8 pairs)
- Phase 16.1 artifacts: `.planning/phases/16.1-pre-prod-hardening/` — CONTEXT.md, 6 PLAN.md files, 6 SUMMARY.md files, VERIFICATION.md
- Phase 17 research (already done in PR #51): `.planning/phases/17-vendor-order-detail-stripe-refund-flow/17-RESEARCH.md`
- Memory: `/home/sanmi/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/project_phase_16_1.md`

---

## Resume instructions for a fresh Claude session

```
Resuming J'Toye OaaS work. Context:
- PR #56 (Phase 16.1 — Pre-prod Hardening, Wave 0 council audit) is READY-TO-MERGE — verify on GitHub and merge if not already done.
- Once merged, Phase 17 (Vendor Order Detail + Stripe Refund Flow) is unblocked.
- Phase 17 MUST reuse the processed_stripe_events idempotency pattern from Phase 16.1 for charge.refunded / refund.updated webhooks. Add RefundWebhookHandlingIntegrationTest upfront.
- Phase 17 MUST use canonical app.current_tenant_id GUC (NOT legacy app.tenant_id) for any new RLS policies.
- Memory rule: feedback_design_direction.md — no autonomous UI redesigns; sketch-first via /gsd-sketch.
- Read /home/sanmi/IdeaProjects/JToye_OaaS_2026/HANDOFF.md for full state.

Plan: git checkout -b feature/phase-17-implementation && /gsd-plan-phase 17
```
