"use client"

import { useEffect, useId, useRef, useState } from "react"
import { Star } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * "Rate your order" — the review entry point for a SIGNED-IN customer on their COMPLETED order
 * (#846, #813; persona close-out 2026-11-04).
 *
 * WHY IT EXISTS. The only way to leave a review was an unauthenticated API call with the customer's
 * email in the URL, which no screen ever made, so no customer could review. This posts to the same-origin
 * proxy `/api/customer-orders/review`, which reads the customer's access token from its HttpOnly cookie
 * and forwards it as `X-Customer-Token`: the server learns who is reviewing from the verified token, never
 * from anything this component sends. The URL carries the shop and the order number and nothing else; the
 * body carries the rating and the comment. Nothing here knows the customer's email or name.
 *
 * THE NOTICE (#813) is inside the form, above the submit button, and describes the comment box, so a
 * screen reader meets it where the writing happens. What it says is true of the server: a review is
 * published straight away under "first name + initial" (`ReviewerDisplayName`, 37-35), and the email is
 * never shown.
 *
 * MODERATION IS OUT OF SCOPE (#813's remainder). There is no flag, hide or report mechanism behind this
 * form, so it does not imply one: it says reviews are published straight away.
 *
 * A signed-out visitor never sees this (the callers render it only for a session); a session that has
 * lapsed by the time Post review is pressed gets the 401 message, with what they typed kept.
 */

export const REVIEW_PUBLIC_NOTICE_COPY =
  "Your review is public. It is shown on the shop's page with your first name and the first letter of your last name (for example, “Grace P.”), and it is published straight away. Your email address is never shown."

const COMMENT_MAX = 1000

type Phase = "idle" | "submitting" | "done"

/** What the server's refusals mean to the customer. The server's detail is matched, never shown raw. */
function refusalCopy(status: number | null, detail: string): string {
  if (status === 401) return "Your session has expired. Sign in again to post your review."
  if (status === 404) return "We couldn't find this order on your account."
  if (status === 400 && /already reviewed/i.test(detail)) return "You've already reviewed this order."
  if (status === 400 && /completed/i.test(detail)) return "Only a completed order can be reviewed."
  return "We couldn't post your review. Nothing was published. Try again in a moment."
}

export function ReviewOrder({
  shopSlug,
  orderNumber,
  shopName,
  className,
}: {
  shopSlug: string
  orderNumber: string
  shopName: string
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const [rating, setRating] = useState<number | null>(null)
  const [comment, setComment] = useState("")
  const [phase, setPhase] = useState<Phase>("idle")
  const [error, setError] = useState<string | null>(null)
  const thanksRef = useRef<HTMLParagraphElement>(null)
  const id = useId()
  const noticeId = `${id}-notice`
  const countId = `${id}-count`
  const formId = `${id}-form`

  useEffect(() => {
    if (phase === "done") thanksRef.current?.focus()
  }, [phase])

  if (phase === "done") {
    return (
      <p
        ref={thanksRef}
        tabIndex={-1}
        role="status"
        className={cn("rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-800 focus:outline-none", className)}
      >
        Thanks, your review is public now.
      </p>
    )
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (phase === "submitting") return
    if (rating === null) {
      setError("Choose a rating from 1 to 5 before posting your review.")
      return
    }
    setError(null)
    setPhase("submitting")
    try {
      const body: { foodRating: number; comment?: string } = { foodRating: rating }
      const text = comment.trim()
      if (text) body.comment = text
      const res = await fetch(
        `/api/customer-orders/review?shop=${encodeURIComponent(shopSlug)}&order=${encodeURIComponent(orderNumber)}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
      )
      if (res.ok) {
        setPhase("done")
        return
      }
      const problem = (await res.json().catch(() => ({}))) as { detail?: unknown }
      setError(refusalCopy(res.status, typeof problem.detail === "string" ? problem.detail : ""))
    } catch {
      setError(refusalCopy(null, ""))
    }
    setPhase("idle")
  }

  return (
    <div className={className}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={formId}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex min-h-11 items-center gap-1.5 rounded-lg border border-cream-100 bg-white px-3 text-sm font-semibold text-oxblood transition-colors duration-150 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-cream focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
      >
        <Star className="h-4 w-4" aria-hidden="true" />
        Rate your order
      </button>

      {open && (
        <form
          id={formId}
          aria-label="Rate your order"
          noValidate
          onSubmit={submit}
          className="mt-3 space-y-3 rounded-xl border border-cream-100 bg-white p-4 shadow-sm"
        >
          <fieldset>
            <legend className="text-sm font-semibold text-slate-900">How was the food from {shopName}?</legend>
            <div className="mt-2 flex items-center gap-1">
              {[1, 2, 3, 4, 5].map((n) => (
                <label key={n} className="relative cursor-pointer">
                  <input
                    type="radio"
                    name={`${id}-rating`}
                    value={n}
                    checked={rating === n}
                    onChange={() => {
                      setRating(n)
                      setError(null)
                    }}
                    className="peer sr-only"
                  />
                  <span className="sr-only">{n} out of 5</span>
                  <Star
                    aria-hidden="true"
                    className={cn(
                      "h-9 w-9 rounded-md p-1 peer-focus-visible:ring-2 peer-focus-visible:ring-brand-700 peer-focus-visible:ring-offset-2",
                      // Outline and fill both clear 3:1 (WCAG 1.4.11) and the literal-contrast contract.
                      rating !== null && n <= rating ? "fill-amber-400 text-amber-700" : "text-slate-600"
                    )}
                  />
                </label>
              ))}
            </div>
          </fieldset>

          <div className="space-y-1.5">
            <label htmlFor={`${id}-comment`} className="block text-xs font-medium text-slate-600">
              Your review (optional)
            </label>
            <textarea
              id={`${id}-comment`}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={COMMENT_MAX}
              rows={3}
              aria-describedby={`${noticeId} ${countId}`}
              className="w-full resize-none rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 focus:border-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-700"
            />
            <p id={countId} className="text-right text-xs tabular-nums text-slate-600">
              {comment.length} of {COMMENT_MAX}
            </p>
          </div>

          <p id={noticeId} data-testid="review-public-notice" className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">
            {REVIEW_PUBLIC_NOTICE_COPY}
          </p>

          {error && (
            <p role="alert" className="text-sm font-medium text-red-700">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={phase === "submitting"}
            className="inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-oxblood px-4 text-sm font-bold text-white transition-colors [@media(hover:hover)_and_(pointer:fine)]:hover:bg-oxblood-700 disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
          >
            {phase === "submitting" ? "Posting…" : "Post review"}
          </button>
        </form>
      )}
    </div>
  )
}
