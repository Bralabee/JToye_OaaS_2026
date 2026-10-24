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
                Merchant Value &amp; ROI Telemetry
              </CardTitle>
              <Badge
                variant="outline"
                className="border-emerald-700/30 bg-emerald-100/80 text-emerald-900 dark:border-emerald-500/40 dark:bg-emerald-950/60 dark:text-emerald-200"
              >
                Scope: {scopeLabel}
              </Badge>
            </div>
            <CardDescription className="text-sm text-muted-foreground">
              Direct ordering margin retained vs. 28% UK third-party aggregator commission
              benchmark (Uber Eats / Deliveroo / Just Eat).
            </CardDescription>
          </div>

          <div
            role="group"
            aria-label="J'Toye commercial billing plan model"
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
          {/* 1. Direct GTV */}
          <div
            data-testid="roi-direct-gtv"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>Direct GTV (Settled Sales)</span>
              <TrendingUp className="h-4 w-4 text-emerald-700 dark:text-emerald-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {formatSignedPennies(roi.directGtvPennies)}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {roi.averageOrderValuePennies > 0
                ? `Avg ${formatSignedPennies(roi.averageOrderValuePennies)}/sale`
                : "No settled sales in range"}
              {roi.voidReversalCount > 0
                ? ` · ${roi.voidReversalCount} void reversal${roi.voidReversalCount === 1 ? "" : "s"} netted`
                : ""}
            </p>
          </div>

          {/* 2. Est. Commission Saved vs. 28% Aggregator */}
          <div
            data-testid="roi-commission-saved"
            className="rounded-lg border border-emerald-300/70 bg-emerald-50/60 p-4 shadow-2xs dark:border-emerald-800/70 dark:bg-emerald-950/30"
          >
            <div className="flex items-center justify-between text-xs font-medium text-emerald-900 dark:text-emerald-200">
              <span>Variable Commission Saved</span>
              <Sparkles className="h-4 w-4 text-emerald-800 dark:text-emerald-300" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight text-emerald-900 dark:text-emerald-100">
              {formatSignedPennies(roi.grossCommissionSavedPennies)}
            </div>
            <p
              data-testid="roi-net-savings"
              className="mt-1 text-xs font-medium text-emerald-900/90 dark:text-emerald-200/90"
            >
              Net vs 28% aggregator ({formatSignedPennies(roi.aggregatorCommissionPennies)}):{" "}
              <span className="font-semibold">
                {formatSignedPennies(roi.netMonthlySavingsPennies)}
              </span>{" "}
              after {roi.plan.shortLabel}
              {roi.activeLocationCount > 1 && roi.plan.monthlyBasePennies > 0
                ? ` (${roi.activeLocationCount} locations)`
                : ""}
            </p>
          </div>

          {/* 3. Repeat Buyer Rate */}
          <div
            data-testid="roi-repeat-buyers"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>Repeat Buyer Rate (Sample)</span>
              <Repeat className="h-4 w-4 text-blue-700 dark:text-blue-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {roi.repeatBuyerStats.repeatBuyerPercent !== null
                ? `${roi.repeatBuyerStats.repeatBuyerPercent}%`
                : "—"}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {roi.repeatBuyerStats.identifiedCustomers > 0
                ? `${roi.repeatBuyerStats.repeatCustomers} of ${roi.repeatBuyerStats.identifiedCustomers} repeat buyers (${roi.repeatBuyerStats.totalSampledOrders} recent orders)`
                : "No identified buyers in sample"}
            </p>
          </div>

          {/* 4. V81 Cash-Order Abuse Protection */}
          <div
            data-testid="roi-v81-protection"
            className="rounded-lg border bg-card p-4 shadow-2xs"
          >
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>V81 Cash-Order Protection</span>
              <ShieldAlert className="h-4 w-4 text-amber-700 dark:text-amber-400" />
            </div>
            <div className="mt-2 text-2xl font-bold tracking-tight">
              {roi.abuseProtectionStats.cancelledUnverifiedCount}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Cancelled unverified orders ({roi.abuseProtectionStats.unverifiedOrdersCount}{" "}
              unverified in sample) · Caps: {roi.abuseProtectionStats.v81CapsLabel}
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
              <span className="font-semibold">Plan optimization notice:</span> Your Direct
              GTV exceeds £710.00/mo. Switching from Starter PAYG (6%) to Hybrid Standard
              (£39/mo + 0.5%) saves an extra{" "}
              <span className="font-semibold">
                {formatSignedPennies(roi.hybridUpgradeSavingsPennies)}/mo
              </span>
              .
            </div>
            <button
              type="button"
              onClick={() => setPlanMode("SAAS_HYBRID_STANDARD")}
              className="shrink-0 rounded-md bg-amber-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-800 dark:bg-amber-700 dark:hover:bg-amber-600"
            >
              Preview Hybrid (£39/mo + 0.5%)
            </button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}
