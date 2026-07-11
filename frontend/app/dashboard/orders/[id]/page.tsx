"use client"

import { useCallback, useEffect, useState } from "react"
import { useParams, useRouter } from "next/navigation"
import { ArrowLeft } from "lucide-react"
import apiClient from "@/lib/api-client"
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
 */
export default function OrderDetailPage() {
  const router = useRouter()
  const params = useParams<{ id: string }>()
  const orderId = params?.id
  const { toast } = useToast()

  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchDetail = useCallback(async () => {
    if (!orderId) return
    setLoading(true)
    setError(null)
    try {
      const res = await apiClient.get<OrderDetail>(
        `/api/v1/orders/${orderId}/detail`
      )
      setOrder(res.data)
    } catch (e: unknown) {
      const err = e as { response?: { status?: number } }
      const status = err.response?.status
      if (status === 404) {
        setError("Order not found.")
      } else if (status === 403) {
        setError("You do not have access to this order.")
      } else {
        setError("Failed to load order. Please try again.")
      }
    } finally {
      setLoading(false)
    }
  }, [orderId])

  useEffect(() => {
    fetchDetail()
  }, [fetchDetail])

  // SSE refresh (#92): the shared hook subscribes to /api/v1/orders/stream
  // with auto-reconnect (capped exponential backoff + fresh token per
  // attempt); we re-fetch detail whenever this order's id appears in an event.
  useOrderEvents((event) => {
    if (event.orderId === orderId) fetchDetail()
  })

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center p-12">
        <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-orange-500"></div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="space-y-4 p-6">
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

  return (
    <div className="space-y-4 p-6">
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
      <OrderDetailPanel order={order} onRefundIssued={handleRefundIssued} />
    </div>
  )
}
