# Phase 19: Full-Frontend Experience Overhaul - Research

**Researched:** 2026-07-11
**Domain:** Next.js 16 App Router multi-shell IA + Spring Boot/Flyway backend support (order snapshot, per-shop scoping, V45 fulfilment schema)
**Confidence:** HIGH (all claims grounded in the running codebase; framework behaviour cross-checked against Next.js official docs via Context7)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
**Landing page (`/`):** Split persona hero. Above the fold: brand statement + two equal doors — "Order food near you" → shop directory, "Run your food business" → `/for-operators`. Shared public header (Shops, For operators, Sign in) + footer (track order, business-model guide, sign in). No blind redirect.

**Dashboard mobile navigation:** Bottom tab bar + drawer. 4 primary tabs (Dashboard, Orders, Products, Kitchen) in the bottom thumb zone; remaining routes behind a "More" drawer. Desktop keeps the existing 256px sidebar. Square/Toast operator pattern.

**Checkout fulfilment:** Delivery + collection. Checkout asks fulfilment type first; address form only for delivery. Fee breakdown (subtotal + delivery + VAT) visible BEFORE payment. Schema via **V45** (`fulfilment_type` + nullable address columns) — **V44 stays reserved for issue #96**.

**Standing directives:** Maintain highest standards; do not regress. Comparator bar: Deliveroo/Just Eat (storefront + public), Square/Toast/Shopify (dashboard). Palette LOCKED to existing orange/emerald/slate (serif/editorial redesign rejected & reverted PR #49/#52 — do not reintroduce). Mobile-first. Production-grade, no "AI-looking" output. Playwright visual validation, images verified rendering (naturalWidth > 0). Do not regress `/dashboard/onboarding` (internal quality reference).

### Claude's Discretion
- Landing hero art direction within the locked palette (photography vs illustration vs gradient). **UI-SPEC decided: gradient-forward, no stock-photo dependency.**
- Exact tab iconography, drawer contents ordering, breakpoint values. **UI-SPEC decided: 4 tabs + More, `md` breakpoint, lucide icons.**
- Whether `ProductRepository`'s `shopId IS NULL` fallback is removed or rendered as deliberate "tenant-wide items" — decide during planning from data reality (24/25 live products have NULL shop_id and need assignment either way).

### Deferred Ideas (OUT OF SCOPE)
- Admin approval queue (tracked by #178).
- Stripe Connect / marketplace payments (tracked by #102).
- No new destructive-action surfaces this phase.
</user_constraints>

<phase_requirements>
## Phase Requirements

IDs `UIX-01..UIX-06` are to be **registered in REQUIREMENTS.md during planning**, mapped verbatim from the ROADMAP Phase 19 success criteria (`.planning/ROADMAP.md:207-213`).

| ID | Description (from ROADMAP success criteria) | Research Support (which findings enable it) |
|----|---------------------------------------------|---------------------------------------------|
| UIX-01 | `/` renders public landing page (no blind redirect) routing 3 personas; shared public header/footer connects `/`, `/for-operators`, `/business-model-guide`, `/track`, `/shop`; zero orphan routes verified by a link-graph test | §Arch Pattern 1 (three-shell), §Arch Pattern 2 (landing page — middleware does NOT gate `/`), §Arch Pattern 8 (link-graph test), §Code Examples |
| UIX-02 | All 11 dashboard routes usable at 390px (sidebar collapses to bottom nav/drawer); Playwright mobile viewport spec passes | §Arch Pattern 3 (mobile tab bar + `sheet` drawer), Playwright `mobile` project already exists (390×844), §Validation |
| UIX-03 | Kitchen + order detail show real product names; `OrderItem.productName` snapshot at creation; "Unknown Product" never renders for an existing product | §Runtime State Inventory (root cause = `PublicStorefrontService:404` never sets productName + backfill), §Code Examples, §Pitfall 3 |
| UIX-04 | Checkout collects delivery address (V45; V44 reserved for #96), shows fee breakdown BEFORE payment; Playwright checkout e2e updated | §Arch Pattern 4 (V45 migration + Envers mirror), §Arch Pattern 5 (fee-before-payment needs shop fetch), §Pitfall 1 (Envers drift), §Pitfall 2 (enum-as-varchar) |
| UIX-05 | Each shop renders its own menu; products assigned `shop_id`; `ProductRepository` `IS NULL` fallback resolved deliberately | §Arch Pattern 6 (single storefront caller, product-form already has shop selector), §Runtime State Inventory, §Open Question 1 |
| UIX-06 | All 15 audit backlog items closed/deferred with reason; 921 logical test invocations stay green; palette locked | §Regression Tripwires, §Validation, all grep counts verified (17 purple, 36 `text-[10px]`, 9 files, 0 `href="/track"`) |
</phase_requirements>

## Summary

Phase 19 is a **convergence, not a redesign**. The UI-SPEC (`19-UI-SPEC.md`, checker-approved 6/6) settles the WHAT down to token values, copy, and grep-verifiable success criteria. This research answers the HOW: exact file touch-points, the backend integration surface, the framework behaviours the plan relies on, and the regression tripwires the planner must assign explicit update tasks for.

The single most load-bearing discovery: **the phase is far less invasive than it looks, because the app's existing wiring already supports most of what's needed.** Middleware does NOT gate routes (no `authorized` callback — the SEC-02 nonce middleware only resolves session + sets CSP), so replacing `app/page.tsx`'s `redirect("/dashboard")` with a real landing page needs **zero middleware changes** and does not break the force-dynamic/CSP-nonce contract. The dashboard products form **already has a shop selector** and `CreateProductRequest.shopId` already exists — the per-shop-menu fix is a data-assignment + one-query-scope change, not a feature build. The "Unknown Product" bug has an exact root cause: `OrderService` correctly snapshots the name (`:148`), but the **guest storefront path (`PublicStorefrontService:404`) never calls `setProductName`** — that one omission, plus a backfill, closes UIX-03. Playwright already ships a `mobile` project at 390×844.

The genuinely new-code surfaces are: (1) a shared public shell (`components/public/*`) + landing page; (2) a mobile bottom-tab-bar + shadcn `sheet` drawer driven off the existing `navigation` array; (3) a **V45 Flyway migration** (`orders.fulfilment_type` + address columns) that MUST mirror to `orders_aud` (the V38 Envers-drift landmine is a documented latent-500 in this exact codebase); (4) checkout fee-before-payment, which requires the checkout page to **fetch the shop** (it currently does not) to read `deliveryFeePennies`/`freeDeliveryThresholdPennies`; and (5) a link-graph regression test. No new npm packages are introduced — shadcn `sheet` is vendored source over the already-present `@radix-ui/react-dialog`.

**Primary recommendation:** Wrap the four marketing routes (`/`, `/for-operators`, `/business-model-guide`, `/track`) in a **`<PublicShell>` component** (lowest churn — no file moves, no URL change, force-dynamic already inherited from root layout) rather than a `(public)` route group; add cross-links to the existing `StorefrontNav` + shop footer instead of forcing the public shell onto `/shop`; fix the guest-order `productName` omission + backfill; ship V45 with the `orders_aud` mirror and a Testcontainers audited-write test that proves no Envers drift.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Public landing page `/` | Frontend Server (RSC) | — | Server-rendered marketing content; force-dynamic already app-wide for CSP nonce |
| Shared public shell (header/footer) | Frontend (client for active-link `usePathname`) | — | Cross-navigation chrome; pure presentation + client route detection |
| Mobile bottom-tab-bar + More drawer | Frontend (client) | — | `usePathname` active state + `sheet` open/close = client interactivity |
| Route protection / persona routing | Frontend Server (`dashboard/layout.tsx` `auth()`) | Middleware (session resolve only) | Existing server-side gate; middleware has no `authorized` callback and must NOT start gating |
| Product-name snapshot | API / Backend (order-creation service) | Database (backfill) | Denormalised snapshot at write-time is a server-side data-integrity concern |
| Per-shop menu scoping | API / Backend (`ProductRepository` query) | Database (shop_id assignment) | Query semantics + data migration; RLS already tenant-scopes |
| Fulfilment type + address persistence | Database (V45) → API (DTO/entity/mapper) → Frontend (form) | — | Schema is source of truth; flows up through the stack end-to-end |
| Fee breakdown before payment | Frontend (compute from shop config) | API (`PublicShopDto` already carries fee fields) | Must mirror server fee logic client-side; final fee still authoritative server-side |
| Colour/type/spacing discipline | Frontend (Tailwind tokens) | — | Pure token migration; no logic |
| Seed/demo-data realism | Database (dev data) / new committed seed | — | See Open Question 1 — no committed product/shop seed exists today |

## Standard Stack

### Core (all already installed — this phase adds NO runtime dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Next.js | 16.2.2 | App Router, RSC, layouts | Existing framework `[VERIFIED: package.json]` |
| React | 19 | UI | Existing `[VERIFIED: package.json]` |
| Tailwind CSS | 3.4.1 | Token-driven styling | Existing; tokens in `tailwind.config.ts`/`globals.css` `[VERIFIED: package.json]` |
| shadcn/ui | new-york/slate | Primitives (vendored source, not npm) | `components.json` present, init satisfied `[VERIFIED: components.json + UI-SPEC]` |
| `@radix-ui/react-dialog` | ^1.1.15 | Underlies `sheet` drawer + existing `dialog` | Already a dep — `sheet` needs no install `[VERIFIED: package.json:18]` |
| `lucide-react` | (installed) | Only icon source | UI-SPEC-locked `[CITED: 19-UI-SPEC.md]` |
| `next/font` Inter | — | Only font | Applied in `app/layout.tsx` `[VERIFIED: app/layout.tsx]` |

### Supporting (backend — all existing)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Flyway | (Spring Boot 3.5.16) | V45 migration | fulfilment_type + address columns + orders_aud mirror |
| Hibernate Envers | (Spring Boot) | `_aud` audit history | `Order`/`OrderItem` are `@Audited` — every new column MUST mirror to `orders_aud` |
| MapStruct | 1.5.5 | DTO↔entity mapping | Add fulfilment/address `@Mapping` lines to `OrderMapper` |
| Testcontainers | 1.21.3 | Real-Postgres + RLS integration tests | Prove V45 audited-write has no Envers drift; prove per-shop scoping |

### New shadcn primitive to add (vendored source, official registry)
| Component | Command | Adds npm dependency? |
|-----------|---------|----------------------|
| `sheet` | `npx shadcn add sheet` | **No** — built on `@radix-ui/react-dialog` which is already installed `[VERIFIED: package.json:18]` |

**Alternatives Considered**
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `<PublicShell>` component wrapper | `(public)/` route group + shared `layout.tsx` | Route group is URL-invariant and DRYer, but requires MOVING `app/page.tsx`, `app/for-operators/page.tsx`, etc. into the group → churns imports/tests. `dynamic` cascades into it from root layout (Context7-verified), so CSP is preserved either way. **Prefer the component wrapper** for lowest churn. |
| Bespoke 2-button fulfilment toggle | shadcn `tabs` | `@radix-ui/react-tabs` ^1.1.13 is already present, but UI-SPEC prefers the bespoke segmented control to keep the new-primitive surface minimal `[CITED: 19-UI-SPEC.md]` |

**Installation:** `cd frontend && npx shadcn add sheet` (verify no lockfile churn beyond `components/ui/sheet.tsx`). No `npm install` required.

**Version verification:** `next` 16.2.2, `react` 19, `@radix-ui/react-dialog` ^1.1.15 all confirmed present in `frontend/package.json` on 2026-07-11. No registry lookups needed — nothing new is installed.

## Package Legitimacy Audit

> **This phase installs no external packages.** The only new UI primitive (`sheet`) is copied as source from the official shadcn registry into `components/ui/` and depends only on `@radix-ui/react-dialog`, already a declared dependency.

| Package | Registry | Disposition |
|---------|----------|-------------|
| shadcn `sheet` (vendored source) | official shadcn registry (not npm) | Approved — no third-party registry, vetting gate not triggered per UI-SPEC Registry Safety |
| `@radix-ui/react-dialog` | npm (already installed ^1.1.15) | Pre-existing — no new install |

**Packages removed due to slopcheck [SLOP] verdict:** none (nothing installed).
**Packages flagged [SUS]:** none. slopcheck not run — not applicable, zero new packages.

## Architecture Patterns

### System Architecture Diagram

```
                          ┌─────────────────────── Next.js 16 App Router (force-dynamic app-wide, CSP nonce) ───────────────────────┐
 Browser request ──▶ middleware.ts (auth() session resolve + per-request CSP nonce; NO route gating — no authorized callback)
                          │
                          ├─ /  ────────────────▶ app/page.tsx  [REPLACE redirect] ──▶ <PublicShell> + Split-persona hero
                          │                                                                    │ doors → /shop , /for-operators
                          ├─ /for-operators ─────▶ <PublicShell> + operator-pitch (token re-skin)
                          ├─ /business-model-guide ▶ <PublicShell> + business-model-guide (token re-skin)
                          ├─ /track ─────────────▶ <PublicShell> + guest lookup (remove RequireCustomerAuth)
                          │                                          │ GET /public/orders/{no}?email=  (IDOR-hardened, AUDIT-W0-02)
                          ├─ /shop (directory) ──▶ app/shop/layout.tsx (StorefrontNav + footer, + NEW cross-links)
                          │      └─ /shop/[slug] ▶ CartProvider + StorefrontNav (cart-aware)
                          │             └─ /checkout ─▶ [NEW] fetch GET /public/shops/{slug} for fee ──┐
                          │                             fulfilment toggle → address (delivery only)    │
                          │                             fee breakdown BEFORE payment                    ▼
                          └─ /dashboard/* (auth() gate) ──▶ DashboardShell                    POST /public/shops/{slug}/orders
                                 ├─ ≥md: 256px Sidebar (unchanged)                            (GuestOrderRequest + fulfilmentType + address)
                                 └─ <md: [NEW] mobile top-bar + bottom-tab-bar(4) + <Sheet> More drawer
                                        (both driven off the SAME `navigation` array in sidebar.tsx)
        ┌──────────────────────────────────────── Spring Boot Core API (RLS + Envers) ───────────────────────────────────────┐
        │ PublicStorefrontService.createGuestOrder  ── [FIX] item.setProductName(product.getTitle())  ◀── UIX-03 root cause    │
        │ ProductRepository.findAvailableByShopOrderedByCategory  ── [SCOPE] drop `OR p.shopId IS NULL` ◀── UIX-05 (1 caller)   │
        │ Order + OrderItem (@Audited)  ── V45 adds fulfilment_type + address → MUST mirror to orders_aud (nullable)           │
        └───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Recommended Structure (new/edited files)
```
frontend/
├── app/
│   ├── page.tsx                       # REPLACE redirect("/dashboard") → landing page
│   └── shop/[slug]/checkout/page.tsx  # EVOLVE: fetch shop, fulfilment toggle, address, fee-before-pay
├── components/
│   ├── public/
│   │   ├── public-header.tsx          # NEW — sticky header + mobile <Sheet> nav
│   │   ├── public-footer.tsx          # NEW — de-orphans /track + /business-model-guide
│   │   └── public-shell.tsx           # NEW — wraps header+footer around children
│   ├── dashboard/
│   │   ├── dashboard-shell.tsx        # EVOLVE — render mobile top-bar + bottom-bar under md:
│   │   ├── sidebar.tsx                # EVOLVE — hidden md:flex; export `navigation` for reuse
│   │   └── mobile-tab-bar.tsx         # NEW — 4 tabs + More <Sheet>, driven off `navigation`
│   ├── storefront/storefront-nav.tsx  # EVOLVE — add "Track order" cross-link
│   └── ui/sheet.tsx                   # NEW — npx shadcn add sheet
└── e2e/link-graph.spec.ts (or jest)   # NEW — orphan-route guard (see Pattern 8)

core-java/src/main/
├── java/uk/jtoye/core/
│   ├── storefront/PublicStorefrontService.java   # FIX setProductName + accept fulfilment/address
│   ├── storefront/dto/GuestOrderRequest.java     # ADD fulfilmentType + address fields
│   ├── order/Order.java + OrderItem              # ADD fulfilmentType + address (@Audited)
│   ├── order/OrderMapper.java (+ OrderDetailDto) # MAP new fields
│   └── product/ProductRepository.java            # SCOPE per-shop query
└── resources/db/migration/V45__order_fulfilment.sql  # NEW — orders + orders_aud + address cols
```

### Pattern 1: Three shells in one app, minimal churn
**What:** Public marketing shell, storefront shell (existing `StorefrontNav`), dashboard shell (existing + new mobile chrome).
**When to use:** This phase.
**How (recommended):**
- Create `components/public/public-shell.tsx` = `<PublicHeader/>{children}<PublicFooter/>`. Import it inside each of `app/page.tsx`, `app/for-operators/page.tsx`, `app/business-model-guide/page.tsx`, `app/track/page.tsx`. **No file moves, no URL change.**
- Do **NOT** force the public shell onto `/shop`. The existing `app/shop/layout.tsx` already gives the directory a header (`StorefrontNav`) + footer. Instead, **add the cross-links** the SPEC requires (`StorefrontNav` gains "Track order"; shop footer gains "For operators"). This closes the IA gaps with less churn than splitting the shared `/shop` layout (which currently serves both `/shop` and `/shop/[slug]`).
- `export const dynamic = "force-dynamic"` in root `app/layout.tsx` **cascades to all children** (Context7-verified: `reduceAppConfig` applies parent configs first, child overwrites) — a `<PublicShell>` component does not touch segment config, so the CSP nonce contract is preserved automatically `[CITED: Next.js docs — reduceAppConfig / route-segment-config]`.

**Anti-pattern:** Moving marketing routes into a `(public)` route group "for cleanliness" — it churns file paths and any test import that references them, for zero URL benefit.

### Pattern 2: Landing page replaces the blind redirect (no middleware change)
**What:** `app/page.tsx` becomes a real server-rendered landing page.
**Key fact:** `middleware.ts` wraps everything in NextAuth `auth()` but has **no `authorized` callback**, so it does not gate any route — its own comment states the broadened matcher "cannot gate public routes". The `/dashboard` gate lives in `app/dashboard/layout.tsx` (`await auth()` → `redirect`). Therefore `/` is already public; replacing the redirect requires **no middleware or auth-config change**, and even signed-in vendors should land here (header offers "Go to dashboard", not an auto-redirect) `[VERIFIED: middleware.ts + app/dashboard/layout.tsx]`.
**Warning:** Keep `app/page.tsx` a Server Component (or at least keep the root `force-dynamic`) so the CSP nonce reaches it — a statically-prerendered `/` would have its bootstrap scripts blocked by the enforcing nonce CSP (this is exactly the #89 failure mode).

### Pattern 3: Mobile bottom-tab-bar + More drawer
**What:** `<md` shows a fixed bottom bar (4 tabs + More) and a slim top brand bar; `≥md` unchanged 256px sidebar.
**How:**
- Export the existing `navigation` array from `sidebar.tsx` (single source of truth) and drive both `sidebar.tsx` and the new `mobile-tab-bar.tsx` from it. Do not fork the list.
- Sidebar becomes `hidden md:flex`; bottom bar is `md:hidden`.
- Bottom bar: `fixed inset-x-0 bottom-0 z-50 h-14 ... pb-[env(safe-area-inset-bottom)]`; main scroll area gets `pb-20` on mobile so content clears it.
- "More" drawer = shadcn `sheet` (side `bottom` or `right`) holding the non-primary routes + theme toggle + sign-out (relocated from the sidebar footer on mobile).
- Active state via `usePathname` (already the sidebar's idiom).
**Accessibility:** every icon-only control (hamburger, sheet close, tabs) needs `aria-label` + a visible `focus-visible:ring` (SPEC contract).

### Pattern 4: V45 Flyway migration (enum-as-varchar + Envers mirror)
**What:** Add `orders.fulfilment_type` + nullable UK address columns; mirror ALL of them into `orders_aud`.
**Codebase convention (verified):** enums are stored as `VARCHAR + CHECK` with `@Enumerated(EnumType.STRING)` — see `orders.status` (V6: `VARCHAR(20)` + `CHECK (status IN (...))`) and `orders.vat_rate` (`@Enumerated(EnumType.STRING) VARCHAR(20)`). Follow this for `fulfilment_type`:
```sql
-- V45__order_fulfilment.sql  (forward-only, idempotent)
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS fulfilment_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY'
        CHECK (fulfilment_type IN ('DELIVERY','COLLECTION')),
    ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city  VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);
-- default 'DELIVERY' backfills historical rows; consider dropping the default afterwards
-- to force explicit inserts (V26 dropped its delivery_fee default for the same reason).

-- CRITICAL: mirror EVERY new column into the Envers audit table (nullable, no CHECK).
ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS fulfilment_type  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS address_line1    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);
```
**RLS impact:** none. `orders`/`orders_aud` already carry ENABLE+FORCE RLS + tenant policies; adding columns to an existing RLS table needs no policy change, and `RlsContractTest` (walks `pg_class`) will stay green because table-level RLS is unchanged.
**No new CHECK landmine:** unlike `orders.status` (V36 had to rewrite the CHECK to add `REFUNDED`), `fulfilment_type`'s two values are complete; no future-value pre-listing needed.

### Pattern 5: Fee breakdown BEFORE payment (checkout must fetch the shop)
**What:** Show subtotal + delivery + VAT + total on Step 1, before order creation.
**Gap:** the checkout page (`app/shop/[slug]/checkout/page.tsx`) **does not fetch the shop today** — it only reads the cart. The delivery fee is currently computed **server-side** in `PublicStorefrontService:424-429` (`deliveryFee = shop.deliveryFeePennies`, waived when subtotal ≥ `freeDeliveryThresholdPennies`) and returned in the `GuestOrderConfirmation`. To surface it *before* payment, the client must fetch `GET /public/shops/{slug}` (returns `PublicShopDto` which already carries `deliveryFeePennies` + `freeDeliveryThresholdPennies`) and mirror the same fee logic client-side.
**Contract:** the client-side number is a *preview*; the server-computed `totalAmountPennies` remains authoritative and must equal it (test this). When `COLLECTION`, delivery fee = £0.

### Pattern 6: Per-shop menu scoping (low blast radius)
**Findings:**
- `findAvailableByShopOrderedByCategory(shopId)` has **exactly one caller**: `PublicStorefrontService:215` (the storefront menu). `AllergenCompletenessGate:71` uses `findByShopId(shopId)` which already has **no** NULL fallback.
- `Product.shopId` is nullable (`@Column(name="shop_id")`, no `nullable=false`); `CreateProductRequest.shopId` **already exists**; the dashboard product form **already has a "Shop Assignment" selector** (`frontend/app/dashboard/products/page.tsx:745`, `selectedShopId`) — but sends `shopId: selectedShopId || undefined`, which is why 24/25 rows are NULL.
**Recommended resolution (deliberate, per CONTEXT discretion):** treat "every product belongs to a shop" as the rule. (1) data-migrate the 24 NULL rows to real shops; (2) drop `OR p.shopId IS NULL` from the storefront query; (3) optionally make the form's shop selector required. This is the direction the ROADMAP success criterion 5 leans toward (tenant-wide items kept only if a product decision says so). See Open Question 1.

### Pattern 7: `/track` guest lookup (remove the sign-in wall)
**What:** Remove `RequireCustomerAuth`; make `/track` a guest lookup (order number + email) against the existing IDOR-hardened `GET /public/orders/{orderNumber}?email=` endpoint (AUDIT-W0-02: `verify` is mandatory, proves ownership). Pre-fill email if a session exists but never require it. Wrap in `<PublicShell>`. Add inbound links (public header/footer + `StorefrontNav` + order-confirmation page) so `grep -rn 'href="/track'` goes 0 → ≥3.

### Pattern 8: Link-graph orphan test (cheapest robust option)
**Recommendation:** a **static Node/Jest test** (no browser, no running stack), not a Playwright crawl. It (a) enumerates routes by walking `app/**/page.tsx` and normalising to URL paths (strip `(groups)`, map `[slug]`→pattern), and (b) greps all `.tsx` for `href="..."` occurrences, then asserts every route has ≥1 inbound `href` from a *different* file. This is the exact method the audit used manually — automating it is a few dozen lines, runs in <1s, and adds a `jest_blocks` count (reconcile `docs/metrics.json`). A Playwright crawl is slower, needs the full docker stack, and is flakier (SSE pages never reach `networkidle` — see Pitfalls). Keep Playwright for *rendering/visual* assertions only.

### Anti-Patterns to Avoid
- **Adding a fourth visual system** on the marketing surface — the fix is token migration onto orange/emerald/slate, not a new palette.
- **`font-bold`/`text-6xl` leaking into the dashboard** — the 700 weight + hero display are a named, closed exception for the two customer-facing brand surfaces only.
- **Making middleware gate routes** to "protect" the landing page — it must stay session-resolve-only; gating lives in `dashboard/layout.tsx`.
- **Adding an order column without mirroring `orders_aud`** — latent HTTP 500 (see Pitfall 1).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Mobile drawer | Custom fixed overlay + focus trap + scroll lock | shadcn `sheet` (Radix Dialog) | Focus management, `aria`, ESC/scroll-lock, portal are solved; matches SPEC |
| Image-with-fallback | New `<img onError>` component | Existing `components/ui/safe-image.tsx` | Already handles null src + onError; SPEC mandates it; Playwright asserts `naturalWidth>0` on its `<img>` |
| Active-nav detection | Manual `window.location` parsing | `usePathname()` (already the sidebar idiom) | SSR-safe, reactive |
| DTO↔entity mapping for new order fields | Manual getters/setters copy | MapStruct `@Mapping` in `OrderMapper` | Compile-time safe; project convention |
| Order-number ownership check on `/track` | New auth wall | Existing `GET /public/orders/{no}?email=` (AUDIT-W0-02) | Already IDOR-hardened + rate-limited; a second impl was the audit finding |
| Delivery-fee waiver logic | New client rule | Mirror `PublicStorefrontService:424-429` exactly | Two implementations drift; assert client==server total |
| UK postcode validation | Fancy library | SPEC regex `^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$` + uppercase-on-blur | Sufficient, no dependency |

**Key insight:** almost everything this phase "needs" already exists in the repo (shop selector, fee logic, IDOR endpoint, SafeImage, mobile Playwright project, `navigation` array). The work is *wiring and convergence*, and the failure mode is re-implementing an existing solution slightly differently (which is how the app got three visual systems in the first place).

## Runtime State Inventory

> This phase includes rename-of-behaviour + data-migration aspects (product-name backfill, shop_id assignment, demo data). All five categories answered.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| **Stored data** | (1) `order_items.product_name = 'Unknown Product'` on orders created via the guest path (root cause: `PublicStorefrontService:404` never calls `setProductName`; the column DEFAULT is `'Unknown Product'` per V30). (2) `products.shop_id` NULL on 24/25 rows. (3) Existing orders have no `fulfilment_type` (post-V45 they default `'DELIVERY'`). | (1) **Backfill** `UPDATE order_items oi SET product_name = p.title FROM products p WHERE oi.product_id = p.id AND oi.product_name = 'Unknown Product'` — run tenant-scoped/under RLS, and this also produces an audited... **no**: a raw UPDATE does not trigger Envers, so also decide whether `order_items_aud` needs a matching history row (V30's backfill did not write aud — acceptable). (2) **Data-migrate** NULL `shop_id` → real shops. (3) code edit (default) + optional backfill. |
| **Live service config** | None. No external UI-held config (n8n/Datadog/Tailscale) references any string this phase changes. | None — verified: phase touches app code + DB only. |
| **OS-registered state** | None. No Task Scheduler/systemd/pm2 registrations embed changed strings. | None — verified. |
| **Secrets/env vars** | None renamed. CSP/Keycloak/Stripe env var names unchanged. | None. |
| **Build artifacts / installed packages** | `sheet` adds `components/ui/sheet.tsx` (source, committed). `docs/metrics.json` counts (`jest_blocks`, `playwright_blocks`, `java_test_methods`, `schema_version`) become stale the moment tests/migrations are added. `core-java/build-local/` holds a stale copy of `V13` (build output — ignore). | Regenerate `docs/metrics.json` via `scripts/docs-freshness.sh` (write mode) after test/migration additions — the `docs-freshness` CI gate fails on drift. Bump `schema_version` 43 → 45. |

**The canonical question — after every file is updated, what runtime state still holds old values?** (a) the dev Postgres volume's `order_items.product_name='Unknown Product'` rows (backfill), and (b) the 24 NULL `shop_id` rows (assign). Both are **data migrations distinct from the code fix** and must be separate plan tasks. Note the DB is a **shared dev volume** (see Open Question 1) — there is no committed product/shop/order seed, so "backfill/re-seed" is a live-data operation unless a committed seed is introduced.

## Common Pitfalls

### Pitfall 1: Envers audit-column drift → latent HTTP 500 (HIGHEST RISK)
**What goes wrong:** Adding `fulfilment_type`/address columns to `orders` (or any `@Audited` entity) without adding matching **nullable** columns to `orders_aud` makes the *next audited write* fail at the audit INSERT with `column ... does not exist` → HTTP 500 on order create/update.
**Why it happens:** This exact defect already bit the repo — V38 was a dedicated fix for `shops`/`order_items` audit-column drift (the `product_name` case surfaced as a "LATENT 500 on the next audited write"). `RlsContractTest` does **not** catch it (it checks RLS, not columns). Testcontainers unit tests that don't perform a real audited write won't catch it either.
**How to avoid:** In V45, mirror every new column into `orders_aud` (nullable, no CHECK, match base type). Add a **Testcontainers integration test that creates an order after V45** (a real audited write) and asserts success — this is the only thing that proves no drift.
**Warning signs:** order-create works in a `@DataJpaTest` but 500s against real Postgres; `revinfo`/`_aud` in the stack trace.

### Pitfall 2: enum column type — follow VARCHAR+CHECK, not native PG enum
**What goes wrong:** Using a native Postgres `ENUM` type for `fulfilment_type` breaks the `@Enumerated(EnumType.STRING)` mapping and makes later value additions painful.
**How to avoid:** `VARCHAR(20) + CHECK (... IN (...))` — the established convention (V6 rewrote `orders.status` off a native enum onto varchar precisely for Hibernate compatibility).

### Pitfall 3: "Unknown Product" — fixing only one path
**What goes wrong:** Patching `OrderService` (which already sets the name at `:148`) while leaving the actual bug — the guest storefront path `PublicStorefrontService:404` — unfixed. The guest path is the primary customer order flow, so it produces the "Unknown Product" rows.
**How to avoid:** Add `item.setProductName(product.getTitle())` in `PublicStorefrontService` right after `new OrderItem(...)` (there are exactly TWO creation paths in core-java; both must set the name). No edge-go/WhatsApp/Sync path constructs `OrderItem` (verified: `SyncController` doesn't; edge-go only forwards). Add a regression test proving a freshly created guest order carries real names, and a Playwright assert that no "Unknown Product" appears on seeded kitchen/order-detail.

### Pitfall 4: jest does NOT type-check
**What goes wrong:** `npm test` (jest) passes while a TypeScript type error ships (bit PR #130). 
**How to avoid:** run `npm run build` (tsc via `next build`) for any touched TS — there is no separate `typecheck` script.

### Pitfall 5: SSE/live pages never reach `networkidle`
**What goes wrong:** Playwright `waitForLoadState('networkidle')` hangs on kitchen/order pages (open SSE/STOMP connection).
**How to avoid:** use `domcontentloaded` (or explicit element waits) on `/dashboard/kitchen`, `/dashboard/orders/[id]`, `/track` (15s auto-refresh). This is a recorded project learning.

### Pitfall 6: docs-freshness gate on count drift
**What goes wrong:** adding tests/migrations without updating `docs/metrics.json` fails the `docs-freshness` CI gate (and the CLAUDE.md count narrative).
**How to avoid:** regenerate metrics after test/migration additions; bump `schema_version` to 45. Counts are grep-derived over tracked files (`git ls-files` + regex) — a merge conflict on `metrics.json` is resolved by re-running the generator (it is the arbiter).

### Pitfall 7: images must be verified rendering, not just present
**What goes wrong:** "build clean" ≠ images render. `SafeImage` renders a fallback div silently when an image fails.
**How to avoid:** Playwright must assert `naturalWidth > 0` on populated `<img>` (SafeImage uses a plain `<img>`, so this works). Any hero/door imagery must pass this or fall back to the gradient.

### Pitfall 8: canonical dev port is 3000 (mostly) — but scripts default to 3100
**What goes wrong:** Port confusion. `package.json` `dev` script sets `NEXTAUTH_URL=http://localhost:3100`, but `playwright.config.ts` `baseURL` defaults to `http://localhost:3000`, and recent learnings say frontend canonical port is 3000 (MCP historically held 3000). 
**How to avoid:** run E2E against whatever the running stack actually binds; don't hardcode. Rebuild ALL docker containers after code changes before E2E (project mandate). Confirm the port before asserting.

## Code Examples

### UIX-03 — guest-order product-name snapshot (the one-line root-cause fix)
```java
// core-java/.../storefront/PublicStorefrontService.java  (~line 404, inside the item loop)
OrderItem item = new OrderItem(
        product.getId(),
        itemReq.getQuantity(),
        product.getPricePennies()); // server-side price
item.setTenantId(tenantId);
item.setProductName(product.getTitle());   // ◀── ADD THIS — mirrors OrderService:148
order.addItem(item);
```

### UIX-05 — scope the storefront menu query (drop the NULL bleed)
```java
// core-java/.../product/ProductRepository.java:22  — after shop_id assignment migration
@Query("SELECT p FROM Product p WHERE p.available = true AND p.shopId = :shopId "
     + "ORDER BY p.category NULLS LAST, p.displayOrder ASC, p.title ASC")
List<Product> findAvailableByShopOrderedByCategory(@Param("shopId") UUID shopId);
// Only caller: PublicStorefrontService:215. AllergenCompletenessGate uses findByShopId (already scoped).
```

### Landing page — public shell wrapper (no route move)
```tsx
// frontend/app/page.tsx  — REPLACES redirect("/dashboard")
import { PublicShell } from "@/components/public/public-shell"
export default function Home() {
  return (
    <PublicShell>
      {/* split-persona hero: two equal doors → /shop and /for-operators */}
    </PublicShell>
  )
}
```

### Mobile chrome switch (breakpoint md, sidebar untouched on desktop)
```tsx
// components/dashboard/dashboard-shell.tsx
<div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
  <Sidebar />                                {/* becomes hidden md:flex internally */}
  <main className="flex-1 overflow-y-auto">
    <MobileTopBar className="md:hidden" />
    <div className="container mx-auto p-4 sm:p-8 pb-20 md:pb-8">{children}</div>
  </main>
  <MobileTabBar className="md:hidden" />     {/* fixed bottom, 4 tabs + More <Sheet> */}
</div>
```

## State of the Art

| Old Approach | Current Approach | When | Impact |
|--------------|------------------|------|--------|
| `/` blind `redirect("/dashboard")` → login wall | Public persona-routing landing page | This phase | First front door for customers/prospects |
| CSP via static `next.config.mjs` headers | Per-request nonce in `middleware.ts` + app-wide `force-dynamic` | PR #166 (#89) | Do NOT break — landing page must stay dynamic |
| Checkout: fee deferred to a footnote | Fee breakdown before payment | This phase (V45) | Requires checkout to fetch the shop |

**Deprecated/outdated:** none introduced. `app/fonts` Geist woffs stay unwired (Inter only).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The 24/25 NULL-`shop_id` products and the "Unknown Product" order rows live in a **shared dev Postgres volume** with **no committed product/shop/order seed** (only V13 seeds tenants). | Runtime State / Open Q1 | If a committed seed DOES exist elsewhere, the "re-seed" task target differs. Grep found none in SQL/Java/scripts. |
| A2 | The right per-shop resolution is "every product belongs to a shop" (assign + drop NULL fallback), not "tenant-wide items are a feature". | Pattern 6 / Open Q1 | If a product decision wants tenant-wide items, the query keeps a labelled NULL branch instead of dropping it — different UI + query. CONTEXT marks this Claude's discretion, to decide at planning from data reality. |
| A3 | A raw SQL backfill of `order_items.product_name` need not write `order_items_aud` history (matching V30's one-time backfill precedent). | Runtime State | If audit completeness policy requires it, add an `_aud` write; low risk (V30 set the precedent). |
| A4 | No edge-go/WhatsApp/Sync path constructs `OrderItem` (so only 2 Java paths need the name fix). | Pitfall 3 | Verified via grep (SyncController has no OrderItem/createOrder; edge-go only forwards requests). Low risk. |
| A5 | Adding order columns needs no RLS policy change and won't trip `RlsContractTest`. | Pattern 4 | Verified: `RlsContractTest` walks table-level RLS, not columns; `orders` already ENABLE+FORCE. |

## Open Questions (RESOLVED — planning decisions 2026-07-11)

> RESOLVED at planning: OQ1 → option (a): committed dev-profile `DemoDataSeeder` (plan 19-02), NOT a Flyway migration; live dev DB aligned via the same seeder. OQ2 → full end-to-end wiring (plans 19-01 backend → 19-06 checkout → 19-07 detail render); display-only explicitly rejected. OQ3 → DEFERRED with reason: collection-only-shop forcing is outside the 15-item backlog; the toggle ships with Delivery default + Collection selectable for all shops; deferral documented in 19-09's backlog-closure notes.

1. **Where does demo/seed data live, and how is "re-seed realistically" delivered?**
   - What we know: the only committed seed is `V13__seed_default_tenants.sql`. No committed SQL/Java/script seeds shops, products, orders, or customers. The 25 products / 10 shops / orders the audit saw are in the **shared dev Postgres volume**, created via API/UI during development.
   - What's unclear: whether the planner should (a) author a **new committed idempotent demo-seed** (SQL migration `V4x` or a dev-profile seeder — durable, testable, re-runnable) or (b) do a **one-off live-DB cleanup** (fast, but not reproducible and invisible to CI).
   - Recommendation: prefer (a) — a dev/demo seed that is NOT a production Flyway migration (keep it profile-scoped or a script) so it doesn't ship to prod and doesn't perturb Testcontainers fixtures/golden files. Confirm with the user; this is the biggest scoping ambiguity in the phase.

2. **Does the checkout API round-trip need address persisted, or is it display-only this phase?**
   - What we know: SPEC says "Order-creation payload gains `fulfilmentType` and (for delivery) `address`", and V45 persists them. So end-to-end: `GuestOrderRequest` (+ `CreateOrderRequest`?) → service → `Order` entity → `orders`/`orders_aud`. `OrderMapper`/`OrderDetailDto` should expose them so the dashboard order-detail can show the delivery address.
   - Recommendation: wire it fully end-to-end (DTO → entity → mapper → detail view) — a half-wired address (collected but not persisted/shown) would be a new "shallow flow" of exactly the kind this phase exists to kill.

3. **Collection-only shops / minimum-order interplay with the fulfilment toggle?** Out of the 15-item backlog's explicit scope, but the toggle defaults to Delivery. If a shop has no delivery (fee/threshold semantics), consider defaulting/forcing Collection. Flag for the planner; likely defer unless trivial.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js / npm | Frontend build, jest | assumed ✓ (repo builds in CI) | — | — |
| `@radix-ui/react-dialog` | shadcn `sheet` | ✓ | ^1.1.15 | — (no install needed) |
| Playwright | E2E + mobile viewport spec | ✓ (config present, `mobile`+`desktop` projects) | @playwright/test 1.59.1 | — |
| Docker + full stack (core-java :9090, Keycloak :8085, Postgres, MinIO) | Live E2E, image `naturalWidth` checks, order flows | required at E2E time | — | Unit/integration (jest + Testcontainers) cover most; live E2E is the project mandate before sign-off |
| Testcontainers (Docker) | V45 audited-write + per-shop scoping integration tests | required | 1.21.3 | none — these tests need real Postgres+RLS |
| Stripe test keys | Full checkout-to-payment E2E | ✗ (empty in env — known blocker) | — | COD path + config-only Stripe verification; assert fee breakdown pre-payment without a live card |

**Missing dependencies with no fallback:** none block the code work. **With fallback:** live Stripe payment E2E — verify the fee-before-payment UI + COD confirmation path instead of a live card charge (consistent with the standing #61 Stripe-keys blocker).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Frontend unit | Jest 29.7.0 + @testing-library/react — `cd frontend && npm test` |
| Frontend typecheck | `cd frontend && npm run build` (tsc; jest does NOT typecheck) |
| E2E | Playwright 1.59.1 — `cd frontend && npx playwright test` (projects: `mobile` 390×844, `desktop` 1440×900; baseURL `http://localhost:3000`) |
| Backend unit | JUnit 5 — `cd core-java && ./gradlew test` |
| Backend integration | Testcontainers (real Postgres+RLS) — `cd core-java && ./gradlew integrationTest` |
| Count gate | `scripts/docs-freshness.sh` → `docs/metrics.json` (regenerate after adds; bump schema 43→45) |

### Phase Requirements → Test Map
| Req | Behaviour | Test Type | Automated Command | File Exists? |
|-----|-----------|-----------|-------------------|--------------|
| UIX-01 | `/` renders landing (no redirect); doors link `/shop` + `/for-operators` | unit | `npm test -- app/__tests__/landing.test.tsx` | ❌ Wave 0 |
| UIX-01 | Zero orphan routes (every route ≥1 inbound href) | static link-graph | `npm test -- __tests__/link-graph.test.ts` | ❌ Wave 0 (new — see Pattern 8) |
| UIX-01 | Public header/footer visible + cross-links on marketing pages | e2e (domcontentloaded) | `npx playwright test link-graph.spec` or extend `storefront-flows` | ❌ Wave 0 |
| UIX-02 | All 11 dashboard routes usable at 390px; no one-word-per-line titles; sidebar hidden, bottom bar shown | e2e (mobile project) | `npx playwright test --project=mobile dashboard-mobile.spec` | ❌ Wave 0 |
| UIX-02 | `/dashboard/onboarding` visually unchanged | e2e (mobile+desktop) | `npx playwright test onboarding` | ⚠️ extend existing onboarding coverage |
| UIX-03 | Guest order snapshots real productName | integration (Testcontainers) | `./gradlew integrationTest --tests "*PublicStorefront*"` | ⚠️ extend `PublicStorefrontServiceTest`/integration |
| UIX-03 | No "Unknown Product" on seeded kitchen/order-detail | unit + e2e | `npm test -- app/dashboard/kitchen`; `npx playwright test kitchen-flow` (domcontentloaded) | ⚠️ update `kitchen/__tests__/page.test.tsx`, `OrderDetailPanel.test.tsx`, `kitchen-flow.spec.ts` |
| UIX-04 | V45 audited order write succeeds (Envers no-drift) | integration (Testcontainers) | `./gradlew integrationTest --tests "*OrderFulfilment*"` | ❌ Wave 0 (critical — see Pitfall 1) |
| UIX-04 | Fulfilment toggle + conditional address + postcode validation | unit | `npm test -- app/shop/[slug]/checkout` | ❌ Wave 0 |
| UIX-04 | Fee breakdown shown before payment; client total == server total | unit + e2e | `npm test`; `npx playwright test storefront-flows` | ⚠️ update `storefront-flows.spec.ts` |
| UIX-05 | Shop A menu ≠ Shop B menu; no NULL bleed; no duplicate rows | integration | `./gradlew integrationTest --tests "*ProductRepository*"` + storefront e2e | ⚠️ update FTS/product tests + `storefront-flows` |
| UIX-06 | Palette locked; discipline grep gates | static grep (CI or test) | `grep -rn "purple-" frontend/app frontend/components` == 0; `text-[10px]` == 0; marketing hex == 0; `href="/track"` ≥3 | ❌ add as assertions |
| UIX-06 | 921 logical invocations stay green + metrics reconciled | full suites + gate | all-suite green; `scripts/docs-freshness.sh` | existing |

### Sampling Rate
- **Per task commit:** the narrowest command touching the change (`npm test -- <file>` or `./gradlew test --tests <Class>`), plus `npm run build` for any TS.
- **Per wave merge:** full `npm test` + `./gradlew test` + affected Playwright project; regenerate `docs/metrics.json`.
- **Phase gate:** full jest + `./gradlew test integrationTest` green, Playwright `mobile`+`desktop` green against a freshly-rebuilt docker stack, all UIX grep gates satisfied, before `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] `frontend/__tests__/link-graph.test.ts` — orphan-route guard (UIX-01)
- [ ] `frontend/e2e/dashboard-mobile.spec.ts` (or extend) — 11 routes at 390px (UIX-02)
- [ ] `core-java/.../OrderFulfilmentAuditIntegrationTest` — audited order write post-V45 proves no Envers drift (UIX-04, Pitfall 1)
- [ ] `frontend/app/__tests__/landing.test.tsx` — landing renders + door links (UIX-01)
- [ ] checkout unit test for fulfilment toggle + postcode + fee preview (UIX-04)
- [ ] `sheet` component added (`npx shadcn add sheet`) before mobile-tab-bar work
- [ ] Regenerate `docs/metrics.json` (schema 43→45, new jest/playwright/java counts) — else `docs-freshness` fails

## Regression Tripwires (existing tests this phase will break — assign explicit update tasks)

| Test / gate | Why it changes | Action |
|-------------|----------------|--------|
| `frontend/e2e/storefront-flows.spec.ts` | checkout adds fulfilment/address/fee-before-pay; per-shop menus | update assertions |
| `frontend/e2e/kitchen-flow.spec.ts` | "Unknown Product" → real names; badge/elapsed-time fixes | update assertions (domcontentloaded, not networkidle) |
| `frontend/app/dashboard/kitchen/__tests__/page.test.tsx` | product-name rendering + `PREPARING` purple→amber + elapsed cap | update |
| `frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx` | product-name + status color | update |
| `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx` | shell now renders mobile bars | update / add mobile assertions |
| `frontend/components/marketing/__tests__/{operator-pitch,business-model-guide}.test.tsx` | hardcoded-hex → token re-skin | update (assert tokens, not hex) |
| `frontend/__tests__/header-snapshot.test.ts` | any header markup change (public shell / CSP-adjacent) | regenerate snapshot `-u` if intended |
| `frontend/__tests__/csp-no-violations` (Playwright `csp-no-violations.spec.ts`) | new landing page + sheet must not add inline-script CSP violations | run + keep green (nonce-safe) |
| `core-java/.../order/OrderServiceTest.java` | if order-creation signature gains fulfilment/address | update fixtures |
| `core-java/.../storefront/PublicStorefrontServiceTest.java` (+ integration) | productName now set; fulfilment/address accepted | update + add name-snapshot assertion |
| ProductRepository / FTS tests (#96, incl. NULL `search_vector` tripwires) | per-shop scoping query change | update to seed `shop_id`; keep FTS plans pinned |
| `frontend/__tests__/shop/cart.test.tsx`, `orders-filter.test.tsx` | seed-name realism (`Jollof`/`Test Shop`) referenced in fixtures | update fixture strings if demo names change |
| `docs/metrics.json` + CLAUDE.md count narrative (`docs-freshness` CI) | test/migration counts + schema version | regenerate; bump schema 45 |

## Security Domain

> `security_enforcement` is not set to `false` in config → treated as enabled. This is primarily a UI/IA phase; the only new input surface is the checkout address.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (no auth changes) | Existing NextAuth/Keycloak untouched; middleware stays session-resolve-only |
| V3 Session Management | no | `/track` uses existing IDOR-hardened guest endpoint (AUDIT-W0-02), no new session |
| V4 Access Control | yes (light) | Per-shop scoping is tenant-internal (RLS already enforces tenant); ensure the storefront query stays tenant-scoped |
| V5 Input Validation | **yes** | New address fields: server-side `@Size`/`@NotBlank`(delivery) on `GuestOrderRequest`; UK postcode regex; never trust client `fulfilmentType` for fee (recompute server-side) |
| V6 Cryptography | no | none |
| V14 Config (CSP) | **yes** | Do not break the per-request nonce CSP: landing page + `sheet` must be nonce-safe; keep `force-dynamic`; run `csp-no-violations` |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Client tampering fulfilment/fee to underpay | Tampering | Recompute delivery fee + total server-side (`PublicStorefrontService`); client value is display-only |
| Address injection / oversized input | Tampering/DoS | Bean Validation `@Size` on all new columns; DB column length caps (VARCHAR) |
| Cross-tenant product leak via scoping change | Info Disclosure | RLS already scopes `products` to tenant; new query only narrows within-tenant by `shop_id` |
| CSP regression on new pages | Tampering (XSS surface) | nonce middleware + force-dynamic; `csp-no-violations` Playwright gate |
| `/track` email enumeration | Info Disclosure | Reuse mandatory-`verify` endpoint (AUDIT-W0-02) + existing public rate limiter (#88) |

## Sources

### Primary (HIGH confidence)
- Running codebase (grep/read, 2026-07-11): `middleware.ts`, `app/layout.tsx`, `app/page.tsx`, `app/dashboard/layout.tsx`, `app/shop/layout.tsx`, `components/dashboard/{dashboard-shell,sidebar}.tsx`, `components/storefront/{storefront-nav,cart-provider}.tsx`, `app/shop/[slug]/checkout/page.tsx`, `components/ui/safe-image.tsx`, `playwright.config.ts`, `next.config.mjs`, `package.json`, `components.json`.
- Backend: `order/{Order,OrderItem,OrderService,OrderMapper}.java`, `storefront/PublicStorefrontService.java` (:404 no-setProductName; :424 fee logic), `storefront/dto/GuestOrderRequest.java`, `product/{ProductRepository,Product}.java` + `dto/CreateProductRequest.java`, migrations `V6/V26/V30/V38/V43`.
- Context7 `/vercel/next.js` — route groups are URL-invariant; `reduceAppConfig` cascades `dynamic` from parent layouts to children (force-dynamic inherited by any `(public)` group).
- `.planning/ROADMAP.md:203-215` (Phase 19 goal + 6 success criteria → UIX-01..06); `docs/metrics.json`; `scripts/docs-freshness.sh`.
- Grep verification (all matched SPEC exactly, 2026-07-11): purple 17, `text-[10px]` 36 across 9 files, `href="/track"` 0, marketing hex in 2 files, jest blocks 130.

### Secondary (MEDIUM confidence)
- `.planning/STATE.md` accumulated learnings (SSE networkidle, jest-no-typecheck, docs-freshness arbiter, port 3000/3100, Stripe-keys blocker #61) — corroborated by CLAUDE.md/MEMORY.md.

### Tertiary (LOW confidence)
- Seed-data provenance (Open Q1): inferred absence of a committed product/shop seed from exhaustive grep; not positively confirmed by a seed-run trace.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — nothing new installed; all versions read from `package.json`.
- Architecture (three shells, landing, mobile bars, V45): HIGH — file-level touch-points verified; framework behaviour Context7-confirmed.
- Backend fixes (productName, scoping, Envers): HIGH — exact lines + a prior identical drift fix (V38) as precedent.
- Seed/demo data: MEDIUM-LOW — no committed seed found; delivery mechanism is an open decision (Open Q1).

**Research date:** 2026-07-11
**Valid until:** 2026-08-10 (stable stack; re-verify grep counts + `docs/metrics.json` at plan time — they drift with any interim commit)
