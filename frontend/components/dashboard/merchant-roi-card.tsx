"use client"

import { useState } from "react"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import {
  BILLING_PLAN_PRESETS,
  computeMerchantRoiTelemetry,
  type BillingPlanMode,
} from "@/lib/finance-roi"
import { formatSignedPennies, type FinanceScope } from "@/lib/finance-api"
import type { FinancialSummary, FinancialTransaction, Order } from "@/types/api"
import {
  PiggyBank,
  Repeat,
  ShieldAlert,
  Sparkles,
  TrendingUp,
} from "lucide-react"

export interface MerchantRoiCardProps {
  summary: FinancialSummary
  transactions: FinancialTransaction[]
  orders?: Order[]
  scope: FinanceScope
  defaultPlanMode?: BillingPlanMode
  /** Number of active locations when viewing tenant-wide ("All shops") totals (D-04, #1011). */
  locationCount?: number
}

const PLAN_ORDER: BillingPlanMode[] = [
  "SAAS_HYBRID_STANDARD",
  "SAAS_FLAT_PREDICTABLE",
  "BATCH_PAYG_STARTER",
]

export function MerchantRoiCard({
  summary,
  transactions,
  orders = [],
  scope,
  defaultPlanMode = "SAAS_HYBRID_STANDARD",
  locationCount,
}: MerchantRoiCardProps) {
  const [planMode, setPlanMode] = useState<BillingPlanMode>(defaultPlanMode)

  const effectiveLocationCount = scope.shopId ? 1 : locationCount
  const roi = computeMerchantRoiTelemetry(
    summary,
    transactions,
    orders,
    planMode,
    effectiveLocationCount
  )
  const scopeLabel = scope.shopName || "All shops"

  return (
    <Card
      data-testid="merchant-roi-card"
      className="border-emerald-200/80 bg-gradient-to-br from-emerald-50/40 via-background to-background dark:border-emerald-900/60 dark:from-emerald-950/20"
    >
      <CardHeader className="pb-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle className="text-lg font-semibold flex items-center gap-2">
                <PiggyBank className="h-5 w-5 text-emerald-700 dark:text-emerald-400" />
                What selling direct saves you
              </CardTitle>
              <Badge
                variant="outline"
                className="border-emerald-700/30 bg-emerald-100/80 text-emerald-900 dark:border-emerald-500/40 dark:bg-emerald-950/60 dark:text-emerald-200"
              >
                Scope: {scopeLabel}
              </Badge>
            </div>
            <CardDescription className="text-sm text-muted-foreground">
              An estimate: what these sales would have cost you on a delivery app that takes
              28% commission (Uber Eats, Deliveroo, Just Eat), against what J&apos;Toye costs.
            </CardDescription>
          </div>

          <div
            role="group"
            aria-label="Your J'Toye plan"
            className="inline-flex flex-wrap items-center gap-1 rounded-lg border bg-muted/60 p-1"
          >
            {PLAN_ORDER.map((mode) => {
              const preset = BILLING_PLAN_PRESETS[mode]
              const active = planMode === mode
              return (
                <button
                  key={mode}
                  type="button"
                  data-testid={`roi-plan-${mode}`}
                  aria-pressed={active}
                  onClick={() => setPlanMode(mode)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${
                    active
                      ? "bg-emerald-800 text-white shadow-xs dark:bg-emerald-700"
                      : "text-foreground/80 hover:bg-background/80 hover:text-foreground"
                  }`}
                >
                  {preset.shortLabel}
                </button>
              )
            })}
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          {/* 1. Sales through J'Toye (settled; SW-ADE-13 plain wording) */}
          <div
            data-testid="roi-direct-gtv"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>Sales through J&apos;Toye</span>
              <TrendingUp className="h-4 w-4 text-emerald-700 dark:text-emerald-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {formatSignedPennies(roi.directGtvPennies)}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {roi.averageOrderValuePennies > 0
                ? `About ${formatSignedPennies(roi.averageOrderValuePennies)} a sale`
                : "No paid sales in this period"}
              {roi.voidReversalCount > 0
                ? ` · ${roi.voidReversalCount} voided sale${roi.voidReversalCount === 1 ? "" : "s"} taken off`
                : ""}
            </p>
          </div>

          {/* 2. Commission not paid (estimate against a 28% delivery-app commission) */}
          <div
            data-testid="roi-commission-saved"
            className="rounded-lg border border-emerald-300/70 bg-emerald-50/60 p-4 shadow-2xs dark:border-emerald-800/70 dark:bg-emerald-950/30"
          >
            <div className="flex items-center justify-between text-xs font-medium text-emerald-900 dark:text-emerald-200">
              <span>Commission you didn&apos;t pay</span>
              <Sparkles className="h-4 w-4 text-emerald-800 dark:text-emerald-300" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight text-emerald-900 dark:text-emerald-100">
              {formatSignedPennies(roi.grossCommissionSavedPennies)}
            </div>
            <p
              data-testid="roi-net-savings"
              className="mt-1 text-xs font-medium text-emerald-900/90 dark:text-emerald-200/90"
            >
              A delivery app would take {formatSignedPennies(roi.aggregatorCommissionPennies)}. You
              keep{" "}
              <span className="font-semibold">
                {formatSignedPennies(roi.netMonthlySavingsPennies)}
              </span>{" "}
              after your {roi.plan.shortLabel} plan
              {roi.activeLocationCount > 1 && roi.plan.monthlyBasePennies > 0
                ? ` (${roi.activeLocationCount} locations)`
                : ""}
            </p>
          </div>

          {/* 3. Customers who ordered again */}
          <div
            data-testid="roi-repeat-buyers"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>Customers who came back</span>
              <Repeat className="h-4 w-4 text-blue-700 dark:text-blue-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {roi.repeatBuyerStats.repeatBuyerPercent !== null
                ? `${roi.repeatBuyerStats.repeatBuyerPercent}%`
                : "—"}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {roi.repeatBuyerStats.identifiedCustomers > 0
                ? `${roi.repeatBuyerStats.repeatCustomers} of ${roi.repeatBuyerStats.identifiedCustomers} customers ordered again (from your last ${roi.repeatBuyerStats.totalSampledOrders} orders)`
                : "No named customers in your recent orders yet"}
            </p>
          </div>

          {/* 4. Cash-order protection (V81 caps) */}
          <div
            data-testid="roi-v81-protection"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>Unconfirmed cash orders cancelled</span>
              <ShieldAlert className="h-4 w-4 text-amber-700 dark:text-amber-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {roi.abuseProtectionStats.cancelledUnverifiedCount}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {roi.abuseProtectionStats.unverifiedOrdersCount} recent order
              {roi.abuseProtectionStats.unverifiedOrdersCount === 1 ? "" : "s"} had unconfirmed contact
              details · Limit per customer: {roi.abuseProtectionStats.v81CapsLabel}
            </p>
          </div>
        </div>

        {roi.recommendHybridUpgrade ? (
          <div
            data-testid="roi-hybrid-upgrade-tip"
            role="status"
            className="flex flex-col gap-2 rounded-lg border border-amber-300 bg-amber-50/90 px-4 py-3 text-xs text-amber-950 sm:flex-row sm:items-center sm:justify-between dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100"
          >
            <div>
              <span className="font-semibold">You could save more:</span> your sales are over
              £710.00 a month, so the £39/mo + 0.5% plan would cost you{" "}
              <span className="font-semibold">
                {formatSignedPennies(roi.hybridUpgradeSavingsPennies)}/mo
              </span>{" "}
              less than £0/mo + 6%.
            </div>
            <button
              type="button"
              onClick={() => setPlanMode("SAAS_HYBRID_STANDARD")}
              className="shrink-0 rounded-md bg-amber-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-800 dark:bg-amber-700 dark:hover:bg-amber-600"
            >
              Show the £39/mo + 0.5% plan
            </button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}
