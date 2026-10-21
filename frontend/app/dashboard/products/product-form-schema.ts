import { z } from "zod"

/**
 * The products form schema (QA council 20260902-134741, A11Y-8 + A11Y-11).
 * Lives in its own module because a Next App Router page.tsx may not export
 * non-route symbols, and the schema is the form's single source of truth
 * (zodResolver) — so it is what gets unit-tested.
 */

/**
 * Upper bound on a price, in pence: the server's own
 * CreateProductRequest @Max(1_000_000_000L, "Price must not exceed
 * £10,000,000"), restated once here so the client rejects before the round
 * trip. Before this there was no bound at all and "99999999999999" produced
 * pricePennies 9999999999999900 — past Number.MAX_SAFE_INTEGER before
 * serialisation (A11Y-11).
 */
export const MAX_PRICE_PENNIES = 1_000_000_000

/**
 * Pounds with optional pence, ANCHORED: "12", "12.5", "12.50". parseFloat
 * stopped at the first bad character ("5abc" -> 5) and accepted exponent
 * notation ("1e3" -> 1000); an anchored pattern rejects both. Negative and
 * sub-penny values are also rejected here rather than left to the browser's
 * type="number" min/step (which already blocks them — this makes the schema
 * the authority regardless of how the value arrives).
 */
const PRICE_POUNDS = /^\d{1,8}(\.\d{1,2})?$/

/** The pennies value the form submits for a validated `pricePounds` string. */
export const toPricePennies = (pricePounds: string): number => Math.round(Number(pricePounds) * 100)

export const productSchema = z.object({
  // .trim() BEFORE .min(1): "     " used to pass every one of these (A11Y-8),
  // and a blank ingredientsText feeds the V63 advisory allergen reconciliation
  // as "declared" rather than "missing". .max() stays after .trim() so a
  // space-padded 200-char title is not rejected.
  sku: z.string().trim().min(1, "SKU is required").max(50, "SKU too long"),
  title: z.string().trim().min(1, "Title is required").max(200, "Title too long"),
  ingredientsText: z
    .string()
    .trim()
    .min(1, "Ingredients are required")
    .max(1000, "Ingredients text too long"),
  pricePounds: z
    .string()
    .min(1, "Price is required")
    .regex(PRICE_POUNDS, "Enter a price like 12.50")
    .refine(
      (val) => {
        const pennies = toPricePennies(val)
        return Number.isSafeInteger(pennies) && pennies <= MAX_PRICE_PENNIES
      },
      "Price must not exceed £10,000,000"
    ),
})

export type ProductFormData = z.infer<typeof productSchema>

/**
 * D-16 (31.1-14): the "may contain" (cross-contact) mask, the same 14-bit FSA layout as the
 * declared allergenMask and the server's CreateProductRequest @Min(0) @Max(16383).
 */
export const MAY_CONTAIN_MASK_MAX = 16383

/**
 * The may-contain value the form submits. `null` is "not recorded" and is NOT the same as 0
 * ("the vendor recorded no cross-contact risk"); the schema keeps both. It is held in page
 * state (checkboxes), not a registered field, so it is parsed at submit rather than by the
 * form resolver.
 */
export const mayContainMaskSchema = z
  .number()
  .int()
  .min(0)
  .max(MAY_CONTAIN_MASK_MAX)
  .nullable()

/**
 * #960 (swarm SW-ADE-03): the two inputs the allergen (PPDS / Natasha's Law) label is dated from.
 * The server stores them as `shelf_life_days` and `durability_type` (V41) and takes them as
 * CreateProductRequest.shelfLifeDays (@Min(0) @Max(3650)) and durabilityType (USE_BY | BEST_BEFORE);
 * the label endpoint answers 422 without both.
 */
export const SHELF_LIFE_MAX_DAYS = 3650

export const DURABILITY_TYPES = ["USE_BY", "BEST_BEFORE"] as const
export type DurabilityType = (typeof DURABILITY_TYPES)[number]

export const DURABILITY_PAIR_COPY =
  "Add a shelf life and choose use-by or best-before together, so the label can print a date."
export const SHELF_LIFE_RANGE_COPY = `Enter a whole number of days, from 0 to ${SHELF_LIFE_MAX_DAYS}.`

export type DurabilityInputs =
  | { ok: true; shelfLifeDays?: number; durabilityType?: DurabilityType }
  | { ok: false; error: string }

/**
 * Reads the two inputs as typed. Both blank is valid and means "not recorded" (nothing is sent, and an
 * edit keeps what is stored); one without the other is refused, because a label needs both; the
 * shelf life must be a whole number of days in range. Anchored like the price pattern: "3abc", "1e2",
 * "2.5" and "-1" are all rejected.
 */
export function parseDurabilityInputs(shelfLifeText: string, durabilityType: string): DurabilityInputs {
  const text = shelfLifeText.trim()
  if (text === "" && durabilityType === "") return { ok: true }
  if (text === "" || durabilityType === "") return { ok: false, error: DURABILITY_PAIR_COPY }
  if (!/^\d{1,4}$/.test(text)) return { ok: false, error: SHELF_LIFE_RANGE_COPY }
  const days = Number(text)
  if (days > SHELF_LIFE_MAX_DAYS) return { ok: false, error: SHELF_LIFE_RANGE_COPY }
  if (!(DURABILITY_TYPES as readonly string[]).includes(durabilityType)) {
    return { ok: false, error: DURABILITY_PAIR_COPY }
  }
  return { ok: true, shelfLifeDays: days, durabilityType: durabilityType as DurabilityType }
}
