"use client"

import { type RefObject } from "react"
import { gsap, useGSAP } from "@/lib/gsap"
import {
  canEnhance,
  DESKTOP_MOTION_QUERY,
  entranceIsSafeForMount,
  splitWords,
} from "@/lib/gsap-gate"

/**
 * useOperatorEntranceScene — the `/for-operators` progressive-enhancement hook.
 *
 * ON-LOAD, NOT ON-SCROLL, AND ONE MOMENT. The page arrives populated, the way
 * `/` does. Exactly two things move, both once, both on load:
 *
 *  - the headline splits and plays its word stagger;
 *  - the hero image frame (`[data-op-media]`, which carries the price docket
 *    `[data-op-figure]` with it) rises into place from 0.6 opacity. It is the
 *    page's LCP candidate, so it is NEVER taken to opacity 0 or hidden: a
 *    transparent image paints nothing, and the page would report a later LCP.
 *
 * Nothing else animates. In particular there is no price count-up (a figure
 * that climbs from 0 reads as a pricing error, and 0.5 rounded to 1 under the
 * old `Math.round`), no ScrollTrigger of any kind, no pinning, and no scroll
 * reveal: the page scrolls normally and `.pin-spacer` stays absent.
 *
 * Desktop-with-motion only (gated by `gsap.matchMedia(DESKTOP_MOTION_QUERY)`).
 *
 * No-FOUC contract: every hidden "from" state is set INSIDE the matchMedia
 * branch, and each is paired with a `.to()` that runs on load with no external
 * trigger — so content can never be stranded invisible. On mobile /
 * reduced-motion / jsdom / SSR (`canEnhance()` false or the query does not
 * match) NOTHING runs and the server-rendered content stays fully visible.
 *
 * LATE-ARRIVAL contract (R-03, ported from `hero-scene.tsx`), the OPPOSITE
 * failure to No-FOUC: both entrance blocks open by HIDING painted content
 * (`autoAlpha: 0`). If this bundle hydrates late on a throttled load, that
 * would blank a headline and image the visitor is already reading. So the
 * verdict `entranceIsSafeForMount` is taken ONCE per mount, OUTSIDE
 * `matchMedia` (the callback re-fires on every breakpoint change, which is not
 * a new mount), and when it is "unsafe" both blocks are skipped. The word
 * spans are still created, because specs hang assertions on `.gsap-word` and
 * they are inert without the tweens. `[data-entrance="played"|"skipped"]`
 * reports the verdict.
 *
 * E2E signals: `[data-motion-active="desktop"]` on the scope (set in-branch,
 * removed in cleanup), `.gsap-word` spans, `[data-entrance]`, and
 * `[data-motion-decided="scene"|"static"]` on the scope — an INERT marker
 * (no stylesheet, no logic reads it) stamped once the enhancer has RUN and
 * decided either way, so absence assertions have a deterministic anchor.
 */
export function useOperatorEntranceScene<T extends HTMLElement>(
  scopeRef: RefObject<T | null>,
): void {
  useGSAP(
    () => {
      if (!canEnhance()) return
      const root = scopeRef.current
      if (!root) return

      const animateEntrance = entranceIsSafeForMount(
        typeof performance !== "undefined" ? performance.now() : 0,
      )

      const mm = gsap.matchMedia()
      mm.add(DESKTOP_MOTION_QUERY, () => {
        root.setAttribute("data-motion-active", "desktop")
        root.setAttribute("data-motion-decided", "scene")
        root.setAttribute("data-entrance", animateEntrance ? "played" : "skipped")

        // Split-type headline entrance (hand-split, no SplitText plugin). The
        // split is UNCONDITIONAL; only the hide-then-reveal is gated.
        const headline = root.querySelector<HTMLElement>("[data-op-headline]")
        if (headline) {
          const words = splitWords(headline)
          if (animateEntrance) {
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
        }

        // The hero image frame (and the docket pinned over it) rises 16px from
        // 0.6 opacity, a beat after the first words. Opacity and transform only,
        // and never to 0: this is the LCP candidate (see the docblock).
        const media = root.querySelector<HTMLElement>("[data-op-media]")
        if (media && animateEntrance) {
          gsap.set(media, { opacity: 0.6, y: 16 })
          gsap.to(media, {
            opacity: 1,
            y: 0,
            duration: 0.6,
            delay: 0.25,
            ease: "power3.out",
          })
        }

        return () => {
          root.removeAttribute("data-motion-active")
          root.removeAttribute("data-entrance")
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
