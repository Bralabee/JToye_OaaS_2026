/**
 * Checkout form accessibility: A11Y-08 (SC 1.3.5 autocomplete) and A11Y-07 (announced field
 * errors) — Phase 31-14.
 *
 * ⚠ WHY THESE ARE DOM/SOURCE ASSERTIONS AND NOT AN AXE SCAN — DO NOT "SIMPLIFY" THEM INTO ONE.
 *
 * axe is BLIND to WCAG 2.1 SC 1.3.5 (Identify Input Purpose). There is no axe rule that fails a
 * text input which collects the user's name and carries no `autocomplete` token. So a
 * zero-violation axe run over this form is entirely compatible with a level-AA failure on every
 * field in it. 31-13 publishes a conformance statement that depends on this being fixed rather
 * than excepted; folding this file into an axe scan would silently un-fix it while staying green.
 *
 * ⚠ WHY THE ASSERTION COMPARES TWO COUNTS RATHER THAN CHECKING ONE FIELD.
 *
 * A spot-check on `email` passes while six other inputs remain untokened. The assertion below
 * counts the user-data inputs and counts the valid tokens and compares the two numbers, with a
 * FLOOR on the input count so that a form which rendered nothing (both counts zero) cannot pass
 * — "found nothing" is never "clean".
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
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
const mockedPost = publicApiClient.post as jest.Mock

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

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

/**
 * The HTML autofill token vocabulary subset this form can legitimately use.
 * An invented value (e.g. "postcode", "fullname") is a 1.3.5 failure that LOOKS fixed, so the
 * membership check below is as load-bearing as the count comparison.
 */
const VALID_AUTOFILL_TOKENS = new Set([
  "name",
  "given-name",
  "family-name",
  "email",
  "tel",
  "tel-national",
  "street-address",
  "address-line1",
  "address-line2",
  "address-line3",
  "address-level1",
  "address-level2",
  "postal-code",
  "country",
  "country-name",
  "organization",
])

function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        {
          productId: "p1",
          title: "Jollof Rice",
          pricePennies: 1000,
          quantity: 1,
          imageUrl: null,
          category: "Mains",
        },
      ],
    })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
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

/**
 * The inputs SC 1.3.5 actually governs: those collecting information ABOUT THE USER.
 *
 * ⚠ MEASURED, NOT ASSUMED. The rendered DOM carries EIGHT `<input>` elements, not the seven in
 * the page source: Radix Checkbox mounts a hidden native `<input type="checkbox">` (its
 * "BubbleInput") so the acknowledgement participates in form semantics. A consent checkbox is not
 * an identity field and correctly takes no autofill token, so a naive `querySelectorAll("input")`
 * comparison fails 7 !== 8 and would tempt a future reader to "fix" it by loosening the
 * comparison to `>=`, which is precisely the assertion that stops catching a missing token.
 * Filtering by control type keeps the comparison an EQUALITY.
 */
const NON_DATA_INPUT_TYPES = new Set(["checkbox", "radio", "hidden", "submit", "button", "reset"])

function userDataInputs(container: HTMLElement): HTMLInputElement[] {
  return Array.from(container.querySelectorAll("input")).filter(
    (i) => !NON_DATA_INPUT_TYPES.has((i.getAttribute("type") ?? "text").toLowerCase())
  )
}

/**
 * Fill the three `required` fields in "Your details".
 *
 * NOT optional scaffolding: jsdom runs HTML constraint validation when a submit button is
 * activated, so with these empty the form NEVER fires `submit` and the page's own handler never
 * runs. A focus assertion would then read `document.activeElement === <body>` and be blamed on the
 * focus code rather than on the browser-level gate that fired first.
 */
function fillRequiredDetails() {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), {
    target: { value: "ade@example.com" },
  })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900000" } })
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) {
      return Promise.resolve({ data: PRODUCTS })
    }
    return Promise.resolve({ data: SHOP })
  })
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
})

describe("A11Y-08 — every checkout input that collects the user's own data carries a token", () => {
  it("token count EQUALS user-data input count (a spot-check would miss six of seven)", async () => {
    seedCart()
    const { container } = renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    // DELIVERY is the default, so the address block is mounted and all seven inputs exist.
    const inputs = userDataInputs(container)

    // FLOOR — without this, a form that rendered nothing gives 0 === 0 and passes.
    expect(inputs.length).toBeGreaterThanOrEqual(7)

    const tokened = inputs.filter((i) => (i.getAttribute("autocomplete") ?? "").trim().length > 0)

    // The comparison itself. Both numbers are recorded in 31-14-SUMMARY.md.
    expect(tokened.length).toBe(inputs.length)
  })

  it("the acknowledgement checkbox is the ONLY untokened input, and is correctly excluded", async () => {
    // Pins the measurement above rather than leaving the filter unexplained: if a future change
    // adds a real data field that the filter silently swallows, this count moves and reds.
    seedCart()
    const { container } = renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    const all = Array.from(container.querySelectorAll("input"))
    const excluded = all.filter((i) =>
      NON_DATA_INPUT_TYPES.has((i.getAttribute("type") ?? "text").toLowerCase())
    )

    expect(all.length).toBe(8)
    expect(userDataInputs(container).length).toBe(7)
    expect(excluded.length).toBe(1)
    expect(excluded[0].getAttribute("type")).toBe("checkbox")
  })

  it("every token is a real HTML autofill token, not an invented one", async () => {
    seedCart()
    const { container } = renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    const inputs = userDataInputs(container)
    expect(inputs.length).toBeGreaterThanOrEqual(7)

    for (const input of inputs) {
      const token = (input.getAttribute("autocomplete") ?? "").trim()
      expect(VALID_AUTOFILL_TOKENS.has(token)).toBe(true)
    }
  })

  it("maps each field to the SEMANTICALLY correct token", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    const expected: Record<string, string> = {
      address1: "address-line1",
      address2: "address-line2",
      city: "address-level2",
      postcode: "postal-code",
      name: "name",
      email: "email",
      phone: "tel",
    }

    for (const [id, token] of Object.entries(expected)) {
      const el = document.getElementById(id)
      expect(el).not.toBeNull()
      expect(el!.getAttribute("autocomplete")).toBe(token)
    }
  })

  it("the notes textarea is correctly EXCLUDED — it collects no data about the user", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    const notes = document.getElementById("notes")
    expect(notes).not.toBeNull()
    expect(notes!.tagName).toBe("TEXTAREA")
    // SC 1.3.5 covers inputs collecting information ABOUT THE USER. Free-text order notes are
    // not such a field; giving it a token would be wrong, not thorough.
    expect(notes!.getAttribute("autocomplete")).toBeNull()
  })
})

describe("A11Y-07 — field errors are announced and associated", () => {
  it("an invalid postcode sets aria-invalid and points aria-describedby at the message", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    fillRequiredDetails()
    fireEvent.change(screen.getByLabelText(/address line 1/i), {
      target: { value: "12 Coldharbour Lane" },
    })
    fireEvent.change(screen.getByLabelText(/town \/ city/i), { target: { value: "London" } })
    fireEvent.change(screen.getByLabelText(/postcode/i), { target: { value: "NOT-A-POSTCODE" } })

    fireEvent.click(screen.getByRole("button", { name: /place order/i }))

    await waitFor(() => {
      const postcode = document.getElementById("postcode")!
      expect(postcode).toHaveAttribute("aria-invalid", "true")
      const describedBy = postcode.getAttribute("aria-describedby")
      expect(describedBy).toBeTruthy()
      expect(document.getElementById(describedBy!)).toHaveTextContent(
        "Enter a valid UK postcode (e.g. SW1A 1AA)"
      )
    })
  })

  it("a valid field carries no aria-invalid=true", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    const postcode = document.getElementById("postcode")!
    expect(postcode.getAttribute("aria-invalid")).not.toBe("true")
  })

  it("moves focus to the first invalid field on a refused submit", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    fillRequiredDetails()
    // Leave address line 1 empty — it is the first invalid field.
    fireEvent.click(screen.getByRole("button", { name: /place order/i }))

    await waitFor(() => {
      expect(document.activeElement).toBe(document.getElementById("address1"))
    })
  })

  it("no order is created while any field is invalid", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    fillRequiredDetails()
    fireEvent.click(screen.getByRole("button", { name: /place order/i }))

    await waitFor(() => {
      expect(document.getElementById("address1")).toHaveAttribute("aria-invalid", "true")
    })
    expect(mockedPost).not.toHaveBeenCalled()
  })
})

describe("A11Y-07 — the generic submit error is announced", () => {
  it("renders the server error in a role=alert region", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    // Switch to COLLECTION so address validation passes, fill details, acknowledge, submit.
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
    fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
    fireEvent.change(screen.getByLabelText(/email address/i), {
      target: { value: "ade@example.com" },
    })
    fireEvent.change(screen.getByLabelText(/phone number/i), {
      target: { value: "07700900000" },
    })
    fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))

    mockedPost.mockRejectedValue({ response: { status: 500, data: {} } })
    fireEvent.click(screen.getByRole("button", { name: /place order/i }))

    await waitFor(() => {
      const alerts = screen.getAllByRole("alert")
      expect(alerts.length).toBeGreaterThan(0)
    })
  })
})
