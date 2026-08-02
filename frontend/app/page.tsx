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
import { HeroScene } from "@/components/marketing/hero-scene"
import { HeroSearch } from "@/components/marketing/hero-search"
import { Reveal } from "@/components/marketing/reveal"
import { DishScroller } from "@/components/marketing/dish-scroller"

export const metadata: Metadata = {
  title: "J'Toye — Order from local kitchens, or run your own",
  description:
    "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders, manage your kitchen, and go live in a day.",
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

// Illustrative "cooking near you" strip (sketch 004 variant A). Emoji + warm
// gradient stand in for real dish photography until vendor media lands; the
// cards link into the storefront.
const featuredDishes = [
  { emoji: "🍗", grad: "from-[#fbe9d4] to-[#f6c99a]", name: "Jollof & Grilled Chicken", vendor: "Mama's Kitchen", rating: "4.8", price: "£9.50", q: "jollof" },
  { emoji: "🍛", grad: "from-[#f6dcd8] to-[#e8a9a2]", name: "Lamb Biryani", vendor: "Spice Route", rating: "4.9", price: "£11.00", q: "biryani" },
  { emoji: "🥙", grad: "from-[#e7f3ea] to-[#a9d9bb]", name: "Halloumi Wrap", vendor: "Olive & Vine", rating: "4.7", price: "£7.25", q: "wrap" },
  { emoji: "🍰", grad: "from-[#fdeecb] to-[#f3cf7a]", name: "Basque Cheesecake", vendor: "Crumb & Co", rating: "4.9", price: "£5.00", q: "dessert" },
  { emoji: "🍜", grad: "from-[#efe4f3] to-[#c9a9d9]", name: "Pho Bo", vendor: "Hanoi House", rating: "4.8", price: "£10.50", q: "pho" },
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
 */
export default function Home() {
  return (
    <PublicShell>
      <HeroScene>
        {/* ── Split-persona hero ─────────────────────────────────────────── */}
        <section data-hero-section className="relative overflow-hidden bg-cream">
          <div
            data-hero-heatwash
            aria-hidden
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(120%_90%_at_85%_-10%,#fde7c8_0%,transparent_55%)]"
          />
          <div className="relative mx-auto max-w-6xl px-4 sm:px-6 lg:px-8 py-14 md:py-20">
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

        {/* ── Cooking near you (A: appetite dish row) ─────────────────────── */}
        <section className="border-t border-cream-100 bg-white">
          <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8 py-12">
            <div className="flex items-baseline justify-between">
              <h2 className="text-2xl font-bold text-oxblood">Cooking near you right now</h2>
              <Link href="/shop" className="text-sm font-bold text-amber-600 hover:text-amber-700">
                See all kitchens →
              </Link>
            </div>
            {/*
              Card hover is gated on a fine pointer. `future.hoverOnlyWhenSupported`
              is unset in tailwind.config.ts, so a bare `hover:` latches on tap and
              leaves the card stuck lifted after a touch.
            */}
            <div className="mt-5">
              <DishScroller label="Dishes cooking near you">
                {featuredDishes.map((d) => (
                  <Link
                    key={d.name}
                    href={`/shop?q=${encodeURIComponent(d.q)}`}
                    className="group min-w-[190px] shrink-0 overflow-hidden rounded-xl border border-cream-100 bg-white shadow-sm transition-[box-shadow,transform] duration-200 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.98] [@media(hover:hover)_and_(pointer:fine)]:hover:shadow-md"
                  >
                    <div className={`grid h-28 place-items-center bg-gradient-to-br ${d.grad} text-5xl`}>
                      <span aria-hidden>{d.emoji}</span>
                    </div>
                    <div className="p-3.5">
                      <div className="font-bold text-slate-900">{d.name}</div>
                      <div className="mt-0.5 text-xs text-slate-500">
                        {d.vendor} · ⭐ {d.rating} · FHRS 5
                      </div>
                      <div className="mt-2 font-extrabold text-oxblood">{d.price}</div>
                    </div>
                  </Link>
                ))}
              </DishScroller>
            </div>
          </div>
        </section>

        {/* ── How it works (motion-hooked) ────────────────────────────────── */}
        <section className="bg-cream py-16">
          <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
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
          <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
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
