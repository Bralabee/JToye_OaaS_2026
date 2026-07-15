---
quick_id: 260714-egj
slug: motion-uplift-phase-b-tail-additive-cart
mode: quick
wave: 1
status: ready
description: >
  Motion uplift phase B tail (frontend-only, additive): a slide-over cart drawer
  opened from the storefront nav basket badge, and a brand-alignment pass that
  replaces the dashboard chrome's residual stock-shadcn blue with brand orange.
  Zero new dependencies. Builds on the motion foundation merged in PR #220.
---

# Quick 260714-egj — Cart drawer + dashboard brand-blue cleanup

## Context / constraints (locked)

- Worktree root: `/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion`, branch
  `feature/motion-uplift-cart-drawer` (off origin/main @ 62078c4, which already
  contains the motion foundation: `MotionProvider`, `lib/motion.ts`,
  `use-cart-count`, brand tokens). NEVER touch `/home/sanmi/IdeaProjects/JToye_OaaS_2026`.
- **Additive only** (Incremental Betterment Doctrine): the existing cart PAGE
  `app/shop/[slug]/cart/page.tsx` and its route MUST remain fully intact and
  reachable. The drawer is a NEW affordance, not a replacement. The `FloatingCartBar`
  on the shop page keeps linking to the cart page unchanged.
- No new npm dependencies. `framer-motion@12` (`m` component under global
  `LazyMotion`), Radix `Sheet`, Tailwind only.
- Reduced-motion is already handled globally by `<MotionConfig reducedMotion="user">`
  in `MotionProvider`; `m`/`AnimatePresence` inside the drawer inherit it.
- Brand orange is the locked palette (sketch-001). This task does NOT introduce a
  new visual direction — it only swaps literal `blue-*` chrome classes to `orange-*`
  to match the brand primary token promoted in PR #220. No layout/structure change.

## Architecture note (verified in code)

`StorefrontNav` renders in the OUTER `app/shop/layout.tsx`, OUTSIDE `CartProvider`
(which mounts in `app/shop/[slug]/layout.tsx`). That is exactly why the nav badge
reads the count via `useCartCount(slug)` (localStorage) rather than `useCart()`.
Therefore the drawer — which needs full cart access (items, update, remove, total)
— MUST mount INSIDE the provider tree, and the nav badge (outside it) must trigger
it via a `window` CustomEvent. The badge only appears on `/shop/[slug]/*` routes,
which are precisely the routes where `CartProvider` (and thus the drawer) is mounted,
so the event always has a listener.

---

## Task 1 — Cart drawer component + mount + nav trigger

**Files:**
- CREATE `frontend/components/storefront/cart-drawer.tsx`
- EDIT `frontend/app/shop/[slug]/layout.tsx` (mount `<CartDrawer />` inside `CartProvider`)
- EDIT `frontend/components/storefront/storefront-nav.tsx` (badge opens drawer)

**Action:**

1. `cart-drawer.tsx` (`"use client"`):
   - Uses `useCart()` for `items, updateQuantity, removeItem, clearCart, itemCount, totalPennies, shopSlug`.
   - Local `open` state. On mount, `window.addEventListener("jtoye:cart-open", () => setOpen(true))` (cleanup on unmount). Also close on pathname change via `usePathname` effect so navigating (e.g. to checkout) dismisses it.
   - Renders Radix `Sheet` (`side="right"`, width ~`w-full sm:max-w-md`, `p-0`, flex column) with a header ("Your basket" + `itemCount` items + a `SheetClose` X + a "Clear all" button when non-empty), a scrollable body, and a sticky footer.
   - Body: `AnimatePresence initial={false}` over `items`; each row is an `m.div` keyed by `productId` with `layout`, `initial/animate/exit` opacity+height (or opacity+x) using the shared `springSoft`/durations from `lib/motion.ts` (add a small variant/spring there if a fitting one is missing — keep it generic). Reuse the cart page's row design: `SafeImage` thumbnail (branded fallback), title, category, line total via a local `formatPrice`, and the same −/＋ quantity stepper (Trash2 icon when quantity===1) wired to `updateQuantity`. Add `whileTap={{ scale: 0.9 }}` to the stepper buttons (touch feedback).
   - Empty state mirrors the cart page: `ShoppingBag`, "Your basket is empty", a `SheetClose`-wrapped "Back to menu" affordance (just closes the drawer).
   - Footer (only when items>0): subtotal + total rows (both `formatPrice(totalPennies)` — mirrors the page), then two actions wrapped so they close the drawer on click:
     - Primary `Link` → `/shop/${shopSlug}/checkout`, orange, "Checkout · £X.XX", `active:scale-[0.98]`.
     - Secondary `Link` → `/shop/${shopSlug}/cart`, bordered, "View full basket" — this is the guaranteed bridge to the still-intact page (no regression).
   - Import `m`/`AnimatePresence` from `framer-motion`, `Sheet`/`SheetContent`/`SheetClose`/`SheetTitle` from `@/components/ui/sheet`, `SafeImage`, icons from `lucide-react`.

2. `app/shop/[slug]/layout.tsx`: render `<CartDrawer />` as a sibling of `{children}` inside `<CartProvider>` (so it shares the cart context and unmounts with the slug subtree). Keep it a client boundary — CartProvider is already `"use client"`; importing CartDrawer (client) is fine.

3. `storefront-nav.tsx` basket badge: keep it a `<Link href={/shop/${slug}/cart}>` for semantics/progressive-enhancement/new-tab, but add an `onClick` that opens the drawer for a plain left click:
   ```
   onClick={(e) => {
     if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
     e.preventDefault()
     window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
   }}
   ```
   Convert the `<Link>` to an `m(Link)` OR keep `Link` and wrap the icon; simplest: keep `Link`, add `whileTap` is not available on Link — instead add `active:scale-95` utility for tap feedback. Preserve the existing badge `m.span`, `aria-live`, and `sr-only` text exactly. (The href remains the cart page, so keyboard/AT users and JS-off still reach a working destination.)

**Verify:**
- `cd frontend && npm run build` (tsc) passes.
- `grep -q 'jtoye:cart-open' frontend/components/storefront/cart-drawer.tsx frontend/components/storefront/storefront-nav.tsx` (both sides wired).
- `grep -q 'CartDrawer' frontend/app/shop/[slug]/layout.tsx` (mounted).
- `app/shop/[slug]/cart/page.tsx` is unchanged (`git diff --quiet -- frontend/app/shop/[slug]/cart/page.tsx`).

**Done:** Drawer component exists, is mounted in the slug layout inside CartProvider, and the nav badge opens it on plain click while still linking to the intact cart page.

---

## Task 2 — Dashboard chrome brand-alignment (blue → orange)

**Files:**
- EDIT `frontend/components/dashboard/sidebar.tsx`
- EDIT `frontend/components/dashboard/mobile-tab-bar.tsx`

**Action:** Pure Tailwind color-class swaps to the brand primary. No structural/behavioural change; preserve every dark-mode variant.
- `sidebar.tsx`:
  - active nav pill `bg-blue-600 text-white shadow-lg shadow-blue-500/50` → `bg-orange-600 text-white shadow-lg shadow-orange-500/40`
  - logo icon `text-blue-400` → `text-orange-500`
  - avatar gradient `from-blue-400 to-blue-600` → `from-orange-400 to-orange-600`
- `mobile-tab-bar.tsx`:
  - active tab text `text-blue-600` → `text-orange-600`
  - focus ring `focus-visible:ring-blue-500` → `focus-visible:ring-orange-500`
  - more-items active `bg-blue-50 text-blue-600 dark:bg-slate-800` → `bg-orange-50 text-orange-600 dark:bg-slate-800`
  - avatar gradient `from-blue-400 to-blue-600` → `from-orange-400 to-orange-600`

**Verify:**
- `cd frontend && npm run build` passes.
- `! grep -RnE 'blue-(400|500|600)' frontend/components/dashboard/sidebar.tsx frontend/components/dashboard/mobile-tab-bar.tsx` (zero residual chrome blue in these two files).
- No other files touched by this task.

**Done:** Dashboard sidebar + mobile tab bar render brand-orange active/logo/avatar chrome, dark-mode intact.

---

## Task 3 — Tests + metrics + gates

**Files:**
- CREATE `frontend/components/storefront/__tests__/cart-drawer.test.tsx`
- EDIT `docs/metrics.json` (via script) and `CLAUDE.md` testing-count line (hand-sync)

**Action:**
1. Jest tests for the drawer (wrap in `CartProvider shopSlug="test-shop"`; the global `framer-motion` mock + Radix Sheet already work in jsdom per the foundation task). ~5–7 `it` blocks:
   - hidden by default (Sheet content not in DOM / not visible).
   - opens when `window.dispatchEvent(new CustomEvent("jtoye:cart-open"))` is fired.
   - seeded cart (pre-populate localStorage key `jtoye-cart-test-shop` before render, OR add an item via a small harness) renders the item title + line total.
   - empty state shows "Your basket is empty".
   - checkout link points to `/shop/test-shop/checkout`; "View full basket" points to `/shop/test-shop/cart`.
   - clicking the stepper +/− calls updateQuantity (assert count/label change).
   Use `@testing-library/react` + `user-event` per repo convention; mock `next/navigation` `usePathname` (already mocked globally — confirm).
2. Refresh counts: from worktree root run `bash scripts/docs-freshness.sh --write`, then `bash scripts/docs-freshness.sh` must print OK. Hand-sync the CLAUDE.md "project standard is N logical invocations … + M Jest it/test blocks across K files" line to the new metrics.json values (docs-freshness manages metrics.json only, NOT the CLAUDE.md prose).

**Verify (all must pass):**
- `cd frontend && npm run build` — tsc clean.
- `cd frontend && npx jest` — full suite green (new blocks included).
- `bash scripts/docs-freshness.sh` — OK, no drift.
- `git diff --quiet -- frontend/package.json frontend/package-lock.json` — zero dependency changes.

**Done:** New drawer tests pass in the full green suite, metrics.json + CLAUDE.md counts reconciled, no dependency drift.

---

## Out of scope (do NOT do here)
- Converting/removing the cart page; changing FloatingCartBar's link target.
- GSAP / marketing scroll scenes (Phase D — needs `/gsd-sketch` first per locked design rule).
- D3 / Market-Heat device (Phase E).
- SSE for guest tracking; next/image migration; mass Skeleton adoption.
- Any backend, dashboard-page chart, or storefront-theme visual overhaul work.
