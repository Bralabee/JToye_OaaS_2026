/**
 * #841 / UXT-072: plausibility checks for the contact details a customer gives at checkout, mirroring core's
 * `ContactPlausibility` case for case (contact-and-radius.test.ts runs the same fixtures on both sides).
 *
 * The aim is to refuse values that can never reach anybody ("x", "0", "call me", name@example) so the shop is
 * not left with an order it cannot chase. It is NOT verification, and it does not judge what a name looks like:
 * two letters in any script is the whole name rule. Core stays the authority and answers a typed 400 for the
 * same values; this gives the message before the round trip.
 */

export const CONTACT_COPY = {
  name: "Enter your name (at least two letters).",
  email: "Enter a valid email address, like name@example.com.",
  phone: "Enter a valid phone number, like 07700 900123 or +44 7700 900123.",
} as const

const EMAIL =
  /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\.[A-Za-z]{2,}$/
const PHONE_CHARS = /^\+?[0-9\s().-]+$/

export function isPlausibleName(name: string): boolean {
  const letters = name.match(/\p{L}/gu)
  return (letters?.length ?? 0) >= 2
}

export function isPlausibleEmail(email: string): boolean {
  return email.length <= 254 && EMAIL.test(email.trim())
}

/** UK national / +44 / 0044, or any other international number in + form (8 to 15 digits). */
export function isPlausiblePhone(phone: string): boolean {
  const trimmed = phone.trim()
  if (trimmed === "" || !PHONE_CHARS.test(trimmed)) return false
  let plus = trimmed.startsWith("+")
  let digits = trimmed.replace(/\D/g, "")
  if (digits === "" || /^(\d)\1*$/.test(digits)) return false
  if (!plus && digits.startsWith("00")) {
    digits = digits.slice(2)
    plus = true
  }
  if (plus) {
    if (digits.startsWith("44")) {
      let national = digits.slice(2)
      if (national.startsWith("0")) national = national.slice(1) // +44 (0)7700 900123
      const mobile = national.startsWith("7") // UK mobiles are 07 + 9 digits, always
      return national.length >= 9 && national.length <= 10 && !national.startsWith("0") && (!mobile || national.length === 10)
    }
    return digits.length >= 8 && digits.length <= 15 && !digits.startsWith("0")
  }
  const ukMobile = digits.startsWith("07")
  return (
    digits.startsWith("0") &&
    (digits.length === 10 || digits.length === 11) &&
    digits[1] !== "0" &&
    (!ukMobile || digits.length === 11)
  )
}
