/**
 * #106 — the KDS feed-liveness derivation.
 *
 * Written to FAIL on the pre-#106 behaviour. The board's only liveness signal was
 * `connected` from useStomp, and it was measured in a real browser reporting
 * "Connected" with a green dot after twelve seconds fully offline. Every test below
 * that asserts a non-live status with `connected: true` is a direct expression of
 * that: pass `{ connected: true }` alone to a socket-only implementation and it says
 * "live" for all of them.
 */
import {
  deriveFeedState,
  formatAge,
  formatClock,
  STALE_AFTER_MS,
  KITCHEN_POLL_INTERVAL_MS,
} from "../feed-state"

const NOW = 1_754_300_000_000
const base = {
  online: true,
  connected: true,
  reconnecting: false,
  lastSyncedAt: NOW,
  lastSyncFailed: false,
  now: NOW,
}

describe("deriveFeedState", () => {
  it("is live and silent when online, connected and freshly synced", () => {
    const s = deriveFeedState(base)
    expect(s.status).toBe("live")
    expect(s.stale).toBe(false)
    expect(s.alerting).toBe(false)
  })

  it("reports OFFLINE from navigator.onLine even while the socket still claims connected", () => {
    // The exact measured lie: socket says connected, machine is offline.
    const s = deriveFeedState({ ...base, online: false })
    expect(s.status).toBe("offline")
    expect(s.alerting).toBe(true)
  })

  it("reports an error when the last read threw, even while connected", () => {
    const s = deriveFeedState({ ...base, lastSyncFailed: true })
    expect(s.status).toBe("error")
    expect(s.alerting).toBe(true)
  })

  it("distinguishes a socket that dropped (reconnecting) from one that never came up", () => {
    expect(
      deriveFeedState({ ...base, connected: false, reconnecting: true }).status
    ).toBe("reconnecting")
    expect(
      deriveFeedState({ ...base, connected: false, reconnecting: false }).status
    ).toBe("offline")
  })

  it("goes stale — and alerts — once data ages past the threshold, while still 'live'", () => {
    // The background-throttled-tab case: the socket is genuinely up, but reads have
    // stopped landing. Status stays honest ("live"); the board still warns.
    const s = deriveFeedState({ ...base, now: NOW + STALE_AFTER_MS + 1000 })
    expect(s.status).toBe("live")
    expect(s.stale).toBe(true)
    expect(s.alerting).toBe(true)
  })

  it("is NOT stale one millisecond before the threshold (the boundary is closed)", () => {
    const s = deriveFeedState({ ...base, now: NOW + STALE_AFTER_MS })
    expect(s.stale).toBe(false)
    expect(s.alerting).toBe(false)
  })

  it("never claims staleness before the first successful read", () => {
    // Otherwise every cold load would raise a banner, and a banner that cries wolf on
    // load is a banner the kitchen learns to ignore.
    const s = deriveFeedState({ ...base, lastSyncedAt: null, now: NOW + 10 * 60_000 })
    expect(s.ageMs).toBeNull()
    expect(s.stale).toBe(false)
    expect(s.alerting).toBe(false)
  })

  it("does not ALERT during a cold load, while still naming the status honestly", () => {
    // Measured regression guard. Between first paint and the first read the socket is
    // not up yet, so the naive `status !== "live"` rule raised the banner on every
    // load and dropped it a second later — /dashboard/kitchen went from CLS 0.2408 to
    // 0.7321 at the declared throttle profile because the banner pushed the ticket
    // grid down and then let it snap back.
    const s = deriveFeedState({
      ...base,
      connected: false,
      reconnecting: false,
      lastSyncedAt: null,
    })
    expect(s.status).toBe("offline") // the pill still tells the truth
    expect(s.alerting).toBe(false) // the space-taking banner does not appear
  })

  it("DOES alert on a cold load whose first read failed", () => {
    // The other half of the pair: a failed read is a fact about this load, not the
    // absence of one. Without this the suppression above would swallow a real failure.
    const s = deriveFeedState({ ...base, lastSyncedAt: null, lastSyncFailed: true })
    expect(s.status).toBe("error")
    expect(s.alerting).toBe(true)
  })

  it("clamps a clock that ran backwards to an age of zero rather than a negative", () => {
    const s = deriveFeedState({ ...base, now: NOW - 5000 })
    expect(s.ageMs).toBe(0)
  })

  it("leaves room for at least one poll before declaring staleness", () => {
    // A threshold below the poll interval would flash the banner between every
    // successful poll — the fastest way to train an operator to ignore it.
    expect(STALE_AFTER_MS).toBeGreaterThan(KITCHEN_POLL_INTERVAL_MS)
  })
})

describe("formatClock", () => {
  it("renders a 24-hour wall clock, zero-padded", () => {
    expect(formatClock(Date.UTC(2026, 7, 4, 9, 5, 3))).toMatch(/^\d{2}:\d{2}:\d{2}$/)
  })
})

describe("formatAge", () => {
  it.each([
    [0, "just now"],
    [9_000, "just now"],
    [10_000, "10s ago"],
    [59_000, "59s ago"],
    [60_000, "1m ago"],
    [59 * 60_000, "59m ago"],
    [60 * 60_000, "1h ago"],
    [23 * 3_600_000, "23h ago"],
    [24 * 3_600_000, "1d ago"],
  ])("formats %ims as %s", (ms, expected) => {
    expect(formatAge(ms)).toBe(expected)
  })

  it("never renders raw uncapped minutes for a very old stamp (backlog #12's shape)", () => {
    expect(formatAge(2245 * 60_000)).toBe("1d ago")
    expect(formatAge(2245 * 60_000)).not.toMatch(/2245/)
  })
})
