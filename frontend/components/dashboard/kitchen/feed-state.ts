/**
 * Kitchen feed liveness — the pure derivation behind the KDS status pill and the
 * stale banner (#106).
 *
 * WHY THIS IS NOT JUST `connected` FROM useStomp.
 *
 * Measured in a real browser on 2026-08-04 against the live compose stack: with
 * Playwright's `context.setOffline(true)` held for twelve seconds — the machine
 * genuinely offline, every request failing — the kitchen board still displayed a
 * green dot reading "Connected". The STOMP client had not yet noticed its socket was
 * dead, and the page had no other source of truth to contradict it. That is worse
 * than the small indicator #106 filed: an indicator that is merely small is ignorable,
 * one that is confidently wrong is trusted.
 *
 * So liveness here is decided by three inputs, and the socket's own opinion is only
 * one of them:
 *
 *   1. `online`      — `navigator.onLine`, which flips synchronously on the browser's
 *                      own offline/online events. Cheap, immediate, and cannot be
 *                      contradicted by a socket that has not timed out yet.
 *   2. `lastSyncedAt`— when the board last actually READ data from the API. The page
 *                      polls on a fixed interval as well as listening on the socket,
 *                      so this advancing is positive proof of liveness; it stalling is
 *                      positive proof of staleness. A quiet kitchen with no new orders
 *                      still refreshes this, so "no tickets for an hour" never reads
 *                      as stale.
 *   3. `connected` / `reconnecting` — the socket's view, used to name WHICH failure it
 *                      is once one of the first two has established that there is one.
 */

/** How long the board may go without a successful read before it says so. */
export const STALE_AFTER_MS = 90_000

/** How often the board re-reads from the API as a liveness probe + safety net. */
export const KITCHEN_POLL_INTERVAL_MS = 60_000

/** How often the status line re-renders so the age of the stamp stays truthful. */
export const KITCHEN_CLOCK_TICK_MS = 10_000

export type FeedStatus = "live" | "reconnecting" | "offline" | "error"

export interface FeedInputs {
  /** `navigator.onLine`. */
  online: boolean
  /** The STOMP socket believes it is subscribed. */
  connected: boolean
  /** The STOMP socket lost a connection it previously had and is retrying. */
  reconnecting: boolean
  /** Epoch ms of the last SUCCESSFUL orders read, or null before the first one. */
  lastSyncedAt: number | null
  /** The last orders read threw. */
  lastSyncFailed: boolean
  /** Epoch ms "now" — passed in so the derivation stays pure and testable. */
  now: number
}

export interface FeedState {
  status: FeedStatus
  /** Data has not been refreshed within {@link STALE_AFTER_MS}. */
  stale: boolean
  /** Age of the data in ms, or null before the first successful read. */
  ageMs: number | null
  /** True when the board should raise a banner rather than just tint the pill. */
  alerting: boolean
}

export function deriveFeedState(input: FeedInputs): FeedState {
  const { online, connected, reconnecting, lastSyncedAt, lastSyncFailed, now } = input

  const ageMs = lastSyncedAt === null ? null : Math.max(0, now - lastSyncedAt)
  // Before the first successful read there is nothing to be stale ABOUT — the board
  // is empty and its own loading state says so. Claiming "stale" there would fire on
  // every cold load, which is the fastest way to train a kitchen to ignore the banner.
  const stale = ageMs !== null && ageMs > STALE_AFTER_MS

  let status: FeedStatus
  if (!online) {
    status = "offline"
  } else if (lastSyncFailed) {
    status = "error"
  } else if (connected) {
    status = "live"
  } else if (reconnecting) {
    status = "reconnecting"
  } else {
    // Online, no error, and a socket that has never come up: the live feed is not
    // running. "Reconnecting" would overstate it — nothing is being re-connected.
    status = "offline"
  }

  return { status, stale, ageMs, alerting: status !== "live" || stale }
}

/** Absolute wall-clock stamp — the thing a cook glances at. Locale-pinned so the
 *  rendered string is identical in the browser, in jsdom and in CI. */
export function formatClock(ts: number): string {
  return new Date(ts).toLocaleTimeString("en-GB", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  })
}

/** Relative age, capped the same way the ticket ages are (backlog #12) so a very
 *  old stamp never renders as raw uncapped minutes. */
export function formatAge(ageMs: number): string {
  const seconds = Math.floor(ageMs / 1000)
  if (seconds < 10) return "just now"
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}
