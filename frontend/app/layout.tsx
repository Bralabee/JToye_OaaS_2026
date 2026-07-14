import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { MotionProvider } from "@/components/motion-provider";

const inter = Inter({ subsets: ["latin"] });

// Force dynamic rendering app-wide so every page is rendered per-request and
// receives the CSP nonce set by middleware.ts (issue #89). Statically
// prerendered pages cannot carry a per-request nonce, so their inline/bootstrap
// scripts would be blocked by the enforcing nonce-based CSP. This app is already
// predominantly dynamic (dashboard + storefront); only auth/utility/redirect
// pages were static, so the tradeoff is small. Applies to all nested segments.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "J'Toye OaaS - Multi-Tenant Order Management",
  description: "Enterprise-grade order-as-a-service platform with tenant isolation",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0f172a" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className={inter.className}>
        <MotionProvider>
          <Providers>{children}</Providers>
        </MotionProvider>
      </body>
    </html>
  );
}
