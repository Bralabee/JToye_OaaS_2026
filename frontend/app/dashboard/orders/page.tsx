"use client"

import { useEffect, useState, Suspense } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import { motion } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import { fetchEventSource } from "@microsoft/fetch-event-source"
import { getSession } from "next-auth/react"
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import {
  ShoppingCart,
  Plus,
  ArrowRight,
  CheckCircle2,
  XCircle,
  Clock,
  ChefHat,
  Package as PackageIcon,
  FileCheck,
  Ban,
  RefreshCcw,
} from "lucide-react"
import { Pagination } from "@/components/ui/pagination"
import type { Order, OrderDetail, OrderStatus, Shop, Product } from "@/types/api"
import { formatDistanceToNow, format } from "date-fns"
import { Trash2 } from "lucide-react"

const orderSchema = z.object({
  shopId: z.string().min(1, "Shop is required"),
  customerName: z.string().min(1, "Customer name is required").max(100),
  customerEmail: z.string().email("Invalid email").max(255),
  customerPhone: z.string().max(20).optional(),
})

type OrderFormData = z.infer<typeof orderSchema>

const statusConfig: Record<
  OrderStatus,
  { label: string; color: string; bgColor: string; icon: React.ComponentType<{ className?: string }> }
> = {
  DRAFT: {
    label: "Draft",
    color: "text-gray-700",
    bgColor: "bg-gray-500",
    icon: Clock,
  },
  PENDING: {
    label: "Pending",
    color: "text-yellow-700",
    bgColor: "bg-yellow-500",
    icon: Clock,
  },
  CONFIRMED: {
    label: "Confirmed",
    color: "text-blue-700",
    bgColor: "bg-blue-500",
    icon: CheckCircle2,
  },
  PREPARING: {
    label: "Preparing",
    color: "text-amber-700",
    bgColor: "bg-amber-500",
    icon: ChefHat,
  },
  READY: {
    label: "Ready",
    color: "text-green-700",
    bgColor: "bg-green-500",
    icon: PackageIcon,
  },
  COMPLETED: {
    label: "Completed",
    color: "text-emerald-700",
    bgColor: "bg-emerald-600",
    icon: FileCheck,
  },
  CANCELLED: {
    label: "Cancelled",
    color: "text-red-700",
    bgColor: "bg-red-500",
    icon: XCircle,
  },
  REFUNDED: {
    // Phase 17-04: REFUNDED is the new terminal state for orders that have
    // had at least one Stripe refund issued. Orange badge keeps it within
    // the existing food-delivery palette (per `feedback_design_direction.md`).
    label: "Refunded",
    color: "text-orange-700",
    bgColor: "bg-orange-500",
    icon: RefreshCcw,
  },
}

const statusFlow: OrderStatus[] = [
  "DRAFT",
  "PENDING",
  "CONFIRMED",
  "PREPARING",
  "READY",
  "COMPLETED",
]

interface StateTransition {
  action: string
  endpoint: string
  nextStatus: OrderStatus
  icon: React.ComponentType<{ className?: string }>
  color: string
}

const getAvailableTransitions = (
  currentStatus: OrderStatus
): StateTransition[] => {
  const transitions: Record<OrderStatus, StateTransition[]> = {
    DRAFT: [
      {
        action: "Submit",
        endpoint: "submit",
        nextStatus: "PENDING",
        icon: ArrowRight,
        color: "bg-yellow-600 hover:bg-yellow-700",
      },
    ],
    PENDING: [
      {
        action: "Confirm",
        endpoint: "confirm",
        nextStatus: "CONFIRMED",
        icon: CheckCircle2,
        color: "bg-blue-600 hover:bg-blue-700",
      },
      {
        action: "Cancel",
        endpoint: "cancel",
        nextStatus: "CANCELLED",
        icon: Ban,
        color: "bg-red-600 hover:bg-red-700",
      },
    ],
    CONFIRMED: [
      {
        action: "Start Prep",
        endpoint: "start-preparation",
        nextStatus: "PREPARING",
        icon: ChefHat,
        color: "bg-amber-600 hover:bg-amber-700",
      },
      {
        action: "Cancel",
        endpoint: "cancel",
        nextStatus: "CANCELLED",
        icon: Ban,
        color: "bg-red-600 hover:bg-red-700",
      },
    ],
    PREPARING: [
      {
        action: "Mark Ready",
        endpoint: "mark-ready",
        nextStatus: "READY",
        icon: PackageIcon,
        color: "bg-green-600 hover:bg-green-700",
      },
    ],
    READY: [
      {
        action: "Complete",
        endpoint: "complete",
        nextStatus: "COMPLETED",
        icon: FileCheck,
        color: "bg-emerald-600 hover:bg-emerald-700",
      },
    ],
    COMPLETED: [],
    CANCELLED: [],
    // REFUNDED is a terminal state — no further transitions from the UI.
    // Refunds are issued via the detail page's RefundDialog, not as a row
    // action on the list.
    REFUNDED: [],
  }
  return transitions[currentStatus] || []
}

const PAGE_SIZE = 20

function OrdersPageInner() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const customerIdParam = searchParams.get("customer")
  const [orders, setOrders] = useState<Order[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<string>("ALL")
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [processingOrderId, setProcessingOrderId] = useState<string | null>(null)
  const [orderItems, setOrderItems] = useState<{ productId: string; quantity: number }[]>([])
  // Phase 17-04: the inline detail Dialog is preserved for v2.2 per
  // 17-CONTEXT but is no longer reachable from the UI — row clicks now
  // navigate to /dashboard/orders/[id] (the dedicated detail route). The
  // setters below are retained so the existing modal JSX still type-checks
  // and so a follow-up cleanup phase can delete the modal without touching
  // unrelated code.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [detailDialogOpen, setDetailDialogOpen] = useState(false)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [selectedOrderDetail, setSelectedOrderDetail] = useState<OrderDetail | null>(null)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [detailLoading, setDetailLoading] = useState(false)
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
    watch,
  } = useForm<OrderFormData>({
    resolver: zodResolver(orderSchema),
  })

  const selectedShopId = watch("shopId")

  useEffect(() => {
    fetchData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  // Real-time updates via SSE. EventSource cannot attach the
  // Authorization header so we use fetchEventSource which runs on fetch()
  // and supports auth headers identically to the rest of the API client.
  useEffect(() => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
    const abortCtrl = new AbortController()
    ;(async () => {
      const session = await getSession()
      if (!session?.accessToken) return
      try {
        await fetchEventSource(`${apiUrl}/api/v1/orders/stream`, {
          signal: abortCtrl.signal,
          headers: { Authorization: `Bearer ${session.accessToken}` },
          openWhenHidden: true,
          onmessage: (ev) => {
            if (ev.event === "order-state-change") fetchData()
          },
          onerror: (err) => {
            throw err
          },
        })
      } catch {
        // Connection closed or failed — give up silently on this page.
      }
    })()
    return () => abortCtrl.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchData = async () => {
    try {
      setLoading(true)
      // Issue #95: /orders/customer/{id} is paginated now (Page response, same
      // shape as /orders) — the old bare-array special case is gone.
      const ordersPromise = customerIdParam
        ? apiClient.get(`/api/v1/orders/customer/${customerIdParam}?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`)
        : apiClient.get(`/api/v1/orders?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`)
      const [ordersRes, shopsRes, productsRes] = await Promise.all([
        ordersPromise,
        apiClient.get("/api/v1/shops?size=100"),
        apiClient.get("/api/v1/products?size=100"),
      ])
      setOrders(ordersRes.data.content || [])
      setTotalPages(ordersRes.data.totalPages || 0)
      setTotalElements(ordersRes.data.totalElements || 0)
      setShops(shopsRes.data.content || [])
      setProducts(productsRes.data.content || [])
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load orders"
      toast({
        variant: "destructive",
        title: "Error loading data",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  // Phase 17-04: `fetchOrderDetail` was the inline-modal loader. The detail
  // route now owns this fetch. The function (and its associated state
  // setters above) are removed; the modal JSX further down is preserved per
  // 17-CONTEXT but is no longer reachable from any row click.

  const getProductName = (productId: string): string => {
    const product = products.find(p => p.id === productId)
    return product ? product.title : productId.substring(0, 8) + "..."
  }

  const getShopName = (shopId: string): string => {
    const shop = shops.find(s => s.id === shopId)
    return shop ? shop.name : shopId.substring(0, 8) + "..."
  }

  const openCreateDialog = () => {
    reset({
      shopId: "",
      customerName: "",
      customerEmail: "",
      customerPhone: "",
    })
    setOrderItems([])
    setDialogOpen(true)
  }

  const addOrderItem = () => {
    setOrderItems([...orderItems, { productId: "", quantity: 1 }])
  }

  const removeOrderItem = (index: number) => {
    setOrderItems(orderItems.filter((_, i) => i !== index))
  }

  const updateOrderItem = (index: number, field: "productId" | "quantity", value: string | number) => {
    const updated = [...orderItems]
    updated[index] = { ...updated[index], [field]: value }
    setOrderItems(updated)
  }

  const onSubmit = async (data: OrderFormData) => {
    try {
      // Validate items
      if (orderItems.length === 0) {
        toast({
          variant: "destructive",
          title: "Validation error",
          description: "Please add at least one item to the order.",
        })
        return
      }

      // Check all items have products selected
      const invalidItems = orderItems.filter(item => !item.productId || item.quantity < 1)
      if (invalidItems.length > 0) {
        toast({
          variant: "destructive",
          title: "Validation error",
          description: "Please select a product and quantity for all items.",
        })
        return
      }

      setSubmitting(true)

      // Add items to form data
      const payload = {
        ...data,
        items: orderItems,
      }

      await apiClient.post("/api/v1/orders", payload)
      toast({
        title: "Order created",
        description: `Order for ${data.customerName} has been created successfully.`,
      })

      setDialogOpen(false)
      reset()
      setOrderItems([])
      if (currentPage === 0) fetchData()
      else setCurrentPage(0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to create order"
      toast({
        variant: "destructive",
        title: "Error creating order",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleStateTransition = async (
    orderId: string,
    endpoint: string,
    actionName: string
  ) => {
    try {
      setProcessingOrderId(orderId)
      await apiClient.post(`/api/v1/orders/${orderId}/${endpoint}`)
      toast({
        title: "Order updated",
        description: `Order has been ${actionName.toLowerCase()} successfully.`,
      })
      fetchData()
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : `Failed to ${actionName.toLowerCase()} order`
      toast({
        variant: "destructive",
        title: `Error ${actionName.toLowerCase()} order`,
        description: errorMessage,
      })
    } finally {
      setProcessingOrderId(null)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Orders</h1>
          <p className="mt-2 text-slate-600">
            {customerIdParam
              ? "Showing orders for selected customer"
              : "Manage orders and track their status through the workflow"}
          </p>
        </div>
        <Button onClick={openCreateDialog} className="gap-2">
          <Plus className="h-4 w-4" />
          Create Order
        </Button>
      </motion.div>

      {/* Status Flow Visualization */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card className="overflow-hidden bg-gradient-to-br from-blue-50 to-blue-100">
          <CardHeader>
            <CardTitle className="text-lg">Order Status Flow</CardTitle>
            <CardDescription>
              Track orders through each stage of fulfillment
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap items-center gap-2">
              {statusFlow.map((status, index) => {
                const config = statusConfig[status]
                const StatusIcon = config.icon
                return (
                  <div key={status} className="flex items-center">
                    <div className="flex items-center gap-2 rounded-lg bg-white px-4 py-2 shadow-sm">
                      <StatusIcon className={`h-4 w-4 ${config.color}`} />
                      <span className="text-sm font-medium">{config.label}</span>
                    </div>
                    {index < statusFlow.length - 1 && (
                      <ArrowRight className="mx-2 h-4 w-4 text-slate-400" />
                    )}
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      </motion.div>

      {/* Orders Table */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
      >
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle>All Orders</CardTitle>
              <CardDescription>
                {totalElements} order{totalElements !== 1 ? "s" : ""} in total
                {statusFilter !== "ALL" && ` (filtered: ${statusFilter})`}
              </CardDescription>
            </div>
            <Select value={statusFilter} onValueChange={(v) => { setStatusFilter(v); setCurrentPage(0) }}>
              <SelectTrigger className="w-[160px]">
                <SelectValue placeholder="Filter status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All Statuses</SelectItem>
                <SelectItem value="DRAFT">Draft</SelectItem>
                <SelectItem value="PENDING">Pending</SelectItem>
                <SelectItem value="CONFIRMED">Confirmed</SelectItem>
                <SelectItem value="PREPARING">Preparing</SelectItem>
                <SelectItem value="READY">Ready</SelectItem>
                <SelectItem value="COMPLETED">Completed</SelectItem>
                <SelectItem value="CANCELLED">Cancelled</SelectItem>
                <SelectItem value="REFUNDED">Refunded</SelectItem>
              </SelectContent>
            </Select>
          </CardHeader>
          <CardContent>
            {orders.filter(o => statusFilter === "ALL" || o.status === statusFilter).length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <ShoppingCart className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No orders yet
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  Get started by creating your first order
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Create Order
                </Button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Order ID</TableHead>
                      <TableHead>Customer</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Total</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {orders.filter(o => statusFilter === "ALL" || o.status === statusFilter).map((order) => {
                      const config = statusConfig[order.status]
                      const StatusIcon = config.icon
                      const transitions = getAvailableTransitions(order.status)
                      const isProcessing = processingOrderId === order.id

                      return (
                        <motion.tr
                          key={order.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="group cursor-pointer hover:bg-slate-50"
                          // Phase 17-04 (VOPS-01): row click navigates to the
                          // dedicated detail route so vendors can issue refunds
                          // and use the full detail panel. The inline detail
                          // Dialog below is kept for v2.2 per 17-CONTEXT
                          // (deferred deprecation — frontend cleanup TBD).
                          onClick={() => router.push(`/dashboard/orders/${order.id}`)}
                        >
                          <TableCell className="font-mono text-xs">
                            {order.id.substring(0, 8)}...
                          </TableCell>
                          <TableCell>
                            <div>
                              <div className="font-medium">
                                {order.customerName || "N/A"}
                              </div>
                              {order.customerEmail && (
                                <div className="text-xs text-slate-500">
                                  {order.customerEmail}
                                </div>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <Badge
                              className={`${config.bgColor} flex w-fit items-center gap-1 text-white`}
                            >
                              <StatusIcon className="h-3 w-3" />
                              {config.label}
                            </Badge>
                          </TableCell>
                          <TableCell className="font-semibold">
                            £{((order.totalAmountPennies || 0) / 100).toFixed(2)}
                          </TableCell>
                          <TableCell className="text-slate-600">
                            {formatDistanceToNow(new Date(order.createdAt), {
                              addSuffix: true,
                            })}
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                              {transitions.map((transition) => {
                                const TransitionIcon = transition.icon
                                return (
                                  <Button
                                    key={transition.action}
                                    size="sm"
                                    className={`${transition.color} text-white h-8`}
                                    onClick={() =>
                                      handleStateTransition(
                                        order.id,
                                        transition.endpoint,
                                        transition.action
                                      )
                                    }
                                    disabled={isProcessing}
                                  >
                                    <TransitionIcon className="mr-1 h-3 w-3" />
                                    {transition.action}
                                  </Button>
                                )
                              })}
                              {transitions.length === 0 && (
                                <span className="text-xs text-slate-400">
                                  No actions
                                </span>
                              )}
                            </div>
                          </TableCell>
                        </motion.tr>
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

      {/* Create Order Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Create New Order</DialogTitle>
            <DialogDescription>
              Create a new order and assign it to a shop.
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="shopId">Shop</Label>
              <Select
                value={selectedShopId}
                onValueChange={(value) => setValue("shopId", value)}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Select a shop" />
                </SelectTrigger>
                <SelectContent>
                  {shops.map((shop) => (
                    <SelectItem key={shop.id} value={shop.id}>
                      {shop.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {errors.shopId && (
                <p className="text-sm text-red-600">{errors.shopId.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="customerName">Customer Name</Label>
              <Input
                id="customerName"
                placeholder="e.g., John Doe"
                {...register("customerName")}
              />
              {errors.customerName && (
                <p className="text-sm text-red-600">
                  {errors.customerName.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="customerEmail">Customer Email</Label>
              <Input
                id="customerEmail"
                type="email"
                placeholder="e.g., john@example.com"
                {...register("customerEmail")}
              />
              {errors.customerEmail && (
                <p className="text-sm text-red-600">
                  {errors.customerEmail.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="customerPhone">Customer Phone (Optional)</Label>
              <Input
                id="customerPhone"
                placeholder="e.g., +44 7700 900000"
                {...register("customerPhone")}
              />
              {errors.customerPhone && (
                <p className="text-sm text-red-600">
                  {errors.customerPhone.message}
                </p>
              )}
            </div>

            {/* Order Items Section */}
            <div className="space-y-3 border-t pt-4">
              <div className="flex items-center justify-between">
                <Label className="text-base font-semibold">Order Items</Label>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={addOrderItem}
                  className="gap-1"
                >
                  <Plus className="h-3 w-3" />
                  Add Item
                </Button>
              </div>

              {orderItems.length === 0 && (
                <p className="text-sm text-slate-500 py-4 text-center border-2 border-dashed rounded-lg">
                  No items added. Click &quot;Add Item&quot; to start building the order.
                </p>
              )}

              {orderItems.map((item, index) => (
                <div key={index} className="flex gap-2 items-start p-3 border rounded-lg bg-slate-50">
                  <div className="flex-1 space-y-2">
                    <Select
                      value={item.productId}
                      onValueChange={(value) => updateOrderItem(index, "productId", value)}
                    >
                      <SelectTrigger className="bg-white">
                        <SelectValue placeholder="Select product" />
                      </SelectTrigger>
                      <SelectContent>
                        {products.map((product) => (
                          <SelectItem key={product.id} value={product.id}>
                            {product.title} - £{((product.pricePennies || 0) / 100).toFixed(2)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <Input
                      type="number"
                      min="1"
                      value={item.quantity}
                      onChange={(e) => updateOrderItem(index, "quantity", parseInt(e.target.value) || 1)}
                      placeholder="Quantity"
                      className="bg-white"
                    />
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => removeOrderItem(index)}
                    className="text-red-600 hover:text-red-700 hover:bg-red-50"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ))}
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setDialogOpen(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting ? "Creating..." : "Create Order"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Order Detail Dialog */}
      <Dialog open={detailDialogOpen} onOpenChange={setDetailDialogOpen}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {detailLoading || !selectedOrderDetail ? (
            <div className="flex items-center justify-center py-12">
              <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
            </div>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle className="flex items-center gap-3">
                  <ShoppingCart className="h-5 w-5" />
                  {selectedOrderDetail.orderNumber || selectedOrderDetail.id.substring(0, 8)}
                </DialogTitle>
                <DialogDescription>
                  Created {format(new Date(selectedOrderDetail.createdAt), "PPpp")}
                </DialogDescription>
              </DialogHeader>

              {/* Status + Total */}
              <div className="flex items-center justify-between rounded-lg bg-slate-50 p-4">
                <Badge className={`${statusConfig[selectedOrderDetail.status].bgColor} flex items-center gap-1 text-white`}>
                  {(() => { const Icon = statusConfig[selectedOrderDetail.status].icon; return <Icon className="h-3 w-3" /> })()}
                  {statusConfig[selectedOrderDetail.status].label}
                </Badge>
                <span className="text-2xl font-bold">
                  £{((selectedOrderDetail.totalAmountPennies || 0) / 100).toFixed(2)}
                </span>
              </div>

              {/* Customer Info */}
              <div className="grid grid-cols-2 gap-4 rounded-lg border p-4">
                <div>
                  <p className="text-xs font-medium text-slate-500">Customer</p>
                  <p className="font-medium">{selectedOrderDetail.customerName || "N/A"}</p>
                </div>
                <div>
                  <p className="text-xs font-medium text-slate-500">Shop</p>
                  <p className="font-medium">{getShopName(selectedOrderDetail.shopId)}</p>
                </div>
                {selectedOrderDetail.customerEmail && (
                  <div>
                    <p className="text-xs font-medium text-slate-500">Email</p>
                    <p className="text-sm">{selectedOrderDetail.customerEmail}</p>
                  </div>
                )}
                {selectedOrderDetail.customerPhone && (
                  <div>
                    <p className="text-xs font-medium text-slate-500">Phone</p>
                    <p className="text-sm">{selectedOrderDetail.customerPhone}</p>
                  </div>
                )}
                {selectedOrderDetail.notes && (
                  <div className="col-span-2">
                    <p className="text-xs font-medium text-slate-500">Notes</p>
                    <p className="text-sm">{selectedOrderDetail.notes}</p>
                  </div>
                )}
              </div>

              {/* Line Items */}
              <div>
                <h3 className="mb-3 text-sm font-semibold text-slate-700">
                  Items ({selectedOrderDetail.items?.length || 0})
                </h3>
                {selectedOrderDetail.items && selectedOrderDetail.items.length > 0 ? (
                  <div className="overflow-hidden rounded-lg border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Product</TableHead>
                          <TableHead className="text-center">Qty</TableHead>
                          <TableHead className="text-right">Unit Price</TableHead>
                          <TableHead className="text-right">Total</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {selectedOrderDetail.items.map((item) => (
                          <TableRow key={item.id}>
                            <TableCell className="font-medium">
                              {getProductName(item.productId)}
                            </TableCell>
                            <TableCell className="text-center">{item.quantity}</TableCell>
                            <TableCell className="text-right">
                              £{((item.unitPricePennies || 0) / 100).toFixed(2)}
                            </TableCell>
                            <TableCell className="text-right font-semibold">
                              £{((item.totalPricePennies || 0) / 100).toFixed(2)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                ) : (
                  <p className="py-4 text-center text-sm text-slate-500">No items in this order.</p>
                )}
              </div>

              {/* Actions */}
              {getAvailableTransitions(selectedOrderDetail.status).length > 0 && (
                <div className="flex justify-end gap-2 border-t pt-4">
                  {getAvailableTransitions(selectedOrderDetail.status).map((transition) => {
                    const TransitionIcon = transition.icon
                    return (
                      <Button
                        key={transition.action}
                        size="sm"
                        className={`${transition.color} text-white`}
                        onClick={() => {
                          handleStateTransition(
                            selectedOrderDetail.id,
                            transition.endpoint,
                            transition.action
                          )
                          setDetailDialogOpen(false)
                        }}
                      >
                        <TransitionIcon className="mr-1 h-3 w-3" />
                        {transition.action}
                      </Button>
                    )
                  })}
                </div>
              )}
            </>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

export default function OrdersPage() {
  return (
    <Suspense fallback={<div className="flex h-full items-center justify-center"><div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div></div>}>
      <OrdersPageInner />
    </Suspense>
  )
}
