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
 */

import { signOut } from "@/auth"
import { GET as logoutCompleteGET } from "../logout-complete/route"

jest.mock("@/auth", () => ({ auth: jest.fn(), signOut: jest.fn() }))

const mockSignOut = signOut as unknown as jest.Mock

const FLAG = "VENDOR_LOGOUT_COMPLETE_ENABLED"

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

describe("/api/vendor-auth/logout-complete — FLAG ON: the server-side clear on the return leg (FE-1)", () => {
  it("calls the SERVER signOut with redirect:false and redirects to /auth/signin", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET()

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
      const res = await logoutCompleteGET()
      const cookies = setCookies(res)

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
      const res = await logoutCompleteGET()
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
      expect(setCookies(res)).toHaveLength(0)
    })
  })

  it("a THROWING signOut never strands the vendor: still 302 to /auth/signin", async () => {
    // The whole point of E-5 is that the worst case is today's defect, never
    // a vendor parked on an error page with SSO alive.
    mockSignOut.mockRejectedValue(new Error("auth misconfigured"))
    const quiet = jest.spyOn(console, "error").mockImplementation(() => {})
    try {
      await withEnv({ [FLAG]: "true" }, async () => {
        const res = await logoutCompleteGET()
        expect(res.status).toBe(302)
        expect(res.headers.get("location")).toBe("/auth/signin")
      })
    } finally {
      quiet.mockRestore()
    }
  })

  it("is never cacheable (the response mutates the session)", async () => {
    await withEnv({ [FLAG]: "true" }, async () => {
      const res = await logoutCompleteGET()
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
      const res = await logoutCompleteGET()
      expect(mockSignOut).not.toHaveBeenCalled()
      expect(res.status).toBe(302)
      expect(res.headers.get("location")).toBe("/auth/signin")
    })
  })

  it("CONTROL: '1' is an accepted ON spelling, so the OFF arms above are not passing vacuously", async () => {
    await withEnv({ [FLAG]: "1" }, async () => {
      await logoutCompleteGET()
      expect(mockSignOut).toHaveBeenCalledTimes(1)
    })
  })
})
