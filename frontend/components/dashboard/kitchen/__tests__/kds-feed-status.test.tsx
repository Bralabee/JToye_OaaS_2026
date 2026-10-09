/**
 * #106 — the KDS status pill and stale banner, at the render level.
 *
 * The pre-#106 indicator was `<span className="h-2.5 w-2.5 …" />` plus a label with
 * `hidden sm:inline` and a `title=` attribute. The first two tests are written to fail
 * on that shape specifically: a label that is present at every viewport, and a state
 * word that is not carried by colour alone.
 */
import { render, screen, fireEvent, within } from "@testing-library/react"
import { KdsFeedBanner, KdsFeedPill } from "../kds-feed-status"
import { deriveFeedState, STALE_AFTER_MS } from "../feed-state"

const NOW = 1_754_300_000_000
const state = (over: Partial<Parameters<typeof deriveFeedState>[0]> = {}) =>
  deriveFeedState({
    online: true,
    connected: true,
    reconnecting: false,
    lastSyncedAt: NOW,
    lastSyncFailed: false,
    now: NOW,
    ...over,
  })

describe("KdsFeedPill", () => {
  it("shows the state as a WORD, never hidden at any viewport", () => {
    render(<KdsFeedPill state={state()} lastSyncedAt={NOW} />)
    const label = screen.getByText("Live")
    expect(label).toBeInTheDocument()
    // The defect this replaces: `hidden sm:inline` made the word invisible on the
    // handheld the expo actually carries.
    expect(label.className).not.toMatch(/\bhidden\b/)
  })

  it("names each state in text, so colour is never the only channel", () => {
    const cases: [Parameters<typeof state>[0], string][] = [
      [{}, "Live"],
      [{ online: false }, "Offline"],
      [{ connected: false, reconnecting: true }, "Reconnecting"],
      [{ lastSyncFailed: true }, "Not updating"],
    ]
    for (const [over, label] of cases) {
      const { unmount } = render(<KdsFeedPill state={state(over)} lastSyncedAt={NOW} />)
      expect(screen.getByText(label)).toBeInTheDocument()
      unmount()
    }
  })

  it("carries a last-updated wall clock — the stamp the board had none of", () => {
    render(<KdsFeedPill state={state()} lastSyncedAt={NOW} />)
    expect(screen.getByTestId("kds-feed-pill").textContent).toMatch(/\d{2}:\d{2}:\d{2}/)
    expect(screen.getByText(/Last updated/)).toBeInTheDocument()
  })

  it("shows a dash rather than a fake time before the first read", () => {
    render(<KdsFeedPill state={state({ lastSyncedAt: null })} lastSyncedAt={null} />)
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("—")
  })

  // --- #536: the pill's box must not depend on what the pill currently says ---

  it("reserves the widest label and a full clock, so the pill cannot re-wrap the header", () => {
    // Measured on the live stack at 390px BEFORE this: a cold load rendered
    // "Offline —", settled to "Live 14:32:07", the pill widened, the header's
    // flex-wrap control row gained a third line and the whole board moved 46px.
    // feed-state.ts asserted in a comment that the pill "occupies the same box in
    // every state"; it did not, and this is the assertion that makes it true.
    render(<KdsFeedPill state={state()} lastSyncedAt={NOW} />)
    expect(screen.getByText("Live").className).toMatch(/min-w-\[/)
    const clock = screen.getByText(/\d{2}:\d{2}:\d{2}/)
    expect(clock.className).toMatch(/min-w-\[/)
  })

  it("says Connecting… while the page has not started, instead of a red Offline", () => {
    // `deriveFeedState` maps "socket never came up" to offline, which is right once
    // the page is running and wrong while it is starting. Rendering the header from
    // the first paint (what stops it growing 108px mid-load) would otherwise show a
    // red Offline on every cold load — the cry-wolf failure #535 removed from the
    // banner. The derived state is NOT changed: only this presentation is.
    const cold = state({ connected: false, lastSyncedAt: null })
    expect(cold.status).toBe("offline")
    render(<KdsFeedPill state={cold} lastSyncedAt={null} pending />)
    const pill = screen.getByTestId("kds-feed-pill-pending")
    expect(pill).toHaveTextContent("Connecting")
    expect(pill).not.toHaveTextContent("Offline")
    // The settled testid must NOT be present — #556's rule, and the reason
    // e2e/kitchen-flow.spec.ts can use a strict locator for it.
    expect(screen.queryByTestId("kds-feed-pill")).not.toBeInTheDocument()
  })

  it("reverts to the honest derived status the moment pending ends", () => {
    // The control arm. `pending` is bounded by the shop-list fetch; if it ever
    // masked a genuinely dead socket this test would fail.
    const cold = state({ connected: false, lastSyncedAt: null })
    render(<KdsFeedPill state={cold} lastSyncedAt={null} pending={false} />)
    const pill = screen.getByTestId("kds-feed-pill")
    expect(pill).toHaveTextContent("Offline")
    expect(pill).not.toHaveTextContent("Connecting")
  })
})

describe("KdsFeedBanner", () => {
  const noop = () => {}

  it("stays silent while the feed is live and fresh", () => {
    render(
      <KdsFeedBanner state={state()} lastSyncedAt={NOW} onRefresh={noop} refreshing={false} />
    )
    expect(screen.queryByTestId("kds-feed-banner")).not.toBeInTheDocument()
  })

  it("announces an offline feed as an alert, with the age of the data", () => {
    render(
      <KdsFeedBanner
        state={state({ online: false, now: NOW + 125_000 })}
        lastSyncedAt={NOW}
        onRefresh={noop}
        refreshing={false}
      />
    )
    const banner = screen.getByTestId("kds-feed-banner")
    expect(banner).toHaveAttribute("role", "alert")
    expect(banner).toHaveTextContent("Offline")
    expect(screen.getByTestId("kds-stale-age")).toHaveTextContent("(2m ago)")
  })

  it("says the board is merely OUT OF DATE when the socket is genuinely still up", () => {
    // A connected socket may well still deliver, so this must not claim new orders
    // will be missed — it says only what is known.
    render(
      <KdsFeedBanner
        state={state({ now: NOW + STALE_AFTER_MS + 1000 })}
        lastSyncedAt={NOW}
        onRefresh={noop}
        refreshing={false}
      />
    )
    const banner = screen.getByTestId("kds-feed-banner")
    expect(banner).toHaveTextContent("These tickets may be out of date")
    expect(banner).not.toHaveTextContent("will not appear")
  })

  it("warns that orders WILL be missed while the connection is down", () => {
    render(
      <KdsFeedBanner
        state={state({ online: false })}
        lastSyncedAt={NOW}
        onRefresh={noop}
        refreshing={false}
      />
    )
    expect(screen.getByTestId("kds-feed-banner")).toHaveTextContent(
      "New orders will not appear until the connection is back"
    )
  })

  it("offers a refresh and calls it", () => {
    const onRefresh = jest.fn()
    render(
      <KdsFeedBanner
        state={state({ lastSyncFailed: true })}
        lastSyncedAt={NOW}
        onRefresh={onRefresh}
        refreshing={false}
      />
    )
    fireEvent.click(screen.getByRole("button", { name: /refresh now/i }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it("disables the refresh while one is in flight, so a jammed board is not stampeded", () => {
    render(
      <KdsFeedBanner
        state={state({ lastSyncFailed: true })}
        lastSyncedAt={NOW}
        onRefresh={noop}
        refreshing
      />
    )
    expect(screen.getByRole("button", { name: /refreshing/i })).toBeDisabled()
  })

  it("says so plainly when the FIRST read failed and nothing has loaded", () => {
    render(
      <KdsFeedBanner
        state={state({ online: false, lastSyncedAt: null, lastSyncFailed: true })}
        lastSyncedAt={null}
        onRefresh={noop}
        refreshing={false}
      />
    )
    expect(screen.getByTestId("kds-feed-banner")).toHaveTextContent("No orders have loaded yet")
  })

  it("stays silent during a cold load — the socket simply has not connected YET", () => {
    // The regression this guards: raising the banner on `!connected` alone made it
    // flash on every page load and then vanish, which is both a cry-wolf warning and
    // a measured CLS breach (0.2408 -> 0.7321 at the declared throttle profile).
    render(
      <KdsFeedBanner
        state={state({ connected: false, reconnecting: false, lastSyncedAt: null })}
        lastSyncedAt={null}
        onRefresh={noop}
        refreshing={false}
      />
    )
    expect(screen.queryByTestId("kds-feed-banner")).not.toBeInTheDocument()
  })
})

/**
 * 37-17 (D-14, roadmap SC-1, F3): the pill says "Live" only when it is true. The table is the
 * UI-SPEC's, written out here rather than imported, so a reordered implementation cannot also
 * reorder the expectation.
 */
describe("KdsFeedPill: the ten-row precedence table (F3)", () => {
  type Over = Partial<Parameters<typeof state>[0]>
  const TABLE: Array<{ n: number; name: string; over: Over; pending?: boolean; label: string; icon: string; tone: RegExp }> = [
    { n: 1, name: "shop list in flight", over: { connected: false, lastSyncedAt: null }, pending: true, label: "Connecting…", icon: "lucide-refresh-cw", tone: /text-slate-700/ },
    { n: 2, name: "session lapsed", over: { signedOut: true }, label: "Signed out", icon: "lucide-log-out", tone: /text-red-800/ },
    { n: 3, name: "access ended", over: { noAccess: true }, label: "No access", icon: "lucide-shield-off", tone: /text-red-800/ },
    { n: 4, name: "offline", over: { online: false }, label: "Offline", icon: "lucide-wifi-off", tone: /text-red-800/ },
    { n: 5, name: "reads failing", over: { lastSyncFailed: true }, label: "Not updating", icon: "lucide-triangle-alert", tone: /text-red-800/ },
    { n: 6, name: "reconnecting", over: { connected: false, reconnecting: true }, label: "Reconnecting", icon: "lucide-refresh-cw", tone: /text-amber-800/ },
    { n: 7, name: "socket up, reads stale", over: { now: NOW + STALE_AFTER_MS + 1000 }, label: "Out of date", icon: "lucide-clock", tone: /text-amber-800/ },
    { n: 8, name: "sound not enabled", over: { soundOff: true }, label: "Sound off", icon: "lucide-volume-x", tone: /text-amber-800/ },
    { n: 9, name: "muted", over: { muted: true }, label: "Muted", icon: "lucide-bell-off", tone: /text-amber-800/ },
    { n: 10, name: "everything above false", over: {}, label: "Live", icon: "lucide-wifi", tone: /text-emerald-800/ },
  ]
  const ALL_LABELS = TABLE.map((r) => r.label)
  const pillOf = () => screen.queryByTestId("kds-feed-pill") ?? screen.getByTestId("kds-feed-pill-pending")

  it.each(TABLE)("row $n ($name), alone, reads '$label' with its icon and tone", (row) => {
    render(<KdsFeedPill state={state(row.over)} lastSyncedAt={NOW} pending={row.pending ?? false} />)
    const pill = pillOf()
    const label = within(pill).getByText(row.label)
    expect(label.className).toMatch(row.tone)
    expect(pill.querySelector(`svg.${row.icon}`)).not.toBeNull()
    for (const other of ALL_LABELS.filter((l) => l !== row.label)) {
      expect(within(pill).queryByText(other)).toBeNull()
    }
  })

  it.each(TABLE.filter((r) => r.n >= 2 && r.n <= 9))(
    "row $n ($name) never reads 'Live'",
    (row) => {
      render(<KdsFeedPill state={state(row.over)} lastSyncedAt={NOW} />)
      expect(pillOf()).not.toHaveTextContent("Live")
    }
  )

  it("combined conditions read the higher-precedence label", () => {
    const rows = TABLE.filter((r) => r.n >= 2 && r.n <= 9)
    for (let i = 0; i < rows.length; i++) {
      for (let j = i + 1; j < rows.length; j++) {
        const { unmount } = render(
          <KdsFeedPill state={state({ ...rows[j].over, ...rows[i].over })} lastSyncedAt={NOW} />
        )
        expect([rows[i].n, rows[j].n, pillOf().textContent]).toEqual([
          rows[i].n,
          rows[j].n,
          expect.stringContaining(rows[i].label),
        ])
        unmount()
      }
    }
  })

  it("'Connecting…' wins over every other row while pending", () => {
    render(
      <KdsFeedPill state={state({ signedOut: true, online: false, muted: true })} lastSyncedAt={NOW} pending />
    )
    expect(pillOf()).toHaveTextContent("Connecting…")
    expect(pillOf()).not.toHaveTextContent("Signed out")
  })

  it("reserves the same width in every state: every label sizes the label slot, whatever it says", () => {
    // jsdom has no layout, so the constant width is asserted as its cause: the slot stacks an
    // invisible sizer for EVERY label in one grid cell, so its width is the widest label's in the
    // real font, in every state. Sizers are pseudo-element content, so they add no text.
    for (const row of TABLE) {
      const { unmount } = render(
        <KdsFeedPill state={state(row.over)} lastSyncedAt={NOW} pending={row.pending ?? false} />
      )
      const sizers = Array.from(pillOf().querySelectorAll("[data-pill-sizer]"))
      expect(sizers.map((s) => s.getAttribute("data-pill-sizer")).sort()).toEqual([...ALL_LABELS].sort())
      for (const s of sizers) {
        expect(s.textContent).toBe("")
        expect(s.getAttribute("aria-hidden")).toBe("true")
        expect(s.className).toMatch(/\binvisible\b/)
        expect(s.className).toMatch(/after:content-\[attr\(data-pill-sizer\)\]/)
        expect(s.className).toMatch(/\bcol-start-1\b/)
        expect(s.className).toMatch(/\brow-start-1\b/)
      }
      const visible = within(pillOf()).getByText(row.label)
      expect(visible.className).toMatch(/\bcol-start-1\b/)
      expect(visible.className).toMatch(/\brow-start-1\b/)
      unmount()
    }
  })
})
