/**
 * @jest-environment node
 *
 * R-01 (2026-08-31 customer-surface audit, **P0**) — vendor federated logout.
 *
 * The defect this route exists to close: "Sign Out" dropped the NextAuth cookie
 * and left all six Keycloak SSO cookies alive, so the next click on "Sign in
 * with Keycloak" silently re-entered the dashboard as the departed user.
 *
 * WHAT THESE TESTS CAN AND CANNOT SAY. They assert the URL this route COMPOSES.
 * They cannot assert that a real Keycloak session is terminated — that needs a
 * rebuilt stack and a cookie-jar probe, and it is the orchestrator's. A green
 * run here is "the URL is right", never "the vendor is signed out".
 *
 * WHY `nextUrl.origin` IS SIMULATED RATHER THAN MOCKED AWAY — the same reason
 * the customer sibling gives (`app/api/customer-auth/__tests__/logout-url-origin.test.ts`):
 * the hazard is that the property carries the container BIND address, so every
 * request built here has origin `http://0.0.0.0:3000`, exactly what was measured
 * on the live compose stack.
 */

import { NextRequest } from "next/server"
import { auth } from "@/auth"
import { GET as vendorLogoutUrlGET } from "../logout-url/route"

jest.mock("@/auth", () => ({ auth: jest.fn() }))

const mockAuth = auth as unknown as jest.Mock

const BIND_ORIGIN = "http://0.0.0.0:3000"
const PUBLIC_ISSUER = "http://localhost:8085/realms/jtoye-dev"
const INTERNAL_ISSUER = "http://keycloak:8080/realms/jtoye-dev"

function containerRequest(path: string): NextRequest {
  return new NextRequest(`${BIND_ORIGIN}${path}`, {
    headers: { host: "localhost:3000" },
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

/** The realm config as compose supplies it: public AND internal both present. */
const SPLIT_HORIZON = {
  NEXT_PUBLIC_KEYCLOAK_URL: undefined,
  KEYCLOAK_ISSUER: PUBLIC_ISSUER,
  KEYCLOAK_ISSUER_INTERNAL: INTERNAL_ISSUER,
  NEXTAUTH_URL: "http://localhost:3000",
  APP_PUBLIC_ORIGIN: undefined,
}

beforeEach(() => {
  mockAuth.mockReset()
  mockAuth.mockResolvedValue({ idToken: "ID", user: { email: "vendor@example.com" } })
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
        "http://localhost:3000/auth/signin"
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

      expect(url).toBe("http://localhost:3000/auth/signin")
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

      expect(plr).toBe("http://localhost:3000/auth/signin")
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
        "http://localhost:3000/dashboard/orders"
      )
    })
  })
})
