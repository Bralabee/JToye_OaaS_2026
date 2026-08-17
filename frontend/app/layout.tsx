import type { Metadata, Viewport } from "next";
import { Work_Sans } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { MotionProvider } from "@/components/motion-provider";
import { CookieNotice } from "@/components/public/cookie-notice";

// Work Sans — the parent brand typeface (jtoyedigital.co.uk). App-wide for brand
// coherence; a clean geometric sans, near drop-in for the prior Inter.
const workSans = Work_Sans({ subsets: ["latin"], weight: ["400", "500", "600", "700", "800", "900"] });

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
      <body className={workSans.className}>
        <MotionProvider>
          <Providers>{children}</Providers>
          {/* Root layout, not PublicShell: /shop/[slug] does not use the shell
              and still needs the notice. Inside MotionProvider because the
              notice uses `m.` (LazyMotion strict), outside Providers because it
              needs no session or query context. */}
          <CookieNotice />
        </MotionProvider>
      </body>
    </html>
  );
}
