import type { Metadata } from "next"
import { DownloadClient } from "./download-client"

// RED stub (31.1-17 Task 1): the route exists so the test can import it; the behaviour does not.
export const metadata: Metadata = {}

export default function DownloadPage() {
  return <DownloadClient />
}
