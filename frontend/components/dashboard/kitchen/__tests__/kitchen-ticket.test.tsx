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

/**
 * 31.1-22 (#812, D-15): the customer's allergy note on the PRINTED ticket.
 *
 * The note is its own field since 31.1-13 (V73), apart from the delivery notes. On paper it
 * must be read before the items, in the two things monochrome carries (a border and uppercase
 * words), and it must say whether anyone in the shop has marked it as read: a torn-off ticket
 * has no board left to ask. Nothing on paper is pressable, so the ticket carries the STATE of
 * the acknowledgement and never a control.
 */
describe("KitchenTicket — the allergy note (31.1-22, #812, D-15)", () => {
  const NOTE = "My son has a peanut allergy. Please no satay sauce."
  const withNote: OrderDetail = {
    ...order,
    allergyNote: NOTE,
    allergyNoteAcknowledgedAt: null,
    allergyNoteAcknowledgedBy: null,
  }

  it("prints an ALLERGY NOTE block with the note text, ABOVE the items", () => {
    const { container } = render(
      <KitchenTicket order={withNote} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    const block = container.querySelector(".kds-ticket__allergy-note")
    expect(block).not.toBeNull()
    expect(block).toHaveTextContent("ALLERGY NOTE")
    expect(block).toHaveTextContent(NOTE)
    const items = container.querySelector(".kds-ticket__items") as HTMLElement
    expect(
      (block as HTMLElement).compareDocumentPosition(items) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
  })

  it("keeps the NOTES line to the delivery notes — the allergy note is never merged into it", () => {
    const { container } = render(
      <KitchenTicket order={withNote} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    const notes = container.querySelector(".kds-ticket__notes")
    expect(notes).toHaveTextContent("No scotch bonnet")
    expect(notes).not.toHaveTextContent("peanut")
  })

  it("renders a note carrying markup as inert text (T-31.1-76)", () => {
    const hostile = '<script>window.__pwned = 1</script><img src=x onerror="alert(1)">'
    const { container } = render(
      <KitchenTicket order={{ ...withNote, allergyNote: hostile }} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector("script")).toBeNull()
    expect(container.querySelector("img")).toBeNull()
    expect(container.querySelector(".kds-ticket__allergy-note")).toHaveTextContent(hostile)
  })

  it("prints that nobody has marked the note as read yet", () => {
    const { container } = render(
      <KitchenTicket order={withNote} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__allergy-note")).toHaveTextContent(
      "NOT YET MARKED AS READ"
    )
  })

  it("prints who marked it as read and when, in UK time, once it has been", () => {
    const { container } = render(
      <KitchenTicket
        order={{
          ...withNote,
          // 17:05Z on 3 Oct 2026 is 18:05 in London (BST).
          allergyNoteAcknowledgedAt: "2026-10-03T17:05:00Z",
          allergyNoteAcknowledgedBy: "kim",
        }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    const block = container.querySelector(".kds-ticket__allergy-note")
    expect(block).toHaveTextContent(/Read by kim at 18:05/)
    expect(block).not.toHaveTextContent("NOT YET MARKED AS READ")
  })

  it("never prints the raw account id of whoever read it", () => {
    const sub = "0f6c2a1e-3b4d-4e5f-8a9b-0c1d2e3f4a5b"
    const { container } = render(
      <KitchenTicket
        order={{ ...withNote, allergyNoteAcknowledgedAt: "2026-10-03T17:05:00Z", allergyNoteAcknowledgedBy: sub }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    const block = container.querySelector(".kds-ticket__allergy-note")
    expect(block).toHaveTextContent(/Read by a member of staff at 18:05/)
    expect(block).not.toHaveTextContent(sub)
  })

  it("carries no control on paper", () => {
    render(<KitchenTicket order={withNote} shopName="Peckham" printedAt={PRINTED_AT} />)
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("a ticket with no allergy note prints exactly as before", () => {
    const before = render(
      <KitchenTicket order={order} shopName="Peckham" printedAt={PRINTED_AT} />
    ).container.innerHTML
    const { container } = render(
      <KitchenTicket order={{ ...order, allergyNote: null }} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__allergy-note")).toBeNull()
    expect(container.innerHTML).toBe(before)
  })
})
