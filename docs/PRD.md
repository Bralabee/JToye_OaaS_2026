# J'Toye OaaS — Product Requirements Document

**Version:** v2.3 (milestone open) · **Status:** living document · **Last verified:** 2026-08-19
**Method:** every claim below was measured against `main @ 53d7bd7d` by a two-round supervised
codebase tour (7 domain reports + 6 adversarial verifications). Where a fact was not re-measured
it is marked *(unverified)*. Figures restated from `docs/metrics.json` are gate-enforced.

> This PRD describes the product **as it actually stands**, not as aspiration. Where the built
> reality and an older document disagree, the tour's measured finding wins and the disagreement is
> noted. The forward-looking commercial hypothesis lives in
> `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`; this document does not restate it, it points to it.

---

## 1. Product summary

**J'Toye OaaS ("Operations as a Service")** is a multi-tenant UK retail SaaS platform for
**owner-led food operators** — the go-to-market cohort is **Nigerian and West African takeaways in
one dense London cluster**. It gives a vendor end-to-end control of a single business — storefront,
menu, orders, customers, kitchen fulfilment, marketing, compliance — on shared infrastructure with
per-tenant data isolation.

**Core value (verbatim, `.planning/PROJECT.md`):** *"Vendors can manage their business end-to-end —
from marketing to kitchen fulfilment — through a single platform with real-time visibility."*

**What it explicitly is NOT** (`docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`): a marketplace, a
delivery operator, a payment custodian / merchant-of-record, a POS, an accounting system, or a
compliance *guarantor*. The platform **assists** compliance; the vendor remains legally responsible.

**Milestone status:** v2.3 is deliberately held **open** by owner ruling (2026-08-01) — *"we will
proceed with 2.3 until it's go-to-market ready."* 10 of 14 phases are complete.

---

## 2. Personas

| Persona | Mechanism in code | Built state |
|---|---|---|
| **Vendor owner** (tenant admin) | `GROUP_ADMIN` role, tenant-wide; JIT auto-provisioned on first login | Full dashboard; the onboarding state machine is the sole writer of `Shop.published` |
| **Shop manager / staff** | `shop_staff` (V52): `GROUP_ADMIN` / `SHOP_MANAGER` / `STAFF`; `ShopAccessService` is the single in-tenant authz funnel | Shipped Phase 23. Strict-scoping flag **defaults OFF** (an ungranted user is an implicit tenant-wide admin until the flag is flipped) |
| **Kitchen staff** | KDS (`/dashboard/kitchen`), STOMP realtime, allergen banner + monochrome print block | Shipped. A client receiving a *relayed* event on a real cluster (L6) has never been captured — needs 2+ replicas |
| **Consumer (account)** | `jtoye-customers` Keycloak realm; email-verified; separate token plane (`CustomerJwtVerifier`) | No 2FA, **no verified phone**, no social login (ADR-0005: IdPs deliberately unpopulated; Google groundwork inert, blocked on an HTTPS domain) |
| **Consumer (guest)** | Guest checkout by email; order tracking at `/track` (proof-of-ownership by order number) | Live. Orders can complete **with no payment** — see §6 (#461) |
| **Platform owner / operator** | **Deliberately none** — there is NO cross-tenant human operator identity | DSAR is executed by a background `asSystem` worker looping per-tenant, never a human; onboarding `MANUAL_REVIEW` notifies nobody (#453, P1 — an open design decision, not a bug) |
| **AI agent** | MCP server, 5 tools, scoped machine credentials (`SCOPE_orders:write` etc.), Idempotency-Key contract | Shipped Phases 20/25 |

---

## 3. Product surfaces (what exists on `main`)

### 3.1 Vendor dashboard — `frontend/app/dashboard/` (18 pages, authenticated, tenant-scoped)
`/dashboard` overview; `shops`, `products`, `products/import`, `orders`, `orders/[id]`, `customers`,
`finance`, `marketing`, `kitchen` (KDS), `staff` (shop-scoped grants), `webhooks`, `webhooks/[id]`,
`onboarding`, `onboarding/approvals`, `media/review`, `payments/connect/{return,refresh}`.
All 18 are client-rendered and fetch on mount — defensible (authenticated, not crawlable).
Responsive down to 375px since Phase 23.

### 3.2 Consumer storefront — `frontend/app/shop/**`, `/`, `/track`, `/legal`, `/unsubscribe`
Per-slug branded storefront, cart, UK checkout (delivery address + fees before payment), order
tracking, promotions/announcements, full-text search. **Phase 33 shipped real locality (CUST-01,
2026-08-09):** the landing row renders real published shops (the five fictional vendors were
deleted); shop coordinates come from the OS Code-Point Open GB postcode-centroid dataset (V61, ~1.75M
rows); `GET /public/shops?lat&lon&radiusKm` runs a haversine over a leakproof bounding box; device
geolocation is gesture-gated with granted/denied/far-away/excluded states; distances shown in miles.
**Product limits (recorded, not defects):** ~100 m centroid accuracy; **Great Britain only** (no
Northern Ireland — Code-Point Open excludes it); excluded shops are disclosed, not silently dropped.
`/shop`, `/shop/[slug]`, `/shop/orders`, the landing `/`, and `sitemap.ts` are **server-rendered**
(via `lib/storefront-server.ts`, three-valued `ok | notfound | defer` loader) — the storefront half
of #507 is done.

### 3.3 Public / marketing + legal (12 pages, unauthenticated)
Landing `/`, `/for-operators`, `/business-model-guide`, `/competitive` (a public Flipdish teardown —
a GTM asset that ships *in the product*), `/auth/signin`, `/track`, `/unsubscribe`, and five `/legal`
routes (index, privacy, cookies, retention, accessibility).

### 3.4 Onboarding (Phases 18 + 21)
A state machine is the **sole writer of `Shop.published`**. Of 8 declared gates, **3 are live** —
`BUSINESS_VERIFIED` (Companies House), `FOOD_HYGIENE_RATING` (FHRS/FHIS), `ALLERGEN_DATA_COMPLETE`.
The other five (`FOOD_BUSINESS_REGISTRATION`, `IDENTITY_KYC`, `PAYMENTS_CONNECTED`,
`AGREEMENT_SIGNED`, `MENU_MINIMUM`) are enum-declared placeholders. Per-gate remediation blocks,
correctable data, WITHDRAW exit, and vendor-visible "in review" states shipped in Phase 21.
**Open:** `MANUAL_REVIEW` reaches no actor (#453, P1) — it intersects the no-operator constraint.

### 3.5 Notifications & comms (Phases 22 + 27)
Transactional branded email (Mailhog in dev → SES in prod; **SES domain unverified**, #294);
GDPR consent + one-click unsubscribe (V54; RFC 8058 `List-Unsubscribe` header unwired in k8s, #592);
vendor-registered **HMAC-signed outbound webhooks** with retry / auto-pause / replay + a delivery-log
UI (V55/V56). Phase 27 fixed a live outage where 100% of webhook events had dead-lettered since Phase
22. **Open shipped defect:** #587 — retry backoff tops out at ~127 s, so a receiver outage longer
than ~2 minutes loses events permanently. WhatsApp/SMS is an **inert seam** (`WhatsAppProperties.enabled`
defaults false; the edge inbound parser exists but is provisioned in no environment — #208).

### 3.6 Media (Phases 24 + 27)
Copy-on-write `media_asset` model (V53) with sha256 dedup and ref-counted physical delete; an async
pipeline that stores **only** a validated WebP derivative (magic-byte sniff, decompression-bomb
guard, EXIF strip, thumbnail); a vendor review queue (PENDING / ACTIVE / FAILED / flagged); and
broker-outage durability so a RabbitMQ outage no longer destroys quarantined uploads (V60).

### 3.7 AI surface (Phases 20 + 25)
An MCP server exposing 5 tools — `list_shops`, `list_products`, `read_orders` (read), `create_order`,
`create_customer` (mutating) — over scoped machine credentials and the uniform Idempotency-Key
contract, RLS-proven under a non-superuser role. Plus Ollama/Claude image analysis for products
(the dev stack pulls `gemma3:12b`). pgvector semantic search is an open spike (#207).

### 3.8 Payments (built end-to-end, never executed for real)
Stripe payment intents, refunds (V36) with an order `REFUNDED` state, the full webhook lifecycle,
destination-charge routing for MARKETPLACE orders per ADR-0001, and a VAT ledger (V40). See §6.

---

## 4. Compliance as product (the wedge)

Compliance is not polish here — it is the differentiator vs Flipdish. Each item below is **built**:

- **UK FSA 14-allergen model.** `AllergenCatalog` (bits 0–13), cross-language parity-tested against
  `frontend/types/api.ts`. V63 snapshots the vendor's declared allergen mask + an advisory
  reconciliation flag onto `order_items` **at write time**, so a post-order product edit can no longer
  rewrite what the customer acknowledged. Three states are kept distinct — *declared* / *declared-none*
  / **NOT RECORDED** (historic rows deliberately not backfilled). Checkout shows the set and refuses
  submit without acknowledgement — **client-side only; the server does not enforce it** (recorded
  limitation, §6). Owner ruling (2026-08-16): a NOT-RECORDED order may still be sold; the platform
  states the absence and issues no instruction.
- **Natasha's Law / PPDS labels.** V41 `allergen_spans` / `shelf_life_days` / `durability_type`;
  `ProductLabelService` + OpenPDF generation with emphasised-allergen markup. The offer must NOT
  claim guaranteed compliance. Batch/thermal output is absent (recorded).
- **FHRS.** `FhrsGate` blocks publication below a configured minimum rating (or FHIS Pass) via the
  free FSA API, with a vendor establishment-correction path. **FHRS ratings are NOT yet in the shop
  DTOs** — which blocks both the chosen storefront theme and #544's "good reviews" signal.
- **UK GDPR.** Article-17 erasure with a PII-free SHA-256 `erasure_records` row + audit-table scrub
  (V42); a public, rate-limited, opaque-202, hash-only **DSAR intake** (V62) executed by a background
  per-tenant fan-out worker (no human holds cross-tenant read); Keycloak deprovisioning on offboard
  (V49). **Owner-blocked:** the published DSAR contact `privacy@olajay.co.uk` — its existence and
  monitoring are unverifiable from the repo, and a published contact nobody reads is worse than none.
- **PECR / cookies.** A cookie notice + client-only consent store (no server table by design — a
  pre-identity visitor cannot be keyed in an RLS table); an exhaustive `/legal/cookies` disclosure
  over measured storage keys.
- **WCAG 2.1 AA — partial.** A conformance statement at `/legal/accessibility` with seven **dated**
  exceptions (an overdue date reds the build); a per-PR axe gate over 12 public surfaces (+ a deliberate instrument control) on both viewports;
  `jsx-a11y` rules all at `error`. The vendor dashboard is deliberately outside the claim.

---

## 5. Business model (pointer, not restated)

The authoritative, forward-looking model is `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`
(a decided **hypothesis awaiting paid-market validation**, self-rated low–medium confidence). In brief:

- **Pricing to test:** £39 / location / month + either 0.5% of direct platform sales or a fixed
  £79–£119 / month; £99 minimum assisted onboarding. **None of this exists in code** — there is no
  subscription/billing entity (verified: `TenantPlan` is a 3-value enum whose javadoc says
  "Billing/metering entities are out of scope"), and the Stripe Connect platform-fee rail sits at
  **0 bps** (`STRIPE_PLATFORM_FEE_BPS:0`, armed by no environment).
- **Cohorts:** **Cohort A (takeaway) is the go-to-market**; **Cohort B (catering)** is parallel
  discovery under epic #428 and gates nothing except GTM-02's recorded finding.
- **Market-intel finding** (separate repo, pointer memory): a standalone solo flat-fee business is
  rated **VERY UNLIKELY**; surviving shapes favour usage-based order-flow monetisation — which is what
  the dormant Connect fee rail is for.

---

## 6. What the product does NOT do yet

| Gap | Issues | State |
|---|---|---|
| **Take money.** Orders complete unpaid; the COD fallback (`PublicStorefrontService.java:907`) violates the owner's 2026-08-02 ruling (no pay-on-collection; a single-use payment link to a **verified phone** / social channel) | #461 (P1), #462, #102, #61, #108 | Phase 30 not started. Chain: capture phone → **verify phone (does not exist)** → channel (#208, WhatsApp inert) → Stripe test keys |
| Subscription billing at the published price | #102 remainder | No billing entity; fee rail dormant at 0 bps |
| Run anywhere but a laptop | #99, #100, #101, #294, #297, #299, #300, #304, #592 | Phase 29 paused 9/16 on owner DNS + secrets; k8s ships **zero** monitoring manifests |
| Verified telephone contact | #462 | Platform verifies email, not phone — while the payment design routes on phone |
| WhatsApp ordering channel | #208 | Inert seam; on the P1 critical path as the payment-link delivery mechanism; needs a WhatsApp Business account |
| Catering (Cohort B) / ingredient evidence graph | #428, #427 (P1) | Both epics unphased beyond #427's Wave-1 slice; ADR-0004 accepted but no phase owns it |
| `MANUAL_REVIEW` adjudication | #453 (P1) | No operator exists to notify — a product decision |
| Personalisation of the discovery row (popularity, reviews, promotion, preferences) | #544 (P1) remainder | Fictional row fixed; every personalisation signal still absent |
| Consumer social signup / 2FA | #432, #462 | ADR-0005: recorded decision; Google groundwork inert |
| **Server-side allergen-acknowledgement enforcement** | *(untracked)* | Client gate only; a direct API caller bypasses it. No issue tracks it — file as an *enforcement* gap, not a data-capture request (recording the customer's allergies re-approaches the Article-9 line that deleted `customerAllergenMask`) |
| Semantic food search | #207 | pgvector spike open; search is string-match only |
| Test truthfulness / SSR conversion | #542, #507, #202, #286, #547, #110 | Phase 34 not started (does not gate GTM) |
| Native apps, kiosk, QR/table, POS, aggregator/courier integrations, delivery fleet | — | Deliberately rejected or absent |

---

## 7. Go-to-market posture

**Four blocking commercial decisions** (owner-side, none engineering):

1. **Production domain — sharper than "pick one".** *(measured 2026-08-19, Nominet RDAP + dig)*
   `jtoye.co.uk` **is registered** (2026-07-27, Namecheap, held to 2031) but **parked** (A record →
   Namecheap parking; nothing served). `olajay.co.uk` — where every `FRONTEND_PUBLIC_*` var, all five
   `/legal` pages, and the DSAR contact point — has **zero A records** on its apex and all staging
   subdomains, and **expires 2026-12-31** (~4.4 months; renewal is an untracked owner action). The
   owner bought `jtoye.co.uk` 18 minutes after pointing the repo variables at `olajay.co.uk`.
   > This corrects `docs/CHANGELOG.md` (which says `jtoye.co.uk` was "never registered"). ROADMAP is right.
2. **Hosting target** — undecided; blocks Phase 29 resume.
3. **Stripe test-mode keys** — the running core-java container has `STRIPE_API_KEY`/`STRIPE_WEBHOOK_SECRET`
   set-but-empty, so the live API answers `acceptsCardPayments:false`. A real `pk_test_`/`sk_test_`
   pair does sit in `.env` under different variable names (`STRIPE_PUBLISHABLE_KEY`/`STRIPE_SECRET_KEY`)
   that `application.yml` does not bind — so the commercial "get keys" step appears part-done; what's
   missing is the name mapping + a webhook secret. Gates Phase 30 entirely.
4. **ADR-0002 sign-off** (managed vs in-cluster datastores) — still **Proposed** (2026-07-12); gates
   PITR / DPLY-04. (ADRs 0001/0003/0004/0005 are all Accepted.)

A **fifth in practice:** a WhatsApp Business API account (#208) — same commercial class.

**First-tenant criteria (Phase 32, gated on 29 + 30 + 31 + 33):**
- **GTM-01** — a `v2.3` git tag exists (latest today is `v2.2`), production is deployed, and
  runtime-freshness is green against production.
- **GTM-02** — one real Cohort A vendor completes onboarding → published shop → **first paid order in
  production**, AND #428's catering discovery has produced its recorded finding (either a validated
  wedge or a documented "not it" — the failure mode is not asking).

**Post-launch validation** (the guide's "ninety-day evidence test"): ~10–12 paid pilots; continue only
if ≥10 pay, ≥70% go live, ≥70% activate a first real order, ≤4 h median assisted onboarding,
<30 min/merchant/month support, ≥80% 90-day retention. Explicit stop/pivot triggers accompany it.

---

## 8. Success metrics — declared vs gaps a PRD should own

**Declared today:** the 90-day pilot gates (business); GTM-01/02 (launch); per-phase falsifiable
engineering criteria; a config-declared throttled-mobile Core-Web-Vitals budget for `/`; the
gate-enforced test manifest (`docs/metrics.json`, 3185 logical invocations); 37 CI gate scripts.

**Gaps a future PRD revision must define — none exist in code today:**
- **No product analytics.** Nothing measures vendor activation, orders per tenant, direct-order
  migration off aggregators, consumer conversion, or repeat rate — Prometheus is ops-only.
- **No time-to-first-published-shop target**, despite onboarding being the flagship pain.
- **No price in code** — no billing entity, plan enforcement, or usage metering for £39 + 0.5%.
- **No NPS / support-time capture**, despite the guide's <30-min support gate.
- **No cohort attribution** (A vs B) data model.

---

## 9. Constraints (from `CLAUDE.md`, still binding)

- **Stack is fixed:** Spring Boot 3.5.16 (JDK 25 on Gradle 9.7), Next.js 16.2.12 /
  React 19, Go 1.26, PostgreSQL 15.
- **Multi-tenancy is non-negotiable:** all new features respect RLS + `TenantContext`.
- **All new code requires tests;** the manifest count is a gate, not a guideline.
- **Compose is the canonical local dev/E2E runtime; k8s is the staging/prod deploy target** — both
  kept. Locally it is Compose **XOR** minikube (they share the dev DB), never both.
- **Incremental betterment:** a change that reworks a user-visible surface must preserve the goods it
  displaces or replace them with something strictly better. Regression by omission is a defect even
  with a green suite.

---

## 10. Related documents

- `docs/architecture/ARCHITECTURE.md` — the current-state system architecture (companion to this PRD).
- `docs/architecture/ESSENTIAL_ARCHITECTURE.md` — the one-read distillation.
- `docs/HOW_IT_WORKS.md` — the end-to-end runtime walkthrough.
- `docs/FAILURE_MODES.md` — what is likely to break, and the edge cases.
- `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md` — the commercial hypothesis.
- `.planning/ROADMAP.md`, `.planning/REQUIREMENTS.md`, `.planning/ISSUE-DISPOSITION.md` — phase/issue truth.
