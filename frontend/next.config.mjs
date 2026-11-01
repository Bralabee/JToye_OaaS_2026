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
    // Remote images (shop uploads) render through a plain <img>, and the only next/image use is the landing's own local files under /public/marketing/landing (D14), which need no remotePatterns entry, so no production hostname is needed here; any future entry must be an exact hostname, never a wildcard (enforced by __tests__/csp-headers.test.ts).
    remotePatterns: [
      {
        protocol: 'http',
        hostname: 'localhost',
        port: '10000',
        pathname: '/devstoreaccount1/jtoye-images/**',
      },
    ],
    // D14: the srcset widths the optimizer may generate. The landing's photos are
    // 800-1000px originals shown at most ~320px wide (a card, a door, a desktop
    // tile), so nothing needs a candidate above 750: a 3x phone asked for 1080w
    // under the default list and got the whole original. Capping the device
    // list at 750 serves phones at most a 750px file at any pixel ratio, and a
    // 2x desktop still gets 640w for a 320px card. imageSizes covers the 32px
    // chip thumbnails at 1x/2x/3x. These are the ONLY next/image consumers.
    deviceSizes: [384, 640, 750],
    imageSizes: [32, 64, 96],
  },
  // The July operator pack PDF was retired with the /for-operators truth refresh;
  // links already handed to operators land on the page that replaced it.
  async redirects() {
    return [
      {
        source: '/jtoye-operator-pilot-pack.pdf',
        destination: '/for-operators',
        permanent: true,
      },
    ]
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
      // The payment-link page (PAY-04 / D-03, plan 30-19, T-30-77). Its link carries a
      // single-use bearer token in the URL fragment; the page strips it to /pay, and
      // no-referrer means not even /pay is handed to Stripe.js or anything else it loads.
      //
      // ORDER IS LOAD-BEARING, as for /invite: Next applies the LAST entry that sets a key
      // for a path, so this must stay after the '/:path*' default.
      // __tests__/header-snapshot.test.ts asserts the order and the match.
      {
        source: '/pay/:path*',
        headers: [{ key: 'Referrer-Policy', value: 'no-referrer' }],
      },
      // My Orders (SW-PRY-06, T-31.1-102). The page server-renders the customer's own allergy note
      // into the HTML and RSC payload, so the document says no-store ITSELF. Before this it was
      // uncacheable only because the root layout sets force-dynamic for the CSP nonce, which is a
      // different concern that could be narrowed with nothing failing. The value matches the WR-04
      // precedent (app/api/vendor-auth/logout-url/route.ts). No Vary here: measured on `next start`,
      // Next replaces a page's Vary with its own RSC list, while this Cache-Control is what it sends.
      // Exact path: a shop's own pages and /track keep the default. __tests__/header-snapshot.test.ts
      // asserts it.
      {
        source: '/shop/orders',
        headers: [{ key: 'Cache-Control', value: 'private, no-store, max-age=0' }],
      },
    ]
  },
};

export default nextConfig;
