import { render, screen } from "@testing-library/react"
import { UnacceptedOrdersBanner, overdueUnacceptedCount } from "@/components/dashboard/unaccepted-orders-banner"

// #821 (owner ruling 2026-11-04): the dashboard says when orders have waited too long to be accepted.
const STAMP = "2026-11-04T12:10:00Z"

describe("overdueUnacceptedCount", () => {
  it("counts only PENDING orders the server has alerted", () => {
    expect(
      overdueUnacceptedCount([
        { status: "PENDING", unacceptedNotifiedAt: STAMP },
        { status: "PENDING", unacceptedNotifiedAt: STAMP },
        { status: "PENDING" },
        { status: "PENDING", unacceptedNotifiedAt: null },
        { status: "CONFIRMED", unacceptedNotifiedAt: STAMP },
        { status: "CANCELLED", unacceptedNotifiedAt: STAMP },
      ])
    ).toBe(2)
  })
})

describe("UnacceptedOrdersBanner", () => {
  it("renders nothing when no order is overdue", () => {
    const { container } = render(<UnacceptedOrdersBanner orders={[{ status: "PENDING" }]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("names one overdue order and links to the kitchen board", () => {
    render(<UnacceptedOrdersBanner orders={[{ status: "PENDING", unacceptedNotifiedAt: STAMP }]} />)
    const banner = screen.getByTestId("unaccepted-orders-banner")
    expect(banner).toHaveAttribute("role", "status")
    expect(banner).toHaveTextContent("1 order has been waiting too long for you to accept it.")
    expect(screen.getByRole("link", { name: "Open the kitchen board" })).toHaveAttribute("href", "/dashboard/kitchen")
  })

  it("pluralises and says the customers were told", () => {
    render(
      <UnacceptedOrdersBanner
        orders={[
          { status: "PENDING", unacceptedNotifiedAt: STAMP },
          { status: "PENDING", unacceptedNotifiedAt: STAMP },
          { status: "PENDING", unacceptedNotifiedAt: STAMP },
        ]}
      />
    )
    const banner = screen.getByTestId("unaccepted-orders-banner")
    expect(banner).toHaveTextContent("3 orders have been waiting too long for you to accept them.")
    expect(banner).toHaveTextContent("The customers have been told the shop has not yet accepted.")
  })
})
