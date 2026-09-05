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
