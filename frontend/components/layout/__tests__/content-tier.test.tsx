import fs from "node:fs"
import path from "node:path"

import { render, screen } from "@testing-library/react"

import { ContentTier, WIDTH_TIER_CLASS } from "../content-tier"
import type { WidthTier } from "@/lib/layout-widths"

/**
 * Phase 35 — the tier vocabulary.
 *
 * `lib/layout-widths.ts` holds the NUMBERS; this module holds the CLASS NAMES,
 * and the split is not stylistic. Tailwind's content globs cover pages/,
 * components/ and app/ — not lib/ — so a utility class written in lib/ is never
 * generated, and the failure is silent: clean build, class present in the
 * markup, element uncapped. That is why the literals live in components/ and why
 * this suite is the one that guards them.
 *
 * NO FULL CLASS LITERAL IS WRITTEN IN THIS FILE, deliberately. Each capped
 * tier's class is asserted to be DERIVED from its own theme key
 * (`max-w-` + tier), which is both a stronger assertion than restating the
 * string and the reason the module under test stays the single place in the tree
 * where those three strings appear — the property plan 35-10's static gate reads.
 *
 * The two assertions that carry the phase's weight, and what makes each
 * falsifiable rather than tautological:
 *
 *   - THE INDEX TIER RENDERS NO CAP. "Uncapped" implemented as an absence is a
 *     contract no assertion can distinguish from a forgotten cap (PATTERNS.md
 *     F-3). So the absence is asserted directly, and it is asserted alongside a
 *     control that renders a CAPPED tier through the same code path — without
 *     that control, an instrument that could never see a max-width class at all
 *     would report the same green.
 *   - THE MODULE CARRIES NO CLIENT DIRECTIVE. Checked against comment-stripped
 *     source, because the module's own docblock explains that it has no such
 *     directive and would satisfy the naive form of the check. Plan 35-01 hit
 *     exactly this shape and its non-vacuity control is reproduced below.
 */

const MODULE_PATH = path.join(__dirname, "..", "content-tier.tsx")
const RAW_SOURCE = fs.readFileSync(MODULE_PATH, "utf8")

/**
 * Strip block comments first, then line comments — order matters, or a `//`
 * inside a block comment starts a phantom line comment. Same helper shape as
 * lib/__tests__/layout-widths.test.ts, for the same reason.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "")
}

const STRIPPED_SOURCE = stripComments(RAW_SOURCE)

/**
 * Every member of the tier union, listed once.
 *
 * The `Exclude<...> extends never` line below is the real guard: it is a
 * COMPILE-TIME assertion that this array covers the union, so adding a tier to
 * `WidthTier` without adding it here reds `tsc --noEmit` rather than silently
 * shrinking every loop in this file. A hand-maintained list that quietly falls
 * behind the type it claims to enumerate is the vacuity risk here.
 */
const ALL_TIERS = ["shell", "index", "detail", "marketing"] as const
type TiersNotCovered = Exclude<WidthTier, (typeof ALL_TIERS)[number]>
const _everyTierIsCovered: TiersNotCovered extends never ? true : never = true
void _everyTierIsCovered

/** The tiers that declare a width. Index is deliberately not one of them. */
const CAPPED_TIERS = ["shell", "detail", "marketing"] as const

/** Render a tier and hand back the element the tier was applied to. */
function renderBand(tier: WidthTier, className?: string): HTMLElement {
  const view = render(
    <ContentTier tier={tier} className={className}>
      <p data-testid="tier-child">band content</p>
    </ContentTier>
  )
  return view.container.firstElementChild as HTMLElement
}

describe("WIDTH_TIER_CLASS — the only place the tier class literals live", () => {
  it.each(CAPPED_TIERS)(
    "derives the %s tier's utility from its own theme key",
    (tier) => {
      // Tailwind generates one utility per key of theme.extend.maxWidth, which is
      // spread from LAYOUT_WIDTHS. Asserting the derivation rather than the string
      // means a class that stopped matching its own generated utility reds here,
      // and it keeps the literal itself to a single occurrence in the tree.
      expect(WIDTH_TIER_CLASS[tier]).toBe(`max-w-${tier}`)
    }
  )

  it("maps the index tier to no class at all", () => {
    // ORCH-03 (orchestrator decision, 2026-08-29): Index is fluid to the shell.
    // A number here would silently NARROW every resource-index surface to it.
    expect(WIDTH_TIER_CLASS.index).toBe("")
  })

  it("carries exactly one entry per member of the tier union", () => {
    // T-35-05: a tier added to the union without a class entry would render
    // uncapped and silently. `Record<WidthTier, string>` reds tsc on a MISSING
    // key; this reds on a STRAY one, which the type cannot see.
    expect(Object.keys(WIDTH_TIER_CLASS).sort()).toEqual([...ALL_TIERS].sort())
  })
})

describe("ContentTier — the wrapper application shape", () => {
  it.each(ALL_TIERS)("declares the %s tier in the DOM", (tier) => {
    // The tier is a DECLARATION, queryable by a spec, not something inferred
    // from a measured width (PATTERNS.md F-3).
    expect(renderBand(tier)).toHaveAttribute("data-width-tier", tier)
  })

  it.each(ALL_TIERS)("centres the %s tier", (tier) => {
    expect(renderBand(tier)).toHaveClass("mx-auto")
  })

  it("renders no max-width class for the index tier", () => {
    // THE FALSIFIABLE FORM of "the Index tier is uncapped".
    expect(renderBand("index").className).not.toMatch(/max-w-/)
  })

  it("can see a max-width class when one is applied — the control for the line above", () => {
    // NON-VACUITY CONTROL. Without it, an instrument structurally incapable of
    // observing a max-width class reports the same green as a correct index tier.
    expect(renderBand("shell").className).toMatch(/max-w-/)
  })

  it("merges a caller-supplied className after the tier class", () => {
    const band = renderBand("shell", "px-6")
    expect(band).toHaveClass("px-6")
    expect(band).toHaveClass(WIDTH_TIER_CLASS.shell)
    // Order is the contract: a caller class that lands BEFORE the tier class
    // cannot override it, which is the only reason to accept one at all.
    expect(band.className.indexOf("px-6")).toBeGreaterThan(
      band.className.indexOf(WIDTH_TIER_CLASS.shell)
    )
  })

  it("renders its children unchanged", () => {
    renderBand("detail")
    expect(screen.getByTestId("tier-child")).toHaveTextContent("band content")
  })
})

describe("content-tier — importable from Server Components", () => {
  it("carries no client directive once comments are stripped", () => {
    // Several consumers of this vocabulary are Server Components. A directive
    // here would drag each of them across the client boundary.
    expect(STRIPPED_SOURCE).not.toMatch(/use client/)
  })

  it("proves the stripper is doing the work that check needs", () => {
    // NON-VACUITY CONTROL: the module's docblock necessarily NAMES the directive
    // it does not carry, so the raw source contains the phrase and a check run
    // against it would fail. Prose counts.
    expect(RAW_SOURCE).toMatch(/use client/)
    // ...and stripping removed the prose without removing the code.
    expect(STRIPPED_SOURCE).toContain("WIDTH_TIER_CLASS")
    expect(STRIPPED_SOURCE).toContain("ContentTier")
  })
})
