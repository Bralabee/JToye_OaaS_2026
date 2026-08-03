"use client"

import { useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { LogOut, Menu, Package, User, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { useCustomerSession } from "@/hooks/use-customer-session"
import { customerLogout } from "@/lib/customer-auth"
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet"

/**
 * Shared public header (Surface B). Sticky wordmark + persona nav on >=sm,
 * icon-only hamburger opening a shadcn sheet on <sm. Active state via
 * usePathname prefix match — the storefront/dashboard active-link idiom.
 *
 * Wordmark contract: it ALWAYS goes to `/`. It previously homed to the surface
 * you were on (/track -> /shop), which made the logo land you somewhere
 * different depending on where you clicked it — the reported "wonky" nav. A
 * logo is the one control users expect to be constant, so every public surface
 * (including the storefront chrome in app/shop/layout.tsx) now points it at the
 * landing page.
 *
 * The three public nav routes (/shop, /for-operators, /track) are rendered as
 * explicit <Link href="..."> literals (not a mapped array) so the link-graph
 * connectivity is greppable and each route reads as a first-class inbound link.
 *
 * Persona gating (#458): /for-operators and /track are rendered only when there
 * is NO customer session. A signed-in customer has self-selected — the operator
 * pitch is noise on their surface, and the GUEST order lookup behind /track
 * (order number + email) is redundant when the system already knows their
 * orders. This header shares the gate with StorefrontNav on purpose: #457 exists
 * because the two headers disagreed about the session once already, and fixing
 * only /shop would recreate that split the moment a signed-in shopper clicked
 * the wordmark. Neither destination is deleted — both stay in the PublicFooter
 * on every surface, and /track sits behind every My Orders card.
 *
 * Sign-in contract: the unqualified "Sign in" CTA is the CUSTOMER one and goes to
 * /shop/signin. It used to point at /auth/signin — the OPERATOR page, on a
 * different Keycloak realm — while the footer's "Vendor sign in" pointed at that
 * same URL, so the two personas were literally indistinguishable and a shopper
 * clicking the primary CTA landed in an identity pool their account is not in,
 * with no route back. The vendor entry point stays in the footer and on
 * /for-operators, where the audience is already self-selected.
 */
export function PublicHeader() {
  const pathname = usePathname()
  const [open, setOpen] = useState(false)
  // #457: this header used to have no notion of the customer session, so going
  // from /shop to / or /track showed "Sign in" to someone who was signed in and
  // read as "the site logged me out". Same hook as StorefrontNav — one source.
  const { profile } = useCustomerSession()

  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`)

  const desktopLink = (active: boolean) =>
    cn(
      "transition-colors",
      active ? "text-oxblood font-semibold" : "text-slate-600 hover:text-oxblood"
    )

  const mobileLink = (active: boolean) =>
    cn(
      "flex min-h-11 items-center rounded-lg px-4 text-sm transition-colors",
      active
        ? "bg-cream text-oxblood font-semibold"
        : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
    )

  return (
    <header className="sticky top-0 z-50 bg-white border-b border-cream-100 shadow-sm">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-14 items-center justify-between">
          {/* Wordmark -> the landing page, from every public surface. */}
          <Link
            href="/"
            aria-label="J'Toye home"
            className="flex items-center gap-2 text-lg font-semibold tracking-tight text-oxblood"
          >
            <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-oxblood text-sm font-bold text-white">
              J
            </span>
            <span>J&apos;Toye</span>
          </Link>

          {/* Desktop nav (>=sm) */}
          <nav className="hidden sm:flex items-center gap-6 text-sm">
            <Link href="/shop" className={desktopLink(isActive("/shop"))}>
              Shops
            </Link>
            {!profile && (
              <>
                <Link
                  href="/for-operators"
                  className={desktopLink(isActive("/for-operators"))}
                >
                  For operators
                </Link>
                <Link href="/track" className={desktopLink(isActive("/track"))}>
                  Track order
                </Link>
              </>
            )}
            {profile ? (
              <>
                <Link
                  href="/shop/orders"
                  className={cn(
                    "flex items-center gap-1",
                    desktopLink(isActive("/shop/orders"))
                  )}
                >
                  <Package className="h-3.5 w-3.5" />
                  My Orders
                </Link>
                {/* Identity chip + sign-out, matching the StorefrontNav idiom so
                    the signed-in state looks the same on every public surface. */}
                <div className="flex items-center gap-2">
                  <div className="flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs text-emerald-700">
                    <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                    <span className="max-w-[100px] truncate">
                      {profile.name || profile.email}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => customerLogout()}
                    className="flex items-center gap-1 text-slate-400 transition-colors hover:text-slate-600"
                    title="Sign out"
                  >
                    <LogOut className="h-3.5 w-3.5" />
                    <span className="sr-only">Sign out</span>
                  </button>
                </div>
              </>
            ) : (
              <Link
                href="/shop/signin"
                className="inline-flex items-center gap-1.5 rounded-full bg-oxblood px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-oxblood-700"
              >
                <User className="h-3 w-3" />
                Sign in
              </Link>
            )}
          </nav>

          {/* Mobile hamburger (<sm) */}
          <Sheet open={open} onOpenChange={setOpen}>
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
                  <Link href="/shop" className={mobileLink(isActive("/shop"))}>
                    Shops
                  </Link>
                </SheetClose>
                {/* Separate code path from the desktop row — the #458 gate has
                    to be repeated here or the link stays live on mobile. */}
                {!profile && (
                  <>
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
                  </>
                )}
                {profile ? (
                  <>
                    <SheetClose asChild>
                      <Link
                        href="/shop/orders"
                        className={mobileLink(isActive("/shop/orders"))}
                      >
                        My Orders
                      </Link>
                    </SheetClose>
                    <div className="mt-2 flex items-center justify-between gap-2 border-t border-cream-100 px-4 pt-3">
                      <span className="flex min-w-0 items-center gap-1.5 text-xs text-emerald-700">
                        <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" />
                        <span className="truncate">
                          {profile.name || profile.email}
                        </span>
                      </span>
                      <SheetClose asChild>
                        <button
                          type="button"
                          onClick={() => customerLogout()}
                          className="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-lg px-3 text-sm text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-900"
                        >
                          <LogOut className="h-4 w-4" />
                          Sign out
                        </button>
                      </SheetClose>
                    </div>
                  </>
                ) : (
                  <SheetClose asChild>
                    <Link
                      href="/shop/signin"
                      className="mt-2 inline-flex min-h-11 items-center justify-center gap-1.5 rounded-full bg-oxblood px-3 text-sm font-medium text-white transition-colors hover:bg-oxblood-700"
                    >
                      <User className="h-4 w-4" />
                      Sign in
                    </Link>
                  </SheetClose>
                )}
              </nav>
            </SheetContent>
          </Sheet>
        </div>
      </div>
    </header>
  )
}
