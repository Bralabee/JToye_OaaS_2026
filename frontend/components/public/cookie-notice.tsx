"use client"

import { m } from "framer-motion"
import Link from "next/link"
import { useEffect, useState } from "react"

import { cn } from "@/lib/utils"
import { BOTTOM_CHROME_VAR } from "@/hooks/use-bottom-chrome-height"
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

/** ZERO LAYOUT SHIFT — the mechanism, so it is not mistaken for a claim.
 *
 *  `fixed` takes the notice OUT OF DOCUMENT FLOW, so no sibling ever moves when
 *  it mounts or unmounts. Being fixed IS the property; nothing is "reserved" and
 *  nothing needs to be. Same mechanism as `mobile-tab-bar.tsx` and the
 *  storefront `FloatingCartBar`, neither of which reserves space either.
 *  UNCHANGED by R-07: still fixed, still out of flow, still never
 *  server-rendered.
 *
 *  The corollary that makes it airtight: the notice never renders on the server
 *  (`shouldShowCookieNotice()` returns false without a `window`) and appears only
 *  after mount. For an in-flow element that would be a guaranteed shift; for a
 *  fixed one it costs nothing, which is why the SSR guard and the CLS property
 *  reinforce each other rather than trading off.
 *
 *  ── THE STACKING DECISION (no precedent existed in the tree) ────────────────
 *  `FloatingCartBar` (shop-detail-client.tsx) and `mobile-tab-bar.tsx` both sit
 *  at `z-50`, bottom-anchored. On a 375x667 viewport they occupy the same corner
 *  as this notice and nothing in the codebase resolved that collision, so this
 *  is designed rather than copied.
 *
 *  DECISION: the notice takes `z-40` — deliberately BELOW both. The basket bar
 *  is a transactional control on the critical path to checkout and the tab bar
 *  is primary navigation; an informational notice must never occlude either.
 *  THAT DECISION IS UNCHANGED and still correct.
 *
 *  ── WHAT R-07 ADDS, AND WHY AN OFFSET IS NOW ACCEPTABLE HERE ────────────────
 *  Four of five lanes of the 2026-08-31 audit found this notice independently.
 *  Five measured symptoms: (1) it covered the vendor sidebar's bottom-rail Sign
 *  Out — `elementFromPoint` returned the notice; (2) on a mobile storefront with
 *  a non-empty basket the z-50 cart bar painted over "Got it", so the notice was
 *  permanently UN-DISMISSABLE and the acknowledgement never written; (3) it
 *  covered the mobile "Browse all kitchens" zero-result escape hatch; (4) it hid
 *  ~80% of the landing "Order food near you" CTA at 390x844; (5) its own copy
 *  truncated in the mobile band.
 *
 *  The z-ranking above did not reason about the REVERSE direction — those bars
 *  occluding the notice's OWN dismiss control — and symptom (2) is exactly that.
 *  No z-index can fix it: either ordering breaks one of the two. The notice has
 *  to stop SHARING THE BAND. Two mechanisms, both structural:
 *
 *   - POINTER-EVENTS SPLIT. The positioning wrapper is `pointer-events-none` and
 *     the card is `pointer-events-auto`, so the notice can no longer intercept a
 *     click on anything it is not itself drawn over. That closes (1), (3) and
 *     (4) as a CLASS rather than one at a time — including the next such
 *     collision, which nobody has found yet.
 *   - A PUBLISHED BOTTOM OFFSET. `var(--jt-bottom-chrome, 0px)` lifts the notice
 *     clear of whichever bar is mounted, closing (2).
 *
 *  The comment above rejected an offset, and that rejection was right about the
 *  offset it had in mind: a TUNED CONSTANT has to be re-tuned every time either
 *  bar changes height, and a stale one fails silently as a covered CTA. This
 *  offset is a different thing. The bar PUBLISHES its own measured height at the
 *  moment it appears (`hooks/use-bottom-chrome-height.ts`) and clears it when it
 *  goes, so there is no constant here to go stale. The `0px` fallback is the
 *  no-bar case and needs no coordination at all.
 *
 *  `pb-[max(0.75rem,env(safe-area-inset-bottom))]` copies `FloatingCartBar`'s
 *  form on purpose: a plain `pb-[env(safe-area-inset-bottom)]` collapses to 0 on
 *  every non-notch device, putting the controls flush against the screen edge.
 */
const WRAPPER_CLASS = cn(
  // `bottom` is an INLINE style rather than a class, because Tailwind cannot
  // express `var(--jt-bottom-chrome, 0px)` as an arbitrary value that also
  // survives the JIT's class extraction reliably.
  "fixed inset-x-0 z-40 pointer-events-none"
)

/** The drawn surface. Everything the visitor can actually click lives here. */
const CARD_CLASS = cn(
  "pointer-events-auto border border-white/15 bg-oxblood text-cream shadow-lg",
  "flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between",
  // Inset from the edges on mobile so it reads as a card rather than a band…
  "mx-3 mb-3 rounded-xl",
  // …and right-aligned with a capped measure from `sm` up, so on the desktop
  // dashboard it sits nowhere near the bottom-LEFT sidebar rail (symptom 1).
  "sm:ml-auto sm:mr-4 sm:max-w-md",
  "px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]",
  "sm:pt-4 sm:pb-[max(1rem,env(safe-area-inset-bottom))]"
)

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
    <m.section
      aria-label="Cookie notice"
      // 200ms fade + 8px translate-Y. framer-motion rather than a CSS
      // transition because `MotionConfig reducedMotion="user"`
      // (motion-provider.tsx) already governs it app-wide, so the
      // reduced-motion duty is inherited; a raw CSS transition would need its
      // own @media block in globals.css, which another plan owns this wave.
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2 }}
      className={WRAPPER_CLASS}
      // R-07 symptom (2): sit ABOVE whatever bottom-fixed bar is mounted. The
      // value is published by the bar itself; `0px` is the no-bar case.
      style={{ bottom: `var(${BOTTOM_CHROME_VAR}, 0px)` }}
    >
      <div className={CARD_CLASS}>
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
      </div>
    </m.section>
  )
}
