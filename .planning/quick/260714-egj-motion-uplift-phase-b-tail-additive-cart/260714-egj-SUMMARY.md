---
quick_id: 260714-egj
slug: motion-uplift-phase-b-tail-additive-cart
mode: quick
status: complete
subsystem: frontend/storefront + frontend/dashboard-chrome
tags: [motion, cart, drawer, brand-orange, additive, frontend]
requires:
  - PR #220 motion foundation (MotionProvider, lib/motion.ts, use-cart-count, brand tokens)
provides:
  - Storefront slide-over cart drawer (new affordance, opened from nav basket badge)
  - Dashboard chrome aligned to brand orange (sidebar + mobile tab bar)
affects:
  - frontend/app/shop/[slug]/* (drawer mounts in slug layout)
  - frontend/dashboard chrome (color only)
key-files:
  created:
    - frontend/components/storefront/cart-drawer.tsx
    - frontend/components/storefront/__tests__/cart-drawer.test.tsx
  modified:
    - frontend/lib/motion.ts (added generic springSoft)
    - frontend/app/shop/[slug]/layout.tsx (mount CartDrawer inside CartProvider)
    - frontend/components/storefront/storefront-nav.tsx (badge opens drawer, href intact)
    - frontend/components/dashboard/sidebar.tsx (blue -> orange chrome)
    - frontend/components/dashboard/mobile-tab-bar.tsx (blue -> orange chrome)
    - docs/metrics.json (jest 261->268 / 40->41, total 1269->1276)
    - CLAUDE.md (testing-count prose sync)
decisions:
  - Drawer mounts INSIDE CartProvider (slug layout); nav badge (outside provider) triggers it via a window CustomEvent `jtoye:cart-open` — the only cross-tree bridge available since the badge reads count via localStorage, not context.
  - Additive-only: nav badge keeps its cart-page href (progressive enhancement / keyboard / AT / JS-off / modified-click), and the drawer links to the still-intact full cart page via "View full basket". No capability displaced.
  - Stepper removal routes through updateQuantity(id, 0) (matches the cart page), so removeItem stays unused-but-exposed for context parity.
metrics:
  duration: ~5.6 min
  tasks: 3
  files-created: 2
  files-modified: 7
  completed: 2026-07-14
---

# Quick 260714-egj: Cart drawer + dashboard brand-blue cleanup Summary

Frontend-only, dependency-free motion Phase-B tail: a storefront slide-over cart drawer opened from the nav basket badge, plus a brand-alignment pass swapping residual stock-shadcn blue chrome to brand orange in the dashboard sidebar and mobile tab bar. Builds on the PR #220 motion foundation. Strictly additive — the existing cart page and route are untouched and remain reachable.

## What shipped

### Task 1 — Cart drawer + mount + nav trigger (commit d4c707f)
- New `CartDrawer` (`components/storefront/cart-drawer.tsx`, `"use client"`): Radix `Sheet` (`side="right"`, `w-full sm:max-w-md`, `p-0`, flex column) with header (title + item count + Clear all + X close), a scrollable `AnimatePresence` body of `m.div` rows (`layout` + opacity/height enter/exit on the new `springSoft`), and a sticky footer (subtotal/total + orange "Checkout · £X.XX" + bordered "View full basket").
- Rows reuse the cart page's design: `SafeImage` branded fallback, title/category, line total via a local `formatPrice`, and the same −/＋ stepper (Trash2 at quantity 1) wired to `updateQuantity`, with `whileTap={{ scale: 0.9 }}` touch feedback.
- Empty state mirrors the cart page (`ShoppingBag`, "Your basket is empty", a `SheetClose` "Back to menu").
- Local `open` state; opens on `window` `jtoye:cart-open` event (cleanup on unmount) and auto-closes on `usePathname` change so navigating away dismisses it. Every navigation action also `setOpen(false)` on click.
- Mounted as a sibling of `{children}` inside `CartProvider` in `app/shop/[slug]/layout.tsx`.
- `storefront-nav.tsx` basket badge: kept as a `<Link href={/shop/${slug}/cart}>` (semantics / new-tab / JS-off), added an `onClick` that `preventDefault()`s a plain left click and dispatches `jtoye:cart-open` (bails on meta/ctrl/shift/non-left so modified clicks still open the page). Added `active:scale-95 transition-all` for tap feedback. Badge `m.span`, `aria-live`, and `sr-only` text preserved exactly.
- Added a generic `springSoft` (stiffness 300 / damping 32) to `lib/motion.ts` for list reflow.

### Task 2 — Dashboard chrome brand-alignment (commit 6fb8627)
Pure Tailwind color-class swaps, no structural/behavioural change, all dark-mode variants preserved:
- `sidebar.tsx`: active nav pill `bg-blue-600 shadow-blue-500/50` → `bg-orange-600 shadow-orange-500/40`; logo `text-blue-400` → `text-orange-500`; avatar gradient `from-blue-400 to-blue-600` → orange.
- `mobile-tab-bar.tsx`: active tab `text-blue-600` → `text-orange-600`; focus ring `ring-blue-500` → `ring-orange-500`; More-drawer active `bg-blue-50 text-blue-600` → orange; avatar gradient → orange.

### Task 3 — Tests + metrics + gates (commit d087835)
- `components/storefront/__tests__/cart-drawer.test.tsx`: 7 `it` blocks under the real `CartProvider` (seeded via localStorage), using the global framer-motion mock + Radix Sheet in jsdom (with defensive `matchMedia`/`ResizeObserver` stubs): hidden by default, opens on the event, seeded item title + line total, empty state, slug-scoped checkout + view-full-basket links, and +/− stepper quantity changes.
- Regenerated `docs/metrics.json` (jest_blocks 261→268, jest_files 40→41, total 1269→1276) via `docs-freshness.sh --write` and hand-synced the `CLAUDE.md` testing-count prose line.

## Gates (all green)
- `npm run build` (tsc / next build): ✓ Compiled successfully — includes the new test file under strict TS.
- `npx jest`: 41 suites / 263 tests passing (incl. palette-discipline + link-graph guards).
- `bash scripts/docs-freshness.sh`: OK — metrics match source (1276).
- `git diff --quiet -- frontend/package.json frontend/package-lock.json`: clean — zero dependency changes.
- `git diff --quiet -- frontend/app/shop/[slug]/cart/page.tsx`: clean — additive invariant intact, cart page + route unchanged and reachable; FloatingCartBar link target untouched.

## Deviations from Plan
None — plan executed exactly as written. `removeItem` is destructured from `useCart()` for context parity (as the plan's context surface lists it) but the stepper reaches removal via `updateQuantity(id, 0)` exactly as the cart page does; a `void removeItem` keeps it explicitly acknowledged with no lint/build noise.

## Known Stubs
None. The drawer is wired to live cart context (items, updateQuantity, clearCart, totals, shopSlug) — no placeholder/empty data paths.

## Self-Check: PASSED
- FOUND: frontend/components/storefront/cart-drawer.tsx
- FOUND: frontend/components/storefront/__tests__/cart-drawer.test.tsx
- FOUND commits: d4c707f, 6fb8627, d087835

## Notes for orchestrator
- SUMMARY.md / STATE.md / ROADMAP.md intentionally NOT committed by the executor (per task constraints) — docs commit is yours.
- Live browser verification (drawer open/close, animations, orange chrome, dark mode) is the post-execution step you own; the running stack was not touched.
