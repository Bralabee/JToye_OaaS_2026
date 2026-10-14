/**
 * 37-49 (UI-SPEC G1, UXT-023, RWO-023): which fulfilment types a shop takes, read ONE way by every
 * customer surface (the storefront header chip and the checkout control).
 *
 * The 37-48 server always sends `offersDelivery` / `offersCollection` as booleans, and refuses a type
 * the shop does not take with 422 `fulfilment-not-offered`. An ABSENT flag (an older backend) reads as
 * offered: that is the behaviour before the flags existed, and the server stays the authority either
 * way, so the worst a stale view can do is meet the typed refusal below.
 */
import type { FulfilmentType } from "@/types/api"

export interface OfferedFlags {
  offersDelivery?: boolean | null
  offersCollection?: boolean | null
}

export const FULFILMENT_NOT_OFFERED_PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-not-offered"

/** UI-SPEC § Copywriting G1/G2 "Storefront chip". */
export const ONLY_CHIP_COPY: Record<FulfilmentType, string> = {
  COLLECTION: "Collection only",
  DELIVERY: "Delivery only",
}

/** UI-SPEC § Copywriting G1/G2 "Checkout single option". */
export const SINGLE_OPTION_COPY: Record<FulfilmentType, string> = {
  COLLECTION: "Collection only. This shop doesn't deliver.",
  DELIVERY: "Delivery only. This shop doesn't offer collection.",
}

/**
 * The one type a shop takes, or null when it takes both. A payload claiming NEITHER (which the
 * server's CHECK makes unstorable) is also null: the page then shows both options and the server's
 * typed refusal decides, rather than the page inventing a single option nobody declared.
 */
export function onlyOfferedType(shop: OfferedFlags | null | undefined): FulfilmentType | null {
  if (!shop) return null
  const delivery = shop.offersDelivery !== false
  const collection = shop.offersCollection !== false
  if (delivery && !collection) return "DELIVERY"
  if (collection && !delivery) return "COLLECTION"
  return null
}

export interface FulfilmentNotOfferedProblem {
  requested: FulfilmentType
  offered: FulfilmentType[]
}

function asType(value: unknown): FulfilmentType | null {
  return value === "DELIVERY" || value === "COLLECTION" ? value : null
}

/** 37-48's 422 fulfilment-not-offered with its typed members; null when it is not one. */
export function readFulfilmentNotOfferedProblem(err: unknown): FulfilmentNotOfferedProblem | null {
  const res = (err as { response?: { status?: number; data?: unknown } } | null)?.response
  if (!res || res.status !== 422 || typeof res.data !== "object" || res.data === null) return null
  const data = res.data as Record<string, unknown>
  const type = data.type
  if (typeof type !== "string" || !(type === FULFILMENT_NOT_OFFERED_PROBLEM_TYPE || type.endsWith("/fulfilment-not-offered"))) {
    return null
  }
  const requested = asType(data.fulfilmentType)
  if (!requested) return null
  const offered = Array.isArray(data.offered)
    ? data.offered.map(asType).filter((t): t is FulfilmentType => t !== null)
    : []
  return { requested, offered }
}

/**
 * UI-SPEC § Copywriting G1/G2 "Server refusal": "This shop doesn't offer {delivery/collection}. Choose
 * {the other} to continue." Built from the typed members, never by echoing the detail, so the sentence
 * is the contract's whatever the server's prose. "Choose {the other}" is said only when the server
 * named a type it does take (the server's own rule, FulfilmentNotOfferedException).
 */
export function fulfilmentNotOfferedCopy(p: FulfilmentNotOfferedProblem): string {
  const asked = p.requested === "DELIVERY" ? "delivery" : "collection"
  const other = p.offered.find((t) => t !== p.requested)
  if (!other) return `This shop doesn't offer ${asked}.`
  return `This shop doesn't offer ${asked}. Choose ${other === "DELIVERY" ? "delivery" : "collection"} to continue.`
}
