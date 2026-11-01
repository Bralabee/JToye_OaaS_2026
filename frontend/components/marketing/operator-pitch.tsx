"use client"

import { useRef } from "react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { useOperatorEntranceScene } from "@/components/marketing/operator-entrance-scene"
import { SafeImage } from "@/components/ui/safe-image"
import { OPERATOR_APPLICATION_REVIEW_LINE, OPERATOR_FBO_LINE } from "@/lib/operator-copy"
import {
  formatRate,
  formatSetupLine,
  PRICING,
  PRICING_CARD_FEE_LINE,
  STRIPE_UK_PRICING_LINK_TEXT,
  STRIPE_UK_PRICING_URL,
} from "@/lib/pricing"

/**
 * Every price figure comes from the shared module (lib/pricing.ts), the same
 * one /business-model-guide and /competitive read, so a ruling is a change in
 * that file and no page can drift from another. What stays here is display
 * only: the tier labels and notes, and which row is the primary one (the hero
 * docket reads it, so the docket and the table cannot disagree).
 */
const PLANS = [
  { name: "Standard", note: "Most operators start here.", docket: "of your direct sales", monthly: [`£${PRICING.coreMonthly}`, "per location"], fee: [PRICING.usageLabel, "of direct sales"], primary: true },
  { name: "Flat", note: "One flat bill.", docket: "of your sales", monthly: [`£${PRICING.flatLow}–£${PRICING.flatHigh}`, "per location"], fee: [formatRate(0), "of sales"], primary: false },
  { name: "Batch pay-as-you-go", note: "Weekend or seasonal batch kitchens.", docket: "of your direct sales", monthly: ["£0", ""], fee: [PRICING.paygLabel, "of direct sales"], primary: false },
] as const

// The two footnotes live in lib/pricing.ts so /legal/terms quotes the same strings.
const SETUP_LINE = formatSetupLine(PRICING)
const CARD_FEE_LINE = PRICING_CARD_FEE_LINE

/**
 * The margin sentence's illustration: the positioning guide's 28% aggregator
 * commission on £10k a month of orders. These two are the guide's figures, not
 * billed prices; the £2,800 is computed from them, never typed.
 */
const AGGREGATOR_RATE = 0.28
const MONTHLY_ORDERS = 10_000
const aggregatorBill = Math.round(AGGREGATOR_RATE * MONTHLY_ORDERS).toLocaleString("en-GB")

/** What is not live, and the date that statement was true. */
const NOT_YET_LIVE_AS_OF = "19 October 2026"
const NOT_YET_LIVE = [
  "Card payments are in Stripe test mode until the first production tenant.",
  "The browser kitchen display is online-only. There is no offline mode.",
  "WhatsApp is a generated pre-order link, not an automated WhatsApp channel.",
  "A storefront does not bring customers by itself.",
] as const

/**
 * Product images. Each is rendered from a fictional shop by
 * frontend/scripts/marketing-captures/capture-operators.mjs, never from live
 * data. Width and height are the intrinsic pixels, so the box is reserved
 * before the file arrives; `box` keeps the same aspect ratio if the file is
 * missing (SafeImage's fallback would otherwise collapse to nothing).
 */
const IMAGES = {
  hero: { box: "aspect-[8/5] w-full rounded-xl", src: "/marketing/operators/kitchen-cook-sheet.webp", width: 1280, height: 800, alt: "The kitchen display for a sample shop: the consolidated cook sheet with batch totals, an allergy instruction and each dish's recorded allergens and label button, above the lane of new orders." },
  counter: { box: "aspect-[3/2] w-full rounded-xl", src: "/marketing/operators/storefront-menu.webp", width: 960, height: 640, alt: "A sample storefront menu with jollof rice, egusi soup, suya and moin moin, each with its allergens listed." },
  weekend: { box: "aspect-[3/2] w-full rounded-xl", src: "/marketing/operators/slot-picker.webp", width: 960, height: 640, alt: "The checkout Fulfilment Window, listing weekend slots with the order cut-off and the places left in each." },
  label: { box: "aspect-[3/2] w-full rounded-xl", src: "/marketing/operators/ppds-label.webp", width: 960, height: 640, alt: "A 62 mm Natasha's Law label for a sample dish, with its ingredients and its allergen, celery, in bold." },
} as const

const DAILY = [
  "Instant web and QR ordering",
  "UK opening hours and a one-tap kitchen pause",
  "Live kitchen display with new-order alert",
  "Caps on cash orders",
] as const

const WEEKEND = [
  "Fulfilment slots with cut-offs and capacity",
  "Prep deposits, then a balance payment link",
  "One consolidated cook sheet for the whole batch",
  "A WhatsApp pre-order link to share",
] as const

const COMPLIANCE = [
  ["Allergen engine", "All 14 allergens acknowledged server-side, “may contain” kept separate, customer allergy notes audited."],
  ["Natasha’s Law labels", "PPDS labels for a 62 mm thermal printer."],
  ["Trader disclosures and data rights", "Statutory trader details on your storefront, GDPR export and erasure."],
] as const

const PILOT_STEPS = [
  ["1", "Map your service", "Your menu, hours and the journey you already run."],
  ["2", "Set up the direct path", "Your storefront and ordering, built together."],
  ["3", "Run a real service", "Online orders on the kitchen display. You fulfil."],
  ["4", "Review, then expand", "Agree the next step together."],
] as const

const FIT_CRITERIA = [
  "You already run a food business with recurring orders.",
  "You trade in London.",
  "You want direct orders, not a marketplace listing.",
] as const

const CTA_CLASS =
  "inline-flex items-center rounded-full bg-amber-500 px-6 py-3.5 text-base font-bold text-amber-ink transition-[background-color,transform] duration-150 ease-out hover:bg-amber-400 active:scale-[0.97] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300 motion-reduce:transition-none motion-reduce:active:scale-100"

/** Secondary text link on the cream ground (contact beside the closing CTA, terms in pricing). */
const INLINE_LINK_CLASS =
  "font-semibold text-oxblood underline decoration-amber-500 decoration-2 underline-offset-[5px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-500"

const H2_CLASS = "max-w-[26ch] text-2xl font-bold leading-tight tracking-[-0.02em] text-oxblood sm:text-3xl"

const TICK_CLASS =
  "relative pl-[22px] before:absolute before:left-0.5 before:top-[0.62em] before:size-2 before:rounded-full before:bg-amber-500 before:content-['']"

/**
 * Operator pitch (Surface C).
 *
 * A kitchen docket, not a SaaS landing page: one dark band (the hero) that
 * holds the product and the number, warm paper everywhere else, hairlines
 * instead of boxes. Five sections, each answering one operator question:
 * what is it and what does it cost, does it fit how I trade, will it keep me
 * legal, what exactly do I pay, what happens after I apply.
 *
 * PHASE 35 / UIX-07: each of the five sections carries its own band element
 * declaring the Marketing width tier, and the tier class is never written as a
 * literal here (the gate reads that it occurs in exactly one module). The
 * COUNT is asserted in __tests__/operator-pitch.test.tsx, because a band left
 * on the old token renders identically today and only diverges once the tier's
 * value moves. Full-bleed backgrounds sit on the OUTER section, which is not a
 * band, so they neither carry the declaration nor change the count.
 *
 * Motion is on-load, not on-scroll (useOperatorEntranceScene): the headline
 * words and the hero image arrive once, and nothing else moves. No pinned
 * sections, no scroll reveals: the page arrives populated.
 */
export function OperatorPitch() {
  const rootRef = useRef<HTMLDivElement>(null)
  useOperatorEntranceScene(rootRef)

  const standard = PLANS.find((plan) => plan.primary) ?? PLANS[0]

  return (
    <div ref={rootRef} className="min-h-screen bg-cream text-slate-900 selection:bg-amber-300 selection:text-oxblood">
      <a href="#main-pitch" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to operator pitch</a>

      {/* 1. Hero: what is it, and what does it cost me? */}
      <section id="main-pitch" className="bg-oxblood text-cream">
        <div
          data-width-tier="marketing"
          className={`mx-auto grid ${WIDTH_TIER_CLASS.marketing} items-center gap-10 px-5 pb-16 pt-14 sm:px-8 lg:grid-cols-[5fr_6fr] lg:gap-16 lg:pb-[88px] lg:pt-[72px]`}
        >
          <div>
            <h1 data-op-headline className="max-w-4xl text-balance text-4xl font-bold leading-[1.08] tracking-[-0.03em] sm:text-5xl">One kitchen. Weekday counter, weekend catering.</h1>
            <p className="mt-5 max-w-xl text-lg leading-relaxed text-cream/80">Direct ordering, scheduled fulfilment and allergen labels for established West African food businesses in London. Customers pay you directly.</p>
            <div className="mt-8 flex flex-wrap items-center gap-x-7 gap-y-3">
              <a href="/dashboard/onboarding" className={CTA_CLASS}>Start your application</a>
              <a href="#pricing" className="text-[15px] font-semibold text-cream underline decoration-gold decoration-2 underline-offset-[5px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300">See pricing</a>
              {/* SW-CLR-04 (persona swarm): a way to ask before applying, which ends at a sign-in. */}
              <a href="/contact" className="text-[15px] font-semibold text-cream underline decoration-gold decoration-2 underline-offset-[5px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300">Contact us</a>
            </div>
          </div>
          <div data-op-media className="relative">
            <SafeImage
              src={IMAGES.hero.src}
              alt={IMAGES.hero.alt}
              width={IMAGES.hero.width}
              height={IMAGES.hero.height} fallbackClassName={IMAGES.hero.box}
              loading="eager"
              fetchPriority="high"
              className="h-auto w-full rounded-xl shadow-[0_24px_60px_-20px_rgba(0,0,0,0.55)]"
            />
            <div data-op-figure className="relative -mt-7 mx-4 rounded-md bg-cream px-[22px] pb-[18px] pt-4 text-oxblood shadow-[0_14px_34px_-14px_rgba(0,0,0,0.6)] lg:absolute lg:-bottom-9 lg:-left-14 lg:mx-0 lg:mt-0">
              <p className="text-5xl font-bold leading-none tracking-[-0.04em] tabular-nums sm:text-6xl">{standard.fee[0]}</p>
              <p className="mt-2 max-w-[19rem] text-sm leading-snug text-slate-700"><strong className="font-semibold text-oxblood">{standard.docket}</strong>, plus {standard.monthly[0]} a month per location. Card fees go to Stripe, not to us.</p>
            </div>
          </div>
        </div>
      </section>

      {/* 2. Both rhythms: does it fit how I actually trade? */}
      <section id="both-rhythms" aria-labelledby="rhythms-heading">
        <div data-width-tier="marketing" className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-5 pb-12 pt-[72px] sm:px-8 lg:pb-16 lg:pt-[88px]`}>
          <h2 id="rhythms-heading" className={H2_CLASS}>Both rhythms, one kitchen</h2>
          <p className="mt-3 max-w-[40rem] text-slate-600">One menu, one ledger, one kitchen display. An established operator paying {Math.round(AGGREGATOR_RATE * 100)}% aggregator commission on £{MONTHLY_ORDERS / 1000}k a month of orders hands over £{aggregatorBill} a month; the same orders through a direct storefront cost £{PRICING.coreMonthly} + {PRICING.usageLabel}, plus Stripe&apos;s card fees, which you would pay either way.</p>
          <div className="mt-10 grid gap-10 md:grid-cols-2 md:gap-0">
            <div className="md:border-r md:border-cream-100 md:pr-10">
              <SafeImage src={IMAGES.counter.src} alt={IMAGES.counter.alt} width={IMAGES.counter.width} height={IMAGES.counter.height} fallbackClassName={IMAGES.counter.box} className="h-auto w-full rounded-xl border border-cream-100" />
              <h3 className="mb-1 mt-5 text-xl font-bold leading-snug text-oxblood">Daily counter and local delivery</h3>
              <p className="text-[15px] text-slate-600">Orders arrive all day.</p>
              <ul className="mt-4 grid gap-2 text-[15px] text-slate-700">{DAILY.map((item) => <li key={item} className={TICK_CLASS}>{item}</li>)}</ul>
            </div>
            <div className="md:pl-10">
              <SafeImage src={IMAGES.weekend.src} alt={IMAGES.weekend.alt} width={IMAGES.weekend.width} height={IMAGES.weekend.height} fallbackClassName={IMAGES.weekend.box} className="h-auto w-full rounded-xl border border-cream-100" />
              <h3 className="mb-1 mt-5 text-xl font-bold leading-snug text-oxblood">Weekend batch and catering</h3>
              <p className="text-[15px] text-slate-600">Orders are booked ahead and cooked in one push.</p>
              <ul className="mt-4 grid gap-2 text-[15px] text-slate-700">{WEEKEND.map((item) => <li key={item} className={TICK_CLASS}>{item}</li>)}</ul>
            </div>
          </div>
        </div>
      </section>

      {/* 3. Compliance: will it keep me legal? */}
      <section id="compliance" aria-labelledby="compliance-heading">
        <div data-width-tier="marketing" className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-5 pb-12 sm:px-8 lg:pb-16`}>
          <h2 id="compliance-heading" className={H2_CLASS}>Compliance is part of the order</h2>
          <div className="mt-8 grid gap-8 md:grid-cols-[5fr_6fr] md:items-start md:gap-16">
            <SafeImage src={IMAGES.label.src} alt={IMAGES.label.alt} width={IMAGES.label.width} height={IMAGES.label.height} fallbackClassName={IMAGES.label.box} className="h-auto w-full rounded-xl border border-cream-100" />
            <div>
              <dl className="grid gap-5">
                {COMPLIANCE.map(([term, detail]) => (
                  <div key={term}>
                    <dt className="font-bold text-oxblood">{term}</dt>
                    <dd className="mt-0.5 text-[15px] text-slate-600">{detail}</dd>
                  </div>
                ))}
              </dl>
              <p className="mt-7 border-l-[3px] border-amber-500 pl-4 text-[15px] font-medium text-slate-700">{OPERATOR_FBO_LINE}</p>
            </div>
          </div>
        </div>
      </section>

      {/* 4. Pricing: what exactly do I pay? A deeper cream groups the terms apart from the product. */}
      <section id="pricing" aria-labelledby="pricing-heading" className="scroll-mt-4 bg-cream-100">
        <div data-width-tier="marketing" className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-5 py-16 sm:px-8 lg:pb-[88px] lg:pt-24`}>
          <h2 id="pricing-heading" className={H2_CLASS}>Pricing</h2>
          <table className="mt-7 w-full border-collapse text-[15px] max-md:block">
            <caption className="sr-only">Platform pricing per location</caption>
            <thead className="max-md:sr-only">
              <tr>
                <th scope="col" className="border-b border-oxblood/15 pb-4 pr-4 text-left text-sm font-semibold text-slate-600">Plan</th>
                <th scope="col" className="border-b border-oxblood/15 pb-4 pr-4 text-left text-sm font-semibold text-slate-600">Monthly</th>
                <th scope="col" className="border-b border-oxblood/15 pb-4 pr-4 text-left text-sm font-semibold text-slate-600">Platform fee</th>
              </tr>
            </thead>
            <tbody className="max-md:block">
              {PLANS.map((plan) => (
                <tr key={plan.name} className="max-md:block max-md:border-b max-md:border-b-oxblood/15 max-md:py-4">
                  <th scope="row" className={`text-left align-top text-base font-bold text-oxblood max-md:block max-md:py-0.5 md:border-b md:border-b-oxblood/15 md:py-4 md:pr-4 ${plan.primary ? "border-l-[3px] border-amber-500 pl-3.5 md:pl-3.5" : ""}`}>
                    {plan.name}
                    <small className="mt-0.5 block text-sm font-normal text-slate-600">{plan.note}</small>
                  </th>
                  <PriceCell label="Monthly" figure={plan.monthly[0]} unit={plan.monthly[1]} />
                  <PriceCell label="Platform fee" figure={plan.fee[0]} unit={plan.fee[1]} />
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-5 max-w-[44rem] text-sm text-slate-600">{SETUP_LINE} {CARD_FEE_LINE} Current rates are on{" "}<a href={STRIPE_UK_PRICING_URL} target="_blank" rel="noopener noreferrer" className={INLINE_LINK_CLASS}>{STRIPE_UK_PRICING_LINK_TEXT}<span className="sr-only"> (opens in a new tab)</span></a>. See the <a href="/legal/terms" className={INLINE_LINK_CLASS}>terms of use</a>.</p>
        </div>
      </section>

      {/* 5. Pilot and limits: what happens after I apply, and what is not ready? */}
      <section id="pilot" aria-labelledby="pilot-heading">
        <div data-width-tier="marketing" className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-5 py-16 sm:px-8 lg:pb-24 lg:pt-20`}>
          <h2 id="pilot-heading" className={H2_CLASS}>How the assisted pilot runs</h2>
          <ol data-pilot-track className="mt-7 grid border-t border-cream-100 md:grid-cols-4 md:gap-7 md:border-t-0">
            {PILOT_STEPS.map(([number, title, detail]) => (
              <li key={number} data-pilot-step className="border-b border-cream-100 py-4 text-[15px] text-slate-600 md:border-b-0 md:border-t-2 md:border-oxblood md:pb-0 md:pt-3.5">
                <span className="text-sm font-bold tabular-nums text-amber-700">{number}</span>
                <h3 className="text-base font-bold text-oxblood">{title}</h3>
                {detail}
              </li>
            ))}
          </ol>
          <div className="mt-14 grid gap-10 md:grid-cols-2 md:gap-16">
            <div className="border-l-[3px] border-amber-500 bg-amber-50 px-[22px] py-5">
              <h3 className="text-lg font-bold text-oxblood">Not yet live</h3>
              <p className="mt-0.5 text-[13px] text-slate-600">As of {NOT_YET_LIVE_AS_OF}</p>
              <ul className="mt-3 grid list-disc gap-2 pl-[18px] text-[15px] text-slate-700">{NOT_YET_LIVE.map((item) => <li key={item}>{item}</li>)}</ul>
            </div>
            <div>
              <h3 className="text-lg font-bold text-oxblood">Is this for your kitchen?</h3>
              <ul className="mb-5 mt-3 grid list-disc gap-1.5 pl-[18px] text-[15px] text-slate-700">{FIT_CRITERIA.map((item) => <li key={item}>{item}</li>)}</ul>
              <div className="flex flex-wrap items-center gap-x-7 gap-y-3">
                <a href="/dashboard/onboarding" className={CTA_CLASS}>Start your application</a>
                <a href="/contact" className={INLINE_LINK_CLASS}>Contact us</a>
              </div>
              <p className="mt-3.5 text-sm text-slate-600">{OPERATOR_APPLICATION_REVIEW_LINE}</p>
            </div>
          </div>
        </div>
      </section>
    </div>
  )
}

function PriceCell({ label, figure, unit }: { label: string; figure: string; unit: string }) {
  return (
    <td
      data-label={label}
      className="align-top text-xl font-bold tabular-nums text-oxblood max-md:block max-md:py-0.5 max-md:text-xl max-md:before:mr-1 max-md:before:text-[13px] max-md:before:font-normal max-md:before:text-slate-600 max-md:before:content-[attr(data-label)] md:whitespace-nowrap md:border-b md:border-b-oxblood/15 md:py-4 md:pr-4"
    >
      {figure}
      {unit && <>{" "}<small className="text-sm font-normal text-slate-600">{unit}</small></>}
    </td>
  )
}
