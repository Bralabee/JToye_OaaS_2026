import { render, screen } from "@testing-library/react"
import { Reveal } from "@/components/marketing/reveal"

/**
 * jsdom has no matchMedia, so `canEnhance()` is false → the floor snapshot is
 * "active" and Reveal renders its framer-motion wrapper. framer-motion is
 * globally mocked (jest.setup.js) to strip motion-only props, so the reveal
 * renders as a plain, fully-visible element — proving the no-FOUC default.
 */
describe("Reveal — mobile / reduced-motion floor", () => {
  it("renders its children fully visible by default", () => {
    render(<Reveal>Trust me</Reveal>)

    const child = screen.getByText("Trust me")
    expect(child).toBeInTheDocument()
    expect(child).toBeVisible()
  })

  it("does not apply a hidden / opacity:0 / aria-hidden state to the wrapper", () => {
    const { container } = render(
      <Reveal as="section" className="trust">
        <span>Chip</span>
      </Reveal>,
    )

    const wrapper = container.firstElementChild as HTMLElement
    expect(wrapper).not.toHaveAttribute("aria-hidden")
    expect(wrapper).not.toHaveAttribute("hidden")
    // framer-only props (initial/whileInView/variants) are stripped → no inline opacity:0
    expect(wrapper.style.opacity).not.toBe("0")
    expect(screen.getByText("Chip")).toBeVisible()
  })

  it("honours the requested wrapper tag and className", () => {
    const { container } = render(
      <Reveal as="section" className="reveal-strip">
        content
      </Reveal>,
    )
    const wrapper = container.firstElementChild as HTMLElement
    expect(wrapper.tagName).toBe("SECTION")
    expect(wrapper).toHaveClass("reveal-strip")
  })
})
