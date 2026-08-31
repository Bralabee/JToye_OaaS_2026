/**
 * @jest-environment node
 *
 * Tailwind-LITERAL contrast contract (31-02 · LGL-02).
 *
 * WHY A SECOND CONTRAST GATE EXISTS
 *
 * `contrast-tokens.test.ts` is 8/8 green and recomputes every ratio from
 * `app/globals.css`. It was green on the day `text-emerald-600` (#059669, 3.77
 * on white / 3.51 on cream) was failing AA on four live nodes of `/shop` and
 * `/shop/[slug]`. That is not a contradiction — it is the shape of the gap. The
 * token test reads CSS custom properties and is STRUCTURALLY INCAPABLE of
 * seeing a utility class. `--trust` had already been moved emerald-600 -> 700
 * for exactly this reason (globals.css:26) and the literals did not move with
 * it, because nothing was watching them.
 *
 * This file watches them. It scans the shipped sources, resolves each
 * `text-<ramp>-<step>` utility to a hex FROM THE TAILWIND PALETTE, and
 * recomputes the WCAG 2.1 ratio. Nothing is hard-coded: changing
 * `text-amber-800` to `text-amber-400` in a scanned component changes the
 * computed number and reds this file.
 *
 * IT LIVES IN `__tests__/` ON PURPOSE — outside `app/` and `components/`, so
 * its own pattern literals are never scanned by its own grep. That placement is
 * the entire reason `palette-discipline.test.ts` works, and it is repeated here
 * rather than re-derived.
 *
 * TWO BACKGROUNDS, NOT ONE. Every pairing is measured against BOTH surfaces
 * this product ships: `#ffffff` and brand cream `#FBF6F0`. slate-500 is 4.76 on
 * white and 4.43 on cream; a white-only gate passes the exact token that
 * produced roughly 100 of the QA council's 220 nodes.
 *
 * WHAT THIS GATE DOES NOT CLAIM. `text-white`/`text-black` carry no numeric
 * step and are not scanned: the class alone does not state a background, and a
 * guess would be worse than an absence. And see `UNASSERTED_SITES` below — it
 * is a debt ledger, not a certificate.
 */
import { execFileSync } from "child_process"
import fs from "fs"
import path from "path"
import twColors from "tailwindcss/colors"

const FRONTEND_ROOT = path.resolve(__dirname, "..")

/** Brand cream, mirrored from tailwind.config.ts `colors.cream.DEFAULT`. */
const CREAM = "#FBF6F0"
const WHITE = "#ffffff"

/** WCAG 2.1 AA for normal-size text. Same constant `contrast-tokens.test.ts` uses. */
const AA_NORMAL = 4.5

/**
 * The D-09 declared consumer surfaces — the ones the phase's conformance
 * statement will actually cover. `app/dashboard/**` is deliberately outside it
 * (the same boundary `eslint.config.mjs` records for the jsx-a11y layer): the
 * dashboard is an authenticated operator tool, not a published consumer
 * surface, and folding it in here would triple the ledger below without adding
 * a single claim anyone makes in public.
 */
const SCAN_ROOTS = [
  "app/page.tsx",
  "app/shop",
  "app/auth/signin",
  "components/public",
  "components/storefront",
  "components/marketing",
]

// --- colour maths (lifted verbatim from contrast-tokens.test.ts:45-60) -------

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

// --- palette resolution -----------------------------------------------------

/**
 * The project's own ramps, read out of `tailwind.config.ts` AS TEXT rather than
 * transcribed here. A transcribed hex is a second source of truth that cannot
 * drift loudly: rebrand the config and a copy in this file keeps asserting the
 * old colour, green and wrong.
 */
const TAILWIND_CONFIG = fs.readFileSync(
  path.join(FRONTEND_ROOT, "tailwind.config.ts"),
  "utf8"
)

function customRamps(): Record<string, Record<string, string>> {
  const ramps: Record<string, Record<string, string>> = {}
  const blocks = TAILWIND_CONFIG.matchAll(
    /^\s*"?([a-z][a-z-]*)"?:\s*\{([^}]*#[0-9a-fA-F]{3,8}[^}]*)\},/gm
  )
  for (const block of blocks) {
    const shades: Record<string, string> = {}
    for (const shade of block[2].matchAll(/"?([A-Za-z0-9]+)"?:\s*"(#[0-9a-fA-F]{3,8})"/g)) {
      shades[shade[1]] = shade[2]
    }
    ramps[block[1]] = shades
  }
  return ramps
}

const CUSTOM = customRamps()
const STOCK = twColors as unknown as Record<string, Record<string, string>>

/**
 * VOID ARM. An unresolvable ramp/step THROWS rather than being skipped —
 * silently skipping is exactly how the failing one gets skipped. Mirrors the
 * `VOID:` throws at contrast-tokens.test.ts:69-85.
 */
function resolve(cls: string): string {
  const m = cls.match(/^text-([a-z]+)-([0-9]{2,3})$/)
  if (!m) throw new Error(`VOID: "${cls}" is not a text-<ramp>-<step> utility`)
  const [, ramp, step] = m
  const hex = CUSTOM[ramp]?.[step] ?? STOCK[ramp]?.[step]
  if (typeof hex !== "string" || !hex.startsWith("#")) {
    throw new Error(
      `VOID: cannot resolve "${cls}" to a hex — ramp "${ramp}" step "${step}" is in ` +
        `neither tailwind.config.ts nor the stock palette. An unresolved colour ` +
        `must not be treated as compliant.`
    )
  }
  return hex
}

// --- the scan ---------------------------------------------------------------

/**
 * Run grep from the frontend root. grep exits 1 on zero matches — a count of 0,
 * not a failure — so that one status is translated and everything else rethrown.
 * Same helper shape as `palette-discipline.test.ts:27-39`.
 */
function grep(args: string[]): string {
  try {
    return execFileSync("grep", args, { cwd: FRONTEND_ROOT, encoding: "utf8" })
  } catch (err) {
    const e = err as { status?: number }
    if (e && e.status === 1) return ""
    throw err
  }
}

interface Site {
  /** Path relative to the frontend root. */
  file: string
  /** e.g. `text-emerald-700`. */
  cls: string
  /** Every line the pair occurs on, so a failure names where to look. */
  lines: string[]
}

/** Every (file, utility) pair across SCAN_ROOTS, keyed `file::class`. */
function scan(): Map<string, Site> {
  const raw = grep([
    "-rnoE",
    "text-[a-z]+-[0-9]{2,3}",
    "--include=*.tsx",
    "--include=*.ts",
    // Co-located test files are NOT shipped surfaces: an assertion like
    // `toHaveClass("text-amber-300")` names a colour without rendering it, and
    // ledgering test files would dilute the debt ledger with non-debt. First
    // hit: floating-cart-bar.test.tsx asserting the (ledgered) amber shortfall
    // label on the oxblood cart bar. The exclusion is by filename contract
    // (jest's testMatch), so a *rendered* component can never fall under it.
    "--exclude=*.test.tsx",
    "--exclude=*.test.ts",
    ...SCAN_ROOTS,
  ])
  const sites = new Map<string, Site>()
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue
    const m = line.match(/^([^:]+):(\d+):(text-[a-z]+-[0-9]{2,3})$/)
    if (!m) throw new Error(`VOID: unparseable grep line "${line}"`)
    const [, file, lineNo, cls] = m
    const key = `${file}::${cls}`
    if (!sites.has(key)) sites.set(key, { file, cls, lines: [] })
    sites.get(key)!.lines.push(lineNo)
  }
  return sites
}

const SITES = scan()

function describeSite(key: string): string {
  const site = SITES.get(key)!
  const hex = resolve(site.cls)
  const rgb = hexToRgb(hex)
  return (
    `${site.file}:${site.lines.join(",")} uses ${site.cls} (${hex}) — ` +
    `${ratio(rgb, hexToRgb(WHITE))} on white, ${ratio(rgb, hexToRgb(CREAM))} on cream ` +
    `(AA needs ${AA_NORMAL})`
  )
}

// --- the debt ledger --------------------------------------------------------

/**
 * A DEBT LEDGER, NOT A CERTIFICATE. Read this before adding to it.
 *
 * Every entry is a (file, utility) pair that was already below AA on both light
 * surfaces the day this gate was written. They were seeded BY MEASUREMENT — the
 * 55 pairs the scan reported — and were NOT individually inspected. Some are
 * certainly fine (a `text-slate-300` heading inside an oxblood marketing block
 * is on a dark surface; `text-amber-500` is mostly a star/flame ICON tint).
 * Some are certainly not: `text-emerald-600` here is the SAME 3.77 pairing this
 * plan just closed on `/shop` and `/shop/[slug]`, still open on three other
 * surfaces, and `text-red-600` misses the cream arm by 0.01.
 *
 * So the ledger's job is not to bless these. It is to (a) stop the set GROWING
 * silently, and (b) hand 31-13 an enumerated, ratio-annotated list of what the
 * conformance statement must either fix or declare. Two assertions below keep
 * it from rotting into a hiding place: a registered pair must still be BELOW AA
 * (so a fix cannot be quietly absorbed), and it must still EXIST in the tree (so
 * removing a site forces the ledger line to go with it — which is what makes
 * reverting `text-emerald-700` back to `-600` fail this file).
 */
const UNASSERTED_SITES: ReadonlySet<string> = new Set([
  "app/auth/signin/page.tsx::text-slate-500", // 4.76 on white, 4.43 on cream
  "app/page.tsx::text-emerald-600", // 3.77 on white, 3.51 on cream
  "app/shop/[slug]/cart/page.tsx::text-red-400", // 2.77 on white, 2.57 on cream
  "app/shop/[slug]/cart/page.tsx::text-red-500", // 3.76 on white, 3.5 on cream
  "app/shop/[slug]/cart/page.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  // text-slate-400 (2.56 on white, 2.39 on cream) fixed -> text-slate-600 (F1 / A11Y-11):
  // both the "Clear all" link and the category label are gone from this file entirely.
  "app/shop/[slug]/checkout/page.tsx::text-emerald-600", // 3.77 on white, 3.51 on cream
  "app/shop/[slug]/checkout/page.tsx::text-red-600", // 4.83 on white, 4.49 on cream
  "app/shop/[slug]/checkout/page.tsx::text-slate-200", // 1.23 on white, 1.15 on cream
  "app/shop/[slug]/checkout/page.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "app/shop/[slug]/not-found.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-emerald-600", // 3.77 on white, 3.51 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-red-500", // 3.76 on white, 3.5 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-red-600", // 4.83 on white, 4.49 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "app/shop/[slug]/orders/[orderNumber]/page.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-amber-300", // 1.44 on white, 1.34 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-amber-400", // 1.67 on white, 1.55 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-blue-500", // 3.68 on white, 3.42 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-emerald-300", // 1.52 on white, 1.42 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "app/shop/[slug]/shop-detail-client.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "app/shop/auth/callback/page.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "app/shop/auth/callback/page.tsx::text-red-600", // 4.83 on white, 4.49 on cream
  "app/shop/orders/orders-client.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "app/shop/orders/orders-client.tsx::text-blue-500", // 3.68 on white, 3.42 on cream
  "app/shop/orders/orders-client.tsx::text-emerald-500", // 2.54 on white, 2.36 on cream
  "app/shop/orders/orders-client.tsx::text-red-500", // 3.76 on white, 3.5 on cream
  "app/shop/orders/orders-client.tsx::text-slate-200", // 1.23 on white, 1.15 on cream
  "app/shop/orders/orders-client.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "app/shop/orders/orders-client.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "app/shop/shop-discovery-client.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "app/shop/shop-discovery-client.tsx::text-cream-100", // 1.18 on white, 1.1 on cream
  "app/shop/shop-discovery-client.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "app/shop/shop-discovery-client.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "components/marketing/business-model-guide.tsx::text-amber-300", // 1.44 on white, 1.34 on cream
  "components/marketing/business-model-guide.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "components/marketing/business-model-guide.tsx::text-slate-50", // 1.05 on white, 1.03 on cream
  "components/marketing/competitive-teardown.tsx::text-amber-300", // 1.44 on white, 1.34 on cream
  "components/marketing/competitive-teardown.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "components/marketing/competitive-teardown.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "components/marketing/competitive-teardown.tsx::text-slate-50", // 1.05 on white, 1.03 on cream
  "components/marketing/hero-search.tsx::text-slate-500", // 4.76 on white, 4.43 on cream
  "components/marketing/operator-pitch.tsx::text-amber-300", // 1.44 on white, 1.34 on cream
  "components/marketing/operator-pitch.tsx::text-emerald-600", // 3.77 on white, 3.51 on cream
  "components/public/public-header.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "components/storefront/cart-drawer.tsx::text-red-400", // 2.77 on white, 2.57 on cream
  "components/storefront/cart-drawer.tsx::text-red-500", // 3.76 on white, 3.5 on cream
  "components/storefront/cart-drawer.tsx::text-slate-300", // 1.48 on white, 1.38 on cream
  "components/storefront/cart-drawer.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "components/storefront/customer-signin-prompt.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
  "components/storefront/product-detail-modal.tsx::text-amber-500", // 2.15 on white, 2 on cream
  "components/storefront/storefront-nav.tsx::text-slate-400", // 2.56 on white, 2.39 on cream
])

// --- the contract -----------------------------------------------------------

describe("Tailwind-literal contrast contract (31-02 · LGL-02)", () => {
  /**
   * POSITIVE CONTROL. Without it a clean sweep is a statement about the grep,
   * not about the code: narrow the pattern to something that matches nothing
   * and every assertion below passes vacuously. Same shape as
   * palette-discipline.test.ts's `toBeGreaterThanOrEqual(3)` closer.
   */
  it("the scan actually finds colour utilities — the instrument is not blind", () => {
    expect(SITES.size).toBeGreaterThanOrEqual(150) // 169 when written
    const files = new Set([...SITES.values()].map((s) => s.file))
    expect(files.size).toBeGreaterThanOrEqual(15) // 30 when written
    // And it reaches the specific file this plan changed, with the fixed value.
    expect(SITES.has("app/shop/shop-discovery-client.tsx::text-emerald-700")).toBe(true)
  })

  /**
   * VOID ARM, demonstrated rather than asserted-about. An unresolvable colour
   * must stop the run, not be skipped — a skipped colour is indistinguishable
   * from a compliant one.
   */
  it("VOIDs on a colour it cannot resolve instead of skipping it", () => {
    expect(() => resolve("text-notaramp-600")).toThrow(/^VOID: cannot resolve/)
    expect(() => resolve("text-emerald-999")).toThrow(/^VOID: cannot resolve/)
    expect(() => resolve("text-white")).toThrow(/^VOID: "text-white" is not/)
    // …and the same function resolves a real one, so the throws above are not
    // simply "this function always throws".
    expect(resolve("text-emerald-700")).toBe("#047857")
  })

  it("recomputes from the palette rather than trusting a copied number", () => {
    // If these two drift, every ratio in this file is measuring the wrong colour.
    expect(resolve("text-emerald-600")).toBe("#059669")
    expect(ratio(hexToRgb(resolve("text-emerald-600")), hexToRgb(WHITE))).toBeLessThan(AA_NORMAL)
    expect(ratio(hexToRgb(resolve("text-emerald-700")), hexToRgb(WHITE)))
      .toBeGreaterThanOrEqual(AA_NORMAL)
    expect(ratio(hexToRgb(resolve("text-emerald-700")), hexToRgb(CREAM)))
      .toBeGreaterThanOrEqual(AA_NORMAL)
  })

  it("every unregistered text colour clears AA on BOTH shipped light surfaces", () => {
    const failures: string[] = []
    for (const key of SITES.keys()) {
      if (UNASSERTED_SITES.has(key)) continue
      const rgb = hexToRgb(resolve(SITES.get(key)!.cls))
      if (
        ratio(rgb, hexToRgb(WHITE)) < AA_NORMAL ||
        ratio(rgb, hexToRgb(CREAM)) < AA_NORMAL
      ) {
        failures.push(describeSite(key))
      }
    }
    expect(failures).toEqual([])
  })

  it("holds no ledger entry that has become compliant — the ledger cannot absorb a fix", () => {
    const dead: string[] = []
    for (const key of UNASSERTED_SITES) {
      const cls = key.split("::")[1]
      const rgb = hexToRgb(resolve(cls))
      if (
        ratio(rgb, hexToRgb(WHITE)) >= AA_NORMAL &&
        ratio(rgb, hexToRgb(CREAM)) >= AA_NORMAL
      ) {
        dead.push(`${key} now clears AA — delete this ledger line`)
      }
    }
    expect(dead).toEqual([])
  })

  it("holds no ledger entry for a site that no longer exists — a fixed site loses its exemption", () => {
    // This is what makes reverting text-emerald-700 to -600 on /shop RED: the
    // pair is gone from the ledger, so its reappearance is unregistered.
    const stale = [...UNASSERTED_SITES].filter((key) => !SITES.has(key))
    expect(stale).toEqual([])
  })

  it("the delivery-threshold string is emerald-700 on both shop surfaces (F-A)", () => {
    for (const file of [
      "app/shop/shop-discovery-client.tsx",
      "app/shop/[slug]/shop-detail-client.tsx",
    ]) {
      const source = fs.readFileSync(path.join(FRONTEND_ROOT, file), "utf8")
      expect(source.match(/text-emerald-600/g) ?? []).toEqual([])
      expect((source.match(/text-emerald-700/g) ?? []).length).toBeGreaterThanOrEqual(2)
    }
  })
})

// --- landmark names (F-B) ---------------------------------------------------

/**
 * Comments out, THEN match. Without this the gate fires on its own
 * documentation: the note above `storefront-nav.tsx`'s header nav explains the
 * `landmark-unique` rule and necessarily spells the element it is about, and a
 * bare mention in prose reads to a regex as a nav with no accessible name. The
 * lazy alternative — requiring whitespace after the tag name — would silence
 * that false positive by going blind to the exact defect being hunted.
 *
 * `//` is stripped only at the start of a line, so a `https://` inside an
 * attribute survives.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "")
}

/** Every `<nav …>` opening tag in a source string, with its aria-label if any. */
function navTagsIn(source: string): { tag: string; label: string | null }[] {
  const tags = stripComments(source).match(/<nav\b[^>]*>/g) ?? []
  return tags.map((tag) => {
    const m = tag.match(/aria-label="([^"]*)"/)
    return { tag, label: m ? m[1] : null }
  })
}

function navLandmarks(file: string): { tag: string; label: string | null }[] {
  return navTagsIn(fs.readFileSync(path.join(FRONTEND_ROOT, file), "utf8"))
}

describe("storefront landmark names (31-02 · LGL-02 · F-B)", () => {
  const FILES = [
    "components/storefront/storefront-nav.tsx",
    "app/shop/[slug]/shop-detail-client.tsx",
  ]

  it("finds the nav landmarks at all — the instrument is not blind", () => {
    const total = FILES.flatMap(navLandmarks)
    expect(total.length).toBeGreaterThanOrEqual(3) // 2 in the nav file, 1 strip
  })

  /**
   * CONTROL for the extractor itself. The comment-stripping above exists to
   * stop a false positive, and a filter added to stop a false positive is the
   * classic way to acquire a false NEGATIVE. This proves, on synthetic input,
   * that the extractor still SEES an attribute-less nav and still reports a
   * missing name — and that stripping removes only prose.
   */
  it("the extractor can still see an unnamed nav — the comment filter did not blind it", () => {
    expect(navTagsIn("<nav>")).toEqual([{ tag: "<nav>", label: null }])
    expect(navTagsIn('<nav className="x">')[0].label).toBeNull()
    expect(navTagsIn('<nav aria-label="Primary">')[0].label).toBe("Primary")
    // prose mentioning the element is not a landmark
    expect(navTagsIn("{/* two <nav> landmarks */}")).toEqual([])
    expect(navTagsIn("  // a <nav> in a line comment")).toEqual([])
  })

  it("gives every storefront nav an accessible name", () => {
    for (const file of FILES) {
      for (const nav of navLandmarks(file)) {
        expect(`${file} ${nav.label ?? "<unnamed>"}`).not.toMatch(/<unnamed>/)
        expect((nav.label ?? "").trim().length).toBeGreaterThan(0)
      }
    }
  })

  it("gives them DIFFERENT names — landmark-unique fires on ambiguity, not absence", () => {
    // Two <nav>s both called "Navigation" satisfy "has a name" and leave a
    // screen reader's landmark list exactly as useless as it was.
    const labels = FILES.flatMap(navLandmarks).map((n) => n.label)
    expect(new Set(labels).size).toBe(labels.length)
  })
})
