/**
 * Client-side VAT PREVIEW. Mirrors `uk.jtoye.core.finance.VatCalculator` clause for clause.
 *
 * COR-6 (QA-council 20260902-134741). The checkout page used to compute
 * `Math.floor((total * 20) / 120)` and print a hardcoded "VAT (incl. 20%)" label, because
 * `PublicProductDto` carried no rate and the client structurally could not resolve one. On a
 * zero-rated basket the customer was therefore shown a VAT figure BEFORE paying and a
 * contradicting figure on the confirmation screen a moment later. The server has resolved the
 * real rate since Issue #81 BUG 2; only the client was left behind.
 *
 * DISPLAY ONLY. `VatCalculator` is authoritative: the order's VAT is recomputed server-side at
 * write time from `products.vat_rate`, so nothing here can change what is charged or what reaches
 * the HMRC-facing ledger. This is the same convention `previewDeliveryFeePennies` already states.
 *
 * UK RATES — re-fetched from the external authority on 2026-09-03, as the plan requires (the
 * planner was localhost-scoped and could only cite in-repo records):
 *   - https://www.gov.uk/vat-rates (page last updated 2014-12-12): standard 20%, reduced 5%,
 *     zero 0%; some supplies are exempt.
 *   - HMRC VAT Notice 709/1, "Catering, takeaway food and VAT"
 *     (https://www.gov.uk/guidance/catering-takeaway-food-and-vat-notice-7091, last updated
 *     2026-06-08): hot takeaway food and drink is standard-rated; most food is zero-rated.
 *     That notice also carries a TEMPORARY reduced rate for certain children's meals running
 *     25 June 2026 to 1 September 2026 — a window that CLOSED two days before this retrieval, so
 *     no temporary rate applies today. It is recorded because it shows REDUCED is a live category
 *     for food, not a theoretical one, and this mirror must keep handling it.
 *
 * ROUNDING: truncate toward zero, which HMRC VAT Notice 700 §17.5.1 permits and which matches
 * Java `long` division and PostgreSQL integer division exactly. `Math.trunc`, NOT `Math.floor`:
 * they differ on negative amounts (refunds/expenses), where floor would round away from zero and
 * disagree with the server by a penny.
 */

export type VatRateName = "STANDARD" | "REDUCED" | "ZERO" | "EXEMPT"

/** Percentage points of VAT for each rate. Mirrors VatCalculator.vatFromGross's switch. */
const PERCENT: Record<VatRateName, number> = {
  STANDARD: 20,
  REDUCED: 5,
  ZERO: 0,
  EXEMPT: 0,
}

/** Human label for the checkout summary line. EXEMPT is not "0%" — it is a different claim. */
const LABEL: Record<VatRateName, string> = {
  STANDARD: "incl. 20%",
  REDUCED: "incl. 5%",
  ZERO: "zero-rated",
  EXEMPT: "exempt",
}

/**
 * Normalise an unknown wire value to a rate.
 *
 * An unrecognised or missing rate resolves to STANDARD, matching
 * `VatCalculator.predominantRate`'s treatment of a null line rate: "no silent zero-rating". Being
 * wrong in the direction of MORE tax is the conservative error for a preview; being wrong toward
 * zero would show a customer a VAT-free basket that is then taxed at checkout.
 */
export function asVatRate(raw: string | null | undefined): VatRateName {
  if (raw === "STANDARD" || raw === "REDUCED" || raw === "ZERO" || raw === "EXEMPT") return raw
  return "STANDARD"
}

/**
 * The VAT contained WITHIN a VAT-inclusive gross amount (UK retail prices are VAT-inclusive, so
 * VAT is never added on top). Mirrors `VatCalculator.vatFromGross`.
 */
export function vatFromGross(grossPennies: number, rate: VatRateName): number {
  const pct = PERCENT[rate]
  if (pct === 0) return 0
  // Multiply BEFORE dividing to keep precision until the single truncation, exactly as the
  // server does.
  return Math.trunc((grossPennies * pct) / (100 + pct))
}

/** One basket line's VAT-inclusive gross and its resolved rate. Mirrors VatCalculator.LineRate. */
export interface VatLine {
  grossPennies: number
  rate: VatRateName
}

/**
 * The PREDOMINANT rate for a basket. Mirrors `VatCalculator.predominantRate`:
 * sum each line's NET (ex-VAT) value per rate and return the rate carrying the greatest net,
 * iterating the fixed priority STANDARD > REDUCED > ZERO > EXEMPT with a strict `>` so equal net
 * values resolve to STANDARD (most conservative for HMRC). An empty basket returns STANDARD; an
 * all-ZERO basket stays ZERO — a genuinely zero-rated basket is NOT upgraded.
 */
export function predominantRate(lines: VatLine[]): VatRateName {
  if (lines.length === 0) return "STANDARD"

  const netByRate = new Map<VatRateName, number>()
  for (const line of lines) {
    const net = line.grossPennies - vatFromGross(line.grossPennies, line.rate)
    netByRate.set(line.rate, (netByRate.get(line.rate) ?? 0) + net)
  }

  const priority: VatRateName[] = ["STANDARD", "REDUCED", "ZERO", "EXEMPT"]
  let best: VatRateName | null = null
  let bestNet = Number.NEGATIVE_INFINITY
  for (const rate of priority) {
    const net = netByRate.get(rate)
    if (net !== undefined && net > bestNet) {
      bestNet = net
      best = rate
    }
  }
  return best ?? "STANDARD"
}

/** The summary-line label for a resolved rate, e.g. "VAT (incl. 20%)" / "VAT (zero-rated)". */
export function vatRateLabel(rate: VatRateName): string {
  return LABEL[rate]
}
