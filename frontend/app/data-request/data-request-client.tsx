"use client"

import { useRef, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Loader2, MailCheck, ShieldCheck } from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import {
  DSAR_INTAKE_PATH,
  httpStatusOf,
  newIdempotencyKey,
  type DsarRequestType,
} from "@/lib/dsar-intake"
import {
  ACCOUNT_REQUEST_FAILED_COPY,
  ACCOUNT_REQUEST_LIMITED_COPY,
} from "@/app/shop/account/account-client"

/**
 * The guest data-request form (SW-SAM-03). See `page.tsx` for why it exists.
 *
 * WHAT THE PAGE MAY SAY: the success copy is the same whatever the intake answers, and names only
 * what the person typed. It never says whether any shop holds data for the address.
 *
 * ONE REQUEST PER PRESS: an in-flight ref stops a double press, and the Idempotency-Key is kept for
 * as long as the address and type stay the same, so a retry after a failure replays the first
 * request instead of queueing a second one. Changing either makes a new request with a new key
 * (reusing a key with a different body is refused by the intake, 422).
 */

type FormState = "idle" | "busy" | "lodged" | "failed" | "limited"

export const DATA_REQUEST_COPY = {
  heading: "Make a data request",
  intro:
    "You can ask for a copy of the personal data held about you, or for it to be erased, by J'Toye " +
    "and by every shop you have ordered from on J'Toye. You do not need an account.",
  emailLabel: "Your email address",
  emailHint: "Use the address you gave when you ordered. We send the confirmation link to it.",
  typeLegend: "What would you like us to do?",
  access: {
    label: "Send me a copy of my data",
    hint: "We gather what every shop holds about you and email you a link to download it.",
  },
  erasure: {
    label: "Erase my details",
    hint:
      "Your name, email address, phone number and delivery address are removed from your orders and " +
      "reviews at every shop. The shops keep their order and tax records, without your details. If you " +
      "have a J'Toye sign-in account, it is deleted too.",
  },
  submit: "Send confirmation email",
  confirmNote: "Nothing happens until you open the link we email you and confirm the request.",
  lodgedHeading: "Check your email to confirm",
  lodgedBody: (email: string) =>
    `We have emailed a link to ${email} to confirm this request. Nothing happens until you open it ` +
    "and confirm. If it does not arrive, check the address and send the request again.",
  otherRights:
    "To have your details corrected, or to object to how they are used, use the contact routes in our",
  invalidEmail: "Enter an email address, like name@example.com.",
}

function looksLikeEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

export function DataRequestClient() {
  const [email, setEmail] = useState("")
  const [type, setType] = useState<DsarRequestType>("ACCESS")
  const [state, setState] = useState<FormState>("idle")
  const [emailError, setEmailError] = useState<string | null>(null)
  const [lodgedFor, setLodgedFor] = useState("")
  const inFlight = useRef(false)
  const key = useRef<{ fingerprint: string; value: string } | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (inFlight.current) return
    const address = email.trim()
    if (!looksLikeEmail(address)) {
      setEmailError(DATA_REQUEST_COPY.invalidEmail)
      return
    }
    setEmailError(null)
    const fingerprint = `${type}\n${address}`
    if (!key.current || key.current.fingerprint !== fingerprint) {
      key.current = { fingerprint, value: newIdempotencyKey() }
    }
    inFlight.current = true
    setState("busy")
    try {
      await publicApiClient.post(
        DSAR_INTAKE_PATH,
        { email: address, requestType: type },
        { headers: { "Idempotency-Key": key.current.value } }
      )
      setLodgedFor(address)
      setState("lodged")
    } catch (err) {
      setState(httpStatusOf(err) === 429 ? "limited" : "failed")
    } finally {
      inFlight.current = false
    }
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      <div className="mb-6 flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-orange-50">
          <ShieldCheck className="h-5 w-5 text-orange-700" aria-hidden="true" />
        </span>
        <h1 className="text-2xl font-semibold leading-tight text-slate-900">{DATA_REQUEST_COPY.heading}</h1>
      </div>
      <p className="mb-6 text-sm leading-relaxed text-slate-600">{DATA_REQUEST_COPY.intro}</p>

      {state === "lodged" ? (
        <div role="status" className="flex gap-3 rounded-xl bg-emerald-50 p-4">
          <MailCheck className="mt-0.5 h-5 w-5 shrink-0 text-emerald-700" aria-hidden="true" />
          <div className="min-w-0 [overflow-wrap:anywhere]">
            <p className="text-sm font-semibold text-emerald-900">{DATA_REQUEST_COPY.lodgedHeading}</p>
            <p className="mt-1 text-sm text-emerald-900">{DATA_REQUEST_COPY.lodgedBody(lodgedFor)}</p>
          </div>
        </div>
      ) : (
        <form
          onSubmit={submit}
          noValidate
          className="flex flex-col gap-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
        >
          <div>
            <label htmlFor="data-request-email" className="text-sm font-medium text-slate-700">
              {DATA_REQUEST_COPY.emailLabel}
            </label>
            <input
              id="data-request-email"
              type="email"
              autoComplete="email"
              inputMode="email"
              required
              aria-required="true"
              aria-invalid={emailError ? true : undefined}
              aria-describedby={emailError ? "data-request-email-error data-request-email-hint" : "data-request-email-hint"}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1.5 min-h-11 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-base text-slate-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700"
            />
            {emailError ? (
              <p id="data-request-email-error" className="mt-2 text-sm text-red-700">
                {emailError}
              </p>
            ) : null}
            <p id="data-request-email-hint" className="mt-2 text-xs text-slate-600">
              {DATA_REQUEST_COPY.emailHint}
            </p>
          </div>

          <fieldset>
            <legend className="text-sm font-medium text-slate-700">{DATA_REQUEST_COPY.typeLegend}</legend>
            <div className="mt-2 flex flex-col gap-2">
              {(["ACCESS", "ERASURE"] as const).map((value) => {
                const copy = value === "ACCESS" ? DATA_REQUEST_COPY.access : DATA_REQUEST_COPY.erasure
                const id = `data-request-type-${value.toLowerCase()}`
                return (
                  <label
                    key={value}
                    htmlFor={id}
                    className="flex cursor-pointer gap-3 rounded-lg border border-slate-200 p-3 has-[:checked]:border-orange-700 has-[:checked]:bg-orange-50"
                  >
                    <input
                      id={id}
                      type="radio"
                      name="requestType"
                      value={value}
                      checked={type === value}
                      onChange={() => setType(value)}
                      aria-describedby={`${id}-hint`}
                      className="mt-1 h-4 w-4 shrink-0 accent-orange-700"
                    />
                    <span className="min-w-0">
                      <span className="block text-sm font-semibold text-slate-900">{copy.label}</span>
                      <span id={`${id}-hint`} className="mt-0.5 block text-xs leading-relaxed text-slate-600">
                        {copy.hint}
                      </span>
                    </span>
                  </label>
                )
              })}
            </div>
          </fieldset>

          {state === "failed" || state === "limited" ? (
            <p role="alert" className="flex gap-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              {state === "limited" ? ACCOUNT_REQUEST_LIMITED_COPY : ACCOUNT_REQUEST_FAILED_COPY}
            </p>
          ) : null}

          <div>
            <button
              type="submit"
              disabled={state === "busy"}
              aria-busy={state === "busy"}
              className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70 sm:w-auto"
            >
              {state === "busy" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}
              {DATA_REQUEST_COPY.submit}
            </button>
            <p className="mt-2 text-xs text-slate-600">{DATA_REQUEST_COPY.confirmNote}</p>
          </div>
        </form>
      )}

      <p className="mt-6 text-xs leading-relaxed text-slate-600">
        {DATA_REQUEST_COPY.otherRights}{" "}
        <Link href="/legal/privacy" className="underline underline-offset-4 hover:text-slate-900">
          privacy notice
        </Link>
        .
      </p>
    </div>
  )
}
