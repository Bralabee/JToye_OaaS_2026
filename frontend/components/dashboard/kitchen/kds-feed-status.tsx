"use client"

import { RefreshCw, Wifi, WifiOff, TriangleAlert } from "lucide-react"
import { Button } from "@/components/ui/button"
import { formatAge, formatClock, type FeedState } from "./feed-state"

/**
 * KDS feed liveness, made legible from across a kitchen (#106).
 *
 * REPLACES a `h-2.5 w-2.5` dot whose only label was `hidden sm:inline` plus a
 * `title=` attribute — invisible on the handheld the expo actually holds, and
 * unreadable from a wall mount at any size. Two pieces:
 *
 *   `KdsFeedPill`   always on, in the header: state word + last-updated clock.
 *   `KdsFeedBanner` only when something is wrong: full-width, says what is wrong,
 *                   how old the tickets are, and offers the one action that helps.
 *
 * COLOUR IS NEVER THE ONLY CHANNEL. Every state carries an icon and a word as well
 * as a hue — a KDS is the archetypal glare-and-distance screen, and roughly one cook
 * in twelve cannot separate the amber from the green.
 */

const PILL: Record<
  FeedState["status"],
  { dot: string; text: string; label: string; Icon: typeof Wifi }
> = {
  live: {
    dot: "bg-emerald-600",
    text: "text-emerald-800",
    label: "Live",
    Icon: Wifi,
  },
  reconnecting: {
    dot: "bg-amber-500",
    text: "text-amber-800",
    label: "Reconnecting",
    Icon: RefreshCw,
  },
  offline: {
    dot: "bg-red-600",
    text: "text-red-800",
    label: "Offline",
    Icon: WifiOff,
  },
  error: {
    dot: "bg-red-600",
    text: "text-red-800",
    label: "Not updating",
    Icon: TriangleAlert,
  },
}

export function KdsFeedPill({
  state,
  lastSyncedAt,
}: {
  state: FeedState
  lastSyncedAt: number | null
}) {
  const tone = PILL[state.status]
  const Icon = tone.Icon
  return (
    <div
      data-testid="kds-feed-pill"
      className="flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-1.5"
    >
      <span
        aria-hidden
        className={`h-3 w-3 flex-shrink-0 rounded-full ${tone.dot} ${
          state.status === "reconnecting" ? "motion-safe:animate-pulse" : ""
        }`}
      />
      <Icon aria-hidden className={`h-4 w-4 flex-shrink-0 ${tone.text}`} />
      {/* No `hidden sm:inline` — the mobile expo view is the one that needs this most. */}
      <span className={`text-sm font-semibold ${tone.text}`}>{tone.label}</span>
      <span className="text-sm tabular-nums text-slate-600">
        {lastSyncedAt === null ? (
          "—"
        ) : (
          <>
            <span className="sr-only">Last updated </span>
            {formatClock(lastSyncedAt)}
          </>
        )}
      </span>
    </div>
  )
}

interface BannerTone {
  box: string
  heading: string
  /** What the operator loses while this lasts. Differs per state on purpose. */
  consequence: string
}

const BANNER: Record<FeedState["status"], BannerTone | null> = {
  // `live` only reaches the banner via `stale` — see STALE_ONLY.
  live: null,
  reconnecting: {
    box: "border-amber-500 bg-amber-50 text-amber-900",
    heading: "Reconnecting to the live order feed",
    consequence: "New orders may not appear until this reconnects.",
  },
  offline: {
    box: "border-red-600 bg-red-50 text-red-900",
    heading: "Offline — no live order feed",
    consequence: "New orders will not appear until the connection is back.",
  },
  error: {
    box: "border-red-600 bg-red-50 text-red-900",
    heading: "Orders are not refreshing",
    consequence: "New orders will not appear until this is fixed.",
  },
}

/**
 * The socket is up and the browser is online, but reads have stopped landing — a
 * background-throttled tab, a hung gateway. The live feed may well still deliver, so
 * this deliberately does NOT claim new orders will be missed; it says only what is
 * actually known, which is that the board is old.
 */
const STALE_ONLY: BannerTone = {
  box: "border-amber-500 bg-amber-50 text-amber-900",
  heading: "These tickets may be out of date",
  consequence: "The board has not refreshed recently.",
}

export function KdsFeedBanner({
  state,
  lastSyncedAt,
  onRefresh,
  refreshing,
}: {
  state: FeedState
  lastSyncedAt: number | null
  onRefresh: () => void
  refreshing: boolean
}) {
  if (!state.alerting) return null
  const tone = BANNER[state.status] ?? STALE_ONLY

  return (
    <div
      // `role="alert"` and not `status`: a kitchen that has stopped receiving orders
      // is an interruption, and a screen reader user gets no benefit from politeness
      // while tickets silently stop arriving.
      role="alert"
      data-testid="kds-feed-banner"
      className={`flex flex-col gap-3 rounded-lg border-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between ${tone.box}`}
    >
      <div className="flex items-start gap-3">
        <TriangleAlert aria-hidden className="mt-0.5 h-6 w-6 flex-shrink-0" />
        <div>
          <p className="text-lg font-bold leading-tight">{tone.heading}</p>
          <p className="mt-0.5 text-sm font-medium">
            {state.ageMs === null ? (
              "No orders have loaded yet."
            ) : (
              <>
                {tone.consequence} Last updated{" "}
                <span className="tabular-nums">
                  {formatClock(lastSyncedAt as number)}
                </span>{" "}
                <span data-testid="kds-stale-age" className="tabular-nums">
                  ({formatAge(state.ageMs)})
                </span>
                .
              </>
            )}
          </p>
        </div>
      </div>
      <Button
        variant="outline"
        onClick={onRefresh}
        disabled={refreshing}
        // Bigger than the default: this is pressed with a thumb, in a hurry,
        // possibly through a glove.
        className="kds-press h-11 flex-shrink-0 border-current bg-white/70 px-5 text-base font-semibold"
      >
        <RefreshCw
          aria-hidden
          className={`mr-2 h-5 w-5 ${refreshing ? "motion-safe:animate-spin" : ""}`}
        />
        {refreshing ? "Refreshing…" : "Refresh now"}
      </Button>
    </div>
  )
}
