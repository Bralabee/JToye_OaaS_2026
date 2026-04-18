"use client"

import { motion } from "framer-motion"
import { Sidebar } from "@/components/dashboard/sidebar"
import { fadeUp, useReducedMotionSafe } from "@/lib/motion"
import { cn } from "@/lib/utils"
import type { ReactNode } from "react"

/**
 * Client-side wrapper around the authenticated dashboard chrome.
 *
 * The parent layout (frontend/app/dashboard/layout.tsx) is a Server Component
 * that performs the real auth check via `auth()` and redirects server-side
 * when the session is missing — no more blank flash on expired sessions.
 * This shell renders the interactive sidebar + main scroll area and provides
 * a subtle page-enter animation that respects `prefers-reduced-motion`.
 */
export function DashboardShell({ children }: { children: ReactNode }) {
  const variants = useReducedMotionSafe(fadeUp)

  return (
    <div className="flex h-screen overflow-hidden bg-surface-canvas text-ink-primary">
      <Sidebar />
      <motion.main
        className="flex-1 overflow-y-auto"
        variants={variants}
        initial="hidden"
        animate="visible"
      >
        <div className={cn("mx-auto max-w-[90rem] p-6 lg:p-10")}>{children}</div>
      </motion.main>
    </div>
  )
}
