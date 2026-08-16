/**
 * 31-15 (LGL-03 / D-04, UI-SPEC S4) — the KDS order-level allergen banner and the
 * per-item badge.
 *
 * WHAT THESE ASSERT, AND WHY IN THIS SHAPE. This is the surface where an allergen
 * warning is acted on by a person holding food, so the failure mode under test is a
 * MISSED warning, not a cosmetic one. Three properties carry that, and each is written
 * so it can fail:
 *
 *  1. The banner carries the COMPLETE set. Only the per-item badge truncates, and it
 *     is allowed to only because the banner above it does not.
 *  2. The signal is never carried by colour alone. Every assertion about the warning
 *     reads `textContent` — a regression that keeps the amber fill and drops the words
 *     is invisible to a screenshot and fails here.
 *  3. THREE states, not two. `null` = NOT RECORDED, `[]` = the vendor declared none of
 *     the 14, and those are different statements. "Absence is the signal" makes an
 *     empty render mean "none declared", so a not-recorded order rendering nothing
 *     would silently claim to be allergen-free. Both directions are asserted in one
 *     test, so collapsing the states in EITHER direction fails.
 *
 * `textContent` concatenates, so text assertions are scoped to the banner element
 * rather than the document — a card-level match would be satisfied by a button label.
 */
import { render, screen } from "@testing-library/react"
import { OrderAllergenBanner } from "../order-allergen-banner"
import { ItemAllergenBadge } from "../item-allergen-badge"
import type { OrderAllergenFlag } from "@/types/api"

/** Five is past any sane truncation point — three names plus "+2" is the badge's shape. */
const FIVE = ["Gluten", "Milk", "Eggs", "Sesame", "Soya"]

const FLAG: OrderAllergenFlag = {
  productName: "Sourdough Loaf",
  allergenBit: 10,
  allergenName: "Sesame",
}

/** Every class on the element and everything under it. */
function classesIn(el: HTMLElement): string[] {
  const nodes = [el, ...Array.from(el.querySelectorAll("*"))]
  return nodes.flatMap((n) => (n.getAttribute("class") || "").split(/\s+/)).filter(Boolean)
}

describe("OrderAllergenBanner", () => {
  it("lists EVERY declared allergen — the banner is the one place the full set is guaranteed", () => {
    render(<OrderAllergenBanner allergenNames={FIVE} allergenFlags={[]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    for (const name of FIVE) expect(banner).toHaveTextContent(name)
    // ...and it did not truncate with a "+2" the way the per-item badge is allowed to.
    expect(banner.textContent).not.toMatch(/\+\d/)
  })

  it("says the word ALLERGENS and each allergen in WORDS, not only in amber", () => {
    // The colour-only regression: drop the icon and the label, keep the fill, and a
    // visual review sees an amber box that still 'looks like' a warning. This fails.
    render(<OrderAllergenBanner allergenNames={["Peanuts"]} allergenFlags={[]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    expect(banner).toHaveTextContent("ALLERGENS")
    expect(banner).toHaveTextContent("Peanuts")
  })

  it("carries an icon ALONGSIDE the words, and the icon is decorative", () => {
    // Icon AND words (UI-SPEC S4 "never colour-only"). The icon is aria-hidden because
    // the word carries the meaning — a screen reader announcing "triangle" adds nothing.
    render(<OrderAllergenBanner allergenNames={["Peanuts"]} allergenFlags={[]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    const icon = banner.querySelector("svg")
    expect(icon).not.toBeNull()
    expect(icon).toHaveAttribute("aria-hidden", "true")
  })

  it("wears the contracted treatment: solid amber-800 fill, white text, full width", () => {
    render(<OrderAllergenBanner allergenNames={["Peanuts"]} allergenFlags={[]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    // Solid fill, not a tint — a tint does not carry at 1.5 m (UI-SPEC S4).
    expect(banner).toHaveClass("bg-amber-800")
    expect(banner).toHaveClass("text-white")
    expect(banner).toHaveClass("w-full")
    expect(banner).toHaveClass("rounded-md")
    expect(banner).toHaveClass("px-3")
    expect(banner).toHaveClass("py-2")
  })

  it("sizes the ALLERGENS label at the contracted 20px/600 uppercase, never 18px and never bold", () => {
    // 20px is the existing base Heading step. An 18px fifth step was blocked twice in
    // UI-SPEC review; the resolution was UPWARD, because this label is glanced at
    // 0.6-1.5 m. 700 is barred from every component this phase introduces.
    render(<OrderAllergenBanner allergenNames={["Peanuts"]} allergenFlags={[]} />)
    const label = screen.getByTestId("kds-allergen-label")

    expect(label).toHaveClass("text-xl")
    expect(label).toHaveClass("font-semibold")
    expect(label).toHaveClass("uppercase")
    expect(label).toHaveClass("tracking-[0.08em]")
    expect(label).not.toHaveClass("text-lg")
    expect(label).not.toHaveClass("font-bold")
  })

  it("renders NOTHING AT ALL for an order that declared none of the 14", () => {
    // Absence is the signal. A "no allergens" banner on every ticket trains staff to
    // ignore the banner, which destroys the only thing it exists to do.
    const { container } = render(<OrderAllergenBanner allergenNames={[]} allergenFlags={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("tells NOT RECORDED apart from NOTHING DECLARED — three states, not two", () => {
    // THE load-bearing test. Because "renders nothing" MEANS "the vendor declared none",
    // a not-recorded order that also renders nothing would silently claim to be
    // allergen-free. Both directions live in one test on purpose: collapsing the states
    // either way fails here.
    const declaredNone = render(<OrderAllergenBanner allergenNames={[]} allergenFlags={[]} />)
    expect(declaredNone.container).toBeEmptyDOMElement()
    declaredNone.unmount()

    render(<OrderAllergenBanner allergenNames={null} allergenFlags={null} />)
    const unrecorded = screen.getByTestId("kds-allergen-unrecorded")
    expect(unrecorded).toHaveTextContent(/NOT RECORDED/)
    // It is NOT the amber warning either: the platform is not asserting that this order
    // HAS allergens, only that it cannot state the set.
    expect(screen.queryByTestId("kds-allergen-banner")).toBeNull()
    expect(unrecorded).not.toHaveClass("bg-amber-800")
  })

  it("renders a reconciliation flag as a CHECK: line naming the item and the allergen", () => {
    render(<OrderAllergenBanner allergenNames={["Gluten"]} allergenFlags={[FLAG]} />)
    const banner = screen.getByTestId("kds-allergen-banner")
    const check = screen.getByTestId("kds-allergen-check")

    expect(check).toHaveTextContent("CHECK:")
    expect(check).toHaveTextContent("Sourdough Loaf")
    expect(check).toHaveTextContent("Sesame")
    // Distinct WORDING, not a distinct colour — the line is legible in mono.
    expect(banner).toContainElement(check)
  })

  it("never merges an advisory flag into the declared list", () => {
    // A text heuristic must not rewrite the vendor's legal statement. "Sesame" appears
    // in the CHECK line only; the declared line still says exactly what was declared.
    render(<OrderAllergenBanner allergenNames={["Gluten"]} allergenFlags={[FLAG]} />)
    const declared = screen.getByTestId("kds-allergen-declared")

    expect(declared).toHaveTextContent("Gluten")
    expect(declared).not.toHaveTextContent("Sesame")
  })

  it("still shows the banner when nothing was declared but something was flagged", () => {
    // Reachable and important: the vendor declared mask 0 while the ingredients text
    // emphasises an allergen. Dropping the flag because the declared set is empty would
    // lose the exact signal D-03 exists to raise.
    render(<OrderAllergenBanner allergenNames={[]} allergenFlags={[FLAG]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    expect(banner).toHaveTextContent("CHECK:")
    expect(banner).toHaveTextContent("Sourdough Loaf")
    // ...and it says plainly that nothing was declared, rather than leaving a blank line.
    expect(screen.getByTestId("kds-allergen-declared")).toHaveTextContent(/None declared/i)
  })

  it("does not animate, flash or pulse — a flashing safety warning is a seizure risk", () => {
    // WCAG 2.3.1. Asserted against the RENDERED class list rather than the source, so a
    // class arriving via a helper or a variant is caught too.
    render(<OrderAllergenBanner allergenNames={FIVE} allergenFlags={[FLAG]} />)
    const banner = screen.getByTestId("kds-allergen-banner")

    for (const cls of classesIn(banner)) {
      expect(cls).not.toMatch(/^animate-/)
      expect(cls).not.toMatch(/^motion-/)
      expect(cls).not.toMatch(/transition/)
      expect(cls).not.toMatch(/pulse/)
    }
  })
})

describe("ItemAllergenBadge", () => {
  it("shows three names then +2 for an item carrying five allergens", () => {
    render(<ItemAllergenBadge allergenNames={FIVE} />)
    const badge = screen.getByTestId("kds-item-allergen-badge")

    expect(badge).toHaveTextContent("Gluten")
    expect(badge).toHaveTextContent("Milk")
    expect(badge).toHaveTextContent("Eggs")
    // The literal overflow marker, not just "some names are missing".
    expect(badge.textContent).toContain("+2")
    // The truncated tail is genuinely absent — otherwise "+2" would be decoration.
    expect(badge).not.toHaveTextContent("Sesame")
    expect(badge).not.toHaveTextContent("Soya")
  })

  it("shows all three and no +N when there are exactly three", () => {
    render(<ItemAllergenBadge allergenNames={["Gluten", "Milk", "Eggs"]} />)
    const badge = screen.getByTestId("kds-item-allergen-badge")

    expect(badge).toHaveTextContent("Gluten")
    expect(badge).toHaveTextContent("Eggs")
    expect(badge.textContent).not.toMatch(/\+\d/)
  })

  it("renders nothing for an item with no allergens, and nothing when not recorded", () => {
    // Both render nothing HERE on purpose: the item row is not where the not-recorded
    // statement is made. The card-level strip qualifies every line beneath it, the same
    // dependency direction that lets this badge truncate at all.
    const none = render(<ItemAllergenBadge allergenNames={[]} />)
    expect(none.container).toBeEmptyDOMElement()
    none.unmount()

    const unrecorded = render(<ItemAllergenBadge allergenNames={null} />)
    expect(unrecorded.container).toBeEmptyDOMElement()
  })

  it("wears the contracted 14px/600 amber-on-amber-50 treatment with a border", () => {
    render(<ItemAllergenBadge allergenNames={["Gluten"]} />)
    const badge = screen.getByTestId("kds-item-allergen-badge")

    expect(badge).toHaveClass("text-amber-800")
    expect(badge).toHaveClass("bg-amber-50")
    expect(badge).toHaveClass("border-amber-700")
    expect(badge).toHaveClass("text-sm")
    expect(badge).toHaveClass("font-semibold")
    expect(badge).not.toHaveClass("font-bold")
  })
})
