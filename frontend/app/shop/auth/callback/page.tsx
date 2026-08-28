"use client"

import { useEffect, useRef, useState } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { handleCallback, getAuthReturnUrl } from "@/lib/customer-auth"
import { Suspense } from "react"

/**
 * The two strings a shopper can be shown here are module CONSTANTS, never
 * built from a query parameter (T-34-04-01). `?code=` and `?state=` arrive
 * from a redirect the browser followed, so they are untrusted input; a
 * template literal here would be a reflected-content sink on the one page a
 * failed OAuth hop always lands on.
 */
const NO_CODE = "No authorization code received."
const AUTH_FAILED = "Authentication failed. Please try again."

function CallbackContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)

  /**
   * Whether there is an authorization code to exchange is knowable DURING
   * RENDER — it is a query parameter, not the result of any asynchronous work.
   * Reading it here rather than writing it to state from a mount effect is what
   * removes the last `react-hooks/set-state-in-effect` suppression on this page
   * (#202), and it is not merely a lint concession: `dynamic = "force-dynamic"`
   * means this page is server-rendered on every request, and an effect never
   * runs on the server. Under the old shape the served bytes for a code-less
   * callback were a spinner that could only resolve after hydration.
   */
  const code = searchParams.get("code")

  /**
   * An authorization code is single-use. React 18/19 StrictMode mounts,
   * unmounts and remounts every effect in development, and a re-render with a
   * new `searchParams` identity would do the same in production — either way a
   * second exchange presents an already-redeemed code, the IdP rejects it, and
   * the shopper is bounced to AUTH_FAILED having actually signed in
   * successfully the first time. That is a real failure mode (T-34-04-03), not
   * a cosmetic double-render, so the guard is a ref rather than a dependency
   * array tweak.
   */
  const exchangeStarted = useRef(false)

  useEffect(() => {
    if (!code) return
    if (exchangeStarted.current) return
    exchangeStarted.current = true

    // The `setError` below lives in a promise continuation, which the ESLint
    // rule deliberately does not flag (measured rule shape B). The asynchronous
    // exchange genuinely belongs in an effect and stays here.
    handleCallback(code, searchParams.get("state")).then((profile) => {
      if (profile) {
        const returnTo = getAuthReturnUrl()
        router.replace(returnTo)
      } else {
        setError(AUTH_FAILED)
      }
    })
  }, [code, searchParams, router])

  const message = code ? error : NO_CODE

  if (message) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <p className="text-sm text-red-600">{message}</p>
          <Link href="/shop" className="mt-4 inline-block text-sm text-amber-700 hover:text-amber-800">
            Back to shop
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen flex items-center justify-center">
      <div className="text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-amber-500" />
        <p className="mt-3 text-sm text-slate-600">Signing you in...</p>
      </div>
    </div>
  )
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="min-h-screen flex items-center justify-center"><Loader2 className="h-8 w-8 animate-spin text-amber-500" /></div>}>
      <CallbackContent />
    </Suspense>
  )
}
