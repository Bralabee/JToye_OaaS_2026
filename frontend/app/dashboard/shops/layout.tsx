import type { Metadata } from "next"
import type { ReactNode } from "react"

/**
 * Title-only segment layout (#450 item 5c).
 *
 * Every dashboard route rendered the root layout's "J'Toye OaaS - Multi-Tenant
 * Order Management", so a vendor with several tabs open could not tell them
 * apart and browser history was a wall of identical entries. A page here cannot
 * fix that itself — these are Client Components and `metadata` is server-only —
 * so the title arrives through an additive layout that renders nothing.
 *
 * The name matches the sidebar nav entry for this segment, which is the label
 * the vendor already navigates by. Titles are absolute rather than composed from
 * a parent `title.template`, because two pages under /dashboard/payments/connect
 * already ship their own finished titles and a template would suffix them twice.
 */
export const metadata: Metadata = {
  title: "Shops — J'Toye OaaS",
}

export default function ShopsLayout({ children }: { children: ReactNode }) {
  return children
}
