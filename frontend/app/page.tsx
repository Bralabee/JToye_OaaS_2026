import type { Metadata } from "next"
import Image from "next/image"
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
import { PHOTOS, PHOTO_CREDITS, PHOTO_DERIVATION_NOTE, type LandingPhoto } from "@/components/marketing/landing-photos"
import { loadShopList } from "@/lib/storefront-server"
import { headers } from "next/headers"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { serialiseJsonLd, shopListStructuredData } from "@/lib/structured-data"

export const metadata: Metadata = {
  title: "J'Toye — Order from local kitchens, or run your own",
  description:
    "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders and manage your kitchen, with an assisted setup.",
  // SW-CLR-03 (swarm epic #964): this description used to promise "go live in a
  // day", while /for-operators says a person reviews every application and the
  // setup is assisted. That promise is removed; "assisted setup" is the
  // /for-operators wording. The positioning itself (UK-wide here, a West African
  // London pilot there) is an open owner decision: design/brief.md, confirmed
  // 2026-11-01, describes independent UK food businesses, so it is not narrowed.
  //
  // FE-5: canonical + Open Graph were missing on the landing page — the one
  // page most likely to be shared/linked externally. `alternates.canonical`
  // stays a relative path: since SW-CLR-08 the root layout sets `metadataBase`
  // from the injected public origin, and Next resolves it to an absolute URL
  // against that (no hostname is written here; app/layout.tsx says why).
  alternates: { canonical: "/" },
  openGraph: {
    title: "J'Toye — Order from local kitchens, or run your own",
    description:
      "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders and manage your kitchen, with an assisted setup.",
    url: "/",
    type: "website",
  },
}

const steps = [
  { icon: Search, title: "Browse", body: "Find independent kitchens near you and explore their menus." },
  { icon: ShoppingBag, title: "Order & pay", body: "Add to your basket and check out securely — delivery or collection." },
  // D14 (owner, 2026-11-01): the platform has no courier tracking — /track polls
  // the order's status (PENDING → CONFIRMED → PREPARING → READY → COMPLETED), so
  // the step promises exactly that and no longer "from the kitchen to your door
  // in real time".
  {
    icon: MapPin,
    title: "Track live",
    body: "See your order's status change as the kitchen confirms it, prepares it and gets it ready.",
  },
]

const trustMarkers = [
  "UK food-hygiene verified",
  "Allergen info on every item",
  "No app to download",
]

// Discovery chips (sketch 004 variant C). Each is a real crawlable link that
// runs the query it advertises (`/shop?q=…`) rather than dumping you on the
// unfiltered index, so the marketplace reads as alive from the first screen.
// D14: each carries a round dish thumbnail in place of its emoji. The picture is
// decorative (`alt=""`): the label is the link's name.
const categories: Array<{ photo: LandingPhoto; label: string; q: string }> = [
  { photo: PHOTOS.suya, label: "Grill", q: "grill" },
  { photo: PHOTOS.jollofVeg, label: "Jollof", q: "jollof" },
  { photo: PHOTOS.riceAndPeas, label: "Caribbean", q: "caribbean" },
  { photo: PHOTOS.biryani, label: "South Asian", q: "south asian" },
  { photo: PHOTOS.chana, label: "Vegan", q: "vegan" },
  { photo: PHOTOS.puffPuff, label: "Desserts", q: "dessert" },
]

// The desktop collage (A): four dish photographs in place of the four gradient
// emoji tiles (D9, D14). The small tilts are static, kept from the old tiles.
const heroTiles: Array<{ photo: LandingPhoto; extra: string }> = [
  { photo: PHOTOS.egusi, extra: "-rotate-2" },
  { photo: PHOTOS.suya, extra: "rotate-2 mt-6" },
  { photo: PHOTOS.jollofVeg, extra: "rotate-2" },
  { photo: PHOTOS.puffPuff, extra: "-rotate-2 -mt-2" },
]

/**
 * Public landing page (Surface A, UIX-01) — sketch 004 winner D (A+C hybrid),
 * ELEVATED 2026-11-01 (design D14: runs/2026-11-01-landing-elevate).
 *
 * The structure is the old page's, kept on purpose (rubric R11: elevate, do not
 * redesign): A's hero + food collage + kitchen row, plus C's search + category
 * chips. The identity is D14's: the oxblood ramp (brand-900 = the parent site's
 * #3A0B0D), gold as the one action colour, a warm-stone neutral ground, Work
 * Sans. Credited dish photographs replace every emoji; the radial wash, the
 * gradient door and tiles, the eyebrow, resting shadows and 1px rules are gone,
 * and surfaces are separated by a tone step (white against warm-100).
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
 *   PRESERVED  DishScroller wrapper — proximity snap, fine-pointer arrows. The
 *              edge fade became a CARD PEEK on phones (D14, gradient ban)
 *   PRESERVED  the label "Dishes cooking near you", BYTE-IDENTICAL. It is an
 *              aria-label on a scroll region (not a heading, so the location
 *              criterion does not reach it) and it is the selector at
 *              marketing-dish-scroller.spec.ts:19. Changing it buys nothing and
 *              breaks the only guard on the affordance.
 *   PRESERVED  active:scale-[0.98] tap feedback, and the written-out
 *              [@media(hover:hover) and (pointer:fine)] hover gate
 *   PRESERVED  every data-hero-* GSAP hook (none were in this row)
 *   PRESERVED  split-persona H1, both persona doors, header, footer
 *   PRESERVED  the How-it-works step icon's data-step-active state (HeroScene
 *              toggles it on scrub): brand-900 at rest, brand-700 once the rail
 *              has passed, in place of the old white-to-oxblood swap (review 1085)
 *   REPLACED   /shop?q=… deep link -> /shop/{slug}: a real shop page instead of a
 *              search that might match nothing
 *   REPLACED   emoji + gradient -> a real picture through next/image or SafeImage
 *              with EXPLICIT width and height, so the box is reserved (CLS):
 *              since D13/D14 the shop's own BANNER when it has one, else a
 *              credited example photo of its cuisine captioned
 *              "<Cuisine> — example photo". The placeholder logoUrl that 33-03
 *              first put here is no longer rendered at all (D12).
 *   REMOVED    hardcoded rating, FHRS badge and dish price — none exists on
 *              PublicShop, and attributing an invented rating to a REAL named
 *              business is worse than the fiction it replaces
 *   FIVE -> N  the row is however many shops are published, currently three
 *
 * The heading makes no claim about where the visitor is. It deliberately does not
 * say "Open now" either. The old client parser returned true when hours were null
 * or empty, so an openness claim was a fiction for every shop with no hours data —
 * exactly what #544 exists to stop. Since 37-22 each CARD carries the server's own
 * answer (Open / Paused / Closed from `openNow`, the decision checkout enforces),
 * and prints nothing when the server sent none; the heading still claims nothing.
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
        {/* D14: a flat warm-100 field. The radial wash that sat here (and the
            GSAP parallax that moved it) is gone under the gradient ban. */}
        <section data-hero-section className="relative overflow-hidden bg-warm-100">
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
              "px-4 sm:px-6 lg:px-8 pt-10 pb-14 md:pt-[72px] md:pb-[88px]"
            )}
          >
            <div className="grid items-center gap-10 lg:grid-cols-2 lg:gap-14">
              {/* Left column — copy, search, doors */}
              <div>
                {/* The tracked-caps eyebrow "Independent UK kitchens" is gone
                    (D14): "independent" is in the sentence below, and "UK" is in
                    the trust strip. Three lines at every width is the owner's
                    ruled exception to taste-skill's two-line rule (D14 item 2).

                    The min-height is a CLS reservation, measured, not styling.
                    The size-adjusted fallback face sets this line NARROWER than
                    Work Sans, so before the web font arrives the h1 takes two
                    lines where Work Sans takes three, and the swap pushed the
                    whole first screen down by one line (throttled 375px: CLS
                    0.2025 against the route's 0.1793 baseline; 1440px: 0.157
                    against 0.1316). Measured per width on the built page, Work
                    Sans takes three lines at 360-413px (34px) and at every
                    width from 1024px (56px, the half-width column is capped by
                    the band), so exactly those ranges reserve three line boxes
                    (3 x leading 1.04 = 3.12em). Elsewhere both faces take the
                    same count, or the gap is a single phone-to-tablet band. */}
                <h1
                  data-hero-headline
                  className="text-[34px] font-extrabold leading-[1.04] tracking-[-0.03em] text-brand-900 [text-wrap:balance] max-[413px]:min-h-[3.12em] sm:text-5xl lg:min-h-[3.12em] lg:text-[56px]"
                >
                  Order from local kitchens. Or run yours.
                </h1>
                <p className="mt-4 max-w-xl text-base text-warm-900 md:text-xl md:leading-normal">
                  J&apos;Toye connects hungry customers with independent food
                  businesses — order in minutes, or apply to take your kitchen
                  online with an assisted setup.
                </p>

                {/* C: discovery search + category chips. Both run a REAL query
                    against /shop?q= — the chips stay crawlable <a href> so the
                    category pages are reachable without JS. */}
                <HeroSearch />
                <div className="mt-4 flex flex-wrap gap-2">
                  {categories.map((c) => (
                    <Link
                      key={c.label}
                      href={`/shop?q=${encodeURIComponent(c.q)}`}
                      className="inline-flex items-center gap-2 rounded-full bg-white py-1 pl-1 pr-3.5 text-sm font-medium text-warm-900 transition-colors duration-150 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-warm-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
                    >
                      <Image
                        src={c.photo.src}
                        width={32}
                        height={32}
                        sizes="32px"
                        alt=""
                        className="h-8 w-8 rounded-full object-cover"
                      />
                      {c.label}
                    </Link>
                  ))}
                </div>

                {/* Two persona doors (motion-hooked). D14: two FLAT fields, gold
                    for ordering (the action accent) and oxblood for operators;
                    no gradient, no resting shadow, no hover lift. Press is a
                    0.98 scale; hover is one ramp step, gated on a fine pointer. */}
                <div className="mt-7 grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-4">
                  <Link
                    href="/shop"
                    data-hero-door
                    className="group flex flex-col overflow-hidden rounded-2xl bg-gold-400 text-brand-900 transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.98] motion-reduce:transition-none [@media(hover:hover)_and_(pointer:fine)]:hover:bg-gold-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
                  >
                    {/* Food in the phone's first screen: the tiles are
                        desktop-only, this strip is not. Eager and high
                        priority — on a phone it is the first picture seen. */}
                    <Image
                      src={PHOTOS.jollofDodo.src}
                      width={PHOTOS.jollofDodo.width}
                      height={PHOTOS.jollofDodo.height}
                      sizes="(min-width: 1024px) 300px, (min-width: 640px) 45vw, 92vw"
                      alt={PHOTOS.jollofDodo.alt}
                      loading="eager"
                      fetchPriority="high"
                      className="h-[120px] w-full object-cover"
                    />
                    <span className="flex flex-1 flex-col px-6 pb-6 pt-5">
                      <UtensilsCrossed aria-hidden className="mb-3 h-7 w-7" />
                      <span className="text-xl font-bold leading-tight">Order food near you</span>
                      <span className="mt-1.5 text-base leading-snug">
                        Browse independent kitchens and order in minutes.
                      </span>
                      <span className="mt-auto inline-flex items-center gap-1.5 pt-4 text-sm font-bold">
                        Browse shops
                        <ArrowRight className="h-4 w-4 transition-transform duration-150 [@media(hover:hover)_and_(pointer:fine)]:group-hover:translate-x-[3px]" />
                      </span>
                    </span>
                  </Link>

                  <Link
                    href="/for-operators"
                    data-hero-door
                    className="group flex flex-col overflow-hidden rounded-2xl bg-brand-900 text-white transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.98] motion-reduce:transition-none [@media(hover:hover)_and_(pointer:fine)]:hover:bg-brand-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
                  >
                    <span className="flex flex-1 flex-col px-6 pb-6 pt-5">
                      <Store aria-hidden className="mb-3 h-7 w-7 text-gold" />
                      <span className="text-xl font-bold leading-tight">Run your food business</span>
                      <span className="mt-1.5 text-base leading-snug text-brand-100">
                        Take orders and manage your kitchen. A person reviews every application.
                      </span>
                      <span className="mt-auto inline-flex items-center gap-1.5 pt-4 text-sm font-bold text-gold">
                        Learn more
                        <ArrowRight className="h-4 w-4 transition-transform duration-150 [@media(hover:hover)_and_(pointer:fine)]:group-hover:translate-x-[3px]" />
                      </span>
                    </span>
                  </Link>
                </div>
              </div>

              {/* Right column — dish photo collage (A), desktop only as before.
                  Decorative beside the doors, so the grid stays aria-hidden and
                  the pictures carry empty alt text. */}
              <div aria-hidden className="hidden grid-cols-2 gap-4 lg:grid">
                {heroTiles.map((t) => (
                  <Image
                    key={t.photo.src}
                    src={t.photo.src}
                    width={t.photo.width}
                    height={t.photo.height}
                    sizes="(min-width: 1024px) 300px, 1px"
                    alt=""
                    className={`h-[200px] w-full rounded-2xl object-cover ${t.extra}`}
                  />
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* ── The kitchen row — REAL published shops (#544, #460) ─────────── */}
        {shops.length > 0 && (
          <section className="bg-white">
            <div
              data-width-tier="marketing"
              className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8 pt-14 pb-16")}
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
        <section className="bg-warm-100 pb-[72px] pt-16 md:pb-24 md:pt-[88px]">
          <div
            data-width-tier="marketing"
            className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8")}
          >
            <h2
              data-hero-howtitle
              className="text-2xl font-bold leading-tight tracking-[-0.015em] text-brand-900 md:text-[28px]"
            >
              How it works
            </h2>
            <div data-hero-steps className="relative mt-9 grid grid-cols-1 gap-8 sm:grid-cols-3">
              {/* The rail: a 2px warm-300 track, decoration only, and the
                  brand-700 fill GSAP scrubs across it. */}
              <div
                aria-hidden
                className="pointer-events-none absolute left-[16%] right-[16%] top-6 hidden h-0.5 bg-warm-300 sm:block"
              >
                <div data-hero-railfill className="absolute inset-0 origin-left bg-brand-700" />
              </div>
              {steps.map((step) => (
                <div key={step.title} data-hero-step className="group relative flex flex-col">
                  {/* HeroScene sets data-step-active on the step as the rail
                      passes it (hero-scene.tsx): the icon answers with one ramp
                      step, brand-900 to brand-700, matching the rail's fill. */}
                  <span className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-brand-900 text-white transition-colors duration-150 group-data-[step-active=true]:bg-brand-700">
                    <step.icon aria-hidden className="h-[22px] w-[22px]" />
                  </span>
                  <h3 className="mt-4 text-xl font-bold leading-tight text-warm-900">{step.title}</h3>
                  <p className="mt-1.5 max-w-[30em] text-base text-warm-700">{step.body}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* ── Trust strip (motion-hooked chips) and the photo credits ─────── */}
        {/* The credit line shares the last band rather than adding a fifth: the
            band count is pinned in landing.test.tsx. It names every creator and
            licence; credits.json beside the files is the per-picture record. */}
        <section className="bg-white pb-8 pt-12">
          <div
            data-width-tier="marketing"
            className={cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8")}
          >
            <Reveal as="div" className="flex flex-wrap gap-3">
              {trustMarkers.map((marker) => (
                <span
                  key={marker}
                  data-hero-chip
                  className="inline-flex items-center gap-2 rounded-full bg-warm-100 px-4 py-2 text-sm font-medium text-warm-900"
                >
                  <CheckCircle2 aria-hidden className="h-4 w-4 text-emerald-700" />
                  {marker}
                </span>
              ))}
            </Reveal>
            {/* Each picture: title linked to its source page, creator, licence
                linked; the modification stated once. What CC BY-SA 4.0 asks for. */}
            <p className="mt-8 max-w-[70ch] text-sm text-warm-700">
              Photographs ({PHOTO_DERIVATION_NOTE}), via Wikimedia Commons:{" "}
              {PHOTO_CREDITS.map((c, i) => (
                <span key={c.src}>
                  <a href={c.page} className="underline decoration-warm-300 underline-offset-2 hover:decoration-warm-700">
                    {c.title}
                  </a>{" "}
                  by {c.creator} (
                  <a href={c.licenseUrl} className="underline decoration-warm-300 underline-offset-2 hover:decoration-warm-700">
                    {c.license}
                  </a>
                  ){i < PHOTO_CREDITS.length - 1 ? "; " : "."}
                </span>
              ))}
            </p>
          </div>
        </section>
      </HeroScene>
    </PublicShell>
  )
}
