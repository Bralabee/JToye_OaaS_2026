/**
 * @jest-environment node
 *
 * FE-1 (QA council 20260902-134741, **Critical**) — the vendor app-session
 * cookie survived "Sign Out".
 *
 * MECHANISM, so the shape of this route makes sense. `@auth/core`'s session
 * action re-signs and RE-ISSUES the JWT cookie on every `GET /api/auth/session`,
 * and `lib/api-client.ts` fires one per axios request (~24 on a dashboard
 * load). The client `signOut()`'s clearing `Set-Cookie` therefore raced
 * in-flight session responses that were still carrying the valid cookie, and
 * whichever landed LAST won — 9/12 desktop runs re-entered the dashboard with
 * no credential prompt. No amount of client patience can beat a response that
 * was dispatched before the clear (measured: the client gap was ~31 ms on both
 * passing and failing runs).
 *
 * THE FIX is to clear the session in the response the browser processes LAST:
 * this route is Keycloak's `post_logout_redirect_uri`. By the time it answers,
 * the dashboard document has been destroyed by two navigations (app -> Keycloak
 * -> app), so no in-flight session GET from it can exist, and there is no
 * writer left to resurrect the cookie.
 *
 * WHAT THESE TESTS CAN AND CANNOT SAY. They assert the route calls the SERVER
 * `signOut` (the canonical Auth.js way to end a session from the server — it
 * owns the cookie names, the `__Secure-` prefix and the chunk cleaning, none of
 * which is typed out here) and that the clearing cookies ride on the redirect
 * it returns. Whether a real browser then finds `/dashboard` closed is the
 * orchestrator's rebuilt-stack probe (`probes/fe-signout-repeat.js --no-settle`).
 *
 * `signOut` is mocked at the `@/auth` boundary with the shape the installed
 * `next-auth@5.0.0-beta.32` returns for `{ redirect: false }`
 * (`node_modules/next-auth/lib/actions.js:54-70`): the raw `@auth/core`
 * response whose `cookies` array carries the clearing cookies.
 *
 * PR #726 review, M4. A bare GET that clears the session is cross-site reachable:
 * `<img src="https://vendor.example/api/vendor-auth/logout-complete">` on any
 * page force-signs-out any vendor. Keycloak's return leg MUST be a GET, so the
 * route is now bound to the sign-out that started it by OIDC RP-initiated-logout
 * `state`: `logout-url` mints it into a short-lived cookie and onto the
 * end-session URL, Keycloak echoes it back as `?state=`, and this route clears
 * the session ONLY when the two agree. Every request below therefore carries (or
 * deliberately omits) a state pair, and the first three arms are the ones that
 * would have been green on the unbound route.
 */

import { NextRequest } from "next/server"
import { signOut } from "@/auth"
import { GET as logoutCompleteGET } from "../logout-complete/route"

jest.mock("@/auth", () => ({ auth: jest.fn(), signOut: jest.fn() }))

const mockSignOut = signOut as unknown as jest.Mock

const FLAG = "VENDOR_LOGOUT_COMPLETE_ENABLED"
const STATE_COOKIE = "jtoye-vendor-logout-state"
const STATE = "3f9c1a2e-7b44-4c0d-9e1a-0f6d2b8c4a11"

/**
 * The request Keycloak's redirect produces: a top-level GET on the container's
 * bind origin, with whatever `?state=` Keycloak echoed and whatever state cookie
 * the browser still holds from the `logout-url` response.
 */
function returnLeg(opts: { query?: string | null; cookie?: string | null } = {}): NextRequest {
  const { query = STATE, cookie = STATE } = opts
  const url = new URL("http://0.0.0.0:3000/api/vendor-auth/logout-complete")
  if (query !== null) url.searchParams.set("state", query)
  const headers: Record<string, string> = { host: "vendor.example.test" }
  if (cookie !== null) headers.cookie = `${STATE_COOKIE}=${cookie}`
  return new NextRequest(url, { headers })
}

/** The clearing Set-Cookie lines for the Auth.js session, if any. */
function sessionClearingCookies(res: Response): string[] {
  return setCookies(res).filter((c) => c.startsWith("authjs.session-token"))
}

/** What `@auth/core` returns when a session cookie WAS present: its clean(). */
const CLEARING = {
  redirect: "/",
  cookies: [
    { name: "authjs.session-token", value: "", options: { maxAge: 0, path: "/", httpOnly: true, sameSite: "lax" } },
    { name: "authjs.session-token.0", value: "", options: { maxAge: 0, path: "/", httpOnly: true, sameSite: "lax" } },
    { name: "authjs.session-token.1", value: "", options: { maxAge: 0, path: "/", httpOnly: true, sameSite: "lax" } },
  ],
}

/**
 * What `@auth/core` returns when there was NO session cookie: it returns
 * early with the cookies it was handed, which is nothing
 * (`node_modules/@auth/core/lib/actions/signout.js`: `if (!sessionToken) return`).
 */
const NOTHING_TO_CLEAR = { redirect: "/", cookies: [] }

const withEnv = async (env: Record<string, string | undefined>, fn: () => Promise<void>) => {
  const saved: Record<string, string | undefined> = {}
  for (const k of Object.keys(env)) {
    saved[k] = process.env[k]
    if (env[k] === undefined) delete process.env[k]
    else process.env[k] = env[k] as string
  }
  try {
    await fn()
  } finally {
    for (const k of Object.keys(env)) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k] as string
    }
  }
}

function setCookies(res: Response): string[] {
  const h = res.headers as Headers & { getSetCookie?: () => string[] }
  return typeof h.getSetCookie === "function" ? h.getSetCookie() : []
}

beforeEach(() => {
  mockSignOut.mockReset()
  mockSignOut.mockResolvedValue(CLEARING)
})

describe("/api/vendor-auth/logout-complete — M4: the clear is BOUND to the sign-out that started it", () => {
  it("MISSING ?state= (the cross-site <img src> shape): does NOT clear the session, no side effects", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg({ query: null }))

      expect(mockSignOut).not.toHaveBeenCalled()
      expect(sessionClearingCookies(res)).toHaveLength(0)
      // No side effects at all — the state cookie is left alone too, so a forged
      // hit cannot even cancel a legitimate sign-out that is mid-flight.
      expect(setCookies(res)).toHaveLength(0)
      // Still the vendor's normal landing, never an error page (E-5 shape).
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })

  it("WRONG ?state= (guessed or replayed): does NOT clear the session", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg({ query: "00000000-0000-4000-8000-000000000000" }))

      expect(mockSignOut).not.toHaveBeenCalled()
      expect(setCookies(res)).toHaveLength(0)
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })

  it("MISSING state COOKIE (no sign-out was started in this browser): does NOT clear the session", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg({ cookie: null }))

      expect(mockSignOut).not.toHaveBeenCalled()
      expect(setCookies(res)).toHaveLength(0)
    })
  })

  it("a state that matches only as a PREFIX is not a match (length is part of the comparison)", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg({ query: STATE.slice(0, -1) }))
      expect(mockSignOut).not.toHaveBeenCalled()
      expect(setCookies(res)).toHaveLength(0)
    })
  })

  it("MATCHING state: clears the session AND expires the one-shot state cookie", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg())

      expect(mockSignOut).toHaveBeenCalledTimes(1)
      expect(sessionClearingCookies(res)).toHaveLength(3)
      const stateLine = setCookies(res).find((c) => c.startsWith(`${STATE_COOKIE}=`))
      expect(stateLine).toBeDefined()
      expect(stateLine).toMatch(/Max-Age=0/i)
      // Scoped exactly as it was set, or the browser would not treat it as the same cookie.
      expect(stateLine).toMatch(/Path=\/api\/vendor-auth/i)
    })
  })

  it("FLAG OFF with a MATCHING state: still does not touch the session (the flag still governs)", async () => {
    await withEnv({ [FLAG]: "false" }, async () => {
      const res = await logoutCompleteGET(returnLeg())
      expect(mockSignOut).not.toHaveBeenCalled()
      expect(setCookies(res)).toHaveLength(0)
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })
})

describe("/api/vendor-auth/logout-complete — FLAG ON: the server-side clear on the return leg (FE-1)", () => {
  it("calls the SERVER signOut with redirect:false and redirects to /auth/signin", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg())

      expect(mockSignOut).toHaveBeenCalledTimes(1)
      expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
      expect(res.status).toBe(302)
      // RELATIVE Location, deliberately: the browser resolves it against the
      // URL it actually requested, which is the public origin. An absolute
      // one would need the container's origin resolved — and `nextUrl.origin`
      // is the bind address (0.0.0.0:3000) in there.
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })

  it("carries the clearing Set-Cookie lines on THIS response (Max-Age=0 on every chunk)", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg())
      const cookies = sessionClearingCookies(res)

      // Non-vacuity: three chunks in, three clearing lines out.
      expect(cookies).toHaveLength(3)
      for (const name of ["authjs.session-token", "authjs.session-token.0", "authjs.session-token.1"]) {
        const line = cookies.find((c) => c.startsWith(`${name}=`))
        expect(line).toBeDefined()
        expect(line).toMatch(/Max-Age=0/i)
      }
    })
  })

  it("with NO session there is nothing to clear: still 302 to /auth/signin, no Set-Cookie, no throw", async () => {
    mockSignOut.mockResolvedValue(NOTHING_TO_CLEAR)
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg())
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
      expect(sessionClearingCookies(res)).toHaveLength(0)
    })
  })

  it("a THROWING signOut never strands the vendor: still 302 to /auth/signin", async () => {
    // The whole point of E-5 is that the worst case is today's defect, never
    // a vendor parked on an error page with SSO alive.
    mockSignOut.mockRejectedValue(new Error("auth misconfigured"))
    const quiet = jest.spyOn(console, "error").mockImplementation(() => {})
    try {
      await withEnv({ [FLAG]: "true" }, async () => {
        const res = await logoutCompleteGET(returnLeg())
        expect(res.status).toBe(302)
        expect(res.headers.get("location")).toBe("/auth/signin")
      })
    } finally {
      quiet.mockRestore()
    }
  })

  it("is never cacheable (the response mutates the session)", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET(returnLeg())
      expect(res.headers.get("cache-control")).toBe("private, no-store, max-age=0")
    })
  })
})

describe("/api/vendor-auth/logout-complete — FLAG OFF: today's landing, untouched (E-5 fail-safe)", () => {
  it.each([
    ["unset", undefined],
    ["false", "false"],
    ["blank", "  "],
    ["yes (not an accepted spelling)", "yes"],
  ])("with the flag %s, does NOT call signOut and redirects to /auth/signin", async (_label, value) => {
    await withEnv({ [FLAG]: value }, async () => {
      const res = await logoutCompleteGET(returnLeg())
      expect(mockSignOut).not.toHaveBeenCalled()
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })

  it("CONTROL: '1' is an accepted ON spelling, so the OFF arms above are not passing vacuously", async () => {
    await withEnv({ [FLAG]: "1" }, async () => {
      await logoutCompleteGET(returnLeg())
      expect(mockSignOut).toHaveBeenCalledTimes(1)
    })
  })
})
