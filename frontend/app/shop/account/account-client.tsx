"use client"

import { useRef, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Download, Loader2, MailCheck, Trash2, UserRound } from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

/**
 * The interactive half of `/shop/account` (#838, D-04, 31.1-26).
 *
 * ONE PATH. Both buttons POST to the existing public intake with the signed-in address and a request
 * type, exactly what an anonymous person writing to us would lodge. The intake answers every valid
 * request with the same opaque 202 and emails a confirmation link; nothing is actioned until that
 * link is used (31.1-20's /data-request/confirm). So this page never needs the access token, and a
 * session that lapses mid-visit changes nothing.
 *
 * WHAT THE PAGE MAY SAY. The success copy is a constant and the API's answer is never rendered: the
 * page must not reveal whether any shop holds data for this address, or which (T-31.1-90). The
 * intake's own response is constant for the same reason (V62); this keeps the page from becoming the
 * leak the endpoint was designed not to be.
 *
 * ONE REQUEST PER PRESS. Each action owns an in-flight ref (a double press inside one frame cannot
 * race a re-render) and an Idempotency-Key minted on its FIRST press and kept. A retry after a
 * failure therefore re-sends the same key: if the first POST did reach the server, the intake replays
 * its answer rather than queueing a second request and a second email (T-31.1-91). The two actions
 * have separate keys, because they are separate requests — pressing both lodges both.
 *
 * Nothing is stored client-side: no key in lib/client-storage-keys.ts belongs to this page.
 */

const INTAKE_PATH = "/api/v1/public/gdpr/dsar"

type RequestType = "ACCESS" | "ERASURE"
type ActionState = "idle" | "busy" | "lodged" | "failed" | "limited"

export const ACCOUNT_DOWNLOAD_COPY = {
  heading: "Get a copy of your data",
  body:
    "We will gather the personal data held about you by every shop you have ordered from on J'Toye " +
    "and email you a link to download it. First we email you a link to confirm the request. " +
    "Nothing happens until you confirm it.",
  button: "Download my data",
}

export const ACCOUNT_DELETE_COPY = {
  heading: "Delete your account",
  body:
    "Remove your details from your orders at every shop you have ordered from, and delete your " +
    "J'Toye sign-in account. We will ask you to check what this means before anything is sent.",
  button: "Delete my account",
}

/** What deletion does, stated before anything is lodged. Each point is a fact the erasure performs. */
export const ACCOUNT_DELETE_CONSEQUENCES_COPY = {
  title: "Delete your account?",
  intro: "If you go ahead, this is what will happen:",
  points: [
    "Your name, email address, phone number and delivery address are removed from your orders and reviews at every shop you have ordered from.",
    "Your J'Toye sign-in account is deleted, so you will no longer be able to sign in with it.",
    "The shops keep their order and tax records, which they must keep for tax purposes, but without your details.",
    "Nothing happens until you confirm the request from the email we send you.",
  ],
  confirm: "Yes, delete my account",
  cancel: "Keep my account",
}

/** The same words whatever the intake answers: they say what happens next, never what is held. */
export const ACCOUNT_REQUEST_LODGED_COPY = {
  heading: "Check your email to confirm",
  body:
    "We have emailed you a link to confirm this request. We will not act on it until you open " +
    "the link and confirm it.",
}

export const ACCOUNT_REQUEST_FAILED_COPY =
  "Something went wrong and we could not confirm that your request reached us. Please try again. " +
  "Trying again will not create a second request."

export const ACCOUNT_REQUEST_LIMITED_COPY =
  "Too many requests have been made recently. Please wait a while and try again."

export const ACCOUNT_NO_EMAIL_COPY =
  "We can't see an email address on your sign-in, so we can't send you the confirmation link these " +
  "requests need. Please sign out and sign in again, or contact us using the details in our privacy notice."

/** Unguessable per-action key: crypto.randomUUID, else a v4 UUID from getRandomValues. */
function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID()
  }
  const buf = new Uint8Array(16)
  crypto.getRandomValues(buf)
  buf[6] = (buf[6] & 0x0f) | 0x40
  buf[8] = (buf[8] & 0x3f) | 0x80
  const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function statusOf(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

export function AccountClient({ email }: { email: string | null }) {
  const [states, setStates] = useState<Record<RequestType, ActionState>>({
    ACCESS: "idle",
    ERASURE: "idle",
  })
  const [deleteOpen, setDeleteOpen] = useState(false)
  const inFlight = useRef<Record<RequestType, boolean>>({ ACCESS: false, ERASURE: false })
  const keys = useRef<Record<RequestType, string | null>>({ ACCESS: null, ERASURE: null })

  const setState = (type: RequestType, state: ActionState) =>
    setStates((prev) => ({ ...prev, [type]: state }))

  async function lodge(type: RequestType): Promise<boolean> {
    if (!email || inFlight.current[type]) return false
    inFlight.current[type] = true
    setState(type, "busy")
    try {
      let key = keys.current[type]
      if (!key) {
        key = newIdempotencyKey()
        keys.current[type] = key
      }
      await publicApiClient.post(
        INTAKE_PATH,
        { email, requestType: type },
        { headers: { "Idempotency-Key": key } }
      )
      setState(type, "lodged")
      return true
    } catch (err) {
      setState(type, statusOf(err) === 429 ? "limited" : "failed")
      return false
    } finally {
      inFlight.current[type] = false
    }
  }

  async function confirmDelete() {
    const lodged = await lodge("ERASURE")
    if (lodged) setDeleteOpen(false)
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      <div className="mb-6 flex items-center gap-3">
        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-orange-50">
          <UserRound className="h-5 w-5 text-orange-700" aria-hidden="true" />
        </span>
        <h1 className="text-2xl font-semibold leading-tight text-slate-900">My account</h1>
      </div>

      {!email ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <p className="text-sm leading-relaxed text-slate-600">{ACCOUNT_NO_EMAIL_COPY}</p>
          <Link
            href="/legal/privacy"
            className="mt-4 inline-flex min-h-11 items-center text-sm font-medium text-slate-700 underline underline-offset-4 hover:text-slate-900"
          >
            Read our privacy notice
          </Link>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <label htmlFor="account-email" className="text-sm font-medium text-slate-700">
              Email address
            </label>
            <input
              id="account-email"
              type="email"
              value={email}
              readOnly
              aria-describedby="account-email-hint"
              className="mt-1.5 w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700"
            />
            <p id="account-email-hint" className="mt-2 text-xs text-slate-600">
              Requests are made for this address, and the confirmation email goes to it.
            </p>
          </div>

          <section
            aria-labelledby="account-download-heading"
            className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
          >
            <h2 id="account-download-heading" className="text-lg font-semibold text-slate-900">
              {ACCOUNT_DOWNLOAD_COPY.heading}
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">{ACCOUNT_DOWNLOAD_COPY.body}</p>
            <ActionOutcome state={states.ACCESS} />
            {states.ACCESS !== "lodged" && (
              <button
                type="button"
                onClick={() => void lodge("ACCESS")}
                disabled={states.ACCESS === "busy"}
                aria-busy={states.ACCESS === "busy"}
                className="mt-5 inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"
              >
                {states.ACCESS === "busy" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                ) : (
                  <Download className="h-4 w-4" aria-hidden="true" />
                )}
                {ACCOUNT_DOWNLOAD_COPY.button}
              </button>
            )}
          </section>

          <section
            aria-labelledby="account-delete-heading"
            className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
          >
            <h2 id="account-delete-heading" className="text-lg font-semibold text-slate-900">
              {ACCOUNT_DELETE_COPY.heading}
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">{ACCOUNT_DELETE_COPY.body}</p>
            {/* The outcome sits outside the dialog too: after a failure the dialog is closed by the
                person, and the next press of "Delete my account" reopens it with the same key. */}
            {!deleteOpen && <ActionOutcome state={states.ERASURE} />}
            {states.ERASURE !== "lodged" && (
              <button
                type="button"
                onClick={() => setDeleteOpen(true)}
                className="mt-5 inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-red-700 bg-white px-5 py-2.5 text-sm font-semibold text-red-700 transition-colors hover:bg-red-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700"
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
                {ACCOUNT_DELETE_COPY.button}
              </button>
            )}
          </section>

          <p className="text-xs leading-relaxed text-slate-600">
            How we handle your data is set out in our{" "}
            <Link href="/legal/privacy" className="underline underline-offset-4 hover:text-slate-900">
              privacy notice
            </Link>
            .
          </p>
        </div>
      )}

      <Dialog
        open={deleteOpen}
        onOpenChange={(open) => {
          // Never close underneath an in-flight request: the person must see how it ended.
          if (!open && inFlight.current.ERASURE) return
          setDeleteOpen(open)
        }}
      >
        <DialogContent className="max-w-md bg-white">
          <DialogHeader>
            <DialogTitle className="text-slate-900">{ACCOUNT_DELETE_CONSEQUENCES_COPY.title}</DialogTitle>
            <DialogDescription className="text-slate-600">
              {ACCOUNT_DELETE_CONSEQUENCES_COPY.intro}
            </DialogDescription>
          </DialogHeader>
          <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-slate-700">
            {ACCOUNT_DELETE_CONSEQUENCES_COPY.points.map((point) => (
              <li key={point}>{point}</li>
            ))}
          </ul>
          <ActionOutcome state={states.ERASURE === "lodged" ? "idle" : states.ERASURE} />
          <DialogFooter className="gap-2 sm:gap-0">
            <button
              type="button"
              onClick={() => setDeleteOpen(false)}
              disabled={states.ERASURE === "busy"}
              className="inline-flex min-h-11 items-center justify-center rounded-lg border border-slate-300 bg-white px-5 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500 disabled:opacity-70"
            >
              {ACCOUNT_DELETE_CONSEQUENCES_COPY.cancel}
            </button>
            <button
              type="button"
              onClick={() => void confirmDelete()}
              disabled={states.ERASURE === "busy"}
              aria-busy={states.ERASURE === "busy"}
              className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-red-700 px-5 py-2.5 text-sm font-semibold text-white hover:bg-red-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700 disabled:cursor-not-allowed disabled:opacity-70"
            >
              {states.ERASURE === "busy" && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              {ACCOUNT_DELETE_CONSEQUENCES_COPY.confirm}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function ActionOutcome({ state }: { state: ActionState }) {
  if (state === "lodged") {
    return (
      <div role="status" className="mt-4 flex gap-3 rounded-lg bg-emerald-50 p-3">
        <MailCheck className="mt-0.5 h-5 w-5 shrink-0 text-emerald-700" aria-hidden="true" />
        <div>
          <p className="text-sm font-semibold text-emerald-900">{ACCOUNT_REQUEST_LODGED_COPY.heading}</p>
          <p className="mt-1 text-sm text-emerald-900">{ACCOUNT_REQUEST_LODGED_COPY.body}</p>
        </div>
      </div>
    )
  }
  if (state === "failed" || state === "limited") {
    return (
      <p role="alert" className="mt-4 flex gap-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        {state === "limited" ? ACCOUNT_REQUEST_LIMITED_COPY : ACCOUNT_REQUEST_FAILED_COPY}
      </p>
    )
  }
  return null
}

export default AccountClient
