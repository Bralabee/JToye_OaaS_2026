import type { PublicSeller } from "@/types/storefront"

// RED skeleton (31.1-24 Task 1): the exports exist so the suite compiles; nothing renders yet.
export const SELLER_BLOCK_HEADING_COPY = ""
export const PLATFORM_NOT_SELLER_COPY = ""
export const CANCELLATION_STATEMENT_COPY = ""
export const SELLER_DETAILS_MISSING_COPY = ""
export const PLATFORM_CONTACT_INTRO_COPY = ""
export const PLATFORM_CONTACT_LINK_COPY = ""
export const SELLER_ENTITY_TYPE_LABELS: Record<PublicSeller["entityType"], string> = {
  COMPANY: "",
  SOLE_TRADER: "",
  PARTNERSHIP: "",
}

export interface SellerBlockProps {
  seller: PublicSeller | null | undefined
  id?: string
  className?: string
}

export function SellerBlock(_props: SellerBlockProps) {
  return null
}
