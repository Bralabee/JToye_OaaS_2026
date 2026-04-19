"use client"

import { useEffect, useState, useCallback, useRef } from "react"
import apiClient from "@/lib/api-client"
import { useStomp } from "@/hooks/use-stomp"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import {
  ChefHat,
  Volume2,
  VolumeX,
  Clock,
  ArrowRight,
  CheckCircle2,
  Package,
} from "lucide-react"
import type {
  Order,
  OrderDetail,
  OrderStatus,
  OrderStateChangeEvent,
  Shop,
} from "@/types/api"

// --- Status config (subset for kitchen) ---

type KitchenBadgeVariant = "info" | "brand" | "success"

const statusConfig: Record<
  string,
  { label: string; variant: KitchenBadgeVariant; icon: React.ComponentType<{ className?: string }> }
> = {
  CONFIRMED: { label: "Confirmed", variant: "info", icon: CheckCircle2 },
  PREPARING: { label: "Preparing", variant: "brand", icon: ChefHat },
  READY: { label: "Ready", variant: "success", icon: Package },
}

// --- Bump actions per status ---

type BumpVariant = "primary" | "editorial" | "secondary"

interface BumpAction {
  label: string
  endpoint: string
  variant: BumpVariant
}

const bumpActions: Record<string, BumpAction> = {
  CONFIRMED: { label: "Start Preparing", endpoint: "start-preparation", variant: "editorial" },
  PREPARING: { label: "Mark Ready", endpoint: "mark-ready", variant: "primary" },
  READY: { label: "Complete", endpoint: "complete", variant: "secondary" },
}

// --- Age border colour ---

function ageBorderClass(createdAt: string): string {
  const minutes = (Date.now() - new Date(createdAt).getTime()) / 60000
  if (minutes < 5) return "border-success"
  if (minutes <= 15) return "border-warning"
  return "border-danger"
}

// --- Elapsed time display ---

function elapsedText(createdAt: string): string {
  const minutes = Math.floor((Date.now() - new Date(createdAt).getTime()) / 60000)
  if (minutes < 1) return "<1m ago"
  return `${minutes}m ago`
}

// --- Audio beep ---

function playBeep() {
  try {
    const ctx = new AudioContext()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = "sine"
    osc.frequency.setValueAtTime(800, ctx.currentTime)
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.2)
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + 0.2)
    // Clean up after playback
    setTimeout(() => ctx.close(), 500)
  } catch {
    // AudioContext may fail without user gesture — ignore silently
  }
}

// --- Kitchen statuses we track ---

const KITCHEN_STATUSES: OrderStatus[] = ["CONFIRMED", "PREPARING", "READY"]

export default function KitchenPage() {
  const { toast } = useToast()

  // Shops
  const [shops, setShops] = useState<Shop[]>([])
  const [selectedShopId, setSelectedShopId] = useState<string | null>(null)

  // Orders map: orderId -> OrderDetail
  const [ordersMap, setOrdersMap] = useState<Map<string, OrderDetail>>(new Map())

  // Mute state
  const [muted, setMuted] = useState(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("kds-muted") === "true"
    }
    return false
  })

  // Tick counter for re-rendering elapsed times every 30s
  const [, setTick] = useState(0)

  // Loading state
  const [loading, setLoading] = useState(true)

  // Ref for mute to avoid stale closure in WS callback
  const mutedRef = useRef(muted)
  useEffect(() => {
    mutedRef.current = muted
  }, [muted])

  // Ref for ordersMap to avoid stale closure
  const ordersMapRef = useRef(ordersMap)
  useEffect(() => {
    ordersMapRef.current = ordersMap
  }, [ordersMap])

  // --- Fetch shops on mount ---

  useEffect(() => {
    const fetchShops = async () => {
      try {
        const res = await apiClient.get("/api/v1/shops?size=100")
        const shopList: Shop[] = res.data.content || []
        setShops(shopList)
        if (shopList.length > 0) {
          setSelectedShopId(shopList[0].id)
        }
      } catch {
        toast({
          variant: "destructive",
          title: "Error loading shops",
          description: "Could not fetch shop list.",
        })
      } finally {
        setLoading(false)
      }
    }
    fetchShops()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // --- Fetch all active orders for selected shop ---

  const fetchOrders = useCallback(async () => {
    if (!selectedShopId) return
    try {
      const res = await apiClient.get(
        `/api/v1/orders?shopId=${selectedShopId}&size=100&sort=createdAt,desc`
      )
      const allOrders: Order[] = res.data.content || []
      const activeOrders = allOrders.filter((o) =>
        KITCHEN_STATUSES.includes(o.status)
      )

      // Fetch detail for each active order
      const detailPromises = activeOrders.map((o) =>
        apiClient.get(`/api/v1/orders/${o.id}/detail`).then((r) => r.data as OrderDetail)
      )
      const details = await Promise.all(detailPromises)

      const newMap = new Map<string, OrderDetail>()
      for (const d of details) {
        newMap.set(d.id, d)
      }
      setOrdersMap(newMap)
    } catch {
      toast({
        variant: "destructive",
        title: "Error loading orders",
        description: "Could not fetch kitchen orders.",
      })
    }
  }, [selectedShopId, toast])

  useEffect(() => {
    if (selectedShopId) {
      fetchOrders()
    }
  }, [selectedShopId, fetchOrders])

  // --- Timer for elapsed time updates (30s) ---

  useEffect(() => {
    const interval = setInterval(() => setTick((t) => t + 1), 30000)
    return () => clearInterval(interval)
  }, [])

  // --- Derive WebSocket topic ---

  const tenantId = shops.length > 0 ? shops[0].tenantId : null
  const stompTopic =
    tenantId && selectedShopId
      ? `/topic/kitchen/${tenantId}/${selectedShopId}`
      : null

  // --- Handle incoming WebSocket messages ---

  const handleWsMessage = useCallback(
    (event: OrderStateChangeEvent) => {
      const { orderId, newStatus, previousStatus } = event

      // If new status is a kitchen status, fetch detail and add/update
      if (KITCHEN_STATUSES.includes(newStatus)) {
        apiClient
          .get(`/api/v1/orders/${orderId}/detail`)
          .then((res) => {
            const detail: OrderDetail = res.data
            setOrdersMap((prev) => {
              const next = new Map(prev)
              next.set(orderId, detail)
              return next
            })
          })
          .catch(() => {
            // If fetch fails, ignore — will sync on next reconnect
          })

        // Play audio if this is a genuinely NEW order entering kitchen view
        const wasInKitchen = KITCHEN_STATUSES.includes(previousStatus)
        if (!wasInKitchen && !mutedRef.current) {
          playBeep()
        }
      } else if (newStatus === "COMPLETED" || newStatus === "CANCELLED") {
        // Remove from display
        setOrdersMap((prev) => {
          const next = new Map(prev)
          next.delete(orderId)
          return next
        })
      }
    },
    []
  )

  // --- WebSocket connection ---

  const { connected, reconnecting } = useStomp(stompTopic, handleWsMessage, fetchOrders)

  // --- Mute toggle ---

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    localStorage.setItem("kds-muted", String(next))
  }

  // --- Status bump ---

  const handleBump = async (orderId: string, currentStatus: OrderStatus) => {
    const action = bumpActions[currentStatus]
    if (!action) return

    // Optimistic update
    setOrdersMap((prev) => {
      const next = new Map(prev)
      const existing = next.get(orderId)
      if (!existing) return prev

      if (currentStatus === "READY") {
        // Completing — remove card
        next.delete(orderId)
      } else {
        // Bump to next status
        const nextStatus: OrderStatus =
          currentStatus === "CONFIRMED" ? "PREPARING" : "READY"
        next.set(orderId, { ...existing, status: nextStatus })
      }
      return next
    })

    try {
      await apiClient.post(`/api/v1/orders/${orderId}/${action.endpoint}`)
    } catch {
      // Revert on error — refetch
      toast({
        variant: "destructive",
        title: "Error updating order",
        description: `Failed to ${action.label.toLowerCase()}. Reverting.`,
      })
      fetchOrders()
    }
  }

  // --- Sorted orders (newest first) ---

  const sortedOrders = Array.from(ordersMap.values()).sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )

  // --- Connection status dot ---

  const connectionDot = connected
    ? "bg-success"
    : reconnecting
      ? "bg-warning"
      : "bg-ink-tertiary"

  const connectionLabel = connected
    ? "Connected"
    : reconnecting
      ? "Reconnecting..."
      : "Disconnected"

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
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="font-display text-4xl font-semibold tracking-tight text-ink-primary">Kitchen Display</h1>
          <p className="mt-1 text-ink-secondary">
            Live order feed &mdash; bump orders through preparation stages
          </p>
        </div>

        <div className="flex items-center gap-3">
          {/* Connection status */}
          <div className="flex items-center gap-2 text-sm text-ink-secondary" title={connectionLabel}>
            <span className={`h-2.5 w-2.5 rounded-full ${connectionDot}`} aria-hidden="true" />
            <span className="hidden sm:inline">{connectionLabel}</span>
          </div>

          {/* Shop selector */}
          {shops.length > 0 && (
            <Select
              value={selectedShopId || ""}
              onValueChange={(v) => setSelectedShopId(v)}
            >
              <SelectTrigger className="w-[200px]">
                <SelectValue placeholder="Select shop" />
              </SelectTrigger>
              <SelectContent>
                {shops.map((shop) => (
                  <SelectItem key={shop.id} value={shop.id}>
                    {shop.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}

          {/* Mute toggle */}
          <Button
            variant="secondary"
            size="icon"
            onClick={toggleMute}
            title={muted ? "Unmute alerts" : "Mute alerts"}
            aria-label={muted ? "Unmute alerts" : "Mute alerts"}
          >
            {muted ? (
              <VolumeX className="h-5 w-5" />
            ) : (
              <Volume2 className="h-5 w-5" />
            )}
          </Button>
        </div>
      </div>

      {/* Order cards grid */}
      {sortedOrders.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 text-center">
          <ChefHat className="mb-4 h-16 w-16 text-ink-tertiary" aria-hidden="true" />
          <h3 className="mb-2 font-display text-xl font-semibold text-ink-primary">
            No active orders
          </h3>
          <p className="text-sm text-ink-tertiary">
            Orders will appear here when customers place them
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {sortedOrders.map((order) => {
            const config = statusConfig[order.status]
            const action = bumpActions[order.status]
            const StatusIcon = config?.icon || Clock
            const itemSummary =
              order.items && order.items.length > 0
                ? `${order.items.length} item${order.items.length !== 1 ? "s" : ""}`
                : "No items"

            return (
              <Card
                key={order.id}
                variant="lifted"
                className={`border-2 ${ageBorderClass(order.createdAt)} transition-colors`}
              >
                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="font-display font-mono tabular-nums text-2xl font-semibold text-ink-primary">
                      {order.orderNumber || `#${order.id.substring(0, 6)}`}
                    </CardTitle>
                    {config && (
                      <Badge variant={config.variant} size="md">
                        <StatusIcon className="h-3 w-3" />
                        {config.label}
                      </Badge>
                    )}
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  {/* Customer name */}
                  <div className="text-base font-medium text-ink-primary">
                    {order.customerName || "Walk-in"}
                  </div>

                  {/* Items */}
                  <div className="text-sm text-ink-secondary">
                    <span className="font-medium">{itemSummary}</span>
                    {order.items && order.items.length > 0 && (
                      <div className="mt-1 text-sm text-ink-primary leading-relaxed">
                        {order.items.map((item, i) => (
                          <span key={item.id || i}>
                            {i > 0 && ", "}
                            <span className="font-mono tabular-nums font-semibold">{item.quantity}</span>x {item.productName}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Elapsed time */}
                  <div className="flex items-center gap-1 text-xs text-ink-tertiary">
                    <Clock className="h-3 w-3" aria-hidden="true" />
                    {elapsedText(order.createdAt)}
                  </div>

                  {/* Bump button — large for touch (min 48px) */}
                  {action && (
                    <Button
                      variant={action.variant}
                      size="lg"
                      className="w-full min-h-[48px]"
                      onClick={() => handleBump(order.id, order.status)}
                    >
                      {order.status === "CONFIRMED" && <ArrowRight className="mr-2 h-4 w-4" />}
                      {order.status === "PREPARING" && <Package className="mr-2 h-4 w-4" />}
                      {order.status === "READY" && <CheckCircle2 className="mr-2 h-4 w-4" />}
                      {action.label}
                    </Button>
                  )}
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}
    </div>
  )
}
