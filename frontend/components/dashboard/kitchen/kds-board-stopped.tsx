"use client"

import { useEffect, useRef, useState } from "react"
import { createPortal } from "react-dom"
import { OctagonAlert } from "lucide-react"

/**
 * 37-18 (D-14, D-08 on the kitchen route; UXT-021, UI-SPEC K2): the "board stopped" overlay.
 *
 * WHAT IT REPLACES. A kitchen board whose session lapsed used to be redirected to /auth/signin by
 * the api-client, like every other dashboard page. On a wall-mounted screen that is a silent
 * failure: the tickets vanish, a sign-in card appears, and nobody across the kitchen notices until
 * a customer asks where their food is. An access grant revoked mid-shift was quieter still.
 *
 * WHAT IT IS. A full-screen, solid red-700 `alertdialog` that cannot be mistaken for a working
 * board: no transparency (the stale tickets underneath are not valid and must not be readable as if
 * they were), nothing animates (UI-SPEC: static red), focus on the primary button and kept there,
 * the rest of the page `inert`. The page decides WHEN it shows (a claimed `jtoye:session-lapsed`,
 * or a shop-access-denied 403); this component only decides how it looks and behaves.
 *
 * OVERFLOW (UI-SPEC K2 overflow, unresolved there; the plan's recorded assumption). The container
 * is `overflow-y-auto` with its content in normal flow, so on a 375x667 screen at 130% text both
 * 56 px buttons stay reachable and focusable by scrolling. The type is NOT scaled down.
 */

export type BoardStoppedReason = "signedOut" | "noAccess"

/** Where "Sign in again" goes: the sign-in page, which returns the tablet to the kitchen. */
export const KITCHEN_SIGN_IN_HREF = "/auth/signin?callbackUrl=%2Fdashboard%2Fkitchen"

/** The tab title while the board is stopped (UI-SPEC K2). */
export const STOPPED_TAB_TITLE = "STOPPED · Kitchen Display — J'Toye"

/** UI-SPEC § Copywriting K2, verbatim. */
export const BOARD_STOPPED_COPY: Record<
  BoardStoppedReason,
  { headline: string; body: string; primary: string }
> = {
  signedOut: {
    headline: "Signed out: new orders are NOT showing",
    body: "Your sign-in has ended, so this screen has stopped receiving orders. Sign in again to bring the board back.",
    primary: "Sign in again",
  },
  noAccess: {
    headline: "Your access has ended: new orders are NOT showing",
    body: "Someone has removed your access to this kitchen. Ask a Group admin if you need it back.",
    primary: "Sign out",
  },
}

export const SILENCE_ALARM_LABEL = "Silence alarm"

export interface KdsBoardStoppedProps {
  reason: BoardStoppedReason
  /** "Silence alarm": stops the sound only; the red screen stays. */
  onSilence?: () => void
  /** "Sign out" on the access-ended variant. */
  onSignOut?: () => void
}

const HEADLINE_ID = "kds-board-stopped-headline"
const BODY_ID = "kds-board-stopped-body"

// White on red-700 is 6.47:1 (UI-SPEC contrast table); the focus ring is white, offset against red.
const FOCUS =
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-red-700"
const BUTTON = `inline-flex h-14 w-full max-w-sm items-center justify-center rounded-md px-6 text-lg font-semibold ${FOCUS}`

export function KdsBoardStopped({ reason, onSilence, onSignOut }: KdsBoardStoppedProps) {
  // The overlay lives in its own node at the end of <body>, so everything else on the page can be
  // made inert without making the overlay inert with it. Created lazily: it only ever renders on
  // the client, after an event.
  const [host] = useState<HTMLElement | null>(() =>
    typeof document === "undefined" ? null : document.createElement("div")
  )
  const dialogRef = useRef<HTMLDivElement>(null)
  const primaryRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!host) return
    host.setAttribute("data-kds-board-stopped", "")
    document.body.appendChild(host)

    // The rest of the page goes inert: no tab stop, no click, no screen-reader browse behind the
    // alarm. Whatever was already inert stays so when the overlay goes.
    const others = Array.from(document.body.children).filter((el) => el !== host)
    const wasInert = others.map((el) => el.hasAttribute("inert"))
    others.forEach((el) => el.setAttribute("inert", ""))

    primaryRef.current?.focus()

    const focusables = () =>
      Array.from(
        dialogRef.current?.querySelectorAll<HTMLElement>("a[href], button:not([disabled])") ?? []
      )

    // Focus is trapped: Tab past the last control wraps to the first, Shift+Tab before the first
    // wraps to the last. `inert` already does this in a browser; this keeps it true where inert is
    // not honoured.
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return
      const items = focusables()
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      const active = document.activeElement
      if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }
    // Anything outside that takes focus anyway gives it straight back.
    const onFocusIn = (event: FocusEvent) => {
      if (event.target instanceof Node && !host.contains(event.target)) {
        primaryRef.current?.focus()
      }
    }
    const dialog = dialogRef.current
    dialog?.addEventListener("keydown", onKeyDown)
    document.addEventListener("focusin", onFocusIn)

    return () => {
      dialog?.removeEventListener("keydown", onKeyDown)
      document.removeEventListener("focusin", onFocusIn)
      others.forEach((el, i) => {
        if (!wasInert[i]) el.removeAttribute("inert")
      })
      host.remove()
    }
  }, [host])

  if (!host) return null
  const copy = BOARD_STOPPED_COPY[reason]

  return createPortal(
    <div
      ref={dialogRef}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={HEADLINE_ID}
      aria-describedby={BODY_ID}
      data-testid="kds-board-stopped"
      data-reason={reason}
      className="fixed inset-0 z-[100] overflow-y-auto bg-red-700 text-white"
    >
      <div className="mx-auto flex min-h-full w-full max-w-xl flex-col items-center justify-center gap-6 px-6 py-10 text-center">
        <OctagonAlert aria-hidden="true" className="h-16 w-16 flex-shrink-0" />
        <h2 id={HEADLINE_ID} className="text-4xl font-semibold leading-[1.1]">
          {copy.headline}
        </h2>
        <p id={BODY_ID} className="text-base leading-normal">
          {copy.body}
        </p>
        <div className="flex w-full flex-col items-center gap-3">
          {reason === "signedOut" ? (
            <a
              ref={(el) => {
                primaryRef.current = el
              }}
              href={KITCHEN_SIGN_IN_HREF}
              className={`${BUTTON} bg-white text-red-800`}
            >
              {copy.primary}
            </a>
          ) : (
            <button
              ref={(el) => {
                primaryRef.current = el
              }}
              type="button"
              onClick={onSignOut}
              className={`${BUTTON} bg-white text-red-800`}
            >
              {copy.primary}
            </button>
          )}
          <button
            type="button"
            onClick={onSilence}
            className={`${BUTTON} border-2 border-white bg-transparent text-white`}
          >
            {SILENCE_ALARM_LABEL}
          </button>
        </div>
      </div>
    </div>,
    host
  )
}
