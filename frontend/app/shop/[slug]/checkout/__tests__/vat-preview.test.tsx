/**
 * COR-6 (QA-council 20260902-134741) — the checkout VAT preview must follow the BASKET's rate.
 *
 * The page used to compute `Math.floor((previewTotalPennies * 20) / 120)` and print a hardcoded
 * "VAT (incl. 20%)" label, because `PublicProductDto` carried no `vatRate` and the client
 * structurally could not resolve one. Most cold takeaway food is ZERO-rated (HMRC VAT Notice
 * 709/1, re-read 2026-09-03), so on such a basket the customer was shown a VAT figure before
 * paying and a contradicting figure on the confirmation screen one screen later.
 *
 * FALSIFIABILITY NOTE — this is why the ZERO arm exists. Every one of the 22 seeded products on
 * the dev DB is STANDARD (`select vat_rate, count(*) from products group by 1` -> STANDARD 22),
 * so a preview-vs-confirmation assertion run against live data passes by coincidence on the
 * BROKEN tree. The zero-rated fixture in `scripts/seed-e2e-fixtures.sh` is the live arming step;
 * this file is its unit-level twin, and its ZERO arm fails on the pre-COR-6 page.
 */

import { Suspense } from "react"
import { render, screen } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

if (!globalThis.crypto || typeof globalThis.crypto.randomUUID !== "function") {
  Object.defineProperty(globalThis, "crypto", {
    value: { ...(globalThis.crypto || {}), randomUUID: () => "test-uuid-0000" },
    configurable: true,
  })
}

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))
jest.mock("@/lib/order-history", () => ({ saveLocalOrder: jest.fn() }))
jest.mock("@stripe/stripe-js", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

// COLLECTION keeps the delivery fee out of the arithmetic so each arm isolates the VAT rule.
const SHOP = {
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
  minimumOrderPennies: 0,
  deliveryFeePennies: 0,
  freeDeliveryThresholdPennies: null,
  tags: null,
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedCart(lines: { pricePennies: number; quantity: number; vatRate: string | null }[]) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: lines.map((line, i) => ({
        productId: `p${i}`,
        title: `Line ${i}`,
        pricePennies: line.pricePennies,
        quantity: line.quantity,
        imageUrl: null,
        category: "Mains",
        vatRate: line.vatRate,
      })),
    })
  )
}

function renderCheckout() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockResolvedValue({ data: SHOP })
})

afterEach(() => {
  localStorage.clear()
})

describe("COR-6: the checkout VAT preview follows the basket's rate", () => {
  it("shows GBP 0.00 and a zero-rated label for an all-ZERO basket", async () => {
    // 1200p of cold takeaway food. The pre-COR-6 page printed 1200*20/120 = 200p here.
    seedCart([{ pricePennies: 1200, quantity: 1, vatRate: "ZERO" }])
    renderCheckout()

    expect(await screen.findByText("VAT (zero-rated)")).toBeInTheDocument()
    // The label alone is not enough — the FIGURE is what the customer compares against the
    // confirmation screen, and 200p is precisely what the old arithmetic produced.
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
    const vatRow = screen.getByText("VAT (zero-rated)").parentElement
    expect(vatRow).toHaveTextContent("£0.00")
  })

  it("still shows the 20% figure for a standard-rated basket — no regression", async () => {
    seedCart([{ pricePennies: 1200, quantity: 1, vatRate: "STANDARD" }])
    renderCheckout()

    expect(await screen.findByText("VAT (incl. 20%)")).toBeInTheDocument()
    const vatRow = screen.getByText("VAT (incl. 20%)").parentElement
    expect(vatRow).toHaveTextContent("£2.00")
  })

  it("uses the 5% fraction for a reduced-rated basket", async () => {
    // 1050p at 5% -> 1050*5/105 = 50p. At the old hardcoded 20% it would have read 175p.
    seedCart([{ pricePennies: 1050, quantity: 1, vatRate: "REDUCED" }])
    renderCheckout()

    expect(await screen.findByText("VAT (incl. 5%)")).toBeInTheDocument()
    const vatRow = screen.getByText("VAT (incl. 5%)").parentElement
    expect(vatRow).toHaveTextContent("£0.50")
  })

  it("resolves a MIXED basket to the predominant rate, as VatCalculator does", async () => {
    // A big zero-rated line beside a small standard line: net 5000 ZERO vs net 100 STANDARD.
    seedCart([
      { pricePennies: 5000, quantity: 1, vatRate: "ZERO" },
      { pricePennies: 120, quantity: 1, vatRate: "STANDARD" },
    ])
    renderCheckout()

    expect(await screen.findByText("VAT (zero-rated)")).toBeInTheDocument()
  })

  it("treats a basket stored before COR-6 (no rate at all) as STANDARD — never silently zero", async () => {
    // Old localStorage baskets carry no vatRate. Resolving them to ZERO would show a VAT-free
    // basket that is then taxed at checkout; STANDARD is the conservative direction and is
    // exactly what those baskets already displayed.
    seedCart([{ pricePennies: 1200, quantity: 1, vatRate: null }])
    renderCheckout()

    expect(await screen.findByText("VAT (incl. 20%)")).toBeInTheDocument()
  })
})
