"use client"

import { Store, Info } from "lucide-react"

/**
 * Says, unmissably, whose tickets are on this board (#450 sub-item 5d).
 *
 * THE DEFECT. In the All-shops context the board fell back to `shops[0].id`
 * (`kitchen/page.tsx:209-222`) and rendered that one shop's tickets. Nothing said so.
 * Measured on the live stack on 2026-08-04 with three published shops: the sidebar
 * switcher read "All shops", the board issued exactly one request —
 * `?shopId=97d95aa4…` (Brixton Village Grill) — and every ticket on screen was
 * Brixton's. The other two shops' kitchens were invisible, and the interface asserted
 * the opposite. That is a trust defect, not a cosmetic one: a vendor reading an empty
 * board concludes there are no orders.
 *
 * THE DECISION — label, not aggregate. Full reasoning is in the PR; the short form is
 * that a KDS is a physical screen in ONE kitchen, the live subscription
 * (`/topic/kitchen.{tenant}.{shop}`) is per shop, and merging three shops' tickets
 * onto one board would put Peckham's orders in front of Brixton's cooks. So the board
 * stays single-shop — which it already was — and stops pretending otherwise.
 *
 * Three parts, because they answer different questions:
 *   `KdsBoardShopName`      what am I looking at?     — always on, in the header.
 *   `KdsAllShopsNotice`     why isn't this all of it? — only in the All-shops context.
 *   `KdsOtherShopNotice`    why isn't this the shop   — only when the board could not
 *                           I asked for?               honour the switcher.
 */

/**
 * #556 — the loading state is a THIRD state, and it used to borrow the empty one's voice.
 *
 * `kitchen/page.tsx` renders this twice: once in the loading early-return and once in the
 * loaded body. Both passed through the branch below, so while data was in flight the
 * header said **"No shop selected"** — which is not merely unhelpful, it is false. A
 * vendor whose shop is loading reads that the board has no shop, and a screen reader
 * announces it as settled fact.
 *
 * It also broke tests in a way that looked like a product bug. Both renders carried the
 * same `data-testid`, and Next server-renders this client component's loading state and
 * swaps it after hydration, so both trees are briefly in the DOM. Playwright strict mode
 * then found two elements and resolved the stale one — reproducibly on desktop, and
 * intermittently on mobile, which is the worst combination to diagnose.
 *
 * So `loading` is now explicit and carries its OWN testid. The three states are
 * distinguishable by any consumer — a test, a screen reader, a future component — rather
 * than only by which of two identical hooks you happened to grab.
 */
export function KdsBoardShopName({
  shopName,
  loading = false,
}: {
  shopName: string | null
  loading?: boolean
}) {
  return (
    <p
      // Distinct testid per state, deliberately. A single id spanning "still loading" and
      // "loaded, nothing selected" cannot express the difference, and that is exactly the
      // ambiguity #556 was filed for.
      data-testid={loading ? "kds-board-shop-loading" : "kds-board-shop"}
      className="mt-1 flex items-center gap-2 text-slate-600"
    >
      <Store aria-hidden className="h-4 w-4 flex-shrink-0" />
      <span>
        {loading ? (
          "Loading shop…"
        ) : shopName ? (
          <>
            Showing tickets for{" "}
            <span className="font-semibold text-slate-900">{shopName}</span>
          </>
        ) : (
          "No shop selected"
        )}
      </span>
    </p>
  )
}

/**
 * The board could not honour the shop the dashboard is set to (#450 sub-item 5d, the
 * half PR #535 left open).
 *
 * #535 closed the All-shops case: the switcher says "All shops", the board shows one,
 * and it now says which. It did NOT close the case where the switcher names a SPECIFIC
 * shop the board cannot show. `kitchen/page.tsx` builds its selector from published
 * shops only (QA-council FIX-4: a blind `shops[0]` could pin a draft and make a live
 * kitchen look idle), while the dashboard switcher lists every GRANTED shop, published
 * or not. Pick an unpublished one — this tenant has two, `Tenant B Probe Kitchen` and
 * `Unsorted legacy items` — and the reconciliation effect silently degrades to
 * `shops[0]`. The sidebar then names one shop while the board shows another's tickets,
 * with nothing on screen connecting the two. The page's own comment called that
 * degrade "D-13" and told nobody.
 *
 * That is the same trust defect as the All-shops case, one path over: an operator
 * reading a quiet board concludes their kitchen has no orders. The header already names
 * the shop that IS showing; this names the shop that ISN'T, and why.
 */
export function KdsOtherShopNotice({ shopName }: { shopName: string | null }) {
  if (!shopName) return null
  return (
    <div
      // `status`, matching KdsAllShopsNotice: the board is correct and complete for the
      // shop it names. What is wrong is the operator's expectation, not the kitchen.
      role="status"
      data-testid="kds-other-shop-notice"
      className="flex items-start gap-3 rounded-lg border border-amber-400 bg-amber-50 px-4 py-3 text-amber-900"
    >
      <Info aria-hidden className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-700" />
      <p className="text-sm leading-relaxed">
        The shop your dashboard is set to has no kitchen board &mdash; a board is only
        shown for a <span className="font-semibold">published</span> shop. These are{" "}
        <span className="font-semibold text-amber-950">{shopName}</span>&rsquo;s tickets.
        Use the shop selector to switch.
      </p>
    </div>
  )
}

export function KdsAllShopsNotice({
  shopName,
  shopCount,
}: {
  shopName: string | null
  shopCount: number
}) {
  if (!shopName) return null
  return (
    <div
      // `status`, not `alert`: nothing is broken and nothing needs doing urgently.
      // Escalating this to an interruption would blunt the real `alert` next to it.
      role="status"
      data-testid="kds-all-shops-notice"
      className="flex items-start gap-3 rounded-lg border border-slate-300 bg-slate-50 px-4 py-3 text-slate-800"
    >
      <Info aria-hidden className="mt-0.5 h-5 w-5 flex-shrink-0 text-slate-500" />
      <p className="text-sm leading-relaxed">
        Your dashboard is set to <span className="font-semibold">All shops</span>, but
        a kitchen board shows <span className="font-semibold">one shop at a time</span>
        . These are{" "}
        <span className="font-semibold text-slate-900">{shopName}</span>&rsquo;s
        tickets only
        {shopCount > 1 ? (
          <>
            {" "}
            &mdash; orders for your{" "}
            {/* "your other 1 shop" is the `"1 items"` defect in #450 item 5 wearing a
                different hat. The count is only worth printing when it is >1.
                #557: the VERB has to agree with that choice too. Dropping the count
                left "your other shop ARE not on this screen" — the same sentence
                defect one vendor-size down, and the half this comment's own reasoning
                should have caught. Noun and verb now come from the same branch, so
                they cannot disagree again. */}
            {shopCount === 2
              ? "other shop is"
              : `other ${shopCount - 1} shops are`}{" "}
            not on this screen. Use the shop selector to switch.
          </>
        ) : (
          "."
        )}
      </p>
    </div>
  )
}
