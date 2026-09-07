/**
 * @jest-environment node
 *
 * QA council 20260902-134741 — A11Y-6 (WCAG 1.4.3), plan.md A26: NO token
 * change; `--primary`/orange-700 (#451) is not touched and not re-litigated.
 * The nodes axe reported are call-site class strings, and each is replaced
 * by the next shade that clears AA on the surface it sits on:
 *   - white text on a solid fill (status pills, action buttons, the storefront
 *     discount badge) needs a -600/-700 fill, or dark text on the amber;
 *   - `text-slate-400` (2.56:1 on white) and the `text-slate-500` override on
 *     the secondary badge (4.34:1) go to `text-slate-600`, which is 7.58 on
 *     white AND 7.05 on the brand cream — `slate-500` is 4.43 on cream and
 *     would red `contrast-literals.test.ts` the day its SCAN_ROOTS widen.
 *
 * Same method as `dashboard-pending-badge-contrast.test.ts`: the ratio is
 * RECOMPUTED from `tailwindcss/colors`, never copied, and the shipped source
 * is read as text. It is its own file for the same reason that one is —
 * `contrast-literals.test.ts` records `app/dashboard/**` as out of scope by
 * decision (D-09), and widening it is the lead's gate-widening step, not this
 * lane's.
 *
 * The colour-map assertions are GENERIC on purpose: every `bgColor:` /
 * `color: "bg-…"` entry in the orders status + transition maps and the finance
 * VAT map is checked, not just the two shades axe happened to see with the
 * seed's order statuses. A pill one status-change away from failing is the
 * same defect.
 */
import fs from "fs"
import path from "path"
import twColors from "tailwindcss/colors"

const FRONTEND_ROOT = path.resolve(__dirname, "..")
const WHITE = "#ffffff"
const CREAM = "#FBF6F0"
const SECONDARY = "#f1f5f9" // hsl(210 40% 96.1%) — globals.css --secondary
const AMBER_INK = "#3A2400" // tailwind.config.ts "amber-ink"
const AA_NORMAL = 4.5

type Rgb = [number, number, number]
const hexToRgb = (hex: string): Rgb => {
  const h = hex.replace("#", "")
  return [0, 1, 2].map((i) => parseInt(h.substr(i * 2, 2), 16)) as Rgb
}
const luminance = ([r, g, b]: Rgb) => {
  const [R, G, B] = [r, g, b]
    .map((v) => v / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * R + 0.7152 * G + 0.0722 * B
}
const ratio = (a: string, b: string) => {
  const [hi, lo] = [luminance(hexToRgb(a)), luminance(hexToRgb(b))].sort((x, y) => y - x)
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
}
const tw = twColors as unknown as Record<string, Record<string, string>>
const tokenHex = (token: string): string => {
  const m = /^bg-([a-z]+)-(\d{2,3})$/.exec(token)
  if (!m) throw new Error(`not a numeric bg token: ${token}`)
  const hex = tw[m[1]]?.[m[2]]
  if (!hex) throw new Error(`unknown tailwind colour: ${token}`)
  return hex
}
const read = (file: string) => fs.readFileSync(path.join(FRONTEND_ROOT, file), "utf8")

describe("the ratio math (controls — if these move, the palette moved)", () => {
  it("the shades being replaced genuinely fail AA for white text", () => {
    for (const t of ["bg-amber-500", "bg-blue-500", "bg-emerald-600", "bg-amber-600", "bg-green-500", "bg-red-500", "bg-orange-500", "bg-green-600"]) {
      expect(ratio(tokenHex(t), WHITE)).toBeLessThan(AA_NORMAL)
    }
  })
  it("slate-400 fails and slate-600 clears AA on white, cream and the secondary badge", () => {
    expect(ratio(tw.slate["400"], WHITE)).toBeLessThan(AA_NORMAL)
    expect(ratio(tw.slate["500"], CREAM)).toBeLessThan(AA_NORMAL)
    expect(ratio(tw.slate["500"], SECONDARY)).toBeLessThan(AA_NORMAL)
    for (const bg of [WHITE, CREAM, SECONDARY]) expect(ratio(tw.slate["600"], bg)).toBeGreaterThanOrEqual(AA_NORMAL)
  })
  it("amber-ink on amber-500 clears AA (the dark-text choice for the discount badge, A26)", () => {
    expect(ratio(AMBER_INK, tw.amber["500"])).toBeGreaterThanOrEqual(AA_NORMAL)
  })
})

describe("white-text fills in the colour maps all clear AA", () => {
  it("app/dashboard/orders/page.tsx — every statusConfig bgColor", () => {
    const src = read("app/dashboard/orders/page.tsx")
    const tokens = [...src.matchAll(/bgColor:\s*"(bg-[a-z]+-\d{3})"/g)].map((m) => m[1])
    expect(tokens.length).toBeGreaterThanOrEqual(8) // instrument sees the map
    for (const t of tokens) expect({ token: t, ratio: ratio(tokenHex(t), WHITE) }).toEqual(expect.objectContaining({ ratio: expect.any(Number) }))
    const failing = tokens.filter((t) => ratio(tokenHex(t), WHITE) < AA_NORMAL)
    expect(failing).toEqual([])
  })

  it("app/dashboard/orders/page.tsx — every transition button fill (base shade)", () => {
    const src = read("app/dashboard/orders/page.tsx")
    const tokens = [...src.matchAll(/color:\s*"(bg-[a-z]+-\d{3}) hover:bg-/g)].map((m) => m[1])
    expect(tokens.length).toBeGreaterThanOrEqual(6)
    const failing = tokens.filter((t) => ratio(tokenHex(t), WHITE) < AA_NORMAL)
    expect(failing).toEqual([])
  })

  it("app/dashboard/finance/page.tsx — every vatRateConfig fill", () => {
    const src = read("app/dashboard/finance/page.tsx")
    const tokens = [...src.matchAll(/color:\s*"(bg-[a-z]+-\d{3})"/g)].map((m) => m[1])
    expect(tokens.length).toBeGreaterThanOrEqual(4)
    const failing = tokens.filter((t) => ratio(tokenHex(t), WHITE) < AA_NORMAL)
    expect(failing).toEqual([])
  })
})

describe("the individual text nodes axe named", () => {
  it("storefront discount badge: bg-amber-500 carries dark text, not white", () => {
    const src = read("app/shop/[slug]/shop-detail-client.tsx")
    expect(src).not.toMatch(/bg-amber-500 px-2 py-0\.5 text-xs font-bold text-white/)
    expect(src.match(/bg-amber-500 px-2 py-0\.5 text-xs font-bold text-amber-ink/g)?.length).toBe(2)
  })

  it("orders: the 'No actions' cell", () => {
    const src = read("app/dashboard/orders/page.tsx")
    expect(src).not.toMatch(/text-slate-400">\s*No actions/)
    expect(src).toMatch(/text-slate-600">\s*No actions/)
  })

  it("marketing: the relative-date lines and the 'Always' / 'No end' placeholders", () => {
    const src = read("app/dashboard/marketing/page.tsx")
    expect(src).not.toMatch(/text-xs text-slate-400">\s*\{formatDateRelative/)
    expect(src.match(/text-xs text-slate-600">\s*\{formatDateRelative/g)?.length).toBe(4)
    expect(src).not.toMatch(/text-slate-400">(Always|No end)</)
    expect(src).toMatch(/text-slate-600">Always</)
    expect(src).toMatch(/text-slate-600">No end</)
  })

  it("onboarding: 'Required'/'Optional', the last-checked line and the company-number hint", () => {
    const src = read("app/dashboard/onboarding/page.tsx")
    expect(src).not.toMatch(/text-slate-400">\{gate\.mandatory/)
    expect(src).not.toMatch(/text-slate-400">\{checkedAtLabel/)
    expect(src).not.toMatch(/text-xs text-slate-400">\s*Companies House number/)
    expect(src).toMatch(/text-slate-600">\{gate\.mandatory/)
    expect(src).toMatch(/text-slate-600">\{checkedAtLabel/)
  })

  it("shops: the Draft badge no longer overrides --secondary-foreground with slate-500", () => {
    const src = read("app/dashboard/shops/page.tsx")
    expect(src).not.toMatch(/variant="secondary" className="text-slate-500">Draft/)
    expect(src).toMatch(/variant="secondary" className="text-slate-600">Draft/)
  })
})
