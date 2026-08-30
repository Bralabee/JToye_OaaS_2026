/**
 * THE horizontal layout contract. One declaration, three consumers.
 *
 * WHY THIS MODULE EXISTS. Phase 35's root cause, stated in the ROADMAP: no
 * document in this repository declared a content-width standard. The 1400px cap
 * every dashboard page inherited was the stock shadcn/ui scaffold block, shipped
 * verbatim and never chosen. The finding was never "1400 is the wrong number" —
 * it was that nobody could say where the number came from. So the fix is not a
 * different literal in the same place; it is a declared contract whose numbers
 * arrive with the measurement that justifies them.
 *
 * THE THREE CONSUMERS, and why the file has to live in lib/ rather than e2e/:
 *
 *   tailwind.config.ts                     (build)   turns these into utilities
 *   components/layout/content-tier.tsx     (runtime) applies a tier to a surface
 *   e2e/layout-width-contract.spec.ts      (test)    measures the rendered band
 *
 * The test consumer is the load-bearing one. A Playwright spec that asserted
 * 1700 while the shell rendered 1690 would be measuring a literal rather than a
 * contract — the failure lib/cart-identity.ts's own docblock warns about, in the
 * one other place this repository shares a constant across that boundary.
 *
 * PURE CONSTANTS, NO IMPORTS. This file is read by three different loaders —
 * jiti (which Tailwind uses to read its TypeScript config), webpack (Next) and
 * esbuild (Playwright). An `import` here, or a `require(` call, has to resolve
 * under all three, and the alias form does not resolve under jiti at all. There
 * is nothing this file needs, so it takes nothing. Its test proves that against
 * comment-stripped source, because this very paragraph would satisfy the naive
 * form of the check.
 *
 * NUMBERS LIVE HERE; CLASS-NAME LITERALS DO NOT. Tailwind's content globs cover
 * pages/, components/ and app/ — not lib/. A utility class name written in this
 * directory is therefore never generated, and the failure is SILENT: the class
 * is present in the markup, the element gets no cap, the build is clean and the
 * page renders at the wrong width. Measured, both arms, during phase 35
 * research. So the tier class strings are assembled in components/ where the
 * scanner can see them, and this file holds only the values they are built from.
 *
 * THE SUPERSEDED VALUE, kept as a record rather than deleted: 1400px, from
 * `theme.container.screens` in the shadcn scaffold, applied at a single call
 * site (the dashboard shell) and inherited by all 21 dashboard routes. It is
 * recorded here for the same reason perf-budgets.ts records
 * LANDING_CLS_KNOWN_BASELINE next to CLS_BUDGET: the phase's before/after
 * comparison needs something to compare against, and a number that lives only
 * in a summary cannot be read by anything that has to reason about it.
 */

/**
 * The dashboard chrome cap, in CSS pixels.
 *
 * PEER-MATCHED, NOT INVENTED. Three independently measured application shells
 * sit within 40px of each other: Stripe's own dashboard at 1690 (its
 * --Chrome-maxWidth custom property), Square's docs shell at 1720 and Square's
 * design site body at 1680. This value sits in that cluster. It is roughly 88%
 * of a 1920px screen and two thirds of a 2560px one, which is where the owner's
 * "at least two thirds of any screen's width" instinct and the measured industry
 * ceiling turn out to be the same number.
 *
 * Note what it does NOT do: at 1920 the dashboard band is still fluid, because
 * the sidebar leaves 1664px and the cap never binds. The first viewport at which
 * this value binds is around 1956px. An assertion phrased as "the band equals
 * this number" is therefore wrong at 1920 on a perfectly correct build — the
 * contract is min(available width, this value), never a bare constant.
 */
export const SHELL_MAX_PX = 1700

/**
 * The reading-and-forms cap, in CSS pixels: order detail, settings, any surface
 * whose job is to be read or filled in rather than scanned.
 *
 * PEER-MATCHED. Detail columns cluster tightly across products that have
 * measured this: Linear's detail ladder tops out at 1136, Square's content
 * ladder at 1016, Lightspeed's content column at 1100. The reason they converge
 * is the same reason prose is capped at a character measure — a line of text
 * that runs the full width of a 2560px screen is materially harder to read.
 *
 * This tier is the reason the phase is not simply "make everything wider".
 * Widening a settings form to the shell cap would be a regression dressed up as
 * an improvement, and the industry evidence says so independently of taste.
 */
export const DETAIL_MAX_PX = 1100

/**
 * The public marketing cap, in CSS pixels: the landing page and its siblings.
 *
 * PEER-MATCHED against Stripe's marketing pages at 1264. Marketing surfaces sit
 * deliberately below the application shell: they are read straight through by a
 * first-time visitor, not scanned for a row, so the argument that widens a data
 * table does not apply to them.
 *
 * Most of this tier is already at this width — it is the value of the stock
 * Tailwind 7xl token, which the marketing shells already use. The surface that
 * actually moves is the landing page, whose content bands sit at the 6xl token
 * (1152px) inside chrome that is already at this one, so its content is inset
 * from its own frame by 128px on each side for no stated reason.
 */
export const MARKETING_MAX_PX = 1280

/**
 * The px-string view of the contract, which is the form Tailwind's theme wants.
 *
 * Every value is DERIVED from the number above it rather than restated, so the
 * two cannot drift — the same discipline perf-budgets.ts uses for
 * LANDING_BUNDLE_CEILING_BYTES, which is written as its own arithmetic so the
 * total can never disagree with its parts.
 */
export const LAYOUT_WIDTHS = {
  shell: `${SHELL_MAX_PX}px`,
  detail: `${DETAIL_MAX_PX}px`,
  marketing: `${MARKETING_MAX_PX}px`,
} as const

/**
 * The tier vocabulary, and the asymmetry that IS the contract.
 *
 * There are four tiers but only three numbers. THE INDEX TIER IS DELIBERATELY
 * NOT DECLARED ABOVE, and this block is here to stop someone adding it.
 *
 * Index means "fluid to the shell" — the resource-index surfaces (products,
 * orders, customers, shops) take whatever width the shell allows and add no
 * further cap of their own. That is the documented pattern for data-dense index
 * surfaces rather than an omission: Shopify's Polaris prescribes a full-width
 * page for lists with many columns, IBM's Carbon ships a full-width escape from
 * its own grid, GitLab offers a fluid preference and Lightspeed's shell is
 * uncapped entirely. Giving Index a number would silently NARROW those surfaces
 * to it, which is the opposite of the intent.
 *
 * So why is `index` in this union at all, when it has no width? Because
 * "uncapped" implemented purely as an absence is a contract no assertion can
 * distinguish from a forgotten cap. The tier is named in the markup, so a spec
 * can find an index surface and assert that it is uncapped — a falsifiable
 * statement, rather than the absence of one.
 *
 * Prose is not in this union. Body copy is capped by a character measure, not a
 * pixel one, it is already correct in the tree, and this phase does not touch
 * it. Admitting it here would put a ch-measured surface into a px-measured
 * contract.
 */
export type WidthTier = "shell" | "index" | "detail" | "marketing"
