// Snapshot regression guard for the security headers (SEC-02 / #89).
//
// Freezes (1) the static response headers emitted by next.config.mjs and
// (2) the CSP directive string produced by buildCsp() for a fixed nonce. Any
// drift (added directive, removed header, changed allowlist, reintroduced
// 'unsafe-inline') fails CI until the snapshot is regenerated via
// `npm test -- -u header-snapshot`, forcing a deliberate acknowledgement.
//
// The nonce is fixed here so the CSP string is deterministic; in production it
// is random per request (middleware.ts). NODE_ENV=production captures the
// production script-src form (no 'unsafe-eval').

import { buildCsp } from "../lib/security-headers"

describe("Security headers snapshot (regression guard)", () => {
  const ORIGINAL_ENV = { ...process.env }

  beforeEach(() => {
    jest.resetModules()
    process.env = {
      ...ORIGINAL_ENV,
      NODE_ENV: "production",
      NEXT_PUBLIC_KEYCLOAK_URL: "https://keycloak.snapshot.local",
      NEXT_PUBLIC_API_URL: "https://api.snapshot.local",
    }
  })

  afterAll(() => {
    process.env = ORIGINAL_ENV
  })

  it("static headers match snapshot", async () => {
    const mod: any = await import("../next.config.mjs")
    const routes = await mod.default.headers()

    const snapshot = routes.map(
      (r: { source: string; headers: Array<{ key: string; value: string }> }) => ({
        source: r.source,
        headers: r.headers
          .slice()
          .sort((a, b) => a.key.localeCompare(b.key))
          .map((h) => ({ key: h.key, value: h.value })),
      }),
    )

    expect(snapshot).toMatchSnapshot()
  })

  // 37-09 (T-37-24): the staff-invitation accept page sends no Referer at all. Two
  // properties the snapshot alone cannot state: Next applies the LAST entry that sets
  // a key for a path, so the /invite entry must come after the '/:path*' default;
  // and its source must actually match the page's path, '/invite' itself (the token
  // is in the fragment, so the path never grows a segment).
  it("/invite gets Referrer-Policy no-referrer, after (so over) the default", async () => {
    const mod: any = await import("../next.config.mjs")
    const routes: Array<{ source: string; headers: Array<{ key: string; value: string }> }> =
      await mod.default.headers()
    const referrer = (r: (typeof routes)[number]) =>
      r.headers.find((h) => h.key === "Referrer-Policy")?.value

    const defaultIdx = routes.findIndex((r) => r.source === "/:path*")
    const inviteIdx = routes.findIndex((r) => referrer(r) === "no-referrer")
    expect(defaultIdx).toBeGreaterThanOrEqual(0)
    expect(inviteIdx).toBeGreaterThan(defaultIdx)

    // Next's own matcher (the compiled path-to-regexp it builds header rules with).
    const { pathToRegexp } = jest.requireActual("next/dist/compiled/path-to-regexp") as {
      pathToRegexp: (source: string, keys: unknown[], options: object) => RegExp
    }
    const matches = (path: string) => pathToRegexp(routes[inviteIdx].source, [], {}).test(path)
    expect(matches("/invite")).toBe(true)
    // Control: it is scoped to the invite page, not a prefix of other routes.
    expect(matches("/invitex")).toBe(false)
    expect(matches("/dashboard/staff")).toBe(false)

    // The last entry setting Referrer-Policy for /invite is the no-referrer one.
    const effective = routes
      .filter((r) => pathToRegexp(r.source, [], {}).test("/invite") && referrer(r))
      .map(referrer)
      .pop()
    expect(effective).toBe("no-referrer")
  })

  it("CSP directive string matches snapshot (fixed nonce)", () => {
    const csp = buildCsp({
      nonce: "SNAPSHOT_NONCE",
      isDev: false,
      keycloakOrigin: "https://keycloak.snapshot.local",
      apiOrigin: "https://api.snapshot.local",
    })
    expect(csp).toMatchSnapshot()
  })
})
