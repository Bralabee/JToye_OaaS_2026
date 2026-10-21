"use client"

import { useCallback, useEffect, useState, type FormEvent } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import { useToast } from "@/hooks/use-toast"
import {
  createShopFulfilmentSlot,
  deactivateShopFulfilmentSlot,
  fetchShopFulfilmentSlots,
  fetchWhatsAppPreOrderLink,
} from "@/lib/shop-slots-api"
import { Calendar, Copy, ExternalLink, Loader2, MessageSquare, Plus, XCircle } from "lucide-react"
import type { FulfilmentSlotDto, Shop, WhatsAppPreOrderLinkDto } from "@/types/api"

export interface ShopFulfilmentSlotsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  shop: Shop | null
  /**
   * #961: whether the caller may create and close slots (SHOP_MANAGER on the shop; the server refuses
   * STAFF with 403). Reading the slots and generating the WhatsApp link stay open to STAFF. Defaults to
   * true: absent, the dialog decides nothing and the server is the boundary.
   */
  canManage?: boolean
}

/**
 * Scheduled Fulfilment Slots & WhatsApp Pre-Order Link Dialog (ADR-0007 / GAP-GTM-02 & GAP-GTM-06).
 *
 * Allows vendors running Scheduled Batch Drops (Model C) or Event Catering (Model B)
 * to create capacity-bounded service windows (e.g., Friday 17:00–19:00, max 25 orders,
 * cutoff Thursday 18:00), deactivate closed windows, and generate 1-click shareable
 * WhatsApp pre-order broadcast links.
 */
export function ShopFulfilmentSlotsDialog({
  open,
  onOpenChange,
  shop,
  canManage = true,
}: ShopFulfilmentSlotsDialogProps) {
  const { toast } = useToast()
  const [slots, setSlots] = useState<FulfilmentSlotDto[]>([])
  const [loading, setLoading] = useState<boolean>(false)
  const [error, setError] = useState<string | null>(null)

  // New slot form state
  const [serviceDate, setServiceDate] = useState<string>("")
  const [windowStart, setWindowStart] = useState<string>("17:00")
  const [windowEnd, setWindowEnd] = useState<string>("19:00")
  const [cutoffLocal, setCutoffLocal] = useState<string>("")
  const [maxOrders, setMaxOrders] = useState<string>("25")
  const [maxUnits, setMaxUnits] = useState<string>("")
  const [submitting, setSubmitting] = useState<boolean>(false)
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null)

  // WhatsApp pre-order link panel state
  const [waLink, setWaLink] = useState<WhatsAppPreOrderLinkDto | null>(null)
  const [loadingWaSlotId, setLoadingWaSlotId] = useState<string | null>(null)

  const loadSlots = useCallback(async () => {
    if (!shop) return
    setLoading(true)
    setError(null)
    try {
      const list = await fetchShopFulfilmentSlots(shop.id)
      setSlots(Array.isArray(list) ? list : [])
    } catch {
      setError("Could not load scheduled fulfilment slots.")
    } finally {
      setLoading(false)
    }
  }, [shop])

  useEffect(() => {
    if (!open || !shop) return
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: reset-on-open for a controlled surface; one post-open render is the contract
    setWaLink(null)
    setError(null)
    void loadSlots()
  }, [open, shop, loadSlots])

  const handleCreateSlot = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!shop || submitting) return
    if (!serviceDate || !windowStart || !windowEnd || !cutoffLocal) {
      setError("Enter the service date, start/end times, and pre-order cutoff.")
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const cutoffIso = cutoffLocal.endsWith("Z") || /[+-]\d{2}:\d{2}$/.test(cutoffLocal)
        ? cutoffLocal
        : new Date(cutoffLocal).toISOString()

      const parsedOrders = maxOrders.trim() ? parseInt(maxOrders.trim(), 10) : null
      const parsedUnits = maxUnits.trim() ? parseInt(maxUnits.trim(), 10) : null

      const created = await createShopFulfilmentSlot(shop.id, {
        serviceDate,
        windowStart,
        windowEnd,
        cutoffAt: cutoffIso,
        maxOrders: parsedOrders && parsedOrders > 0 ? parsedOrders : null,
        maxUnits: parsedUnits && parsedUnits > 0 ? parsedUnits : null,
      })
      setSlots((prev) => [...prev, created])
      toast({
        title: "Fulfilment slot created",
        description: `${created.windowLabel} is now open for pre-orders.`,
      })
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { detail?: unknown } } } | null)?.response?.data?.detail
      const message =
        typeof detail === "string" && detail.trim()
          ? detail
          : "Could not create fulfilment slot. Check the window times and cutoff."
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  const handleDeactivate = async (slotId: string) => {
    if (!shop || deactivatingId) return
    setDeactivatingId(slotId)
    setError(null)
    try {
      const updated = await deactivateShopFulfilmentSlot(shop.id, slotId)
      setSlots((prev) => prev.map((s) => (s.id === slotId ? updated : s)))
      toast({
        title: "Slot deactivated",
        description: "No further pre-orders will reserve this window.",
      })
    } catch {
      setError("Could not deactivate slot.")
    } finally {
      setDeactivatingId(null)
    }
  }

  const handleGenerateWhatsAppLink = async (slotId?: string | null) => {
    if (!shop) return
    const key = slotId ?? "general"
    setLoadingWaSlotId(key)
    setError(null)
    try {
      const link = await fetchWhatsAppPreOrderLink(shop.id, slotId)
      setWaLink(link)
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { detail?: unknown } } } | null)?.response?.data?.detail
      setError(
        typeof detail === "string" && detail.trim()
          ? detail
          : "Could not generate WhatsApp pre-order link for that window."
      )
    } finally {
      setLoadingWaSlotId(null)
    }
  }

  const copyMessage = async (text: string) => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      }
      toast({
        title: "Copied to clipboard",
        description: "Paste into your WhatsApp group or customer broadcast.",
      })
    } catch {
      // Ignore clipboard failure in non-secure environments
    }
  }

  if (!shop) return null

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Calendar className="h-5 w-5 text-slate-700" aria-hidden="true" />
            Scheduled Pre-Order Slots &amp; WhatsApp Link — {shop.name}
          </DialogTitle>
          <DialogDescription>
            Create capacity-capped batch drop or catering windows and share 1-click WhatsApp checkout links.
          </DialogDescription>
        </DialogHeader>

        {error && (
          <div
            role="alert"
            data-testid="slots-dialog-error"
            className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800"
          >
            {error}
          </div>
        )}

        {/* Existing Slots List */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-600">
              Scheduled Windows ({slots.length})
            </h4>
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-testid="shop-general-whatsapp-btn"
              disabled={loadingWaSlotId === "general"}
              onClick={() => void handleGenerateWhatsAppLink(null)}
              className="h-8 gap-1.5 text-xs"
            >
              <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
              General WhatsApp Link
            </Button>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-6 text-sm text-slate-600">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
              Loading scheduled slots…
            </div>
          ) : slots.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-4 text-center text-sm text-slate-600">
              No scheduled fulfilment slots yet. Add a window below for weekend drops or catering pre-orders.
            </div>
          ) : (
            <div className="space-y-2">
              {slots.map((slot) => (
                <div
                  key={slot.id}
                  data-testid={`slot-row-${slot.id}`}
                  className="flex flex-col justify-between gap-2 rounded-lg border border-slate-200 bg-white p-3 sm:flex-row sm:items-center"
                >
                  <div className="space-y-0.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-slate-900">
                        {slot.windowLabel}
                      </span>
                      {slot.active ? (
                        <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">
                          Active
                        </Badge>
                      ) : (
                        <Badge variant="secondary" className="text-slate-600">
                          Inactive
                        </Badge>
                      )}
                      <span className="text-xs text-slate-600">
                        {slot.reservedOrders}
                        {slot.maxOrders !== null ? `/${slot.maxOrders}` : ""} orders
                        {slot.maxUnits !== null
                          ? ` · ${slot.reservedUnits}/${slot.maxUnits} portions`
                          : ""}
                      </span>
                    </div>
                    <p className="text-xs text-slate-600">
                      Cutoff: {new Date(slot.cutoffAt).toLocaleString("en-GB", { timeZone: "Europe/London" })}
                    </p>
                  </div>

                  <div className="flex flex-wrap items-center gap-2">
                    {slot.active && (
                      <>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          data-testid={`slot-whatsapp-btn-${slot.id}`}
                          disabled={loadingWaSlotId === slot.id}
                          onClick={() => void handleGenerateWhatsAppLink(slot.id)}
                          className="h-8 gap-1.5 text-xs"
                        >
                          <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
                          WhatsApp Link
                        </Button>
                        {canManage && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            data-testid={`slot-deactivate-btn-${slot.id}`}
                            disabled={deactivatingId === slot.id}
                            onClick={() => void handleDeactivate(slot.id)}
                            className="h-8 gap-1 text-xs text-red-700 hover:bg-red-50 hover:text-red-800"
                          >
                            <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
                            Close slot
                          </Button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Shareable WhatsApp Pre-Order Link Panel */}
        {waLink && (
          <div
            data-testid="whatsapp-preorder-link-panel"
            className="space-y-2 rounded-lg border border-emerald-300 bg-emerald-50 p-3.5 text-sm text-emerald-950"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="font-semibold">
                WhatsApp Broadcast &amp; Reply Link
                {waLink.windowLabel ? ` (${waLink.windowLabel})` : ""}
              </p>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => void copyMessage(waLink.prefilledMessage)}
                  className="h-8 gap-1.5 bg-white text-xs text-slate-900"
                >
                  <Copy className="h-3.5 w-3.5" aria-hidden="true" />
                  Copy broadcast text
                </Button>
                <a
                  href={waLink.waMeUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex h-8 items-center gap-1.5 rounded-md bg-emerald-700 px-3 text-xs font-medium text-white hover:bg-emerald-800"
                >
                  <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                  Share on WhatsApp
                </a>
              </div>
            </div>
            <p className="rounded border border-emerald-200 bg-white p-2.5 font-mono text-xs text-slate-800 break-words">
              {waLink.prefilledMessage}
            </p>
            <p className="text-xs text-emerald-900">
              Checkout path: <span className="font-mono">{waLink.storefrontPath}</span>
            </p>
          </div>
        )}

        {/* Create New Scheduled Slot Form */}
        {canManage && (
          <form onSubmit={handleCreateSlot} className="space-y-3 border-t border-slate-200 pt-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-600">
              Add Scheduled Fulfilment Slot
            </h4>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <Label htmlFor="slot-service-date">Service date</Label>
                <Input
                  id="slot-service-date"
                  type="date"
                  value={serviceDate}
                  onChange={(e) => setServiceDate(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="slot-window-start">Window start (UK)</Label>
                <Input
                  id="slot-window-start"
                  type="time"
                  value={windowStart}
                  onChange={(e) => setWindowStart(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="slot-window-end">Window end (UK)</Label>
                <Input
                  id="slot-window-end"
                  type="time"
                  value={windowEnd}
                  onChange={(e) => setWindowEnd(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <Label htmlFor="slot-cutoff-at">Pre-order cutoff (date &amp; time)</Label>
                <Input
                  id="slot-cutoff-at"
                  type="datetime-local"
                  value={cutoffLocal}
                  onChange={(e) => setCutoffLocal(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="slot-max-orders">Max orders (optional)</Label>
                <Input
                  id="slot-max-orders"
                  type="number"
                  min="1"
                  placeholder="e.g. 25"
                  value={maxOrders}
                  onChange={(e) => setMaxOrders(e.target.value)}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="slot-max-units">Max portions (optional)</Label>
                <Input
                  id="slot-max-units"
                  type="number"
                  min="1"
                  placeholder="e.g. 60"
                  value={maxUnits}
                  onChange={(e) => setMaxUnits(e.target.value)}
                />
              </div>
            </div>

            <div className="flex justify-end">
              <Button
                type="submit"
                size="sm"
                data-testid="create-slot-submit-btn"
                disabled={submitting}
                className="gap-1.5"
              >
                <Plus className="h-4 w-4" aria-hidden="true" />
                {submitting ? "Adding slot…" : "Add Scheduled Slot"}
              </Button>
            </div>
          </form>
        )}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
