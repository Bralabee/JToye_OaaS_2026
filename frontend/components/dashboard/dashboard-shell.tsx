"use client"

import { Store } from "lucide-react"
import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { CompanyLegalLine } from "@/components/platform/company-legal"
import type { ReactNode } from "react"

/**
 * Client-side wrapper around the authenticated dashboard chrome.
 *
 * The parent layout (frontend/app/dashboard/layout.tsx) is a Server Component
 * that performs the real auth check via `auth()` and redirects server-side
 * when the session is missing — no more blank flash on expired sessions.
 *
 * Desktop (>= md): the 256px `<Sidebar/>` column + main scroll area — unchanged.
 * Mobile (< md): the sidebar is hidden; a slim top bar carries the wordmark and
 * a fixed bottom `<MobileTabBar/>` (4 tabs + More drawer) provides thumb-zone
 * navigation. `pb-20` on the container clears the fixed bar.
 */
export function DashboardShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
      <Sidebar />
      <main className="flex-1 overflow-y-auto">
        {/* Mobile-only top bar — brand chrome once the sidebar is gone. */}
        <div className="sticky top-0 z-40 flex h-14 items-center gap-2 border-b border-slate-200 bg-white px-4 md:hidden dark:border-slate-800 dark:bg-slate-900">
          <Store className="h-6 w-6 text-blue-500" aria-hidden="true" />
          <span className="font-bold text-slate-900 dark:text-slate-100">J&apos;Toye</span>
        </div>
        <div className="container mx-auto p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100">
          {children}
          <footer className="mt-10 border-t border-slate-200 pt-4 dark:border-slate-800">
            <CompanyLegalLine />
          </footer>
        </div>
      </main>
      <MobileTabBar className="md:hidden" />
    </div>
  )
}
