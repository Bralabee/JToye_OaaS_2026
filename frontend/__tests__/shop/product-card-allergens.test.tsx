/**
 * #817 / #787 (storefront half) — 31.1-21, D-09 and D-18: a menu card names its allergens in
 * WORDS, before the Add control, and never implies "none" when the ingredients say otherwise.
 *
 * What the persona met on the unfixed tree: a card carried an amber triangle and a bare count
 * ("△2"), which a screen reader read as "Halal Spicy £8.50 2". The count came AFTER the price and
 * nothing was said when the declaration was empty, so the persona's dish, whose ingredients say
 * MILK and whose declaration is empty, looked identical to a dish with nothing to declare.
 *
 * Each block below is written to fail on that tree:
 *   - the declared names must be text ("Contains: Gluten, Milk"), with no bare numeric badge;
 *   - the line must PRECEDE the Add button in document order (compareDocumentPosition), so a
 *     reader moving through the card meets it first;
 *   - the Add button's accessible DESCRIPTION must carry the line (aria-describedby), so a
 *     screen-reader user who jumps straight to "Add Halal Spicy to basket" still hears it;
 *   - an empty declaration says "No allergens declared", and an undeclared ingredient allergen
 *     says "Ingredients name: MILK – check with the shop" INSTEAD (never both).
 *
 * The cart is the stub used by server-seeded-islands.test.tsx (items: []), so every card renders
 * the plain "Add" branch this contract is about.
 */

import { act, render, screen, within } from "@testing-library/react"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: "mama-ades-kitchen",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

// Bits in the shared 14-entry table (types/api.ts ALLERGENS = core-java AllergenCatalog).
const GLUTEN = 1 << 0
const MILK = 1 << 6
const SESAME = 1 << 10

const shop: PublicShop = {
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  description: null,
  address: "1 Test Street, London",
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: null,
  deliveryFeePennies: null,
  freeDeliveryThresholdPennies: null,
  tags: null,
}

function dish(id: string, title: string, overrides: Partial<PublicProduct> = {}): PublicProduct {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "rice",
    allergenMask: 0,
    pricePennies: 850,
    category: "Mains",
    dietaryTags: "Halal, Spicy",
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
    ...overrides,
  }
}

async function renderMenu(products: PublicProduct[]) {
  const detail: ShopDetail = {
    shop,
    products: { Mains: products },
    reviews: [],
    reviewCount: 0,
    avgRating: 0,
    promotions: [],
    announcements: [],
    isOpen: true,
  }
  await act(async () => {
    render(<ShopDetailClient slug={shop.slug} initial={detail} />)
  })
}

function addButton(title: string) {
  return screen.getByRole("button", { name: `Add ${title} to basket` })
}

function cardOf(title: string): HTMLElement {
  const article = addButton(title).closest("article")
  expect(article).not.toBeNull()
  return article as HTMLElement
}

/** True when `a` comes before `b` in document order. */
function precedes(a: Node, b: Node): boolean {
  return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
}

beforeEach(() => {
  mockGet.mockReset()
  mockGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
})

describe("#817: a card names its declared allergens before Add", () => {
  it("mask Gluten|Milk reads 'Contains: Gluten, Milk', before the Add button in document order", async () => {
    await renderMenu([dish("p1", "Halal Spicy", { allergenMask: GLUTEN | MILK })])
    const card = cardOf("Halal Spicy")
    const line = within(card).getByText("Contains: Gluten, Milk")
    const add = addButton("Halal Spicy")

    expect(precedes(line, add)).toBe(true)
    // The control: the same comparison the other way round is false, so the helper can fail.
    expect(precedes(add, line)).toBe(false)
  })

  it("the Add button's accessible description carries the allergen line; its name is unchanged", async () => {
    await renderMenu([dish("p1", "Halal Spicy", { allergenMask: GLUTEN | MILK })])
    const add = addButton("Halal Spicy")
    expect(add).toHaveAccessibleName("Add Halal Spicy to basket")
    expect(add).toHaveAccessibleDescription(/Contains: Gluten, Milk/)
  })

  it("has no bare numeric badge (the '△2' a screen reader read as '2')", async () => {
    await renderMenu([dish("p1", "Halal Spicy", { allergenMask: GLUTEN | MILK })])
    const card = cardOf("Halal Spicy")
    expect(within(card).queryByText(/^\s*\d+\s*$/)).toBeNull()
  })

  it("lists names in catalogue bit order, rendered from the shared table", async () => {
    await renderMenu([dish("p1", "Halal Spicy", { allergenMask: SESAME | MILK | GLUTEN })])
    expect(within(cardOf("Halal Spicy")).getByText("Contains: Gluten, Milk, Sesame")).toBeInTheDocument()
  })
})

describe("D-18 / D-09: the card is never silent, and never says 'none' over the ingredients", () => {
  it("mask 0 with no flag reads 'No allergens declared'", async () => {
    await renderMenu([dish("p1", "Plain Rice")])
    const card = cardOf("Plain Rice")
    expect(within(card).getByText("No allergens declared")).toBeInTheDocument()
    expect(addButton("Plain Rice")).toHaveAccessibleDescription(/No allergens declared/)
  })

  it("mask 0 with an undeclared ingredient allergen reads the D-09 wording, never 'No allergens declared'", async () => {
    await renderMenu([
      dish("p1", "Party Jollof Rice", { undeclaredIngredientAllergens: ["Milk"] }),
    ])
    const card = cardOf("Party Jollof Rice")
    const line = within(card).getByText("Ingredients name: MILK – check with the shop")
    expect(precedes(line, addButton("Party Jollof Rice"))).toBe(true)
    expect(within(card).queryByText("No allergens declared")).toBeNull()
    expect(addButton("Party Jollof Rice")).toHaveAccessibleDescription(
      /Ingredients name: MILK – check with the shop/
    )
  })

  it("names several undeclared ingredient allergens in catalogue order", async () => {
    await renderMenu([
      dish("p1", "Party Jollof Rice", { undeclaredIngredientAllergens: ["Sesame", "Gluten"] }),
    ])
    expect(
      within(cardOf("Party Jollof Rice")).getByText(
        "Ingredients name: GLUTEN, SESAME – check with the shop"
      )
    ).toBeInTheDocument()
  })

  it("a declared allergen beside an undeclared one shows both lines, each its own statement", async () => {
    await renderMenu([
      dish("p1", "Party Jollof Rice", {
        allergenMask: GLUTEN,
        undeclaredIngredientAllergens: ["Milk"],
      }),
    ])
    const card = cardOf("Party Jollof Rice")
    expect(within(card).getByText("Contains: Gluten")).toBeInTheDocument()
    expect(within(card).getByText("Ingredients name: MILK – check with the shop")).toBeInTheDocument()
  })

  it("adjacency: declared Milk with no flag shows only 'Contains: Milk'", async () => {
    await renderMenu([dish("p1", "Milky Stew", { allergenMask: MILK, undeclaredIngredientAllergens: [] })])
    const card = cardOf("Milky Stew")
    expect(within(card).getByText("Contains: Milk")).toBeInTheDocument()
    expect(within(card).queryByText(/Ingredients name/)).toBeNull()
  })

  it("adjacency: a flag for a bit that IS declared adds nothing (the card adds no second line)", async () => {
    await renderMenu([
      dish("p1", "Milky Stew", { allergenMask: MILK, undeclaredIngredientAllergens: ["Milk"] }),
    ])
    const card = cardOf("Milky Stew")
    expect(within(card).getByText("Contains: Milk")).toBeInTheDocument()
    expect(within(card).queryByText(/Ingredients name/)).toBeNull()
  })

  it("an older backend (fields absent) still states the declaration", async () => {
    const legacy = dish("p1", "Plain Rice")
    delete (legacy as Partial<PublicProduct>).undeclaredIngredientAllergens
    delete (legacy as Partial<PublicProduct>).mayContainAllergens
    await renderMenu([legacy])
    expect(within(cardOf("Plain Rice")).getByText("No allergens declared")).toBeInTheDocument()
  })
})

describe("the featured rail renders a dish twice: both copies are described, by distinct ids", () => {
  it("each Add control resolves its own allergen line", async () => {
    await renderMenu([dish("p1", "Halal Spicy", { allergenMask: GLUTEN, featured: true })])
    const rail = screen.getByRole("button", { name: "Add Halal Spicy to basket (featured)" })
    const list = addButton("Halal Spicy")
    expect(rail).toHaveAccessibleDescription(/Contains: Gluten/)
    expect(list).toHaveAccessibleDescription(/Contains: Gluten/)
    expect(rail.getAttribute("aria-describedby")).not.toBe(list.getAttribute("aria-describedby"))
  })
})

describe("prohibition: no card says what the platform cannot stand behind", () => {
  it("no text equals 'No allergens' and none says allergen-free / safe for you / no allergens present", async () => {
    await renderMenu([
      dish("p1", "Plain Rice"),
      dish("p2", "Party Jollof Rice", { undeclaredIngredientAllergens: ["Milk"] }),
      dish("p3", "Halal Spicy", { allergenMask: GLUTEN | MILK }),
    ])
    // Control: the menu rendered (three cards), so the absence below is about the words.
    expect(screen.getAllByRole("button", { name: /^Add .+ to basket$/ })).toHaveLength(3)
    expect(screen.queryByText(/^\s*No allergens\s*$/)).toBeNull()
    const text = document.body.textContent ?? ""
    expect(text).not.toMatch(/allergen[-\s]free/i)
    expect(text).not.toMatch(/safe for you/i)
    expect(text).not.toMatch(/no allergens present/i)
  })
})
