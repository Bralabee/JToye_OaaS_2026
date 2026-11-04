import type { Metadata } from "next"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import { PublicShell } from "@/components/public/public-shell"
import { LAUNCH_AREA } from "@/lib/positioning"

// OWNER RULING 2026-11-04 (positioning, issue 878 UXT-119 and SW-CLR-03): UK-wide,
// launching first in Birmingham and the West Midlands. The description used to say
// "established Nigerian and West African operators in London"; it is now built from
// lib/positioning.ts, the same constant the page body, /about and /contact read.
const DESCRIPTION = `A direct-order and scheduled-fulfilment pilot for established food businesses across the UK, launching first in ${LAUNCH_AREA}, running the daily takeaway counter and the weekend batch and catering run from one kitchen.`

export const metadata: Metadata = {
  title: "Operator pilot | J'Toye OaaS",
  description: DESCRIPTION,
  // FE-5: canonical + Open Graph — see the equivalent note on app/page.tsx.
  alternates: { canonical: "/for-operators" },
  openGraph: {
    title: "Operator pilot | J'Toye OaaS",
    description: DESCRIPTION,
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
