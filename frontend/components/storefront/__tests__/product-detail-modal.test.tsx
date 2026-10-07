/**
 * Product detail modal — image framing contract + dialog contract.
 *
 * IMAGE FRAMING
 * The hero image sits in an `aspect-[4/3]` box so every product opens the same
 * shape. That only holds while the image is OUT OF FLOW: as an in-flow child,
 * `h-full` has no definite height to resolve against (the parent's height is
 * precisely what `aspect-ratio` is deriving), so the browser falls back to the
 * image's intrinsic ratio and the box stretches to match — `aspect-ratio`
 * yields to content. Shipped that way, the modal changed shape per product: a
 * 900x1200 photo rendered 512x683 and an 858x645 one rendered 512x385, which
 * read as "some long horizontally, others vertically".
 *
 * jsdom has no layout engine, so this asserts the STRUCTURE that produces the
 * geometry rather than the geometry itself. The live 4:3 result is verified in
 * the browser (all products measured 512x384 after the fix).
 *
 * DIALOG CONTRACT (#446 / #272)
 * The modal is a Radix Dialog, so it renders through a PORTAL — assertions here
 * query `document.body`, not the render container, which is why the framing
 * checks below changed target when the port landed.
 *
 * What is asserted here is only what jsdom can honestly answer: the ATTRIBUTES
 * in the rendered output. The behaviours the issue is actually about — Escape
 * dismissing the overlay, focus being trapped, focus returning to the trigger,
 * body scroll locking — are runtime behaviours jsdom cannot demonstrate (no
 * layout, no real focus management, no scrollbar). Those are proven in a real
 * browser by `e2e/storefront-dish-modal-a11y.spec.ts`. Do not read a green run
 * of this file as evidence that the dialog BEHAVES correctly.
 */
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { ProductDetailModal } from "../product-detail-modal"
import type { PublicProduct } from "@/types/storefront"
import { expectSoundAspectFrames } from "@/test-utils/aspect-frame-contract"

function product(overrides: Partial<PublicProduct> = {}): PublicProduct {
  return {
    id: "p-1",
    title: "Party Jollof Rice",
    description: "Smoky long-grain rice",
    imageUrl: "http://example.test/jollof.jpg",
    imageUrls: ["http://example.test/jollof.jpg"],
    ingredientsText: "rice, scotch bonnet",
    allergenMask: 0,
    pricePennies: 950,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
    ...overrides,
  }
}

function renderModal(
  p: PublicProduct,
  props: Partial<{ isOpen: boolean; onClose: () => void }> = {}
) {
  return render(
    <ProductDetailModal
      product={p}
      isOpen={props.isOpen ?? true}
      onClose={props.onClose ?? (() => {})}
      quantity={0}
      onAdd={() => {}}
      onIncrement={() => {}}
      onDecrement={() => {}}
    />
  )
}

describe("ProductDetailModal image framing", () => {
  it("frames the hero image soundly (shared aspect-frame contract)", () => {
    renderModal(product())
    // One frame, structurally sound. The shared helper is the single place
    // this rule is written down, so every component that grows a frame can
    // adopt the same guard. Portalled, so `document.body` is the container.
    expectSoundAspectFrames(document.body, 1)
  })

  it("declares a 4:3 window and takes the image out of flow", () => {
    renderModal(product())

    const img = screen.getByAltText(/party jollof rice - image 1/i)
    const frame = img.closest("[data-aspect-frame]") as HTMLElement
    expect(frame.getAttribute("data-aspect-frame")).toBe("4/3")
    expect(frame.className).toMatch(/aspect-\[4\/3\]/)
    expect(img.className).toMatch(/\babsolute\b/)
    expect(img.className).toMatch(/\binset-0\b/)
    expect(img.className).toMatch(/\bobject-cover\b/)
  })

  it("uses the same sound 4:3 frame for the no-image placeholder", () => {
    renderModal(product({ imageUrl: null, imageUrls: [] }))
    expectSoundAspectFrames(document.body, 1)
    expect(document.body.querySelector('[data-aspect-frame="4/3"]')).not.toBeNull()
  })

  it("keeps the carousel overlays inside the frame (multi-image)", () => {
    renderModal(
      product({
        imageUrls: ["http://example.test/a.jpg", "http://example.test/b.jpg"],
      })
    )
    // Thumbnails live in their own definite-height boxes and must NOT be
    // mistaken for unsound frame images — the contract excludes them.
    expectSoundAspectFrames(document.body, 1)
    expect(screen.getByAltText(/image 1/i)).toBeTruthy()
  })
})

describe("ProductDetailModal dialog contract (#446 / #272)", () => {
  it("exposes role=dialog with aria-modal, named by the dish title", () => {
    renderModal(product())

    const dialog = screen.getByRole("dialog")
    expect(dialog).toHaveAttribute("aria-modal", "true")
    // The accessible name resolves through aria-labelledby -> the <h2> title.
    // Retrieving the dialog BY that name is what proves the wiring; the bare
    // presence of the attribute would not.
    expect(screen.getByRole("dialog", { name: /Party Jollof Rice/i })).toBe(dialog)
  })

  it("points aria-describedby at the dish description when there is one", () => {
    renderModal(product())

    const describedBy = screen.getByRole("dialog").getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(describedBy!)?.textContent).toBe(
      "Smoky long-grain rice"
    )
  })

  it("leaves aria-describedby unset when the dish has no description", () => {
    renderModal(product({ description: null }))
    expect(screen.getByRole("dialog")).not.toHaveAttribute("aria-describedby")
  })

  it("renders nothing at all while closed", () => {
    renderModal(product(), { isOpen: false })
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("gives every icon-only control an accessible name", () => {
    renderModal(
      product({
        imageUrls: ["http://example.test/a.jpg", "http://example.test/b.jpg"],
      })
    )

    // Previously all of these were unnamed <button><svg/></button>: a screen
    // reader announced "button" with no indication of what it did, on the
    // screen that carries the allergen panel.
    expect(screen.getByRole("button", { name: /^close$/i })).toBeTruthy()
    expect(screen.getByRole("button", { name: /previous image/i })).toBeTruthy()
    expect(screen.getByRole("button", { name: /next image/i })).toBeTruthy()
    expect(
      screen.getAllByRole("button", { name: /show image 2 of 2/i }).length
    ).toBeGreaterThan(0)
  })

  it("still surfaces the allergen panel inside the dialog", () => {
    // Regression guard for the reason this component was prioritised at all:
    // the modal is where allergen data is communicated, so the port must not
    // have moved it out of the dialog's accessible subtree.
    renderModal(product({ allergenMask: 1 }))
    expect(screen.getByRole("dialog").textContent).toMatch(/Allergen Information/i)
  })

  it("wires Escape to onClose", async () => {
    // Wiring only. That the OVERLAY actually disappears, that focus returns to
    // the trigger and that body scroll unlocks are browser facts, asserted in
    // e2e/storefront-dish-modal-a11y.spec.ts.
    const onClose = jest.fn()
    const user = userEvent.setup({ pointerEventsCheck: 0 })
    renderModal(product(), { onClose })

    await user.keyboard("{Escape}")
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it("wires the close button to onClose", async () => {
    const onClose = jest.fn()
    const user = userEvent.setup({ pointerEventsCheck: 0 })
    renderModal(product(), { onClose })

    await user.click(screen.getByRole("button", { name: /^close$/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

/**
 * 31.1-21 (#817, #787, #861; D-09, D-16, D-18): the dish modal LEADS with what the dish contains,
 * may contain, and what its ingredients say.
 *
 * On the unfixed tree the "Allergen Information" box sat after the description and the
 * ingredients, and was not rendered at all for an empty declaration — so the persona's MILK dish
 * (empty declaration, MILK in the ingredients) opened with no allergen statement anywhere, and a
 * 200%-zoom reader on a phone had to scroll past the description to reach the box when there was
 * one. There was no may-contain line.
 *
 * jsdom has no layout, so "visible without scrolling" is asserted as the structure that produces
 * it: the section comes BEFORE the description and the ingredients, directly under the title. The
 * zoomed viewport itself is measured by e2e/storefront-dish-modal-a11y.spec.ts.
 */
describe("ProductDetailModal allergen section (31.1-21)", () => {
  const GLUTEN = 1 << 0
  const MILK = 1 << 6

  function allergenSection() {
    return screen.getByRole("region", { name: "Allergen Information" })
  }

  function precedes(a: Node, b: Node): boolean {
    return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
  }

  it("declared: a named 'Contains' list of the allergens in catalogue order, before Add", () => {
    renderModal(product({ allergenMask: MILK | GLUTEN }))
    const section = allergenSection()
    const list = within(section).getByRole("list", { name: "Contains" })
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual(["Gluten", "Milk"])
    expect(precedes(section, screen.getByRole("button", { name: /Add to cart/i }))).toBe(true)
  })

  it("comes before the description and the ingredients, directly under the title", () => {
    renderModal(product({ allergenMask: MILK }))
    const section = allergenSection()
    expect(precedes(section, screen.getByRole("heading", { name: "About" }))).toBe(true)
    expect(precedes(section, screen.getByRole("heading", { name: "Ingredients" }))).toBe(true)
    expect(precedes(screen.getByRole("heading", { level: 2 }), section)).toBe(true)
  })

  it("none declared: the section still renders and says 'No allergens declared'", () => {
    renderModal(product({ allergenMask: 0, undeclaredIngredientAllergens: [], mayContainAllergens: [] }))
    expect(within(allergenSection()).getByText("No allergens declared")).toBeInTheDocument()
    expect(within(allergenSection()).queryByRole("list", { name: "Contains" })).toBeNull()
  })

  it("flagged (D-09): the ingredients line names MILK and 'No allergens declared' is absent", () => {
    renderModal(product({ allergenMask: 0, undeclaredIngredientAllergens: ["Milk"] }))
    const section = allergenSection()
    expect(within(section).getByText("Ingredients name: MILK – check with the shop")).toBeInTheDocument()
    expect(within(section).queryByText("No allergens declared")).toBeNull()
  })

  it("may contain (D-16): 'May contain: Sesame' is its own line, never inside the Contains list", () => {
    renderModal(product({ allergenMask: MILK, mayContainAllergens: ["Sesame"] }))
    const section = allergenSection()
    const line = within(section).getByText("May contain: Sesame")
    const list = within(section).getByRole("list", { name: "Contains" })
    expect(list.contains(line)).toBe(false)
    expect(within(list).queryByText(/Sesame/)).toBeNull()
    // Beneath the declared set, not above it.
    expect(precedes(list, line)).toBe(true)
  })

  it("all three statements together keep their order: contains, may contain, ingredients", () => {
    renderModal(
      product({ allergenMask: GLUTEN, mayContainAllergens: ["Sesame"], undeclaredIngredientAllergens: ["Milk"] })
    )
    const section = allergenSection()
    const list = within(section).getByRole("list", { name: "Contains" })
    const may = within(section).getByText("May contain: Sesame")
    const flag = within(section).getByText("Ingredients name: MILK – check with the shop")
    expect(precedes(list, may)).toBe(true)
    expect(precedes(may, flag)).toBe(true)
  })

  it("comes before the quantity stepper once the dish is in the basket", () => {
    render(
      <ProductDetailModal
        product={product({ allergenMask: MILK })}
        isOpen
        onClose={() => {}}
        quantity={2}
        onAdd={() => {}}
        onIncrement={() => {}}
        onDecrement={() => {}}
      />
    )
    const more = screen.getByRole("button", { name: "Add one more Party Jollof Rice to cart" })
    expect(precedes(allergenSection(), more)).toBe(true)
  })

  it("never says 'No allergens' alone or anything the platform cannot stand behind", () => {
    renderModal(product({ allergenMask: 0 }))
    const text = screen.getByRole("dialog").textContent ?? ""
    expect(text).toContain("No allergens declared")
    expect(screen.queryByText(/^\s*No allergens\s*$/)).toBeNull()
    expect(text).not.toMatch(/allergen[-\s]free|safe for you|no allergens present/i)
  })
})
