"use client"

import { useEffect, useState, useCallback } from "react"
import Link from "next/link"
import { motion } from "framer-motion"
import { User, LogOut, Package } from "lucide-react"
import { getCustomerSession, customerLogin, customerLogout } from "@/lib/customer-auth"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { navUnderline, useReducedMotionSafe } from "@/lib/motion"
import { cn } from "@/lib/utils"

interface CustomerProfile {
  email: string
  name: string
}

/**
 * Animated link wrapper — adds a brand-coloured underline that slides
 * in from the left on hover. Respects `prefers-reduced-motion`.
 */
function NavLink({
  href,
  children,
  className,
}: {
  href: string
  children: React.ReactNode
  className?: string
}) {
  const variants = useReducedMotionSafe(navUnderline)
  return (
    <Link
      href={href}
      className={cn(
        "group relative inline-flex items-center gap-1 text-ink-secondary transition-colors duration-fast ease-standard hover:text-ink-primary motion-reduce:transition-none",
        className,
      )}
    >
      <motion.span initial="rest" whileHover="hover" animate="rest" className="relative inline-flex items-center gap-1">
        {children}
        <motion.span
          aria-hidden="true"
          variants={variants}
          className="absolute -bottom-1 left-0 right-0 h-px bg-brand-primary"
        />
      </motion.span>
    </Link>
  )
}

export function StorefrontNav() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)

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
    <nav className="flex items-center gap-3 sm:gap-5 text-sm" aria-label="Storefront">
      <NavLink href="/shop">Browse</NavLink>
      {profile && (
        <NavLink href="/shop/orders">
          <Package className="h-3.5 w-3.5" strokeWidth={1.5} />
          <span className="hidden sm:inline">My orders</span>
        </NavLink>
      )}

      {profile ? (
        <div className="flex items-center gap-2">
          <Badge
            variant="success"
            size="sm"
            className="hidden sm:inline-flex rounded-pill px-2.5 py-1"
          >
            <span
              aria-hidden="true"
              className="inline-block h-1.5 w-1.5 rounded-full bg-success motion-safe:animate-pulse"
            />
            <span className="truncate max-w-[120px]">{profile.name || profile.email}</span>
          </Badge>
          <div className="sm:hidden flex h-7 w-7 items-center justify-center rounded-full bg-success-subtle text-success" aria-hidden="true">
            <User className="h-3.5 w-3.5" strokeWidth={1.5} />
          </div>
          <button
            onClick={() => customerLogout()}
            className="flex items-center gap-1 text-ink-tertiary hover:text-ink-primary transition-colors duration-fast ease-standard motion-reduce:transition-none"
            title="Sign out"
            aria-label="Sign out"
          >
            <LogOut className="h-3.5 w-3.5" strokeWidth={1.5} />
          </button>
        </div>
      ) : (
        <Button
          variant="primary"
          size="sm"
          className="rounded-pill"
          onClick={() => customerLogin(typeof window !== "undefined" ? window.location.pathname : "/shop")}
        >
          <User className="h-3 w-3" strokeWidth={1.5} />
          Sign in
        </Button>
      )}
    </nav>
  )
}
