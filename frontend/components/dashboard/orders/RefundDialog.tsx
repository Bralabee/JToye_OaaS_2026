"use client"

import { useEffect, useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
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
import apiClient from "@/lib/api-client"
import type { CreateRefundRequest, Refund, RefundReason } from "@/types/api"

/**
 * RefundDialog
 * ------------
 * Vendor-facing modal that posts a refund request to
 *   POST /api/v1/orders/{id}/refund
 *
 * Per Phase 17 CONTEXT (UC-1 LOCKED): the Idempotency-Key header is generated
 * fresh per submit-click via crypto.randomUUID(). The backend uses a
 * stored-first idempotency strategy — replays return the SAME refund row, so
 * a client-side double-click cannot create two refunds.
 *
 * Stays inside the existing food-delivery palette (orange/emerald/slate). NO
 * new design-system primitives, NO serif/editorial type — see
 * `feedback_design_direction.md` memory.
 */

interface RefundDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  orderId: string
  remainingPennies: number
  onSuccess?: (refund: Refund) => void
}

const REASONS: { value: RefundReason; label: string }[] = [
  { value: "REQUESTED_BY_CUSTOMER", label: "Requested by customer" },
  { value: "DUPLICATE", label: "Duplicate charge" },
  { value: "FRAUDULENT", label: "Fraudulent" },
]

function formatPounds(pennies: number): string {
  return (Math.max(0, pennies) / 100).toFixed(2)
}

/**
 * Generate a cryptographically-secure idempotency key.
 *
 * <p>Idempotency keys are a security-critical contract — a key collision
 * across same-tenant submits would let one client replay another's refund
 * via {@code findByTenantIdAndIdempotencyKey}. Math.random is per-tab-seeded
 * and timestamps are observable, so the WR-07 fallback to
 * {@code `${Date.now()}-${Math.random()}`} is unsafe.
 *
 * <p>Order of preference:
 *   1. {@code crypto.randomUUID} — modern HTTPS contexts (Safari 15.4+, Chrome 92+)
 *   2. {@code crypto.getRandomValues} — RFC 4122 v4 UUID hand-rolled from
 *      16 secure random bytes
 *   3. throw — secure random is mandatory; we will never silently fall
 *      back to Math.random for an idempotency key.
 */
function makeIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID()
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const buf = new Uint8Array(16)
    crypto.getRandomValues(buf)
    // RFC 4122 v4 — set version (top 4 bits of byte 6) and variant (top 2 bits of byte 8).
    buf[6] = (buf[6] & 0x0f) | 0x40
    buf[8] = (buf[8] & 0x3f) | 0x80
    const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("")
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  throw new Error(
    "No secure random source available — refunds require a cryptographic Idempotency-Key. " +
      "Upgrade to a browser that supports crypto.randomUUID or crypto.getRandomValues."
  )
}

export function RefundDialog({
  open,
  onOpenChange,
  orderId,
  remainingPennies,
  onSuccess,
}: RefundDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  const remainingPounds = formatPounds(remainingPennies)

  // Schema is rebuilt when remainingPennies changes so the upper-bound
  // validation tracks the latest backend state without reload.
  const schema = z.object({
    amountPounds: z
      .string()
      .optional()
      .refine(
        (v) => !v || /^\d+(\.\d{1,2})?$/.test(v),
        "Use a valid amount, e.g., 4.50"
      )
      .refine(
        (v) => !v || Math.round(parseFloat(v) * 100) <= remainingPennies,
        `Amount exceeds remaining £${remainingPounds}`
      )
      .refine(
        (v) => !v || Math.round(parseFloat(v) * 100) > 0,
        "Amount must be greater than zero"
      ),
    reason: z.enum(["REQUESTED_BY_CUSTOMER", "DUPLICATE", "FRAUDULENT"]),
    note: z
      .string()
      .max(500, "Max 500 characters")
      .optional(),
  })
  type FormValues = z.infer<typeof schema>

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { reason: "REQUESTED_BY_CUSTOMER", amountPounds: "", note: "" },
  })

  // Reset the form whenever the dialog re-opens so previous error state and
  // values do not bleed across vendor sessions.
  useEffect(() => {
    if (open) {
      reset({ reason: "REQUESTED_BY_CUSTOMER", amountPounds: "", note: "" })
      setServerError(null)
    }
  }, [open, reset])

  const onSubmit = async (values: FormValues) => {
    setSubmitting(true)
    setServerError(null)
    try {
      const trimmedNote = values.note?.trim() ?? ""
      const payload: CreateRefundRequest = {
        amountPennies:
          values.amountPounds && values.amountPounds.length > 0
            ? Math.round(parseFloat(values.amountPounds) * 100)
            : undefined,
        reason: values.reason,
        note: trimmedNote.length > 0 ? trimmedNote : undefined,
      }
      const idempotencyKey = makeIdempotencyKey()
      const res = await apiClient.post<Refund>(
        `/api/v1/orders/${orderId}/refund`,
        payload,
        { headers: { "Idempotency-Key": idempotencyKey } }
      )
      reset()
      onSuccess?.(res.data)
      onOpenChange(false)
    } catch (err: unknown) {
      const e = err as {
        response?: { data?: { detail?: string; message?: string } }
        message?: string
      }
      setServerError(
        e?.response?.data?.detail ??
          e?.response?.data?.message ??
          e?.message ??
          "Refund failed — please try again."
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Issue refund</DialogTitle>
          <DialogDescription>
            Refunds go to the original payment method. Stripe processing may
            take a few business days.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="amountPounds">
              Amount (£){" "}
              <span className="text-xs font-normal text-slate-500">
                — leave blank for full remaining (£{remainingPounds})
              </span>
            </Label>
            <Input
              id="amountPounds"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              placeholder={`Up to ${remainingPounds}`}
              {...register("amountPounds")}
            />
            {errors.amountPounds && (
              <p className="text-xs text-red-600">{errors.amountPounds.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="reason">Reason</Label>
            <select
              id="reason"
              className="flex h-10 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-orange-500"
              {...register("reason")}
            >
              {REASONS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="note">Internal note (optional)</Label>
            <textarea
              id="note"
              rows={3}
              className="flex w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-orange-500"
              placeholder="Visible to staff only"
              {...register("note")}
            />
            {errors.note && (
              <p className="text-xs text-red-600">{errors.note.message}</p>
            )}
          </div>

          {serverError && (
            <p
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {serverError}
            </p>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={submitting}
              className="bg-orange-500 text-white hover:bg-orange-600"
            >
              {submitting ? "Refunding…" : "Issue refund"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export default RefundDialog
