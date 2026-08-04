/**
 * StorefrontNav basket announcement — pluralisation (#272).
 *
 * The nav's basket affordance carries an `sr-only` description of the SAME
 * basket the cart drawer describes. It was hardcoded `{cartCount} items in
 * basket`, so at exactly 1 a screen reader announced "1 items in basket".
 *
 * Why it survived every UI audit: `sr-only` renders to zero pixels, so no
 * screenshot, visual diff, or assertion on VISIBLE text can see it. The only
 * thing that catches it is reading the accessible name — which is what this
 * file does, via `getByRole("link", { name })`.
 *
 * The count of 1 is the whole point. A test at 0 or 3 passes on the broken
 * code, so asserting only those would be vacuous.
 */

// Real framer-motion for this file (overrides the global jest.setup mock) so
// LazyMotion strict would throw on any full `motion.` component — same idiom as
// storefront-nav-badge.test.tsx.
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
  await act(async () => {})
}

/**
 * The sr-only description itself, whitespace-normalised. Read directly rather
 * than via the link's textContent, because the link also contains the visible
 * numeric badge and concatenation order is not the thing under test.
 */
function basketAnnouncement(): string {
  const el = Array.from(document.querySelectorAll("span.sr-only")).find((s) =>
    /in basket/.test(s.textContent || "")
  )
  return (el?.textContent || "(not found)").replace(/\s+/g, " ").trim()
}

describe("StorefrontNav basket announcement pluralisation (#272)", () => {
  beforeEach(() => {
    localStorage.clear()
    mockUseParams.mockReturnValue({ slug: SLUG })
  })

  it("says 'item' (singular) at exactly one", async () => {
    seed([{ quantity: 1 }])
    await renderNav()
    // The one count that distinguishes correct from broken: a test at 0 or 3
    // passes on the defective code too.
    expect(basketAnnouncement()).toBe("1 item in basket")
    expect(basketAnnouncement()).not.toMatch(/items/)
  })

  it("carries the singular through to the link's accessible name", async () => {
    seed([{ quantity: 1 }])
    await renderNav()
    expect(screen.getByRole("link", { name: /1 item in basket/i })).toBeTruthy()
    expect(screen.queryByRole("link", { name: /1 items in basket/i })).toBeNull()
  })

  it("says 'items' (plural) at zero", async () => {
    await renderNav()
    expect(basketAnnouncement()).toBe("0 items in basket")
  })

  it("says 'items' (plural) above one", async () => {
    seed([{ quantity: 2 }, { quantity: 1 }])
    await renderNav()
    expect(basketAnnouncement()).toBe("3 items in basket")
  })

  it("matches the idiom the cart drawer uses for the same basket", async () => {
    // cart-drawer.tsx and /shop/[slug]/cart both render
    // `{n} item{n !== 1 ? "s" : ""}`. The nav describing the SAME basket
    // differently was the defect; keep the two from drifting apart again.
    seed([{ quantity: 1 }])
    await renderNav()
    const n = 1
    expect(basketAnnouncement()).toBe(`${n} item${n !== 1 ? "s" : ""} in basket`)
  })
})
