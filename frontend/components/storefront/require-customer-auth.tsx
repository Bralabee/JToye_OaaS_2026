"use client"

import { useEffect, useState } from "react"
import { LogIn, ShoppingBag } from "lucide-react"
import Link from "next/link"
import { getCustomerSession } from "@/lib/customer-auth"

interface RequireCustomerAuthProps {
  children: React.ReactNode
  message?: string
}

/**
 * Wraps storefront pages that require customer authentication.
 * Shows a sign-in prompt if the customer is not logged in.
 */
export function RequireCustomerAuth({ children, message }: RequireCustomerAuthProps) {
  const [checked, setChecked] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)

  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled) return
      setAuthenticated(!!session)
      setChecked(true)
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (!checked) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-amber-500 border-t-transparent" />
      </div>
    )
  }

  if (!authenticated) {
    const nextPath =
      typeof window !== "undefined" ? window.location.pathname + window.location.search : "/shop"
    return (
      <div className="mx-auto max-w-md px-4 py-20 text-center">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-amber-100">
          <ShoppingBag className="h-8 w-8 text-amber-700" />
        </div>
        <h2 className="text-xl font-bold text-slate-900 mb-2">Sign in to continue</h2>
        <p className="text-sm text-slate-500 mb-6">
          {message || "You need to be signed in to view your orders and track deliveries."}
        </p>
        {/* Links to the sign-in PAGE rather than firing customerLogin() straight at
            Keycloak. The bare redirect gave the shopper no landing destination — no
            back button that worked, no "create an account" option, and no way to
            reach the vendor page if they had guessed wrong about which they needed. */}
        <Link
          href={`/shop/signin?next=${encodeURIComponent(nextPath)}`}
          className="inline-flex items-center gap-2 rounded-full bg-oxblood hover:bg-oxblood-700 text-white font-semibold px-6 py-3 transition-colors"
        >
          <LogIn className="h-5 w-5" />
          Sign in
        </Link>
        <p className="mt-4 text-xs text-slate-400">
          Don&apos;t have an account? You can create one on the next screen.
        </p>
      </div>
    )
  }

  return <>{children}</>
}
