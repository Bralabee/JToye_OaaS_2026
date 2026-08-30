import { cn } from "@/lib/utils"
import type { WidthTier } from "@/lib/layout-widths"
import type { ReactNode } from "react"

/**
 * THE TIER VOCABULARY. One map, one wrapper, four tiers.
 *
 * `lib/layout-widths.ts` declares the NUMBERS; this module declares the CLASS
 * NAMES built from them, and every surface in the product speaks its width
 * through one of the two.
 *
 * WHY THE LITERALS ARE HERE AND NOT NEXT TO THE NUMBERS. Tailwind's content
 * globs (tailwind.config.ts) cover pages/, components/ and app/ — not lib/. A
 * utility class name written in lib/ is therefore never generated, and the
 * failure is SILENT in the worst way: the build is clean, the class is present
 * in the markup, and the element renders with no cap at all. Measured in both
 * directions during phase 35 research. So the numbers stay in lib/ where three
 * different loaders can read them, and the strings assembled from those numbers
 * live here, where the scanner can see them.
 *
 * This is the ONLY place in the tree where those three utility strings appear as
 * literals. Plan 35-10's static gate reads that property, which is also why the
 * prose in this file describes the tokens rather than spelling each of them out
 * a second time — a comment satisfies a grep just as well as code does, and this
 * repository has already shipped one check that its own documentation defeated.
 *
 * THE INDEX TIER MAPS TO THE EMPTY STRING, ON PURPOSE. Index means "fluid to the
 * shell": the resource-index surfaces (products, orders, customers, shops) take
 * whatever width the shell allows and add no further cap, which is the
 * documented pattern for data-dense lists rather than an omission — Polaris
 * prescribes a full-width page for lists with many columns, Carbon ships a
 * full-width escape from its own grid, and Lightspeed's shell is uncapped
 * outright. Giving Index a value would silently narrow exactly the surfaces this
 * phase exists to widen.
 *
 * The empty string is nevertheless a MAP ENTRY rather than a missing key, and
 * the tier is still written into the DOM, because of PATTERNS.md finding F-3:
 * "uncapped" implemented as "change nothing" produces a contract no assertion
 * can distinguish from the bug. A spec that measures a dashboard index page at
 * the shell width cannot tell whether the page is deliberately Index-tier or
 * whether someone forgot to cap it — both read identically. The attribute turns
 * the tier into a declaration a test can find and falsify. ORCH-03 (orchestrator
 * decision, 2026-08-29).
 *
 * TWO APPLICATION SHAPES, and which to use is not a matter of taste. Every
 * wave-3 plan in this phase follows this rule rather than restating it:
 *
 *   IN PLACE (preferred) — the surface already has a band element carrying
 *   auto margins and a max-width. Swap the max-width class for the map entry and
 *   add the tier attribute to that SAME element. No new DOM node, so no change
 *   to any layout, motion or scroll-reveal behaviour, and nothing for the
 *   existing CLS and bounding-box assertions to notice.
 *
 *   WRAPPER (only when no band element exists) — use `ContentTier` below. It
 *   adds a DOM node, which is the shape that can move things, so it is the
 *   fallback rather than the default.
 *
 * ONE COMPONENT, PARAMETERISED BY TIER — not one component per tier.
 * `components/public/public-shell.tsx` records what this repository already paid
 * for going the other way: three copies of a shared pattern existed and two had
 * already drifted apart, so a fourth variant would have been the drift rather
 * than the fix.
 *
 * NO CLIENT DIRECTIVE. There is deliberately no "use client" at the top of this
 * file: the component holds no state, no hook and no event handler, and several
 * of the surfaces that will apply a tier are Server Components which a directive
 * here would drag across the client boundary. The co-located test asserts this
 * against comment-stripped source, precisely because this paragraph would
 * otherwise satisfy the check.
 */

/**
 * Tier -> the class that caps it.
 *
 * Each capped tier's string is `max-w-` followed by that tier's own key in
 * `theme.extend.maxWidth`, which `tailwind.config.ts` spreads from
 * `LAYOUT_WIDTHS`. The test asserts that derivation rather than restating the
 * strings, so a rename on either side of the build reds.
 *
 * `Record<WidthTier, string>` is load-bearing: a tier added to the union without
 * an entry here would render uncapped and silently, and this type is what stops
 * that reaching a browser (T-35-05).
 */
export const WIDTH_TIER_CLASS: Record<WidthTier, string> = {
  shell: "max-w-shell",
  index: "",
  detail: "max-w-detail",
  marketing: "max-w-marketing",
}

export interface ContentTierProps {
  /**
   * The declared tier. A compile-time member of a closed union, never a runtime
   * string from a request or a URL — which is why no injected value can select a
   * class here (T-35-07). If that ever stops being true, that disposition has to
   * be revisited.
   */
  tier: WidthTier
  /** Extra classes, merged AFTER the tier class so a caller can override it. */
  className?: string
  children?: ReactNode
}

/**
 * The WRAPPER application shape: a centred band declaring its tier.
 *
 * Prefer the in-place shape described above wherever a band element already
 * exists — this one adds a DOM node.
 */
export function ContentTier({ tier, className, children }: ContentTierProps) {
  return (
    <div data-width-tier={tier} className={cn("mx-auto", WIDTH_TIER_CLASS[tier], className)}>
      {children}
    </div>
  )
}
