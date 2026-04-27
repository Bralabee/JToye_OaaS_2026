# Council Audit — J'Toye OaaS

**Date**: 2026-04-27
**Council**: 10 specialist agents (7 technical, 3 commercial)
**Scope**: full-stack code audit + UK food-SaaS competitive landscape + strategic critique
**Source docs**: `docs/audit/sources/01-…10-…md`
**Synthesis author**: Claude (Opus 4.7), main session

---

## TL;DR — the one-paragraph read

The codebase is **engineering-impressive but commercially undefined**. Architecture, multi-tenancy, test discipline, and operational scaffolding sit at staff-engineer level — the council scored the platform between 5.5 and 7.5 across seven technical dimensions, with no audit dropping below 5/10. But three confirmed cross-tenant data leaks plus a payment-integrity gap put the platform in **"do not ship to production"** territory until specific bugs are fixed (~2 days of focused work). On the commercial side the platform has feature-parity with crowded, well-capitalised UK incumbents (Toast, Square, Flipdish, Vita Mojo, Slerp) but **no customer-visible differentiator** as currently scoped. The honest commercial verdict from the critic agent: "a beautifully built Flipdish clone that cannot articulate who its customer is." A clean wedge exists — UK ethnic-food independents (halal Caribbean / West African) using WhatsApp ordering + Natasha's Law allergen labels, priced at £39–179/mo with no per-order commission — but it requires a **commercial pivot**, not a code pivot.

---

## Council scorecard

| # | Domain | Score | Headline read | Source |
|---|---|---|---|---|
| 1 | Backend / distributed systems | **6.5/10** | Solid bones; two showstopper bugs (SSE leak, Stripe idempotency) | [01-backend-engineer.md](sources/01-backend-engineer.md) |
| 2 | Security | **High risk** | RLS scaffolding largely correct; 3 confirmed criticals make ship unsafe | [02-security-engineer.md](sources/02-security-engineer.md) |
| 3 | Database / RLS | **6/10** schema, **5.5/10** ops | Right pattern, GUC-name bug + 4 tables missing FORCE RLS | [03-database-engineer.md](sources/03-database-engineer.md) |
| 4 | DevOps / SRE | **6/10** | Right-shape infra; production observability is dark | [04-devops-sre.md](sources/04-devops-sre.md) |
| 5 | Frontend / UX | code **7/10**, design **5.5/10**, mobile-first **6/10** | Two products: polished storefront + neglected vendor dashboard | [05-frontend-ux.md](sources/05-frontend-ux.md) |
| 6 | QA / test | quality **7/10**, critical-path **6/10**, trust **6/10** | Counts undersold (595+ vs claimed 516+); payment subsystem is the hole | [06-qa-engineer.md](sources/06-qa-engineer.md) |
| 7 | Edge-go gateway | **6/10** impl, "**delete and absorb**" verdict | 50 LOC of orchestration wrapped in 1000 LOC of half-built gateway | [07-edge-go.md](sources/07-edge-go.md) |
| 8 | UK market position | n/a (analyst) | At parity, no buyer-visible differentiator; 3 wedges identified | [08-market-analyst.md](sources/08-market-analyst.md) |
| 9 | Vertical SaaS strategy | n/a (analyst) | Hospitality SaaS = 1.8x revenue unless you embed fintech | [09-vertical-saas-strategist.md](sources/09-vertical-saas-strategist.md) |
| 10 | Commercial critique | **Pass at £3M cap today** | Engineering-impressive, ICP undefined; pivot to niche-first | [10-commercial-critic.md](sources/10-commercial-critic.md) |

---

## The most important finding

> **Three confirmed cross-tenant data leaks.** Two were independently surfaced by the backend and security agents; one was independently surfaced by the database and security agents. They are the lead items because they are the cheapest fix with the highest blast radius and they functionally prevent multi-tenant production rollout.

| # | Bug | Evidence | Independently flagged by | Severity |
|---|---|---|---|---|
| 1 | **`OrderSseService` broadcasts every order to every SSE subscriber across all tenants** | `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:17,29-40` — single global `CopyOnWriteArrayList<SseEmitter>`, `broadcast()` ignores tenant. STOMP path is correctly scoped; SSE was never updated. | Backend #1, Security #1 | **CRITICAL** |
| 2 | **Customer-orders IDOR via optional `verify` param** | `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104` + `db/migration/V18__order_history_by_email.sql:9-19` — anyone with an email can list that customer's full order history. | Security #2 | **CRITICAL** |
| 3 | **`reviews_tenant_write` reads wrong session GUC** | `db/migration/V27__customer_reviews.sql:31-36` — checks `app.tenant_id` but app sets `app.current_tenant_id`. The OR-clause on `customer_email` is the only working path → review-bombing primitive. | Database HIGH #1, Security HIGH #4 | **HIGH** |
| 4 | **Stripe webhook has no idempotency / replay protection** | `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:113-132` — every retry creates duplicate `financial_transactions`, double-publishes events, double-emails customer. | Backend #2, Security #3, QA P0 #1 | **CRITICAL** (financial integrity) |
| 5 | **No method-level authorization anywhere** (`@PreAuthorize` count = 0 across `core-java/src/main/java/`) | `SecurityConfig.java:68-74` ends with `.anyRequest().authenticated()`. Any tenant employee can call `/gdpr/customers/{id}/erase`, `DELETE /orders/{id}`, etc. | Security HIGH #5 | **HIGH** |

These five sit above everything else. None are conceptually hard fixes — total estimated effort is 1–2 days plus regression tests. They MUST be done before any production rollout that hosts >1 tenant or processes real card payments.

---

## Cross-cutting themes (multiple agents independently surfaced)

### A. Tenant isolation works *until* it doesn't

The three-layer defence (RLS → `JwtTenantFilter` → `TenantContext` ThreadLocal) is the right architecture, and three audits (Backend, Security, Database) all credit the **synchronous request path** as sound. The leaks are above and beside the RLS layer:

- **SSE service** never reads `TenantContext` (Backend, Security)
- **Storefront IDOR** uses RLS but lets the application set the comparison value from request input (Security, Database)
- **Reviews policy** uses the wrong GUC name (Database, Security)
- **Async `@Async` paths** have no `TaskDecorator` propagating `TenantContext` — only safe today because no `@Async` method touches the DB yet (Backend HIGH #3)
- **`@CacheEvict(allEntries=true)`** in `BulkImportService.java:55,110` and `SyncService.java:41-44` undoes the per-tenant cache key generator (Backend, Database)
- **`tenants` table has no RLS** — app role can enumerate every tenant (Security, Database)
- **`FORCE ROW LEVEL SECURITY` missing** on `reviews`, `shop_promotions`, `shop_announcements`, and all six `_aud` tables → privileged role bypass (Security, Database)

**Pattern**: when something new is added, the RLS coverage and tenant-context propagation are an afterthought. The team has lived through this four times already (V11 fixes V4, V14 fixes V9, V15 fixes V5, V33 fixes V27/28/29 per the database audit). **Process gap, not code gap.** The fix is a tenant-isolation regression test required on every new-table PR.

### B. The team built defence-in-depth — then disabled half of it in production

- Prometheus/Grafana/Alertmanager stack fully wired with k8s annotations + NetworkPolicies — but `application-prod.yml:91` exposes only `health,info` actuator endpoints with a comment promising a sidecar that doesn't exist. **Every business metric is paper-only in prod.** (DevOps CRITICAL #1)
- edge-go has zero `/metrics` endpoint despite the entire scrape config + a planning doc claiming Prometheus scrapes it. (DevOps + Edge audits)
- Custom alert rule `tenant_context_missing_total` references a counter that does not exist anywhere in Java code — the alert will silently never fire. (DevOps)
- `JwtTenantFilter` populates `TenantContext` but never `MDC.put("tenantId", …)` — on-call cannot grep logs by tenant. (DevOps HIGH #3)
- `PaymentServiceTest` mocks `MockedStatic<Webhook>` everywhere → real HMAC signature verification is never exercised. A wrong webhook secret would still pass green CI. (QA Sneaky risk #1)
- `TestJWTMiddleware_Validate_ValidToken` (edge-go) uses `t.Logf` instead of `t.Errorf` and admits in a comment it can't actually validate the token — the test passes regardless of outcome. (QA + Edge)

**Pattern**: the *shape* of best practice is checked in (probes, alerts, scrape config, signed webhook handler, test files). The *behaviour* under load was never verified. This is the second-most-likely class of production surprise after the tenant-isolation bugs.

### C. The dashboard is a different product than the storefront

The frontend audit's standout finding: **the customer storefront is a real food product**, the **vendor dashboard is shadcn-default blue**. `app/globals.css:13` still has `--primary: 221.2 83.2% 53.3%` (shadcn blue), the sidebar uses blue + purple gradients on slate-900, every `<Button variant="default">` ships blue. The orange/emerald palette only exists as hardcoded utility classes on `/shop/*` pages (54 hits vs 26 blue/purple in dashboard chrome).

Combined with `components/dashboard/sidebar.tsx:54` being `w-64` with no responsive breakpoint (sidebar consumes 68% of a 375px viewport), and **zero `next/image` usage anywhere** (`next.config.mjs` `remotePatterns` is dead config), the dashboard story is: a vendor signs up, sees the slick orange storefront a customer will see, then logs into a blue Vercel template that doesn't work on their phone. After the rejected "Warm Editorial" PR #49, the brand never reached the admin chrome.

### D. edge-go does ~50 LOC of real work wrapped in ~1000 LOC of half-built gateway

The edge audit's verdict: **delete it and absorb into Core**. Every primitive edge-go reimplements (rate limiting, JWT validation, circuit breaker, observability) is already present in Core, better. The only thing earning rent is the WhatsApp webhook handler (~150 LOC of orchestration) — easily moved to a `WhatsAppController` in Spring. The codebase's own `.planning/codebase/CONCERNS.md:195` already flags the gateway as "broken at horizontal scale" because the rate limiter is in-process per-pod, not distributed. Plus a JWKS map data race, no `aud` check, single global circuit breaker for four operations, and `WHATSAPP_DEFAULT_SHOP_ID` is single-tenant (breaks on the second WhatsApp tenant).

### E. The test count claim is undersold *and* the payment subsystem is the hole

QA audit verified 432 Java `@Test` (claimed 390), 84 Jest blocks (claimed 76), 54 Go funcs (claimed 50), plus 21 uncounted Playwright tests and 18 uncounted Testcontainers classes — total ~595 logical invocations vs claimed 516. **CLAUDE.md is stale.**

But the *quality* read is more nuanced: tenant-isolation tests are gold-standard (`MultiTenantIsolationIntegrationTest` asserts `pg_class.relrowsecurity` directly + RLS bypass attempts via Testcontainers PG). Payment tests are mocked-through:

- Stripe `Webhook.constructEvent` is mocked everywhere → signature verification untested
- `Order.idempotencyKey` field exists, lookup logic exists, **zero tests** verify duplicate POST returns same order
- `charge.refunded` webhook is in the "ignore" branch despite Phase 17 (PR #51) shipping vendor refunds
- Playwright is **not in CI** — the most behavioural tests don't gate merges
- No JaCoCo for Java → 432 `@Test` annotations with no branch-coverage visibility

---

## Pre-production blocker list

These are items the council collectively says should not ship to a production environment serving real tenants and real payments:

1. **Fix `OrderSseService` cross-tenant broadcast** — capture tenant id at `subscribe()`, filter in `broadcast()`. Add `OrderSseServiceTenantIsolationTest` regression. (~1h)
2. **Make `verify` mandatory on `GET /public/orders`** — return 400 when missing. (~30m)
3. **Add Stripe event idempotency** — `processed_stripe_events(event_id PRIMARY KEY)` table + `INSERT ON CONFLICT DO NOTHING` guard at top of `handleWebhookEvent`. (~2h)
4. **Fix `reviews_tenant_write` policy** — V35 migration with correct GUC name, drop the customer_email OR-clause, require `EXISTS (SELECT 1 FROM orders WHERE id=order_id AND customer_email=app.customer_email)`. (~1h)
5. **Add `FORCE ROW LEVEL SECURITY` to** `reviews`, `shop_promotions`, `shop_announcements`, and all six `_aud` tables. (~30m)
6. **Strip `accessToken`/`refreshToken` from NextAuth `session()` callback** — keep server-side only, route via BFF. (~3h, fronted by careful XSS audit)
7. **Add role-based authorization** — `JwtGrantedAuthoritiesConverter` + `@PreAuthorize` on `/gdpr/...`, `DELETE /orders/{id}`, `DELETE /shops/{id}`, financial-write endpoints. Define OWNER/MANAGER/STAFF/KITCHEN/READONLY roles. (~1 day)
8. **Edge-go `aud` claim verification + `sync.RWMutex` on `publicKeys`** — or skip the patch and execute the "delete and absorb" plan. (~1h to patch)
9. **Per-tenant rate limiting in edge-go** (or move to Spring's Bucket4j+Redis path). Today: single-tenant DoS is trivial. (~4h to patch, or part of the absorb plan)
10. **Flip prod actuator exposure** to include `prometheus,metrics` OR wire a sidecar. **Add `/metrics` to edge-go** (or absorb). Add `MDC.put("tenantId", …)` in `JwtTenantFilter`. Verify `tenant_context_missing_total` counter actually exists. (~1 day)
11. **Backup hygiene** — verify `backups/jtoye_jtoye_*.sql.gz` (84 files, all but one in `.gitignore`; the one tracked file `jtoye_jtoye_20251231_121414.sql.gz` is from before the gitignore was added — verify contents, purge from git history if real). The dev backup script also appears broken (capturing `pg_dump` stderr noise into `.gz` files instead of dumps — see Jan 19+ files at <200 bytes).
12. **Confirm Stripe production secret wiring** — `STRIPE_API_KEY` is read by core-java but no prod K8s Secret/env exposes it.

**Total estimated effort**: 4–5 focused engineering days. Not a sprint. Most items are 1–4 hours each.

---

## Day-2 hardening (ship-but-fix-soon)

13. Replace Spring StateMachine with a transition-table `EnumMap<OrderStatus, EnumMap<OrderEvent, OrderStatus>>` — eliminates 5 `Mono.block()` calls per order transition (Backend HIGH #5)
14. Configure bounded `TaskExecutor` + `TaskDecorator` for `@EnableAsync` so the next `@Async` method that touches the DB doesn't silently bypass tenancy (Backend HIGH #3)
15. Remove the duplicate `TenantFilter` registration (`@Component` + `addFilterBefore`) — currently three filters race to clear `TenantContext` (Backend HIGH #4)
16. Add `fallbackMethod` to `@CircuitBreaker(name="stripe")` and define a `stripe` retry instance in `application.yml`
17. Map `ObjectOptimisticLockingFailureException` to 409 in `GlobalExceptionHandler` so concurrent order transitions return clean retry signal not 500
18. Replace `@CacheEvict(allEntries=true)` in `BulkImportService` and `SyncService` with the tenant-aware evictor pattern already established
19. Add `(tenant_id, status, created_at DESC)` and `(tenant_id, shop_id, created_at DESC)` composites on `orders`. Drop the three duplicate indexes on `orders.order_number`. Add `tenant_id` indexes on `shop_promotions` and `shop_announcements`
20. Add `@EntityGraph(attributePaths = "items")` to `OrderRepository.findAll(Pageable)` — fixes the dashboard N+1
21. Pool reset hook for `app.customer_email` and `app.tracking_*` GUCs (or switch to `SET LOCAL` only) — most likely silent cross-tenant leak path
22. Refactor `PaymentEventOutboxFlusher` to one transaction per tenant, not one per cycle (connection-pool starvation risk at 1000 tenants)
23. Add API versioning (`/v1/...` URI prefix) — without it, the first DTO breaking change is a customer outage
24. Backup-verify CronJob (download yesterday's dump into a sandbox namespace, run `pg_restore --list`, alert on failure)
25. MinIO replication / mirror to S3 Glacier — today vendor product images have zero backup
26. Add JaCoCo, fail CI below 70% line coverage on `payment`, `security`, `order` packages — this single change forces the missing payment tests to surface
27. Add the top-5 missing tests from QA audit: `PaymentWebhookSignatureIntegrationTest`, `GuestOrderIdempotencyIntegrationTest`, `JwtSecurityIntegrationTest` (expired/wrong-aud/no-token), `RefundWebhookHandlingIntegrationTest`, Playwright in CI
28. Frontend: rebrand `--primary` design token (one-line cascade), make dashboard sidebar responsive, swap `SafeImage` to `next/image`, split mega-pages (marketing 1231 LOC, orders 951, products 889), add TanStack Query for server state

---

## Strategic verdict — the commercial read

### Where the platform actually sits

**At table-stakes feature parity** with the established UK food-SaaS field. Verified UK pricing baseline from the market analysis:

| Player | Entry £/mo | Card processing | Hardware | Notable |
|---|---|---|---|---|
| Square for Restaurants | £0 / £69+VAT / Custom | 1.75% in-person, 1.4% + 25p online | £549 KDS screen | Lowest barrier in market |
| Toast | £80–£150 + extras | 2.49% + 15p in-person | £799+ proprietary | $44B-class public co; subsidises with US payment margins |
| Lightspeed Restaurant | £59 / £109 / £339 per site | quote | quote | Public (NYSE: LSPD); $545/mo ARPU |
| Flipdish | £119 (annual) / £139 (monthly) | bundled | n/a | $157M raised; UK/IE strong; per-order fee in legacy data |
| Access Collins | £135 / £149 (Evo, AI) | n/a | n/a | UK; bookings-focused |
| Vita Mojo | from £50 (quote) | quote | quote | $30M Series B Jan-2025; enterprise/QSR |
| Slerp | unlisted (~$4.4M ARR) | bundled | n/a | UK boutique D2C; 0% commission positioning |
| Fresh KDS (standalone) | $15/screen/mo | n/a | iOS/Android tablet | Best-in-class KDS-only |

J'Toye claims roughly the table-stakes 2026 feature surface (POS + ordering + KDS + marketing + payments + storefront + allergen labels) and lacks several common bundle components: branded native mobile app, aggregator integrations (Deliveroo/UE/JE — usually via Deliverect), MTD VAT bridge, inventory/COGS, payroll/scheduling, public pricing page.

### Why "feature parity" is a losing position

From the vertical SaaS strategist:
- **Hospitality SaaS trades at ~1.8x revenue** (Oct 2025) — the worst vertical
- **Toast earns ~76% of revenue from financial services** (payments, lending, payroll) not subscriptions
- **Olo was acquired by Thoma Bravo in July 2025 for ~$2B** (~5–6x revenue) on the "own the customer relationship" thesis — i.e., **"replace Deliveroo's 30%"** is the single most validated SaaS narrative in this space
- UK independent dining is **22.7% smaller than pre-COVID, 3.4 net closures per day, margins crushed from 15-20% to ~5%**
- 60% of UK independents lack the capital or skills to implement chain-grade systems — a real opening

### The three credible UK wedges (market analyst)

1. **Sub-£50/site/mo all-in, no per-order commission** — undercut Square's effective cost-of-ownership for busy independents
2. **Compliance-first** — Natasha's Law (Oct-2021 + March-2025 FSA expansion) + MTD VAT bridge — none of the global incumbents headlines this
3. **Multi-tenant white-label for franchises / dark kitchens / multi-brand operators** — directly maps to the RLS architecture as a *real* asset (not just a backend implementation detail)

### The commercial critic's preferred play

**Niche down to UK ethnic-food independents** (halal Caribbean / West African / regional South Asian) where:
- Founder lived experience is the only credible moat available pre-revenue
- Customers are loyal to brand operators (resilient sub-segment)
- WhatsApp is the existing ordering channel (30–60% of orders happen there today via spreadsheet)
- Natasha's Law allergen labels are a £19/mo trojan-horse pain point
- 5 specific London neighbourhoods (Peckham, Brixton, Tottenham, East Ham, Croydon) are walkable, knockable distribution channels

Recommended pricing tiers: **£39 / £89 / £179 monthly** with **no per-order commission** (pass Stripe at 1.5% + 20p, no markup as a trust signal). 120–150 paying customers = £100k ARR. 600–700 = £500k ARR. Both plausible within one cultural niche in London alone.

### Investment verdict

**Pass at £500k @ £3M cap today.** What's missing: any evidence of design partners, founder-market-fit narrative beyond "I built this", a defined customer cohort, a "why now" story (Flipdish was raising in 2022 — the window is narrower in 2026 not wider), a domain-credentialed co-founder.

**What would change the verdict**: 10 paying vendors at £49/mo each (£5,880 ARR) and a six-month retention number. At that point the £3M cap is defensible because the platform has de-risked itself by proving the founder can sell, not just build. Or drop the cap to £1.2M for a £150k friends-and-family round that funds the door-knocking phase.

---

## What I would do in the next 90 days (synthesis author's opinion)

This is my read across the 10 source docs, presented as an opinionated plan rather than a balanced menu.

### Days 1–7: stop the bleed, then stop building

Fix the five pre-prod blockers above (#1–#5 in particular — SSE leak, IDOR, Stripe idempotency, reviews policy, FORCE RLS). That is ~2 days of focused work. Then **freeze all feature development.** Three milestones of post-audit hardening with zero customers is the polish-before-product trap. Every test added before customer #10 is a sunk cost.

### Days 7–30: customer development, not code

Spend the next three weekends door-knocking 30 ethnic-food independents in Peckham, Brixton, Tottenham, East Ham, Croydon. Lead with the **WhatsApp ordering + Natasha's Law allergen labels + £49/mo, no commission** pitch, not a feature demo. Take the platform offline if needed — show paper mockups, get verbal commitments, find the version of the wedge that resonates locally.

If you cannot get 5 verbal commitments in 30 days, the product is wrong, the niche is wrong, or the founder-market fit is wrong — and the answer is to stop, not to ship more code.

### Days 30–60: convert to first 3 paying

Onboard the first 3 paying vendors. Treat them as design partners. The technical work in this window is whatever they need to actually use the product on their phone — which means the **dashboard responsive rebuild** (Frontend top-fix #2) and the **rebrand cascade via `--primary` design token** (top-fix #1) jump to the front of the queue.

Backend day-2 hardening can wait unless something is actively breaking.

### Days 60–90: prove or pivot

Get to 10 paying vendors at £49/mo (£5,880 ARR). At that point: raise £150–250k friends-and-family at £1.2M cap and quit the day job to scale.

If you cannot get to 10 in 90 focused days, run one of the three pivots from the commercial critic:
- **(a)** keep the niche, change the channel (white-label to ethnic-food digital agencies — ~200 in UK)
- **(b)** narrow further to **Allergen Compliance as a Service** (single £19/mo SKU, single buyer, single pain point — could land 1000+ UK customers without selling the rest of the platform)
- **(c)** position as a **developer-extensible commerce platform** for food-tech builders, sell the engine not the app

### Months 4–12 (if traction): reposition for fintech multiple

The vertical SaaS strategist's most important insight: at 1.8x revenue you need £20M ARR for a £36M exit; at 7–9x with embedded payments and lending the same ARR is £140–180M. **Stripe Connect interchange-plus deal in month 6, card-sales-secured working capital advances in month 12** are the single largest valuation-multiple shifts available. Skip payroll-first — that's the wrong order.

---

## What I would NOT do

- **Do not** keep adding features to reach "Flipdish parity". Parity with a £200M-funded incumbent is a losing position. You have to be different, not similar.
- **Do not** rebrand under another generic name. "OaaS" is engineer-language but the alternative isn't "OrderingPlatform" — it's "Habibi Kitchen OS" or similar community-rooted positioning that signals exactly who it's for.
- **Do not** pursue the marketplace / aggregator angle (storefront aggregation, public storefront controllers hint at this). 100x the engineering and capital. Cut it or commit fully — drift is death.
- **Do not** keep edge-go. ~1000 LOC of half-built gateway primitives that Spring already implements better, with the project's own planning docs flagging it as "broken at horizontal scale". Absorb the WhatsApp handler into `WhatsAppController` and delete the rest.
- **Do not** invest in JaCoCo / API versioning / async TaskDecorator until customer #10. They are correct items — but they are correct items for a customer-bearing system.

---

## Honest critique of this audit

Three caveats on the council's output:

1. **Agents do not see each other's findings.** Some bugs were independently surfaced by 2–3 agents from different angles (SSE leak, Stripe idempotency, reviews RLS) — this triangulation increases confidence. Other findings are single-source — they should be verified before being treated as load-bearing.

2. **The commercial critic's "pivot to ethnic food" is one read, not the only read.** It is the most defensible read given the inferred founder context (Bralabee email, J'Toye naming, prior memory referencing African/Caribbean food context). If the founder's actual community is elsewhere, the niche should follow that — the principle ("niche to a community where you have earned trust") generalises; the specific niche does not.

3. **No agent ran the code.** Every finding is static analysis or web research. Two specific verifications would strengthen the report: (a) actually reproduce the SSE cross-tenant leak in a running stack with two browser sessions, and (b) actually run a Stripe webhook test event twice and observe the duplicate `financial_transactions` row. Both would take ~30 minutes and would convert "high-confidence claim" into "verified bug." I have not done these in this synthesis.

---

## Source documents

| # | Title | Author persona | File |
|---|---|---|---|
| 01 | Backend / Distributed Systems | Senior backend engineer, 15y multi-tenant SaaS | [01-backend-engineer.md](sources/01-backend-engineer.md) |
| 02 | Security | AppSec / pentester, multi-tenant SaaS specialty | [02-security-engineer.md](sources/02-security-engineer.md) |
| 03 | Database / Data Engineer | Senior PostgreSQL DBA, multi-tenant SaaS | [03-database-engineer.md](sources/03-database-engineer.md) |
| 04 | DevOps / SRE | On-call survivor, multi-tenant SaaS production | [04-devops-sre.md](sources/04-devops-sre.md) |
| 05 | Frontend / UX | Senior FE engineer, B2C food-delivery | [05-frontend-ux.md](sources/05-frontend-ux.md) |
| 06 | QA / Test Engineering | Senior QA lead, production-failure veteran | [06-qa-engineer.md](sources/06-qa-engineer.md) |
| 07 | Edge Gateway (edge-go) | Distributed systems engineer, Go gateway specialty | [07-edge-go.md](sources/07-edge-go.md) |
| 08 | UK Food-SaaS Market Analysis (2025–2026) | UK hospitality tech industry analyst | [08-market-analyst.md](sources/08-market-analyst.md) |
| 09 | Vertical SaaS Strategic Context | Vertical SaaS GTM consultant | [09-vertical-saas-strategist.md](sources/09-vertical-saas-strategist.md) |
| 10 | Commercial / Product Critique | Brutally honest product strategist | [10-commercial-critic.md](sources/10-commercial-critic.md) |

---

## One closing read

The hardest sentence to write in this audit is also the most important one: **the technical risk is fixable in days; the commercial risk is structural.** Every code finding above can be closed with focused effort by a competent engineer — and the codebase strongly suggests that engineer is the founder. None of the commercial findings can be closed by writing more code. They can only be closed by selling.

The platform has run its first three milestones brilliantly on the engineering axis and not at all on the commercial axis. Milestone 4 should be the first one that doesn't ship a single new feature — only the pre-prod blockers, the design-token rebrand, the responsive dashboard fix, and **10 paying vendors**. If milestone 4 succeeds on those terms, the platform has a real future. If it doesn't, the codebase is still an exceptional staff-engineering portfolio piece — and that is a real outcome, not a failure.
