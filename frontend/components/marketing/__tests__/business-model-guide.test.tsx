import fs from "fs"
import path from "path"
import { fireEvent, render, screen } from "@testing-library/react"
import { BusinessModelGuide } from "@/components/marketing/business-model-guide"
import BusinessModelGuidePage from "@/app/business-model-guide/page"

describe("BusinessModelGuide design tokens (Surface C re-skin)", () => {
  const src = fs.readFileSync(
    path.join(process.cwd(), "components/marketing/business-model-guide.tsx"),
    "utf8"
  )

  it("uses only design tokens — zero hardcoded hex", () => {
    // The re-skin converges the bespoke teal/rust/olive palette onto the same
    // dark surface (bg-slate-900) as the operator pitch.
    expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it("renders on-token classes within the locked orange/emerald/slate family", () => {
    expect(src).toMatch(/bg-slate-900/)
    expect(src).toMatch(/text-orange-600/)
    expect(src).toMatch(/bg-emerald-50/)
  })

  it("obeys the public display cap — no font-black / text-7xl / serif", () => {
    expect(src).not.toMatch(/font-black|text-7xl|text-8xl|font-serif/)
  })

  it("wraps the guide in the shared PublicShell (connected surface)", () => {
    render(<BusinessModelGuidePage />)
    // The shared public footer carries the allergen note — proves PublicShell wrapped it.
    expect(
      screen.getByText(/allergen info available on all products/i)
    ).toBeTruthy()
  })
})

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