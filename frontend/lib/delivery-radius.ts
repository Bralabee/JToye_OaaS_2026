/**
 * #460 / UXT-030: a shop can limit how far it delivers. Core refuses a DELIVERY order beyond it with a typed
 * 422 (`delivery-out-of-range`), or, when the shop limits distance and the postcode cannot be placed,
 * `delivery-postcode-unrecognised`. The storefront states the radius up front and, on a refusal, puts the
 * sentence on the postcode field and offers collection.
 */

export const DELIVERY_OUT_OF_RANGE_TYPE = "https://jtoye.uk/errors/delivery-out-of-range"
export const DELIVERY_RADIUS_UNRECOGNISED_TYPE = "https://jtoye.uk/errors/delivery-postcode-unrecognised"

export interface DeliveryRadiusProblem {
  kind: "OUT_OF_RANGE" | "POSTCODE_NOT_RECOGNISED"
  deliveryRadiusKm: number
  /** The measured distance, to 0.1 km; null when the postcode could not be placed. */
  distanceKm: number | null
}

export function readDeliveryRadiusProblem(err: unknown): DeliveryRadiusProblem | null {
  const res = (err as { response?: { status?: number; data?: unknown } } | null)?.response
  if (!res || res.status !== 422 || typeof res.data !== "object" || res.data === null) return null
  const data = res.data as Record<string, unknown>
  const type = typeof data.type === "string" ? data.type : ""
  const kind = type.endsWith("/delivery-out-of-range")
    ? "OUT_OF_RANGE"
    : type.endsWith("/delivery-postcode-unrecognised")
      ? "POSTCODE_NOT_RECOGNISED"
      : null
  if (!kind) return null
  const radius = typeof data.deliveryRadiusKm === "number" ? data.deliveryRadiusKm : 0
  const distance = typeof data.distanceKm === "number" ? data.distanceKm : null
  return { kind, deliveryRadiusKm: radius, distanceKm: distance }
}

function km(value: number): string {
  return String(value)
}

/** Said under the postcode field when the shop limits delivery, before the customer types anything. */
export function deliveryRadiusHintCopy(radiusKm: number | null | undefined): string | null {
  if (radiusKm == null || !(radiusKm > 0)) return null
  return `This kitchen delivers within ${km(radiusKm)} km.`
}

/** The postcode field's message after a refusal. Built from the typed members, not the server's prose. */
export function deliveryRadiusFieldCopy(p: DeliveryRadiusProblem, collectionOffered: boolean): string {
  const choose = collectionOffered ? "Choose collection, or a delivery address closer to the kitchen." : "Try a delivery address closer to the kitchen."
  if (p.kind === "POSTCODE_NOT_RECOGNISED") {
    return `We couldn't place that postcode to check it is within ${km(p.deliveryRadiusKm)} km. Check it, or ${collectionOffered ? "choose collection" : "try another address"}.`
  }
  const away = p.distanceKm != null ? ` and that address is about ${km(p.distanceKm)} km away` : ""
  return `This kitchen delivers within ${km(p.deliveryRadiusKm)} km${away}. ${choose}`
}
