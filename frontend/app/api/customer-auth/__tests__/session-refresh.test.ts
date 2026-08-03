/**
 * @jest-environment node
 *
 * Issue #465 — the customer session must survive the access-token lifespan.
 *
 * Before this, /api/customer-auth/session answered `authenticated: false` the
 * moment the short-lived access cookie expired (300s on the jtoye-customers
 * realm) while a 30-day refresh token sat unused in an HttpOnly cookie. Measured
 * in a browser: six minutes of continuous navigation ended in a forced sign-out.
 *
 * These tests pin the renewal path, and specifically the two things that make it
 * fail in a way unit tests usually miss:
 *   - the ROTATED refresh token must be persisted (the realm sets
 *     revokeRefreshToken=true / refreshTokenMaxReuse=0, so reusing the old one is
 *     rejected and the customer is signed out on the NEXT refresh, not this one);
 *   - concurrent probes must redeem exactly once, because StorefrontNav fires
 *     several session checks around the expiry boundary.
 */

import { GET as sessionGET } from "../session/route"
import { NextRequest } from "next/server"

function fakeJwt(claims: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url")
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url")
  return `${header}.${payload}.sig`
}

function futureJwt(sub = "user-1") {
  return fakeJwt({
    sub,
    email: "alice@example.com",
    name: "Alice",
    email_verified: true,
    exp: Math.floor(Date.now() / 1000) + 300,
  })
}

/** A request carrying an EXPIRED access state plus a live refresh cookie. */
function expiredWithRefresh(refreshValue: string): NextRequest {
  const expiredId = fakeJwt({ sub: "user-1", exp: Math.floor(Date.now() / 1000) - 60 })
  return new NextRequest("http://localhost/api/customer-auth/session", {
    headers: {
      cookie: [
        `jtoye-customer-id=${expiredId}`,
        `jtoye-customer-refresh=${refreshValue}`,
      ].join("; "),
    },
  })
}

function setCookieValue(res: Response, name: string): string | null {
  const all = res.headers.getSetCookie?.() ?? []
  const hit = all.find((c) => c.startsWith(`${name}=`))
  if (!hit) return null
  return hit.slice(name.length + 1).split(";")[0]
}

const ORIGINAL_ENV = { ...process.env }

beforeEach(() => {
  process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = "http://keycloak:8080/realms/jtoye-customers"
  jest.restoreAllMocks()
})

afterEach(() => {
  process.env = { ...ORIGINAL_ENV }
})

describe("/api/customer-auth/session — renewal (#465)", () => {
  it("redeems the refresh token when the access token has expired, instead of signing the customer out", async () => {
    const fetchMock = jest.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          access_token: "new-access",
          refresh_token: "rotated-1",
          id_token: futureJwt(),
          expires_in: 300,
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    )
    global.fetch = fetchMock as unknown as typeof fetch

    const res = await sessionGET(expiredWithRefresh("original-refresh"))
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.authenticated).toBe(true)
    expect(body.profile.email).toBe("alice@example.com")

    // It went to the INTERNAL issuer with a refresh_token grant.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe("http://keycloak:8080/realms/jtoye-customers/protocol/openid-connect/token")
    expect(String(init.body)).toContain("grant_type=refresh_token")

    // And the tokens never appear in the response body.
    const serialized = JSON.stringify(body)
    expect(serialized).not.toContain("new-access")
    expect(serialized).not.toContain("rotated-1")
  })

  it("persists the ROTATED refresh token, not the one it was given", async () => {
    global.fetch = jest.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          access_token: "new-access",
          refresh_token: "rotated-1",
          id_token: futureJwt(),
          expires_in: 300,
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    ) as unknown as typeof fetch

    const res = await sessionGET(expiredWithRefresh("original-refresh"))

    expect(setCookieValue(res, "jtoye-customer-refresh")).toBe("rotated-1")
    expect(setCookieValue(res, "jtoye-customer-access")).toBe("new-access")
  })

  it("survives TWO consecutive refreshes — a single-refresh test passes even when rotation is mishandled", async () => {
    const issued: string[] = []
    global.fetch = jest.fn().mockImplementation((_url: string, init: RequestInit) => {
      const sent = new URLSearchParams(String(init.body)).get("refresh_token")!
      issued.push(sent)
      const next = `rotated-${issued.length}`
      return Promise.resolve(
        new Response(
          JSON.stringify({
            access_token: `access-${issued.length}`,
            refresh_token: next,
            id_token: futureJwt(),
            expires_in: 300,
          }),
          { status: 200, headers: { "content-type": "application/json" } }
        )
      )
    }) as unknown as typeof fetch

    const first = await sessionGET(expiredWithRefresh("original-refresh"))
    const carried = setCookieValue(first, "jtoye-customer-refresh")!
    const second = await sessionGET(expiredWithRefresh(carried))

    expect((await second.json()).authenticated).toBe(true)
    // The second redemption used the token the first one issued — this is the
    // assertion that fails when the old token is written back.
    expect(issued).toEqual(["original-refresh", "rotated-1"])
    expect(setCookieValue(second, "jtoye-customer-refresh")).toBe("rotated-2")
  })

  it("redeems ONCE for concurrent probes — rotation makes a double redemption fatal", async () => {
    let calls = 0
    global.fetch = jest.fn().mockImplementation(async () => {
      calls += 1
      await new Promise((r) => setTimeout(r, 10))
      return new Response(
        JSON.stringify({
          access_token: "new-access",
          refresh_token: "rotated-1",
          id_token: futureJwt(),
          expires_in: 300,
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    }) as unknown as typeof fetch

    const results = await Promise.all([
      sessionGET(expiredWithRefresh("shared-token")),
      sessionGET(expiredWithRefresh("shared-token")),
      sessionGET(expiredWithRefresh("shared-token")),
    ])

    for (const r of results) expect((await r.json()).authenticated).toBe(true)
    expect(calls).toBe(1)
  })

  it("clears all three cookies when the IdP refuses the refresh token", async () => {
    global.fetch = jest.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 })
    ) as unknown as typeof fetch

    const res = await sessionGET(expiredWithRefresh("dead-token"))
    const body = await res.json()

    expect(res.status).toBe(200) // still never a 401 — backlog #13
    expect(body.authenticated).toBe(false)
    for (const name of [
      "jtoye-customer-access",
      "jtoye-customer-refresh",
      "jtoye-customer-id",
    ]) {
      expect(setCookieValue(res, name)).toBe("")
    }
  })

  it("does not attempt a refresh for an anonymous visitor — the quiet-probe path is untouched", async () => {
    const fetchMock = jest.fn()
    global.fetch = fetchMock as unknown as typeof fetch

    const res = await sessionGET(new NextRequest("http://localhost/api/customer-auth/session"))

    expect((await res.json()).authenticated).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("fails closed when the IdP is unreachable, rather than reporting a live session", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("ECONNREFUSED")) as unknown as typeof fetch

    const res = await sessionGET(expiredWithRefresh("some-token"))
    expect((await res.json()).authenticated).toBe(false)
  })
})
