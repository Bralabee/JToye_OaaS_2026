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
 * READY is the one step whose description depends on the order (37-F review F2): "Ready for
 * collection" was said — and shown on the order page's tracker — for a DELIVERY order too. The
 * description comes from `statusCopy`, with the order's fulfilment type, on the tracker AND in the
 * announcement, so the two cannot disagree. A response without a fulfilment type (an older backend,
 * a row predating V45) gets a neutral sentence, never a guessed "collection" or "delivery".
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

/** READY's description for each recorded fulfilment type, and the neutral one when none is. */
const READY_DESC: Readonly<Record<string, string>> = {
  COLLECTION: "Ready for collection",
  DELIVERY: "Ready for delivery",
}
const READY_DESC_UNRECORDED = "Your order is ready"

/**
 * The label and description for `status` on THIS order, or undefined for a status this platform
 * does not know. Only READY varies, by `fulfilmentType`; every other status reads as in
 * `ORDER_STATUS_COPY`.
 */
export function statusCopy(
  status: string,
  fulfilmentType?: string | null
): { label: string; desc: string } | undefined {
  const copy = Object.prototype.hasOwnProperty.call(ORDER_STATUS_COPY, status)
    ? ORDER_STATUS_COPY[status]
    : undefined
  if (!copy || status !== "READY") return copy
  const desc =
    fulfilmentType && Object.prototype.hasOwnProperty.call(READY_DESC, fulfilmentType)
      ? READY_DESC[fulfilmentType]
      : READY_DESC_UNRECORDED
  return { label: copy.label, desc }
}

/**
 * The announcement for an order that has just moved into `status`, or null for a status this
 * platform does not know — an unknown enum value announces nothing rather than an invented
 * sentence. `fulfilmentType` is the order's own (see `statusCopy`).
 */
export function statusUpdateAnnouncement(status: string, fulfilmentType?: string | null): string | null {
  const copy = statusCopy(status, fulfilmentType)
  return copy ? `Order update: ${copy.label}. ${copy.desc}.` : null
}
