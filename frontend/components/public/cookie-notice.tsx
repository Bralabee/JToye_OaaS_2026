"use client"

import { m } from "framer-motion"
import Link from "next/link"
import { useEffect, useState } from "react"

import { cn } from "@/lib/utils"
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
 *  Ranking by z-index rather than by an offset was chosen because an offset has
 *  to be re-tuned every time either bar changes height, and a stale offset fails
 *  SILENTLY as a covered CTA. A z-index cannot drift.
 *
 *  `pb-[max(0.75rem,env(safe-area-inset-bottom))]` copies `FloatingCartBar`'s
 *  form on purpose: a plain `pb-[env(safe-area-inset-bottom)]` collapses to 0 on
 *  every non-notch device, putting the controls flush against the screen edge.
 */
const NOTICE_CLASS = cn(
  "fixed inset-x-0 bottom-0 z-40 border-t border-white/15 bg-oxblood text-cream",
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
      className={NOTICE_CLASS}
    >
      <div className="mx-auto flex max-w-3xl flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          {/* h2, at the same 14px/600 as the actions: this is chrome, and it
              must not compete with the page's own <h1>. */}
          <h2 className="text-sm font-semibold leading-[1.5]">{"Cookies on J'Toye"}</h2>
          <p className="mt-1 text-sm leading-[1.5] text-cream/85">
            We only use cookies and browser storage that are strictly necessary to run this
            site — keeping you signed in, remembering what is in your basket, and keeping
            your order secure. We do not use advertising or analytics cookies, so there is
            nothing here to accept or reject.
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
