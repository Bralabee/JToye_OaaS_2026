import { signOut } from "next-auth/react"
import { vendorLogout, VENDOR_LOGOUT_TIMEOUT_MS } from "@/lib/vendor-logout"

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
