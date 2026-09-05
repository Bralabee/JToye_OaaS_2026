/**
 * The guest-checkout ORDER INTENT — the exact body the storefront POSTs to
 * `/public/shops/{slug}/orders`, minus the `idempotencyKey` itself — and the
 * signature the checkout page binds that key to.
 *
 * PR #726 review, M3. The server enforces the platform's V50 Idempotency-Key
 * contract on the guest checkout: the same key with a DIFFERENT body is refused
 * 422 `errors/idempotency-payload-mismatch`. The client's key must therefore
 * follow the PAYLOAD. The first version of that binding signed only
 * `productId:quantity`, so after a failed submit a shopper who corrected a
 * mistyped phone number, changed the address, or switched Delivery to
 * Collection retried with the SAME key and a DIFFERENT body — a correct server
 * refusal with no client recovery path.
 *
 * ONE BUILDER FOR BOTH USES. `buildGuestOrderIntent` produces the body and
 * `guestOrderIntentSignature` serialises that SAME object, so a field cannot be
 * submitted without being signed — adding a field to the request adds it to the
 * signature by construction. That is the property the review asked for and the
 * one a hand-maintained `${a}:${b}` template cannot give.
 *
 * The signature is a plain `JSON.stringify` of the intent. Key order is fixed by
 * the builder, `undefined` members are dropped by the serialiser exactly as they
 * are dropped from the wire body, and the normalisation (`trim`, upper-cased
 * postcode) happens BEFORE signing, so a retyped-but-equivalent value ("SW9 8LF"
 * vs "sw9 8lf ") keeps its key and a pure retry of an identical payload replays
 * rather than duplicates.
 */
import type { FulfilmentType } from "@/types/api"

/** One basket line as the server receives it (`GuestOrderRequest.items[]`). */
export interface GuestOrderLine {
  productId: string
  quantity: number
}

/** The raw form state the checkout page holds. Nothing here is trimmed yet. */
export interface GuestOrderFields {
  customerName: string
  customerEmail: string
  customerPhone: string
  notes: string
  fulfilmentType: FulfilmentType
  address1: string
  address2: string
  city: string
  postcode: string
  items: readonly GuestOrderLine[]
}

/**
 * The server contract (`GuestOrderRequest`, plan 19-01) is FLAT: `fulfilmentType`
 * + `addressLine1/2` + `addressCity` + `addressPostcode`, never a nested address
 * object. Address fields are ABSENT (not empty strings) on a COLLECTION order.
 */
export interface GuestOrderIntent {
  customerName: string
  customerEmail: string
  customerPhone: string
  notes?: string
  fulfilmentType: FulfilmentType
  addressLine1?: string
  addressLine2?: string
  addressCity?: string
  addressPostcode?: string
  items: GuestOrderLine[]
}

/** Normalise the form state into the body the checkout page submits. */
export function buildGuestOrderIntent(fields: GuestOrderFields): GuestOrderIntent {
  return {
    customerName: fields.customerName.trim(),
    customerEmail: fields.customerEmail.trim(),
    customerPhone: fields.customerPhone.trim(),
    notes: fields.notes.trim() || undefined,
    fulfilmentType: fields.fulfilmentType,
    ...(fields.fulfilmentType === "DELIVERY"
      ? {
          addressLine1: fields.address1.trim(),
          addressLine2: fields.address2.trim() || undefined,
          addressCity: fields.city.trim(),
          addressPostcode: fields.postcode.trim().toUpperCase(),
        }
      : {}),
    items: fields.items.map((item) => ({
      productId: item.productId,
      quantity: item.quantity,
    })),
  }
}

/**
 * A stable signature of the whole intent. Two intents that would serialise to
 * the same wire body have the same signature; any difference in ANY submitted
 * field produces a different one, which is the cue to mint a fresh key.
 */
export function guestOrderIntentSignature(intent: GuestOrderIntent): string {
  return JSON.stringify(intent)
}
