/**
 * What a tracking page's status region says when the order MOVES ON (37-51, UI-SPEC § F1,
 * RWO-081 / UXT-081): "Order update: {label}. {description}."
 *
 * One copy for both tracking surfaces — the per-shop order page and /track — so the two pages
 * cannot drift into announcing the same change in different words. The labels are the tracker's
 * own step labels; the descriptions are the order page's step descriptions. CANCELLED and REFUNDED
 * are not tracker steps (each page shows its own panel for them), but moving into either IS a
 * change, so each has a sentence here too.
 *
 * WHEN to announce is each page's job, and the rule is the same on both: only when a tracking read
 * returns a status different from the previous read of the SAME order. The first read is not a
 * change (UI-SPEC F loading), an unchanged read writes nothing (no DOM mutation, so nothing is
 * read aloud), and a failed read writes nothing (F1 error, planner assumption).
 */
export const ORDER_STATUS_COPY: Readonly<Record<string, { label: string; desc: string }>> = {
  PENDING: { label: "Received", desc: "Order sent to shop" },
  CONFIRMED: { label: "Confirmed", desc: "Shop accepted your order" },
  PREPARING: { label: "Preparing", desc: "Being prepared now" },
  READY: { label: "Ready", desc: "Ready for collection" },
  COMPLETED: { label: "Completed", desc: "Order complete" },
  CANCELLED: { label: "Cancelled", desc: "This order was cancelled" },
  REFUNDED: { label: "Refunded", desc: "This order was refunded" },
}

/**
 * The announcement for an order that has just moved into `status`, or null for a status this
 * platform does not know — an unknown enum value announces nothing rather than an invented
 * sentence.
 */
export function statusUpdateAnnouncement(status: string): string | null {
  const copy = Object.prototype.hasOwnProperty.call(ORDER_STATUS_COPY, status)
    ? ORDER_STATUS_COPY[status]
    : undefined
  return copy ? `Order update: ${copy.label}. ${copy.desc}.` : null
}
