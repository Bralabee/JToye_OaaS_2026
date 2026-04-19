"use client"

import { useEffect, useState, Suspense } from "react"
import Link from "next/link"
import { useSearchParams, useRouter } from "next/navigation"
import { Loader2 } from "lucide-react"
import { handleCallback, getAuthReturnUrl } from "@/lib/customer-auth"
import { Button } from "@/components/ui/button"

function CallbackContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const code = searchParams.get("code")
    if (!code) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- single-shot error hydration on mount; no cascade risk
      setError("No authorization code received.")
      return
    }

    handleCallback(code).then((profile) => {
      if (profile) {
        const returnTo = getAuthReturnUrl()
        router.replace(returnTo)
      } else {
        setError("Authentication failed. Please try again.")
      }
    })
  }, [searchParams, router])

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-surface-canvas">
        <div className="text-center space-y-4">
          <p className="text-sm text-danger font-sans">{error}</p>
          <Button asChild variant="primary" size="sm">
            <Link href="/shop">Back to shops</Link>
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface-canvas">
      <div className="text-center">
        <Loader2
          className="mx-auto h-8 w-8 animate-spin text-brand-primary motion-reduce:animate-none"
          aria-label="Signing in"
        />
        <p className="mt-3 text-sm text-ink-secondary font-sans">Signing you in…</p>
      </div>
    </div>
  )
}

export default function AuthCallbackPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-surface-canvas">
          <Loader2
            className="h-8 w-8 animate-spin text-brand-primary motion-reduce:animate-none"
            aria-label="Loading"
          />
        </div>
      }
    >
      <CallbackContent />
    </Suspense>
  )
}
