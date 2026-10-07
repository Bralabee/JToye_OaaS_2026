import { z } from "zod"
import type { TraderEntityType } from "@/types/api"

/**
 * #789 (31.1-10): the client mirror of the server's UpdateTraderIdentityRequest rules, so
 * the vendor hears about a typo before the round trip. The SERVER is the authority: its
 * field errors are still rendered (a rule the client misses is caught there).
 *
 * The patterns are restated, not invented, from core-java TraderIdentityFields:
 * - postcode: UK shape, any case, up to four spaces between outward and inward code;
 * - VAT: GB followed by exactly 9 or exactly 12 digits, any case, optional single spaces.
 * .trim() runs BEFORE .min()/.max(), matching the server, which strips before validating:
 * a name of spaces is blank, and a space-padded 255-character name is allowed.
 */
export const POSTCODE_PATTERN = /^[A-Za-z]{1,2}[0-9][A-Za-z0-9]?\s{0,4}[0-9][A-Za-z]{2}$/
export const VAT_PATTERN = /^[Gg][Bb](\s?[0-9]){9}((\s?[0-9]){3})?$/

export const ENTITY_TYPE_OPTIONS: ReadonlyArray<{ value: TraderEntityType; label: string }> = [
  { value: "COMPANY", label: "Limited company" },
  { value: "SOLE_TRADER", label: "Sole trader" },
  { value: "PARTNERSHIP", label: "Partnership" },
]

const ENTITY_TYPES: readonly string[] = ENTITY_TYPE_OPTIONS.map((o) => o.value)

export const traderIdentitySchema = z.object({
  legalName: z
    .string()
    .trim()
    .min(1, "Enter the legal name customers buy from")
    .max(255, "The legal name must be 255 characters or fewer"),
  entityType: z.string().refine((v) => ENTITY_TYPES.includes(v), "Choose your business type"),
  addressLine1: z
    .string()
    .trim()
    .min(1, "Enter the first line of the address")
    .max(255, "Address line 1 must be 255 characters or fewer"),
  addressLine2: z.string().trim().max(255, "Address line 2 must be 255 characters or fewer"),
  addressCity: z
    .string()
    .trim()
    .min(1, "Enter the town or city")
    .max(120, "The town or city must be 120 characters or fewer"),
  addressPostcode: z.string().trim().regex(POSTCODE_PATTERN, "Enter a UK postcode, like SW1A 1AA"),
  vatNumber: z
    .string()
    .trim()
    .refine((v) => v === "" || VAT_PATTERN.test(v), "Enter GB followed by 9 or 12 digits, or leave it empty"),
})

export type TraderIdentityFormValues = z.infer<typeof traderIdentitySchema>

/** Field order on the page: the first of these with an error receives focus. */
export const FIELD_ORDER: ReadonlyArray<keyof TraderIdentityFormValues> = [
  "legalName",
  "entityType",
  "addressLine1",
  "addressLine2",
  "addressCity",
  "addressPostcode",
  "vatNumber",
]

export const EMPTY_VALUES: TraderIdentityFormValues = {
  legalName: "",
  entityType: "",
  addressLine1: "",
  addressLine2: "",
  addressCity: "",
  addressPostcode: "",
  vatNumber: "",
}
