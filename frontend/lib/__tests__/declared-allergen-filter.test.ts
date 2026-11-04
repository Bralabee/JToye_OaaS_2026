import {
  DECLARED_FILTER_HELP,
  DECLARED_FILTER_LABEL,
  DECLARED_FILTER_OPTIONS,
  declaredFilterActiveNote,
  isFreeFromQuery,
} from "@/lib/declared-allergen-filter"
import { ALLERGENS } from "@/types/api"

/** #862 (owner ruling 2026-11-04): "declares", never "free from". */
describe("declared-allergen filter wording and query reading", () => {
  it("the options ARE the 14 UK FSA allergens, in catalogue order (names from the one shared table)", () => {
    expect(DECLARED_FILTER_OPTIONS).toHaveLength(14)
    expect(DECLARED_FILTER_OPTIONS.map((o) => o.name)).toEqual(ALLERGENS.map((a) => a.name))
  })

  it("every user-facing string says 'declare(d)' and none asserts absence of an allergen", () => {
    const copy = [
      DECLARED_FILTER_LABEL,
      DECLARED_FILTER_HELP,
      declaredFilterActiveNote("Peanuts", "kitchens"),
      declaredFilterActiveNote("Peanuts", "dishes"),
    ]
    expect(copy.join(" ")).toMatch(/declare/i)
    for (const line of copy) {
      expect(line).not.toMatch(/\bfree from\b|allergen[- ]free|\bsafe\b|guarantee/i)
    }
    // The help may deny, and must.
    expect(DECLARED_FILTER_HELP).toMatch(/not confirmed free of it/i)
  })

  it.each([
    "gluten free",
    "gluten-free",
    "Peanut free",
    "free from nuts",
    "without milk",
    "no sesame",
    "dairy free",
  ])("%s is read as an ABSENCE request", (q) => {
    expect(isFreeFromQuery(q)).toBe(true)
  })

  it.each(["peanut", "gluten", "jollof", "peanut stew", "vegan", "free delivery", "sugar free", ""])(
    "%s is not an absence request",
    (q) => {
      expect(isFreeFromQuery(q)).toBe(false)
    }
  )
})
