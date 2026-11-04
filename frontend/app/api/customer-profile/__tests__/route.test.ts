/**
 * @jest-environment node
 *
 * SW-JRD-07 / SW-SAM-11 (owner ruling 2026-11-04): a signed-in customer changes their display name.
 * The access token lives in an HttpOnly cookie, so this server seam reads it and sends it to core on
 * X-Customer-Token; core takes WHOSE name to change from the verified token alone.
 *
 *  - no cookies at all -> 401 and core is never called
 *  - an expired access cookie with a live refresh cookie is renewed first
 *  - the name is the only thing forwarded (no email, no id: the caller cannot choose whose name changes)
 *  - after a successful change the session cookies are re-issued so the new name shows at once
 *  - core's status passes through; an unreachable core is a 502
 */
import { NextRequest } from "next/server"

jest.mock("@/lib/customer-token-refresh", () => ({ refreshCustomerTokens: jest.fn() }))
import { refreshCustomerTokens } from "@/lib/customer-token-refresh"
import { PUT } from "../route"

const mockRefresh = refreshCustomerTokens as jest.Mock

function req(body: unknown, cookies: Record<string, string> = {}): NextRequest {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ")
  return new NextRequest("http://localhost/api/customer-profile", {
    method: "PUT",
    headers: { "content-type": "application/json", ...(cookie ? { cookie } : {}), "x-forwarded-for": "203.0.113.9" },
    body: JSON.stringify(body),
  })
}

const renewed = { accessToken: "new-access", refreshToken: "new-refresh", idToken: "a.b.c", expiresAt: 9999999999 }

describe("PUT /api/customer-profile", () => {
  const realFetch = global.fetch
  beforeEach(() => mockRefresh.mockReset())
  afterEach(() => {
    global.fetch = realFetch
  })

  it("is a 401 without calling core when there is no session cookie at all", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await PUT(req({ displayName: "Ada" }))

    expect(res.status).toBe(401)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it("forwards only the name, with the cookie token on X-Customer-Token, then re-issues the session cookies", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada Lovelace" }) })
    global.fetch = fetchSpy as unknown as typeof fetch
    mockRefresh.mockResolvedValue({ kind: "renewed", tokens: renewed })

    const res = await PUT(
      req(
        { displayName: "Ada Lovelace", email: "victim@example.com", sub: "someone-else" },
        { "jtoye-customer-access": "tok", "jtoye-customer-refresh": "ref" }
      )
    )

    expect(res.status).toBe(200)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(String(url)).toContain("/public/customer/profile")
    expect(init.method).toBe("PUT")
    expect(init.headers["X-Customer-Token"]).toBe("tok")
    expect(JSON.parse(init.body)).toEqual({ displayName: "Ada Lovelace" })
    expect(mockRefresh).toHaveBeenCalledWith("ref")
    const setCookie = res.headers.get("set-cookie") ?? ""
    expect(setCookie).toContain("jtoye-customer-id=")
    expect(setCookie).toContain("jtoye-customer-access=new-access")
  })

  it("an expired access cookie with a live refresh cookie is renewed BEFORE the call", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada" }) })
    global.fetch = fetchSpy as unknown as typeof fetch
    mockRefresh.mockResolvedValue({ kind: "renewed", tokens: renewed })

    await PUT(req({ displayName: "Ada" }, { "jtoye-customer-refresh": "ref" }))

    expect(fetchSpy.mock.calls[0][1].headers["X-Customer-Token"]).toBe("new-access")
  })

  it("a refresh the IdP refuses is a 401 and core is not called", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch
    mockRefresh.mockResolvedValue({ kind: "refused" })

    const res = await PUT(req({ displayName: "Ada" }, { "jtoye-customer-refresh": "dead" }))

    expect(res.status).toBe(401)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it("a refresh the IdP could not answer (transient) is a 401, core is not called, and no cookie is cleared", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch
    mockRefresh.mockResolvedValue({ kind: "transient" })

    const res = await PUT(req({ displayName: "Ada" }, { "jtoye-customer-refresh": "ref" }))

    expect(res.status).toBe(401)
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(res.headers.get("set-cookie")).toBeNull()
  })

  it("a post-write renewal that is refused leaves the cookies alone (the name change already succeeded)", async () => {
    global.fetch = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada" }) }) as unknown as typeof fetch
    mockRefresh.mockResolvedValue({ kind: "refused" })

    const res = await PUT(req({ displayName: "Ada" }, { "jtoye-customer-access": "tok", "jtoye-customer-refresh": "ref" }))

    expect(res.status).toBe(200)
    expect(res.headers.get("set-cookie")).toBeNull()
  })

  it("passes core's refusal through and does NOT re-issue cookies", async () => {
    global.fetch = jest.fn().mockResolvedValue({
      status: 503,
      json: async () => ({ type: "https://jtoye.uk/errors/profile-update-unavailable" }),
    }) as unknown as typeof fetch

    const res = await PUT(req({ displayName: "Ada" }, { "jtoye-customer-access": "tok", "jtoye-customer-refresh": "ref" }))

    expect(res.status).toBe(503)
    expect(mockRefresh).not.toHaveBeenCalled()
    expect(res.headers.get("set-cookie")).toBeNull()
  })

  it("an unreachable core is a 502", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("down")) as unknown as typeof fetch
    const res = await PUT(req({ displayName: "Ada" }, { "jtoye-customer-access": "tok" }))
    expect(res.status).toBe(502)
  })

  // Review round 1 (#1122): when the FIRST renewal succeeded the old refresh token is already revoked
  // (revokeRefreshToken=true, refreshTokenMaxReuse=0), so the renewed tokens must reach the browser on
  // EVERY outcome, or the next session probe signs the customer out.
  describe("a first renewal that succeeded is written to the cookies on every outcome", () => {
    const cookiesWith = { "jtoye-customer-refresh": "ref" } // access expired -> renewed first

    function setCookie(res: Response): string {
      return res.headers.get("set-cookie") ?? ""
    }

    it("core answers 400: the renewed tokens are still set", async () => {
      global.fetch = jest.fn().mockResolvedValue({ status: 400, json: async () => ({ title: "bad name" }) }) as unknown as typeof fetch
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed })
      const res = await PUT(req({ displayName: "" }, cookiesWith))
      expect(res.status).toBe(400)
      expect(setCookie(res)).toContain("jtoye-customer-refresh=new-refresh")
      expect(setCookie(res)).toContain("jtoye-customer-access=new-access")
      expect(mockRefresh).toHaveBeenCalledTimes(1)
    })

    it("core answers 503 (admin seam off): the renewed tokens are still set", async () => {
      global.fetch = jest.fn().mockResolvedValue({ status: 503, json: async () => ({}) }) as unknown as typeof fetch
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed })
      const res = await PUT(req({ displayName: "Ada" }, cookiesWith))
      expect(res.status).toBe(503)
      expect(setCookie(res)).toContain("jtoye-customer-refresh=new-refresh")
    })

    it("core is unreachable (502): the renewed tokens are still set", async () => {
      global.fetch = jest.fn().mockRejectedValue(new Error("down")) as unknown as typeof fetch
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed })
      const res = await PUT(req({ displayName: "Ada" }, cookiesWith))
      expect(res.status).toBe(502)
      expect(setCookie(res)).toContain("jtoye-customer-refresh=new-refresh")
    })

    it("core 2xx but the second renewal is refused: the first renewal's tokens are set", async () => {
      global.fetch = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada" }) }) as unknown as typeof fetch
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed }).mockResolvedValueOnce({ kind: "refused" })
      const res = await PUT(req({ displayName: "Ada" }, cookiesWith))
      expect(res.status).toBe(200)
      expect(setCookie(res)).toContain("jtoye-customer-refresh=new-refresh")
      expect(setCookie(res)).toContain("jtoye-customer-access=new-access")
    })

    it("core 2xx but the second renewal is transient: the first renewal's tokens are set", async () => {
      global.fetch = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada" }) }) as unknown as typeof fetch
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed }).mockResolvedValueOnce({ kind: "transient" })
      const res = await PUT(req({ displayName: "Ada" }, cookiesWith))
      expect(setCookie(res)).toContain("jtoye-customer-refresh=new-refresh")
    })

    it("core 2xx and the second renewal succeeds: the SECOND tokens replace the first", async () => {
      global.fetch = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ displayName: "Ada" }) }) as unknown as typeof fetch
      const second = { accessToken: "second-access", refreshToken: "second-refresh", idToken: "d.e.f", expiresAt: 9999999999 }
      mockRefresh.mockResolvedValueOnce({ kind: "renewed", tokens: renewed }).mockResolvedValueOnce({ kind: "renewed", tokens: second })
      const res = await PUT(req({ displayName: "Ada" }, cookiesWith))
      expect(setCookie(res)).toContain("jtoye-customer-refresh=second-refresh")
      expect(setCookie(res)).not.toContain("new-refresh")
      expect(mockRefresh).toHaveBeenNthCalledWith(2, "new-refresh")
    })
  })

  it("a body that is not a name is a 400 and core is not called", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch
    const res = await PUT(req({ displayName: 42 }, { "jtoye-customer-access": "tok" }))
    expect(res.status).toBe(400)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
