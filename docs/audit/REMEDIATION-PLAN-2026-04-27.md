# Remediation Plan — J'Toye OaaS Council Findings

**Date**: 2026-04-27
**Synthesis author**: Claude (Opus 4.7), main session
**Source pairs**: 8 specialist + assistant remediation docs under `docs/audit/remediation/`
**Predecessor**: `docs/audit/COUNCIL-AUDIT-2026-04-27.md` (10-agent council audit)

---

## TL;DR — what to do this week

Of the council's findings, **5 confirmed defects can be fully closed in ~48 hours of focused engineering work**. Everything else is genuine but not blocking. The remediation pairs converged on a **Wave 0 / Wave 1 / Wave 2 / Wave 3+ structure** with explicit cross-pair sequencing. The single most important architectural finding from the pairs (not present in the original audit): **edge-go is currently receiving zero production traffic** — no ingress route, no frontend caller, broken sync URL nobody noticed. That makes the absorb plan a 32-hour migration with rollback in <10 minutes per wave, not the multi-week project the original audit implied. The single most important commercial finding from the pair: **the council's £5,880 ARR Gate B target was overstated** — at the £19/mo founder rate, 10 paying vendors is £2,280 ARR, and the assistant flagged that the right gate is honest, not aspirational.

---

## Total scope by pair

| Pair | Findings | Total hours | Key independent catch |
|---|---|---|---|
| 01 Backend | 10 | **~22h** | Stripe idempotency needs both `existsByEventId` + insert guard (TOCTOU race) |
| 02 Security | 10 | **~92h** (~10 days) | Spring's `NimbusJwtDecoder` has the same missing `aud` check as edge-go — fix both |
| 03 Database | 11 | **~28h** (5 migrations across 5 waves) | GUC pool-leak risk surface narrower than audit claimed — `is_local=true` already used everywhere |
| 04 DevOps/SRE | 13 | **~6 days** Wave 1+2 (~48h), 3-5 days Wave 3+4 | Tracked backup file independently verified as pg_dump stderr noise — `git rm` not history rewrite |
| 05 Frontend | 10 | **~8 days** (~64h) | Token rebrand MUST land before mega-page split (PR #49 mistake repeats otherwise) |
| 06 QA | 12 | **~15-18h** (3-4 PRs) | `addFilters=false` migration cost is +10s not +90s (context-cache reasoning) |
| 07 Edge-go | 11 | **~32h** (1 calendar week + 72h soak) | edge-go has zero prod traffic today — absorb is low-risk |
| 08 Commercial | 12 | 12 calendar weeks | "10 paying at £19" = £2,280 ARR, not the audit's £5,880 — restate Gate B honestly |

**Engineering total (technical pairs 01-07)**: ~266 hours of focused work, ~6.7 engineering weeks for one senior engineer.
**Of which Wave 0 + Wave 1 (the council's pre-prod blockers)**: ~50 hours / ~6 working days.

The commercial pair's plan runs in parallel calendar time with the technical pairs — door-knocking starts in week 4 once Wave 0+1 are closed.

---

## The 48-hour pre-prod path

These five items, fully scoped and reconciled by the pairs, must close before any production rollout that hosts >1 tenant or processes real card payments:

| # | Item | Owner pair | Hours | Files |
|---|---|---|---|---|
| 1 | **OrderSseService cross-tenant broadcast** — capture tenant id at `subscribe()`, filter `broadcast()`, regression test asserting tenant A subscriber receives no tenant B event | Backend 01 F1 | 1.5h | `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:17,29-40` |
| 2 | **Customer-orders IDOR** — make `verify` mandatory; reject 400 when missing | Security 02 F1 (Phase 1) | 2h | `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104` |
| 3 | **Stripe webhook idempotency** — V35 `processed_stripe_events` table + `existsByEventId`-then-insert guard (assistant caught the TOCTOU; both layers required) | Backend 01 F2 | 3h | `core-java/.../payment/PaymentService.java:113-132` + new migration |
| 4 | **`reviews_tenant_write` policy fix** — V35 drops broken `app.tenant_id` ref + `customer_email` OR-clause; requires EXISTS check against orders | Database 03 F1 | 1h | `db/migration/V35__rls_force_and_fixes.sql` (new) |
| 5 | **FORCE ROW LEVEL SECURITY pass** + `RlsContractTest` — `reviews`, `shop_promotions`, `shop_announcements`, all 6 `_aud` tables. Add CI test asserting `pg_class.relforcerowsecurity = true` for every tenant-scoped table | Database 03 F2+F11 | 2h | V35 + new test file |
| 6 | **Backend asymmetry**: `git rm backups/jtoye_jtoye_20251231_121414.sql.gz` (verified pg_dump stderr noise, no real data — no history rewrite needed) | DevOps 04 F13 | 1h | `backups/` |
| 7 | **Production observability flip** — `application-prod.yml:91` include `prometheus,metrics`, add nginx `^~/actuator { return 404; }` block, verify Grafana renders | DevOps 04 F1 | 1 day | `application-prod.yml`, `k8s/base/ingress.yaml` |
| 8 | **MDC tenantId** — add `MDC.put("tenantId", ...)` in `JwtTenantFilter` + log pattern update. (Defer @Async/SSE coverage until backend 01 F3 lands.) | DevOps 04 F3 | 4h | `JwtTenantFilter.java:40-43`, `application-prod.yml:75-76` |

**Pre-prod path total**: ~30 hours / 4 focused days.

These items close: 3 confirmed cross-tenant data leaks (SSE, IDOR, reviews RLS), the financial-integrity gap (Stripe replay), the existential-risk discovery gap (FORCE RLS missing on 9 tables → privileged-role bypass), and the visibility blackout (no metrics in prod).

What this Wave does NOT close: role-based authorization (Security F2, ~25h), NextAuth token leak (Security F3, ~12h), edge-go hardening (Security F4 / Edge 07), Stripe production secret wiring (DevOps F12, 4h). Those land in Wave 1.

---

## Cross-pair dependency map

The pairs identified the following mandatory sequencing across domains. Skip any of these and the implementation will silently break the consumer:

```
                    ┌─────────────────────────────────────────────────────┐
                    │  WAVE 0 (~30h) — pre-prod path above                │
                    │  Backend F1, F2 │ Security F1 │ Database F1+F2+F11  │
                    │  DevOps F1, F3, F13                                 │
                    └─────────────────────────────────────────────────────┘
                                              │
              ┌───────────────────────────────┼─────────────────────────────────┐
              ▼                               ▼                                 ▼
   ┌──────────────────────┐    ┌──────────────────────────┐    ┌────────────────────────┐
   │  WAVE 1A (Backend)    │    │  WAVE 1B (Security)       │    │  WAVE 1C (DevOps)        │
   │  F3 AsyncConfig +     │    │  F2 RBAC + roles          │    │  F4 alert hygiene        │
   │  TaskDecorator (2h) ──┼───→│  F3 BFF + token strip     │    │  F5 backup verify (2d)   │
   │  F4 dedup filters     │    │  (12h + CSP report-only)  │    │  F12 Stripe prod secret  │
   │  F5 StateMachine      │    │  F4 Edge JWT (gated by    │    │       (4h)               │
   │  F8 409 handler       │    │      Pair 07 absorb)      │    │  F2 edge-go /metrics     │
   └──────────────────────┘    │  F8 secret rotation       │    │      (gated by 07)       │
              │                 └──────────────────────────┘    └────────────────────────┘
              │                               │                                 │
              ├───────────────────────────────┼─────────────────────────────────┘
              ▼                               ▼
   ┌──────────────────────┐    ┌──────────────────────────┐
   │  WAVE 1D (Database)   │    │  WAVE 1E (QA)             │
   │  F3 TenantEnumeration │    │  F12 docs-freshness CI    │
   │  F4 RESET ALL hook    │    │  F1 Stripe HMAC test      │
   │  F5 V35.1 indexes     │    │  F2 Idempotency test      │
   │  F8 flusher refactor  │    │  F6 Concurrency rewrite   │
   │  F7 cache evict (with │    │  F11 Codecov wiring       │
   │      Backend F6)      │    │                           │
   └──────────────────────┘    └──────────────────────────┘
              │                               │
              └───────────────┬───────────────┘
                              ▼
                  ┌──────────────────────┐
                  │  WAVE 2 (~3-5 days)   │
                  │  Backend F6, F7, F9, │
                  │  F10                 │
                  │  Security F1 Phase 2  │
                  │  (magic-link tokens)  │
                  │  Database F6 N+1     │
                  │  DevOps F6 MinIO     │
                  │      mirror          │
                  │  Frontend Wave 1+2:   │
                  │  F1 token rebrand,    │
                  │  F9 status badge,     │
                  │  F10 primitives,      │
                  │  F2 responsive,       │
                  │  F3 next/image,       │
                  │  F6 a11y              │
                  │  QA F3, F8 step 1     │
                  │  EDGE-GO ABSORB       │
                  │  (Pair 07, 32h, ─────┼──→  ships SEPARATE release
                  │   gated on founder    │     after Wave 0 closes
                  │   decision)           │
                  └──────────────────────┘
                              │
                              ▼
                  ┌──────────────────────┐
                  │  WAVE 3+ (~5-8 days)  │
                  │  Frontend Wave 3+4    │
                  │  (TanStack Query,     │
                  │   mega-page split)    │
                  │  QA F4 enable refund │
                  │  test, F8 step 2,     │
                  │  F9, F10              │
                  │  Database F10         │
                  │  shop_featured_       │
                  │  products             │
                  │  DevOps F8 Stage 1    │
                  │  drift-check          │
                  │  Backend F7 API       │
                  │  versioning policy    │
                  │  Commercial WAVE A:   │
                  │  pricing page,        │
                  │  ROI calculator,      │
                  │  ICP hit-list         │
                  └──────────────────────┘
                              │
                              ▼
                  ┌──────────────────────┐
                  │  COMMERCIAL          │
                  │  WAVE B-D (12 wks)    │
                  │  door-knock,         │
                  │  Gate A wk8,         │
                  │  Gate B wk12          │
                  └──────────────────────┘
```

### Critical sequencing rules (the things that silently break if violated)

1. **Database F11 (RlsContractTest) lands FIRST** — gates every subsequent migration. Without it, the next forgotten RLS table is just another retro-patch.
2. **Database F1 must precede Database F2** — adding FORCE RLS to `reviews` without first fixing `reviews_tenant_write` is a default-deny outage on customer reviews (no INSERT policy = denied under FORCE).
3. **Database F2 prerequisite** — verify `_aud` tables actually have INSERT/UPDATE policies (V11) before adding FORCE. Pair 02 specifically called this out.
4. **Database F3 part A (Java) must ship before V35** — the flusher's `SELECT id FROM tenants` returns zero rows post-V35 if `TenantEnumerationService` isn't deployed first. Two-PR sequence.
5. **Backend F3 (TaskDecorator) must land before DevOps F3 covers @Async paths** — MDC tenantId in @Async requires the decorator to propagate it.
6. **Backend F1 fix must be deployed before Frontend F5 SSE-invalidation path is enabled** — TanStack Query SSE listener assumes server-side fix shipped.
7. **Frontend F1 (token rebrand) MUST precede Frontend F4 (mega-page split)** — bundling restyles into refactors is exactly the PR #49 mistake the user already rejected.
8. **Edge-go absorb (Pair 07) ships SEPARATE release from Wave 0** — to avoid compound rollbacks. Pair 07 explicitly assumes Wave 0 ships first.
9. **Pair 07 absorb decision unblocks DevOps F2 (edge-go /metrics) and Security F4/F7 scope** — if absorb is approved, those efforts shrink to "delete the metrics package" rather than "add metrics to a soon-to-die service".
10. **QA F4 (refund test) ships `@Disabled`** until Backend pair lands the `charge.refunded` handler — avoids perpetually-red CI.
11. **QA F10 (vendor admin E2E specs) must use `getByRole`/`getByTestId` only** if written before Frontend pair 05 lands the responsive shell — semantic locators survive markup churn, CSS class selectors don't.
12. **Stripe key rotation (Security F8)** uses Stripe's two-active-keys window, not single-cut. Keycloak client secret rotation requires core-java FIRST, then frontend (else 401 outage during the window).
13. **CSP `frame-ancestors`** must allow `https://*.stripe.com` if Stripe Elements is in use. Pair 02 flagged this as a Stripe-flow killer if missed.
14. **Commercial Wave B (pricing page launch)** is gated on technical Wave 0+1 closing. The commercial assistant explicitly moved the pricing page from week 1-2 to week 3 to enforce this.
15. **CONCURRENTLY for index builds on `orders`** — non-concurrent CREATE INDEX takes `AccessExclusiveLock` for the build duration. Database F5 splits V35 (transactional) from V35.1 (`-- flyway:executeInTransaction=false`) for this reason.

---

## Founder decisions blocking the plan

The pairs collectively flagged **9 decisions only the founder can make**. These should be answered before Wave 1 starts so the plan can sequence cleanly:

| # | Decision | Blocks | Pair recommendation |
|---|---|---|---|
| 1 | **Approve edge-go absorb?** | Pair 07 entire plan; Security F4/F7 scope; DevOps F2 scope | **Yes** — verified zero prod traffic, broken sync URL, no live WhatsApp tenant; absorb is a 32h migration with rollback in <10 min/wave |
| 2 | **Where does prod K8s actually live?** | DevOps F7 (managed PG vs self-host PITR) | EKS/GKE/AKS → migrate to managed PG; non-cloud → `pgbackrest` |
| 3 | **Real production traffic today (vendor count, payments live)?** | DevOps F7, F8, F12 urgency; Backend F7 API versioning; Commercial Wave A urgency | If 0 customers, defer F7/F8 Stage 2; if 1+ vendors, Wave 0+1 are urgent |
| 4 | **Guest order-tracking via `/public/orders` or auth-required?** | Security F1 Phase 2 (~5h saved if auth-required) | Product call |
| 5 | **KITCHEN role IP-pinning?** | Security F2 RBAC scope | Operator preference; tighter security but breaks cellular failover |
| 6 | **CSP `frame-ancestors` policy** — Stripe-only or no embeds? | Security F9 CSP enforce | Verify Stripe Elements usage in `frontend/` first |
| 7 | **External pentest budget (£3-5k post-Wave-1)?** | Security F10 OWASP closure verification | Without external eyes, "PASS" claims are self-graded |
| 8 | **Founder personal runway + day-job status** | Commercial F10 (raise vs bootstrap) | Pair cannot assess raise size without this |
| 9 | **Founder lived experience in chosen community** | Commercial F1 ICP, F3 narrative | If absent, must name a community substitute publicly by Wave B week 1 — no vapour partners |

Open questions 1, 3, and 8 are the highest-leverage. Answer those three and the remaining sequencing locks itself.

---

## Per-pair pointer table

Drill into each pair's full doc for the specialist proposals + assistant deliberations + reconciled positions. Every code block, migration SQL, YAML diff, and test file is ready to execute as written.

| Pair | Doc | Findings | Hours | Headline |
|---|---|---|---|---|
| 01 Backend | [`remediation/01-backend-remediation.md`](remediation/01-backend-remediation.md) | 10 | ~22h | SSE leak + Stripe idempotency + bounded async + 409 handler |
| 02 Security | [`remediation/02-security-remediation.md`](remediation/02-security-remediation.md) | 10 | ~92h | IDOR + RBAC + token strip + edge JWT + secret rotation + OWASP closure |
| 03 Database | [`remediation/03-database-remediation.md`](remediation/03-database-remediation.md) | 11 | ~28h | Reviews policy + FORCE RLS + composite indexes + GUC discipline + flusher refactor |
| 04 DevOps | [`remediation/04-devops-remediation.md`](remediation/04-devops-remediation.md) | 13 | ~48h | Prod observability flip + MDC tenantId + backup-verify + MinIO mirror + Stripe prod secret |
| 05 Frontend | [`remediation/05-frontend-remediation.md`](remediation/05-frontend-remediation.md) | 10 | ~64h | Token rebrand + responsive shell + next/image + mega-page split + TanStack Query |
| 06 QA | [`remediation/06-qa-remediation.md`](remediation/06-qa-remediation.md) | 12 | ~15-18h | 5 missing tests with full code + JaCoCo phased + Playwright in CI + addFilters=false kill |
| 07 Edge-go | [`remediation/07-edge-absorb-remediation.md`](remediation/07-edge-absorb-remediation.md) | 11 | ~32h | Concrete absorb in 4 waves with <10min rollback per wave |
| 08 Commercial | [`remediation/08-commercial-remediation.md`](remediation/08-commercial-remediation.md) | 12 | 12 wks | 90-day GTM with door-knock script + ROI calc + Gate A/B/D triggers |

---

## What changed from the council audit

The remediation pairs surfaced **9 substantive corrections** to the original audit. These are facts the audit got wrong or sized incorrectly, now corrected by direct code/system inspection:

1. **Edge-go has zero production traffic.** The original audit treated edge-go as an active service that needed hardening or replacement. Pair 07 verified: ingress (`k8s/base/ingress.yaml:54-63`) routes `api.jtoye.co.uk/` directly to `service/core-java:9090`, not to edge-go. No frontend caller references edge-go. Even the sync passthrough URL is broken end-to-end and nobody noticed. Absorb is therefore low-risk, fast, and reclaims ~£40/mo of compute.

2. **Backups directory in repo is benign.** Original audit suggested potential history rewrite. DevOps pair 04 verified by `zcat`: the one tracked file is pg_dump stderr noise, not real tenant data. `git rm` in a normal commit is sufficient. The dev backup script bug at `infra/backups/backup.sh:131` — `--verbose 2>&1 | gzip` captures stderr into the .gz instead of the dump — is the actual root cause and gets a separate fix.

3. **GUC pool-leak surface is narrower than audit claimed.** Database pair 03 verified all three `app.customer_email`/`app.tracking_*` set sites in `PublicStorefrontService` use `is_local=true` inside `@Transactional(readOnly=true)`. The transactional path is leak-safe today. Real risk is non-tx callers and future `is_local=false` drift; defence is `connection-init-sql=RESET ALL` (cheap), not the full aspect-level RESET pair the audit implied.

4. **Stripe webhook idempotency needs both `existsByEventId` AND insert guard, not one or the other.** Backend pair 01's assistant caught a TOCTOU race in the specialist's first proposal (single-check would let two concurrent webhook deliveries both pass and both insert). Reconciled position keeps both layers — cheap retry path + race-safe insert.

5. **Spring's `NimbusJwtDecoder` has the same missing `aud` check as edge-go.** Audit flagged the missing audience validation only on edge-go. Security pair 02 grep'd Spring's JwtDecoder config — same gap. Fix applies to both layers; absorbing edge-go does not close the audience gap on Spring side.

6. **`addFilters=false` migration cost is +10s, not +90s.** QA pair 06's assistant corrected the specialist's worst-case CI runtime estimate using context-cache reasoning. Cheaper to convert to full `@SpringBootTest` than the specialist initially proposed.

7. **framer-motion is ~34 KB gzipped, not 60 KB.** Frontend pair 05 verified via bundlephobia. The original frontend audit's bundle-size claim was inflated.

8. **Test claim CLAUDE.md numbers are stale.** Audit said 516+ logical invocations. Actual: 432 Java + 84 Jest + 54 Go + 21 Playwright = **595+**. QA pair 06 added a CI step that fails on future drift.

9. **The "10 paying at £49/mo = £5,880 ARR" Gate B is overstated.** Commercial pair 08 noted that at the £19/mo founder rate, 10 paying = £2,280 ARR. Restated as "10 paying at founder rate, retained ≥ 90 days at month 6". Three brutal pushbacks from the assistant on the original critic's plan landed in the reconciled position: door-knock conversion baseline dropped from 30% to 10%, design-partner offer changed from "6 months free" to "£19/mo locked 24 months" (commitment signal), pivot triggers gained Option D (stop and post-mortem with mandatory external advisor).

---

## Recommended ship order (the next 12 weeks)

A single ordering across all 8 pairs that respects every dependency above. Each step has explicit success criteria.

### Week 1 — Wave 0 (~30 hours)
- **Day 1**: Database F11 (`RlsContractTest` + CONTRIBUTING.md, 1h). Database F4 (`connection-init-sql: "RESET ALL"`, 30m). Database F1 + F2 (V35 reviews fix + FORCE RLS pass, 3h). DevOps F13 (`git rm` backup file, 1h).
  - **Success**: `RlsContractTest` green. Every tenant-scoped table has FORCE RLS verified by the test. Backup file removed from working tree.
- **Day 2**: Backend F1 (SSE leak fix + regression test, 1.5h). Security F1 Phase 1 (mandatory `verify` param, 2h). Backend F2 + V35 (`processed_stripe_events` table + idempotency guard, 3h).
  - **Success**: Two browser sessions from different tenants on `/orders/stream` — tenant B does not see tenant A events. Double-deliver `payment_intent.succeeded` webhook → exactly one `financial_transactions` row.
- **Day 3-4**: DevOps F1 (prod actuator exposure flip + nginx deny + 2 Grafana dashboards, 1 day). DevOps F3 (MDC tenantId, 4h).
  - **Success**: `/actuator/prometheus` returns 404 from public ingress, scrape works internally, Grafana dashboards render with non-zero data under synthetic load. Logs grep-able by tenant ID.

### Week 2 — Wave 1 starts (~30 hours)
- Backend F3 (AsyncConfig + TaskDecorator, 2h). Backend F4 (dedup TenantFilter, 30m). Backend F5 (StateMachine table, 2h). Backend F6 (cache evict regressions, with Database F7, 2h).
- Database F3 (TenantEnumerationService + V35 tenants policy, 2h, sequenced as PR-N then PR-N+1). Database F5 V35.1 (CONCURRENTLY indexes + duplicate drops, 2h). Database F8 (flusher per-tenant txn + `FOR UPDATE SKIP LOCKED`, 3h).
- Security F2 (RBAC converter + `@PreAuthorize` annotations, 1 day). Security F8 Day-0 git audit + first secret rotation drill (4h).
- DevOps F12 (Stripe production secret wiring, 4h). DevOps F4 (`jwt_tenant_claim_missing_total` counter + alert rename + Alertmanager mute, 4h).
- QA F12 (CLAUDE.md update + docs-freshness CI step, 30m). QA F1 (PaymentWebhookSignatureIntegrationTest, 1h). QA F2 (GuestOrderIdempotencyIntegrationTest, 2h). QA F6 (concurrency test rewrite, 1h). QA F11 (Codecov wiring, 1h).

### Week 3 — Edge-go absorb + commercial Wave A start
- Pair 07 absorb in 4 waves over ~5 days: WhatsApp controller in Spring, V36 + idempotency table, Meta admin URL swing with 72h soak, deletion. Rollback at every wave is `kubectl rollout undo` + revert Meta admin URL.
- Commercial Wave B starts: pricing page (Finding 4), ROI calculator (Finding 5), one-pager (Finding 6) live. ICP hit-list built (Finding 1).

### Week 4-7 — Wave 2 (frontend + remaining hardening) + commercial door-knock
- Frontend Wave 1: F1 token rebrand + F9 status badge + F10 primitives doc (1 day). Frontend Wave 2: F2 responsive shell + F3 next/image + F6 a11y (2 days).
- Security F3 BFF + token strip + CSP report-only (12h). Security F1 Phase 2 magic-link (~5h, optional based on founder decision #4).
- Commercial: 10 door-knocks in week 4, 3 design partners onboarded weeks 5-7. Reduced cadence to 5 door-knocks/week during onboarding (assistant's correction).

### Week 8 — Gate A
- Commercial Finding 11: 3 paying design partners + at least one second-degree lead. Pass = continue to week 12. Fail = pivot decision within 7 days with external advisor.
- Frontend Wave 3 starts: F5 TanStack Query (depends on Backend F1 SSE fix shipped — confirmed in Wave 0).

### Week 9-12 — Wave 3 + commercial scale
- Frontend Wave 3 + 4: TanStack Query rollout + mega-page split (5 days).
- QA F3 JWT security test (depends on Security F2 audience-validator landing). QA F4 enable refund test (depends on Backend pair landing `charge.refunded` handler). QA F8 step 2 (flip JaCoCo gates on). QA F9 + F10 Playwright + vendor admin E2E.
- Database F10 V35.2 (shop_featured_products join table + backfill). Database F6 N+1 EntityGraph.
- DevOps F6 MinIO mirror (1 day). DevOps F10 PSA + readOnlyRootFilesystem (1 day code + 1 week soak).
- Commercial Wave D: 10 paying vendors target by week 12 (Gate B).

### Week 13+ — Day-2 (post-customer-#10)
- Database V36 column drop after 28-day soak. Backend F7 API versioning policy. DevOps F7 managed PG migration. DevOps F8 Stage 2 Argo CD. DevOps F9 Stage 2 Kyverno admission policy. Security F10 external pentest. Commercial Wave E (allergen SKU build, geographic expansion, investor conversations).

---

## What I would NOT do (synthesis author's opinion, carried from the council audit)

- **Do not start Wave 2 frontend or DevOps day-2 work before Wave 0 closes.** The pairs explicitly sequenced this; deviating creates compound rollback risk if a Wave 0 fix has to be reverted.
- **Do not run the Kong fallback (Edge Pair 07 Option B) in parallel with absorb.** Pick one; absorb is the recommended path because edge-go has zero traffic.
- **Do not pursue the brand-renaming exercise now.** Commercial pair 08 deferred to month 6+ — the brand follows the customer, the customer is not yet there. Drop "OaaS" from public copy via 1-hour grep-and-replace; keep in engineering codebase.
- **Do not attempt the £100k ARR by year-end forecast.** Commercial pair 08's assistant explicitly recalibrated to £25k by year-end (50 paying vendors), £100k 12-18 months out. Honest forecasts win cheques in due diligence.
- **Do not flip JaCoCo gates to 80% on day 1.** QA pair 06 phased this: visibility-only first, gates flip after F1-F4 land. 80% as a day-1 hard gate creates perverse incentives (vanity tests against trivial paths).
- **Do not enforce CSP without a 2-week report-only soak.** Security pair 02 explicitly required this — going straight to enforce will break inline scripts that NextJS or third-party libs inject.
- **Do not migrate to Argo CD before customer #10.** DevOps pair 04 Stage 2 deferred to post-revenue. At 0 customers, Argo migration is yak-shaving.

---

## The honest synthesis

The original council audit said: "the technical risk is fixable in days; the commercial risk is structural." The remediation pairs validate that read precisely. **~30 hours closes the existential technical risks**. **~50 more hours over Wave 1 closes the high-risk hardening**. Everything else (Frontend Wave 3+4, DevOps day-2, Database V36 + V40 partitioning, Security external pentest) should follow the first 10 paying customers, not precede them.

The commercial pair's plan is the bet that matters. If the founder cannot get to 3 paying design partners in 8 weeks of focused door-knocking with the assistant-revised offer (£19/mo locked 24 months, no per-order commission, lead with WhatsApp + Natasha's Law), the platform's commercial future is uncertain regardless of how clean the codebase becomes. Commercial pair 08 was explicit: pivot Option D ("stop and post-mortem with mandatory external advisor") is a valid outcome. Engineering excellence does not earn revenue by itself.

The pair model worked: 16 voices (8 specialist + 8 assistant) caught **at least 30 substantive flaws** in the specialists' first-pass proposals — TOCTOU races, default-deny outages, frame-ancestors that would break Stripe, design tokens that would flash green on every Radix dropdown, founder narratives that lead with product instead of human, eng-hour estimates off by 9× in the wrong direction. Every one of those would have shipped silently if the pair had been one voice. The cost of the second voice is structural, not stylistic.

Read the per-pair docs for the actual code, SQL, and tests. They are ready to execute as written.

---

**Total deliverables**: 1 council audit + 10 source agent reports + 8 remediation pair docs + 1 consolidated remediation plan = **20 documents, ~120,000 words of evidence-backed analysis and ready-to-ship code**.

Next step: founder answers open questions 1, 3, 8 from the founder-decisions table. Wave 0 starts the morning after.
