"use client"

import { AlertCircle } from "lucide-react"
import { Button } from "@/components/ui/button"

interface LoadErrorPanelProps {
  /** What failed to load, e.g. "products" — used only in the heading. */
  subject: string
  /** Human-readable detail from `lib/human-error.ts`. */
  message: string
  onRetry: () => void
}

/**
 * QA-council F2 (FEB-1 / A11Y-2 / A11Y-8): the error-state vs empty-state
 * discrimination panel shared by the vendor dashboard's list pages.
 *
 * A list that fails to load (429, network error, 5xx) must never render the
 * SAME "No <thing> yet" empty state a vendor with genuinely zero rows sees —
 * that silently tells them their data is gone. Callers render this BEFORE the
 * zero-length empty check, keyed off a `loadFailed` flag rather than list
 * length, because the fetch catch blocks that feed it deliberately do not
 * reset the list to `[]` (so a transient failure never wipes what was already
 * on screen).
 *
 * `role="alert"` so assistive tech announces the failure without the vendor
 * having to find it visually — this is the surface A11Y-2/A11Y-8 named.
 */
export function LoadErrorPanel({ subject, message, onRetry }: LoadErrorPanelProps) {
  return (
    <div
      role="alert"
      data-testid="load-error-panel"
      className="flex flex-col items-center justify-center py-12 text-center"
    >
      <AlertCircle className="mb-4 h-12 w-12 text-red-400" aria-hidden="true" />
      <h3 className="mb-2 text-lg font-semibold text-slate-900">
        Couldn&apos;t load {subject}
      </h3>
      <p className="mb-4 max-w-sm text-sm text-slate-500">{message}</p>
      <Button onClick={onRetry} variant="outline">
        Try again
      </Button>
    </div>
  )
}
