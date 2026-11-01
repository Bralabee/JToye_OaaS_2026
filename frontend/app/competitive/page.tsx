import type { Metadata } from "next"
import { CompetitiveTeardown } from "@/components/marketing/competitive-teardown"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "How we compare — Flipdish vs J'Toye | J'Toye OaaS",
  description:
    "An evidence-based competitive teardown of Flipdish vs J'Toye OaaS: 29 features across 8 categories, a coverage radar, and a filterable feature matrix. J'Toye wins the UK-compliance, marketplace-payments and agent-ready wedge.",
  alternates: { canonical: "/competitive" },
  // SW-CLR-05 (swarm epic #964): an internal strategy page, unlinked from the
  // footer. Published, but not for search engines. The sitemap entry is Phase
  // 30's file (app/sitemap.ts) and is a recorded follow-up.
  robots: { index: false, follow: false },
  openGraph: {
    title: "How we compare — Flipdish vs J'Toye",
    description:
      "Evidence-based feature teardown of Flipdish vs J'Toye OaaS: coverage radar + filterable matrix. Compete on the compliance + marketplace-engine + agent-ready wedge.",
    url: "/competitive",
    type: "article",
  },
}

export default function CompetitivePage() {
  return (
    <PublicShell>
      <CompetitiveTeardown />
    </PublicShell>
  )
}
