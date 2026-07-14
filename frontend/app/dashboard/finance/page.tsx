"use client"

import { useEffect, useState } from "react"
import { m } from "framer-motion"
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
  ShieldCheck,
} from "lucide-react"
import type {
  FinancialTransaction,
  FinancialSummary,
  VatRate,
} from "@/types/api"
import { formatDistanceToNow } from "date-fns"

const vatRateConfig: Record<VatRate, { label: string; rate: string; color: string }> = {
  STANDARD: { label: "Standard", rate: "20%", color: "bg-blue-500" },
  REDUCED: { label: "Reduced", rate: "5%", color: "bg-yellow-500" },
  ZERO: { label: "Zero", rate: "0%", color: "bg-green-500" },
  EXEMPT: { label: "Exempt", rate: "N/A", color: "bg-gray-500" },
}

const formatPennies = (pennies: number): string => {
  const pounds = pennies / 100
  return new Intl.NumberFormat("en-GB", {
    style: "currency",
    currency: "GBP",
  }).format(pounds)
}

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
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const { toast } = useToast()

  useEffect(() => {
    fetchData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

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
      setForbidden(false)
    } catch (error: unknown) {
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
      setLoading(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  // QA FE-2: honest admin-gate state — mirrors the Approvals "Admin access required"
  // card, instead of an empty "No transactions yet" table plus a red 403 error toast.
  if (forbidden) {
    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Finance</h1>
          <p className="mt-2 text-slate-600">Revenue, expenses, and VAT reporting</p>
        </div>
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

  return (
    <div className="space-y-6">
      {/* Header */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1 className="text-4xl font-bold text-slate-900">Finance</h1>
        <p className="mt-2 text-slate-600">
          Revenue, expenses, and VAT reporting
        </p>
      </m.div>

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
              <Receipt className="h-4 w-4 text-amber-600" />
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
              <div className="overflow-x-auto">
                <Table>
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
                      return (
                        <TableRow key={tx.id}>
                          <TableCell className="font-medium">
                            {tx.description || tx.id.substring(0, 8) + "..."}
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
      </m.div>
    </div>
  )
}
