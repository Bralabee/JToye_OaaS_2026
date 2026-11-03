import { act, fireEvent, render, screen } from "@testing-library/react"
import { CuisineRail } from "@/components/marketing/cuisine-rail"

/**
 * D18 (2026-11-03): mouse users get prev/next arrows on the cuisine rail while
 * that side has hidden chips. The arrows are always mounted; CSS shows them
 * from three wrapper attributes (data-fine-pointer, data-can-left,
 * data-can-right), so these cases assert the attributes. jsdom applies no CSS:
 * that the attributes actually hide the arrows on touch is proven in Chromium
 * (PR 1108), not here.
 */
type Metrics = { scrollWidth: number; clientWidth: number; scrollLeft: number }

function setMetrics(el: HTMLElement, m: Metrics) {
  Object.defineProperty(el, "scrollWidth", { configurable: true, value: m.scrollWidth })
  Object.defineProperty(el, "clientWidth", { configurable: true, value: m.clientWidth })
  Object.defineProperty(el, "scrollLeft", { configurable: true, writable: true, value: m.scrollLeft })
}

function scrollTo(el: HTMLElement, m: Metrics) {
  setMetrics(el, m)
  act(() => {
    fireEvent.scroll(el)
  })
}

function mockMatchMedia(fine: boolean) {
  window.matchMedia = jest.fn().mockImplementation((q: string) => ({
    matches: q.includes("pointer: fine") ? fine : false,
    media: q,
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  })) as unknown as typeof window.matchMedia
}

function renderRail() {
  const { container } = render(
    <CuisineRail>
      <span>Grill</span>
      <span>Desserts</span>
    </CuisineRail>,
  )
  const rail = container.querySelector("[data-cuisine-rail]") as HTMLElement
  return { rail, wrapper: rail.parentElement as HTMLElement }
}

const edges = (w: HTMLElement) => [w.dataset.canLeft, w.dataset.canRight]

describe("CuisineRail mouse arrows (D18)", () => {
  const realMatchMedia = window.matchMedia
  const realRO = (global as { ResizeObserver?: unknown }).ResizeObserver
  afterEach(() => {
    window.matchMedia = realMatchMedia
    ;(global as { ResizeObserver?: unknown }).ResizeObserver = realRO
  })

  it("marks the pointer fine or coarse from the media query", () => {
    mockMatchMedia(true)
    expect(renderRail().wrapper.dataset.finePointer).toBe("true")
  })

  it("never marks a touch pointer fine", () => {
    mockMatchMedia(false)
    expect(renderRail().wrapper.dataset.finePointer).toBe("false")
  })

  it("offers neither side when every chip fits (1280 and up)", () => {
    const { rail, wrapper } = renderRail()
    scrollTo(rail, { scrollWidth: 560, clientWidth: 580, scrollLeft: 0 })
    expect(edges(wrapper)).toEqual(["false", "false"])
  })

  it("offers right at the start, left at the end, both in between", () => {
    const { rail, wrapper } = renderRail()
    scrollTo(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 0 })
    expect(edges(wrapper)).toEqual(["false", "true"])
    scrollTo(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 180 })
    expect(edges(wrapper)).toEqual(["true", "false"])
    scrollTo(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 90 })
    expect(edges(wrapper)).toEqual(["true", "true"])
  })

  it("re-measures when a chip widens inside an unchanged rail (late web font)", () => {
    let callback: () => void = () => {}
    const observed: Element[] = []
    ;(global as { ResizeObserver?: unknown }).ResizeObserver = class {
      constructor(cb: () => void) {
        callback = cb
      }
      observe(el: Element) {
        observed.push(el)
      }
      disconnect() {}
    }
    const { rail, wrapper } = renderRail()
    expect(observed).toEqual([rail, ...Array.from(rail.children)])
    setMetrics(rail, { scrollWidth: 560, clientWidth: 580, scrollLeft: 0 })
    act(() => callback())
    expect(edges(wrapper)).toEqual(["false", "false"])
    setMetrics(rail, { scrollWidth: 640, clientWidth: 580, scrollLeft: 0 })
    act(() => callback())
    expect(edges(wrapper)).toEqual(["false", "true"])
  })

  it("falls back to window resize without ResizeObserver", () => {
    ;(global as { ResizeObserver?: unknown }).ResizeObserver = undefined
    const { rail, wrapper } = renderRail()
    setMetrics(rail, { scrollWidth: 640, clientWidth: 580, scrollLeft: 0 })
    act(() => {
      window.dispatchEvent(new Event("resize"))
    })
    expect(edges(wrapper)).toEqual(["false", "true"])
  })

  it("keeps both arrows mounted, so a clicked arrow that runs out of room hides without unmounting", () => {
    const { rail } = renderRail()
    scrollTo(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 180 })
    expect(screen.getByRole("button", { name: "Scroll cuisines right", hidden: true })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Scroll cuisines left", hidden: true })).toBeInTheDocument()
  })

  it("an arrow scrolls the rail by most of its width in that direction", () => {
    const { rail } = renderRail()
    const scrollBy = jest.fn()
    rail.scrollBy = scrollBy as unknown as typeof rail.scrollBy
    setMetrics(rail, { scrollWidth: 600, clientWidth: 420, scrollLeft: 90 })
    fireEvent.click(screen.getByRole("button", { name: "Scroll cuisines right", hidden: true }))
    fireEvent.click(screen.getByRole("button", { name: "Scroll cuisines left", hidden: true }))
    expect(scrollBy.mock.calls[0][0].left).toBeCloseTo(336)
    expect(scrollBy.mock.calls[1][0].left).toBeCloseTo(-336)
  })
})

describe("CuisineRail keyboard focus (PR 1108 review, finding 2)", () => {
  it("brings a focused chip fully into view without moving the page vertically", () => {
    const { container } = render(
      <CuisineRail>
        <button type="button">Grill</button>
        <button type="button">Vegan</button>
      </CuisineRail>,
    )
    const chip = screen.getByRole("button", { name: "Vegan" })
    const scrollIntoView = jest.fn()
    chip.scrollIntoView = scrollIntoView
    act(() => chip.focus())
    expect(container.querySelector("[data-cuisine-rail]")).not.toBeNull()
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.calls[0][0]).toMatchObject({ inline: "nearest", block: "nearest" })
  })
})
