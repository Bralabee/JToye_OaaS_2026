"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { motion } from "framer-motion"
import { cn } from "@/lib/utils"
import { BRAND } from "@/lib/brand"
import { useReducedMotionSafe, fadeIn } from "@/lib/motion"
import {
  Store,
  Package,
  ShoppingCart,
  Users,
  LayoutDashboard,
  Banknote,
  Megaphone,
  UtensilsCrossed,
  LogOut,
  Moon,
  Sun,
  type LucideIcon,
} from "lucide-react"
import { signOut, useSession } from "next-auth/react"
import { Button } from "@/components/ui/button"

type NavItem = { name: string; href: string; icon: LucideIcon }
type NavGroup = { label: string; items: NavItem[] }

const NAV_GROUPS: NavGroup[] = [
  {
    label: "Workspace",
    items: [
      { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
      { name: "Shops", href: "/dashboard/shops", icon: Store },
      { name: "Products", href: "/dashboard/products", icon: Package },
      { name: "Orders", href: "/dashboard/orders", icon: ShoppingCart },
    ],
  },
  {
    label: "Operations",
    items: [
      { name: "Customers", href: "/dashboard/customers", icon: Users },
      { name: "Finance", href: "/dashboard/finance", icon: Banknote },
      { name: "Marketing", href: "/dashboard/marketing", icon: Megaphone },
      { name: "Kitchen", href: "/dashboard/kitchen", icon: UtensilsCrossed },
    ],
  },
]

function getInitials(name?: string | null, email?: string | null): string {
  const source = name?.trim() || email?.trim() || "U"
  const parts = source.split(/[\s._-]+/).filter(Boolean)
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase()
  }
  return source.slice(0, 2).toUpperCase()
}

export function Sidebar() {
  const pathname = usePathname()
  const { data: session } = useSession()
  const [dark, setDark] = useState(false)
  const railVariants = useReducedMotionSafe(fadeIn)

  useEffect(() => {
    const saved = localStorage.getItem("theme")
    const isDark = saved === "dark" || (!saved && window.matchMedia("(prefers-color-scheme: dark)").matches)
    setDark(isDark)
    document.documentElement.classList.toggle("dark", isDark)
  }, [])

  const toggleDark = () => {
    const next = !dark
    setDark(next)
    document.documentElement.classList.toggle("dark", next)
    localStorage.setItem("theme", next ? "dark" : "light")
  }

  return (
    <div
      data-testid="dashboard-sidebar"
      className={cn(
        "flex h-full w-64 flex-col",
        "bg-surface-subtle text-ink-primary",
        "dark:bg-surface-inverse dark:text-ink-on-brand",
        "border-r border-border-tone-subtle",
      )}
    >
      {/* Brand lockup */}
      <div className="flex h-16 items-center border-b border-border-tone-subtle px-6">
        <Link href="/dashboard" className="flex items-center" aria-label={`${BRAND.fullName} dashboard home`}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={BRAND.marks.wordmarkWithProduct}
            alt={BRAND.fullName}
            width={160}
            height={32}
            className="h-8 w-auto select-none"
          />
        </Link>
      </div>

      {/* User info */}
      {session?.user && (
        <div className="border-b border-border-tone-subtle px-6 py-4">
          <div className="flex items-center gap-3">
            <div
              aria-hidden="true"
              className={cn(
                "flex h-10 w-10 items-center justify-center rounded-full",
                "bg-brand-primary/10 text-brand-primary",
                "font-display text-base font-semibold",
              )}
            >
              {getInitials(session.user.name, session.user.email)}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-ink-primary dark:text-ink-on-brand">
                {session.user.name || session.user.email}
              </p>
              <p className="truncate text-xs text-ink-tertiary">{session.user.email}</p>
            </div>
          </div>
        </div>
      )}

      {/* Navigation */}
      <nav
        className={cn(
          "flex-1 overflow-y-auto px-3 py-4",
          "[scrollbar-width:none] [&::-webkit-scrollbar]:hidden",
        )}
        aria-label="Primary"
      >
        {NAV_GROUPS.map((group, groupIdx) => (
          <div key={group.label} className={cn(groupIdx > 0 && "mt-6")}>
            <p
              className={cn(
                "px-3 mb-2",
                "text-overline font-medium uppercase tracking-widest",
                "text-ink-tertiary",
              )}
            >
              {group.label}
            </p>
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const isActive = pathname === item.href
                const Icon = item.icon
                return (
                  <li key={item.name} className="relative">
                    {isActive && (
                      <motion.span
                        layoutId="nav-rail"
                        aria-hidden="true"
                        variants={railVariants}
                        initial="hidden"
                        animate="visible"
                        className={cn(
                          "absolute left-0 top-1 bottom-1 w-[3px] rounded-r-pill",
                          "bg-brand-primary",
                        )}
                      />
                    )}
                    <Link
                      href={item.href}
                      aria-current={isActive ? "page" : undefined}
                      data-active={isActive ? "true" : undefined}
                      className={cn(
                        "group relative flex items-center gap-3 rounded-md pl-4 pr-3 py-2.5",
                        "text-sm transition-colors duration-fast ease-standard motion-reduce:transition-none",
                        isActive
                          ? "bg-brand-primary/10 text-brand-primary font-medium"
                          : "text-ink-secondary hover:bg-surface-muted hover:text-ink-primary dark:hover:text-ink-on-brand",
                      )}
                    >
                      <Icon
                        strokeWidth={1.5}
                        className={cn(
                          "h-5 w-5 shrink-0",
                          isActive ? "text-brand-primary" : "text-ink-tertiary group-hover:text-ink-secondary",
                        )}
                      />
                      <span>{item.name}</span>
                    </Link>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      {/* Tray: theme toggle + sign out */}
      <div className="border-t border-border-tone-subtle p-3">
        <div className={cn("rounded-xl bg-surface-muted p-2 space-y-1", "dark:bg-white/5")}>
          <Button
            variant="ghost"
            size="sm"
            className="w-full justify-start gap-3 text-ink-secondary hover:text-ink-primary dark:hover:text-ink-on-brand"
            onClick={toggleDark}
            aria-label={dark ? "Switch to light mode" : "Switch to dark mode"}
          >
            {dark ? (
              <Sun strokeWidth={1.5} className="h-4 w-4" />
            ) : (
              <Moon strokeWidth={1.5} className="h-4 w-4" />
            )}
            <span>{dark ? "Light mode" : "Dark mode"}</span>
          </Button>
          <div className="border-t border-border-tone-subtle/60" role="separator" />
          <Button
            variant="ghost"
            size="sm"
            className="w-full justify-start gap-3 text-ink-secondary hover:text-ink-primary dark:hover:text-ink-on-brand"
            onClick={() => signOut({ callbackUrl: "/auth/signin" })}
          >
            <LogOut strokeWidth={1.5} className="h-4 w-4" />
            <span>Sign out</span>
          </Button>
        </div>
      </div>
    </div>
  )
}
