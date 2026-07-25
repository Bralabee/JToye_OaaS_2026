/**
 * Product detail modal — image framing contract.
 *
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
 */
import { render, screen } from "@testing-library/react"
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

function renderModal(p: PublicProduct) {
  return render(
    <ProductDetailModal
      product={p}
      isOpen
      onClose={() => {}}
      quantity={0}
      onAdd={() => {}}
      onIncrement={() => {}}
      onDecrement={() => {}}
    />
  )
}

describe("ProductDetailModal image framing", () => {
  it("frames the hero image soundly (shared aspect-frame contract)", () => {
    const { container } = renderModal(product())
    // One frame, structurally sound. The shared helper is the single place
    // this rule is written down, so every component that grows a frame can
    // adopt the same guard.
    expectSoundAspectFrames(container, 1)
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
    const { container } = renderModal(
      product({ imageUrl: null, imageUrls: [] })
    )
    expectSoundAspectFrames(container, 1)
    expect(
      container.querySelector('[data-aspect-frame="4/3"]')
    ).not.toBeNull()
  })

  it("keeps the carousel overlays inside the frame (multi-image)", () => {
    const { container } = renderModal(
      product({
        imageUrls: [
          "http://example.test/a.jpg",
          "http://example.test/b.jpg",
        ],
      })
    )
    // Thumbnails live in their own definite-height boxes and must NOT be
    // mistaken for unsound frame images — the contract excludes them.
    expectSoundAspectFrames(container, 1)
    expect(screen.getByAltText(/image 1/i)).toBeTruthy()
  })
})
