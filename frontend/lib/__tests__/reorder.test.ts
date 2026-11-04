/**
 * #845: turning a past order's lines into a basket, against the shop's LIVE menu, and saying what changed.
 *
 * The lines carry what the customer paid; the menu carries what is true now. Nothing from the old order is
 * trusted for money: the basket takes the menu's price, the title and the VAT rate, and the server reprices
 * every order at checkout anyway (D-16). So "what changed" is information, never a charge.
 */
import { planReorder, type ReorderLine } from "@/lib/reorder"
import type { PublicProduct } from "@/types/storefront"

function product(partial: Partial<PublicProduct> & { id: string; title: string; pricePennies: number }): PublicProduct {
  return {
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "",
    allergenMask: 0,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
    ...partial,
  }
}

const line = (productId: string, productName: string, quantity: number, unitPricePennies: number): ReorderLine => ({
  productId,
  productName,
  quantity,
  unitPricePennies,
})

describe("planReorder", () => {
  const MENU = [
    product({ id: "p1", title: "Jollof Rice", pricePennies: 1000, imageUrl: "https://img/j.webp", vatRate: "ZERO" }),
    product({ id: "p2", title: "Moin Moin", pricePennies: 800 }),
    product({ id: "p3", title: "Suya", pricePennies: 1200, inStock: false }),
  ]

  it("adds every line still on the menu at the MENU's price, title and image, in the quantity ordered", () => {
    const plan = planReorder([line("p1", "Jollof Rice", 2, 1000)], MENU, null)
    expect(plan.add).toEqual([
      {
        quantity: 2,
        item: { productId: "p1", title: "Jollof Rice", pricePennies: 1000, imageUrl: "https://img/j.webp", category: "Mains", vatRate: "ZERO" },
      },
    ])
    expect(plan.unavailable).toEqual([])
    expect(plan.priceChanged).toEqual([])
    expect(plan.reduced).toEqual([])
  })

  it("a dish that is not on the menu any more, or is sold out, is listed as unavailable by its ORDERED name and not added", () => {
    const plan = planReorder([line("p3", "Suya (old name)", 1, 1200), line("gone", "Egusi", 1, 900), line("p1", "Jollof Rice", 1, 1000)], MENU, null)
    expect(plan.add.map((a) => a.item.productId)).toEqual(["p1"])
    expect(plan.unavailable).toEqual([{ name: "Suya (old name)" }, { name: "Egusi" }])
  })

  it("a changed price is reported as paid-then and now, and the basket takes the new one", () => {
    const plan = planReorder([line("p2", "Moin Moin", 1, 700)], MENU, null)
    expect(plan.add[0].item.pricePennies).toBe(800)
    expect(plan.priceChanged).toEqual([{ name: "Moin Moin", wasPennies: 700, nowPennies: 800 }])
  })

  it("an unchanged price is not reported", () => {
    expect(planReorder([line("p1", "Jollof Rice", 1, 1000)], MENU, null).priceChanged).toEqual([])
  })

  it("a quantity over the shop's per-line limit is reduced to it, and said", () => {
    const plan = planReorder([line("p1", "Jollof Rice", 30, 1000)], MENU, 20)
    expect(plan.add[0].quantity).toBe(20)
    expect(plan.reduced).toEqual([{ name: "Jollof Rice", asked: 30, got: 20 }])
  })

  it("no limit sent (null or absent) means no client limit, the server's 422 being the only gate", () => {
    expect(planReorder([line("p1", "Jollof Rice", 30, 1000)], MENU, null).add[0].quantity).toBe(30)
    expect(planReorder([line("p1", "Jollof Rice", 30, 1000)], MENU, undefined).add[0].quantity).toBe(30)
  })

  it("a line with a nonsense quantity is not added and not invented (0, negative, fractional)", () => {
    const plan = planReorder([line("p1", "Jollof Rice", 0, 1000), line("p2", "Moin Moin", -2, 800), line("p1", "Jollof Rice", 1.5, 1000)], MENU, null)
    expect(plan.add).toEqual([])
  })

  it("an empty order, or an empty menu, adds nothing and lists every line as unavailable", () => {
    expect(planReorder([], MENU, null)).toEqual({ add: [], unavailable: [], priceChanged: [], reduced: [] })
    const plan = planReorder([line("p1", "Jollof Rice", 1, 1000)], [], null)
    expect(plan.add).toEqual([])
    expect(plan.unavailable).toEqual([{ name: "Jollof Rice" }])
  })

  it("the same product on two lines is two additions (the basket merges them)", () => {
    const plan = planReorder([line("p1", "Jollof Rice", 1, 1000), line("p1", "Jollof Rice", 2, 1000)], MENU, null)
    expect(plan.add.map((a) => a.quantity)).toEqual([1, 2])
  })
})
