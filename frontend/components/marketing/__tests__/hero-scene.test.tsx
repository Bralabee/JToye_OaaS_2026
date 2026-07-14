import { render, screen } from "@testing-library/react"
import { HeroScene } from "@/components/marketing/hero-scene"

/**
 * jsdom has no matchMedia and cannot drive scroll, so `canEnhance()` is false
 * and the whole GSAP matchMedia branch no-ops. These tests prove the no-FOUC
 * default: server-rendered children stay fully visible and the enhancer never
 * hides anything or marks the scope active when the gate never fires. The
 * desktop scene itself is proven by the Playwright spec (Task 5).
 */
describe("HeroScene — no-FOUC progressive enhancer", () => {
  it("renders server children fully visible when the gate never fires", () => {
    render(
      <HeroScene>
        <h1 data-hero-headline>Order from local kitchens. Or run yours.</h1>
        <a data-hero-door href="#shop">
          Order food near you
        </a>
        <div data-hero-step>
          <h3>Browse</h3>
        </div>
      </HeroScene>,
    )

    expect(
      screen.getByRole("heading", {
        name: /order from local kitchens\. or run yours\./i,
      }),
    ).toBeVisible()
    expect(screen.getByText("Order food near you")).toBeVisible()
    expect(screen.getByRole("heading", { name: /browse/i })).toBeVisible()
  })

  it("does not hide content or mark the scope active in jsdom", () => {
    const { container } = render(
      <HeroScene>
        <p>Visible content</p>
      </HeroScene>,
    )

    const wrapper = container.firstElementChild as HTMLElement
    expect(wrapper).not.toHaveAttribute("data-motion-active")
    expect(wrapper.style.opacity).not.toBe("0")
    expect(screen.getByText("Visible content")).toBeVisible()
    // headline never split client-side → no word spans injected in jsdom
    expect(container.querySelectorAll(".gsap-word")).toHaveLength(0)
  })
})
