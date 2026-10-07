/**
 * /shop/account — the signed-in customer's front door to their data rights (#838, D-04, 31.1-26).
 *
 * The contract this file holds:
 *
 *   - Signed out: the SAME sign-in wall My Orders uses, returning to /shop/account. The page is
 *     noindex (a per-customer surface).
 *   - Signed in: the email from the ID token is shown READ-ONLY, with "Download my data" and
 *     "Delete my account". Both lodge through the EXISTING public intake
 *     (POST /api/v1/public/gdpr/dsar) with an Idempotency-Key per action — one path, still
 *     email-verified. Nothing new is built server-side.
 *   - "Delete my account" opens a dialog stating what will happen BEFORE anything is lodged;
 *     Cancel lodges nothing.
 *   - A double press sends ONE request; a retry after a failure REUSES the same key, so a
 *     request that did reach the server is replayed, not queued twice.
 *   - Both actions are independent: two requests, two keys, ACCESS and ERASURE.
 *   - No email in the token: an explanation, no buttons, nothing lodged with a blank address.
 *   - The success copy is a constant. Nothing the API answers is rendered, so the page cannot
 *     reveal whether any shop holds data for the address (T-31.1-90).
 *   - No marketing-consent control anywhere (D-04).
 *   - The storefront menu offers "My account" only to a signed-in customer.
 *
 * publicApiClient is mocked; the intake itself is DsarIntake*Test on the backend.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import AccountPage, { metadata } from "../page"
import {
  AccountClient,
  ACCOUNT_DELETE_CONSEQUENCES_COPY,
  ACCOUNT_DOWNLOAD_COPY,
  ACCOUNT_REQUEST_LODGED_COPY,
} from "../account-client"
import publicApiClient from "@/lib/public-api-client"
import { ACCESS_COOKIE, ID_COOKIE, REFRESH_COOKIE } from "@/lib/customer-auth-cookies"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/public-api-client")
const mockedPost = publicApiClient.post as jest.Mock

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(),
}))
const mockedSession = getCustomerSession as jest.Mock

// The server component reads the session cookies. `jar` is what this test's "browser" sent.
let jar: Record<string, string> = {}
jest.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name in jar ? { name, value: jar[name] } : undefined),
  }),
}))

const INTAKE_PATH = "/api/v1/public/gdpr/dsar"
const EMAIL = "grace@x.test"

/** An unsigned ID token carrying `claims` — displayEmailFromIdToken only decodes the payload. */
function idToken(claims: Record<string, unknown>): string {
  const b64url = btoa(JSON.stringify(claims)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `eyJhbGciOiJub25lIn0.${b64url}.sig`
}

let keySeq = 0
beforeEach(() => {
  jar = {}
  keySeq = 0
  mockedPost.mockReset()
  mockedPost.mockResolvedValue({ status: 202, data: { message: "ack" } })
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
  Object.defineProperty(globalThis, "crypto", {
    configurable: true,
    value: { randomUUID: jest.fn(() => `key-${++keySeq}`) },
  })
})

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const downloadButton = () => screen.getByRole("button", { name: ACCOUNT_DOWNLOAD_COPY.button })
const deleteButton = () => screen.getByRole("button", { name: "Delete my account" })

describe("/shop/account — the server page", () => {
  it("is a noindex, per-customer page titled 'My account'", () => {
    expect(metadata.title).toBe("My account — J'Toye")
    expect(metadata.robots).toEqual({ index: false, follow: false })
  })

  it("answers a signed-out visitor with the sign-in wall that returns to /shop/account", async () => {
    render(await AccountPage())
    expect(screen.getByRole("heading", { name: "Sign in to continue" })).toBeTruthy()
    const link = screen.getByRole("link", { name: /sign in/i })
    expect(link.getAttribute("href")).toBe("/shop/signin?next=%2Fshop%2Faccount")
    expect(screen.queryByRole("button", { name: ACCOUNT_DOWNLOAD_COPY.button })).toBeNull()
  })

  it("shows the signed-in email read-only with both actions", async () => {
    jar = { [ACCESS_COOKIE]: "a", [REFRESH_COOKIE]: "r", [ID_COOKIE]: idToken({ email: EMAIL }) }
    render(await AccountPage())
    const field = screen.getByLabelText("Email address") as HTMLInputElement
    expect(field.value).toBe(EMAIL)
    expect(field.readOnly).toBe(true)
    expect(downloadButton()).toBeTruthy()
    expect(deleteButton()).toBeTruthy()
  })

  it("still offers the actions when only the refresh cookie is alive (access token lapsed)", async () => {
    jar = { [REFRESH_COOKIE]: "r", [ID_COOKIE]: idToken({ email: EMAIL }) }
    render(await AccountPage())
    expect(downloadButton()).toBeTruthy()
  })
})

describe("AccountClient — Download my data", () => {
  it("POSTs {email, ACCESS} to the existing intake with an Idempotency-Key and asks for email confirmation", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(downloadButton())
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)

    expect(mockedPost).toHaveBeenCalledTimes(1)
    const [path, body, config] = mockedPost.mock.calls[0]
    expect(path).toBe(INTAKE_PATH)
    expect(body).toEqual({ email: EMAIL, requestType: "ACCESS" })
    expect(config.headers["Idempotency-Key"]).toBe("key-1")
    // The intake is public: a lapsed sign-in session cannot stop the request, and none is sent.
    expect(config.headers.Authorization).toBeUndefined()
    expect(screen.getByText(ACCOUNT_REQUEST_LODGED_COPY.body)).toBeTruthy()
  })

  it("a double press sends exactly ONE request", async () => {
    const pending = deferred<{ status: number; data: unknown }>()
    mockedPost.mockReturnValueOnce(pending.promise)
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(downloadButton())
    fireEvent.click(downloadButton())
    expect(mockedPost).toHaveBeenCalledTimes(1)
    await act(async () => pending.resolve({ status: 202, data: {} }))
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("a retry after a failure reuses the SAME Idempotency-Key", async () => {
    mockedPost.mockRejectedValueOnce(new Error("Network Error"))
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(downloadButton())
    await screen.findByRole("alert")
    expect(screen.queryByText(ACCOUNT_REQUEST_LODGED_COPY.heading)).toBeNull()

    fireEvent.click(downloadButton())
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    expect(mockedPost).toHaveBeenCalledTimes(2)
    const firstKey = mockedPost.mock.calls[0][2].headers["Idempotency-Key"]
    const retryKey = mockedPost.mock.calls[1][2].headers["Idempotency-Key"]
    expect(retryKey).toBe(firstKey)
  })

  it("renders the same constant success copy whatever the API answers — nothing indicates data held", async () => {
    mockedPost.mockResolvedValueOnce({
      status: 202,
      data: { message: "A shop holds data for this address", vendorCount: 3 },
    })
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(downloadButton())
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    expect(screen.getByText(ACCOUNT_REQUEST_LODGED_COPY.body)).toBeTruthy()
    expect(document.body.textContent).not.toContain("A shop holds data")
    expect(document.body.textContent).not.toMatch(/vendorCount|\b3 shops?\b/)
  })
})

describe("AccountClient — Delete my account", () => {
  it("opens a dialog stating every consequence before anything is lodged", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(deleteButton())
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText(ACCOUNT_DELETE_CONSEQUENCES_COPY.title)).toBeTruthy()
    for (const point of ACCOUNT_DELETE_CONSEQUENCES_COPY.points) {
      expect(within(dialog).getByText(point)).toBeTruthy()
    }
    // The four facts the plan requires the dialog to state, pinned by content, not just by constant.
    const text = dialog.textContent ?? ""
    expect(text).toMatch(/every shop/i)
    expect(text).toMatch(/sign-in account is deleted/i)
    expect(text).toMatch(/order and tax records/i)
    expect(text).toMatch(/confirm/i)
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("Cancel lodges NOTHING", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(deleteButton())
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: ACCOUNT_DELETE_CONSEQUENCES_COPY.cancel }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(mockedPost).toHaveBeenCalledTimes(0)
  })

  it("Confirm POSTs {email, ERASURE} with an Idempotency-Key and asks for email confirmation", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(deleteButton())
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: ACCOUNT_DELETE_CONSEQUENCES_COPY.confirm }))
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    const [path, body, config] = mockedPost.mock.calls[0]
    expect(path).toBe(INTAKE_PATH)
    expect(body).toEqual({ email: EMAIL, requestType: "ERASURE" })
    expect(typeof config.headers["Idempotency-Key"]).toBe("string")
  })

  it("a double press on Confirm sends exactly ONE request", async () => {
    const pending = deferred<{ status: number; data: unknown }>()
    mockedPost.mockReturnValueOnce(pending.promise)
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(deleteButton())
    const dialog = await screen.findByRole("dialog")
    const confirm = within(dialog).getByRole("button", { name: ACCOUNT_DELETE_CONSEQUENCES_COPY.confirm })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    await act(async () => pending.resolve({ status: 202, data: {} }))
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })
})

describe("AccountClient — both actions, and the no-email session", () => {
  it("pressing both lodges two independent requests (ACCESS and ERASURE) under different keys", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(downloadButton())
    await screen.findByText(ACCOUNT_REQUEST_LODGED_COPY.heading)
    fireEvent.click(deleteButton())
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: ACCOUNT_DELETE_CONSEQUENCES_COPY.confirm }))
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    const types = mockedPost.mock.calls.map((c) => c[1].requestType)
    expect(types).toEqual(["ACCESS", "ERASURE"])
    const keys = mockedPost.mock.calls.map((c) => c[2].headers["Idempotency-Key"])
    expect(keys[0]).not.toBe(keys[1])
  })

  it("with no email in the token, explains and offers NO actions — nothing is lodged blank", () => {
    render(<AccountClient email={null} />)
    expect(screen.queryByRole("button", { name: ACCOUNT_DOWNLOAD_COPY.button })).toBeNull()
    expect(screen.queryByRole("button", { name: "Delete my account" })).toBeNull()
    expect(document.body.textContent).toMatch(/can't see an email address/i)
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("captures no marketing consent: no checkbox, no opt-in wording", async () => {
    render(<AccountClient email={EMAIL} />)
    fireEvent.click(deleteButton())
    await screen.findByRole("dialog")
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0)
    expect(document.body.textContent).not.toMatch(/marketing|newsletter|opt[- ]in/i)
  })
})

describe("StorefrontNav — 'My account'", () => {
  async function renderNav() {
    render(<StorefrontNav />)
    await act(async () => {})
  }

  it("is offered to a signed-in customer, on the desktop row and in the mobile sheet", async () => {
    mockedSession.mockResolvedValue({ profile: { email: EMAIL, name: "Grace" } })
    await renderNav()
    expect(screen.getByRole("link", { name: /^my account$/i }).getAttribute("href")).toBe("/shop/account")
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    // Radix aria-hides everything outside the open sheet, so this finds the SHEET's link.
    expect(screen.getByRole("link", { name: /^my account$/i }).getAttribute("href")).toBe("/shop/account")
  })

  it("is NOT offered to a signed-out visitor, on either code path", async () => {
    await renderNav()
    expect(screen.queryByRole("link", { name: /^my account$/i })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(screen.queryByRole("link", { name: /^my account$/i })).toBeNull()
  })
})
