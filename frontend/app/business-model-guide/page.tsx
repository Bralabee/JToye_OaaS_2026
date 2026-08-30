import type { Metadata } from "next"
import { BusinessModelGuide } from "@/components/marketing/business-model-guide"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Business model guide | J'Toye OaaS",
  description:
    "The public business-model decision guide for J'Toye OaaS: boundaries, evidence, economics and validation gates.",
  // FE-5: canonical + Open Graph — see the equivalent note on app/page.tsx.
  // type "article" matches the sibling long-form analysis page (/competitive).
  alternates: { canonical: "/business-model-guide" },
  openGraph: {
    title: "Business model guide | J'Toye OaaS",
    description:
      "The public business-model decision guide for J'Toye OaaS: boundaries, evidence, economics and validation gates.",
    url: "/business-model-guide",
    type: "article",
  },
}

export default function BusinessModelGuidePage() {
  return (
    <PublicShell>
      <BusinessModelGuide />
    </PublicShell>
  )
}