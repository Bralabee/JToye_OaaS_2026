"use client"

import { useEffect, useRef, useState, type ReactNode } from "react"
import { createPortal } from "react-dom"
import { m } from "framer-motion"

import { cn } from "@/lib/utils"
import { BOTTOM_CHROME_VAR } from "@/hooks/use-bottom-chrome-height"

/**
 * ONE bottom-chrome contract for every bottom-anchored notice — WR-03.
 *
 * WHY THIS MODULE EXISTS. R-07 fixed `CookieNotice` and left `ConsentBanner`
 * on the pre-fix shape (`fixed inset-x-0 bottom-0 z-40`, no pointer-events
 * split, no offset). `CookieNotice` RETURNS `<ConsentBanner />` and exits before
 * any of the R-07 work whenever a non-essential category is registered, so
 * every one of the five measured symptoms came back on that branch — including
 * symptom (2), the storefront cart bar painting over the dismiss control, which
 * on a CONSENT surface makes the banner permanently un-dismissable and the
 * choice unrecordable. That is a compliance failure, not a cosmetic one.
 *
 * It was dormant only by accident: nothing non-essential is registered today.
 * The day an analytics category ships, it would have shipped broken behind a
 * green suite, and the fix would have LOOKED done because `cookie-notice.tsx`
 * was fixed. So there is now one shell rather than a fixed copy and a forgotten
 * one — the drift is closed structurally instead of by remembering.
 *
 * ── WHAT THE SHELL GUARANTEES ───────────────────────────────────────────────
 *  - `pointer-events-none` on the positioning wrapper and `pointer-events-auto`
 *    on the card, so a notice cannot intercept a click on anything it is not
 *    itself drawn over.
 *  - `bottom: var(--jt-bottom-chrome, 0px)`, so it sits ABOVE whichever
 *    bottom-fixed bar is mounted and its own dismiss control stays clickable.
 *    The value is published by the bar itself
 *    (`hooks/use-bottom-chrome-height.ts`), so unlike a tuned constant it
 *    cannot go stale.
 *  - `z-40`, deliberately BELOW the z-50 cart bar and tab bar. That ranking was
 *    already correct and is unchanged: an informational notice must never
 *    occlude a transactional control or primary navigation.
 *  - ZERO CLS, by the same mechanism as before: `fixed` keeps it out of
 *    document flow, so no sibling moves when it mounts or unmounts, and neither
 *    caller server-renders.
 *  - `pb-[max(…,env(safe-area-inset-bottom))]` rather than a bare
 *    `env(safe-area-inset-bottom)`, which collapses to 0 on every non-notch
 *    device and puts the controls flush against the screen edge.
 *
 * ── SCOPE OF THE POINTER-EVENTS CLAIM, STATED HONESTLY (WR-02) ──────────────
 * `pointer-events-none` removes CLICK INTERCEPTION. It does not make the card
 * transparent, and a control the card is drawn OVER is still hidden — a visitor
 * cannot click a CTA they cannot see. So the split closes the interception
 * class, and it closes the dashboard sidebar case specifically (the card is
 * right-aligned from `sm` up, so it sits beside rather than over the bottom-left
 * rail). It does NOT by itself clear a control the card physically covers; that
 * is the offset's job, and the offset only helps where a publisher exists.
 * Today the publishers are the dashboard tab bar and the storefront cart bar,
 * so `/` and `/shop` still fall back to `bottom: 0px`. Recorded rather than
 * implied, because the earlier wording claimed the split "closes (1), (3) and
 * (4) as a CLASS" and that was stronger than the mechanism.
 */

/**
 * ── FOCUS IS NEVER HIDDEN BEHIND IT (37-52, UI-SPEC F5, UXT-085, WCAG 2.2 SC 2.4.11) ──────────────
 * Mounted at the end of the root layout, the notice was the LAST thing in the document: on a phone a
 * keyboard customer tabbed through the whole page BEHIND the card and reached "Got it" at the 32nd
 * stop. So the shell now does two things for every bottom notice:
 *
 *  - DOM ORDER. The section is portalled into a host PREPENDED to <body>, so it is the first thing a
 *    keyboard or a screen reader meets wherever the notice is mounted (the GOV.UK cookie-banner
 *    position, before the skip link). `fixed` keeps the VISUAL position exactly as before, and still
 *    out of flow, so the zero-CLS property is untouched. The host is created after mount and removed
 *    with the notice; neither caller renders on the server.
 *  - SCROLL PADDING. While the notice shows, `scroll-padding-bottom` on the document equals the band
 *    it covers (viewport bottom up to the notice's top edge, so the published bottom-chrome offset
 *    is included), and the browser scrolls a focused element clear of it. Re-measured by a
 *    ResizeObserver on the notice, on window resize, when the entrance animation settles, and when
 *    the bottom-chrome offset changes; removed (the previous inline value restored) when it goes.
 */

/** Marks the host element the notice is portalled into (first child of <body>). */
export const BOTTOM_NOTICE_HOST_ATTR = "data-bottom-notice-host"

/** The positioning wrapper: full-bleed, transparent to the pointer. */
export const BOTTOM_NOTICE_WRAPPER_CLASS = "fixed inset-x-0 z-40 pointer-events-none"

/** The drawn surface. Everything a visitor can click lives here. */
export const BOTTOM_NOTICE_CARD_CLASS = cn(
  "pointer-events-auto border border-white/15 bg-oxblood text-cream shadow-lg",
  // Inset from the edges on mobile so it reads as a card rather than a band…
  "mx-3 mb-3 rounded-xl",
  // …and right-aligned with a capped measure from `sm` up, so on the desktop
  // dashboard it sits nowhere near the bottom-LEFT sidebar rail.
  "sm:ml-auto sm:mr-4 sm:max-w-md",
  "px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]",
  "sm:pt-4 sm:pb-[max(1rem,env(safe-area-inset-bottom))]"
)

export function BottomNoticeShell({
  label,
  cardClassName,
  children,
}: {
  /** Accessible name for the region. */
  label: string
  /**
   * Merged over {@link BOTTOM_NOTICE_CARD_CLASS} via `cn` (tailwind-merge), so
   * a caller can widen the measure without being able to drop
   * `pointer-events-auto` by accident — a different utility group.
   */
  cardClassName?: string
  children: ReactNode
}) {
  // The host <body>'s first child. Created after mount: the notice never server-renders, and the
  // host must not outlive it.
  const [host, setHost] = useState<HTMLElement | null>(null)
  useEffect(() => {
    const el = document.createElement("div")
    el.setAttribute(BOTTOM_NOTICE_HOST_ATTR, "")
    document.body.prepend(el)
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the host exists only after mount (DOM API)
    setHost(el)
    return () => el.remove()
  }, [])

  const sectionRef = useRef<HTMLElement | null>(null)
  const measureRef = useRef<() => void>(() => {})
  useEffect(() => {
    const section = sectionRef.current
    if (!host || !section) return
    const root = document.documentElement
    const previous = root.style.scrollPaddingBottom
    const measure = () => {
      const band = Math.max(0, Math.ceil(window.innerHeight - section.getBoundingClientRect().top))
      const next = `${band}px`
      // Written only on a change: the style-attribute observer below would otherwise re-fire on
      // its own write.
      if (root.style.scrollPaddingBottom !== next) root.style.scrollPaddingBottom = next
    }
    measureRef.current = measure
    measure()
    const resize = new ResizeObserver(measure)
    resize.observe(section)
    // The bottom-chrome offset (the cart bar, the tab bar) is published on <html>'s style and moves
    // the notice without resizing it.
    const offset = new MutationObserver(measure)
    offset.observe(root, { attributes: true, attributeFilter: ["style"] })
    window.addEventListener("resize", measure)
    return () => {
      measureRef.current = () => {}
      resize.disconnect()
      offset.disconnect()
      window.removeEventListener("resize", measure)
      root.style.scrollPaddingBottom = previous
    }
  }, [host])

  if (!host) return null

  return createPortal(
    <m.section
      ref={sectionRef}
      aria-label={label}
      // 200ms fade + 8px translate-Y. framer-motion rather than a CSS
      // transition because `MotionConfig reducedMotion="user"`
      // (motion-provider.tsx) already governs it app-wide, so the
      // reduced-motion duty is inherited; a raw CSS transition would need its
      // own @media block in globals.css.
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2 }}
      // The entrance slide is a transform, which a ResizeObserver does not see: measure once more
      // when it settles so the padding is the band at rest.
      onAnimationComplete={() => measureRef.current()}
      className={BOTTOM_NOTICE_WRAPPER_CLASS}
      style={{ bottom: `var(${BOTTOM_CHROME_VAR}, 0px)` }}
    >
      <div className={cn(BOTTOM_NOTICE_CARD_CLASS, cardClassName)}>{children}</div>
    </m.section>,
    host
  )
}
