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
 * ConfirmActionDialog (COMMS-06) — a focus-trapped, Esc-cancellable confirm
 * used for Rotate secret, Revoke endpoint, and Replay delivery. The confirm
 * button is the orange accent by default and the destructive red variant for
 * terminal actions (Revoke). The body is linked via `aria-describedby` (radix
 * wires this from `DialogDescription`).
 *
 * `onConfirm` may be async — the dialog shows a pending state and closes itself
 * on success; failures are surfaced by the caller (toast) and re-enable the
 * button.
 */

interface ConfirmActionDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: React.ReactNode
  confirmLabel: string
  destructive?: boolean
  onConfirm: () => void | Promise<void>
}

export function ConfirmActionDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
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
            Cancel
          </Button>
          <Button
            type="button"
            variant={destructive ? "destructive" : undefined}
            className={
              destructive ? undefined : "bg-orange-500 text-white hover:bg-orange-600"
            }
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
