# Phase 10: Storefront Marketing Render + Missing Customer Routes - Research

**Researched:** 2026-04-14
**Domain:** Next.js 16 storefront + Spring Boot public controller integration
**Confidence:** HIGH

## Summary

Phase 10 is almost entirely a **frontend rendering + test-gap closure** phase. The audit's premise that "the storefront never calls `/promotions` or `/announcements`" was **partially correct at the time the audit was written**, but several of the "missing" artefacts have in fact already been built:

- Backend `GET /public/shops/{slug}/promotions` and `GET /public/shops/{slug}/announcements` **already exist** at `PublicStorefrontController.java:64-74` with full service + repository wiring and `PublicStorefrontServiceTest` coverage. **STFR-01/02 need only controller-level MockMvc integration tests added** (the service methods are already unit-tested).
- The storefront shop detail page (`frontend/app/shop/[slug]/page.tsx:421-443`) **already renders** the announcement banner and a simple promotion strip via a third, coarser endpoint `/public/shops/{slug}/config`. What is **missing** is: (a) per-product discount badges overlaid on product cards, (b) use of the dedicated `/promotions` + `/announcements` endpoints instead of `/config`, and (c) `useMemo` on the per-product promotion lookup.
- `frontend/app/shop/[slug]/cart/page.tsx` **already exists** and reads from the same `jtoye-cart-{slug}` localStorage key as the floating cart bar. What is missing: **Jest coverage** (STFR-04 calls out Jest for empty + populated).
- `frontend/app/shop/orders/page.tsx` **already exists**, gated by `RequireCustomerAuth`, and loads via `GET /public/orders?email=...`. What is missing: **status filter, date filter, and pagination** (STFR-05 calls these out explicitly; current impl has active/past split only).
- Playwright e2e covers browse → add → cart → checkout → confirmation, but **does not verify the promotion banner renders** and **does not cover Stripe test-mode PaymentElement completion** (STFR-06). Current checkout path uses COD fallback because the test seed shop has no Stripe key configured.

**Primary recommendation:** Frame this phase as **"complete the wiring, add the missing tests, close the filter/pagination gaps"** — not "build from scratch." Most of the task work is small edits and test additions, not new components.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Active-promotion/announcement filtering | API / Backend | Database (JPQL `CURRENT_TIMESTAMP`) | Time-window logic must run server-side so clients cannot bypass |
| Tenant scoping by slug | API / Backend | Database (RLS on `shop_promotions`) | `findBySlugAndPublishedTrue` on the public RLS policy; no tenant header needed |
| Promotion banner render | Frontend (Client) | — | Pure presentation, reads from `/public` JSON response |
| Discount badge per product card | Frontend (Client) | — | Product ↔ promotion match (by `category`) is derivable client-side; `useMemo` by `(products, promotions)` |
| Cart state persistence | Browser / Client | localStorage (`jtoye-cart-{slug}`) | Guest cart — no auth, no server roundtrip |
| Customer order history filtering/pagination | Frontend (Client) | API (`/public/orders?email=`) | Filtering + pagination can be client-side for current dataset sizes; backend endpoint already returns full list |
| Customer auth gate | Frontend (SSR/Client) | `/api/customer-auth/session` (Next.js API route) | HttpOnly cookie pattern shipped in PR #36 |
| Stripe test-mode payment | Frontend (Client) | API (PaymentIntent creation) | Stripe Elements lives in the browser; core-java creates the intent |

## Standard Stack

### Core (already in use — NO new libraries needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Next.js | 16.2.2 | App-router storefront | Already project default [VERIFIED: frontend/package.json] |
| React | 19 | UI | App router compatible |
| axios | 1.15.0 | HTTP client | `frontend/lib/public-api-client.ts` already points at `NEXT_PUBLIC_API_URL` |
| @playwright/test | 1.59.1 | e2e | Config at `frontend/playwright.config.ts:1-32` |
| Jest | 29.7.0 + @testing-library/react | Unit/component | Already in project per CLAUDE.md |
| lucide-react | — | Icons | Already used throughout (`Package`, `Clock`, `ShoppingBag`) |
| Tailwind 3.4.1 | — | Styling | Project default |
| Spring Boot Test / MockMvc | 3.4.2 | Controller integration tests | `@WebMvcTest(PublicStorefrontController.class)` pattern at `core-java/src/test/.../PublicStorefrontControllerTest.java:28-39` |

### Supporting (already installed)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `@/components/ui/badge` | shadcn/ui | Discount badge | Existing component (`frontend/components/ui/badge.tsx`), variants: default, secondary, destructive, outline |
| `@stripe/react-stripe-js` | 6.1.0 | Stripe Elements | Only needed if STFR-06 extends to test-mode payment; COD fallback path is already tested |

**Installation:** None. Everything required is already in the tree.

## Architecture Patterns

### System Architecture Diagram

```
Browser (Next.js client component)
  │
  │  GET /public/shops/{slug}         (shop details)
  │  GET /public/shops/{slug}/products (menu grouped by category)
  │  GET /public/shops/{slug}/promotions   ← STFR-01: already exists
  │  GET /public/shops/{slug}/announcements ← STFR-02: already exists
  │  GET /public/shops/{slug}/reviews
  │  (4–5 parallel fetches from useEffect via Promise.all)
  ▼
publicApiClient (axios, baseURL=http://localhost:9090)
  │
  │  Direct to core-java (NOT via edge-go — edge only covers
  │  /api/v1/sync/batch + /api/v1/webhooks/whatsapp)
  ▼
core-java :9090
  │  SecurityConfig.java:65 — /public/** permitAll
  │  WebConfig.java:23 — /api/v1 prefix does NOT include
  │                      uk.jtoye.core.storefront, so /public/**
  │                      lives at root
  ▼
PublicStorefrontController
  │  → PublicStorefrontService.getActivePromotions(slug)
  │  → shopRepository.findBySlugAndPublishedTrue(slug)
  │       (public RLS policy allows published=true SELECT w/o tenant)
  │  → promotionRepository.findActiveByShopId(shop.getId())
  │       (JPQL active=true AND validFrom<=NOW AND validUntil>NOW)
  ▼
shop_promotions / shop_announcements (Postgres, V28+)
```

### Recommended Project Structure

No new folders. Edits land in:

```
frontend/
├── app/shop/[slug]/
│   ├── page.tsx           # EDIT — replace /config with /promotions + /announcements, add badge
│   └── cart/page.tsx      # NO CODE EDIT — Jest test file needed
├── app/shop/orders/
│   └── page.tsx           # EDIT — add status filter, date filter, pagination
├── components/storefront/
│   └── cart-provider.tsx  # NO EDIT (reference only)
├── types/storefront.ts    # EDIT — add PublicPromotion + PublicAnnouncement types
├── e2e/storefront-flows.spec.ts  # EDIT — add promo-render assertion + Stripe test-mode spec
└── __tests__/shop/cart.test.tsx  # NEW — Jest for STFR-04

core-java/
└── src/test/java/uk/jtoye/core/storefront/
    └── PublicStorefrontControllerTest.java  # EDIT — add promotions + announcements MockMvc tests
```

### Pattern 1: Controller-level MockMvc Test Pattern (STFR-01/02)

**What:** `@WebMvcTest` with `@MockitoBean` for the service; `addFilters = false` to bypass `JwtTenantFilter`.
**When to use:** Every new endpoint in `PublicStorefrontController`.
**Example:** Copy the shape from `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java:62-101`:

```java
// Source: core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java
@WebMvcTest(PublicStorefrontController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicStorefrontControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PublicStorefrontService storefrontService;
    @MockitoBean private ReviewService reviewService;

    @Test
    void getShopPromotions_returns200WithActivePromotions() throws Exception {
        PublicPromotionDto dto = new PublicPromotionDto();
        dto.setLabel("Lunch special");
        dto.setDiscountType(DiscountType.PERCENTAGE);
        dto.setDiscountPercent(10);
        dto.setCategory("Mains");
        when(storefrontService.getActivePromotions("test-shop")).thenReturn(List.of(dto));

        mockMvc.perform(get("/public/shops/test-shop/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Lunch special"))
                .andExpect(jsonPath("$[0].discountPercent").value(10));
    }

    @Test
    void getShopPromotions_nonexistent_returns404() throws Exception {
        when(storefrontService.getActivePromotions("ghost"))
                .thenThrow(new ResourceNotFoundException("Shop not found: ghost"));
        mockMvc.perform(get("/public/shops/ghost/promotions"))
                .andExpect(status().isNotFound());
    }
}
```

The service-layer filter logic is already covered in `PublicStorefrontServiceTest.java:267-342` (three tests: `getActivePromotions_returnsFilteredList`, `getActiveAnnouncements_returnsFilteredList`, `getActivePromotions_shopNotFound_throws`). **No service edits required.**

### Pattern 2: Parallel Fetch in Client Component

**What:** `useEffect` with `Promise.all` — the same shape already in use at `frontend/app/shop/[slug]/page.tsx:213-241`.
**When to use:** Adding a new public GET alongside existing ones on the shop detail page.
**Example:**

```tsx
// Source: frontend/app/shop/[slug]/page.tsx:217-231 (existing pattern)
const [shopRes, productsRes, reviewsRes, promosRes, announcementsRes] = await Promise.all([
  publicApiClient.get<PublicShop>(`/public/shops/${slug}`),
  publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
  publicApiClient.get<{ content: Review[], totalElements: number }>(`/public/shops/${slug}/reviews?size=5`).catch(() => ({ data: { content: [], totalElements: 0 } })),
  publicApiClient.get<PublicPromotion[]>(`/public/shops/${slug}/promotions`).catch(() => ({ data: [] })),
  publicApiClient.get<PublicAnnouncement[]>(`/public/shops/${slug}/announcements`).catch(() => ({ data: [] })),
])
```

**Critical:** Wrap the new endpoints with `.catch(() => ({ data: [] }))` so a single failed endpoint does not nuke the whole page — existing code already does this for `/reviews` and `/config`.

### Pattern 3: Per-Product Promotion Lookup Memoisation (STFR-03)

**What:** Memo a `Map<string, PublicPromotion>` keyed by product category (the only join field on `ShopPromotion`).
**Why:** `ShopPromotion.category` at `ShopPromotion.java:33-34` is the join key; there is no `productId` FK. A promotion with `category="Mains"` applies to every product with `PublicProduct.category === "Mains"`.
**Example:**

```tsx
// Source: this research — pattern to add to frontend/app/shop/[slug]/page.tsx
const promotionsByCategory = useMemo(() => {
  const map = new Map<string, PublicPromotion>()
  for (const promo of promotions) {
    if (promo.category) map.set(promo.category, promo)
  }
  return map
}, [promotions])

// Inside ProductCard — pass promotionsByCategory as a prop or read via a
// lighter context. Avoid .find() per product-per-render.
const activePromo = product.category ? promotionsByCategory.get(product.category) : undefined
```

Pass `promotionsByCategory` into `ProductCard` as a prop — **do not** call `promotions.find(...)` inside `ProductCard`, it turns an O(P+N) render into O(P*N).

### Pattern 4: shadcn Badge for Discount Overlay

```tsx
// Source: frontend/components/ui/badge.tsx (existing component)
import { Badge } from "@/components/ui/badge"

{activePromo && (
  <Badge
    variant="destructive"
    className="absolute top-1.5 left-1.5 text-[10px] px-1.5 py-0 shadow-md"
  >
    {activePromo.discountType === "PERCENTAGE"
      ? `${activePromo.discountPercent}% off`
      : `£${(activePromo.discountAmountPennies! / 100).toFixed(2)} off`}
  </Badge>
)}
```

Overlay positioning: the product image container at `frontend/app/shop/[slug]/page.tsx:168-184` has `className="relative w-24 sm:w-28 flex-shrink-0"` — the `absolute` badge positions against it. Good existing precedent at line 178 (multi-image indicator).

### Anti-Patterns to Avoid

- **Do not** add SSR/Server Component data fetching to `app/shop/[slug]/page.tsx`. The entire file is `"use client"` (line 1) and all shop children need the `CartProvider` context from `frontend/app/shop/[slug]/layout.tsx`. Converting to a Server Component breaks `useCart()`.
- **Do not** fabricate a new `/api/v1/public/...` path. The storefront package is deliberately excluded from the `/api/v1` prefix at `WebConfig.java:23-33`. Paths stay at `/public/**`.
- **Do not** drop `/public/shops/{slug}/config`. It is still called on the shop page (`page.tsx:221`), and while STFR-03 replaces its promotions usage with the dedicated endpoint, `ShopConfig.featuredProducts` is still consumed by the existing featured section (`page.tsx:244-246, 512-524`). Either keep the config fetch or migrate featured-products off it first — but that is out of scope.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Active-time filtering | Custom JS filter on unfiltered promotion list | Server-side JPQL `validFrom <= NOW AND validUntil > NOW` (already implemented `ShopPromotionRepository.java:14`) | Clock drift, DST, tenant isolation |
| Tenant isolation for public endpoints | Tenant header on public calls | `ShopRepository.findBySlugAndPublishedTrue` with RLS public-published policy (`V28__shop_config.sql:26` etc) | Public clients have no tenant |
| Cart persistence | New Zustand/Redux | Existing `CartProvider` + localStorage at `frontend/components/storefront/cart-provider.tsx:32-53` with `jtoye-cart-{slug}` key namespaced by slug | Already memoised via `useMemo` PR #36 0ed3f5c |
| Customer auth gate | `useRouter().push('/signin')` on render | Existing `<RequireCustomerAuth>` wrapper at `frontend/components/storefront/require-customer-auth.tsx:16-65` | Already ships sign-in UI + PKCE flow |
| Pagination primitives | Custom paging controls | Client-side `.slice((page-1)*size, page*size)` for STFR-05 (dataset is small; server returns full list) | The backend endpoint returns an unpaginated list today; server-side pagination would require a repository + DTO change that's out of scope |
| Badge styling | Hand-rolled `<span>` | `@/components/ui/badge` (`variant="destructive"`) | Already in tree with CVA variants |

**Key insight:** Because STFR-01/02 backend + service tests and STFR-04/05 frontend components already exist, this phase is mostly "wire + test + fill gaps," not a net-new build. Hand-rolling anything here would be pure waste.

## Runtime State Inventory

Not applicable — this is a pure code-addition phase (new frontend renders, new controller tests, new Jest/Playwright specs). No renames, no data migrations, no OS registrations.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None | — |
| Live service config | None | — |
| OS-registered state | None | — |
| Secrets / env vars | None new. Phase reuses existing `NEXT_PUBLIC_API_URL=http://localhost:9090` from `frontend/.env.local:2` | — |
| Build artifacts | None | — |

## Common Pitfalls

### Pitfall 1: Path is `/public/**`, not `/api/v1/public/**`
**What goes wrong:** Task author adds `/api/v1` to the promotions URL on the frontend and gets 404.
**Why it happens:** Every other endpoint uses `/api/v1/` and it's the project default.
**How to avoid:** The `/api/v1` prefix is injected by `WebConfig.java:23` via `HandlerTypePredicate.forBasePackage(...)` and the list **does not include `uk.jtoye.core.storefront`**. The existing shop detail page at `frontend/app/shop/[slug]/page.tsx:218-221` already calls `/public/shops/${slug}` without `/api/v1` — copy that shape.
**Warning signs:** 404 on manually constructed URLs; the working shop fetch right above the new one uses the bare `/public` path.

### Pitfall 2: Frontend bypasses the edge gateway for public calls
**What goes wrong:** Task author tries to route public calls through `edge-go :8080` expecting rate limiting + circuit breaker.
**Why it happens:** The mental model "everything goes through the edge" is wrong for public paths.
**How to avoid:** `frontend/lib/public-api-client.ts:3-8` + `frontend/.env.local:2` point directly at `http://localhost:9090` (core-java). The Go edge at `edge-go/cmd/edge/main.go:178-215` only proxies `/api/v1/sync/batch` and `/api/v1/webhooks/whatsapp` — both inside `protected` JWT middleware. Public storefront calls never touch edge-go.
**Warning signs:** Calling `http://localhost:8080/public/...` → 404 from Gin; rate limit errors in unexpected places.

### Pitfall 3: `"use client"` + Next.js 16 `params` is a Promise
**What goes wrong:** `params.slug` returns `undefined` (or triggers a hydration warning).
**Why it happens:** Next.js 16 App Router passes `params` as `Promise<{ slug: string }>` and requires `React.use()` to unwrap.
**How to avoid:** Copy the existing pattern at `frontend/app/shop/[slug]/page.tsx:201-202`:
```tsx
import { use } from "react"
export default function ShopDetailPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  // ...
}
```
The cart page already does this (`frontend/app/shop/[slug]/cart/page.tsx:3, 12-13`). The orders page doesn't need it (no dynamic segment).
**Warning signs:** `Cannot destructure property 'slug' of 'params' as it is undefined`; runtime warning about sync access to params.

### Pitfall 4: Cart localStorage hydration mismatch (SSR vs client first paint)
**What goes wrong:** On first render, `items.length === 0` (SSR snapshot) but a moment later `items` populates from localStorage → React logs a hydration mismatch warning and the page briefly flashes "empty cart" before showing items.
**Why it happens:** `CartProvider` uses `useState([])` and loads from localStorage only inside `useEffect` (`frontend/components/storefront/cart-provider.tsx:62-67`). Anything that renders conditionally on `items.length` before that effect flushes will flicker.
**How to avoid:** The current `/cart/page.tsx` relies on this and is acceptable for the empty branch because both server and client render the same empty state initially. For Jest tests, manually `localStorage.setItem` before rendering and **then** render with `<CartProvider shopSlug="x">`. Reference: `cart-provider.tsx:36-47` for the exact storage key + shape.
**Warning signs:** Hydration mismatch warnings in dev console; flash of empty state on refresh.

### Pitfall 5: Customer order history endpoint requires `verify` param for unknown callers
**What goes wrong:** Calling `GET /public/orders?email=...` anonymously returns a list but **only because the `verify` param is optional at `PublicStorefrontController.java:95-104`**. The controller's own comment (line 97-98) flags this: "Without this, anyone who knows an email can enumerate all their orders."
**Why it happens:** The security model relies on the UI being reachable only through `RequireCustomerAuth`, which proves the caller owns the email via Keycloak sign-in. Direct API callers bypass this.
**How to avoid:** For STFR-05, **do not change this endpoint shape** — the phase-10 UI is already gated by `RequireCustomerAuth`. The correct long-term fix is server-side claim validation against the Keycloak access token. Note this as a follow-up but **out of scope** for phase 10 (it would be a separate security ticket).
**Warning signs:** Anyone thinking "just use tenant_id" (wrong — public has no tenant) or "just forward the access token" (possible but requires new middleware + scope).

### Pitfall 6: Stripe test-mode checkout is not wired in the seed data
**What goes wrong:** STFR-06 asks for a Playwright run that reaches "Stripe test-mode payment → confirmation." The existing checkout test at `frontend/e2e/storefront-flows.spec.ts:202-232` gets around this by hitting **COD fallback** because `PaymentService.isConfigured()` returns false (no Stripe key in `.env.local`).
**Why it happens:** `PublicStorefrontService.createGuestOrder` at line 422-435 branches: if Stripe configured → create PaymentIntent + require client confirmation; else → mark order `PENDING`, `paymentMethod = "Cash on Delivery"`, skip PaymentElement.
**How to avoid:** Two honest options:
  1. **Stay on COD path** (cheapest) — the e2e is "real" end-to-end, just not testing Stripe specifically. Rewrite STFR-06 verification to "checkout completes + order persists + confirmation screen renders" without requiring card entry.
  2. **Enable Stripe test mode** — set `STRIPE_SECRET_KEY=sk_test_...` + `STRIPE_PUBLISHABLE_KEY=pk_test_...` in `.env.local` for local e2e, `4242 4242 4242 4242` as the test card, but this requires (a) a Stripe account, (b) CI secret injection, and (c) Playwright card-element interaction which is flaky via iframe.
**Recommendation for the planner:** Go with option 1. The existing test at `storefront-flows.spec.ts:202-232` already passes — STFR-06 can extend it with banner/badge assertions rather than add Stripe.
**Warning signs:** A task that assumes a Stripe test key exists; Playwright test that iframes into `js.stripe.com` without a timeout strategy.

### Pitfall 7: `PublicStorefrontServiceTest` mocks Hibernate session work — MockMvc tests must NOT
**What goes wrong:** Copy-pasting the Hibernate `Session.doWork()` setup from `PublicStorefrontServiceTest.java:62-71` into the controller test class.
**Why it happens:** The service test uses real service logic and mocks the persistence layer; the controller test should mock the service entirely.
**How to avoid:** Controller tests use `@WebMvcTest(PublicStorefrontController.class)` + `@MockitoBean private PublicStorefrontService` (see the existing test at lines 28-39). No Hibernate in scope. `addFilters = false` disables the security/tenant filters for the slice test.
**Warning signs:** `Unnecessary stubbings detected` warnings; mocks of `EntityManager` / `Connection` / `PreparedStatement` inside a `@WebMvcTest`.

## Code Examples

### Example 1: Adding the promotions fetch + banner + badge to the shop detail page

```tsx
// Source: research — edit for frontend/app/shop/[slug]/page.tsx
"use client"
import { useMemo, useState, useEffect, use } from "react"
import publicApiClient from "@/lib/public-api-client"
import { Badge } from "@/components/ui/badge"
import type { PublicPromotion, PublicAnnouncement } from "@/types/storefront"

// 1. Add to types/storefront.ts
export interface PublicPromotion {
  label: string
  discountType: "PERCENTAGE" | "FLAT_AMOUNT"
  discountPercent: number | null
  discountAmountPennies: number | null
  category: string | null
  validUntil: string
}

export interface PublicAnnouncement {
  title: string
  body: string | null
  validUntil: string | null
}

// 2. In the shop detail page:
const [promotions, setPromotions] = useState<PublicPromotion[]>([])
const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>([])

useEffect(() => {
  async function load() {
    const [shopRes, productsRes, reviewsRes, promosRes, annRes] = await Promise.all([
      publicApiClient.get<PublicShop>(`/public/shops/${slug}`),
      publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
      publicApiClient.get<{ content: Review[], totalElements: number }>(`/public/shops/${slug}/reviews?size=5`).catch(() => ({ data: { content: [], totalElements: 0 } })),
      publicApiClient.get<PublicPromotion[]>(`/public/shops/${slug}/promotions`).catch(() => ({ data: [] })),
      publicApiClient.get<PublicAnnouncement[]>(`/public/shops/${slug}/announcements`).catch(() => ({ data: [] })),
    ])
    setShop(shopRes.data)
    setProducts(productsRes.data)
    setPromotions(promosRes.data)
    setAnnouncements(annRes.data)
    // ...rest as before
  }
  load()
}, [slug])

// 3. Memoise the category→promotion map
const promotionsByCategory = useMemo(() => {
  const map = new Map<string, PublicPromotion>()
  for (const p of promotions) {
    if (p.category) map.set(p.category, p)
  }
  return map
}, [promotions])

// 4. Pass into ProductCard:
<ProductCard key={product.id} product={product} promo={product.category ? promotionsByCategory.get(product.category) : undefined} />

// 5. Inside ProductCard, overlay on the image container (line 169):
<div className="relative w-24 sm:w-28 flex-shrink-0">
  <SafeImage ... />
  {promo && (
    <Badge variant="destructive" className="absolute top-1.5 left-1.5 text-[10px] px-1.5 py-0 shadow-md">
      {promo.discountType === "PERCENTAGE" ? `${promo.discountPercent}% off` : `£${(promo.discountAmountPennies! / 100).toFixed(2)} off`}
    </Badge>
  )}
</div>
```

### Example 2: Jest test for standalone cart page (STFR-04)

```tsx
// Source: research — new file frontend/__tests__/shop/cart.test.tsx
import { render, screen } from "@testing-library/react"
import CartPage from "@/app/shop/[slug]/cart/page"
import { CartProvider } from "@/components/storefront/cart-provider"

function renderWithCart(slug: string, seed?: unknown) {
  if (seed) localStorage.setItem(`jtoye-cart-${slug}`, JSON.stringify(seed))
  return render(
    <CartProvider shopSlug={slug}>
      <CartPage params={Promise.resolve({ slug })} />
    </CartProvider>
  )
}

describe("CartPage", () => {
  afterEach(() => localStorage.clear())

  it("renders empty state when no items", async () => {
    renderWithCart("jollof-express")
    expect(await screen.findByText("Your basket is empty")).toBeInTheDocument()
    expect(screen.getByText("Back to menu")).toHaveAttribute("href", "/shop/jollof-express")
  })

  it("renders items when cart has contents", async () => {
    renderWithCart("jollof-express", {
      shopSlug: "jollof-express",
      items: [{ productId: "p1", title: "Jollof Rice", pricePennies: 899, quantity: 2, imageUrl: null, category: "Mains" }],
    })
    expect(await screen.findByText("Jollof Rice")).toBeInTheDocument()
    expect(screen.getByText("Proceed to checkout")).toBeInTheDocument()
  })
})
```

Note: `CartPage` component takes a `Promise<{slug}>` — wrap in `Promise.resolve({slug})` for tests.

### Example 3: Filter + pagination for customer orders (STFR-05)

```tsx
// Source: research — edit for frontend/app/shop/orders/page.tsx
const [statusFilter, setStatusFilter] = useState<string>("ALL")
const [dateFrom, setDateFrom] = useState<string>("")
const [page, setPage] = useState(1)
const PAGE_SIZE = 10

const filtered = useMemo(() => {
  return orders.filter(o => {
    if (statusFilter !== "ALL" && o.status !== statusFilter) return false
    if (dateFrom && new Date(o.createdAt) < new Date(dateFrom)) return false
    return true
  })
}, [orders, statusFilter, dateFrom])

const paged = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)
const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
```

Client-side because: (a) the `/public/orders` endpoint returns the full list today, (b) dataset per customer is bounded in the low hundreds, (c) server-side pagination would require a new paginated endpoint, which is out of scope. Use `<select>` for status, `<input type="date">` for date, plain `<button>` prev/next for pagination.

### Example 4: e2e assertion for promotion banner (STFR-06 extension)

```typescript
// Source: research — edit for frontend/e2e/storefront-flows.spec.ts
test("promotion banner and discount badge render on shop detail", async ({ page }) => {
  // Seed data assumption: jollof-express-brixton-900b57a8 has at least one
  // active promotion in shop_promotions (add a fixture if missing; see
  // core-java/src/main/resources/db/migration/V30+ for the seed pattern).
  await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("networkidle")

  // Banner (announcement) exists above the menu
  const banner = page.locator("div.bg-amber-50, div.bg-blue-50").first()
  await expect(banner).toBeVisible({ timeout: 5000 })

  // At least one product card shows a destructive-variant badge matching the
  // "% off" or "£X off" pattern
  const discountBadge = page.locator('article >> text=/\\d+%\\s+off|£\\d+\\.\\d{2}\\s+off/').first()
  await expect(discountBadge).toBeVisible({ timeout: 5000 })
})
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `/public/shops/{slug}/config` returns everything (bundled) | Dedicated `/promotions` + `/announcements` endpoints alongside `/config` | Phase 3 (2026-04-08, commit around V29) | Shop detail page can drop config dependence for promo rendering |
| Cart modal only | Cart modal + standalone `/cart` page | Already shipped | Direct URL works; localStorage shared |
| Guest-only order tracking by number | Customer-authenticated `/shop/orders` page + `RequireCustomerAuth` | Shipped 2026-04-xx per PR #36 | Customer loyalty loop closed |

**Deprecated/outdated:**
- `ShopConfigDto.activePromotions` (server-side legacy): still in use at `PublicStorefrontService.getShopConfig()` — **do not remove** in this phase; only migrate the shop detail page away from it. Removal is a separate deprecation cycle.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Client-side pagination is acceptable for STFR-05 given typical per-customer order count (<100) | Don't Hand-Roll | If customers average >500 orders, page render slows; migrate to server pagination |
| A2 | STFR-06's "Stripe test mode" requirement can be satisfied via COD flow + banner/badge assertions, since adding Stripe test-mode config is out of scope | Pitfall 6 | If reviewer insists on real Stripe Elements interaction, need new task to wire `pk_test_...` + card entry |
| A3 | The seed shop `jollof-express-brixton-900b57a8` has or will have at least one active promotion fixture in `shop_promotions` | Code Example 4 | If no seed exists, the Playwright assertion needs a `@BeforeAll` API call to insert one (requires vendor-auth JWT) — or the task adds a SQL seed to `V33` / `test-data.sql` |
| A4 | `/public/orders?email=` endpoint's lack of server-side claim validation is acceptable within phase scope (UI gated by `RequireCustomerAuth`) | Pitfall 5 | Medium — it's a soft enumeration risk but the UI path does prove email ownership via Keycloak |

## Open Questions

1. **Should STFR-03 drop the `/config` fetch entirely or keep it for featuredProducts?**
   - What we know: `page.tsx:221` also uses `ShopConfig.featuredProducts`; `page.tsx:244-246` derives `featuredProducts` from the regular product map, so the config-driven featured is currently unused.
   - What's unclear: Whether the vendor-supplied `Shop.featuredProductIds` (line 105-110 in `PublicStorefrontService`) is a planned or dead feature.
   - Recommendation: Keep `/config` fetch but stop using its `activePromotions` + `announcements` fields; replace those with the dedicated endpoints. Defer dead-code cleanup.

2. **Does STFR-05 need a backend endpoint extension or can it reuse `/public/orders`?**
   - What we know: `/public/orders?email=` already returns a full `List<PublicOrderStatus>` (`PublicStorefrontController.java:91-104`).
   - What's unclear: Whether product owners consider client-side filtering acceptable — the REQUIREMENTS.md bullet says "with status filter + date filter + pagination" without specifying client/server.
   - Recommendation: Client-side for this phase; flag server-side pagination as a v2 follow-up if the order count ever warrants it.

3. **Fixture for Playwright promo assertion.**
   - What we know: No seed `V*` migration adds a row to `shop_promotions`.
   - What's unclear: Whether the e2e should mutate state via the vendor API before asserting, or rely on a new SQL seed.
   - Recommendation: Add a `V33__test_seed_promotion.sql` conditionally gated to `spring.profiles.active=dev` OR better: have the Playwright test call the vendor API with a test token to insert/cleanup a promotion. Decide in planning.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker + full-stack compose | Playwright e2e | ✓ | `docker-compose.full-stack.yml` present | — |
| Keycloak seed realm | Customer login for STFR-05 e2e | ✓ | `infra/keycloak/realm-export.json` | — |
| Mailhog | Order confirmation email test (existing) | ✓ | `:8025` per existing test | — |
| Stripe test account | STFR-06 real card flow (not recommended) | ✗ | — | Use COD path (Pitfall 6) |
| Jest + @testing-library | STFR-04 unit test | ✓ | Per CLAUDE.md project stack | — |

**Missing with fallback:** Stripe test mode → use COD.
**Missing blocking:** None.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework (backend) | JUnit 5 + Spring Boot Test 3.4.2 + MockMvc |
| Framework (frontend unit) | Jest 29.7.0 + @testing-library/react |
| Framework (e2e) | Playwright 1.59.1 |
| Config file (backend) | `core-java/build.gradle.kts` |
| Config file (frontend unit) | `frontend/jest.config.*` (implied by package.json) |
| Config file (e2e) | `frontend/playwright.config.ts` |
| Quick run (backend slice test) | `./gradlew test --tests PublicStorefrontControllerTest` |
| Quick run (frontend unit) | `npm --prefix frontend test -- cart.test` |
| Quick run (e2e one spec) | `npx playwright test e2e/storefront-flows.spec.ts --project=desktop` |
| Full suite (backend) | `./gradlew test` |
| Full suite (frontend) | `npm --prefix frontend test && npx playwright test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STFR-01 | Active promotions endpoint returns filtered list + 404 on unknown shop | integration (MockMvc) | `./gradlew test --tests PublicStorefrontControllerTest.getShopPromotions*` | ❌ Wave 0 — add to existing file |
| STFR-01 | Service layer filter logic | unit | `./gradlew test --tests PublicStorefrontServiceTest.getActivePromotions*` | ✅ (lines 267-342) |
| STFR-02 | Active announcements endpoint returns filtered list + 404 | integration (MockMvc) | `./gradlew test --tests PublicStorefrontControllerTest.getShopAnnouncements*` | ❌ Wave 0 — add to existing file |
| STFR-02 | Service layer filter logic | unit | `./gradlew test --tests PublicStorefrontServiceTest.getActiveAnnouncements*` | ✅ (lines 305-333) |
| STFR-03 | Banner + discount badge render | e2e | `npx playwright test -g "promotion banner"` | ❌ Wave 0 — add to storefront-flows.spec.ts |
| STFR-04 | Cart empty + populated states | unit (Jest + RTL) | `npm --prefix frontend test -- cart` | ❌ Wave 0 — new file `__tests__/shop/cart.test.tsx` |
| STFR-05 | Status/date filter + pagination | unit (Jest) + e2e check | `npm --prefix frontend test -- orders-filter` + existing e2e | ❌ Wave 0 — new test file + filter/pagination edits |
| STFR-06 | Full browse→add→cart→checkout→confirmation | e2e | `npx playwright test -g "add items, view cart, modify quantity, checkout"` | ✅ (lines 202-232 — extend with promo assertion) |

### Sampling Rate
- **Per task commit:** Backend: `./gradlew test --tests PublicStorefrontControllerTest` (~15s slice test). Frontend: `npm --prefix frontend test -- cart` (~5s).
- **Per wave merge:** Backend full: `./gradlew test` (~4 min, 335 tests). Frontend full: `npm test && npx playwright test --project=desktop` (~3 min).
- **Phase gate:** Full suite green before `/gsd-verify-work`. Docker stack must be up for Playwright.

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` — add 4 new tests (2 per endpoint, success + 404)
- [ ] `frontend/__tests__/shop/cart.test.tsx` — new file, 2 tests (empty + populated)
- [ ] `frontend/__tests__/shop/orders-filter.test.tsx` — new file, tests for status filter + date filter + pagination pure logic
- [ ] `frontend/e2e/storefront-flows.spec.ts` — add `test("promotion banner and discount badge render...")` and assert the existing checkout path still ends on confirmation (STFR-06 extension)
- [ ] No framework install needed

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes (STFR-05) | Keycloak OIDC + HttpOnly cookie session — already shipped PR #36 (`frontend/lib/customer-auth.ts`) |
| V3 Session Management | yes (STFR-05) | `/api/customer-auth/session` Next.js API route; no raw tokens in JS |
| V4 Access Control | partial | `/public/**` is deliberately permitAll but `/public/orders?email=` has a soft enumeration weakness (Pitfall 5) — UI is gated by `RequireCustomerAuth` |
| V5 Input Validation | yes | `@Valid` on `CreateReviewRequest` / `GuestOrderRequest`; new endpoints take only `@PathVariable String slug` — safe |
| V6 Cryptography | no | No new crypto introduced |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Tenant enumeration via slug guessing | Information Disclosure | `findBySlugAndPublishedTrue` already filters unpublished; 404 is returned, no tenant leak |
| Customer-email enumeration on `/public/orders` | Information Disclosure | Soft — relies on `RequireCustomerAuth` UI gate + `verify` order number hint; accepted for phase scope (Pitfall 5) |
| Stored XSS via promotion label or announcement body | Tampering | Vendor CRUD validation on inbound; React auto-escapes all string interpolation on render — do NOT introduce `dangerouslySetInnerHTML` for either field |
| localStorage tampering (cart) | Tampering | Server-side price re-lookup at `PublicStorefrontService.createGuestOrder:401` (comment: "Server-side price — never trust client") — already enforced |
| CSRF on `/public/orders` POST | Tampering | `SecurityConfig.java` disables CSRF for APIs (justified at line 52 per PR #33 `34ea652`); endpoint is unauthenticated guest order so CSRF is moot |

## Sources

### Primary (HIGH confidence — verified by Read)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` — full controller read
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` — full service read; promotion + announcement methods at lines 128-162
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotion.java` and `ShopAnnouncement.java` — entity shape
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotionRepository.java:14` — active filter JPQL
- `core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncementRepository.java:12-16` — active filter JPQL
- `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java:23-33` — `/api/v1` prefix scope (excludes storefront)
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:65` — `/public/**` permitAll
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` — existing MockMvc pattern
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` — existing service tests for promo/announce at lines 267-342
- `frontend/app/shop/[slug]/page.tsx` — full shop detail page; existing shopConfig render at lines 421-443
- `frontend/app/shop/[slug]/cart/page.tsx` — existing standalone cart
- `frontend/app/shop/orders/page.tsx` — existing customer order history (no filter/pagination yet)
- `frontend/components/storefront/cart-provider.tsx` — CartProvider shape + `jtoye-cart-{slug}` key
- `frontend/components/storefront/require-customer-auth.tsx` — auth gate
- `frontend/lib/customer-auth.ts` — HttpOnly cookie session pattern
- `frontend/lib/public-api-client.ts` — axios client pointing at `NEXT_PUBLIC_API_URL` (direct to core-java:9090)
- `frontend/types/storefront.ts` — current public type defs
- `frontend/e2e/storefront-flows.spec.ts` — existing e2e coverage
- `frontend/playwright.config.ts` — test projects mobile + desktop, sequential, 60s timeout
- `frontend/components/ui/badge.tsx` — shadcn Badge variants
- `edge-go/cmd/edge/main.go:178-215` — proves edge does not proxy `/public/**`
- `docker-compose.full-stack.yml:149,178,202` — port mappings
- `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md:462-474` — Blocker 2 + Blocker 3 original finding
- `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md:590-609` — Work Order B scope
- `.planning/REQUIREMENTS.md:34-54` — STFR-01..06 canonical definitions

### Secondary (MEDIUM confidence)
- None — every claim above is verified against a source file in this session.

### Tertiary (LOW confidence)
- None.

## Metadata

**Confidence breakdown:**
- Backend surface (STFR-01/02): HIGH — code and service tests already exist; only MockMvc controller tests missing
- Frontend rendering (STFR-03): HIGH — pattern established; clean edit with memoized lookup
- Cart route (STFR-04): HIGH — component exists, only Jest test needed
- Orders route (STFR-05): HIGH — component exists, needs filter/pagination edits
- e2e (STFR-06): MEDIUM — depends on assumption A2 (stay on COD path, not Stripe test mode)

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable stack, no upcoming framework upgrades)
