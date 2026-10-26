"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useParams, useRouter } from "next/navigation"
import { ArrowLeft } from "lucide-react"
import apiClient from "@/lib/api-client"
import { cn } from "@/lib/utils"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { useOrderEvents } from "@/hooks/use-order-events"
import { Button } from "@/components/ui/button"
import { useToast } from "@/hooks/use-toast"
import { OrderDetailPanel } from "@/components/dashboard/orders/OrderDetailPanel"
import type { OrderDetail } from "@/types/api"

/**
 * Vendor order detail route — VOPS-01.
 *
 * Loads /api/v1/orders/{id}/detail on mount, subscribes to the existing
 * order-state-change SSE channel for live updates, and renders an
 * `OrderDetailPanel` that includes the refund action button.
 *
 * Auth is handled by the parent dashboard layout (server component) which
 * runs `auth()` and redirects unauthenticated visitors. We just fetch.
 *
 * A live update (SSE, or the re-read after a refund) refreshes the order IN
 * PLACE, so an open dialog and what was typed into it survive; only the first
 * load for a route id shows the loading spinner or the error page.
 */
export default function OrderDetailPage() {
  const router = useRouter()
  const params = useParams<{ id: string }>()
  const orderId = params?.id
  const { toast } = useToast()

  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // The id of the order currently ON SCREEN (null while nothing is). A read for
  // that same id is a background refresh: it never sets loading or error, because
  // the loading branch replaces the page and would unmount the panel and any open
  // dialog with it (E2E 'G-37-6 item 4': a typed void reason was lost whenever
  // any event for the order arrived). Keyed on the id, not on "an order is
  // loaded", so a navigation to a different order is still a fresh load.
  const shownOrderIdRef = useRef<string | null>(null)
  // The route id as of the latest commit: a response for an id the page has
  // since left is dropped, so a slow read cannot paint the previous order.
  const routeIdRef = useRef(orderId)
  useEffect(() => {
    routeIdRef.current = orderId
  }, [orderId])
  // Latest toast, read through a ref (the onEventRef pattern in
  // hooks/use-order-events.ts). Putting `toast` in fetchDetail's deps would make
  // fetchDetail change whenever toast's identity does, and re-fire the mount
  // effect: a test double that builds a new function per render loops for ever.
  const toastRef = useRef(toast)
  useEffect(() => {
    toastRef.current = toast
  }, [toast])

  const fetchDetail = useCallback(async () => {
    if (!orderId) return
    const background = shownOrderIdRef.current === orderId
    if (!background) {
      shownOrderIdRef.current = null
      setLoading(true)
      setError(null)
    }
    try {
      const res = await apiClient.get<OrderDetail>(
        `/api/v1/orders/${orderId}/detail`
      )
      if (routeIdRef.current !== orderId) return
      setOrder(res.data)
      shownOrderIdRef.current = orderId
    } catch (e: unknown) {
      if (routeIdRef.current !== orderId) return
      const err = e as { response?: { status?: number } }
      const status = err.response?.status
      if (background) {
        // Keep what is on screen and say the refresh failed. The 403/404
        // meaning the error page used to carry is kept in the title.
        toastRef.current({
          variant: "destructive",
          title:
            status === 403
              ? "You no longer have access to this order"
              : status === 404
                ? "This order is no longer available"
                : "Couldn't refresh this order",
          description: "Showing what was last loaded.",
        })
      } else if (status === 404) {
        setError("Order not found.")
      } else if (status === 403) {
        setError("You do not have access to this order.")
      } else {
        setError("Failed to load order. Please try again.")
      }
    } finally {
      if (!background && routeIdRef.current === orderId) setLoading(false)
    }
  }, [orderId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchDetail()
  }, [fetchDetail])

  // SSE refresh (#92): the shared hook subscribes to /api/v1/orders/stream
  // with auto-reconnect (capped exponential backoff + fresh token per
  // attempt); we re-fetch detail whenever this order's id appears in an event.
  useOrderEvents((event) => {
    if (event.orderId === orderId) fetchDetail()
  })

  // The loading branch carries the SAME tier as the loaded one, deliberately —
  // otherwise this spinner sits in the full Shell band and the page snaps
  // narrower the instant the fetch resolves. Not a redundant cap on a spinner.
  if (loading) {
    return (
      <div
        data-width-tier="detail"
        className={cn(
          "mx-auto",
          WIDTH_TIER_CLASS.detail,
          "flex h-full items-center justify-center p-12"
        )}
      >
        <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-orange-500"></div>
      </div>
    )
  }

  // Same tier as the loaded branch, for the same reason: a failed load must not
  // render wider than a successful one.
  if (error) {
    return (
      <div
        data-width-tier="detail"
        className={cn("mx-auto", WIDTH_TIER_CLASS.detail, "space-y-4 p-6")}
      >
        <Button
          variant="ghost"
          onClick={() => router.push("/dashboard/orders")}
          className="gap-2"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Back to orders
        </Button>
        <div
          role="alert"
          className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700"
        >
          {error}
        </div>
      </div>
    )
  }

  if (!order) return null

  const handleRefundIssued = () => {
    toast({
      title: "Refund submitted",
      description: "Stripe is processing the refund. The status will update shortly.",
    })
    fetchDetail()
  }

  // THE DETAIL TIER (phase 35, UIX-09). This page is a reading surface — a
  // labelled key-value read plus a line-item list — not a data grid. Its measure
  // comes from the industry cluster CONTEXT.md section 3 records: detail and
  // reading columns sit tightly between 1016px (Square's content ladder) and
  // 1136px (Linear's detail ladder), with Lightspeed's content column at 1100,
  // and prose-measure guidance (45-75 characters a line) is why they converge.
  //
  // So this element is DELIBERATELY narrower than the Shell band around it, and
  // it centres inside that band rather than hugging its left edge. Widening it
  // to the shell cap would be a regression dressed up as an improvement. The
  // number itself lives once, in lib/layout-widths.ts (DETAIL_MAX_PX) — change
  // it there, never here.
  return (
    <div
      data-width-tier="detail"
      className={cn("mx-auto", WIDTH_TIER_CLASS.detail, "space-y-4 p-6")}
    >
      <div className="flex items-center justify-between">
        <Button
          variant="ghost"
          onClick={() => router.push("/dashboard/orders")}
          className="gap-2"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Back to orders
        </Button>
      </div>
      <OrderDetailPanel
        order={order}
        onRefundIssued={handleRefundIssued}
        // 37-12: the void's 200 IS the order detail (37-10), so the page keeps it as its own.
        onVoided={(detail) => {
          if (detail?.id === order.id) setOrder(detail)
        }}
      />
    </div>
  )
}
