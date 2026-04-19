// Snapshot regression guard for Next.js security headers (SEC-02).
//
// This test freezes the exact set of response headers + CSP directive string
// emitted by next.config.mjs. Any drift (added directive, removed header,
// changed allowlist) fails CI (wired by Task 12-02-06) until the snapshot
// is regenerated via `npm test -- -u header-snapshot`, forcing a deliberate
// developer acknowledgement.
//
// Env vars are fixed to deterministic values so the snapshot is reproducible
// on every machine. NODE_ENV=production captures the production CSP form
// (no 'unsafe-eval' in script-src); the dev form is less rigid and not
// worth snapshotting.

describe('Security headers snapshot (regression guard)', () => {
  const ORIGINAL_ENV = { ...process.env }

  beforeEach(() => {
    jest.resetModules()
    process.env = {
      ...ORIGINAL_ENV,
      NODE_ENV: 'production',
      NEXT_PUBLIC_KEYCLOAK_URL: 'https://keycloak.snapshot.local',
      NEXT_PUBLIC_API_URL: 'https://api.snapshot.local',
    }
  })

  afterAll(() => {
    process.env = ORIGINAL_ENV
  })

  it('matches snapshot', async () => {
    const mod = (await import('../next.config.mjs')) as { default: { headers: () => Promise<Array<{ source: string; headers: Array<{ key: string; value: string }> }>> } }
    const routes = await mod.default.headers()

    // Normalize: sort headers by key for deterministic output
    const snapshot = routes.map(
      (r: { source: string; headers: Array<{ key: string; value: string }> }) => ({
        source: r.source,
        headers: r.headers
          .slice()
          .sort((a, b) => a.key.localeCompare(b.key))
          .map((h) => ({ key: h.key, value: h.value })),
      })
    )

    expect(snapshot).toMatchSnapshot()
  })
})
