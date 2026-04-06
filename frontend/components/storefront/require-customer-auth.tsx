"use client"

import { useEffect, useState } from "react"
import { LogIn, ShoppingBag } from "lucide-react"
import { getCustomerSession, customerLogin } from "@/lib/customer-auth"

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
    const session = getCustomerSession()
    setAuthenticated(!!session)
    setChecked(true)
  }, [])

  if (!checked) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-orange-500 border-t-transparent" />
      </div>
    )
  }

  if (!authenticated) {
    return (
      <div className="mx-auto max-w-md px-4 py-20 text-center">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-orange-100">
          <ShoppingBag className="h-8 w-8 text-orange-500" />
        </div>
        <h2 className="text-xl font-bold text-slate-900 mb-2">Sign in to continue</h2>
        <p className="text-sm text-slate-500 mb-6">
          {message || "You need to be signed in to view your orders and track deliveries."}
        </p>
        <button
          onClick={() => customerLogin(typeof window !== "undefined" ? window.location.pathname : undefined)}
          className="inline-flex items-center gap-2 rounded-xl bg-orange-500 hover:bg-orange-600 text-white font-semibold px-6 py-3 transition-colors"
        >
          <LogIn className="h-5 w-5" />
          Sign in
        </button>
        <p className="mt-4 text-xs text-slate-400">
          Don&apos;t have an account? You can register during sign-in.
        </p>
      </div>
    )
  }

  return <>{children}</>
}
