/**
 * CartDrawer — the slide-over basket opened from the storefront nav badge via
 * the `jtoye:cart-open` window CustomEvent. Rendered INSIDE the real
 * CartProvider so it exercises the actual cart context (seeded from
 * localStorage). Relies on the global framer-motion mock from jest.setup
 * (m.* passthrough + AnimatePresence passthrough) so rows mount synchronously
 * in jsdom; the Sheet is Radix Dialog, unaffected by that mock.
 */

// next/navigation is mocked globally (usePathname -> "/"); the drawer only
// needs a stable pathname so its close-on-navigation effect stays inert here.

import { render, screen, act, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CartDrawer } from "@/components/storefront/cart-drawer"
import publicApiClient from "@/lib/public-api-client"
import {
  BASKET_LINE_ALLERGENS_UNAVAILABLE_COPY,
  NO_ALLERGENS_DECLARED_COPY,
} from "@/lib/allergen-copy"

// #860 (31.1-23): the open drawer reads the live catalogue for each line's allergens. Every test
// gets a catalogue by default; the #860 block below varies it.
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
const mockedGet = publicApiClient.get as jest.Mock

const MILK = 1 << 6
const catalogueProduct = (id: string, title: string, allergenMask: number, extra: object = {}) => ({
  id,
  title,
  description: null,
  imageUrl: null,
  imageUrls: [],
  ingredientsText: "",
  allergenMask,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: null,
  preparationTimeMinutes: null,
  featured: false,
  inStock: true,
  ...extra,
})
const CATALOGUE = {
  Mains: [
    catalogueProduct("p-1", "Jollof Rice", MILK),
    catalogueProduct("p-2", "Plantain", 0),
    catalogueProduct("p-3", "Egusi", 0, { undeclaredIngredientAllergens: ["Milk"] }),
  ],
}
const serveCatalogue = (catalogue: unknown) =>
  mockedGet.mockImplementation((url: string) =>
    String(url).endsWith("/products")
      ? Promise.resolve({ data: catalogue })
      : Promise.reject(new Error(`unexpected GET ${url}`))
  )
const productGets = () => mockedGet.mock.calls.filter(([url]) => String(url).endsWith("/products")).length
const lineAllergenText = () =>
  screen.queryAllByTestId("basket-line-allergens").map((el) => el.textContent)

const SLUG = "test-shop"
const KEY = `jtoye-cart-${SLUG}`

interface SeedItem {
  productId: string
  title: string
  pricePennies: number
  quantity: number
  imageUrl: string | null
  category: string | null
}

function seed(items: SeedItem[]) {
  localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, items }))
}

const item = (over: Partial<SeedItem> = {}): SeedItem => ({
  productId: "p-1",
  title: "Jollof Rice",
  pricePennies: 850,
  quantity: 1,
  imageUrl: null,
  category: "Mains",
  ...over,
})

function renderDrawer() {
  return render(
    <CartProvider shopSlug={SLUG}>
      <CartDrawer />
    </CartProvider>
  )
}

function openDrawer() {
  act(() => {
    window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
  })
}

// Radix Dialog + react-remove-scroll poke a couple of jsdom-missing browser
// APIs when the sheet mounts. Stub them so opening the drawer never throws.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = jest.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    }))
  }
  if (!window.ResizeObserver) {
    window.ResizeObserver = ResizeObserverStub
  }
})

describe("CartDrawer", () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGet.mockReset()
    serveCatalogue(CATALOGUE)
  })

  it("renders nothing visible until the open event fires", () => {
    seed([item()])
    renderDrawer()
    // Closed: Radix Dialog does not mount its portal content.
    expect(screen.queryByText("Your basket")).not.toBeInTheDocument()
  })

  it("opens when the jtoye:cart-open event is dispatched", () => {
    seed([item()])
    renderDrawer()
    openDrawer()
    expect(screen.getByText("Your basket")).toBeInTheDocument()
  })

  it("renders a seeded item's title and line total", () => {
    seed([item({ quantity: 2 })])
    renderDrawer()
    openDrawer()
    expect(screen.getByText("Jollof Rice")).toBeInTheDocument()
    // 850 pennies x 2 = 1700 => £17.00 (line total, subtotal, total).
    expect(screen.getAllByText("£17.00").length).toBeGreaterThan(0)
  })

  it("shows the empty state when the cart has no items", () => {
    renderDrawer()
    openDrawer()
    expect(screen.getByText("Your basket is empty")).toBeInTheDocument()
  })

  it("links checkout and full-basket to the slug-scoped routes", () => {
    seed([item()])
    renderDrawer()
    openDrawer()
    const checkout = screen.getByRole("link", { name: /checkout/i })
    expect(checkout.getAttribute("href")).toBe(`/shop/${SLUG}/checkout`)
    const fullBasket = screen.getByRole("link", { name: /view full basket/i })
    expect(fullBasket.getAttribute("href")).toBe(`/shop/${SLUG}/cart`)
  })

  it("increments quantity via the + stepper", async () => {
    const user = userEvent.setup()
    seed([item({ quantity: 1 })])
    renderDrawer()
    openDrawer()
    expect(screen.getByText("1")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: /increase quantity/i }))

    expect(screen.getByText("2")).toBeInTheDocument()
    // Line total reflows to 850 x 2 = £17.00.
    expect(screen.getAllByText("£17.00").length).toBeGreaterThan(0)
  })

  it("decrements quantity via the − stepper", async () => {
    const user = userEvent.setup()
    seed([item({ quantity: 2 })])
    renderDrawer()
    openDrawer()
    expect(screen.getByText("2")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: /decrease quantity/i }))

    expect(screen.getByText("1")).toBeInTheDocument()
  })
})

describe("CartDrawer — each line's own allergens (#860)", () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGet.mockReset()
    serveCatalogue(CATALOGUE)
  })

  it("does not fetch the catalogue while closed", () => {
    seed([item()])
    renderDrawer()
    expect(productGets()).toBe(0)
  })

  it("states each line's declared set, 'No allergens declared', or the D-09 line, from the live catalogue", async () => {
    seed([
      item({ productId: "p-1", title: "Jollof Rice" }),
      item({ productId: "p-2", title: "Plantain" }),
      item({ productId: "p-3", title: "Egusi" }),
    ])
    renderDrawer()
    openDrawer()

    await waitFor(() =>
      expect(lineAllergenText()).toEqual([
        "Contains: Milk",
        NO_ALLERGENS_DECLARED_COPY,
        "Ingredients name: MILK – check with the shop",
      ])
    )
    expect(mockedGet).toHaveBeenCalledWith(`/public/shops/${SLUG}/products`)
  })

  it("a failed catalogue fetch says 'not available' on every line, never 'No allergens declared'", async () => {
    mockedGet.mockRejectedValue(new Error("upstream down"))
    seed([item({ productId: "p-1" }), item({ productId: "p-2", title: "Plantain" })])
    renderDrawer()
    openDrawer()

    await waitFor(() =>
      expect(lineAllergenText()).toEqual([
        BASKET_LINE_ALLERGENS_UNAVAILABLE_COPY,
        BASKET_LINE_ALLERGENS_UNAVAILABLE_COPY,
      ])
    )
    expect(screen.queryByText(NO_ALLERGENS_DECLARED_COPY)).toBeNull()
  })

  it("re-reads the catalogue each time it opens, so a vendor edit between opens is shown", async () => {
    seed([item({ productId: "p-1" })])
    renderDrawer()
    openDrawer()
    await waitFor(() => expect(lineAllergenText()).toEqual(["Contains: Milk"]))

    await userEvent.setup().click(screen.getByRole("button", { name: /close basket/i }))
    serveCatalogue({ Mains: [catalogueProduct("p-1", "Jollof Rice", MILK | 1)] })
    openDrawer()

    await waitFor(() => expect(lineAllergenText()).toEqual(["Contains: Gluten, Milk"]))
    expect(productGets()).toBe(2)
  })

  it("writes nothing to the stored cart: its JSON is byte-identical before and after the lines render", async () => {
    seed([item({ productId: "p-1" }), item({ productId: "p-3", title: "Egusi" })])
    const seeded = localStorage.getItem(KEY)
    renderDrawer()
    openDrawer()

    await waitFor(() =>
      expect(lineAllergenText()).toEqual(["Contains: Milk", "Ingredients name: MILK – check with the shop"])
    )
    expect(seeded).not.toBeNull()
    expect(localStorage.getItem(KEY)).toBe(seeded)
  })
})
