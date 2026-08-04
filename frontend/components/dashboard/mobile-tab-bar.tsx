"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { signOut, useSession } from "next-auth/react"
import { cn } from "@/lib/utils"
import { Menu, LogOut, Moon, Sun } from "lucide-react"
// Single source of truth: the SAME navigation array the desktop sidebar renders.
// Do NOT re-declare it here — both bars must never drift (see 19-UI-SPEC Surface D).
import { navigation } from "@/components/dashboard/sidebar"
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet"
import { Button } from "@/components/ui/button"

// The 4 primary tabs in the thumb zone, in display order. Each href is resolved
// against the shared `navigation` array so its icon/label stay in lock-step with
// the sidebar. Everything else falls into the "More" drawer.
const PRIMARY_ORDER = [
  "/dashboard",
  "/dashboard/orders",
  "/dashboard/products",
  "/dashboard/kitchen",
] as const

type NavItem = (typeof navigation)[number]

const primaryTabs: NavItem[] = PRIMARY_ORDER.map((href) =>
  navigation.find((item) => item.href === href)
).filter((item): item is NavItem => Boolean(item))

const moreItems: NavItem[] = navigation.filter(
  (item) => !PRIMARY_ORDER.includes(item.href as (typeof PRIMARY_ORDER)[number])
)

/** Exact match for the dashboard root; prefix match for its sub-routes so
 *  /dashboard/products/import lights up the Products tab, etc. */
function isTabActive(pathname: string, href: string): boolean {
  if (href === "/dashboard") return pathname === "/dashboard"
  return pathname === href || pathname.startsWith(`${href}/`)
}

/**
 * Mobile bottom tab bar (< md). Four primary tabs in the thumb zone plus a
 * "More" sheet holding the remaining dashboard routes and the user/theme/
 * sign-out controls relocated from the sidebar footer. Hidden at md+ where the
 * 256px sidebar takes over.
 */
export function MobileTabBar({ className }: { className?: string }) {
  const pathname = usePathname()
  const { data: session } = useSession()
  const [open, setOpen] = useState(false)
  const [dark, setDark] = useState(false)

  // Reflect whatever theme the sidebar established (it owns the on-mount class
  // toggle); we only need the current value to label the toggle button.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
    setDark(document.documentElement.classList.contains("dark"))
  }, [])

  const toggleDark = () => {
    const next = !dark
    setDark(next)
    document.documentElement.classList.toggle("dark", next)
    localStorage.setItem("theme", next ? "dark" : "light")
  }

  const tabClass = (active: boolean) =>
    cn(
      "flex flex-1 flex-col items-center justify-center gap-0.5 min-h-11 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-500",
      // orange-700: the active label is 12px text on white, so 4.5:1 applies and
      // orange-600 reached only 3.56 (#451). Matches the --primary token.
      active ? "text-orange-700" : "text-slate-500 hover:text-slate-700"
    )

  return (
    <nav
      data-testid="mobile-tab-bar"
      aria-label="Primary"
      className={cn(
        "fixed inset-x-0 bottom-0 z-50 flex h-14 border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)] md:hidden dark:border-slate-800 dark:bg-slate-900",
        className
      )}
    >
      {primaryTabs.map((item) => {
        const active = isTabActive(pathname, item.href)
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={tabClass(active)}
          >
            <item.icon className="h-5 w-5" aria-hidden="true" />
            <span>{item.name}</span>
          </Link>
        )
      })}

      <Sheet open={open} onOpenChange={setOpen}>
        <SheetTrigger asChild>
          <button
            type="button"
            aria-label="More navigation"
            className={tabClass(false)}
          >
            <Menu className="h-5 w-5" aria-hidden="true" />
            <span>More</span>
          </button>
        </SheetTrigger>
        <SheetContent side="right" className="flex w-72 flex-col gap-0 p-0">
          <SheetHeader className="border-b border-slate-200 px-4 py-4 text-left dark:border-slate-800">
            <SheetTitle>Menu</SheetTitle>
          </SheetHeader>

          {session?.user && (
            <div className="flex items-center gap-3 border-b border-slate-200 px-4 py-4 dark:border-slate-800">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-orange-400 to-orange-600 font-semibold text-white">
                {session.user.name?.charAt(0) || session.user.email?.charAt(0) || "U"}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">
                  {session.user.name || session.user.email}
                </p>
                <p className="truncate text-xs text-slate-500">{session.user.email}</p>
              </div>
            </div>
          )}

          <nav className="flex-1 space-y-1 overflow-y-auto p-3">
            {moreItems.map((item) => {
              const active = isTabActive(pathname, item.href)
              return (
                <SheetClose asChild key={item.href}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className={cn(
                      "flex min-h-11 items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors",
                      active
                        ? "bg-orange-50 text-orange-700 dark:bg-slate-800"
                        : "text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
                    )}
                  >
                    <item.icon className="h-5 w-5" aria-hidden="true" />
                    {item.name}
                  </Link>
                </SheetClose>
              )
            })}
          </nav>

          <div className="space-y-1 border-t border-slate-200 p-3 dark:border-slate-800">
            <Button
              variant="ghost"
              className="w-full justify-start gap-3 text-slate-700 dark:text-slate-200"
              onClick={toggleDark}
            >
              {dark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
              {dark ? "Light Mode" : "Dark Mode"}
            </Button>
            <Button
              variant="ghost"
              className="w-full justify-start gap-3 text-slate-700 dark:text-slate-200"
              onClick={() => signOut({ callbackUrl: "/auth/signin" })}
            >
              <LogOut className="h-5 w-5" />
              Sign Out
            </Button>
          </div>
        </SheetContent>
      </Sheet>
    </nav>
  )
}
