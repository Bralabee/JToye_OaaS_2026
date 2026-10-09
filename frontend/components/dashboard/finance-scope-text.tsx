import { scopeShopLabel, type FinanceScope } from "@/lib/finance-api"

/**
 * "Showing **{Shop}** only" / "Showing **all shops**" (37-12, UI-SPEC § Copywriting B4-B6),
 * built ONLY from the scope the server echoed. Shared by the finance page banner and the
 * dashboard home tile footnote so the two can never word the same scope differently.
 */
export function FinanceScopeText({ scope }: { scope: FinanceScope }) {
  const shop = scopeShopLabel(scope)
  if (shop === null) {
    return (
      <>
        Showing <strong className="font-semibold text-slate-900">all shops</strong>
      </>
    )
  }
  return (
    <>
      Showing <strong className="font-semibold text-slate-900">{shop}</strong> only
    </>
  )
}

/** The manager-on-All-shops copy (§ Copywriting B4-B6 "Finance: manager on All shops"). */
export const CHOOSE_SHOP_HEADING = "Choose a shop to see its figures"
export const CHOOSE_SHOP_BODY = "Pick one of your shops in the shop switcher at the top of the page."
