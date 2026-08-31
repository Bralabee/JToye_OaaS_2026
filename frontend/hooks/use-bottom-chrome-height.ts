"use client"

import { useEffect, useLayoutEffect, type RefObject } from "react"

/**
 * Publish a mounted bottom-fixed bar's height so other bottom-anchored chrome
 * can sit ABOVE it instead of underneath it — R-07 (2026-08-31
 * customer-surface audit, found independently by four of five audit lanes).
 *
 * ── WHY THE BARS PUBLISH AND THE NOTICE CONSUMES ────────────────────────────
 * Deliberately this way round rather than the cookie notice measuring the DOM.
 * The render that changes a bar's presence is exactly the render the PUBLISHER
 * already observes, so no `MutationObserver` and no polling are needed, and
 * there is no tuned offset constant that can drift. `cookie-notice.tsx`'s own
 * comment rejects offsets precisely because "a stale offset fails SILENTLY as a
 * covered CTA" — that objection is answered here rather than ignored: this
 * offset is published by the bar itself, from its own measured height, at the
 * moment it appears.
 *
 * ── THE MECHANISM IS LOAD-BEARING; A DEPENDENCY ARRAY SHIPS THE BUG ─────────
 * Verified in the tree: `FloatingCartBar` is rendered UNCONDITIONALLY by
 * `app/shop/[slug]/shop-detail-client.tsx`, so it mounts with the page and
 * never unmounts. The element that actually carries `fixed bottom-0 … z-50` is
 * the `m.div` INSIDE its `<AnimatePresence>`, gated on `itemCount > 0`. The two
 * lifecycles are decoupled.
 *
 * A literal `useEffect(() => {…}, [])` would therefore fire exactly ONCE, at
 * `FloatingCartBar`'s mount, when the basket is empty and `ref.current` is
 * `null`. It would publish nothing, never run again, and the notice would still
 * sit under the cart bar with "Got it" unreachable — shipped broken behind a
 * green Jest run. So:
 *
 *   - NO DEPENDENCY ARRAY. The effect re-runs after every render. `useCart()`
 *     supplies `itemCount`, so a basket going non-empty RE-RENDERS the bar (it
 *     does not remount it), and that render is the only signal available.
 *   - `ref.current` is re-read FRESH on every run, never closed over.
 *   - A height of `0` — ref detached, or present but `display:none` — removes
 *     the property. One rule covering null-ref, hidden and unmounted alike.
 *   - The cleanup removes it too, so a final unmount cannot strand a value.
 *     A stale value would push the notice permanently off the bottom of a page
 *     that has no bottom bar at all.
 *   - A `resize` listener re-measures. NOT decoration: `mobile-tab-bar` is
 *     `md:hidden` rather than conditionally rendered, so its ref is ALWAYS
 *     attached and its height is 0 only because of the breakpoint. Crossing
 *     `md` causes no re-render, so without this a session that loads at >=md
 *     and then narrows keeps a stale `0px` and the notice sits under the tab
 *     bar — the exact silent staleness this design exists to avoid.
 *
 * The two publishers live on disjoint surfaces (dashboard tab bar, storefront
 * cart bar) and are never mounted together, so a single shared custom property
 * has no writer contention to resolve.
 */

/** The custom property name. Consumed by `components/public/cookie-notice.tsx`. */
export const BOTTOM_CHROME_VAR = "--jt-bottom-chrome"

/**
 * `useLayoutEffect` warns during SSR, and both callers are `"use client"`
 * components that Next still server-renders. Selected once at module scope so
 * the hook order is stable.
 */
const useIsomorphicLayoutEffect =
  typeof window !== "undefined" ? useLayoutEffect : useEffect

export function useBottomChromeHeight(ref: RefObject<HTMLElement | null>): void {
  // eslint-disable-next-line react-hooks/exhaustive-deps -- NO dependency array, deliberately: see the docblock. The bar's ref is attached by a CONDITIONAL child inside <AnimatePresence>, so a `[]` array would run once against a null ref and never publish anything.
  useIsomorphicLayoutEffect(() => {
    const publish = () => {
      // Read FRESH — never a value captured at mount.
      const height = ref.current?.offsetHeight ?? 0
      const root = document.documentElement
      if (height > 0) root.style.setProperty(BOTTOM_CHROME_VAR, `${height}px`)
      else root.style.removeProperty(BOTTOM_CHROME_VAR)
    }

    publish()
    window.addEventListener("resize", publish)
    return () => {
      window.removeEventListener("resize", publish)
      document.documentElement.style.removeProperty(BOTTOM_CHROME_VAR)
    }
  })
}
