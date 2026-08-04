"use client"

import { useEffect, useState, useCallback, useMemo, useRef } from "react"
import { m, AnimatePresence } from "framer-motion"
import apiClient from "@/lib/api-client"
import { useStomp } from "@/hooks/use-stomp"
import { useToast } from "@/hooks/use-toast"
import { useShopContext } from "@/hooks/use-shop-context"
import { fetchAllMyShops } from "@/lib/shops-api"
import {
  KITCHEN_STATUSES,
  fetchActiveKitchenOrders,
  fetchKitchenOrderDetails,
} from "@/lib/kitchen-orders-api"
import {
  deriveFeedState,
  KITCHEN_CLOCK_TICK_MS,
  KITCHEN_POLL_INTERVAL_MS,
} from "@/components/dashboard/kitchen/feed-state"
import {
  KdsFeedBanner,
  KdsFeedPill,
} from "@/components/dashboard/kitchen/kds-feed-status"
import {
  KdsAllShopsNotice,
  KdsBoardShopName,
} from "@/components/dashboard/kitchen/kds-board-scope"
import { useKitchenPrint } from "@/components/dashboard/kitchen/use-kitchen-print"
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
  Printer,
} from "lucide-react"
import type {
  OrderDetail,
  OrderStatus,
  OrderStateChangeEvent,
  Shop,
} from "@/types/api"

// --- Status config (subset for kitchen) ---

const statusConfig: Record<
  string,
  { label: string; bgColor: string; icon: React.ComponentType<{ className?: string }> }
> = {
  CONFIRMED: { label: "Confirmed", bgColor: "bg-blue-500", icon: CheckCircle2 },
  PREPARING: { label: "Preparing", bgColor: "bg-amber-500", icon: ChefHat },
  READY: { label: "Ready", bgColor: "bg-green-500", icon: Package },
}

// --- Bump actions per status ---

interface BumpAction {
  label: string
  endpoint: string
  color: string
}

const bumpActions: Record<string, BumpAction> = {
  CONFIRMED: { label: "Start Preparing", endpoint: "start-preparation", color: "bg-amber-600 hover:bg-amber-700" },
  PREPARING: { label: "Mark Ready", endpoint: "mark-ready", color: "bg-green-600 hover:bg-green-700" },
  READY: { label: "Complete", endpoint: "complete", color: "bg-emerald-600 hover:bg-emerald-700" },
}

// --- Age border colour ---

function ageBorderClass(createdAt: string): string {
  const minutes = (Date.now() - new Date(createdAt).getTime()) / 60000
  if (minutes < 5) return "border-green-500"
  if (minutes <= 15) return "border-yellow-500"
  return "border-red-500"
}

// --- Elapsed time display ---

// Cap/format the elapsed time so a stale order never renders raw uncapped
// minutes (the old "2245m ago" bug, backlog #12): <1m → "just now",
// <60m → "Xm ago", <24h → "Xh ago", ≥24h → "Xd ago".
function elapsedText(createdAt: string): string {
  const totalMinutes = Math.floor((Date.now() - new Date(createdAt).getTime()) / 60000)
  if (totalMinutes < 1) return "just now"
  if (totalMinutes < 60) return `${totalMinutes}m ago`
  const totalHours = Math.floor(totalMinutes / 60)
  if (totalHours < 24) return `${totalHours}h ago`
  const totalDays = Math.floor(totalHours / 24)
  return `${totalDays}d ago`
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
//
// Moved to lib/kitchen-orders-api.ts (#485) so the paging loop that filters on them
// and the page that renders them cannot drift apart. Re-exported nowhere: the import
// above is the single definition.

export default function KitchenPage() {
  const { toast } = useToast()

  // Shops
  const [shops, setShops] = useState<Shop[]>([])
  const [selectedShopId, setSelectedShopId] = useState<string | null>(null)

  // VSA-03: the global switcher is the single source of truth for which shop the
  // board shows. The local <Select> below stays for on-board convenience, but it
  // no longer owns an independent default. `null` = All shops.
  const { contextShopId, isAllShops } = useShopContext()

  // Orders map: orderId -> OrderDetail
  const [ordersMap, setOrdersMap] = useState<Map<string, OrderDetail>>(new Map())

  // Mute state
  const [muted, setMuted] = useState(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("kds-muted") === "true"
    }
    return false
  })

  // Tick counter for re-rendering elapsed times and the last-updated age.
  // Was 30s; now KITCHEN_CLOCK_TICK_MS (10s) because the staleness stamp added for
  // #106 has to be truthful to within a glance, and a 30s-granular "2m ago" on a
  // board someone is deciding to trust is not.
  const [tick, setTick] = useState(0)

  // Loading state
  const [loading, setLoading] = useState(true)

  // --- #106: feed liveness ---
  //
  // `lastSyncedAt` advances on every SUCCESSFUL read, so it is positive evidence the
  // board is current; `syncFailed` records a read that threw. Neither depends on the
  // socket's opinion of itself, which was measured lying (see feed-state.ts).
  const [lastSyncedAt, setLastSyncedAt] = useState<number | null>(null)
  const [syncFailed, setSyncFailed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [online, setOnline] = useState(true)

  // --- #485: the board is showing only part of this shop's orders ---
  const [ordersTruncated, setOrdersTruncated] = useState(false)

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

  // One-shot ember glow bookkeeping: ids in the FIRST loaded batch are seeded
  // without glowing (null until the first non-empty batch); ids appearing
  // later glow exactly once — this effect runs AFTER the glow render, so the
  // next render sees the id as seen and the keyframe never replays.
  const seenIdsRef = useRef<Set<string> | null>(null)
  useEffect(() => {
    if (seenIdsRef.current === null) {
      if (ordersMap.size > 0) seenIdsRef.current = new Set(ordersMap.keys())
      return
    }
    for (const id of ordersMap.keys()) seenIdsRef.current.add(id)
  }, [ordersMap])

  // --- Fetch shops on mount ---

  useEffect(() => {
    const fetchShops = async () => {
      try {
        // #485 (call site :175): was a single `/api/v1/shops?size=100`, which treated
        // page 0 as the whole list — a tenant past 100 shops silently lost the tail,
        // so a shop could be missing from the KDS selector with no error. This is the
        // SAME endpoint and the SAME truncation #282 fixed for the switcher, so it
        // reuses that loop rather than inventing a second one.
        const shopList: Shop[] = await fetchAllMyShops()
        // QA-council FIX-4 (M2 + L2): a blind shopList[0] default could select
        // a draft/junk shop, making the kitchen look idle while real orders
        // waited on a published shop — and the selector listed every draft.
        // Prefer published (Live) shops; fall back to all so a vendor with
        // only drafts still gets a working (never selector-empty) KDS.
        const publishedShops = shopList.filter((s) => s.published)
        const selectable = publishedShops.length > 0 ? publishedShops : shopList
        // 23-07 (VSA-03): the board's shop is no longer chosen here. Selection is
        // reconciled below against the GLOBAL switcher context so there is one
        // source of truth rather than two competing defaults.
        setShops(selectable)
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

  // --- Reconcile the board's shop with the global switcher context (VSA-03) ---
  //
  // A specific switcher context always wins — switching shop in the sidebar moves
  // the KDS board with no reload. In the All-shops context the board keeps any
  // on-board manual selection and falls back to the published-first default
  // (today's behaviour, QA-council FIX-4). A context shop that isn't selectable
  // (revoked/unpublished) degrades to that same fallback rather than crashing (D-13).
  useEffect(() => {
    if (shops.length === 0) return
    const inList = (id: string | null) => !!id && shops.some((s) => s.id === id)
    const next = inList(contextShopId)
      ? (contextShopId as string)
      : inList(selectedShopId)
        ? (selectedShopId as string)
        : shops[0].id
    if (next !== selectedShopId) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- derives the board shop from the fetched list + the hydrated switcher context
      setSelectedShopId(next)
    }
  }, [shops, contextShopId, selectedShopId])

  // --- Fetch all active orders for selected shop ---

  const fetchOrders = useCallback(
    /**
     * @param incremental re-use the detail already held for any ticket whose id AND
     *        status are unchanged. The liveness poll added for #106 runs on this path,
     *        which keeps a quiet board at ONE request per minute instead of one per
     *        minute per ticket — on a board of eighteen that is the difference between
     *        19 requests/min and 1, against a tenant rate limit of 100/min.
     *        Every other caller (shop switch, reconnect resync, manual refresh) takes
     *        the full path, so a detail edit that did not change status is still picked
     *        up the next time anything of consequence happens.
     */
    async (incremental = false) => {
      if (!selectedShopId) return
      try {
        // #485 (call site :229): was a single `?size=100`, so a shop past 100 lifetime
        // orders lost every ticket after the first page — silently, with no error and
        // no indicator. Raising the number cannot fix it: the API clamps page size at
        // 100 (measured; see fetchActiveKitchenOrders). This follows the list instead,
        // and reports back when even its own page bound was hit so the board can SAY so.
        const { orders: activeOrders, truncated } =
          await fetchActiveKitchenOrders(selectedShopId)

        const held = ordersMapRef.current
        const needDetail = incremental
          ? activeOrders.filter((o) => held.get(o.id)?.status !== o.status)
          : activeOrders
        const fetched = await fetchKitchenOrderDetails(needDetail)
        const byId = new Map(fetched.map((d) => [d.id, d]))

        const newMap = new Map<string, OrderDetail>()
        for (const o of activeOrders) {
          const detail = byId.get(o.id) ?? held.get(o.id)
          if (detail) newMap.set(o.id, detail)
        }
        setOrdersMap(newMap)
        setOrdersTruncated(truncated)
        // #106: a successful read is the ONLY thing that advances the stamp.
        setLastSyncedAt(Date.now())
        setSyncFailed(false)
      } catch {
        // #106: record the failure in the feed state as well as toasting it. A toast is
        // gone in five seconds; a kitchen board that stopped updating is wrong until
        // someone fixes it, so the page has to keep saying so.
        setSyncFailed(true)
        toast({
          variant: "destructive",
          title: "Error loading orders",
          description: "Could not fetch kitchen orders.",
        })
      }
    },
    [selectedShopId, toast]
  )

  /** Operator-triggered refresh from the stale banner (#106). */
  const handleManualRefresh = useCallback(async () => {
    setRefreshing(true)
    try {
      await fetchOrders()
    } finally {
      setRefreshing(false)
    }
  }, [fetchOrders])

  useEffect(() => {
    if (selectedShopId) {
      // Switching shops loads a different board — reseed the glow set from
      // that board's first batch instead of glowing every carried-over card.
      seenIdsRef.current = null
      fetchOrders()
    }
  }, [selectedShopId, fetchOrders])

  // --- Timer for elapsed time + last-updated age (#106) ---

  useEffect(() => {
    const interval = setInterval(() => setTick((t) => t + 1), KITCHEN_CLOCK_TICK_MS)
    return () => clearInterval(interval)
  }, [])

  // --- Polling safety net (#106) ---
  //
  // The socket is the FAST path, not the TRUSTED one. Measured against the live stack:
  // with the browser held offline for twelve seconds the board still read "Connected",
  // because the STOMP client had not yet noticed. A periodic read gives the page a
  // fact it owns — either it lands (the stamp advances, the board is genuinely
  // current) or it does not (the banner appears). Cheap: one request plus one per
  // active ticket, once a minute.
  useEffect(() => {
    if (!selectedShopId) return
    const interval = setInterval(() => {
      fetchOrders(true)
    }, KITCHEN_POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [selectedShopId, fetchOrders])

  // --- Browser connectivity (#106) ---
  //
  // `navigator.onLine` flips synchronously on the browser's own events, so this
  // reaches the operator long before a socket timeout would. Read after mount so SSR
  // and the first client render agree.
  useEffect(() => {
    setOnline(navigator.onLine)
    const goOffline = () => setOnline(false)
    const goOnline = () => {
      setOnline(true)
      // Re-read IMMEDIATELY rather than waiting out the poll interval. Measured
      // before this line existed: after a 110-second offline spell the board sat on
      // "Not updating" for the rest of the minute even though the network was back,
      // because `syncFailed` is only cleared by a successful read and the next one was
      // up to 60s away. A board that keeps warning after the problem is fixed teaches
      // the kitchen to ignore the warning.
      fetchOrders()
    }
    window.addEventListener("online", goOnline)
    window.addEventListener("offline", goOffline)
    return () => {
      window.removeEventListener("online", goOnline)
      window.removeEventListener("offline", goOffline)
    }
  }, [fetchOrders])

  // --- Derive WebSocket topic ---

  const tenantId = shops.length > 0 ? shops[0].tenantId : null
  // #266: one dot-separated segment after /topic/. Everything after the prefix becomes an
  // AMQP routing key on amq.topic, which may not contain '/' — the old slashed shape was
  // rejected by the relay broker in staging/production while working in dev's in-memory one.
  const stompTopic =
    tenantId && selectedShopId
      ? `/topic/kitchen.${tenantId}.${selectedShopId}`
      : null

  // --- Handle incoming WebSocket messages ---

  const handleWsMessage = useCallback(
    (event: OrderStateChangeEvent) => {
      const { orderId, newStatus, previousStatus } = event

      // #106: a frame off the socket is proof the feed is alive right now — advance
      // the stamp so a busy kitchen never sees a stale warning over live tickets.
      setLastSyncedAt(Date.now())
      setSyncFailed(false)

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

  // --- #450 5d: whose board is this? ---

  const selectedShop = shops.find((s) => s.id === selectedShopId) ?? null
  const selectedShopName = selectedShop?.name ?? null

  // --- #105: printing ---

  const { print, clear: clearPrintSheet, sheet: printSheet } =
    useKitchenPrint(selectedShopName)

  // Never let a ticket for one kitchen stay re-printable from another kitchen's board.
  useEffect(() => {
    clearPrintSheet()
  }, [selectedShopId, clearPrintSheet])

  // --- #106: feed liveness ---
  //
  // `tick` is in the dep list on purpose: the derivation reads a clock, so it has to
  // be recomputed on the same 10s beat that re-renders the ticket ages, or a board
  // left alone would keep reporting the age it had when it last re-rendered.
  const feedState = useMemo(
    () =>
      deriveFeedState({
        online,
        connected,
        reconnecting,
        lastSyncedAt,
        lastSyncFailed: syncFailed,
        now: Date.now(),
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `tick` is the clock beat; Date.now() is read fresh on each one
    [online, connected, reconnecting, lastSyncedAt, syncFailed, tick]
  )

  if (loading) {
    // Keep the HEADER, and reserve the grid's space rather than centring a spinner in
    // an otherwise empty page. The old version rendered a 128px spinner and then
    // swapped the entire board in underneath it, which is a whole-page layout shift on
    // every load — at the repo's declared throttle profile it was the single largest
    // contributor to /dashboard/kitchen's CLS. The `min-h` band below is deliberately
    // the same order of height as the first row of tickets, so the grid lands roughly
    // where the placeholder was instead of pushing everything down.
    return (
      <div className="space-y-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h1 className="text-4xl font-bold text-slate-900">Kitchen Display</h1>
            <KdsBoardShopName shopName={null} />
            <p className="mt-0.5 text-sm text-slate-500">
              Live order feed &mdash; bump orders through preparation stages
            </p>
          </div>
        </div>
        <div
          className="flex min-h-[16rem] items-center justify-center"
          role="status"
          aria-label="Loading kitchen orders"
        >
          <div className="h-16 w-16 motion-safe:animate-spin rounded-full border-b-2 border-t-2 border-primary" />
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Kitchen Display</h1>
          {/* #450 5d: the board names its shop before it says anything else about
              itself. This used to be inferable only from a 200px <Select> in the
              corner, while the dashboard switcher next to it said "All shops".
              The original tagline is kept below rather than displaced — it explains
              what the board is FOR to someone seeing it the first time, which the
              shop name does not. */}
          <KdsBoardShopName shopName={selectedShopName} />
          <p className="mt-0.5 text-sm text-slate-500">
            Live order feed &mdash; bump orders through preparation stages
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {/* #106: the connection state, legible from a wall mount. Replaces a
              h-2.5 w-2.5 dot whose label was `hidden sm:inline`. */}
          <KdsFeedPill state={feedState} lastSyncedAt={lastSyncedAt} />

          {/* Shop selector */}
          {shops.length > 0 && (
            <Select
              value={selectedShopId || ""}
              onValueChange={(v) => setSelectedShopId(v)}
            >
              <SelectTrigger className="w-[200px]" aria-label="Kitchen display shop">
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

          {/* #105: print every ticket on the board, one per page — the start-of-shift
              and printer-jam case. Per-ticket printing lives on each card. */}
          <Button
            variant="outline"
            onClick={() => print(sortedOrders)}
            disabled={sortedOrders.length === 0}
            title="Print all tickets on this board"
            className="kds-press"
          >
            <Printer className="mr-2 h-5 w-5" />
            Print all
          </Button>

          {/* Mute toggle */}
          <Button
            variant="outline"
            size="icon"
            onClick={toggleMute}
            title={muted ? "Unmute alerts" : "Mute alerts"}
            className="kds-press"
          >
            {muted ? (
              <VolumeX className="h-5 w-5" />
            ) : (
              <Volume2 className="h-5 w-5" />
            )}
          </Button>
        </div>
      </div>

      {/* #106: the stale/offline banner. Rendered above the tickets because it
          qualifies every ticket underneath it.

          It animates because an element that pops into a live board mid-service
          reads as a rendering glitch, not a warning — the purpose is "prevent a
          jarring change", not decoration. Enter is 200ms ease-out (fast start:
          the operator is looking at the board, and this must register); exit is
          140ms, deliberately quicker, because the system responding is not
          something anyone needs to watch. `MotionConfig reducedMotion="user"`
          in motion-provider.tsx already strips the movement for anyone who
          asked for that. */}
      <AnimatePresence initial={false}>
        {feedState.alerting && (
          <m.div
            key="kds-feed-banner"
            initial={{ opacity: 0, transform: "translateY(-8px)" }}
            animate={{
              opacity: 1,
              transform: "translateY(0px)",
              transition: { duration: 0.2, ease: [0.23, 1, 0.32, 1] },
            }}
            exit={{
              opacity: 0,
              transform: "translateY(-8px)",
              transition: { duration: 0.14, ease: [0.23, 1, 0.32, 1] },
            }}
          >
            <KdsFeedBanner
              state={feedState}
              lastSyncedAt={lastSyncedAt}
              onRefresh={handleManualRefresh}
              refreshing={refreshing}
            />
          </m.div>
        )}
      </AnimatePresence>

      {/* #450 5d: name the mismatch rather than letting the operator find it. */}
      {isAllShops && (
        <KdsAllShopsNotice shopName={selectedShopName} shopCount={shops.length} />
      )}

      {/* #485: the board bound out before the API said the list had ended. Say so —
          an incomplete board that admits it is a different thing from one that lies. */}
      {ordersTruncated && (
        <div
          role="status"
          data-testid="kds-truncated-notice"
          className="rounded-lg border-2 border-amber-500 bg-amber-50 px-4 py-3 text-sm font-medium text-amber-900"
        >
          This shop has more order history than the board reads in one go. Older
          tickets may not be shown &mdash; check the Orders screen if a ticket is
          missing.
        </div>
      )}

      {/* Order cards grid.
          `loading` above only covers the SHOP list; the orders arrive after it, so the
          board used to render "No active orders" in the gap and then replace it with a
          full grid. That is a claim the board had not yet checked — the same class of
          untruth as #450 5d — and it was the largest remaining layout shift on the
          route, measured at the declared throttle profile. `lastSyncedAt === null` is
          exactly "orders have never been read", so it drives both fixes at once. */}
      {lastSyncedAt === null && sortedOrders.length === 0 ? (
        <div
          className="flex min-h-[16rem] items-center justify-center"
          role="status"
          aria-label="Loading kitchen orders"
        >
          <div className="h-16 w-16 motion-safe:animate-spin rounded-full border-b-2 border-t-2 border-primary" />
        </div>
      ) : sortedOrders.length === 0 ? (
        <div className="flex min-h-[16rem] flex-col items-center justify-center py-24 text-center">
          <ChefHat className="mb-4 h-16 w-16 text-slate-300" />
          <h3 className="mb-2 text-xl font-semibold text-slate-900">
            No active orders
          </h3>
          <p className="text-sm text-slate-500">
            Orders will appear here when customers place them
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <AnimatePresence mode="popLayout">
            {sortedOrders.map((order) => {
              const config = statusConfig[order.status]
              const action = bumpActions[order.status]
              const StatusIcon = config?.icon || Clock
              const itemNames =
                order.items
                  ?.map((item) => item.productName)
                  .join(", ") || "No items"
              const itemSummary =
                order.items && order.items.length > 0
                  ? `${order.items.length} item${order.items.length !== 1 ? "s" : ""}`
                  : "No items"
              const isNew =
                seenIdsRef.current !== null && !seenIdsRef.current.has(order.id)

              return (
                <m.div
                  layout
                  key={order.id}
                  initial={{ opacity: 0, scale: 0.92 }}
                  animate={
                    isNew
                      ? {
                          opacity: 1,
                          scale: 1,
                          // One-shot ember glow (sketch --shadow-glow-ember)
                          boxShadow: [
                            "0 0 0 rgba(249,115,22,0)",
                            "0 8px 32px rgba(249,115,22,0.35)",
                            "0 0 0 rgba(249,115,22,0)",
                          ],
                        }
                      : { opacity: 1, scale: 1 }
                  }
                  exit={{ opacity: 0, scale: 0.92 }}
                  transition={isNew ? { boxShadow: { duration: 1.6 } } : undefined}
                  className="rounded-lg"
                >
                  <Card
                    className={`border-2 ${ageBorderClass(order.createdAt)} transition-colors`}
                  >
                    <CardHeader className="pb-3">
                      <div className="flex items-start justify-between gap-2">
                        <CardTitle className="min-w-0 truncate text-lg font-semibold">
                          {order.orderNumber || `#${order.id.substring(0, 6)}`}
                        </CardTitle>
                        {config && (
                          <Badge className={`${config.bgColor} flex flex-shrink-0 items-center gap-1 text-white`}>
                            <StatusIcon className="h-3 w-3" />
                            {config.label}
                          </Badge>
                        )}
                      </div>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      {/* Customer name */}
                      <div className="text-sm font-medium text-slate-700">
                        {order.customerName || "Walk-in"}
                      </div>
    
                      {/* Items */}
                      <div className="text-sm text-slate-600">
                        <span className="font-medium">{itemSummary}</span>
                        {order.items && order.items.length > 0 && (
                          <div className="mt-1 text-xs text-slate-500">
                            {order.items.map((item, i) => (
                              <span key={item.id || i}>
                                {i > 0 && ", "}
                                {item.quantity}x {item.productName}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
    
                      {/* Elapsed time */}
                      <div className="flex items-center gap-1 text-xs text-slate-500">
                        <Clock className="h-3 w-3" />
                        {elapsedText(order.createdAt)}
                      </div>
    
                      {/* Actions. The bump keeps the full width it has always had —
                          it is the one control pressed a hundred times a service, and
                          #105's print must not shrink it. Print sits beside it as an
                          icon button with an accessible name.

                          Both are h-11 (44px): the shadcn default is h-10, which
                          measured 40x40 for the print button on a 375px iPhone SE
                          profile — under the 44px minimum for a target pressed by a
                          cook's thumb. Enlarging the bump alongside it is not a trade,
                          it is the same control with more of it to hit. */}
                      <div className="flex items-stretch gap-2">
                        {action && (
                          <Button
                            className={`h-11 w-full ${action.color} text-white kds-press`}
                            onClick={() => handleBump(order.id, order.status)}
                          >
                            {order.status === "CONFIRMED" && <ArrowRight className="mr-2 h-4 w-4" />}
                            {order.status === "PREPARING" && <Package className="mr-2 h-4 w-4" />}
                            {order.status === "READY" && <CheckCircle2 className="mr-2 h-4 w-4" />}
                            {action.label}
                          </Button>
                        )}
                        <Button
                          variant="outline"
                          size="icon"
                          onClick={() => print([order])}
                          aria-label={`Print ticket ${order.orderNumber || order.id.substring(0, 6)}`}
                          title="Print ticket"
                          className="h-11 w-11 flex-shrink-0 kds-press"
                        >
                          <Printer className="h-4 w-4" />
                        </Button>
                      </div>
                    </CardContent>
                  </Card>
                </m.div>
              )
            })}
          </AnimatePresence>
        </div>
      )}

      {/* #105: the ticket sheet, portalled to <body> so one @media print rule in
          globals.css can hide the dashboard chrome without editing the shell. */}
      {printSheet}
    </div>
  )
}
