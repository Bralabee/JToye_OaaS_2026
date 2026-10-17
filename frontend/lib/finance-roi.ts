import type { FinancialSummary, FinancialTransaction, Order } from "@/types/api"

/**
 * UK third-party aggregator commission benchmark (28% = 2,800 bps) across
 * Deliveroo / Uber Eats / Just Eat, per docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md §3 & §7.
 */
export const AGGREGATOR_COMMISSION_BPS = 2800

/**
 * Direct GTV crossover (£710.00 = 71,000 pennies) above which SAAS_HYBRID_STANDARD
 * (£39/mo + 0.5%) is strictly cheaper than BATCH_PAYG_STARTER (£0/mo + 6.0%),
 * per docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md §7.
 */
export const PAYG_UPGRADE_THRESHOLD_PENNIES = 71_000

/**
 * Commercial billing modes defined in ADR-0007 Axis 4 (BillingPlanMode).
 */
export type BillingPlanMode =
  | "SAAS_HYBRID_STANDARD"
  | "SAAS_FLAT_PREDICTABLE"
  | "BATCH_PAYG_STARTER"

export interface BillingPlanPreset {
  mode: BillingPlanMode
  label: string
  shortLabel: string
  monthlyBasePennies: number
  usageBps: number
  description: string
}

export const BILLING_PLAN_PRESETS: Record<BillingPlanMode, BillingPlanPreset> = {
  SAAS_HYBRID_STANDARD: {
    mode: "SAAS_HYBRID_STANDARD",
    label: "Hybrid Standard (£39/mo + 0.5%)",
    shortLabel: "£39/mo + 0.5%",
    monthlyBasePennies: 3_900,
    usageBps: 50,
    description: "Default GTM plan: £39/location/mo base retainer + 0.5% platform usage fee.",
  },
  SAAS_FLAT_PREDICTABLE: {
    mode: "SAAS_FLAT_PREDICTABLE",
    label: "Flat Predictable (£99/mo + 0%)",
    shortLabel: "£99/mo + 0%",
    monthlyBasePennies: 9_900,
    usageBps: 0,
    description: "High-volume flat SaaS tier: £99/location/mo with 0% J'Toye transaction fee.",
  },
  BATCH_PAYG_STARTER: {
    mode: "BATCH_PAYG_STARTER",
    label: "Starter PAYG (£0/mo + 6%)",
    shortLabel: "£0/mo + 6%",
    monthlyBasePennies: 0,
    usageBps: 600,
    description: "Zero monthly commitment for batch/event drops: £0/mo + 6.0% platform fee.",
  },
}

export interface RepeatBuyerStats {
  identifiedCustomers: number
  repeatCustomers: number
  /** Null when identifiedCustomers === 0 so empty samples are never misrepresented as 0%. */
  repeatBuyerPercent: number | null
  totalSampledOrders: number
}

export interface AbuseProtectionStats {
  unverifiedOrdersCount: number
  cancelledUnverifiedCount: number
  v81CapsLabel: string
}

export interface MerchantRoiTelemetry {
  plan: BillingPlanPreset
  directGtvPennies: number
  aggregatorCommissionPennies: number
  jtoyeUsageFeePennies: number
  jtoyeMonthlyBasePennies: number
  jtoyeTotalCostPennies: number
  grossCommissionSavedPennies: number
  netMonthlySavingsPennies: number
  settledSaleCount: number
  voidReversalCount: number
  averageOrderValuePennies: number
  repeatBuyerStats: RepeatBuyerStats
  abuseProtectionStats: AbuseProtectionStats
  recommendHybridUpgrade: boolean
  hybridUpgradeSavingsPennies: number
}

function extractCustomerKey(order: Order): string | null {
  const byId = order.customerId?.trim()
  if (byId) return `id:${byId}`
  const byEmail = order.customerEmail?.trim().toLowerCase()
  if (byEmail) return `email:${byEmail}`
  const byPhone = order.customerPhone?.trim()
  if (byPhone) return `phone:${byPhone}`
  return null
}

/**
 * Deterministically computes GAP-GTM-07 Merchant Value & ROI Telemetry from the
 * shop-scoped V79 financial summary, ledger rows, and recent orders sample.
 */
export function computeMerchantRoiTelemetry(
  summary: FinancialSummary,
  transactions: FinancialTransaction[],
  orders: Order[],
  planMode: BillingPlanMode = "SAAS_HYBRID_STANDARD"
): MerchantRoiTelemetry {
  const plan = BILLING_PLAN_PRESETS[planMode]
  const directGtvPennies = Math.max(0, summary.totalRevenuePennies)

  const aggregatorCommissionPennies = Math.round(
    (directGtvPennies * AGGREGATOR_COMMISSION_BPS) / 10_000
  )
  const jtoyeUsageFeePennies = Math.round((directGtvPennies * plan.usageBps) / 10_000)
  const jtoyeMonthlyBasePennies = plan.monthlyBasePennies
  const jtoyeTotalCostPennies = jtoyeMonthlyBasePennies + jtoyeUsageFeePennies
  const grossCommissionSavedPennies = aggregatorCommissionPennies - jtoyeUsageFeePennies
  const netMonthlySavingsPennies = aggregatorCommissionPennies - jtoyeTotalCostPennies

  const settledSaleCount = transactions.filter(
    (tx) =>
      tx.entryKind === "SALE" ||
      (!tx.entryKind && tx.amountPennies > 0 && Boolean(tx.orderId))
  ).length
  const voidReversalCount = transactions.filter((tx) => tx.entryKind === "REVERSAL").length

  const denominator =
    settledSaleCount > 0
      ? settledSaleCount
      : summary.transactionCount > 0
        ? summary.transactionCount
        : 0
  const averageOrderValuePennies =
    denominator > 0 ? Math.round(directGtvPennies / denominator) : 0

  // Repeat Buyer % across non-cancelled, non-refunded, non-draft, non-voided orders
  const customerOrderCounts = new Map<string, number>()
  for (const order of orders) {
    if (
      order.status === "CANCELLED" ||
      order.status === "REFUNDED" ||
      order.status === "DRAFT" ||
      Boolean(order.voidedAt)
    ) {
      continue
    }
    const key = extractCustomerKey(order)
    if (!key) continue
    customerOrderCounts.set(key, (customerOrderCounts.get(key) ?? 0) + 1)
  }

  const identifiedCustomers = customerOrderCounts.size
  let repeatCustomers = 0
  for (const count of customerOrderCounts.values()) {
    if (count >= 2) repeatCustomers += 1
  }

  const repeatBuyerPercent =
    identifiedCustomers > 0 ? Math.round((repeatCustomers / identifiedCustomers) * 100) : null

  // V81 (37-27 / D-18) Cash-Order Abuse Protection: only contactVerified === false is unverified
  let unverifiedOrdersCount = 0
  let cancelledUnverifiedCount = 0
  for (const order of orders) {
    if (order.contactVerified === false) {
      unverifiedOrdersCount += 1
      if (order.status === "CANCELLED" || Boolean(order.voidedAt)) {
        cancelledUnverifiedCount += 1
      }
    }
  }

  // Upgrade crossover check for BATCH_PAYG_STARTER (£0/mo + 6% vs £39/mo + 0.5%)
  const hybridPreset = BILLING_PLAN_PRESETS.SAAS_HYBRID_STANDARD
  const hybridTotalCostPennies =
    hybridPreset.monthlyBasePennies +
    Math.round((directGtvPennies * hybridPreset.usageBps) / 10_000)
  const hybridUpgradeSavingsPennies = Math.max(0, jtoyeTotalCostPennies - hybridTotalCostPennies)
  const recommendHybridUpgrade =
    planMode === "BATCH_PAYG_STARTER" &&
    directGtvPennies > PAYG_UPGRADE_THRESHOLD_PENNIES &&
    hybridUpgradeSavingsPennies > 0

  return {
    plan,
    directGtvPennies,
    aggregatorCommissionPennies,
    jtoyeUsageFeePennies,
    jtoyeMonthlyBasePennies,
    jtoyeTotalCostPennies,
    grossCommissionSavedPennies,
    netMonthlySavingsPennies,
    settledSaleCount,
    voidReversalCount,
    averageOrderValuePennies,
    repeatBuyerStats: {
      identifiedCustomers,
      repeatCustomers,
      repeatBuyerPercent,
      totalSampledOrders: orders.length,
    },
    abuseProtectionStats: {
      unverifiedOrdersCount,
      cancelledUnverifiedCount,
      v81CapsLabel: "3/email · 3/phone · 5/IP",
    },
    recommendHybridUpgrade,
    hybridUpgradeSavingsPennies,
  }
}
