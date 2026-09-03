/**
 * @jest-environment node
 *
 * R-01 (2026-08-31 customer-surface audit, **P0**) — vendor federated logout.
 *
 * The defect this route exists to close: "Sign Out" dropped the NextAuth cookie
 * and left all six Keycloak SSO cookies alive, so the next click on "Sign in
 * with Keycloak" silently re-entered the dashboard as the departed user.
 *
 * FE-1 (QA council 20260902-134741, **Critical**) — the OTHER half. The app
 * cookie itself survived, because `@auth/core` re-issues it on every session
 * GET and the client-side clear raced ~24 of those. The return leg of the
 * Keycloak round-trip is now `/api/vendor-auth/logout-complete`, which clears
 * the session SERVER-SIDE in the last response the browser processes. This
 * route composes that `post_logout_redirect_uri`, CONFIG-INJECTED from
 * `NEXTAUTH_URL` (never a literal), behind `VENDOR_LOGOUT_COMPLETE_ENABLED`
 * (E-5 fail-safe: off = today's `/auth/signin` landing, so a deployed realm
 * that has not registered the new URI can never strand a vendor on a Keycloak
 * 400 with SSO alive).
 *
 * WHAT THESE TESTS CAN AND CANNOT SAY. They assert the URL this route COMPOSES.
 * They cannot assert that a real Keycloak session is terminated — that needs a
 * rebuilt stack and a cookie-jar probe, and it is the orchestrator's. A green
 * run here is "the URL is right", never "the vendor is signed out".
 *
 * WHY THE PUBLIC ORIGIN IS `https://vendor.example.test` AND NOT THE COMPOSE ONE.
 * The derivation-from-config assertions below are only non-vacuous if the
 * configured origin is one no literal in the source could produce. With the
 * compose origin (loopback, port 3000) a route that had that origin typed into
 * it would pass every case here. The FE-1 change record asserts the compose
 * origin appears NOWHERE under app/api/vendor-auth — this file included.
 *
 * WHY `nextUrl.origin` IS SIMULATED RATHER THAN MOCKED AWAY — the same reason
 * the customer sibling gives (`app/api/customer-auth/__tests__/logout-url-origin.test.ts`):
 * the hazard is that the property carries the container BIND address, so every
 * request built here has origin `http://0.0.0.0:3000`, exactly what was measured
 * on the live compose stack.
 */

import { NextRequest } from "next/server"
import { auth, signOut } from "@/auth"
import { GET as vendorLogoutUrlGET } from "../logout-url/route"

jest.mock("@/auth", () => ({ auth: jest.fn(), signOut: jest.fn() }))

const mockAuth = auth as unknown as jest.Mock
const mockSignOut = signOut as unknown as jest.Mock

const BIND_ORIGIN = "http://0.0.0.0:3000"
const PUBLIC_ORIGIN = "https://vendor.example.test"
const PUBLIC_HOST = new URL(PUBLIC_ORIGIN).host
const PUBLIC_ISSUER = "http://localhost:8085/realms/jtoye-dev"
const INTERNAL_ISSUER = "http://keycloak:8080/realms/jtoye-dev"
const FLAG = "VENDOR_LOGOUT_COMPLETE_ENABLED"
const COMPLETE_PATH = "/api/vendor-auth/logout-complete"

function containerRequest(path: string): NextRequest {
  return new NextRequest(`${BIND_ORIGIN}${path}`, {
    headers: { host: PUBLIC_HOST },
  })
}

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

/**
 * The realm config as compose supplies it: public AND internal both present.
 * The FE-1 flag is explicitly ABSENT here, so every pre-existing case below
 * exercises today's landing (`/auth/signin`) and would go red if the default
 * ever flipped on silently — the E-5 design says off-unless-set.
 */
const SPLIT_HORIZON = {
  NEXT_PUBLIC_KEYCLOAK_URL: undefined,
  KEYCLOAK_ISSUER: PUBLIC_ISSUER,
  KEYCLOAK_ISSUER_INTERNAL: INTERNAL_ISSUER,
  NEXTAUTH_URL: PUBLIC_ORIGIN,
  APP_PUBLIC_ORIGIN: undefined,
  [FLAG]: undefined,
}

function setCookies(res: Response): string[] {
  const h = res.headers as Headers & { getSetCookie?: () => string[] }
  return typeof h.getSetCookie === "function" ? h.getSetCookie() : []
}

beforeEach(() => {
  mockAuth.mockReset()
  mockAuth.mockResolvedValue({ idToken: "ID", user: { email: "vendor@example.com" } })
  mockSignOut.mockReset()
  mockSignOut.mockResolvedValue({ redirect: "/", cookies: [] })
})

describe("/api/vendor-auth/logout-url — the end-session URL (R-01)", () => {
  it("names the PUBLIC issuer host and carries the id_token_hint", async () => {
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()

      const parsed = new URL(url)
      expect(parsed.host).toBe("localhost:8085")
      expect(parsed.pathname).toBe("/realms/jtoye-dev/protocol/openid-connect/logout")
      expect(parsed.searchParams.get("id_token_hint")).toBe("ID")
      expect(parsed.searchParams.get("post_logout_redirect_uri")).toBe(
        `${PUBLIC_ORIGIN}/auth/signin`
      )
      expect(url).not.toContain("0.0.0.0")
    })
  })

  it("SPLIT HORIZON: never emits the container-internal host, even when it is set", async () => {
    // The whole trap in one assertion. `KEYCLOAK_ISSUER_INTERNAL` is CORRECT for
    // auth.ts's server-to-server refresh and would be catastrophic here: the
    // BROWSER navigates to this URL and cannot resolve `keycloak:8080` at all,
    // so the sign-out would silently do nothing. Both variables are set, so a
    // route that reached for the wrong one would still produce a valid-looking
    // URL — which is exactly how this would ship unnoticed.
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()

      expect(url).not.toContain("keycloak:8080")
      expect(url).toContain("localhost:8085")
    })
  })

  it("prefers NEXT_PUBLIC_KEYCLOAK_URL over KEYCLOAK_ISSUER when both are set", async () => {
    await withEnv(
      { ...SPLIT_HORIZON, NEXT_PUBLIC_KEYCLOAK_URL: "https://id.example.com/realms/jtoye" },
      async () => {
        const res = await vendorLogoutUrlGET(
          containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
        )
        const { url } = await res.json()
        expect(new URL(url).host).toBe("id.example.com")
      }
    )
  })

  it("with NO trustworthy origin, keeps the id_token_hint and OMITS post_logout_redirect_uri", async () => {
    // Measured on the live realm (customer sibling, #504): `id_token_hint` with
    // no redirect uri TERMINATES the session; an unregistered redirect uri
    // errors WITHOUT terminating anything. Losing the return journey is
    // cosmetic; losing the sign-out is the security defect.
    await withEnv(
      { ...SPLIT_HORIZON, NEXTAUTH_URL: undefined, APP_PUBLIC_ORIGIN: undefined },
      async () => {
        const res = await vendorLogoutUrlGET(
          containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
        )
        const { url } = await res.json()

        expect(url).toContain(`${PUBLIC_ISSUER}/protocol/openid-connect/logout`)
        expect(url).toContain("id_token_hint=ID")
        expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBeNull()
        expect(url).not.toContain("0.0.0.0")
      }
    )
  })

  it("with no session, returns an app path with no id_token_hint and no Keycloak host", async () => {
    mockAuth.mockResolvedValue(null)
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()

      expect(url).toBe(`${PUBLIC_ORIGIN}/auth/signin`)
      expect(url).not.toContain("id_token_hint")
      expect(url).not.toContain("8085")
      expect(url).not.toContain("keycloak:8080")
    })
  })

  it("with a session that carries no idToken, degrades the same way", async () => {
    mockAuth.mockResolvedValue({ user: { email: "vendor@example.com" } })
    await withEnv(
      { ...SPLIT_HORIZON, NEXTAUTH_URL: undefined, APP_PUBLIC_ORIGIN: undefined },
      async () => {
        const res = await vendorLogoutUrlGET(
          containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
        )
        const { url } = await res.json()

        // No trustworthy origin either, so the RELATIVE path — resolved by the
        // browser against the page it is already on, which is this app.
        expect(url).toBe("/auth/signin")
        expect(url).not.toContain("id_token_hint")
      }
    )
  })
})

describe("/api/vendor-auth/logout-url — the redirect can never leave this origin (T-QF-01)", () => {
  const hostile: Array<[string, string]> = [
    ["protocol-relative", "//evil.example"],
    ["backslash trick", "/\\evil.example"],
    ["absolute http", "http://evil.example/steal"],
    ["absolute https", "https://evil.example/steal"],
    ["scheme-ish", "javascript:alert(1)"],
    ["empty", ""],
  ]

  it.each(hostile)("rejects a %s redirect and falls back to /auth/signin", async (_label, raw) => {
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest(
          `/api/vendor-auth/logout-url?redirect=${encodeURIComponent(raw)}`
        )
      )
      const { url } = await res.json()
      const plr = new URL(url).searchParams.get("post_logout_redirect_uri")

      expect(plr).toBe(`${PUBLIC_ORIGIN}/auth/signin`)
      expect(plr).not.toContain("evil.example")
      expect(url).not.toContain("evil.example")
    })
  })

  it("CONTROL: a legitimate relative redirect is still honoured", async () => {
    // Without this arm the sanitiser could be returning the fallback for
    // EVERYTHING and every hostile case above would still read green.
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=%2Fdashboard%2Forders")
      )
      const { url } = await res.json()
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
        `${PUBLIC_ORIGIN}/dashboard/orders`
      )
    })
  })
})

/**
 * WR-04 (code review, 2026-08-31) — the id_token must not be storable.
 *
 * The body embeds the caller's raw id_token. `export const dynamic =
 * "force-dynamic"` governs Next's RENDERING mode, not the emitted
 * `Cache-Control`, so without an explicit header correctness rested on a
 * framework default plus every intermediary inferring "do not share this" from
 * a URL that carries no user-varying component. A shared cache keyed on path
 * alone would serve user A's id_token to user B.
 *
 * BOTH branches are asserted. The degraded branch carries no token today, but
 * the two exits are one edit away from drifting, and the drift would be silent.
 */
describe("/api/vendor-auth/logout-url — the id_token is never cacheable (WR-04)", () => {
  it("sends no-store and Vary: Cookie on the branch that carries the token", async () => {
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      // Non-vacuity: this really is the branch with the token in it.
      const { url } = await res.clone().json()
      expect(url).toContain("id_token_hint=ID")

      expect(res.headers.get("cache-control")).toBe("private, no-store, max-age=0")
      expect(res.headers.get("vary")).toBe("Cookie")
    })
  })

  it("sends the same headers on the degraded branch, so the two cannot drift", async () => {
    mockAuth.mockResolvedValue(null)
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.clone().json()
      expect(url).not.toContain("id_token_hint")

      expect(res.headers.get("cache-control")).toBe("private, no-store, max-age=0")
      expect(res.headers.get("vary")).toBe("Cookie")
    })
  })
})

/**
 * FE-1 — the return leg is the server-side clear, and it is CONFIG-INJECTED.
 *
 * E-5 point 1: the `post_logout_redirect_uri` derives from `NEXTAUTH_URL`,
 * never a literal. E-5 point 3: it ships behind `VENDOR_LOGOUT_COMPLETE_ENABLED`,
 * off unless set, so the worst case is today's defect and never a Keycloak
 * "Invalid redirect uri" page — which, measured (#504), errors WITHOUT
 * terminating the SSO session.
 */
describe("/api/vendor-auth/logout-url — FE-1: the return leg is /api/vendor-auth/logout-complete, config-injected", () => {
  it("FLAG ON: post_logout_redirect_uri is <NEXTAUTH_URL>/api/vendor-auth/logout-complete, with NO query string", async () => {
    await withEnv({ ...SPLIT_HORIZON, [FLAG]: "true" }, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()
      const plr = new URL(url).searchParams.get("post_logout_redirect_uri")

      expect(plr).toBe(`${PUBLIC_ORIGIN}${COMPLETE_PATH}`)
      // No query, deliberately (plan R2): the realm check must be "does the
      // path match /*", never "does Keycloak's matcher tolerate a query".
      expect(new URL(plr as string).search).toBe("")
      // The id_token_hint half is untouched by the flag.
      expect(new URL(url).searchParams.get("id_token_hint")).toBe("ID")
    })
  })

  it("FLAG ON, DERIVATION ARM: a different NEXTAUTH_URL moves the return leg with it", async () => {
    // The fail direction of "config-injected": a literal origin anywhere in
    // the source would leave this URI unchanged when the config changes.
    const OTHER = "https://other-tenant.example.test"
    await withEnv({ ...SPLIT_HORIZON, [FLAG]: "true", NEXTAUTH_URL: OTHER }, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
        `${OTHER}${COMPLETE_PATH}`
      )
      expect(url).not.toContain(PUBLIC_HOST)
    })
  })

  it.each([
    ["unset", undefined],
    ["false", "false"],
  ])("FLAG %s: keeps today's /auth/signin return leg (E-5 fail-safe)", async (_label, value) => {
    await withEnv({ ...SPLIT_HORIZON, [FLAG]: value }, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
        `${PUBLIC_ORIGIN}/auth/signin`
      )
      expect(url).not.toContain(COMPLETE_PATH)
    })
  })

  it("FLAG ON: the degraded branch (no id token) is UNCHANGED — no Keycloak leg means no return leg", async () => {
    mockAuth.mockResolvedValue(null)
    await withEnv({ ...SPLIT_HORIZON, [FLAG]: "true" }, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      const { url } = await res.json()
      expect(url).toBe(`${PUBLIC_ORIGIN}/auth/signin`)
    })
  })

  it("FLAG ON, NO trustworthy origin: still omits post_logout_redirect_uri rather than guess one", async () => {
    // A configured flag must not be able to talk the route into emitting a
    // bind-address URI — the #504 defect — just to reach the new leg.
    await withEnv(
      { ...SPLIT_HORIZON, [FLAG]: "true", NEXTAUTH_URL: undefined, APP_PUBLIC_ORIGIN: undefined },
      async () => {
        const res = await vendorLogoutUrlGET(
          containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
        )
        const { url } = await res.json()
        expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBeNull()
        expect(url).not.toContain("0.0.0.0")
      }
    )
  })
})

/**
 * FE-1 (a) — the EARLY, best-effort leg. Not the fix (it answers inside the
 * very window the in-flight session GETs occupy), but harmless and strictly
 * additive: the clearing cookies the server signOut produces ride on this
 * response too. What matters is the failure shape: a signOut that throws must
 * never cost the vendor the end-session URL — that URL is the P0 path.
 */
describe("/api/vendor-auth/logout-url — FE-1 (a): best-effort early clear, never load-bearing", () => {
  it("copies the server signOut's clearing cookies onto the URL response", async () => {
    mockSignOut.mockResolvedValue({
      redirect: "/",
      cookies: [{ name: "authjs.session-token", value: "", options: { maxAge: 0, path: "/" } }],
    })
    await withEnv(SPLIT_HORIZON, async () => {
      const res = await vendorLogoutUrlGET(
        containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
      )
      expect(mockSignOut).toHaveBeenCalledWith({ redirect: false })
      const cookies = setCookies(res)
      expect(cookies.some((c) => c.startsWith("authjs.session-token=") && /Max-Age=0/i.test(c))).toBe(true)
      // And the URL is still there — this leg is additive.
      const { url } = await res.json()
      expect(url).toContain("id_token_hint=ID")
    })
  })

  it("a THROWING signOut does not cost the vendor the end-session URL", async () => {
    mockSignOut.mockRejectedValue(new Error("auth misconfigured"))
    const quiet = jest.spyOn(console, "error").mockImplementation(() => {})
    try {
      await withEnv(SPLIT_HORIZON, async () => {
        const res = await vendorLogoutUrlGET(
          containerRequest("/api/vendor-auth/logout-url?redirect=/auth/signin")
        )
        expect(res.status).toBe(200)
        const { url } = await res.json()
        expect(url).toContain(`${PUBLIC_ISSUER}/protocol/openid-connect/logout`)
        expect(url).toContain("id_token_hint=ID")
      })
    } finally {
      quiet.mockRestore()
    }
  })
})
