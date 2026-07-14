import { render, screen } from "@testing-library/react"
import { IngredientText } from "@/components/ui/ingredient-text"

describe("IngredientText (QA FE-1)", () => {
  it("bolds **allergen** spans and never leaks literal asterisks", () => {
    const { container } = render(
      <IngredientText text="mango, **yoghurt (milk)**, cardamom" />
    )
    // The allergen span is emphasised...
    const strong = screen.getByText("yoghurt (milk)")
    expect(strong.tagName).toBe("STRONG")
    // ...and the raw markdown asterisks are gone from the rendered text.
    expect(container.textContent).toBe("mango, yoghurt (milk), cardamom")
    expect(container.textContent).not.toContain("**")
  })

  it("handles multiple allergen spans", () => {
    render(<IngredientText text="beef, **wheat wrap**, salad, **yaji (peanuts)**" />)
    expect(screen.getByText("wheat wrap").tagName).toBe("STRONG")
    expect(screen.getByText("yaji (peanuts)").tagName).toBe("STRONG")
  })

  it("renders plain text with no emphasis unchanged", () => {
    const { container } = render(<IngredientText text="hibiscus punch, pineapple" />)
    expect(container.textContent).toBe("hibiscus punch, pineapple")
    expect(container.querySelector("strong")).toBeNull()
  })

  it("renders nothing for empty/nullish text", () => {
    const { container } = render(<IngredientText text={null} />)
    expect(container.textContent).toBe("")
  })
})
