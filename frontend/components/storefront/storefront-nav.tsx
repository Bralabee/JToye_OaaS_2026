"use client"

import { useEffect, useState, useCallback } from "react"
import Link from "next/link"
import { useParams, usePathname } from "next/navigation"
import { m } from "framer-motion"
import { User, LogOut, Package, MapPin, Menu, X, ShoppingBag } from "lucide-react"
import { cn } from "@/lib/utils"
import { springPop } from "@/lib/motion"
import { useCartCount } from "@/hooks/use-cart-count"
import { getCustomerSession, customerLogin, customerLogout } from "@/lib/customer-auth"
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet"

interface CustomerProfile {
  email: string
  name: string
}

export function StorefrontNav() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const pathname = usePathname()
  const params = useParams<{ slug?: string }>()
  const slug = params?.slug
  const cartCount = useCartCount(slug)
  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`)

  const checkSession = useCallback(async () => {
    const session = await getCustomerSession()
    setProfile(session?.profile || null)
  }, [])

  useEffect(() => {
    // Check on mount
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
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

  const desktopLink = (active: boolean) =>
    cn(
      "transition-colors",
      active ? "text-oxblood font-semibold" : "text-slate-600 hover:text-oxblood"
    )

  // Same mobile sheet-link idiom as PublicHeader (44px touch target, active tint).
  const mobileLink = (active: boolean) =>
    cn(
      "flex min-h-11 items-center rounded-lg px-4 text-sm transition-colors",
      active
        ? "bg-cream text-oxblood font-semibold"
        : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
    )

  return (
    <nav className="flex items-center gap-3 sm:gap-4 text-sm">
      {/* Desktop links (>=sm) — destination parity with the shared PublicHeader,
          including "For operators" so the operator door is reachable from /shop. */}
      <div className="hidden sm:flex items-center gap-4">
        {/* Label parity with PublicHeader — the same destination must not be
            called "Shops" on one surface and "Browse" on the next. */}
        <Link href="/shop" className={desktopLink(pathname === "/shop")}>
          Shops
        </Link>
        <Link
          href="/for-operators"
          className={desktopLink(isActive("/for-operators"))}
        >
          For operators
        </Link>
        <Link
          href="/track"
          className={cn("flex items-center gap-1", desktopLink(isActive("/track")))}
        >
          <MapPin className="h-3.5 w-3.5" />
          Track order
        </Link>
        {profile && (
          <Link
            href="/shop/orders"
            className="text-slate-600 hover:text-slate-900 transition-colors flex items-center gap-1"
          >
            <Package className="h-3.5 w-3.5" />
            My Orders
          </Link>
        )}
      </div>

      {/* Basket — only on /shop/[slug] routes where a cart exists (all
          viewports). Badge remounts on count change so the spring replays. */}
      {slug && (
        <Link
          href={`/shop/${slug}/cart`}
          aria-live="polite"
          onClick={(e) => {
            // Plain left-click opens the slide-over drawer; modified clicks
            // (new tab/window) and keyboard/AT/JS-off still hit the cart PAGE.
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
            e.preventDefault()
            window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
          }}
          className="relative inline-flex h-9 w-9 items-center justify-center rounded-full text-slate-700 hover:bg-slate-100 active:scale-95 transition-all"
        >
          <ShoppingBag className="h-5 w-5" />
          {cartCount > 0 && (
            <m.span
              key={cartCount}
              initial={{ scale: 0.5 }}
              animate={{ scale: 1 }}
              transition={springPop}
              className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-oxblood px-1 text-xs font-bold leading-none text-white"
            >
              {cartCount}
            </m.span>
          )}
          <span className="sr-only">{cartCount} items in basket</span>
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
          className="inline-flex items-center gap-1.5 rounded-full bg-oxblood px-3 py-1.5 text-xs font-medium text-white hover:bg-oxblood-700 transition-colors"
        >
          <User className="h-3 w-3" />
          Sign in
        </button>
      )}

      {/* Mobile hamburger (<sm) — same idiom as PublicHeader so every public
          destination (incl. /for-operators) is one visible tap from /shop. */}
      <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
        <SheetTrigger asChild>
          <button
            type="button"
            aria-label="Open menu"
            className="sm:hidden inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
          >
            <Menu className="h-5 w-5" />
          </button>
        </SheetTrigger>
        <SheetContent side="right" hideCloseButton className="w-72 p-0">
          <div className="flex h-14 items-center justify-between border-b border-cream-100 px-4">
            <SheetTitle className="text-base font-semibold text-slate-900">
              Menu
            </SheetTitle>
            <SheetClose
              aria-label="Close menu"
              className="inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
            >
              <X className="h-5 w-5" />
            </SheetClose>
          </div>
          <nav className="flex flex-col p-2">
            <SheetClose asChild>
              <Link href="/shop" className={mobileLink(pathname === "/shop")}>
                Shops
              </Link>
            </SheetClose>
            <SheetClose asChild>
              <Link
                href="/for-operators"
                className={mobileLink(isActive("/for-operators"))}
              >
                For operators
              </Link>
            </SheetClose>
            <SheetClose asChild>
              <Link href="/track" className={mobileLink(isActive("/track"))}>
                Track order
              </Link>
            </SheetClose>
            {profile && (
              <SheetClose asChild>
                <Link
                  href="/shop/orders"
                  className={mobileLink(isActive("/shop/orders"))}
                >
                  My Orders
                </Link>
              </SheetClose>
            )}
          </nav>
        </SheetContent>
      </Sheet>
    </nav>
  )
}
