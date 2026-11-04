import type { CartItem } from "@/components/storefront/cart-provider"
import type { PublicProduct } from "@/types/storefront"

/**
 * "Order again" (#845): a past order's lines, planned against the shop's LIVE menu.
 *
 * The order carries what the customer bought and paid; the menu carries what is true now. So the basket is
 * built from the MENU (its price, title, image, category, VAT rate) and the order contributes only which
 * dish and how many. Nothing from the old order is trusted for money: the server reprices every order at
 * checkout (D-16), so "what changed" below is information for the customer, never a charge.
 *
 * Pure, so it is tested without a browser (lib/__tests__/reorder.test.ts).
 */

/** One line of a past order, as `GET /public/shops/{slug}/orders/{orderNumber}/reorder` returns it. */
export interface ReorderLine {
  productId: string
  productName: string
  quantity: number
  /** What the customer paid per unit, in pennies, when they ordered. */
  unitPricePennies: number
}

/**
 * Where the order was delivered, as the server returns it for the CALLER'S OWN order (null for a
 * collection order, an order with no address, or one whose personal data was erased).
 */
export interface ReorderDelivery {
  addressLine1: string
  addressLine2?: string | null
  addressCity?: string | null
  addressPostcode: string
  phone?: string | null
}

export interface ReorderResponse {
  orderNumber: string
  shopSlug: string
  lines: ReorderLine[]
  /** Absent from an older backend, which is the same as null. */
  delivery?: ReorderDelivery | null
}

export interface ReorderPlan {
  /** Lines to put in the basket: the menu's item, and how many. */
  add: Array<{ item: Omit<CartItem, "quantity">; quantity: number }>
  /** Dishes that are no longer on the menu or are sold out, by the name they were ordered under. */
  unavailable: Array<{ name: string }>
  /** Dishes added at a price different from the one paid. */
  priceChanged: Array<{ name: string; wasPennies: number; nowPennies: number }>
  /**
   * Dishes added in a smaller quantity than ordered (`got` may be 0: none added), because of the shop's
   * per-line limit, counting what the basket already holds and every line of the same dish.
   */
  reduced: Array<{ name: string; asked: number; got: number }>
}

/**
 * @param maxLineQuantity the shop's per-line limit (`PublicShop.maxLineQuantity`); null or absent is no
 *                        client limit, and the server's 422 is then the only gate. The basket merges a
 *                        dish into ONE line, so the limit is per dish: what the basket already holds and
 *                        what earlier lines of this plan add are spent before this line gets any.
 * @param inBasket        quantity already in the basket, by product id.
 */
export function planReorder(
  lines: readonly ReorderLine[],
  menu: readonly PublicProduct[],
  maxLineQuantity: number | null | undefined,
  inBasket: ReadonlyMap<string, number> = new Map()
): ReorderPlan {
  const byId = new Map(menu.map((p) => [p.id, p]))
  const plan: ReorderPlan = { add: [], unavailable: [], priceChanged: [], reduced: [] }
  // Per dish: what the basket holds, then what this plan has already put on top of it.
  const held = new Map(inBasket)

  for (const line of lines) {
    // A quantity that is not a whole number of at least one is not a line anybody ordered: it is not
    // invented into one, and it is not reported as a dish that went off the menu either.
    if (!Number.isInteger(line.quantity) || line.quantity < 1) continue

    const live = byId.get(line.productId)
    if (!live || live.inStock === false) {
      plan.unavailable.push({ name: line.productName })
      continue
    }

    const limited = typeof maxLineQuantity === "number" && maxLineQuantity > 0
    const room = limited ? Math.max(0, maxLineQuantity - (held.get(live.id) ?? 0)) : Infinity
    const quantity = Math.min(line.quantity, room)
    if (quantity < line.quantity) {
      plan.reduced.push({ name: live.title, asked: line.quantity, got: quantity })
    }
    // Nothing of this dish goes in, so it is not a price change either: the customer is told once, as reduced.
    if (quantity === 0) continue
    held.set(live.id, (held.get(live.id) ?? 0) + quantity)
    if (live.pricePennies !== line.unitPricePennies) {
      plan.priceChanged.push({ name: live.title, wasPennies: line.unitPricePennies, nowPennies: live.pricePennies })
    }
    plan.add.push({
      quantity,
      item: {
        productId: live.id,
        title: live.title,
        pricePennies: live.pricePennies,
        imageUrl: live.imageUrl,
        category: live.category,
        vatRate: live.vatRate ?? null,
      },
    })
  }
  return plan
}
