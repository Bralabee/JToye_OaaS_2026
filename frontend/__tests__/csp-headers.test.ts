/**
 * Jest unit tests for the security response headers (SEC-02 / ASVS 14.4.x).
 *
 * Since issue #89 (P1-7) the Content-Security-Policy is built PER-REQUEST in
 * middleware.ts via buildCsp() (lib/security-headers.ts) so it can carry a
 * nonce and drop `script-src 'unsafe-inline'`. This suite therefore splits into:
 *   1. buildCsp() — the CSP directive assertions (nonce, strict-dynamic, no
 *      unsafe-inline in script-src, Stripe/Keycloak allowlists).
 *   2. next.config.mjs headers() — the remaining STATIC headers, and a guard
 *      that CSP is no longer emitted statically (it must come from middleware).
 *
 * This is the CI gate wired in ci-cd.yaml — any edit that reintroduces
 * 'unsafe-inline' to script-src, drops Stripe from an allowlist, or strips the
 * nosniff header MUST fail this suite.
 */

import { buildCsp } from "../lib/security-headers"

/**
 * Parse a CSP header string into a map of directive name -> value tokens.
 */
function parseCsp(csp: string): Record<string, string> {
  const map: Record<string, string> = {}
  for (const raw of csp.split("; ")) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    const [name, ...rest] = trimmed.split(" ")
    map[name] = rest.join(" ")
  }
  return map
}

describe("buildCsp() — Content-Security-Policy directives", () => {
  const base = {
    nonce: "TEST_NONCE_abc123",
    isDev: false,
    keycloakOrigin: "https://keycloak.example.test",
    apiOrigin: "https://api.example.test",
  }

  // #382 split staff (jtoye-dev) and customer (jtoye-customers) into separate
  // realms. `keycloakOrigin` carries a realm PATH, not a bare origin, so the
  // customer realm is a genuinely different CSP source and is NOT covered by
  // the staff one. Omitting it does not fail loudly: registration creates the
  // Keycloak user, then the browser blocks the token exchange and the shopper
  // sees "Authentication failed. Please try again." Measured on the live stack
  // 2026-08-01 before this was wired.
  describe("customer realm is its own connect-src source (#382 realm split)", () => {
    const staff = "http://kc.example.test/realms/jtoye-dev"
    const customer = "http://kc.example.test/realms/jtoye-customers"

    it("allows BOTH realms in connect-src when both are configured", () => {
      const directives = parseCsp(
        buildCsp({ ...base, keycloakOrigin: staff, customerKeycloakOrigin: customer })
      )
      expect(directives["connect-src"]).toContain(staff)
      expect(directives["connect-src"]).toContain(customer)
    })

    // The assertion that actually makes sign-in work. A CSP source with a path
    // matches EXACTLY unless it ends in "/", so the bare realm URL never covers
    // `/protocol/openid-connect/token`. Listing the realm and still being blocked
    // is precisely what shipped; measured in a real browser on 2026-08-01.
    it("emits the trailing-slash SUBTREE form, not just the bare realm path", () => {
      const connectSrc = parseCsp(
        buildCsp({ ...base, keycloakOrigin: staff, customerKeycloakOrigin: customer })
      )["connect-src"]
      const sources = connectSrc.split(" ")
      for (const realm of [staff, customer]) {
        expect(sources).toContain(`${realm}/`)
      }
    })

    it("covers the token endpoint the browser actually calls", () => {
      const sources = parseCsp(
        buildCsp({ ...base, keycloakOrigin: staff, customerKeycloakOrigin: customer })
      )["connect-src"].split(" ")
      const tokenUrl = `${customer}/protocol/openid-connect/token`
      // CSP path semantics: a source covers the URL when it is the exact path, or
      // a trailing-slash prefix of it.
      const covered = sources.some(
        (src) => src === tokenUrl || (src.endsWith("/") && tokenUrl.startsWith(src))
      )
      expect(covered).toBe(true)
    })

    // The falsifying arm: this is the exact shipped state that broke customer
    // sign-in, so the test must FAIL if the customer source is ever dropped.
    it("does NOT smuggle the customer realm in via the staff realm", () => {
      const directives = parseCsp(buildCsp({ ...base, keycloakOrigin: staff }))
      expect(directives["connect-src"]).toContain(staff)
      expect(directives["connect-src"]).not.toContain(customer)
    })

    it("emits neither a duplicate nor a blank when the two are equal or absent", () => {
      const same = parseCsp(
        buildCsp({ ...base, keycloakOrigin: staff, customerKeycloakOrigin: staff })
      )["connect-src"]
      expect(same.split(" ").filter((s) => s === staff)).toHaveLength(1)

      const neither = parseCsp(
        buildCsp({ ...base, keycloakOrigin: "", customerKeycloakOrigin: "" })
      )["connect-src"]
      expect(neither).not.toMatch(/\s{2,}|\s$/)
    })
  })

  it("has the baseline directives", () => {
    const csp = buildCsp(base)
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain("frame-ancestors 'none'")
    expect(csp).toContain("base-uri 'self'")
    expect(csp).toContain("object-src 'none'")
  })

  it("script-src is nonce + strict-dynamic and has NO 'unsafe-inline'", () => {
    const directives = parseCsp(buildCsp(base))
    const scriptSrc = directives["script-src"]
    expect(scriptSrc).toContain(`'nonce-${base.nonce}'`)
    expect(scriptSrc).toContain("'strict-dynamic'")
    expect(scriptSrc).not.toContain("'unsafe-inline'")
  })

  it("does not add 'unsafe-eval' in production, but does in dev", () => {
    expect(parseCsp(buildCsp(base))["script-src"]).not.toContain("'unsafe-eval'")
    expect(parseCsp(buildCsp({ ...base, isDev: true }))["script-src"]).toContain("'unsafe-eval'")
  })

  it("allowlists Stripe in the correct directives (script-src, frame-src, connect-src)", () => {
    const directives = parseCsp(buildCsp(base))
    expect(directives["script-src"]).toContain("https://js.stripe.com")
    expect(directives["frame-src"]).toContain("https://js.stripe.com")
    expect(directives["frame-src"]).toContain("https://hooks.stripe.com")
    expect(directives["connect-src"]).toContain("https://api.stripe.com")
  })

  it("form-action includes the configured Keycloak origin", () => {
    expect(buildCsp(base)).toContain("form-action 'self' https://keycloak.example.test")
  })

  it("connect-src derives the wss:// origin from the API origin", () => {
    const directives = parseCsp(buildCsp(base))
    expect(directives["connect-src"]).toContain("https://api.example.test")
    expect(directives["connect-src"]).toContain("wss://api.example.test")
  })

  it("emits upgrade-insecure-requests only when explicitly enabled (and not in dev)", () => {
    expect(buildCsp(base)).not.toContain("upgrade-insecure-requests")
    expect(buildCsp({ ...base, upgradeInsecure: true })).toContain("upgrade-insecure-requests")
    expect(buildCsp({ ...base, upgradeInsecure: true, isDev: true })).not.toContain(
      "upgrade-insecure-requests",
    )
  })
})

describe("next.config.mjs static security headers", () => {
  const ORIGINAL_ENV = { ...process.env }

  beforeEach(() => {
    jest.resetModules()
    process.env = { ...ORIGINAL_ENV }
  })

  afterAll(() => {
    process.env = ORIGINAL_ENV
  })

  async function loadHeaders() {
    const mod: any = await import("../next.config.mjs")
    return mod.default.headers()
  }

  it("returns a single route matching all paths", async () => {
    const routes = await loadHeaders()
    expect(routes).toHaveLength(1)
    expect(routes[0].source).toBe("/:path*")
  })

  it("emits X-Content-Type-Options nosniff, Referrer-Policy, and Permissions-Policy", async () => {
    const routes = await loadHeaders()
    const headers = routes[0].headers as Array<{ key: string; value: string }>
    expect(headers.find((h) => h.key === "X-Content-Type-Options")?.value).toBe("nosniff")
    expect(headers.find((h) => h.key === "Referrer-Policy")).toBeDefined()
    expect(headers.find((h) => h.key === "Permissions-Policy")).toBeDefined()
  })

  it("does NOT emit CSP statically — it must be per-request from middleware", async () => {
    const routes = await loadHeaders()
    const headers = routes[0].headers as Array<{ key: string }>
    expect(
      headers.find(
        (h) =>
          h.key === "Content-Security-Policy" ||
          h.key === "Content-Security-Policy-Report-Only",
      ),
    ).toBeUndefined()
  })
})
