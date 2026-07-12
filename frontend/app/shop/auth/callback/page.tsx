"use client"

import { useEffect, useState } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { handleCallback, getAuthReturnUrl } from "@/lib/customer-auth"
import { Suspense } from "react"

function CallbackContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const code = searchParams.get("code")
    if (!code) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
      setError("No authorization code received.")
      return
    }

    handleCallback(code, searchParams.get("state")).then((profile) => {
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
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <p className="text-sm text-red-600">{error}</p>
          <Link href="/shop" className="mt-4 inline-block text-sm text-orange-600 hover:text-orange-700">
            Back to shop
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen flex items-center justify-center">
      <div className="text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-orange-500" />
        <p className="mt-3 text-sm text-slate-500">Signing you in...</p>
      </div>
    </div>
  )
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="min-h-screen flex items-center justify-center"><Loader2 className="h-8 w-8 animate-spin text-orange-500" /></div>}>
      <CallbackContent />
    </Suspense>
  )
}
