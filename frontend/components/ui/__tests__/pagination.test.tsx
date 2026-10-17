import { fireEvent, render, screen, within } from "@testing-library/react"

import { Pagination, paginationWindow } from "@/components/ui/pagination"

/**
 * G-37-6 defect 3 (UAT test 6): the shared Pagination was a fixed 320 px row of nine 32 px
 * buttons, so in a 308 px card at 390 px the last-page button sat on the screen edge outside
 * the card, and every target was under the phase's 44 px minimum (UI-SPEC Spacing exceptions).
 *
 * The controls now choose themselves from the component's OWN width (container queries):
 * previous, "Page N of M" and next always; first and last from 19rem; a three-page window
 * from 21rem in place of the status; the five-page window from 27rem.
 *
 * jsdom has no layout, so these tests assert what jsdom can observe: the declarations each
 * control carries, the windows, and the goods. Whether Tailwind EMITS each container rule is
 * checked against the generated CSS (jsdom cannot see emission), and the live widths at
 * 390/768/1280 are measured by 37-59.
 */

const TOTAL_ELEMENTS = 475 // 24 pages of 20
const TOTAL_PAGES = 24

function renderPagination(props: Partial<React.ComponentProps<typeof Pagination>> = {}) {
  const onPageChange = jest.fn()
  const utils = render(
    <Pagination
      currentPage={0}
      totalPages={TOTAL_PAGES}
      totalElements={TOTAL_ELEMENTS}
      pageSize={20}
      onPageChange={onPageChange}
      {...props}
    />
  )
  return { onPageChange, ...utils }
}

const nav = () => screen.getByRole("navigation", { name: "Pagination" })
const button = (name: string) => within(nav()).getByRole("button", { name })
const tokens = (el: Element) => el.className.split(/\s+/).filter(Boolean)
const containerTokens = (el: Element) => tokens(el).filter((t) => t.startsWith("[@container"))

describe("Pagination fits the card it sits in (G-37-6)", () => {
  it("root: an inline-size container that wraps; the nav is named and 'Showing' is kept", () => {
    renderPagination()

    const root = nav().parentElement as HTMLElement
    expect(root).toHaveClass("[container-type:inline-size]", "flex", "flex-wrap")
    expect(screen.getByText("Showing 1-20 of 475")).toBeInTheDocument()
  })

  it("size: every control is at least 44 x 44 px (h-11 and w-11 or min-w-11), none 32 px", () => {
    renderPagination({ currentPage: 10 })

    const buttons = within(nav()).getAllByRole("button", { hidden: true })
    expect(buttons.length).toBeGreaterThanOrEqual(9)
    for (const b of buttons) {
      const t = tokens(b)
      expect({ name: b.getAttribute("aria-label"), h8: t.includes("h-8"), w8: t.includes("w-8") }).toEqual({
        name: b.getAttribute("aria-label"),
        h8: false,
        w8: false,
      })
      expect(t).toContain("h-11")
      expect(t.includes("w-11") || t.includes("min-w-11")).toBe(true)
    }
  })

  it("tiers: first and last appear from 19rem; previous and next are always shown", () => {
    renderPagination({ currentPage: 10 })

    for (const name of ["Go to first page", "Go to last page"]) {
      expect(button(name)).toHaveClass("hidden", "[@container_(min-width:19rem)]:inline-flex")
    }
    for (const name of ["Go to previous page", "Go to next page"]) {
      expect(button(name)).not.toHaveClass("hidden")
      expect(containerTokens(button(name))).toEqual([])
    }
  })

  it("compact status: 'Page 11 of 24' below 21rem, outside every button", () => {
    renderPagination({ currentPage: 10 })

    const status = screen.getByText("Page 11 of 24")
    expect(status.textContent).toBe("Page 11 of 24")
    expect(status).toHaveClass("[@container_(min-width:21rem)]:hidden")
    expect(status.closest("button")).toBeNull()
  })

  it("windows at page 11: three pages from 21rem, five from 27rem; only page 11 is current", () => {
    renderPagination({ currentPage: 10 })

    for (const n of [10, 11, 12]) {
      expect(button(`Go to page ${n}`)).toHaveClass("hidden", "[@container_(min-width:21rem)]:inline-flex")
    }
    for (const n of [9, 13]) {
      expect(button(`Go to page ${n}`)).toHaveClass("hidden", "[@container_(min-width:27rem)]:inline-flex")
    }
    expect(within(nav()).queryByRole("button", { name: "Go to page 8" })).toBeNull()
    expect(within(nav()).queryByRole("button", { name: "Go to page 14" })).toBeNull()

    expect(button("Go to page 11")).toHaveAttribute("aria-current", "page")
    const current = within(nav())
      .getAllByRole("button", { hidden: true })
      .filter((b) => b.hasAttribute("aria-current"))
    expect(current.map((b) => b.getAttribute("aria-label"))).toEqual(["Go to page 11"])
  })

  it("window edges: clamped to the ends, and the three-page window sits inside the five", () => {
    const cases: Array<[number, number, number[], number[]]> = [
      [0, 24, [0, 1, 2, 3, 4], [0, 1, 2]],
      [23, 24, [19, 20, 21, 22, 23], [21, 22, 23]],
      [1, 2, [0, 1], [0, 1]],
      [10, 24, [8, 9, 10, 11, 12], [9, 10, 11]],
    ]
    for (const [current, total, five, three] of cases) {
      expect(paginationWindow(current, total, 5)).toEqual(five)
      expect(paginationWindow(current, total, 3)).toEqual(three)
      const outer = new Set(paginationWindow(current, total, 5))
      expect(paginationWindow(current, total, 3).every((p) => outer.has(p))).toBe(true)
    }
  })

  it("goods: one page renders nothing", () => {
    const { container } = renderPagination({ totalPages: 1, totalElements: 12 })
    expect(container).toBeEmptyDOMElement()
  })

  it("goods: pressing next at page 1 asks for page 2 (index 1), once", () => {
    const { onPageChange } = renderPagination({ currentPage: 0 })

    fireEvent.click(button("Go to next page"))
    expect(onPageChange).toHaveBeenCalledTimes(1)
    expect(onPageChange).toHaveBeenCalledWith(1)
  })

  it("goods: first and previous are disabled on the first page, next and last on the last", () => {
    const { unmount } = renderPagination({ currentPage: 0 })
    expect(button("Go to first page")).toBeDisabled()
    expect(button("Go to previous page")).toBeDisabled()
    expect(button("Go to next page")).toBeEnabled()
    expect(button("Go to last page")).toBeEnabled()
    unmount()

    renderPagination({ currentPage: TOTAL_PAGES - 1 })
    expect(button("Go to next page")).toBeDisabled()
    expect(button("Go to last page")).toBeDisabled()
    expect(button("Go to first page")).toBeEnabled()
    expect(button("Go to previous page")).toBeEnabled()
    expect(screen.getByText("Showing 461-475 of 475")).toBeInTheDocument()
  })
})
