/**
 * #105 — the printable kitchen ticket.
 *
 * These assert the ticket's CONTENT and the class hooks the print stylesheet keys
 * off. They deliberately do NOT assert that it "prints": jsdom loads no stylesheet
 * and has no print media, so a passing render here says nothing about `@media print`.
 * The print behaviour is proven in a real browser instead — `page.emulateMedia({
 * media: "print" })` plus a generated PDF — because that is the only place the
 * question can actually be answered.
 */
import { render, screen, within } from "@testing-library/react"
import { KitchenTicket } from "../kitchen-ticket"
import type { OrderDetail } from "@/types/api"

const PRINTED_AT = Date.UTC(2026, 7, 4, 18, 30)

const order: OrderDetail = {
  id: "11111111-2222-3333-4444-555555555555",
  tenantId: "t-1",
  shopId: "s-1",
  orderNumber: "ORD-2026-0042",
  status: "PREPARING",
  customerName: "Adeola",
  notes: "No scotch bonnet",
  totalAmountPennies: 2350,
  items: [
    { id: "i1", productId: "p1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 800, totalPricePennies: 1600, createdAt: "2026-08-04T17:00:00Z" },
    { id: "i2", productId: "p2", productName: "Suya Wrap", quantity: 1, unitPricePennies: 750, totalPricePennies: 750, createdAt: "2026-08-04T17:00:00Z" },
  ],
  createdAt: "2026-08-04T17:00:00Z",
  updatedAt: "2026-08-04T17:00:00Z",
  fulfilmentType: "DELIVERY",
  addressLine1: "12 Rye Lane",
  addressCity: "London",
  addressPostcode: "SE15 5BS",
}

describe("KitchenTicket", () => {
  it("leads with the shop, the order reference and the fulfilment type", () => {
    const { container } = render(
      <KitchenTicket order={order} shopName="Peckham Jollof Co." printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__shop")).toHaveTextContent("Peckham Jollof Co.")
    expect(container.querySelector(".kds-ticket__ref")).toHaveTextContent("ORD-2026-0042")
    expect(container.querySelector(".kds-ticket__fulfilment")).toHaveTextContent("DELIVERY")
  })

  it("lists every line item with its quantity first", () => {
    const { container } = render(
      <KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    const items = container.querySelectorAll(".kds-ticket__items li")
    expect(items).toHaveLength(2)
    expect(within(items[0] as HTMLElement).getByText("2×")).toBeInTheDocument()
    expect(items[0]).toHaveTextContent("Jollof Rice")
    expect(within(items[1] as HTMLElement).getByText("1×")).toBeInTheDocument()
    expect(items[1]).toHaveTextContent("Suya Wrap")
  })

  it("carries the customer, the order time and the status", () => {
    render(<KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />)
    expect(screen.getByText("Adeola")).toBeInTheDocument()
    expect(screen.getByText("PREPARING")).toBeInTheDocument()
    expect(screen.getByText("Customer")).toBeInTheDocument()
  })

  it("prints notes and the delivery address for a DELIVERY order", () => {
    const { container } = render(
      <KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__notes")).toHaveTextContent("No scotch bonnet")
    const addr = container.querySelector(".kds-ticket__address")
    expect(addr).toHaveTextContent("12 Rye Lane")
    expect(addr).toHaveTextContent("SE15 5BS")
  })

  it("omits the address block entirely for a COLLECTION order", () => {
    // A collection ticket with a delivery address on it is a delivery waiting to
    // happen. Address fields can be non-null on a pre-V45 row, so the fulfilment
    // type — not the presence of the fields — has to decide.
    const { container } = render(
      <KitchenTicket
        order={{ ...order, fulfilmentType: "COLLECTION" }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    expect(container.querySelector(".kds-ticket__fulfilment")).toHaveTextContent("COLLECTION")
    expect(container.querySelector(".kds-ticket__address")).toBeNull()
  })

  it("carries NO money — a prep ticket is not a receipt", () => {
    const { container } = render(
      <KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.textContent).not.toMatch(/£|23\.50|2350/)
  })

  it("falls back to a short id when the order carries no reference", () => {
    const { container } = render(
      <KitchenTicket
        order={{ ...order, orderNumber: undefined }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    expect(container.querySelector(".kds-ticket__ref")).toHaveTextContent("#11111111")
  })

  it("still prints a usable ticket with no shop name, no customer and no items", () => {
    const { container } = render(
      <KitchenTicket
        order={{ ...order, items: [], customerName: undefined, notes: undefined }}
        shopName={null}
        printedAt={PRINTED_AT}
      />
    )
    expect(container.querySelector(".kds-ticket__shop")).toHaveTextContent("Kitchen")
    expect(screen.getByText("Walk-in")).toBeInTheDocument()
    expect(screen.getByText("No items on this order")).toBeInTheDocument()
  })

  it("stamps when it was printed, so a re-print is distinguishable from the original", () => {
    const { container } = render(
      <KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__foot")).toHaveTextContent(/^Printed \d{2} \w{3}, \d{2}:\d{2}$/)
  })
})
