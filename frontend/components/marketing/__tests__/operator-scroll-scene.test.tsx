import { render, screen } from "@testing-library/react"
import { OperatorPitch } from "@/components/marketing/operator-pitch"

/**
 * jsdom has no matchMedia and cannot drive scroll, so `useOperatorScrollScene`
 * no-ops (`canEnhance()` false): no pin, no horizontal track, no split. These
 * tests prove the no-FOUC floor — the headline and all four pilot steps stay
 * fully visible and the scope is never marked active. The pin/horizontal
 * behaviour itself is proven by the Playwright spec (Task 5).
 */
describe("OperatorScrollScene enhancement — no-FOUC floor", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = jest.fn()
  })

  it("keeps the headline fully visible (splitWords is desktop-client-only)", () => {
    render(<OperatorPitch />)
    expect(
      screen.getByRole("heading", {
        name: /keep the order.*keep the customer.*keep the kitchen moving/i,
      }),
    ).toBeVisible()
  })

  it("keeps every one of the four pilot steps fully visible", () => {
    render(<OperatorPitch />)
    expect(screen.getByRole("heading", { name: /map your service/i })).toBeVisible()
    expect(screen.getByRole("heading", { name: /set up the direct path/i })).toBeVisible()
    expect(screen.getByRole("heading", { name: /run a real service/i })).toBeVisible()
    expect(screen.getByRole("heading", { name: /review before expanding/i })).toBeVisible()
  })

  it("does not mark the scope active or split the headline in jsdom", () => {
    const { container } = render(<OperatorPitch />)
    const root = container.firstElementChild as HTMLElement
    expect(root).not.toHaveAttribute("data-motion-active")
    expect(container.querySelectorAll(".gsap-word")).toHaveLength(0)
  })

  it("preserves the pilot list semantics and step numbers", () => {
    render(<OperatorPitch />)
    const track = document.querySelector("[data-pilot-track]")
    expect(track?.tagName).toBe("OL")
    const steps = document.querySelectorAll("[data-pilot-step]")
    expect(steps).toHaveLength(4)
    steps.forEach((step) => expect(step.tagName).toBe("LI"))
    // terms count-up hooks keep the real number as their visible text
    expect(screen.getByText(/per location, per month/i).textContent).toContain("£39")
  })
})
