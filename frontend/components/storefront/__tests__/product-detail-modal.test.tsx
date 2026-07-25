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
  it("takes the hero image OUT OF FLOW so the 4:3 box governs its height", () => {
    renderModal(product())

    const img = screen.getByAltText(/party jollof rice - image 1/i)
    // Out of flow + pinned to all four edges: without this the box inherits
    // the image's intrinsic ratio instead of imposing 4:3.
    expect(img.className).toMatch(/\babsolute\b/)
    expect(img.className).toMatch(/\binset-0\b/)
    expect(img.className).toMatch(/\bobject-cover\b/)
  })

  it("frames the hero image in a clipped 4:3 box", () => {
    renderModal(product())

    const img = screen.getByAltText(/party jollof rice - image 1/i)
    const box = img.parentElement as HTMLElement
    expect(box.className).toMatch(/aspect-\[4\/3\]/)
    // The box must clip, so object-cover crops rather than bleeding past the
    // modal's rounded corners.
    expect(box.className).toMatch(/\boverflow-hidden\b/)
    // A positioned ancestor is required for inset-0 to resolve to this box.
    expect(box.className).toMatch(/\brelative\b/)
  })

  it("uses the same 4:3 frame for the no-image placeholder", () => {
    const { container } = renderModal(
      product({ imageUrl: null, imageUrls: [] })
    )
    const placeholder = container.querySelector('[class*="aspect-[4/3]"]')
    expect(placeholder).not.toBeNull()
  })
})
