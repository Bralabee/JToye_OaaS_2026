import { render, screen } from "@testing-library/react"
import { KdsReadyByBadge, KdsUnacceptedBadge } from "@/components/dashboard/kitchen/kds-time-badges"

// #825: the kitchen ticket's ready-by is an estimate. #821: the New lane flags an order waiting too long.
describe("KdsReadyByBadge", () => {
  it("shows 'Est. ready' with the UK time", () => {
    render(<KdsReadyByBadge estimatedReadyAt="2099-11-04T14:35:00Z" />)
    expect(screen.getByTestId("kds-ready-by-badge")).toHaveTextContent("Est. ready 14:35")
  })

  it("renders nothing when the shop set no prep time", () => {
    const { container } = render(<KdsReadyByBadge />)
    expect(container).toBeEmptyDOMElement()
    const { container: b } = render(<KdsReadyByBadge estimatedReadyAt={null} />)
    expect(b).toBeEmptyDOMElement()
  })
})

describe("KdsUnacceptedBadge", () => {
  it("flags a PENDING order the sweep has alerted, in words", () => {
    render(<KdsUnacceptedBadge status="PENDING" unacceptedNotifiedAt="2026-11-04T12:10:00Z" />)
    expect(screen.getByTestId("kds-unaccepted-badge")).toHaveTextContent("Waiting too long. The customer has been told.")
  })

  it("renders nothing for a PENDING order that has not been alerted", () => {
    const { container } = render(<KdsUnacceptedBadge status="PENDING" />)
    expect(container).toBeEmptyDOMElement()
  })

  it.each([["CONFIRMED"], ["PREPARING"], ["READY"], ["CANCELLED"]])(
    "renders nothing once the order is %s, even with a stamp",
    (status) => {
      const { container } = render(<KdsUnacceptedBadge status={status} unacceptedNotifiedAt="2026-11-04T12:10:00Z" />)
      expect(container).toBeEmptyDOMElement()
    }
  )
})
