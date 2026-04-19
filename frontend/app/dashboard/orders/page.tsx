"use client"

import { useEffect, useState, Suspense } from "react"
import { useSearchParams } from "next/navigation"
import { motion } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
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

type OrderBadgeVariant = "subtle" | "warning" | "info" | "brand" | "success" | "danger" | "editorial"

const statusConfig: Record<
  OrderStatus,
  { label: string; variant: OrderBadgeVariant; iconColor: string; icon: React.ComponentType<{ className?: string }> }
> = {
  DRAFT: {
    label: "Draft",
    variant: "subtle",
    iconColor: "text-ink-tertiary",
    icon: Clock,
  },
  PENDING: {
    label: "Pending",
    variant: "warning",
    iconColor: "text-ink-primary",
    icon: Clock,
  },
  CONFIRMED: {
    label: "Confirmed",
    variant: "info",
    iconColor: "text-info",
    icon: CheckCircle2,
  },
  PREPARING: {
    label: "Preparing",
    variant: "brand",
    iconColor: "text-brand-primary",
    icon: ChefHat,
  },
  READY: {
    label: "Ready",
    variant: "success",
    iconColor: "text-success",
    icon: PackageIcon,
  },
  COMPLETED: {
    label: "Completed",
    variant: "editorial",
    iconColor: "text-accent-editorial",
    icon: FileCheck,
  },
  CANCELLED: {
    label: "Cancelled",
    variant: "danger",
    iconColor: "text-danger",
    icon: XCircle,
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

type TransitionVariant = "primary" | "editorial" | "secondary" | "destructive"

interface StateTransition {
  action: string
  endpoint: string
  nextStatus: OrderStatus
  icon: React.ComponentType<{ className?: string }>
  variant: TransitionVariant
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
        variant: "primary",
      },
    ],
    PENDING: [
      {
        action: "Confirm",
        endpoint: "confirm",
        nextStatus: "CONFIRMED",
        icon: CheckCircle2,
        variant: "primary",
      },
      {
        action: "Cancel",
        endpoint: "cancel",
        nextStatus: "CANCELLED",
        icon: Ban,
        variant: "destructive",
      },
    ],
    CONFIRMED: [
      {
        action: "Start Prep",
        endpoint: "start-preparation",
        nextStatus: "PREPARING",
        icon: ChefHat,
        variant: "editorial",
      },
      {
        action: "Cancel",
        endpoint: "cancel",
        nextStatus: "CANCELLED",
        icon: Ban,
        variant: "destructive",
      },
    ],
    PREPARING: [
      {
        action: "Mark Ready",
        endpoint: "mark-ready",
        nextStatus: "READY",
        icon: PackageIcon,
        variant: "primary",
      },
    ],
    READY: [
      {
        action: "Complete",
        endpoint: "complete",
        nextStatus: "COMPLETED",
        icon: FileCheck,
        variant: "secondary",
      },
    ],
    COMPLETED: [],
    CANCELLED: [],
  }
  return transitions[currentStatus] || []
}

const PAGE_SIZE = 20

function OrdersPageInner() {
  const searchParams = useSearchParams()
  const customerIdParam = searchParams.get("customer")
  const initialStatusParam = searchParams.get("status")
  const shouldAutoOpenNew = searchParams.get("new") === "1"
  const [orders, setOrders] = useState<Order[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<string>(
    initialStatusParam && initialStatusParam !== "ALL" ? initialStatusParam : "ALL"
  )
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [processingOrderId, setProcessingOrderId] = useState<string | null>(null)
  const [orderItems, setOrderItems] = useState<{ productId: string; quantity: number }[]>([])
  const [detailDialogOpen, setDetailDialogOpen] = useState(false)
  const [selectedOrderDetail, setSelectedOrderDetail] = useState<OrderDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [autoOpenedNew, setAutoOpenedNew] = useState(false)
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

  // Real-time updates via SSE
  useEffect(() => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
    const eventSource = new EventSource(`${apiUrl}/api/v1/orders/stream`)
    eventSource.addEventListener("order-state-change", () => {
      fetchData()
    })
    eventSource.onerror = () => {
      // SSE connection failed — fall back to no auto-refresh
      eventSource.close()
    }
    return () => eventSource.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchData = async () => {
    try {
      setLoading(true)
      const ordersPromise = customerIdParam
        ? apiClient.get(`/api/v1/orders/customer/${customerIdParam}`)
        : apiClient.get(`/api/v1/orders?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`)
      const [ordersRes, shopsRes, productsRes] = await Promise.all([
        ordersPromise,
        apiClient.get("/api/v1/shops?size=100"),
        apiClient.get("/api/v1/products?size=100"),
      ])
      // Customer endpoint returns array; paginated returns {content, ...}
      const orderData = customerIdParam ? ordersRes.data : ordersRes.data.content
      setOrders(orderData || [])
      setTotalPages(customerIdParam ? 1 : (ordersRes.data.totalPages || 0))
      setTotalElements(customerIdParam ? (orderData?.length || 0) : (ordersRes.data.totalElements || 0))
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

  const fetchOrderDetail = async (orderId: string) => {
    try {
      setDetailLoading(true)
      setDetailDialogOpen(true)
      setSelectedOrderDetail(null)
      const res = await apiClient.get(`/api/v1/orders/${orderId}/detail`)
      setSelectedOrderDetail(res.data)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load order details"
      toast({
        variant: "destructive",
        title: "Error loading order details",
        description: errorMessage,
      })
      setDetailDialogOpen(false)
    } finally {
      setDetailLoading(false)
    }
  }

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

  // Honour ?new=1 — auto-open the create-order dialog once after the initial load
  useEffect(() => {
    if (!shouldAutoOpenNew || autoOpenedNew || loading) return
    openCreateDialog()
    setAutoOpenedNew(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shouldAutoOpenNew, autoOpenedNew, loading])

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
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="font-display text-4xl font-semibold tracking-tight text-ink-primary">Orders</h1>
          <p className="mt-2 text-ink-secondary">
            {customerIdParam
              ? "Showing orders for selected customer"
              : "Manage orders and track their status through the workflow"}
          </p>
        </div>
        <Button onClick={openCreateDialog} variant="primary" className="gap-2">
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
        <Card variant="inset" className="overflow-hidden">
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
                    <div className="flex items-center gap-2 rounded-md bg-surface-card border border-subtle px-4 py-2 shadow-subtle">
                      <StatusIcon className={`h-4 w-4 ${config.iconColor}`} aria-hidden="true" />
                      <span className="text-sm font-medium text-ink-primary">{config.label}</span>
                    </div>
                    {index < statusFlow.length - 1 && (
                      <ArrowRight className="mx-2 h-4 w-4 text-ink-tertiary" aria-hidden="true" />
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
              </SelectContent>
            </Select>
          </CardHeader>
          <CardContent>
            {orders.filter(o => statusFilter === "ALL" || o.status === statusFilter).length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <ShoppingCart className="mb-4 h-12 w-12 text-ink-tertiary" aria-hidden="true" />
                <h3 className="mb-2 font-display text-lg font-semibold text-ink-primary">
                  No orders yet
                </h3>
                <p className="mb-4 text-sm text-ink-tertiary">
                  Get started by creating your first order
                </p>
                <Button onClick={openCreateDialog} variant="secondary">
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
                          className="group cursor-pointer hover:bg-surface-subtle/60 transition-colors duration-fast motion-reduce:transition-none"
                          onClick={() => fetchOrderDetail(order.id)}
                        >
                          <TableCell className="font-mono tabular-nums text-xs text-ink-secondary">
                            {order.id.substring(0, 8)}...
                          </TableCell>
                          <TableCell>
                            <div>
                              <div className="font-medium text-ink-primary">
                                {order.customerName || "N/A"}
                              </div>
                              {order.customerEmail && (
                                <div className="text-xs text-ink-tertiary">
                                  {order.customerEmail}
                                </div>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <Badge variant={config.variant} size="sm" className="flex w-fit items-center gap-1">
                              <StatusIcon className="h-3 w-3" />
                              {config.label}
                            </Badge>
                          </TableCell>
                          <TableCell numeric className="font-semibold text-ink-primary">
                            £{((order.totalAmountPennies || 0) / 100).toFixed(2)}
                          </TableCell>
                          <TableCell className="text-ink-secondary">
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
                                    variant={transition.variant}
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
                                <span className="text-xs text-ink-tertiary">
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
                <p className="text-sm text-danger">{errors.shopId.message}</p>
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
                <p className="text-sm text-danger">
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
                <p className="text-sm text-danger">
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
                <p className="text-sm text-danger">
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
                <p className="text-sm text-ink-tertiary py-4 text-center border-2 border-dashed border-subtle rounded-md">
                  No items added. Click &quot;Add Item&quot; to start building the order.
                </p>
              )}

              {orderItems.map((item, index) => (
                <div key={index} className="flex gap-2 items-start p-3 border border-subtle rounded-md bg-surface-subtle">
                  <div className="flex-1 space-y-2">
                    <Select
                      value={item.productId}
                      onValueChange={(value) => updateOrderItem(index, "productId", value)}
                    >
                      <SelectTrigger className="bg-surface-card">
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
                      className="bg-surface-card"
                    />
                  </div>
                  <Button
                    type="button"
                    size="iconSm"
                    variant="ghost"
                    onClick={() => removeOrderItem(index)}
                    className="text-danger hover:text-danger hover:bg-danger-subtle"
                    aria-label="Remove item"
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
              <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-brand-primary motion-reduce:animate-none" aria-label="Loading"></div>
            </div>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle className="font-display flex items-center gap-3">
                  <ShoppingCart className="h-5 w-5" aria-hidden="true" />
                  <span className="font-mono tabular-nums">{selectedOrderDetail.orderNumber || selectedOrderDetail.id.substring(0, 8)}</span>
                </DialogTitle>
                <DialogDescription>
                  Created {format(new Date(selectedOrderDetail.createdAt), "PPpp")}
                </DialogDescription>
              </DialogHeader>

              {/* Status + Total */}
              <div className="flex items-center justify-between rounded-md bg-surface-subtle p-4">
                <Badge variant={statusConfig[selectedOrderDetail.status].variant} size="md">
                  {(() => { const Icon = statusConfig[selectedOrderDetail.status].icon; return <Icon className="h-3 w-3" /> })()}
                  {statusConfig[selectedOrderDetail.status].label}
                </Badge>
                <span className="font-display font-mono tabular-nums text-2xl font-semibold text-ink-primary">
                  £{((selectedOrderDetail.totalAmountPennies || 0) / 100).toFixed(2)}
                </span>
              </div>

              {/* Customer Info */}
              <div className="grid grid-cols-2 gap-4 rounded-md border border-subtle p-4">
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Customer</p>
                  <p className="font-medium text-ink-primary">{selectedOrderDetail.customerName || "N/A"}</p>
                </div>
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Shop</p>
                  <p className="font-medium text-ink-primary">{getShopName(selectedOrderDetail.shopId)}</p>
                </div>
                {selectedOrderDetail.customerEmail && (
                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Email</p>
                    <p className="text-sm text-ink-primary">{selectedOrderDetail.customerEmail}</p>
                  </div>
                )}
                {selectedOrderDetail.customerPhone && (
                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Phone</p>
                    <p className="text-sm text-ink-primary">{selectedOrderDetail.customerPhone}</p>
                  </div>
                )}
                {selectedOrderDetail.notes && (
                  <div className="col-span-2">
                    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Notes</p>
                    <p className="text-sm text-ink-primary">{selectedOrderDetail.notes}</p>
                  </div>
                )}
              </div>

              {/* Line Items */}
              <div>
                <h3 className="mb-3 font-display text-sm font-semibold text-ink-primary">
                  Items (<span className="font-mono tabular-nums">{selectedOrderDetail.items?.length || 0}</span>)
                </h3>
                {selectedOrderDetail.items && selectedOrderDetail.items.length > 0 ? (
                  <div className="overflow-hidden rounded-md border border-subtle">
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
                            <TableCell className="text-center font-mono tabular-nums">{item.quantity}</TableCell>
                            <TableCell numeric>
                              £{((item.unitPricePennies || 0) / 100).toFixed(2)}
                            </TableCell>
                            <TableCell numeric className="font-semibold text-ink-primary">
                              £{((item.totalPricePennies || 0) / 100).toFixed(2)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                ) : (
                  <p className="py-4 text-center text-sm text-ink-tertiary">No items in this order.</p>
                )}
              </div>

              {/* Actions */}
              {getAvailableTransitions(selectedOrderDetail.status).length > 0 && (
                <div className="flex justify-end gap-2 border-t border-subtle pt-4">
                  {getAvailableTransitions(selectedOrderDetail.status).map((transition) => {
                    const TransitionIcon = transition.icon
                    return (
                      <Button
                        key={transition.action}
                        size="sm"
                        variant={transition.variant}
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
      <OrdersPageInner />
    </Suspense>
  )
}
