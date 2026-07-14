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
