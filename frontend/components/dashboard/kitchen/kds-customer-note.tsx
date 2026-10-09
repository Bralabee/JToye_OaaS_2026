import { useId } from "react"

export const CUSTOMER_NOTE_LABEL_COPY = "NOTE FROM CUSTOMER"

/**
 * The customer's order notes on every kitchen card (Phase 37-16, UXT-011 P0; UI-SPEC § K1 "Card
 * additions").
 *
 * THE REPRO. A customer wrote "severe peanut allergy" in the order notes. The printed ticket carried it;
 * the kitchen screen did not, so the cook reading the board never saw it. These are `order.notes`
 * (delivery notes, free text), a different field from the V73 allergy note, which keeps its own solid
 * slate-900 block (AllergyNoteBlock, L6). This block is deliberately quieter and DIFFERENT from both that
 * and the amber-800 allergen banner: a left rule on slate-50, so a cook never reads one signal as another.
 *
 * Placed after the customer name and before the items. The text is 16px (UI-SPEC typography override:
 * at 14px a multi-line note is not readable at board distance), `whitespace-pre-wrap break-words`, and
 * NEVER truncated or clamped: a cut-off note is worse than a long card.
 *
 * PLAIN TEXT ONLY (T-37-42). It is free text from an unauthenticated customer rendered on staff devices.
 * React escapes it and there is no HTML path here on purpose.
 *
 * Empty or whitespace-only notes render nothing.
 */
export function KdsCustomerNote({ note }: { note?: string | null }) {
  const labelId = useId()
  if (!note || note.trim().length === 0) return null
  return (
    <div
      role="group"
      aria-labelledby={labelId}
      data-testid="kds-customer-note"
      className="border-l-4 border-slate-400 bg-slate-50 px-4 py-2"
    >
      <p id={labelId} className="text-sm font-semibold uppercase text-slate-700">
        {CUSTOMER_NOTE_LABEL_COPY}
      </p>
      <p
        data-testid="kds-customer-note-text"
        className="whitespace-pre-wrap break-words text-base font-normal leading-normal text-slate-900"
      >
        {note}
      </p>
    </div>
  )
}
