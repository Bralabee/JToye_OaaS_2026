"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { AlertTriangle, CheckCircle2, Loader2, ShieldCheck } from "lucide-react"
import publicApiClient from "@/lib/public-api-client"

/**
 * The client island of `/data-request/confirm` (#839, D-04, 31.1-20).
 *
 * THE TOKEN ARMS A REQUEST, and the request may be an erasure, so this
 * component is mostly about WHEN it is spent:
 *
 *   1. It arrives in the URL fragment (`#token=…`), which a browser never sends
 *      to any server, and is read once on mount.
 *   2. It is removed from the address bar immediately (`history.replaceState`),
 *      so it does not survive in history, a copied URL or a screenshot.
 *   3. It lives in memory only (a ref). Nothing is written to localStorage or
 *      sessionStorage — this page registers no client-persisted key
 *      (lib/client-storage-keys.ts).
 *   4. It is POSTed in a JSON body only when the person presses "Confirm my
 *      request". A mail scanner or link prefetcher that renders this page
 *      confirms nothing (threat T-31.1-71) — otherwise an erasure lodged
 *      against somebody else's address could be confirmed by their own mail
 *      filter.
 *
 * The outcome is chosen from the API's `status` (verified | already_verified |
 * invalid), never from its English `detail`, and the copy lives in
 * `CONFIRM_COPY` below. Anything else the API might ever answer is treated as
 * invalid: an unknown answer must never read as "confirmed". A failure that is
 * not an answer (network, 5xx) keeps the token and offers a retry — the server
 * changed nothing, so the link is not spent.
 */

const VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify"

type Outcome = "verified" | "already_verified" | "invalid"

type Copy = { heading: string; body: string }

/** The words for each answer the API can give. One source, so a test can pin them. */
export const CONFIRM_COPY: Record<Outcome, Copy> = {
  verified: {
    heading: "Your request is confirmed",
    body: "Thank you. We will now act on your request, and we will email you when it has been done.",
  },
  already_verified: {
    heading: "This request is already confirmed",
    body: "You do not need to do anything else. We will email you when your request has been dealt with.",
  },
  invalid: {
    heading: "This link can't be used",
    body: "This confirmation link is not valid, or it has expired. Nothing has been changed. You can make a new request.",
  },
}

type Phase =
  | { kind: "checking" }
  | { kind: "ready" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "answered"; outcome: Outcome }

function toOutcome(status: unknown): Outcome {
  return status === "verified" || status === "already_verified" ? status : "invalid"
}

export function ConfirmClient() {
  const tokenRef = useRef<string | null>(null)
  const inFlight = useRef(false)
  const [phase, setPhase] = useState<Phase>({ kind: "checking" })

  useEffect(() => {
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""))
    const token = fragment.get("token")
    if (window.location.hash) {
      // Drop the fragment before anything else can observe it.
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname + window.location.search
      )
    }
    // Only a token actually read may replace the held one: under Strict Mode
    // (next dev) this effect runs twice, and the second run sees the fragment
    // the first run already stripped.
    if (token && token.trim()) tokenRef.current = token.trim()
    // The fragment exists only in the browser, so which state to show can only be
    // decided after mount. No request is made here.
    setPhase(tokenRef.current ? { kind: "ready" } : { kind: "answered", outcome: "invalid" })
  }, [])

  async function confirm() {
    const token = tokenRef.current
    if (!token || inFlight.current) return
    inFlight.current = true
    setPhase({ kind: "loading" })
    try {
      const res = await publicApiClient.post<{ status?: string }>(VERIFY_PATH, { token })
      tokenRef.current = null
      setPhase({ kind: "answered", outcome: toOutcome(res.data?.status) })
    } catch {
      setPhase({ kind: "failed" })
    } finally {
      inFlight.current = false
    }
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        {phase.kind === "checking" && (
          <div className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-600" aria-hidden="true" />
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">Checking your link…</h1>
          </div>
        )}

        {(phase.kind === "ready" || phase.kind === "loading" || phase.kind === "failed") && (
          <div className="flex flex-col py-2">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-orange-50">
              <ShieldCheck className="h-6 w-6 text-orange-700" aria-hidden="true" />
            </span>
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">Confirm your data request</h1>
            <p className="mt-3 text-sm leading-relaxed text-slate-600">
              Somebody asked us to act on the personal data held for your email address. If that was
              you, press the button below. We will not act on the request until you do.
            </p>
            <p className="mt-3 text-sm leading-relaxed text-slate-600">
              If you did not make this request, close this page. Nothing will happen and no data
              will be changed.
            </p>
            {phase.kind === "failed" && (
              <p role="alert" className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
                Something went wrong on our side and your request has not been confirmed. Your link
                still works — please try again.
              </p>
            )}
            <button
              type="button"
              onClick={confirm}
              disabled={phase.kind === "loading"}
              className="mt-6 inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"
            >
              {phase.kind === "loading" && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              Confirm my request
            </button>
          </div>
        )}

        {phase.kind === "answered" && phase.outcome !== "invalid" && (
          <Confirmed copy={CONFIRM_COPY[phase.outcome]} />
        )}

        {phase.kind === "answered" && phase.outcome === "invalid" && <Invalid />}
      </div>
    </div>
  )
}

function Confirmed({ copy }: { copy: Copy }) {
  return (
    <div className="flex flex-col py-2">
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50">
        <CheckCircle2 className="h-6 w-6 text-emerald-700" aria-hidden="true" />
      </span>
      <h1 className="text-2xl font-semibold leading-tight text-slate-900">{copy.heading}</h1>
      <p className="mt-3 text-sm leading-relaxed text-slate-600">{copy.body}</p>
      <div className="mt-6">
        <Link
          href="/legal/privacy"
          className="inline-flex min-h-11 items-center text-sm font-medium text-slate-700 underline underline-offset-4 hover:text-slate-900"
        >
          Read our privacy notice
        </Link>
      </div>
    </div>
  )
}

function Invalid() {
  const copy = CONFIRM_COPY.invalid
  return (
    <div className="flex flex-col py-2">
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100">
        <AlertTriangle className="h-6 w-6 text-amber-700" aria-hidden="true" />
      </span>
      <h1 className="text-2xl font-semibold leading-tight text-slate-900">{copy.heading}</h1>
      <p className="mt-3 text-sm leading-relaxed text-slate-600">{copy.body}</p>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center">
        <Link
          href="/shop/account"
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700"
        >
          Make a new request
        </Link>
        <Link
          href="/legal/privacy"
          className="inline-flex min-h-11 items-center text-sm font-medium text-slate-700 underline underline-offset-4 hover:text-slate-900"
        >
          Read our privacy notice
        </Link>
      </div>
    </div>
  )
}

export default ConfirmClient
