import type { Metadata } from "next"
import type { ReactNode } from "react"

/**
 * Title-only segment layout (the #450 item 5c convention, as `dashboard/webhooks/layout.tsx`).
 *
 * The page is a Client Component and `metadata` is server-only, so the tab title arrives through
 * this additive layout, which renders nothing of its own. The name matches the sidebar entry.
 */
export const metadata: Metadata = {
  title: "Developers — J'Toye OaaS",
}

export default function DevelopersLayout({ children }: { children: ReactNode }) {
  return children
}
