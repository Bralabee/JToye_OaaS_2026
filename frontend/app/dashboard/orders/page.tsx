"use client"

import { useEffect, useState, Suspense } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import { m } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { fetchAllMyShops } from "@/lib/shops-api"
import { fetchAllProducts } from "@/lib/products-api"
import { describeLoadError } from "@/lib/human-error"
import { useToast } from "@/hooks/use-toast"
import { useOrderEvents } from "@/hooks/use-order-events"
import { useShopContext } from "@/hooks/use-shop-context"
import { LoadErrorPanel } from "@/components/dashboard/load-error-panel"
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
import { IconButton } from "@/components/ui/icon-button"
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
import { VoidedBadge } from "@/components/dashboard/orders/voided-badge"
import { Checkbox } from "@/components/ui/checkbox"
import { UnverifiedContactBadge } from "@/components/dashboard/kitchen/unverified-contact-badge"
import { AwaitingPaymentBadge } from "@/components/dashboard/orders/awaiting-payment-badge"
import { isAwaitingPayment } from "@/lib/awaiting-payment"
import {
  RejectOrdersDialog,
  type RejectableOrder,
} from "@/components/dashboard/orders/reject-orders-dialog"
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
  Search,
} from "lucide-react"
import { Pagination } from "@/components/ui/pagination"
import type { Order, OrderDetail, OrderStatus, PaymentStatus, Shop, Product } from "@/types/api"
import { formatDistanceToNow } from "date-fns"
import { formatUkDateTime } from "@/lib/uk-datetime"
import { Trash2 } from "lucide-react"

// COR-1 (QA-council 20260902-134741, owner ruling E-1). The dialog had NO fulfilment control, so
// every order it created took the backend's V45 entity default and persisted as DELIVERY with a
// GBP 0.00 fee and no address — a delivery kitchen ticket with nowhere to deliver to.
//
// COLLECTION is the default because that is what this dialog captures: a walk-in / phone ticket.
// DELIVERY stays reachable (E-1: vendors take delivery orders by phone) and then REQUIRES the
// address, mirroring the server's FulfilmentPolicy.requireDeliveryAddress. The client check is a
// UX affordance only — the server is authoritative and answers 400 on its own.
const orderSchema = z
  .object({
    shopId: z.string().min(1, "Shop is required"),
    customerName: z.string().min(1, "Customer name is required").max(100),
    customerEmail: z.string().email("Invalid email").max(255),
    customerPhone: z.string().max(20).optional(),
    // No .default() here: zod's default makes the INPUT optional while the OUTPUT stays
    // required, and react-hook-form's Resolver types then refuse to line up. The default is
    // supplied by useForm({ defaultValues }) instead, which is where the form's initial state
    // belongs anyway.
    fulfilmentType: z.enum(["COLLECTION", "DELIVERY"]),
    addressLine1: z.string().optional(),
    addressLine2: z.string().optional(),
    addressCity: z.string().optional(),
    addressPostcode: z.string().optional(),
  })
  .superRefine((data, ctx) => {
    if (data.fulfilmentType !== "DELIVERY") return
    const required = [
      ["addressLine1", "Address line 1 is required for a delivery order"],
      ["addressCity", "City is required for a delivery order"],
      ["addressPostcode", "Postcode is required for a delivery order"],
    ] as const
    for (const [field, message] of required) {
      if (!data[field]?.trim()) {
        ctx.addIssue({ code: "custom", message, path: [field] })
      }
    }
    // Collection omits the address entirely, including stale values from a delivery edit.
    const limits = [
      ["addressLine1", 255],
      ["addressLine2", 255],
      ["addressCity", 120],
      ["addressPostcode", 12],
    ] as const
    for (const [field, maximum] of limits) {
      if ((data[field]?.length ?? 0) > maximum) {
        ctx.addIssue({ code: "too_big", origin: "string", maximum, inclusive: true, path: [field] })
      }
    }
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
    // bg-yellow-700, not -500: white text on -500 is 1.92:1 on white — fails
    // AA (F3 / A11Y-1). -700 is 4.92:1.
    bgColor: "bg-yellow-700",
    icon: Clock,
  },
  CONFIRMED: {
    label: "Confirmed",
    color: "text-blue-700",
    // A11Y-6 (QA council 20260902-134741): white text on a solid fill needs
    // 4.5:1. Measured from tailwindcss/colors — blue-500 3.68, blue-600 5.17;
    // amber-500 2.15 -> amber-700 5.02; green-500 2.28 -> green-700 5.02;
    // emerald-600 3.77 -> emerald-700 5.48; red-500 3.76 -> red-600 4.83;
    // orange-500 2.80 -> orange-700 5.18. Asserted generically over this map
    // in __tests__/cluster-g-contrast-sites.test.ts. Not a token change (A26).
    bgColor: "bg-blue-600",
    icon: CheckCircle2,
  },
  PREPARING: {
    label: "Preparing",
    color: "text-amber-700",
    bgColor: "bg-amber-700",
    icon: ChefHat,
  },
  READY: {
    label: "Ready",
    color: "text-green-700",
    bgColor: "bg-green-700",
    icon: PackageIcon,
  },
  COMPLETED: {
    label: "Completed",
    color: "text-emerald-700",
    bgColor: "bg-emerald-700",
    icon: FileCheck,
  },
  CANCELLED: {
    label: "Cancelled",
    color: "text-red-700",
    bgColor: "bg-red-600",
    icon: XCircle,
  },
  REFUNDED: {
    // Phase 17-04: REFUNDED is the new terminal state for orders that have
    // had at least one Stripe refund issued. Orange badge keeps it within
    // the existing food-delivery palette (per `feedback_design_direction.md`).
    label: "Refunded",
    color: "text-orange-700",
    bgColor: "bg-orange-700",
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

/** Transition endpoints the server refuses while an order awaits its payment (plan 30-05, #958). */
const PAYMENT_GATED_ENDPOINTS = new Set(["submit", "confirm", "start-preparation"])

/**
 * The row / detail-panel actions for an order.
 *
 * 30-06 (issue #958, UI-SPEC § S2): it takes the ORDER, not only its status. For an order awaiting
 * its card or link payment (`isAwaitingPayment`, the server rule) Submit, Confirm and Start Prep are not
 * offered, because the server refuses all three with 409 awaiting-payment; the "Awaiting payment" badge
 * takes their place. Cancel stays on every non-terminal status, DRAFT included, EXCEPT an unpaid card
 * DRAFT: a one-tap cancel there leaves its PaymentIntent payable, and a customer still at checkout could
 * then pay a cancelled order. Such a DRAFT is cleared by the D-02 tool, which cancels the intent first
 * and refuses a paid one (PR #1030 review round 1, findings 1 and 5). Display only: the server stays
 * authoritative (T-30-23).
 */
const getAvailableTransitions = (order: {
  status: OrderStatus
  paymentStatus?: PaymentStatus | null
  paymentReference?: string | null
}): StateTransition[] => {
  const transitions: Record<OrderStatus, StateTransition[]> = {
    DRAFT: [
      {
        action: "Submit",
        endpoint: "submit",
        nextStatus: "PENDING",
        icon: ArrowRight,
        // A11Y-10: white text needs bg-yellow-700 (4.92:1) to clear WCAG AA;
        // -600 (2.94:1) fails. Hover deepens to -800 to keep a visible delta.
        color: "bg-yellow-700 hover:bg-yellow-800",
      },
      {
        action: "Cancel",
        endpoint: "cancel",
        nextStatus: "CANCELLED",
        icon: Ban,
        color: "bg-red-600 hover:bg-red-700",
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
        // A11Y-6: amber-600 is 3.19 with white text; -700 is 5.02, -800 7.09.
        color: "bg-amber-700 hover:bg-amber-800",
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
        color: "bg-green-700 hover:bg-green-800",
      },
    ],
    READY: [
      {
        action: "Complete",
        endpoint: "complete",
        nextStatus: "COMPLETED",
        icon: FileCheck,
        color: "bg-emerald-700 hover:bg-emerald-800",
      },
    ],
    COMPLETED: [],
    CANCELLED: [],
    // REFUNDED is a terminal state — no further transitions from the UI.
    // Refunds are issued via the detail page's RefundDialog, not as a row
    // action on the list.
    REFUNDED: [],
  }
  const available = transitions[order.status] || []
  if (!isAwaitingPayment(order)) return available
  return available.filter(
    (t) =>
      !PAYMENT_GATED_ENDPOINTS.has(t.endpoint) &&
      !(order.status === "DRAFT" && t.endpoint === "cancel")
  )
}

const PAGE_SIZE = 20

/** The sticky bar's confirm label (UI-SPEC § D1 "Bulk reject (Orders page)"), singular at one. */
export function rejectSelectionCopy(count: number): string {
  return count === 1 ? "Reject 1 order" : `Reject ${count} orders`
}

export const SELECT_ALL_PENDING_LABEL = "Select all pending orders on this page"
export const CLEAR_SELECTION_COPY = "Clear selection"

function OrdersPageInner() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const customerIdParam = searchParams.get("customer")
  const [orders, setOrders] = useState<Order[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  const [products, setProducts] = useState<Product[]>([])
  // #485: true when the catalogue was longer than the picker's page bound, so the
  // dialog can SAY the product list is partial rather than silently dropping the tail.
  const [productsTruncated, setProductsTruncated] = useState(false)
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<string>("ALL")
  /** SW-ADE-10: a text search over the orders already loaded on this page (no new request). */
  const [searchQuery, setSearchQuery] = useState("")
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [processingOrderId, setProcessingOrderId] = useState<string | null>(null)
  // 37-30 (UI-SPEC § D1): the PENDING rows ticked for a bulk reject, and the dialog that
  // rejects them. Held as ids; what is "selected" is always re-derived against the rows on
  // screen, so a row that left PENDING under the selection drops out without an effect.
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [rejectDialogOpen, setRejectDialogOpen] = useState(false)
  const [orderItems, setOrderItems] = useState<{ productId: string; quantity: number }[]>([])
  // Phase 17-04: the inline detail Dialog is preserved for v2.2 per
  // 17-CONTEXT but is no longer reachable from the UI — row clicks now
  // navigate to /dashboard/orders/[id] (the dedicated detail route). The
  // setters below are retained so the existing modal JSX still type-checks
  // and so a follow-up cleanup phase can delete the modal without touching
  // unrelated code.
   
  const [detailDialogOpen, setDetailDialogOpen] = useState(false)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [selectedOrderDetail, setSelectedOrderDetail] = useState<OrderDetail | null>(null)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [detailLoading, setDetailLoading] = useState(false)
  // F2 (A11Y-2): a 429/network failure must render an error panel, never the
  // "No orders yet" empty state — `fetchData`'s catch below deliberately does
  // NOT reset `orders` to `[]`.
  const [loadFailed, setLoadFailed] = useState(false)
  const [loadErrorMessage, setLoadErrorMessage] = useState("")
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
    // COR-1: the fulfilment control opens on COLLECTION, which is what this dialog captures.
    defaultValues: { fulfilmentType: "COLLECTION" },
  })

  const selectedShopId = watch("shopId")
  const selectedFulfilment = watch("fulfilmentType")

  // #1016: offer only the selected shop's available products, and nothing until a shop is chosen.
  const pickerProducts = selectedShopId
    ? products.filter((p) => p.shopId === selectedShopId && p.available !== false)
    : []

  // VSA-03: the persisted switcher selection. `null` = All shops (no narrow).
  const { contextShopId } = useShopContext()

  useEffect(() => {
    fetchData()
    // A switcher change must refetch so the list narrows live (no reload).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage, contextShopId])

  // #485: the create-order dialog's two pickers, loaded ONCE on mount.
  //
  // They used to ride inside `fetchData`, which runs on every pager click, every
  // switcher change AND — via `useOrderEvents` below — every order event on the SSE
  // stream. That was affordable while each was a single truncating request; it is not
  // once they follow the list, because a large catalogue would then re-page itself on
  // every ticket that moves. Neither list depends on the order page, the selected
  // shop or any order event (the dialog constrains itself to the switcher's shop
  // client-side), so hoisting them here costs strictly fewer requests than before
  // AND makes them complete.
  useEffect(() => {
    fetchPickerData()
  }, [])

  // Real-time updates via SSE (#92): the shared hook owns the auth header,
  // keep-alive-friendly connection, capped exponential-backoff reconnect, and
  // reconnect-after-server-recycle — this page no longer gives up on error.
  //
  // A live event refreshes IN PLACE (quiet): the full-page spinner unmounts everything below it,
  // so an event arriving while a dialog is open threw the dialog away. The bulk reject emits one
  // event per order it cancels, which replaced its own result summary with "Reject 0 orders?"
  // (nightly #683, abuse-bounds.spec.ts on mobile). The rows are kept until the new page arrives.
  useOrderEvents(() => fetchData({ quiet: true }))

  const fetchData = async ({ quiet = false }: { quiet?: boolean } = {}) => {
    try {
      if (!quiet) setLoading(true)
      // Issue #95: /orders/customer/{id} is paginated now (Page response, same
      // shape as /orders) — the old bare-array special case is gone.
      // VSA-03: outside the All-shops context the collection list narrows
      // SERVER-side via ?shopId= (OrderService.getOrdersByShop, gated by the
      // 23-03 grant check) — the client never widens the authorised set. The
      // customer-scoped endpoint takes no shop param, so that branch narrows on
      // the rendered rows instead (see visibleOrders).
      const shopScope = contextShopId ? `&shopId=${contextShopId}` : ""
      const ordersRes = customerIdParam
        ? await apiClient.get(`/api/v1/orders/customer/${customerIdParam}?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`)
        : await apiClient.get(`/api/v1/orders?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc${shopScope}`)
      setOrders(ordersRes.data.content || [])
      setTotalPages(ordersRes.data.totalPages || 0)
      setTotalElements(ordersRes.data.totalElements || 0)
      setLoadFailed(false)
    } catch (error: unknown) {
      const { message } = describeLoadError(error)
      toast({
        variant: "destructive",
        title: "Error loading data",
        description: message,
      })
      // F2 (A11Y-2): `orders` is deliberately left untouched above — a false
      // "No orders yet" empty state is worse than briefly stale rows.
      setLoadFailed(true)
      setLoadErrorMessage(message)
    } finally {
      setLoading(false)
    }
  }

  /**
   * #485 (call sites :297 shops, :298 products). Both were a single `?size=100`
   * request whose first page was treated as the whole list. Past 100 shops a vendor
   * could not choose the tail as the order's shop; past 100 products they could not
   * add products 101+ as line items — with no error and nothing on screen to say the
   * lists were partial.
   *
   * Failures are swallowed rather than toasted, matching the products, marketing and
   * onboarding screens: these feed a dialog, and a picker that cannot load must not
   * take the orders table down with it — which it did while it shared `fetchData`'s
   * `Promise.all` and its "Error loading data" toast.
   */
  const fetchPickerData = async () => {
    try {
      const [allShops, { products: allProducts, truncated }] = await Promise.all([
        fetchAllMyShops(),
        fetchAllProducts(),
      ])
      setShops(allShops)
      setProducts(allProducts)
      setProductsTruncated(truncated)
    } catch {
      // Non-critical — the dialog's selects simply stay empty.
    }
  }

  // Phase 17-04: `fetchOrderDetail` was the inline-modal loader. The detail
  // route now owns this fetch. The function (and its associated state
  // setters above) are removed; the modal JSX further down is preserved per
  // 17-CONTEXT but is no longer reachable from any row click.

  // The collection list is already narrowed server-side via ?shopId=; the
  // customer-scoped endpoint takes no shop param, so its rows are narrowed here
  // so both entry points honour the same switcher context.
  const visibleOrders =
    contextShopId && customerIdParam
      ? orders.filter((o) => o.shopId === contextShopId)
      : orders

  // SW-ADE-10 (swarm epic #964): order number, customer name or email, case-insensitive, over the
  // rows this page already holds. The list is paged server-side, so the search covers this page only
  // and says so in its placeholder.
  const needle = searchQuery.trim().toLowerCase()
  const filteredOrders = visibleOrders.filter(
    (o) =>
      (statusFilter === "ALL" || o.status === statusFilter) &&
      (!needle ||
        [o.orderNumber, o.id, o.customerName, o.customerEmail].some((field) =>
          field?.toLowerCase().includes(needle)
        ))
  )

  // 37-30 (D-18): only a PENDING order can be rejected in bulk (37-29 reports any other state
  // ALREADY_ACCEPTED / ALREADY_CANCELLED), so only PENDING rows offer a checkbox, the header
  // checkbox covers exactly those, and the column itself exists only while there is one.
  const pendingOnPage = filteredOrders.filter((o) => o.status === "PENDING")
  const showSelection = pendingOnPage.length > 0
  const selectedPending: RejectableOrder[] = pendingOnPage
    .filter((o) => selectedIds.has(o.id))
    .map((o) => ({ id: o.id, label: o.orderNumber ?? `${o.id.substring(0, 8)}...` }))
  const allPendingSelected =
    pendingOnPage.length > 0 && pendingOnPage.every((o) => selectedIds.has(o.id))

  const toggleSelected = (orderId: string, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) next.add(orderId)
      else next.delete(orderId)
      return next
    })
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
      // D-08: a single-shop context does single-shop writes only.
      shopId: contextShopId ?? "",
      customerName: "",
      customerEmail: "",
      customerPhone: "",
      // COR-1: reset must restore the fulfilment default too, or the dialog opens with no
      // selection and the first order of the session goes out unclassified again.
      fulfilmentType: "COLLECTION",
      addressLine1: "",
      addressLine2: "",
      addressCity: "",
      addressPostcode: "",
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

      // Add items to form data.
      //
      // COR-1: the address block is sent ONLY for a DELIVERY order. The server ignores it on a
      // COLLECTION order anyway (FulfilmentPolicy decides from the type, never from the payload),
      // but posting a set of empty strings would put a delivery address on the wire for an order
      // that has none, and any future audit of the request body would read it as one.
      const { addressLine1, addressLine2, addressCity, addressPostcode, ...rest } = data
      const payload =
        data.fulfilmentType === "DELIVERY"
          ? { ...rest, addressLine1, addressLine2, addressCity, addressPostcode, items: orderItems }
          : { ...rest, items: orderItems }

      await apiClient.post("/api/v1/orders", payload)
      toast({
        title: "Order created",
        description: `Order for ${data.customerName} has been created successfully.`,
      })

      setDialogOpen(false)
      reset({ fulfilmentType: "COLLECTION" })
      setOrderItems([])
      if (currentPage === 0) fetchData()
      else setCurrentPage(0)
    } catch (error: unknown) {
      // A11Y-2: was a raw axios string (e.g. "Request failed with status code
      // 429") — describeLoadError resolves the same RFC 7807 detail/message
      // shape the rest of the dashboard now reads.
      toast({
        variant: "destructive",
        title: "Error creating order",
        description: describeLoadError(error, "Failed to create order").message,
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
      // A11Y-2: was a raw axios string for any HTTP failure with no readable
      // body (e.g. "Request failed with status code 409" on an invalid
      // transition) — same fix as onSubmit above.
      const errorMessage = describeLoadError(
        error,
        `Failed to ${actionName.toLowerCase()} order`
      ).message
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
    // Phase 35, Index tier: a resource index, deliberately uncapped below the
    // dashboard band. This is the surface that motivated the phase — six
    // columns confined to the old 1400px cap left roughly 900px of empty
    // gutter beside the table at a 2560 viewport. The tier adds NO width class
    // on purpose: "fluid to the shell" is the documented pattern for
    // data-dense lists, and the attribute is here so that being uncapped is a
    // declaration a test can falsify rather than an absence indistinguishable
    // from a forgotten cap. Do not "tidy" this by adding a max-width — the
    // narrowing would be the defect this phase exists to remove.
    <div data-width-tier="index" className={`space-y-6${selectedPending.length > 0 ? " pb-28" : ""}`}>
      {/* Header */}
      <m.div
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
      </m.div>

      {/*
        A11Y-6: every section below is a `CardTitle`, hard-coded to render an
        <h3> (components/ui/card.tsx — a shared primitive used across the
        whole app, out of scope to change here). Without a real <h2> between
        the page's <h1> and the first of those <h3>s, the outline skips a
        level (axe heading-order). `sr-only` because this corrects the
        SEMANTIC level, not the visual size — every heading below keeps the
        size its own className already gives it.
      */}
      <h2 className="sr-only">Overview</h2>

      {/* Status Flow Visualization */}
      <m.div
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
      </m.div>

      {/* Orders Table */}
      <m.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
      >
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle>All Orders</CardTitle>
              <CardDescription>
                {/* #688: never assert a count nothing loaded (see products). */}
                {loadFailed
                  ? "—"
                  : `${totalElements} order${totalElements !== 1 ? "s" : ""} in total${
                      statusFilter !== "ALL" ? ` (filtered: ${statusFilter})` : ""
                    }`}
              </CardDescription>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <div className="relative">
                <Search
                  className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
                  aria-hidden="true"
                />
                <Input
                  type="search"
                  aria-label="Search orders on this page"
                  placeholder="Order no., name or email"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="h-11 w-full pl-9 sm:h-10 sm:w-[240px]"
                />
              </div>
              <Select value={statusFilter} onValueChange={(v) => { setStatusFilter(v); setCurrentPage(0) }}>
                <SelectTrigger className="w-[160px]" aria-label="Filter orders by status">
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
            </div>
          </CardHeader>
          <CardContent>
            {loadFailed ? (
              <LoadErrorPanel
                subject="orders"
                message={loadErrorMessage}
                onRetry={() => fetchData()}
              />
            ) : filteredOrders.length === 0 && needle && visibleOrders.length > 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Search className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No orders match &ldquo;{searchQuery.trim()}&rdquo;
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  The search covers the orders on this page. Try another name, email or order number.
                </p>
                <Button onClick={() => setSearchQuery("")} variant="outline">
                  Clear search
                </Button>
              </div>
            ) : filteredOrders.length === 0 ? (
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
              <Table containerLabel="Orders table">
                <TableHeader>
                  <TableRow>
                    {showSelection && (
                      <TableHead className="w-11 px-1">
                        <div className="flex min-h-11 min-w-11 items-center justify-center">
                          <Checkbox
                            aria-label={SELECT_ALL_PENDING_LABEL}
                            checked={allPendingSelected}
                            onCheckedChange={(checked) =>
                              setSelectedIds(
                                checked === true ? new Set(pendingOnPage.map((o) => o.id)) : new Set()
                              )
                            }
                          />
                        </div>
                      </TableHead>
                    )}
                    <TableHead>Order ID</TableHead>
                    <TableHead>Customer</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Total</TableHead>
                    <TableHead>Created</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredOrders.map((order) => {
                    const config = statusConfig[order.status]
                    const StatusIcon = config.icon
                    const transitions = getAvailableTransitions(order)
                    const isProcessing = processingOrderId === order.id
                    const orderLabel = order.orderNumber ?? `${order.id.substring(0, 8)}...`

                    return (
                      <m.tr
                        key={order.id}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        className="group cursor-pointer hover:bg-slate-50"
                        // Phase 17-04 (VOPS-01): row click navigates to the
                        // dedicated detail route so vendors can issue refunds
                        // and use the full detail panel. The inline detail
                        // Dialog below is kept for v2.2 per 17-CONTEXT
                        // (deferred deprecation — frontend cleanup TBD).
                        onClick={(e) => {
                          // A click that started inside the actions cell is a
                          // button press, not a request to open the order.
                          if (
                            (e.target as HTMLElement).closest("[data-row-actions]")
                          ) {
                            return
                          }
                          router.push(`/dashboard/orders/${order.id}`)
                        }}
                      >
                        {/* 37-30 (UI-SPEC § D1): a 24px box in a 44px cell on PENDING rows only;
                            other rows keep an empty cell so the columns line up. The cell is a
                            row action, so the row's own click handler declines to navigate. */}
                        {showSelection && (
                          <TableCell className="w-11 px-1" data-row-actions>
                            {order.status === "PENDING" && (
                              <div className="flex min-h-11 min-w-11 items-center justify-center">
                                <Checkbox
                                  aria-label={`Select order ${orderLabel}`}
                                  checked={selectedIds.has(order.id)}
                                  onCheckedChange={(checked) => toggleSelected(order.id, checked === true)}
                                />
                              </div>
                            )}
                          </TableCell>
                        )}
                        <TableCell className="font-mono text-xs">
                          {/* QA-council FIX-5 (L1): customers quote the ORD-…
                              number from their receipt — show it here so a
                              vendor can match a phone enquiry to a row.
                              Legacy orders without a number keep the
                              truncated-UUID fallback. */}
                          {order.orderNumber ?? `${order.id.substring(0, 8)}...`}
                        </TableCell>
                        <TableCell>
                          <div>
                            {/* 37-30 (F20): the unverified-contact badge beside the name, from the
                                stored fact only — `false` renders it, null/absent never does. */}
                            <div className="flex flex-wrap items-center gap-2 font-medium">
                              <span>{order.customerName || "N/A"}</span>
                              <UnverifiedContactBadge contactVerified={order.contactVerified} />
                              {/* 30-06 (#958, UI-SPEC § S2): unpaid card / link orders, by the
                                  server's own rule; Submit/Confirm are not offered for them. */}
                              <AwaitingPaymentBadge
                                orderStatus={order.status}
                                paymentStatus={order.paymentStatus}
                                paymentReference={order.paymentReference}
                              />
                            </div>
                            {order.customerEmail && (
                              <div className="text-xs text-slate-500">
                                {order.customerEmail}
                              </div>
                            )}
                          </div>
                        </TableCell>
                        <TableCell>
                          {/* 37-12 (UI-SPEC § B4 "After"): a voided order stays listed,
                              keeps its status, and carries the Voided badge beside it. */}
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge
                              className={`${config.bgColor} flex w-fit items-center gap-1 text-white`}
                            >
                              <StatusIcon className="h-3 w-3" />
                              {config.label}
                            </Badge>
                            {order.voidedAt && <VoidedBadge />}
                          </div>
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
                          {/* `data-row-actions`, not an onClick shield. The
                              shield was `onClick={e => e.stopPropagation()}`
                              on a plain <div> — a `no-static-element-interactions`
                              defect whose whole job was to suppress an
                              ancestor. The row's own handler now declines to
                              navigate when the click originated inside this
                              container, which is the same rule expressed once,
                              at the element that owns the decision
                              (31-02 / LGL-02). */}
                          <div className="flex justify-end gap-2" data-row-actions>
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
                              <span className="text-xs text-slate-600">
                                No actions
                              </span>
                            )}
                          </div>
                        </TableCell>
                      </m.tr>
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

      {/* 37-30 (UI-SPEC § D1 "Bulk reject"): the sticky bottom action bar, bottom-fixed and
          safe-area padded, present only while something is selected. The same bar as K1's. */}
      {selectedPending.length > 0 && (
        <div
          role="region"
          aria-label="Selected orders"
          data-testid="orders-selection-bar"
          className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] shadow-[0_-4px_12px_rgba(15,23,42,0.08)]"
        >
          <div className="mx-auto flex max-w-screen-2xl flex-wrap items-center gap-3">
            <span className="text-sm font-semibold text-slate-900">
              {selectedPending.length} selected
            </span>
            <Button
              type="button"
              variant="outline"
              className="h-11 border-red-700 text-red-800 hover:bg-red-50 hover:text-red-900"
              onClick={() => setRejectDialogOpen(true)}
            >
              <Ban className="mr-2 h-4 w-4" aria-hidden="true" />
              {rejectSelectionCopy(selectedPending.length)}
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="h-11"
              onClick={() => setSelectedIds(new Set())}
            >
              {CLEAR_SELECTION_COPY}
            </Button>
          </div>
        </div>
      )}

      <RejectOrdersDialog
        open={rejectDialogOpen}
        onOpenChange={setRejectDialogOpen}
        orders={selectedPending}
        onRejected={() => {}}
        onClosedAfterSubmit={() => {
          // The list is re-read only now: fetchData swaps the page for its spinner, which would
          // unmount the dialog before the summary could be read.
          setSelectedIds(new Set())
          fetchData()
        }}
      />

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
              {/* D-08: pinned to the selected shop outside the All-shops context. */}
              <Select
                value={selectedShopId}
                onValueChange={(value) => {
                  setValue("shopId", value)
                  const allowedIds = new Set(
                    products
                      .filter((p) => p.shopId === value && p.available !== false)
                      .map((p) => p.id)
                  )
                  setOrderItems((prev) =>
                    prev.map((it) =>
                      it.productId && !allowedIds.has(it.productId)
                        ? { ...it, productId: "" }
                        : it
                    )
                  )
                }}
                disabled={!!contextShopId}
              >
                <SelectTrigger aria-label="Shop for this order">
                  <SelectValue placeholder="Select a shop" />
                </SelectTrigger>
                <SelectContent>
                  {(contextShopId
                    ? shops.filter((s) => s.id === contextShopId)
                    : shops
                  ).map((shop) => (
                    <SelectItem key={shop.id} value={shop.id}>
                      {shop.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {contextShopId && (
                <p className="text-xs text-slate-500">
                  Creating in your selected shop context. Switch to “All shops” to choose a different shop.
                </p>
              )}
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

            {/* COR-1 / E-1: how is this order fulfilled? Without this control every order the
                dialog created was silently a DELIVERY order with no address and no fee. */}
            <div className="space-y-2">
              <Label htmlFor="fulfilmentType">Fulfilment</Label>
              <Select
                value={selectedFulfilment ?? "COLLECTION"}
                onValueChange={(value) =>
                  setValue("fulfilmentType", value as "COLLECTION" | "DELIVERY", {
                    shouldValidate: true,
                  })
                }
              >
                <SelectTrigger id="fulfilmentType" aria-label="How this order is fulfilled">
                  <SelectValue placeholder="Collection" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="COLLECTION">Collection — customer picks it up</SelectItem>
                  <SelectItem value="DELIVERY">Delivery — to the address below</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-slate-500">
                {selectedFulfilment === "DELIVERY"
                  ? "The shop's delivery fee is applied by the server, and waived above the shop's free-delivery threshold."
                  : "No delivery fee. Choose Delivery to take a phone order for delivery."}
              </p>
            </div>

            {selectedFulfilment === "DELIVERY" && (
              <div className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-3">
                <Label className="text-sm font-semibold">Delivery address</Label>
                <div className="space-y-2">
                  <Label htmlFor="addressLine1" className="text-xs">Address line 1</Label>
                  <Input
                    id="addressLine1"
                    className="bg-white"
                    placeholder="e.g., 12 Coldharbour Lane"
                    {...register("addressLine1")}
                  />
                  {errors.addressLine1 && (
                    <p className="text-sm text-red-600">{errors.addressLine1.message}</p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="addressLine2" className="text-xs">Address line 2 (optional)</Label>
                  <Input
                    id="addressLine2"
                    className="bg-white"
                    placeholder="e.g., Flat 3"
                    {...register("addressLine2")}
                  />
                  {errors.addressLine2 && (
                    <p className="text-sm text-red-600">{errors.addressLine2.message}</p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="addressCity" className="text-xs">City</Label>
                  <Input
                    id="addressCity"
                    className="bg-white"
                    placeholder="e.g., London"
                    {...register("addressCity")}
                  />
                  {errors.addressCity && (
                    <p className="text-sm text-red-600">{errors.addressCity.message}</p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="addressPostcode" className="text-xs">Postcode</Label>
                  <Input
                    id="addressPostcode"
                    className="bg-white"
                    placeholder="e.g., SW9 8LF"
                    {...register("addressPostcode")}
                  />
                  {errors.addressPostcode && (
                    <p className="text-sm text-red-600">{errors.addressPostcode.message}</p>
                  )}
                </div>
              </div>
            )}

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

              {/* #485: the catalogue outran the picker's page bound. Say so — a
                  partial list that admits it is a different thing from one that
                  silently drops the tail, which was the whole defect. */}
              {productsTruncated && (
                <p
                  data-testid="order-products-truncated"
                  className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
                >
                  This product list is incomplete — your catalogue is larger than the
                  picker can load. Search for the product on the Products page if you
                  cannot find it here.
                </p>
              )}

              {orderItems.map((item, index) => (
                <div key={index} className="flex gap-2 items-start p-3 border rounded-lg bg-slate-50">
                  <div className="flex-1 space-y-2">
                    <Select
                      value={item.productId}
                      onValueChange={(value) => updateOrderItem(index, "productId", value)}
                    >
                      <SelectTrigger className="bg-white" aria-label={`Product for order item ${index + 1}`}>
                        <SelectValue placeholder="Select product" />
                      </SelectTrigger>
                      <SelectContent>
                        {pickerProducts.map((product) => (
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
                  <IconButton
                    type="button"
                    onClick={() => removeOrderItem(index)}
                    className="text-red-600 hover:bg-red-50 hover:text-red-700"
                    label={`Remove item ${index + 1} from this order`}
                    icon={<Trash2 className="h-4 w-4" />}
                  />
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
                  Created {formatUkDateTime(selectedOrderDetail.createdAt)}
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
                    <Table containerLabel="Order items table">
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
              {getAvailableTransitions(selectedOrderDetail).length > 0 && (
                <div className="flex justify-end gap-2 border-t pt-4">
                  {getAvailableTransitions(selectedOrderDetail).map((transition) => {
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
