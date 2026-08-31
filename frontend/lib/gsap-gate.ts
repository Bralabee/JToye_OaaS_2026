/**
 * Pure GSAP-gating utilities — the decision layer that keeps the heavy
 * desktop scroll choreography (sketch 002-B / 003-B) OFF phones and
 * `prefers-reduced-motion` users.
 *
 * This module is deliberately PURE: no `"use client"`, no `gsap` import, no
 * side effects at module scope. That keeps it jsdom-testable in isolation and
 * out of the GSAP route chunk's registration path. The `"use client"`
 * enhancers (`hero-scene.tsx`, `operator-scroll-scene.tsx`) import both this
 * gate and `lib/gsap.ts`; unit tests import ONLY this file so `gsap` never
 * enters the jsdom test graph.
 */

/**
 * Media query that unlocks the GSAP scenes: Tailwind `md` breakpoint and up
 * (768px, per RESEARCH A2) AND no reduced-motion preference. Used verbatim by
 * both `gsap.matchMedia(...)` branches so the CSS-media decision and the
 * runtime-JS decision can never diverge.
 */
export const DESKTOP_MOTION_QUERY =
  "(min-width: 768px) and (prefers-reduced-motion: no-preference)"

/**
 * Pure predicate mirroring {@link DESKTOP_MOTION_QUERY} for unit testing and
 * any imperative width/reduced-motion decision. 768 is inclusive (Tailwind
 * `md` = min-width:768px).
 */
export function prefersDesktopMotion(opts: {
  width: number
  reducedMotion: boolean
}): boolean {
  return opts.width >= 768 && !opts.reducedMotion
}

/**
 * True only in a browser that can evaluate media queries. The enhancers guard
 * every scene build with this so that jsdom / SSR (where `matchMedia` is
 * undefined) is a clean no-op and the server-rendered content stays visible.
 */
export function canEnhance(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
}

/**
 * How long after navigation start an ENTRANCE animation is still an entrance.
 *
 * 1200 ms: comfortably past a healthy hydration on a warm connection, and well
 * short of the ~2.5 s measured on a throttled 4x-CPU / Slow-4G landing load.
 */
export const ENTRANCE_BUDGET_MS = 1200

/**
 * Is it still safe to play an ENTRANCE — R-03 (2026-08-31 customer-surface
 * audit).
 *
 * AN ENTRANCE IS AN ENTRANCE, NOT A REVEAL. It works by hiding content and
 * bringing it in, which is correct only while there is nothing on screen yet.
 * Past the budget the content is ALREADY PAINTED and the visitor is already
 * reading it, so hiding it in order to animate it in is a regression dressed as
 * a flourish — measured on `/`, a late GSAP bundle blanked the h1 and the
 * persona CTAs for ~800 ms after the user had been reading them.
 *
 * This is the OPPOSITE failure to the one `hero-scene.tsx`'s "No-FOUC contract"
 * guards. That one is "the JS never arrives"; this one is "the JS arrives
 * LATE", and it needs the opposite defence — which is why a new predicate
 * rather than a tweak to the existing gate.
 *
 * @param elapsedMs milliseconds since navigation start, i.e. `performance.now()`.
 *                  Inclusive at the boundary; anything at or below the budget
 *                  is safe, so a caller that cannot measure (passing 0) always
 *                  gets the animated path it would have had before this change.
 */
export function entranceIsSafe(elapsedMs: number): boolean {
  return elapsedMs <= ENTRANCE_BUDGET_MS
}

/**
 * Has the first scene of this document already mounted? Latched per document.
 *
 * This is the module's ONLY mutable state. It carries no side effect at module
 * scope, no `"use client"` and no `gsap` import, so the two properties the
 * docblock above actually depends on — jsdom-testability, and staying out of
 * the GSAP route chunk — are untouched. Keeping the latch HERE rather than in
 * `hero-scene.tsx` is what makes the rule below testable at all: jsdom has no
 * `matchMedia`, so `canEnhance()` is false and the scene branch that would own
 * the flag is unreachable from a component test.
 */
let firstMountPending = true

/**
 * Is it safe to play the entrance for a scene mounting RIGHT NOW — WR-01.
 *
 * `entranceIsSafe` alone was measured against the wrong clock.
 * `performance.now()` is milliseconds since the document's TIME ORIGIN, which
 * is set once at the initial page load and is **not** reset by client-side
 * routing. `HeroScene` is mounted by `app/page.tsx`, and `/` is reachable by
 * soft navigation from every public surface — `public-header.tsx`'s own
 * docblock states the wordmark "ALWAYS goes to `/`" via `next/link`.
 *
 * So a visitor who landed on `/shop`, browsed for 30 s and clicked the wordmark
 * mounted the hero at `performance.now() ≈ 30000` and the entrance was refused.
 * The entrance was therefore **dead for the whole rest of the session** after
 * the first 1.2 s, however fast the bundle had arrived — and `data-entrance`
 * faithfully reported `"skipped"`, so an observation pass read green either way.
 * That is a regression by omission under the Incremental Betterment Doctrine: a
 * working good traded away to fix the late-hydration case.
 *
 * The budget answers exactly ONE question — "was content painted before this
 * code ran, such that hiding it now would blank something the visitor is
 * already reading?" — and that question only has a yes on the FIRST mount after
 * a document load, where the server-rendered hero was painted at first paint.
 * On a soft navigation the hero's DOM did not exist a moment ago; nothing was
 * painted to blank, so the entrance is always safe.
 *
 * REJECTED ALTERNATIVE, because it is the obvious one and it is silently wrong:
 * capturing `mountedAt = performance.now()` in the effect and testing
 * `performance.now() - mountedAt`. Both readings sit in the same synchronous
 * effect body, so the delta is MEASURED at 0.001–0.010 ms — always inside any
 * budget. The guard would be permanently open, `data-entrance` would always
 * report `"played"`, and R-03 would be undone while the code still looked as
 * though it had a budget.
 *
 * DEV CAVEAT, stated rather than discovered later: React StrictMode
 * double-invokes effects in development, so the second invoke consumes the
 * latch and reads as a soft navigation. Production mounts once. Dev hydration
 * is slow enough that the entrance would usually be skipped there anyway, so
 * the difference is dev-only and cosmetic.
 */
export function entranceIsSafeForMount(elapsedMs: number): boolean {
  if (!firstMountPending) return true
  firstMountPending = false
  return entranceIsSafe(elapsedMs)
}

/**
 * Re-arm the first-mount latch. TEST ONLY.
 *
 * Jest keeps one module registry per FILE, so the latch outlives an individual
 * `it`. Same escape hatch, and the same reason, as
 * `lib/customer-session-store.__resetForTests`.
 */
export function __resetEntranceMountGateForTests(): void {
  firstMountPending = true
}

const WORD_CLASS = "gsap-word"

/**
 * Wrap each whitespace-delimited word of `el` in a `<span class="gsap-word">`
 * so the headline can animate word-by-word, WITHOUT the SplitText plugin
 * (RESEARCH "Don't Hand-Roll").
 *
 * Safety + correctness contract:
 *  - Uses `textContent`/`createElement` only — never `innerHTML` with data,
 *    closing the DOM-XSS surface (STRIDE T-motion-D-03). Input is static,
 *    developer-authored copy.
 *  - Preserves whitespace tokens and nested child elements (e.g. an accent
 *    `<span>`): a child element survives and its inner words are wrapped
 *    inside it, so styling and `textContent` round-trip losslessly.
 *  - Idempotent: if `el` already contains `.gsap-word` spans, the existing
 *    spans are returned unchanged (no double-wrapping on a matchMedia re-run).
 */
export function splitWords(el: HTMLElement): HTMLSpanElement[] {
  const existing = el.querySelectorAll<HTMLSpanElement>(`.${WORD_CLASS}`)
  if (existing.length > 0) return Array.from(existing)

  const appendTokens = (target: Node, text: string): void => {
    // Keep the whitespace tokens (capturing group) so spacing is preserved.
    for (const token of text.split(/(\s+)/)) {
      if (token === "") continue
      if (token.trim() === "") {
        target.appendChild(document.createTextNode(token))
        continue
      }
      const span = document.createElement("span")
      span.className = WORD_CLASS
      span.textContent = token
      target.appendChild(span)
    }
  }

  const original = Array.from(el.childNodes)
  // Detach existing children (no innerHTML write — just removes nodes).
  while (el.firstChild) el.removeChild(el.firstChild)

  for (const node of original) {
    if (node.nodeType === Node.TEXT_NODE) {
      appendTokens(el, node.textContent ?? "")
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const child = node as HTMLElement
      const text = child.textContent ?? ""
      while (child.firstChild) child.removeChild(child.firstChild)
      appendTokens(child, text)
      el.appendChild(child)
    } else {
      el.appendChild(node)
    }
  }

  return Array.from(el.querySelectorAll<HTMLSpanElement>(`.${WORD_CLASS}`))
}
