"use client"

import { useId, useRef, useState } from "react"
import { AlertTriangle, Mail } from "lucide-react"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  createStaffInvite,
  INVITE_OTHER_BUSINESS_PROBLEM,
  ROLE_HINTS,
  ROLE_LABELS,
  ROLE_ORDER,
  type CreateStaffInviteResult,
  type ShopRole,
} from "@/lib/staff-api"

/**
 * D-07 (37-09, UI-SPEC § B1) — "Invite someone": email, role and shop, and one
 * button that emails a single-use link (`POST /api/v1/staff/invites`, 37-07).
 *
 * The server is the authority: it validates the address, refuses a shop-scoped
 * Group admin (the `CHECK (role <> 'GROUP_ADMIN' OR shop_id IS NULL)` rule) and
 * decides whether the call created an invitation (201) or found the same one already
 * open (200). This form mirrors the Group-admin rule BEFORE the server has to refuse
 * it — the shop select locks to "All shops" — and says what the server answered:
 * a refusal stays inline next to the form (`role="alert"`), never only in a toast.
 *
 * What the parent does with a success (toast, re-read the pending list) is the
 * parent's: this component reports the server's answer through `onInvited`.
 */

export const INVITE_COPY = {
  title: "Invite someone",
  description:
    "We'll email them a link. When they accept, they get exactly the access you choose here, nothing more.",
  groupAdminHint: "Group admin always covers every shop.",
  submit: "Send invitation",
  allShops: "All shops",
  emailRequired: "Enter an email address.",
  emailInvalid: "Enter one valid email address, like name@example.com.",
  otherBusiness:
    "This email address already has a J'Toye account with another business. Ask them for a different email address.",
  shopGone: "That shop is no longer part of your business. Choose another shop and try again.",
  failed: "Couldn't send the invitation. Check your connection and try again.",
  forbidden: "Only a Group admin can invite people. Ask a Group admin in your business.",
} as const

/** The labels, shared with the page's loading skeleton so the two cannot drift. */
export const INVITE_FIELD_LABELS = { email: "Email", role: "Role", shop: "Shop" } as const

const ALL_SHOPS_VALUE = ""

const SELECT_CLASS =
  "h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-700"

/** Axios error → `{status, type}`, or nothing for a non-HTTP failure. */
function problemOf(err: unknown): { status?: number; type?: string } {
  if (err && typeof err === "object" && "response" in err) {
    const res = (err as { response?: { status?: number; data?: { type?: unknown } } }).response
    return {
      status: res?.status,
      type: typeof res?.data?.type === "string" ? res.data.type : undefined,
    }
  }
  return {}
}

export interface InviteStaffFormProps {
  shops: { id: string; name: string }[]
  /** Called with the server's answer once an invitation exists (201 or the 200 replay). */
  onInvited: (result: CreateStaffInviteResult) => void | Promise<void>
}

export function InviteStaffForm({ shops, onInvited }: InviteStaffFormProps) {
  const titleId = useId()
  const emailErrorId = useId()
  const shopHintId = useId()

  const [email, setEmail] = useState("")
  const [role, setRole] = useState<ShopRole>("STAFF")
  const [shopId, setShopId] = useState(ALL_SHOPS_VALUE)
  const [emailError, setEmailError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const emailRef = useRef<HTMLInputElement>(null)
  /** A second press while the first is in flight must not send a second email. */
  const inFlight = useRef(false)

  const groupAdmin = role === "GROUP_ADMIN"
  // Group admin is tenant-wide by definition: whatever shop was chosen before the
  // role changed is not sent, and the select shows the value that WILL be sent.
  const effectiveShopId = groupAdmin ? ALL_SHOPS_VALUE : shopId

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (inFlight.current) return
    setRefusal(null)
    const address = email.trim()
    if (!address) {
      setEmailError(INVITE_COPY.emailRequired)
      emailRef.current?.focus()
      return
    }
    setEmailError(null)
    inFlight.current = true
    setSending(true)
    try {
      const result = await createStaffInvite({
        email: address,
        role,
        shopId: effectiveShopId === ALL_SHOPS_VALUE ? null : effectiveShopId,
      })
      setEmail("")
      await onInvited(result)
    } catch (err: unknown) {
      const { status, type } = problemOf(err)
      if (status === 409 || type?.endsWith(INVITE_OTHER_BUSINESS_PROBLEM)) {
        setRefusal(INVITE_COPY.otherBusiness)
      } else if (status === 400) {
        // The server's 400 here is "not one valid address" (a shop-scoped Group admin
        // cannot be sent from this form: the select is locked).
        setEmailError(INVITE_COPY.emailInvalid)
        emailRef.current?.focus()
      } else if (status === 404) {
        setRefusal(INVITE_COPY.shopGone)
      } else if (status === 403) {
        setRefusal(INVITE_COPY.forbidden)
      } else {
        setRefusal(INVITE_COPY.failed)
      }
    } finally {
      inFlight.current = false
      setSending(false)
    }
  }

  return (
    <Card role="region" aria-labelledby={titleId}>
      <CardHeader>
        <CardTitle id={titleId} className="flex items-center gap-2">
          <Mail className="h-5 w-5 text-orange-600" aria-hidden="true" />
          {INVITE_COPY.title}
        </CardTitle>
        <CardDescription>{INVITE_COPY.description}</CardDescription>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={submit} className="space-y-4">
          {refusal && (
            <div
              role="alert"
              className="flex items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900"
            >
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" aria-hidden="true" />
              <p>{refusal}</p>
            </div>
          )}
          <div className="grid gap-4 md:grid-cols-3">
            <div className="space-y-1.5">
              <label htmlFor="invite-email" className="text-sm font-medium text-slate-700">
                {INVITE_FIELD_LABELS.email}
              </label>
              <Input
                id="invite-email"
                ref={emailRef}
                type="email"
                autoComplete="off"
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value)
                  if (emailError) setEmailError(null)
                }}
                aria-invalid={emailError ? true : undefined}
                aria-describedby={emailError ? emailErrorId : undefined}
                className="h-11"
              />
              {emailError && (
                <p id={emailErrorId} role="alert" className="text-sm text-red-700">
                  {emailError}
                </p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="invite-role" className="text-sm font-medium text-slate-700">
                {INVITE_FIELD_LABELS.role}
              </label>
              <select
                id="invite-role"
                value={role}
                onChange={(e) => setRole(e.target.value as ShopRole)}
                className={SELECT_CLASS}
              >
                {ROLE_ORDER.map((r) => (
                  <option key={r} value={r}>
                    {`${ROLE_LABELS[r]} — ${ROLE_HINTS[r]}`}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="invite-shop" className="text-sm font-medium text-slate-700">
                {INVITE_FIELD_LABELS.shop}
              </label>
              <select
                id="invite-shop"
                value={effectiveShopId}
                onChange={(e) => setShopId(e.target.value)}
                disabled={groupAdmin}
                aria-describedby={groupAdmin ? shopHintId : undefined}
                className={SELECT_CLASS}
              >
                <option value={ALL_SHOPS_VALUE}>{INVITE_COPY.allShops}</option>
                {shops.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              {groupAdmin && (
                <p id={shopHintId} className="text-sm text-slate-600">
                  {INVITE_COPY.groupAdminHint}
                </p>
              )}
            </div>
          </div>

          <div className="flex justify-end">
            <Button type="submit" className="h-11 w-full sm:w-auto" disabled={sending}>
              {INVITE_COPY.submit}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}

export default InviteStaffForm
