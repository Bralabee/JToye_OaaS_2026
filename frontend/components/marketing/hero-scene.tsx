"use client"

import { useRef, type ReactNode } from "react"
import { gsap, ScrollTrigger, useGSAP } from "@/lib/gsap"
import {
  canEnhance,
  DESKTOP_MOTION_QUERY,
  entranceIsSafeForMount,
  splitWords,
} from "@/lib/gsap-gate"

/**
 * HeroScene — the `/` landing progressive-enhancement seam (sketch 002-B).
 *
 * Renders its Server-Component children UNTOUCHED and fully visible, then —
 * only in the browser, only on desktop with motion allowed — layers the GSAP
 * "full-award" choreography over them: split-type headline stagger, persona
 * door deal-in, parallax heat-wash, and a scrubbed step-rail that draws and
 * activates each step as you pass it.
 *
 * No-FOUC contract: every hidden "from" state is set INSIDE the
 * `gsap.matchMedia(DESKTOP_MOTION_QUERY, …)` branch (client + desktop +
 * motion-allowed). If the bundle fails, is slow, the gate does not match, or
 * we are on jsdom/SSR (`canEnhance()` false), NONE of it runs and the
 * server-rendered content stays visible. `useGSAP({ scope })` auto-reverts
 * every tween + ScrollTrigger (and matchMedia restores `autoAlpha`) on
 * unmount / breakpoint change, so nothing leaks across a client route change.
 *
 * E2E signals: `[data-motion-active="desktop"]` on the scope (set in-branch,
 * removed in cleanup), `.gsap-word` spans from `splitWords`, and
 * `[data-motion-decided="scene"|"static"]` on the scope — an INERT marker
 * (no stylesheet, no logic reads it) stamped once the enhancer has RUN and
 * decided either way, so absence assertions have a deterministic anchor.
 * `[data-entrance="played"|"skipped"]` joins them (R-03), reporting whether the
 * bundle arrived in time for an entrance to still BE an entrance.
 *
 * LATE-ARRIVAL contract (R-03), which is the OPPOSITE of the No-FOUC one above
 * and needs the opposite defence: on the FIRST mount after a document load,
 * past `ENTRANCE_BUDGET_MS` the two entrance blocks are SKIPPED, because their
 * `autoAlpha: 0` would hide content the visitor is already reading. On a SOFT
 * NAVIGATION to `/` the entrance always plays (WR-01): nothing was painted
 * before this scene existed, so there is nothing to blank. Everything else in
 * the scene is scroll-triggered and cannot blank a first paint, so it is
 * untouched. The no-JS path (nothing runs) and the reduced-motion / mobile path
 * (the query does not match) are both already CORRECT and are likewise
 * untouched — this fix must not regress either to close the third.
 */
export function HeroScene({ children }: { children: ReactNode }) {
  const scope = useRef<HTMLDivElement>(null)

  useGSAP(
    () => {
      if (!canEnhance()) return
      const root = scope.current
      if (!root) return

      // R-03 (2026-08-31 customer-surface audit). The two ENTRANCE blocks below
      // open by HIDING content (`autoAlpha: 0`) and then bringing it in. That
      // is correct while there is nothing on screen yet — and a regression once
      // there is. On a throttled load this bundle hydrates ~2.5s after first
      // paint, so the `set` RETROACTIVELY BLANKED an h1 and the persona CTAs
      // the visitor had already been reading (~800ms of blank). The file's
      // No-FOUC contract above guards the opposite failure ("the JS never
      // arrives") and cannot see this one.
      //
      // DECIDED ONCE PER MOUNT, OUTSIDE `matchMedia` (WR-01). Two reasons:
      //  - `entranceIsSafeForMount` consumes the per-document first-mount
      //    latch, and the matchMedia callback re-fires on every breakpoint
      //    change, which is not a new mount and must not be counted as one;
      //  - a breakpoint change therefore reuses this mount's verdict instead of
      //    re-playing an entrance over content that is already on screen.
      //
      // The argument is `performance.now()` — ms since the document's TIME
      // ORIGIN — and it is the right clock for the FIRST mount only. The gate
      // owns that distinction; see its docblock for why a soft navigation must
      // not be measured against it.
      const animateEntrance = entranceIsSafeForMount(
        typeof performance !== "undefined" ? performance.now() : 0
      )

      const mm = gsap.matchMedia()
      mm.add(DESKTOP_MOTION_QUERY, () => {
        root.setAttribute("data-motion-active", "desktop")
        root.setAttribute("data-motion-decided", "scene")
        // INERT marker so the orchestrator's throttled-profile pass can observe
        // which way the decision went. Inert means inert: no stylesheet rule
        // reads it and no logic here branches on it.
        root.setAttribute("data-entrance", animateEntrance ? "played" : "skipped")

        // Headline split-type entrance (hand-split, no SplitText plugin).
        const headline = root.querySelector<HTMLElement>("[data-hero-headline]")
        if (headline) {
          // UNCONDITIONAL, even when the entrance is skipped. The `.gsap-word`
          // spans are an E2E signal that other specs hang assertions on, and
          // they are visually inert without the tweens — skipping the wrap to
          // "save work" would break assertions that have nothing to do with
          // this fix.
          const words = splitWords(headline)
          if (animateEntrance) {
            gsap.set(words, { yPercent: 115, autoAlpha: 0 })
            gsap.to(words, {
              yPercent: 0,
              autoAlpha: 1,
              duration: 0.7,
              stagger: 0.045,
              ease: "power3.out",
              delay: 0.1,
            })
          }
        }

        // Persona doors deal-in. Explicit set+to (NOT `gsap.from`): a plain
        // `from` (no ScrollTrigger to own its lifecycle) has immediateRender,
        // and the fonts.ready `ScrollTrigger.refresh()` below re-asserts its
        // hidden start state — leaving the doors stuck at autoAlpha:0 (the
        // primary CTAs invisible on desktop). set+to animates toward the
        // natural visible state and is refresh-safe, mirroring the headline.
        const doors = root.querySelectorAll<HTMLElement>("[data-hero-door]")
        if (doors.length && animateEntrance) {
          gsap.set(doors, { autoAlpha: 0, y: 34, rotateZ: -1.5 })
          gsap.to(doors, {
            autoAlpha: 1,
            y: 0,
            rotateZ: 0,
            duration: 0.6,
            stagger: 0.12,
            delay: 0.45,
            ease: "power3.out",
          })
        }

        // Parallax heat-wash on scroll through the hero.
        const heatwash = root.querySelector<HTMLElement>("[data-hero-heatwash]")
        const hero = root.querySelector<HTMLElement>("[data-hero-section]")
        if (heatwash && hero) {
          gsap.to(heatwash, {
            yPercent: 30,
            ease: "none",
            scrollTrigger: {
              trigger: hero,
              start: "top top",
              end: "bottom top",
              scrub: true,
            },
          })
        }

        // "How it works" section title reveal.
        const howTitle = root.querySelector<HTMLElement>("[data-hero-howtitle]")
        if (howTitle) {
          gsap.from(howTitle, {
            autoAlpha: 0,
            y: 24,
            duration: 0.6,
            ease: "power3.out",
            scrollTrigger: { trigger: howTitle, start: "top 82%" },
          })
        }

        // Scrubbed step-rail: draw the fill left→right as the grid passes.
        const stepsGrid = root.querySelector<HTMLElement>("[data-hero-steps]")
        const railFill = root.querySelector<HTMLElement>("[data-hero-railfill]")
        if (stepsGrid && railFill) {
          gsap.set(railFill, { transformOrigin: "left", scaleX: 0 })
          gsap.to(railFill, {
            scaleX: 1,
            ease: "none",
            scrollTrigger: {
              trigger: stepsGrid,
              start: "top 75%",
              end: "bottom 62%",
              scrub: 0.5,
            },
          })
        }

        // Each step deals in and toggles its active state on scrub.
        const steps = root.querySelectorAll<HTMLElement>("[data-hero-step]")
        steps.forEach((step) => {
          gsap.from(step, {
            autoAlpha: 0,
            y: 30,
            duration: 0.5,
            ease: "power3.out",
            scrollTrigger: { trigger: step, start: "top 82%" },
          })
          ScrollTrigger.create({
            trigger: step,
            start: "top 62%",
            end: "bottom 40%",
            onEnter: () => step.setAttribute("data-step-active", "true"),
            onLeaveBack: () => step.removeAttribute("data-step-active"),
          })
        })

        // Trust chips pop-in.
        const chips = root.querySelectorAll<HTMLElement>("[data-hero-chip]")
        if (chips.length) {
          gsap.from(chips, {
            autoAlpha: 0,
            y: 24,
            duration: 0.5,
            stagger: 0.1,
            ease: "back.out(1.6)",
            scrollTrigger: { trigger: chips[0], start: "top 88%" },
          })
        }

        // Recompute trigger positions once webfonts settle (CLS safety).
        if (typeof document !== "undefined" && "fonts" in document) {
          void document.fonts.ready.then(() => ScrollTrigger.refresh())
        }

        return () => {
          root.removeAttribute("data-motion-active")
          // Describes a scene that no longer exists, so it goes with it —
          // same treatment as `data-motion-active`.
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
    { scope },
  )

  return <div ref={scope}>{children}</div>
}
