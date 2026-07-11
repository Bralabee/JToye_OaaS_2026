"use client"

import { useEffect, useState, useCallback } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { User, LogOut, Package, MapPin } from "lucide-react"
import { cn } from "@/lib/utils"
import { getCustomerSession, customerLogin, customerLogout } from "@/lib/customer-auth"

interface CustomerProfile {
  email: string
  name: string
}

export function StorefrontNav() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)
  const pathname = usePathname()
  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`)

  const checkSession = useCallback(async () => {
    const session = await getCustomerSession()
    setProfile(session?.profile || null)
  }, [])

  useEffect(() => {
    // Check on mount
    checkSession()

    // Re-check when page gains focus (covers OAuth redirect return)
    const onFocus = () => checkSession()
    const onVisibility = () => {
      if (document.visibilityState === "visible") checkSession()
    }
    // Re-check on storage changes (covers cross-tab login via marker)
    const onStorage = (e: StorageEvent) => {
      if (e.key === "jtoye-customer-logged-in" || e.key === "jtoye-customer-expires-at") {
        checkSession()
      }
    }

    window.addEventListener("focus", onFocus)
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("storage", onStorage)

    // Also poll briefly after mount to catch the redirect scenario
    // (OAuth callback sets localStorage then redirects — same tab, no storage event)
    const timer = setInterval(checkSession, 1000)
    const cleanup = setTimeout(() => clearInterval(timer), 5000) // Stop polling after 5s

    return () => {
      window.removeEventListener("focus", onFocus)
      document.removeEventListener("visibilitychange", onVisibility)
      window.removeEventListener("storage", onStorage)
      clearInterval(timer)
      clearTimeout(cleanup)
    }
  }, [checkSession])

  return (
    <nav className="flex items-center gap-3 sm:gap-4 text-sm">
      <Link
        href="/shop"
        className={cn(
          "transition-colors",
          isActive("/shop") && pathname === "/shop"
            ? "text-slate-900 font-semibold"
            : "text-slate-600 hover:text-slate-900"
        )}
      >
        Browse
      </Link>
      <Link
        href="/track"
        className={cn(
          "flex items-center gap-1 transition-colors",
          isActive("/track")
            ? "text-slate-900 font-semibold"
            : "text-slate-600 hover:text-slate-900"
        )}
      >
        <MapPin className="h-3.5 w-3.5" />
        <span className="hidden sm:inline">Track order</span>
      </Link>
      {profile && (
        <Link
          href="/shop/orders"
          className="text-slate-600 hover:text-slate-900 transition-colors flex items-center gap-1"
        >
          <Package className="h-3.5 w-3.5" />
          <span className="hidden sm:inline">My Orders</span>
        </Link>
      )}

      {profile ? (
        <div className="flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs text-emerald-700">
            <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
            <span className="truncate max-w-[100px]">{profile.name || profile.email}</span>
          </div>
          <div className="sm:hidden flex h-7 w-7 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
            <User className="h-3.5 w-3.5" />
          </div>
          <button
            onClick={() => customerLogout()}
            className="flex items-center gap-1 text-slate-400 hover:text-slate-600 transition-colors"
            title="Sign out"
          >
            <LogOut className="h-3.5 w-3.5" />
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
