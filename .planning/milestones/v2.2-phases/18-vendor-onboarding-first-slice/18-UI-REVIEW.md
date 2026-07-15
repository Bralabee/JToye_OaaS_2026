# Full-Frontend UI Audit

**Audited:** 2026-07-11
**Baseline:** `18-UI-SPEC.md` for the onboarding slice specifically; Deliveroo/Just Eat for the B2C storefront and public pages; Square/Toast/Shopify admin for the vendor dashboard, everywhere else.
**Scope:** All 23 routes across 3 surfaces (Public/Marketing, B2C Storefront, B2B Dashboard). This report lives in the phase-18 directory for GSD bookkeeping only — it audits the **entire frontend**, not just vendor onboarding.
**Screenshots:** Captured live via Playwright against the running dev stack (`http://localhost:3000`, core API `:9090`, Keycloak `:8085`). 49 PNGs at 1280×900 (desktop) and 390×844 (mobile) saved to `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/43b38d22-44cc-4260-a3b4-f7416e832cf1/scratchpad/ui-audit/` (scratchpad — not committed; filenames referenced throughout as evidence).
**Data used:** Published shops `valid-shop-1783622803` and `jollof-express-brixton-52563e05` (tenant `00000000-0000-0000-0000-000000000001`), order `ORD-00000000-20260709-DCFB3F82`, dashboard order `13fa7a1e-eada-44a2-b21b-31217a35b7cb`, vendor login `admin-user` (Keycloak, `jtoye-dev` realm).

---

## Verdict

The user's assessment is correct and this audit substantiates it with evidence, not vibes. The three surfaces were built as **three separate products that happen to share a git repository**: three different visual languages, zero cross-navigation, and — critically — the flows that "work" break at the exact moments that matter (checkout can't take a delivery address, the kitchen display can't show what to cook, the entire admin dashboard is unusable on a phone). One genuine bright spot exists and is called out explicitly below so the audit stays calibrated: the Phase 18 vendor-onboarding slice (`/dashboard/onboarding`) matches its design contract almost line-for-line and should be the template other dashboard pages are brought up to, not the exception.

**Whole-app score: 42/72 (58%)** (Marketing 12/24 · Storefront 15/24 · Dashboard 15/24 — see per-surface tables below).

---

## Constraints the remediation must respect

- **Keep the existing orange/emerald/slate food-delivery palette.** A prior Warm-Editorial/serif redesign (PR #49) was explicitly rejected by the user as "newspaper, not food delivery" and reverted (PR #52). No serif display type, no editorial/newspaper aesthetic, in any remediation — including on the marketing surface, which currently uses two *other* hand-rolled palettes that also need to converge, not be replaced with a third new one.
- **No functional regressions.** 918 pre-existing + logical test invocations (currently 921 per `docs/metrics.json`) must stay green. Any fix touching `OrderItem`/`ProductRepository`/`Sidebar`/`DashboardShell` needs accompanying tests, not just a visual patch.
- **Mobile-first.** This is a standing project mandate (`feedback_ui_quality.md`), not a nice-to-have — and it is the single most violated constraint in the current build (see Dashboard Pillar 6 and IA below).
- **Design system stays shadcn/ui + Radix + Tailwind + CSS-variable tokens** (`components.json`, `tailwind.config.ts`, `app/globals.css`) — the dashboard and storefront already do this correctly; the fix is to stop the marketing surface from hardcoding hex values that bypass the token layer, not to introduce a new one.

---

## Information Architecture (unscored)

This is the user's #1 complaint and the audit confirms it in the code, not just the screenshots.

**The navigation graph is disconnected by construction.** There are exactly two nav components in the entire app (`components/dashboard/sidebar.tsx`, `components/storefront/storefront-nav.tsx`) and neither links to the other, nor to the marketing surface:

```
grep -rn 'href="/dashboard'  app/shop app/for-operators app/business-model-guide app/track components/storefront components/marketing
  → components/marketing/operator-pitch.tsx:72  (the ONE exception — "Start your application" CTA)
grep -rn 'href="/shop\|href="/for-operators\|href="/business-model-guide\|href="/track'  app/dashboard components/dashboard
  → (zero results)
grep -rln 'href="/"'  app components
  → (zero results — nothing in the app links to the root/home route at all)
```

**Orphan pages, confirmed by inbound-link grep, not assertion:**
- `/business-model-guide` — the only references anywhere in the codebase are its own `page.tsx` import, its own test file, and its own self-referential `/business-model-guide.pdf` print link. **Zero inbound navigational links from any other page.**
- `/track` — **zero matches anywhere** for `href="/track"`. The route exists, renders, and is completely unreachable by clicking anything in the app.
- `/for-operators` — exactly one inbound link, from `business-model-guide.tsx:151` — which is itself unreachable. So `/for-operators` is reachable only if a visitor already has the URL, matches the confirmed defect.

**The entry point is a login wall, not a landing page.** `app/layout.tsx` (root) → `/` (`redirect("/dashboard")`, `frontend/app/page.tsx`) → `dashboard/layout.tsx` has no session → `redirect("/auth/signin")`. Screenshot `home-root--desktop.png` / `auth-signin--desktop.png`: a floating white card with a house icon, "J'Toye OaaS", and a single "Sign in with Keycloak" button, centred in ~900px of otherwise empty page. There is no explanation of what the product is, no path to the storefront, no path to the marketing pitch, before a first-time visitor is asked to authenticate. A prospective vendor who lands on the bare domain has no route to `/for-operators` at all unless someone hands them the URL directly.

**Three unrelated visual identities bolted together with no shared chrome**, confirmed by grep of the actual hex values in use:
- Dashboard/storefront: token-driven, `--primary` blue-600, slate-900 chrome, orange/emerald storefront accent (per `tailwind.config.ts` / `app/globals.css`).
- `components/marketing/operator-pitch.tsx` (`/for-operators`): hardcoded navy `#211c36`, orange `#f26522`, yellow `#ffdf7e` — 17-20 occurrences each, zero use of `--primary` or Tailwind's `orange-*`/`blue-*` tokens.
- `components/marketing/business-model-guide.tsx` (`/business-model-guide`): a **third**, different hardcoded palette — teal `#122c33`, rust `#b75d2c`, olive `#e4eecd` — again bypassing the token system entirely.

Each of the three marketing/storefront/dashboard surfaces also renders its own wordmark treatment independently ("J'Toye / OaaS Platform" sidebar, "J'Toye" orange-badge storefront header, "J'TOYE / OPERATOR PILOT" and "J'TOYE / FIELD GUIDE" small-caps mastheads) with no shared header component between them.

**Net effect:** there is no way to discover this product's other surfaces from within itself. A vendor who signs in never sees the pitch that sold them; a customer browsing `/shop` never sees a path to become a vendor; a prospect on `/for-operators` who bounces has no path to `/business-model-guide` (the actual economics doc) unless they scroll to its own footer CTA, and vice versa is one-directional.

---

## Pillar Scores by Surface

### Surface 1 — Public / Marketing (`/`, `/for-operators`, `/business-model-guide`, `/track`, `/auth/signin`)

| Pillar | Score | Key Finding |
|--------|-------|-------------|
| 1. Copywriting | 3/4 | `/for-operators` and `/business-model-guide` copy is genuinely sharp and specific (not generic AI output) — but it's gated behind orphaned routes, and the actual front door (`/auth/signin`) is bare boilerplate. |
| 2. Visuals | 1/4 | No landing page exists; the entry experience is a bare login card. Three incompatible visual systems across the surface's own 3 content pages. |
| 3. Color | 1/4 | Two of three marketing pages hardcode hex values (17-20 occurrences each) instead of using `--primary`/Tailwind tokens — a wholesale bypass of the design system, not just accent overuse. |
| 4. Typography | 3/4 | Internally disciplined per-page (large bold headline system reads well on each individual page) but introduces `text-6xl`/`text-7xl`/`font-black` found nowhere else in the app — a scale unique to 2 of 23 routes. |
| 5. Spacing | 3/4 | Mostly on Tailwind's scale; no major violations found beyond the typography-driven arbitrary values noted under Typography. |
| 6. Experience Design | 1/4 | Zero persona routing at the front door (customer vs. vendor vs. prospect); `/track` forces a full sign-in wall with no guest order-number lookup, contradicting its own name and duplicating the (better) email-gated pattern already built into the order-confirmation page. |

**Marketing surface: 12/24**

### Surface 2 — B2C Storefront (`/shop`, `/shop/[slug]`, `/shop/[slug]/cart`, `/shop/[slug]/checkout`, `/shop/[slug]/orders/[orderNumber]`, `/shop/orders`, `/track`)

| Pillar | Score | Key Finding |
|--------|-------|-------------|
| 1. Copywriting | 3/4 | Clean, purposeful microcopy throughout ("Your basket is empty", "Proceed to checkout £8.99") — a real strength; undercut only by the generic top-level error boundary. |
| 2. Visuals | 2/4 | Genuinely close to the Deliveroo/Just Eat pattern language (hero banner, category tabs, product cards) — but the content behind it is broken (see Experience Design), so the polish is decorating a fake catalog. |
| 3. Color | 4/4 | Consistent orange/emerald/slate throughout, zero hardcoded-hex violations in storefront `.tsx` (the one hex hit, `checkout/page.tsx:437`, is a justified Stripe Elements `colorPrimary` config value that can't take a Tailwind class, correctly set to orange-500). |
| 4. Typography | 2/4 | `text-[10px]` (below the smallest Tailwind token, `text-xs`=12px) appears 36 times across 9 files including 5 storefront pages — a repeated, systemic off-scale pattern, not a one-off. |
| 5. Spacing | 3/4 | Generally on-scale card/section rhythm; empty states (`shop-cart--desktop.png`, `shop-checkout--desktop.png`) pin content near the top and leave ~700px of dead grey space before the footer — a vertical-balance issue, not a scale violation. |
| 6. Experience Design | 1/4 | **Checkout collects no delivery address** (form fields are Full name / Email / Phone / Order notes only — verified against every `htmlFor=`/`id=` in `app/shop/[slug]/checkout/page.tsx`) despite the directory advertising "Free delivery on orders over £25." No delivery-time estimate is shown anywhere, and the fee is explicitly deferred: *"Final total confirmed after order is placed. Delivery fee may apply."* (checkout footnote, `text-[10px]`). Compare Deliveroo/Just Eat, which show delivery ETA + itemised fee before the pay button, not after. Compounding this: every shop under a tenant renders an **identical, duplicated menu** — `Jollof Express Brixton` and `Validation Shop` both show the same 24 items including the same duplicate "Jollof Rice"/"Chin Chin"/"Fried Plantain" line items, because `ProductRepository.java:21` resolves products by `(shopId = :shopId OR shopId IS NULL)` and 24 of 25 seeded products have `shop_id = NULL`, so they leak into every shop in the tenant. This isn't a seed-data quirk to wave away — it's live query logic that will do the same thing to a real multi-shop vendor's first two shops. |

**Storefront surface: 15/24**

### Surface 3 — B2B Dashboard (`/dashboard` and its 10 sub-routes)

| Pillar | Score | Key Finding |
|--------|-------|-------------|
| 1. Copywriting | 3/4 | Clear page headers/subtitles throughout; the onboarding slice's copy matches its design contract almost verbatim. One generic `action: "Submit"` string (`app/dashboard/orders/page.tsx:154`) and the "Unknown Product" fallback label surfacing where it shouldn't (see Experience Design) are the deductions. |
| 2. Visuals | 2/4 | Desktop dashboard (Shops/Products/Orders/Customers/Finance/Marketing/Onboarding) is clean and genuinely Square/Toast-adjacent — but the Kitchen Display Screen, the highest-stakes real-time surface in the product, has clipped/overlapping status badges on wrapped order-ID text (`dashboard-kitchen--desktop.png`) and the whole surface collapses to unusable on mobile (see Experience Design). |
| 3. Color | 3/4 | Mostly disciplined blue-600/slate + the documented semantic badge palette — but introduces **purple** as an undocumented 7th hue (`bg-purple-500/600/700`, `text-purple-600/700`, 13 occurrences) for the "Preparing" order status and for the VAT bar in the Finance chart, alongside a palette that's otherwise emerald/blue/amber/orange/red/slate only. |
| 4. Typography | 3/4 | The onboarding slice (`/dashboard/onboarding`) matches its 4-size/2-weight contract exactly — genuinely well executed. Legacy dashboard pages are looser (`font-bold`, extra sizes), contributing to the app-wide sprawl. |
| 5. Spacing | 3/4 | Card grids and page rhythm (`p-8` container, `space-y-6`, `gap-4`/`gap-6`) are consistent with the declared scale in `18-UI-SPEC.md` and hold up across every dashboard screenshot. Kitchen-card badge clipping is a component-overflow bug, not a scale violation. |
| 6. Experience Design | 1/4 | **Two blocker-tier failures.** (a) `components/dashboard/sidebar.tsx` is a fixed `w-64` (256px) black column with zero responsive classes and `components/dashboard/dashboard-shell.tsx` wraps it in `flex h-screen overflow-hidden` with no breakpoint or mobile-drawer pattern at all — on a 390px viewport this consumes ~66% of screen width and leaves page titles wrapping one word per line (`dashboard-home--mobile.png`, `dashboard-kitchen--mobile.png`, `dashboard-orders--mobile.png`). Every one of the 11 dashboard routes is affected. This directly violates the project's own mobile-first standard. (b) The Kitchen Display (`app/dashboard/kitchen/page.tsx`) and the order-detail page (`dashboard-order-detail--desktop.png`) both render **"Unknown Product"** for real order line items (`ORD-00000000-20260709-5A531BC6`: "6x Unknown Product"; `ORD-00000000-20260709-DC2A8FE7`: "3x Unknown Product"). `OrderItem.java:34` defaults `productName` to `"Unknown Product"` and the seeded orders were created without it populated — meaning the order-creation path can silently produce orders kitchen staff cannot act on. A KDS that can't say what to cook fails at its one job. Minor pile-on: elapsed time renders as raw uncapped minutes (`"2245m ago"`, `kitchen/page.tsx:75-79`) instead of hours/days. |

**Dashboard surface: 15/24**

---

## Whole-App Verdict

| Surface | Copy | Visuals | Color | Type | Spacing | Experience | Total |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Marketing | 3 | 1 | 1 | 3 | 3 | 1 | **12/24** |
| Storefront | 3 | 2 | 4 | 2 | 3 | 1 | **15/24** |
| Dashboard | 3 | 2 | 3 | 3 | 3 | 1 | **15/24** |
| **Whole app** | | | | | | | **42/72 (58%)** |

Every surface scores **1/4 on Experience Design** — not because loading spinners or empty states are missing (they mostly aren't), but because each surface's primary task breaks at the finish line: marketing can't route a visitor anywhere, storefront checkout can't collect an address or show a fee, and the dashboard's flagship live screen can't identify what's in an order — and none of it works on a phone. This is exactly the "shallow" pattern the user named: individually competent components assembled into flows that don't complete.

---

## Top Priority Fixes

1. **Dashboard is unusable on mobile — no responsive sidebar anywhere.** Every vendor checking orders or the kitchen display from a phone gets a 256px-wide black column eating two-thirds of the screen and single-word-wrapped content. Fix: add a mobile breakpoint to `components/dashboard/dashboard-shell.tsx` + `components/dashboard/sidebar.tsx` — collapse to a hamburger-triggered drawer under `md:`, matching the mobile-first mandate. (Blocker · Dashboard · L)
2. **Kitchen Display and order detail show "Unknown Product" for real order items.** `OrderItem.java:34` defaults to a placeholder that's leaking into production UI on the one screen whose entire job is telling kitchen staff what to prepare. Fix: find why order creation isn't populating `productName` (check `OrderService` order-item construction path), backfill/patch, add a regression test. (Blocker · Dashboard · M)
3. **Checkout can't take a delivery address and hides the delivery fee until after payment.** Form is Full name / Email / Phone / Order notes only — no address field exists in `app/shop/[slug]/checkout/page.tsx`. Combined with "Final total confirmed after order is placed. Delivery fee may apply.", this fails the core Deliveroo/Just Eat comparator (ETA + fee shown before pay) and can't actually fulfil a delivery order as currently built. Fix: add address collection (or an explicit delivery/pickup toggle if pickup-only is intended) and surface fee/ETA pre-payment via the existing `deliveryFeePennies` field already in the DTO. (Blocker · Storefront · M/L)
4. **No public landing page; marketing pages are unreachable islands.** `/` blind-redirects to a login wall; `/business-model-guide` and `/track` have zero inbound links anywhere in the codebase, `/for-operators` has exactly one (from another orphan page). Fix: build a real `/` landing page with persona routing (customer / vendor / prospect), and cross-link all three surfaces from a shared header. (Blocker · IA, all surfaces · L)
5. **Every shop in a tenant shows an identical, duplicated menu.** `ProductRepository.findAvailableByShopOrderedByCategory` (`ProductRepository.java:21`) matches `shopId = :shopId OR shopId IS NULL`; 24 of 25 seeded products have no `shop_id`, so they render on every shop under the tenant, including visible duplicate line items ("Jollof Rice" appears twice under Mains). This will do the same thing to any real vendor's second shop. Fix: require `shop_id` on product creation or explicitly scope the fallback, and re-seed demo data cleanly. (Critical · Storefront · M)

---

## Remediation Backlog

| # | Severity | Surface | Finding | Fix Direction | Size |
|---|----------|---------|---------|----------------|:---:|
| 1 | Blocker | Dashboard | No responsive sidebar — dashboard unusable on any phone viewport (all 11 routes) | Mobile drawer/hamburger pattern in `dashboard-shell.tsx` + `sidebar.tsx`, `md:` breakpoint | L |
| 2 | Blocker | Dashboard | Kitchen Display + order detail render "Unknown Product" for real orders | Fix order-item creation to populate `productName`; backfill affected rows; add regression test | M |
| 3 | Blocker | Storefront | Checkout has no delivery-address field; fee/ETA hidden until after payment | Add address input (or pickup/delivery toggle); surface `deliveryFeePennies` + ETA pre-payment | M/L |
| 4 | Blocker | IA (all) | No public landing page; `/` redirects straight to login wall | Build persona-routed `/` landing page | M |
| 5 | Blocker | IA (all) | `/business-model-guide`, `/track` are unreachable; `/for-operators` has one inbound link from another orphan | Add shared header/footer cross-links across marketing ↔ storefront ↔ dashboard | L |
| 6 | Critical | Storefront | Multi-shop product bleed — identical/duplicated menus across shops in a tenant | Scope `ProductRepository` query correctly; require `shop_id` on creation; re-seed | M |
| 7 | Major | Marketing | Two of three marketing pages hardcode hex palettes bypassing the design-token system entirely | Migrate `operator-pitch.tsx`/`business-model-guide.tsx` off hardcoded hex onto Tailwind tokens/CSS vars, within the existing orange/emerald/slate family | M |
| 8 | Major | Dashboard | Kitchen order cards: status badge clipped/overlapping on wrapped long order IDs | Fix card header layout (`flex-wrap`/truncate order ID, reposition badge) | S |
| 9 | Major | Marketing/Storefront (IA) | `/track` forces full sign-in with no guest order lookup, duplicating and contradicting the better email-gated pattern already on the order-confirmation page | Consolidate on one guest-tracking pattern (order number + email) and make it reachable | M |
| 10 | Minor | Dashboard | Undocumented purple hue (`Preparing` status, Finance VAT bar) outside the declared semantic palette | Replace with an existing semantic hue or formally extend the documented palette | S |
| 11 | Minor | Storefront/Marketing | `text-[10px]` arbitrary off-scale size used 36× across 9 files | Replace with `text-xs` (12px) or add a deliberate "micro" token to the scale | S |
| 12 | Minor | Dashboard | KDS elapsed time shown as raw uncapped minutes ("2245m ago") | Cap/format via hours/days once past ~60 minutes | S |
| 13 | Minor | Storefront | Repeated 401 console errors on every unauthenticated page load from silent customer-session probing | Handle expected-401 session check quietly (no console.error) in `lib/customer-auth.ts` | S |
| 14 | Minor | All | Generic global error boundary copy ("Something went wrong… Please try again") | Low priority — acceptable as a last-resort fallback; leave as-is unless touching `app/error.tsx` for other reasons | S |
| 15 | Minor | Storefront/Dashboard | Demo/seed data pollution (test-fixture shop and customer names, zero product images on the one populated published shop, duplicate menu rows) undermines a credible vendor demo | Clean seed data pass — real-looking shop/customer names, populate images, dedupe rows | S |

---

## Detailed Findings

### Marketing surface

**Visuals (1/4).** `home-root--desktop.png` / `auth-signin--desktop.png`: the entire viewport above and below a small centred card is blank white — no imagery, no brand storytelling, nothing to orient a first-time visitor. `for-operators--desktop.png` (navy `#211c36` hero, mustard `#ffdf7e` highlight text) and `business-model-guide--desktop.png` (teal `#122c33` hero, olive `#e4eecd` body) are two more, mutually incompatible visual systems, neither of which resembles the dashboard/storefront's blue/orange/slate.

**Color (1/4).** `grep -rlE "#[0-9a-fA-F]{3,8}" app components --include="*.tsx"` returns exactly 4 files app-wide with hardcoded hex: `checkout/page.tsx` (1 justified Stripe config value), `dashboard/page.tsx` (chart library colors, acceptable), and — the actual problem — `components/marketing/operator-pitch.tsx` and `components/marketing/business-model-guide.tsx`, each with 17-20+ distinct hex values forming two separate bespoke palettes. `text-primary|bg-primary|border-primary` usage in `components/marketing/*`: **zero**.

**Experience Design (1/4).** `track--desktop.png`: "Sign in to continue / Sign in to track your orders." — no order-number or email field, just a hard sign-in wall, despite the storefront already having a working guest-tracking flow at `/shop/[slug]/orders/[orderNumber]` (email-gated, no full account required — `shop-order-confirm--desktop.png`). Two different, contradictory implementations of the same feature.

### Storefront surface

**Experience Design (1/4) — primary evidence.** Populated checkout captured live (`shop-checkout-populated--desktop.png`) after adding "Jollof Rice" (£8.99) to cart: form fields are exactly `id="name"`, `id="email"`, `id="phone"`, `id="notes"` (verified via `grep -n 'htmlFor=\|id="' app/shop/[slug]/checkout/page.tsx`) — no address field exists in the file at all. Order summary shows "Estimated total £8.99" with the footnote "Final total confirmed after order is placed. Delivery fee may apply." — the fee is a known unknown at the moment the customer is asked to pay.

Product-bleed evidence (live DB, `docker exec jtoye-postgres psql`):
```
SELECT count(*), count(*) FILTER (WHERE shop_id IS NULL) FROM products;
→ 25 total, 24 NULL shop_id, all under tenant 00000000-0000-0000-0000-000000000001
```
Both `shop-detail-valid-shop--desktop.png` and `shop-detail-jollof--desktop.png` render the identical 24-item menu, including the same duplicate "Jollof Rice" / "Chin Chin" / "Fried Plantain" rows appearing twice within their own category. Root cause confirmed in code: `ProductRepository.java:21` — `WHERE p.available = true AND (p.shopId = :shopId OR p.shopId IS NULL)`.

**Color (4/4).** No violations found; called out as the strongest pillar in the whole audit. `bg-orange-500/600`, `text-orange-*`, `bg-emerald-*` used consistently across `app/shop/**` and `components/storefront/**`, matching the declared brand family in `CLAUDE.md`/prior design decisions.

### Dashboard surface

**Experience Design (1/4) — primary evidence.**

Mobile sidebar, confirmed in code (no `md:`/`lg:` breakpoint anywhere):
```tsx
// components/dashboard/sidebar.tsx:56
<div className="flex h-full w-64 flex-col bg-slate-900 text-white">
// components/dashboard/dashboard-shell.tsx
<div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
  <Sidebar />
  <main className="flex-1 overflow-y-auto">
    <div className="container mx-auto p-8 dark:text-slate-100">{children}</div>
```
On a 390px viewport this is a fixed 256px chrome column (66% of width) with `p-8` (32px) container padding on top, leaving ~70px of usable content width. Screenshots `dashboard-home--mobile.png`, `dashboard-kitchen--mobile.png`, `dashboard-orders--mobile.png` all show headings and stat cards clipped to single words/digits.

"Unknown Product," confirmed live on 2 real orders in the Kitchen Display (`dashboard-kitchen--desktop.png`):
- `ORD-00000000-20260709-5A531BC6` — "1 item / 6x Unknown Product"
- `ORD-00000000-20260709-DC2A8FE7` — "1 item / 3x Unknown Product"

and again on the order-detail page (`dashboard-order-detail--desktop.png`, `ORD-00000000-20260709-DCFB3F82`, "Items (1)" → "Unknown Product / Qty 1 / £9.99"). Root default: `core-java/src/main/java/uk/jtoye/core/order/OrderItem.java:34` — `private String productName = "Unknown Product";`, backed by `V30__order_item_product_name.sql`. This is a documented *fallback*, but it should never be the value customers/kitchen see on a real completed order — the order-creation path is not populating it.

**Bright spot, stated for calibration.** `dashboard-onboarding--desktop.png` (the Phase 18 slice) matches `18-UI-SPEC.md` closely: badge classes (`Failed` red, `Not required` slate, `Manual review` amber) exactly match the spec's `GateStatus` table; icons (`Building2`, `UtensilsCrossed`, `Wheat`) match the spec's per-gate icon assignments (verified in `app/dashboard/onboarding/page.tsx:29-38, 80-82`); the "Progress" timeline and "Action required" copy both match the Copywriting Contract almost verbatim. This is the one screen in the app built *to* a written contract, and it shows. Recommend using it as the reference pattern when fixing everything else, not treating it as the exception.

---

## Files Audited

**Config/tokens:** `frontend/tailwind.config.ts`, `frontend/app/globals.css`, `frontend/app/layout.tsx`, `frontend/components.json`

**Navigation:** `frontend/components/dashboard/sidebar.tsx`, `frontend/components/dashboard/dashboard-shell.tsx`, `frontend/components/storefront/storefront-nav.tsx`

**Marketing:** `frontend/app/page.tsx`, `frontend/app/for-operators/page.tsx`, `frontend/components/marketing/operator-pitch.tsx`, `frontend/app/business-model-guide/page.tsx`, `frontend/components/marketing/business-model-guide.tsx`, `frontend/app/track/page.tsx`, `frontend/app/auth/signin/**`

**Storefront:** `frontend/app/shop/page.tsx`, `frontend/app/shop/[slug]/page.tsx`, `frontend/app/shop/[slug]/cart/page.tsx`, `frontend/app/shop/[slug]/checkout/page.tsx`, `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx`, `frontend/app/shop/orders/page.tsx`, `frontend/lib/customer-auth.ts`

**Dashboard:** `frontend/app/dashboard/page.tsx`, `frontend/app/dashboard/shops/page.tsx`, `frontend/app/dashboard/products/page.tsx`, `frontend/app/dashboard/products/import/page.tsx`, `frontend/app/dashboard/orders/page.tsx`, `frontend/app/dashboard/orders/[id]/page.tsx`, `frontend/app/dashboard/customers/page.tsx`, `frontend/app/dashboard/finance/page.tsx`, `frontend/app/dashboard/marketing/page.tsx`, `frontend/app/dashboard/kitchen/page.tsx`, `frontend/app/dashboard/onboarding/page.tsx`, `frontend/app/dashboard/layout.tsx`, `frontend/app/error.tsx`

**Backend (cross-referenced for root causes):** `core-java/src/main/java/uk/jtoye/core/order/OrderItem.java`, `core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java`, `core-java/src/main/java/uk/jtoye/core/product/Product.java`

**Live data (read-only, via `docker exec jtoye-postgres psql`):** `shops`, `products`, `orders`, `tenants` tables

**Registry Safety:** `components.json` present (shadcn initialized); `18-UI-SPEC.md` declares shadcn-official-only, no third-party registries → registry vetting gate not triggered for this audit.
