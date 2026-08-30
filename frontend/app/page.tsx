import type { Metadata } from "next"
import Link from "next/link"
import {
  UtensilsCrossed,
  Store,
  Search,
  ShoppingBag,
  MapPin,
  CheckCircle2,
  ArrowRight,
} from "lucide-react"
import { PublicShell } from "@/components/public/public-shell"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { cn } from "@/lib/utils"
import { HeroScene } from "@/components/marketing/hero-scene"
import { HeroSearch } from "@/components/marketing/hero-search"
import { Reveal } from "@/components/marketing/reveal"
import { NearYouRow } from "@/components/marketing/near-you-row"
import { loadShopList } from "@/lib/storefront-server"
import { headers } from "next/headers"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { serialiseJsonLd, shopListStructuredData } from "@/lib/structured-data"

export const metadata: Metadata = {
  title: "J'Toye — Order from local kitchens, or run your own",
  description:
    "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders, manage your kitchen, and go live in a day.",
  // FE-5: canonical + Open Graph were missing on the landing page — the one
  // page most likely to be shared/linked externally. `alternates.canonical`
  // is a relative path deliberately (no `metadataBase` is set app-wide, so
  // Next emits it root-relative — see the note in app/shop/layout.tsx for why
  // no hostname is guessed here).
  alternates: { canonical: "/" },
  openGraph: {
    title: "J'Toye — Order from local kitchens, or run your own",
    description:
      "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders, manage your kitchen, and go live in a day.",
    url: "/",
    type: "website",
  },
}

const steps = [
  { icon: Search, title: "Browse", body: "Find independent kitchens near you and explore their menus." },
  { icon: ShoppingBag, title: "Order & pay", body: "Add to your basket and check out securely — delivery or collection." },
  { icon: MapPin, title: "Track live", body: "Follow your order from the kitchen to your door in real time." },
]

const trustMarkers = [
  "UK food-hygiene verified",
  "Allergen info on every item",
  "No app to download",
]

// Discovery chips (sketch 004 variant C). Each is a real crawlable link that
// runs the query it advertises (`/shop?q=…`) rather than dumping you on the
// unfiltered index, so the marketplace reads as alive from the first screen.
const categories = [
  { emoji: "🍗", label: "Grill", q: "grill" },
  { emoji: "🍚", label: "Jollof", q: "jollof" },
  { emoji: "🥘", label: "Caribbean", q: "caribbean" },
  { emoji: "🍛", label: "South Asian", q: "south asian" },
  { emoji: "🥗", label: "Vegan", q: "vegan" },
  { emoji: "🍰", label: "Desserts", q: "dessert" },
]

const heroTiles = [
  { emoji: "🍜", grad: "from-white to-[#fbe9d4]", extra: "-rotate-2" },
  { emoji: "🥘", grad: "from-white to-[#f6dcd8]", extra: "rotate-2 mt-6" },
  { emoji: "🥗", grad: "from-white to-[#e7f3ea]", extra: "rotate-2" },
  { emoji: "🍰", grad: "from-white to-[#fdeecb]", extra: "-rotate-2 -mt-2" },
]

/**
 * Public landing page (Surface A, UIX-01) — sketch 004 winner D (A+C hybrid).
 *
 * Brand thread matched to the parent site jtoyedigital.co.uk: oxblood (#3A0B0D)
 * anchor + Work Sans, with amber/orange as the appetite accent. A's warm
 * appetite hero + food collage + "cooking near you" dish row, plus C's search +
 * category chips for immediate discovery.
 *
 * Server Component (no client directive) so the root layout's force-dynamic CSP
 * nonce cascades through (the #89 failure mode). All `data-hero-*` hooks are
 * preserved so the GSAP HeroScene choreography + marketing-motion.spec keep
 * working; the new sections are additive and unhooked.
 *
 * `async` does NOT make this a Client Component and MUST NOT be "fixed" by adding
 * a client directive — that would regress #89 and add a page to #507/#542's
 * count. `HeroSearch` and `DishScroller` are already client islands inside this
 * server page; `app/shop/page.tsx` is the fuller form of the same shape.
 *
 * The structural guard in `app/__tests__/landing.test.tsx` greps this file for the
 * directive. It cannot tell a real directive from prose NAMING one, so this
 * paragraph deliberately does not spell the string out — writing it here turned
 * that guard red, which is the recorded "a rule that must name the token it
 * forbids fires on its own definition" shape. The guard is correct and is left
 * exactly as it was.
 *
 * ── THE KITCHEN ROW (#544) ────────────────────────────────────────────────────
 *
 * The row used to render five INVENTED vendors under an unconditional "Cooking
 * near you right now" heading. It now renders the real published shops from
 * `loadShopList`. Every good the old row carried is accounted for here, because a
 * green suite over a row that quietly lost its affordance is still a regression:
 *
 *   PRESERVED  DishScroller wrapper — edge fade, proximity snap, fine-pointer arrows
 *   PRESERVED  the label "Dishes cooking near you", BYTE-IDENTICAL. It is an
 *              aria-label on a scroll region (not a heading, so the location
 *              criterion does not reach it) and it is the selector at
 *              marketing-dish-scroller.spec.ts:19. Changing it buys nothing and
 *              breaks the only guard on the affordance.
 *   PRESERVED  active:scale-[0.98] tap feedback, and the written-out
 *              [@media(hover:hover) and (pointer:fine)] hover gate
 *   PRESERVED  every data-hero-* GSAP hook (none were in this row)
 *   PRESERVED  split-persona H1, both persona doors, header, footer
 *   REPLACED   /shop?q=… deep link -> /shop/{slug}: a real shop page instead of a
 *              search that might match nothing
 *   REPLACED   emoji + gradient -> the shop's real logoUrl through SafeImage with
 *              EXPLICIT width and height, so the box is reserved (CLS)
 *   REMOVED    hardcoded rating, FHRS badge and dish price — none exists on
 *              PublicShop, and attributing an invented rating to a REAL named
 *              business is worse than the fiction it replaces
 *   FIVE -> N  the row is however many shops are published, currently three
 *
 * The heading makes no claim about where the visitor is. It deliberately does not
 * say "Open now" either: `lib/opening-hours.ts:74` returns true when hours are
 * null or empty, so an openness claim would be a NEW fiction for every shop with
 * no hours data — exactly what #544 exists to stop.
 *
 * ── DEVICE LOCATION (33-07) ───────────────────────────────────────────────────
 *
 * The row's markup moved into `components/marketing/near-you-row.tsx`, a client
 * island, so that a visitor who explicitly asks can have the same shops
 * re-ordered by real distance. What did NOT move is the fetch: `loadShopList`
 * still runs HERE, on the server, and the island receives the result as a prop —
 * so the real names are still in the initial HTML before any JavaScript, which
 * is the whole point of #507. The island's no-coordinate state renders exactly
 * what this section rendered before it existed, heading included, and only ever
 * says "near you" while it genuinely holds a coordinate.
 */
export default async function Home() {
  // Server-side, at request time, so the real names are in the INITIAL HTML —
  // before any JavaScript. A useEffect fetch would leave the crawler and the
  // first paint with an empty row, which is #507's measured complaint.
  const shopList = await loadShopList({ page: 0, size: 8 })
  const shops = shopList.state === "ok" ? (shopList.data.content ?? []) : []
  // How many published shops EXIST, not how many fitted on the page — the
  // island's exclusion disclosure may only do arithmetic over `shops` when the
  // two agree (review WR-01: a truncated sample must never be presented as a
  // census). Undefined when the load failed; the island then treats a full
  // page as possibly truncated, which suppresses rather than fabricates.
  const serverTotal = shopList.state === "ok" ? shopList.data.totalElements : undefined

  // SEO. `/` is a public, unauthenticated surface and — as of this change — a
  // genuine shop-discovery surface for the first time, with no structured data at
  // all. Measured before this change: 0 occurrences of `structured-data` or
  // `ld+json` in this file, against 31 files under `app/` carrying metadata, so
  // the corpus is searchable and the zero was real. CLAUDE.md makes
  // discoverability a standing design-time criterion for public surfaces, so this
  // is IN scope, recorded rather than silently omitted as N/A.
  //
  // Reuses `shopListStructuredData` and `serialiseJsonLd` exactly as
  // `app/shop/page.tsx` does. NEVER hand-roll either: `dangerouslySetInnerHTML` is
  // unavoidable for a ld+json script, so the mitigation is the serialiser, not its
  // absence: structured-data.ts emits JSON.stringify(data) with every "less than"
  // character replaced by its unicode escape, which stops a vendor-controlled shop
  // name from closing the script tag while parsers read it identically. A second
  // serialiser is a second place for that to be forgotten.
  const nonce = (await headers()).get("x-nonce") ?? undefined
  const jsonLd = shops.length > 0 ? shopListStructuredData(shops, resolvePublicOrigin()) : null

  return (
    <PublicShell>
      {jsonLd && (
        <script
          type="application/ld+json"
          nonce={nonce}
          dangerouslySetInnerHTML={{ __html: serialiseJsonLd(jsonLd) }}
        />
      )}
      <HeroScene>
        {/* ── Split-persona hero ─────────────────────────────────────────── */}
        <section data-hero-section className="relative overflow-hidden bg-cream">
          <div
            data-hero-heatwash
            aria-hidden
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(120%_90%_at_85%_-10%,#fde7c8_0%,transparent_55%)]"
          />
          {/* ── THE LANDING CONTENT BAND, EXPLAINED ONCE ────────────────────

              This band — and the three below it — is deliberately EQUAL to the
              public header and footer rails that wrap this page. It was not.
              The rails rendered at 1280px and these four bands at 1152px, so
              the landing content sat 128px inside its own chrome: the nav and
              this hero did not share a left edge. That is the specific
              mechanical reason the page read as confined, and it is the one
              width VALUE that actually changes anywhere in phase 35 — every
              other surface in the phase is a rename at the same number.

              Nothing invented a figure here. 1280px is what the chrome around
              this page was already doing, and what its three sibling marketing
              routes were already doing. Moving these bands to the same declared
              tier is an alignment, not a re-layout.

              ORCH-04 (orchestrator decision, 2026-08-29); CONTEXT.md section 4b.

              Applied IN PLACE on the element that already existed. No wrapper
              node was added: this page is dense with GSAP hooks and
              scroll-reveal, and a new node between a section and its band is
              exactly the change that moves a boundingBox assertion for no
              reason. The class comes from the vocabulary module rather than
              being written out, so the tier literals stay single-occurrence.

              Explained ONCE. The three bands below apply the same thing without
              repeating this; four copies of one explanation is drift waiting to
              happen, and the padding and auto margin on every band are pinned by
              app/__tests__/landing.test.tsx rather than by a comment. What is
              deliberately NOT touched: the hero sub-paragraph's reading measure
              and the search form's width, both nested inside this band. They are
              typographic measures and a CLS-sensitive control, not page bands. */}
          <div
            data-width-tier="marketing"
            className={cn(
              "relative mx-auto",
              WIDTH_TIER_CLASS.marketing,
              "px-4 sm:px-6 lg:px-8 py-14 md:py-20"
            )}
          >
            <div className="grid items-center gap-10 lg:grid-cols-2">
              {/* Left column — copy, search, doors */}
              <div>
                <span className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">
                  🇬🇧 Independent UK kitchens
                </span>
                <h1
                  data-hero-headline
                  className="mt-3 text-4xl sm:text-5xl md:text-6xl font-black leading-[1.03] tracking-tight text-oxblood"
                >
                  Order from local kitchens. Or run yours.
                </h1>
                <p className="mt-4 max-w-xl text-lg text-slate-600">
                  J&apos;Toye connects hungry customers with independent food
                  businesses — order in minutes, or take your kitchen online and
                  go live in a day.
                </p>

                {/* C: discovery search + category chips. Both run a REAL query
                    against /shop?q= — the chips stay crawlable <a href> so the
                    category pages are reachable without JS. */}
                <HeroSearch />
                <div className="mt-3.5 flex flex-wrap gap-2">
                  {categories.map((c) => (
                    <Link
                      key={c.label}
                      href={`/shop?q=${encodeURIComponent(c.q)}`}
                      className="inline-flex items-center gap-1.5 rounded-full border border-cream-100 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 shadow-sm transition-colors hover:border-amber-300 hover:text-oxblood"
                    >
                      <span aria-hidden>{c.emoji}</span> {c.label}
                    </Link>
                  ))}
                </div>

                {/* Two persona doors (motion-hooked) */}
                <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Link
                    href="/shop"
                    data-hero-door
                    className="group flex flex-col rounded-2xl bg-gradient-to-br from-amber-400 to-orange-500 p-6 text-amber-ink shadow-md transition-all hover:-translate-y-0.5 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:ring-offset-2"
                  >
                    <UtensilsCrossed className="h-8 w-8" />
                    <span className="mt-4 text-lg font-bold">Order food near you</span>
                    <span className="mt-1 text-sm text-amber-ink/75">
                      Browse independent kitchens and order in minutes.
                    </span>
                    <span className="mt-4 inline-flex items-center gap-1 text-sm font-semibold">
                      Browse shops
                      <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
                    </span>
                  </Link>

                  <Link
                    href="/for-operators"
                    data-hero-door
                    className="group flex flex-col rounded-2xl bg-oxblood p-6 text-white shadow-md transition-all hover:-translate-y-0.5 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-oxblood-600 focus-visible:ring-offset-2"
                  >
                    <Store className="h-8 w-8 text-gold" />
                    <span className="mt-4 text-lg font-bold">Run your food business</span>
                    <span className="mt-1 text-sm text-cream/80">
                      Take orders, manage your kitchen, go live in a day.
                    </span>
                    <span className="mt-4 inline-flex items-center gap-1 text-sm font-semibold text-gold">
                      Learn more
                      <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
                    </span>
                  </Link>
                </div>
              </div>

              {/* Right column — food-tile collage (A) */}
              <div aria-hidden className="hidden grid-cols-2 gap-4 lg:grid">
                {heroTiles.map((t, i) => (
                  <div
                    key={i}
                    className={`grid h-40 place-items-center rounded-2xl bg-gradient-to-br ${t.grad} text-6xl shadow-md ${t.extra}`}
                  >
                    {t.emoji}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* ── The kitchen row — REAL published shops (#544, #460) ─────────── */}
        {shops.length > 0 && (
          <section className="border-t border-cream-100 bg-white">
            <div
              data-width-tier="marketing"
              className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8 py-12")}
            >
              {/* The row's heading, its scroller and its cards now live in a
                  client island so that a visitor who GRANTS location gets the
                  same shops re-ordered by real distance (33-07). The shops are
                  still fetched HERE, on the server, and passed down — so the
                  real names are in the initial HTML exactly as before, and the
                  island's no-coordinate state is byte-for-byte what 33-03
                  shipped. The heading it renders without a coordinate makes no
                  locality claim; see near-you-row.tsx for the three states. */}
              <NearYouRow serverShops={shops} serverTotal={serverTotal} />
            </div>
          </section>
        )}

        {/* ── How it works (motion-hooked) ────────────────────────────────── */}
        <section className="bg-cream py-16">
          <div
            data-width-tier="marketing"
            className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8")}
          >
            <h2 data-hero-howtitle className="text-3xl font-bold leading-tight text-oxblood">
              How it works
            </h2>
            <div data-hero-steps className="relative mt-10 grid grid-cols-1 gap-8 sm:grid-cols-3">
              <div
                aria-hidden
                className="pointer-events-none absolute left-[16%] right-[16%] top-6 hidden h-0.5 rounded bg-cream-100 sm:block"
              >
                <div data-hero-railfill className="absolute inset-0 origin-left rounded bg-amber-500" />
              </div>
              {steps.map((step) => (
                <div key={step.title} data-hero-step className="group relative flex flex-col">
                  <span className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-white text-oxblood-600 shadow-sm ring-4 ring-cream transition-colors group-data-[step-active=true]:bg-oxblood group-data-[step-active=true]:text-white">
                    <step.icon className="h-6 w-6" />
                  </span>
                  <h3 className="mt-4 text-lg font-semibold text-slate-900">{step.title}</h3>
                  <p className="mt-2 text-sm text-slate-600">{step.body}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* ── Trust strip (motion-hooked chips) ───────────────────────────── */}
        <section className="border-t border-cream-100 bg-white py-12">
          <div
            data-width-tier="marketing"
            className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8")}
          >
            <Reveal as="div" className="flex flex-wrap gap-3">
              {trustMarkers.map((marker) => (
                <span
                  key={marker}
                  data-hero-chip
                  className="inline-flex items-center gap-2 rounded-full bg-cream px-4 py-2 text-sm font-medium text-slate-700 shadow-sm ring-1 ring-cream-100"
                >
                  <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                  {marker}
                </span>
              ))}
            </Reveal>
          </div>
        </section>
      </HeroScene>
    </PublicShell>
  )
}
