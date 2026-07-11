import type { Metadata } from "next"
import { OperatorPitch } from "@/components/marketing/operator-pitch"

export const metadata: Metadata = {
  title: "Operator pilot | J'Toye OaaS",
  description:
    "A supported direct-order and fulfilment pilot for established Nigerian and West African takeaway and catering operators in London.",
}

export default function ForOperatorsPage() {
  return <OperatorPitch />
}