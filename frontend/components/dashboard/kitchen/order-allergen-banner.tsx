"use client"

import { AlertTriangle, HelpCircle } from "lucide-react"
import type { OrderAllergenFlag } from "@/types/api"

/**
 * The order-level allergen banner on the kitchen display (31-15, LGL-03 / D-04,
 * UI-SPEC S4).
 *
 * THIS IS WHERE THE WARNING IS ACTED ON BY SOMEONE HOLDING FOOD. The failure mode is a
 * missed warning, not a cosmetic defect, and every decision below is downstream of that.
 *
 * THE VIEWING-DISTANCE CONTRACT is why the sizes look large for a dashboard. The KDS is
 * a wall-mounted or counter-propped display read at 0.6-1.5 m, glanced at under time
 * pressure, through glare and steam. At the contracted 20px the "ALLERGENS" label
 * subtends at 1.5 m what 8px does at 60 cm — legible for a short uppercase label and
 * for nothing longer. Hence a short label plus a short list, never prose. The 20px is
 * the existing base Heading step; an 18px fifth step was proposed and blocked twice in
 * UI-SPEC review, and the resolution was UPWARD rather than down to 16px, because
 * shrinking this label to satisfy a scale rule would trade a real legibility property
 * for a documentary one.
 *
 * NEVER COLOUR-ONLY. The reader may be colour-fatigued or colour-blind, and the card
 * already carries a green/yellow/red border that means AGE, not allergens. So the
 * warning is carried by an icon AND words, on a SOLID amber-800 fill (a tint does not
 * carry at 1.5 m). The colour is an accelerant, never the message.
 *
 * NOTHING HERE ANIMATES. A flashing safety warning is a seizure risk (WCAG 2.3.1) and
 * an attention tax in a room that already has too many. The card's one-shot ember glow
 * for a newly-arrived order is a different signal and is untouched.
 *
 * ------------------------------------------------------------------------------------
 * THREE STATES, NOT TWO — the part most likely to be "simplified" by a later reader.
 *
 *   allergenNames == null   NOT RECORDED. The order (or one of its lines) predates
 *                           V63, so the platform CANNOT STATE the allergen set.
 *   allergenNames == []     The vendor DECLARED NONE of the 14 regulated allergens.
 *   allergenNames.length>0  The declared set, rendered complete.
 *
 * The middle state renders NOTHING, deliberately: a "no allergens" banner on every
 * ticket trains staff to ignore the banner, which destroys the one thing it exists to
 * do. Absence is the signal, and it is safe precisely because the banner is unmissable
 * when present.
 *
 * WHICH IS EXACTLY WHY THE FIRST STATE CANNOT ALSO RENDER NOTHING. Once "no banner"
 * MEANS "the vendor declared none", a not-recorded order that renders nothing is not
 * silent — it is making the allergen-free claim, on data that does not exist. So it
 * gets a third treatment: distinct WORDS ("ALLERGENS NOT RECORDED"), a neutral slate
 * outline rather than the amber warning fill, and no claim in either direction.
 *
 * It is deliberately NOT amber: the platform is not asserting that this order HAS
 * allergens either. It is stating one fact — that it cannot state the set — and it
 * stops there. What a kitchen should DO with such a ticket is an operating decision
 * this component must not invent, so no instruction is printed here. (Recorded as an
 * open owner question in the 31-15 summary.)
 *
 * The banner-fatigue objection to this third state was weighed and does not bite: the
 * KDS shows only ACTIVE tickets (CONFIRMED/PREPARING/READY), which are by definition
 * recent, and every order written after V63 carries the snapshot. The not-recorded
 * strip is therefore a transient state on a handful of in-flight tickets at migration,
 * not a permanent fixture on every card.
 * ------------------------------------------------------------------------------------
 *
 * ADVISORY FLAGS ARE NOT DECLARATIONS. `allergenFlags` is 31-04's reconciliation
 * result — bits the product's emphasised ingredients text names that its declared mask
 * omits. They render as their own "CHECK:" lines and are NEVER merged into the declared
 * list: a text heuristic must not rewrite a vendor's legal statement, and merging would
 * also hide the vendor's underlying data error instead of surfacing it.
 */
export function OrderAllergenBanner({
  allergenNames,
  allergenFlags,
}: {
  /** The order's DECLARED set, from the server's write-time snapshot. `null` = not recorded. */
  allergenNames?: string[] | null
  /** ADVISORY reconciliation lines. Never merged into the declared set. */
  allergenFlags?: OrderAllergenFlag[] | null
}) {
  // NOT RECORDED. Checked first and by `== null` (covers `undefined` too, which is what
  // a cached pre-31-10 response deserialises to). Never `allergenNames ?? []` — that
  // collapse IS the defect, and it fails in the direction that injures someone.
  if (allergenNames == null) {
    return (
      <div
        data-testid="kds-allergen-unrecorded"
        className="mt-2 w-full rounded-md border-2 border-slate-700 bg-white px-3 py-2 text-slate-900"
      >
        <div className="flex items-center gap-2">
          <HelpCircle aria-hidden="true" className="h-6 w-6 flex-shrink-0" />
          <span
            data-testid="kds-allergen-unrecorded-label"
            className="text-xl font-semibold uppercase tracking-[0.08em]"
          >
            {/* Written uppercase in the MARKUP, not only via the class. A stylesheet
                that fails to load, a copy-paste into a chat, or a future refactor that
                drops the utility must not be able to soften a safety statement. */}
            ALLERGENS NOT RECORDED
          </span>
        </div>
        <p className="mt-1 text-base font-semibold">
          No allergen data was recorded for this order.
        </p>
      </div>
    )
  }

  const flags = allergenFlags ?? []

  // The vendor declared none of the 14 and nothing was flagged: render nothing.
  if (allergenNames.length === 0 && flags.length === 0) return null

  return (
    <div
      data-testid="kds-allergen-banner"
      className="mt-2 w-full rounded-md bg-amber-800 px-3 py-2 text-white"
    >
      <div className="flex items-center gap-2">
        <AlertTriangle aria-hidden="true" className="h-6 w-6 flex-shrink-0" />
        <span
          data-testid="kds-allergen-label"
          className="text-xl font-semibold uppercase tracking-[0.08em]"
        >
          {/* Uppercase in the MARKUP as well as the class — see the note above. */}
          ALLERGENS
        </span>
      </div>

      {/* THE COMPLETE SET. This line never truncates — it is the guarantee the per-item
          badge's "+N" borrows against, and inverting that dependency (truncating here
          so the card looks tidier) would leave the full set nowhere on the display. */}
      <p data-testid="kds-allergen-declared" className="mt-1 text-base font-semibold">
        {allergenNames.length > 0 ? allergenNames.join(", ") : "None declared"}
      </p>

      {/* Advisory, and distinguished by WORDING rather than by colour — the whole banner
          is one fill, so a colour distinction would carry nothing here anyway. */}
      {flags.map((flag) => (
        <p
          key={`${flag.productName}-${flag.allergenBit}`}
          data-testid="kds-allergen-check"
          className="mt-1 text-base font-semibold"
        >
          CHECK: {flag.productName} &mdash; ingredients mention {flag.allergenName}
        </p>
      ))}
    </div>
  )
}
