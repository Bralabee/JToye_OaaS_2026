/**
 * Checkout, #841 and #460.
 *
 * #841 / UXT-072: an implausible name, email or phone ("x", "call me", name@example) is stopped at the form
 * with a message on the field, focus on it, and NOTHING is sent, because an order nobody can call back about
 * is a dead end for the shop.
 *
 * #460 / UXT-030: a shop that limits delivery by distance says so under the postcode; when core refuses a
 * delivery as out of range the sentence lands on the postcode field (not a generic banner) and collection is
 * offered.
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
jest.mock("@stripe/stripe-js/pure", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock

const SLUG = "jollof-express"
const CATALOGUE = { Mains: [{ id: "p1", allergenMask: 0 }] }
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
  deliveryFeePennies: 299,
  freeDeliveryThresholdPennies: 3000,
  tags: null,
  offersDelivery: true,
  offersCollection: true,
}

function mockShop(shop: unknown) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? CATALOGUE : shop })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedCart() {
  localStorage.setItem(
    `jtoye-cart-${SLUG}`,
    JSON.stringify({
      shopSlug: SLUG,
      items: [{ productId: "p1", title: "Suya", pricePennies: 2000, quantity: 1, imageUrl: null, category: "Mains" }],
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

function fill(name: string, email: string, phone: string) {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: name } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: email } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: phone } })
}

function fillAddress(postcode = "SW9 8LF") {
  fireEvent.change(screen.getByLabelText(/address line 1/i), { target: { value: "12 Coldharbour Lane" } })
  fireEvent.change(screen.getByLabelText(/town/i), { target: { value: "London" } })
  fireEvent.change(screen.getByLabelText(/postcode/i), { target: { value: postcode } })
}

function place() {
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

afterEach(() => {
  localStorage.clear()
})

describe("implausible contact details are stopped at the form (#841)", () => {
  it.each([
    ["phone", "Ade Johnson", "ade@example.com", "call me", /phone number/i, "Enter a valid phone number"],
    ["email", "Ade Johnson", "name@example", "07700 900000", /email address/i, "Enter a valid email address"],
    ["name", "x", "ade@example.com", "07700 900000", /full name/i, "Enter your name"],
  ])("a bad %s shows a message on that field, flags it, and sends nothing", async (_f, name, email, phone, label, message) => {
    mockShop(SHOP)
    seedCart()
    renderCheckout()
    await screen.findByRole("button", { name: /^collection$/i })
    fireEvent.click(screen.getByRole("button", { name: /^collection$/i }))

    fill(name, email, phone)
    place()

    const field = await screen.findByLabelText(label)
    await waitFor(() => expect(field).toHaveAttribute("aria-invalid", "true"))
    const describedBy = field.getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(message)
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("control: plausible details are sent", async () => {
    mockShop(SHOP)
    mockedPost.mockResolvedValue({ data: { orderNumber: "ORD-1", status: "PENDING", clientSecret: null, allergenWarnings: [] } })
    seedCart()
    renderCheckout()
    await screen.findByRole("button", { name: /^collection$/i })
    fireEvent.click(screen.getByRole("button", { name: /^collection$/i }))

    fill("Ade Johnson", "ade@example.com", "+44 7700 900000")
    place()

    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
  })
})

describe("delivery radius at checkout (#460)", () => {
  const WITH_RADIUS = { ...SHOP, deliveryRadiusKm: 10 }

  it("states the radius under the postcode when the shop limits delivery, and nothing when it does not", async () => {
    mockShop(WITH_RADIUS)
    seedCart()
    const { unmount } = renderCheckout()
    await screen.findByRole("button", { name: /^delivery$/i })
    expect(await screen.findByText("This kitchen delivers within 10 km.")).toBeInTheDocument()
    unmount()

    mockShop(SHOP)
    renderCheckout()
    await screen.findByRole("button", { name: /^delivery$/i })
    expect(screen.queryByText(/This kitchen delivers within/)).not.toBeInTheDocument()
  })

  it("puts an out-of-range refusal on the postcode field, flags it, and keeps the order for a closer address", async () => {
    mockShop(WITH_RADIUS)
    mockedPost.mockRejectedValueOnce({
      response: {
        status: 422,
        headers: {},
        data: {
          type: "https://jtoye.uk/errors/delivery-out-of-range",
          title: "Outside the delivery area",
          status: 422,
          detail: "server prose",
          code: "DELIVERY_OUT_OF_RANGE",
          deliveryRadiusKm: 10,
          distanceKm: 530.2,
        },
      },
    })
    seedCart()
    renderCheckout()
    await screen.findByRole("button", { name: /^delivery$/i })
    fill("Ade Johnson", "ade@example.com", "07700 900000")
    fillAddress("EH1 1YZ")
    place()

    const postcode = screen.getByLabelText(/postcode/i)
    await waitFor(() => expect(postcode).toHaveAttribute("aria-invalid", "true"))
    const message = document.getElementById(postcode.getAttribute("aria-describedby") as string)
    expect(message).toHaveTextContent("within 10 km")
    expect(message).toHaveTextContent("530.2 km away")
    expect(message).toHaveTextContent(/choose collection/i)
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })
})
