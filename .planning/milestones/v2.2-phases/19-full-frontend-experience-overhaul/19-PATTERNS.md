# Phase 19: Full-Frontend Experience Overhaul - Pattern Map

**Mapped:** 2026-07-11
**Files analyzed:** 29 (new + modified, frontend + backend + data)
**Analogs found:** 27 / 29 (2 net-new with no in-repo analog: dev seed mechanism, static link-graph test)

> This phase is **convergence, not greenfield** (per RESEARCH §Summary). Almost every "new" file
> copies an existing repo idiom. The excerpts below are the exact source each new/edited file should
> mirror. Line numbers verified against the running codebase on 2026-07-11.

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `frontend/components/public/public-shell.tsx` | component (shell) | presentation | `frontend/app/shop/layout.tsx` | role-match |
| `frontend/components/public/public-header.tsx` | component (nav) | presentation + route-detect | `frontend/app/shop/layout.tsx` (header) + `sidebar.tsx` (active) | role-match |
| `frontend/components/public/public-footer.tsx` | component | presentation | `frontend/app/shop/layout.tsx` (footer) | exact |
| `frontend/components/dashboard/mobile-tab-bar.tsx` | component (nav) | presentation + route-detect | `frontend/components/dashboard/sidebar.tsx` | exact |
| `frontend/components/ui/sheet.tsx` | ui primitive | presentation | `frontend/components/ui/dialog.tsx` | exact |
| `frontend/app/page.tsx` | page (RSC) | request-response | `frontend/app/for-operators/page.tsx` | role-match |
| `frontend/app/shop/[slug]/checkout/page.tsx` | page (form) | request-response | self + `frontend/app/shop/page.tsx` (fetch) | evolve |
| `frontend/components/dashboard/dashboard-shell.tsx` | component (shell) | presentation | self (RESEARCH code example) | evolve |
| `frontend/components/dashboard/sidebar.tsx` | component (nav) | presentation | self | evolve |
| `frontend/components/storefront/storefront-nav.tsx` | component (nav) | presentation | self | evolve |
| `frontend/components/marketing/operator-pitch.tsx` | component | presentation | self (token re-skin) | evolve |
| `frontend/components/marketing/business-model-guide.tsx` | component | presentation | self (token re-skin) | evolve |
| `frontend/app/shop/layout.tsx` | layout | presentation | self | evolve |
| `frontend/app/track/page.tsx` | page (form) | request-response | self + `PublicShell` | evolve |
| `frontend/app/dashboard/kitchen/page.tsx` | page | streaming (STOMP) | self | evolve (fix) |
| `frontend/app/dashboard/orders/[id]/page.tsx` + `components/dashboard/orders/OrderDetailPanel.tsx` | page/component | request-response | self | evolve (fix) |
| `frontend/app/shop/page.tsx` / `[slug]/page.tsx` / `[slug]/cart/page.tsx` | page | request-response | self | evolve (polish) |
| `frontend/lib/customer-auth.ts` | utility | request-response | self | evolve (see NOTE) |
| `frontend/__tests__/link-graph.test.ts` | test | static analysis | `frontend/__tests__/shop/cart.test.tsx` (conventions only) | partial |
| `frontend/app/__tests__/landing.test.tsx` | test | component render | `frontend/__tests__/shop/cart.test.tsx` | exact |
| `frontend/e2e/dashboard-mobile.spec.ts` | test | e2e | `frontend/e2e/kitchen-flow.spec.ts` | exact |
| `core-java/.../db/migration/V45__order_fulfilment.sql` | migration | schema | `V40`/`V41` (+ `V6` CHECK, `V43` _aud) | exact |
| `core-java/.../storefront/PublicStorefrontService.java` | service | request-response | self + `OrderService.java:142-148` | evolve (fix) |
| `core-java/.../storefront/dto/GuestOrderRequest.java` | dto (model) | request-response | self (Bean Validation idiom) | evolve |
| `core-java/.../order/Order.java` + `order/OrderItem.java` | model (entity, @Audited) | CRUD | self (`@Enumerated(EnumType.STRING)` fields) | evolve |
| `core-java/.../order/OrderMapper.java` | mapper | transform | self (`@Mapping` idiom) | evolve |
| `core-java/.../order/dto/OrderDetailDto.java` | dto | transform | self | evolve |
| `core-java/.../product/ProductRepository.java` | repository | CRUD | self `:22` vs `:26 findByShopId` | evolve (fix) |
| `core-java/.../order/*FulfilmentAuditIntegrationTest.java` (NEW) + extend `PublicStorefrontServiceTest` | test | integration | `core-java/.../order/OrderControllerIntegrationTest.java` | exact |
| dev/demo seed (mechanism TBD — Open Q1) | data | batch | `V13__seed_default_tenants.sql` (INSERT..ON CONFLICT) | partial |

---

## Pattern Assignments

### `components/public/public-shell.tsx` (NEW — component shell)

**Analog:** `frontend/app/shop/layout.tsx` (lines 15–52) — the app's existing "sticky header + `flex-1` main + footer" wrapper. Copy this three-part structure; swap `StorefrontNav` for the new `PublicHeader`/`PublicFooter`.

```tsx
// app/shop/layout.tsx:15-36 — the shell skeleton to mirror
<div className="min-h-screen bg-slate-50 flex flex-col">
  <header className="sticky top-0 z-50 bg-white border-b border-slate-200 shadow-sm">
    <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
      <div className="flex h-14 items-center justify-between"> {/* h-14 = the locked 56px bar */}
        {/* wordmark → StorefrontNav */}
      </div>
    </div>
  </header>
  <main className="flex-1">{children}</main>
  <footer className="border-t border-slate-200 bg-white">...</footer>
</div>
```

Wordmark idiom (reuse verbatim, `app/shop/layout.tsx:25-28`) — this is the orange `J` badge the UI-SPEC mandates:
```tsx
<span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-orange-500 text-sm font-bold text-white">J</span>
<span>J&apos;Toye</span>
```
`PublicShell` is a **plain server component wrapper** (no `"use client"`) — the RESEARCH-recommended low-churn path: import it into each of `app/page.tsx`, `app/for-operators/page.tsx`, `app/business-model-guide/page.tsx`, `app/track/page.tsx`. It touches no route-segment config, so root `force-dynamic` (`app/layout.tsx:14`) and the CSP nonce are preserved automatically. **Do NOT create a `(public)/` route group.**

---

### `components/public/public-header.tsx` (NEW — client nav)

**Analog A — active-link detection:** `components/dashboard/sidebar.tsx` (`usePathname` + `cn()` active class) and `components/storefront/storefront-nav.tsx` (`"use client"` nav). Copy the active-state idiom:
```tsx
// sidebar.tsx:37,84,89-94 — the usePathname + cn active pattern to mirror
const pathname = usePathname()
const isActive = pathname === item.href   // header uses prefix match per UI-SPEC
className={cn("...base...", isActive ? "text-slate-900 font-semibold" : "text-slate-600 hover:text-slate-900")}
```

**Analog B — mobile sheet trigger:** the icon-only hamburger opens the new `components/ui/sheet.tsx`. Accessibility contract (UI-SPEC Surface B): the trigger is a real `<button aria-label="Open menu">` with `focus-visible:ring-2 focus-visible:ring-orange-300`; the sheet close carries `aria-label="Close menu"`. Icons from `lucide-react` (`Menu`).

**Nav links (UI-SPEC copy):** Shops (`/shop`) · For operators (`/for-operators`) · Track order (`/track`) · Sign in (`/auth/signin`, slate-900 pill — reuse the storefront sign-in pill idiom at `storefront-nav.tsx:91-97`).

---

### `components/public/public-footer.tsx` (NEW — component)

**Analog:** `frontend/app/shop/layout.tsx` (lines 39–50) — the existing footer. Expand its single row into the UI-SPEC column layout (Brand / For customers / For operators / bottom row). **This footer is the mechanism that de-orphans `/business-model-guide` + `/track`** (RESEARCH UIX-01, SPEC Surface B). Preserve the existing note verbatim:
```tsx
// app/shop/layout.tsx:42-46 — keep this line + copyright
<p className="text-sm text-slate-500">&copy; {new Date().getFullYear()} J&apos;Toye OaaS...</p>
<span>Allergen info available on all products</span>
```

---

### `components/dashboard/mobile-tab-bar.tsx` (NEW — client nav)

**Analog:** `frontend/components/dashboard/sidebar.tsx` (whole file) — **drive both bars off the SAME `navigation` array** (do not fork). The array to export lives at `sidebar.tsx:24-34`:
```tsx
// sidebar.tsx:24-34 — export this; tab bar takes the first 4 + a "More" entry
const navigation = [
  { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { name: "Shops", href: "/dashboard/shops", icon: Store },
  { name: "Products", href: "/dashboard/products", icon: Package },
  { name: "Orders", href: "/dashboard/orders", icon: ShoppingCart },
  ... "Kitchen" ... "Go live" (Rocket, Phase 18 — preserve) ]
```
UI-SPEC Surface D locks the 4 primary tabs = Dashboard / Orders / Products / Kitchen + a 5th "More" (`Menu`) opening the sheet. Active-state via `usePathname` (`sidebar.tsx:37,84`). **Light-bar active idiom differs from the dark sidebar:** active = `text-blue-600` (icon+label), inactive = `text-slate-500` — NOT the `bg-blue-600` pill (that stays the dark sidebar's, `sidebar.tsx:92`). Bar shell: `fixed inset-x-0 bottom-0 z-50 h-14 bg-white border-t border-slate-200 flex md:hidden` + `pb-[env(safe-area-inset-bottom)]`. The "More" sheet relocates the theme-toggle + sign-out currently at `sidebar.tsx:104-121`.

---

### `components/ui/sheet.tsx` (NEW — `npx shadcn add sheet`)

**Analog (vendoring convention):** `frontend/components/ui/dialog.tsx` — the shadcn `sheet` is the SAME Radix Dialog primitive with side-anchored content. It needs **no npm install** (`@radix-ui/react-dialog@^1.1.15` already present). Mirror dialog.tsx's structure exactly:
```tsx
// dialog.tsx:1-13 — same imports/aliases sheet.tsx will use
import * as DialogPrimitive from "@radix-ui/react-dialog"
import { X } from "lucide-react"
import { cn } from "@/lib/utils"
const Dialog = DialogPrimitive.Root  // sheet renames these Sheet/SheetTrigger/etc.
```
The forwardRef + `displayName` + portal/overlay pattern (`dialog.tsx:15-52`) is identical; `sheet` adds a `side` variant (`top|bottom|left|right`) on the content. Prefer generating via the shadcn CLI, then confirm it matches this convention. Keep the `sr-only` close label idiom (`dialog.tsx:47`).

---

### `app/page.tsx` (EVOLVE — replace `redirect("/dashboard")`)

**Current (the bug):**
```tsx
// app/page.tsx:1-5 — the blind redirect to REPLACE
import { redirect } from "next/navigation"
export default function Home() { redirect("/dashboard") }
```
**Analog:** `frontend/app/for-operators/page.tsx` — the "server page returns a component" idiom. New `Home()` returns `<PublicShell>` + split-persona hero (server component; keep it non-`"use client"` so the CSP nonce reaches it — RESEARCH Pattern 2 / Pitfall 3 #89). No middleware change (`app/dashboard/layout.tsx` remains the sole auth gate). Doors use `SafeImage` (see Shared Patterns) with the orange→rose gradient fallback; hero art = gradient-forward (`bg-gradient-to-br from-orange-400 via-orange-500 to-rose-500`), no stock photo.

---

### `app/shop/[slug]/checkout/page.tsx` (EVOLVE — fulfilment + address + fee-before-pay)

**Self is the primary analog** — the Step-1 form (`:456-591`), COD breakdown (`:280-350`) and payment-step breakdown (`:368-414`) already exist. Three changes:

1. **Fetch the shop (NEW behaviour).** The page does NOT fetch the shop today. Copy the `publicApiClient` GET idiom from `app/shop/page.tsx` to hit `GET /public/shops/{slug}` (confirmed endpoint at `PublicStorefrontController.java:65` → `PublicShopDto`, which already carries `deliveryFeePennies`/`freeDeliveryThresholdPennies`, `PublicShopDto.java:19-20`).

2. **Fulfilment toggle + conditional address.** Bespoke 2-button segmented control (no new dep). Reuse the existing input styling verbatim (`checkout/page.tsx:483` idiom): `rounded-lg border border-slate-200 px-3 py-2.5 ... focus:border-orange-300 focus:ring-2 focus:ring-orange-100`. Postcode regex + uppercase-on-blur per UI-SPEC Surface E.

3. **Replace the deferred footnote with a definite breakdown.** The exact block to remove is `checkout/page.tsx:557-562`:
```tsx
// REMOVE this — the "deferred" footnote the audit flagged (checkout/page.tsx:557-562)
<span className="text-base font-bold text-slate-900">Estimated total</span> ...
<p className="text-[10px] text-slate-400">Final total confirmed after order is placed. Delivery fee may apply.</p>
```
Replace with the **already-existing** post-order breakdown rows (Subtotal / Delivery-or-"Free" / VAT / Total) copied from `checkout/page.tsx:386-413`. Mirror the server fee waiver **exactly** (`PublicStorefrontService.java:424-428`): `deliveryFee = shop.deliveryFeePennies`, waived to 0 when `subtotal ≥ freeDeliveryThresholdPennies` or `COLLECTION`. `text-[10px]` here (`:561`, `:375`, `:533`) must become `text-xs` (backlog #11). Order payload (`:212-222`) gains `fulfilmentType` + `address`.

---

### `components/dashboard/dashboard-shell.tsx` (EVOLVE — mobile chrome)

**Self analog** — current shell is `Sidebar` + `main` (`:14-22`). Apply the RESEARCH code example verbatim:
```tsx
// target shape (RESEARCH §Code Examples) — Sidebar becomes hidden md:flex internally
<div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
  <Sidebar />
  <main className="flex-1 overflow-y-auto">
    <MobileTopBar className="md:hidden" />
    <div className="container mx-auto p-4 sm:p-8 pb-20 md:pb-8">{children}</div>
  </main>
  <MobileTabBar className="md:hidden" />
</div>
```
Note `pb-20` on mobile so content clears the fixed bar (SPEC Spacing exception). Container drops `p-8`→`p-4` below `sm`. **Regression tripwire:** `components/dashboard/__tests__/dashboard-shell.test.tsx` will need mobile-bar assertions added.

---

### `components/marketing/operator-pitch.tsx` + `business-model-guide.tsx` (EVOLVE — token re-skin)

**Self analog — this is a re-skin, not a rebuild.** The dark full-bleed layout + copy are PRESERVED; only hex → token. The hardcoded palette to migrate is dense — e.g. `operator-pitch.tsx:55` `bg-[#f8f7f2] text-[#211c36]`, `:65` `bg-[#211c36]`, `:69` `text-[#ffdf7e]`, `:72` `bg-[#f26522]`. UI-SPEC §Surface-C table gives the exact mapping (`#211c36`→`bg-slate-900`, `#f26522`→`orange-500`, `#ffdf7e`→`amber-300`). **Success gate:** `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx` == 0. **Regression tripwire:** `components/marketing/__tests__/{operator-pitch,business-model-guide}.test.tsx` assert on hardcoded hex — update to assert tokens.

---

### `app/track/page.tsx` (EVOLVE — remove sign-in wall)

**Self analog** — the guest lookup form + progress stepper + 15s auto-refresh already exist (`:129-244`, `:95-107`) and call the correct IDOR-hardened endpoint (`:82-85` `GET /public/orders/{orderNumber}?email=`). Two edits: (1) remove the `RequireCustomerAuth` wrapper at `:38,42` (keep the email pre-fill at `:50-61`, never require it); (2) replace the bespoke mini-header (`:115-125`) with `<PublicShell>`. The `text-[9px]`/`text-[10px]` (`:221,235`) → `text-xs`.

---

### `app/dashboard/kitchen/page.tsx` + order-detail (EVOLVE — fix)

**Self analog.** Three fixes, exact lines:
- **Purple→amber** (backlog #10): `kitchen/page.tsx:46` `bg-purple-500`→`bg-amber-500`; `:59` `bg-purple-600 hover:bg-purple-700`→amber. Same in `dashboard/orders/page.tsx`, `OrderDetailPanel.tsx:57`, `dashboard/page.tsx` (per UI-SPEC §Color exhaustive list of 17 hits).
- **Badge clipping** (#8): header `:423` `flex items-start justify-between` + title `:424` `text-2xl font-bold` → title `min-w-0 truncate text-lg font-semibold`, badge `flex-shrink-0`, header `gap-2`.
- **Elapsed cap** (#12): `elapsedText()` at `:75` currently uncapped → cap to just now/Xm/Xh/Xd.
- **Product name** renders correctly here already (`:449` `{item.quantity}x {item.productName}`) — the fix is upstream (see backend). **Regression tripwires:** `kitchen/__tests__/page.test.tsx`, `OrderDetailPanel.test.tsx`, `e2e/kitchen-flow.spec.ts` (use `domcontentloaded`, not `networkidle` — SSE/STOMP never idles).

---

### `lib/customer-auth.ts` (EVOLVE — #13 quiet 401) — **VERIFY FIRST**

**NOTE / discrepancy:** RESEARCH/SPEC #13 says this file logs `console.error` on every expected-401. **Grep on 2026-07-11 found ZERO `console.*` calls in `lib/customer-auth.ts`** — all catch blocks are already silent (`} catch {` at `:76,86,99,115,158,306,341,364,377`). The noisy `console.error` may already be resolved, or the 401 log originates in `lib/api-client.ts` (the dashboard `apiClient` 401 interceptor, `:90-91`) not the public path. Planner: confirm the actual console source with a live public page-load before assigning a fix task; the target file may be `api-client.ts`, or #13 may already be closed.

---

### `V45__order_fulfilment.sql` (NEW migration)

**Analogs:** `V40__vat_ledger_correctness.sql` (column-add + `_aud` mirror + VARCHAR+CHECK), `V41__ppds_label_compliance.sql` (additive nullable + `_aud` mirror), `V6` (status CHECK convention), `V43` (`_aud` mirror structure). Copy the V40 two-part shape exactly:

```sql
-- base table: VARCHAR+CHECK enum + nullable address (V40:49-51 / V6:18 convention)
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS fulfilment_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY'
        CHECK (fulfilment_type IN ('DELIVERY','COLLECTION')),
    ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city  VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);

-- CRITICAL Envers mirror (V40:53-56 / V41:39-41 / V43:112-126): ALWAYS nullable, NO CHECK
ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS fulfilment_type  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS address_line1    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);
```
**RLS:** none needed — `orders`/`orders_aud` already ENABLE+FORCE (RESEARCH Pattern 4, A5). **V44 stays reserved for #96.** Bump `docs/metrics.json` `schema_version` 43→45. See the "Envers mirror" shared pattern below — this is the HIGHEST-RISK item (V38 was a dedicated fix for this exact drift).

---

### `PublicStorefrontService.java` (EVOLVE — the #1 blocker fix)

**The bug** (`:404-410`): the guest path constructs `OrderItem` but **never calls `setProductName`**, so the entity default `"Unknown Product"` (`OrderItem.java:34`) persists.
**The correct analog** is 4 lines away conceptually — `OrderService.java:142-148` does it right:
```java
// OrderService.java:142-148 — the CORRECT snapshot (copy the setProductName line)
OrderItem item = new OrderItem(product.getId(), itemRequest.getQuantity(), unitPrice);
item.setTenantId(tenantId);
item.setProductName(product.getTitle());   // ◀── PublicStorefrontService:409 is MISSING this
order.addItem(item);
```
Add `item.setProductName(product.getTitle());` at `PublicStorefrontService.java:409` (right after `setTenantId`). These are the ONLY two `OrderItem` construction sites in core-java (RESEARCH A4 — no edge/sync/WhatsApp path builds `OrderItem`). Also accept `fulfilmentType`/address from `GuestOrderRequest` and set on `Order`; delivery-fee logic at `:420-429` stays authoritative (client value is preview-only).

---

### `GuestOrderRequest.java` (EVOLVE — add fields)

**Self analog** — mirror the existing Bean Validation idiom (`:12-23` `@NotBlank`/`@Size`/`@Email`). Add `fulfilmentType` (`@NotBlank`, enum-string), and nullable address fields with `@Size(max=…)` matching the V45 column caps. Server-side conditional-required for delivery (V5 input-validation, RESEARCH Security). Also mirror onto `CreateOrderRequest` if the admin path needs parity (Open Q2 — wire end-to-end).

---

### `Order.java` / `OrderItem.java` (EVOLVE — @Audited entity fields)

**Self analog** — the `@Enumerated(EnumType.STRING)` + `@Column(length=20)` idiom already used for `status` (`Order.java:45-47`) and `vatRate` (`:71-73`). Add a `FulfilmentType` enum field the same way, plus `@Column(name="address_line1", length=255)` etc. Both entities are `@Audited` (`Order.java:21`, `OrderItem.java:16`) — **every field added here MUST have a matching nullable column in `orders_aud` via V45** (see Envers shared pattern).

---

### `OrderMapper.java` + `OrderDetailDto.java` (EVOLVE — expose to detail view)

**Self analog** — `OrderMapper.java:20-42` `@Mapping` idiom (MapStruct, `componentModel="spring"`). Add `@Mapping(target="fulfilmentType"…)` + address lines to `toDetailDto`. `OrderDetailDto.java` follows its existing nullable-field pattern (`:29-32` payment fields) — add fulfilment/address getters/setters so `/dashboard/orders/[id]` can render the delivery address (Open Q2: wire fully, no shallow flow).

---

### `ProductRepository.java` (EVOLVE — drop the NULL bleed)

**The bug** (`:21-22`): `p.shopId = :shopId OR p.shopId IS NULL` makes every shop show the same 24 NULL-`shop_id` products.
**Analog for the fix** — the sibling method `:26 findByShopId(UUID shopId)` is already correctly scoped (no NULL fallback), as is `AllergenCompletenessGate`. After the shop_id data-migration, narrow `:22` to `p.shopId = :shopId` (drop `OR p.shopId IS NULL`). Exactly ONE caller: `PublicStorefrontService:215` (RESEARCH Pattern 6). **Regression tripwires:** FTS/#96 product tests must seed `shop_id`.

---

### Integration tests (NEW `*FulfilmentAuditIntegrationTest` + extend `PublicStorefrontServiceTest`)

**Analog:** `core-java/.../order/OrderControllerIntegrationTest.java:40-70` — the Testcontainers scaffold to copy verbatim:
```java
// OrderControllerIntegrationTest.java:40-58 — the exact boilerplate to reuse
@SpringBootTest @Testcontainers @ActiveProfiles("test") @Transactional
@org.junit.jupiter.api.Tag("testcontainers")
class ... {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")...;
  @DynamicPropertySource static void configureProperties(DynamicPropertyRegistry r) {
      IntegrationTestSupport.registerPostgresTestProperties(r, postgres);  // testsupport/IntegrationTestSupport.java
  }
}
```
The new test must perform a **real audited order write after V45** (create order → assert success) — a `@DataJpaTest` will NOT catch Envers drift; only a real Postgres audited INSERT does (RESEARCH Pitfall 1). Extend `PublicStorefrontServiceTest` with a name-snapshot assertion (fresh guest order carries `product.getTitle()`, not `"Unknown Product"`).

---

### Dev/demo seed (NEW — mechanism is Open Q1)

**Closest analog:** `V13__seed_default_tenants.sql` — the ONLY committed seed (`INSERT ... ON CONFLICT (id) DO NOTHING`, idempotent). No committed shop/product/order seed exists. RESEARCH recommends a **dev-profile-scoped seeder or script (NOT a prod Flyway migration)** so it doesn't ship to prod or perturb Testcontainers fixtures. **Backfill data-migrations** (distinct tasks) mirror V40's historical-UPDATE style (safe on zero rows):
```sql
-- product_name backfill — mirrors V40:79-84 "UPDATE ... FROM ... safe on zero rows"
UPDATE order_items oi SET product_name = p.title
  FROM products p
 WHERE oi.product_id = p.id AND oi.product_name = 'Unknown Product';
-- (run tenant-scoped/under RLS; raw UPDATE does not mint an Envers revision — acceptable per V30/A3)
```
Planner: confirm seed mechanism with user (biggest scoping ambiguity — Open Q1).

---

## Shared Patterns

### Envers `_aud` audit-column mirror (HIGHEST-RISK — apply to every V45 column)
**Source:** `V40__vat_ledger_correctness.sql:53-56`, `V41:39-41`, `V43:104-126` (all say the same thing).
**Apply to:** every column added to any `@Audited` entity (`Order`, `OrderItem`).
```sql
-- Audit mirror columns are ALWAYS nullable, NEVER carry DEFAULT or CHECK.
-- Omitting this makes the NEXT audited write 500 with "column ... does not exist" (V38 fixed exactly this).
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS <col> <type>;  -- nullable, no CHECK
```
Prove it with a Testcontainers audited-write test — `RlsContractTest` walks table-level RLS, not columns, and will NOT catch drift.

### Enum-as-VARCHAR + CHECK (never native PG enum)
**Source:** `V6:18` (`orders.status`), `V40:49-51` (`products.vat_rate`), `V43:26-29` (`status`), entity side `Order.java:45-47,71-73` (`@Enumerated(EnumType.STRING)`).
**Apply to:** `orders.fulfilment_type`. `fulfilment_type`'s two values are complete — no future-value pre-listing needed (unlike V36's REFUNDED landmine).

### Testcontainers integration-test scaffold
**Source:** `core-java/.../testsupport/IntegrationTestSupport.java` (`registerPostgresTestProperties`) + `OrderControllerIntegrationTest.java:40-58`.
**Apply to:** every new backend integration test. `@Tag("testcontainers")`, `@ActiveProfiles("test")`, ddl-auto none (Flyway is schema truth). For RLS-enforcement (not just app-scoping) additionally `ALTER ROLE ... NOSUPERUSER` after seeding (see IntegrationTestSupport javadoc + `ShopImageCrossTenantIntegrationTest`).

### Radix primitive vendoring
**Source:** `components/ui/dialog.tsx:1-52` (forwardRef + `displayName` + portal/overlay + `cn()` + `sr-only` close).
**Apply to:** `components/ui/sheet.tsx` (same `@radix-ui/react-dialog` base, no npm install).

### Active-nav via `usePathname` + `cn()`
**Source:** `components/dashboard/sidebar.tsx:37,84,89-94`; also `components/storefront/storefront-nav.tsx`.
**Apply to:** `public-header.tsx`, `mobile-tab-bar.tsx` (single `navigation` array — do not fork).

### `SafeImage` with branded fallback (never a broken `<img>`)
**Source:** `components/ui/safe-image.tsx` (plain `<img>` + `onError` → fallback div; SPEC-mandated so Playwright can assert `naturalWidth > 0`).
**Apply to:** landing doors/hero imagery, all storefront/product/shop card images (UI-SPEC Surface A, G). Fallback = orange→rose gradient (banners) or `Store`/`UtensilsCrossed` tile (logos/products).

### Public data fetching — `publicApiClient`
**Source:** `app/shop/page.tsx:6` import + `app/track/page.tsx:82-85` (`publicApiClient.get('/public/...')`), `checkout/page.tsx:224` (`.post('/public/shops/{slug}/orders')`).
**Apply to:** checkout shop-fetch (`GET /public/shops/{slug}`), landing (if any public data). Dashboard uses `apiClient` (`/api/v1`, Bearer + `X-Tenant-Id`); public uses `publicApiClient` (`/public`) — do not mix.

### Route protection stays server-side (do NOT add middleware gating)
**Source:** `app/dashboard/layout.tsx` (`await auth()` → `redirect("/auth/signin")`).
**Apply to:** nothing new — `/` and all public routes stay ungated; `middleware.ts` remains session-resolve + CSP-nonce only (RESEARCH Pattern 2, Anti-pattern).

### Jest test conventions
**Source:** `__tests__/shop/cart.test.tsx` (`render`/`screen`, `resolvedThenable` for `use()` params, `localStorage.clear()` in `afterEach`); config `jest.config.js` (`@/` alias, `testMatch **/__tests__/**`, e2e excluded).
**Apply to:** `app/__tests__/landing.test.tsx`, `__tests__/link-graph.test.ts` (static: walk `app/**/page.tsx`, grep `href="..."`, assert ≥1 inbound per route — adds to `jest_blocks`).

### Playwright e2e conventions
**Source:** `e2e/kitchen-flow.spec.ts:15-19` (`route()` stubbing, `PLAYWRIGHT_BASE_URL` honour, fake session), `playwright.config.ts` (`mobile` 390×844 + `desktop` 1440×900 projects; baseURL `:3000`).
**Apply to:** `e2e/dashboard-mobile.spec.ts` (run `--project=mobile`, 11 routes at 390px). **Use `domcontentloaded`, not `networkidle`** on kitchen/order/track (open SSE/STOMP).

### docs-freshness metric reconciliation
**Source:** `docs/metrics.json` (current: `schema_version:43`, `jest_blocks:130`, `playwright_blocks:23`, `java_test_methods:693`, `total_logical_invocations:921`); gate `scripts/docs-freshness.sh`.
**Apply to:** every plan that adds a test/migration — regenerate (write mode); bump `schema_version`→45. Merge conflicts on this file are resolved by re-running the generator (it is the arbiter).

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `frontend/__tests__/link-graph.test.ts` | test | static analysis | No static route/href-graph test exists; conventions borrowed from jest analogs but the walk-app/grep-href logic is net-new (RESEARCH Pattern 8, ~few dozen lines). |
| dev/demo seed (shops/products/orders/customers) | data | batch | Only `V13` (tenants) is committed; no product/shop/order seed exists. Mechanism (dev-profile seeder vs one-off cleanup) is Open Q1 — user decision. |

---

## Metadata

**Analog search scope:** `frontend/{app,components,lib,e2e,__tests__}`, `core-java/src/main/java/uk/jtoye/core/{order,storefront,product}`, `core-java/src/main/resources/db/migration`, `core-java/src/test/.../{order,testsupport}`, `docs/`.
**Files scanned:** ~35 (18 read in full/targeted, ~17 via grep).
**Pattern extraction date:** 2026-07-11
**Key cross-file invariant:** the phase's failure mode is re-implementing an existing solution slightly differently (how the app grew three visual systems). Every excerpt above points at the ONE canonical idiom to copy.
