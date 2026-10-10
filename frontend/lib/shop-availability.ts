import type { PublicShop } from "@/types/storefront"

/** RED stub (37-22 Task 1): the reader is written in the GREEN commit. */
export type ShopAvailability =
  | { kind: "unknown" }
  | { kind: "open" }
  | { kind: "paused"; reopensAt: string | null }
  | { kind: "closed"; nextOpenAt: string | null }

type AvailabilityFields = Pick<PublicShop, "openNow" | "nextOpenAt" | "pausedUntil" | "pausedIndefinitely">

export function readShopAvailability(_shop: AvailabilityFields): ShopAvailability {
  return { kind: "unknown" }
}
