/** @type {import('next').NextConfig} */

// Static security response headers (SEC-02 / ASVS 14.4.x).
//
// The Content-Security-Policy is NOT set here: it needs a fresh per-request
// nonce to drop `script-src 'unsafe-inline'` (issue #89 P1-7), so it is built
// in middleware.ts (see lib/security-headers.ts). Only the headers that are
// constant across requests live here.
const nextConfig = {
  output: 'standalone',
  images: {
    remotePatterns: [
      {
        protocol: 'http',
        hostname: 'localhost',
        port: '9000',
        pathname: '/jtoye-images/**',
      },
      // Add production S3/CloudFront patterns here
      // {
      //   protocol: 'https',
      //   hostname: '*.amazonaws.com',
      //   pathname: '/jtoye-images/**',
      // },
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
    ]
  },
};

export default nextConfig;
