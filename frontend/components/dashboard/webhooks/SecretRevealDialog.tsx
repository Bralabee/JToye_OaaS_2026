/**
 * Moved to `components/dashboard/secret-reveal-dialog.tsx` in 37-46, when it was generalised for
 * API credentials (UI-SPEC § Component Inventory: title, secretLabel and optional clientId props).
 * This re-export keeps the old import path working, the 37-06 ConfirmActionDialog precedent.
 */
export {
  SecretRevealDialog,
  default,
  type SecretRevealDialogProps,
} from "@/components/dashboard/secret-reveal-dialog"
