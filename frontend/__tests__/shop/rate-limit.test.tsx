/**
 * Component tests for the F-RATE fix (#88): a public-API HTTP 429 must render a
 * transient "busy / retrying" state on both storefront surfaces — NEVER the
 * authoritative empty state ("No kitchens found" / "Shop not found"). A genuine
 * 200-with-empty-content must still render the real empty state, proving the UI
 * distinguishes "rate limited" from "actually empty".
 *
 * Fake timers drive the bounded auto-retry: we render under a 429, assert the
 * busy copy, then flip the mock to a real empty response and advance the retry
 * timer to prove the empty state only appears for a genuine empty 200.
 *
 * WHAT #507 CHANGED HERE, AND WHY THE CONTRACT IS UNCHANGED. These blocks used
 * to render `app/shop/page.tsx` and `app/shop/[slug]/page.tsx` directly. Both
 * are now async SERVER components that `fetch` from the core API, which jsdom
 * cannot execute — so they render the client islands instead, with
 * `initial={null}`.
 *
 * `initial={null}` is not a test convenience: it is exactly the production state
 * this suite is about. When the server's own call is rate-limited it deliberately
 * does NOT guess an answer — it defers, passes no seed, and the island runs the
 * very retry-and-backoff path asserted below. So the same behaviour is under
 * test, one component down, on the path a 429 actually takes.
 *
 * The server half of the same rule (429 -> defer, and specifically NOT
 * "notfound") is covered separately in lib/__tests__/storefront-server.test.ts,
 * so neither half can regress unnoticed.
 */

import { act, render, screen } from "@testing-library/react"
import { BASE_DELAY_MS } from "@/lib/public-fetch-retry"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

// Defensive: the shop-detail tree reads useCart deep in its subtree. The busy /
// not-found branches return before that subtree mounts, but mock it anyway so a
// wiring change can never make these tests fail for the wrong reason.
jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: "x",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"

const rateLimited429 = { response: { status: 429, headers: {} } }

beforeEach(() => {
  jest.useFakeTimers()
  mockGet.mockReset()
})

afterEach(() => {
  act(() => {
    jest.runOnlyPendingTimers()
  })
  jest.useRealTimers()
})

// Flush the pending promise microtasks queued by an awaited axios call.
async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe("/shop list — 429 handling", () => {
  it("shows a busy/retrying state on 429 instead of 'No kitchens found'", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(<ShopDiscoveryClient initial={null} initialQuery="" />)
    })
    await flush()

    expect(screen.getByText(/retrying/i)).toBeInTheDocument()
    expect(screen.queryByText("No kitchens found")).not.toBeInTheDocument()
  })

  it("renders the genuine empty state once a real empty 200 arrives (429 != empty)", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(<ShopDiscoveryClient initial={null} initialQuery="" />)
    })
    await flush()
    expect(screen.getByText(/retrying/i)).toBeInTheDocument()

    // Recovery: the limiter releases and the marketplace is genuinely empty.
    mockGet.mockResolvedValue({ data: { content: [], totalPages: 0 } })
    await act(async () => {
      await jest.advanceTimersByTimeAsync(BASE_DELAY_MS)
    })
    await flush()

    expect(screen.queryByText(/retrying/i)).not.toBeInTheDocument()
    expect(screen.getByText("No kitchens found")).toBeInTheDocument()
  })
})

describe("/shop/[slug] detail — 429 handling", () => {
  it("shows a busy/retrying state on 429 instead of 'Shop not found'", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(<ShopDetailClient slug="x" initial={null} />)
    })
    await flush()

    expect(screen.getByText(/retrying/i)).toBeInTheDocument()
    expect(screen.queryByText("Shop not found")).not.toBeInTheDocument()
  })
})
