"use client"

import { useEffect, useState } from "react"
import { motion } from "framer-motion"
import apiClient from "@/lib/api-client"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { useToast } from "@/hooks/use-toast"
import {
  Store,
  Package,
  ShoppingCart,
  Users,
  TrendingUp,
  Clock,
  CheckCircle2,
  XCircle,
  RefreshCcw,
} from "lucide-react"
import type { Order, OrderStatus, FinancialSummary } from "@/types/api"
import { formatDistanceToNow } from "date-fns"
import {
  PieChart,
  Pie,
  Cell,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts"

interface DashboardStats {
  shops: number
  products: number
  orders: number
  customers: number
}

const statusConfig: Record<
  OrderStatus,
  { label: string; color: string; chartColor: string; icon: React.ComponentType<{ className?: string }> }
> = {
  DRAFT: { label: "Draft", color: "bg-gray-500", chartColor: "#6b7280", icon: Clock },
  PENDING: { label: "Pending", color: "bg-yellow-500", chartColor: "#eab308", icon: Clock },
  CONFIRMED: { label: "Confirmed", color: "bg-blue-500", chartColor: "#3b82f6", icon: CheckCircle2 },
  PREPARING: { label: "Preparing", color: "bg-purple-500", chartColor: "#a855f7", icon: Clock },
  READY: { label: "Ready", color: "bg-green-500", chartColor: "#22c55e", icon: CheckCircle2 },
  COMPLETED: { label: "Completed", color: "bg-emerald-600", chartColor: "#059669", icon: CheckCircle2 },
  CANCELLED: { label: "Cancelled", color: "bg-red-500", chartColor: "#ef4444", icon: XCircle },
  // Phase 17-04: REFUNDED is a new terminal state — orange keeps it within
  // the existing food-delivery palette (per `feedback_design_direction.md`).
  REFUNDED: { label: "Refunded", color: "bg-orange-500", chartColor: "#f97316", icon: RefreshCcw },
}

const vatRateLabels: Record<string, { label: string; color: string }> = {
  STANDARD: { label: "Standard (20%)", color: "#3b82f6" },
  REDUCED: { label: "Reduced (5%)", color: "#eab308" },
  ZERO: { label: "Zero (0%)", color: "#22c55e" },
  EXEMPT: { label: "Exempt", color: "#6b7280" },
}

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [recentOrders, setRecentOrders] = useState<Order[]>([])
  const [allOrders, setAllOrders] = useState<Order[]>([])
  const [financialSummary, setFinancialSummary] = useState<FinancialSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const { toast } = useToast()

  useEffect(() => {
    fetchDashboardData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchDashboardData = async () => {
    try {
      setLoading(true)

      const [shopsRes, productsRes, ordersRes, customersRes, recentOrdersRes, allOrdersRes, finSummaryRes] =
        await Promise.all([
          apiClient.get("/api/v1/shops?size=1"),
          apiClient.get("/api/v1/products?size=1"),
          apiClient.get("/api/v1/orders?size=1"),
          apiClient.get("/api/v1/customers?size=1"),
          apiClient.get("/api/v1/orders?size=10&sort=createdAt,desc"),
          // Server enforces a max page size of 100 (Issue #95) — the previous
          // size=200 was silently over-asking. Chart the 100 most recent orders.
          apiClient.get("/api/v1/orders?size=100&sort=createdAt,desc"),
          apiClient.get("/api/v1/financial-transactions/summary").catch(() => ({ data: null })),
        ])

      setStats({
        shops: shopsRes.data.totalElements || 0,
        products: productsRes.data.totalElements || 0,
        orders: ordersRes.data.totalElements || 0,
        customers: customersRes.data.totalElements || 0,
      })

      setRecentOrders(recentOrdersRes.data.content || [])
      setAllOrders(allOrdersRes.data.content || [])
      setFinancialSummary(finSummaryRes.data)
    } catch (error: unknown) {
      toast({
        variant: "destructive",
        title: "Error loading dashboard",
        description: error instanceof Error ? error.message : "Failed to load dashboard data",
      })
    } finally {
      setLoading(false)
    }
  }

  // Compute order status distribution
  const statusDistribution = Object.entries(
    allOrders.reduce<Record<string, number>>((acc, order) => {
      acc[order.status] = (acc[order.status] || 0) + 1
      return acc
    }, {})
  ).map(([status, count]) => ({
    name: statusConfig[status as OrderStatus]?.label || status,
    value: count,
    color: statusConfig[status as OrderStatus]?.chartColor || "#6b7280",
  }))

  // Compute VAT breakdown for bar chart
  const vatChartData = financialSummary?.vatBreakdown.map((vat) => ({
    name: vatRateLabels[vat.vatRate]?.label || vat.vatRate,
    revenue: vat.totalAmountPennies / 100,
    vat: vat.totalVatPennies / 100,
    color: vatRateLabels[vat.vatRate]?.color || "#6b7280",
  })) || []

  const containerVariants = {
    hidden: { opacity: 0 },
    visible: {
      opacity: 1,
      transition: { staggerChildren: 0.1 },
    },
  }

  const itemVariants = {
    hidden: { opacity: 0, y: 20 },
    visible: { opacity: 1, y: 0 },
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  const statCards = [
    { title: "Shops", value: stats?.shops || 0, icon: Store, color: "text-blue-600", bgColor: "bg-blue-100" },
    { title: "Products", value: stats?.products || 0, icon: Package, color: "text-purple-600", bgColor: "bg-purple-100" },
    { title: "Orders", value: stats?.orders || 0, icon: ShoppingCart, color: "text-green-600", bgColor: "bg-green-100" },
    { title: "Customers", value: stats?.customers || 0, icon: Users, color: "text-orange-600", bgColor: "bg-orange-100" },
  ]

  return (
    <div className="space-y-8">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <h1 className="text-4xl font-bold text-slate-900">Dashboard</h1>
        <p className="mt-2 text-slate-600">
          Welcome to your J&apos;Toye OaaS management dashboard
        </p>
      </motion.div>

      {/* Stats Cards */}
      <motion.div
        variants={containerVariants}
        initial="hidden"
        animate="visible"
        className="grid gap-6 md:grid-cols-2 lg:grid-cols-4"
      >
        {statCards.map((stat) => (
          <motion.div key={stat.title} variants={itemVariants}>
            <Card className="overflow-hidden transition-all hover:shadow-lg">
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-sm font-medium text-slate-600">
                  {stat.title}
                </CardTitle>
                <div className={`rounded-lg p-2 ${stat.bgColor}`}>
                  <stat.icon className={`h-5 w-5 ${stat.color}`} />
                </div>
              </CardHeader>
              <CardContent>
                <div className="flex items-baseline gap-2">
                  <div className="text-3xl font-bold text-slate-900">
                    {stat.value}
                  </div>
                  <div className="flex items-center text-sm text-green-600">
                    <TrendingUp className="mr-1 h-4 w-4" />
                    Active
                  </div>
                </div>
              </CardContent>
            </Card>
          </motion.div>
        ))}
      </motion.div>

      {/* Charts Row */}
      <div className="grid gap-6 lg:grid-cols-2">
        {/* Order Status Distribution */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.3 }}
        >
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Order Status Distribution</CardTitle>
              <CardDescription>Breakdown of orders by current status</CardDescription>
            </CardHeader>
            <CardContent>
              {statusDistribution.length === 0 ? (
                <p className="py-8 text-center text-sm text-slate-500">No orders to display</p>
              ) : (
                <ResponsiveContainer width="100%" height={250}>
                  <PieChart>
                    <Pie
                      data={statusDistribution}
                      cx="50%"
                      cy="50%"
                      innerRadius={60}
                      outerRadius={100}
                      paddingAngle={2}
                      dataKey="value"
                    >
                      {statusDistribution.map((entry, index) => (
                        <Cell key={index} fill={entry.color} />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(value) => [`${value} order${value !== 1 ? "s" : ""}`, ""]}
                    />
                    <Legend />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </CardContent>
          </Card>
        </motion.div>

        {/* Revenue by VAT Rate */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.4 }}
        >
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Revenue by VAT Category</CardTitle>
              <CardDescription>
                {financialSummary
                  ? `Total: £${(financialSummary.totalRevenuePennies / 100).toFixed(2)} revenue, £${(financialSummary.totalVatPennies / 100).toFixed(2)} VAT`
                  : "No financial data yet"}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {vatChartData.length === 0 ? (
                <p className="py-8 text-center text-sm text-slate-500">No transactions to display</p>
              ) : (
                <ResponsiveContainer width="100%" height={250}>
                  <BarChart data={vatChartData}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                    <YAxis tick={{ fontSize: 12 }} tickFormatter={(v) => `£${v}`} />
                    <Tooltip formatter={(value) => [`£${Number(value).toFixed(2)}`, ""]} />
                    <Legend />
                    <Bar dataKey="revenue" name="Revenue" fill="#3b82f6" radius={[4, 4, 0, 0]} />
                    <Bar dataKey="vat" name="VAT" fill="#a855f7" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </CardContent>
          </Card>
        </motion.div>
      </div>

      {/* Recent Orders */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, delay: 0.5 }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-xl">Recent Orders</CardTitle>
          </CardHeader>
          <CardContent>
            {recentOrders.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <ShoppingCart className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No orders yet
                </h3>
                <p className="text-sm text-slate-500">
                  Orders will appear here once they are created
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-slate-200 text-left text-sm font-medium text-slate-600">
                      <th className="pb-3">Order ID</th>
                      <th className="pb-3">Customer</th>
                      <th className="pb-3">Status</th>
                      <th className="pb-3">Total</th>
                      <th className="pb-3">Created</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {recentOrders.map((order) => {
                      const StatusIcon = statusConfig[order.status].icon
                      return (
                        <motion.tr
                          key={order.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="text-sm transition-colors hover:bg-slate-50"
                        >
                          <td className="py-4 font-mono text-xs text-slate-600">
                            {order.id.substring(0, 8)}...
                          </td>
                          <td className="py-4">
                            <div className="font-medium text-slate-900">
                              {order.customerName || "N/A"}
                            </div>
                            {order.customerEmail && (
                              <div className="text-xs text-slate-500">
                                {order.customerEmail}
                              </div>
                            )}
                          </td>
                          <td className="py-4">
                            <Badge
                              className={`${
                                statusConfig[order.status].color
                              } flex w-fit items-center gap-1 text-white`}
                            >
                              <StatusIcon className="h-3 w-3" />
                              {statusConfig[order.status].label}
                            </Badge>
                          </td>
                          <td className="py-4 font-semibold text-slate-900">
                            £{((order.totalAmountPennies || 0) / 100).toFixed(2)}
                          </td>
                          <td className="py-4 text-slate-600">
                            {formatDistanceToNow(new Date(order.createdAt), {
                              addSuffix: true,
                            })}
                          </td>
                        </motion.tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </motion.div>
    </div>
  )
}
