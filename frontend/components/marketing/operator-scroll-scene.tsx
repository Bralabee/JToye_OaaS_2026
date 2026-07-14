"use client"

import { type RefObject } from "react"
import { gsap, ScrollTrigger, useGSAP } from "@/lib/gsap"
import { canEnhance, DESKTOP_MOTION_QUERY, splitWords } from "@/lib/gsap-gate"

/**
 * useOperatorScrollScene — the `/for-operators` progressive-enhancement hook
 * (sketch 003-B), scoped to the OperatorPitch root so `useGSAP` auto-reverts
 * every tween + ScrollTrigger on unmount / breakpoint change.
 *
 * Desktop-with-motion only (gated by `gsap.matchMedia(DESKTOP_MOTION_QUERY)`):
 *  - Signature 1: pin the Service-rail hero and BUILD its three rail items
 *    one-by-one on scrub.
 *  - Signature 2: pin the pilot section and translate the four-step track
 *    HORIZONTALLY as you scroll vertically (function-based x/end +
 *    invalidateOnRefresh so widths recompute on resize / font-load).
 *  - Scrubbed count-up on the terms-band numeric values.
 *  - split-type headline entrance.
 *
 * No-FOUC contract: every hidden "from" state is set INSIDE the matchMedia
 * branch. On mobile / reduced-motion / jsdom / SSR (`canEnhance()` false or
 * the query does not match) NOTHING runs and the server-rendered content stays
 * fully visible. The horizontal track's overflow/flex layout is itself gated
 * by Tailwind `motion-safe:md:*` (same query) so on those paths the four steps
 * simply wrap and remain reachable.
 *
 * E2E signals: `[data-motion-active="desktop"]` on the scope (set in-branch,
 * removed in cleanup), `.gsap-word` spans, and ScrollTrigger's `.pin-spacer`.
 */
export function useOperatorScrollScene<T extends HTMLElement>(
  scopeRef: RefObject<T | null>,
): void {
  useGSAP(
    () => {
      if (!canEnhance()) return
      const root = scopeRef.current
      if (!root) return

      const mm = gsap.matchMedia()
      mm.add(DESKTOP_MOTION_QUERY, () => {
        root.setAttribute("data-motion-active", "desktop")

        // Split-type headline entrance (hand-split, no SplitText plugin).
        const headline = root.querySelector<HTMLElement>("[data-op-headline]")
        if (headline) {
          const words = splitWords(headline)
          gsap.set(words, { yPercent: 115, autoAlpha: 0 })
          gsap.to(words, {
            yPercent: 0,
            autoAlpha: 1,
            duration: 0.7,
            stagger: 0.04,
            ease: "power3.out",
            delay: 0.1,
          })
        }

        // SIGNATURE 1: pin the hero, build the three rail items on scrub.
        const heroPin = root.querySelector<HTMLElement>('[data-op-pin="hero"]')
        const railItems = gsap.utils.toArray<HTMLElement>(
          root.querySelectorAll("[data-rail-item]"),
        )
        if (heroPin && railItems.length) {
          gsap.set(railItems, { autoAlpha: 0, x: 24 })
          ScrollTrigger.create({
            trigger: heroPin,
            start: "top top",
            end: "+=1100",
            pin: true,
            scrub: true,
            invalidateOnRefresh: true,
            onUpdate: (self) => {
              const p = self.progress
              railItems.forEach((item, i) => {
                const seg = gsap.utils.clamp(0, 1, (p - i * 0.26) / 0.26)
                gsap.set(item, { autoAlpha: seg, x: 24 * (1 - seg) })
              })
            },
          })
        }

        // SIGNATURE 2: horizontal pilot rail — translate the wide track left
        // as its section is pinned. Function-based x/end + invalidateOnRefresh
        // so the scroll length recomputes on resize / webfont layout.
        const pilotPin = root.querySelector<HTMLElement>('[data-op-pin="pilot"]')
        const track = root.querySelector<HTMLElement>("[data-pilot-track]")
        if (pilotPin && track) {
          const scrollLen = () =>
            Math.max(0, track.scrollWidth - (track.parentElement?.clientWidth ?? 0) + 40)
          gsap.to(track, {
            x: () => -scrollLen(),
            ease: "none",
            scrollTrigger: {
              trigger: pilotPin,
              start: "top 12%",
              end: () => "+=" + scrollLen(),
              pin: true,
              scrub: 1,
              invalidateOnRefresh: true,
            },
          })
        }

        // Scrubbed count-up on the terms-band numeric values.
        const counts = root.querySelectorAll<HTMLElement>("[data-count-to]")
        if (counts.length) {
          const termsBand = root.querySelector<HTMLElement>("[data-op-terms]")
          ScrollTrigger.create({
            trigger: termsBand ?? counts[0],
            start: "top 80%",
            once: true,
            onEnter: () => {
              counts.forEach((el) => {
                const target = Number(el.getAttribute("data-count-to")) || 0
                const proxy = { value: 0 }
                gsap.to(proxy, {
                  value: target,
                  duration: 0.9,
                  ease: "power3.out",
                  onUpdate: () => {
                    el.textContent = String(Math.round(proxy.value))
                  },
                })
              })
            },
          })
        }

        // Recompute trigger positions once webfonts settle (CLS safety).
        if (typeof document !== "undefined" && "fonts" in document) {
          void document.fonts.ready.then(() => ScrollTrigger.refresh())
        }

        return () => {
          root.removeAttribute("data-motion-active")
        }
      })
    },
    { scope: scopeRef },
  )
}
