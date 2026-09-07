"use client"

import { Store } from "lucide-react"
import { usePathname } from "next/navigation"
import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { ShopSwitcher, shopSwitcherApplies } from "@/components/dashboard/shop-switcher"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { CompanyLegalLine } from "@/components/platform/company-legal"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { cn } from "@/lib/utils"
import type { ReactNode } from "react"

/**
 * Client-side wrapper around the authenticated dashboard chrome.
 *
 * The parent layout (frontend/app/dashboard/layout.tsx) is a Server Component
 * that performs the real auth check via `auth()` and redirects server-side
 * when the session is missing — no more blank flash on expired sessions.
 *
 * Desktop (>= md): the 256px `<Sidebar/>` column + main scroll area — unchanged.
 * Mobile (< md): the sidebar is hidden; a slim top bar carries the wordmark and
 * a fixed bottom `<MobileTabBar/>` (4 tabs + More drawer) provides thumb-zone
 * navigation. `pb-20` on the container clears the fixed bar.
 */
export function DashboardShell({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const showShopSwitcher = shopSwitcherApplies(pathname)

  return (
    // One provider above BOTH switchers (sidebar + mobile top bar): a single
    // fetch and a single hydration writer (WR-06). Renders no DOM of its own, so
    // the MOBL-01-verified 375px shell markup below is unchanged.
    <ShopSwitcherProvider>
    <div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
      {/* SKIP LINK (A11Y-1, QA council 20260902-134741; WCAG 2.4.1). With no
          bypass control the first Tab stop was the shop switcher and <main> was
          17 presses away on every dashboard load (probes/a11y/23), against 2 on
          the public shell. This is the same markup public-shell.tsx,
          app/shop/layout.tsx and app/auth/signin/page.tsx already ship — class
          string and target id copied verbatim, FIRST in document order so it
          is the first Tab stop. `<main>` needs no tabindex: Chromium moves the
          sequential-focus start point to the fragment target (probe 22b). */}
      <a href="#main" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to main content</a>
      <Sidebar />
      <main id="main" className="flex-1 overflow-y-auto">
        {/* Mobile-only top bar — brand chrome once the sidebar is gone. The
            shop-context switcher (VSA-03) rides here next to the wordmark; it is
            width-capped + truncating so it adds no horizontal overflow at 375px
            (MOBL-01, RESEARCH §7). */}
        <div
          data-testid="mobile-topbar"
          className="sticky top-0 z-40 flex h-14 items-center gap-2 border-b border-slate-200 bg-white px-4 md:hidden dark:border-slate-800 dark:bg-slate-900"
        >
          <Store className="h-6 w-6 shrink-0 text-blue-500" aria-hidden="true" />
          <span className="shrink-0 font-bold text-slate-900 dark:text-slate-100">J&apos;Toye</span>
          {/* Omitted on the per-tenant onboarding sub-tree, where the control
              acts on nothing (#450 item 1). The bar keeps its fixed h-14, so
              dropping it moves no other chrome. */}
          {showShopSwitcher && (
            <div className="ml-auto min-w-0 max-w-[55%]">
              <ShopSwitcher variant="topbar" />
            </div>
          )}
        </div>
        {/* THE content band. Phase 35 applies the Shell tier here, IN PLACE:
            the max-width class is swapped on the element that already existed
            and the tier is declared beside it, so no DOM node is added and no
            layout, motion or scroll-reveal behaviour moves. This is the tree's
            only width call site — all 21 dashboard routes inherit this line and
            none declares a width of its own (PATTERNS F-1) — which is why the
            tier is 1700px of measured peer evidence (lib/layout-widths.ts)
            rather than the 1400px that arrived with the shadcn scaffold and
            that nobody could account for.

            THE DISPLACED-GOODS LEDGER. The shadcn width utility this replaces
            supplied three declarations, and each is accounted for:
              - its auto margins were already duplicated by the mx-auto sitting
                on this same element, so nothing is lost;
              - its 2rem padding was ALREADY DEAD. That utility is a
                components-layer rule while p-4 and sm:p-8 are utilities,
                emitted later at equal specificity, so the padding utilities
                already won at every width — confirmed by reading the generated
                CSS ordering, not inferred;
              - its width:100% is equivalent here, because the parent main is a
                block container so this child fills by default, and Tailwind's
                preflight sets border-box. Corroborated by the tree itself:
                every marketing surface already centres correctly with a bare
                mx-auto plus a max-width and no width utility.
            The class name it replaces is deliberately not spelled out again —
            a comment satisfies a grep as readily as code does.

            Everything else on this element is unchanged, and each kept class is
            asserted by name in __tests__/dashboard-shell.test.tsx rather than
            assumed. */}
        <div
          data-width-tier="shell"
          className={cn(
            "mx-auto",
            WIDTH_TIER_CLASS.shell,
            "p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100"
          )}
        >
          {children}
          <footer className="mt-10 border-t border-slate-200 pt-4 dark:border-slate-800">
            <CompanyLegalLine />
          </footer>
        </div>
      </main>
      <MobileTabBar className="md:hidden" />
    </div>
    </ShopSwitcherProvider>
  )
}
