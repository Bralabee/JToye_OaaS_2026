import { exchangeCallback } from "@/lib/customer-auth"

/**
 * SW-JRD-05 / SW-SAM-13 and PR 1110 review round 1: a reset or verification
 * link opened in a new tab hands that tab a code for ANOTHER tab's sign-in.
 * The tab holds no verifier, or a stale one from its own abandoned sign-in
 * whose state does not match. Both must read "not-this-tab" (the page offers a
 * fresh sign-in) and never reach the token endpoint; only a matching state is
 * exchanged, and only that exchange's failure is "failed".
 */
describe("exchangeCallback", () => {
  const realFetch = global.fetch
  let fetchCalls: string[]

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    fetchCalls = []
    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      fetchCalls.push(String(input))
      return { ok: false, json: async () => ({}) } as Response
    }) as unknown as typeof fetch
  })
  afterAll(() => {
    global.fetch = realFetch
  })

  it("is not-this-tab when the tab holds no verifier", async () => {
    await expect(exchangeCallback("code", "other-tab-state")).resolves.toEqual({ kind: "not-this-tab" })
    expect(fetchCalls).toEqual([])
  })

  it("is not-this-tab when a stale verifier's state does not match, and clears it", async () => {
    sessionStorage.setItem("jtoye-pkce-verifier", "stale-verifier")
    sessionStorage.setItem("jtoye-oauth-state", "stale-state")
    sessionStorage.setItem("jtoye-oauth-nonce", "stale-nonce")

    await expect(exchangeCallback("code", "other-tab-state")).resolves.toEqual({ kind: "not-this-tab" })
    expect(fetchCalls).toEqual([])
    expect(sessionStorage.getItem("jtoye-pkce-verifier")).toBeNull()
    expect(sessionStorage.getItem("jtoye-oauth-state")).toBeNull()
  })

  it("exchanges a matching state, and a refused exchange is failed", async () => {
    sessionStorage.setItem("jtoye-pkce-verifier", "verifier")
    sessionStorage.setItem("jtoye-oauth-state", "state-1")
    sessionStorage.setItem("jtoye-oauth-nonce", "nonce-1")

    await expect(exchangeCallback("code", "state-1")).resolves.toEqual({ kind: "failed" })
    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0]).toContain("/protocol/openid-connect/token")
  })

  it("is not-this-tab, not a throw, when sessionStorage is unavailable", async () => {
    const spy = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError")
    })
    try {
      await expect(exchangeCallback("code", "s")).resolves.toEqual({ kind: "not-this-tab" })
    } finally {
      spy.mockRestore()
    }
  })
})
