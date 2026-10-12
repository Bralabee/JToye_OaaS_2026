/**
 * UnverifiedContactBadge — 37-30 (UI-SPEC § K1 / § D1; D-18; F20).
 *
 * RED scaffold: the exported names exist so the suites load and fail on their planned
 * assertions rather than on a missing module. The GREEN commit replaces this file.
 */

export const UNVERIFIED_CONTACT_COPY = "Unverified contact"
export const UNVERIFIED_CONTACT_TITLE = "This customer's email or phone hasn't been confirmed."

export function UnverifiedContactBadge(_props: { contactVerified?: boolean | null; className?: string }) {
  return null
}

export default UnverifiedContactBadge
