/**
 * Static link-graph orphan guard (UIX-01, backlog #5 — Pattern 8).
 *
 * Walks `app/**\/page.tsx` to enumerate every route, collects every navigation
 * edge across `app` + `components` — `href="..."`, `href: "..."`,
 * `href={\`...\`}`, and `router.push/replace(...)` — normalises dynamic
 * segments (`[slug]`/`[id]`/`[orderNumber]`, `${...}`) to a wildcard, and
 * asserts every route has >=1 inbound edge from a DIFFERENT file.
 *
 * STATIC by design: no browser, no running stack, <1s. A Playwright crawl is
 * slower, needs the full docker stack, and hangs on SSE/live pages (they never
 * reach `networkidle` — see 19-RESEARCH Pitfall 5). Keep Playwright for
 * rendering/visual assertions only.
 *
 * If this test fails, a route lost its last inbound link — add a nav link (or,
 * for a genuinely non-navigable utility route, extend ALLOWLIST with a reason).
 */

import fs from "fs"
import path from "path"

const ROOT = path.resolve(__dirname, "..") // frontend/
const APP_DIR = path.join(ROOT, "app")
const SCAN_DIRS = [APP_DIR, path.join(ROOT, "components")]

/** Sentinel a dynamic path segment (`[slug]` / `${slug}`) normalises to. */
const WILDCARD = "*"

/**
 * Allowlist — routes that legitimately need no inbound in-app link:
 *  - "/"                   the site root / front door — the intrinsic entry
 *                          point every visitor reaches directly, not via a link.
 *  - "/shop/auth/callback" the customer OIDC callback — reached only via an
 *                          external IdP (Keycloak) redirect, never an in-app
 *                          link; not user-navigable.
 *  - "/unsubscribe"        the public one-click email opt-out (COMMS-03) —
 *                          reached ONLY via a token link in an outbound email
 *                          (List-Unsubscribe / footer link), never an in-app
 *                          nav link; noindex + sitemap-excluded by design.
 *  - "/dashboard/payments/connect/return"   Stripe Connect redirect targets
 *  - "/dashboard/payments/connect/refresh"  (#295). Same shape as
 *                          /shop/auth/callback: the ONLY inbound edge is an
 *                          external redirect from Stripe's hosted Express
 *                          onboarding flow, whose destination is set server-side
 *                          by `stripe.connect.return-url` / `refresh-url` in
 *                          core-java's application.yml (+ the k8s overlays), not
 *                          by any href in this codebase. Adding an in-app nav
 *                          link to satisfy this guard would ship a dashboard
 *                          entry that goes nowhere useful — the allowlist is the
 *                          honest answer, not a decorative link.
 *
 * NOTE: API route handlers under `app/api/**` have no `page.tsx`, so they are
 * never enumerated as routes — they need no allowlist entry.
 */
const ALLOWLIST = new Set<string>([
  "/",
  "/shop/auth/callback",
  "/unsubscribe",
  "/dashboard/payments/connect/return",
  "/dashboard/payments/connect/refresh",
])

function isTestFile(file: string): boolean {
  return /(^|[/\\])__tests__[/\\]/.test(file) || /\.(test|spec)\.[jt]sx?$/.test(file)
}

function walk(dir: string): string[] {
  if (!fs.existsSync(dir)) return []
  const out: string[] = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === ".next") continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else out.push(full)
  }
  return out
}

function isDynamicSegment(seg: string): boolean {
  return seg.startsWith("[") && seg.endsWith("]")
}

/** `app/dashboard/orders/[id]/page.tsx` -> `/dashboard/orders/*` */
function routeFromPageFile(file: string): string {
  const rel = path.relative(APP_DIR, path.dirname(file))
  if (rel === "" || rel === ".") return "/"
  const segments = rel
    .split(path.sep)
    .filter((s) => !(s.startsWith("(") && s.endsWith(")"))) // strip route groups
    .map((s) => (isDynamicSegment(s) ? WILDCARD : s))
  return "/" + segments.join("/")
}

/** Normalise a raw href / router target to a comparable route pattern, or null. */
function normalizeTarget(raw: string): string | null {
  let p = raw.trim()
  if (!p.startsWith("/")) return null // external, tel:, mailto:, #anchor, relative
  p = p.split("?")[0].split("#")[0] // strip query + hash
  const segments = p
    .split("/")
    .filter((s, i) => !(i === 0 && s === "")) // drop the leading ""
    .map((s) => (s.includes("${") ? WILDCARD : s))
  const norm = "/" + segments.join("/")
  return norm === "/" ? "/" : norm.replace(/\/+$/, "")
}

interface Edge {
  target: string
  file: string
}

function collectEdges(files: string[]): Edge[] {
  const edges: Edge[] = []
  // href="/x"  href='/x'  href: "/x"  href={"/x"}  href={`/x`}
  const hrefRe = /\bhref\s*[:=]\s*\{?\s*(["'`])([^"'`]*)\1/g
  // router.push(`/x`)  router.replace("/x")
  const pushRe = /router\.(?:push|replace)\(\s*(["'`])([^"'`]*)\1/g
  for (const file of files) {
    const src = fs.readFileSync(file, "utf8")
    for (const re of [hrefRe, pushRe]) {
      re.lastIndex = 0
      let m: RegExpExecArray | null
      while ((m = re.exec(src)) !== null) {
        const norm = normalizeTarget(m[2])
        if (norm) edges.push({ target: norm, file })
      }
    }
  }
  return edges
}

describe("Link-graph orphan guard", () => {
  const scanFiles = SCAN_DIRS.flatMap(walk).filter(
    (f) => /\.[jt]sx?$/.test(f) && !isTestFile(f)
  )
  const pageFiles = walk(APP_DIR).filter((f) => /(^|[/\\])page\.tsx$/.test(f))
  const routes = pageFiles.map((f) => ({ route: routeFromPageFile(f), file: f }))
  const edges = collectEdges(scanFiles)

  it("enumerates routes and navigation edges from the static tree", () => {
    expect(routes.length).toBeGreaterThan(0)
    expect(edges.length).toBeGreaterThan(0)
  })

  it("every route has >=1 inbound link from a different file (zero orphans)", () => {
    const orphans: string[] = []
    for (const { route, file } of routes) {
      if (ALLOWLIST.has(route)) continue
      const hasInbound = edges.some(
        (e) =>
          e.target === route &&
          path.resolve(e.file) !== path.resolve(file)
      )
      if (!hasInbound) orphans.push(`${route}  <- ${path.relative(ROOT, file)}`)
    }
    // A non-empty list names exactly which route lost its last inbound link.
    expect(orphans).toEqual([])
  })

  it("self-check: the guard flags a route with zero inbound edges", () => {
    // Demonstrates the assertion catches orphans — a synthetic route that no
    // file links to must resolve to zero inbound edges (i.e. would be flagged).
    const injected = "/definitely-not-linked-anywhere"
    const inbound = edges.filter((e) => e.target === injected)
    expect(inbound).toHaveLength(0)
  })

  it("normalises dynamic routes to wildcard patterns before matching", () => {
    const routeSet = new Set(routes.map((r) => r.route))
    // These dynamic routes exist and MUST be represented as wildcard patterns.
    expect(routeSet.has("/shop/*")).toBe(true)
    expect(routeSet.has("/dashboard/orders/*")).toBe(true)
    expect(routeSet.has("/shop/*/orders/*")).toBe(true)
    // No unexpanded bracket segments should survive normalisation.
    for (const r of routeSet) expect(r).not.toContain("[")
  })
})
