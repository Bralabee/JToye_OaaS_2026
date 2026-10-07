/**
 * The page the DSAR verification email links to (#839, D-04, 31.1-20).
 *
 * The link is `{base}#token=<43 chars>`. Before this page existed the link
 * pointed at the API (localhost:8080 on compose, where nothing listens) and,
 * where it did land, showed raw JSON — so a non-technical requester could not
 * confirm, and no request lodged on the canonical runtime was ever verified.
 *
 * The token is a single-use credential that ARMS a request (an erasure is
 * destructive), so the contract held here is about WHEN it is spent:
 *
 *   - read from the URL FRAGMENT, removed from the address bar on load;
 *   - NOTHING is sent until the person presses "Confirm my request" — a mail
 *     scanner or link prefetcher that renders this page must not confirm an
 *     erasure on someone's behalf (T-31.1-71). Asserted as zero POSTs before
 *     the press, and zero POSTs for an empty fragment;
 *   - the outcome copy is chosen from the API's `status`, never its English
 *     `detail`, and is never raw JSON;
 *   - "already confirmed" is distinct from "not valid or expired", and both
 *     are distinct from "confirmed".
 *
 * publicApiClient is mocked; the backend half is DsarVerificationIntegrationTest.
 */
import { StrictMode } from "react"
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import ConfirmPage, { metadata } from "../confirm/page"
import { ConfirmClient, CONFIRM_COPY } from "../confirm/confirm-client"
import publicApiClient from "@/lib/public-api-client"

jest.mock("@/lib/public-api-client")
const mockedPost = publicApiClient.post as jest.Mock

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const TOKEN = "Zm9vYmFy-_tokenvalue_that_must_never_render_0"
const PATH = "/data-request/confirm"
const VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify"
const CONFIRM = /confirm my request/i

function openWithHash(hash: string) {
  window.history.replaceState(null, "", `${PATH}${hash}`)
}

/** The API's real response shape; `detail` is deliberately NOT the page's copy. */
function respond(status: string) {
  mockedPost.mockResolvedValue({
    status: 200,
    data: { status, detail: `server detail for ${status} - must not be shown` },
  })
}

async function press() {
  fireEvent.click(await screen.findByRole("button", { name: CONFIRM }))
}

beforeEach(() => {
  jest.clearAllMocks()
  openWithHash(`#token=${TOKEN}`)
})

describe("the confirm page's metadata", () => {
  it("is noindex,nofollow and titled for the requester", () => {
    const robots = (metadata.robots ?? {}) as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
    expect(metadata.title).toBe("Confirm your data request — J'Toye")
  })

  it("renders inside the public shell with the client island", async () => {
    render(<ConfirmPage />)
    expect(await screen.findByRole("button", { name: CONFIRM })).toBeInTheDocument()
  })
})

describe("the token is spent only by an explicit press", () => {
  it("offers 'Confirm my request' and sends nothing before it is pressed", async () => {
    render(<ConfirmClient />)
    expect(await screen.findByRole("button", { name: CONFIRM })).toBeInTheDocument()
    // Give any effect-driven request every chance to fire.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    expect(mockedPost).toHaveBeenCalledTimes(0)
  })

  it("removes the token from the address bar on load", async () => {
    render(<ConfirmClient />)
    await screen.findByRole("button", { name: CONFIRM })
    expect(window.location.hash).toBe("")
    expect(window.location.href).not.toContain(TOKEN)
    expect(window.location.pathname).toBe(PATH)
  })

  it("POSTs the token in the body exactly once when pressed, even if pressed twice", async () => {
    let resolve!: (v: unknown) => void
    mockedPost.mockReturnValue(new Promise((r) => (resolve = r)))
    render(<ConfirmClient />)
    const button = await screen.findByRole("button", { name: CONFIRM })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPost.mock.calls[0][0]).toBe(VERIFY_PATH)
    expect(mockedPost.mock.calls[0][1]).toEqual({ token: TOKEN })
    await act(async () => {
      resolve({ status: 200, data: { status: "verified", detail: "x" } })
    })
  })

  it("never renders the token into the page", async () => {
    respond("verified")
    const { container } = render(<ConfirmClient />)
    await press()
    await screen.findByRole("heading", { name: CONFIRM_COPY.verified.heading })
    expect(container.innerHTML).not.toContain(TOKEN)
  })
})

describe("the three outcomes are distinct, and chosen by status", () => {
  it("verified -> 'Your request is confirmed'", async () => {
    respond("verified")
    const { container } = render(<ConfirmClient />)
    await press()
    expect(await screen.findByRole("heading", { name: /your request is confirmed/i })).toBeInTheDocument()
    expect(screen.queryByText(/already confirmed/i)).toBeNull()
    expect(screen.queryByRole("button", { name: CONFIRM })).toBeNull()
    expect(container.textContent).not.toContain("must not be shown")
  })

  it("already_verified -> 'already confirmed', not 'confirmed' and not 'invalid'", async () => {
    respond("already_verified")
    const { container } = render(<ConfirmClient />)
    await press()
    expect(await screen.findByRole("heading", { name: /already confirmed/i })).toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: CONFIRM_COPY.verified.heading })).toBeNull()
    expect(screen.queryByRole("heading", { name: CONFIRM_COPY.invalid.heading })).toBeNull()
    expect(container.textContent).not.toContain("must not be shown")
  })

  it("invalid -> the invalid copy with a route to a new request and the privacy notice, never JSON", async () => {
    respond("invalid")
    const { container } = render(<ConfirmClient />)
    await press()
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.invalid.heading })).toBeInTheDocument()
    expect(screen.getByText(/not valid, or it has expired/i)).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /make a new request/i })).toHaveAttribute("href", "/shop/account")
    expect(screen.getByRole("link", { name: /privacy notice/i })).toHaveAttribute("href", "/legal/privacy")
    expect(screen.queryByRole("heading", { name: /already confirmed/i })).toBeNull()
    expect(container.textContent).not.toContain("{")
    expect(container.textContent).not.toContain("must not be shown")
  })

  it("an unrecognised status is treated as invalid, never as confirmed", async () => {
    respond("something_new")
    render(<ConfirmClient />)
    await press()
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.invalid.heading })).toBeInTheDocument()
  })

  it("the three headings are three different strings", () => {
    const headings = new Set([
      CONFIRM_COPY.verified.heading,
      CONFIRM_COPY.already_verified.heading,
      CONFIRM_COPY.invalid.heading,
    ])
    expect(headings.size).toBe(3)
  })
})

describe("a link with no token", () => {
  it("shows the invalid state, offers no button and sends nothing (no fragment)", async () => {
    openWithHash("")
    render(<ConfirmClient />)
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.invalid.heading })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /make a new request/i })).toHaveAttribute("href", "/shop/account")
    expect(screen.queryByRole("button", { name: CONFIRM })).toBeNull()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(0))
  })

  it("shows the invalid state and sends nothing for an empty token", async () => {
    openWithHash("#token=")
    render(<ConfirmClient />)
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.invalid.heading })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: CONFIRM })).toBeNull()
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    expect(mockedPost).toHaveBeenCalledTimes(0)
    expect(window.location.hash).toBe("")
  })
})

describe("a failure that is not an answer", () => {
  it("keeps the token and offers a retry, which sends the same token", async () => {
    mockedPost.mockRejectedValueOnce({ response: { status: 503 } })
    render(<ConfirmClient />)
    await press()
    expect(await screen.findByRole("alert")).toHaveTextContent(/not been confirmed/i)
    respond("verified")
    fireEvent.click(screen.getByRole("button", { name: CONFIRM }))
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.verified.heading })).toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(2)
    expect(mockedPost.mock.calls[1][1]).toEqual({ token: TOKEN })
  })
})

describe("under React Strict Mode (next dev double-runs mount effects)", () => {
  // Strict Mode mounts, unmounts and re-mounts, so the read-once effect runs
  // twice. The first run strips the fragment; the second must not then
  // overwrite the token it already holds with null, or a valid link reads as
  // "This link can't be used" on `next dev` (the start-dev.sh hybrid runtime).
  it("keeps the token read on the first run and spends it on the press", async () => {
    respond("verified")
    render(
      <StrictMode>
        <ConfirmClient />
      </StrictMode>
    )
    await press()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(mockedPost).toHaveBeenCalledWith(VERIFY_PATH, { token: TOKEN })
    expect(await screen.findByRole("heading", { name: CONFIRM_COPY.verified.heading })).toBeInTheDocument()
    expect(window.location.hash).toBe("")
  })
})
