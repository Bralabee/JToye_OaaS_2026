/**
 * RecordedAllergenSet — "what was recorded on your order" (Phase 31.1-15, D-08; Phase 31 D-03).
 *
 * The confirmation is where the customer learns what the platform will say they acknowledged and
 * what the kitchen will work from. Three statements, never merged:
 *
 *   acknowledged — the set they ticked to confirm they had read (orders.allergen_ack_mask);
 *   recorded     — the order-line snapshot union the kitchen works from (V63);
 *   flags        — ADVISORY "Check" lines, beside the declaration, never inside it.
 *
 * And for each set, THREE states that must never be conflated: null (not recorded), [] (declared
 * none of the 14) and a list. `null` rendered as "none" would put a claim in the kitchen's mouth
 * it never made — the direction that injures someone.
 */

import { render, screen, within } from "@testing-library/react"
import {
  RecordedAllergenSet,
  RECORDED_ACK_HEADING_COPY,
  RECORDED_ACK_NONE_COPY,
  RECORDED_ACK_NOT_RECORDED_COPY,
  RECORDED_ALLERGEN_SET_TITLE_COPY,
  RECORDED_SET_HEADING_COPY,
} from "@/components/storefront/recorded-allergen-set"
import {
  ALLERGEN_PANEL_EMPTY_HEADING_COPY,
  ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY,
  allergenFlagCopy,
} from "@/components/storefront/order-allergen-panel"

const FLAG = { productName: "Fish Pie", allergenBit: 6, allergenName: "Milk" }

describe("the copy is the exported constants, verbatim", () => {
  it("pins the wording a regulator may read", () => {
    expect(RECORDED_ACK_HEADING_COPY).toBe("You confirmed you had read:")
    expect(RECORDED_SET_HEADING_COPY).toBe("Recorded on your order:")
    expect(typeof RECORDED_ACK_NOT_RECORDED_COPY).toBe("string")
    expect(typeof RECORDED_ACK_NONE_COPY).toBe("string")
  })
})

describe("RecordedAllergenSet — a declared set", () => {
  it("states the acknowledged set and the recorded set as two separate statements", () => {
    render(<RecordedAllergenSet acknowledged={["Milk"]} recorded={["Milk"]} flags={[]} />)

    expect(screen.getByTestId("recorded-ack")).toHaveTextContent("You confirmed you had read: Milk")
    expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk")
    // Rendered = constant, not a paraphrase of it.
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(`${RECORDED_ACK_HEADING_COPY} Milk`)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(`${RECORDED_SET_HEADING_COPY} Milk`)
  })

  it("lists several names in the order given (AllergenCatalog bit order from the server)", () => {
    render(
      <RecordedAllergenSet
        acknowledged={["Gluten", "Fish", "Peanuts", "Milk"]}
        recorded={["Gluten", "Fish", "Peanuts", "Milk"]}
        flags={[]}
      />
    )
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(
      "Recorded on your order: Gluten, Fish, Peanuts, Milk"
    )
  })

  it("is a titled region with the allergen box's warning icon", () => {
    render(<RecordedAllergenSet acknowledged={["Milk"]} recorded={["Milk"]} flags={[]} />)
    const region = screen.getByRole("region", { name: RECORDED_ALLERGEN_SET_TITLE_COPY })
    expect(region).toBeInTheDocument()
    expect(within(region).getByTestId("recorded-allergen-icon")).toHaveAttribute("aria-hidden", "true")
  })
})

describe("RecordedAllergenSet — null, [] and a list are three different statements", () => {
  it("acknowledged null (vendor-entered / pre-V69 order) says NOT RECORDED, never 'none'", () => {
    render(<RecordedAllergenSet acknowledged={null} recorded={["Milk"]} flags={[]} />)
    const ack = screen.getByTestId("recorded-ack")
    expect(ack).toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(ack.textContent ?? "").not.toMatch(/none|no allergens/i)
  })

  it("acknowledged [] says the customer read that the kitchen declared none — DIFFERENT copy from null", () => {
    const { unmount } = render(<RecordedAllergenSet acknowledged={null} recorded={[]} flags={[]} />)
    const nullText = screen.getByTestId("recorded-ack").textContent
    unmount()

    render(<RecordedAllergenSet acknowledged={[]} recorded={[]} flags={[]} />)
    const emptyText = screen.getByTestId("recorded-ack").textContent

    expect(emptyText).toContain(RECORDED_ACK_NONE_COPY)
    expect(nullText).toContain(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(emptyText).not.toBe(nullText)
  })

  it("recorded [] uses the panel's declared-none copy; recorded null its not-recorded copy", () => {
    const { unmount } = render(<RecordedAllergenSet acknowledged={[]} recorded={[]} flags={[]} />)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(ALLERGEN_PANEL_EMPTY_HEADING_COPY)
    unmount()

    render(<RecordedAllergenSet acknowledged={null} recorded={null} flags={null} />)
    const set = screen.getByTestId("recorded-set")
    expect(set).toHaveTextContent(ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY)
    expect(set).not.toHaveTextContent(ALLERGEN_PANEL_EMPTY_HEADING_COPY)
  })
})

describe("RecordedAllergenSet — reconciliation flags are separate advisory lines", () => {
  it("renders each flag as its own 'Check' line, outside both sets", () => {
    render(<RecordedAllergenSet acknowledged={["Gluten"]} recorded={["Gluten"]} flags={[FLAG]} />)

    const flags = screen.getAllByTestId("recorded-allergen-flag")
    expect(flags).toHaveLength(1)
    expect(flags[0]).toHaveTextContent(allergenFlagCopy(FLAG))
    expect(flags[0]).toHaveTextContent(/^Check/)
    // Never merged into a declared statement.
    expect(screen.getByTestId("recorded-set")).not.toHaveTextContent("Milk")
    expect(screen.getByTestId("recorded-ack")).not.toHaveTextContent("Milk")
  })

  it("renders no flag line when there are none (null or [])", () => {
    render(<RecordedAllergenSet acknowledged={["Gluten"]} recorded={["Gluten"]} flags={null} />)
    expect(screen.queryAllByTestId("recorded-allergen-flag")).toHaveLength(0)
  })
})
