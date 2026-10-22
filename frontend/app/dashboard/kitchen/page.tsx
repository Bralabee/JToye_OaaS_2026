"use client"

import { useEffect, useState, useCallback, useMemo, useRef, useSyncExternalStore } from "react"
import { m, AnimatePresence } from "framer-motion"
import apiClient from "@/lib/api-client"
import { useStomp } from "@/hooks/use-stomp"
import { useToast } from "@/hooks/use-toast"
import { useShopContext } from "@/hooks/use-shop-context"
import { fetchAllMyShops } from "@/lib/shops-api"
import {
  KITCHEN_STATUSES,
  NEW_LANE_STATUS,
  fetchKitchenBoard,
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
  KdsOtherShopNotice,
} from "@/components/dashboard/kitchen/kds-board-scope"
import { useKitchenPrint } from "@/components/dashboard/kitchen/use-kitchen-print"
import { OrderAllergenBanner } from "@/components/dashboard/kitchen/order-allergen-banner"
import {
  AllergyNoteBlock,
  type AllergyNoteAcknowledgement,
} from "@/components/dashboard/kitchen/allergy-note-block"
import { KdsNewLane } from "@/components/dashboard/kitchen/kds-new-lane"
import { UnverifiedContactBadge } from "@/components/dashboard/kitchen/unverified-contact-badge"
import { AwaitingPaymentBadge } from "@/components/dashboard/orders/awaiting-payment-badge"
import { describeLoadError } from "@/lib/human-error"
import { KdsItemList, elapsedText } from "@/components/dashboard/kitchen/kds-card-parts"
import { KdsFulfilmentBadge } from "@/components/dashboard/kitchen/kds-fulfilment-badge"
import { KdsCustomerNote } from "@/components/dashboard/kitchen/kds-customer-note"
import {
  KdsSoundBar,
  KdsSoundControl,
  kitchenTabTitle,
  useKitchenAlert,
} from "@/components/dashboard/kitchen/kds-sound"
import {
  isDeliberateSignOut,
  KdsBoardStopped,
  markDeliberateSignOut,
  STOPPED_TAB_TITLE,
  type BoardStoppedReason,
} from "@/components/dashboard/kitchen/kds-board-stopped"
import { useSessionKeepAlive } from "@/components/dashboard/kitchen/use-session-keep-alive"
import {
  PauseOrdersControl,
  useShopPause,
} from "@/components/dashboard/kitchen/pause-orders-control"
import { PausedBanner } from "@/components/dashboard/kitchen/paused-banner"
import {
  KdsCookSheetPanel,
  KdsScheduledWindowBadge,
} from "@/components/dashboard/kitchen/kds-cook-sheet-panel"
import { SESSION_LAPSED_EVENT } from "@/lib/session-lapse"
import { SHOP_ACCESS_DENIED_EVENT } from "@/lib/access-events"
import { vendorLogout } from "@/lib/vendor-logout"
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
  ClipboardList,
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
  /** 30-06 (UI-SPEC § S2): the verb in "Couldn't {verb} this order. Check your connection…". */
  failureVerb: string
}

const bumpActions: Record<string, BumpAction> = {
  CONFIRMED: { label: "Start Preparing", endpoint: "start-preparation", color: "bg-amber-600 hover:bg-amber-700", failureVerb: "start preparing" },
  PREPARING: { label: "Mark Ready", endpoint: "mark-ready", color: "bg-green-600 hover:bg-green-700", failureVerb: "mark ready" },
  READY: { label: "Complete", endpoint: "complete", color: "bg-emerald-600 hover:bg-emerald-700", failureVerb: "complete" },
}

/** The kitchen's fallback when a bump got no answer at all (UI-SPEC copy row "Kitchen refusal toast"). */
function bumpFailedCopy(failureVerb: string): string {
  return `Couldn't ${failureVerb} this order. Check your connection and try again.`
}

// --- Age border colour ---

function ageBorderClass(createdAt: string): string {
  const minutes = (Date.now() - new Date(createdAt).getTime()) / 60000
  if (minutes < 5) return "border-green-500"
  if (minutes <= 15) return "border-yellow-500"
  return "border-red-500"
}

// --- Elapsed time display ---
//
// `elapsedText` (the #12 cap) moved to kds-card-parts.tsx in 37-16 so the New lane renders the
// same age the grid does; a Next page module cannot export it.

// --- New-order sound ---
//
// 37-17 (D-12): the one-shot `playBeep` that lived here built a fresh AudioContext inside the
// socket callback, outside any user gesture, so the browser suspended it and the error was
// swallowed: a new order could arrive in silence. The alert is now `useKitchenAlert`
// (components/dashboard/kitchen/kds-sound.tsx): one context, unlocked by the "Turn on sound" tap,
// repeating while an order waits in the New lane.

// --- Kitchen statuses we track ---
//
// Moved to lib/kitchen-orders-api.ts (#485) so the paging loop that filters on them
// and the page that renders them cannot drift apart. Re-exported nowhere: the import
// above is the single definition.

// --- #536: the board's reserved height ---
//
// ONE band, used by the loading skeleton, the empty state and the loaded grid alike,
// so the region the board occupies is decided before any data arrives and does not
// change when it does.
//
// WHY A VIEWPORT-SIZED RESERVE AND NOT A TICKET-COUNT-SIZED ONE. #536 proposed sizing
// the placeholder to the expected ticket count. Measuring the route first (390px
// `isMobile` + `hasTouch`, Fast-3G 1.5Mbps/40ms, 4x CPU, real API, an 18-ticket board)
// showed the count is not what the score is made of. The single largest shift, 0.6593
// of a total 0.8287, is the SHELL FOOTER — `dashboard-shell.tsx` renders it directly
// under the board, the old 16rem band left it sitting at y=797 in an 844px viewport,
// and the grid arriving shoved it 4574px down while it was still on screen. Whether
// the reserve is 4 tickets tall or 18 changes nothing about that; whether it clears
// the fold changes everything. `100svh` (not `dvh`) because a KDS is a wall screen and
// a reserve that breathes with a mobile URL bar would reintroduce the shift it exists
// to remove.
//
// It is a floor, never a ceiling: a taller board grows past it (the footer is already
// off-screen, so nothing in view moves), and a one-ticket board keeps it (the footer
// stays exactly where the skeleton left it instead of jumping UP into view).
const BOARD_RESERVE = "min-h-[calc(100svh_-_11rem)]"

/** Skeleton cards rendered while the board is being read. */
function KdsBoardSkeleton() {
  return (
    // `overflow-hidden` + the exact reserve height, so the skeleton cannot decide the
    // page's height. It is free to render more cards than fit — the count only has to
    // be enough to fill the widest breakpoint's first rows, and the clip guarantees the
    // outer box is the reserve whatever it renders. `aria-hidden`: the bones say
    // nothing the `role="status"` wrapper has not already said.
    <div
      aria-hidden
      className="grid h-[calc(100svh_-_11rem)] grid-cols-1 gap-4 overflow-hidden sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
    >
      {Array.from({ length: 12 }, (_, i) => (
        <div
          key={i}
          data-testid="kds-ticket-skeleton"
          className="rounded-lg border-2 border-slate-200 bg-white p-4"
        >
          <div className="flex items-start justify-between gap-2">
            <div className="h-6 w-24 rounded bg-slate-200 motion-safe:animate-pulse" />
            <div className="h-6 w-20 rounded-full bg-slate-200 motion-safe:animate-pulse" />
          </div>
          <div className="mt-4 h-4 w-28 rounded bg-slate-100 motion-safe:animate-pulse" />
          <div className="mt-3 h-4 w-20 rounded bg-slate-100 motion-safe:animate-pulse" />
          <div className="mt-1 h-3 w-40 rounded bg-slate-100 motion-safe:animate-pulse" />
          <div className="mt-3 h-3 w-16 rounded bg-slate-100 motion-safe:animate-pulse" />
          <div className="mt-3 flex items-stretch gap-2">
            <div className="h-11 w-full rounded-md bg-slate-200 motion-safe:animate-pulse" />
            <div className="h-11 w-11 flex-shrink-0 rounded-md bg-slate-200 motion-safe:animate-pulse" />
          </div>
        </div>
      ))}
    </div>
  )
}

// #709: stable subscribe for the `online` external store — module scope so the
// reference never changes and useSyncExternalStore never resubscribes.
function subscribeToConnectivity(onStoreChange: () => void): () => void {
  window.addEventListener("online", onStoreChange)
  window.addEventListener("offline", onStoreChange)
  return () => {
    window.removeEventListener("online", onStoreChange)
    window.removeEventListener("offline", onStoreChange)
  }
}

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

  // Clock beat for re-rendering elapsed times and the last-updated age.
  // Was 30s; now KITCHEN_CLOCK_TICK_MS (10s) because the staleness stamp added for
  // #106 has to be truthful to within a glance, and a 30s-granular "2m ago" on a
  // board someone is deciding to trust is not.
  // #709 (react-hooks 7.1): the beat CARRIES the timestamp instead of a bare
  // counter, so consumers (the feed-state memo) read `tick` as "now" without
  // calling Date.now() during render. Same 10s granularity as before — the memo
  // only ever recomputed on the beat anyway.
  const [tick, setTick] = useState(() => Date.now())

  // Loading state
  const [loading, setLoading] = useState(true)

  // GAP-GTM-02 (V87 / ADR-0007): consolidated batch prep "Cook Sheet" panel,
  // toggled on demand so the live ticket board stays a 1-request initial mount.
  const [cookSheetOpen, setCookSheetOpen] = useState(false)

  // --- 37-18 (D-14, UI-SPEC K2): the board has stopped ---
  //
  // `null` while the board works. "signedOut" when the session lapsed (a 401 the api-client's one
  // silent refresh could not fix); "noAccess" when the server refused a kitchen read or bump with
  // the typed shop-access-denied 403 (D-08). Once stopped, the board stays stopped until the page is
  // left: the only ways out are signing in again or signing out, and both navigate away. A lapse
  // outranks an access refusal (the pill's precedence): signing in again is the first thing to do.
  const [stopped, setStopped] = useState<BoardStoppedReason | null>(null)
  // "Silence alarm" stops the sound only; the red screen stays (UI-SPEC K2).
  const [alarmSilenced, setAlarmSilenced] = useState(false)

  // The api-client announces a lapse as a CANCELLABLE `jtoye:session-lapsed` event and redirects to
  // /auth/signin only if nobody claims it. The kitchen claims it: a board that quietly becomes a
  // sign-in page misses every order until someone walks up to it (UXT-021).
  //
  // Except after a DELIBERATE sign-out (goods P2-TUN-14): someone pressed Sign out, so the session
  // ending is the point. The event is left unclaimed and the browser goes where it was going, with
  // no red screen and no alarm.
  useEffect(() => {
    const onSessionLapsed = (event: Event) => {
      if (isDeliberateSignOut()) return
      event.preventDefault()
      setStopped("signedOut")
    }
    window.addEventListener(SESSION_LAPSED_EVENT, onSessionLapsed)
    return () => window.removeEventListener(SESSION_LAPSED_EVENT, onSessionLapsed)
  }, [])

  // D-08 on the kitchen route: any shop-access-denied 403 on this page stops the board with the
  // access-ended variant. The api-client sends SHOP_ACCESS_DENIED_EVENT for every such refusal; on
  // this route every API call is the board's own (its read, the per-order detail, a bump, Accept,
  // Reject, the allergy acknowledgement), so listening here covers the New lane's card handlers
  // too. The dashboard shell's no-access page deliberately leaves this route alone (37-06).
  //
  // Owner ruling 2026-10-10 (D-08): this holds only because every call on this route has the STAFF
  // floor or none — the board read (OrderService.java:452), order detail (:324), the transitions
  // (:560), the allergy acknowledgement (:648), the shop read (ShopService.java:171) and the pause
  // (:459, :485). They are declared in components/dashboard/kitchen/kitchen-route-calls.ts, and its
  // guard test fails if an undeclared call, or one with a higher floor, is added here. A call with a
  // higher floor must be exempted from this listener first: its refusal would not mean access ended.
  // The pause control therefore shows no inline copy for this 403; this overlay is the answer.
  useEffect(() => {
    const onAccessDenied = () => {
      if (isDeliberateSignOut()) return
      setStopped((current) => current ?? "noAccess")
    }
    window.addEventListener(SHOP_ACCESS_DENIED_EVENT, onAccessDenied)
    return () => window.removeEventListener(SHOP_ACCESS_DENIED_EVENT, onAccessDenied)
  }, [])

  // While the SSO session lasts, refresh the token before it expires, so the overlay above means a
  // real lapse and never a routine access-token expiry (D-14). Not on a stopped board.
  useSessionKeepAlive(stopped === null)

  // --- #106: feed liveness ---
  //
  // `lastSyncedAt` advances on every SUCCESSFUL read, so it is positive evidence the
  // board is current; `syncFailed` records a read that threw. Neither depends on the
  // socket's opinion of itself, which was measured lying (see feed-state.ts).
  const [lastSyncedAt, setLastSyncedAt] = useState<number | null>(null)
  const [syncFailed, setSyncFailed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  // #709: connectivity is an external store, not component state — see the
  // "Browser connectivity" block below for the measured behaviour this keeps.
  const online = useSyncExternalStore(
    subscribeToConnectivity,
    () => navigator.onLine,
    () => true
  )

  // --- #485: the board is showing only part of this shop's orders ---
  const [ordersTruncated, setOrdersTruncated] = useState(false)

  // #564: the ordersMap ref is gone. It existed so `fetchOrders` could re-use detail it
  // already held for tickets whose status had not changed — the optimisation that made a
  // one-request-per-ticket refresh survivable. The refresh is now one request, so there
  // is nothing to re-use and nothing to keep in sync.

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
     * Read the whole board. There is no longer a cheap path and an expensive one.
     *
     * This used to take an `incremental` flag that re-used held detail for tickets
     * whose status had not changed, because the full path cost one request per ticket
     * and the liveness poll could not afford that every minute. #564 made the full read
     * ONE request, so the distinction — and the drift it allowed, where a detail edit
     * that did not change status went unseen until "something of consequence" happened
     * — is gone rather than optimised.
     */
    async () => {
      if (!selectedShopId) return
      try {
        // #564: ONE read, whatever the board holds. This used to be a list request plus
        // one `/detail` per ticket — 19 requests on an 18-ticket board, fired twice in
        // ~400 ms whenever the browser came back online, against a tenant budget of
        // 100/minute shared with everything else the tenant is doing.
        //
        // #485's paging contract is preserved inside `fetchKitchenBoard`: `truncated`
        // still comes back, so a board that could not be read in full still SAYS so
        // rather than silently dropping the tail.
        const { orders: activeOrders, truncated } =
          await fetchKitchenBoard(selectedShopId)

        const newMap = new Map<string, OrderDetail>()
        for (const order of activeOrders) newMap.set(order.id, order)
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
      // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch-on-change effect; the traced sync setRefreshing/setLoading prefix IS the loading-UI contract. One extra render accepted.
      fetchOrders()
    }
  }, [selectedShopId, fetchOrders])

  // --- Timer for elapsed time + last-updated age (#106) ---

  useEffect(() => {
    const interval = setInterval(() => setTick(Date.now()), KITCHEN_CLOCK_TICK_MS)
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
  //
  // 37-18: a stopped board stops reading. A lapsed session would only 401 again, and a revoked
  // grant 403 again; the overlay already says what is wrong and what to do.
  useEffect(() => {
    if (!selectedShopId || stopped) return
    const interval = setInterval(() => {
      fetchOrders()
    }, KITCHEN_POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [selectedShopId, fetchOrders, stopped])

  // --- Browser connectivity (#106) ---
  //
  // `navigator.onLine` flips synchronously on the browser's own events, so this
  // reaches the operator long before a socket timeout would. #709 (react-hooks
  // 7.1): `online` moved from mount-synced useState to useSyncExternalStore —
  // the sanctioned shape for exactly this ("SSR and the first client render
  // agree" is what the server-snapshot argument is for). The re-read effect
  // below keeps the measured behaviour that used to live in goOnline: after a
  // 110-second offline spell the board sat on "Not updating" for the rest of
  // the minute even though the network was back, because `syncFailed` is only
  // cleared by a successful read and the next one was up to 60s away. A board
  // that keeps warning after the problem is fixed teaches the kitchen to
  // ignore the warning — so re-read IMMEDIATELY when connectivity returns.
  useEffect(() => {
    if (stopped) return
    const goOnline = () => fetchOrders()
    window.addEventListener("online", goOnline)
    return () => window.removeEventListener("online", goOnline)
  }, [fetchOrders, stopped])

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
      const { orderId, newStatus } = event

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

        // 37-17: no sound from here. A new order lands in the New lane through the read above,
        // and the lane's count drives the repeating alert (useKitchenAlert below).
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

  //
  // 37-18 (D-08 on the kitchen route): a stopped board drops its topic, which deactivates the STOMP
  // client. The server checks access only on SUBSCRIBE, so after a revoke an open socket would keep
  // delivering OrderStateChangeEvent frames (order id, number, status) until it closed; the board
  // closes it on the first 403 (residual recorded in 37-18-SUMMARY, T-37-47). On a lapse there is
  // no token to reconnect with either.
  const { connected, reconnecting } = useStomp(
    stopped ? null : stompTopic,
    handleWsMessage,
    fetchOrders
  )

  // --- Mute (the `kds-muted` key and its lifetime are unchanged; UXT-050 is 37.x) ---

  const setMutedPreference = (next: boolean) => {
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
    } catch (error: unknown) {
      // 30-06 (issue #958, UI-SPEC § S2): say WHY. A server answer carries its RFC 7807 `detail`
      // (an order awaiting payment reads plan 30-05's staff sentence verbatim), rendered as a text
      // node. With no response at all, describeLoadError would return the bare "Network Error", so
      // the kitchen uses its own fallback naming the action. The board still re-reads, which
      // reverts the optimistic move above.
      const fallback = bumpFailedCopy(action.failureVerb)
      const hasResponse = Boolean((error as { response?: unknown } | null)?.response)
      toast({
        variant: "destructive",
        title: "Order not updated",
        description: hasResponse ? describeLoadError(error, fallback).message : fallback,
      })
      fetchOrders()
    }
  }

  // --- 31.1-22: the allergy note was marked read ---
  //
  // Merge the SERVER's who/when into the board's copy of the order (never the click time), so
  // the print sheet and any later render agree with the card. The next board read replaces the
  // entry wholesale anyway; this only closes the gap until then.
  const handleAllergyNoteAcknowledged = useCallback(
    (orderId: string, ack: AllergyNoteAcknowledgement) => {
      setOrdersMap((prev) => {
        const existing = prev.get(orderId)
        if (!existing) return prev
        const next = new Map(prev)
        next.set(orderId, {
          ...existing,
          allergyNoteAcknowledgedAt: ack.acknowledgedAt,
          allergyNoteAcknowledgedBy: ack.acknowledgedBy,
        })
        return next
      })
    },
    []
  )

  // --- 37-16 (D-11): the kitchen accepted a New-lane order ---
  //
  // Called only after the server's 200 on /confirm, so the move is a fact, not a guess. The order keeps
  // its detail and becomes CONFIRMED, which puts it in the grid with "Start Preparing"; the STOMP frame
  // and the next read replace the entry wholesale anyway.
  const handleAccepted = useCallback((orderId: string) => {
    setOrdersMap((prev) => {
      const existing = prev.get(orderId)
      if (!existing) return prev
      const next = new Map(prev)
      next.set(orderId, { ...existing, status: "CONFIRMED" })
      return next
    })
  }, [])

  // --- 37-16 (D-11): the kitchen rejected a New-lane order ---
  //
  // Called only after the server's 200 on /cancel. A cancelled order is not a ticket, so it leaves the
  // board, exactly as a CANCELLED frame off the socket removes it.
  const handleRejected = useCallback((orderId: string) => {
    setOrdersMap((prev) => {
      if (!prev.has(orderId)) return prev
      const next = new Map(prev)
      next.delete(orderId)
      return next
    })
  }, [])

  // --- Sorted orders (newest first) ---

  const sortedOrders = Array.from(ordersMap.values()).sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )

  // 37-16 (D-11): PENDING orders wait in the New lane; the grid keeps CONFIRMED/PREPARING/READY exactly
  // as before. Print all stays a print of the GRID: a batch print is for tickets the kitchen is cooking,
  // and an order nobody has accepted yet must not reach the pass by the back door (each New card keeps
  // its own per-ticket print).
  const newOrders = sortedOrders.filter((o) => o.status === NEW_LANE_STATUS)
  const boardOrders = sortedOrders.filter((o) => o.status !== NEW_LANE_STATUS)

  // --- 37-17 (D-12): the repeating new-order alert ---
  //
  // The chime counts only THIS board's shop. After a shop switch the map still holds the previous
  // shop's orders until the new read lands; they must not keep ringing for a board that no longer
  // shows them. `stopped` is the K2 "board stopped" state (37-18): the chime gives way to the alarm.
  const alertCount = newOrders.filter((o) => o.shopId === selectedShopId).length
  const { soundState, enableSound, soundEnabled } = useKitchenAlert({
    pendingCount: alertCount,
    muted,
    stopped: stopped !== null,
    // 37-18 (K2): three 440 Hz square tones every 3 s through the same unlocked context, until
    // "Silence alarm". A board whose sound was never turned on cannot ring, and the overlay says so.
    alarm: stopped !== null && !alarmSilenced,
  })

  // The access-ended overlay's "Sign out" is a deliberate sign-out like the sidebar's: marked first,
  // so the 401 it provokes cannot flip the overlay to "Signed out" and restart the alarm.
  const handleStoppedSignOut = () => {
    markDeliberateSignOut()
    void vendorLogout()
  }

  // Unmuting is a tap, so it is also the gesture that unlocks audio: one press, and the board rings.
  const handleUnmute = () => {
    setMutedPreference(false)
    enableSound()
  }

  // The tab title carries the New-lane count, so a backgrounded tab still says an order waits.
  // The restore effect is declared first so it captures the title the route arrived with.
  const newLaneCount = newOrders.length
  useEffect(() => {
    const previous = document.title
    return () => {
      document.title = previous
    }
  }, [])
  useEffect(() => {
    // 37-18 (K2): a stopped board says so first, so a backgrounded tab cannot read as working.
    document.title = stopped ? STOPPED_TAB_TITLE : kitchenTabTitle(newLaneCount)
  }, [newLaneCount, stopped])

  // --- #450 5d: whose board is this? ---

  const selectedShop = shops.find((s) => s.id === selectedShopId) ?? null
  const selectedShopName = selectedShop?.name ?? null

  // --- 37-21 (D-15, UI-SPEC K3): pause storefront orders from the kitchen ---
  //
  // The pause state is the SERVER's shop, re-read on the board's own 60 s poll cadence
  // (KITCHEN_POLL_INTERVAL_MS), after every pause or resume, and at a timed pause's stated end, so a
  // pause made or ended on another device reaches this board within one poll. A stopped board reads
  // nothing, like the rest of the board. The pause needs STAFF on the shop, the same grant the board's
  // own read needs, so a 403 here means access ended and the D-08 listener above stops the board.
  const shopPause = useShopPause(selectedShopId, { active: stopped === null })

  // The dashboard names a SPECIFIC shop and the board is showing a different one —
  // the D-13 degrade in the reconciliation effect above, which until now happened in
  // silence. `selectedShopId` is only compared once it has settled, so the first
  // render (before reconciliation) never flashes the notice.
  const boardIsNotTheContextShop =
    !isAllShops &&
    contextShopId !== null &&
    selectedShopId !== null &&
    contextShopId !== selectedShopId

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
        // `tick` IS the clock reading (set from Date.now() on each 10s beat) —
        // pure w.r.t. render, same freshness as the old in-memo Date.now().
        now: tick,
        // 37-17 (D-14): the pill says "Live" only when the board can also ring. Staleness is
        // derived inside from `lastSyncedAt` against the existing STALE_AFTER_MS.
        // 37-18 (K2): behind the overlay the pill reads "Signed out" or "No access", so a
        // screenshot of the header never says "Live" on a stopped board.
        signedOut: stopped === "signedOut",
        noAccess: stopped === "noAccess",
        soundOff: soundState === "off",
        muted: soundState === "muted",
      }),
    [online, connected, reconnecting, lastSyncedAt, syncFailed, tick, soundState, stopped]
  )

  // #536: ONE tree, not a `loading` early-return that duplicated the header.
  //
  // The duplicate was itself a defect factory — #556 was two header subtrees carrying
  // the same testid — and it is also why the header CHANGED HEIGHT mid-load: the
  // loading copy had no control row, so the pill/selector/print/mute appeared at
  // 90px..218px and pushed the whole board down. The controls now render in both
  // states (disabled while there is nothing to act on), so the header is the height it
  // will end up being from the first paint.
  /*
   * WIDTH TIER — Index, and this was a decision rather than a default.
   *
   * PATTERNS A-6. The board is a card grid rather than a table, which is the
   * reason it could defensibly have gone either way. It is Index because a grid
   * that reflows into more columns is the same argument as a table that shows
   * more columns, and because a kitchen display is the one dashboard surface
   * genuinely likely to run on a large wall-mounted screen.
   *
   * HAZARD, recorded here because it is not visible from this line: the board is
   * STOMP-live and its tickets mount and unmount through `AnimatePresence`, so
   * more columns changes the reflow on every order transition. That is a runtime
   * property no jsdom assertion can see. `e2e/kitchen-flow.spec.ts` and
   * `e2e/stomp-relay.spec.ts` are re-run in plan 35-12 for exactly this reason.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). This board has ONE render branch; #536 removed the
   * loading early-return, so this root is the only root there is.
   */
  return (
    <div data-width-tier="index" className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Kitchen Display</h1>
          {/* #450 5d: the board names its shop before it says anything else about
              itself. This used to be inferable only from a 200px <Select> in the
              corner, while the dashboard switcher next to it said "All shops".
              The original tagline is kept below rather than displaced — it explains
              what the board is FOR to someone seeing it the first time, which the
              shop name does not.

              #556: `loading` is a THIRD state and gets its own testid — without it
              this said "No shop selected" while the shop was still loading, which is
              false. Now that there is one header rather than two, the two testids can
              no longer be in the DOM at the same time by construction. */}
          <KdsBoardShopName
            shopName={loading ? null : selectedShopName}
            loading={loading}
          />
          <p className="mt-0.5 text-sm text-slate-500">
            Live order feed &mdash; bump orders through preparation stages
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {/* #106: the connection state, legible from a wall mount. Replaces a
              h-2.5 w-2.5 dot whose label was `hidden sm:inline`.
              #536: `pending` only while the shop list is in flight — see the PENDING
              note in kds-feed-status.tsx. It is bounded by that fetch, so it can never
              paper over a socket that is genuinely down. */}
          <KdsFeedPill
            state={feedState}
            lastSyncedAt={lastSyncedAt}
            pending={loading}
          />

          {/* Shop selector. #536: while the shop list is loading this renders as a
              DISABLED trigger of the same size rather than not at all — an absent
              control is a 108px hole that fills in a second later, and every ticket
              on the board moves down when it does. */}
          {shops.length > 0 ? (
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
          ) : (
            <Select disabled value="">
              <SelectTrigger
                className="w-[200px]"
                aria-label="Kitchen display shop"
                data-testid="kds-shop-select-placeholder"
              >
                <SelectValue placeholder={loading ? "Loading shops…" : "No shops"} />
              </SelectTrigger>
            </Select>
          )}

          {/* 37-21 (D-15, UI-SPEC K3): one-tap pause, after the shop selector. The row wraps
              (flex-wrap), so on a narrow screen the pause and sound controls take a new line
              instead of overflowing. */}
          <PauseOrdersControl
            view={shopPause.view}
            pending={shopPause.pending}
            error={shopPause.error}
            disabled={!selectedShopId || !shopPause.loaded}
            onPause={shopPause.pause}
            onResume={shopPause.resume}
          />

          {/* #105: print every ticket on the board, one per page — the start-of-shift
              and printer-jam case. Per-ticket printing lives on each card. */}
          <Button
            variant="outline"
            onClick={() => print(boardOrders)}
            disabled={boardOrders.length === 0}
            title="Print all tickets on this board"
            className="kds-press"
          >
            <Printer className="mr-2 h-5 w-5" />
            Print all
          </Button>

          {/* GAP-GTM-02 (V87 / ADR-0007): toggle the consolidated Cook Sheet batch-prep view */}
          <Button
            type="button"
            variant={cookSheetOpen ? "default" : "outline"}
            onClick={() => setCookSheetOpen((open) => !open)}
            disabled={!selectedShopId}
            aria-expanded={cookSheetOpen}
            aria-controls="kds-cook-sheet-panel"
            data-testid="kds-cook-sheet-toggle"
            className="kds-press"
          >
            <ClipboardList className="mr-2 h-5 w-5" />
            {cookSheetOpen ? "Hide cook sheet" : "Cook sheet"}
          </Button>

          {/* 37-17 (D-12): the sound control says, in words, what the board can do. It replaces the
              icon-only mute button; the `kds-muted` preference behind it is the same. */}
          <KdsSoundControl
            soundState={soundState}
            onTurnOn={enableSound}
            onMute={() => setMutedPreference(true)}
            onUnmute={handleUnmute}
          />
        </div>
      </div>

      {/* 37-17 (D-12): the board says when it cannot ring, under the header and above every notice
          that qualifies the tickets. Not dismissible; its only button is the fix. */}
      <KdsSoundBar
        soundState={soundState}
        onTurnOn={enableSound}
        onUnmute={handleUnmute}
      />

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

      {/* 37-21 (UI-SPEC K3): orders are paused. Below the feed banner (which qualifies every ticket),
          above the board. Read from the server's shop, never a client timer. */}
      <PausedBanner
        shopName={selectedShopName}
        view={shopPause.view}
        pending={shopPause.pending}
        onResume={() => void shopPause.resume()}
      />

      {/* #450 5d: name the mismatch rather than letting the operator find it.
          Two mismatches, and they are mutually exclusive by construction — the
          dashboard is either on All shops or on one specific shop. */}
      {isAllShops ? (
        <KdsAllShopsNotice shopName={selectedShopName} shopCount={shops.length} />
      ) : (
        boardIsNotTheContextShop && (
          <KdsOtherShopNotice shopName={selectedShopName} />
        )
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

      {/* GAP-GTM-02 (V87 / ADR-0007): consolidated batch prep "Cook Sheet" panel.
          Mounted on demand when toggled open so initial mount fires zero extra API calls. */}
      {cookSheetOpen && (
        <div id="kds-cook-sheet-panel">
          <KdsCookSheetPanel
            shopId={selectedShopId}
            shopName={selectedShopName}
            stopped={stopped !== null}
          />
        </div>
      )}

      {/* 37-16 (D-11): the New lane, above the grid and below every notice that qualifies the board.
          Present from the first paint (a one-line row), so it never moves the grid when it fills. */}
      <KdsNewLane
        orders={newOrders}
        loaded={!loading && lastSyncedAt !== null}
        onAccepted={handleAccepted}
        onRejected={handleRejected}
        onRefresh={() => {
          void fetchOrders()
        }}
        onPrint={(order) => print([order])}
        onAllergyNoteAcknowledged={handleAllergyNoteAcknowledged}
      />

      {/* The board itself, in exactly one of four states — shops loading, orders never
          read, read and empty, read and populated. All four occupy {@link BOARD_RESERVE},
          so the footer `dashboard-shell.tsx` renders underneath sits below the fold from
          the first paint and stays there.

          `loading` covers the SHOP list only; the orders arrive after it, so the board
          used to render "No active orders" in the gap and then replace it with a full
          grid. That is a claim the board had not yet checked — the same class of untruth
          as #450 5d. `lastSyncedAt === null` is exactly "orders have never been read".

          THE `key` ON EACH BRANCH IS LOAD-BEARING, not decoration. React reconciles a
          conditional by position, so without distinct keys these four `div`s are ONE
          node that persists across the whole load — and a persisting node that MOVES is
          an unstable element, while a freshly-mounted one is not. The all-shops notice
          above inserts 163px in the same commit that ends the shops load; with one
          shared node that pushes a viewport-tall board down and scores ~0.19 on its own,
          and with a key it costs nothing because the node the notice pushes did not
          exist a frame ago. Keep the keys distinct if these branches are ever
          restructured. */}
      {loading || (lastSyncedAt === null && sortedOrders.length === 0) ? (
        <div
          key={loading ? "kds-board-loading-shops" : "kds-board-loading-orders"}
          className={BOARD_RESERVE}
          role="status"
          aria-label="Loading kitchen orders"
        >
          <KdsBoardSkeleton />
        </div>
      ) : boardOrders.length === 0 ? (
        <div
          key="kds-board-empty"
          className={`flex ${BOARD_RESERVE} flex-col items-center justify-center py-24 text-center`}
        >
          <ChefHat className="mb-4 h-16 w-16 text-slate-300" />
          <h3 className="mb-2 text-xl font-semibold text-slate-900">
            No active orders
          </h3>
          <p className="text-sm text-slate-500">
            Orders will appear here when customers place them
          </p>
        </div>
      ) : (
        <div
          key="kds-board-grid"
          data-testid="kds-board-grid"
          className={`grid ${BOARD_RESERVE} grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4`}
        >
          <AnimatePresence mode="popLayout">
            {/* eslint-disable-next-line react-hooks/refs -- #709: the ember-glow check below deliberately reads the LAST COMMITTED seen-set (the effect at the seenIdsRef declaration commits it after paint, so the keyframe plays exactly once per new id). Converting to state would itself be a set-state-in-effect and add a render per batch; staleness here is the mechanism, and the stakes are one card animation. */}
            {boardOrders.map((order) => {
              const config = statusConfig[order.status]
              const action = bumpActions[order.status]
              const StatusIcon = config?.icon || Clock
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
                    data-testid="kds-grid-card"
                    className={`border-2 ${ageBorderClass(order.createdAt)} transition-colors`}
                  >
                    {/* 31-15: `data-testid` is not decoration here. The allergen banner's
                        contract is POSITIONAL — inside this header, under the order
                        number, above the customer name — and a test that can only ask
                        "is the banner somewhere on the card" would pass with it at the
                        bottom, which on a wall screen is the same as absent. This is the
                        hook that lets the position be asserted, so a future card
                        refactor learns what it must not break. */}
                    <CardHeader className="pb-3" data-testid="kds-card-header">
                      {/* 37-16 (UXT-011): the fulfilment badge sits beside the number. The number
                          keeps #8's `min-w-0 truncate` (so it can never run under a badge) and
                          gains `whitespace-normal break-words`, which win the cascade over
                          truncate's nowrap: it wraps to a second line rather than losing its tail
                          to an ellipsis, so the FULL number shows (roadmap SC-1).

                          THE ROW WRAPS (`flex-wrap`). Measured on the live desktop run (1280px,
                          four columns): in a non-wrapping row `min-w-0` let flex squeeze the number
                          to ZERO width between DELIVERY and the status badge, and truncate's
                          overflow:hidden then hid it. Wrapping makes a badge drop to the next line
                          instead; the status badge keeps the right edge with `ml-auto`. */}
                      <div className="flex flex-wrap items-start gap-2">
                        <CardTitle className="min-w-0 truncate whitespace-normal break-words text-lg font-semibold">
                          {order.orderNumber || `#${order.id.substring(0, 6)}`}
                        </CardTitle>
                        <KdsFulfilmentBadge fulfilmentType={order.fulfilmentType} />
                        <KdsScheduledWindowBadge
                          scheduledWindowLabel={order.scheduledWindowLabel}
                          scheduledForDate={order.scheduledForDate}
                        />
                        {/* 30-06 (#958, UI-SPEC § S2): unpaid card / link order, by the server's
                            rule. Warns before the tap; Start Preparing stays enabled so the
                            server's refusal (toast) is always reachable. */}
                        <AwaitingPaymentBadge
                          orderStatus={order.status}
                          paymentStatus={order.paymentStatus}
                          paymentReference={order.paymentReference}
                        />
                        {config && (
                          <Badge className={`${config.bgColor} ml-auto flex flex-shrink-0 items-center gap-1 text-white`}>
                            <StatusIcon className="h-3 w-3" />
                            {config.label}
                          </Badge>
                        )}
                      </div>

                      {/* 31-15 (LGL-03 / D-04): the order's allergen picture, read
                          straight off the server's write-time snapshot. NOTHING is
                          aggregated client-side — the point of the snapshot is that the
                          kitchen and the checkout see ONE answer, the one the customer
                          acknowledged, and a second derivation here would be a second
                          answer waiting to disagree.

                          Directly under the order number so it is visible without
                          scrolling the card; the component itself decides between the
                          declared set, the "not recorded" strip and rendering nothing. */}
                      <OrderAllergenBanner
                        allergenNames={order.allergenNames}
                        allergenFlags={order.allergenFlags}
                      />

                      {/* 31.1-22 (#812, D-15): the customer's allergy note, directly under the
                          allergen banner and still inside the header, so it is read before the
                          customer name and the items. Renders nothing for an order without a
                          note. Marking it read updates this board's copy of the order from the
                          server's 200, so a re-print says who read it. No STOMP frame announces
                          an acknowledgement (31.1-13); other screens learn it on their next read. */}
                      <AllergyNoteBlock
                        orderId={order.id}
                        orderLabel={order.orderNumber || `#${order.id.substring(0, 6)}`}
                        note={order.allergyNote}
                        acknowledgedAt={order.allergyNoteAcknowledgedAt}
                        acknowledgedBy={order.allergyNoteAcknowledgedBy}
                        onAcknowledged={(ack) => handleAllergyNoteAcknowledged(order.id, ack)}
                      />
                    </CardHeader>
                    <CardContent className="space-y-3">
                      {/* Customer name + the unverified-contact badge (37-30, F20): from the stored
                          fact only — `false` renders it, null/absent (not recorded) never does. */}
                      <div className="flex flex-wrap items-center gap-2 text-sm font-medium text-slate-700">
                        <span>{order.customerName || "Walk-in"}</span>
                        <UnverifiedContactBadge contactVerified={order.contactVerified} />
                      </div>

                      {/* 37-16 (UXT-011, P0): the customer's notes, after the name and before
                          the items. Renders nothing for an order without notes. */}
                      <KdsCustomerNote note={order.notes} />
    
                      {/* Items.

                          31-15: this was an inline comma-joined 12px <span> run carrying
                          quantity and name only. It is now a <ul> at 14px, one row per
                          item, because a 12px inline run cannot carry a per-item allergen
                          badge legibly at the 0.6-1.5 m this display is read from — and
                          D-04 needs the cook to know not just that the order contains
                          sesame but WHICH of the dishes has it.

                          THE "{n} items" SUMMARY ABOVE STAYS. This is additive: the
                          summary is a preserved good (it is the one line that answers
                          "how big is this ticket" at a glance) and the list is new
                          capacity, not a replacement for it. */}
                      <KdsItemList items={order.items} />
    
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

      {/* 37-18 (D-14, K2): the board has stopped. Portalled to <body> above everything; the rest
          of the page is inert while it shows. */}
      {stopped && (
        <KdsBoardStopped
          reason={stopped}
          soundNeverEnabled={!soundEnabled}
          alarmSilenced={alarmSilenced}
          onSilence={() => setAlarmSilenced(true)}
          onSignOut={handleStoppedSignOut}
        />
      )}
    </div>
  )
}
