/**
 * 31.1-23 (#860, D-18): which dish in the basket carries which DECLARED allergen.
 *
 * The rules under test, each a way the customer could be told something false:
 *   - NOT RECORDED propagates. One line the catalogue cannot resolve makes the whole attribution
 *     null, never a partial list: a partial list under-states, and under-stating injures.
 *   - Declared only. A D-09 undeclared-ingredient name or a may-contain name is never attributed as
 *     a declared allergen; D-09 names surface as their own advisory flags.
 *   - Order. Allergens in catalogue bit order (the same order the panel's chips use), dishes in
 *     basket order.
 *   - Adjacency. Two dishes sharing an allergen list it ONCE, attributed to both.
 */
import { ALLERGENS } from "@/types/api"
import type { PublicProduct } from "@/types/storefront"
import {
  attributionAgreesWith,
  basketAllergenAttribution,
  basketAllergenFlags,
  lineAllergens,
} from "@/lib/basket-allergens"

const bit = (name: string): number => {
  const entry = ALLERGENS.find((a) => a.name === name)
  if (!entry) throw new Error(`VOID: ${name} is not in the shared allergen table`)
  return 1 << entry.bit
}

function product(id: string, title: string, mask: number, extra: Partial<PublicProduct> = {}): PublicProduct {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "",
    allergenMask: mask,
    pricePennies: 500,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
    ...extra,
  }
}

const JOLLOF = product("p-jollof", "Jollof Rice", bit("Milk"))
const PUFF = product("p-puff", "Puff Puff", bit("Milk") | bit("Eggs"))
const PLANTAIN = product("p-plantain", "Plantain", 0)
const index = (...products: PublicProduct[]) => new Map(products.map((p) => [p.id, p]))

const line = (p: PublicProduct) => ({ productId: p.id, title: p.title })

describe("basketAllergenAttribution", () => {
  it("attributes each declared allergen to the dishes carrying it, allergens in catalogue order", () => {
    expect(basketAllergenAttribution([line(JOLLOF), line(PUFF)], index(JOLLOF, PUFF))).toEqual([
      { allergen: "Eggs", dishes: ["Puff Puff"] },
      { allergen: "Milk", dishes: ["Jollof Rice", "Puff Puff"] },
    ])
  })

  it("adjacency: a shared allergen is listed once, attributed to both dishes", () => {
    const result = basketAllergenAttribution([line(JOLLOF), line(PUFF)], index(JOLLOF, PUFF))
    expect(result?.filter((a) => a.allergen === "Milk")).toHaveLength(1)
  })

  it("ordering: dishes follow the BASKET order, not the catalogue's", () => {
    expect(basketAllergenAttribution([line(PUFF), line(JOLLOF)], index(JOLLOF, PUFF))).toEqual([
      { allergen: "Eggs", dishes: ["Puff Puff"] },
      { allergen: "Milk", dishes: ["Puff Puff", "Jollof Rice"] },
    ])
  })

  it("a dish that declares none of the 14 is attributed nothing, and the basket still resolves", () => {
    expect(basketAllergenAttribution([line(PLANTAIN), line(JOLLOF)], index(PLANTAIN, JOLLOF))).toEqual([
      { allergen: "Milk", dishes: ["Jollof Rice"] },
    ])
    expect(basketAllergenAttribution([line(PLANTAIN)], index(PLANTAIN))).toEqual([])
  })

  it("NOT RECORDED: any line missing from the catalogue makes the whole attribution null", () => {
    expect(
      basketAllergenAttribution([line(JOLLOF), { productId: "p-gone", title: "Gone" }], index(JOLLOF))
    ).toBeNull()
  })

  it("NOT RECORDED: no catalogue, or an empty basket, is null — never []", () => {
    expect(basketAllergenAttribution([line(JOLLOF)], null)).toBeNull()
    expect(basketAllergenAttribution([], index(JOLLOF))).toBeNull()
  })

  it("declared only: an undeclared-ingredient or may-contain name is never attributed", () => {
    const flagged = product("p-flag", "Egusi", bit("Gluten"), {
      undeclaredIngredientAllergens: ["Milk"],
      mayContainAllergens: ["Sesame"],
    })
    expect(basketAllergenAttribution([line(flagged)], index(flagged))).toEqual([
      { allergen: "Gluten", dishes: ["Egusi"] },
    ])
  })

  it("names the dish as the basket lists it", () => {
    expect(
      basketAllergenAttribution([{ productId: JOLLOF.id, title: "Jollof Rice (large)" }], index(JOLLOF))
    ).toEqual([{ allergen: "Milk", dishes: ["Jollof Rice (large)"] }])
  })
})

describe("lineAllergens", () => {
  it("one line's own statement, read from the catalogue", () => {
    expect(lineAllergens(line(PUFF), index(JOLLOF, PUFF))?.containsLine).toBe("Contains: Eggs, Milk")
  })

  it("an empty declaration reads 'No allergens declared'", () => {
    expect(lineAllergens(line(PLANTAIN), index(PLANTAIN))?.noneDeclaredLine).toBe("No allergens declared")
  })

  it("a D-09 flag is stated, and never as 'No allergens declared'", () => {
    const flagged = product("p-flag", "Egusi", 0, { undeclaredIngredientAllergens: ["Milk"] })
    const statement = lineAllergens(line(flagged), index(flagged))
    expect(statement?.undeclaredLine).toBe("Ingredients name: MILK – check with the shop")
    expect(statement?.noneDeclaredLine).toBeNull()
  })

  it("NOT RECORDED: a product the catalogue does not have, or no catalogue, is null", () => {
    expect(lineAllergens({ productId: "p-gone" }, index(JOLLOF))).toBeNull()
    expect(lineAllergens(line(JOLLOF), null)).toBeNull()
  })
})

describe("basketAllergenFlags", () => {
  it("each line's D-09 names become their own advisory flag, naming the dish", () => {
    const flagged = product("p-flag", "Egusi", 0, { undeclaredIngredientAllergens: ["Milk", "Gluten"] })
    expect(basketAllergenFlags([line(JOLLOF), line(flagged)], index(JOLLOF, flagged))).toEqual([
      { productName: "Egusi", allergenName: "Gluten", allergenBit: 0 },
      { productName: "Egusi", allergenName: "Milk", allergenBit: 6 },
    ])
  })

  it("a flag for an allergen the dish already declares adds nothing", () => {
    const flagged = product("p-flag", "Egusi", bit("Milk"), { undeclaredIngredientAllergens: ["Milk"] })
    expect(basketAllergenFlags([line(flagged)], index(flagged))).toEqual([])
  })

  it("NOT RECORDED propagates to the flags too", () => {
    expect(basketAllergenFlags([{ productId: "p-gone", title: "Gone" }], index(JOLLOF))).toBeNull()
  })
})

describe("attributionAgreesWith", () => {
  const attribution = [
    { allergen: "Eggs", dishes: ["Puff Puff"] },
    { allergen: "Milk", dishes: ["Jollof Rice", "Puff Puff"] },
  ]

  it("agrees only when it names exactly the declared set the panel shows, in order", () => {
    expect(attributionAgreesWith(attribution, ["Eggs", "Milk"])).toBe(true)
    // A stale 409 put the server's newer set on the panel: the client's attribution no longer
    // matches it, and showing it would attribute a set the panel does not state.
    expect(attributionAgreesWith(attribution, ["Eggs", "Milk", "Sesame"])).toBe(false)
    expect(attributionAgreesWith(attribution, ["Milk"])).toBe(false)
  })

  it("never agrees with NOT RECORDED", () => {
    expect(attributionAgreesWith(null, ["Milk"])).toBe(false)
    expect(attributionAgreesWith(attribution, null)).toBe(false)
  })
})
