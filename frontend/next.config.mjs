/** @type {import('next').NextConfig} */

// Static security response headers (SEC-02 / ASVS 14.4.x).
//
// The Content-Security-Policy is NOT set here: it needs a fresh per-request
// nonce to drop `script-src 'unsafe-inline'` (issue #89 P1-7), so it is built
// in middleware.ts (see lib/security-headers.ts). Only the headers that are
// constant across requests live here.
const nextConfig = {
  output: 'standalone',
  // Build-time type-check scope: shipped code only (tests/e2e excluded).
  // The Docker build context is ./frontend, so a test importing a repo-root
  // file (docs/retention-manifest.json) can never resolve there; tests are
  // type-checked by the bare `tsc --noEmit` CI step instead. Rationale and
  // the 2026-08-28 measurement live in tsconfig.build.json's header.
  typescript: {
    tsconfigPath: 'tsconfig.build.json',
  },
  images: {
    // Staging/production images render through a plain <img> (no next/image import exists), so no production hostname is needed here; any future entry must be an exact hostname, never a wildcard (enforced by __tests__/csp-headers.test.ts).
    remotePatterns: [
      {
        protocol: 'http',
        hostname: 'localhost',
        port: '10000',
        pathname: '/devstoreaccount1/jtoye-images/**',
      },
    ],
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          // Content-Security-Policy is emitted per-request by middleware.ts.
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          // geolocation=(self) — ONE capability, scoped to the document's own
          // origin. Third-party frames still get nothing.
          //
          // It was `geolocation=()`, an EMPTY allowlist, which denies the API to
          // the page's OWN origin on every route, before any permission prompt,
          // with no console error worth reading. Measured live 2026-08-08 and
          // recorded as CA-2 in the phase control arms: it presented identically
          // to a user declining the prompt, so the located path was dead on
          // arrival and would have been misdiagnosed as a user denial.
          //
          // camera, microphone and browsing-topics stay fully denied. The E2E
          // assertion in storefront-ssr-seo.spec.ts asserts the PERMISSIVE string
          // is present rather than the restrictive one absent — an absence check
          // would also pass if the whole header were deleted, silently dropping
          // those three denials.
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(self), browsing-topics=()' },
        ],
      },
      // The staff-invitation accept page (D-07, 37-09, T-37-24). Its link carries a
      // bearer token in the URL fragment; no-referrer means not even the page's own
      // address (path included) is handed to anything it links to or loads.
      //
      // ORDER IS LOAD-BEARING: when two entries set the same key for one path, Next
      // applies the LAST one. This entry must stay after the '/:path*' default or
      // /invite silently keeps strict-origin-when-cross-origin.
      // __tests__/header-snapshot.test.ts asserts the order and the match.
      {
        source: '/invite/:path*',
        headers: [{ key: 'Referrer-Policy', value: 'no-referrer' }],
      },
    ]
  },
};

export default nextConfig;
