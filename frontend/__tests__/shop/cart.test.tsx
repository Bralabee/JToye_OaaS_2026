/**
 * Tests for the standalone shop cart page (STFR-04).
 *
 * Covers:
 *  - Empty cart state when localStorage has no seeded items
 *  - Populated cart state when localStorage was hydrated before mount
 *  - The money the basket screen CLAIMS (COR-2/COR-3): a delivery line taken
 *    from the shop DTO, no bare "Total" over an item subtotal, the shared
 *    minimum-order shortfall, and a CTA that is disabled below the minimum.
 *
 * The CartProvider loads from localStorage in a useEffect, so tests MUST
 * seed localStorage BEFORE rendering the provider (see 10-RESEARCH pitfall 4).
 */

import { Suspense } from "react"
import { render, screen } from "@testing-library/react"
import CartPage from "@/app/shop/[slug]/cart/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

// The basket page fetches the shop for its server-authoritative delivery fee
// and minimum — same shape (and same graceful degradation) as checkout.
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

const mockedGet = publicApiClient.get as jest.Mock

// Default for every test in this file: the shop is UNKNOWN. That is the page's
// documented degraded state (no delivery line, no minimum line), so the three
// describes that predate the fetch render exactly what they were written
// against. The money describes below opt in to a real shop.
beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockRejectedValue(new Error("no shop stubbed for this test"))
})

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

// React's use() hook needs a thenable. A plain Promise resolves via microtask
// which jest+jsdom does not always flush between render and the first query,
// so we hand React a pre-resolved thenable it can unwrap synchronously.
function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderCart() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CartPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
}

describe("Shop cart page (/shop/[slug]/cart)", () => {
  afterEach(() => {
    localStorage.clear()
  })

  it("renders empty state when no items", async () => {
    renderCart()

    expect(
      await screen.findByText(/your basket is empty/i)
    ).toBeTruthy()
    expect(
      screen.getByText(/add items from the menu to get started/i)
    ).toBeTruthy()

    // Back-to-menu link points to the shop detail page
    const backLinks = screen.getAllByRole("link", { name: /back to menu/i })
    expect(backLinks.length).toBeGreaterThan(0)
    expect(backLinks[0].getAttribute("href")).toBe(`/shop/${SLUG}`)
  })

  it("renders items when cart has contents", async () => {
    // Seed localStorage BEFORE rendering — CartProvider hydrates in useEffect
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        items: [
          {
            productId: "p1",
            title: "Jollof Rice",
            pricePennies: 899,
            quantity: 2,
            imageUrl: null,
            category: "Mains",
          },
        ],
      })
    )

    renderCart()

    // Item title appears after hydration tick
    expect(await screen.findByText("Jollof Rice")).toBeTruthy()

    // 2 × £8.99 = £17.98 should be rendered (as line total and order total)
    const totals = screen.getAllByText(/£17\.98/)
    expect(totals.length).toBeGreaterThan(0)

    // Proceed-to-checkout link exists and points at the checkout route
    const checkoutLink = screen.getByRole("link", { name: /proceed to checkout/i })
    expect(checkoutLink.getAttribute("href")).toBe(`/shop/${SLUG}/checkout`)
  })
})

/**
 * A11Y-7: the +/- quantity steppers carried NO accessible name at all — a
 * screen-reader user landed on an unlabelled "button" and had no way to tell
 * which line item, or which direction, it acted on. The fix names the OBJECT
 * (the dish), not just the action, and the minus button additionally states
 * removal rather than "decrease" the moment decreasing WOULD remove the line
 * (quantity === 1) — a silent removal on the last unit is a worse surprise
 * than an unlabelled button.
 */
describe("Shop cart page — quantity stepper accessible names (A11Y-7)", () => {
  afterEach(() => {
    localStorage.clear()
  })

  it("names both steppers after the item when quantity > 1", async () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        items: [
          {
            productId: "p1",
            title: "Jollof Rice",
            pricePennies: 899,
            quantity: 2,
            imageUrl: null,
            category: "Mains",
          },
        ],
      })
    )

    renderCart()
    await screen.findByText("Jollof Rice")

    expect(
      screen.getByRole("button", { name: "Decrease quantity of Jollof Rice" })
    ).toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: "Increase quantity of Jollof Rice" })
    ).toBeInTheDocument()
  })

  it("names the minus button as a removal once quantity is 1 (decreasing further removes the line)", async () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        items: [
          {
            productId: "p1",
            title: "Jollof Rice",
            pricePennies: 899,
            quantity: 1,
            imageUrl: null,
            category: "Mains",
          },
        ],
      })
    )

    renderCart()
    await screen.findByText("Jollof Rice")

    expect(
      screen.getByRole("button", { name: "Remove Jollof Rice from basket" })
    ).toBeInTheDocument()
    // The generic, un-named form must not also be present under a different name.
    expect(
      screen.queryByRole("button", { name: "Decrease quantity of Jollof Rice" })
    ).not.toBeInTheDocument()
  })
})

/**
 * COR-2 / COR-3 — what the basket screen CLAIMS about money.
 *
 * Measured live before the fix: 1 x Zobo (300p) at a shop charging 350p
 * delivery below a 2500p free-delivery threshold, with a 1000p minimum, showed
 * "Subtotal £3.00", "Total £3.00" and an ENABLED "Proceed to checkout £3.00".
 * One tap later checkout showed "Delivery £3.50", "Total £6.50", a minimum-order
 * warning, and a DISABLED submit. Three disagreements about one basket.
 *
 * The fee and the minimum both come from the shop DTO — never a literal. A test
 * that hardcoded 350 here would pass against a page that hardcoded 350 there.
 */
const FEE_SHOP = {
  slug: SLUG,
  name: "Jollof Express",
  description: null,
  address: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 350,
  freeDeliveryThresholdPennies: 2500,
  tags: null,
}

function seedBasket(pricePennies: number, quantity = 1) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        {
          productId: "p1",
          title: "Zobo",
          pricePennies,
          quantity,
          imageUrl: null,
          category: "Drinks",
        },
      ],
    })
  )
}

describe("Shop cart page — the money it claims (COR-2)", () => {
  beforeEach(() => {
    mockedGet.mockReset()
    mockedGet.mockResolvedValue({ data: FEE_SHOP })
  })
  afterEach(() => {
    localStorage.clear()
  })

  it("shows a delivery line carrying the shop's own fee, not a literal", async () => {
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    expect(screen.getByText(/^delivery$/i)).toBeInTheDocument()
    // "from" — the basket cannot know the fulfilment type yet; the customer
    // picks it on the next screen. It states the floor, and never a Total.
    expect(await screen.findByText(/from £3\.50/i)).toBeInTheDocument()
    // The fee is READ, not assumed: a different shop moves the number.
    expect(mockedGet).toHaveBeenCalledWith(`/public/shops/${SLUG}`)
  })

  it("explains the delivery rule in one sentence naming the shop's own threshold", async () => {
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    // Pins the ASSEMBLED sentence — two JSX text chunks either side of a
    // conditional clause — and the fact that the threshold in it is the SHOP's,
    // £25.00 here because the stub says 2500, not a literal in the page.
    //
    // MEASURED, so the comment does not overclaim: Testing Library normalises
    // whitespace, so this does NOT catch a stray space (that break arm passed).
    // It does catch the wording and the number — rephrasing "delivery is free
    // over" to "delivery costs nothing over" reds it, rc=1.
    expect(
      await screen.findByText(
        "Added at checkout, where collection is free and delivery is free over £25.00."
      )
    ).toBeInTheDocument()
  })

  it("no longer labels the item subtotal 'Total'", async () => {
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    expect(screen.getByText(/^subtotal$/i)).toBeInTheDocument()
    expect(screen.queryByText(/^total$/i)).not.toBeInTheDocument()
  })

  it("says delivery is Free once the subtotal clears the shop's threshold", async () => {
    seedBasket(2600)
    renderCart()
    await screen.findByText("Zobo")

    expect(await screen.findByText(/^free$/i)).toBeInTheDocument()
    expect(screen.queryByText(/from £3\.50/i)).not.toBeInTheDocument()
  })

  it("degrades to no delivery claim when the shop cannot be fetched", async () => {
    mockedGet.mockRejectedValue(new Error("upstream down"))
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    // Silence beats a guess: an unknown fee must not render as £0.00 (WR-04),
    // and the removed "Total" must not come back as a fallback.
    expect(screen.queryByText(/^delivery$/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/^total$/i)).not.toBeInTheDocument()
    expect(screen.getByText(/^subtotal$/i)).toBeInTheDocument()
  })
})

describe("Shop cart page — the minimum-order rule (COR-3)", () => {
  beforeEach(() => {
    mockedGet.mockReset()
    mockedGet.mockResolvedValue({ data: FEE_SHOP })
  })
  afterEach(() => {
    localStorage.clear()
  })

  it("states the shortfall in the same words as the floating bar", async () => {
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    // 1000 - 300 = 700. The number comes from lib/minimum-order.ts, which is
    // also what the bar and checkout call — one rule, three screens.
    expect(
      await screen.findByText(/add £7\.00 to order · min £10\.00/i)
    ).toBeInTheDocument()
  })

  it("disables the checkout CTA below the minimum instead of leading the customer into a dead end", async () => {
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    const cta = await screen.findByRole("button", { name: /proceed to checkout/i })
    expect(cta).toBeDisabled()
    // and it is genuinely not navigable — no link version alongside it.
    expect(
      screen.queryByRole("link", { name: /proceed to checkout/i })
    ).not.toBeInTheDocument()
  })

  it("leaves the CTA navigable once the basket meets the minimum", async () => {
    seedBasket(1000)
    renderCart()
    await screen.findByText("Zobo")

    const cta = await screen.findByRole("link", { name: /proceed to checkout/i })
    expect(cta.getAttribute("href")).toBe(`/shop/${SLUG}/checkout`)
    expect(screen.queryByText(/to order · min/i)).not.toBeInTheDocument()
  })

  it("says nothing about a minimum when the shop has none", async () => {
    mockedGet.mockResolvedValue({ data: { ...FEE_SHOP, minimumOrderPennies: null } })
    seedBasket(300)
    renderCart()
    await screen.findByText("Zobo")

    expect(screen.queryByText(/to order · min/i)).not.toBeInTheDocument()
    expect(
      await screen.findByRole("link", { name: /proceed to checkout/i })
    ).toBeInTheDocument()
  })
})
