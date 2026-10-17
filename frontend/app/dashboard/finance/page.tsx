"use client"

import { useEffect, useRef, useState } from "react"
import { m } from "framer-motion"
import { useToast } from "@/hooks/use-toast"
import { useShopContext } from "@/hooks/use-shop-context"
import { useOptionalShopSwitcherData } from "@/components/dashboard/shop-switcher-provider"
import {
  CHOOSE_SHOP_BODY,
  CHOOSE_SHOP_HEADING,
  FinanceScopeText,
} from "@/components/dashboard/finance-scope-text"
import { VoidedBadge } from "@/components/dashboard/orders/voided-badge"
import { MerchantRoiCard } from "@/components/dashboard/merchant-roi-card"
import { Button } from "@/components/ui/button"
import {
  fetchFinanceExport,
  fetchFinanceOrdersSample,
  fetchFinanceSummary,
  fetchFinanceTransactions,
  financeGate,
  financeShopId,
  formatSignedPennies,
  queryFromScope,
  saveBlob,
  type FinanceQuery,
  type FinanceScope,
} from "@/lib/finance-api"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { Pagination } from "@/components/ui/pagination"
import {
  TrendingUp,
  TrendingDown,
  Banknote,
  Receipt,
  ShieldCheck,
  Store,
  Download,
} from "lucide-react"
import type {
  FinancialTransaction,
  FinancialSummary,
  Order,
  VatRate,
} from "@/types/api"
import { formatDistanceToNow } from "date-fns"

const vatRateConfig: Record<VatRate, { label: string; rate: string; color: string }> = {
  // A11Y-6 (QA council 20260902-134741): white text on blue-500 is 3.68:1 and
  // on green-500 2.28:1; blue-600 is 5.17, green-700 5.02. No token change.
  STANDARD: { label: "Standard", rate: "20%", color: "bg-blue-600" },
  // bg-yellow-700, not -500: white text on -500 is 1.92:1 on white — fails AA
  // (F3 / A11Y-1). -700 is 4.92:1.
  REDUCED: { label: "Reduced", rate: "5%", color: "bg-yellow-700" },
  ZERO: { label: "Zero", rate: "0%", color: "bg-green-700" },
  EXEMPT: { label: "Exempt", rate: "N/A", color: "bg-gray-500" },
}

// 37-12: every amount on this page goes through the one signed formatter, so a negative
// figure (a reversal, an expense, a negative net) carries a real minus sign (U+2212)
// rather than relying on red text (UI-SPEC § B5 "never colour alone").
const formatPennies = formatSignedPennies

const EXPORT_FAILED_COPY = "Couldn't export. Try again."
const EXPORT_EMPTY_COPY = "No transactions in this range"

const PAGE_SIZE = 20

function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status
  }
  return undefined
}

export default function FinancePage() {
  const [summary, setSummary] = useState<FinancialSummary | null>(null)
  const [transactions, setTransactions] = useState<FinancialTransaction[]>([])
  const [ordersSample, setOrdersSample] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  // The page number belongs to the scope it was chosen in: switching shop starts again
  // at page 0 instead of asking the new shop for a page it may not have.
  const [pageState, setPageState] = useState<{ shop: string | null; page: number }>({
    shop: null,
    page: 0,
  })
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  // 37-12: the scope the SERVER filtered on, from the response. The banner and the
  // export read this, never the switcher (say is not data, T-37-33).
  const [scope, setScope] = useState<FinanceScope | null>(null)
  const [lastQuery, setLastQuery] = useState<FinanceQuery>({})
  const [exporting, setExporting] = useState(false)
  const [exportFailed, setExportFailed] = useState(false)
  const [exportEmpty, setExportEmpty] = useState(false)
  const { toast } = useToast()

  const { contextShopId: selectedShopId } = useShopContext()
  const switcher = useOptionalShopSwitcherData()
  // The switcher's selection, or the one shop a single-grant manager is pinned to.
  const contextShopId = financeShopId(selectedShopId, switcher)
  const gate = financeGate(contextShopId, switcher)
  const currentPage = pageState.shop === contextShopId ? pageState.page : 0
  const setCurrentPage = (page: number) => setPageState({ shop: contextShopId, page })

  // Only the newest load may write state: the shop context hydrates after mount and a
  // manager can switch shop mid-flight, so an older, slower response must never paint
  // its scope over the one the switcher now asks for.
  const loadSeq = useRef(0)

  async function fetchData() {
    const seq = ++loadSeq.current
    const query: FinanceQuery = { shopId: contextShopId }
    try {
      setLoading(true)
      setExportFailed(false)
      setExportEmpty(false)
      const [summaryRes, txRes, ordersRes] = await Promise.all([
        fetchFinanceSummary(query),
        fetchFinanceTransactions(query, currentPage, PAGE_SIZE),
        fetchFinanceOrdersSample(query),
      ])
      if (seq !== loadSeq.current) return
      setSummary(summaryRes.summary)
      setTransactions(txRes.content)
      setOrdersSample(ordersRes)
      setTotalPages(txRes.totalPages)
      setTotalElements(txRes.totalElements)
      setScope(summaryRes.scope ?? txRes.scope)
      setLastQuery(query)
      setForbidden(false)
    } catch (error: unknown) {
      if (seq !== loadSeq.current) return
      // QA FE-2: finance is admin-gated. A 403 is an honest "access required" state,
      // not a data-load failure — surface the same card Approvals uses, and do NOT show
      // the contradictory empty "No transactions yet" table plus a red error toast.
      if (httpStatus(error) === 403) {
        setForbidden(true)
      } else {
        const errorMessage =
          error instanceof Error ? error.message : "Failed to load financial data"
        toast({
          variant: "destructive",
          title: "Error loading data",
          description: errorMessage,
        })
      }
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }

  useEffect(() => {
    // Only "fetch" asks the server. "wait" keeps the loading branch; the two refusals
    // render from `gate` below and request nothing (a manager is never sent, and never
    // asks for, tenant totals). Any older in-flight load is made stale.
    if (gate !== "fetch") {
      loadSeq.current++
      return
    }
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage, contextShopId, gate])

  async function handleExport() {
    // Exactly what is shown: the scope the server echoed (and its range), or, from an
    // older server with no echo, the query that produced these figures.
    const query = scope ? queryFromScope(scope) : lastQuery
    setExporting(true)
    setExportFailed(false)
    setExportEmpty(false)
    try {
      const { blob, filename } = await fetchFinanceExport(query)
      saveBlob(blob, filename)
      // An empty range is still a valid export (the server sends its header row); say
      // so, rather than leave the user wondering why the file is blank.
      if (totalElements === 0) setExportEmpty(true)
    } catch {
      setExportFailed(true)
      toast({
        variant: "destructive",
        title: "Export failed",
        description: EXPORT_FAILED_COPY,
      })
    } finally {
      setExporting(false)
    }
  }

  const header = (
    <div>
      <h1 className="text-4xl font-bold text-slate-900">Finance</h1>
      <p className="mt-2 text-slate-600">Revenue, expenses, and VAT reporting</p>
    </div>
  )

  /*
   * WIDTH TIER — Index, and this was a decision rather than a default.
   *
   * PATTERNS A-4. This surface holds two things that want opposite widths: the
   * VAT/transaction ledger, which is a wide horizontally-scrolling table, and the
   * revenue charts, which have a fixed aspect ratio and read as very wide, very
   * short plots when stretched. Index is chosen because the ledger is the reason
   * the page exists — capping the whole page to protect the charts would starve
   * exactly the surface this phase was opened to widen.
   *
   * If the charts do need protecting, the fix is ADDITIVE and local: cap the
   * chart band inside an Index page. It is deliberately NOT added here on a
   * hunch; plan 35-12's visual capture at 2560 decides whether it is needed, and
   * a speculative local cap would be a scattered width literal.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). It is declared on EVERY render branch below, not just
   * the loaded one: a branch without it is an undeclared first paint.
   *
   * COVERAGE BOUNDARY, stated rather than implied: this page has no mounted page
   * test, so none of the three declarations below is asserted in jsdom. They are
   * covered by plan 35-10's static gate and by a browser spec in plan 35-08 that
   * no current tree executes (#683 — the nightly lane is dark). Nothing on a
   * pull request exercises them.
   */
  if (gate === "wait" || (gate === "fetch" && loading)) {
    return (
      <div data-width-tier="index" className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  // QA FE-2: honest admin-gate state — mirrors the Approvals "Admin access required"
  // card, instead of an empty "No transactions yet" table plus a red 403 error toast.
  if (gate === "staff-refused" || (gate === "fetch" && forbidden)) {
    return (
      <div data-width-tier="index" className="space-y-6">
        {header}
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <ShieldCheck className="mb-4 h-12 w-12 text-slate-300" />
            <h3 className="mb-2 text-lg font-semibold text-slate-900">Admin access required</h3>
            <p className="text-sm text-slate-500">
              Viewing financial transactions needs the admin role. Ask your administrator for access.
            </p>
          </CardContent>
        </Card>
      </div>
    )
  }

  // UI-SPEC § B5 "Shop manager, All shops selected": no figures and no request for
  // them; tenant-wide totals stay Group-admin only (goods P2-TUN-15).
  if (gate === "choose-shop") {
    return (
      <div data-width-tier="index" className="space-y-6">
        {header}
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <Store className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
            <h2 className="mb-2 text-lg font-semibold text-slate-900">{CHOOSE_SHOP_HEADING}</h2>
            <p className="text-sm text-slate-500">{CHOOSE_SHOP_BODY}</p>
          </CardContent>
        </Card>
      </div>
    )
  }

  // A REVERSAL names the SALE it takes back out; that sale stays listed, marked Voided.
  const reversedSaleIds = new Set(
    transactions
      .filter((tx) => tx.entryKind === "REVERSAL" && tx.reversesTransactionId)
      .map((tx) => tx.reversesTransactionId as string)
  )

  return (
    <div data-width-tier="index" className="space-y-6">
      {/* Header, with Export CSV in the header row (UI-SPEC § B5 "Export") */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex flex-wrap items-start justify-between gap-4"
      >
        {header}
        <div className="flex flex-col items-start gap-2 sm:items-end">
          <Button
            type="button"
            variant="outline"
            className="h-11"
            onClick={handleExport}
            disabled={exporting}
          >
            <Download className="mr-2 h-4 w-4" aria-hidden="true" />
            Export CSV
          </Button>
          {exportFailed && (
            <p role="alert" className="text-sm font-medium text-red-700">
              {EXPORT_FAILED_COPY}
            </p>
          )}
          {exportEmpty && (
            <p role="status" className="text-sm text-slate-600">
              {EXPORT_EMPTY_COPY}
            </p>
          )}
        </div>
      </m.div>

      {/* Scope banner: the scope the API filtered on, never the switcher's own value */}
      {scope && (
        <p
          data-testid="finance-scope-banner"
          className="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm text-slate-700 [overflow-wrap:anywhere]"
        >
          <FinanceScopeText scope={scope} />
        </p>
      )}

      {/* Summary Cards */}
      {summary && (
        <m.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          className="grid gap-4 md:grid-cols-4"
        >
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Revenue</CardTitle>
              <TrendingUp className="h-4 w-4 text-green-600" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-green-700">
                {formatPennies(summary.totalRevenuePennies)}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Expenses</CardTitle>
              <TrendingDown className="h-4 w-4 text-red-600" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-red-700">
                {formatPennies(summary.totalExpensesPennies)}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Net</CardTitle>
              <Banknote className="h-4 w-4 text-blue-600" />
            </CardHeader>
            <CardContent>
              <div
                className={`text-2xl font-bold ${
                  summary.netAmountPennies >= 0
                    ? "text-green-700"
                    : "text-red-700"
                }`}
              >
                {formatPennies(summary.netAmountPennies)}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Total VAT</CardTitle>
              <Receipt className="h-4 w-4 text-amber-700" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-amber-700">
                {formatPennies(summary.totalVatPennies)}
              </div>
              <p className="text-xs text-slate-500 mt-1">
                {summary.transactionCount} transaction{summary.transactionCount !== 1 ? "s" : ""}
              </p>
            </CardContent>
          </Card>
        </m.div>
      )}

      {/* GAP-GTM-07: Merchant Value & ROI Telemetry (Direct GTV, 28% Aggregator Commission Saved, Repeat Buyers, V81 Cash Abuse Protection) */}
      {summary && (
        <m.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.15 }}
        >
          <MerchantRoiCard
            summary={summary}
            transactions={transactions}
            orders={ordersSample}
            scope={scope ?? { shopId: null, shopName: null, from: null, to: null }}
          />
        </m.div>
      )}

      {/* VAT Breakdown */}
      {summary && summary.vatBreakdown.length > 0 && (
        <m.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <Card>
            <CardHeader>
              <CardTitle>VAT Breakdown</CardTitle>
              <CardDescription>Revenue by VAT rate category</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {summary.vatBreakdown.map((vat) => {
                  const config = vatRateConfig[vat.vatRate]
                  return (
                    <div
                      key={vat.vatRate}
                      className="flex items-center gap-3 rounded-lg border p-3"
                    >
                      <Badge className={`${config.color} text-white`}>
                        {config.rate}
                      </Badge>
                      <div className="flex-1">
                        <p className="text-sm font-medium">{config.label}</p>
                        <p className="text-lg font-bold">
                          {formatPennies(vat.totalAmountPennies)}
                        </p>
                        <p className="text-xs text-slate-500">
                          VAT: {formatPennies(vat.totalVatPennies)} ({vat.count}{" "}
                          tx)
                        </p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        </m.div>
      )}

      {/* Transactions Table */}
      <m.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3 }}
      >
        <Card>
          <CardHeader>
            <CardTitle>Transactions</CardTitle>
            <CardDescription>
              {totalElements} transaction{totalElements !== 1 ? "s" : ""} in
              total
            </CardDescription>
          </CardHeader>
          <CardContent>
            {transactions.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Receipt className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No transactions yet
                </h3>
                <p className="text-sm text-slate-500">
                  Financial transactions are created automatically when orders
                  are completed.
                </p>
              </div>
            ) : (
              <Table containerLabel="Financial transactions table">
                <TableHeader>
                  <TableRow>
                    <TableHead>Reference</TableHead>
                    <TableHead>Amount</TableHead>
                    <TableHead>VAT Rate</TableHead>
                    <TableHead>VAT Amount</TableHead>
                    <TableHead>Created</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {transactions.map((tx) => {
                    const config = vatRateConfig[tx.vatRate]
                    const isReversal = tx.entryKind === "REVERSAL"
                    const isVoidedSale = tx.entryKind === "SALE" && reversedSaleIds.has(tx.id)
                    // The server writes a reversal's reference as "Reversal of {order number}".
                    const reference =
                      tx.description ||
                      (isReversal ? "Reversal of an earlier sale" : tx.id.substring(0, 8) + "...")
                    return (
                      <TableRow key={tx.id}>
                        <TableCell className="font-medium">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="[overflow-wrap:anywhere]">{reference}</span>
                            {isReversal && <Badge variant="outline">Reversal</Badge>}
                            {isVoidedSale && <VoidedBadge />}
                          </div>
                        </TableCell>
                        <TableCell
                          className={`font-semibold ${
                            tx.amountPennies >= 0
                              ? "text-green-700"
                              : "text-red-700"
                          }`}
                        >
                          {formatPennies(tx.amountPennies)}
                        </TableCell>
                        <TableCell>
                          <Badge
                            className={`${config.color} text-white text-xs`}
                          >
                            {config.label} ({config.rate})
                          </Badge>
                        </TableCell>
                        <TableCell>
                          {formatPennies(tx.vatAmountPennies)}
                        </TableCell>
                        <TableCell className="text-slate-600">
                          {formatDistanceToNow(new Date(tx.createdAt), {
                            addSuffix: true,
                          })}
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            )}
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={PAGE_SIZE}
              onPageChange={setCurrentPage}
            />
          </CardContent>
        </Card>
      </m.div>
    </div>
  )
}
