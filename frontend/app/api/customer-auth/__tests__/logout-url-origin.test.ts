/**
 * @jest-environment node
 *
 * Issue #504 — customer sign-out landed on a Keycloak "Invalid redirect uri"
 * error page and left the IdP session alive.
 *
 * These tests pin the two things that were wrong and the one thing that was
 * already RIGHT and must stay so.
 *
 * WHY `nextUrl.origin` IS SIMULATED RATHER THAN MOCKED AWAY. The defect is not
 * "the code read a property"; it is "the property carries the BIND address in a
 * container". So every test here hands the route a request whose origin is
 * `http://0.0.0.0:3000` — exactly what was measured against the live compose
 * stack, where `HOSTNAME: 0.0.0.0` is set and the Host header does not override
 * it. A test built on a `http://localhost` request would pass on the broken tree
 * and prove nothing.
 */

import { NextRequest } from "next/server"
import { GET as logoutUrlGET } from "../logout-url/route"

const BIND_ORIGIN = "http://0.0.0.0:3000"
const KC = "http://localhost:8085/realms/jtoye-customers"

/**
 * A request as it arrives INSIDE the container: the URL Next.js builds from the
 * bind address, with the browser's real Host header present and ignored.
 */
function containerRequest(path: string, cookies: Record<string, string> = {}): NextRequest {
  const req = new NextRequest(`${BIND_ORIGIN}${path}`, {
    headers: { host: "localhost:3000" },
  })
  for (const [k, v] of Object.entries(cookies)) {
    req.cookies.set(k, v)
  }
  return req
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

describe("/api/customer-auth/logout-url — post_logout_redirect_uri origin (#504)", () => {
  it("NEVER emits the container bind address, even though the request carries it", async () => {
    await withEnv({ NEXTAUTH_URL: "http://localhost:3000", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
          "jtoye-customer-id": "id-token-value",
        })
      )
      const { url } = await res.json()
      // The whole bug in one assertion: this string is what Keycloak refused.
      expect(url).not.toContain("0.0.0.0")
      const plr = new URL(url).searchParams.get("post_logout_redirect_uri")
      expect(plr).toBe("http://localhost:3000/shop")
    })
  })

  it("uses the INJECTED origin, not the request's — proven by making them differ", async () => {
    // The staging value. If the route were still reading the request, this would
    // come back as the bind address and the assertion would fail.
    await withEnv(
      { NEXTAUTH_URL: "https://app-staging.olajay.co.uk", APP_PUBLIC_ORIGIN: undefined },
      async () => {
        const res = await logoutUrlGET(
          containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
            "jtoye-customer-id": "id-token-value",
          })
        )
        const { url } = await res.json()
        expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
          "https://app-staging.olajay.co.uk/shop"
        )
      }
    )
  })

  it("lets APP_PUBLIC_ORIGIN override NEXTAUTH_URL", async () => {
    await withEnv(
      { NEXTAUTH_URL: "https://nextauth.example.com", APP_PUBLIC_ORIGIN: "https://shop.example.com" },
      async () => {
        const res = await logoutUrlGET(
          containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
            "jtoye-customer-id": "id-token-value",
          })
        )
        const { url } = await res.json()
        expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
          "https://shop.example.com/shop"
        )
      }
    )
  })

  it("strips a trailing slash / path so the redirect can never become //shop", async () => {
    await withEnv({ NEXTAUTH_URL: "https://app.example.com/", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
          "jtoye-customer-id": "id-token-value",
        })
      )
      const { url } = await res.json()
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
        "https://app.example.com/shop"
      )
    })
  })

  it("with NO trustworthy origin, omits post_logout_redirect_uri rather than sending a bad one", async () => {
    // Measured against the live realm: id_token_hint with no redirect uri
    // TERMINATES the session and renders "You are logged out". An unregistered
    // redirect uri errors WITHOUT terminating. Degrading the return journey is
    // acceptable; degrading the sign-out is the defect.
    await withEnv({ NEXTAUTH_URL: undefined, APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
          "jtoye-customer-id": "id-token-value",
        })
      )
      const { url } = await res.json()
      expect(url).toContain(`${KC}/protocol/openid-connect/logout`)
      expect(url).toContain("id_token_hint=id-token-value")
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBeNull()
      expect(url).not.toContain("0.0.0.0")
    })
  })

  it("with no session and no trustworthy origin, bounces to a RELATIVE path", async () => {
    await withEnv({ NEXTAUTH_URL: undefined, APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(containerRequest("/api/customer-auth/logout-url?redirect=/shop"))
      const { url } = await res.json()
      expect(url).toBe("/shop")
      expect(url).not.toContain("0.0.0.0")
    })
  })
})

describe("/api/customer-auth/logout-url — the same-origin restriction still holds (regression guard)", () => {
  // This behaviour is CORRECT before the fix. It is asserted because the fix
  // touches the very line that builds the redirect, and a fix that accepted an
  // arbitrary target would close #504 by opening an open-redirect.
  const hostile: Array<[string, string]> = [
    ["protocol-relative", "//evil.example.com/steal"],
    ["absolute http", "http://evil.example.com/steal"],
    ["absolute https", "https://evil.example.com/steal"],
    ["backslash trick", "/\\evil.example.com/steal"],
    ["scheme-ish", "javascript:alert(1)"],
    ["empty", ""],
    // PR #726 follow-up to low (a): the vendor sibling lost its private `sanitizeRedirect`
    // for the shared `safeReturnTo`; this route was the LAST copy. These are the cases the
    // local copy ACCEPTED — an interior backslash, which some browsers normalise to a
    // protocol-relative URL, and whitespace-padded variants of the hostile forms above that
    // a `startsWith("/")` check never sees. One sanitiser across both realms now.
    ["interior backslash", "/shop\\@evil.example.com"],
    ["double-backslash host", "\\\\evil.example.com"],
    ["padded protocol-relative", "  //evil.example.com/steal"],
    ["padded absolute https", " https://evil.example.com/steal"],
    ["padded javascript:", " javascript:alert(1)"],
  ]

  it.each(hostile)("rejects a %s redirect and falls back to /shop", async (_label, raw) => {
    await withEnv({ NEXTAUTH_URL: "http://localhost:3000", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest(
          `/api/customer-auth/logout-url?redirect=${encodeURIComponent(raw)}`,
          { "jtoye-customer-id": "id-token-value" }
        )
      )
      const { url } = await res.json()
      const plr = new URL(url).searchParams.get("post_logout_redirect_uri")
      expect(plr).toBe("http://localhost:3000/shop")
      expect(plr).not.toContain("evil.example.com")
    })
  })

  it("still honours a legitimate relative redirect", async () => {
    await withEnv({ NEXTAUTH_URL: "http://localhost:3000", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=%2Fshop%2Fpeckham-jollof-co", {
          "jtoye-customer-id": "id-token-value",
        })
      )
      const { url } = await res.json()
      expect(new URL(url).searchParams.get("post_logout_redirect_uri")).toBe(
        "http://localhost:3000/shop/peckham-jollof-co"
      )
    })
  })
})

/**
 * WR-04 (code review, 2026-08-31) — the same gap, on the sibling that had it
 * first. Fixing one route alone is how the pair starts to diverge, so both
 * carry the header and both assert it.
 */
describe("/api/customer-auth/logout-url — the id_token is never cacheable (WR-04)", () => {
  it("sends no-store and Vary: Cookie on the branch that carries the token", async () => {
    await withEnv({ NEXTAUTH_URL: "http://localhost:3000", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=/shop", {
          "jtoye-customer-id": "id-token-value",
        })
      )
      const { url } = await res.clone().json()
      expect(url).toContain("id_token_hint=id-token-value")

      expect(res.headers.get("cache-control")).toBe("private, no-store, max-age=0")
      expect(res.headers.get("vary")).toBe("Cookie")
    })
  })

  it("sends the same headers on the no-session branch", async () => {
    await withEnv({ NEXTAUTH_URL: "http://localhost:3000", APP_PUBLIC_ORIGIN: undefined }, async () => {
      const res = await logoutUrlGET(
        containerRequest("/api/customer-auth/logout-url?redirect=/shop")
      )
      expect(res.headers.get("cache-control")).toBe("private, no-store, max-age=0")
      expect(res.headers.get("vary")).toBe("Cookie")
    })
  })
})
