import { fireEvent, render, screen } from "@testing-library/react"
import { BusinessModelGuide } from "@/components/marketing/business-model-guide"

describe("BusinessModelGuide", () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: jest.fn().mockResolvedValue(undefined) },
    })
  })

  it("updates the transparent unit economics when GTV changes", () => {
    render(<BusinessModelGuide />)

    expect(screen.getByText("£89")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Monthly GTV"), { target: { value: "20000" } })

    expect(screen.getByText("£139")).toBeInTheDocument()
    expect(screen.getByText("£29")).toBeInTheDocument()
    expect(screen.getByText("£110")).toBeInTheDocument()
  })

  it("filters the evidence ledger by confidence", () => {
    render(<BusinessModelGuide />)

    fireEvent.click(screen.getByRole("button", { name: "To validate" }))

    expect(screen.getByText("Assisted onboarding may beat a self-serve funnel")).toBeInTheDocument()
    expect(screen.queryByText("The first cluster should be narrow, not pan-European")).not.toBeInTheDocument()
  })

  it("copies the link and provides a printable PDF", async () => {
    render(<BusinessModelGuide />)

    fireEvent.click(screen.getAllByRole("button", { name: "Copy link" })[0])
    expect(await screen.findByText("Link copied")).toBeInTheDocument()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(window.location.href)

    const printLinks = screen.getAllByRole("link", { name: /print \/ save pdf/i })
    expect(printLinks[0]).toHaveAttribute("href", "/business-model-guide.pdf")
    expect(printLinks[0]).toHaveAttribute("target", "_blank")
  })
})