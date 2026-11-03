"use client"

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"

/**
 * The landing's cuisine chip rail (D15: one non-wrapping row, all six chips).
 *
 * From 1280px up the six chips fit and nothing here shows. Below that the rail
 * overflows: touch swipes it, a keyboard tabs through it (the browser scrolls
 * the focused chip into view), but a MOUSE had only the cut-off chip as a cue
 * and no way to scroll a hidden-scrollbar row without a trackpad. D18 (owner,
 * 2026-11-03) adds prev/next arrows for that one case: they render only when
 * that side has hidden chips, and only for a hovering fine pointer — never on
 * touch. They are out of flow (no layout shift) and out of the tab order
 * (keyboard users already reach every chip, so they would be dead tab stops),
 * but keep an accessible name for anyone who does activate them.
 *
 * Flat by identity.md: a white pill on the stone hero, a warm-300 ring to part
 * it from the white chips it sits over; no shadow, no gradient (rulings.tsv).
 */
export function CuisineRail({ children }: { children: ReactNode }) {
  const railRef = useRef<HTMLDivElement>(null)
  const [canLeft, setCanLeft] = useState(false)
  const [canRight, setCanRight] = useState(false)

  const measure = useCallback(() => {
    const el = railRef.current
    if (!el) return
    // 1px tolerance: fractional widths at browser zoom leave a sub-pixel remainder.
    setCanLeft(el.scrollLeft > 1)
    setCanRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1)
  }, [])

  useEffect(() => {
    const el = railRef.current
    if (!el) return
    measure()
    el.addEventListener("scroll", measure, { passive: true })
    const ro = typeof ResizeObserver !== "undefined" ? new ResizeObserver(measure) : null
    ro?.observe(el)
    return () => {
      el.removeEventListener("scroll", measure)
      ro?.disconnect()
    }
  }, [measure])

  const scrollByPage = (direction: -1 | 1) => {
    const el = railRef.current
    if (!el) return
    const reduce =
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    el.scrollBy({
      left: direction * Math.max(el.clientWidth * 0.8, 80),
      behavior: reduce ? "auto" : "smooth",
    })
  }

  const arrowClass =
    "absolute top-1/2 z-10 hidden h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full bg-white text-warm-900 ring-1 ring-warm-300 transition-colors duration-150 hover:bg-warm-200 active:scale-[0.96] motion-reduce:transition-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 [@media(hover:hover)_and_(pointer:fine)]:flex"

  return (
    <div className="relative -mx-1 -mb-1 mt-3">
      <div
        ref={railRef}
        data-cuisine-rail
        className="flex snap-x flex-nowrap gap-1 overflow-x-auto scroll-px-1 p-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {children}
      </div>
      {canLeft && (
        <button
          type="button"
          tabIndex={-1}
          aria-label="Scroll cuisines left"
          data-cuisine-rail-prev
          onClick={() => scrollByPage(-1)}
          className={cn(arrowClass, "left-0")}
        >
          <ChevronLeft className="h-4 w-4" aria-hidden="true" />
        </button>
      )}
      {canRight && (
        <button
          type="button"
          tabIndex={-1}
          aria-label="Scroll cuisines right"
          data-cuisine-rail-next
          onClick={() => scrollByPage(1)}
          className={cn(arrowClass, "right-0")}
        >
          <ChevronRight className="h-4 w-4" aria-hidden="true" />
        </button>
      )}
    </div>
  )
}
