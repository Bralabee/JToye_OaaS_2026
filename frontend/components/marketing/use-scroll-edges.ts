"use client"

import { useCallback, useEffect, type RefObject } from "react"

/**
 * The scroll-edge logic shared by DishScroller (the kitchen row) and
 * CuisineRail (the hero's chip rail). One implementation, so a fix lands in
 * both (PR 1108 review, finding 3). DishScroller's header holds the reasoning;
 * in short:
 *
 * - State is written to `data-can-left` / `data-can-right` /
 *   `data-fine-pointer` on the wrapper through a ref — never React state, so
 *   a scroll re-renders nothing (finding 4), and the buttons stay mounted, so
 *   a clicked arrow that runs out of room hides without dropping focus
 *   (finding 6). CSS reads the attributes.
 * - The fine pointer is resolved in JS: a Tailwind arbitrary `@media` variant
 *   stacked on a data-attribute variant emits no `@media` at all and shows the
 *   arrows on touch (measured on the dev build 2026-08-02).
 * - The row's end moves without a scroll event when the box resizes OR when
 *   an item's width changes inside an unchanged box — a web-font swap
 *   (`display: swap`) widens every label. So the scroller AND each child are
 *   observed (finding 1: widening the chips by 169px with the rail's box
 *   unchanged showed no arrow). Without ResizeObserver it falls back to window
 *   resize plus the font set's load (finding 5).
 */

// Subpixel tolerance: `scrollLeft` is fractional under zoom and on HiDPI.
export const EDGE_EPSILON_PX = 2

// Nudge just under a viewport so a sliver of the outgoing item stays visible.
export const NUDGE_RATIO = 0.8

export function useScrollEdges(
  wrapperRef: RefObject<HTMLElement | null>,
  scrollerRef: RefObject<HTMLElement | null>,
) {
  const syncEdges = useCallback(() => {
    const wrapper = wrapperRef.current
    const scroller = scrollerRef.current
    if (!wrapper || !scroller) return

    const maxScroll = scroller.scrollWidth - scroller.clientWidth
    const overflows = maxScroll > EDGE_EPSILON_PX
    wrapper.dataset.canLeft = String(overflows && scroller.scrollLeft > EDGE_EPSILON_PX)
    wrapper.dataset.canRight = String(overflows && scroller.scrollLeft < maxScroll - EDGE_EPSILON_PX)
  }, [wrapperRef, scrollerRef])

  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller) return

    syncEdges()
    scroller.addEventListener("scroll", syncEdges, { passive: true })

    const cleanups: Array<() => void> = [
      () => scroller.removeEventListener("scroll", syncEdges),
    ]
    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(syncEdges)
      observer.observe(scroller)
      // border-box: a padding or border change widens an item too, not only its content.
      for (const child of Array.from(scroller.children)) observer.observe(child, { box: "border-box" })
      cleanups.push(() => observer.disconnect())
    } else {
      window.addEventListener("resize", syncEdges)
      cleanups.push(() => window.removeEventListener("resize", syncEdges))
      // Late web fonts change item widths with no resize; re-measure once they land.
      if (typeof document !== "undefined" && document.fonts?.ready) {
        let live = true
        document.fonts.ready.then(() => {
          if (live) syncEdges()
        })
        cleanups.push(() => {
          live = false
        })
      }
    }
    return () => cleanups.forEach((fn) => fn())
  }, [scrollerRef, syncEdges])

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
  }, [wrapperRef])

  const nudge = useCallback(
    (direction: 1 | -1) => {
      const scroller = scrollerRef.current
      if (!scroller) return
      // Reduced motion: same distance, no travel animation.
      const reduced =
        typeof window !== "undefined" &&
        typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches
      scroller.scrollBy({
        left: direction * scroller.clientWidth * NUDGE_RATIO,
        behavior: reduced ? "auto" : "smooth",
      })
    },
    [scrollerRef],
  )

  return { nudge }
}
