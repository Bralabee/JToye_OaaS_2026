import type { Metadata } from "next"
import { PayClient } from "./pay-client"

// RED scaffold (plan 30-18 Task 1): no metadata and no shell yet. Replaced in the GREEN commit.
export const metadata: Metadata = {}

export default function PayPage() {
  return <PayClient />
}
