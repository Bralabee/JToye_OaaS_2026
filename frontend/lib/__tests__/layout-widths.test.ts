import fs from "node:fs"
import path from "node:path"

import * as layoutWidths from "@/lib/layout-widths"
import {
  DETAIL_MAX_PX,
  LAYOUT_WIDTHS,
  MARKETING_MAX_PX,
  SHELL_MAX_PX,
  type WidthTier,
} from "@/lib/layout-widths"

/**
 * Phase 35 — the declaration module for the horizontal layout contract.
 *
 * WHY A TEST THAT RESTATES THE NUMBERS. Ordinarily a test that asserts
 * `CONST === <the literal in CONST>` is a change-detector and worth nothing.
 * Here it is the point. The three tier widths are peer-matched values locked by
 * the phase CONTEXT, not implementation detail — a silent edit to any of them
 * changes what every dashboard, form and marketing page in the product looks
 * like. Restating them in exactly one other place, a test, makes such an edit a
 * deliberate two-file act that arrives at review with its own diff. That is the
 * same reason `e2e/perf-budgets.ts`'s numbers are asserted rather than trusted.
 *
 * The interesting assertions are the other three, and none of them is a
 * change-detector:
 *
 *   - the px strings are REBUILT from the numbers, so a hand-edited literal that
 *     drifts from its own source reds;
 *   - the module is proven to have no `import` and no `require(` after its
 *     comments are stripped, because three different loaders read it;
 *   - the docblock is proven to still carry the justification for each number,
 *     which is the defect this whole phase exists to fix.
 */

const MODULE_PATH = path.join(__dirname, "..", "layout-widths.ts")
const RAW_SOURCE = fs.readFileSync(MODULE_PATH, "utf8")

/**
 * Strip block comments first, then line comments.
 *
 * Order matters: a `//` inside a block comment must not be treated as the start
 * of a line comment. Safe on this module specifically because its only strings
 * are the derived `px` template literals — no URL, no regex, nothing else that
 * can contain a `//` outside a comment. If that ever stops being true this
 * helper has to grow, and the raw-source control below is what will notice.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "")
}

const STRIPPED_SOURCE = stripComments(RAW_SOURCE)

describe("layout-widths — the declared tier values", () => {
  it("declares the three peer-matched tier numbers", () => {
    // Locked by CONTEXT.md section 4. Shell tracks Stripe's dashboard (1690) and
    // Square (1680-1720); Detail tracks Linear (1136) / Square (1016) /
    // Lightspeed (1100); Marketing tracks Stripe marketing (1264).
    expect(SHELL_MAX_PX).toBe(1700)
    expect(DETAIL_MAX_PX).toBe(1100)
    expect(MARKETING_MAX_PX).toBe(1280)
  })

  it("declares them as numbers, so a consumer can do arithmetic on them", () => {
    // The Playwright contract spec asserts min(available, TIER). A px string
    // there would silently coerce and compare wrong.
    for (const value of [SHELL_MAX_PX, DETAIL_MAX_PX, MARKETING_MAX_PX]) {
      expect(typeof value).toBe("number")
      expect(Number.isInteger(value)).toBe(true)
    }
  })
})

describe("layout-widths — the px view Tailwind consumes", () => {
  it("derives every px string from its own number rather than restating it", () => {
    // Rebuilt from the constant, not compared to a literal typed here. A
    // hand-edited px string that drifts from its number reds on this line.
    expect(LAYOUT_WIDTHS.shell).toBe(`${SHELL_MAX_PX}px`)
    expect(LAYOUT_WIDTHS.detail).toBe(`${DETAIL_MAX_PX}px`)
    expect(LAYOUT_WIDTHS.marketing).toBe(`${MARKETING_MAX_PX}px`)
  })

  it("carries exactly three keys and no index key", () => {
    // The Index tier means "no cap below the Shell cap". Declaring a number for
    // it would silently narrow it, and would make "fluid to shell" a claim no
    // assertion could distinguish from a forgotten cap.
    expect(Object.keys(LAYOUT_WIDTHS).sort()).toEqual([
      "detail",
      "marketing",
      "shell",
    ])
    expect(LAYOUT_WIDTHS).not.toHaveProperty("index")
  })
})

describe("layout-widths — the tier vocabulary", () => {
  it("names four tiers, including the one that declares no width", () => {
    // The asymmetry IS the contract: the union carries `index`, LAYOUT_WIDTHS
    // deliberately does not. This list is typed, so dropping a member from the
    // union reds `tsc --noEmit` rather than this expectation.
    const allTiers: WidthTier[] = ["shell", "index", "detail", "marketing"]
    expect(allTiers).toHaveLength(4)
  })

  it("excludes prose, which is measured in characters and is not a tier here", () => {
    // Prose stays at `max-w-[68ch]` and is explicitly unchanged by this phase.
    // The ts-expect-error is the assertion: it reds `tsc --noEmit` if prose is
    // ever admitted to this union, which would put a ch-measured surface into a
    // px-measured contract.
    // @ts-expect-error prose is deliberately outside the tier vocabulary
    const notATier: WidthTier = "prose"
    expect(notATier).toBe("prose")
  })
})

describe("layout-widths — loadable by all three of its loaders", () => {
  it("has no import and no require after comments are stripped", () => {
    // jiti (Tailwind config), webpack (Next) and esbuild (Playwright) all read
    // this file. Anything pulled in here has to resolve under all three.
    expect(STRIPPED_SOURCE).not.toMatch(/\bimport\b/)
    expect(STRIPPED_SOURCE).not.toMatch(/\brequire\s*\(/)
  })

  it("exposes no default export", () => {
    // A default export would be ambiguous across the three loaders' interop.
    expect(STRIPPED_SOURCE).not.toMatch(/\bexport\s+default\b/)
    expect(Object.keys(layoutWidths)).not.toContain("default")
  })

  it("exports exactly the four declared names", () => {
    // WidthTier is a type and is correctly absent at runtime.
    const exported = Object.keys(layoutWidths).filter((k) => k !== "__esModule")
    expect(exported.sort()).toEqual([
      "DETAIL_MAX_PX",
      "LAYOUT_WIDTHS",
      "MARKETING_MAX_PX",
      "SHELL_MAX_PX",
    ])
  })

  it("proves the comment stripper is doing the work the purity check needs", () => {
    // NON-VACUITY CONTROL, and it is the whole reason the purity check is run
    // against stripped source. The docblock necessarily explains that the module
    // must carry no `import` and no `require(` — so the RAW source contains both
    // tokens, and a purity check run against it would fail. A check its own
    // documentation can satisfy is not a check; a check its own documentation
    // would BREAK is only meaningful once you have shown the stripping works.
    expect(RAW_SOURCE).toMatch(/\bimport\b/)
    expect(RAW_SOURCE).toMatch(/\brequire\s*\(/)
    // ...and stripping removed the prose without removing the code.
    expect(STRIPPED_SOURCE).toContain("SHELL_MAX_PX")
    expect(STRIPPED_SOURCE).toContain("LAYOUT_WIDTHS")
  })
})

describe("layout-widths — the justification travels with the number", () => {
  it("records the peer measurement behind each tier", () => {
    // ROADMAP Phase 35's root cause, verbatim: "No document in the repo declares
    // a width standard - the number came with the scaffold." A number whose
    // justification is not written down is exactly the defect being fixed, so
    // deleting the justification must red something.
    expect(RAW_SOURCE).toContain("1690") // Stripe Dashboard  -> Shell
    expect(RAW_SOURCE).toContain("1136") // Linear detail     -> Detail
    expect(RAW_SOURCE).toContain("1264") // Stripe marketing  -> Marketing
  })

  it("records the superseded shadcn container value it replaces", () => {
    // Held next to the declared targets the same way perf-budgets.ts holds
    // LANDING_CLS_KNOWN_BASELINE next to CLS_BUDGET: the phase's before/after
    // comparison needs something to A/B against, and prose in a summary cannot
    // be compared against by anything.
    expect(RAW_SOURCE).toContain("1400")
  })
})
