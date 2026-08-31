/**
 * FloatingCartBar — the fixed "View basket" bar on the shop detail page.
 * Rendered INSIDE the real CartProvider (seeded from localStorage, the
 * cart-drawer.test.tsx pattern) so it exercises the actual cart context.
 * Relies on the global framer-motion mock from jest.setup (m.* passthrough +
 * AnimatePresence passthrough) so the AnimatePresence-gated bar mounts
 * synchronously in jsdom.
 *
 * Regression suite for the 2026-08-31 owner complaint: below the shop
 * minimum the bar rendered grey (bg-slate-700) — an off-brand, dead-looking
 * control on the customer's FIRST add. Locked design: the bar is ALWAYS
 * bg-oxblood, and the shortfall is an amber active-voice sub-label
 * ("Add £X.XX to order"), never a colour downgrade.
 */

// shop-detail-client.tsx imports @/lib/public-api-client at module level;
// FloatingCartBar never calls it, so an inert stub cannot mask behaviour.
jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: {} }))

import { render, screen } from "@testing-library/react"
import { CartProvider } from "@/components/storefront/cart-provider"
import { FloatingCartBar } from "@/app/shop/[slug]/shop-detail-client"

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
  pricePennies: 899,
  quantity: 1,
  imageUrl: null,
  category: "Mains",
  ...over,
})

function renderBar(minimumOrderPennies: number) {
  return render(
    <CartProvider shopSlug={SLUG}>
      <FloatingCartBar slug={SLUG} minimumOrderPennies={minimumOrderPennies} />
    </CartProvider>
  )
}

describe("FloatingCartBar", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("renders the branded oxblood bar even below the minimum (never the grey dead-state)", () => {
    // 899 < 1000 — the customer's FIRST add at the seeded shop.
    seed([item()])
    renderBar(1000)
    // DOM-status trap: locate the link by ROLE, never a text search a button
    // label could satisfy, and assert on its className directly.
    const link = screen.getByRole("link")
    expect(link.className).toContain("bg-oxblood")
    expect(link.className).not.toContain("bg-slate-700")
  })

  it("shows the amber active-voice shortfall label with the computed amount", () => {
    // 1000 - 899 = 101 pennies -> "£1.01".
    seed([item()])
    renderBar(1000)
    const label = screen.getByText("Add £1.01 to order")
    expect(label.className).toContain("text-amber-300")
  })

  it("renders no shortfall label at/above the minimum, bar still oxblood", () => {
    // quantity 2 -> 1798 >= 1000.
    seed([item({ quantity: 2 })])
    renderBar(1000)
    expect(screen.queryByText(/to order/)).toBeNull()
    expect(screen.getByRole("link").className).toContain("bg-oxblood")
  })

  it("renders no shortfall label when the shop has no minimum", () => {
    seed([item()])
    renderBar(0)
    expect(screen.queryByText(/to order/)).toBeNull()
    expect(screen.getByRole("link").className).toContain("bg-oxblood")
  })
})
