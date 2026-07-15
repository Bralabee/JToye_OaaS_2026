"use client"

import { useRef, type ReactNode } from "react"
import { gsap, ScrollTrigger, useGSAP } from "@/lib/gsap"
import { canEnhance, DESKTOP_MOTION_QUERY, splitWords } from "@/lib/gsap-gate"

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
 * removed in cleanup) and `.gsap-word` spans from `splitWords`.
 */
export function HeroScene({ children }: { children: ReactNode }) {
  const scope = useRef<HTMLDivElement>(null)

  useGSAP(
    () => {
      if (!canEnhance()) return
      const root = scope.current
      if (!root) return

      const mm = gsap.matchMedia()
      mm.add(DESKTOP_MOTION_QUERY, () => {
        root.setAttribute("data-motion-active", "desktop")

        // Headline split-type entrance (hand-split, no SplitText plugin).
        const headline = root.querySelector<HTMLElement>("[data-hero-headline]")
        if (headline) {
          const words = splitWords(headline)
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

        // Persona doors deal-in. Explicit set+to (NOT `gsap.from`): a plain
        // `from` (no ScrollTrigger to own its lifecycle) has immediateRender,
        // and the fonts.ready `ScrollTrigger.refresh()` below re-asserts its
        // hidden start state — leaving the doors stuck at autoAlpha:0 (the
        // primary CTAs invisible on desktop). set+to animates toward the
        // natural visible state and is refresh-safe, mirroring the headline.
        const doors = root.querySelectorAll<HTMLElement>("[data-hero-door]")
        if (doors.length) {
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
        }
      })
    },
    { scope },
  )

  return <div ref={scope}>{children}</div>
}
