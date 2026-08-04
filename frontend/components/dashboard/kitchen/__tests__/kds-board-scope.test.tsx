/**
 * #450 sub-item 5d — the board says whose tickets it is showing.
 *
 * Measured before this existed: the sidebar switcher read "All shops", the board
 * issued exactly one `?shopId=…` request, and every ticket on screen belonged to one
 * of three shops. Nothing on the page said so.
 */
import { render, screen } from "@testing-library/react"
import { KdsAllShopsNotice, KdsBoardShopName } from "../kds-board-scope"

describe("KdsBoardShopName", () => {
  it("names the shop whose tickets are on the board", () => {
    render(<KdsBoardShopName shopName="Brixton Village Grill" />)
    expect(screen.getByTestId("kds-board-shop")).toHaveTextContent(
      "Showing tickets for Brixton Village Grill"
    )
  })

  it("says no shop is selected rather than rendering an empty claim", () => {
    render(<KdsBoardShopName shopName={null} />)
    expect(screen.getByTestId("kds-board-shop")).toHaveTextContent("No shop selected")
  })
})

describe("KdsAllShopsNotice", () => {
  it("explains the mismatch: All shops selected, one shop shown, and which one", () => {
    render(<KdsAllShopsNotice shopName="Brixton Village Grill" shopCount={3} />)
    const notice = screen.getByTestId("kds-all-shops-notice")
    expect(notice).toHaveTextContent("All shops")
    expect(notice).toHaveTextContent("one shop at a time")
    expect(notice).toHaveTextContent("Brixton Village Grill")
  })

  it("counts the shops NOT on screen, so the gap is quantified not implied", () => {
    render(<KdsAllShopsNotice shopName="Brixton Village Grill" shopCount={3} />)
    expect(screen.getByTestId("kds-all-shops-notice")).toHaveTextContent(
      "your other 2 shops are not on this screen"
    )
  })

  it("drops the count entirely for a vendor with exactly two shops", () => {
    // "your other 1 shop" is the `"1 items"` defect from #450 item 5 in another
    // costume; the number is only worth printing when it is greater than one.
    render(<KdsAllShopsNotice shopName="Brixton Village Grill" shopCount={2} />)
    const notice = screen.getByTestId("kds-all-shops-notice")
    expect(notice).toHaveTextContent("your other shop are not on this screen")
    expect(notice).not.toHaveTextContent("other 1 shop")
  })

  it("claims nothing about other shops when there is only one", () => {
    // With one shop, "All shops" and "this shop" are the same set — there is no
    // mismatch to explain and the notice must not invent one.
    render(<KdsAllShopsNotice shopName="Only Shop" shopCount={1} />)
    const notice = screen.getByTestId("kds-all-shops-notice")
    expect(notice).toHaveTextContent("Only Shop")
    expect(notice).not.toHaveTextContent("not on this screen")
  })

  it("renders nothing before a shop has been resolved", () => {
    render(<KdsAllShopsNotice shopName={null} shopCount={3} />)
    expect(screen.queryByTestId("kds-all-shops-notice")).not.toBeInTheDocument()
  })

  it("is a status, not an alert — nothing here is broken or urgent", () => {
    render(<KdsAllShopsNotice shopName="Brixton Village Grill" shopCount={3} />)
    expect(screen.getByTestId("kds-all-shops-notice")).toHaveAttribute("role", "status")
  })
})
