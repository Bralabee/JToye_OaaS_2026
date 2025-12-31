"use client"

import { useEffect, useState } from "react"
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
import type { Order, OrderStatus, Shop, Product } from "@/types/api"
import { formatDistanceToNow } from "date-fns"
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
    color: "text-purple-700",
    bgColor: "bg-purple-500",
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
        endpoint: "start-preparing",
        nextStatus: "PREPARING",
        icon: ChefHat,
        color: "bg-purple-600 hover:bg-purple-700",
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
  }
  return transitions[currentStatus] || []
}

export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [processingOrderId, setProcessingOrderId] = useState<string | null>(null)
  const [orderItems, setOrderItems] = useState<{ productId: string; quantity: number }[]>([])
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
  }, [])

  const fetchData = async () => {
    try {
      setLoading(true)
      const [ordersRes, shopsRes, productsRes] = await Promise.all([
        apiClient.get("/orders?size=100&sort=createdAt,desc"),
        apiClient.get("/shops?size=100"),
        apiClient.get("/products?size=100"),
      ])
      setOrders(ordersRes.data.content || [])
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

      await apiClient.post("/orders", payload)
      toast({
        title: "Order created",
        description: `Order for ${data.customerName} has been created successfully.`,
      })

      setDialogOpen(false)
      reset()
      setOrderItems([])
      fetchData()
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
      await apiClient.post(`/orders/${orderId}/${endpoint}`)
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
            Manage orders and track their status through the workflow
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
        <Card className="overflow-hidden bg-gradient-to-br from-blue-50 to-purple-50">
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
          <CardHeader>
            <CardTitle>All Orders</CardTitle>
            <CardDescription>
              {orders.length} order{orders.length !== 1 ? "s" : ""} in total
            </CardDescription>
          </CardHeader>
          <CardContent>
            {orders.length === 0 ? (
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
                    {orders.map((order) => {
                      const config = statusConfig[order.status]
                      const StatusIcon = config.icon
                      const transitions = getAvailableTransitions(order.status)
                      const isProcessing = processingOrderId === order.id

                      return (
                        <motion.tr
                          key={order.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="group"
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
                            £{(order.totalPricePennies / 100).toFixed(2)}
                          </TableCell>
                          <TableCell className="text-slate-600">
                            {formatDistanceToNow(new Date(order.createdAt), {
                              addSuffix: true,
                            })}
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-2">
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
    </div>
  )
}
