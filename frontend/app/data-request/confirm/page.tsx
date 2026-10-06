import type { Metadata } from "next"
import { ConfirmClient } from "./confirm-client"

// RED stub (31.1-20): replaced in the GREEN commit.
export const metadata: Metadata = {}

export default function ConfirmPage() {
  return <ConfirmClient />
}
