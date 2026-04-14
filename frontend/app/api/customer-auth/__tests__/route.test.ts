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
  it("clears all three cookies with maxAge=0", async () => {
    const res = await logoutPOST()
    expect(res.status).toBe(200)

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
  })
})

describe("/api/customer-auth/session", () => {
  it("returns 401 without cookies", async () => {
    const req = new NextRequest("http://localhost/api/customer-auth/session")
    const res = await sessionGET(req)
    expect(res.status).toBe(401)
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

  it("returns 401 when the id token is expired", async () => {
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
    expect(res.status).toBe(401)
  })
})
