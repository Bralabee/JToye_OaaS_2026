import { render, screen } from "@testing-library/react"
import { EstimatedReadyBy } from "@/components/storefront/estimated-ready-by"

// #825 (owner ruling 2026-11-04): the customer's ready-by line is always an ESTIMATE and never guessed.
describe("EstimatedReadyBy", () => {
  it("shows the time labelled as an estimate while the order is waiting to be made", () => {
    render(<EstimatedReadyBy estimatedReadyAt="2099-11-04T14:35:00Z" status="CONFIRMED" />)
    const line = screen.getByTestId("estimated-ready-by")
    expect(line).toHaveTextContent("Estimated ready by")
    expect(line).toHaveTextContent("14:35")
    expect(line).toHaveTextContent("This is an estimate, not a promise.")
  })

  it("renders nothing when the shop set no prep time (field absent)", () => {
    const { container } = render(<EstimatedReadyBy status="PENDING" />)
    expect(container).toBeEmptyDOMElement()
  })

  it("renders nothing for a null or unreadable value", () => {
    const { container: a } = render(<EstimatedReadyBy estimatedReadyAt={null} status="PENDING" />)
    expect(a).toBeEmptyDOMElement()
    const { container: b } = render(<EstimatedReadyBy estimatedReadyAt="soon" status="PENDING" />)
    expect(b).toBeEmptyDOMElement()
  })

  it.each([["READY"], ["COMPLETED"], ["CANCELLED"], ["REFUNDED"]])(
    "renders nothing once the order is %s",
    (status) => {
      const { container } = render(<EstimatedReadyBy estimatedReadyAt="2099-11-04T14:35:00Z" status={status} />)
      expect(container).toBeEmptyDOMElement()
    }
  )
})
