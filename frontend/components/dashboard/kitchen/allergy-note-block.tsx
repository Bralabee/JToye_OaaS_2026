"use client"

import { useId, useRef, useState } from "react"
import { useSession } from "next-auth/react"
import { MessageSquareWarning, CheckCircle2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  ALLERGY_NOTE_ACK_BUTTON_COPY,
  ALLERGY_NOTE_ACK_FAILED_COPY,
  ALLERGY_NOTE_ACK_FORBIDDEN_COPY,
  ALLERGY_NOTE_LABEL_COPY,
  allergyNoteReadCopy,
} from "@/lib/allergen-copy"
import {
  acknowledgeAllergyNote,
  describeAllergyNoteReader,
  subjectOfAccessToken,
} from "@/lib/allergy-note-api"
import { formatUkClockTime } from "@/lib/uk-datetime"

/**
 * The customer's allergy note, and the act of marking it read (31.1-22; #812, D-15).
 *
 * Rendered on the kitchen board card (inside the header, right under the allergen banner) and
 * on the vendor order detail. The printed ticket carries the same note and its read STATE, never
 * this control: paper cannot be pressed (see kitchen-ticket.tsx).
 *
 * WHY A SOLID SLATE-900 FILL. The allergen banner directly above it is solid amber-800, and the
 * two must not read as one block: the banner is the vendor's DECLARATION, this is the customer's
 * REQUEST. Near-black with white text is the highest-contrast pair available (17:1) and carries
 * at the 0.6-1.5 m the KDS is read from without inventing a new warning colour; the label is the
 * banner's own 20px/600 uppercase step, so the two read as one family. Nothing animates (WCAG 2.3.1,
 * the S4 contract). The words are the signal; the fill only separates.
 *
 * THE NOTE IS PLAIN TEXT (T-31.1-76). It is free text from an unauthenticated customer rendered
 * on staff devices; React escapes it, and there is no HTML path here on purpose. `whitespace-pre-wrap`
 * keeps the customer's line breaks, `break-words` keeps a long unbroken string inside the card, and
 * nothing truncates it: a cut-off allergy note is worse than a long card.
 *
 * NO OPTIMISTIC "READ" (T-31.1-77). The read line appears only after the server's 200, and states
 * the who/when the SERVER returned — which, first write winning, may be a colleague's earlier
 * acknowledgement rather than this press. A 200 without an acknowledgement is treated as a failure.
 * A refusal (403, T-31.1-78) or any failure is announced with role=alert and the button stays.
 *
 * WHO. The server stores the principal id, not a name; see describeAllergyNoteReader.
 */
export interface AllergyNoteAcknowledgement {
  acknowledgedAt: string
  acknowledgedBy: string | null
}

export function AllergyNoteBlock({
  orderId,
  orderLabel,
  note,
  acknowledgedAt = null,
  acknowledgedBy = null,
  onAcknowledged,
}: {
  orderId: string
  /** The order number as staff say it; completes the button's accessible name on a busy board. */
  orderLabel: string
  note: string | null | undefined
  acknowledgedAt?: string | null
  acknowledgedBy?: string | null
  /** Called with the server's who/when after a 200, so the caller's copy of the order can learn it. */
  onAcknowledged?: (ack: AllergyNoteAcknowledgement) => void
}) {
  const labelId = useId()
  const { data: session } = useSession()
  const [serverAck, setServerAck] = useState<AllergyNoteAcknowledgement | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // A ref, not only the `pending` state: three taps inside one frame all see the same render's
  // `pending === false`, and the endpoint should be asked once per press-burst.
  const inFlight = useRef(false)

  if (!note) return null

  const ack: AllergyNoteAcknowledgement | null =
    serverAck ?? (acknowledgedAt ? { acknowledgedAt, acknowledgedBy } : null)

  const markRead = async () => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(true)
    setError(null)
    try {
      const detail = await acknowledgeAllergyNote(orderId)
      if (!detail?.allergyNoteAcknowledgedAt) {
        // The server said 200 but recorded nothing we can show. Saying "read" now would be a
        // claim with no record behind it.
        setError(ALLERGY_NOTE_ACK_FAILED_COPY)
        return
      }
      const next = {
        acknowledgedAt: detail.allergyNoteAcknowledgedAt,
        acknowledgedBy: detail.allergyNoteAcknowledgedBy ?? null,
      }
      setServerAck(next)
      onAcknowledged?.(next)
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } } | null)?.response?.status
      setError(status === 403 ? ALLERGY_NOTE_ACK_FORBIDDEN_COPY : ALLERGY_NOTE_ACK_FAILED_COPY)
    } finally {
      inFlight.current = false
      setPending(false)
    }
  }

  const reader = ack
    ? describeAllergyNoteReader(ack.acknowledgedBy, subjectOfAccessToken(session?.accessToken))
    : null

  return (
    <div
      role="group"
      aria-labelledby={labelId}
      data-testid="allergy-note"
      className="mt-2 w-full rounded-md bg-slate-900 px-3 py-2 text-white"
    >
      <div className="flex items-center gap-2">
        <MessageSquareWarning aria-hidden="true" className="h-6 w-6 flex-shrink-0" />
        <span id={labelId} className="text-xl font-semibold uppercase tracking-[0.08em]">
          {ALLERGY_NOTE_LABEL_COPY}
        </span>
      </div>

      <p
        data-testid="allergy-note-text"
        className="mt-1 whitespace-pre-wrap break-words text-base font-semibold"
      >
        {note}
      </p>

      {ack ? (
        <p
          data-testid="allergy-note-read"
          className="mt-2 flex items-center gap-1.5 text-base font-semibold"
        >
          <CheckCircle2 aria-hidden="true" className="h-5 w-5 flex-shrink-0" />
          {allergyNoteReadCopy(reader, formatUkClockTime(ack.acknowledgedAt))}
        </p>
      ) : (
        <Button
          type="button"
          onClick={markRead}
          disabled={pending}
          className="kds-press mt-2 h-auto min-h-11 w-full whitespace-normal bg-white px-3 py-2 text-base font-semibold text-slate-900 hover:bg-slate-100"
        >
          {ALLERGY_NOTE_ACK_BUTTON_COPY}
          <span className="sr-only"> for order {orderLabel}</span>
        </Button>
      )}

      {error && !ack ? (
        <p
          role="alert"
          className="mt-2 rounded bg-white px-2 py-1 text-sm font-semibold text-red-800"
        >
          {error}
        </p>
      ) : null}
    </div>
  )
}
