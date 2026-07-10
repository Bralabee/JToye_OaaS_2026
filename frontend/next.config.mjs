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
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), browsing-topics=()' },
        ],
      },
    ]
  },
};

export default nextConfig;
