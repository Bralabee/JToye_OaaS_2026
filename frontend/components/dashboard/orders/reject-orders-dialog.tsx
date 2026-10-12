"use client"

/**
 * RejectOrdersDialog — 37-30 (UI-SPEC § D1; D-18, SC-4; RWO-041).
 *
 * RED scaffold: the exported names exist so the suites load and fail on their planned
 * assertions rather than on a missing module. The GREEN commit replaces this file.
 */

export interface RejectableOrder {
  id: string
  label: string
}

export type RejectOutcome = "REJECTED" | "ALREADY_ACCEPTED" | "ALREADY_CANCELLED" | "NOT_FOUND"

export interface RejectOrdersDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  orders: RejectableOrder[]
  onRejected: (orderIds: string[]) => void
  onClosedAfterSubmit?: () => void
}

export function RejectOrdersDialog(_props: RejectOrdersDialogProps) {
  return null
}

export default RejectOrdersDialog
