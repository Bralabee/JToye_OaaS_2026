/**
 * The payment-link page `/pay` (PAY-04 / D-03; UI-SPEC S3; plan 30-18).
 *
 * The link a shop sends is `/pay#token={token}` (owner decision 30-16 Task 1, "fragment"): the token
 * is in no request line and no access log. The island reads it from `location.hash` and POSTs it in a
 * JSON body — `POST /api/v1/public/payment-links/preview {token}` on mount (read-only, never spends the
 * link) and `POST /api/v1/public/payment-links/redeem {token}` only when "Continue to payment" is
 * pressed, and only after Stripe.js has loaded (UI-SPEC S3 steps 2a-2d).
 *
 * What is held here:
 *   - the customer sees who is asking and for how much before anything is spent (T-30-74);
 *   - redeem runs only after publishableKey() and an AWAITED loadStripeOnce() (T-30-72): the ordering
 *     arm compares invocationCallOrder, and every failure arm counts zero redeem calls;
 *   - after redeem the token leaves the address bar (history.replaceState to "/pay") before Stripe
 *     mounts, and the history.state resume entry carries the client secret and display fields only,
 *     never the token (T-30-73, T-30-129); nothing is written to local or session storage (a spy on
 *     Storage.prototype.setItem counts every write);
 *   - a direct-charge link initialises Stripe.js on the vendor's connected account (T-30-76).
 *
 * Harness: the checkout's card-unavailable harness — mocked public API, a throwing DEFAULT Stripe
 * entry (the page may only reach `/pure`), a `/pure` loadStripe each test controls, and Elements /
 * PaymentElement stand-ins. The PaymentElement stand-in renders a button that fires the `onLoadError`
 * prop it was given, so "the Payment Element failed to load" is a click.
 */
import { type ReactNode } from "react"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { loadStripe } from "@stripe/stripe-js/pure"
import publicApiClient from "@/lib/public-api-client"
import { resetStripeLoaderForTests } from "@/lib/stripe-loader"
import PayPage, { metadata } from "../page"
import { PayClient } from "../pay-client"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

// The DEFAULT entry must never be evaluated (#793): its import injects js.stripe.com.
jest.mock("@stripe/stripe-js", () => {
  throw new Error("default Stripe entry imported")
})

jest.mock("@stripe/stripe-js/pure", () => ({
  loadStripe: jest.fn(),
}))

// What useStripe() hands the card form. null = Stripe not ready (the Pay button stays disabled).
let mockStripeInstance: unknown = null
const elementsOptions: unknown[] = []
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ options, children }: { options: unknown; children: ReactNode }) => {
    elementsOptions.push(options)
    return <div data-testid="stripe-elements">{children}</div>
  },
  PaymentElement: ({ onLoadError }: { onLoadError?: (event: unknown) => void }) => (
    <button
      type="button"
      onClick={() => onLoadError?.({ elementType: "payment", error: { type: "api_error" } })}
    >
      simulate payment element load error
    </button>
  ),
  useStripe: () => mockStripeInstance,
  useElements: () => (mockStripeInstance ? { __fakeElements: true } : null),
}))

const mockedLoadStripe = loadStripe as unknown as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock
const mockedGet = publicApiClient.get as jest.Mock
/** The preview and redeem calls, split out of the one mocked POST so each has its own call order. */
const mockedPreview = jest.fn()
const mockedRedeem = jest.fn()

const PREVIEW_PATH = "/api/v1/public/payment-links/preview"
const REDEEM_PATH = "/api/v1/public/payment-links/redeem"
const PUBLISHABLE_KEY = "pk_test_pay_link_3018"
const TOKEN = "tok-pay-link-fragment-that-must-never-render-7Qx"
const ACCOUNT = "acct_1VENDOR3018"
const CLIENT_SECRET = "pi_3018_secret_abc"
const SHOP_NAME = "Mama Ade's Kitchen"
const ORDER_NUMBER = "ORD-30-18-0001"

// 1 Oct 2026 13:05 UTC is 14:05 in London (BST), so a page that formatted in UTC would read 13:05.
const PREVIEW = {
  shopName: SHOP_NAME,
  orderNumber: ORDER_NUMBER,
  amountPennies: 1250,
  currency: "gbp",
  purpose: "UPFRONT",
  expiresAt: "2026-10-01T13:05:00Z",
  state: "ACTIVE",
}

const REDEEMED = {
  linkId: "6f1c0000-0000-0000-0000-000000003018",
  orderId: "6f1c0000-0000-0000-0000-000000000001",
  purpose: "UPFRONT",
  amountPennies: 1250,
  stripePaymentIntentId: "pi_3018",
  clientSecret: CLIENT_SECRET,
  usedAt: "2026-09-30T10:00:00Z",
  stripeAccountId: ACCOUNT,
}

const COPY = {
  checking: "Checking your payment link…",
  continue: "Continue to payment",
  note: "This link opens once. After you continue, keep this page open until you've paid.",
  received: "Payment received",
  receivedBody: `You've paid £12.50 to ${SHOP_NAME} for order ${ORDER_NUMBER}. You can close this page.`,
}

/** A Stripe stand-in whose retrievePaymentIntent answers `status`. */
function fakeStripe(status = "requires_payment_method") {
  return {
    __fakeStripe: true,
    retrievePaymentIntent: jest.fn(() => Promise.resolve({ paymentIntent: { status, amount: 1250 } })),
  }
}

/** An axios-shaped rejection carrying an RFC 7807 body. */
function problem(status: number, detail = "server prose the page must not branch on") {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data: { type: "https://jtoye.uk/errors/x", title: "x", status, detail } },
  })
}

const ok = (data: unknown) => () => Promise.resolve({ status: 200, data })

/** Route the mocked POST by path. */
function serve(preview: () => Promise<unknown>, redeem?: () => Promise<unknown>) {
  mockedPreview.mockImplementation(preview)
  mockedRedeem.mockImplementation(redeem ?? (() => Promise.reject(new Error("redeem not expected"))))
  mockedPost.mockImplementation((url: string, body: unknown) => {
    if (url === PREVIEW_PATH) return mockedPreview(url, body)
    if (url === REDEEM_PATH) return mockedRedeem(url, body)
    return Promise.reject(new Error(`unexpected POST ${url}`))
  })
}

/** Open `/pay` the way the shop's link does: a fragment token, plus a router key that must survive. */
function openLink(path = `/pay#token=${TOKEN}`, state: unknown = { __NA: true, routerKey: "kept" }) {
  window.history.replaceState(state, "", path)
}

/** Exactly one h1 on the page, whatever the state (UI-SPEC S3 heading structure). */
function theOneH1(): HTMLElement {
  const h1s = screen.getAllByRole("heading", { level: 1 })
  expect(h1s).toHaveLength(1)
  return h1s[0]
}

async function activeLink() {
  render(<PayClient />)
  await screen.findByRole("heading", { level: 1, name: `Pay ${SHOP_NAME}` })
}

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
let setItem: jest.SpyInstance
let replaceState: jest.SpyInstance

beforeEach(() => {
  mockedPost.mockReset()
  mockedGet.mockReset()
  mockedPreview.mockReset()
  mockedRedeem.mockReset()
  mockedLoadStripe.mockReset()
  mockedLoadStripe.mockImplementation(() => Promise.resolve(fakeStripe()))
  resetStripeLoaderForTests()
  mockStripeInstance = null
  elementsOptions.length = 0
  window.localStorage.clear()
  window.sessionStorage.clear()
  process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
  openLink()
  setItem = jest.spyOn(Storage.prototype, "setItem")
  replaceState = jest.spyOn(window.history, "replaceState")
})

afterEach(() => {
  setItem.mockRestore()
  replaceState.mockRestore()
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("the /pay route shell (UI-SPEC S3 route and SEO hygiene)", () => {
  it("is titled, noindex,nofollow, with no canonical, Open Graph or other discoverability", () => {
    expect(metadata.title).toBe("Pay for your order — J'Toye")
    expect(metadata.description).toBe("Pay for an order you placed with a shop on J'Toye.")
    const robots = (metadata.robots ?? {}) as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
    expect(metadata.alternates).toBeUndefined()
    expect(metadata.openGraph).toBeUndefined()
  })

  it("wraps the island in the public shell and fetches nothing itself; the token never renders", async () => {
    serve(ok(PREVIEW))
    render(<PayPage />)
    // PublicShell's main landmark holds the island.
    const main = screen.getByRole("main")
    await screen.findByRole("heading", { level: 1, name: `Pay ${SHOP_NAME}` })
    expect(main).toContainElement(theOneH1())
    expect(mockedGet).toHaveBeenCalledTimes(0)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(document.body.innerHTML).not.toContain(TOKEN)
  })
})

describe("a customer opens a payment link, sees who and how much, presses Continue, and pays", () => {
  it("on mount shows 'Checking your payment link…' in role=status and asks the preview only, with the fragment token in the body", async () => {
    let answer: (v: unknown) => void = () => {}
    serve(() => new Promise((r) => (answer = r)))
    render(<PayClient />)

    const status = await screen.findByRole("status")
    expect(status).toHaveTextContent(COPY.checking)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPreview).toHaveBeenCalledWith(PREVIEW_PATH, { token: TOKEN })
    expect(mockedRedeem).toHaveBeenCalledTimes(0)
    // No Stripe request before the press (#793 and the UI-SPEC loading row).
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)

    answer({ status: 200, data: PREVIEW })
    await screen.findByRole("heading", { level: 1, name: `Pay ${SHOP_NAME}` })
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
  })

  it("an active link shows the shop, the purpose heading, the amount, the order, the London expiry, the single-use note and Continue", async () => {
    serve(ok(PREVIEW))
    await activeLink()

    const h1 = theOneH1()
    expect(h1).toHaveTextContent(`Pay ${SHOP_NAME}`)
    expect(h1).toHaveClass("text-2xl", "font-semibold")
    const amount = screen.getByText("£12.50")
    expect(amount).toHaveClass("text-2xl", "font-semibold", "tabular-nums")
    expect(screen.getByText(`Order ${ORDER_NUMBER}`)).toBeInTheDocument()
    expect(screen.getByText("This link expires 1 Oct 2026, 14:05")).toBeInTheDocument()
    expect(screen.getByText(COPY.note)).toBeInTheDocument()
    const cta = screen.getByRole("button", { name: COPY.continue })
    expect(cta).toHaveClass("h-12", "bg-oxblood")
    // The token is never rendered, and the address bar keeps it until the press (a reload of an
    // unused link still works).
    expect(document.body.innerHTML).not.toContain(TOKEN)
    expect(window.location.hash).toBe(`#token=${TOKEN}`)
  })

  it.each([
    ["DEPOSIT", `Pay your deposit to ${SHOP_NAME}`],
    ["BALANCE", `Pay the balance to ${SHOP_NAME}`],
  ])("a %s link is headed by its purpose", async (purpose, heading) => {
    serve(ok({ ...PREVIEW, purpose }))
    render(<PayClient />)
    await screen.findByRole("heading", { level: 1, name: heading })
  })

  it("a direct-charge link: Stripe loads BEFORE redeem, the token leaves the address bar, Stripe mounts on the vendor's account, and paying shows 'Payment received' focused", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    const confirmPayment = jest.fn(() => Promise.resolve({}))
    mockStripeInstance = { confirmPayment }
    await activeLink()

    fireEvent.click(screen.getByRole("button", { name: COPY.continue }))
    await screen.findByTestId("stripe-elements")

    // 2b before 2c: Stripe.js loaded (platform account) before the single-use link was spent.
    expect(mockedRedeem).toHaveBeenCalledTimes(1)
    expect(mockedRedeem).toHaveBeenCalledWith(REDEEM_PATH, { token: TOKEN })
    expect(mockedLoadStripe.mock.invocationCallOrder[0]).toBeLessThan(mockedRedeem.mock.invocationCallOrder[0])
    expect(mockedLoadStripe.mock.calls[0]).toEqual([PUBLISHABLE_KEY])

    // 2d: the address bar is "/pay" BEFORE Stripe is loaded on the connected account.
    const strip = replaceState.mock.calls.findIndex((c) => c[2] === "/pay")
    expect(strip).toBeGreaterThanOrEqual(0)
    expect(replaceState.mock.invocationCallOrder[strip]).toBeLessThan(mockedLoadStripe.mock.invocationCallOrder[1])
    expect(mockedLoadStripe.mock.calls[1]).toEqual([PUBLISHABLE_KEY, { stripeAccount: ACCOUNT }])
    expect(window.location.pathname).toBe("/pay")
    expect(window.location.hash).toBe("")

    // The resume entry is the ONLY key the page writes: the client secret and display fields, never the
    // token. Next's own keys are NOT copied in: next/dist/client/components/app-router.js's patched
    // replaceState treats any payload carrying `__NA` as one of its own internal writes and skips the
    // router sync, so the router's canonical URL would keep "/pay#token=…" and its next history write
    // would put the token back in the bar (PR #1064 review round 1, finding 1). Without `__NA` Next
    // copies its keys itself and records "/pay" as the canonical URL.
    const state = replaceState.mock.calls[strip][0]
    expect(state).not.toHaveProperty("__NA")
    expect(state).toEqual({
      jtoyePayResume: {
        clientSecret: CLIENT_SECRET,
        stripeAccountId: ACCOUNT,
        shopName: SHOP_NAME,
        purpose: "UPFRONT",
        amountPennies: 1250,
        orderNumber: ORDER_NUMBER,
      },
    })
    expect(JSON.stringify(replaceState.mock.calls)).not.toContain(TOKEN)

    // The intent is read on the account that owns it, then the shared card form mounts.
    const stripe = await mockedLoadStripe.mock.results[1].value
    expect(stripe.retrievePaymentIntent).toHaveBeenCalledWith(CLIENT_SECRET)
    const options = elementsOptions[elementsOptions.length - 1] as {
      clientSecret: string
      appearance: { variables: { colorPrimary: string } }
    }
    expect(options.clientSecret).toBe(CLIENT_SECRET)
    expect(options.appearance.variables.colorPrimary).toBe("#c2410c")
    // The amount block stays visible above the form; the form renders no h1 of its own.
    expect(theOneH1()).toHaveTextContent(`Pay ${SHOP_NAME}`)
    expect(screen.getByText("£12.50")).toBeInTheDocument()
    expect(screen.getByRole("heading", { level: 2, name: "Payment details" })).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))
    const received = await screen.findByRole("heading", { level: 1, name: COPY.received })
    await waitFor(() => expect(received).toHaveFocus())
    expect(screen.getByText(COPY.receivedBody)).toBeInTheDocument()
    expect(theOneH1()).toBe(received)

    // The link page knows no customer email, so no receipt_email is sent; the return URL names the account.
    expect(confirmPayment).toHaveBeenCalledWith(
      expect.objectContaining({
        confirmParams: { return_url: `${window.location.origin}/pay?acct=${ACCOUNT}` },
        redirect: "if_required",
      })
    )
    // Paid: the resume entry is gone.
    // jsdom has no Next history patch, so nothing re-adds Next's keys after the page's own writes: what
    // this proves is that the page's entry is gone and the page wrote nothing else of its own.
    expect(window.history.state).toEqual({})
  })

  it("a destination-charge link (no account): one platform load serves both steps, and the return URL carries an empty acct", async () => {
    serve(ok(PREVIEW), ok({ ...REDEEMED, stripeAccountId: null }))
    const confirmPayment = jest.fn(() => Promise.resolve({}))
    mockStripeInstance = { confirmPayment }
    await activeLink()

    fireEvent.click(screen.getByRole("button", { name: COPY.continue }))
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledWith(PUBLISHABLE_KEY)
    expect(mockedLoadStripe.mock.calls[0]).toHaveLength(1)

    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))
    await screen.findByRole("heading", { level: 1, name: COPY.received })
    expect(confirmPayment).toHaveBeenCalledWith(
      expect.objectContaining({ confirmParams: { return_url: `${window.location.origin}/pay?acct=` } })
    )
  })

  it("writes nothing to local or session storage across the whole paid journey", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockStripeInstance = { confirmPayment: jest.fn(() => Promise.resolve({})) }
    await activeLink()
    fireEvent.click(screen.getByRole("button", { name: COPY.continue }))
    await screen.findByTestId("stripe-elements")
    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))
    await screen.findByRole("heading", { level: 1, name: COPY.received })

    // Non-vacuity: the spy sees a write made through either storage.
    expect(setItem).toHaveBeenCalledTimes(0)
    window.sessionStorage.setItem("control", "1")
    expect(setItem).toHaveBeenCalledTimes(1)
    expect(document.body.innerHTML).not.toContain(TOKEN)
  })
})

// ---------------------------------------------------------------------------------------------
// Task 2: every dead, failed and returning link says what happened, and nothing the page does
// spends a link it should not. States are chosen from HTTP status and the preview's state enum only;
// the server's prose (`problem()` detail) is never shown and never branched on (T-30-75).
// ---------------------------------------------------------------------------------------------

const DEAD = {
  used: {
    heading: "This payment link has already been opened",
    body: "Each link opens once. If you haven't finished paying, ask the shop to send you a new link.",
  },
  gone: {
    heading: "This payment link no longer works",
    body: "It has expired, or the shop has sent you a newer one. Use the newest link you were sent, or ask the shop for a new one.",
  },
  notFound: {
    heading: "We can't find this payment link",
    body: "Check you opened the whole link from your message. If it still doesn't work, ask the shop to send it again.",
  },
  paid: {
    heading: "This order is already paid",
    body: `There's nothing more to pay for order ${ORDER_NUMBER}. You can close this page.`,
  },
}
const NOT_USED = "Card payment isn't available right now. Your link hasn't been used, so you can try again later."
const LINK_OPENED =
  "We couldn't load the card form, and this link has now been opened. Ask the shop to send you a new link. Nothing has been charged."
const CHECK_FAILED = "We couldn't check this link just now. Please try again."
const PROCESSING = { heading: "Payment processing", body: "Your payment of £12.50 is being processed. You don't need to do anything else." }
const REDIRECT_FAILED = {
  heading: "Your payment didn't go through",
  body: "Nothing has been charged. Ask the shop to send you a new link to try again.",
}
const NO_LINK = { heading: "Open your payment link", body: "To pay, open the link the shop sent you by text or message." }
const SERVER_PROSE = "server prose the page must not branch on"

const RESUME = {
  clientSecret: CLIENT_SECRET,
  stripeAccountId: ACCOUNT,
  shopName: SHOP_NAME,
  purpose: "UPFRONT",
  amountPennies: 1250,
  orderNumber: ORDER_NUMBER,
}

/** The alert carrying `text`, asserted to be role=alert with the amber inline-alert classes. */
async function findAlert(text: string) {
  const alert = await screen.findByRole("alert")
  expect(alert).toHaveTextContent(text)
  expect(alert).toHaveClass("rounded-lg", "bg-amber-50", "p-3", "text-sm", "text-amber-900")
  return alert
}

const noContinue = () => expect(screen.queryByRole("button", { name: COPY.continue })).toBeNull()

async function pressContinue() {
  await activeLink()
  fireEvent.click(screen.getByRole("button", { name: COPY.continue }))
}

describe("before redeem: a card form that cannot load never spends the link", () => {
  it("with NO publishable key: the 'link hasn't been used' alert with Try again, and redeem is never called", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    serve(ok(PREVIEW), ok(REDEEMED))
    await pressContinue()

    await findAlert(NOT_USED)
    expect(screen.getByRole("button", { name: "Try again" })).toHaveClass("h-11")
    expect(mockedRedeem).toHaveBeenCalledTimes(0)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    // The customer still sees who and how much; the link is still in the address bar.
    expect(theOneH1()).toHaveTextContent(`Pay ${SHOP_NAME}`)
    expect(window.location.hash).toBe(`#token=${TOKEN}`)
  })

  it("when Stripe.js resolves null: the same alert, zero redeems, and Try again loads again and then pays", async () => {
    serve(ok(PREVIEW), ok({ ...REDEEMED, stripeAccountId: null }))
    mockedLoadStripe.mockImplementationOnce(() => Promise.resolve(null))
    await pressContinue()

    await findAlert(NOT_USED)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedRedeem).toHaveBeenCalledTimes(0)

    fireEvent.click(screen.getByRole("button", { name: "Try again" }))
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    expect(mockedRedeem).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe.mock.invocationCallOrder[1]).toBeLessThan(mockedRedeem.mock.invocationCallOrder[0])
  })

  it("when Stripe.js REJECTS (network, CSP): the same alert and zero redeems", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockedLoadStripe.mockImplementationOnce(() => Promise.reject(new Error("Failed to load Stripe.js")))
    await pressContinue()

    await findAlert(NOT_USED)
    expect(mockedRedeem).toHaveBeenCalledTimes(0)
  })
})

describe("redeem refused: each status reads as its own state, without the server's prose", () => {
  it.each([
    [409, DEAD.used],
    [410, DEAD.gone],
    [404, DEAD.notFound],
  ])("redeem %i", async (status, copy) => {
    serve(ok(PREVIEW), () => Promise.reject(problem(status)))
    await pressContinue()

    const h1 = await screen.findByRole("heading", { level: 1, name: copy.heading })
    expect(theOneH1()).toBe(h1)
    expect(screen.getByText(copy.body)).toBeInTheDocument()
    noContinue()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    expect(screen.queryByText(SERVER_PROSE)).toBeNull()
  })

  it("redeem 502: the 'link has now been opened… Nothing has been charged' alert, no Continue", async () => {
    serve(ok(PREVIEW), () => Promise.reject(problem(502)))
    await pressContinue()

    await findAlert(LINK_OPENED)
    noContinue()
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull()
    expect(theOneH1()).toHaveTextContent(`Pay ${SHOP_NAME}`)
  })

  it("the Payment Element fails to load after redeem: the 'link has now been opened' alert replaces the form", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    await pressContinue()
    await screen.findByTestId("stripe-elements")
    fireEvent.click(screen.getByRole("button", { name: /simulate payment element load error/i }))

    await findAlert(LINK_OPENED)
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    noContinue()
  })
})

describe("after redeem: the intent's own status decides", () => {
  it("an intent that already succeeded reads 'This order is already paid', no form, and the resume entry is dropped", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockedLoadStripe.mockImplementation(() => Promise.resolve(fakeStripe("succeeded")))
    await pressContinue()

    await screen.findByRole("heading", { level: 1, name: DEAD.paid.heading })
    expect(screen.getByText(DEAD.paid.body)).toBeInTheDocument()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    // jsdom has no Next history patch, so nothing re-adds Next's keys after the page's own writes: what
    // this proves is that the page's entry is gone and the page wrote nothing else of its own.
    expect(window.history.state).toEqual({})
  })

  it("an intent that is processing reads 'Payment processing' with the amount", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockedLoadStripe.mockImplementation(() => Promise.resolve(fakeStripe("processing")))
    await pressContinue()

    await screen.findByRole("heading", { level: 1, name: PROCESSING.heading })
    expect(screen.getByText(PROCESSING.body)).toBeInTheDocument()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
  })

  it("a declined card keeps the form mounted with Stripe's own message, and Pay re-enables", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockStripeInstance = {
      confirmPayment: jest.fn(() => Promise.resolve({ error: { message: "Your card was declined." } })),
    }
    await pressContinue()
    await screen.findByTestId("stripe-elements")
    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))

    await screen.findByText("Your card was declined.")
    expect(screen.getByTestId("stripe-elements")).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole("button", { name: "Pay £12.50" })).toBeEnabled())
  })
})

describe("the preview itself: dead links read correctly and a failed check offers no Continue", () => {
  it.each([
    ["USED", DEAD.used],
    ["EXPIRED", DEAD.gone],
    ["REVOKED", DEAD.gone],
    ["PAID", DEAD.paid],
  ])("preview state %s", async (state, copy) => {
    serve(ok({ ...PREVIEW, state }))
    render(<PayClient />)

    const h1 = await screen.findByRole("heading", { level: 1, name: copy.heading })
    expect(theOneH1()).toBe(h1)
    expect(screen.getByText(copy.body)).toBeInTheDocument()
    noContinue()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    expect(mockedRedeem).toHaveBeenCalledTimes(0)
  })

  it("preview 404 reads 'We can't find this payment link'", async () => {
    serve(() => Promise.reject(problem(404)))
    render(<PayClient />)
    await screen.findByRole("heading", { level: 1, name: DEAD.notFound.heading })
    noContinue()
    expect(screen.queryByText(SERVER_PROSE)).toBeNull()
  })

  it.each([
    ["429", () => Promise.reject(problem(429))],
    ["a network error", () => Promise.reject(new Error("Network Error"))],
    ["503", () => Promise.reject(problem(503))],
  ])("preview %s: 'Pay for your order' with no amount, the check-failed alert, and Try again re-asks the preview", async (_label, failure) => {
    mockedPreview.mockReset()
    serve(failure)
    render(<PayClient />)

    await findAlert(CHECK_FAILED)
    expect(theOneH1()).toHaveTextContent("Pay for your order")
    expect(screen.queryByText("£12.50")).toBeNull()
    noContinue()

    mockedPreview.mockImplementation(ok(PREVIEW))
    fireEvent.click(screen.getByRole("button", { name: "Try again" }))
    await screen.findByRole("heading", { level: 1, name: `Pay ${SHOP_NAME}` })
    expect(mockedPreview).toHaveBeenCalledTimes(2)
    expect(screen.getByRole("button", { name: COPY.continue })).toBeInTheDocument()
    expect(mockedRedeem).toHaveBeenCalledTimes(0)
  })

  it("long shop names and order numbers wrap anywhere", async () => {
    serve(ok(PREVIEW))
    await activeLink()
    expect(screen.getByText(SHOP_NAME, { selector: "p" })).toHaveClass("[overflow-wrap:anywhere]")
    expect(screen.getByText(`Order ${ORDER_NUMBER}`)).toHaveClass("[overflow-wrap:anywhere]")
    expect(theOneH1()).toHaveClass("[overflow-wrap:anywhere]")
  })
})

describe("/pay with no link: Stripe's return, the reload resume, and the empty state", () => {
  it.each([
    ["succeeded", "succeeded", COPY.received, "You've paid £12.50. You can close this page."],
    ["processing", "processing", PROCESSING.heading, PROCESSING.body],
    ["failed", "requires_payment_method", REDIRECT_FAILED.heading, REDIRECT_FAILED.body],
  ])("a redirect return with redirect_status=%s reads the intent on its account and shows the outcome", async (redirectStatus, piStatus, heading, body) => {
    openLink(
      `/pay?acct=${ACCOUNT}&payment_intent=pi_3018&payment_intent_client_secret=${CLIENT_SECRET}&redirect_status=${redirectStatus}`,
      null
    )
    mockedLoadStripe.mockImplementation(() => Promise.resolve(fakeStripe(piStatus)))
    render(<PayClient />)

    await screen.findByRole("heading", { level: 1, name: heading })
    expect(screen.getByText(body)).toBeInTheDocument()
    expect(mockedLoadStripe).toHaveBeenCalledWith(PUBLISHABLE_KEY, { stripeAccount: ACCOUNT })
    const stripe = await mockedLoadStripe.mock.results[0].value
    expect(stripe.retrievePaymentIntent).toHaveBeenCalledWith(CLIENT_SECRET)
    expect(mockedPost).toHaveBeenCalledTimes(0)
  })

  it("no token, no redirect query and no resume entry: 'Open your payment link', and nothing is asked of anyone", async () => {
    openLink("/pay", null)
    render(<PayClient />)

    const h1 = await screen.findByRole("heading", { level: 1, name: NO_LINK.heading })
    expect(theOneH1()).toBe(h1)
    expect(screen.getByText(NO_LINK.body)).toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(0)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    noContinue()
  })

  it("a bare redirect_status with no client secret is not Stripe's return: 'Open your payment link', never 'Payment received'", async () => {
    // PR #1064 review round 1, finding 3: https://…/pay?redirect_status=succeeded, typed by hand, carried
    // no intent to read, and the fallback `status ?? redirectStatus` rendered a success state on the
    // public host with no payment behind it. redirect_status is only a fallback for an intent that
    // cannot be read; with no payment_intent_client_secret there is nothing to fall back from.
    openLink("/pay?redirect_status=succeeded", null)
    render(<PayClient />)

    const h1 = await screen.findByRole("heading", { level: 1, name: NO_LINK.heading })
    expect(theOneH1()).toBe(h1)
    expect(screen.queryByText(COPY.received)).not.toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(0)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    noContinue()
  })

  it("reload after redeem: the resume entry remounts the card form on the vendor's account, and paying drops the entry", async () => {
    openLink("/pay", { __NA: true, [`jtoyePayResume`]: RESUME })
    mockStripeInstance = { confirmPayment: jest.fn(() => Promise.resolve({})) }
    render(<PayClient />)

    await screen.findByTestId("stripe-elements")
    expect(mockedPost).toHaveBeenCalledTimes(0)
    expect(mockedLoadStripe).toHaveBeenCalledWith(PUBLISHABLE_KEY, { stripeAccount: ACCOUNT })
    const stripe = await mockedLoadStripe.mock.results[0].value
    expect(stripe.retrievePaymentIntent).toHaveBeenCalledWith(CLIENT_SECRET)
    expect(theOneH1()).toHaveTextContent(`Pay ${SHOP_NAME}`)
    expect(screen.getByText("£12.50")).toBeInTheDocument()
    expect(screen.getByText(`Order ${ORDER_NUMBER}`)).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))
    await screen.findByRole("heading", { level: 1, name: COPY.received })
    expect(screen.getByText(COPY.receivedBody)).toBeInTheDocument()
    expect(window.history.state).toEqual({ __NA: true })
  })

  it("a reload round trip: Continue, then the page is reloaded on the same history entry, and the card form comes back without a second redeem", async () => {
    serve(ok(PREVIEW), ok(REDEEMED))
    mockStripeInstance = { confirmPayment: jest.fn(() => Promise.resolve({})) }
    const first = render(<PayClient />)
    await screen.findByRole("heading", { level: 1, name: `Pay ${SHOP_NAME}` })
    fireEvent.click(screen.getByRole("button", { name: COPY.continue }))
    await screen.findByTestId("stripe-elements")

    // The reload: the page is gone, the module memo with it; the address bar and history.state stay.
    first.unmount()
    resetStripeLoaderForTests()
    mockedLoadStripe.mockClear()
    expect(window.location.pathname + window.location.hash).toBe("/pay")
    render(<PayClient />)

    // What the island itself wrote is what brings the form back: no preview, no second redeem.
    await screen.findByTestId("stripe-elements")
    expect(mockedRedeem).toHaveBeenCalledTimes(1)
    expect(mockedPreview).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledWith(PUBLISHABLE_KEY, { stripeAccount: ACCOUNT })
    expect(theOneH1()).toHaveTextContent(`Pay ${SHOP_NAME}`)
    expect(screen.queryByRole("heading", { level: 1, name: NO_LINK.heading })).toBeNull()

    fireEvent.click(screen.getByRole("button", { name: "Pay £12.50" }))
    await screen.findByRole("heading", { level: 1, name: COPY.received })
    // jsdom has no Next history patch, so nothing re-adds Next's keys after the page's own writes: what
    // this proves is that the page's entry is gone and the page wrote nothing else of its own.
    expect(window.history.state).toEqual({})
  })

  it("reload after redeem when the intent has succeeded: 'This order is already paid' and the entry is dropped", async () => {
    openLink("/pay", { __NA: true, [`jtoyePayResume`]: RESUME })
    mockedLoadStripe.mockImplementation(() => Promise.resolve(fakeStripe("succeeded")))
    render(<PayClient />)

    await screen.findByRole("heading", { level: 1, name: DEAD.paid.heading })
    expect(window.history.state).toEqual({ __NA: true })
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
  })

  it("reload after redeem when the intent cannot be read: the entry is dropped and the page asks for the link", async () => {
    openLink("/pay", { __NA: true, [`jtoyePayResume`]: RESUME })
    mockedLoadStripe.mockImplementation(() =>
      Promise.resolve({ retrievePaymentIntent: jest.fn(() => Promise.reject(new Error("network"))) })
    )
    render(<PayClient />)

    await screen.findByRole("heading", { level: 1, name: NO_LINK.heading })
    expect(window.history.state).toEqual({ __NA: true })
  })
})
