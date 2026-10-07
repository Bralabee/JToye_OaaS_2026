/**
 * Tests for the guest order-tracking page (UIX-01, backlog #9, Surface H).
 *
 * Contract:
 *  - `/track` is a GUEST lookup — two inputs (order number + email), NO forced
 *    sign-in wall (RequireCustomerAuth absent).
 *  - Submitting order# + email calls the IDOR-hardened guest endpoint
 *    GET /public/orders/{orderNumber}?email= and renders the progress stepper.
 *  - A customer session pre-fills the email but never requires it.
 *  - Not-found renders the exact copywriting-contract message.
 *  - The page is wrapped in the shared PublicShell.
 *  - The order-confirmation page links to /track ("Track this order").
 */

import fs from "fs"
import path from "path"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useSearchParams } from "next/navigation"
import TrackOrderPage from "@/app/track/page"
import {
  RECORDED_ACK_NOT_RECORDED_COPY,
  RECORDED_SET_NOT_LOADED_COPY,
  RECORDED_SET_SHOW_COPY,
  VENDOR_PLACED_COPY,
  ALLERGY_NOTE_UNREAD_COPY,
} from "@/components/storefront/recorded-allergen-set"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
}))

const activeOrder = {
  orderNumber: "ORD-12345678",
  status: "PREPARING",
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  createdAt: "2026-07-11T10:00:00Z",
  updatedAt: "2026-07-11T10:05:00Z",
}

describe("Track page (guest lookup, Surface H)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockGetSession.mockReset()
    mockGetSession.mockResolvedValue(null)
  })

  it("renders two inputs (order number + email) with no sign-in wall", async () => {
    render(<TrackOrderPage />)
    expect(await screen.findByLabelText(/order number/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
    // The guest page must NOT present a RequireCustomerAuth sign-in wall.
    expect(
      screen.queryByRole("button", { name: /^sign in$/i })
    ).not.toBeInTheDocument()
  })

  it("submits order# + email to the IDOR-hardened guest endpoint and renders the stepper", async () => {
    mockGet.mockResolvedValue({ data: activeOrder })
    render(<TrackOrderPage />)

    fireEvent.change(await screen.findByLabelText(/order number/i), {
      target: { value: "ORD-12345678" },
    })
    fireEvent.change(screen.getByLabelText(/email/i), {
      target: { value: "guest@example.com" },
    })
    fireEvent.click(screen.getByRole("button", { name: /track order/i }))

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith("/public/orders/ORD-12345678", {
        params: { email: "guest@example.com" },
      })
    )
    // Stepper renders: "Received" is unique to the progress tracker.
    expect(await screen.findByText("Received")).toBeInTheDocument()
    expect(screen.getByText("Ada's Kitchen")).toBeInTheDocument()
  })

  it("pre-fills the email from a customer session but never requires it", async () => {
    mockGetSession.mockResolvedValue({ profile: { email: "member@example.com" } })
    render(<TrackOrderPage />)

    const emailInput = (await screen.findByLabelText(/email/i)) as HTMLInputElement
    await waitFor(() => expect(emailInput.value).toBe("member@example.com"))
    // Editable, not gated behind auth.
    expect(emailInput).not.toBeDisabled()
  })

  it("shows the copywriting-contract not-found message", async () => {
    mockGet.mockRejectedValue(new Error("404"))
    render(<TrackOrderPage />)

    fireEvent.change(await screen.findByLabelText(/order number/i), {
      target: { value: "ORD-nope" },
    })
    fireEvent.change(screen.getByLabelText(/email/i), {
      target: { value: "guest@example.com" },
    })
    fireEvent.click(screen.getByRole("button", { name: /track order/i }))

    expect(
      await screen.findByText(
        "Order not found. Check your order number and email address."
      )
    ).toBeInTheDocument()
  })

  it("has no auth wall and is wrapped in PublicShell (structural)", () => {
    const src = fs.readFileSync(
      path.join(process.cwd(), "app/track/page.tsx"),
      "utf8"
    )
    expect(src).not.toMatch(/RequireCustomerAuth/)
    expect(src).toMatch(/PublicShell/)
    expect(src).toMatch(/\/public\/orders\//)
  })

  it("is reachable from the order-confirmation page (Track this order affordance)", () => {
    const src = fs.readFileSync(
      path.join(
        process.cwd(),
        "app/shop/[slug]/orders/[orderNumber]/page.tsx"
      ),
      "utf8"
    )
    expect(src).toMatch(/\/track/)
  })
})

/*
 * 31.1-18 (#785, #812; D-07, D-08, D-15): /track shows the order's allergen record from the
 * tracking response, through the shared RecordedAllergenSet. The guest path is unchanged: the
 * record arrives only through the number + email lookup asserted above.
 */
const ALLERGEN_RECORD = {
  placedVia: "STOREFRONT",
  acknowledgedAllergenMask: 64,
  acknowledgedAllergens: ["Milk"],
  recordedAllergens: ["Milk"],
  recordedAllergenFlags: [],
  allergyNoteProvided: true,
  allergyNoteAcknowledgedAt: "2026-07-11T17:05:00Z",
}

async function guestLookup(response: Record<string, unknown>) {
  mockGet.mockResolvedValue({ data: { ...activeOrder, ...response } })
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: "ORD-12345678" } })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: "guest@example.com" } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
  return screen.findByTestId("recorded-allergen-set")
}

describe("Track page — the order's allergen record (31.1-18)", () => {
  const mockedSearchParams = useSearchParams as jest.Mock

  beforeEach(() => {
    mockGet.mockReset()
    mockGetSession.mockReset()
    mockGetSession.mockResolvedValue(null)
    mockedSearchParams.mockReturnValue({ get: () => null })
    sessionStorage.clear()
  })

  it("shows the acknowledged and recorded sets from the tracking response", async () => {
    const block = await guestLookup(ALLERGEN_RECORD)
    expect(block).toHaveTextContent("You confirmed you had read: Milk")
    expect(block).toHaveTextContent("Recorded on your order: Milk")
  })

  it("says the allergy note was sent to the shop and when the shop read it — never the note text", async () => {
    await guestLookup(ALLERGEN_RECORD)
    const note = screen.getByTestId("allergy-note-status")
    expect(note).toHaveTextContent("Your allergy note was sent to Ada's Kitchen")
    expect(note).toHaveTextContent(/Read by the shop at 18:05/)
  })

  it("an unread note says so", async () => {
    await guestLookup({ ...ALLERGEN_RECORD, allergyNoteAcknowledgedAt: null })
    expect(screen.getByTestId("allergy-note-status")).toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
  })

  it("a vendor-placed order and a pre-acknowledgement order say two different things", async () => {
    await guestLookup({ ...ALLERGEN_RECORD, placedVia: "VENDOR", acknowledgedAllergenMask: null, acknowledgedAllergens: null })
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(VENDOR_PLACED_COPY)
  })

  it("a pre-acknowledgement order says NOT RECORDED", async () => {
    await guestLookup({ ...ALLERGEN_RECORD, placedVia: null, acknowledgedAllergenMask: null, acknowledgedAllergens: null })
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(screen.getByTestId("recorded-ack")).not.toHaveTextContent(VENDOR_PLACED_COPY)
  })

  describe("signed in: the order comes from the history LIST, which never carries the recorded set", () => {
    const SESSION = { profile: { email: "member@example.com" } }
    const LIST_ENTRY = { ...activeOrder, ...ALLERGEN_RECORD, recordedAllergens: null, recordedAllergenFlags: null }

    function mockOwnOrders(content: unknown[]) {
      global.fetch = jest.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ content }),
      }) as unknown as typeof fetch
    }

    it("says the recorded set is NOT LOADED — never 'not recorded' — and loads it only when asked", async () => {
      mockGetSession.mockResolvedValue(SESSION)
      mockOwnOrders([LIST_ENTRY])
      render(<TrackOrderPage />)

      const set = await screen.findByTestId("recorded-set")
      expect(set).toHaveTextContent(RECORDED_SET_NOT_LOADED_COPY)
      expect(set).not.toHaveTextContent(/not recorded/i)
      expect(screen.getByTestId("recorded-ack")).toHaveTextContent("You confirmed you had read: Milk")
      // The auto path still never looks an order up by number on its own (#458 guard).
      expect(mockGet).not.toHaveBeenCalled()

      mockGet.mockResolvedValue({ data: { ...activeOrder, ...ALLERGEN_RECORD } })
      fireEvent.click(screen.getByRole("button", { name: RECORDED_SET_SHOW_COPY }))

      await waitFor(() => expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk"))
      // Through the number + email endpoint, with the signed-in customer's own email.
      expect(mockGet).toHaveBeenCalledWith("/public/orders/ORD-12345678", {
        params: { email: "member@example.com" },
      })
    })

    it("arriving from My Orders, the full record looked up by number is not replaced by the list entry", async () => {
      mockedSearchParams.mockReturnValue({ get: (k: string) => (k === "order" ? "ORD-12345678" : null) })
      sessionStorage.setItem("jtoye-track-email", "member@example.com")
      mockGet.mockResolvedValue({ data: { ...activeOrder, ...ALLERGEN_RECORD } })
      // The session (and so the list) resolves AFTER the by-number lookup.
      let releaseSession: (v: unknown) => void = () => {}
      mockGetSession.mockReturnValue(new Promise((r) => (releaseSession = r)))
      mockOwnOrders([LIST_ENTRY])

      render(<TrackOrderPage />)
      await waitFor(() => expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk"))

      releaseSession(SESSION)
      await waitFor(() => expect(global.fetch).toHaveBeenCalled())
      await screen.findByText(/No order number needed/)
      expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk")
      expect(screen.getByTestId("recorded-set")).not.toHaveTextContent(RECORDED_SET_NOT_LOADED_COPY)
    })
  })
})
