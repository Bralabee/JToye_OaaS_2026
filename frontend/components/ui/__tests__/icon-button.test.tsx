import { render, screen } from "@testing-library/react"
import { Trash2 } from "lucide-react"

import { IconButton } from "@/components/ui/icon-button"

/**
 * The 92 `button-name` criticals in #451 were all icon-only controls with an
 * EMPTY accessible name. These assert the property that fixes them — the name
 * is present and it is the one the caller asked for — rather than asserting the
 * markup, which would pass over a component that names every button "button".
 */
describe("IconButton (#451)", () => {
  it("exposes its label as the accessible name", () => {
    render(<IconButton label="Delete product Party Jollof Rice" icon={<Trash2 />} />)
    expect(
      screen.getByRole("button", { name: "Delete product Party Jollof Rice" })
    ).toBeInTheDocument()
  })

  it("names the OBJECT, so two rows are distinguishable by name alone", () => {
    render(
      <>
        <IconButton label="Delete product Party Jollof Rice" icon={<Trash2 />} />
        <IconButton label="Delete product Suya Skewers" icon={<Trash2 />} />
      </>
    )
    const names = screen.getAllByRole("button").map((b) => b.getAttribute("aria-label"))
    expect(new Set(names).size).toBe(2)
  })

  it("defaults the hover tooltip to the label, and honours an explicit one", () => {
    const { rerender } = render(<IconButton label="Edit shop Peckham Jollof Co" icon={<Trash2 />} />)
    expect(screen.getByRole("button")).toHaveAttribute("title", "Edit shop Peckham Jollof Co")

    rerender(<IconButton label="Edit shop Peckham Jollof Co" tooltip="Edit" icon={<Trash2 />} />)
    expect(screen.getByRole("button")).toHaveAttribute("title", "Edit")
  })

  it("suppresses the tooltip when asked, without losing the accessible name", () => {
    render(<IconButton label="Dismiss notification" tooltip={false} icon={<Trash2 />} />)
    const btn = screen.getByRole("button", { name: "Dismiss notification" })
    expect(btn).not.toHaveAttribute("title")
  })

  it("keeps the icon out of the accessibility tree", () => {
    const { container } = render(<IconButton label="Delete" icon={<Trash2 data-testid="glyph" />} />)
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull()
  })

  it("lets the caller widen the 32px default without fighting it", () => {
    render(<IconButton label="Delete" className="h-11 w-11" icon={<Trash2 />} />)
    const cls = screen.getByRole("button").className
    expect(cls).toContain("h-11")
    expect(cls).not.toContain("h-8")
  })
})
