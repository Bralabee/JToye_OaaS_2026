/**
 * OrderAllergenPanel — the pre-submit allergen surface (Phase 31-14, S3, LGL-03 / D-01..D-03).
 *
 * This is the one component in this product that can contribute to injuring someone, so the
 * assertions here are deliberately literal: the legally-operative copy is compared as exact
 * strings rather than by role alone, because a PARAPHRASE is the failure mode and a paraphrase
 * is invisible to a query-by-role test.
 *
 * Three panel states, and conflating any two of them is the defect:
 *
 *   allergenNames === null   -> NOT RECORDED. We do not have the information.
 *   allergenNames === []     -> the vendor declared NONE of the 14 regulated allergens.
 *   allergenNames.length > 0 -> the declared set.
 *
 * `null` and `[]` are DIFFERENT STATEMENTS. Rendering the "no allergens declared" copy for a
 * null would make the panel claim, on the vendor's behalf, something the vendor never said.
 *
 * Every axe scan below is preceded IN THE SAME TEST by a non-vacuity control. An axe scan over
 * an unmounted tree returns zero violations and is indistinguishable from a pass — this project
 * has already paid for that artefact once (see __tests__/axe-instrument.test.tsx).
 */
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { axe, toHaveNoViolations } from "jest-axe"

import {
  OrderAllergenPanel,
  ALLERGEN_ACK_ERROR_COPY,
  ALLERGEN_ACK_LABEL_COPY,
  ALLERGEN_PANEL_SUBLINE_COPY,
  ALLERGEN_PANEL_HEADING_COPY,
  ALLERGEN_PANEL_EMPTY_HEADING_COPY,
  ALLERGEN_PANEL_EMPTY_BODY_COPY,
  ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY,
  ALLERGEN_PANEL_NOT_RECORDED_BODY_COPY,
} from "../order-allergen-panel"
import type { OrderAllergenFlag } from "@/types/api"

expect.extend(toHaveNoViolations)

// jsdom has no layout and axe walks the whole subtree; the default 5s is tight on a loaded runner.
const AXE_TIMEOUT_MS = 30_000

const VENDOR = "Mama Ade's Kitchen"

const DECLARED = ["Milk", "Gluten", "Sesame"]

const FLAGS: OrderAllergenFlag[] = [
  { productName: "Chocolate Tart", allergenBit: 4, allergenName: "Peanuts" },
]

/**
 * D-01 FIXTURE — a customer whose STORED allergen profile names an allergen that is NOT in the
 * order's declared set. The separation matters: if the profile named "Milk" the assertion would
 * pass trivially because "Milk" is legitimately on screen as a DECLARED allergen, and the test
 * would prove nothing. "Peanuts" appears in this fixture and nowhere in DECLARED, so its presence
 * in the DOM could only come from the profile.
 *
 * The customer's stored allergen profile was removed from this surface on 2026-07-30 as special
 * category data with no Article 9 condition. Re-introducing it, even read-only, re-opens that
 * finding.
 */
const CUSTOMER_PROFILE_FIXTURE = {
  allergenRestrictions: 1 << 4, // bit 4 = Peanuts
  allergenRestrictionNames: ["Peanuts"],
}
const PROFILE_SENTINEL = "Peanuts"

/**
 * The panel is a section of a page that has an `h1` and a `main`. Scanning it bare would let
 * page-level axe rules fire on the absence of page-level landmarks, which would be an artefact of
 * the harness rather than a defect in the component.
 */
function Harness({ children }: { children: React.ReactNode }) {
  return (
    <main>
      <h1>Checkout</h1>
      {children}
    </main>
  )
}

function renderPanel(overrides: Partial<React.ComponentProps<typeof OrderAllergenPanel>> = {}) {
  const onAcknowledgedChange = jest.fn()
  const utils = render(
    <Harness>
      <OrderAllergenPanel
        vendorName={VENDOR}
        allergenNames={DECLARED}
        allergenFlags={[]}
        acknowledged={false}
        onAcknowledgedChange={onAcknowledgedChange}
        {...overrides}
      />
    </Harness>
  )
  return { ...utils, onAcknowledgedChange }
}

describe("OrderAllergenPanel — the declared set (S3)", () => {
  it("renders one chip per declared allergen, each carrying the NAME IN WORDS", () => {
    renderPanel({ allergenNames: DECLARED })

    const chips = screen.getAllByTestId("allergen-chip")
    expect(chips).toHaveLength(3)
    expect(chips.map((c) => c.textContent)).toEqual(["Milk", "Gluten", "Sesame"])
  })

  it("names the vendor in the intro, so the declaration is attributed to the kitchen", () => {
    renderPanel()
    expect(
      screen.getByText(
        `These items are prepared by ${VENDOR}. Based on what the kitchen has declared, this order contains:`
      )
    ).toBeInTheDocument()
  })

  it("carries the D-01/D-02 sub-line verbatim", () => {
    renderPanel()
    expect(screen.getByText(ALLERGEN_PANEL_SUBLINE_COPY)).toBeInTheDocument()
    expect(ALLERGEN_PANEL_SUBLINE_COPY).toBe(
      "We do not store your allergies and we cannot check this order against them."
    )
  })

  it("uses the contracted heading when a set is declared", () => {
    renderPanel()
    expect(
      screen.getByRole("heading", { name: ALLERGEN_PANEL_HEADING_COPY, level: 2 })
    ).toBeInTheDocument()
    expect(ALLERGEN_PANEL_HEADING_COPY).toBe("Allergens in this order")
  })
})

describe("OrderAllergenPanel — the three states are NEVER conflated", () => {
  it("EMPTY: still renders the panel, with copy that explicitly denies allergen-free", () => {
    renderPanel({ allergenNames: [] })

    // A silently absent panel is indistinguishable from a panel that failed to render.
    expect(screen.getByTestId("order-allergen-panel")).toBeInTheDocument()
    expect(
      screen.getByRole("heading", { name: ALLERGEN_PANEL_EMPTY_HEADING_COPY, level: 2 })
    ).toBeInTheDocument()
    expect(screen.getByText(ALLERGEN_PANEL_EMPTY_BODY_COPY)).toBeInTheDocument()

    // The exact contracted strings, so a paraphrase reds this test.
    expect(ALLERGEN_PANEL_EMPTY_HEADING_COPY).toBe("No allergens declared for this order")
    expect(ALLERGEN_PANEL_EMPTY_BODY_COPY).toBe(
      "The kitchen has not declared any of the 14 regulated allergens for these items. That is not the same as allergen-free — if you have a serious allergy, tell the kitchen before you order."
    )

    // No chips, and crucially no claim of a declared set.
    expect(screen.queryAllByTestId("allergen-chip")).toHaveLength(0)
  })

  it("NOT RECORDED: does NOT claim 'no allergens declared' — it says the information is missing", () => {
    renderPanel({ allergenNames: null })

    expect(screen.getByTestId("order-allergen-panel")).toBeInTheDocument()
    expect(
      screen.getByRole("heading", { name: ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY, level: 2 })
    ).toBeInTheDocument()
    expect(screen.getByText(ALLERGEN_PANEL_NOT_RECORDED_BODY_COPY)).toBeInTheDocument()

    // THE LOAD-BEARING ASSERTION: the empty-state copy must be ABSENT. `null` means we do not
    // know; rendering "the kitchen has not declared any of the 14" here would put a statement in
    // the vendor's mouth that the vendor never made.
    expect(screen.queryByText(ALLERGEN_PANEL_EMPTY_HEADING_COPY)).not.toBeInTheDocument()
    expect(screen.queryByText(ALLERGEN_PANEL_EMPTY_BODY_COPY)).not.toBeInTheDocument()
    expect(screen.queryAllByTestId("allergen-chip")).toHaveLength(0)
  })

  it("the empty and not-recorded headings are different strings", () => {
    // Guards against a future 'simplification' that points both states at one constant.
    expect(ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY).not.toBe(ALLERGEN_PANEL_EMPTY_HEADING_COPY)
    expect(ALLERGEN_PANEL_NOT_RECORDED_BODY_COPY).not.toBe(ALLERGEN_PANEL_EMPTY_BODY_COPY)
  })

  it("the acknowledgement is present in ALL THREE states — the gate does not depend on the set", () => {
    for (const names of [DECLARED, [], null] as (string[] | null)[]) {
      const { unmount } = renderPanel({ allergenNames: names })
      expect(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })).toBeInTheDocument()
      unmount()
    }
  })
})

describe("OrderAllergenPanel — reconciliation flags are advisory and structurally separate (D-03)", () => {
  it("renders a flag as its own Check line naming the product AND the allergen", () => {
    renderPanel({ allergenNames: DECLARED, allergenFlags: FLAGS })

    const flagLines = screen.getAllByTestId("allergen-flag")
    expect(flagLines).toHaveLength(1)
    expect(flagLines[0]).toHaveTextContent(
      "Check — Chocolate Tart: the ingredients list mentions Peanuts, which the kitchen has not declared for this item. Ask the kitchen before you order."
    )
  })

  it("NEVER promotes a flagged allergen into a declared chip", () => {
    renderPanel({ allergenNames: DECLARED, allergenFlags: FLAGS })

    // The flag names Peanuts. The declaration does not. OR-ing the flag into the declaration
    // would make the platform the author of an allergen claim the vendor never made.
    const chips = screen.getAllByTestId("allergen-chip")
    expect(chips.map((c) => c.textContent)).toEqual(["Milk", "Gluten", "Sesame"])
    expect(chips.map((c) => c.textContent)).not.toContain("Peanuts")
  })

  it("distinguishes a flag from a chip by MORE THAN COLOUR — the word 'Check' and an icon", () => {
    renderPanel({ allergenNames: DECLARED, allergenFlags: FLAGS })

    const flag = screen.getAllByTestId("allergen-flag")[0]
    // Textual differentiator, which survives greyscale and a screen reader.
    expect(flag.textContent).toContain("Check")
    // Non-colour graphical differentiator alongside it.
    expect(within(flag).getByTestId("allergen-flag-icon")).toBeInTheDocument()

    // And the chip carries neither, so the two are not merely differently tinted.
    const chip = screen.getAllByTestId("allergen-chip")[0]
    expect(chip.textContent).not.toContain("Check")
    expect(within(chip).queryByTestId("allergen-flag-icon")).not.toBeInTheDocument()
  })

  it("renders no flag lines when there are none", () => {
    renderPanel({ allergenFlags: [] })
    expect(screen.queryAllByTestId("allergen-flag")).toHaveLength(0)
  })
})

describe("OrderAllergenPanel — the acknowledgement control", () => {
  it("is a real checkbox with a real label, and is NOT pre-checked", () => {
    renderPanel()
    const box = screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
    expect(box).toBeInTheDocument()
    expect(box).not.toBeChecked()
    expect(ALLERGEN_ACK_LABEL_COPY).toBe("I have read the allergen information for this order.")
  })

  it("wires the label to the control by htmlFor/id, not by proximity", () => {
    const { container } = renderPanel()
    const box = screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
    const label = container.querySelector("label[for]") as HTMLLabelElement | null
    expect(label).not.toBeNull()
    expect(label!.getAttribute("for")).toBe(box.getAttribute("id"))
  })

  it("sits in a row that meets the 44px minimum touch target", () => {
    renderPanel()
    // jsdom computes no layout, so the contract is asserted on the class that encodes it.
    expect(screen.getByTestId("allergen-ack-row").className).toContain("min-h-11")
  })

  it("reports a toggle to the parent", async () => {
    const user = userEvent.setup()
    const { onAcknowledgedChange } = renderPanel()

    await user.click(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY }))
    expect(onAcknowledgedChange).toHaveBeenCalledWith(true)
  })

  it("reflects a checked acknowledgement passed down from the parent", () => {
    renderPanel({ acknowledged: true })
    expect(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })).toBeChecked()
  })
})

describe("OrderAllergenPanel — the refusal is announced (T-31-14-03)", () => {
  it("errored: renders role=alert with the exact copy, and wires aria-invalid + aria-describedby", () => {
    renderPanel({ errored: true, errorId: "ack-error" })

    const alert = screen.getByRole("alert")
    expect(alert).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)
    expect(ALLERGEN_ACK_ERROR_COPY).toBe(
      "Confirm you have read the allergen information before placing this order."
    )

    const box = screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
    expect(box).toHaveAttribute("aria-invalid", "true")

    // The describedby must actually POINT AT the alert; an id that resolves to nothing is
    // indistinguishable from no wiring at all for a screen-reader user.
    const describedBy = box.getAttribute("aria-describedby")
    expect(describedBy).toBe("ack-error")
    expect(alert).toHaveAttribute("id", "ack-error")
  })

  it("not errored: NO alert region renders and aria-invalid is not true", () => {
    renderPanel({ errored: false })

    // An always-present alert region is announced on mount and trains users to ignore it.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    const box = screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
    expect(box.getAttribute("aria-invalid")).not.toBe("true")
  })
})

describe("OrderAllergenPanel — D-01: the stored customer allergen profile is never rendered", () => {
  it("does not render any value derived from a customer profile that IS supplied to the test", () => {
    // Non-vacuity: the panel really rendered, and the fixture really carries a profile value.
    const { container } = renderPanel({ allergenNames: DECLARED, allergenFlags: [] })
    expect(screen.getAllByTestId("allergen-chip").length).toBeGreaterThan(0)
    expect(CUSTOMER_PROFILE_FIXTURE.allergenRestrictionNames).toContain(PROFILE_SENTINEL)
    expect(DECLARED).not.toContain(PROFILE_SENTINEL)

    // The assertion itself.
    expect(container.textContent).not.toContain(PROFILE_SENTINEL)
  })

  it("renders none of the phrasings that would imply the platform knows the customer's allergies", () => {
    const { container } = renderPanel()
    const text = (container.textContent ?? "").toLowerCase()

    for (const forbidden of [
      "matches your profile",
      "matches your allergies",
      "safe for you",
      "no allergens present",
      "based on your allergies",
    ]) {
      expect(text).not.toContain(forbidden)
    }
  })

  it("never claims the order is allergen-free in the populated state", () => {
    const { container } = renderPanel({ allergenNames: DECLARED })
    // Scoped to the POPULATED state deliberately: the contracted empty-state body legitimately
    // contains the phrase, as a DENIAL ("That is not the same as allergen-free").
    expect((container.textContent ?? "").toLowerCase()).not.toContain("allergen-free")
  })
})

describe("OrderAllergenPanel — axe, each scan preceded by its own non-vacuity control", () => {
  it(
    "POPULATED: zero violations",
    async () => {
      const { container } = renderPanel({ allergenNames: DECLARED, allergenFlags: FLAGS })

      // NON-VACUITY CONTROL — asserted before the scan, in this same test.
      expect(screen.getAllByTestId("allergen-chip").length).toBeGreaterThan(0)
      expect(screen.getAllByTestId("allergen-flag").length).toBeGreaterThan(0)
      expect(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })).toBeInTheDocument()

      expect(await axe(container)).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )

  it(
    "EMPTY: zero violations",
    async () => {
      const { container } = renderPanel({ allergenNames: [] })

      // NON-VACUITY CONTROL — the empty state has no chips, so the control is its heading.
      expect(
        screen.getByRole("heading", { name: ALLERGEN_PANEL_EMPTY_HEADING_COPY, level: 2 })
      ).toBeInTheDocument()
      expect(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })).toBeInTheDocument()

      expect(await axe(container)).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )

  it(
    "ERRORED: zero violations with the alert region live",
    async () => {
      const { container } = renderPanel({ errored: true, errorId: "ack-error" })

      // NON-VACUITY CONTROL.
      expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)

      expect(await axe(container)).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )
})
