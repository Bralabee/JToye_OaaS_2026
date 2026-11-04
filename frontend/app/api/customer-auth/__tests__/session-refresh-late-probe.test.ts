/**
 * @jest-environment node
 *
 * Issue #843 — a regression of #465 on a slow network.
 *
 * The realm rotates refresh tokens (revokeRefreshToken=true, refreshTokenMaxReuse=0), so a
 * refresh token is redeemable exactly once. The single-flight guard in
 * lib/customer-token-refresh.ts collapsed probes that overlap IN TIME, and released the moment
 * the redemption settled. Under latency (the persona run: Slow 3G, 2000 ms RTT, 20% loss) the
 * storefront's several session probes all leave the browser carrying the SAME cookie, but reach
 * the server at different times: the first redeems and settles, and a probe that arrives AFTER
 * that finds no in-flight entry, redeems the already-rotated token, is refused by the IdP, and
 * the route answers `authenticated:false` with Set-Cookie clearing all three customer cookies,
 * overwriting the cookies the first probe had just issued. The customer is signed out.
 *
 * These tests pin the late-probe shape specifically: the second request arrives after the first
 * has fully settled. The "redeems ONCE for concurrent probes" test in session-refresh.test.ts
 * cannot see it, because its probes overlap.
 */

import { GET as sessionGET } from "../session/route"
import { NextRequest } from "next/server"
import { __resetRefreshMemoForTests } from "@/lib/customer-token-refresh"

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

function expiredWithRefresh(refreshValue: string): NextRequest {
  const expiredId = fakeJwt({ sub: "user-1", exp: Math.floor(Date.now() / 1000) - 60 })
  return new NextRequest("http://localhost/api/customer-auth/session", {
    headers: {
      cookie: [`jtoye-customer-id=${expiredId}`, `jtoye-customer-refresh=${refreshValue}`].join("; "),
    },
  })
}

function setCookieValue(res: Response, name: string): string | null {
  const hit = (res.headers.getSetCookie?.() ?? []).find((c) => c.startsWith(`${name}=`))
  return hit ? hit.slice(name.length + 1).split(";")[0] : null
}

/**
 * A faithful stand-in for the realm: each refresh token is single use; a second redemption of the
 * same token is `invalid_grant`. This is the property the memo exists to respect.
 */
function rotatingIdp() {
  const spent = new Set<string>()
  let issued = 0
  const redemptions: string[] = []
  const fetchMock = jest.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    const sent = new URLSearchParams(String(init.body)).get("refresh_token")!
    redemptions.push(sent)
    if (spent.has(sent)) {
      return new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 })
    }
    spent.add(sent)
    issued += 1
    return new Response(
      JSON.stringify({
        access_token: `access-${issued}`,
        refresh_token: `rotated-${issued}`,
        id_token: liveIdToken(),
        expires_in: 300,
      }),
      { status: 200, headers: { "content-type": "application/json" } }
    )
  })
  global.fetch = fetchMock as unknown as typeof fetch
  return { fetchMock, redemptions }
}

const ORIGINAL_ENV = { ...process.env }

beforeEach(() => {
  process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL = "http://keycloak:8080/realms/jtoye-customers"
  jest.restoreAllMocks()
  __resetRefreshMemoForTests()
})

afterEach(() => {
  jest.useRealTimers()
  process.env = { ...ORIGINAL_ENV }
})

describe("/api/customer-auth/session — late probe after a settled refresh (#843)", () => {
  it("answers a probe that carries the already-rotated token with the SAME renewed session, not a sign-out", async () => {
    const { redemptions } = rotatingIdp()

    // Probe 1 redeems and settles completely.
    const first = await sessionGET(expiredWithRefresh("R0"))
    expect((await first.json()).authenticated).toBe(true)

    // Probe 2 left the browser before probe 1's response landed, so it still carries R0.
    const late = await sessionGET(expiredWithRefresh("R0"))
    const body = await late.json()

    expect(body.authenticated).toBe(true)
    // It must not clear the cookies probe 1 just issued ...
    for (const name of ["jtoye-customer-access", "jtoye-customer-refresh", "jtoye-customer-id"]) {
      expect(setCookieValue(late, name)).not.toBe("")
    }
    // ... and must hand back the SAME rotated refresh token, so whichever response the browser
    // stores last, the cookie jar ends up holding a redeemable token.
    expect(setCookieValue(late, "jtoye-customer-refresh")).toBe("rotated-1")
    // The IdP saw R0 exactly once.
    expect(redemptions).toEqual(["R0"])
  })

  it("keeps a customer signed in across four probes spread over a slow connection (fake timers)", async () => {
    jest.useFakeTimers({ now: Date.now() })
    const { redemptions } = rotatingIdp()

    const results: Response[] = []
    // The persona run: a probe on mount, then one a second for five seconds. Under 2000 ms RTT
    // each is answered late, so every one carries R0.
    for (let t = 0; t < 4; t += 1) {
      results.push(await sessionGET(expiredWithRefresh("R0")))
      jest.advanceTimersByTime(1500)
    }

    for (const r of results) expect((await r.json()).authenticated).toBe(true)
    expect(redemptions).toEqual(["R0"])
  })

  it("does not replay a spent token for ever: past the grace window the IdP is asked again and refuses", async () => {
    jest.useFakeTimers({ now: Date.now() })
    const { redemptions } = rotatingIdp()

    expect((await (await sessionGET(expiredWithRefresh("R0"))).json()).authenticated).toBe(true)

    // Well past any in-flight request: a replay of R0 is no longer a late probe.
    jest.advanceTimersByTime(10 * 60 * 1000)

    const replay = await sessionGET(expiredWithRefresh("R0"))
    expect((await replay.json()).authenticated).toBe(false)
    expect(redemptions).toEqual(["R0", "R0"])
    expect(setCookieValue(replay, "jtoye-customer-refresh")).toBe("")
  })

  it("never hands one customer's renewed session to a different refresh token", async () => {
    const { redemptions } = rotatingIdp()

    await sessionGET(expiredWithRefresh("R0"))
    const other = await sessionGET(expiredWithRefresh("SOMEONE-ELSE"))

    // A different token is a different redemption, not a memo hit.
    expect(redemptions).toEqual(["R0", "SOMEONE-ELSE"])
    expect(setCookieValue(other, "jtoye-customer-refresh")).toBe("rotated-2")
  })

  it("does not memoise a refusal: a transient failure must not pin a customer signed out", async () => {
    let call = 0
    global.fetch = jest.fn().mockImplementation(async () => {
      call += 1
      if (call === 1) return new Response(JSON.stringify({ error: "server_error" }), { status: 503 })
      return new Response(
        JSON.stringify({ access_token: "a", refresh_token: "r", id_token: liveIdToken(), expires_in: 300 }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    }) as unknown as typeof fetch

    expect((await (await sessionGET(expiredWithRefresh("R0"))).json()).authenticated).toBe(false)
    expect((await (await sessionGET(expiredWithRefresh("R0"))).json()).authenticated).toBe(true)
  })
})
