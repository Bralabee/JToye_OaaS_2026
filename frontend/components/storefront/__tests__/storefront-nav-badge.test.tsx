/**
 * StorefrontNav basket badge — the /shop/[slug] basket affordance with a live
 * count from useCartCount. Renders under the REAL MotionProvider (LazyMotion
 * strict + MotionConfig) so the m.span badge is exercised exactly as shipped.
 */

// Real framer-motion for this file (overrides the global jest.setup mock) so
// LazyMotion strict would throw on any full `motion.` component.
jest.mock("framer-motion", () => jest.requireActual("framer-motion"))

const mockUseParams = jest.fn<Record<string, string>, []>(() => ({ slug: "test-shop" }))
jest.mock("next/navigation", () => ({
  usePathname: () => "/shop/test-shop",
  useParams: () => mockUseParams(),
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

import { render, screen, act } from "@testing-library/react"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { MotionProvider } from "@/components/motion-provider"

const SLUG = "test-shop"
const KEY = `jtoye-cart-${SLUG}`

function seed(items: Array<{ quantity: number }>) {
  localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, items }))
}

// jsdom has no matchMedia — MotionConfig reducedMotion="user" queries it.
beforeAll(() => {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: jest.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })),
  })
})

async function renderNav() {
  render(
    <MotionProvider>
      <StorefrontNav />
    </MotionProvider>
  )
  // Flush the mount-time session check + LazyMotion feature load.
  await act(async () => {})
}

describe("StorefrontNav basket badge", () => {
  beforeEach(() => {
    localStorage.clear()
    mockUseParams.mockReturnValue({ slug: SLUG })
  })

  it("renders the basket link with the count from seeded localStorage", async () => {
    seed([{ quantity: 2 }, { quantity: 1 }])
    await renderNav()

    const basketLink = screen.getByRole("link", { name: /3 items in basket/i })
    expect(basketLink.getAttribute("href")).toBe(`/shop/${SLUG}/cart`)
    expect(screen.getByText("3")).toBeInTheDocument()
  })

  it("updates the badge count when jtoye:cart-updated is dispatched", async () => {
    seed([{ quantity: 1 }])
    await renderNav()
    expect(screen.getByText("1")).toBeInTheDocument()

    act(() => {
      window.dispatchEvent(
        new CustomEvent("jtoye:cart-updated", {
          detail: { slug: SLUG, itemCount: 5, totalPennies: 2500 },
        })
      )
    })

    expect(screen.getByText("5")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /5 items in basket/i })).toBeTruthy()
  })

  it("renders no basket link on slug-less routes", async () => {
    mockUseParams.mockReturnValue({})
    await renderNav()

    expect(screen.queryByRole("link", { name: /items in basket/i })).toBeNull()
  })
})
