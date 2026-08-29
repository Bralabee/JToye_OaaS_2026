/**
 * @jest-environment node
 *
 * QA-council F3 (A11Y-1 Medium, A11Y-10 Medium): the vendor dashboard's PENDING
 * order-status badge is white text on `bg-yellow-500`, computed below at 1.92:1
 * against WCAG 2.1 AA's 4.5:1 floor for normal text — a fail large enough that
 * moving the ramp ONE step (to yellow-600, 2.94:1) still fails; the fix has to
 * go to yellow-700 (4.92:1) to clear AA.
 *
 * This mirrors `contrast-literals.test.ts`'s method (recompute the ratio FROM
 * the Tailwind palette, never a copied number) but is deliberately its own
 * file rather than an addition to that one's `SCAN_ROOTS`: that file's header
 * records `app/dashboard/**` as OUT OF SCOPE on purpose (an authenticated
 * operator tool, not a published consumer surface, per the D-09 conformance
 * boundary) — folding the dashboard in there would silently widen a
 * conformance statement this phase was never asked to make. This file makes a
 * narrower, explicit claim: the four PENDING badge sites this fix touches, and
 * only those.
 */
import fs from "fs"
import path from "path"
import twColors from "tailwindcss/colors"

const FRONTEND_ROOT = path.resolve(__dirname, "..")
const WHITE = "#ffffff"
const AA_NORMAL = 4.5

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

const yellow = twColors.yellow as Record<string, string>

describe("PENDING badge contrast — the ratio math (F3 / A11Y-1)", () => {
  it("VOID-style positive control: yellow-500 white text genuinely fails AA", () => {
    // If this ever passes, the palette itself changed and every claim below
    // is measuring the wrong colour.
    expect(yellow["500"]).toBe("#eab308")
    expect(ratio(hexToRgb(yellow["500"]), hexToRgb(WHITE))).toBeLessThan(AA_NORMAL)
  })

  it("yellow-600 (one step) is STILL below AA — the fix must go to -700, not -600", () => {
    expect(ratio(hexToRgb(yellow["600"]), hexToRgb(WHITE))).toBeLessThan(AA_NORMAL)
  })

  it("yellow-700 clears AA for white text — the value every site below moves to", () => {
    expect(yellow["700"]).toBe("#a16207")
    expect(ratio(hexToRgb(yellow["700"]), hexToRgb(WHITE))).toBeGreaterThanOrEqual(AA_NORMAL)
  })
})

/**
 * The four duplicated PENDING status maps named in the finding. Each file is
 * read as text (not imported) so this proves the SHIPPED SOURCE, the same
 * proof shape `contrast-literals.test.ts` uses for its own delivery-threshold
 * assertion.
 */
const PENDING_SITES = [
  "app/dashboard/page.tsx",
  "app/dashboard/finance/page.tsx",
  "app/dashboard/orders/page.tsx",
  "components/dashboard/orders/OrderDetailPanel.tsx",
]

describe("PENDING badge contrast — the shipped source (F3 / A11Y-1)", () => {
  it("the scan actually reads the four named files — the instrument is not blind", () => {
    for (const file of PENDING_SITES) {
      expect(fs.existsSync(path.join(FRONTEND_ROOT, file))).toBe(true)
    }
  })

  it("no PENDING site still ships the failing bg-yellow-500 token", () => {
    for (const file of PENDING_SITES) {
      const source = fs.readFileSync(path.join(FRONTEND_ROOT, file), "utf8")
      expect(source).not.toMatch(/bg-yellow-500/)
    }
  })

  it("every PENDING site ships the AA-passing bg-yellow-700 token instead", () => {
    for (const file of PENDING_SITES) {
      const source = fs.readFileSync(path.join(FRONTEND_ROOT, file), "utf8")
      expect(source).toMatch(/bg-yellow-700/)
    }
  })
})
