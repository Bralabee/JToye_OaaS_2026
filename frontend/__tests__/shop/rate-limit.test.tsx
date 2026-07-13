/**
 * Component tests for the F-RATE fix (#88): a public-API HTTP 429 must render a
 * transient "busy / retrying" state on both storefront surfaces — NEVER the
 * authoritative empty state ("No shops found" / "Shop not found"). A genuine
 * 200-with-empty-content must still render the real empty state, proving the UI
 * distinguishes "rate limited" from "actually empty".
 *
 * Fake timers drive the bounded auto-retry: we render under a 429, assert the
 * busy copy, then flip the mock to a real empty response and advance the retry
 * timer to prove the empty state only appears for a genuine empty 200.
 */

import { Suspense } from "react"
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

import ShopDiscoveryPage from "@/app/shop/page"
import ShopDetailPage from "@/app/shop/[slug]/page"

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
  it("shows a busy/retrying state on 429 instead of 'No shops found'", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(<ShopDiscoveryPage />)
    })
    await flush()

    expect(screen.getByText(/retrying/i)).toBeInTheDocument()
    expect(screen.queryByText("No shops found")).not.toBeInTheDocument()
  })

  it("renders the genuine empty state once a real empty 200 arrives (429 != empty)", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(<ShopDiscoveryPage />)
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
    expect(screen.getByText("No shops found")).toBeInTheDocument()
  })
})

describe("/shop/[slug] detail — 429 handling", () => {
  it("shows a busy/retrying state on 429 instead of 'Shop not found'", async () => {
    mockGet.mockRejectedValue(rateLimited429)

    await act(async () => {
      render(
        <Suspense fallback={<div>loading</div>}>
          <ShopDetailPage params={Promise.resolve({ slug: "x" })} />
        </Suspense>
      )
    })
    await flush()

    expect(screen.getByText(/retrying/i)).toBeInTheDocument()
    expect(screen.queryByText("Shop not found")).not.toBeInTheDocument()
  })
})
