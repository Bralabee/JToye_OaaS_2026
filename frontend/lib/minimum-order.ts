/**
 * Minimum-order arithmetic — the ONE place it lives (#718 review F-3: the cart
 * bar and checkout each computed the shortfall independently; a rule change —
 * delivery-fee inclusion, rounding — would have had to be found twice, and a
 * miss shows the customer two different amounts on consecutive screens).
 *
 * The minimum is judged against the ITEM subtotal, delivery fee excluded —
 * mirroring the server's authoritative gate in createGuestOrder (WR-01).
 */
export function minimumShortfallPennies(
  subtotalPennies: number,
  minimumOrderPennies: number
): number | null {
  if (minimumOrderPennies <= 0 || subtotalPennies >= minimumOrderPennies) return null
  return minimumOrderPennies - subtotalPennies
}
