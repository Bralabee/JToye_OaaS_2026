/**
 * #957 (Phase 30-02, UI-SPEC § S1 "Customer order page, DRAFT") — an unpaid card order says so.
 *
 * A card order is created as a DRAFT and stays one until its payment completes. The order page
 * called every non-terminal status "Order in Progress", so a customer whose card step failed was told
 * their unpaid order was in progress — which the shop will not prepare. A DRAFT now reads "Awaiting
 * payment", and the shared status copy (the tracker words and the status-region announcement) gains
 * a DRAFT row. DRAFT is NOT a production step, so the tracker steps are unchanged.
 *
 * Harness: order-tracking-allergens.test.tsx (tracking bound to number + email).
 */
import { Suspense } from "react"
import { render, screen } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"
import { ORDER_STATUS_COPY, statusCopy, statusUpdateAnnouncement } from "@/lib/order-status-announcement"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
}))

const SLUG = "mama-ades-kitchen"
const ORDER_NUMBER = "ORD-11112222-20261022-DRAFT957"
const EMAIL = "ravi@example.com"

const BASE = {
  orderNumber: ORDER_NUMBER,
  shopName: "Mama Ade's Kitchen",
  totalAmountPennies: 1250,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-10-22T16:30:00Z",
  updatedAt: "2026-10-22T16:30:00Z",
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

async function renderWithStatus(status: string) {
  mockGet.mockResolvedValue({ data: { ...BASE, status } })
  render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
  // The shop name is in the same status block as the status line: once it is up, so is the line.
  await screen.findByText("Mama Ade's Kitchen")
}

beforeEach(() => {
  mockGet.mockReset()
  localStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
})

describe("the customer order page for an unpaid DRAFT (#957)", () => {
  it("a DRAFT order's status line reads 'Awaiting payment', not 'Order in Progress'", async () => {
    await renderWithStatus("DRAFT")
    expect(screen.getByText("Awaiting payment")).toBeInTheDocument()
    expect(screen.queryByText("Order in Progress")).toBeNull()
    // Still bound to number + email (goods F3 / P2-KYL-P5).
    expect(mockGet).toHaveBeenCalledWith(`/public/orders/${ORDER_NUMBER}`, { params: { email: EMAIL } })
  })

  it("a PENDING order still reads 'Order in Progress' (unchanged)", async () => {
    await renderWithStatus("PENDING")
    expect(screen.getByText("Order in Progress")).toBeInTheDocument()
    expect(screen.queryByText("Awaiting payment")).toBeNull()
  })
})

describe("the shared status copy gains a DRAFT row (#957)", () => {
  it("statusCopy('DRAFT') is { label 'Awaiting payment', desc 'Waiting for your payment' }", () => {
    expect(ORDER_STATUS_COPY.DRAFT).toEqual({ label: "Awaiting payment", desc: "Waiting for your payment" })
    expect(statusCopy("DRAFT")).toEqual({ label: "Awaiting payment", desc: "Waiting for your payment" })
    expect(statusUpdateAnnouncement("DRAFT")).toBe("Order update: Awaiting payment. Waiting for your payment.")
  })

  it("PENDING is unchanged ('Received' / 'Order sent to shop')", () => {
    expect(statusCopy("PENDING")).toEqual({ label: "Received", desc: "Order sent to shop" })
  })
})
