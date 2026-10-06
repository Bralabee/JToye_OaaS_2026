"use client"

/** Title of the "what was recorded on your order" block. */
export const RECORDED_ALLERGEN_SET_TITLE_COPY = "Allergen record for this order"

/** Lead-in for the set the customer acknowledged at checkout. */
export const RECORDED_ACK_HEADING_COPY = "You confirmed you had read:"

/** Lead-in for the set recorded on the order lines (what the kitchen works from). */
export const RECORDED_SET_HEADING_COPY = "Recorded on your order:"

/** The acknowledgement is NOT RECORDED (a vendor-entered or pre-acknowledgement order). Never "none". */
export const RECORDED_ACK_NOT_RECORDED_COPY = "No allergen acknowledgement was recorded for this order."

/** The customer acknowledged a basket whose kitchen declared none of the 14. A statement, not an absence. */
export const RECORDED_ACK_NONE_COPY =
  "You confirmed you had read that the kitchen declared none of the 14 regulated allergens for these items."

export interface RecordedAllergenSetProps {
  acknowledged: string[] | null
  recorded: string[] | null
  flags: unknown[] | null
}

/** RED skeleton (31.1-15 Task 2): renders nothing yet. */
export function RecordedAllergenSet(_props: RecordedAllergenSetProps) {
  return null
}
