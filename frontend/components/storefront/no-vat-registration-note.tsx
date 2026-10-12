/**
 * UXT-047 (#816, 37-14, UI-SPEC § B7). A VAT row renders only for a seller who DECLARED a VAT
 * registration (`shop.seller.vatNumber`, 31.1 V71). For a seller who declared none, the row is
 * omitted and this one line sits under Total instead: a "VAT (incl. 20%)" line under a seller below
 * the registration threshold would be a false statement about the price (T-37-38).
 *
 * Shared by every screen that shows an order breakdown — the checkout's step 1 and card payment
 * summary, and (37-37, UI-SPEC C3) the order page the cash confirmation moved to — so the wording
 * is defined once. Display only: nothing here says anything about how the ledger computes VAT.
 */
export const NO_VAT_REGISTRATION_COPY = "This seller hasn't declared a VAT registration, so no VAT is shown."

export function NoVatRegistrationNote() {
  return <p className="text-sm text-slate-600">{NO_VAT_REGISTRATION_COPY}</p>
}
