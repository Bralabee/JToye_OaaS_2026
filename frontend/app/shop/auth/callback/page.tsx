"use client"

import { useEffect, useRef, useState } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { handleCallback, getAuthReturnUrl, hasPendingLogin, customerLogin } from "@/lib/customer-auth"
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
/**
 * A code arrived but this tab never started a sign-in: there is no PKCE
 * verifier in its sessionStorage, so the code cannot be exchanged. This is the
 * normal ending of a password-reset or email-verification link, which an email
 * client opens in a new tab or browser (SW-JRD-05 / SW-SAM-13). The password the
 * person just set IS saved by then; what they need is a fresh sign-in, which
 * usually completes without a form because Keycloak has just opened a session.
 */
const OTHER_TAB = "This sign-in finished in a different tab or browser."
const OTHER_TAB_DETAIL = "If you just set a new password, it has been saved. Sign in to continue."

function CallbackContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<string | null>(null)

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
    // No verifier means this tab never started the sign-in, so there is
    // nothing to exchange the code with; the code is left unredeemed.
    const pending = hasPendingLogin()
    const exchange = pending ? handleCallback(code, searchParams.get("state")) : Promise.resolve(null)
    exchange.then((profile) => {
      if (profile) {
        const returnTo = getAuthReturnUrl()
        router.replace(returnTo)
      } else if (pending) {
        setError(AUTH_FAILED)
      } else {
        setError(OTHER_TAB)
        setDetail(OTHER_TAB_DETAIL)
      }
    })
  }, [code, searchParams, router])

  const message = code ? error : NO_CODE

  if (message) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <p className={detail ? "text-sm text-slate-800" : "text-sm text-red-600"}>{message}</p>
          {code && detail ? <p className="mt-1 text-sm text-slate-600">{detail}</p> : null}
          {code ? (
            <button
              type="button"
              onClick={() => void customerLogin()}
              className="mt-4 block w-full rounded-md bg-amber-700 px-4 py-2 text-sm font-medium text-white hover:bg-amber-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-800"
            >
              Sign in
            </button>
          ) : null}
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
