"use client"

import { SessionProvider } from "next-auth/react"
import { Toaster } from "@/components/ui/toaster"

export function Providers({ children }: { children: React.ReactNode }) {
  // refetchOnWindowFocus=false stops NextAuth re-polling /api/auth/session
  // on tab/window focus, which otherwise races with in-flight `getSession()`
  // calls from the axios interceptor and surfaces spurious ClientFetchErrors
  // in the console. The axios 401 handler still refreshes the session on
  // real expiry, so user-visible session freshness is unaffected.
  return (
    <SessionProvider refetchOnWindowFocus={false}>
      {children}
      <Toaster />
    </SessionProvider>
  )
}
