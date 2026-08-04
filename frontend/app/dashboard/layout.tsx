import type { Metadata } from "next"
import { redirect } from "next/navigation"
import { auth } from "@/auth"
import { DashboardShell } from "@/components/dashboard/dashboard-shell"

/**
 * The dashboard's own title, and the fallback for any descendant that has not
 * declared one (#450 item 5c). Each segment overrides it from its own
 * `layout.tsx`; the two /dashboard/payments/connect pages override it from their
 * `page.tsx`, which is why this is a plain string and not a `title.template` —
 * a template would suffix those finished titles a second time.
 */
export const metadata: Metadata = {
  title: "Dashboard — J'Toye OaaS",
}

/**
 * Server Component dashboard layout.
 *
 * Resolves the NextAuth session server-side via `auth()` and redirects
 * unauthenticated visitors BEFORE any HTML is streamed. This fixes the
 * blank flash on expired sessions that the previous client-side useSession
 * implementation suffered from, and provides defence in depth on top of
 * middleware.ts (which also matches /dashboard/:path*).
 */
export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  const session = await auth()
  if (!session) {
    redirect("/auth/signin")
  }
  return <DashboardShell>{children}</DashboardShell>
}
