"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { PauseCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { KITCHEN_POLL_INTERVAL_MS } from "@/components/dashboard/kitchen/feed-state"
import { describeLoadError } from "@/lib/human-error"
import { formatUkTime } from "@/lib/shop-availability"
import {
  fetchShop,
  pauseShopOrders,
  resumeShopOrders,
  type PauseChoice,
} from "@/lib/shops-api"
import type { Shop } from "@/types/api"

/**
 * 37-21 (UI-SPEC K3 vendor side, D-15, RWO-022): the kitchen's one-tap pause.
 *
 * TRUTH SOURCE. Everything the board says about a pause comes from the SERVER's shop
 * (`ordersPausedUntil` / `ordersPausedIndefinitely`, 37-20): the PUT/DELETE response, and the shop
 * re-read that follows it, on the board's 60 s liveness cadence and at the stated end. The browser
 * never adds 20 minutes to its own clock (threat T-37-54: a banner showing a pause the server does
 * not hold). The clock is used for ONE thing: a stored `ordersPausedUntil` that has passed is no
 * pause, because the server keeps an ended pause's instant on the row instead of clearing it.
 */
export type PauseView =
  | { kind: "none" }
  | { kind: "timed"; until: string }
  | { kind: "indefinite" }

const NOT_PAUSED: PauseView = { kind: "none" }

/** UI-SPEC § Copywriting K3, "Pause refused (403)". */
export const PAUSE_REFUSED_COPY = "You can't pause orders for this shop. Ask a manager."

type PauseFields = Pick<Shop, "ordersPausedUntil" | "ordersPausedIndefinitely">

/** The pause the server holds, read at `nowMs`. */
export function readPauseView(shop: PauseFields | null | undefined, nowMs: number): PauseView {
  if (!shop) return NOT_PAUSED
  if (shop.ordersPausedIndefinitely === true) return { kind: "indefinite" }
  const until = shop.ordersPausedUntil
  if (until) {
    const at = Date.parse(until)
    if (!Number.isNaN(at) && at > nowMs) return { kind: "timed", until }
  }
  return NOT_PAUSED
}

/** "Paused until 18:40", "Paused", or "Pause orders". */
export function pauseControlLabel(view: PauseView): string {
  if (view.kind === "indefinite") return "Paused"
  if (view.kind === "timed") return `Paused until ${formatUkTime(view.until) ?? ""}`.trim()
  return "Pause orders"
}

function httpStatus(error: unknown): number | undefined {
  return (error as { response?: { status?: number } })?.response?.status
}

interface PauseState {
  shopId: string | null
  shop: Shop | null
  view: PauseView
}

const EMPTY: PauseState = { shopId: null, shop: null, view: NOT_PAUSED }

/**
 * The selected shop's pause, as the server holds it, plus the two writes.
 *
 * Re-reads: on the shop changing; every KITCHEN_POLL_INTERVAL_MS (60 s, the board's own liveness
 * poll) while `active`; after each write; and at a timed pause's stated end (the banner is hidden at
 * that moment and the re-read confirms or replaces it). A stopped board (`active` false) reads
 * nothing, like the rest of the board.
 */
export function useShopPause(shopId: string | null, { active = true }: { active?: boolean } = {}) {
  const [state, setState] = useState<PauseState>(EMPTY)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<{ shopId: string; message: string } | null>(null)
  // A ref as well as state: two taps in one frame both see `pending` false in their closures.
  const inFlight = useRef(false)

  const apply = useCallback((id: string, shop: Shop) => {
    setState({ shopId: id, shop, view: readPauseView(shop, Date.now()) })
  }, [])

  const refresh = useCallback(async () => {
    if (!shopId) return
    try {
      const shop = await fetchShop(shopId)
      if (shop && typeof shop === "object") apply(shopId, shop)
    } catch {
      // The board's own read reports a failing feed; the last pause the server stated stays shown.
    }
  }, [shopId, apply])

  useEffect(() => {
    if (!shopId || !active) return
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-change effect: state is set only after the awaited read lands, never synchronously
    void refresh()
    const interval = setInterval(() => void refresh(), KITCHEN_POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [shopId, active, refresh])

  const current = state.shopId === shopId ? state : EMPTY
  const until = current.view.kind === "timed" ? current.view.until : null

  // At the stated end: stop claiming the pause, then ask the server whether it really ended.
  useEffect(() => {
    if (!until || !active) return
    const wait = Math.max(0, Date.parse(until) - Date.now()) + 500
    const timer = setTimeout(() => {
      setState((prev) => ({ ...prev, view: readPauseView(prev.shop, Date.now()) }))
      void refresh()
    }, wait)
    return () => clearTimeout(timer)
  }, [until, active, refresh])

  const write = useCallback(
    async (send: (id: string) => Promise<Shop>, failure: string) => {
      if (!shopId || inFlight.current) return
      inFlight.current = true
      setPending(true)
      setError(null)
      try {
        apply(shopId, await send(shopId))
        void refresh()
      } catch (err) {
        const message =
          httpStatus(err) === 403 ? PAUSE_REFUSED_COPY : describeLoadError(err, failure).message
        setError({ shopId, message })
      } finally {
        inFlight.current = false
        setPending(false)
      }
    },
    [shopId, apply, refresh]
  )

  const pause = useCallback(
    (choice: PauseChoice) => write((id) => pauseShopOrders(id, choice), "Could not pause orders"),
    [write]
  )
  const resume = useCallback(
    () => write((id) => resumeShopOrders(id), "Could not resume orders"),
    [write]
  )

  return {
    view: current.view,
    /**
     * The server has answered for THIS shop. Until then the control is disabled: before the first read
     * "Pause orders" would be a claim (not paused) nobody has checked (found live on a paused shop).
     */
    loaded: state.shopId === shopId && state.shop !== null,
    pending,
    error: error && error.shopId === shopId ? error.message : null,
    pause,
    resume,
  }
}

interface PauseOrdersControlProps {
  view: PauseView
  pending: boolean
  error: string | null
  /** No shop to pause yet (the shop list is loading or empty). */
  disabled?: boolean
  onPause: (choice: PauseChoice) => Promise<void>
  onResume: () => Promise<void>
}

const MENU_ROW = "min-h-11 cursor-pointer px-3 text-base"

/**
 * The kitchen-header control (UI-SPEC K3): outline, h-11, PauseCircle. One tap on a menu row applies
 * (pausing is reversible in one tap, so there is no confirm). The menu stays open, every row disabled,
 * until the server answers, then closes.
 */
export function PauseOrdersControl({
  view,
  pending,
  error,
  disabled = false,
  onPause,
  onResume,
}: PauseOrdersControlProps) {
  const [open, setOpen] = useState(false)
  const paused = view.kind !== "none"

  const run = (action: () => Promise<void>) => (event: Event) => {
    event.preventDefault()
    if (pending) return
    void action().finally(() => setOpen(false))
  }

  return (
    <div className="flex flex-col gap-1">
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            className="kds-press h-11"
            disabled={disabled || pending}
            aria-busy={pending || undefined}
            data-testid="kds-pause-control"
          >
            <PauseCircle className="mr-2 h-5 w-5" aria-hidden="true" />
            {pauseControlLabel(view)}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-[12rem]">
          <DropdownMenuItem className={MENU_ROW} disabled={pending} onSelect={run(() => onPause({ minutes: 20 }))}>
            20 minutes
          </DropdownMenuItem>
          <DropdownMenuItem className={MENU_ROW} disabled={pending} onSelect={run(() => onPause({ minutes: 40 }))}>
            40 minutes
          </DropdownMenuItem>
          <DropdownMenuItem
            className={MENU_ROW}
            disabled={pending}
            onSelect={run(() => onPause({ untilReopened: true }))}
          >
            Until I reopen
          </DropdownMenuItem>
          {paused && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem className={`${MENU_ROW} font-semibold`} disabled={pending} onSelect={run(onResume)}>
                Resume orders now
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {error && (
        <p role="alert" className="max-w-[16rem] text-sm font-medium text-red-700">
          {error}
        </p>
      )}
    </div>
  )
}
