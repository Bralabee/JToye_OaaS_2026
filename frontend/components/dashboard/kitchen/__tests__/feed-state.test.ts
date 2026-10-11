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

/**
 * 37-17 (D-14, roadmap SC-1): "Live" only when it is true.
 *
 * UI-SPEC K1's precedence, first match wins: Signed out > No access > Offline > Not updating >
 * Reconnecting > Out of date > Sound off > Muted > Live (row 1, "Connecting…", is the pill's
 * presentation-only `pending` and is asserted in kds-feed-status.test.tsx). `pill` is a NEW field:
 * `status` keeps its four values and every assertion above is unchanged.
 */
describe("deriveFeedState: the pill's precedence (D-14)", () => {
  type Over = Partial<Parameters<typeof deriveFeedState>[0]>
  const rows: Array<[string, Over, string]> = [
    ["session lapsed", { signedOut: true }, "signedOut"],
    ["access ended", { noAccess: true }, "noAccess"],
    ["offline", { online: false }, "offline"],
    ["reads failing", { lastSyncFailed: true }, "error"],
    ["reconnecting", { connected: false, reconnecting: true }, "reconnecting"],
    ["socket up but reads stale", { now: NOW + STALE_AFTER_MS + 1000 }, "stale"],
    ["sound not enabled", { soundOff: true }, "soundOff"],
    ["muted", { muted: true }, "muted"],
  ]

  it.each(rows)("%s, alone, yields its own pill state and never 'live'", (_name, over, pill) => {
    const s = deriveFeedState({ ...base, ...over })
    expect(s.pill).toBe(pill)
    expect(s.pill).not.toBe("live")
  })

  it("is 'live' only when every condition above is false", () => {
    expect(deriveFeedState(base).pill).toBe("live")
    expect(
      deriveFeedState({ ...base, signedOut: false, noAccess: false, soundOff: false, muted: false }).pill
    ).toBe("live")
  })

  it("a combination yields the HIGHER-precedence state, for every pair", () => {
    for (let i = 0; i < rows.length; i++) {
      for (let j = i + 1; j < rows.length; j++) {
        const s = deriveFeedState({ ...base, ...rows[j][1], ...rows[i][1] })
        expect([rows[i][0], rows[j][0], s.pill]).toEqual([rows[i][0], rows[j][0], rows[i][2]])
      }
    }
  })

  it("leaves `status`, `stale` and the banner exactly as before: sound and mute raise no feed banner", () => {
    const s = deriveFeedState({ ...base, soundOff: true, muted: true })
    expect(s.status).toBe("live")
    expect(s.stale).toBe(false)
    expect(s.alerting).toBe(false)
  })

  it("'Out of date' uses the existing staleness threshold, boundary closed", () => {
    expect(deriveFeedState({ ...base, now: NOW + STALE_AFTER_MS }).pill).toBe("live")
    expect(deriveFeedState({ ...base, now: NOW + STALE_AFTER_MS + 1 }).pill).toBe("stale")
  })
})
