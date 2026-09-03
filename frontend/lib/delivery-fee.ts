/**
 * Delivery-fee PREVIEW arithmetic — the one place it is meant to live.
 *
 * This is the #718 F-3 pattern applied a second time. `lib/minimum-order.ts`
 * exists because the cart bar and checkout each computed the shortfall
 * independently; COR-2 is the same defect one column over — the basket screen
 * showed an item subtotal labelled "Total" while checkout, one tap later,
 * showed that subtotal PLUS a delivery fee. Two screens, two answers, for the
 * identical basket.
 *
 * It mirrors the server waiver EXACTLY
 * (`PublicStorefrontService.calculateDeliveryFee`): COLLECTION is always £0;
 * DELIVERY uses the shop's own fee, waived to £0 once the item subtotal clears
 * the shop's free-delivery threshold. DISPLAY ONLY — the server recomputes the
 * authoritative total on order creation, so nothing here can underpay an order.
 *
 * FEE SOURCE: `PublicShopDto` over the wire, never a literal. A hardcoded
 * "£3.50" is wrong for every other shop on the platform and silently wrong for
 * this one the day the vendor edits it.
 *
 * NOT YET THE ONLY COPY. `app/shop/[slug]/checkout/page.tsx` still exports its
 * own byte-identical `previewDeliveryFeePennies`; that file is owned by another
 * lane in this remediation round, so it could not be edited here. The follow-up
 * is three lines — delete the checkout body and re-export this one — and until
 * it lands, `lib/__tests__/delivery-fee.test.tsx` asserts the two functions
 * agree on every case, so the duplication cannot drift silently.
 */
// The union already exists on the shared API types (`types/api.ts:263`, mirroring
// the backend enum). Imported rather than re-declared: a second copy of a
// two-member union is exactly how a third member gets added in one place only.
import type { FulfilmentType } from "@/types/api"

export function previewDeliveryFeePennies(
  subtotalPennies: number,
  fulfilmentType: FulfilmentType,
  deliveryFeePennies: number | null | undefined,
  freeDeliveryThresholdPennies: number | null | undefined
): number {
  if (fulfilmentType === "COLLECTION") return 0
  const base = deliveryFeePennies ?? 0
  if (freeDeliveryThresholdPennies != null && subtotalPennies >= freeDeliveryThresholdPennies) {
    return 0
  }
  return base
}
