/**
 * #812 (D-15) at the checkout, and D-08's recorded set on the confirmation (Phase 31.1-15).
 *
 * The allergy note is its OWN field, separate from the delivery/order notes: 31.1-13 put it on the
 * kitchen ticket with a vendor acknowledgement, so it must not be buried in "ring the bell". It is
 * optional, at most 500 characters with a live count, attributed to the named shop, sent as
 * `allergyNote` (trimmed; absent when blank — a request without it fingerprints exactly as before,
 * 31.1-13), and SIGNED with the rest of the intent.
 *
 * It is never persisted client-side (T-31.1-55): the only place the value goes is the POST body.
 *
 * The second half: the COD confirmation and the card payment step render the RECORDED allergen
 * set from the confirmation response (RecordedAllergenSet), not from anything the client holds.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  ALLERGEN_ACK_LABEL_COPY,
  ALLERGY_NOTE_LABEL_COPY,
  ALLERGY_NOTE_MAX_LENGTH,
  allergyNoteHelpCopy,
} from "@/components/storefront/order-allergen-panel"
import {
  RECORDED_ACK_HEADING_COPY,
  RECORDED_SET_HEADING_COPY,
} from "@/components/storefront/recorded-allergen-set"

let uuidCounter = 0
const nextUuid = () => `00000000-0000-4000-8000-${String(++uuidCounter).padStart(12, "0")}` as const

beforeAll(() => {
  const existing = globalThis.crypto as Crypto | undefined
  if (existing && typeof existing.randomUUID === "function") {
    jest.spyOn(existing, "randomUUID").mockImplementation(nextUuid)
  } else {
    Object.defineProperty(globalThis, "crypto", {
      value: { ...(existing ?? {}), randomUUID: nextUuid },
      configurable: true,
    })
  }
})

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
const mockedPost = publicApiClient.post as jest.Mock

const SLUG = "rosies-kitchen"
const STORAGE_KEY = `jtoye-cart-${SLUG}`
const SHOP_NAME = "Rosie's Kitchen"

const SHOP = {
  slug: SLUG,
  name: SHOP_NAME,
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

const MILK = 1 << 6
const PRODUCTS = {
  Mains: [
    {
      id: "p1",
      title: "Fish Pie",
      description: null,
      imageUrl: null,
      imageUrls: [],
      ingredientsText: "",
      allergenMask: MILK,
      pricePennies: 900,
      category: "Mains",
      dietaryTags: null,
      preparationTimeMinutes: null,
      featured: false,
      inStock: true,
    },
  ],
}

const CONFIRMATION = {
  orderNumber: "ORD-NOTE-1",
  status: "PENDING",
  subtotalPennies: 900,
  deliveryFeePennies: 0,
  vatRate: "ZERO",
  vatAmountPennies: 0,
  totalAmountPennies: 900,
  shopName: SHOP_NAME,
  itemCount: 1,
  clientSecret: null as string | null,
  allergenWarnings: [],
  acknowledgedAllergenMask: MILK,
  acknowledgedAllergens: ["Milk"],
  recordedAllergens: ["Milk"],
  recordedAllergenFlags: [{ productName: "Fish Pie", allergenBit: 3, allergenName: "Fish" }],
}

function mockEndpoints() {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? PRODUCTS : SHOP })
  )
}

function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [{ productId: "p1", title: "Fish Pie", pricePennies: 900, quantity: 1, imageUrl: null, category: "Mains" }],
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

const noteField = () => screen.getByLabelText(ALLERGY_NOTE_LABEL_COPY) as HTMLTextAreaElement
const submitButton = () => screen.getByRole("button", { name: /place order/i })

async function armAndSubmit({ note, notes }: { note?: string; notes?: string } = {}) {
  seedCart()
  renderCheckout()
  await waitFor(() => expect(screen.getAllByTestId("allergen-chip").map((c) => c.textContent)).toEqual(["Milk"]))
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ravi Patel" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ravi@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900123" } })
  if (notes !== undefined) fireEvent.change(screen.getByLabelText(/order notes/i), { target: { value: notes } })
  if (note !== undefined) fireEvent.change(noteField(), { target: { value: note } })
  fireEvent.click(screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY }))
  fireEvent.click(submitButton())
  await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
}

const body = (i = 0) => mockedPost.mock.calls[i][1] as Record<string, unknown>

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

beforeEach(() => {
  uuidCounter = 0
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockEndpoints()
  mockedPost.mockResolvedValue({ data: CONFIRMATION })
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("the allergy note field (#812, D-15)", () => {
  it("is a labelled, optional textarea, separate from the order notes, capped at 500", async () => {
    seedCart()
    renderCheckout()
    const field = await waitFor(() => noteField())

    expect(ALLERGY_NOTE_LABEL_COPY).toBe("Allergy or dietary note (optional)")
    expect(ALLERGY_NOTE_MAX_LENGTH).toBe(500)
    expect(field.tagName).toBe("TEXTAREA")
    expect(field.id).toBe("allergy-note")
    expect(field).toHaveAttribute("maxLength", "500")
    expect(field).not.toBeRequired()
    expect(field).not.toBe(screen.getByLabelText(/order notes/i))
  })

  it("says where the note goes, naming the shop", async () => {
    seedCart()
    renderCheckout()
    expect(allergyNoteHelpCopy(SHOP_NAME)).toBe(
      "Goes to Rosie's Kitchen so they can prepare your order. Only write what the kitchen needs to know."
    )
    await screen.findByText(allergyNoteHelpCopy(SHOP_NAME))
    // The help text is the field's description, so a screen reader hears it with the label.
    const describedBy = (noteField().getAttribute("aria-describedby") ?? "").split(/\s+/)
    const help = screen.getByText(allergyNoteHelpCopy(SHOP_NAME))
    expect(describedBy).toContain(help.id)
  })

  it("shows a live 'N of 500' count as the customer types", async () => {
    seedCart()
    renderCheckout()
    await waitFor(() => noteField())

    const count = screen.getByTestId("allergy-note-count")
    expect(count).toHaveTextContent("0 of 500")
    expect(count).toHaveAttribute("aria-live", "polite")

    fireEvent.change(noteField(), { target: { value: "No sesame please" } })
    expect(count).toHaveTextContent("16 of 500")
  })

  it("posts the note as allergyNote, trimmed, separately from notes", async () => {
    await armAndSubmit({ note: "  Severe sesame allergy  ", notes: "Ring the bell" })

    expect(body().allergyNote).toBe("Severe sesame allergy")
    expect(body().notes).toBe("Ring the bell")
  })

  it("does not send a blank note at all — the field is absent from the body", async () => {
    await armAndSubmit({ note: "    " })
    // Asserted on the WIRE form: axios serialises the body with JSON.stringify, which drops an
    // undefined member — the same rule lib/checkout-idempotency.ts signs by. Non-vacuity: the same
    // serialisation does keep a real note (the arm above).
    const wire = JSON.parse(JSON.stringify(body())) as Record<string, unknown>
    expect(Object.prototype.hasOwnProperty.call(wire, "allergyNote")).toBe(false)
    expect(wire.acknowledgedAllergenMask).toBe(MILK)
  })

  it("never writes the note to browser storage (T-31.1-55)", async () => {
    await armAndSubmit({ note: "Severe sesame allergy" })
    await screen.findByText(/order confirmed/i)
    const stored = [
      ...Object.keys(localStorage).map((k) => localStorage.getItem(k) ?? ""),
      ...Object.keys(sessionStorage).map((k) => sessionStorage.getItem(k) ?? ""),
    ].join("\n")
    // Non-vacuity: something WAS stored (the checkout email), so the scan read real storage.
    expect(stored).toContain("ravi@example.com")
    expect(stored).not.toContain("sesame")
  })
})

describe("the confirmation shows the RECORDED set from the response (D-08)", () => {
  it("COD confirmation renders the acknowledged and recorded sets, and the flag as a Check line", async () => {
    await armAndSubmit()
    await screen.findByText(/order confirmed/i)

    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(`${RECORDED_ACK_HEADING_COPY} Milk`)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(`${RECORDED_SET_HEADING_COPY} Milk`)
    expect(screen.getByTestId("recorded-allergen-flag")).toHaveTextContent(/^Check — Fish Pie/)
  })

  it("renders what the SERVER recorded, not the basket the client computed", async () => {
    // The response says Gluten was recorded; the basket's client-side set is Milk. The block must
    // follow the response — it is the record, and the client view is not.
    mockedPost.mockResolvedValue({
      data: { ...CONFIRMATION, recordedAllergens: ["Gluten", "Milk"], recordedAllergenFlags: [] },
    })
    await armAndSubmit()
    await screen.findByText(/order confirmed/i)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(`${RECORDED_SET_HEADING_COPY} Gluten, Milk`)
  })

  it("the card payment step renders the recorded set too", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = "pk_test_recorded_set"
    mockedPost.mockResolvedValue({ data: { ...CONFIRMATION, clientSecret: "pi_x_secret_y" } })
    await armAndSubmit()

    await screen.findByRole("heading", { name: "Payment", level: 1 })
    expect(screen.getByTestId("recorded-ack")).toHaveTextContent(`${RECORDED_ACK_HEADING_COPY} Milk`)
    expect(screen.getByTestId("recorded-set")).toHaveTextContent(`${RECORDED_SET_HEADING_COPY} Milk`)
  })
})
