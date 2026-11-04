/**
 * The panel a customer sees on a CANCELLED order (#820, owner ruling 2026-11-04): that it was cancelled, why
 * in the shop's category words, and how to call the shop.
 *
 * `reason` is the server's plain-English sentence for the cancel category (never a code, never free text);
 * absent means none was recorded, and then no reason is claimed. `phone` is the shop's public phone;
 * absent means the shop has none, and then the older "contact the shop" sentence stands in. A phone is
 * rendered as a tap-to-call link whose href keeps only digits and a leading +.
 */
export function CancelledNotice({
  reason,
  phone,
  shopName,
}: {
  reason?: string | null
  phone?: string | null
  shopName?: string | null
}) {
  const phoneText = phone?.trim() ? phone.trim() : null
  const dial = phoneText ? phoneText.replace(/[^\d+]/g, "") : ""
  const name = shopName?.trim() || "the shop"
  return (
    <div
      data-testid="cancelled-notice"
      className="rounded-xl bg-red-50 border border-red-100 p-5 mb-6 text-center space-y-1"
    >
      <p className="text-sm font-medium text-red-800">This order was cancelled.</p>
      {reason ? <p className="text-sm text-red-800">{reason}</p> : null}
      {phoneText && dial ? (
        <p className="text-sm text-red-800">
          If this was unexpected,{" "}
          <a
            href={`tel:${dial}`}
            className="inline-flex min-h-11 items-center font-semibold underline underline-offset-2"
          >
            call {name} on {phoneText}
          </a>
          .
        </p>
      ) : (
        <p className="text-sm text-red-800">If this was unexpected, please contact the shop.</p>
      )}
    </div>
  )
}
