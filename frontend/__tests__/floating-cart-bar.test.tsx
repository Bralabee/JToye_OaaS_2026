/**
 * FloatingCartBar — the fixed "View basket" bar on the shop detail page.
 * Rendered INSIDE the real CartProvider (seeded from localStorage, the
 * cart-drawer.test.tsx pattern) so it exercises the actual cart context.
 * Relies on the global framer-motion mock from jest.setup (m.* passthrough +
 * AnimatePresence passthrough) so the AnimatePresence-gated bar mounts
 * synchronously in jsdom.
 *
 * LIVES IN `__tests__/` ON PURPOSE — outside the contrast gate's SCAN_ROOTS,
 * for the same reason contrast-literals.test.ts documents for itself: a test
 * asserting `text-amber-300` by name is not a rendered surface, and placing it
 * under app/shop/ turned the contrast scan red (#718 review F-1; the first fix
 * excluded *.test.tsx from the scan, which diverged from jest's real testMatch
 * in both directions — placement is the correct fix, the scanner is untouched).
 *
 * Regression suite for the 2026-08-31 owner complaint: below the shop
 * minimum the bar rendered grey (bg-slate-700) — an off-brand, dead-looking
 * control on the customer's FIRST add. Locked design: the bar is ALWAYS
 * bg-oxblood, and the shortfall is an amber active-voice sub-label stating
 * BOTH the delta and the shop's absolute minimum (review F-2), never a
 * colour downgrade.
 */
import { render, screen } from "@testing-library/react"
import { CartProvider } from "@/components/storefront/cart-provider"
import type { CartItem } from "@/components/storefront/cart-provider"
import { FloatingCartBar } from "@/components/storefront/floating-cart-bar"

const SLUG = "test-shop"
const KEY = `jtoye-cart-${SLUG}`

// Seeds are typed as the REAL persisted item shape minus provider-owned
// fields, so a payload change (the R-16 owner stamp was exactly that) fails
// compilation here instead of silently seeding a shape the provider drops
// (review F-4; full seed-helper dedup across the four suites is follow-up).
type SeedItem = Pick<CartItem, "productId" | "title" | "pricePennies" | "quantity" | "imageUrl" | "category">

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

  it("shows the amber shortfall label with the computed amount AND the absolute minimum", () => {
    // 1000 - 899 = 101 pennies -> "£1.01"; the absolute "£10.00" must be
    // stated too — the delta alone is a moving target (review F-2).
    seed([item()])
    renderBar(1000)
    const label = screen.getByText("Add £1.01 to order · min £10.00")
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
