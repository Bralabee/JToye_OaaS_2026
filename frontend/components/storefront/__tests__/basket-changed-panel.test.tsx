/**
 * C1 — the "Your basket has changed" panel (Phase 37-34, UI-SPEC § C1 and § Copywriting C1-C4;
 * D-16; RWO-007 / UXT-007).
 *
 * The panel is purely presentational: it renders the server's `changes[]` (37-33) one row each with
 * that kind's exact sentence, names a nameless REMOVED entry from the basket's own copy, says when the
 * allergens moved too, and ends with "Accept changes". Until that is pressed the page refuses Place
 * order with a role=alert line; after it, the panel collapses to the one-line note.
 *
 * These are the C1 rows of the UI-SPEC's state table (populated, partial, zero-one-many, error,
 * and the 375px / 130% backstops as a jest render: jsdom lays nothing out, so the held-out claim is
 * that every row is present IN FULL and carries no class that clips or refuses to wrap it; the visual
 * pass is the F6 instrument in 37-38).
 */

import { createRef } from "react"
import { render, screen, fireEvent, within } from "@testing-library/react"
import {
  ALLERGENS_ALSO_CHANGED_COPY,
  BASKET_CHANGED_ACCEPT_LABEL,
  BASKET_CHANGED_HEADING_COPY,
  BASKET_CHANGED_REFUSAL_COPY,
  BASKET_UPDATED_NOTE_COPY,
  BasketChangedPanel,
  basketChangeCopy,
} from "@/components/storefront/basket-changed-panel"
import type { BasketChange } from "@/types/storefront"

/** The basket as the customer saw it: the only place a REMOVED entry's name can come from. */
const BASKET_NAMES: Record<string, string> = { p1: "Suya", p2: "Jollof Rice", p3: "Puff Puff" }
const nameFor = (productId: string | undefined) => (productId ? (BASKET_NAMES[productId] ?? null) : null)

const PRICE: BasketChange = { kind: "PRICE_CHANGED", productId: "p1", name: "Suya", expected: 900, current: 1400 }
const UNAVAILABLE: BasketChange = { kind: "UNAVAILABLE", productId: "p2", name: "Jollof Rice" }
const SOLD_OUT_SOME: BasketChange = { kind: "SOLD_OUT", productId: "p3", name: "Puff Puff", requested: 3, available: 2 }
const SOLD_OUT_NONE: BasketChange = { kind: "SOLD_OUT", productId: "p3", name: "Puff Puff", requested: 3, available: 0 }
const REMOVED: BasketChange = { kind: "REMOVED", productId: "p2" }
const BELOW_MINIMUM: BasketChange = {
  kind: "BELOW_MINIMUM",
  minimum: 1000,
  subtotal: 300,
  detail: "Order is below the minimum of £10.00",
}
const TOTAL: BasketChange = { kind: "TOTAL_CHANGED", expected: 900, current: 1400 }

function renderPanel(overrides: Partial<React.ComponentProps<typeof BasketChangedPanel>> = {}) {
  const onAccept = jest.fn()
  const utils = render(
    <BasketChangedPanel
      changes={[PRICE]}
      nameFor={nameFor}
      allergensChanged={false}
      accepted={false}
      onAccept={onAccept}
      refused={false}
      {...overrides}
    />
  )
  return { ...utils, onAccept }
}

const panel = () => screen.getByTestId("basket-changed-panel")
const rows = () => screen.getAllByTestId("basket-change-row")

describe("BasketChangedPanel: the populated state (UI-SPEC § C1)", () => {
  it("is a role=alert container with the C1 styling, the icon, and the focusable h2 heading", () => {
    renderPanel()
    const container = panel()
    expect(container).toHaveAttribute("role", "alert")
    for (const cls of ["rounded-xl", "border", "border-orange-700", "bg-orange-50", "p-4"]) {
      expect(container).toHaveClass(cls)
    }
    const heading = within(container).getByRole("heading", { level: 2, name: BASKET_CHANGED_HEADING_COPY })
    expect(heading).toHaveAttribute("tabindex", "-1")
    expect(container.querySelector("svg.lucide-refresh-ccw")).not.toBeNull()
  })

  it("moves focus to the heading when it appears", () => {
    renderPanel()
    expect(document.activeElement).toBe(screen.getByRole("heading", { level: 2, name: BASKET_CHANGED_HEADING_COPY }))
  })

  it("F14: a PRICE_CHANGED row reads 'Suya · £9.00 → £14.00', with 'was £9.00, now £14.00' for screen readers in place of the arrow", () => {
    renderPanel()
    const [row] = rows()
    expect(row).toHaveTextContent("Suya · £9.00 → £14.00")
    // The arrow is visual only; assistive tech hears the sentence instead.
    const sr = within(row).getByText("was £9.00, now £14.00")
    expect(sr).toHaveClass("sr-only")
    const arrow = within(row).getByText(/→/)
    expect(arrow).toHaveAttribute("aria-hidden", "true")
  })

  it("ends with an outline 'Accept changes' button that reports the press", () => {
    const { onAccept } = renderPanel()
    const accept = screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL })
    for (const cls of ["border-2", "border-oxblood", "text-oxblood", "bg-white", "h-11"]) {
      expect(accept).toHaveClass(cls)
    }
    fireEvent.click(accept)
    expect(onAccept).toHaveBeenCalledTimes(1)
  })

  it("forwards the accept ref so the page can move focus to the button on a refusal", () => {
    const acceptRef = createRef<HTMLButtonElement>()
    renderPanel({ acceptRef })
    expect(acceptRef.current).toBe(screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL }))
  })
})

describe("BasketChangedPanel: every change kind has its exact sentence (UI-SPEC § C1 partial)", () => {
  it.each<[string, BasketChange, string]>([
    ["UNAVAILABLE", UNAVAILABLE, "Jollof Rice · no longer available, removed from your basket"],
    [
      "SOLD_OUT with some left",
      SOLD_OUT_SOME,
      "Puff Puff · only 2 left, we've changed your quantity from 3 to 2",
    ],
    ["SOLD_OUT with none left", SOLD_OUT_NONE, "Puff Puff · sold out, removed from your basket"],
    ["REMOVED, named from the basket's own copy", REMOVED, "Jollof Rice · no longer on this shop's menu, removed from your basket"],
    ["BELOW_MINIMUM", BELOW_MINIMUM, "Your basket is now below this shop's £10.00 minimum. Add £7.00 more to order."],
    ["TOTAL_CHANGED", TOTAL, "Total was £9.00, now £14.00"],
  ])("%s", (_kind, change, sentence) => {
    renderPanel({ changes: [change] })
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toHaveTextContent(sentence)
  })

  it("a REMOVED entry carries no name on the wire (37-33), so the basket's copy names it — never 'undefined' or 'null'", () => {
    renderPanel({ changes: [REMOVED] })
    expect(rows()[0]).toHaveTextContent(/^Jollof Rice · /)
    expect(panel()).not.toHaveTextContent(/undefined|null/)
  })

  it("basketChangeCopy prefers the server's name and falls back to the basket, then to 'An item'", () => {
    expect(basketChangeCopy(PRICE, () => "Basket name")?.text).toBe("Suya · £9.00 → £14.00")
    expect(basketChangeCopy(REMOVED, nameFor)?.text).toMatch(/^Jollof Rice · /)
    expect(basketChangeCopy({ kind: "REMOVED", productId: "p9" }, nameFor)?.text).toMatch(/^An item · /)
  })

  it("says when the allergens changed too, and not otherwise", () => {
    const { unmount } = renderPanel({ allergensChanged: true })
    expect(screen.getByText(ALLERGENS_ALSO_CHANGED_COPY)).toBeInTheDocument()
    unmount()
    renderPanel({ allergensChanged: false })
    expect(screen.queryByText(ALLERGENS_ALSO_CHANGED_COPY)).not.toBeInTheDocument()
  })
})

describe("BasketChangedPanel: zero-one-many under the same heading (UI-SPEC § C1)", () => {
  it("one change: one row", () => {
    renderPanel({ changes: [PRICE] })
    expect(rows()).toHaveLength(1)
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(BASKET_CHANGED_HEADING_COPY)
  })

  it("many changes: one row each, in the server's order, the same heading", () => {
    renderPanel({ changes: [PRICE, SOLD_OUT_SOME, REMOVED, BELOW_MINIMUM, TOTAL] })
    const texts = rows().map((r) => r.textContent ?? "")
    expect(texts).toHaveLength(5)
    expect(texts[0]).toContain("Suya · £9.00 → £14.00")
    expect(texts[1]).toContain("Puff Puff · only 2 left")
    expect(texts[2]).toContain("Jollof Rice · no longer on this shop's menu")
    expect(texts[3]).toContain("below this shop's £10.00 minimum")
    expect(texts[4]).toContain("Total was £9.00, now £14.00")
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(BASKET_CHANGED_HEADING_COPY)
  })
})

describe("BasketChangedPanel: the refusal and the collapsed note (UI-SPEC § C1 error / re-confirmation)", () => {
  it("refused: a role=alert line with the refusal copy, beside the panel", () => {
    renderPanel({ refused: true, refusalId: "basket-changed-refusal" })
    const line = screen.getByText(BASKET_CHANGED_REFUSAL_COPY)
    expect(line).toHaveAttribute("role", "alert")
    expect(line).toHaveAttribute("id", "basket-changed-refusal")
    // The panel's own list is still there: the customer reads the changes, then presses Accept.
    expect(rows()).toHaveLength(1)
  })

  it("not refused: no refusal line", () => {
    renderPanel({ refused: false })
    expect(screen.queryByText(BASKET_CHANGED_REFUSAL_COPY)).not.toBeInTheDocument()
  })

  it("accepted: collapses to 'Basket updated. Check the new total before you place your order.' with no list and no button", () => {
    renderPanel({ accepted: true })
    const note = screen.getByText(BASKET_UPDATED_NOTE_COPY)
    expect(note).toHaveAttribute("role", "status")
    expect(screen.queryAllByTestId("basket-change-row")).toHaveLength(0)
    expect(screen.queryByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL })).not.toBeInTheDocument()
    expect(screen.queryByRole("heading", { level: 2 })).not.toBeInTheDocument()
  })
})

describe("BasketChangedPanel: rows wrap at 375px and at 360px/130% (UI-SPEC § C1 backstops, jest half)", () => {
  it("renders a long item name in full in a row that carries no clipping or no-wrap class", () => {
    const longName = "Jollof rice with grilled plantain, suya and extra pepper sauce" // 60+ chars
    expect(longName.length).toBeGreaterThanOrEqual(60)
    renderPanel({ changes: [{ kind: "PRICE_CHANGED", productId: "p9", name: longName, expected: 900, current: 1400 }] })
    const [row] = rows()
    expect(row).toHaveTextContent(`${longName} · £9.00 → £14.00`)
    for (const el of [row, ...Array.from(row.querySelectorAll("*"))]) {
      for (const cls of ["truncate", "whitespace-nowrap", "overflow-hidden", "text-ellipsis"]) {
        expect(el).not.toHaveClass(cls)
      }
    }
    expect(row).toHaveClass("break-words")
    // Prices are tabular so a column of rows lines up; the item name is the emphasised part.
    expect(within(row).getByText(longName)).toHaveClass("font-semibold")
  })
})
