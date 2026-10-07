/**
 * The per-shop order page (/shop/{slug}/orders/{orderNumber}) after the order — Phase 31.1-18.
 *
 * #785 found that no page the customer can return to shows the allergen set the order recorded,
 * so a customer could not see that it differed from what they acknowledged. 31.1-09 put the
 * fields on the tracking response; this page now renders them through the shared
 * RecordedAllergenSet, plus two statements only the tracking pages make:
 *
 *   - D-07: placedVia VENDOR — the shop placed it, so there is no confirmation to show, and the
 *     page says WHY instead of showing an empty acknowledgement block;
 *   - D-15: the customer's allergy note was sent, and whether / when the shop read it. The note's
 *     text is not in the response and is never rendered (T-31.1-67).
 *
 * Tracking stays bound to order number + email (goods F3, P2-KYL-P5): the request below must carry
 * the email as its proof-of-ownership parameter.
 */
import { Suspense } from "react"
import { render, screen, waitFor, within } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"
import {
  RECORDED_ACK_NOT_RECORDED_COPY,
  VENDOR_PLACED_COPY,
  ALLERGY_NOTE_UNREAD_COPY,
} from "@/components/storefront/recorded-allergen-set"
import { allergenFlagCopy } from "@/components/storefront/order-allergen-panel"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
}))

const SLUG = "mama-ades-kitchen"
const ORDER_NUMBER = "ORD-11112222-20261006-AAAABBBB"
const EMAIL = "ravi@example.com"

const BASE = {
  orderNumber: ORDER_NUMBER,
  status: "PREPARING",
  shopName: "Mama Ade's Kitchen",
  totalAmountPennies: 1250,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-10-06T16:30:00Z",
  updatedAt: "2026-10-06T16:45:00Z",
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

async function renderWith(response: Record<string, unknown>) {
  mockGet.mockResolvedValue({ data: { ...BASE, ...response } })
  render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
  await screen.findByText("Order in Progress")
}

beforeEach(() => {
  mockGet.mockReset()
  localStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
})

describe("the per-shop order page shows the order's allergen record (#785, D-08)", () => {
  it("a storefront order with Milk acknowledged and recorded lists Milk under both headings", async () => {
    await renderWith({
      placedVia: "STOREFRONT",
      acknowledgedAllergenMask: 64,
      acknowledgedAllergens: ["Milk"],
      recordedAllergens: ["Milk"],
      recordedAllergenFlags: [],
    })
    const block = screen.getByTestId("recorded-allergen-set")
    expect(within(block).getByTestId("recorded-ack")).toHaveTextContent("You confirmed you had read: Milk")
    expect(within(block).getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk")
    // Still bound to number + email (goods F3 / P2-KYL-P5).
    expect(mockGet).toHaveBeenCalledWith(`/public/orders/${ORDER_NUMBER}`, { params: { email: EMAIL } })
  })

  it("a vendor-placed order states that the shop placed it — and still shows the recorded set (D-07)", async () => {
    await renderWith({
      placedVia: "VENDOR",
      acknowledgedAllergenMask: null,
      acknowledgedAllergens: null,
      recordedAllergens: ["Gluten", "Milk"],
      recordedAllergenFlags: [],
    })
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(VENDOR_PLACED_COPY)
    expect(screen.getByTestId("recorded-ack")).not.toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Gluten, Milk")
  })

  it("a pre-acknowledgement order says the acknowledgement was NOT RECORDED — never 'none' (D-06)", async () => {
    await renderWith({
      placedVia: null,
      acknowledgedAllergenMask: null,
      acknowledgedAllergens: null,
      recordedAllergens: null,
      recordedAllergenFlags: null,
    })
    const ack = screen.getByTestId("recorded-ack")
    expect(ack).toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(ack).not.toHaveTextContent(VENDOR_PLACED_COPY)
    expect(ack.textContent ?? "").not.toMatch(/none|no allergens/i)
  })

  it("a reconciliation flag renders as its own Check line, not inside the recorded list", async () => {
    const flag = { productName: "Fish Pie", allergenBit: 6, allergenName: "Milk" }
    await renderWith({
      placedVia: "STOREFRONT",
      acknowledgedAllergens: ["Fish"],
      recordedAllergens: ["Fish"],
      recordedAllergenFlags: [flag],
    })
    const flags = screen.getAllByTestId("recorded-allergen-flag")
    expect(flags).toHaveLength(1)
    expect(flags[0]).toHaveTextContent(allergenFlagCopy(flag))
    expect(screen.getByTestId("recorded-set")).not.toHaveTextContent("Milk")
  })
})

describe("the per-shop order page says whether the shop read the allergy note (#812, D-15)", () => {
  it("sent, not yet read", async () => {
    await renderWith({
      placedVia: "STOREFRONT",
      acknowledgedAllergens: ["Milk"],
      recordedAllergens: ["Milk"],
      allergyNoteProvided: true,
      allergyNoteAcknowledgedAt: null,
    })
    const note = screen.getByTestId("allergy-note-status")
    expect(note).toHaveTextContent("Your allergy note was sent to Mama Ade's Kitchen")
    expect(note).toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
  })

  it("read by the shop at 18:05 (en-GB, Europe/London)", async () => {
    await renderWith({
      placedVia: "STOREFRONT",
      acknowledgedAllergens: ["Milk"],
      recordedAllergens: ["Milk"],
      allergyNoteProvided: true,
      allergyNoteAcknowledgedAt: "2026-10-06T17:05:00Z",
    })
    expect(screen.getByTestId("allergy-note-status")).toHaveTextContent(/Read by the shop at 18:05/)
  })

  it("no note: no note line", async () => {
    await renderWith({ placedVia: "STOREFRONT", acknowledgedAllergens: ["Milk"], recordedAllergens: ["Milk"], allergyNoteProvided: false })
    await waitFor(() => expect(screen.getByTestId("recorded-allergen-set")).toBeInTheDocument())
    expect(screen.queryByTestId("allergy-note-status")).not.toBeInTheDocument()
  })
})

describe("times on this page are UK times (goods P2-CHA-18)", () => {
  it("the status line's time is the London time, whatever zone the browser is in", async () => {
    // 16:45Z on 6 Oct is 17:45 BST. Falsified by running this suite under TZ=America/New_York
    // with the formatter's timeZone removed (12:45).
    await renderWith({})
    expect(screen.getByText(/Being prepared now · 17:45/)).toBeInTheDocument()
  })
})
