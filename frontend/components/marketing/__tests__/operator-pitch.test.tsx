import fs from "fs"
import path from "path"
import { fireEvent, render, screen } from "@testing-library/react"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import ForOperatorsPage from "@/app/for-operators/page"

describe("OperatorPitch design tokens (Surface C re-skin)", () => {
  const src = fs.readFileSync(
    path.join(process.cwd(), "components/marketing/operator-pitch.tsx"),
    "utf8"
  )

  it("uses only design tokens — zero hardcoded hex", () => {
    // The re-skin migrates the bespoke navy/orange/yellow palette onto tokens.
    expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it("renders on-token classes within the landing brand family (oxblood/cream/gold/amber)", () => {
    // The surface used to run its own navy/emerald/mono skin, which read as a
    // different product to anyone arriving from `/`. It now shares the landing
    // brand thread (jtoyedigital.co.uk oxblood + Work Sans + amber appetite).
    expect(src).toMatch(/bg-oxblood/)
    expect(src).toMatch(/text-cream|bg-cream/)
    expect(src).toMatch(/text-gold/)
    expect(src).toMatch(/bg-amber-500/)
  })

  it("does not regress to the old off-brand navy/emerald/mono skin", () => {
    expect(src).not.toMatch(/bg-slate-900|bg-slate-800|bg-emerald-50|border-emerald-200/)
    expect(src).not.toMatch(/font-mono/)
  })

  it("obeys the public display cap — no font-black / text-7xl / serif", () => {
    expect(src).not.toMatch(/font-black|text-7xl|text-8xl|font-serif/)
  })

  it("wraps the operator pitch in the shared PublicShell (connected surface)", () => {
    render(<ForOperatorsPage />)
    // The shared public header exposes a "For operators" nav link (header-only copy),
    // proving PublicShell rendered around the pitch.
    const headerLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(headerLink.getAttribute("href")).toBe("/for-operators")
  })
})

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