"use client"

import { useEffect, useId, useRef, useState } from "react"
import { AlertTriangle, Eye, EyeOff, Loader2, UserPlus } from "lucide-react"
import { signIn } from "next-auth/react"
import publicApiClient from "@/lib/public-api-client"
import { ROLE_LABELS, type ShopRole } from "@/lib/staff-api"

/**
 * The client island of `/invite` (D-07, D-26; UI-SPEC § B2; 37-09).
 *
 * THE LINK IS A BEARER CREDENTIAL until it is used or expires, so most of this
 * component is about where it can end up (T-37-24):
 *
 *   1. It arrives in the URL fragment (`#token={tenantId}.{token}`), which a browser
 *      never sends to any server, and is read once on mount.
 *   2. It is removed from the address bar at once (`history.replaceState`), so it
 *      does not survive in history, a copied URL or a screenshot.
 *   3. It lives in memory only (a ref) and is POSTed in a JSON body — never in a
 *      path, where ProblemDetail's `instance` would echo it back and the rate
 *      limiter would log it (37-08). Nothing is written to web storage, local or
 *      session; this page registers no client-persisted key.
 *
 * THE PASSWORD (T-37-26) is read from an UNCONTROLLED input at submit time, so it is
 * never React state, never a `value` attribute in the DOM, never in a message. A
 * refusal of it is Keycloak's own words, shown verbatim under the field (the realm's
 * policy is the only policy: the page states none that could disagree with it).
 *
 * WHAT HAPPENS ON SUCCESS is the owner's D-26 answer, "login-hint" (37-01): the
 * server creates the account, and this page starts the ORDINARY Keycloak sign-in with
 * the invited address pre-filled; the person types the password they just chose once
 * more. No second way of minting a session exists. The callback carries `joined=1`,
 * and the dashboard builds its one-time welcome from the server's own access answer.
 *
 * Each state is chosen from the HTTP status and the preview's `accountState`, never
 * from English `detail` — except the password refusal, whose detail IS the message.
 * Every 404 is one state with one copy: expired, used, cancelled, another business's
 * link or an unknown one cannot be told apart, here or in the server's answer.
 */

const PREVIEW_PATH = "/api/v1/public/staff-invites/preview"
const ACCEPT_PATH = "/api/v1/public/staff-invites/accept"
const JOINED_CALLBACK = "/dashboard?joined=1"

type AccountState = "NEW" | "EXISTS_HERE" | "OTHER_BUSINESS"

/** `StaffInvitePreviewDto` (37-08). `shopName` null = every shop of the business. */
interface Preview {
  businessName: string
  role: ShopRole
  shopName: string | null
  inviterName: string | null
  email: string
  accountState: AccountState
}

export const INVITE_PAGE_COPY = {
  checking: "Checking your invitation…",
  unreachable: "We couldn't check your invitation just now. Your link still works. Try again in a moment.",
  tryAgain: "Try again",
  existing: "You already have a J'Toye account with this email.",
  signInToAccept: "Sign in to accept",
  submit: "Create account and join",
  submitting: "Creating your account…",
  signingIn: "Signing you in…",
  showPassword: "Show password",
  allShops: "all shops",
  firstNameRequired: "Enter your first name.",
  lastNameRequired: "Enter your last name.",
  passwordRequired: "Enter a password.",
  passwordRefusedFallback: "That password can't be used. Choose a different one.",
  tooMany: "Too many attempts from this connection. Wait a minute and try again.",
  serviceDown: "We can't create accounts right now. Your link still works. Try again in a few minutes.",
  failed: "Something went wrong and your account was not created. Your link still works. Please try again.",
  unusable: {
    heading: "This invitation link can't be used",
    body: "It may have expired, already been used, or been cancelled. Ask the person who invited you to send a new one.",
  },
  otherBusiness: {
    heading: "This email already belongs to another business",
    body: "Each J'Toye account works with one business. Ask the person who invited you to use a different email address.",
  },
} as const

type Phase =
  | { kind: "checking" }
  | { kind: "unreachable" }
  | { kind: "ready-new"; preview: Preview }
  | { kind: "ready-existing"; preview: Preview }
  | { kind: "submitting"; preview: Preview }
  | { kind: "signing-in" }
  | { kind: "unusable" }
  | { kind: "other-business" }

type FieldErrors = { firstName?: string; lastName?: string; password?: string }

/** Axios error → `{status, detail}`; nothing for a failure that is not an HTTP answer. */
function answerOf(err: unknown): { status?: number; detail?: string } {
  if (err && typeof err === "object" && "response" in err) {
    const res = (err as { response?: { status?: number; data?: { detail?: unknown } } }).response
    return {
      status: res?.status,
      detail: typeof res?.data?.detail === "string" ? res.data.detail : undefined,
    }
  }
  return {}
}

function isPreview(data: unknown): data is Preview {
  if (!data || typeof data !== "object") return false
  const p = data as Record<string, unknown>
  return (
    typeof p.businessName === "string" &&
    typeof p.email === "string" &&
    typeof p.role === "string" &&
    (p.accountState === "NEW" || p.accountState === "EXISTS_HERE" || p.accountState === "OTHER_BUSINESS")
  )
}

/** The fragment's `token` parameter, or null. */
function readRef(): string | null {
  const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""))
  const ref = fragment.get("token")
  return ref && ref.trim() ? ref.trim() : null
}

const CARD = "mx-auto max-w-md rounded-2xl border border-cream-100 bg-white p-6"
const H1 = "text-2xl font-semibold leading-tight text-slate-900 [overflow-wrap:anywhere]"
const BODY = "mt-3 text-sm leading-relaxed text-slate-600 [overflow-wrap:anywhere]"
const INPUT =
  "h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-600 aria-[invalid=true]:border-red-600"
const PRIMARY =
  "inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 text-sm font-semibold text-white transition-colors hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"

export function InviteClient() {
  const refRef = useRef<string | null>(null)
  const started = useRef(false)
  const inFlight = useRef(false)
  const [phase, setPhase] = useState<Phase>({ kind: "checking" })

  async function runPreview() {
    const ref = refRef.current
    if (!ref) {
      setPhase({ kind: "unusable" })
      return
    }
    setPhase({ kind: "checking" })
    try {
      const res = await publicApiClient.post<Preview>(PREVIEW_PATH, { ref })
      const preview = res.data
      if (!isPreview(preview)) {
        // An answer this page does not understand is never read as a usable link.
        setPhase({ kind: "unreachable" })
      } else if (preview.accountState === "OTHER_BUSINESS") {
        setPhase({ kind: "other-business" })
      } else if (preview.accountState === "EXISTS_HERE") {
        setPhase({ kind: "ready-existing", preview })
      } else {
        setPhase({ kind: "ready-new", preview })
      }
    } catch (err: unknown) {
      const { status } = answerOf(err)
      if (status === 404) setPhase({ kind: "unusable" })
      else if (status === 409) setPhase({ kind: "other-business" })
      else setPhase({ kind: "unreachable" })
    }
  }

  useEffect(() => {
    // Under Strict Mode (next dev) this effect runs twice; the link is read and the
    // preview asked for once.
    if (started.current) return
    started.current = true
    refRef.current = readRef()
    if (window.location.hash) {
      // Drop the fragment before anything else can observe it.
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname + window.location.search
      )
    }
    // The fragment exists only in the browser, so which state to show can only be
    // decided after mount.
    runPreview()
  }, [])

  const business =
    phase.kind === "ready-new" || phase.kind === "ready-existing" || phase.kind === "submitting"
      ? phase.preview.businessName
      : null
  useEffect(() => {
    if (business) document.title = `Join ${business} on J'Toye`
  }, [business])

  /** Take the invitation, then start the ordinary sign-in with the invited address (D-26). */
  async function accept(preview: Preview, body: Record<string, string>): Promise<AcceptOutcome> {
    // A second press while the first is in flight sends nothing and changes nothing.
    if (inFlight.current) return undefined
    inFlight.current = true
    setPhase({ kind: "submitting", preview })
    try {
      await publicApiClient.post(ACCEPT_PATH, { ref: refRef.current, ...body })
      refRef.current = null
      setPhase({ kind: "signing-in" })
      await signIn("keycloak", { callbackUrl: JOINED_CALLBACK }, { login_hint: preview.email })
      return null
    } catch (err: unknown) {
      const { status, detail } = answerOf(err)
      const back: Phase =
        preview.accountState === "EXISTS_HERE"
          ? { kind: "ready-existing", preview }
          : { kind: "ready-new", preview }
      if (status === 404) {
        setPhase({ kind: "unusable" })
        return null
      }
      if (status === 409) {
        setPhase({ kind: "other-business" })
        return null
      }
      setPhase(back)
      if (status === 422) return { password: detail || INVITE_PAGE_COPY.passwordRefusedFallback }
      if (status === 429) return INVITE_PAGE_COPY.tooMany
      if (status === 503) return INVITE_PAGE_COPY.serviceDown
      if (status === 400 && detail) return detail
      return INVITE_PAGE_COPY.failed
    } finally {
      inFlight.current = false
    }
  }

  return (
    <div className="px-4 py-16">
      <div className={CARD}>
        {phase.kind === "checking" && (
          <div role="status" className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-600" aria-hidden="true" />
            <h1 className={H1}>{INVITE_PAGE_COPY.checking}</h1>
          </div>
        )}

        {phase.kind === "unreachable" && (
          <div className="flex flex-col py-2">
            <p role="alert" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
              {INVITE_PAGE_COPY.unreachable}
            </p>
            <button type="button" onClick={() => runPreview()} className={`mt-4 ${PRIMARY}`}>
              {INVITE_PAGE_COPY.tryAgain}
            </button>
          </div>
        )}

        {(phase.kind === "ready-new" ||
          (phase.kind === "submitting" && phase.preview.accountState === "NEW")) && (
          <NewAccount
            preview={phase.preview}
            submitting={phase.kind === "submitting"}
            onAccept={accept}
          />
        )}

        {(phase.kind === "ready-existing" ||
          (phase.kind === "submitting" && phase.preview.accountState === "EXISTS_HERE")) && (
          <ExistingAccount
            preview={phase.preview}
            submitting={phase.kind === "submitting"}
            onAccept={accept}
          />
        )}

        {phase.kind === "signing-in" && (
          <div role="status" className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-600" aria-hidden="true" />
            <h1 className={H1}>{INVITE_PAGE_COPY.signingIn}</h1>
          </div>
        )}

        {phase.kind === "unusable" && <Refused copy={INVITE_PAGE_COPY.unusable} />}
        {phase.kind === "other-business" && <Refused copy={INVITE_PAGE_COPY.otherBusiness} />}
      </div>
    </div>
  )
}

/** "Join {business} on J'Toye" and the sentence naming the offer, from the preview only. */
function Offer({ preview }: { preview: Preview }) {
  const shop = preview.shopName ?? INVITE_PAGE_COPY.allShops
  const role = ROLE_LABELS[preview.role] ?? preview.role
  return (
    <>
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-orange-50">
        <UserPlus className="h-6 w-6 text-orange-700" aria-hidden="true" />
      </span>
      <h1 className={H1}>{`Join ${preview.businessName} on J'Toye`}</h1>
      <p className={BODY}>
        {preview.inviterName ? `${preview.inviterName} has invited you` : "You've been invited"} to
        work on <strong className="font-semibold text-slate-900">{shop}</strong> as{" "}
        <strong className="font-semibold text-slate-900">{role}</strong>.
      </p>
      <p className={BODY}>
        You&apos;ll sign in with <strong className="font-semibold text-slate-900">{preview.email}</strong>.
      </p>
    </>
  )
}

/**
 * What accepting came to, for the form that asked: `null` = done (signing in, or the
 * page moved to a terminal state), a string = a form-level message, field errors =
 * the password refusal, `undefined` = ignored because an accept was already in flight.
 */
type AcceptOutcome = FieldErrors | string | null | undefined
type AcceptFn = (preview: Preview, body: Record<string, string>) => Promise<AcceptOutcome>

function NewAccount({
  preview,
  submitting,
  onAccept,
}: {
  preview: Preview
  submitting: boolean
  onAccept: AcceptFn
}) {
  const ids = {
    first: useId(),
    last: useId(),
    password: useId(),
    firstError: useId(),
    lastError: useId(),
    passwordError: useId(),
  }
  const [firstName, setFirstName] = useState("")
  const [lastName, setLastName] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const firstRef = useRef<HTMLInputElement>(null)
  const lastRef = useRef<HTMLInputElement>(null)
  /** UNCONTROLLED: the password is read at submit time and is never React state. */
  const passwordRef = useRef<HTMLInputElement>(null)
  /** Which field to focus after the render that shows its error. */
  const [focusField, setFocusField] = useState<keyof FieldErrors | null>(null)

  useEffect(() => {
    if (!focusField) return
    const target = { firstName: firstRef, lastName: lastRef, password: passwordRef }[focusField]
    target.current?.focus()
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot focus request, cleared once honoured
    setFocusField(null)
  }, [focusField])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (submitting) return
    setFormError(null)
    const password = passwordRef.current?.value ?? ""
    const next: FieldErrors = {}
    if (!firstName.trim()) next.firstName = INVITE_PAGE_COPY.firstNameRequired
    if (!lastName.trim()) next.lastName = INVITE_PAGE_COPY.lastNameRequired
    if (!password) next.password = INVITE_PAGE_COPY.passwordRequired
    setErrors(next)
    const firstInvalid = (["firstName", "lastName", "password"] as const).find((k) => next[k])
    if (firstInvalid) {
      setFocusField(firstInvalid)
      return
    }
    const outcome = await onAccept(preview, {
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      password,
    })
    if (outcome === undefined) return
    if (outcome === null) {
      if (passwordRef.current) passwordRef.current.value = ""
      return
    }
    if (typeof outcome === "string") {
      setFormError(outcome)
    } else {
      setErrors(outcome)
      setFocusField("password")
    }
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col py-2">
      <Offer preview={preview} />

      <div className="mt-6 space-y-4">
        <div className="space-y-1.5">
          <label htmlFor={ids.first} className="text-sm font-medium text-slate-700">
            First name
          </label>
          <input
            id={ids.first}
            ref={firstRef}
            type="text"
            autoComplete="given-name"
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
            aria-invalid={errors.firstName ? true : undefined}
            aria-describedby={errors.firstName ? ids.firstError : undefined}
            className={INPUT}
          />
          {errors.firstName && (
            <p id={ids.firstError} role="alert" className="text-sm text-red-700">
              {errors.firstName}
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <label htmlFor={ids.last} className="text-sm font-medium text-slate-700">
            Last name
          </label>
          <input
            id={ids.last}
            ref={lastRef}
            type="text"
            autoComplete="family-name"
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            aria-invalid={errors.lastName ? true : undefined}
            aria-describedby={errors.lastName ? ids.lastError : undefined}
            className={INPUT}
          />
          {errors.lastName && (
            <p id={ids.lastError} role="alert" className="text-sm text-red-700">
              {errors.lastName}
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <label htmlFor={ids.password} className="text-sm font-medium text-slate-700">
            Password
          </label>
          <div className="relative">
            <input
              id={ids.password}
              ref={passwordRef}
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              aria-invalid={errors.password ? true : undefined}
              aria-describedby={errors.password ? ids.passwordError : undefined}
              className={`${INPUT} pr-12`}
            />
            <button
              type="button"
              aria-label={INVITE_PAGE_COPY.showPassword}
              aria-pressed={showPassword}
              onClick={() => setShowPassword((v) => !v)}
              className="absolute right-0 top-0 inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-600 hover:text-slate-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-orange-700"
            >
              {showPassword ? (
                <EyeOff className="h-5 w-5" aria-hidden="true" />
              ) : (
                <Eye className="h-5 w-5" aria-hidden="true" />
              )}
            </button>
          </div>
          {errors.password && (
            <p id={ids.passwordError} role="alert" className="text-sm text-red-700 [overflow-wrap:anywhere]">
              {errors.password}
            </p>
          )}
        </div>
      </div>

      {formError && (
        <p role="alert" className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
          {formError}
        </p>
      )}

      <button type="submit" disabled={submitting} className={`mt-6 ${PRIMARY}`}>
        {submitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
        {submitting ? INVITE_PAGE_COPY.submitting : INVITE_PAGE_COPY.submit}
      </button>
    </form>
  )
}

function ExistingAccount({
  preview,
  submitting,
  onAccept,
}: {
  preview: Preview
  submitting: boolean
  onAccept: AcceptFn
}) {
  const [formError, setFormError] = useState<string | null>(null)

  async function press() {
    if (submitting) return
    setFormError(null)
    const outcome = await onAccept(preview, {})
    if (typeof outcome === "string") setFormError(outcome)
  }

  return (
    <div className="flex flex-col py-2">
      <Offer preview={preview} />
      <p className={BODY}>{INVITE_PAGE_COPY.existing}</p>
      {formError && (
        <p role="alert" className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
          {formError}
        </p>
      )}
      <button type="button" onClick={press} disabled={submitting} className={`mt-6 ${PRIMARY}`}>
        {submitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
        {INVITE_PAGE_COPY.signInToAccept}
      </button>
    </div>
  )
}

/**
 * One component for a refused link, given only constant copy: it has no prop through
 * which a server `detail`, a status or the ref could reach the page, so every 404
 * renders the same bytes (the server's half is one byte-identical body, 37-08).
 */
function Refused({ copy }: { copy: { heading: string; body: string } }) {
  return (
    <div className="flex flex-col py-2">
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100">
        <AlertTriangle className="h-6 w-6 text-amber-700" aria-hidden="true" />
      </span>
      <h1 className={H1}>{copy.heading}</h1>
      <p className={BODY}>{copy.body}</p>
    </div>
  )
}

export default InviteClient
