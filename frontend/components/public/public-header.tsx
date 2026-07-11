"use client"

import { useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { Menu, User, X } from "lucide-react"
import { cn } from "@/lib/utils"
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
 * The three public nav routes (/shop, /for-operators, /track) are rendered as
 * explicit <Link href="..."> literals (not a mapped array) so the link-graph
 * connectivity is greppable and each route reads as a first-class inbound link.
 */
export function PublicHeader() {
  const pathname = usePathname()
  const [open, setOpen] = useState(false)

  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`)

  const desktopLink = (active: boolean) =>
    cn(
      "transition-colors",
      active ? "text-slate-900 font-semibold" : "text-slate-600 hover:text-slate-900"
    )

  const mobileLink = (active: boolean) =>
    cn(
      "flex min-h-11 items-center rounded-lg px-4 text-sm transition-colors",
      active
        ? "bg-slate-100 text-slate-900 font-semibold"
        : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
    )

  return (
    <header className="sticky top-0 z-50 bg-white border-b border-slate-200 shadow-sm">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-14 items-center justify-between">
          {/* Wordmark -> home */}
          <Link
            href="/"
            className="flex items-center gap-2 text-lg font-semibold tracking-tight text-slate-900"
          >
            <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-orange-500 text-sm font-bold text-white">
              J
            </span>
            <span>J&apos;Toye</span>
          </Link>

          {/* Desktop nav (>=sm) */}
          <nav className="hidden sm:flex items-center gap-6 text-sm">
            <Link href="/shop" className={desktopLink(isActive("/shop"))}>
              Shops
            </Link>
            <Link
              href="/for-operators"
              className={desktopLink(isActive("/for-operators"))}
            >
              For operators
            </Link>
            <Link href="/track" className={desktopLink(isActive("/track"))}>
              Track order
            </Link>
            <Link
              href="/auth/signin"
              className="inline-flex items-center gap-1.5 rounded-full bg-slate-900 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-slate-800"
            >
              <User className="h-3 w-3" />
              Sign in
            </Link>
          </nav>

          {/* Mobile hamburger (<sm) */}
          <Sheet open={open} onOpenChange={setOpen}>
            <SheetTrigger asChild>
              <button
                type="button"
                aria-label="Open menu"
                className="sm:hidden inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-300"
              >
                <Menu className="h-5 w-5" />
              </button>
            </SheetTrigger>
            <SheetContent side="right" hideCloseButton className="w-72 p-0">
              <div className="flex h-14 items-center justify-between border-b border-slate-200 px-4">
                <SheetTitle className="text-base font-semibold text-slate-900">
                  Menu
                </SheetTitle>
                <SheetClose
                  aria-label="Close menu"
                  className="inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-300"
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
                <SheetClose asChild>
                  <Link
                    href="/auth/signin"
                    className="mt-2 inline-flex min-h-11 items-center justify-center gap-1.5 rounded-full bg-slate-900 px-3 text-sm font-medium text-white transition-colors hover:bg-slate-800"
                  >
                    <User className="h-4 w-4" />
                    Sign in
                  </Link>
                </SheetClose>
              </nav>
            </SheetContent>
          </Sheet>
        </div>
      </div>
    </header>
  )
}
