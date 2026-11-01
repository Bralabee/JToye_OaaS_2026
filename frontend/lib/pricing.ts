// Public price points for the marketing pages (/business-model-guide, /competitive).
//
// Source of truth is the BILLING CODE, not any doc: StripeProperties.Billing
// (core-java/.../payment/StripeProperties.java) and the V88 migration header.
//   SAAS_HYBRID_STANDARD   = coreMonthly per location per month + usageRate of direct sales
//   SAAS_FLAT_PREDICTABLE  = flatLow..flatHigh per location per month, 0% platform fee
//   BATCH_PAYG_STARTER     = GBP 0 per month + paygRate of direct sales
// Stripe card fees are a pass-through on the vendor's own Stripe account.
//
// setupFrom is NOT billed by StripeBillingService: it is the one-off assisted
// onboarding fee from ADR-0007 axis 4, invoiced outside the billing code, and is
// printed as "from", scoped to the work agreed.
//
// WALK_SETUP_STEPDOWN (GBP 195 during setup) is an internal tier and is
// deliberately NOT part of this module, so it cannot be printed by accident.
//
// Pricing is a DEFAULT the owner may overrule: a ruling is a change in this file.

/** 0.005 -> "0.5%", 0.06 -> "6%". Rounds to two decimals so float noise never prints. */
export function formatRate(rate: number): string {
  return `${Number((rate * 100).toFixed(2))}%`
}

const BASE = {
  coreMonthly: 39,
  usageRate: 0.005,
  flatLow: 79,
  flatHigh: 149,
  paygRate: 0.06,
  setupFrom: 99,
} as const

export const PRICING = {
  ...BASE,
  usageLabel: formatRate(BASE.usageRate),
  paygLabel: formatRate(BASE.paygRate),
} as const

/**
 * The two pricing footnotes /for-operators prints under its plan table, moved
 * here (SW-SAM-12, swarm epic #964) so /legal/terms can quote them instead of
 * retyping them. operator-pitch.tsx is a client module, and a server component
 * that imports a constant from a "use client" file receives a client reference,
 * not the string — so the shared copy has to live in a plain module.
 */
//
// The setup line is a FUNCTION of the pricing object, not a precomputed string:
// operator-pitch-pricing.test.tsx proves the page reads PRICING by mocking it
// with substitute figures, and a string built here at module load would carry
// the real £ figure past that mock. Callers pass the PRICING they imported.
export function formatSetupLine(pricing: { setupFrom: number } = PRICING): string {
  return `Assisted setup from £${pricing.setupFrom}, scoped to the work agreed.`
}
export const PRICING_CARD_FEE_LINE =
  "Stripe card fees are charged on your own Stripe account, because you are the merchant of record."
