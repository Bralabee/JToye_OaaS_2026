import postcss from "postcss"
import type { AcceptedPlugin, ChildNode, Container } from "postcss"
import tailwindcss from "tailwindcss"
import type { Config } from "tailwindcss"

import tailwindConfig from "../../tailwind.config"
import {
  DETAIL_MAX_PX,
  LAYOUT_WIDTHS,
  MARKETING_MAX_PX,
  SHELL_MAX_PX,
} from "@/lib/layout-widths"

/**
 * Phase 35 — does the build actually emit the contract the module declares?
 *
 * WHAT THIS CATCHES THAT THE OTHER SUITE CANNOT. `layout-widths.test.ts` proves
 * the numbers are declared once and derived correctly. It says nothing about
 * whether Tailwind ever turns them into CSS. The two can diverge silently in
 * both directions: a hand-typed value in tailwind.config.ts drifts away from the
 * module, or the config fails to pick the module up at all and every capped
 * element renders uncapped with a completely clean build. This runs the repo's
 * REAL config through PostCSS and reads the answer out of the emitted stylesheet.
 *
 * WHY THE AST AND NOT STRING MATCHING. One of the four properties is "this rule
 * is not inside a media query", and that is a question about structure. A regex
 * over the CSS text can tell you the declaration exists; only the tree can tell
 * you what it is nested in. That property is how the mobile-must-not-move
 * constraint is proven: a cap that emits no media query is present at 390px but
 * cannot bind against a 390px parent, so mobile geometry is identical BY
 * CONSTRUCTION rather than by a browser observation somebody has to trust.
 *
 * NON-VACUITY. Every assertion here would pass trivially over an empty
 * stylesheet — "no rule is inside a media query" and "no container rule exists"
 * are both true of nothing at all. So the suite first proves the config loaded
 * and produced this repository's own tokens. Without that, a config that threw
 * and silently fell back would read as a clean pass.
 *
 * Stack-free and fast, which matters: the dashboard tiers themselves can only be
 * measured in a real browser against a live stack, and that lane is the nightly
 * one. This is the part of the contract that can be checked on every PR.
 */

/**
 * A pre-existing token from this repository's own theme.extend.colors block.
 * It exists ONLY because the real config was loaded — stock Tailwind has no
 * such colour — so its presence is the proof that the run below is meaningful.
 */
const REPO_SPECIFIC_TOKEN = "bg-oxblood"

/** The class the retired shadcn scaffold used to produce. */
const RETIRED_UTILITY = "container"

/**
 * Tier class names are ASSEMBLED from the module's own keys rather than typed
 * out, so this file cannot assert a utility the contract does not declare.
 */
function tierUtility(tier: keyof typeof LAYOUT_WIDTHS): string {
  return `max-w-${tier}`
}

const TIER_EXPECTATIONS = [
  ["shell", SHELL_MAX_PX],
  ["detail", DETAIL_MAX_PX],
  ["marketing", MARKETING_MAX_PX],
] as const

interface EmittedRule {
  selector: string
  decls: Record<string, string>
  /** `params` of every enclosing @media, outermost last. Empty means unwrapped. */
  media: string[]
}

let emitted: EmittedRule[] = []

/**
 * Does `selector` name `token` as a utility?
 *
 * Tailwind emits `.max-w-shell` unprefixed and `.lg\:max-w-shell` for a
 * variant, so a plain equality check would miss exactly the case that matters
 * most here — a tier that only applies above a breakpoint. Matching the variant
 * forms too is what lets the "no media query" assertion be about the rule's
 * nesting rather than about which selectors happened to be looked for.
 */
function namesUtility(selector: string, token: string): boolean {
  const trimmed = selector.trim()
  if (!trimmed.endsWith(token)) return false
  const prefix = trimmed.slice(0, trimmed.length - token.length)
  return prefix === "." || prefix.endsWith("\\:")
}

function rulesNaming(token: string): EmittedRule[] {
  return emitted.filter((rule) => namesUtility(rule.selector, token))
}

beforeAll(async () => {
  const raw = [
    ...(Object.keys(LAYOUT_WIDTHS) as (keyof typeof LAYOUT_WIDTHS)[]).map(
      tierUtility
    ),
    RETIRED_UTILITY,
    REPO_SPECIFIC_TOKEN,
  ].join(" ")

  const result = await postcss([
    tailwindcss({
      ...(tailwindConfig as Config),
      content: [{ raw }],
    }) as AcceptedPlugin,
  ]).process("@tailwind base;\n@tailwind components;\n@tailwind utilities;", {
    from: undefined,
  })

  emitted = []
  result.root.walkRules((rule) => {
    const media: string[] = []
    let parent: Container<ChildNode> | undefined = rule.parent as
      | Container<ChildNode>
      | undefined
    while (parent && parent.type !== "root") {
      if (parent.type === "atrule" && "name" in parent && parent.name === "media") {
        media.push(String((parent as { params?: string }).params ?? ""))
      }
      parent = parent.parent as Container<ChildNode> | undefined
    }

    const decls: Record<string, string> = {}
    rule.walkDecls((decl) => {
      decls[decl.prop] = decl.value
    })

    emitted.push({ selector: rule.selector, decls, media })
  })
}, 120_000)

/**
 * Render a rule the way a failure should read: selector plus the media nesting
 * that is usually the thing that went wrong.
 *
 * Jest's `expect` takes exactly one argument — the second-argument message form
 * is Playwright's, and passing it here throws "Expect takes at most one
 * argument" on every assertion, which is how the first draft of this file
 * managed to report eleven failures that were all the instrument. So the
 * diagnosis has to travel in the compared VALUE instead, which is why the
 * assertions below compare descriptive arrays rather than bare counts.
 */
function describeRule(rule: EmittedRule): string {
  const nesting = rule.media.length ? `@media ${rule.media.join(" / ")}` : "unwrapped"
  return `${rule.selector} [${nesting}]`
}

describe("generated CSS — the run itself is meaningful", () => {
  it("loaded THIS repository's config, not a stock fallback", () => {
    // The vacuity control, and it runs first on purpose. Every other assertion
    // in this file is satisfied by an empty stylesheet.
    //
    // Two independent signals, both measured on this tree rather than guessed:
    // the brand colour exists ONLY in this repo's theme.extend.colors, so it
    // proves the real config was read; the preflight rule proves the base layer
    // ran at all. A first draft asserted a rule COUNT above 50 and reported a
    // false red at the true value of 45 — a threshold nobody measured.
    expect(rulesNaming(REPO_SPECIFIC_TOKEN).length).toBeGreaterThan(0)
    expect(
      emitted.filter((rule) => rule.selector.includes("html")).length
    ).toBeGreaterThan(0)
  })
})

describe("generated CSS — the tier utilities", () => {
  it.each(TIER_EXPECTATIONS)(
    "emits the %s tier at the value declared in the module",
    (tier, declaredPx) => {
      const rules = rulesNaming(tierUtility(tier))
      // Found at all: a selector that matches nothing would make every
      // per-rule assertion below vacuously true.
      expect(rules.length).toBeGreaterThan(0)
      // Compared against the imported constant, never a number typed here.
      expect(rules.map((rule) => rule.decls["max-width"])).toEqual(
        rules.map(() => `${declaredPx}px`)
      )
    }
  )

  it.each(TIER_EXPECTATIONS)("wraps the %s tier in no media query at all", (tier) => {
    const rules = rulesNaming(tierUtility(tier))
    expect(rules.length).toBeGreaterThan(0)
    // An unconditional cap is inert until its parent exceeds it, so it is
    // present at 390px and cannot bind there. This is the mobile-safety claim
    // expressed as a diffable property of the stylesheet.
    expect(rules.filter((rule) => rule.media.length > 0).map(describeRule)).toEqual([])
  })
})

describe("generated CSS — the retired shadcn container", () => {
  it("emits no container rule even when the class name is in the content", () => {
    // The raw content above deliberately contains the class name. Disabling the
    // theme block alone would NOT achieve this: the core plugin then falls back
    // to the default screens and emits five media queries instead of one, which
    // is strictly worse than the tree's starting state. Only switching the
    // plugin off makes the retirement real, and this is what proves it.
    expect(rulesNaming(RETIRED_UTILITY).map(describeRule)).toEqual([])
  })
})

describe("tailwind.config.ts — the structural half", () => {
  // Secondary to the emitted-CSS assertions above, deliberately. A structural
  // check can pass while the function is still broken, so these exist to say
  // WHY the CSS looks the way it does, never as the evidence that it does.
  const config = tailwindConfig as Config

  it("carries no theme.container block for anyone to re-adopt", () => {
    expect(config.theme).toBeDefined()
    expect(config.theme).not.toHaveProperty("container")
  })

  it("switches the container core plugin off", () => {
    expect(config.corePlugins).toMatchObject({ container: false })
  })

  it("feeds theme.extend.maxWidth from the declaration module", () => {
    expect(config.theme?.extend?.maxWidth).toEqual(LAYOUT_WIDTHS)
  })
})
