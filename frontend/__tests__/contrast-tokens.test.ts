/**
 * @jest-environment node
 *
 * Colour-token contrast contract (#451 / QA council disc-20260802 F-M4-A11Y).
 *
 * The 220 `color-contrast` axe violations in that finding were not 220 bugs —
 * they were ~15 token pairs rendered many times. This gate recomputes the WCAG
 * 2.1 ratio for each pair FROM `app/globals.css`, so reverting a token to its
 * pre-fix value fails the build rather than waiting for the next audit.
 *
 * Two things it deliberately checks that a single-background gate would miss:
 *
 *  1. Every "text on a light surface" token is measured against BOTH surfaces
 *     this product ships — #ffffff and brand cream #FBF6F0. slate-500 is 4.76
 *     on white and 4.43 on cream, so a white-only gate would have passed the
 *     exact token that produced ~100 of the violations.
 *  2. Dark mode is measured too: --primary-foreground is near-white in both
 *     themes, so the pairing that has to clear 4.5:1 is theme-independent.
 */
import fs from "fs"
import path from "path"

const FRONTEND_ROOT = path.resolve(__dirname, "..")
const CSS = fs.readFileSync(path.join(FRONTEND_ROOT, "app", "globals.css"), "utf8")

/** Brand cream, mirrored from tailwind.config.ts `colors.cream.DEFAULT`. */
const CREAM = "#FBF6F0"
const WHITE = "#ffffff"

// --- colour maths -----------------------------------------------------------

function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const c = (1 - Math.abs(2 * l - 1)) * s
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = l - c / 2
  const [r, g, b] =
    h < 60 ? [c, x, 0] :
    h < 120 ? [x, c, 0] :
    h < 180 ? [0, c, x] :
    h < 240 ? [0, x, c] :
    h < 300 ? [x, 0, c] : [c, 0, x]
  return [(r + m) * 255, (g + m) * 255, (b + m) * 255]
}

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace("#", "")
  return [0, 1, 2].map((i) => parseInt(h.substr(i * 2, 2), 16)) as [number, number, number]
}

function luminance([r, g, b]: [number, number, number]): number {
  const [R, G, B] = [r, g, b]
    .map((v) => v / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * R + 0.7152 * G + 0.0722 * B
}

function ratio(a: [number, number, number], b: [number, number, number]): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
}

// --- token extraction -------------------------------------------------------

/**
 * Read a token out of the `:root` or `.dark` block. Scoped rather than global
 * because most tokens are declared twice with different values — a global
 * first-match read would silently measure the light value in both directions.
 */
function block(scope: ":root" | ".dark"): string {
  const start = CSS.indexOf(`${scope} {`)
  if (start === -1) throw new Error(`VOID: no ${scope} block in globals.css`)
  const end = CSS.indexOf("\n  }", start)
  if (end === -1) throw new Error(`VOID: unterminated ${scope} block`)
  return CSS.slice(start, end)
}

function token(name: string, scope: ":root" | ".dark" = ":root"): [number, number, number] {
  const m = block(scope).match(new RegExp(`--${name}:\\s*([^;]+);`))
  if (!m) throw new Error(`VOID: token --${name} not found in ${scope}`)
  const raw = m[1].trim()
  if (raw.startsWith("#")) return hexToRgb(raw)
  const parts = raw.match(/([\d.]+)\s+([\d.]+)%\s+([\d.]+)%/)
  if (!parts) throw new Error(`VOID: token --${name} is not an HSL triple: "${raw}"`)
  return hslToRgb(Number(parts[1]), Number(parts[2]) / 100, Number(parts[3]) / 100)
}

// --- the contract -----------------------------------------------------------

const AA_NORMAL = 4.5

describe("colour-token contrast contract (#451)", () => {
  it("extracts tokens at all — the instrument can see the file", () => {
    // Guards the vacuous case: if the regexes stopped matching, every ratio
    // below would throw rather than silently pass, but this states it outright.
    expect(CSS.length).toBeGreaterThan(500)
    expect(ratio(token("primary"), token("primary-foreground"))).toBeGreaterThan(1)
  })

  it("primary carries its own foreground at AA (was orange-600, 3.56:1)", () => {
    expect(ratio(token("primary"), token("primary-foreground"))).toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("primary carries its foreground at AA in DARK mode too", () => {
    expect(ratio(token("primary", ".dark"), token("primary-foreground", ".dark")))
      .toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("destructive carries its own foreground at AA (was red-500, 3.60:1)", () => {
    expect(ratio(token("destructive"), token("destructive-foreground"))).toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("body foreground clears AA on both light surfaces", () => {
    expect(ratio(token("foreground"), hexToRgb(WHITE))).toBeGreaterThanOrEqual(AA_NORMAL)
    expect(ratio(token("foreground"), hexToRgb(CREAM))).toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("muted-foreground clears AA on CREAM, not just white (was slate-500, 4.43:1 on cream)", () => {
    expect(ratio(token("muted-foreground"), hexToRgb(WHITE))).toBeGreaterThanOrEqual(AA_NORMAL)
    expect(ratio(token("muted-foreground"), hexToRgb(CREAM))).toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("the trust accent clears AA as text on both light surfaces (was emerald-600, 3.77:1)", () => {
    expect(ratio(token("trust"), hexToRgb(WHITE))).toBeGreaterThanOrEqual(AA_NORMAL)
    expect(ratio(token("trust"), hexToRgb(CREAM))).toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("the graphics-only accents stay documented as such rather than quietly used for text", () => {
    // --ember-bright (2.80:1) and --trust-bright (2.28:1) CANNOT reach AA as
    // text and are not meant to; the contract is that globals.css says so, so
    // the next reader does not mistake them for text colours.
    expect(ratio(token("ember-bright"), hexToRgb(WHITE))).toBeLessThan(AA_NORMAL)
    expect(CSS).toMatch(/GRAPHICS ONLY/)
  })
})
