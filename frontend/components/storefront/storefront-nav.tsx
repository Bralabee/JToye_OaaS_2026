"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { User, LogOut, Package } from "lucide-react"
import { getCustomerSession, customerLogin, customerLogout } from "@/lib/customer-auth"

interface CustomerProfile {
  email: string
  name: string
}

export function StorefrontNav() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)

  useEffect(() => {
    const session = getCustomerSession()
    if (session) {
      setProfile(session.profile)
    }
  }, [])

  return (
    <nav className="flex items-center gap-3 sm:gap-4 text-sm">
      <Link
        href="/shop"
        className="text-slate-600 hover:text-slate-900 transition-colors"
      >
        Browse
      </Link>
      <Link
        href="/shop/orders"
        className="text-slate-600 hover:text-slate-900 transition-colors flex items-center gap-1"
      >
        <Package className="h-3.5 w-3.5" />
        <span className="hidden sm:inline">My Orders</span>
      </Link>

      {profile ? (
        <div className="flex items-center gap-2">
          <span className="hidden sm:inline text-xs text-slate-500 truncate max-w-[120px]">
            {profile.name || profile.email}
          </span>
          <button
            onClick={() => customerLogout()}
            className="flex items-center gap-1 text-slate-500 hover:text-slate-700 transition-colors"
            title="Sign out"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      ) : (
        <button
          onClick={() => customerLogin(typeof window !== "undefined" ? window.location.pathname : "/shop")}
          className="inline-flex items-center gap-1.5 rounded-full bg-slate-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-800 transition-colors"
        >
          <User className="h-3 w-3" />
          Sign in
        </button>
      )}
    </nav>
  )
}
