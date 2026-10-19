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
