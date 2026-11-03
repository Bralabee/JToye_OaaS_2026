import { act, fireEvent, render, screen } from "@testing-library/react"
import { CuisineRail } from "@/components/marketing/cuisine-rail"

/**
 * D18 (2026-11-03): mouse users get prev/next arrows on the cuisine rail, but
 * only while that side has hidden chips. jsdom lays nothing out, so each case
 * pins the three scroll metrics the component reads and fires a scroll event.
 */
function setMetrics(el: HTMLElement, m: { scrollWidth: number; clientWidth: number; scrollLeft: number }) {
  Object.defineProperty(el, "scrollWidth", { configurable: true, value: m.scrollWidth })
  Object.defineProperty(el, "clientWidth", { configurable: true, value: m.clientWidth })
  Object.defineProperty(el, "scrollLeft", { configurable: true, writable: true, value: m.scrollLeft })
  act(() => {
    fireEvent.scroll(el)
  })
}

function renderRail() {
  const { container } = render(
    <CuisineRail>
      <span>Grill</span>
      <span>Desserts</span>
    </CuisineRail>,
  )
  return container.querySelector("[data-cuisine-rail]") as HTMLElement
}

describe("CuisineRail mouse arrows (D18)", () => {
  it("shows no arrows when every chip fits (1280 and up)", () => {
    const rail = renderRail()
    setMetrics(rail, { scrollWidth: 560, clientWidth: 580, scrollLeft: 0 })
    expect(screen.queryByRole("button", { name: /scroll cuisines/i })).toBeNull()
  })

  it("at the start of an overflowing rail shows only the right arrow", () => {
    const rail = renderRail()
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 0 })
    expect(screen.getByRole("button", { name: "Scroll cuisines right" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Scroll cuisines left" })).toBeNull()
  })

  it("at the end shows only the left arrow, in between both", () => {
    const rail = renderRail()
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 180 })
    expect(screen.getByRole("button", { name: "Scroll cuisines left" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Scroll cuisines right" })).toBeNull()
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 90 })
    expect(screen.getAllByRole("button", { name: /scroll cuisines/i })).toHaveLength(2)
  })

  it("clicking an arrow scrolls the rail by most of its width in that direction", () => {
    const rail = renderRail()
    const scrollBy = jest.fn()
    rail.scrollBy = scrollBy as unknown as typeof rail.scrollBy
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 90 })
    fireEvent.click(screen.getByRole("button", { name: "Scroll cuisines right" }))
    fireEvent.click(screen.getByRole("button", { name: "Scroll cuisines left" }))
    expect(scrollBy.mock.calls[0][0].left).toBeCloseTo(336)
    expect(scrollBy.mock.calls[1][0].left).toBeCloseTo(-336)
  })

  it("keeps the arrows mouse-only and out of the tab order", () => {
    const rail = renderRail()
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 90 })
    for (const b of screen.getAllByRole("button", { name: /scroll cuisines/i })) {
      expect(b).toHaveAttribute("tabindex", "-1")
      expect(b.className).toContain("hidden")
      expect(b.className).toContain("[@media(hover:hover)_and_(pointer:fine)]:flex")
    }
  })
})
