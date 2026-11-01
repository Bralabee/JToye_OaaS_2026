import type { Metadata, Viewport } from "next";
import { Work_Sans } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { MotionProvider } from "@/components/motion-provider";
import { CookieNotice } from "@/components/public/cookie-notice";
import { resolvePublicOrigin } from "@/lib/public-origin";

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

/**
 * App-wide metadata defaults, and the `metadataBase` every page resolves against.
 *
 * SW-CLR-08 (swarm epic #964): the landing page, /for-operators and /legal
 * emitted RELATIVE canonical and og:url values, because nothing outside /shop
 * set a metadataBase and Next passes a relative string through unchanged
 * without one. app/shop/layout.tsx already solved this for the storefront;
 * this is the same solution one level up, so every page's relative canonical
 * and Open Graph URL resolve against one injected origin.
 *
 * A function, not a constant, for the reason app/shop/layout.tsx records: the
 * origin is read per request at RUNTIME from resolvePublicOrigin() (APP_PUBLIC_
 * ORIGIN, then NEXTAUTH_URL, both plain server envs set per environment), never
 * from a NEXT_PUBLIC_* that Next would inline at build time. `undefined` is a
 * deliberate outcome when no trusted origin is configured: no hostname is
 * guessed, and the pages fall back to root-relative URLs as before.
 */
export async function generateMetadata(): Promise<Metadata> {
  const origin = resolvePublicOrigin();
  return {
    metadataBase: origin ? new URL(origin) : undefined,
    title: "J'Toye OaaS - Multi-Tenant Order Management",
    description: "Enterprise-grade order-as-a-service platform with tenant isolation",
  };
}

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
