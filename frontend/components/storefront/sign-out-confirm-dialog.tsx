"use client"

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

/**
 * #877 / UXT-107 (J-10, P2-GRA-12): signing out from the header icon asks first.
 *
 * The header's sign-out is an icon, and a mis-tap on a phone signed an older customer out and, because an
 * explicit sign-out clears every basket stored on the device (#459), emptied their basket with it. The
 * labelled, 44 px control of the earlier fix made the target findable; this makes the action recoverable.
 *
 * "Stay signed in" is FIRST in DOM order, so it takes the dialog's initial focus (Radix focuses the first
 * tabbable element) and Enter on a freshly opened dialog never signs anyone out. Escape and a tap outside
 * both cancel. While the sign-out is in flight the dialog stays open and the confirm button is busy,
 * because the confirm navigates the whole document away (the same WR-06 contract as the trigger: busy
 * until the page goes, never reset on promise resolution).
 *
 * Used by the two headers that show the signed-in icon (StorefrontNav and PublicHeader). Not used by
 * "Not you? Sign out": that one is offered to a signed-OUT browser to end somebody else's lingering
 * session, and a confirmation there would only stand between the next person and their own account.
 */
export function SignOutConfirmDialog({
  open,
  onOpenChange,
  onConfirm,
  busy,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
  busy: boolean
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => (busy ? undefined : onOpenChange(next))}>
      <DialogContent showCloseButton={false} className="max-w-sm" data-testid="sign-out-confirm">
        <DialogHeader>
          <DialogTitle>Sign out?</DialogTitle>
          <DialogDescription>
            You will need to sign in again to see your orders. Baskets saved on this device are cleared when you
            sign out.
          </DialogDescription>
        </DialogHeader>
        {/* DOM order is visual order at every width: DialogFooter's default `flex-col-reverse` would draw the
            destructive button ABOVE "Stay signed in" on a phone while focus starts on "Stay signed in"
            (WCAG 2.4.3 / 1.3.2). */}
        <DialogFooter className="flex-col gap-2 sm:flex-row sm:gap-2 sm:space-x-0">
          <button
            type="button"
            onClick={() => onOpenChange(false)}
            disabled={busy}
            className="inline-flex min-h-11 items-center justify-center rounded-lg border border-slate-300 px-4 text-sm font-medium text-slate-800 transition-colors hover:bg-slate-50 disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
          >
            Stay signed in
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            aria-busy={busy}
            className="inline-flex min-h-11 items-center justify-center rounded-lg bg-oxblood px-4 text-sm font-medium text-white transition-colors hover:bg-oxblood-700 disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
          >
            Sign out
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
