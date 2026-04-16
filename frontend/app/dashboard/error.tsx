"use client"

import { useEffect } from "react"
import { Button } from "@/components/ui/button"

export default function DashboardError({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    console.error("Dashboard error:", error)
  }, [error])

  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center px-4 text-center">
      <h2 className="text-xl font-semibold text-slate-900">
        Dashboard error
      </h2>
      <p className="mt-2 text-sm text-slate-500">
        Something went wrong loading this page. Your data is safe.
      </p>
      {error.digest && (
        <p className="mt-1 text-xs text-slate-400">
          Error ID: {error.digest}
        </p>
      )}
      <div className="mt-6 flex gap-3">
        <Button onClick={reset}>Try again</Button>
        <Button variant="outline" onClick={() => window.location.href = "/dashboard"}>
          Back to dashboard
        </Button>
      </div>
    </div>
  )
}
