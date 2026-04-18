/** @type {import('next').NextConfig} */

// Security headers per ASVS 14.4.1-14.4.7 (SEC-02).
// Rollout: Content-Security-Policy-Report-Only in this merge; flip to
// Content-Security-Policy enforce after >=1 week of staging observation
// (manual gate, see PLAN 12-02 Task 07).
const keycloakOrigin = process.env.NEXT_PUBLIC_KEYCLOAK_URL || ''
const apiOrigin = process.env.NEXT_PUBLIC_API_URL || ''
const wsOrigin = apiOrigin.replace(/^http/, 'ws')
const isDev = process.env.NODE_ENV === 'development'

const cspDirectives = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''} https://js.stripe.com https://*.js.stripe.com`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",
  "font-src 'self' data:",
  `connect-src 'self' https://api.stripe.com https://*.stripe.com ${apiOrigin} ${wsOrigin} ${keycloakOrigin}`.replace(/\s+/g, ' ').trim(),
  "frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com",
  "frame-ancestors 'none'",
  `form-action 'self' ${keycloakOrigin}`.replace(/\s+/g, ' ').trim(),
  "base-uri 'self'",
  "object-src 'none'",
  "upgrade-insecure-requests",
].join('; ')

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
          // Report-Only rollout (see ASVS 14.4.x / SEC-02). Task 12-02-07
          // is the manual gate that flips this key to 'Content-Security-Policy'
          // after a >=1-week staging observation window.
          { key: 'Content-Security-Policy-Report-Only', value: cspDirectives },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), browsing-topics=()' },
        ],
      },
    ]
  },
};

export default nextConfig;
