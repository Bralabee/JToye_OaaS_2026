/**
 * The public data-request intake, shared by the signed-in account page (`/shop/account`) and the
 * guest form (`/data-request`, SW-SAM-03). Both lodge exactly what an anonymous person writing to
 * us would: an address and a request type. Core answers every valid request with the same opaque
 * 202 and emails a confirmation link to the address; nothing is actioned until that link is used.
 */
export const DSAR_INTAKE_PATH = "/api/v1/public/gdpr/dsar"

export type DsarRequestType = "ACCESS" | "ERASURE"

/** Unguessable per-request Idempotency-Key: crypto.randomUUID, else a v4 UUID from getRandomValues. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID()
  }
  const buf = new Uint8Array(16)
  crypto.getRandomValues(buf)
  buf[6] = (buf[6] & 0x0f) | 0x40
  buf[8] = (buf[8] & 0x3f) | 0x80
  const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function httpStatusOf(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}
