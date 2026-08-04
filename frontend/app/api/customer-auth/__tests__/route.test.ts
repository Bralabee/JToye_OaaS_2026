/**
 * @jest-environment node
 *
 * Contract tests for /api/customer-auth/{login,logout,session}. The bulk of
 * our security story lives in these routes: tokens must land in HttpOnly
 * cookies on login, must be cleared on logout, and must never be returned
 * to the browser by the session endpoint.
 */

import { POST as loginPOST } from "../login/route"
import { POST as logoutPOST } from "../logout/route"
import { GET as sessionGET } from "../session/route"
import { NextRequest } from "next/server"

function makeJson(body: unknown): NextRequest {
  return new NextRequest("http://localhost/api/customer-auth/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  })
}

// Build a minimal unsigned JWT with a specified exp and claims
function fakeJwt(claims: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url")
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url")
  return `${header}.${payload}.sig`
}

describe("/api/customer-auth/login", () => {
  it("rejects missing tokens", async () => {
    const res = await loginPOST(makeJson({}))
    expect(res.status).toBe(400)
  })

  it("sets three HttpOnly cookies with Lax SameSite on success", async () => {
    const req = makeJson({
      tokens: {
        accessToken: "access-xyz",
        refreshToken: "refresh-xyz",
        idToken: "id-xyz",
        expiresAt: Math.floor(Date.now() / 1000) + 3600,
      },
    })
    const res = await loginPOST(req)
    expect(res.status).toBe(200)

    const setCookie = res.headers.getSetCookie
      ? res.headers.getSetCookie()
      : [res.headers.get("set-cookie") || ""]

    const joined = setCookie.join("\n")
    expect(joined).toContain("jtoye-customer-access=access-xyz")
    expect(joined).toContain("jtoye-customer-refresh=refresh-xyz")
    expect(joined).toContain("jtoye-customer-id=id-xyz")
    expect(joined).toMatch(/HttpOnly/i)
    expect(joined).toMatch(/SameSite=lax/i)
    // Secure is only set in production; in jest env we expect it off
    expect(joined).not.toMatch(/Secure/i)

    const json = await res.json()
    expect(json.ok).toBe(true)
    expect(typeof json.expiresAt).toBe("number")
  })
})

describe("/api/customer-auth/logout", () => {
  const realFetch = global.fetch
  afterEach(() => {
    global.fetch = realFetch
  })

  function logoutRequest(cookies: Record<string, string> = {}): NextRequest {
    const req = new NextRequest("http://localhost/api/customer-auth/logout", { method: "POST" })
    for (const [k, v] of Object.entries(cookies)) req.cookies.set(k, v)
    return req
  }

  function assertCookiesCleared(res: Response) {
    const setCookie = res.headers.getSetCookie
      ? res.headers.getSetCookie()
      : [res.headers.get("set-cookie") || ""]

    const joined = setCookie.join("\n")
    for (const name of [
      "jtoye-customer-access",
      "jtoye-customer-refresh",
      "jtoye-customer-id",
    ]) {
      expect(joined).toContain(`${name}=`)
    }
    // maxAge=0 or Max-Age=0 depending on serializer
    expect(joined).toMatch(/Max-Age=0/i)
  }

  it("clears all three cookies with maxAge=0", async () => {
    const res = await logoutPOST(logoutRequest())
    expect(res.status).toBe(200)
    assertCookiesCleared(res)
  })

  // #504: the app-side clear is only half of sign-out. The IdP session has to
  // die too, and it must not depend on the front-channel redirect being right.
  it("revokes the refresh token at the IdP before clearing the cookies", async () => {
    const spy = jest.fn(async () => new Response(null, { status: 204 }))
    global.fetch = spy as unknown as typeof fetch
    const prev = process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL
    process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = "http://keycloak:8080/realms/jtoye-customers"
    try {
      const res = await logoutPOST(logoutRequest({ "jtoye-customer-refresh": "refresh-xyz" }))
      expect(res.status).toBe(200)
      expect(await res.json()).toEqual({ ok: true, idp: "ok" })
      expect(spy).toHaveBeenCalledTimes(1)
      expect(String((spy.mock.calls[0] as unknown as [string])[0])).toContain(
        "/protocol/openid-connect/logout"
      )
      assertCookiesCleared(res)
    } finally {
      if (prev === undefined) delete process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL
      else process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = prev
    }
  })

  // The failure direction that matters most: a shopper who pressed Sign out has
  // to end up signed out of the app even when the IdP is unreachable.
  it("still clears the cookies when the IdP call fails", async () => {
    global.fetch = (async () => {
      throw new Error("ECONNREFUSED")
    }) as unknown as typeof fetch
    const prev = process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL
    process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = "http://keycloak:8080/realms/jtoye-customers"
    try {
      const res = await logoutPOST(logoutRequest({ "jtoye-customer-refresh": "refresh-xyz" }))
      expect(res.status).toBe(200)
      expect(await res.json()).toEqual({ ok: true, idp: "failed" })
      assertCookiesCleared(res)
    } finally {
      if (prev === undefined) delete process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL
      else process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = prev
    }
  })
})

describe("/api/customer-auth/session", () => {
  it("returns 200 { authenticated: false } (no profile) without cookies — quiet probe, #13", async () => {
    const req = new NextRequest("http://localhost/api/customer-auth/session")
    const res = await sessionGET(req)
    // 200 (not 401) so the browser doesn't log a failed request on every
    // anonymous public page view; the body carries no session data.
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.authenticated).toBe(false)
    expect(body.profile).toBeUndefined()
  })

  it("returns profile but never the raw tokens when the session is valid", async () => {
    const exp = Math.floor(Date.now() / 1000) + 3600
    const idToken = fakeJwt({
      sub: "user-1",
      email: "alice@example.com",
      name: "Alice",
      email_verified: true,
      exp,
    })
    const req = new NextRequest("http://localhost/api/customer-auth/session", {
      headers: {
        cookie: [
          `jtoye-customer-access=access-xyz`,
          `jtoye-customer-id=${idToken}`,
        ].join("; "),
      },
    })
    const res = await sessionGET(req)
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.authenticated).toBe(true)
    expect(body.profile).toEqual({
      sub: "user-1",
      email: "alice@example.com",
      name: "Alice",
      emailVerified: true,
    })
    const serialized = JSON.stringify(body)
    expect(serialized).not.toContain("access-xyz")
    expect(serialized).not.toContain(idToken)
  })

  it("returns 200 { authenticated: false } (no profile) when the id token is expired — quiet probe, #13", async () => {
    const exp = Math.floor(Date.now() / 1000) - 60
    const idToken = fakeJwt({ sub: "user-1", exp })
    const req = new NextRequest("http://localhost/api/customer-auth/session", {
      headers: {
        cookie: [
          `jtoye-customer-access=access-xyz`,
          `jtoye-customer-id=${idToken}`,
        ].join("; "),
      },
    })
    const res = await sessionGET(req)
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.authenticated).toBe(false)
    expect(body.profile).toBeUndefined()
  })
})
