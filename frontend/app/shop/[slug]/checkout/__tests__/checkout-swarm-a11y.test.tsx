/**
 * Four checkout findings from the 2026-10-19 staging persona swarm (epic issue 964), each asserted
 * on the rendered form:
 *
 *  - SW-MRC-07: every text field shares ONE focus style, the brand-700 ring (#6E1D21, about 11:1
 *    on the white field), never the pale amber-200 ring that measured about 1.2:1 to 1.7:1.
 *  - SW-MRC-08: the allergy-note count is visible and describes the field but is NOT a live region
 *    (it spoke on every keystroke); a separate status region speaks only at the 450 and 500 marks.
 *  - SW-MRC-11: the three required delivery-address fields say so to assistive technology.
 *  - SW-SAM-10: the contact details carry a link to the privacy notice where they are collected.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import * as allergenPanel from "@/components/storefront/order-allergen-panel"

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
jest.mock("@stripe/stripe-js/pure", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock
const { ALLERGY_NOTE_LABEL_COPY } = allergenPanel

const SLUG = "jollof-express"
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
  freeDeliveryThresholdPennies: 0,
  tags: null,
  acceptsCardPayments: false,
}
const PRODUCTS = {
  Mains: [
    {
      id: "p1",
      title: "Jollof Rice",
      description: null,
      imageUrl: null,
      imageUrls: [],
      ingredientsText: "rice, tomato",
      allergenMask: 0,
      pricePennies: 1000,
      category: "Mains",
      dietaryTags: null,
      preparationTimeMinutes: null,
      featured: false,
      inStock: true,
    },
  ],
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

async function renderCheckout() {
  localStorage.setItem(
    `jtoye-cart-${SLUG}`,
    JSON.stringify({
      shopSlug: SLUG,
      items: [{ productId: "p1", title: "Jollof Rice", pricePennies: 1000, quantity: 1, imageUrl: null, category: "Mains" }],
    })
  )
  const utils = render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
  await waitFor(() => expect(screen.getByLabelText(/full name/i)).toBeInTheDocument())
  return utils
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? PRODUCTS : SHOP })
  )
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
})

/** Every control a customer types into: text-like inputs and textareas, never the checkbox. */
function typedFields(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>("form input, form textarea")).filter(
    (el) =>
      !["checkbox", "radio", "hidden", "submit", "button"].includes((el.getAttribute("type") ?? "text").toLowerCase())
  )
}

describe("SW-MRC-07 — one visible focus style on every checkout field", () => {
  it("gives every typed field the brand-700 focus ring and no pale amber ring", async () => {
    const { container } = await renderCheckout()
    const fields = typedFields(container)
    // Floor: Delivery shows address1, address2, city, postcode, then name, email, phone, notes,
    // allergy note. A form that rendered none of them must not pass by having nothing to check.
    expect(fields.length).toBeGreaterThanOrEqual(9)

    const off = fields.filter((el) => {
      const cls = el.className.split(/\s+/)
      return (
        !cls.includes("focus:ring-brand-700") ||
        !cls.includes("focus:border-brand-700") ||
        cls.some((c) => /^focus:(ring|border)-amber-/.test(c))
      )
    })
    expect(off.map((el) => el.id)).toEqual([])
  })
})

describe("SW-MRC-08 — the allergy-note count does not speak on every keystroke", () => {
  it("keeps the count visible and describing the field, but not as a live region", async () => {
    await renderCheckout()
    const field = screen.getByLabelText(ALLERGY_NOTE_LABEL_COPY)
    const count = screen.getByTestId("allergy-note-count")
    expect(count).toHaveTextContent("0 of 500")
    expect(count).not.toHaveAttribute("aria-live")
    expect(count).not.toHaveAttribute("role")
    expect((field.getAttribute("aria-describedby") ?? "").split(/\s+/)).toContain(count.id)

    fireEvent.change(field, { target: { value: "No sesame" } })
    expect(count).toHaveTextContent("9 of 500")
  })

  it("announces only at the thresholds: silent below 450, a warning from 450, the limit at 500", async () => {
    await renderCheckout()
    const field = screen.getByLabelText(ALLERGY_NOTE_LABEL_COPY)
    const live = screen.getByTestId("allergy-note-limit")
    expect(live).toHaveAttribute("aria-live", "polite")

    const at = (n: number) => {
      fireEvent.change(field, { target: { value: "x".repeat(n) } })
      return live.textContent
    }
    expect(at(10)).toBe("")
    expect(at(449)).toBe("")
    const warn = at(450)
    expect(warn).not.toBe("")
    // The text does not change between keystrokes inside a band, so nothing re-announces.
    expect(at(451)).toBe(warn)
    expect(at(499)).toBe(warn)
    const limit = at(500)
    expect(limit).not.toBe(warn)
    expect(limit).toMatch(/500/)
    expect(at(120)).toBe("")
  })
})

describe("SW-MRC-11 — required delivery fields say they are required", () => {
  it("marks address line 1, town and postcode aria-required while Delivery is chosen", async () => {
    await renderCheckout()
    expect(screen.getByRole("button", { name: /delivery/i })).toHaveAttribute("aria-pressed", "true")
    for (const label of [/address line 1/i, /town \/ city/i, /^postcode/i]) {
      expect(screen.getByLabelText(label)).toHaveAttribute("aria-required", "true")
    }
    // Optional stays optional.
    expect(screen.getByLabelText(/address line 2/i)).not.toHaveAttribute("aria-required")
  })

  it("asks for no address at all once Collection is chosen", async () => {
    await renderCheckout()
    // CONTROL: the attribute is there before the switch, so "none after" is a measurement.
    expect(document.querySelectorAll('[aria-required="true"]').length).toBe(3)
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
    expect(screen.queryByLabelText(/address line 1/i)).toBeNull()
    expect(document.querySelectorAll('[aria-required="true"]').length).toBe(0)
  })
})

describe("SW-SAM-10 — the privacy notice is linked where contact details are collected", () => {
  it("puts a link to /legal/privacy inside the Your details section", async () => {
    await renderCheckout()
    const heading = screen.getByRole("heading", { name: /your details/i })
    const section = heading.parentElement as HTMLElement
    const link = Array.from(section.querySelectorAll("a")).find((a) => a.getAttribute("href") === "/legal/privacy")
    expect(link).toBeDefined()
    expect(link).toHaveTextContent(/privacy notice/i)
    const line = link!.closest("p")?.textContent ?? ""
    // Plain wording: the line names the shop that receives the details, in the site's voice.
    await waitFor(() => expect(link!.closest("p")).toHaveTextContent(/Jollof Express/))
    expect(line).not.toMatch(/!|seamless/i)
  })
})
