/**
 * QA council 20260902-134741 — A11Y-12, WCAG 2.2.2 Pause, Stop, Hide.
 *
 * `components/motion-provider.tsx` wraps the app in
 * `<MotionConfig reducedMotion="user">`, which gates every framer-motion
 * animation and cannot touch a Tailwind keyframe. `app/globals.css` carried
 * exactly ONE `@media (prefers-reduced-motion: reduce)` block and it covered
 * only `.kds-press`, so the "Open now" dot on a shop page and the three
 * skeletons on /shop ran an infinite 2s `animate-pulse` identically with the
 * preference on and off (probes/a11y/15, read from the raw records because
 * JSON.stringify(Infinity) is null in the summary line).
 *
 * WHY A SOURCE ASSERTION. jsdom applies no stylesheets and evaluates no media
 * queries, so the only stack-free proof is that the rule EXISTS inside the
 * reduce block; the browser truth (0 running infinite animations under
 * `reduce`, unchanged count under `no-preference`) is probe 15 after the
 * rebuild. The parser below is proven able to see the block by the
 * `.kds-press` control it already contains.
 */
import { readFileSync } from "node:fs"
import { join } from "node:path"

const css = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf8")

/** Every `@media (prefers-reduced-motion: reduce)` block body, braces balanced. */
function reducedMotionBlocks(source: string): string[] {
  const blocks: string[] = []
  const re = /@media\s*\(\s*prefers-reduced-motion\s*:\s*reduce\s*\)\s*\{/g
  let m: RegExpExecArray | null
  while ((m = re.exec(source))) {
    const start = m.index + m[0].length
    let depth = 1
    let i = start
    while (i < source.length && depth > 0) {
      if (source[i] === "{") depth++
      else if (source[i] === "}") depth--
      i++
    }
    blocks.push(source.slice(start, i - 1))
  }
  return blocks
}

const blocks = reducedMotionBlocks(css)
const joined = blocks.join("\n")

describe("globals.css — prefers-reduced-motion covers the Tailwind pulse (A11Y-12)", () => {
  it("has at least one reduce block, and the parser can see the .kds-press rule inside it (control)", () => {
    expect(blocks.length).toBeGreaterThanOrEqual(1)
    expect(joined).toMatch(/\.kds-press\s*\{[^}]*transition-duration\s*:\s*0m?s/)
  })

  it("disables .animate-pulse under reduce", () => {
    expect(joined).toMatch(/\.animate-pulse\s*\{[^}]*animation\s*:\s*none/)
  })

  it("is declared AFTER @tailwind utilities so the equal-specificity override wins the cascade", () => {
    const utilitiesAt = css.indexOf("@tailwind utilities")
    const pulseAt = css.search(/\.animate-pulse\s*\{[^}]*animation\s*:\s*none/)
    expect(utilitiesAt).toBeGreaterThanOrEqual(0)
    expect(pulseAt).toBeGreaterThan(utilitiesAt)
  })
})
