/**
 * The staff-invitation accept page (D-07, D-26; UI-SPEC § B2; 37-09 Task 2).
 *
 * The emailed link is `{base}#token={tenantId}.{token}` (37-08 moved the token from
 * the path to the FRAGMENT, which a browser never sends to a server; WINDOWS.md entry
 * 21). The page reads it once, drops it from the address bar, and POSTs it in a JSON
 * body: `POST /api/v1/public/staff-invites/preview {ref}` to learn what the link
 * offers, and `POST /api/v1/public/staff-invites/accept {ref, firstName, lastName,
 * password}` to take it.
 *
 * What is held here:
 *   - token hygiene: the fragment is dropped with history.replaceState, the ref and
 *     the password never reach the DOM, and NOTHING is written to localStorage or
 *     sessionStorage (a spy counts every write);
 *   - one unusable state for every 404, chosen without reading the server's
 *     `detail`, so expired, used and wrong-business links are indistinguishable;
 *   - the password rule is the server's: Keycloak's refusal is shown verbatim under
 *     the field, and the page shows no rule of its own;
 *   - D-26 (owner answer "login-hint", 37-01): after a 201 the ordinary Keycloak
 *     sign-in starts with the invited email as login_hint and /dashboard?joined=1
 *     as the callback.
 *
 * publicApiClient and next-auth are mocked; the server half is
 * StaffInviteAcceptIntegrationTest (37-08).
 */
import { StrictMode } from "react"
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import InvitePage, { metadata } from "../page"
import { InviteClient } from "../invite-client"
import publicApiClient from "@/lib/public-api-client"
import { signIn } from "next-auth/react"

jest.mock("@/lib/public-api-client")
const mockedPost = publicApiClient.post as jest.Mock

jest.mock("next-auth/react", () => ({
  signIn: jest.fn(() => Promise.resolve()),
}))
const mockedSignIn = signIn as jest.Mock

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const TENANT = "00000000-0000-0000-0000-000000000001"
const TOKEN = "Zm9vYmFy-_invite_token_that_must_never_render_9"
const REF = `${TENANT}.${TOKEN}`
const PATH = "/invite"
const PREVIEW_PATH = "/api/v1/public/staff-invites/preview"
const ACCEPT_PATH = "/api/v1/public/staff-invites/accept"
const PASSWORD = "PwMarker-7c1f-correct horse battery staple"

const NEW_PREVIEW = {
  businessName: "Mama Ade's Kitchen Ltd",
  role: "SHOP_MANAGER",
  shopName: "Peckham Kitchen",
  inviterName: "Ada Owner",
  email: "maya@example.test",
  accountState: "NEW",
}

function openWithHash(hash: string) {
  window.history.replaceState(null, "", `${PATH}${hash}`)
}

/** An axios-shaped rejection carrying an RFC 7807 body. */
function problem(status: number, type: string, detail: string) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: {
      status,
      data: { type: `https://api.jtoye.uk/problems/${type}`, title: "x", status, detail },
    },
  })
}

/** Route the mocked POST by path: preview answers `preview`, accept answers `accept`. */
function serve(preview: () => Promise<unknown>, accept?: () => Promise<unknown>) {
  mockedPost.mockImplementation((url: string) => {
    if (url === PREVIEW_PATH) return preview()
    if (url === ACCEPT_PATH && accept) return accept()
    return Promise.reject(new Error(`unexpected POST ${url}`))
  })
}

const ok = (data: unknown, status = 200) => () => Promise.resolve({ status, data })

async function readyNew() {
  serve(ok(NEW_PREVIEW), ok({ businessName: NEW_PREVIEW.businessName, role: "SHOP_MANAGER", shopName: "Peckham Kitchen" }, 201))
  const view = render(<InviteClient />)
  await screen.findByRole("heading", { name: "Join Mama Ade's Kitchen Ltd on J'Toye" })
  return view
}

function fill(label: RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

let setItem: jest.SpyInstance

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
  setItem = jest.spyOn(Storage.prototype, "setItem")
  openWithHash(`#token=${REF}`)
  document.title = ""
})

afterEach(() => {
  setItem.mockRestore()
})

describe("the invite page's metadata (UI-SPEC § B2)", () => {
  it("is titled generically, noindex,nofollow, with no canonical", () => {
    expect(metadata.title).toBe("Your invitation — J'Toye")
    const robots = (metadata.robots ?? {}) as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
    expect(metadata.alternates?.canonical).toBeUndefined()
    expect(metadata.openGraph).toBeUndefined()
  })

  it("renders the client island inside the public shell", async () => {
    serve(ok(NEW_PREVIEW))
    render(<InvitePage />)
    expect(
      await screen.findByRole("heading", { name: "Join Mama Ade's Kitchen Ltd on J'Toye" })
    ).toBeInTheDocument()
    expect(screen.getByRole("main")).toBeInTheDocument()
  })
})

describe("reading the link (token hygiene, T-37-24)", () => {
  it("shows 'Checking your invitation…' in a status region while the preview is in flight", async () => {
    serve(() => new Promise(() => {}))
    render(<InviteClient />)
    const status = await screen.findByRole("status")
    expect(status).toHaveTextContent("Checking your invitation…")
  })

  it("POSTs the ref from the fragment in a body, once, even under Strict Mode", async () => {
    serve(ok(NEW_PREVIEW))
    render(
      <StrictMode>
        <InviteClient />
      </StrictMode>
    )
    await screen.findByRole("heading", { name: /join mama ade's kitchen ltd/i })
    const previews = mockedPost.mock.calls.filter((c) => c[0] === PREVIEW_PATH)
    expect(previews).toHaveLength(1)
    expect(previews[0][1]).toEqual({ ref: REF })
  })

  it("drops the token from the address bar and never renders it", async () => {
    serve(ok(NEW_PREVIEW))
    const { container } = render(<InviteClient />)
    await screen.findByRole("heading", { name: /join mama ade's kitchen ltd/i })
    expect(window.location.hash).toBe("")
    expect(window.location.pathname).toBe(PATH)
    expect(window.location.href).not.toContain(TOKEN)
    expect(container.innerHTML).not.toContain(TOKEN)
  })

  it("shows the unusable state and sends nothing when the link has no token", async () => {
    openWithHash("")
    render(<InviteClient />)
    expect(
      await screen.findByRole("heading", { name: "This invitation link can't be used" })
    ).toBeInTheDocument()
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("names the business in the document title once the preview has loaded", async () => {
    await readyNew()
    expect(document.title).toBe("Join Mama Ade's Kitchen Ltd on J'Toye")
  })
})

describe("a new account (UI-SPEC § B2 state 2)", () => {
  it("summarises the offer, shows the email read-only, and offers names + password", async () => {
    await readyNew()
    expect(
      screen.getByText((_, el) =>
        el?.tagName === "P" &&
        el.textContent === "Ada Owner has invited you to work on Peckham Kitchen as Shop manager."
      )
    ).toBeInTheDocument()
    expect(
      screen.getByText((_, el) =>
        el?.tagName === "P" && el.textContent === "You'll sign in with maya@example.test."
      )
    ).toBeInTheDocument()
    // The email is fixed by the invitation: shown, never an editable field.
    expect(screen.queryByDisplayValue("maya@example.test")).toBeNull()

    expect(screen.getByLabelText(/first name/i)).toHaveAttribute("autocomplete", "given-name")
    expect(screen.getByLabelText(/last name/i)).toHaveAttribute("autocomplete", "family-name")
    const password = screen.getByLabelText(/^password$/i)
    expect(password).toHaveAttribute("type", "password")
    expect(password).toHaveAttribute("autocomplete", "new-password")
    expect(screen.getByRole("button", { name: "Create account and join" })).toBeInTheDocument()
  })

  it("reads 'all shops' for an invitation that covers every shop", async () => {
    serve(ok({ ...NEW_PREVIEW, shopName: null, role: "GROUP_ADMIN" }))
    render(<InviteClient />)
    await screen.findByRole("heading", { name: /join mama ade's kitchen ltd/i })
    expect(
      screen.getByText((_, el) =>
        el?.tagName === "P" &&
        el.textContent === "Ada Owner has invited you to work on all shops as Group admin."
      )
    ).toBeInTheDocument()
  })

  it("has a 44px show/hide toggle that reports its state with aria-pressed", async () => {
    await readyNew()
    const toggle = screen.getByRole("button", { name: "Show password" })
    expect(toggle).toHaveAttribute("aria-pressed", "false")
    expect(toggle).toHaveClass("h-11", "w-11")
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute("aria-pressed", "true")
    expect(screen.getByLabelText(/^password$/i)).toHaveAttribute("type", "text")
    fireEvent.click(toggle)
    expect(screen.getByLabelText(/^password$/i)).toHaveAttribute("type", "password")
  })

  it("shows no client-side password rule", async () => {
    await readyNew()
    expect(screen.queryByText(/at least \d+|characters|uppercase|special character/i)).toBeNull()
  })

  it("refuses empty fields inline, focuses the first, and sends nothing", async () => {
    await readyNew()
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))

    const alerts = await screen.findAllByRole("alert")
    expect(alerts.map((a) => a.textContent)).toEqual([
      "Enter your first name.",
      "Enter your last name.",
      "Enter a password.",
    ])
    expect(screen.getByLabelText(/first name/i)).toHaveAttribute("aria-invalid", "true")
    expect(screen.getByLabelText(/last name/i)).toHaveAttribute("aria-invalid", "true")
    expect(screen.getByLabelText(/^password$/i)).toHaveAttribute("aria-invalid", "true")
    expect(screen.getByLabelText(/first name/i)).toHaveFocus()
    expect(mockedPost.mock.calls.filter((c) => c[0] === ACCEPT_PATH)).toHaveLength(0)
  })

  it("refuses only the empty one, and focuses it", async () => {
    await readyNew()
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("Enter a password.")
    expect(screen.getByLabelText(/^password$/i)).toHaveFocus()
    expect(screen.getByLabelText(/first name/i)).not.toHaveAttribute("aria-invalid")
    expect(mockedPost.mock.calls.filter((c) => c[0] === ACCEPT_PATH)).toHaveLength(0)
  })

  it("POSTs {ref, firstName, lastName, password} once, says 'Creating your account…', then starts the Keycloak sign-in with the login hint", async () => {
    await readyNew()
    let resolve!: (v: unknown) => void
    serve(ok(NEW_PREVIEW), () => new Promise((r) => (resolve = r)))
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fill(/^password$/i, PASSWORD)
    const submit = screen.getByRole("button", { name: "Create account and join" })
    fireEvent.click(submit)
    fireEvent.click(submit)

    const accepts = mockedPost.mock.calls.filter((c) => c[0] === ACCEPT_PATH)
    expect(accepts).toHaveLength(1)
    expect(accepts[0][1]).toEqual({
      ref: REF,
      firstName: "Maya",
      lastName: "Cole",
      password: PASSWORD,
    })
    expect(await screen.findByRole("button", { name: "Creating your account…" })).toBeDisabled()

    await act(async () => {
      resolve({
        status: 201,
        data: { businessName: NEW_PREVIEW.businessName, role: "SHOP_MANAGER", shopName: "Peckham Kitchen" },
      })
    })
    await waitFor(() =>
      expect(mockedSignIn).toHaveBeenCalledWith(
        "keycloak",
        { callbackUrl: "/dashboard?joined=1" },
        { login_hint: "maya@example.test" }
      )
    )
  })

  it("writes nothing to localStorage or sessionStorage across the whole journey", async () => {
    const { container } = await readyNew()
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fill(/^password$/i, PASSWORD)
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))
    await waitFor(() => expect(mockedSignIn).toHaveBeenCalled())

    expect(setItem).toHaveBeenCalledTimes(0)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(container.innerHTML).not.toContain(PASSWORD)
    expect(container.innerHTML).not.toContain(TOKEN)
  })

  it("renders Keycloak's password refusal verbatim under the field, with aria-invalid and focus, never the password", async () => {
    const { container } = await readyNew()
    serve(ok(NEW_PREVIEW), () =>
      Promise.reject(problem(422, "staff-invite-password-rejected", "Password policy not met"))
    )
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fill(/^password$/i, PASSWORD)
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))

    const password = screen.getByLabelText(/^password$/i)
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/^Password policy not met$/)
    expect(password).toHaveAttribute("aria-invalid", "true")
    expect(password.getAttribute("aria-describedby")).toBe(alert.id)
    await waitFor(() => expect(password).toHaveFocus())
    expect(container.innerHTML).not.toContain(PASSWORD)
    expect(container.textContent).not.toContain(PASSWORD)
    expect(mockedSignIn).not.toHaveBeenCalled()
  })
})

describe("the unusable link (UI-SPEC § B2 state 4)", () => {
  /** Three different causes, three different refs and details: one page. */
  const CAUSES = [
    { ref: `${TENANT}.expired_token_aaaaaaaaaaaaaaaaaaaaaaaaaaaa`, detail: "expired" },
    { ref: `${TENANT}.used_token_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb`, detail: "already accepted" },
    {
      ref: `99999999-9999-9999-9999-999999999999.wrong_tenant_cccccccccccccccc`,
      detail: "This invitation can't be used.",
    },
  ]

  it("renders identical HTML for an expired, a used and a wrong-business link", async () => {
    const html: string[] = []
    for (const cause of CAUSES) {
      openWithHash(`#token=${cause.ref}`)
      serve(() => Promise.reject(problem(404, "staff-invite-unavailable", cause.detail)))
      const { container, unmount } = render(<InviteClient />)
      await screen.findByRole("heading", { name: "This invitation link can't be used" })
      expect(container.innerHTML).not.toContain(cause.ref.split(".")[1])
      html.push(container.innerHTML)
      unmount()
    }
    expect(html[1]).toBe(html[0])
    expect(html[2]).toBe(html[0])
    expect(html[0]).toContain(
      "It may have expired, already been used, or been cancelled. Ask the person who invited you to send a new one."
    )
  })

  it("shows the same state when the link dies between preview and accept", async () => {
    await readyNew()
    serve(ok(NEW_PREVIEW), () =>
      Promise.reject(problem(404, "staff-invite-unavailable", "This invitation can't be used."))
    )
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fill(/^password$/i, PASSWORD)
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))
    expect(
      await screen.findByRole("heading", { name: "This invitation link can't be used" })
    ).toBeInTheDocument()
  })
})

describe("an address that belongs to another business (UI-SPEC § B2 state 5)", () => {
  it("is shown from the preview's accountState, with no form", async () => {
    serve(ok({ ...NEW_PREVIEW, accountState: "OTHER_BUSINESS" }))
    render(<InviteClient />)
    expect(
      await screen.findByRole("heading", { name: "This email already belongs to another business" })
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        "Each J'Toye account works with one business. Ask the person who invited you to use a different email address."
      )
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/^password$/i)).toBeNull()
  })

  it("is shown when accepting answers 409", async () => {
    await readyNew()
    serve(ok(NEW_PREVIEW), () =>
      Promise.reject(problem(409, "staff-invite-email-in-other-business", "x"))
    )
    fill(/first name/i, "Maya")
    fill(/last name/i, "Cole")
    fill(/^password$/i, PASSWORD)
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }))
    expect(
      await screen.findByRole("heading", { name: "This email already belongs to another business" })
    ).toBeInTheDocument()
  })
})

describe("an account that already belongs to this business (UI-SPEC § B2 state 3)", () => {
  it("offers 'Sign in to accept', which accepts with the ref alone and signs in", async () => {
    serve(ok({ ...NEW_PREVIEW, accountState: "EXISTS_HERE" }), ok({ businessName: NEW_PREVIEW.businessName, role: "SHOP_MANAGER", shopName: "Peckham Kitchen" }, 201))
    render(<InviteClient />)
    expect(
      await screen.findByText("You already have a J'Toye account with this email.")
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/^password$/i)).toBeNull()

    fireEvent.click(screen.getByRole("button", { name: "Sign in to accept" }))
    await waitFor(() =>
      expect(mockedSignIn).toHaveBeenCalledWith(
        "keycloak",
        { callbackUrl: "/dashboard?joined=1" },
        { login_hint: "maya@example.test" }
      )
    )
    const accepts = mockedPost.mock.calls.filter((c) => c[0] === ACCEPT_PATH)
    expect(accepts).toHaveLength(1)
    expect(accepts[0][1]).toEqual({ ref: REF })
  })
})

describe("a failure that is not an answer", () => {
  it("keeps the link and offers to check again when the preview cannot be reached", async () => {
    let calls = 0
    serve(() => {
      calls += 1
      return calls === 1 ? Promise.reject(new Error("Network Error")) : Promise.resolve({ status: 200, data: NEW_PREVIEW })
    })
    render(<InviteClient />)
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("We couldn't check your invitation just now.")
    fireEvent.click(within(alert.parentElement as HTMLElement).getByRole("button", { name: "Try again" }))
    expect(
      await screen.findByRole("heading", { name: "Join Mama Ade's Kitchen Ltd on J'Toye" })
    ).toBeInTheDocument()
    const previews = mockedPost.mock.calls.filter((c) => c[0] === PREVIEW_PATH)
    expect(previews).toHaveLength(2)
    expect(previews[1][1]).toEqual({ ref: REF })
  })
})

describe("long business names (UI-SPEC § B2 backstop, jsdom half)", () => {
  it("lets the heading wrap anywhere rather than overflow", async () => {
    const long = "The Very Long Named Caribbean And West African Kitchen Group"
    expect(long.length).toBe(60)
    serve(ok({ ...NEW_PREVIEW, businessName: long }))
    render(<InviteClient />)
    const h1 = await screen.findByRole("heading", { level: 1, name: `Join ${long} on J'Toye` })
    expect(h1).toHaveClass("[overflow-wrap:anywhere]")
  })
})
