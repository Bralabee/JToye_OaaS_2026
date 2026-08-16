import { Hand, Timer } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

/**
 * The published data-retention schedule (UI-SPEC S2a, LGL-01 / D-07).
 *
 * THE HARD PART IS THAT IT HAS TO FIT. Four columns of legally operative text
 * on a 375px phone is this phase's one genuine no-analog UI problem, and both
 * existing table treatments in this repo are anti-analogs rather than templates:
 *
 *   - components/marketing/business-model-guide.tsx:227 pins a 640px minimum
 *     width inside an `overflow-x-auto`, which does not avoid a horizontal
 *     scrollbar at 375px — it GUARANTEES one.
 *   - app/dashboard/webhooks/page.tsx:303 hides the table below `sm` and renders
 *     a parallel mobile card list, which duplicates the data in the DOM.
 *     Duplicated DOM is a trap this repo has already filed twice as a product
 *     bug (#556, #593): `getByTestId`/`getByTitle` see two copies while
 *     `getByRole` sees one, so tests and users disagree about what is on screen.
 *
 * NOTE ON HOW THOSE TWO ARE DESCRIBED RATHER THAN SPELLED. The class names are
 * deliberately written as prose, because the acceptance check for this file is a
 * literal `grep -F` for exactly those strings. Naming them here would satisfy
 * that grep from inside the comment that forbids them, leaving a check that can
 * never pass — the mirror image of a check that can never fail, and the seventh
 * instance of this shape in this phase alone. The check must be able to report
 * the truth in both directions, so the tokens live only where they would be a
 * defect: nowhere.
 *
 * So neither pattern is copied. This is ONE real `<table>` that is made to FIT
 * rather than made to scroll: no minimum-width pin, no suppression of wrapping
 * on cells, no block-display restyle at narrow widths, and no second mobile-only
 * copy of the data. Cells wrap, horizontal padding tightens below `sm`, and
 * `overflow-wrap: anywhere` lets the few long unbreakable tokens in the detail
 * column (`jtoye-customer-refresh`, `jtoye-track-email`) break rather than force
 * the table's min-content width past the viewport. Those tokens are the actual
 * mechanism by which this table would have overflowed, and they are the reason
 * that utility is here rather than decoration.
 *
 * WHY NOT A BLOCK-DISPLAY RESTYLE AT NARROW WIDTHS. It is the widely copied
 * responsive pattern and it strips table semantics in several screen readers,
 * turning a legally operative schedule into an unlabelled run of text. A
 * regulator's schedule that cannot be navigated by row and column header is not
 * the same document.
 *
 * THE SCROLL REGION IS THE SAFETY NET, NOT THE PLAN. The wrapper carries
 * `role="region"`, an `aria-label` and `tabIndex={0}` because an unfocusable
 * scroll container is exactly what axe's `scrollable-region-focusable` fails,
 * and because if a future row ever does overflow, the table must still be
 * reachable by keyboard. shadcn's `Table` primitive wraps in a bare
 * `<div className="relative w-full overflow-auto">` with no `role`, no
 * `aria-label` and no `tabindex` (`components/ui/table.tsx:9`), so this extends
 * that idea explicitly rather than relying on a wrapper that does not carry it.
 *
 * `caption-top`, DELIBERATELY. shadcn's default is `caption-bottom`; the
 * marketing table already overrides to `caption-top` and that is the right call
 * here for the same reason: a caption a reader meets BEFORE the data orients
 * them, whereas a caption after it can only explain what they have already tried
 * to interpret. For a screen-reader user the caption is the table's accessible
 * name either way, but sighted reading order is not, and this document is read
 * by people who are looking for one row.
 */

/** The only two values the enforcement column may carry (UI-SPEC S2a). */
export type RetentionEnforcement = "Automated" | "Operational"

export interface RetentionRow {
  /** Stable row id from docs/retention-manifest.json, e.g. "R-1". */
  id: string
  /** Short data-category name — the row header. */
  category: string
  /** Plain-English description of what the category actually covers. */
  detail: string
  /** Human-readable period, e.g. "24 hours" or "Kept indefinitely". */
  period: string
  /** The lawful basis, verbatim from the manifest. */
  lawfulBasis: string
  /** Whether the period is enforced automatically or operationally. */
  enforcement: RetentionEnforcement
}

/** The accessible name of the scroll region; asserted by name in the tests. */
export const RETENTION_REGION_LABEL = "Data retention schedule"

/**
 * Rendered from a constant rather than written as JSX text so the apostrophe in
 * "How it's enforced" is a plain string rather than a JSX text node, which keeps
 * `react/no-unescaped-entities` out of it without spelling the word with an HTML
 * entity in the middle of a column heading.
 */
export const RETENTION_COLUMNS = [
  "Data category",
  "Retention period",
  "Lawful basis",
  "How it's enforced",
] as const

const CAPTION =
  "How long J'Toye keeps each category of personal data, the lawful basis for keeping it, " +
  "and whether the period is enforced automatically by the system or operationally by people."

/** Shared cell geometry. Padding tightens below `sm` — that width is the budget. */
const CELL = "px-2 py-3 align-top [overflow-wrap:anywhere] sm:px-4"

export function RetentionTable({
  rows,
  className,
}: {
  rows: readonly RetentionRow[]
  className?: string
}) {
  return (
    <div
      role="region"
      aria-label={RETENTION_REGION_LABEL}
      tabIndex={0}
      data-testid="retention-scroll-region"
      className={cn(
        "overflow-x-auto rounded-lg border border-slate-200",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        className
      )}
    >
      <table className="w-full caption-top border-collapse text-left text-sm leading-[1.4] text-slate-700">
        <caption className="px-2 py-3 text-left text-sm leading-[1.4] text-slate-600 sm:px-4">
          {CAPTION}
        </caption>
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50">
            {RETENTION_COLUMNS.map((column) => (
              <th
                key={column}
                scope="col"
                className={cn(CELL, "text-sm font-semibold text-oxblood")}
              >
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              data-testid="retention-row"
              className="border-b border-slate-200 last:border-b-0"
            >
              <th scope="row" className={cn(CELL, "text-sm font-semibold text-slate-900")}>
                {row.category}
                <span className="mt-1 block text-sm font-normal leading-[1.4] text-slate-600">
                  {row.detail}
                </span>
              </th>
              <td className={CELL}>{row.period}</td>
              <td className={CELL}>{row.lawfulBasis}</td>
              <td className={CELL}>
                <EnforcementBadge enforcement={row.enforcement} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * The enforcement signal, carrying an icon AND the literal word — never colour
 * alone.
 *
 * This is a compliance signal read by people who may be colour-blind, and it is
 * the same rule the KDS allergen surfaces follow. It is also the column that
 * makes D-08's gate possible at all: an `Automated` row is one
 * scripts/check-retention-enforcement.sh can hold to a real enforcement site,
 * and an `Operational` row is explicitly a promise kept by people rather than by
 * a timer. Rendering that distinction as a hue would make it unreadable in
 * monochrome print and unreadable to a screen reader simultaneously, so the word
 * is the payload and the colour is redundant reinforcement.
 */
function EnforcementBadge({ enforcement }: { enforcement: RetentionEnforcement }) {
  const automated = enforcement === "Automated"
  const Icon = automated ? Timer : Hand
  return (
    <Badge
      variant="outline"
      className={cn(
        "gap-1.5 border px-2 py-0.5 text-sm font-semibold",
        automated
          ? "border-teal-700 bg-teal-50 text-teal-900"
          : "border-amber-700 bg-amber-50 text-amber-900"
      )}
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
      {enforcement}
    </Badge>
  )
}
