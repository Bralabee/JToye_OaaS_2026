import { DashboardShell } from "@/components/dashboard/dashboard-shell"

// THROWAWAY, never committed under app/. The real dashboard layout resolves the
// session with auth() on the server and redirects when there is none; this one
// renders the same shell without that gate, so the kitchen page can be captured
// with fictional data and no sign-in. See ../capture-kitchen-route/README.md.
export default function CaptureKitchenLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell>{children}</DashboardShell>
}
