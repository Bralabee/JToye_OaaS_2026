"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { createPortal } from "react-dom"
import type { OrderDetail } from "@/types/api"
import { KitchenTicket } from "./kitchen-ticket"

/**
 * The print mechanism behind #105.
 *
 * WHY A PORTAL TO `document.body`, and not a wrapper inside the page.
 *
 * The print stylesheet has to hide the whole dashboard chrome — sidebar, header,
 * toasts — and show only the tickets. Doing that from inside the page means marking
 * up the dashboard shell, which lives in `components/dashboard/dashboard-shell.tsx`
 * and belongs to another workstream. Rendering the ticket sheet as a DIRECT CHILD of
 * `<body>` lets one rule in `globals.css` do it instead:
 *
 *     @media print { body:has(#kds-print-root) > *:not(#kds-print-root) { display: none } }
 *
 * which is both smaller and immune to whatever the shell becomes.
 *
 * WHY `window.print()` AND NOT A NEW WINDOW. `document.write` into `window.open` is
 * the other common recipe; it is blocked by this app's enforced CSP and by popup
 * blockers, and it loses the stylesheet. Printing the current document keeps the
 * ticket styles that are already loaded and needs no new origin.
 *
 * WHY THE SHEET IS NOT TORN DOWN AFTER PRINTING. The obvious cleanup is to clear it
 * on `afterprint`. That makes the printed artefact exist only inside a callback the
 * browser may never fire (headless Chromium does not), so a failed print is
 * indistinguishable from a successful one and there is nothing left to inspect. It
 * also makes a re-print — the single most common thing a kitchen does with a ticket,
 * because the first one smudged — a round trip through the board. So the sheet stays
 * mounted, `display:none` on screen, until the next print replaces it or the board
 * changes shop. The `:has()` guard above is what makes that safe: with no sheet
 * mounted, Ctrl+P still prints the board rather than a blank page.
 */

interface PrintState {
  orders: OrderDetail[]
  printedAt: number
}

export function useKitchenPrint(shopName: string | null) {
  const [state, setState] = useState<PrintState | null>(null)
  const [mounted, setMounted] = useState(false)
  const printedRef = useRef<PrintState | null>(null)

  useEffect(() => {
    // Portals need a DOM. Mounting after hydration keeps the server-rendered markup
    // and the first client render identical (the shop-switcher idiom).
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount latch for createPortal
    setMounted(true)
  }, [])

  const print = useCallback((orders: OrderDetail[]) => {
    if (orders.length === 0) return
    setState({ orders, printedAt: Date.now() })
  }, [])

  /** Drop the mounted sheet — called when the board switches shop, so a ticket for
   *  one kitchen can never be re-printed from another's screen. */
  const clear = useCallback(() => setState(null), [])

  useEffect(() => {
    if (!state || printedRef.current === state) return
    printedRef.current = state

    // Let React commit the ticket markup before asking the browser to paginate it —
    // `window.print()` snapshots the document synchronously, so calling it in the same
    // tick prints the page WITHOUT the tickets. Two frames: one to flush the commit,
    // one to be sure layout ran.
    let raf2 = 0
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        try {
          window.print()
        } catch {
          // A headless or embedded browser may not implement print. The sheet is
          // mounted either way, so the user can still print from the browser menu —
          // never let this take the board down.
        }
      })
    })
    return () => {
      cancelAnimationFrame(raf1)
      if (raf2) cancelAnimationFrame(raf2)
    }
  }, [state])

  const sheet =
    mounted && state
      ? createPortal(
          <div id="kds-print-root" data-testid="kds-print-root">
            {state.orders.map((order) => (
              <KitchenTicket
                key={order.id}
                order={order}
                shopName={shopName}
                printedAt={state.printedAt}
              />
            ))}
          </div>,
          document.body
        )
      : null

  return { print, clear, sheet, ticketCount: state?.orders.length ?? 0 }
}
