"use client"

import { useRef, type FocusEvent, type ReactNode } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { useScrollEdges } from "@/components/marketing/use-scroll-edges"

/**
 * The landing's cuisine chip rail (D15: one non-wrapping row, all six chips).
 *
 * From 1280px up the six chips fit and no arrow shows. Below that the rail
 * overflows: touch swipes it, a keyboard tabs through it (the browser scrolls
 * the focused chip into view), but a MOUSE had only the cut-off chip as a cue
 * and no way to scroll a hidden-scrollbar row without a trackpad. D18 (owner,
 * 2026-11-03) adds prev/next arrows for that one case, on the same measuring
 * as the kitchen row's DishScroller (`use-scroll-edges.ts`): an arrow shows
 * only for a hovering fine pointer and only while its side has hidden chips.
 *
 * With a fine pointer the rail's scroll padding grows to 40px, wider than the
 * 32px arrows, so a snapped chip settles clear of them instead of under them.
 * The browser does NOT scroll a focused chip that is already partly visible
 * (measured: focusing "Vegan" at 1024 left scrollLeft at 0 with 32px of it
 * under the right arrow, PR 1108 review finding 2), so a focused chip is
 * brought in with `scrollIntoView({ inline: "nearest" })`, which honours that
 * padding. Snap is off for a fine pointer (it is a swipe aid, and its
 * proximity snap pulled that chip back 6px under the arrow); touch keeps the
 * 4px padding and its snap positions unchanged.
 *
 * Out of the tab order (a keyboard already reaches every chip), out of flow
 * (no layout shift). Flat by identity.md: a white pill on the stone hero, a
 * warm-300 ring to part it from the white chips it sits over; no shadow, no
 * gradient (rulings.tsv).
 */
export function CuisineRail({ children }: { children: ReactNode }) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  const railRef = useRef<HTMLDivElement>(null)
  const { nudge } = useScrollEdges(wrapperRef, railRef)

  const revealFocused = (event: FocusEvent<HTMLDivElement>) => {
    const target = event.target
    if (target === railRef.current || typeof target.scrollIntoView !== "function") return
    const reduced =
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    // block "nearest": never move the page vertically, only the rail.
    target.scrollIntoView({ inline: "nearest", block: "nearest", behavior: reduced ? "auto" : "smooth" })
  }

  return (
    <div
      ref={wrapperRef}
      className="relative -mx-1 -mb-1 mt-3"
      data-can-left="false"
      data-can-right="false"
      data-fine-pointer="false"
    >
      <div
        ref={railRef}
        data-cuisine-rail
        onFocus={revealFocused}
        className="flex snap-x flex-nowrap gap-1 overflow-x-auto scroll-px-1 p-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden [[data-fine-pointer=true]_>_&]:scroll-px-10 [[data-fine-pointer=true]_>_&]:snap-none"
      >
        {children}
      </div>
      <RailArrow side="left" onClick={() => nudge(-1)} />
      <RailArrow side="right" onClick={() => nudge(1)} />
    </div>
  )
}

function RailArrow({ side, onClick }: { side: "left" | "right"; onClick: () => void }) {
  const Icon = side === "left" ? ChevronLeft : ChevronRight
  // One compound parent selector turns it on: fine pointer AND room this way.
  const shown =
    side === "left"
      ? "[[data-fine-pointer=true][data-can-left=true]_>_&]:flex"
      : "[[data-fine-pointer=true][data-can-right=true]_>_&]:flex"
  return (
    <button
      type="button"
      tabIndex={-1}
      aria-label={side === "left" ? "Scroll cuisines left" : "Scroll cuisines right"}
      {...{ [`data-cuisine-rail-${side === "left" ? "prev" : "next"}`]: "" }}
      onClick={onClick}
      className={[
        "absolute top-1/2 z-10 hidden h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full",
        "bg-white text-warm-900 ring-1 ring-warm-300 hover:bg-warm-200",
        "transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.96] motion-reduce:transition-none",
        side === "left" ? "left-0" : "right-0",
        shown,
      ].join(" ")}
    >
      <Icon className="h-4 w-4" aria-hidden="true" />
    </button>
  )
}
