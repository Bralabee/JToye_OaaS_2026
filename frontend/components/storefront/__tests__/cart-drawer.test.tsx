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

import { render, screen, act } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CartDrawer } from "@/components/storefront/cart-drawer"

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
