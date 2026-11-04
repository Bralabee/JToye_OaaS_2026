/**
 * @jest-environment node
 *
 * Issue #843 (a regression of #465 on a slow network) — the server half, STATELESS.
 *
 * An earlier design held the renewed tokens in a per-process memo for 60 s and answered a late
 * probe carrying the spent token with them. Review (PR #1114) rejected it: it hands a live refresh
 * token to anyone holding the spent one, it survives sign-out (a slow pre-sign-out probe re-set the
 * cookies afterwards), and it is per pod while staging scales the frontend to 10. So the route
 * keeps NO tokens at all. What it decides is only whether to CLEAR the cookies:
 *
 *   - a refusal from the IdP (a 4xx: invalid_grant) answers `authenticated:false` and leaves the
 *     cookies alone, and says `refreshRefused` so the browser may probe once more with the cookie
 *     it holds NOW;
 *   - a refusal on a request that carries the retry marker (the query parameter `?retry=1`) is final: the
 *     three cookies are cleared;
 *   - a transient failure (5xx, 429, network, an unreadable answer) NEVER clears anything.
 *
 * MEASURED on Keycloak 26.8.1 (evidence/reuse-detection-measured.txt): replaying a spent refresh
 * token is refused AND revokes the session, so its successor then fails too. The fake IdP below
 * behaves that way, so no test here can pass by assuming a replay is harmless. What keeps a
 * replay from ever being sent is the browser-side serialisation tested in
 * lib/__tests__/customer-session-transition.test.ts.
 */

import { GET as sessionGET } from "../session/route"
import { NextRequest } from "next/server"

function fakeJwt(claims: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url")
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url")
  return `${header}.${payload}.sig`
}

function liveIdToken() {
  return fakeJwt({
    sub: "user-1",
    email: "alice@example.com",
    name: "Alice",
    email_verified: true,
    exp: Math.floor(Date.now() / 1000) + 300,
  })
}

function probe(refreshValue: string | null, retry = false): NextRequest {
  const expiredId = fakeJwt({ sub: "user-1", exp: Math.floor(Date.now() / 1000) - 60 })
  const cookies = [`jtoye-customer-id=${expiredId}`]
  if (refreshValue) cookies.push(`jtoye-customer-refresh=${refreshValue}`)
  return new NextRequest(`http://localhost/api/customer-auth/session${retry ? "?retry=1" : ""}`, {
    headers: { cookie: cookies.join("; ") },
  })
}

function setCookieNames(res: Response): string[] {
  return (res.headers.getSetCookie?.() ?? []).map((c) => c.split("=")[0])
}

function setCookieValue(res: Response, name: string): string | null {
  const hit = (res.headers.getSetCookie?.() ?? []).find((c) => c.startsWith(`${name}=`))
  return hit ? hit.slice(name.length + 1).split(";")[0] : null
}

const COOKIES = ["jtoye-customer-access", "jtoye-customer-refresh", "jtoye-customer-id"]

/** The realm as measured: single-use tokens, and a replay revokes the whole session. */
function realmIdp(initial: string[]) {
  const family = new Map<string, number>()
  const spent = new Set<string>()
  const revoked = new Set<number>()
  initial.forEach((t) => family.set(t, 0))
  let issued = 0
  const redeemed: string[] = []
  const fetchMock = jest.fn().mockImplementation(async (_u: string, init: RequestInit) => {
    const sent = new URLSearchParams(String(init.body)).get("refresh_token")!
    redeemed.push(sent)
    const fam = family.get(sent)
    if (fam === undefined || revoked.has(fam)) {
      return new Response(JSON.stringify({ error: "invalid_grant", error_description: "Session not active" }), { status: 400 })
    }
    if (spent.has(sent)) {
      revoked.add(fam)
      return new Response(JSON.stringify({ error: "invalid_grant", error_description: "Maximum allowed refresh token reuse exceeded" }), { status: 400 })
    }
    spent.add(sent)
    issued += 1
    const next = `${sent}-r${issued}`
    family.set(next, fam)
    return new Response(
      JSON.stringify({ access_token: `access-${issued}`, refresh_token: next, id_token: liveIdToken(), expires_in: 300 }),
      { status: 200, headers: { "content-type": "application/json" } }
    )
  })
  global.fetch = fetchMock as unknown as typeof fetch
  return { fetchMock, redeemed, revoke: () => family.forEach((f) => revoked.add(f)) }
}

const ORIGINAL_ENV = { ...process.env }
beforeEach(() => {
  process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = "http://keycloak:8080/realms/jtoye-customers"
  jest.restoreAllMocks()
})
afterEach(() => {
  process.env = { ...ORIGINAL_ENV }
})

describe("/api/customer-auth/session — stateless renewal (#843)", () => {
  it("renews with a live token, with or without the retry marker, and hands back the rotated one", async () => {
    realmIdp(["A0"])
    const res = await sessionGET(probe("A0", true))
    expect((await res.json()).authenticated).toBe(true)
    expect(setCookieValue(res, "jtoye-customer-refresh")).toBe("A0-r1")
  })

  it("a refused probe WITHOUT the retry marker clears nothing and invites exactly one retry", async () => {
    realmIdp(["B0"])
    await sessionGET(probe("B0")) // probe 1 redeems B0
    const late = await sessionGET(probe("B0")) // probe 2 left with the same cookie: a replay

    const body = await late.json()
    expect(body.authenticated).toBe(false)
    expect(body.refreshRefused).toBe(true)
    // It must not overwrite what probe 1 just issued, nor re-set anything.
    expect(setCookieNames(late)).toEqual([])
  })

  it("holds no tokens: the answer to a replay never carries the successor's tokens", async () => {
    realmIdp(["C0"])
    const first = await sessionGET(probe("C0"))
    expect(setCookieValue(first, "jtoye-customer-refresh")).toBe("C0-r1")
    const replay = await sessionGET(probe("C0"))
    const text = JSON.stringify(await replay.json()) + (replay.headers.get("set-cookie") ?? "")
    expect(text).not.toContain("C0-r1")
    expect(text).not.toContain("access-1")
  })

  it("SIGN-OUT then a slow probe carrying the old token: the customer stays signed out and no cookie is re-set", async () => {
    const idp = realmIdp(["D0"])
    const renewed = await sessionGET(probe("D0")) // renewal succeeded: cookie jar holds D0-r1
    expect(setCookieValue(renewed, "jtoye-customer-refresh")).toBe("D0-r1")
    idp.revoke() // the customer presses Sign out: logout revokes the session at the IdP
    // a slow probe sent BEFORE sign-out lands afterwards, still carrying D0
    const late = await sessionGET(probe("D0"))
    expect((await late.json()).authenticated).toBe(false)
    for (const name of COOKIES) expect(setCookieNames(late)).not.toContain(name)
    // and the browser, whose jar is now empty, sends nothing to redeem
    const after = await sessionGET(probe(null))
    expect((await after.json()).authenticated).toBe(false)
    expect(setCookieNames(after)).toEqual([])
  })

  it("a genuinely dead session is cleared by the retry, not before", async () => {
    realmIdp([]) // the realm knows no such token
    const first = await sessionGET(probe("DEAD"))
    expect((await first.json()).refreshRefused).toBe(true)
    expect(setCookieNames(first)).toEqual([])

    const retry = await sessionGET(probe("DEAD", true))
    expect((await retry.json()).authenticated).toBe(false)
    for (const name of COOKIES) expect(setCookieValue(retry, name)).toBe("")
  })

  it.each([
    ["a Keycloak 503", () => new Response("{}", { status: 503 })],
    ["a 429", () => new Response("{}", { status: 429 })],
    ["an unreadable 200", () => new Response("not json", { status: 200 })],
  ])("%s clears nothing, with or without the retry marker", async (_n, answer) => {
    global.fetch = jest.fn().mockImplementation(async () => answer()) as unknown as typeof fetch
    for (const retry of [false, true]) {
      const res = await sessionGET(probe("T0", retry))
      const body = await res.json()
      expect(body.authenticated).toBe(false)
      expect(body.refreshRefused).toBeUndefined()
      expect(setCookieNames(res)).toEqual([])
    }
  })

  it("a network failure clears nothing, with or without the retry marker", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("ECONNREFUSED")) as unknown as typeof fetch
    for (const retry of [false, true]) {
      const res = await sessionGET(probe("N0", retry))
      expect((await res.json()).authenticated).toBe(false)
      expect(setCookieNames(res)).toEqual([])
    }
  })

  it("an anonymous visitor is never told to retry and nothing is redeemed", async () => {
    const idp = realmIdp([])
    const res = await sessionGET(probe(null))
    const body = await res.json()
    expect(body.authenticated).toBe(false)
    expect(body.refreshRefused).toBeUndefined()
    expect(idp.fetchMock).not.toHaveBeenCalled()
  })
})
