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

import { fireEvent, render, screen, within } from "@testing-library/react"
import {
  RecordedAllergenSet,
  RECORDED_ACK_HEADING_COPY,
  RECORDED_ACK_NONE_COPY,
  RECORDED_ACK_NOT_RECORDED_COPY,
  RECORDED_ALLERGEN_SET_TITLE_COPY,
  RECORDED_SET_HEADING_COPY,
  RECORDED_SET_NOT_LOADED_COPY,
  RECORDED_SET_SHOW_COPY,
  VENDOR_PLACED_COPY,
  ALLERGY_NOTE_SENT_COPY,
  ALLERGY_NOTE_READ_COPY,
  ALLERGY_NOTE_UNREAD_COPY,
  formatAllergyNoteReadAt,
  acknowledgementStatement,
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


/*
 * 31.1-18 (D-07, D-08, D-15): the same block on the tracking pages, which know two more facts —
 * WHO placed the order, and whether the shop READ the customer's allergy note.
 */
describe("RecordedAllergenSet — who placed the order (D-07)", () => {
  it("a shop-placed order states WHY there is no acknowledgement; the recorded set still shows", () => {
    render(
      <RecordedAllergenSet acknowledged={null} recorded={["Milk"]} flags={[]} placedVia="VENDOR" />
    )
    expect(VENDOR_PLACED_COPY).toBe(
      "This order was placed by the shop for you, so no allergen confirmation was recorded."
    )
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(VENDOR_PLACED_COPY)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk")
  })

  it("NULL (not recorded), VENDOR (placed by the shop) and [] (declared none) are three different sentences", () => {
    const texts: string[] = []
    for (const props of [
      { acknowledged: null, placedVia: null },
      { acknowledged: null, placedVia: "VENDOR" as const },
      { acknowledged: [] as string[], placedVia: "STOREFRONT" as const },
    ]) {
      const { unmount } = render(<RecordedAllergenSet recorded={[]} flags={[]} {...props} />)
      texts.push(screen.getByTestId("recorded-ack").textContent ?? "")
      unmount()
    }
    expect(texts[0]).toContain(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(texts[1]).toContain(VENDOR_PLACED_COPY)
    expect(texts[2]).toContain(RECORDED_ACK_NONE_COPY)
    expect(new Set(texts).size).toBe(3)
    // Neither absence is ever "none".
    expect(texts[0]).not.toMatch(/none|no allergens/i)
    expect(texts[1]).not.toMatch(/none|no allergens/i)
  })

  it("acknowledgementStatement is the one source both surfaces quote", () => {
    expect(acknowledgementStatement(null, "VENDOR")).toEqual({ kind: "vendor", text: VENDOR_PLACED_COPY })
    expect(acknowledgementStatement(null, null)).toEqual({ kind: "not-recorded", text: RECORDED_ACK_NOT_RECORDED_COPY })
    expect(acknowledgementStatement(undefined, undefined)).toEqual({
      kind: "not-recorded",
      text: RECORDED_ACK_NOT_RECORDED_COPY,
    })
    expect(acknowledgementStatement([], "STOREFRONT")).toEqual({ kind: "none", text: RECORDED_ACK_NONE_COPY })
    expect(acknowledgementStatement(["Gluten", "Milk"], "STOREFRONT")).toEqual({
      kind: "list",
      names: ["Gluten", "Milk"],
    })
  })
})

describe("RecordedAllergenSet — a recorded set this response does not carry", () => {
  it("recorded undefined (the history list) says NOT LOADED, never 'not recorded' and never 'none'", () => {
    const onShow = jest.fn()
    render(
      <RecordedAllergenSet acknowledged={["Milk"]} recorded={undefined} flags={null} onShowRecorded={onShow} />
    )
    const set = screen.getByTestId("recorded-set")
    expect(set).toHaveTextContent(`${RECORDED_SET_HEADING_COPY} ${RECORDED_SET_NOT_LOADED_COPY}`)
    expect(set).not.toHaveTextContent(ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY)
    expect(set).not.toHaveTextContent(ALLERGEN_PANEL_EMPTY_HEADING_COPY)
    fireEvent.click(screen.getByRole("button", { name: RECORDED_SET_SHOW_COPY }))
    expect(onShow).toHaveBeenCalledTimes(1)
  })
})

describe("RecordedAllergenSet — the allergy note (D-15)", () => {
  it("says the note was sent to the named shop, and that it is not read yet", () => {
    render(
      <RecordedAllergenSet
        acknowledged={["Milk"]}
        recorded={["Milk"]}
        flags={[]}
        shopName="Mama Ade's Kitchen"
        allergyNoteProvided
        allergyNoteAcknowledgedAt={null}
      />
    )
    const note = screen.getByTestId("allergy-note-status")
    expect(note).toHaveTextContent("Your allergy note was sent to Mama Ade's Kitchen.")
    expect(note).toHaveTextContent(ALLERGY_NOTE_SENT_COPY("Mama Ade's Kitchen"))
    expect(note).toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
    expect(note).not.toHaveTextContent(/Read by the shop/)
  })

  it("once acknowledged, says when the shop read it (en-GB, Europe/London)", () => {
    // 17:05Z on 6 Oct 2026 is 18:05 BST.
    render(
      <RecordedAllergenSet
        acknowledged={["Milk"]}
        recorded={["Milk"]}
        flags={[]}
        shopName="Mama Ade's Kitchen"
        allergyNoteProvided
        allergyNoteAcknowledgedAt="2026-10-06T17:05:00Z"
      />
    )
    const note = screen.getByTestId("allergy-note-status")
    expect(note).toHaveTextContent("Your allergy note was sent to Mama Ade's Kitchen.")
    expect(note).toHaveTextContent(/Read by the shop at 18:05/)
    expect(note).not.toHaveTextContent(ALLERGY_NOTE_UNREAD_COPY)
  })

  it("renders no note line when the customer sent none (false, null or absent)", () => {
    render(<RecordedAllergenSet acknowledged={["Milk"]} recorded={["Milk"]} flags={[]} shopName="X" />)
    expect(screen.queryByTestId("allergy-note-status")).not.toBeInTheDocument()
  })

  it("formats the read time in London, adding the London date when it is not today there", () => {
    const now = new Date("2026-10-06T20:00:00Z") // 21:00 BST, 6 Oct
    expect(formatAllergyNoteReadAt("2026-10-06T17:05:00Z", now)).toBe("18:05")
    expect(ALLERGY_NOTE_READ_COPY(formatAllergyNoteReadAt("2026-10-06T17:05:00Z", now))).toBe(
      "Read by the shop at 18:05."
    )
    // 23:30Z on 5 Oct is 00:30 BST on 6 Oct — TODAY in London, whatever the host zone says.
    expect(formatAllergyNoteReadAt("2026-10-05T23:30:00Z", now)).toBe("00:30")
    // 22:30Z on 5 Oct is 23:30 BST on 5 Oct — yesterday in London.
    expect(formatAllergyNoteReadAt("2026-10-05T22:30:00Z", now)).toBe("23:30 on 5 Oct")
    // GMT after the clocks go back (25 Oct 2026): 18:05Z is 18:05 GMT.
    expect(formatAllergyNoteReadAt("2026-11-02T18:05:00Z", new Date("2026-11-02T20:00:00Z"))).toBe("18:05")
  })
})
