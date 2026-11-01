"use client"

import { useCallback, useEffect, useRef, type ReactNode } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"

/**
 * DishScroller — horizontal card row with a real scroll affordance.
 *
 * The row it replaces was `overflow-x-auto` and nothing else. That scrolls, but
 * nothing on screen SAYS it scrolls: the last card is hard-clipped at the
 * container edge (measured 2026-08-02 — "Lamb Biryani" cut mid-word at 390px,
 * "Pho Bo" cut at 1440px), and on touch the scrollbar is an overlay that does
 * not exist until you are already scrolling. The affordance has to be the
 * thing you see BEFORE you interact, not the feedback you get after.
 *
 * Three parts, each deliberate:
 *
 *  1. CARD PEEK (D14, owner ruling 2026-11-01). The cards (`shop-card.tsx`)
 *     are 80% of the row on a phone, so the next one always shows at the edge
 *     while there is one. This replaced an EDGE FADE — a white-to-transparent
 *     gradient at whichever end had more to show — because the page's
 *     `gradient-decoration` ruling bans gradients (design/rulings.tsv) and the
 *     owner chose the peek over keeping the fade as an exception. The
 *     `data-can-left` / `data-can-right` flags the fade read are kept: they
 *     still drive the arrows, and the e2e spec reads them.
 *
 *  2. SNAP — proximity, not mandatory. Cards are `min-w`, so their real widths
 *     vary with content; mandatory snapping on uneven widths fights the user
 *     near the ends. Proximity assists without capturing.
 *
 *  3. ARROWS — pointer-fine only. On touch the swipe IS the affordance and an
 *     arrow is redundant chrome; on a mouse there is no swipe, so the arrows
 *     are the only non-scrollbar way to move. Tailwind's `hover:` gate
 *     (`future.hoverOnlyWhenSupported`, issue 503) tests hover only, not the
 *     pointer, so the pointer query is written out rather than assumed.
 *
 * Both arrows are driven by ONE pair of booleans — `canLeft` /
 * `canRight` — rather than by separate "at start", "at end" and "overflows"
 * flags. With separate flags each control needed a rule to show it and another
 * to hide it, and which won came down to the order Tailwind happened to emit
 * two arbitrary variants of equal specificity. One attribute per direction
 * means exactly one rule ever enables a control, which is the ordinary
 * `hidden` + variant cascade Tailwind already guarantees.
 *
 * Scroll state is written straight to those `data-*` attributes via a ref —
 * never React state. Two reasons: a scroll handler that calls setState
 * re-renders the whole row on every frame, and `react-hooks/set-state-in-effect`
 * (the rule that bit PR 221) forbids the initial-sync pattern anyway. CSS reads
 * the attributes.
 *
 * No-JS / pre-hydration contract: both flags default to `false`, so no arrow
 * renders and the row is natively scrollable regardless. If JS never
 * runs the user gets exactly the old behaviour — never a masked or frozen row.
 */

// Subpixel tolerance. `scrollLeft` is fractional under browser zoom and on
// HiDPI, so an exact `=== scrollWidth - clientWidth` end test never fires and
// the right-hand arrow would stay on forever at the end of the row.
const EDGE_EPSILON_PX = 2

// Nudge just under a viewport so a sliver of the outgoing card stays visible —
// a full-width page means losing your place.
const NUDGE_RATIO = 0.8

type DishScrollerProps = {
  children: ReactNode
  /** Accessible name for the scroll region, e.g. "Dishes cooking near you". */
  label: string
}

export function DishScroller({ children, label }: DishScrollerProps) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)

  /** Reflect scroll position onto the wrapper so CSS can drive the arrows. */
  const syncEdges = useCallback(() => {
    const wrapper = wrapperRef.current
    const scroller = scrollerRef.current
    if (!wrapper || !scroller) return

    const maxScroll = scroller.scrollWidth - scroller.clientWidth
    // A row that fits entirely has nothing to disclose in either direction;
    // showing the affordance anyway would be a false promise.
    const overflows = maxScroll > EDGE_EPSILON_PX

    wrapper.dataset.canLeft = String(overflows && scroller.scrollLeft > EDGE_EPSILON_PX)
    wrapper.dataset.canRight = String(overflows && scroller.scrollLeft < maxScroll - EDGE_EPSILON_PX)
  }, [])

  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller) return

    syncEdges()
    scroller.addEventListener("scroll", syncEdges, { passive: true })

    // Card widths change with the font and the viewport, so the end position
    // moves without a scroll event ever firing. Observe the box rather than
    // listening for resize, which misses in-place layout shifts.
    //
    // Feature-detected, not assumed: jsdom has no ResizeObserver, so calling it
    // unguarded threw during the passive-effect flush and took down all five
    // rendering assertions in app/__tests__/landing.test.tsx. `reveal.tsx`
    // guards its matchMedia use the same way and for the same reason. Where the
    // observer is missing we fall back to window resize — coarser, but the
    // affordance degrades rather than disappearing.
    let disconnect: () => void
    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(syncEdges)
      observer.observe(scroller)
      disconnect = () => observer.disconnect()
    } else {
      window.addEventListener("resize", syncEdges)
      disconnect = () => window.removeEventListener("resize", syncEdges)
    }

    return () => {
      scroller.removeEventListener("scroll", syncEdges)
      disconnect()
    }
  }, [syncEdges])

  // Pointer type is resolved in JS, not in a Tailwind variant, because the two
  // conditions the arrows need — "fine pointer" AND "there is somewhere to go"
  // — cannot be stacked as arbitrary variants. Tailwind emits the media query
  // into the CLASS NAME rather than wrapping the rule, producing
  // `[data-can-right=true] > .\[\@media\(hover\:hover\)...\] { display: grid }`
  // with no @media at all — the arrows would then show on touch. Verified
  // against the dev build 2026-08-02. `reveal.tsx` resolves its media query in
  // JS for the same reason, so this stays consistent with the file next door.
  useEffect(() => {
    const wrapper = wrapperRef.current
    if (!wrapper || typeof window === "undefined" || typeof window.matchMedia !== "function") return

    const mql = window.matchMedia("(hover: hover) and (pointer: fine)")
    const apply = () => {
      wrapper.dataset.finePointer = String(mql.matches)
    }
    apply()
    mql.addEventListener("change", apply)
    return () => mql.removeEventListener("change", apply)
  }, [])

  const nudge = useCallback((direction: 1 | -1) => {
    const scroller = scrollerRef.current
    if (!scroller) return

    // Reduced motion means less movement, not less function: the row still
    // moves the same distance, it just arrives without the travel animation.
    const reduced =
      typeof window !== "undefined" &&
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches

    scroller.scrollBy({
      left: direction * scroller.clientWidth * NUDGE_RATIO,
      behavior: reduced ? "auto" : "smooth",
    })
  }, [])

  return (
    <div
      ref={wrapperRef}
      className="relative"
      data-can-left="false"
      data-can-right="false"
      data-fine-pointer="false"
    >
      <div
        ref={scrollerRef}
        role="region"
        aria-label={label}
        tabIndex={0}
        className="flex snap-x gap-4 overflow-x-auto scroll-px-4 overscroll-x-contain pb-2 [scrollbar-width:thin] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2 [&>*]:snap-start"
      >
        {children}
      </div>

      <ScrollButton side="left" onClick={() => nudge(-1)} />
      <ScrollButton side="right" onClick={() => nudge(1)} />
    </div>
  )
}

function ScrollButton({ side, onClick }: { side: "left" | "right"; onClick: () => void }) {
  const Icon = side === "left" ? ChevronLeft : ChevronRight
  // One rule turns this on, via a single compound parent selector: the pointer
  // is fine AND there is somewhere to go in this direction.
  const shown =
    side === "left"
      ? "[[data-fine-pointer=true][data-can-left=true]_>_&]:grid"
      : "[[data-fine-pointer=true][data-can-right=true]_>_&]:grid"

  return (
    <button
      type="button"
      onClick={onClick}
      // Not in the tab order: the region itself is focusable and arrow keys
      // already scroll it, so these would be two extra stops offering nothing a
      // keyboard user cannot already do.
      tabIndex={-1}
      aria-hidden
      data-testid={`dish-scroll-${side}`}
      className={[
        "absolute top-1/2 z-10 hidden h-9 w-9 -translate-y-1/2 place-items-center rounded-full",
        // Flat: white on the warm-100 card it sits over, no outline, no shadow
        // and no backdrop blur (the `glass` ban).
        "bg-white text-brand-900",
        "transition-transform duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.97]",
        side === "left" ? "left-1" : "right-1",
        shown,
      ].join(" ")}
    >
      <Icon className="h-5 w-5" />
    </button>
  )
}
