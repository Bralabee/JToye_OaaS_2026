/**
 * 31.1-19 (#787, #861; D-09, D-16, D-18) — the one source of the allergen phrases the vendor
 * and the storefront surfaces print.
 *
 * The strings are asserted VERBATIM. A paraphrase in passing is the failure this module exists
 * to stop: the persona saw "No allergens" for a dish whose ingredients said MILK, and the
 * wording that replaces it has to be the same wherever it appears.
 *
 * The forbidden-phrase arm reads the module SOURCE, not only the exported values, so a phrase
 * added in a comment, an unexported constant or a new export is caught too.
 */
import { readFileSync } from "fs"
import path from "path"
import {
  CONTAINS_LABEL_COPY,
  NO_ALLERGENS_DECLARED_COPY,
  INGREDIENTS_EMPHASIS_HELP_COPY,
  MAY_CONTAIN_HELP_COPY,
  KEEP_AS_IS_COPY,
  containsCopy,
  mayContainCopy,
  tickAllergenCopy,
  undeclaredIngredientCopy,
  undeclaredIngredientVendorCopy,
  vendorSaveWarningCopy,
  ALLERGY_NOTE_LABEL_COPY,
  ALLERGY_NOTE_ACK_BUTTON_COPY,
  ALLERGY_NOTE_NOT_READ_PRINT_COPY,
  ALLERGY_NOTE_READER_YOU_COPY,
  ALLERGY_NOTE_READER_STAFF_COPY,
  ALLERGY_NOTE_ACK_FORBIDDEN_COPY,
  ALLERGY_NOTE_ACK_FAILED_COPY,
  allergyNoteReadCopy,
  customerConfirmedAllergensCopy,
  customerConfirmedNoneCopy,
  VENDOR_PLACED_ORDER_COPY,
  CUSTOMER_CONFIRMATION_NOT_RECORDED_COPY,
} from "@/lib/allergen-copy"

const MODULE_PATH = path.join(__dirname, "..", "allergen-copy.ts")

// Claims the platform can never stand behind: an absence of DECLARED allergens is not an
// absence of allergens, and nothing here knows who the reader is.
const FORBIDDEN = [/allergen[-\s]free/i, /safe for you/i, /no allergens present/i]

describe("D-09: the undeclared-ingredient wording names the allergen as the vendor typed it", () => {
  it("storefront form: 'Ingredients name: MILK – check with the shop' (the en dash is part of it)", () => {
    expect(undeclaredIngredientCopy(["Milk"])).toBe("Ingredients name: MILK – check with the shop")
    expect(undeclaredIngredientCopy(["Milk"])).toContain("–")
  })

  it("vendor form: 'Ingredients name: MILK – not ticked'", () => {
    expect(undeclaredIngredientVendorCopy(["Milk"])).toBe("Ingredients name: MILK – not ticked")
  })

  it("names every allergen, in the order given, never a bare count", () => {
    expect(undeclaredIngredientCopy(["Milk", "Eggs"])).toBe(
      "Ingredients name: MILK, EGGS – check with the shop"
    )
    expect(undeclaredIngredientVendorCopy(["Cereals containing gluten"])).toBe(
      "Ingredients name: CEREALS CONTAINING GLUTEN – not ticked"
    )
    expect(undeclaredIngredientCopy(["Milk", "Eggs"])).not.toMatch(/\d/)
  })

  it("has nothing to say when nothing disagrees (null, not an empty sentence)", () => {
    expect(undeclaredIngredientCopy([])).toBeNull()
    expect(undeclaredIngredientVendorCopy([])).toBeNull()
  })

  it("the save-time warning names the allergen twice, as the vendor reads it on the form", () => {
    expect(vendorSaveWarningCopy("Milk")).toBe("The ingredients mention Milk, but Milk is not ticked")
    expect(tickAllergenCopy("Milk")).toBe("Tick Milk")
    expect(KEEP_AS_IS_COPY).toBe("Keep as it is")
  })
})

describe("D-18: an empty declaration says what was declared, not what the food contains", () => {
  it("NO_ALLERGENS_DECLARED_COPY is 'No allergens declared'", () => {
    expect(NO_ALLERGENS_DECLARED_COPY).toBe("No allergens declared")
  })
})

describe("D-18: the declared set in words, on the menu card and the dish modal (31.1-21)", () => {
  it("'Contains: Gluten, Milk' in the order given, null for an empty declaration", () => {
    expect(containsCopy(["Gluten", "Milk"])).toBe("Contains: Gluten, Milk")
    expect(containsCopy(["Milk"])).toBe("Contains: Milk")
    expect(containsCopy([])).toBeNull()
  })

  it("the list label is 'Contains'", () => {
    expect(CONTAINS_LABEL_COPY).toBe("Contains")
  })
})

describe("D-16: may contain is its own line", () => {
  it("'May contain: Sesame' for one, joined for several, null for none", () => {
    expect(mayContainCopy(["Sesame"])).toBe("May contain: Sesame")
    expect(mayContainCopy(["Peanuts", "Sesame"])).toBe("May contain: Peanuts, Sesame")
    expect(mayContainCopy([])).toBeNull()
  })

  it("the vendor helper text says these are not ingredients", () => {
    expect(MAY_CONTAIN_HELP_COPY).toBe(
      "Allergens that may get into this dish from your kitchen, shown separately to customers. These are not ingredients."
    )
  })
})

describe("Pitfall 6: the ingredients helper text teaches the two emphasis forms the server reads", () => {
  it("mentions CAPITALS and **double asterisks**", () => {
    expect(INGREDIENTS_EMPHASIS_HELP_COPY).toContain("CAPITALS")
    expect(INGREDIENTS_EMPHASIS_HELP_COPY).toContain("**double asterisks**")
  })
})

describe("D-15 (31.1-22): the allergy note the kitchen must mark as read", () => {
  it("the label is uppercase in the string itself, not only in a stylesheet", () => {
    expect(ALLERGY_NOTE_LABEL_COPY).toBe("ALLERGY NOTE")
    expect(ALLERGY_NOTE_NOT_READ_PRINT_COPY).toBe("NOT YET MARKED AS READ")
  })

  it("the action says what pressing it records", () => {
    expect(ALLERGY_NOTE_ACK_BUTTON_COPY).toBe("Mark allergy note as read")
  })

  it("'Read by kim at 18:05', and 'Read at 18:05' when nobody can be named", () => {
    expect(allergyNoteReadCopy("kim", "18:05")).toBe("Read by kim at 18:05")
    expect(allergyNoteReadCopy(ALLERGY_NOTE_READER_YOU_COPY, "18:05")).toBe("Read by you at 18:05")
    expect(allergyNoteReadCopy(ALLERGY_NOTE_READER_STAFF_COPY, "18:05 on 4 Oct")).toBe(
      "Read by a member of staff at 18:05 on 4 Oct"
    )
    expect(allergyNoteReadCopy(null, "18:05")).toBe("Read at 18:05")
  })

  it("a refusal and a failure each say the note is NOT marked as read", () => {
    expect(ALLERGY_NOTE_ACK_FORBIDDEN_COPY).toBe(
      "Not marked as read: your account cannot act on this shop's orders."
    )
    expect(ALLERGY_NOTE_ACK_FAILED_COPY).toBe(
      "Not marked as read: the request did not go through. Try again."
    )
  })
})

describe("D-07 (31.1-22): how the order was placed, said to the vendor", () => {
  it("the confirmed set, with when, in the order the server gave", () => {
    expect(customerConfirmedAllergensCopy(["Gluten", "Milk"], "3 October 2026, 18:02")).toBe(
      "Customer confirmed allergens: Gluten, Milk — 3 October 2026, 18:02"
    )
    expect(customerConfirmedAllergensCopy(["Milk"], null)).toBe("Customer confirmed allergens: Milk")
  })

  it("a confirmation of a declared-none basket is a statement, not an absence", () => {
    expect(customerConfirmedNoneCopy("3 October 2026, 18:02")).toBe(
      "Customer confirmed the order declared none of the 14 regulated allergens — 3 October 2026, 18:02"
    )
  })

  it("vendor-placed and not-recorded are different sentences", () => {
    expect(VENDOR_PLACED_ORDER_COPY).toBe("Placed by the shop — no customer allergen confirmation recorded")
    expect(CUSTOMER_CONFIRMATION_NOT_RECORDED_COPY).toBe("Customer allergen confirmation: not recorded")
  })
})

describe("no phrase the platform cannot stand behind appears anywhere in the module", () => {
  const source = readFileSync(MODULE_PATH, "utf8")

  it("control: the source was read (it contains its own named export)", () => {
    expect(source).toContain("NO_ALLERGENS_DECLARED_COPY")
  })

  it.each(FORBIDDEN.map((re) => [re.source, re] as const))("absent: %s", (_name, re) => {
    expect(source).not.toMatch(re)
  })
})
