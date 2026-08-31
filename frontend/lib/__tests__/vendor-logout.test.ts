import { signOut } from "next-auth/react"
import {
  vendorLogout,
  VENDOR_LOGOUT_TIMEOUT_MS,
  __resetVendorLogoutForTests,
} from "@/lib/vendor-logout"

/**
 * R-01 (2026-08-31 customer-surface audit, **P0**) — the client half of vendor
 * federated logout.
 *
 * `vendorLogout()` RETURNS the URL it navigated to, and these tests read that
 * return value rather than `window.location.href`. jsdom refuses to navigate
 * and reports it through the virtual console, leaving `location.href`
 * unchanged, so the return value is the only honest handle available here. The
 * NAVIGATION ITSELF — that a real browser lands on Keycloak and that the SSO
 * cookies are gone afterwards — is a browser-level truth and belongs to the
 * orchestrator's rebuilt-stack cookie-jar probe. Nothing in this file may be
 * read as evidence for it.
 *
 * `signOut` is the `next-auth/react` mock from `jest.setup.js`, asserted on
 * directly.
 */

const mockSignOut = signOut as unknown as jest.Mock

// The trailing `window.location.href = …` is reported by jsdom's virtual
// console as a console.error rather than thrown. Silenced narrowly so a REAL
// console.error still surfaces — the same idiom as
// customer-auth-signout-clears-carts.test.ts.
const realConsoleError = console.error
beforeAll(() => {
  console.error = (...args: unknown[]) => {
    if (String(args[0]).includes("Not implemented: navigation")) return
    realConsoleError(...args)
  }
})
afterAll(() => {
  console.error = realConsoleError
})

beforeEach(() => {
  mockSignOut.mockReset()
  mockSignOut.mockResolvedValue(undefined)
  // `vendorLogout` latches its in-flight promise for the life of the DOCUMENT
  // (CR-02) — a sign-out has no "finished, try again" state in a real browser,
  // only "the page went away". Jest keeps one module registry per FILE, so
  // without this every case after the first would be handed the first case's
  // outcome.
  __resetVendorLogoutForTests()
})

describe("vendorLogout", () => {
  it("fetches the end-session URL, clears the app session, and navigates to the IdP", async () => {
    const END_SESSION =
      "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/logout?id_token_hint=ID"
    const fetchMock = jest.fn(async () =>
      ({ ok: true, json: async () => ({ url: END_SESSION }) }) as Response
    )
    global.fetch = fetchMock as unknown as typeof fetch

    const destination = await vendorLogout()

    // The lookup happens FIRST, while the session still exists — the route
    // reads the id_token off it, so the reverse order would silently return an
    // app path and skip the IdP half entirely.
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/vendor-auth/logout-url")
    // `redirect: false` because the navigation we want is to Keycloak. Letting
    // NextAuth navigate to /auth/signin is exactly what abandoned the IdP half.
    expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
    expect(destination).toBe(END_SESSION)
  })

  it("still signs out locally when the end-session lookup REJECTS", async () => {
    global.fetch = jest.fn(async () => {
      throw new Error("network down")
    }) as unknown as typeof fetch

    const destination = await vendorLogout()

    // A broken IdP lookup must never leave a vendor signed in — that is the
    // same defect one step milder.
    expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
    expect(destination).toBe("/auth/signin")
  })

  it("still signs out locally when the lookup returns a non-OK response", async () => {
    global.fetch = jest.fn(async () =>
      ({ ok: false, json: async () => ({}) }) as Response
    ) as unknown as typeof fetch

    const destination = await vendorLogout()

    expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
    expect(destination).toBe("/auth/signin")
  })

  it("still signs out locally when the lookup NEVER SETTLES", async () => {
    // The one a plain `await fetch` cannot survive. A stalled response used to
    // mean the vendor simply stayed signed in with no feedback at all.
    jest.useFakeTimers()
    try {
      global.fetch = jest.fn(() => new Promise<Response>(() => {})) as unknown as typeof fetch

      const pending = vendorLogout()
      await jest.advanceTimersByTimeAsync(VENDOR_LOGOUT_TIMEOUT_MS + 1)
      const destination = await pending

      expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
      expect(destination).toBe("/auth/signin")
    } finally {
      jest.useRealTimers()
    }
  })

  /**
   * CR-01 (code review, 2026-08-31) — the hole the other four arms could not see.
   *
   * The lookup was bounded; `signOut` was not. `next-auth/react`'s `signOut`
   * makes two un-timeouted fetches (`/api/auth/csrf`, `/api/auth/signout`), and
   * because it is awaited INSIDE the `finally`, a stall there means the
   * `window.location.href` assignment on the next line NEVER RUNS. The vendor
   * stays on the dashboard with the app session and every Keycloak SSO cookie
   * alive, and the button gives no feedback — the R-04 defect verbatim, left on
   * the P0 path.
   *
   * The three arms above cover a lookup that stalls, a lookup that rejects, and
   * a signOut that REJECTS. None covers a signOut that never answers, which is
   * what a phone leaving a wifi cell actually produces.
   */
  it("still navigates when NextAuth's signOut NEVER SETTLES", async () => {
    jest.useFakeTimers()
    try {
      const END_SESSION = "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/logout?id_token_hint=ID"
      // A HEALTHY lookup, so the only thing under test is the local teardown.
      global.fetch = jest.fn(async () =>
        ({ ok: true, json: async () => ({ url: END_SESSION }) }) as Response
      ) as unknown as typeof fetch
      mockSignOut.mockImplementation(() => new Promise(() => {}))

      const pending = vendorLogout()
      await jest.advanceTimersByTimeAsync(VENDOR_LOGOUT_TIMEOUT_MS + 1)

      // Navigating to the Keycloak end-session URL is the thing that matters;
      // the local cookie drop is a best-effort second, and the redirect back to
      // /auth/signin re-evaluates it anyway.
      await expect(pending).resolves.toBe(END_SESSION)
    } finally {
      jest.useRealTimers()
    }
  })

  it("does not strand the vendor when NextAuth's own signOut throws", async () => {
    global.fetch = jest.fn(async () =>
      ({ ok: true, json: async () => ({ url: "http://kc.example/logout" }) }) as Response
    ) as unknown as typeof fetch
    mockSignOut.mockRejectedValue(new Error("nextauth exploded"))

    // Resolves rather than rejecting: the caller is a button handler, and an
    // unhandled rejection there is a sign-out that looks like nothing happened.
    await expect(vendorLogout()).resolves.toBe("http://kc.example/logout")
  })
})

/**
 * CR-02 (code review, 2026-08-31) — a double-tap could CANCEL the federated
 * logout and hand the P0 straight back.
 *
 * The sequence the reviewer traced, which is ordinary user behaviour on a
 * connection slow enough to make a 3s-bounded round trip visible:
 *
 *   1. Tap 1 -> lookup A in flight.
 *   2. Tap 2 -> lookup B in flight.
 *   3. A resolves with the Keycloak end-session URL -> signOut() ->
 *      `location.href = <keycloak>`. The browser BEGINS navigating, async.
 *   4. B resolves. The app session was dropped by step 3, so the route takes
 *      its `!idToken` branch and returns `<origin>/auth/signin`. The second
 *      invocation assigns that, OVERRIDING the pending Keycloak navigation.
 *
 * Net result: app cookie gone, Keycloak SSO session alive, vendor parked on the
 * sign-in page where one click re-enters the dashboard with no prompt. Exactly
 * the P0 this branch exists to close.
 *
 * The fixture models step 4 faithfully — the SECOND lookup answers with the app
 * path, because by then there is genuinely no id_token to hint with.
 */
describe("vendorLogout — a second tap cannot cancel the first (CR-02)", () => {
  const END_SESSION =
    "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/logout?id_token_hint=ID"
  const APP_PATH = "http://localhost:3000/auth/signin"

  /** Call 1 answers with the IdP URL; every later call answers as a dead session would. */
  function sessionDroppingFetch() {
    let call = 0
    return jest.fn(async () => {
      const url = call++ === 0 ? END_SESSION : APP_PATH
      return { ok: true, json: async () => ({ url }) } as Response
    })
  }

  it("returns the FIRST call's destination to both callers and never re-navigates", async () => {
    const fetchMock = sessionDroppingFetch()
    global.fetch = fetchMock as unknown as typeof fetch

    const [first, second] = await Promise.all([vendorLogout(), vendorLogout()])

    // The decisive assertion: the second tap must not be able to name a
    // different destination, because naming one is how it overrides the first.
    expect(first).toBe(END_SESSION)
    expect(second).toBe(END_SESSION)
    expect(second).not.toBe(APP_PATH)
    // And it never even asked — one lookup, one local sign-out.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(mockSignOut).toHaveBeenCalledTimes(1)
  })

  it("holds the latch for a THIRD tap arriving after the first has resolved", async () => {
    // The in-flight window is not the only exposure: `location.href` only
    // SCHEDULES a navigation, so the document stays live and tappable for the
    // whole commit window — which on a bad connection is the slow part.
    const fetchMock = sessionDroppingFetch()
    global.fetch = fetchMock as unknown as typeof fetch

    const first = await vendorLogout()
    const later = await vendorLogout()

    expect(first).toBe(END_SESSION)
    expect(later).toBe(END_SESSION)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("CONTROL: the fixture really does answer differently on the second call", async () => {
    // Without this, both arms above would be equally satisfied by a fixture
    // that returns the SAME url every time — the override could not have been
    // reproduced and the assertions would prove nothing.
    const fetchMock = sessionDroppingFetch()
    const a = await (await fetchMock()).json()
    const b = await (await fetchMock()).json()
    expect(a.url).toBe(END_SESSION)
    expect(b).toEqual({ url: APP_PATH })
  })
})
