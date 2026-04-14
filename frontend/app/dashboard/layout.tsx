import { redirect } from "next/navigation"
import { auth } from "@/auth"
import { DashboardShell } from "@/components/dashboard/dashboard-shell"

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
