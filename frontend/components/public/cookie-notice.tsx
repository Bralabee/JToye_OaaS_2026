"use client"

import Link from "next/link"
import { useEffect, useState } from "react"

import { cn } from "@/lib/utils"
import { BottomNoticeShell } from "./bottom-notice-shell"
import {
  acknowledgeCookieNotice,
  choosableCategories,
  onChange,
  shouldShowCookieNotice,
} from "@/lib/consent"
import { ConsentBanner } from "./consent-banner"

/**
 * The essential-cookies NOTICE (S1, D-05) — deliberately not a consent gate.
 *
 * Measured on this tree: zero analytics scripts, zero tag managers, zero
 * non-essential cookies. The only browser storage is the auth cookies and the
 * basket/order-history/shop-context `localStorage` keys, all strictly necessary.
 * So there is genuinely nothing to accept or reject, and presenting a choice
 * would be a lie about what the site does.
 *
 * What the visitor is owed instead is to be TOLD, once, what is stored — which
 * is what this is. Under PECR `localStorage` is storage on terminal equipment
 * exactly as a cookie is, so the copy says "cookies and browser storage" and
 * never the narrower claim that cookies are the only thing involved.
 *
 * (That sentence is deliberately phrased the long way round. The banned phrase
 * is asserted absent from this file by a literal grep, and a comment quoting it
 * in order to forbid it would satisfy that grep and make the guard useless —
 * the same comment-satisfies-grep shape this phase has already hit repeatedly.)
 *
 * If a non-essential category is ever registered, `ConsentBanner` supersedes
 * this notice automatically (see the branch below) — the notice must not sit
 * there saying "nothing to accept" while a real choice is pending.
 */

/** POSITIONING, STACKING AND ZERO CLS all live in `BottomNoticeShell` now — see
 *  that file for the full R-07 record. Kept here in summary because this file is
 *  where the reasoning was first written and a reader arrives here first:
 *
 *  ZERO LAYOUT SHIFT. `fixed` takes the notice OUT OF DOCUMENT FLOW, so no
 *  sibling ever moves when it mounts or unmounts. The corollary that makes it
 *  airtight: the notice never renders on the server
 *  (`shouldShowCookieNotice()` returns false without a `window`) and appears only
 *  after mount. For an in-flow element that would be a guaranteed shift; for a
 *  fixed one it costs nothing. UNCHANGED throughout R-07 and WR-03.
 *
 *  THE STACKING DECISION. `FloatingCartBar` and `mobile-tab-bar.tsx` both sit at
 *  `z-50`, bottom-anchored, and on a 375x667 viewport they occupy this corner.
 *  The notice takes `z-40`, deliberately BELOW both: the basket bar is a
 *  transactional control on the path to checkout and the tab bar is primary
 *  navigation; an informational notice must never occlude either. UNCHANGED.
 *
 *  WHAT R-07 ADDED. Four of five audit lanes found this notice independently.
 *  Five measured symptoms: (1) it covered the vendor sidebar's bottom-rail Sign
 *  Out; (2) on a mobile storefront with a non-empty basket the z-50 cart bar
 *  painted over "Got it", so the notice was permanently UN-DISMISSABLE and the
 *  acknowledgement never written; (3) it covered the mobile "Browse all
 *  kitchens" escape hatch; (4) it hid ~80% of the landing CTA at 390x844;
 *  (5) its own copy truncated. The z-ranking never reasoned about the REVERSE
 *  direction — those bars occluding the notice's own dismiss control — and no
 *  z-index can fix that, because either ordering breaks one of the two. The
 *  shell's pointer-events split and published offset are the answer.
 *
 *  WHAT THE SPLIT ACTUALLY BUYS (WR-02 — this wording was previously too
 *  strong). `pointer-events-none` removes CLICK INTERCEPTION; it does not make
 *  the card transparent, and a control the card is drawn OVER remains hidden.
 *  An earlier version of this comment claimed it "closes (1), (3) and (4) as a
 *  CLASS", which overstated the mechanism and would have told the next reader to
 *  stop looking. Precisely: the split closes the interception class everywhere,
 *  and closes (1) outright because the card is right-aligned from `sm` up and so
 *  sits beside rather than over the bottom-LEFT rail. Clearing a control the
 *  card physically covers is the OFFSET's job, and the offset only acts where a
 *  publisher exists — today the dashboard tab bar and the storefront cart bar,
 *  so `/` and `/shop` still fall back to `bottom: 0px`. Symptoms (3) and (4)
 *  therefore rest on the smaller, inset, right-aligned card rather than on the
 *  offset, and their proof is the orchestrator's browser pass at 390x844.
 */

/** Cream ring: `--ring` is orange-700, and orange-700 on #3A0B0D is a weak
 *  boundary. Ring visibility is itself a 3:1 requirement, so the surface gets
 *  its own ring colour rather than inheriting the app token. */
const FOCUS_RING = cn(
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cream",
  "focus-visible:ring-offset-2 focus-visible:ring-offset-oxblood"
)

export function CookieNotice() {
  const [show, setShow] = useState(false)

  useEffect(() => {
    const sync = () => setShow(shouldShowCookieNotice())
    sync()
    // Both channels: a dismissal in THIS tab (CustomEvent) and in another one
    // (storage). Without the former the notice would linger until a re-render
    // happened to occur for some unrelated reason.
    return onChange(sync)
  }, [])

  // A real choice outranks an informational notice.
  if (choosableCategories().length > 0) return <ConsentBanner />

  if (!show) return null

  return (
    <BottomNoticeShell
      label="Cookie notice"
      cardClassName="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <>
        <div className="min-w-0">
          {/* h2, at the same 14px/600 as the actions: this is chrome, and it
              must not compete with the page's own <h1>. */}
          <h2 className="text-sm font-semibold leading-[1.5]">{"Cookies on J'Toye"}</h2>
          {/* R-07 symptom (5): compacted to one sentence so it does not truncate
              in the mobile band. Every element of the legal intent survives —
              cookies AND browser storage (PECR treats localStorage as storage on
              terminal equipment exactly as a cookie is, and this site uses it for
              the basket), strictly necessary, and nothing to accept or reject.
              The detail now lives where it always did, behind /legal/cookies. */}
          <p className="mt-1 text-sm leading-[1.5] text-cream/85">
            We use cookies and browser storage only where strictly necessary to run this
            site — there is nothing to accept or reject.
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-3">
          <button
            type="button"
            onClick={acknowledgeCookieNotice}
            className={cn(
              "inline-flex min-h-11 min-w-11 items-center justify-center rounded-md px-4",
              "text-sm font-semibold leading-[1.5] bg-cream text-oxblood",
              "transition-colors hover:bg-white",
              FOCUS_RING
            )}
          >
            Got it
          </button>
          <Link
            href="/legal/cookies"
            className={cn(
              "inline-flex min-h-11 items-center rounded-md px-1",
              "text-sm font-semibold leading-[1.5] text-cream underline underline-offset-4",
              "transition-colors hover:text-white",
              FOCUS_RING
            )}
          >
            Cookie policy
          </Link>
        </div>
      </>
    </BottomNoticeShell>
  )
}
