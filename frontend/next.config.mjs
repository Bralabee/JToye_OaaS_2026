/** @type {import('next').NextConfig} */
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
};

export default nextConfig;
