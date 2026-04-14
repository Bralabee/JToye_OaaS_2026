"use client"

import { Sidebar } from "@/components/dashboard/sidebar"
import type { ReactNode } from "react"

/**
 * Client-side wrapper around the authenticated dashboard chrome.
 *
 * The parent layout (frontend/app/dashboard/layout.tsx) is a Server Component
 * that performs the real auth check via `auth()` and redirects server-side
 * when the session is missing — no more blank flash on expired sessions.
 * This shell just renders the interactive sidebar + main scroll area.
 */
export function DashboardShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
      <Sidebar />
      <main className="flex-1 overflow-y-auto">
        <div className="container mx-auto p-8 dark:text-slate-100">{children}</div>
      </main>
    </div>
  )
}
