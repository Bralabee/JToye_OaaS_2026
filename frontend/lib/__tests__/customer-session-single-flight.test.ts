/**
 * Issue #843 — the browser half of the slow-network sign-out.
 *
 * `getCustomerSession()` is called from the nav session store (on mount, then every second for
 * five seconds, and on focus / visibility / storage events) AND from the checkout page, the
 * order pages and the sign-in card. Each call is a GET /api/customer-auth/session, and past the
 * access-token lifetime each of those is a refresh-token redemption on the server. Under latency
 * the first request has not come back when the second leaves, so they all carry the same single-use
 * cookie. Overlapping calls must therefore share ONE request; a call made after that request has
 * settled must still make a fresh one (the answer is not cached).
 */

import { getCustomerSession } from "@/lib/customer-auth"

function sessionResponse(authenticated: boolean) {
  // jsdom has no Response: the code under test reads only `ok` and `json()`.
  const body = authenticated
    ? {
        authenticated: true,
        expiresAt: Math.floor(Date.now() / 1000) + 300,
        profile: { sub: "u1", email: "a@example.com", name: "A", emailVerified: true },
      }
    : { authenticated: false }
  return { ok: true, status: 200, json: async () => body } as unknown as Response
}

describe("getCustomerSession — single flight (#843)", () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it("collapses overlapping calls into one request", async () => {
    const pending: Array<(r: Response) => void> = []
    const fetchMock = jest.fn().mockImplementation(
      () => new Promise<Response>((resolve) => pending.push(resolve))
    )
    global.fetch = fetchMock as unknown as typeof fetch

    const a = getCustomerSession()
    const b = getCustomerSession()
    const c = getCustomerSession()
    for (const release of pending) release(sessionResponse(true))
    const [ra, rb, rc] = await Promise.all([a, b, c])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(ra?.profile.sub).toBe("u1")
    expect(rb?.profile.sub).toBe("u1")
    expect(rc?.profile.sub).toBe("u1")
  })

  it("does not cache: a call after the first has settled makes a fresh request", async () => {
    const fetchMock = jest
      .fn()
      .mockImplementationOnce(async () => sessionResponse(true))
      .mockImplementationOnce(async () => sessionResponse(false))
    global.fetch = fetchMock as unknown as typeof fetch

    expect((await getCustomerSession())?.profile.sub).toBe("u1")
    // The second answer is "nobody": it must reach the caller, not be masked by the first.
    expect(await getCustomerSession()).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("a failed request does not poison the next call", async () => {
    const fetchMock = jest
      .fn()
      .mockRejectedValueOnce(new Error("network"))
      .mockImplementationOnce(async () => sessionResponse(true))
    global.fetch = fetchMock as unknown as typeof fetch

    expect(await getCustomerSession()).toBeNull()
    expect((await getCustomerSession())?.profile.sub).toBe("u1")
  })
})
