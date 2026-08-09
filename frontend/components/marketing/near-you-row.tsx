"use client"

import { useCallback, useState } from "react"
import Link from "next/link"
import { Loader2, LocateFixed } from "lucide-react"
import { DishScroller } from "@/components/marketing/dish-scroller"
import { ShopCard } from "@/components/marketing/shop-card"
import type { PageResponse } from "@/types/api"
import type { PublicShop } from "@/types/storefront"

/**
 * The landing kitchen row, upgraded by DEVICE LOCATION (issue 460, CUST-01).
 *
 * Issue numbers are written without a leading hash throughout this file. The
 * `__tests__/palette-discipline` gate greps this directory for raw hex colours
 * with /#[0-9a-fA-F]{3,8}/, and "460" is valid hex — the recorded shape where a
 * rule that must match a token fires on prose that is not one. It caught this
 * file on its first run. The gate is right about hex and is left alone;
 * `dish-scroller.tsx` next door writes "PR 221" for the same reason.
 *
 * A client island inside the Server-Component landing page — the same shape as
 * `hero-search.tsx` next door and `app/shop/shop-discovery-client.tsx`, and for
 * the same reason: `app/page.tsx` must stay server-rendered so the root layout's
 * force-dynamic CSP nonce cascades through (the #89 failure mode), and so the
 * real shop names are in the INITIAL HTML before any JavaScript. The server has
 * already fetched them; this component receives them as `serverShops` and
 * renders them verbatim until — and only until — it holds a real coordinate.
 *
 * ── THREE STATES, THREE HEADINGS. THE HEADING IS DERIVED, NEVER HARDCODED ────
 *
 *   no coordinate (initial, AND after a denial)  "Kitchens on J'Toye"
 *   coordinate held, shops inside the radius     "Kitchens near you"
 *   coordinate held, nothing inside the radius   "No kitchens within N km —
 *                                                 here is everything on J'Toye"
 *
 * The third state is not optional. Showing the full list under a "near you"
 * heading when nothing is near is the same class of untruth that issue 544
 * exists to stop: the row would be claiming a relationship to the visitor's
 * position that the data does not support. It is also why no string containing
 * "near you" may be written into any branch that can render without a
 * coordinate — the criterion is asserted as
 * `getByRole('heading', { name: /near you/i }).toHaveCount(0)` in both the
 * initial and post-denial states, scoped to HEADINGS because `/` legitimately
 * carries that phrase at three other, non-heading sites (the primary CTA, the
 * "Browse" step body and the scroller's accessible name). 33-03 recorded that
 * scoping decision; this component inherits it and must not break those three.
 *
 * ── NOTHING ABOUT THE VISITOR IS PERSISTED ──────────────────────────────────
 *
 * A precise coordinate is personal data under UK GDPR, and issue 116's consent
 * banner has NOT shipped, so writing one to a cookie would create a PECR
 * question this phase cannot close — a cookie is also sent on every matched
 * request thereafter, which is a far wider disclosure than the single query it
 * would serve. React state only: the coordinate lives for the lifetime of this
 * component and is gone on reload. There is no write to any browser storage API
 * anywhere in this file, and the threat register asserts that with a grep over
 * all four sinks (T-33-07-01).
 *
 * That grep is why this paragraph names none of those four APIs. A rule that has
 * to spell out the token it forbids fires on its own definition — the recorded
 * shape that turned `app/page.tsx`'s docblock red in 33-03. The assertion is
 * correct and is left exactly as it is; the prose works around it.
 *
 * ── THE PROMPT IS GESTURE-GATED ─────────────────────────────────────────────
 *
 * `getCurrentPosition` is called ONLY from the button's click handler, never
 * during render and never from a mount effect. An unsolicited permission prompt
 * on first paint is penalised by browsers, reads as hostile, and — once denied —
 * cannot be re-asked without the visitor going into site settings.
 *
 * All THREE failures (PERMISSION_DENIED, POSITION_UNAVAILABLE, TIMEOUT) land in
 * the same place as "never asked": the server list, the location-free heading,
 * a short non-blocking note. No spinner survives any of them. `Permissions-Policy`
 * must also permit the API to same-origin — 33-03 set `geolocation=(self)`; with
 * the previous empty allowlist the call is refused before any prompt and presents
 * to a user and to a tester IDENTICALLY to a denial, so read the live header
 * before debugging anything here.
 */

/**
 * The radius asked for, in kilometres, and the number the heading quotes.
 *
 * Declared here and SENT on the request rather than letting the server apply its
 * own default, because the empty-state heading names it: a UI that says "nothing
 * within 5 km" while the query used some other radius is telling the visitor
 * something untrue about their own result set. 33-06's ceiling is 50 km and a
 * request past it is refused rather than clamped, so this must stay under it.
 */
export const NEAR_YOU_RADIUS_KM = 5

/** How many shops the located query asks for — matched to `page.tsx`'s server call. */
const PAGE_SIZE = 8

/**
 * The browser-facing core origin — the same value `lib/public-api-client.ts`
 * gives its axios instance, read the same way, so there is still exactly one
 * place that decides where a public call goes.
 *
 * WHY NOT `publicApiClient` ITSELF, WHICH IS THE PATTERN NEXT DOOR. Measured, on
 * the rebuilt stack, with the landing route's own bundle meter:
 *
 *   island using publicApiClient (axios)   1,005,834 bytes   +52,481 over 33-03
 *   island using fetch                       958,988 bytes   + 5,635 over 33-03
 *
 * Importing axios here puts 46,846 bytes of HTTP client onto `/` — the
 * LCP-critical route every customer sees first — to issue ONE GET with five
 * query parameters and read one JSON body. Nothing axios provides is used on
 * that path: there is no auth interceptor on the public client (its own comment
 * says so), no upload progress, no cancellation, and the rate-limit retry helpers
 * in `lib/public-fetch-retry.ts` are not wired in here. Web performance is a
 * standing design-time acceptance criterion in CLAUDE.md, and a 5.5% bundle
 * increase on the landing page for an unused abstraction is precisely the
 * "unbounded island on the LCP-critical public route" it exists to catch.
 *
 * `app/shop/shop-discovery-client.tsx` keeps `publicApiClient`, correctly: it
 * pages, searches, and consumes the axios-shaped 429 errors that
 * `public-fetch-retry` inspects. This is one call, on the first page load.
 *
 * If a future change here needs interceptors, retry classification or cancellation,
 * switch back and RE-MEASURE — do not switch back on symmetry alone.
 */
const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? ""

/**
 * Geolocation options. High accuracy is deliberately OFF: shop coordinates are
 * postcode centroids accurate to ~100 m, so a GPS fix good to 5 m cannot improve
 * the ranking by one position, and asking for it costs battery, seconds, and (on
 * some devices) a more alarming permission dialog.
 */
const GEO_OPTIONS: PositionOptions = {
  enableHighAccuracy: false,
  timeout: 8000,
  maximumAge: 300000,
}

/**
 * Coordinate precision sent upstream, in decimal places. 4 dp is ~11 m, an order
 * of magnitude finer than the ~100 m centroids being ranked, so rounding cannot
 * change the order — while a raw 13-decimal reading is a far more identifying
 * value to put in a URL. Data minimisation (UK GDPR art. 5(1)(c)), not decoration.
 */
const COORD_DP = 4

type Phase = "idle" | "locating" | "located" | "empty" | "error"

function round(value: number): number {
  const f = 10 ** COORD_DP
  return Math.round(value * f) / f
}

/**
 * Which of the server's published shops CANNOT be ranked at all, because they
 * hold no coordinate.
 *
 * This is the honest denominator for the exclusion disclosure and it is computed
 * from the shops themselves rather than by subtracting the located count from
 * the server count. The subtraction conflates two completely different reasons a
 * shop is missing — "we do not know where it is" and "it is further away than
 * you asked for" — and would report a shop 20 km down the road as having no
 * location data. Inventing a reason is how the row starts lying again.
 */
function withoutCoordinates(shops: PublicShop[]): PublicShop[] {
  return shops.filter((s) => s.latitude == null || s.longitude == null)
}

export function NearYouRow({ serverShops }: { serverShops: PublicShop[] }) {
  const [phase, setPhase] = useState<Phase>("idle")
  const [nearby, setNearby] = useState<PublicShop[] | null>(null)
  const [note, setNote] = useState<string | null>(null)

  const located = phase === "located" && nearby !== null
  // The server list is the fallback for EVERY non-located phase, including both
  // error phases and the nothing-in-radius one. A visitor who denies permission
  // must never be left with a worse page than one who was never asked.
  const shops = located ? nearby : serverShops

  const heading = located
    ? "Kitchens near you"
    : phase === "empty"
      ? `No kitchens within ${NEAR_YOU_RADIUS_KM} km — here is everything on J'Toye`
      : "Kitchens on J'Toye"

  const unranked = located ? withoutCoordinates(serverShops) : []
  const beyondRadius = located
    ? Math.max(0, serverShops.length - nearby.length - unranked.length)
    : 0

  const requestLocation = useCallback(() => {
    if (phase === "locating") return
    setNote(null)

    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setPhase("error")
      setNote("This browser cannot share your location — showing every kitchen instead.")
      return
    }

    setPhase("locating")
    navigator.geolocation.getCurrentPosition(
      async (position) => {
        // ONE request per grant. Not per render, not per keystroke — there is no
        // input here to debounce, and a watchPosition would re-fire on every
        // metre of drift against a rate-limited public endpoint (T-33-07-04).
        try {
          const params = new URLSearchParams({
            lat: String(round(position.coords.latitude)),
            lon: String(round(position.coords.longitude)),
            radiusKm: String(NEAR_YOU_RADIUS_KM),
            page: "0",
            size: String(PAGE_SIZE),
          })
          const res = await fetch(`${API_BASE}/public/shops?${params}`, {
            headers: { Accept: "application/json" },
          })
          // A 429 or a 5xx is NOT an answer. Falling through to `res.json()`
          // here would parse an RFC 7807 problem document into a shop page and
          // render an empty row as if the visitor genuinely had no kitchens
          // nearby — a non-answer presented as an authoritative one, which is
          // the distinction `lib/storefront-server.ts` exists to preserve.
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          const body = (await res.json()) as PageResponse<PublicShop>
          const content = body?.content ?? []
          if (content.length === 0) {
            setNearby(null)
            setPhase("empty")
            return
          }
          setNearby(content)
          setPhase("located")
        } catch {
          // A 429, a 5xx or a dropped connection. The server list is still on
          // screen and still true, so say so briefly and stop — never an empty
          // row, and never a spinner left running.
          setNearby(null)
          setPhase("error")
          setNote("We could not check what is near you just now — showing every kitchen.")
        }
      },
      (error) => {
        setNearby(null)
        setPhase("error")
        setNote(
          error.code === error.PERMISSION_DENIED
            ? "No problem — showing every kitchen on J'Toye."
            : error.code === error.TIMEOUT
              ? "That took too long — showing every kitchen on J'Toye."
              : "We could not get your location — showing every kitchen on J'Toye."
        )
      },
      GEO_OPTIONS
    )
  }, [phase])

  return (
    <>
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
        {/*
          The heading swap IS the claim this component makes, so it has to be
          announced: a screen-reader user who activates the button and hears
          nothing has no way to know the list changed. The live region wraps the
          heading AND the status line, and both are rendered unconditionally so
          the region exists in the DOM before anything changes inside it — a
          live region added at the same moment as its content is not announced.
        */}
        <div aria-live="polite" className="min-w-0">
          <h2 className="text-2xl font-bold text-oxblood">{heading}</h2>
          {/*
            Reserved height, always present. The status line appears after a
            click, and although input-adjacent shifts are excluded from CLS, a
            row that jumps under the visitor's finger still reads as broken.
          */}
          <p className="mt-1 min-h-[1.25rem] text-sm text-slate-600">
            {located && unranked.length > 0 && (
              <>
                {unranked.length === 1
                  ? "1 kitchen has no location data yet, so it is not ranked here"
                  : `${unranked.length} kitchens have no location data yet, so they are not ranked here`}
                {beyondRadius > 0 &&
                  `, and ${beyondRadius} more ${beyondRadius === 1 ? "is" : "are"} further than ${NEAR_YOU_RADIUS_KM} km away`}
                .{" "}
                <Link href="/shop" className="font-semibold text-amber-700 underline hover:text-amber-800">
                  See every kitchen
                </Link>
                .
              </>
            )}
            {located && unranked.length === 0 && beyondRadius > 0 && (
              <>
                {beyondRadius} more {beyondRadius === 1 ? "kitchen is" : "kitchens are"} further than{" "}
                {NEAR_YOU_RADIUS_KM} km away.{" "}
                <Link href="/shop" className="font-semibold text-amber-700 underline hover:text-amber-800">
                  See every kitchen
                </Link>
                .
              </>
            )}
            {note}
          </p>
        </div>

        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={requestLocation}
            disabled={phase === "locating"}
            className="inline-flex items-center gap-1.5 rounded-full border border-cream-100 bg-white px-3.5 py-1.5 text-sm font-bold text-oxblood shadow-sm transition-colors hover:border-amber-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:ring-offset-2 disabled:opacity-60"
          >
            {phase === "locating" ? (
              <Loader2 aria-hidden className="h-4 w-4 animate-spin" />
            ) : (
              <LocateFixed aria-hidden className="h-4 w-4" />
            )}
            Use my location
          </button>
          <Link href="/shop" className="text-sm font-bold text-amber-700 hover:text-amber-800">
            See all kitchens →
          </Link>
        </div>
      </div>

      <div className="mt-5">
        {/*
          The scroller, its label and the cards are all UNCHANGED — this island
          renders INTO 33-03's affordance rather than replacing it. The label is
          byte-identical because it is `marketing-dish-scroller.spec.ts`'s
          selector, and it is an accessible name on a scroll region rather than a
          heading, so the no-locality-claim criterion deliberately does not reach
          it. `aria-busy` marks the in-flight window without unmounting anything:
          the cards stay on screen while the located list is fetched, so their
          boxes are already reserved and the swap cannot shift the page.
        */}
        <div aria-busy={phase === "locating"}>
          <DishScroller label="Dishes cooking near you">
            {shops.map((shop) => (
              <ShopCard key={shop.slug} shop={shop} />
            ))}
          </DishScroller>
        </div>
      </div>
    </>
  )
}
