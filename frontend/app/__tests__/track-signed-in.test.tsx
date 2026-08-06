/**
 * Issue #458 (items 2, 4) — /track RENDERED for a signed-in customer.
 *
 * The auto-population added by PR #508 is currently proven only through the pure
 * `pickTrackedOrder` selection rule (app/__tests__/track-pick-order.test.ts). A
 * pure function cannot tell you whether the page actually opens on the order or
 * still shows a form, and it cannot see the case the report is really about:
 * a signed-in customer who has NO orders. Today they are handed the guest form
 * and asked to type "ORD-XXXXXXXX-XXXXXXXX-XXXXXXXX" — an order reference that,
 * by definition, does not exist. That is the "should only be present when
 * there's been an order" half of the report, and it survived #508.
 *
 * Three arms, deliberately:
 *   - HAS an order   -> opens on it, no order-number input demanded  (item 4)
 *   - has NO order   -> says so, does not demand a reference          (item 2)
 *   - GUEST          -> form unchanged, email still mandatory         (regression guard)
 *
 * The guest arm is the control that stops this becoming a security regression:
 * the public endpoint takes the email as proof of ownership (AUDIT-W0-02), so a
 * change that quietly dropped the email challenge would "pass" any test that
 * only looked at the signed-in path.
 */
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import { useSearchParams } from "next/navigation"
import TrackOrderPage from "@/app/track/page"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSearchParams = useSearchParams as jest.Mock

const SESSION = {
  profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
  expiresAt: Math.floor(Date.now() / 1000) + 300,
}

const ORDER = {
  orderNumber: "ORD-AAAA1111",
  status: "PREPARING",
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  createdAt: "2026-08-01T10:00:00Z",
  updatedAt: "2026-08-01T10:05:00Z",
}

/** `content` is what /api/customer-orders returns for THIS caller's own orders. */
function mockOwnOrders(content: unknown[]) {
  global.fetch = jest.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({ content }),
  }) as unknown as typeof fetch
}

beforeEach(() => {
  mockGet.mockReset()
  mockGetSession.mockReset()
  mockedSearchParams.mockReturnValue({ get: () => null })
  global.fetch = jest.fn().mockResolvedValue({
    ok: false,
    status: 401,
    json: async () => ({}),
  }) as unknown as typeof fetch
})

describe("/track for a signed-in customer WITH an order (#458 item 4)", () => {
  it("opens on their order without asking for an order number", async () => {
    mockGetSession.mockResolvedValue(SESSION)
    mockOwnOrders([ORDER])

    render(<TrackOrderPage />)

    expect(await screen.findByText("Ada's Kitchen")).toBeInTheDocument()
    expect(screen.getByText(ORDER.orderNumber)).toBeInTheDocument()

    // The form is still in the DOM (one tap away) but must not be presented.
    const orderNumberInput = screen.getByLabelText(/order number/i)
    expect(orderNumberInput.closest("form")).toHaveAttribute("hidden")
    expect(
      screen.getByRole("button", { name: /track a different order/i })
    ).toBeInTheDocument()
  })

  it("never fetches an order by number on the auto path — it matches its own list", async () => {
    mockGetSession.mockResolvedValue(SESSION)
    mockOwnOrders([ORDER])

    render(<TrackOrderPage />)
    await screen.findByText("Ada's Kitchen")

    // publicApiClient is the by-number guest endpoint. The auto path must not use
    // it, or a `?order=` deep link would become a lookup for somebody else's order.
    expect(mockGet).not.toHaveBeenCalled()
  })
})

describe("/track for a signed-in customer with NO orders (#458 item 2)", () => {
  beforeEach(() => {
    mockGetSession.mockResolvedValue(SESSION)
    mockOwnOrders([])
  })

  it("says they have no orders instead of demanding an order reference", async () => {
    render(<TrackOrderPage />)

    expect(await screen.findByTestId("track-no-orders")).toBeInTheDocument()

    // The defect: the order-number field presented to someone who cannot have one.
    const orderNumberInput = screen.getByLabelText(/order number/i)
    expect(orderNumberInput.closest("form")).toHaveAttribute("hidden")
  })

  it("sends them somewhere useful — shops, and their (empty) order history", async () => {
    render(<TrackOrderPage />)
    const card = await screen.findByTestId("track-no-orders")

    // Scoped to the card: the page renders inside PublicShell, whose footer also
    // links to /shop, and an unscoped query would match that instead — passing
    // even if this card had no call to action at all.
    expect(within(card).getByRole("link", { name: /browse shops/i })).toHaveAttribute(
      "href",
      "/shop"
    )
    expect(screen.getByRole("link", { name: /all my orders/i })).toHaveAttribute(
      "href",
      "/shop/orders"
    )
  })

  it("still lets them chase a guest order placed on another email — one tap, not a dead end", async () => {
    render(<TrackOrderPage />)
    await screen.findByTestId("track-no-orders")

    fireEvent.click(screen.getByTestId("track-show-manual-form"))

    const orderNumberInput = screen.getByLabelText(/order number/i)
    expect(orderNumberInput.closest("form")).not.toHaveAttribute("hidden")
    // ...and the empty-state card gives way rather than stacking above it.
    expect(screen.queryByTestId("track-no-orders")).not.toBeInTheDocument()
  })
})

/**
 * Added after a break arm went GREEN. Setting `noOrdersOfTheirOwn` on the
 * !res.ok path as well as the empty-list one — the #467 defect, "we could not
 * ask" rendered as "you have none" — did not fail a single test in the suite
 * above. The code comment claimed the error paths were safe and nothing checked
 * it, which is precisely the unfalsifiable-criterion shape.
 */
describe("/track when the orders request FAILS (#467 shape)", () => {
  it("does not tell a signed-in customer they have no orders", async () => {
    mockGetSession.mockResolvedValue(SESSION)
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({}),
    }) as unknown as typeof fetch

    render(<TrackOrderPage />)

    await waitFor(() => expect(global.fetch).toHaveBeenCalled())
    // A truthful fallback: the lookup form, which can still get them an answer.
    // Never the confident "you haven't placed an order yet".
    expect(screen.queryByTestId("track-no-orders")).not.toBeInTheDocument()
    expect(screen.getByLabelText(/order number/i).closest("form")).not.toHaveAttribute("hidden")
  })

  it("does not tell them that when the network throws either", async () => {
    mockGetSession.mockResolvedValue(SESSION)
    global.fetch = jest.fn().mockRejectedValue(new Error("offline")) as unknown as typeof fetch

    render(<TrackOrderPage />)

    await waitFor(() => expect(global.fetch).toHaveBeenCalled())
    expect(screen.queryByTestId("track-no-orders")).not.toBeInTheDocument()
    expect(screen.getByLabelText(/order number/i).closest("form")).not.toHaveAttribute("hidden")
  })
})

describe("/track CONTROL — the guest path is untouched", () => {
  it("shows the form and still demands BOTH order number and email", async () => {
    mockGetSession.mockResolvedValue(null)

    render(<TrackOrderPage />)

    const orderNumberInput = await screen.findByLabelText(/order number/i)
    const emailInput = screen.getByLabelText(/email/i)

    expect(orderNumberInput.closest("form")).not.toHaveAttribute("hidden")
    // Proof of ownership, AUDIT-W0-02. Both are `required`; neither is pre-filled.
    expect(orderNumberInput).toBeRequired()
    expect(emailInput).toBeRequired()
    expect(emailInput).toHaveValue("")
    expect(screen.queryByTestId("track-no-orders")).not.toBeInTheDocument()
  })

  it("does not call the session-authenticated orders proxy for a guest", async () => {
    mockGetSession.mockResolvedValue(null)
    const fetchSpy = global.fetch as jest.Mock

    render(<TrackOrderPage />)
    await screen.findByLabelText(/order number/i)

    await waitFor(() => expect(mockGetSession).toHaveBeenCalled())
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
