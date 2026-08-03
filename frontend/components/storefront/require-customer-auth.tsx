"use client"

import { useEffect, useState } from "react"
import { getCustomerSession } from "@/lib/customer-auth"
import { CustomerSignInPrompt } from "@/components/storefront/customer-signin-prompt"

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
    // Markup lives in CustomerSignInPrompt so the server-rendered wall on
    // /shop/orders and this client-probed one stay identical (see #463).
    return <CustomerSignInPrompt message={message} nextPath={nextPath} />
  }

  return <>{children}</>
}
