/**
 * What checkout remembers for the customer, and whose it is (#876 UXT-104, #845).
 *
 * TWO STORES, BOTH USER-SCOPED, so both carry an identity stamp and the stamp's LIFECYCLE is the design
 * (CLAUDE.md, "Client-persisted identity lifecycle"; the basket's rules in `cart-identity.ts`, R-16):
 *
 *   DRAFT — sessionStorage `jtoye-checkout-draft-<shop>`. The delivery address, phone, notes and the
 *   fulfilment choice typed so far, so Back then Forward does not wipe them (UXT-104). Per tab and per
 *   shop. Written as the customer types, so it is re-serialised constantly, which is exactly the shape
 *   that ate the basket's owner stamp: a signed-out render re-persisting `owner: A` as `owner: null`.
 *   So it is stamped through `resolveCartOwner`, the basket's own rule: a write ADDS or CONFIRMS an
 *   owner, never erases one, and a write during a lapse keeps the prior owner. Read through
 *   `canAdoptCart`: the owner, nobody-signed-in-right-now and an anonymous stamp may read it; a DIFFERENT
 *   signed-in customer may not.
 *
 *   LAST DELIVERY — localStorage `jtoye-last-delivery`. The delivery address and phone of the signed-in
 *   customer's last order, to prefill the next one (#845). It is the customer's home address, so it is
 *   stricter than the draft: it is written only by an identified customer (never a guest, never a live
 *   session whose identity was not recorded) and read ONLY by exactly that customer. A lapse neither
 *   erases it nor offers it. A different customer who then orders replaces it (they are signed in and
 *   writing, so they own the slot, the basket's "prior A, current B -> B" branch); nothing of A's
 *   survives in it.
 *
 * ONLY an explicit sign-out removes either (`PERSONAL_KEYS_CLEARED_ON_SIGN_OUT`, through the registry in
 * `client-storage-keys.ts`, which also puts both in the published cookie and storage policy). Nothing
 * here is called from a lapse path, and nothing is stored server-side: a server-side address store is a
 * retention and erasure decision (GdprService, the DSAR export) that belongs to the owner.
 *
 * NEVER STORED, because the type has no field for them: the allergy note (T-31.1-55, health data that
 * goes to the POST body and nowhere else), the name and email (the session and the existing per-shop email
 * key already carry those), and anything about payment.
 *
 * Through-the-transition test, asserting the STORED stamp and payload by content:
 * `lib/__tests__/checkout-memory.test.ts`.
 */
import {
  canAdoptCart,
  getCurrentCustomerId,
  hasActiveSessionMarker,
  resolveCartOwner,
} from "@/lib/cart-identity"
import type { FulfilmentType } from "@/types/api"

/** sessionStorage, one entry per shop. */
export const CHECKOUT_DRAFT_KEY_PREFIX = "jtoye-checkout-draft-"

/** localStorage, one entry per browser (an address belongs to the person, not to a shop). */
export const LAST_DELIVERY_KEY = "jtoye-last-delivery"

export interface CheckoutDraftFields {
  fulfilmentType: FulfilmentType
  address1: string
  address2: string
  city: string
  postcode: string
  phone: string
  notes: string
}

export interface LastDelivery {
  address1: string
  address2: string
  city: string
  postcode: string
  phone: string
}

/** An owner is a non-empty string or it is nothing (the basket's `validOwner`, WR-01). */
function validOwner(owner: unknown): string | null {
  return typeof owner === "string" && owner.length > 0 ? owner : null
}

function str(value: unknown): value is string {
  return typeof value === "string"
}

function draftKey(slug: string): string {
  return `${CHECKOUT_DRAFT_KEY_PREFIX}${slug}`
}

function parse(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function asDraftFields(value: unknown): CheckoutDraftFields | null {
  if (!value || typeof value !== "object") return null
  const f = value as Record<string, unknown>
  if (
    !(f.fulfilmentType === "DELIVERY" || f.fulfilmentType === "COLLECTION") ||
    !str(f.address1) ||
    !str(f.address2) ||
    !str(f.city) ||
    !str(f.postcode) ||
    !str(f.phone) ||
    !str(f.notes)
  ) {
    return null
  }
  return {
    fulfilmentType: f.fulfilmentType,
    address1: f.address1,
    address2: f.address2,
    city: f.city,
    postcode: f.postcode,
    phone: f.phone,
    notes: f.notes,
  }
}

/** The draft this tab holds for `slug`, if the current identity may read it. */
export function readCheckoutDraft(slug: string): CheckoutDraftFields | null {
  if (typeof window === "undefined") return null
  try {
    const stored = parse(window.sessionStorage.getItem(draftKey(slug)))
    if (!stored) return null
    if (!canAdoptCart(validOwner(stored.owner), getCurrentCustomerId())) return null
    return asDraftFields(stored.fields)
  } catch {
    return null
  }
}

/**
 * Persist the draft. The owner is `resolveCartOwner(prior, current, sessionActive)`: the same three
 * facts, supplied here, that the basket's write supplies. Copies exactly the declared fields, so a
 * caller that hands over more (the allergy note) cannot store more.
 */
export function writeCheckoutDraft(slug: string, fields: CheckoutDraftFields): void {
  if (typeof window === "undefined") return
  try {
    const prior = parse(window.sessionStorage.getItem(draftKey(slug)))
    const owner = resolveCartOwner(validOwner(prior?.owner), getCurrentCustomerId(), hasActiveSessionMarker())
    const payload = {
      owner,
      fields: {
        fulfilmentType: fields.fulfilmentType,
        address1: fields.address1,
        address2: fields.address2,
        city: fields.city,
        postcode: fields.postcode,
        phone: fields.phone,
        notes: fields.notes,
      },
    }
    // The prefix is spelled out (not interpolated from the constant) so the #840 source scan can resolve
    // the key; the registry names the same constant, so the two cannot drift unseen.
    window.sessionStorage.setItem(`jtoye-checkout-draft-${slug}`, JSON.stringify(payload))
  } catch {
    /* storage full or disabled: the draft is a convenience, never a requirement */
  }
}

/** The order was placed: the draft has done its job. */
export function clearCheckoutDraft(slug: string): void {
  if (typeof window === "undefined") return
  try {
    window.sessionStorage.removeItem(draftKey(slug))
  } catch {
    /* ignore */
  }
}

function asLastDelivery(stored: Record<string, unknown>): LastDelivery | null {
  if (
    !str(stored.address1) ||
    !str(stored.address2) ||
    !str(stored.city) ||
    !str(stored.postcode) ||
    !str(stored.phone)
  ) {
    return null
  }
  return {
    address1: stored.address1,
    address2: stored.address2,
    city: stored.city,
    postcode: stored.postcode,
    phone: stored.phone,
  }
}

/**
 * The signed-in customer's own last delivery details, or null. Needs a stored owner AND a current
 * customer AND the two equal: during a lapse nobody is identified, so nothing is offered (and nothing is
 * erased).
 */
export function readLastDelivery(): LastDelivery | null {
  if (typeof window === "undefined") return null
  try {
    const stored = parse(window.localStorage.getItem(LAST_DELIVERY_KEY))
    const owner = validOwner(stored?.owner)
    const current = getCurrentCustomerId()
    if (!stored || !owner || !current || owner !== current) return null
    return asLastDelivery(stored)
  } catch {
    return null
  }
}

/**
 * Remember the delivery details of an order the signed-in customer just placed. A no-op unless an
 * identified customer is writing: a guest's address is not stored, and a write that cannot say whose it
 * is must not claim the slot or erase another person's entry in it.
 */
export function rememberLastDelivery(details: LastDelivery): void {
  if (typeof window === "undefined") return
  const current = getCurrentCustomerId()
  if (!current) return
  if (!details.address1.trim() || !details.postcode.trim()) return
  try {
    window.localStorage.setItem(
      LAST_DELIVERY_KEY,
      JSON.stringify({
        owner: current,
        address1: details.address1,
        address2: details.address2,
        city: details.city,
        postcode: details.postcode,
        phone: details.phone,
      })
    )
  } catch {
    /* storage full or disabled: the prefill is a convenience */
  }
}

/**
 * Remember a delivery address ONLY when this customer has none remembered. For a source that is not the
 * order just placed (reordering an OLD order): its address is not newer than the one they last used, and
 * replacing it would have the next checkout offer the address they moved away from. Another customer's
 * entry is not "this customer's", so a signed-in customer with only someone else's entry stored takes the
 * slot, as `rememberLastDelivery` does.
 */
export function rememberLastDeliveryIfNone(details: LastDelivery): void {
  if (readLastDelivery()) return
  rememberLastDelivery(details)
}
