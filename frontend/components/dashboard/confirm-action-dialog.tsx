"use client"

import { useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"

/**
 * ConfirmActionDialog — the dashboard's one confirm pattern: focus-trapped,
 * Esc-cancellable, the body linked via `aria-describedby` (radix wires this from
 * `DialogDescription`). First built for the webhooks page (COMMS-06: Rotate secret,
 * Revoke endpoint, Replay delivery); moved here in 37-06 for Remove access (B1) and
 * the Phase 37 confirms that follow (void, reject, credentials, invitations).
 *
 * - `cancelLabel` is REQUIRED and names what cancelling keeps ("Keep access",
 *   "Keep endpoint", "Keep current secret"). A bare "Cancel" next to a destructive
 *   button makes the person work out which of the two is the safe one.
 * - A non-destructive confirm is the `Button` default variant (orange-700 =
 *   `--primary`, 5.18:1 with white text). It replaced an orange-500 literal that
 *   measured 2.80:1 and failed WCAG 1.4.3 (UI-SPEC § Color). A terminal action
 *   passes `destructive`.
 * - `onConfirm` may be async: the dialog shows "Working…" and cannot be closed
 *   while it runs. Failures are surfaced by the caller (toast or inline notice).
 */

export interface ConfirmActionDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: React.ReactNode
  confirmLabel: string
  /** What cancelling keeps, as a verb + noun. Required: there is no default. */
  cancelLabel: string
  destructive?: boolean
  onConfirm: () => void | Promise<void>
}

export function ConfirmActionDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  destructive = false,
  onConfirm,
}: ConfirmActionDialogProps) {
  const [pending, setPending] = useState(false)

  const handleConfirm = async () => {
    setPending(true)
    try {
      await onConfirm()
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !pending && onOpenChange(o)}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={pending}
          >
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant={destructive ? "destructive" : "default"}
            onClick={handleConfirm}
            disabled={pending}
          >
            {pending ? "Working…" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export default ConfirmActionDialog
