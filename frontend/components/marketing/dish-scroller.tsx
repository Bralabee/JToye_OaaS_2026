"use client"

import { useRef, type ReactNode } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { useScrollEdges } from "@/components/marketing/use-scroll-edges"

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

// EDGE_EPSILON_PX, NUDGE_RATIO and the measuring live in use-scroll-edges.ts,
// shared with CuisineRail (PR 1108 review, finding 3).

type DishScrollerProps = {
  children: ReactNode
  /** Accessible name for the scroll region, e.g. "Dishes cooking near you". */
  label: string
}

export function DishScroller({ children, label }: DishScrollerProps) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)

  const { nudge } = useScrollEdges(wrapperRef, scrollerRef)

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
