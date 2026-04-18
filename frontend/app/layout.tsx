import type { Metadata } from "next";
import "./globals.css";
import { Providers } from "@/components/providers";
import { fontVariables, inter } from "./fonts";

export const metadata: Metadata = {
  title: "J'Toye OaaS - Multi-Tenant Order Management",
  description: "Enterprise-grade order-as-a-service platform with tenant isolation",
  icons: { icon: "/favicon.svg", apple: "/apple-touch-icon.svg" },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={fontVariables} suppressHydrationWarning>
      <body className={inter.className}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
