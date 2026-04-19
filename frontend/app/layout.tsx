import type { Metadata } from "next";
import "./globals.css";
import { Providers } from "@/components/providers";
import { fontVariables, inter } from "./fonts";
import { BRAND } from "@/lib/brand";
import { cn } from "@/lib/utils";

const PAGE_TITLE = `${BRAND.fullName} — ${BRAND.tagline}`;
const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: PAGE_TITLE,
  description: BRAND.description,
  icons: { icon: "/favicon.svg", apple: "/apple-touch-icon.svg" },
  openGraph: {
    title: PAGE_TITLE,
    description: BRAND.description,
    siteName: BRAND.fullName,
    type: "website",
    images: [
      {
        url: BRAND.marks.og,
        width: 1200,
        height: 630,
        alt: BRAND.fullName,
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: PAGE_TITLE,
    description: BRAND.description,
    images: [BRAND.marks.og],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={fontVariables} suppressHydrationWarning>
      <body className={cn(inter.className, "bg-surface-canvas text-ink-primary antialiased")}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
