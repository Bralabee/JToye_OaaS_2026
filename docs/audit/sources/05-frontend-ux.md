# Frontend / UX Audit
**Auditor persona**: Senior FE engineer, B2C food-delivery experience (Deliveroo / Just Eat)
**Date**: 2026-04-27
**Code quality score**: 7/10
**Design quality score**: 5.5/10 — looks like a real food product on the storefront, looks like a shadcn admin starter on the dashboard
**Mobile-first reality score**: 6/10 — storefront is mobile-first; vendor admin is desktop-only

---

## Route inventory

19 page routes, 53 `.tsx` files in `app/` + `components/`. Three product surfaces:

**Customer storefront** (`/shop/*` — `frontend/app/shop/`):
- `/shop` — vendor discovery grid with search and pagination
- `/shop/[slug]` — shop detail with menu by category, sticky category nav, floating cart
- `/shop/[slug]/cart` — basket review
- `/shop/[slug]/checkout` — Stripe Elements payment flow
- `/shop/[slug]/orders/[orderNumber]` — single-order receipt
- `/shop/orders` — customer order history
- `/shop/auth/callback` — customer OIDC callback
- `/track` — public order tracker (PENDING → COMPLETED step UI)

**Vendor admin** (`/dashboard/*` — `frontend/app/dashboard/`):
- `/dashboard` — overview with PieChart + BarChart
- `/dashboard/shops` (600 LOC), `/dashboard/products` (889 LOC), `/dashboard/orders` (951 LOC), `/dashboard/customers` (529 LOC), `/dashboard/finance` (314 LOC), `/dashboard/marketing` (1231 LOC), `/dashboard/kitchen` (482 LOC, KDS), `/dashboard/products/import`

**Auth + API**:
- `/auth/signin` (Keycloak SSO), `/api/auth/[...nextauth]`, `/api/customer-auth/{login,logout,session,logout-url}`

Server vs client split: 19 pages, **22 client components** in `app/` (most pages are `"use client"`). The two layouts that matter — `app/dashboard/layout.tsx` and `app/shop/layout.tsx` — are server components and do auth-gating server-side (`auth()` + `redirect()`). That part is correct; the layout boundary is the right place for it. Comment in `app/dashboard/layout.tsx` even calls out the prior bug (blank flash on expired sessions).

Loading / error / not-found: three `error.tsx` boundaries (`app/`, `app/shop/`, `app/dashboard/`). **Zero `loading.tsx` files, zero `not-found.tsx`**, zero use of Suspense for data fetching (one `Suspense` boundary in `app/track/page.tsx` wraps `useSearchParams`, not data). Every page rolls its own `loading` boolean + spinner; no streaming SSR.

---

## Architecture quality

Clean module split: `app/` (routes), `components/{ui,dashboard,storefront}` (organised by surface, not just generic UI), `hooks/`, `lib/`, `types/`. The `@/` path alias is used consistently. The shadcn primitives (`button.tsx`, `card.tsx`, `dialog.tsx`, `select.tsx`, `dropdown-menu.tsx`, `pagination.tsx`, `table.tsx`, `toast.tsx`) are stock — no custom variants beyond the cva defaults in `components/ui/button.tsx`, which still ships with `default | destructive | outline | secondary | ghost | link`. That's the giveaway: the primitive layer was never adapted to the brand.

**State management** — three patterns, all sensible at their scope:
- `CartProvider` (`components/storefront/cart-provider.tsx`) — Context per shop slug, persists to `localStorage` keyed by slug, uses `useMemo` and `useCallback` properly to avoid re-renders. Solid work, this is the cleanest provider in the codebase.
- `useStomp` (`hooks/use-stomp.ts`, 112 LOC) — STOMP/WebSocket abstraction for KDS live updates with reconnect.
- React Hook Form + Zod for every dashboard form (consistent across products, shops, marketing, orders).
- Server state: no SWR / TanStack Query / RSC streaming. Every page does `useEffect → fetch → setState`, with manual refetch after mutations and no stale-while-revalidate, no refetch-on-focus. Vendor switches tabs and waits for spinner.

**Page sizes are alarming**. `app/dashboard/marketing/page.tsx` is **1231 lines**, `app/dashboard/orders/page.tsx` is 951, `app/dashboard/products/page.tsx` is 889. These are monolithic client components with form schemas, dialogs, tables, and helpers all inlined. The KDS page (`app/dashboard/kitchen/page.tsx`, 482 LOC) is closer to acceptable but still has audio + WS + state machine + UI in one file.

**TypeScript discipline is excellent**. Grep for `: any` or `as any` across `app/`, `components/`, `hooks/`, `lib/`, `types/` returns **1 hit, and it's in a comment** (`app/dashboard/marketing/page.tsx:204`). Types are centralised in `types/api.ts` (291 LOC) and `types/storefront.ts`. Discriminated unions used reasonably (`OrderStatus` enum, `ItemStatus` literal union in marketing). Strict mode on. This is well above industry median.

**`api-client.ts` is genuinely good.** Read `frontend/lib/api-client.ts:62-102` — a singleton refresh promise debounces parallel 401s, 5xx retries with backoff, single-flight session refresh. This is the kind of thing junior teams ship as a TODO and never come back to.

---

## Design quality (the brutal honest read)

**Two products, two tiers of design care.**

The **storefront** (`app/shop/page.tsx`, `app/shop/[slug]/page.tsx`, `app/shop/[slug]/checkout/page.tsx`, `components/storefront/storefront-nav.tsx`) is genuinely well-designed. Orange/rose gradient banners, emerald "Open" pulses, shop logos overlaid on banners with `ring-2 ring-white`, dietary badges with semantic colour (vegan emerald, spicy red, gluten amber, halal teal — see `app/shop/[slug]/page.tsx:48-56`), a floating bottom cart bar that turns slate when below minimum order (`app/shop/[slug]/page.tsx:605-643`), sticky category navigation, skeleton loaders that match real card geometry, and quantity steppers in the product cards. This is Deliveroo-adjacent. It would not embarrass the brand.

The **vendor dashboard** is a different product. Open `components/dashboard/sidebar.tsx`:
- Logo icon: `text-blue-400` (line 57)
- User avatar: `bg-gradient-to-br from-blue-400 to-purple-500` (line 68)
- Active nav item: `bg-blue-600 text-white shadow-lg shadow-blue-500/50` (line 90)
- Background: `bg-slate-900`

This is a Vercel/shadcn template. There is no orange or emerald anywhere in the chrome. The dashboard page itself (`app/dashboard/page.tsx`) reinforces it: stat cards use `text-blue-600 / text-purple-600 / text-green-600 / text-orange-600` as four different category colours (lines 152-157), the loading spinner is `border-blue-600` (line 147), the BarChart fills are `#3b82f6` and `#a855f7` (blue + purple).

The smoking gun is `app/globals.css`. The CSS custom properties are **literally the shadcn defaults**: `--primary: 221.2 83.2% 53.3%` (lines 13, 25 — that is shadcn-blue) and the dark variant uses `--primary: 217.2 91.2% 59.8%` (line 36 — also blue). The `tailwind.config.ts` wires `bg-primary` to `hsl(var(--primary))`, so every `<Button variant="default">` (used throughout dashboard forms) is shadcn blue. The food-delivery palette only exists as **hardcoded utility classes on the storefront pages** — `bg-orange-500`, `text-emerald-600`. Grep counts: `bg-orange|bg-emerald` 54 hits, `bg-blue|text-blue` 26, `bg-purple|text-purple|to-purple|from-purple` 13 hits. The brand was applied page-by-page on the storefront and never touched the design tokens or the admin chrome.

After the rejected "Warm Editorial" PR #49, there was a chance to actually unify the brand. That didn't happen. The dashboard still looks like the day after `npx shadcn-ui init`.

Component density is acceptable on the storefront (shop detail menu uses `gap-3` with horizontal product cards, `text-xs` micro-meta) but hostile on the dashboard: tables jammed against form dialogs, no whitespace rhythm, status badges using six different vivid colours per row (DRAFT gray, PENDING yellow, CONFIRMED blue, PREPARING purple, READY green, COMPLETED emerald, CANCELLED red — `app/dashboard/orders/page.tsx:73-119` and again duplicated in dashboard/page.tsx). Could you put this side-by-side with Deliveroo's vendor portal? No. Would you ship the storefront against Deliveroo Marketplace? With work — yes, the bones are good.

`framer-motion` is imported in 8 files but only does `opacity 0→1` and small `y: 20→0` entrance animations. It's overkill — a 60kB+ runtime dependency to fade in cards. Replace with CSS `@keyframes` and you delete the dep.

---

## Mobile-first reality

Storefront: real mobile-first. `app/shop/page.tsx` uses `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`, banner `h-36 sm:h-44`, hero `text-2xl sm:text-3xl`. Floating cart bar pinned `fixed bottom-0` with `safe` padding. Product cards collapse to horizontal layout for thumb-reachable taps. This is the right pattern.

Dashboard: **not mobile**. `components/dashboard/sidebar.tsx:54` is `flex h-full w-64 flex-col bg-slate-900` — fixed 16rem width, no `md:` breakpoint, no hamburger toggle, no responsive collapse. On a 375px iPhone, this consumes 68% of the viewport. `components/dashboard/dashboard-shell.tsx:16-22` wraps it in `flex h-screen` with `container mx-auto p-8` — `p-8` (32px) is also non-responsive. The dashboard mostly uses `md:grid-cols-2 lg:grid-cols-4` patterns inside pages, which works for cards, but the chrome that frames everything ignores phones entirely. A vendor opening this on the move sees a wall of slate-900 sidebar and a sliver of content.

KDS (`app/dashboard/kitchen/page.tsx`) — at least uses `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4` for order tiles. But it lives inside the same broken sidebar shell.

---

## Accessibility

This is the weakest area. **Zero `aria-label` attributes in the entire codebase** (grep `app/ components/` returns 0). 39 native `<button>` tags vs only 2 `<div onClick>` (good — semantic HTML mostly), but icon-only buttons everywhere lack accessible names. The mute toggle (`app/dashboard/kitchen/page.tsx:376-387`) has a `title` attribute (tooltip, ignored by most screen readers) but no `aria-label`. The signout `<button>` in `components/storefront/storefront-nav.tsx:82-88` is icon-only with `title="Sign out"` — same problem. The cart quantity `+` and `-` controls (`app/shop/[slug]/page.tsx:151-163`) are unlabeled.

Radix primitives (`@radix-ui/react-dialog`, `react-select`, `react-dropdown-menu`) handle focus trap and keyboard nav internally — that's a free win. But forms don't associate `<Label>` with `<Input>` via `htmlFor` everywhere, no `aria-invalid` or `aria-describedby` for validation errors, and the toast announcer hasn't been verified for `aria-live`.

Colour contrast: `text-slate-400` on `bg-white` (used heavily for meta text, e.g. `app/shop/[slug]/page.tsx:139`) is around 3.5:1 — fails WCAG AA for body text (4.5:1). The `bg-orange-500` with white text passes (4.7:1) but only just. The dashboard's `text-slate-300` on `bg-slate-900` (sidebar nav) is fine.

No `<html lang>` issue (`app/layout.tsx:19` sets `lang="en"`). `suppressHydrationWarning` is on the `<html>` for the dark-mode toggle in the sidebar — acceptable, but the dark-mode implementation only flips a class and the storefront pages are not dark-mode aware (hardcoded `bg-white`).

---

## Performance

**Critical: `next/image` is used zero times.** `frontend/components/ui/safe-image.tsx` returns a raw `<img src=... onError=...>` (line 38). Every shop banner, product photo, review photo, and logo across the storefront and dashboard is an unoptimised `<img>`. That defeats the entire `next.config.mjs` `remotePatterns` block, which is now dead config. No automatic responsive `srcSet`, no AVIF/WebP, no lazy-loading via the Next image loader, no LCP optimisation. On a shop page with 30 product cards each loading a banner-resolution image from MinIO, this is several MB transferred for a phone view. Easy fix: `<Image>` with `fill` and `sizes`, keep the error fallback.

Bundle red flags:
- `framer-motion` ^12 imported eagerly in 8 files for trivial fades.
- `recharts` ^3.8 in `app/dashboard/page.tsx` — at least it's only on the dashboard, but no `dynamic()` import — it ships in the dashboard initial bundle.
- `@stripe/stripe-js` + `@stripe/react-stripe-js` only loaded in `app/shop/[slug]/checkout/page.tsx` (correct — checkout page only).
- `lucide-react` used everywhere — fine, it's tree-shakable.
- **Zero `dynamic()` imports across the entire app.** No route-level code splitting beyond what Next.js does for `/dashboard` vs `/shop`.

`apiClient` retry/refresh is well-designed but every page does `Promise.all([6 calls])` on mount with no caching layer. The dashboard hits 7 endpoints on every load (`app/dashboard/page.tsx:79-88`), the shop detail hits 6 (`app/shop/[slug]/page.tsx:230-237`). A lightweight TanStack Query layer would cut perceived latency in half on tab returns.

---

## TypeScript discipline

Best section. Strict mode on. 1 `any` reference, in a comment. `types/api.ts` (291 LOC) and `types/storefront.ts` (61 LOC) own the contract; entities have explicit `| null` for nullable fields rather than `?`-optionals. Discriminated unions where they earn it (`OrderStatus`, `ItemStatus`, `VatRate`). Form schemas via Zod with `z.infer<>` for the `useForm` generic. `next-auth.d.ts` extends the session type for `tenantId` and `accessToken`. The one risky pattern is `(error: unknown)` catches that immediately `instanceof Error`-narrow without exhaustive handling — fine for toast messages, would be nicer with a typed error envelope.

---

## Daily-use vendor experience

A vendor running this 8 hours a day will feel three things, in order:

1. **The dashboard is a desktop tool**, no negotiation. They cannot manage from a phone — sidebar eats the screen. KDS-on-a-tablet works because tablets have desktop-class viewports.
2. **Every page is a full reload**. Switch from Orders to Products and back, and you wait for the spinner both times. No optimistic UI on forms (except the KDS bump button — `app/dashboard/kitchen/page.tsx:280-295` does optimistic state then revert on error, which is the right model and should be everywhere).
3. **Mega-files mean every small change ships a 1000-line diff**. The marketing page (1231 LOC) handles promotions AND announcements AND date pickers AND tables AND four dialogs in one file. Code review on this is hostile.

The **KDS page is the sleeper hit**. Live STOMP updates, audio beep with mute persisted to localStorage, age-coded card borders (green <5min, yellow <15min, red >15min — `app/dashboard/kitchen/page.tsx:66-71`), connection-status dot, optimistic bump-through-status. This is the one screen a real kitchen can use today.

The **storefront checkout** is also done well — Stripe Elements wrapped, `redirect: "if_required"` for one-tap flows, allergen warnings on confirmation, local order history persistence.

---

## Top 5 strengths

1. **Storefront design tells a real food story.** `app/shop/page.tsx` and `app/shop/[slug]/page.tsx` are mobile-first, semantic, brand-aligned. Floating cart, sticky category nav, dietary badges with appropriate colour semantics (`app/shop/[slug]/page.tsx:48-56`), skeleton loaders that match the rendered geometry.
2. **`api-client.ts` is production-grade.** Singleton refresh promise (`frontend/lib/api-client.ts:62-70`), 5xx retry with backoff, debounced 401 handling, X-Tenant-Id defence-in-depth. Unusual to see in a startup codebase.
3. **TypeScript hygiene.** 1 `any` in 53 files, all in a code comment. Centralised `types/api.ts`, Zod-inferred form types, proper `next-auth` module augmentation in `types/next-auth.d.ts`.
4. **CartProvider is correct React.** `components/storefront/cart-provider.tsx` — `useMemo` on the context value, `useCallback` for stable mutators, localStorage scoped per shop slug, SSR-safe `typeof window` guards. This is what every junior tutorial gets wrong.
5. **KDS page is functionally excellent.** Live STOMP via `useStomp`, mute persisted, age-coded borders, optimistic bumps with revert-on-failure. `app/dashboard/kitchen/page.tsx` is the only screen that feels designed for a real workflow.

## Top 5 concerns

1. **The dashboard chrome is a shadcn template wearing the brand's name.** `components/dashboard/sidebar.tsx` uses blue + purple gradients on a slate-900 background; `app/globals.css:13` has `--primary: 221.2 83.2% 53.3%` (shadcn blue) so every default `<Button>` ships blue. The orange/emerald palette only exists on the storefront as hardcoded utilities. After a rejected design overhaul, the brand still hasn't reached the admin.
2. **Sidebar is not responsive.** `components/dashboard/sidebar.tsx:54` is `w-64` with no `md:` prefix, no hamburger, no collapse. On 375px viewports the sidebar consumes 68% of the screen. Vendors cannot manage on a phone.
3. **Zero `next/image` usage.** `components/ui/safe-image.tsx` ships raw `<img>` everywhere. `next.config.mjs` `remotePatterns` is dead config. No `srcSet`, no AVIF/WebP, no LCP optimisation. Storefront performance leaves easy wins on the table.
4. **Mega client components.** `app/dashboard/marketing/page.tsx` (1231 LOC), `app/dashboard/orders/page.tsx` (951), `app/dashboard/products/page.tsx` (889) are single-file kitchen sinks. Hard to review, hard to test, hard to refactor.
5. **Zero `aria-label` attributes** in the codebase, no `loading.tsx` or `not-found.tsx` files, no Suspense for data, no `dynamic()` imports. Accessibility, perceived performance, and bundle splitting are all uninvested.

---

## Would I let this represent the brand? **With major changes.**

The storefront — yes, today, after one performance pass to swap `SafeImage` for `next/image` and tighten contrast on `text-slate-400` body copy. It looks like a real food-delivery product. The vendor dashboard — no. Not because it's broken, but because it tells a different story than the storefront does. A vendor signs up, sees the slick orange storefront a customer will see, then logs into a blue Vercel dashboard. That dissonance erodes trust. Fix the design tokens in `globals.css`, retheme the sidebar, replace the six-colour status badge rainbow with two semantic states + a muted neutral, and make the chrome responsive. After that — yes.

---

## Highest-leverage 5 fixes

1. **Rebrand the design tokens, not just the storefront pages.** Edit `app/globals.css:13` to set `--primary: 24 95% 53%` (orange-500) and add `--accent: 158 64% 52%` (emerald-500). Update `--ring` to match. Then the existing `<Button variant="default">` calls across 22 dashboard files instantly flip to brand without touching markup. Retheme `components/dashboard/sidebar.tsx` to use orange accents on slate-950, drop the blue→purple avatar gradient.
2. **Make the dashboard responsive.** `components/dashboard/sidebar.tsx` needs a `md:w-64 w-full md:translate-x-0` pattern with a hamburger trigger in `components/dashboard/dashboard-shell.tsx` and a Radix `<Sheet>` overlay on mobile. This unlocks vendor-on-phone, which is the whole point of a SaaS.
3. **Swap `SafeImage` to `next/image`.** Rewrite `components/ui/safe-image.tsx` to use `<Image fill sizes="..." onError={...}>` keeping the error fallback. Add the production S3/CloudFront pattern that's commented out in `next.config.mjs:43-49`. This is one component change that improves every storefront page LCP.
4. **Split the mega-pages.** `app/dashboard/marketing/page.tsx` becomes `marketing/page.tsx` + `marketing/PromotionForm.tsx` + `marketing/AnnouncementForm.tsx` + `marketing/PromotionsTable.tsx`. Same for orders and products. Target <300 LOC per page file. Code review and incremental refactor become possible.
5. **Add a server-state layer.** Drop in TanStack Query at `components/providers.tsx`, replace the `useEffect → fetch → setState` pattern in dashboard pages with `useQuery`. Free wins: refetch-on-focus, request deduplication, optimistic mutations, stale-while-revalidate on tab returns. Vendor stops staring at spinners every navigation.
