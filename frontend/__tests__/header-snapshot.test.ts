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
