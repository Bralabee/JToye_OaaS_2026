"use client"

import { useEffect } from "react"
import { Button } from "@/components/ui/button"

export default function StorefrontError({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    console.error("Storefront error:", error)
  }, [error])

  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center px-4 text-center">
      <h2 className="text-xl font-semibold text-slate-900">
        Something went wrong
      </h2>
      <p className="mt-2 text-sm text-slate-600">
        We couldn&apos;t load this page. Please try again.
      </p>
      <div className="mt-6 flex gap-3">
        <Button onClick={reset}>Try again</Button>
        <Button variant="outline" onClick={() => window.location.href = "/shop"}>
          Browse shops
        </Button>
      </div>
    </div>
  )
}
