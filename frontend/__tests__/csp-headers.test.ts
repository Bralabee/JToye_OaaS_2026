/**
 * Jest unit test for Next.js security response headers (SEC-02 / ASVS 14.4.x).
 *
 * Imports next.config.mjs and parses the output of its async headers() function.
 * This test is the CI gate wired in ci-cd.yaml (Task 12-02-06) — any future edit
 * that drops Stripe from frame-src, strips the nosniff header, or breaks the
 * Keycloak form-action allowlist MUST fail this suite.
 *
 * Task 12-02-01 (RED): next.config.mjs does NOT yet declare headers(); all
 * tests are expected to fail until Task 12-02-02 adds the headers() function.
 */

/**
 * Parse a CSP header string ("default-src 'self'; script-src 'self' ...")
 * into a map of directive name -> space-separated value tokens.
 * Used to make per-directive assertions so a regression that drops Stripe
 * from frame-src (but keeps it in script-src) is caught.
 */
function parseCsp(csp: string): Record<string, string> {
  const map: Record<string, string> = {}
  for (const raw of csp.split('; ')) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    const [name, ...rest] = trimmed.split(' ')
    map[name] = rest.join(' ')
  }
  return map
}

describe('Next.js security response headers (next.config.mjs)', () => {
  const ORIGINAL_ENV = { ...process.env }

  beforeEach(() => {
    jest.resetModules()
    process.env = { ...ORIGINAL_ENV }
  })

  afterAll(() => {
    process.env = ORIGINAL_ENV
  })

  type HeaderRoute = {
    source: string
    headers: Array<{ key: string; value: string }>
  }

  async function loadHeaders(): Promise<HeaderRoute[]> {
    const mod = (await import('../next.config.mjs')) as {
      default: { headers: () => Promise<HeaderRoute[]> }
    }
    return mod.default.headers()
  }

  it('returns a single route matching all paths', async () => {
    const routes = await loadHeaders()
    expect(routes).toHaveLength(1)
    expect(routes[0].source).toBe('/:path*')
  })

  it('emits a Content-Security-Policy or Content-Security-Policy-Report-Only header', async () => {
    const routes = await loadHeaders()
    const cspHeader = routes[0].headers.find(
      (h: { key: string }) =>
        h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
    )
    expect(cspHeader).toBeDefined()
  })

  it('has baseline directives (default-src, frame-ancestors, base-uri, object-src)', async () => {
    const routes = await loadHeaders()
    const cspHeader = routes[0].headers.find(
      (h: { key: string }) =>
        h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
    )
    expect(cspHeader).toBeDefined()
    const value = cspHeader!.value as string
    expect(value).toContain("default-src 'self'")
    expect(value).toContain("frame-ancestors 'none'")
    expect(value).toContain("base-uri 'self'")
    expect(value).toContain("object-src 'none'")
  })

  it('allowlists Stripe in the correct directives (script-src, frame-src, connect-src) — per-directive', async () => {
    const routes = await loadHeaders()
    const cspHeader = routes[0].headers.find(
      (h: { key: string }) =>
        h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
    )
    expect(cspHeader).toBeDefined()
    const directives = parseCsp(cspHeader!.value as string)
    expect(directives['script-src']).toContain('https://js.stripe.com')
    expect(directives['frame-src']).toContain('https://js.stripe.com')
    expect(directives['frame-src']).toContain('https://hooks.stripe.com')
    expect(directives['connect-src']).toContain('https://api.stripe.com')
  })

  it('form-action includes the configured Keycloak origin', async () => {
    process.env.NEXT_PUBLIC_KEYCLOAK_URL = 'https://keycloak.example.test'
    const routes = await loadHeaders()
    const cspHeader = routes[0].headers.find(
      (h: { key: string }) =>
        h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
    )
    expect(cspHeader).toBeDefined()
    const value = cspHeader!.value as string
    expect(value).toContain("form-action 'self' https://keycloak.example.test")
  })

  it('connect-src derives wss:// origin from NEXT_PUBLIC_API_URL', async () => {
    process.env.NEXT_PUBLIC_API_URL = 'https://api.example.test'
    const routes = await loadHeaders()
    const cspHeader = routes[0].headers.find(
      (h: { key: string }) =>
        h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
    )
    expect(cspHeader).toBeDefined()
    const directives = parseCsp(cspHeader!.value as string)
    expect(directives['connect-src']).toContain('https://api.example.test')
    expect(directives['connect-src']).toContain('wss://api.example.test')
  })

  it('emits X-Content-Type-Options nosniff, Referrer-Policy, and Permissions-Policy headers', async () => {
    const routes = await loadHeaders()
    const headers = routes[0].headers as Array<{ key: string; value: string }>
    expect(headers.find((h) => h.key === 'X-Content-Type-Options')?.value).toBe('nosniff')
    expect(headers.find((h) => h.key === 'Referrer-Policy')).toBeDefined()
    expect(headers.find((h) => h.key === 'Permissions-Policy')).toBeDefined()
  })
})
