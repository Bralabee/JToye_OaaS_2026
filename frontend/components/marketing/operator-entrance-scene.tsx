"use client"

import { type RefObject } from "react"
import { gsap, ScrollTrigger, useGSAP } from "@/lib/gsap"
import { canEnhance, DESKTOP_MOTION_QUERY, splitWords } from "@/lib/gsap-gate"

/**
 * useOperatorEntranceScene — the `/for-operators` progressive-enhancement hook.
 *
 * ON-LOAD, NOT ON-SCROLL. The previous version (sketch 003-B) pinned the hero
 * and *scrubbed* the three Service-rail items in, so the page arrived with an
 * empty headline and an empty rail card and only filled as you scrolled — plus
 * a pinned horizontal pilot track that hijacked the wheel. That was rejected:
 * the page should land already populated, the way `/` does. So:
 *
 *  - the headline splits and plays its word stagger immediately on load;
 *  - the three rail items deal in immediately on load (stagger), never hidden
 *    behind a scroll position;
 *  - NO ScrollTrigger pinning anywhere — the page scrolls normally;
 *  - the only scroll-linked touch left is the terms-band count-up, which fires
 *    once when that band (far below the fold) enters view and never hides text:
 *    the real figure is what is server-rendered.
 *
 * Desktop-with-motion only (gated by `gsap.matchMedia(DESKTOP_MOTION_QUERY)`).
 *
 * No-FOUC contract: every hidden "from" state is set INSIDE the matchMedia
 * branch, and each is paired with a `.to()` that runs on load with no external
 * trigger — so content can never be stranded invisible. On mobile /
 * reduced-motion / jsdom / SSR (`canEnhance()` false or the query does not
 * match) NOTHING runs and the server-rendered content stays fully visible.
 *
 * E2E signals: `[data-motion-active="desktop"]` on the scope (set in-branch,
 * removed in cleanup), `.gsap-word` spans, and
 * `[data-motion-decided="scene"|"static"]` on the scope — an INERT marker
 * (no stylesheet, no logic reads it) stamped once the enhancer has RUN and
 * decided either way, so absence assertions have a deterministic anchor.
 * There is deliberately no `.pin-spacer` any more — its absence is part of
 * the contract.
 */
export function useOperatorEntranceScene<T extends HTMLElement>(
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
        root.setAttribute("data-motion-decided", "scene")

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

        // Service-rail items deal in on LOAD (they used to be scrubbed in by a
        // pinned ScrollTrigger, which left the card blank until you scrolled).
        const railItems = gsap.utils.toArray<HTMLElement>(
          root.querySelectorAll("[data-rail-item]"),
        )
        if (railItems.length) {
          gsap.set(railItems, { autoAlpha: 0, x: 24 })
          gsap.to(railItems, {
            autoAlpha: 1,
            x: 0,
            duration: 0.55,
            stagger: 0.12,
            delay: 0.35,
            ease: "power3.out",
          })
        }

        // Count-up on the terms-band numeric values. Fires once when the band
        // (far below the fold) enters view; the server-rendered figure is real.
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
          // matchMedia cleanup also runs on breakpoint change, so the marker
          // stays truthful if the desktop query stops matching mid-session.
          root.setAttribute("data-motion-decided", "static")
        }
      })

      // Negative-branch default: mm.add fires its callback synchronously when
      // the query matches, so on desktop the attribute is already "scene" here.
      // Mobile / reduced-motion pages get their stamp the moment the enhancer
      // has run and DECLINED to build a scene.
      if (!root.hasAttribute("data-motion-decided")) {
        root.setAttribute("data-motion-decided", "static")
      }
    },
    { scope: scopeRef },
  )
}
