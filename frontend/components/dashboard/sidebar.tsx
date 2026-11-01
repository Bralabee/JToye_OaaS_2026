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
  Code2,
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
import { markDeliberateSignOut } from "@/components/dashboard/kitchen/kds-board-stopped"
import { Button } from "@/components/ui/button"
import { ShopSwitcher, shopSwitcherApplies } from "@/components/dashboard/shop-switcher"
import { useDashboardNoAccess } from "@/components/dashboard/dashboard-access"
import { useOptionalShopSwitcherData } from "@/components/dashboard/shop-switcher-provider"
import type { LucideIcon } from "lucide-react"
import { useTheme } from "@/hooks/use-theme"

/**
 * The role a nav item's page needs (SW-TND-05, swarm epic #964), as the server's staff/me answer
 * decides it. `"GROUP_ADMIN"`: listed only for a Group admin. `"SHOP_MANAGER"`: listed for a Group
 * admin or a caller who manages at least one shop. Unset: every caller with access. A hint for what to
 * OFFER, never an authorization input: every page and every API call refuses the rest regardless.
 */
export type NavRequirement = "SHOP_MANAGER" | "GROUP_ADMIN"

export interface NavItem {
  name: string
  href: string
  icon: LucideIcon
  /**
   * The role this page needs (see {@link NavRequirement}). Before the staff/me answer arrives, or
   * outside the dashboard shell, a gated item is not listed: nothing gated is offered on a guess
   * (37-46, UI-SPEC § E1, T-37-113).
   */
  requires?: NavRequirement
  /**
   * Never listed on a guess, even when the caller's role is UNKNOWN (staff/me could not be read).
   * Developers only (37-46, T-37-113: integrator credentials), which is how it behaved before #1076.
   */
  serverWordOnly?: boolean
}

export const navigation: NavItem[] = [
  { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { name: "Shops", href: "/dashboard/shops", icon: Store },
  { name: "Products", href: "/dashboard/products", icon: Package },
  { name: "Orders", href: "/dashboard/orders", icon: ShoppingCart },
  // STAFF keep Customers: counter staff may look a customer up (owner ruling 2026-10-09, option B);
  // edits need SHOP_MANAGER and the page/API refuse them.
  { name: "Customers", href: "/dashboard/customers", icon: Users },
  // Finance: a shop's figures need SHOP_MANAGER on it, the tenant's need Group admin (37-11).
  { name: "Finance", href: "/dashboard/finance", icon: Banknote, requires: "SHOP_MANAGER" },
  // Marketing: promotion and announcement writes need SHOP_MANAGER on the shop.
  { name: "Marketing", href: "/dashboard/marketing", icon: Megaphone, requires: "SHOP_MANAGER" },
  { name: "Kitchen", href: "/dashboard/kitchen", icon: UtensilsCrossed },
  // Go live: the onboarding lifecycle needs SHOP_MANAGER on the onboarded shop (#961).
  { name: "Go live", href: "/dashboard/onboarding", icon: Rocket, requires: "SHOP_MANAGER" },
  // Approvals: the admin realm role (OnboardingAdminController), which staff/me reports as groupAdmin.
  // The page still renders its access-required state for a Group admin without the realm role.
  { name: "Approvals", href: "/dashboard/onboarding/approvals", icon: ShieldCheck, requires: "GROUP_ADMIN" },
  // Staff (VSA-04, D-10) and Webhooks (COMMS-06): requireGroupAdmin() on every endpoint.
  { name: "Staff", href: "/dashboard/staff", icon: UserCog, requires: "GROUP_ADMIN" },
  { name: "Webhooks", href: "/dashboard/webhooks", icon: Webhook, requires: "GROUP_ADMIN" },
  // Integrator API credentials (37-46, D-22..D-24): Group admin only.
  { name: "Developers", href: "/dashboard/developers", icon: Code2, requires: "GROUP_ADMIN", serverWordOnly: true },
  // Image review (IMG-04, Phase 24): Keep and Re-process need SHOP_MANAGER on the asset's shop.
  { name: "Image review", href: "/dashboard/media/review", icon: Images, requires: "SHOP_MANAGER" },
]

/** What the shell's staff/me answer lets this caller be offered in the nav. */
export interface NavAccess {
  /** True only while the answer is still pending: nothing gated is offered yet. */
  pending: boolean
  /**
   * True when there is no answer to decide on: staff/me failed (or the page sits outside the
   * dashboard shell). The role is then UNKNOWN, not "none" (review R1-2 on #1076): every gated item
   * but a `serverWordOnly` one is offered, and each page and the API still refuse what they refuse.
   */
  unknown: boolean
  isGroupAdmin: boolean
  isShopManager: boolean
}

/**
 * Read from the shell's staff/me answer. Only a RESOLVED answer hides an item. `isShopManager` is
 * true for a Group admin, a tenant-wide SHOP_MANAGER, or SHOP_MANAGER on any one shop (#961
 * `shopRoles`). A server older than #961 sends no `shopRoles`; then the role cannot be told and
 * manager items are offered, as before.
 */
export function useNavAccess(): NavAccess {
  const switcher = useOptionalShopSwitcherData()
  const none = { pending: false, unknown: false, isGroupAdmin: false, isShopManager: false }
  if (!switcher) return { ...none, unknown: true }
  if (switcher.loading) return { ...none, pending: true }
  const access = switcher.access
  const isGroupAdmin = switcher.isGroupAdmin
  if (isGroupAdmin) return { ...none, isGroupAdmin, isShopManager: true }
  if (!access) return { ...none, unknown: true }
  const managerRole = (role: string | null | undefined) =>
    role === "SHOP_MANAGER" || role === "GROUP_ADMIN"
  if (managerRole(access.tenantWideRole)) return { ...none, isShopManager: true }
  if (access.shopRoles == null) return { ...none, isShopManager: true }
  return { ...none, isShopManager: Object.values(access.shopRoles).some(managerRole) }
}

/** The items this caller is shown, in `navigation` order. */
export function visibleNavigation(items: NavItem[], access: NavAccess): NavItem[] {
  return items.filter((item) => {
    if (!item.requires) return true
    if (access.pending) return false
    if (access.unknown) return !item.serverWordOnly
    if (item.requires === "GROUP_ADMIN") return access.isGroupAdmin
    return access.isShopManager
  })
}

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
    // 37-18 (K2, P2-TUN-14): a deliberate sign-out must never turn the kitchen board red or sound
    // its alarm. Marked BEFORE the sign-out starts, so a 401 it provokes is not claimed.
    markDeliberateSignOut()
    void vendorLogout()
  }
  // Theme lives in ONE shared store (hooks/use-theme.ts), not in this
  // component. The store owns the localStorage read/write and the
  // documentElement class, so there is no mount-time setState here to suppress
  // and no second copy of the class side effect. `available` is false while
  // DARK_THEME_AVAILABLE is false (owner ruling 2026-10-16): the control is withheld.
  const { dark, available, toggle } = useTheme()
  // D-08 (37-06): while the no-access page shows, every route below would be
  // refused (each reads tenant data behind the shop/group gate, and the dashboard
  // home itself shows the same page), so the links and the shop switcher are
  // hidden. The user block and Sign out stay (UI-SPEC § B3).
  const noAccess = useDashboardNoAccess()
  const navAccess = useNavAccess()

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
      {shopSwitcherApplies(pathname) && !noAccess && (
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
        {!noAccess && visibleNavigation(navigation, navAccess).map((item) => {
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

      {/* Theme Toggle + Logout. The theme control is withheld while
          DARK_THEME_AVAILABLE is false (owner ruling 2026-10-16, quick
          261016-edv): the dashboard is light until its dark pass ships. */}
      <div className="border-t border-slate-800 p-4 space-y-1">
        {available && (
          <Button
            variant="ghost"
            className="w-full justify-start gap-3 text-slate-300 hover:bg-slate-800 hover:text-white"
            onClick={toggle}
          >
            {dark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
            {dark ? "Light Mode" : "Dark Mode"}
          </Button>
        )}
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
