import type * as React from "react"

/**
 * Focus-restore plumbing shared by `dialog.tsx` and `sheet.tsx` (A11Y-2).
 *
 * Radix's modal `Content` closes with `preventDefault()` + focus its own
 * `Trigger`. Every `@/components/ui/dialog` importer in this tree is
 * controlled-`open` with no `<DialogTrigger>` (12 of 12), and the basket
 * drawer is a controlled Sheet with no `<SheetTrigger>`, so that trigger ref
 * is null and focus was measured landing on <body> on every close path
 * (QA council 20260902-134741, probes/a11y/07 + 08). A keyboard user loses
 * their place in a table of 20 rows every time they dismiss a dialog.
 *
 * The primitive therefore remembers what had focus when it opened and puts
 * focus back there — but ONLY while that element is still in the document.
 * If it is gone (a deleted row's own delete button), the handler does not
 * preventDefault and Radix behaves exactly as it does today, which for the
 * three `SheetTrigger` consumers is "focus the trigger" and is unchanged.
 * That guard is what makes a 16-consumer primitive edit strictly-better-or-
 * identical at every call site.
 *
 * `components/storefront/product-detail-modal.tsx` builds on the Radix
 * primitives directly and already does this inline; it is unaffected.
 */

/**
 * Called from `onOpenAutoFocus`, which Radix dispatches BEFORE it moves focus
 * into the panel — so `document.activeElement` is still the invoker. <body>
 * means "nothing had focus" and is recorded as no opener.
 */
export function captureOpener(): HTMLElement | null {
  if (typeof document === "undefined") return null
  const el = document.activeElement
  return el instanceof HTMLElement && el !== document.body ? el : null
}

/**
 * Called from `onCloseAutoFocus` AFTER any consumer-supplied handler, so a
 * call site can still opt out with `event.preventDefault()`.
 */
export function restoreOpener(
  event: Event,
  openerRef: React.MutableRefObject<HTMLElement | null>
): void {
  const opener = openerRef.current
  openerRef.current = null
  if (event.defaultPrevented) return
  if (opener && opener.isConnected) {
    event.preventDefault()
    opener.focus()
  }
}
