/**
 * Phase 37-16 (D-11/D-12; UI-SPEC § K1 "Flash", § Motion "Flashing"): the New-card ring.
 *
 * A new order on the kitchen board flashes a 4px orange-700 ring, at 1 Hz (500 ms on, 500 ms off), and
 * NOTHING ELSE flashes. That is one flash per second, under WCAG 2.3.1's three-per-second threshold, and
 * only the ring moves: the NEW strip, the allergen banner and the text never do. Under
 * prefers-reduced-motion the ring is static.
 *
 * WHY A SOURCE ASSERTION (same argument as reduced-motion-css.test.ts). jsdom applies no stylesheets and
 * evaluates no media queries, so the stack-free proof is the rule itself: its duration, its iteration,
 * what its keyframes are allowed to touch, and the reduce block that stops it. The plan's break arm is a
 * 200 ms duration, which must turn this suite red.
 */
import { readFileSync } from "node:fs"
import { join } from "node:path"

const css = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf8")

/** Every top-level `@media (prefers-reduced-motion: reduce)` block body, braces balanced. */
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

/** The source with every reduced-motion block removed: what applies with no motion preference. */
function withoutReducedMotion(source: string): string {
  let out = source
  for (const block of reducedMotionBlocks(source)) out = out.replace(block, "")
  return out
}

/** Duration of a CSS time token in milliseconds ("1s" -> 1000, "1000ms" -> 1000). */
function toMs(token: string): number {
  const m = /^([\d.]+)(ms|s)$/.exec(token.trim())
  if (!m) throw new Error(`not a CSS time: ${token}`)
  return m[2] === "s" ? Number(m[1]) * 1000 : Number(m[1])
}

const defaultCss = withoutReducedMotion(css)
const flashRule = /\.kds-new-flash\s*\{([^}]*)\}/.exec(defaultCss)
const keyframes = /@keyframes\s+kds-new-flash\s*\{((?:[^{}]*\{[^}]*\})*)\s*\}/.exec(css)

describe("globals.css — the New-card ring flashes at 1 Hz, ring only (WCAG 2.3.1)", () => {
  it("declares a .kds-new-flash rule and its @keyframes (control: the parser can see both)", () => {
    expect(flashRule).not.toBeNull()
    expect(keyframes).not.toBeNull()
  })

  it("runs kds-new-flash with a cycle of at least 1000 ms, stepped, repeating", () => {
    const body = flashRule![1]
    const animation = /animation\s*:\s*([^;]+);/.exec(body)
    expect(animation).not.toBeNull()
    const parts = animation![1].trim().split(/\s+/)
    expect(parts).toContain("kds-new-flash")
    expect(parts).toContain("infinite")
    expect(animation![1]).toMatch(/steps\(/)
    const times = parts.filter((p) => /^[\d.]+(ms|s)$/.test(p))
    expect(times.length).toBeGreaterThanOrEqual(1)
    // The first time value in the shorthand is the duration: one on/off cycle.
    expect(toMs(times[0])).toBeGreaterThanOrEqual(1000)
  })

  it("its keyframes change the ring (box-shadow) and nothing else", () => {
    const props = Array.from(keyframes![1].matchAll(/([a-z-]+)\s*:/g)).map((m) => m[1])
    expect(props.length).toBeGreaterThan(0)
    expect(new Set(props)).toEqual(new Set(["box-shadow"]))
  })

  it("is the only infinite animation the kitchen board defines (nothing else flashes)", () => {
    const kdsInfinite = Array.from(defaultCss.matchAll(/\.(kds-[a-z-]+)\s*\{[^}]*animation\s*:[^;]*infinite/g)).map((m) => m[1])
    expect(kdsInfinite).toEqual(["kds-new-flash"])
  })

  it("stops under prefers-reduced-motion and leaves a static ring", () => {
    const joined = reducedMotionBlocks(css).join("\n")
    const reduced = /\.kds-new-flash\s*\{([^}]*)\}/.exec(joined)
    expect(reduced).not.toBeNull()
    expect(reduced![1]).toMatch(/animation\s*:\s*none/)
    expect(reduced![1]).toMatch(/box-shadow\s*:\s*0 0 0 4px/)
  })
})
