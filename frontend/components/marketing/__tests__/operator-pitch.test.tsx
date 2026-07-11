import { fireEvent, render, screen } from "@testing-library/react"
import { OperatorPitch } from "@/components/marketing/operator-pitch"

describe("OperatorPitch", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = jest.fn()
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  it("speaks directly to the established London-cluster operator audience", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /keep the order.*keep the customer.*keep the kitchen moving/i })).toBeInTheDocument()
    expect(screen.getByText(/established Nigerian and West African takeaway and catering operators/i)).toBeInTheDocument()
    expect(screen.getByText(/one London cluster/i)).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /one change.*three channels.*two versions/i })).toBeInTheDocument()
    expect(screen.getByText(/fewer places to check/i)).toBeInTheDocument()
    expect(screen.getAllByRole("link", { name: /download vendor pack|download the pack/i })[0]).toHaveAttribute("href", "/jtoye-operator-pilot-pack.pdf")
    expect(screen.getAllByRole("link", { name: /download vendor pack|download the pack/i })[0]).toHaveAttribute("download")
    expect(screen.getByText(/return this pack to the person who shared it/i)).toBeInTheDocument()
    expect(screen.getByText(/edition 10 July 2026/i)).toBeInTheDocument()
  })

  it("offers a 'Start your application' CTA linking to onboarding", () => {
    render(<OperatorPitch />)

    const cta = screen.getByRole("link", { name: /start your application/i })
    expect(cta).toHaveAttribute("href", "/dashboard/onboarding")
  })

  it("keeps the takeaway and catering cohorts distinct", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /takeaway: protect the regular order/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /catering: make the handover legible/i })).toBeInTheDocument()
    expect(screen.getAllByText(/catering and WhatsApp are validation tracks/i)).not.toHaveLength(0)
  })

  it("states the product and responsibility caveats plainly", () => {
    render(<OperatorPitch />)

    expect(screen.getByText(/direct storefront does not generate demand/i)).toBeInTheDocument()
    expect(screen.getByText(/not an offline KDS/i)).toBeInTheDocument()
    expect(screen.getByText(/allergen information is vendor-entered assistance/i)).toBeInTheDocument()
    expect(screen.getAllByText(/not production connected-account settlement/i)).toHaveLength(2)
  })

  it("reveals the fit check and copies its locally generated summary", async () => {
    render(<OperatorPitch />)

    fireEvent.click(screen.getByRole("button", { name: /check your pilot fit/i }))
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText("Your main service"), { target: { value: "Catering" } })
    fireEvent.change(screen.getByLabelText("What you want to steady first"), { target: { value: "Make catering handover clearer" } })
    fireEvent.click(screen.getByRole("button", { name: /copy fit summary/i }))

    expect(await screen.findByRole("status")).toHaveTextContent("Fit summary copied")
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining("Business: Catering"))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining("Priority: Make catering handover clearer"))
  })
})