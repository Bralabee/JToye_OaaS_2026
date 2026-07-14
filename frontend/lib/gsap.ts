"use client"

/**
 * The ONE place GSAP plugins are registered for the whole app.
 *
 * Imported only by the two `"use client"` marketing enhancers
 * (`hero-scene.tsx`, `operator-scroll-scene.tsx`), which are in turn imported
 * only by the two marketing routes — so Next code-splits GSAP + ScrollTrigger
 * into those route chunks and it never leaks into the app/storefront bundles
 * (STRIDE T-motion-D-02). Registration at module scope runs once per chunk
 * load; `useGSAP` is registered so its React integration participates in
 * GSAP's plugin system.
 *
 * GSAP core + ScrollTrigger do not use `eval`/`new Function`, so bundling is
 * CSP-clean under the #89 `script-src 'strict-dynamic'` policy (no
 * `'unsafe-eval'` needed) — verified by the extended csp-no-violations spec.
 */
import { gsap } from "gsap"
import { ScrollTrigger } from "gsap/ScrollTrigger"
import { useGSAP } from "@gsap/react"

// Register only in a real browser. ScrollTrigger.register() calls
// `window.matchMedia` on enable, which is undefined during the SSR/prerender
// Node pass and in jsdom — the same condition the enhancers gate scene-builds
// on (`canEnhance()`), so skipping registration there is safe: no GSAP scene
// is ever built without matchMedia. In the browser this runs once per chunk.
if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
  gsap.registerPlugin(ScrollTrigger, useGSAP)
}

export { gsap, ScrollTrigger, useGSAP }
