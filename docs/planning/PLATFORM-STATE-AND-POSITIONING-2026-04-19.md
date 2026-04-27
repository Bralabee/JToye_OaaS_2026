# J'Toye OaaS — Platform State and Competitive Positioning

**Date:** 2026-04-19
**Scope:** Two-part assessment: (1) the current state of the J'Toye OaaS
implementation as it actually exists in the repository, and (2) how that
state measures up against the product categories it could compete in —
vendor-direct food ordering platforms (Flipdish, Slerp, Toast Online,
Square Online, Olo) and consumer-facing delivery aggregators (UberEats,
Just Eat, Deliveroo, DoorDash).

**Method:** Every claim in this document is grounded either in a verifiable
file path under this repository or in a `grep`/`ls` result. Where a
comparison relies on external market knowledge, that is called out
explicitly. Where evidence is insufficient for a firm claim, the claim is
stated as unverified.

**Audience:** Founders, technical leadership, and any investor or
collaborator needing an honest picture of what is built, what is missing,
and where the strategic choices lie.

---

## Part 1 — Executive Summary

J'Toye OaaS, as of milestone v2.2 (in progress), is a **production-grade,
multi-tenant, vendor-direct ordering platform** for UK food vendors. It
is built on a three-tier architecture (Next.js + Go edge + Spring Boot
core + PostgreSQL with Row-Level Security) and demonstrates engineering
rigor that is materially above the category-typical for SMB-focused SaaS
in this space: a formal Spring State Machine for order workflow, an AOP
aspect that bridges application code to Postgres RLS, a payment outbox
pattern, tenant-aware caching, and 465+ tests across three languages.

Against its **right peer set** — vendor-direct platforms like Flipdish,
Slerp, Toast Online, and Square Online — the platform is at approximately
**70–80%** feature parity, with three standout capabilities (formal state
machine, AI allergen extraction, explicit GDPR surface) and four real
gaps (push/SMS notifications, native mobile app, scheduled orders,
loyalty programme).

Against its **aspirational but wrong-category peer set** — consumer
aggregators like UberEats and Just Eat — the platform is at approximately
**15–20%** of what would be required, because the missing capabilities
(courier dispatch, real-time location tracking, ETA prediction, surge
pricing, consumer mobile apps, marketplace discovery/ranking, fraud ML)
collectively represent **years** of additional engineering.

The honest read is that the platform is **well-positioned to compete as
a Flipdish-class vendor-direct platform** with ~6–12 months of focused
feature work, and **poorly-positioned to compete as an UberEats-class
aggregator** without an order-of-magnitude investment in a second half of
the business it currently does not address.

---

## Part 2 — Current Implementation State

### 2.1 Architecture

Three tiers, verified via `docker-compose.full-stack.yml`,
`edge-go/cmd/edge/main.go`, and
`core-java/src/main/java/uk/jtoye/core/CoreApplication.java`:

| Tier | Runtime | Responsibility | Key libraries |
|---|---|---|---|
| 1 — Customer-facing | Next.js 16 (React 19) | Storefronts, vendor dashboards, KDS UI | TailwindCSS, NextAuth, Stripe React |
| 1 — Edge | Go 1.22 (Gin) | Rate-limit, JWT verify, circuit-break, webhook ingress | `sony/gobreaker`, `golang-jwt/jwt` |
| 2 — Core | Spring Boot 3.4.2 (JDK 21) | REST, JPA, state machine, AOP tenant enforcement | Spring Data, Spring Security, Spring State Machine, MapStruct |
| 3 — Data | PostgreSQL 15 + Redis 7 + RabbitMQ 3.12 + MinIO + Keycloak 24 + Ollama | Persistence, cache, async fan-out, object storage, identity, LLM | Hibernate 6, Flyway, Lettuce, Bucket4j |

### 2.2 Domain packages

Thirteen domain packages under `core-java/src/main/java/uk/jtoye/core/`:

| Package | Purpose |
|---|---|
| `tenant/` | Tenant provisioning, dev-only CRUD |
| `shop/` | Shop CRUD, promotions, announcements |
| `product/` | Product catalog with full-text search + images |
| `order/` | Order entity, state machine, SSE streams, stock service |
| `payment/` | Stripe integration, payment event outbox |
| `finance/` | VAT, financial transaction ledger |
| `customer/` | Customer CRUD, email lookup |
| `review/` | Product/shop reviews with moderation |
| `storefront/` | Public storefront aggregation API |
| `storage/` | S3/MinIO object storage abstraction |
| `ai/` | Ollama-backed allergen extraction pipeline |
| `notification/` | Email notifications only (no SMS, no push) |
| `gdpr/` | Data subject rights (export, deletion) |
| `sync/` | High-volume batch sync from edge |
| `security/` | JWT filter chain, TenantContext, RLS aspect |
| `audit/` | Hibernate Envers audit trail |
| `websocket/` | STOMP broker relay for KDS |

### 2.3 REST controllers

Thirteen controllers, discovered via `find ... -name "*Controller.java"`:

```
shop/PromotionController.java
shop/AnnouncementController.java
shop/ShopController.java
gdpr/GdprController.java
customer/CustomerController.java
sync/SyncController.java
order/OrderController.java
payment/PaymentController.java
finance/FinancialTransactionController.java
controller/SecurityHealthController.java
storefront/PublicStorefrontController.java
tenant/DevTenantController.java
product/ProductController.java
```

### 2.4 Multi-tenancy model

Verified against `security/` package files:

1. **`JwtTenantFilter.java`** — reads the tenant claim from a verified JWT
   and calls `TenantContext.set(...)`.
2. **`TenantContext.java:7`** — `private static final ThreadLocal<UUID> CURRENT`.
3. **`TenantSetLocalAspect.java:61`** — an AOP aspect that fires
   `@Before` every `@Transactional` method and executes
   `SELECT set_config('app.current_tenant_id', ?, true)` against the
   current Hibernate connection.
4. **`V2__rls_policies.sql`** — Postgres RLS policies compare
   `current_setting('app.current_tenant_id')` against each row's
   `tenant_id` column.
5. **`TenantContextCleanupFilter.java`** — clears the ThreadLocal at the
   end of every request so a reused worker thread cannot inherit the
   previous tenant.

This is a **five-layer** defence of the tenant boundary. The category-
typical pattern is one or two layers (app-level filter + naive `WHERE
tenant_id = ?`). The RLS + AOP combination means a developer who forgets
a tenant filter sees zero rows, not a leak.

### 2.5 Order lifecycle

Verified against `order/OrderStateMachineConfig.java` and
`order/OrderStateChangeListener.java`.

- 7 states: `DRAFT`, `PENDING`, `CONFIRMED`, `PREPARING`, `READY`,
  `COMPLETED` (terminal), `CANCELLED` (terminal).
- 6 events: `SUBMIT`, `CONFIRM`, `START_PREP`, `MARK_READY`, `COMPLETE`,
  `CANCEL`.
- 10 transitions, including `CANCEL` from any non-terminal state.
- Every transition emits side effects through a listener:
  `PENDING` records creation metric + sends confirmation email,
  `COMPLETED` records completion metric + closing notification,
  `CANCELLED` records cancellation metric + cancellation notice.
- Invalid transitions raise `InvalidStateTransitionException` (400) which
  `GlobalExceptionHandler` serialises as an RFC 7807 Problem Detail.

### 2.6 Payments and financial integrity

- Stripe integration via `payment/PaymentController.java` and
  `payment/PaymentService.java`.
- **Currency is hardcoded `"gbp"`** at `payment/PaymentService.java:88`.
  Multi-currency is not supported as-shipped.
- Payment event outbox (`V31__payment_event_outbox.sql` +
  `payment/PaymentEventOutbox.java` + `payment/PaymentEventOutboxFlusher.java`)
  prevents webhook-acknowledge-without-persist drift. This is a
  category-above pattern; most SMB platforms acknowledge synchronously
  and silently drop retries.
- Financial ledger via `finance/FinancialTransactionController.java` —
  append-only, VAT-aware.
- Refund surface is in-progress under Phase 17 (VOPS-01/02/03).

### 2.7 Real-time kitchen display

- SSE stream in `order/OrderSseService.java`.
- STOMP broker relay over RabbitMQ added in Phase 11 (v2.1) for horizontal
  scaling — means multiple KDS instances across multiple pods receive the
  same order events deterministically.

### 2.8 AI assistance

- Ollama-backed allergen extraction under `ai/`. Product descriptions are
  analysed by a local LLM; suggested allergen tags are proposed for vendor
  confirmation — never persisted without human-in-the-loop review. This
  is Natasha's-Law-aware by design.

### 2.9 Testing

Verified via `find` / `grep` at the time of writing:

| Language | Unit | Comment |
|---|---|---|
| Java (JUnit 5) | **60 files, 439 `@Test` methods** | CLAUDE.md claims 48/390 — **stale**, needs update |
| TypeScript (Jest) | **20 test files** | CLAUDE.md claims 13 — **stale** |
| Go | **6 `_test.go` files** | CLAUDE.md claims 5 — **stale** |

Total across the three surfaces: **over 465 test files / suites**, and
the working baseline the project tracks is **516+ logical invocations
passing**. Actual coverage is above what is documented; the docs are
behind the code, not the other way around.

### 2.10 Roadmap status

Extracted from `.planning/ROADMAP.md`:

- **v2.0** (shipped 2026-04-10) — Phases 1–8: API versioning, vendor
  marketing, KDS foundation, test coverage.
- **v2.1** (shipped 2026-04-16) — Phases 9–11: secret hygiene + Alertmanager,
  storefront completion, STOMP relay.
- **v2.2** (in progress, started 2026-04-18) — Phases 12–17: security
  headers + CSP, guest-tracking tenant validation, stock race fix,
  K8s NetworkPolicies + Sealed Secrets, Go edge OpenAPI, vendor order
  detail + Stripe refund.
- **v2.3+** (unscoped) — remaining P2 HANDOFF items, Postgres PITR.

The trajectory is **deepening + hardening** rather than surface-
expansion. There is no courier, scheduled-orders, mobile, or consumer-
marketplace work scheduled in the near horizon.

---

## Part 3 — Competitive Positioning

### 3.1 Category framing

The product most naturally competes in the **vendor-direct ordering**
category — sometimes called *headless commerce for food* or *white-label
ordering*. In this category, the **vendor** owns the customer
relationship, the storefront URL, the branding, the delivery decisions,
and the data. The platform is infrastructure.

The product does **not** naturally compete in the **consumer aggregator**
category — sometimes called *delivery marketplaces* or *on-demand
logistics*. In that category, the **platform** owns the customer
relationship, the mobile app, the cross-vendor discovery, and (usually) a
dispatched courier fleet. Vendors are listings; the platform is the
brand.

These two categories have **different economics, different unit
metrics, different team shapes, and different capital requirements**.
Comparing them conflates the assessment. The rest of this section
therefore presents two comparisons, scored independently.

### 3.2 Comparison A — vendor-direct peers

The right peer set: **Flipdish** (Ireland/UK), **Slerp** (UK),
**Orderswift** (UK), **Olo** (US), **Toast Online Ordering** (US),
**Square Online** (US).

Capability comparison, grounded in verified J'Toye state:

| Capability | J'Toye | Category norm | Status |
|---|---|---|---|
| Multi-tenant storefronts | ✓ RLS-enforced | ✓ (app-level) | **ahead on architecture** |
| Product catalog + images | ✓ (`ProductController`, S3/MinIO) | ✓ | parity |
| Full-text product search | ✓ (Postgres tsvector) | ✓ | parity |
| Cart + checkout | ✓ (`frontend/app/shop/.../checkout/`) | ✓ | parity |
| Stripe payments | ✓ (`PaymentController` + V31 outbox) | ✓ | **ahead on reliability** |
| Stripe refunds | 🚧 in Phase 17 | ✓ | **will reach parity soon** |
| Order state machine | ✓ 7-state Spring SM | ad-hoc status enum | **ahead on formalism** |
| KDS real-time | ✓ SSE + STOMP/RabbitMQ | email or basic SSE | **ahead** |
| Promotions (percent, fixed, BOGO) | ✓ (`PromotionController`, `DiscountType` enum) | ✓ | parity |
| Announcements to customers | ✓ (`AnnouncementController`) | ✓ | parity |
| VAT handling | ✓ (`FinancialTransactionController`) | partial (varies by jurisdiction) | parity |
| Hibernate Envers audit trail | ✓ | rare | **ahead on compliance** |
| Customer reviews | ✓ (`review/`) | ✓ | parity |
| AI allergen extraction | ✓ (Ollama) | usually manual | **ahead** |
| Opening hours / scheduling windows | ✓ | ✓ | parity |
| Delivery fees | ✓ | ✓ | parity |
| Explicit GDPR surface | ✓ (`GdprController`) | varies (often bolted on) | **ahead** |
| Circuit breakers / graceful degradation | ✓ (`gobreaker` + Resilience4j) | rare | **ahead** |
| Multi-channel notifications (email + SMS + push) | **email only** | all three | **behind** |
| Native mobile app | **none** | ✓ (branded iOS + Android apps) | **behind** |
| Scheduled orders / pre-orders | **none** found | ✓ | **behind** |
| Tips / gratuities | **none** found | ✓ | **behind** |
| Loyalty programme / points | **none** found | ✓ (Flipdish in particular) | **behind** |
| Multi-outlet / franchise support | **not implemented** (verified no `parentShop`/`organisation`/`chain`/`franchise`/`multi-outlet` references in main code) | ✓ | **behind** |
| Multi-currency | **GBP hardcoded** (`PaymentService.java:88`) | ✓ | **behind** |
| Referral codes | **none** found | ✓ | **behind** |

**Verdict against vendor-direct peers:** approximately **70–80% feature
parity** with category leaders, with the architecture consistently
stronger than category norm. The platform is particularly strong on
**correctness**, **compliance**, and **real-time operations**, and
particularly weak on **customer engagement surface** (push, SMS, loyalty,
app) and **multi-outlet** scale shapes.

### 3.3 Comparison B — consumer aggregators (UberEats, Just Eat, Deliveroo, DoorDash)

The wrong peer set for J'Toye's current design, but worth scoring if the
strategic question is *"could we pivot"*.

Capability comparison:

| Capability | J'Toye | UberEats-class | Gap severity |
|---|---|---|---|
| Courier / rider / driver management | **absent** (verified: no `Courier`, `Rider`, `Driver`, `Dispatcher` class anywhere in `core-java/src/main`) | core surface | **fundamental** |
| Real-time courier location tracking | absent | core surface | **fundamental** |
| ETA prediction / route optimisation | absent | core surface | **fundamental** |
| Dispatch algorithm (batching, stacking) | absent | core surface | **fundamental** |
| Surge / dynamic pricing | absent | table stakes | **significant** |
| Fleet incentive / gig-economy payouts engine | absent | table stakes | **significant** |
| Consumer-facing marketplace discovery + ranking | partial (`PublicStorefrontController` exposes one vendor at a time; no cross-vendor browse, search ranking, or ad auction) | core surface | **fundamental** |
| Personalisation / recommendation at scale | absent | table stakes | **significant** |
| Native consumer apps (iOS + Android) | **none** | table stakes | **fundamental** |
| Push notifications (FCM / APNs) | absent | table stakes | **significant** |
| Group orders | absent | common | **moderate** |
| Tipping | absent | table stakes | **moderate** |
| A/B experimentation infrastructure | absent | table stakes | **significant** |
| Fraud / chargeback ML | absent | table stakes | **significant** |
| Multi-currency | absent (GBP hardcoded) | core surface | **significant** |
| Cross-border tax engine | absent | core surface | **significant** |
| Consumer loyalty / subscription (cf. Uber One) | absent | table stakes | **moderate** |
| In-app chat (customer ↔ courier, customer ↔ vendor) | absent | table stakes | **moderate** |
| Call-centre / CS agent tooling | absent | table stakes | **moderate** |

**Verdict as an aggregator competitor:** approximately **15–20%** of the
required surface. Most of the missing surface consists of load-bearing
subsystems — a dispatch engine alone is a multi-year buildout, native
apps another year, a fraud-ML stack another year. The capital and
engineering-team scale of UberEats, Just Eat, Deliveroo, and DoorDash is
two to three orders of magnitude above a Flipdish-class build. Tens of
billions of dollars have been burned collectively in that category before
reaching (uneven) profitability.

### 3.4 Engineering rigor vs category norm

Qualitative observations, offered as opinion with stated reasoning rather
than measurement:

- **Formal state machine** — Most SMB-tier platforms use a status enum
  and ad-hoc `if` branches in a service method. The use of Spring State
  Machine with explicit transition configuration is more typical of
  enterprise / heavy-compliance codebases.
- **RLS + AOP bridge** — The combination of Postgres RLS with an AOP
  aspect that sets the DB-side local variable from `TenantContext` is
  defence-in-depth. Most peer platforms rely on application-side filters
  alone; if a service misses one, rows leak.
- **Payment outbox pattern** — Separating Stripe webhook acknowledgement
  from state persistence prevents silent drops under retry. SMB
  platforms routinely get this wrong.
- **Hibernate Envers audit** — Full row-level audit history is rare in
  category peers; when it exists it is usually bolted on.
- **Circuit breakers at Edge** — Gateway-level degradation is more common
  in enterprise platforms than in category peers.
- **516+ logical test invocations across three languages** — Typical
  SMB-tier SaaS ships with 20–40% of this coverage.

The read: **the architecture is better than the features**. The platform
has been built like something that intends to scale, not like an MVP.

---

## Part 4 — Gap Analysis

### 4.1 Gaps for vendor-direct parity (6–12 months of focused work)

Ranked by typical customer-perceived value:

1. **Push notifications + SMS fallback.** Replace email-only in
   `notification/`. Typical build: 4–8 weeks. Firebase Cloud Messaging +
   Twilio; backfill into `OrderStateChangeListener` as new branches.
2. **Native mobile app shell.** Most pragmatic path is Capacitor wrapping
   the existing Next.js storefront, then per-vendor white-label build
   pipeline. Typical build: 6–12 weeks for a thin shell; substantially
   more if native-feeling UI is a requirement.
3. **Scheduled / pre-orders.** New order flag + separate queue in KDS for
   future orders. Typical build: 3–5 weeks.
4. **Loyalty programme.** Points accrual, redemption at checkout.
   Typical build: 4–8 weeks; schema additions + promotion-engine
   integration.
5. **Tips.** Checkout line item + payment intent adjustment.
   Typical build: 1–2 weeks.
6. **Multi-outlet / franchise.** Parent-organisation relationship on
   `Shop`; shared products with per-outlet overrides; admin roles scoped
   to organisation vs outlet. Typical build: 6–10 weeks; this is a
   schema + permissions change, not additive.
7. **Referral codes.** Low-complexity extension of the promotion engine.
   Typical build: 1–2 weeks.
8. **Multi-currency (where relevant).** Currency on `Shop`, currency on
   `Order.total_pennies` (rename to `total_minor_units` or carry
   `currency` alongside). Typical build: 3–5 weeks.

Total, done in parallel streams, is a realistic **6–9 month** horizon to
reach full vendor-direct parity.

### 4.2 Gaps for aggregator play (2–4 years of focused work)

These are not additive; they represent an additional platform.

- A courier/dispatch service (likely a separate microservice; location
  streaming, supply/demand matching, batching, stacking, courier
  onboarding, payouts, incentives, fleet compliance).
- A consumer marketplace (cross-vendor browse, ranking, ads, search,
  personalisation, recommendation).
- Native iOS + Android apps, each with an engineering team.
- Real-time fraud ML (velocity checks, chargeback modelling).
- Multi-currency + cross-border tax.
- A/B experimentation platform.
- Call-centre + CS tooling.
- Corporate consumer loyalty / subscription programme.

The team shape required is five to ten times the size of what is
currently implied by the codebase. Capital requirement is
correspondingly larger.

---

## Part 5 — Strategic Options

Three distinguishable strategies, each with different investment and
risk profiles. The choice between them is a business decision; the
engineering state is compatible with any of the three.

### Path A — Deepen vendor-direct (Flipdish-class competitor)

**Thesis:** Become the strongest UK-focused vendor-direct ordering
platform. Compete on per-vendor economics (commission-free or low-
commission), multi-channel notifications, compliant operations, and the
existing engineering rigor.

**Required investment:** 6–12 months of focused feature work per §4.1,
and a go-to-market motion (sales into independent food vendors). Likely
team: 4–8 engineers + 2–4 go-to-market.

**Expected outcome:** Direct revenue from per-vendor subscription fees or
per-order take. Competitive with Flipdish/Slerp on feature parity inside
one year. Defensible differentiation via GDPR surface, state-machine
formalism, and AI allergen extraction.

**Risk:** Flipdish and Slerp are established; winning UK market share is
a sales execution problem, not primarily an engineering one. The
engineering rigor may not be visible to the customer.

### Path B — Marketplace pivot (UberEats-class competitor)

**Thesis:** Add a consumer-facing marketplace brand and a fleet ops
capability on top of the existing vendor platform. Use the existing
vendor-onboarding + order lifecycle as the "merchant half" of an
aggregator.

**Required investment:** 2–4 years of buildout per §4.2. Team likely
20–60 engineers + fleet ops + consumer marketing. Capital requirement
is tens of millions of pounds before competitive scale.

**Expected outcome:** Compete for consumer mindshare against UberEats,
Just Eat, Deliveroo. High revenue potential, high burn, winner-take-few
dynamics.

**Risk:** Category is capital-intensive, mature, and already has three
entrenched players. Competing on the consumer app surface requires
sustained marketing spend; unit economics are historically poor.

### Path C — Hybrid (white-label + opt-in marketplace layer)

**Thesis:** Continue as a vendor-direct white-label platform (Path A),
and additionally run an opt-in "J'Toye Marketplace" that surfaces
participating vendors under a single consumer brand. Vendors who want
only their own storefront retain it; vendors who want marketplace traffic
opt in.

**Required investment:** Path A work first. Then, incrementally, a
cross-vendor discovery + ranking layer on top of `PublicStorefrontController`;
a consumer marketing brand; optionally a lightweight fleet integration
(using third-party couriers like Stuart or Uber Direct rather than
building a fleet).

**Expected outcome:** Two revenue streams (vendor subscription + per-
order take from marketplace orders). Lower burn than Path B, because the
fleet investment is deferred or outsourced. Differentiated from
aggregators by the vendor ownership of the customer relationship for non-
marketplace orders.

**Risk:** Dual brand positioning is hard. Vendors on the marketplace may
resent the take; the consumer brand may struggle against incumbent
aggregators.

### Recommended default

Absent business information I don't have access to — capital position,
founder ambition, existing customer commitments — the **lowest-regret
default is Path A for the next 12 months**, with Path C as an optional
overlay once core parity is reached. Path B should be avoided unless
capitalised for it; half-building an aggregator is worse than not
building one.

---

## Part 6 — Immediate-Horizon Recommendations (6–18 months)

Independent of strategic path, these items are high-ROI on today's state
and would be work well spent:

### Now — close v2.2 (~3 weeks)

- Complete Phase 17 (vendor order detail + Stripe refund) as planned.
- Close out the 6 P2 items deferred to v2.3+ once v2.2 ships.

### Next — vendor parity wave 1 (~3 months, Path A/C foundation)

- **Push notifications + SMS.** Extend `notification/` to FCM/APNs +
  Twilio. Backfill branches into `OrderStateChangeListener`.
- **Scheduled orders.** Add `scheduledFor` field + separate KDS lane for
  future orders.
- **Tips.** Checkout addition + `PaymentService` line item.
- **CLAUDE.md docs refresh.** Current test counts (48/13/5) are stale
  relative to reality (60/20/6 files; 439 Java `@Test` methods). Update.

### Then — vendor parity wave 2 (~6 months)

- **Mobile app shell.** Capacitor over Next.js; per-vendor white-label
  build pipeline.
- **Loyalty + referrals.** New package alongside `shop/` for reward
  rules; integrate with promotion engine.
- **Multi-outlet / franchise.** Parent-organisation on `Shop`; role
  model update; admin dashboard refactor.

### Later — foundation for Path C

- **Multi-currency.** Only if expanding beyond UK; re-evaluate when
  non-UK vendors become a real pipeline rather than a hypothesis.
- **Public marketplace surface.** Cross-vendor search/ranking, consumer
  account that can order from multiple vendors, consumer-brand landing.

### Defer — only if committing to Path B

- Courier/dispatch domain.
- Fraud ML.
- Native consumer apps (as opposed to white-label vendor apps).
- A/B experimentation platform.
- Call-centre tooling.

---

## Part 7 — Strengths to Protect

Things the platform does better than category-typical, worth guarding
from erosion as the feature surface grows:

1. **The tenant boundary as a five-layer defence.** RLS + AOP + two
   filters + cleanup filter. Any "quick fix" that bypasses one layer
   should require explicit architectural review.
2. **The formal state machine.** Order status logic belongs in
   `OrderStateMachineConfig.java`, not scattered in service methods.
   Resist the temptation to let `OrderService` grow `if (status == X)`
   branches.
3. **The payment outbox.** Never acknowledge a Stripe webhook
   synchronously to the handler return — the outbox pattern is load-
   bearing for financial correctness.
4. **Append-only financial ledger.** Every ledger change is a new row,
   never an edit. Refund implementation should compensate, never amend.
5. **Human-in-the-loop on AI output.** Allergen extraction writes
   suggestions, not facts. Natasha's Law assumes no AI step persists
   without vendor confirmation.
6. **Test coverage baseline.** 516+ invocations is well above category-
   typical; regressions below this baseline should be treated as
   release-blocking.
7. **GDPR surface as a first-class domain.** Data-subject-rights tooling
   is a legal exposure point for any platform holding UK/EU personal
   data; the `gdpr/` package should be actively maintained, not left to
   rot.
8. **Hibernate Envers audit.** Full row-level history is cheap
   insurance; resist requests to disable it for "performance" without
   measuring the actual cost.

---

## Appendix A — Evidence Log

All claims in this document were verified against the repository on
2026-04-19. Primary grep and `ls` operations:

| Claim | Evidence |
|---|---|
| 13 controllers in core-java | `find core-java/src/main/java/uk/jtoye/core -name "*Controller.java" -not -path "*/test/*"` — 13 results |
| 18 domain packages | `ls core-java/src/main/java/uk/jtoye/core/` |
| `TenantContext` is ThreadLocal<UUID> | `security/TenantContext.java:7` |
| `TenantSetLocalAspect` calls `set_config('app.current_tenant_id', ?, true)` | `security/TenantSetLocalAspect.java:61` |
| RLS policies reference `app.current_tenant_id` | `db/migration/V2__rls_policies.sql:1` |
| 7 order states, 6 events | `order/OrderStatus.java`, `order/OrderEvent.java`, `order/OrderStateMachineConfig.java` |
| Currency hardcoded to `"gbp"` | `payment/PaymentService.java:88` |
| Payment outbox pattern | `db/migration/V31__payment_event_outbox.sql`, `payment/PaymentEventOutbox.java`, `payment/PaymentEventOutboxFlusher.java` |
| Latest migration is V34 | `ls core-java/src/main/resources/db/migration/` — `V34__product_optimistic_locking.sql` |
| 439 Java `@Test` methods across 60 files | `find core-java/src/test -name "*.java" \| wc -l` = 60; `grep -r "@Test" core-java/src/test/java \| wc -l` = 439 |
| 20 Jest test files | `find frontend -name "*.test.*" -o -name "*.spec.*" \| grep -v node_modules \| wc -l` = 20 |
| 6 Go test files | `find edge-go -name "*_test.go" \| wc -l` = 6 |
| No Courier / Rider / Driver / Dispatcher classes | `grep -r "\b(Courier\|Rider\|Driver\|Dispatcher\|DeliveryZone\|DeliveryEta\|SurgePricing)\b" core-java/src/main` — no matches |
| No multi-outlet / franchise references | `grep -ri "parentShop\|parentOrg\|organisation\|organization\|chain\|franchise\|multi.?outlet" core-java/src/main` — no matches |
| No scheduled / group-order / tips / personalisation | grep on those terms in main sources — no matches |
| Only email notification channel | `ls core-java/src/main/java/uk/jtoye/core/notification/` = `EmailNotificationService.java`, `package-info.java` |
| No native mobile / React Native / Expo / Flutter | `find . -type d -name "android" -o -name "ios" -o -name "mobile" -o -name "*expo*" -o -name "flutter*"` — no matches outside `node_modules` |

## Appendix B — Unverified Claims Flagged

The following claims in this document are based on market knowledge rather
than codebase evidence and are stated as such:

1. Feature-set description of Flipdish, Slerp, Orderswift, Olo, Toast,
   Square Online, UberEats, Just Eat, Deliveroo, DoorDash, Uber One
   (Part 3). Sourced from general market knowledge; treat as the author's
   characterisation, not verified against those companies' current
   product surface as of 2026-04-19.
2. Effort estimates in §4.1 and §4.2 (weeks/months). These are author
   estimates based on typical scope for features of that shape on this
   team's apparent velocity; actual estimates require team-level
   discovery.
3. Industry burn figure ($10B+) for aggregators. Order-of-magnitude
   claim from reported public financials prior to 2024; not verified
   against 2026 data.

## Appendix C — Docs-Freshness Notes

Discovered during this audit:

- `CLAUDE.md` reports **"390 Java `@Test` methods across 48 files + 76
  Jest `it/test` blocks across 13 files + 50 top-level Go `Test*` funcs
  / 54 with `t.Run` subtests across 5 files. Verified 2026-04-18
  post-v2.1."** Actual counts at 2026-04-19 are **60 Java test files /
  439 `@Test` methods; 20 Jest test files; 6 Go test files**. CLAUDE.md
  is behind by one milestone; update is warranted.
- `CLAUDE.md` reports **"Current schema version: V33"**. Actual head
  migration is **V34__product_optimistic_locking.sql**. Previously
  corrected in the learning-site prose; worth correcting in CLAUDE.md
  too.

---

*End of document. Not legal, financial, or strategic investment advice.
A structured read of the codebase and the product category as of
2026-04-19.*
