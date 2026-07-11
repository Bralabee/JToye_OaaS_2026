import type { Metadata } from "next"
import { BusinessModelGuide } from "@/components/marketing/business-model-guide"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Business model guide | J'Toye OaaS",
  description:
    "The public business-model decision guide for J'Toye OaaS: boundaries, evidence, economics and validation gates.",
}

export default function BusinessModelGuidePage() {
  return (
    <PublicShell>
      <BusinessModelGuide />
    </PublicShell>
  )
}