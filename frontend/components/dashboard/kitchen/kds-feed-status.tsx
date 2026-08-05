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

/**
 * The pill's box before the page has anything to report (#536).
 *
 * `deriveFeedState` maps "online, no error, socket has never come up" to **offline**,
 * which is right once the page is running and wrong while it is still starting: the
 * machine is online and the socket is mid-handshake. On main that mattered for ~270ms
 * because the header — and therefore the pill — did not exist until the shop list had
 * loaded. Rendering the header from the first paint (which is what stops it growing
 * 108px mid-load) would have stretched a red **Offline** across ~2.9s of every cold
 * load, and a warning shown on every load is one the kitchen stops reading — the exact
 * failure #535 removed from the banner.
 *
 * So this is deliberately NOT a new `FeedStatus`: `deriveFeedState` is untouched and
 * still owns what the feed's state IS. This is a presentation-only "we have not
 * finished starting yet", and the caller may only pass it while the shop list is in
 * flight. It therefore cannot outlive that fetch and sit over a genuinely dead socket.
 */
const PENDING = {
  dot: "bg-slate-400",
  text: "text-slate-700",
  label: "Connecting…",
  Icon: RefreshCw,
}

export function KdsFeedPill({
  state,
  lastSyncedAt,
  pending = false,
}: {
  state: FeedState
  lastSyncedAt: number | null
  /** The page has not started reading yet. See {@link PENDING}. */
  pending?: boolean
}) {
  const tone = pending ? PENDING : PILL[state.status]
  const Icon = tone.Icon
  return (
    <div
      // Distinct testid while pending, for the reason #556 gave for
      // `KdsBoardShopName`: Next streams the page into a `<div hidden id="S:0">` and
      // swaps it in, so for a few milliseconds the fallback and the streamed copy are
      // BOTH in the DOM. Measured on the live stack once the header started rendering
      // from the first paint: `getByTestId('kds-feed-pill')` resolved to 2 elements and
      // e2e/kitchen-flow.spec.ts failed strict mode — intermittently, on mobile only,
      // which is the worst way to find out. Both transient copies are the PENDING state
      // (the server always renders it), so splitting the testid makes the settled one
      // unique by construction rather than by luck.
      data-testid={pending ? "kds-feed-pill-pending" : "kds-feed-pill"}
      className="flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-1.5"
    >
      <span
        aria-hidden
        className={`h-3 w-3 flex-shrink-0 rounded-full ${tone.dot} ${
          pending || state.status === "reconnecting" ? "motion-safe:animate-pulse" : ""
        }`}
      />
      <Icon aria-hidden className={`h-4 w-4 flex-shrink-0 ${tone.text}`} />
      {/* No `hidden sm:inline` — the mobile expo view is the one that needs this most.
          #536: the two spans below reserve their WIDEST content instead of sizing to
          whatever they currently say. feed-state.ts already claimed the pill "occupies
          the same box in every state, so it cannot shift" — measured on the live stack
          at 390px it did not: a cold load renders "Offline —" and settles to
          "Live 14:32:07", the pill widens, and the header's `flex-wrap` control row
          gains a third line. The board and everything under it then move 46px, which
          is a layout shift attributable to a status label. `min-w` (not a fixed `w`)
          so a longer localisation grows the pill rather than clipping it. */}
      <span className={`min-w-[5.5rem] text-sm font-semibold ${tone.text}`}>
        {tone.label}
      </span>
      <span className="min-w-[4rem] text-sm tabular-nums text-slate-600">
        {pending || lastSyncedAt === null ? (
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
