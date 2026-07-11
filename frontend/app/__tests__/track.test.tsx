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
