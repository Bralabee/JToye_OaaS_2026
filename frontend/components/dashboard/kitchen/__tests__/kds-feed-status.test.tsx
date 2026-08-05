/**
 * #106 — the KDS status pill and stale banner, at the render level.
 *
 * The pre-#106 indicator was `<span className="h-2.5 w-2.5 …" />` plus a label with
 * `hidden sm:inline` and a `title=` attribute. The first two tests are written to fail
 * on that shape specifically: a label that is present at every viewport, and a state
 * word that is not carried by colour alone.
 */
import { render, screen, fireEvent } from "@testing-library/react"
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
