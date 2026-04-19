"use client"

import { Suspense, useEffect, useState } from "react"
import { useSearchParams } from "next/navigation"
import { motion } from "framer-motion"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
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
} from "lucide-react"
import type {
  FinancialTransaction,
  FinancialSummary,
  VatRate,
} from "@/types/api"
import { formatDistanceToNow } from "date-fns"

type VatBadgeVariant = "info" | "warning" | "success" | "subtle"

const vatRateConfig: Record<VatRate, { label: string; rate: string; variant: VatBadgeVariant }> = {
  STANDARD: { label: "Standard", rate: "20%", variant: "info" },
  REDUCED: { label: "Reduced", rate: "5%", variant: "warning" },
  ZERO: { label: "Zero", rate: "0%", variant: "success" },
  EXEMPT: { label: "Exempt", rate: "N/A", variant: "subtle" },
}

const formatPennies = (pennies: number): string => {
  const pounds = pennies / 100
  return new Intl.NumberFormat("en-GB", {
    style: "currency",
    currency: "GBP",
  }).format(pounds)
}

const PAGE_SIZE = 20

function toCsv(rows: FinancialTransaction[]): string {
  const header = ["id", "description", "amountPennies", "vatRate", "vatAmountPennies", "createdAt"]
  const esc = (v: string) => `"${v.replace(/"/g, '""')}"`
  const lines = [header.join(",")]
  for (const tx of rows) {
    lines.push(
      [
        esc(tx.id),
        esc(tx.description || ""),
        String(tx.amountPennies ?? 0),
        esc(tx.vatRate),
        String(tx.vatAmountPennies ?? 0),
        esc(tx.createdAt),
      ].join(","),
    )
  }
  return lines.join("\n")
}

function FinancePageInner() {
  const searchParams = useSearchParams()
  const shouldExport = searchParams.get("export") === "1"
  const [summary, setSummary] = useState<FinancialSummary | null>(null)
  const [transactions, setTransactions] = useState<FinancialTransaction[]>([])
  const [loading, setLoading] = useState(true)
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [hasExported, setHasExported] = useState(false)
  const { toast } = useToast()

  useEffect(() => {
    fetchData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  // Honour ?export=1 — auto-trigger CSV download once transactions have loaded.
  useEffect(() => {
    if (!shouldExport || hasExported || loading || transactions.length === 0) return
    const csv = toCsv(transactions)
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `financial-transactions-page-${currentPage + 1}.csv`
    a.click()
    URL.revokeObjectURL(url)
    setHasExported(true)
  }, [shouldExport, hasExported, loading, transactions, currentPage])

  const fetchData = async () => {
    try {
      setLoading(true)
      const [summaryRes, txRes] = await Promise.all([
        apiClient.get("/api/v1/financial-transactions/summary"),
        apiClient.get(
          `/api/v1/financial-transactions?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`
        ),
      ])
      setSummary(summaryRes.data)
      setTransactions(txRes.data.content || [])
      setTotalPages(txRes.data.totalPages || 0)
      setTotalElements(txRes.data.totalElements || 0)
    } catch (error: unknown) {
      const errorMessage =
        error instanceof Error ? error.message : "Failed to load financial data"
      toast({
        variant: "destructive",
        title: "Error loading data",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-brand-primary motion-reduce:animate-none" aria-label="Loading"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1 className="font-display text-4xl font-semibold tracking-tight text-ink-primary">Finance</h1>
        <p className="mt-2 text-ink-secondary">
          Revenue, expenses, and VAT reporting
        </p>
      </motion.div>

      {/* Summary Cards */}
      {summary && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          className="grid gap-4 md:grid-cols-4"
        >
          <Card variant="lifted">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Revenue</CardTitle>
              <TrendingUp className="h-4 w-4 text-success" aria-hidden="true" />
            </CardHeader>
            <CardContent>
              <div className="font-display font-semibold text-3xl text-ink-primary tabular-nums">
                {formatPennies(summary.totalRevenuePennies)}
              </div>
            </CardContent>
          </Card>

          <Card variant="lifted">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Expenses</CardTitle>
              <TrendingDown className="h-4 w-4 text-danger" aria-hidden="true" />
            </CardHeader>
            <CardContent>
              <div className="font-display font-semibold text-3xl text-ink-primary tabular-nums">
                {formatPennies(summary.totalExpensesPennies)}
              </div>
            </CardContent>
          </Card>

          <Card variant="lifted">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Net</CardTitle>
              <Banknote className="h-4 w-4 text-info" aria-hidden="true" />
            </CardHeader>
            <CardContent>
              <div
                className={`font-display font-semibold text-3xl tabular-nums ${
                  summary.netAmountPennies >= 0
                    ? "text-success"
                    : "text-danger"
                }`}
              >
                {formatPennies(summary.netAmountPennies)}
              </div>
            </CardContent>
          </Card>

          <Card variant="lifted">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Total VAT</CardTitle>
              <Receipt className="h-4 w-4 text-accent-editorial" aria-hidden="true" />
            </CardHeader>
            <CardContent>
              <div className="font-display font-semibold text-3xl text-ink-primary tabular-nums">
                {formatPennies(summary.totalVatPennies)}
              </div>
              <p className="text-xs text-ink-tertiary mt-1">
                <span className="font-mono tabular-nums">{summary.transactionCount}</span> transaction{summary.transactionCount !== 1 ? "s" : ""}
              </p>
            </CardContent>
          </Card>
        </motion.div>
      )}

      {/* VAT Breakdown */}
      {summary && summary.vatBreakdown.length > 0 && (
        <motion.div
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
                      className="flex items-center gap-3 rounded-md border border-subtle bg-surface-card p-3"
                    >
                      <Badge variant={config.variant} size="sm">
                        {config.rate}
                      </Badge>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium text-ink-primary">{config.label}</p>
                        <p className="font-mono tabular-nums text-lg font-semibold text-ink-primary">
                          {formatPennies(vat.totalAmountPennies)}
                        </p>
                        <p className="text-xs text-ink-tertiary">
                          VAT:{" "}
                          <span className="font-mono tabular-nums">{formatPennies(vat.totalVatPennies)}</span>
                          {" "}(<span className="font-mono tabular-nums">{vat.count}</span> tx)
                        </p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        </motion.div>
      )}

      {/* Transactions Table */}
      <motion.div
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
                <Receipt className="mb-4 h-12 w-12 text-ink-tertiary" aria-hidden="true" />
                <h3 className="mb-2 font-display text-lg font-semibold text-ink-primary">
                  No transactions yet
                </h3>
                <p className="text-sm text-ink-tertiary">
                  Financial transactions are created automatically when orders
                  are completed.
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Reference</TableHead>
                      <TableHead className="text-right">Amount</TableHead>
                      <TableHead>VAT Rate</TableHead>
                      <TableHead className="text-right">VAT Amount</TableHead>
                      <TableHead>Created</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {transactions.map((tx) => {
                      const config = vatRateConfig[tx.vatRate]
                      return (
                        <TableRow key={tx.id}>
                          <TableCell className="font-medium">
                            {tx.description || tx.id.substring(0, 8) + "..."}
                          </TableCell>
                          <TableCell
                            numeric
                            className={
                              tx.amountPennies >= 0
                                ? "text-success font-semibold"
                                : "text-danger font-semibold"
                            }
                          >
                            {formatPennies(tx.amountPennies)}
                          </TableCell>
                          <TableCell>
                            <Badge variant={config.variant} size="sm">
                              {config.label} ({config.rate})
                            </Badge>
                          </TableCell>
                          <TableCell numeric>
                            {formatPennies(tx.vatAmountPennies)}
                          </TableCell>
                          <TableCell className="text-ink-secondary">
                            {formatDistanceToNow(new Date(tx.createdAt), {
                              addSuffix: true,
                            })}
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </div>
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
      </motion.div>
    </div>
  )
}

export default function FinancePage() {
  return (
    <Suspense
      fallback={
        <div className="flex h-full items-center justify-center">
          <div
            className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-brand-primary motion-reduce:animate-none"
            aria-label="Loading"
          ></div>
        </div>
      }
    >
      <FinancePageInner />
    </Suspense>
  )
}
