/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // Enable instrumentation for environment validation
  experimental: {
    instrumentationHook: true,
  },
};

export default nextConfig;
