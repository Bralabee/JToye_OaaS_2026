"use client"

import { useEffect, useState } from "react"
import { useSearchParams } from "next/navigation"
import { BellOff, CheckCircle2, AlertTriangle, Loader2 } from "lucide-react"
import publicApiClient from "@/lib/public-api-client"

/**
 * Public, no-auth one-click unsubscribe confirmation (Surface C, COMMS-03).
 *
 * Reads `?tenant=&email=&category=&token=` from the URL and POSTs the token to
 * the no-auth backend (`/api/v1/public/unsubscribe`) via `publicApiClient` — the
 * SEPARATE public client (no Bearer, no redirect-on-401), NOT the authenticated
 * `apiClient`. The HMAC token is the sole authorization; there is no session.
 *
 * Security / privacy (T-22-07-01):
 *   - the `email` and `token` are sent to the API but are NEVER rendered into
 *     the visible body (nor the page meta — the page is noindex,nofollow and
 *     sitemap-excluded). Only the non-PII `category` label is shown.
 *   - the enclosing `page.tsx` carries `robots: { index:false, follow:false }`
 *     and is omitted from `app/sitemap.ts`.
 */

type State = "loading" | "unsubscribed" | "already_unsubscribed" | "invalid"

// Non-PII, human-friendly labels for the four notification categories
// (NotificationCategory enum, uppercase on the wire). Never the email/token.
const CATEGORY_LABEL: Record<string, string> = {
  ORDERS: "order",
  ONBOARDING: "onboarding",
  FINANCIAL: "financial",
  MARKETING: "marketing",
}

export function UnsubscribeContent() {
  const searchParams = useSearchParams()
  const [state, setState] = useState<State>("loading")

  // category is a non-PII enum — safe to render as a friendly label.
  const categoryRaw = (searchParams.get("category") || "").toUpperCase()
  const categoryLabel = CATEGORY_LABEL[categoryRaw] ?? "these"

  useEffect(() => {
    const tenant = searchParams.get("tenant")
    const email = searchParams.get("email")
    const category = searchParams.get("category")
    const token = searchParams.get("token")

    // A malformed link (missing any part) can never verify — show invalid
    // without hitting the network or leaking which part was missing.
    if (!tenant || !email || !category || !token) {
      setState("invalid")
      return
    }

    let cancelled = false
    publicApiClient
      .post<{ status: string }>("/api/v1/public/unsubscribe", null, {
        // Query params — the backend reads them via @RequestParam. The token
        // and email live in the request, never in the rendered page.
        params: { tenant, email, category, token },
      })
      .then((res) => {
        if (cancelled) return
        const status = res?.data?.status
        setState(
          status === "unsubscribed"
            ? "unsubscribed"
            : status === "already_unsubscribed"
              ? "already_unsubscribed"
              : "invalid"
        )
      })
      .catch(() => {
        if (!cancelled) setState("invalid")
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      {/* Brand row — orange "J" tile + wordmark (shop/layout.tsx). No nav. */}
      <div className="mb-6 flex items-center gap-2">
        <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-orange-500 text-lg font-bold text-white">
          J
        </span>
        <span className="text-lg font-semibold text-slate-900">J&apos;Toye</span>
      </div>

      <div className="rounded-xl border border-slate-100 bg-white p-6 shadow-sm">
        {state === "loading" && (
          <div className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-500" />
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">
              Updating your preferences…
            </h1>
          </div>
        )}

        {state === "unsubscribed" && (
          <div className="flex flex-col items-center py-4 text-center">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100">
              <BellOff className="h-6 w-6 text-emerald-600" />
            </span>
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">
              You&apos;re unsubscribed
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              You won&apos;t receive any more {categoryLabel} emails from this
              vendor. Changed your mind? Contact the vendor to opt back in.
            </p>
          </div>
        )}

        {state === "already_unsubscribed" && (
          <div className="flex flex-col items-center py-4 text-center">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-slate-100">
              <CheckCircle2 className="h-6 w-6 text-slate-500" />
            </span>
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">
              You&apos;re already unsubscribed
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              You&apos;ve already opted out of {categoryLabel} emails from this
              vendor. No further action needed.
            </p>
          </div>
        )}

        {state === "invalid" && (
          <div className="flex flex-col items-center py-4 text-center">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100">
              <AlertTriangle className="h-6 w-6 text-amber-600" />
            </span>
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">
              This link isn&apos;t valid
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              We couldn&apos;t verify this unsubscribe link — it may be incomplete
              or altered. Contact the vendor to update your email preferences.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

export default UnsubscribeContent
