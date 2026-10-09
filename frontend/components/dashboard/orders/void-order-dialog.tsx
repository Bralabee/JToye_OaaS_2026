"use client"

import { useId, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { useToast } from "@/hooks/use-toast"
import apiClient from "@/lib/api-client"
import { formatUkDate } from "@/lib/uk-datetime"
import type { OrderDetail, OrderStatus } from "@/types/api"

/**
 * VoidOrderDialog — 37-12, UI-SPEC § B4 (D-10, UXT-018).
 *
 * Takes a CONFIRMED-or-later order out of the books WITHOUT deleting it: the server
 * (37-10, POST /api/v1/orders/{id}/void) keeps the order, records who voided it, when and
 * why, and writes one ledger REVERSAL of its sale. The dialog is the ConfirmActionDialog
 * shape (title, body, "Keep order" / destructive "Void order", "Working…" while pending,
 * no closing mid-flight) plus the one thing that pattern has no slot for: a REQUIRED
 * reason, kept on the order.
 *
 * The UI only reflects; the server decides (403 for a non-admin, 409 for DRAFT/PENDING and
 * for a second void with a different reason, T-37-34).
 */

/** A DRAFT or PENDING order is deleted, never voided (the server's 409 order-not-voidable). */
const NOT_VOIDABLE = new Set<OrderStatus>(["DRAFT", "PENDING"])

/** CONFIRMED or later, and not already voided. */
export function isVoidable(order: { status: OrderStatus; voidedAt?: string | null }): boolean {
  return !NOT_VOIDABLE.has(order.status) && !order.voidedAt
}

/** A card payment the shop took: voiding does not give it back, so the dialog says so. */
export function isCardPaid(order: Pick<OrderDetail, "paymentStatus" | "paymentReference" | "paymentMethod">): boolean {
  return order.paymentStatus === "CAPTURED" || !!order.paymentReference
}

const SUBJECT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export const VOIDER_SELF_COPY = "you"
export const VOIDER_ADMIN_COPY = "a Group admin"

/**
 * Who voided the order, in words. The server stores the authenticated principal (a Keycloak
 * subject id, 37-10), never a display name, and only a Group admin may read the staff
 * directory — the same constraint the allergy note's reader has (31.1-13). So:
 *   - the viewer's own account          -> "you"
 *   - a person in the directory          -> their name (or masked email)
 *   - any other account id               -> "a Group admin" (only Group admins can void;
 *                                           the raw id is never shown)
 *   - a non-id principal name ("kim")    -> that name
 */
export function describeVoider(
  voidedBy: string | null | undefined,
  viewerId: string | null | undefined,
  people: { userId: string; displayName: string | null; maskedEmail: string | null }[] | null
): string {
  if (!voidedBy) return VOIDER_ADMIN_COPY
  if (viewerId && voidedBy === viewerId) return VOIDER_SELF_COPY
  const person = people?.find((p) => p.userId === voidedBy)
  if (person) return person.displayName || person.maskedEmail || VOIDER_ADMIN_COPY
  if (SUBJECT_ID.test(voidedBy)) return VOIDER_ADMIN_COPY
  return voidedBy
}

export const VOID_REASON_LABEL = "Reason (kept on the order)"
export const VOID_REASON_HINT = "Add a reason to void this order"
export const VOID_NO_REFUND_COPY = "Voiding doesn't refund the customer. Use Refund for that."
const VOID_BODY_COPY =
  "The order stays on record, marked as voided, and a reversing entry is added to your ledger so your totals stay correct. This can't be undone."

export function alreadyVoidedCopy(name: string, voidedAt: string | null | undefined): string {
  const on = voidedAt ? formatUkDate(voidedAt) : ""
  return on
    ? `This order was already voided by ${name} on ${on}.`
    : `This order was already voided by ${name}.`
}

const ALREADY_VOIDED_TYPE_SUFFIX = "/order-already-voided"

interface VoidOrderDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  orderId: string
  /** The order number the title names ("Void order ORD-…?"). */
  orderLabel: string
  /** Adds the no-refund sentence for a card-paid order. */
  cardPaid: boolean
  /** Called with the server's order detail after a successful void. */
  onVoided: (detail: OrderDetail) => void
  /** Who voided it, in words, for the 409 copy (may read the staff directory). */
  nameFor: (voidedBy: string | null | undefined) => Promise<string>
}

export function VoidOrderDialog({
  open,
  onOpenChange,
  orderId,
  orderLabel,
  cardPaid,
  onVoided,
  nameFor,
}: VoidOrderDialogProps) {
  const [reason, setReason] = useState("")
  const [pending, setPending] = useState(false)
  const [conflict, setConflict] = useState<string | null>(null)
  const { toast } = useToast()
  const reasonId = useId()
  const hintId = useId()

  const trimmed = reason.trim()

  const close = (next: boolean) => {
    if (pending) return
    if (!next) {
      setReason("")
      setConflict(null)
    }
    onOpenChange(next)
  }

  const handleConfirm = async () => {
    if (!trimmed || pending) return
    setPending(true)
    setConflict(null)
    try {
      const res = await apiClient.post<OrderDetail>(`/api/v1/orders/${orderId}/void`, {
        reason: trimmed,
      })
      setReason("")
      onVoided(res.data)
    } catch (e: unknown) {
      const response = (e as { response?: { status?: number; data?: Record<string, unknown> } } | null)
        ?.response
      const type = response?.data?.type
      if (
        response?.status === 409 &&
        typeof type === "string" &&
        type.endsWith(ALREADY_VOIDED_TYPE_SUFFIX)
      ) {
        const by = typeof response.data?.voidedBy === "string" ? response.data.voidedBy : null
        const at = typeof response.data?.voidedAt === "string" ? response.data.voidedAt : null
        setConflict(alreadyVoidedCopy(await nameFor(by), at))
      } else {
        // Any other failure follows the ConfirmActionDialog contract: the caller says so
        // (a toast), and the dialog stays open with the reason the person typed.
        toast({
          variant: "destructive",
          title: "Couldn't void the order",
          description: "Nothing was changed. Try again.",
        })
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{`Void order ${orderLabel}?`}</DialogTitle>
          <DialogDescription>
            {VOID_BODY_COPY}
            {cardPaid && <> {VOID_NO_REFUND_COPY}</>}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          <Label htmlFor={reasonId}>{VOID_REASON_LABEL}</Label>
          <textarea
            id={reasonId}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            aria-required="true"
            aria-describedby={hintId}
            disabled={pending}
            className="flex min-h-[5.5rem] w-full rounded-md border border-input bg-background px-3 py-2 text-base ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50 sm:text-sm"
          />
        </div>

        {conflict && (
          <p role="alert" className="text-sm font-medium text-red-700 [overflow-wrap:anywhere]">
            {conflict}
          </p>
        )}

        <DialogFooter className="items-center gap-2 sm:gap-2">
          <p id={hintId} className="text-sm text-slate-600 sm:mr-auto">
            {VOID_REASON_HINT}
          </p>
          <Button
            type="button"
            variant="outline"
            className="h-11"
            onClick={() => close(false)}
            disabled={pending}
          >
            Keep order
          </Button>
          <Button
            type="button"
            variant="destructive"
            className="h-11"
            onClick={handleConfirm}
            disabled={pending || !trimmed}
          >
            {pending ? "Working…" : "Void order"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export default VoidOrderDialog
