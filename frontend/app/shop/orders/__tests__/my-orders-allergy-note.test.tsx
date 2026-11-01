/**
 * SW-PRY-06 (swarm epic #964), under T-31.1-46: the customer reads back their OWN allergy note on
 * the signed-in My Orders page, and nowhere else.
 *
 * The server sends the text only on `/public/orders/mine` (CustomerOrderStatus), and only for an
 * order whose contact was verified at checkout. This page renders it as text, keeps it in React
 * state only, and the tracking page, which reads the same signed-in list, still never renders it.
 *
 * The tracking arm imports `app/track/page` READ-ONLY: this lane does not edit that page; it proves
 * the page shows no note text even when the list it is handed carries one.
 */
import { fireEvent, render, screen, within } from "@testing-library/react"
import { useSearchParams } from "next/navigation"
import { OrdersClient } from "../orders-client"
import TrackOrderPage from "@/app/track/page"
import {
  MY_ORDERS_ALLERGY_NOTE_HEADING_COPY,
  type OrderSummary,
  type OrdersLoad,
} from "@/lib/customer-orders"
import { ALLERGY_NOTE_SENT_COPY, ALLERGY_NOTE_UNREAD_COPY } from "@/components/storefront/recorded-allergen-set"

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const NOTE = "My child is allergic to peanuts and sesame - please confirm."

function order(partial: Partial<OrderSummary>): OrderSummary {
  return {
    orderNumber: "ORD-00000000-20261101-AAAAAAAA",
    status: "PREPARING",
    shopName: "Mama Ade's Kitchen",
    totalAmountPennies: 1250,
    itemCount: 1,
    unitCount: 1,
    placedVia: "STOREFRONT",
    acknowledgedAllergenMask: 1,
    acknowledgedAllergens: ["Gluten"],
    createdAt: "2026-11-01T12:00:00Z",
    updatedAt: "2026-11-01T12:05:00Z",
    ...partial,
  }
}

const loadOf = (...orders: OrderSummary[]): OrdersLoad => ({ state: "ok", orders, totalElements: orders.length })

const WITH_NOTE = order({
  orderNumber: "ORD-1111-NOTE",
  allergyNoteProvided: true,
  allergyNoteAcknowledgedAt: null,
  allergyNote: NOTE,
})

beforeEach(() => {
  mockGet.mockReset()
  mockGetSession.mockReset()
  mockGetSession.mockResolvedValue({
    profile: { sub: "u1", email: "buyer@example.com", name: "Buyer", emailVerified: true },
    expiresAt: Math.floor(Date.now() / 1000) + 300,
  })
  ;(useSearchParams as jest.Mock).mockReturnValue({ get: () => null })
  global.fetch = jest.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({ content: [WITH_NOTE], totalElements: 1 }),
  }) as unknown as typeof fetch
  localStorage.clear()
  sessionStorage.clear()
})

describe("My Orders shows the customer's own allergy note (SW-PRY-06)", () => {
  it("renders the note text under its heading, with the shop's read state", () => {
    render(<OrdersClient initial={loadOf(WITH_NOTE)} email="buyer@example.com" />)
    const block = screen.getByTestId(`order-allergy-note-${WITH_NOTE.orderNumber}`)
    expect(block).toHaveTextContent(MY_ORDERS_ALLERGY_NOTE_HEADING_COPY)
    expect(within(block).getByText(NOTE)).toBeInTheDocument()
    expect(block).toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
  })

  it("says when the shop read it once the acknowledgement is recorded", () => {
    const read = order({ ...WITH_NOTE, allergyNoteAcknowledgedAt: "2026-11-01T12:03:00Z" })
    render(<OrdersClient initial={loadOf(read)} email="buyer@example.com" />)
    const block = screen.getByTestId(`order-allergy-note-${read.orderNumber}`)
    expect(block).not.toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
    expect(block.textContent ?? "").toMatch(/read/i)
  })

  it("renders no note block when the server sent none (no note, unverified contact, or erased)", () => {
    const withheld = order({ orderNumber: "ORD-2222-WITHHELD", allergyNoteProvided: true, allergyNote: null })
    const absent = order({ orderNumber: "ORD-3333-ABSENT", allergyNoteProvided: false })
    render(<OrdersClient initial={loadOf(withheld, absent)} email="buyer@example.com" />)
    expect(screen.queryByTestId(/^order-allergy-note-/)).toBeNull()
    expect(screen.queryByText(MY_ORDERS_ALLERGY_NOTE_HEADING_COPY)).toBeNull()
  })

  it("renders the note as text, never as markup", () => {
    const hostile = order({ orderNumber: "ORD-4444-HTML", allergyNote: '<img src="x" alt="injected">No nuts' })
    const { container } = render(<OrdersClient initial={loadOf(hostile)} email="buyer@example.com" />)
    expect(container.querySelector('img[alt="injected"]')).toBeNull()
    expect(screen.getByText('<img src="x" alt="injected">No nuts')).toBeInTheDocument()
  })

  // Review R1: the old "stores the note nowhere" arm could not fail (nothing here writes Web Storage).
  // What CAN regress is the opt-in: the proxy strips the note for any caller that does not send
  // include=allergyNote (route.test.ts), so a refresh without it would silently drop the note.
  it("refreshes through the proxy WITH include=allergyNote, so the note survives a refresh", async () => {
    render(<OrdersClient initial={{ state: "error", reason: "upstream" }} email="buyer@example.com" />)
    fireEvent.click(screen.getByTestId("orders-retry"))
    const block = await screen.findByTestId(`order-allergy-note-${WITH_NOTE.orderNumber}`)
    expect(within(block).getByText(NOTE)).toBeInTheDocument()
    const [url] = (global.fetch as jest.Mock).mock.calls[0]
    expect(new URL(String(url), "http://localhost").searchParams.get("include")).toBe("allergyNote")
  })
})

describe("the tracking page never renders the note, even from the signed-in list (T-31.1-46)", () => {
  it("opens on the order and says a note was sent, without its text", async () => {
    render(<TrackOrderPage />)
    expect(await screen.findByText(WITH_NOTE.orderNumber)).toBeInTheDocument()
    expect(await screen.findByTestId("allergy-note-status")).toHaveTextContent(
      ALLERGY_NOTE_SENT_COPY(WITH_NOTE.shopName)
    )
    // A literal, not the My Orders constant: this arm guards the tracking page on its own, and must
    // pass on a tree where that constant does not exist.
    expect(document.body.textContent ?? "").not.toContain("peanuts")
    expect(screen.queryByText("Your allergy note")).toBeNull()
  })
})
