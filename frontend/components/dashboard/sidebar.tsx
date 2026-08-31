"use client"

import { useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import {
  Store,
  Package,
  ShoppingCart,
  Users,
  LayoutDashboard,
  Banknote,
  Megaphone,
  UtensilsCrossed,
  Rocket,
  ShieldCheck,
  UserCog,
  Webhook,
  Images,
  LogOut,
  Moon,
  Sun,
} from "lucide-react"
import { useSession } from "next-auth/react"
// R-01 (P0): NOT a bare next-auth `signOut`. That drops the app cookie and
// leaves every Keycloak SSO cookie alive, so the next "Sign in with Keycloak"
// re-entered this dashboard as the departed user. `vendorLogout` fetches the
// end-session URL first, then clears the app session, then navigates to the IdP.
import { vendorLogout } from "@/lib/vendor-logout"
import { Button } from "@/components/ui/button"
import { ShopSwitcher, shopSwitcherApplies } from "@/components/dashboard/shop-switcher"
import { useTheme } from "@/hooks/use-theme"

export const navigation = [
  { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { name: "Shops", href: "/dashboard/shops", icon: Store },
  { name: "Products", href: "/dashboard/products", icon: Package },
  { name: "Orders", href: "/dashboard/orders", icon: ShoppingCart },
  { name: "Customers", href: "/dashboard/customers", icon: Users },
  { name: "Finance", href: "/dashboard/finance", icon: Banknote },
  { name: "Marketing", href: "/dashboard/marketing", icon: Megaphone },
  { name: "Kitchen", href: "/dashboard/kitchen", icon: UtensilsCrossed },
  { name: "Go live", href: "/dashboard/onboarding", icon: Rocket },
  // Admin-only surface (like Finance): the page itself renders an
  // access-required state on 403 for non-admin users.
  { name: "Approvals", href: "/dashboard/onboarding/approvals", icon: ShieldCheck },
  // GROUP_ADMIN-only surface (VSA-04, D-10) — same convention as Approvals: the
  // item is always listed and the /dashboard/staff PAGE renders the
  // access-required state on the server's typed 403 for a non-group-admin.
  { name: "Staff", href: "/dashboard/staff", icon: UserCog },
  // Machine channel (COMMS-06): vendor-facing webhook subscriptions +
  // delivery-log browser. Falls into the mobile "More" sheet automatically.
  { name: "Webhooks", href: "/dashboard/webhooks", icon: Webhook },
  // Image review (IMG-04, Phase 24): rejected uploads + content-flagged images
  // for Keep/Replace. Like the other secondary surfaces, it flows into the
  // mobile "More" sheet automatically (not one of the 4 primary thumb tabs).
  { name: "Image review", href: "/dashboard/media/review", icon: Images },
]

export function Sidebar() {
  const pathname = usePathname()
  const { data: session } = useSession()
  // CR-02: the same busy state the four CUSTOMER sign-out affordances got, for
  // the same stated reason — "without a busy state the user gets no
  // acknowledgement at all and taps again". The vendor round-trip is bounded at
  // 3s and therefore visibly slow on a bad connection, and here a re-tap was
  // not merely untidy: it could override the pending Keycloak navigation and
  // hand the P0 back. `vendorLogout` carries its own latch as well; this is the
  // half the vendor can see.
  //
  // NEVER RESET, deliberately (and `vendorLogout` is not awaited for the same
  // reason). `location.href` only SCHEDULES a navigation; the document stays
  // live and tappable until it commits, which on a bad connection is the slow
  // part. A sign-out button's correct terminal state is "busy until this page
  // goes away".
  const [signingOut, setSigningOut] = useState(false)
  const handleSignOut = () => {
    setSigningOut(true)
    void vendorLogout()
  }
  // Theme lives in ONE shared store (hooks/use-theme.ts), not in this
  // component. The store owns the localStorage read/write and the
  // documentElement class, so there is no mount-time setState here to suppress
  // and no second copy of the class side effect.
  const { dark, toggle } = useTheme()

  return (
    <div className="hidden md:flex h-full w-64 flex-col bg-slate-900 text-white">
      {/* Logo */}
      <div className="flex h-16 items-center gap-2 border-b border-slate-800 px-6">
        <Store className="h-8 w-8 text-orange-500" />
        <div className="flex flex-col">
          <span className="font-bold text-lg">J&apos;Toye</span>
          <span className="text-xs text-slate-400">OaaS Platform</span>
        </div>
      </div>

      {/* Shop-context switcher (VSA-03) — persisted per-device (D-07); a
          GROUP_ADMIN lands on "All shops" (D-06). Omitted, border and all, on
          the per-tenant onboarding sub-tree, where it acts on nothing
          (#450 item 1 — see `shopSwitcherApplies`). */}
      {shopSwitcherApplies(pathname) && (
        <div className="border-b border-slate-800 px-3 py-3">
          <ShopSwitcher variant="sidebar" />
        </div>
      )}

      {/* User Info */}
      {session?.user && (
        <div className="border-b border-slate-800 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-full bg-gradient-to-br from-orange-400 to-orange-600 flex items-center justify-center font-semibold">
              {session.user.name?.charAt(0) || session.user.email?.charAt(0) || "U"}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{session.user.name || session.user.email}</p>
              <p className="text-xs text-slate-400 truncate">{session.user.email}</p>
            </div>
          </div>
        </div>
      )}

      {/* Navigation */}
      <nav className="flex-1 space-y-1 px-3 py-4">
        {navigation.map((item) => {
          const isActive = pathname === item.href
          return (
            <Link
              key={item.name}
              href={item.href}
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all hover:bg-slate-800",
                isActive
                  // orange-700, not orange-600: this pill carries white text at
                  // 14px, so 4.5:1 applies (#451). The glow keeps the brighter
                  // orange-500 — a shadow is decorative and carries no text.
                  ? "bg-orange-700 text-white shadow-lg shadow-orange-500/40"
                  : "text-slate-300 hover:text-white"
              )}
            >
              <item.icon className="h-5 w-5" />
              {item.name}
            </Link>
          )
        })}
      </nav>

      {/* Theme Toggle + Logout */}
      <div className="border-t border-slate-800 p-4 space-y-1">
        <Button
          variant="ghost"
          className="w-full justify-start gap-3 text-slate-300 hover:bg-slate-800 hover:text-white"
          onClick={toggle}
        >
          {dark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          {dark ? "Light Mode" : "Dark Mode"}
        </Button>
        <Button
          variant="ghost"
          className="w-full justify-start gap-3 text-slate-300 hover:bg-slate-800 hover:text-white"
          onClick={handleSignOut}
          disabled={signingOut}
          aria-busy={signingOut}
        >
          <LogOut className="h-5 w-5" />
          Sign Out
        </Button>
      </div>
    </div>
  )
}
