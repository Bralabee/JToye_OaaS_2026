import type { Metadata } from "next"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Operator pilot | J'Toye OaaS",
  description:
    "A supported direct-order and fulfilment pilot for established Nigerian and West African takeaway and catering operators in London.",
  // FE-5: canonical + Open Graph — see the equivalent note on app/page.tsx.
  alternates: { canonical: "/for-operators" },
  openGraph: {
    title: "Operator pilot | J'Toye OaaS",
    description:
      "A supported direct-order and fulfilment pilot for established Nigerian and West African takeaway and catering operators in London.",
    url: "/for-operators",
    type: "website",
  },
}

export default function ForOperatorsPage() {
  return (
    <PublicShell>
      <OperatorPitch />
    </PublicShell>
  )
}