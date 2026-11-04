import type { Metadata } from "next"
import { openGraphWithDefaultImage } from "@/lib/og-image"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Operator pilot | J'Toye OaaS",
  description:
    "A direct-order and scheduled-fulfilment pilot for established Nigerian and West African operators in London, running the daily takeaway counter and the weekend batch and catering run from one kitchen.",
  // FE-5: canonical + Open Graph — see the equivalent note on app/page.tsx.
  alternates: { canonical: "/for-operators" },
  // SW-CLR-08: a page's own openGraph replaces the root's, so the default share image is added here.
  openGraph: openGraphWithDefaultImage({
    title: "Operator pilot | J'Toye OaaS",
    description:
      "A direct-order and scheduled-fulfilment pilot for established Nigerian and West African operators in London, running the daily takeaway counter and the weekend batch and catering run from one kitchen.",
    url: "/for-operators",
    type: "website",
  }),
}

export default function ForOperatorsPage() {
  return (
    <PublicShell>
      <OperatorPitch />
    </PublicShell>
  )
}