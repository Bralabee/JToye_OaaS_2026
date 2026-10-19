"use client"

import { useMemo, useRef, useState, type ReactNode } from "react"
import { ArrowDown, ArrowUpRight, Check, ClipboardCheck, Copy, MessageCircle, Package, Store } from "lucide-react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { useOperatorEntranceScene } from "@/components/marketing/operator-entrance-scene"

type BusinessShape = "Takeaway" | "Catering" | "Both"

const pilotSteps = [
  ["01", "Map your service", "Work through your menu, trading hours and the customer journey you already run."],
  ["02", "Set up the direct path", "Prepare your storefront and ordering flow together, rather than handing over a blank dashboard."],
  ["03", "Run a real service", "Take live and scheduled orders through the kitchen display and the cook sheet, so the team works from one list instead of messages."],
  ["04", "Review before expanding", "Look at what worked in the pilot and agree the next operating step together."],
] as const

/**
 * Every figure the page prints about price lives HERE, so a later owner ruling
 * is a one-line change. Source of truth is the billing code, not the guides:
 * The three RECURRING tiers (Core, Flat, Batch starter) mirror BillingPlanMode
 * as StripeBillingService bills them (javadoc + V88 migration header).
 * WALK_SETUP_STEPDOWN is internal and deliberately not printed. The assisted
 * setup line is a ONE-OFF fee from ADR-0007 axis 4, invoiced outside
 * StripeBillingService; it is not a billing tier. Do not add a recurring tier
 * the code does not bill.
 */
const PRICING = {
  terms: [
    ["Core", "£39 per location per month + 0.5% of direct sales"],
    ["Flat", "£79–£149 per location per month, 0% platform fee"],
    ["Batch starter", "£0 per month + 6% of direct sales"],
    ["Assisted setup", "From £99, scoped to the work agreed"],
  ],
  footnote:
    "Card processing fees are charged by Stripe on your own Stripe account — you are the merchant of record. Live card settlement switches on with the first production operator.",
} as const

function reducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
}

/**
 * Operator pitch (Surface C).
 *
 * Palette + type are the LANDING brand (oxblood anchor, cream ground, amber
 * appetite accent, gold eyebrow-on-dark, Work Sans throughout, soft rounded
 * cards). It previously ran a bespoke navy/emerald/mono brutalist skin, so a
 * visitor crossing from `/` landed on what read as a different product.
 *
 * Motion is on-load, not on-scroll — see useOperatorEntranceScene. No pinned
 * sections and no horizontally scrolled pilot rail: the page arrives populated.
 */
export function OperatorPitch() {
  const rootRef = useRef<HTMLDivElement>(null)
  useOperatorEntranceScene(rootRef)
  const [fitCheckOpen, setFitCheckOpen] = useState(false)
  const [businessShape, setBusinessShape] = useState<BusinessShape>("Takeaway")
  const [location, setLocation] = useState("Yes, in one London cluster")
  const [orders, setOrders] = useState("Yes, recurring orders")
  const [priority, setPriority] = useState("Keep daily takeaway orders moving")
  const [copyFeedback, setCopyFeedback] = useState("")

  const summary = useMemo(() => [
    "J'Toye pilot fit summary",
    `Business: ${businessShape}`,
    `Location: ${location}`,
    `Order pattern: ${orders}`,
    `Priority: ${priority}`,
    "This is a local planning summary, not a submission or a promise of acceptance.",
  ].join("\n"), [businessShape, location, orders, priority])

  function revealFitCheck() {
    setFitCheckOpen(true)
    document.getElementById("fit-check")?.scrollIntoView({
      behavior: reducedMotion() ? "auto" : "smooth",
      block: "start",
    })
  }

  async function copySummary() {
    try {
      if (!navigator.clipboard) throw new Error("Clipboard unavailable")
      await navigator.clipboard.writeText(summary)
      setCopyFeedback("Fit summary copied")
    } catch {
      setCopyFeedback("Copy is unavailable here — select and copy the summary instead.")
    }
  }

  return (
    <div ref={rootRef} className="min-h-screen bg-cream text-slate-900 selection:bg-amber-300 selection:text-oxblood">
      <a href="#main-pitch" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to operator pitch</a>

      <div className="border-b border-cream-100 bg-cream">
        {/* PHASE 35 / UIX-07 — the DECLARED Marketing width tier, applied IN PLACE.
            This band's rendered width does not change: it is deliberately equal to
            the shared public header and footer rails, so the chrome and the content
            it frames stay aligned. What changes is that the width is now DECLARED
            rather than inherited — it was previously the stock scale token that
            happens to equal the same number, i.e. correct by coincidence, which is
            the same class of problem as the unaccounted-for dashboard width this
            phase exists to remove. The class name it replaces is deliberately not
            spelled out again; a comment satisfies a grep as readily as code does.

            The tier class is never written as a literal here — plan 35-10's gate
            reads the property that it occurs in exactly one module. The three
            band sites in this file all carry the declaration, and the COUNT is
            asserted in __tests__/operator-pitch.test.tsx, because a band left on
            the old token renders identically today and only diverges once the
            tier's value moves.

            The headline and paragraph measures below are typographic clamps, not
            page bands. They are orthogonal to the tier and stay untouched. */}
        <div
          data-width-tier="marketing"
          className={`mx-auto flex ${WIDTH_TIER_CLASS.marketing} items-center justify-between gap-4 px-5 py-4 sm:px-8`}
        >
          <p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">J&apos;Toye / operator pilot</p>
          <p className="text-right text-xs font-semibold text-slate-600">Built for the service you already run</p>
        </div>
      </div>

      <section id="main-pitch" className="relative overflow-hidden bg-gradient-to-br from-oxblood via-oxblood to-oxblood-700 text-cream">
        <div
          data-width-tier="marketing"
          className={`relative mx-auto grid ${WIDTH_TIER_CLASS.marketing} gap-10 px-5 py-14 sm:px-8 sm:py-20 lg:grid-cols-[1.2fr_0.8fr] lg:items-end`}
        >
          <div>
            <p className="mb-5 inline-flex rounded-full border border-white/25 px-3 py-1.5 text-xs font-bold uppercase tracking-[0.14em] text-gold">One London cluster · owner-led food businesses</p>
            <h1 data-op-headline className="max-w-4xl text-4xl font-bold leading-[0.98] tracking-[-0.05em] sm:text-5xl sm:leading-[0.93] lg:text-6xl">Keep the order. <span className="text-amber-300">Keep the customer.</span> Keep the kitchen moving.</h1>
            <p className="mt-7 max-w-2xl text-lg leading-8 text-cream/80 sm:text-xl">For established Nigerian and West African takeaway and catering operators with recurring order volume. Run the daily counter takeaway and the weekend batch and catering run from one kitchen, one menu and one ledger — built around your direct customer relationship, not a marketplace.</p>
            <div className="mt-9 flex flex-wrap gap-3">
              <a href="/dashboard/onboarding" className="inline-flex items-center gap-2 rounded-full bg-amber-500 px-6 py-3.5 text-base font-bold text-oxblood transition hover:-translate-y-0.5 hover:bg-amber-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300 motion-reduce:transition-none motion-reduce:hover:translate-y-0">Start your application <ArrowUpRight size={18} aria-hidden="true" /></a>
              <button type="button" onClick={revealFitCheck} className="inline-flex items-center gap-2 rounded-full border-2 border-gold/50 px-6 py-3 text-base font-bold text-cream transition hover:bg-white/10 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300">Check your pilot fit <ArrowDown size={18} aria-hidden="true" /></button>
            </div>
          </div>
          <div className="rounded-2xl border border-gold/30 bg-oxblood-700 p-5 shadow-xl sm:p-7">
            <div className="flex items-center justify-between border-b border-white/15 pb-4 text-xs font-bold uppercase tracking-[0.15em] text-gold"><span>Service rail</span><span>One kitchen</span></div>
            <div className="space-y-4 pt-5">
              <RailItem icon={<Store size={18} />} title="Direct storefront and QR ordering" detail="A customer path you control, for daily orders and scheduled pre-orders." />
              <RailItem icon={<Package size={18} />} title="Kitchen display with cook sheet" detail="A live order lane for the counter; one consolidated tally for batch days." />
              <RailItem icon={<ClipboardCheck size={18} />} title="Allergen and label engine" detail="14-allergen acknowledgement at checkout and PPDS labels." />
            </div>
          </div>
        </div>
      </section>

      <div
        data-width-tier="marketing"
        className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-5 py-14 sm:px-8 sm:py-20`}
      >
        <section aria-labelledby="pressure-heading" className="grid gap-6 border-b border-cream-100 pb-14 lg:grid-cols-[0.8fr_1.2fr]">
          <div><p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">The service leak</p><h2 id="pressure-heading" className="mt-4 text-4xl font-bold leading-none tracking-[-0.045em] text-oxblood sm:text-5xl">One change. Three channels. Two versions of the order.</h2></div>
          <div className="grid gap-3 sm:grid-cols-3"><Impact title="The counter checks messages" detail="Changes sit beside the order instead of becoming the order." /><Impact title="The kitchen works from memory" detail="Availability, portions and handover details become verbal." /><Impact title="The owner carries the risk" detail="The customer still expects one accurate promise." /></div>
          <p className="lg:col-start-2 text-base font-bold leading-7 text-slate-700">J&apos;Toye&apos;s pilot tests one practical result: can a direct order move from customer agreement to kitchen handover with fewer places to check? An established operator paying 28% aggregator commission on £10k a month of orders hands over £2,800 a month; the same orders through a direct storefront cost £39 + 0.5%, plus Stripe&apos;s card fees, which you would pay either way.</p>
        </section>

        <section aria-labelledby="paths-heading" className="pt-14">
          <p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">Two jobs. Different pressure.</p>
          <div className="mt-4 flex flex-wrap items-end justify-between gap-5"><h2 id="paths-heading" className="max-w-3xl text-3xl font-bold leading-tight tracking-[-0.04em] text-oxblood sm:text-5xl">Choose the part of service you need to steady first.</h2><p className="max-w-sm text-base leading-7 text-slate-600">One kitchen, one menu and one ledger run both: the daily counter and the weekend batch.</p></div>
          <div className="mt-9 grid gap-5 md:grid-cols-2">
            <CohortCard icon={<Store size={28} />} label="Daily service" title="Takeaway: protect the regular order" tone="white"><p>Give repeat customers a direct way to order, and give the counter a clearer handover when the kitchen is busy.</p><ul className="mt-6 space-y-3 text-sm leading-6"><li><CheckItem>Direct storefront</CheckItem></li><li><CheckItem>QR and online ordering</CheckItem></li><li><CheckItem>Structured opening hours and a one-tap kitchen pause</CheckItem></li><li><CheckItem>Real-time kitchen display</CheckItem></li><li><CheckItem>Cash-order abuse caps</CheckItem></li></ul></CohortCard>
            <CohortCard icon={<MessageCircle size={28} />} label="Batch and catering" title="Catering: plan the weekend batch" tone="cream"><p>Take pre-orders for a set fulfilment slot, secure them with a deposit, and give the kitchen one consolidated list to cook from.</p><ul className="mt-6 space-y-3 text-sm leading-6"><li><CheckItem>Scheduled pre-order slots with cut-offs and capacity</CheckItem></li><li><CheckItem>Deposit and balance payment links</CheckItem></li><li><CheckItem>Consolidated cook sheet</CheckItem></li><li><CheckItem>PPDS labels</CheckItem></li></ul><p className="mt-6 border-l-4 border-amber-500 pl-4 text-sm font-semibold leading-6 text-slate-700">Scheduled orders, deposits and cook sheets are built and tested; card settlement runs in Stripe test mode until the first production operator goes live.</p></CohortCard>
          </div>
        </section>

        <section aria-labelledby="pilot-heading" className="mt-20 border-t border-cream-100 pt-12">
          <div className="grid gap-8 lg:grid-cols-[0.7fr_1.3fr]"><div><p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">Assisted, not abstract</p><h2 id="pilot-heading" className="mt-4 text-3xl font-bold tracking-[-0.04em] text-oxblood sm:text-4xl">A four-step pilot that respects the shift.</h2></div><ol data-pilot-track className="grid gap-4 sm:grid-cols-2">{pilotSteps.map(([number, title, detail]) => <li key={number} data-pilot-step className="rounded-2xl border border-cream-100 bg-white p-5 shadow-sm"><span className="text-xs font-bold tracking-[0.14em] text-amber-700">{number}</span><h3 className="mt-5 text-lg font-bold text-oxblood">{title}</h3><p className="mt-2 text-sm leading-6 text-slate-600">{detail}</p></li>)}</ol></div>
        </section>

        <section aria-labelledby="terms-heading" data-op-terms className="mt-20 rounded-2xl bg-oxblood p-6 text-cream shadow-xl sm:p-9">
          <div className="border-b border-white/15 pb-5"><p className="text-xs font-bold uppercase tracking-[0.16em] text-gold">Pilot terms, plainly stated</p><h2 id="terms-heading" className="mt-2 text-3xl font-bold tracking-[-0.04em]">No mystery maths at the counter.</h2></div>
          <div className="mt-7 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">{PRICING.terms.map(([title, detail]) => <Terms key={title} title={title} detail={detail} />)}</div>
          <p className="mt-6 border-t border-white/15 pt-5 text-sm leading-6 text-cream/75">{PRICING.footnote}</p>
        </section>

        <section aria-labelledby="boundaries-heading" className="mt-20 border-t border-cream-100 pt-12">
          <p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">A responsible pilot is clear about the edges</p><h2 id="boundaries-heading" className="mt-4 text-3xl font-bold tracking-[-0.04em] text-oxblood sm:text-4xl">Useful help does not remove operator responsibility.</h2>
          <div className="mt-8 grid gap-4 md:grid-cols-2"><Boundary title="What is built in" items={["The allergen engine: 14-allergen acknowledgement at checkout, “may contain” lines, Natasha's Law PPDS labels, trader disclosures and GDPR data requests.", "Audited order voiding, with a VAT ledger that reverses alongside it.", "Per-shop staff roles, so each person sees only the shops they have been granted."]} /><Boundary accent title="What stays with you" items={["You remain the Food Business Operator: allergen accuracy and cross-contamination in your kitchen are yours.", "A direct storefront does not generate demand; you remain responsible for bringing customers to it.", "The browser kitchen display is online-only; it is not an offline KDS.", "WhatsApp is a generated pre-order link you share, not an automated WhatsApp channel.", "Live card settlement waits for the production cutover."]} /></div>
        </section>

        <section id="fit-check" aria-labelledby="fit-check-heading" className="mt-20 scroll-mt-6 rounded-2xl border border-cream-100 bg-white p-6 shadow-sm sm:p-9">
          <div className="flex flex-wrap items-start justify-between gap-5"><div><p className="text-xs font-bold uppercase tracking-[0.16em] text-oxblood-600">No form. No lead capture.</p><h2 id="fit-check-heading" className="mt-3 text-3xl font-bold tracking-[-0.04em] text-oxblood sm:text-4xl">Check your pilot fit.</h2><p className="mt-3 max-w-2xl leading-7 text-slate-600">Make a short local summary for your own discussion. Nothing is sent anywhere.</p></div>{!fitCheckOpen && <button type="button" onClick={revealFitCheck} className="inline-flex items-center gap-2 rounded-full bg-oxblood px-5 py-3 font-bold text-white transition hover:bg-oxblood-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-oxblood">Open fit check <ArrowDown size={17} aria-hidden="true" /></button>}</div>
          {fitCheckOpen && <div className="mt-8 grid gap-8 border-t border-cream-100 pt-7 lg:grid-cols-[1fr_0.9fr]"><div className="grid gap-5 sm:grid-cols-2"><FitSelect label="Your main service" value={businessShape} options={["Takeaway", "Catering", "Both"]} onChange={(value) => setBusinessShape(value as BusinessShape)} /><FitSelect label="Your operating area" value={location} options={["Yes, in one London cluster", "Not in one London cluster yet"]} onChange={setLocation} /><FitSelect label="Your order pattern" value={orders} options={["Yes, recurring orders", "Still building recurring orders"]} onChange={setOrders} /><FitSelect label="What you want to steady first" value={priority} options={["Keep daily takeaway orders moving", "Make catering handover clearer", "Understand both paths"]} onChange={setPriority} /></div><div className="rounded-2xl border border-cream-100 bg-cream p-5"><label htmlFor="fit-summary" className="text-xs font-bold uppercase tracking-[0.13em] text-oxblood-600">Your copyable fit summary</label><textarea id="fit-summary" readOnly value={summary} rows={7} className="mt-3 w-full resize-none rounded-xl border border-cream-100 bg-white p-3 text-sm leading-6 text-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500" /><button type="button" onClick={copySummary} className="mt-4 inline-flex items-center gap-2 rounded-full bg-amber-500 px-4 py-2.5 text-sm font-bold text-oxblood transition hover:bg-amber-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-oxblood"><Copy size={16} aria-hidden="true" /> Copy fit summary</button>{copyFeedback && <p role="status" className="mt-3 text-sm font-semibold text-slate-700">{copyFeedback}</p>}</div></div>}
          <div className="mt-8 border-t border-cream-100 pt-6"><p className="text-lg font-bold text-oxblood">If this sounds like your service, <a href="/dashboard/onboarding" className="underline decoration-amber-500 decoration-2 underline-offset-4 hover:text-oxblood-700">Start your application</a>.</p><p className="mt-2 text-sm leading-6 text-slate-600">A person reviews every application, and nothing is charged until a plan is agreed.</p></div>
        </section>
        <div className="mt-8 flex flex-wrap justify-between gap-3 border-t border-cream-100 pt-5 text-xs font-bold uppercase tracking-[0.12em] text-slate-600"><span>J&apos;Toye operator pilot</span><span>Updated 19 October 2026 · figures from the billing code and the October 2026 positioning guide</span></div>
      </div>
    </div>
  )
}

function RailItem({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) {
  return <div data-rail-item className="flex gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-cream text-oxblood">{icon}</span><div><p className="font-bold">{title}</p><p className="text-sm text-cream/70">{detail}</p></div></div>
}

function Impact({ title, detail }: { title: string; detail: string }) {
  return <article className="rounded-xl border-l-4 border-amber-500 bg-white p-4 shadow-sm"><h3 className="text-base font-bold text-oxblood">{title}</h3><p className="mt-2 text-sm leading-6 text-slate-600">{detail}</p></article>
}

function CohortCard({ icon, label, title, tone, children }: { icon: ReactNode; label: string; title: string; tone: "white" | "cream"; children: ReactNode }) {
  return <article className={`rounded-2xl border border-cream-100 p-6 shadow-sm sm:p-8 ${tone === "white" ? "bg-white" : "bg-cream"}`}><div className="flex items-center justify-between"><span className="text-amber-700">{icon}</span><span className="text-xs font-bold uppercase tracking-[0.14em] text-oxblood-600">{label}</span></div><h3 className="mt-9 text-2xl font-bold tracking-[-0.03em] text-oxblood">{title}</h3><div className="mt-4 leading-7 text-slate-600">{children}</div></article>
}

function CheckItem({ children }: { children: ReactNode }) {
  return <span className="flex gap-3"><Check className="mt-0.5 shrink-0 text-emerald-600" size={17} aria-hidden="true" />{children}</span>
}

function Terms({ title, detail }: { title: string; detail: string }) {
  return <div><p className="text-xs font-bold uppercase tracking-[0.15em] text-gold">{title}</p><p className="mt-2 text-lg font-bold leading-6">{renderCountable(detail)}</p></div>
}

// Wrap the FIRST integer of a terms value in a count-up hook WITHOUT altering
// the visible copy (textContent round-trips byte-for-byte). The span keeps the
// real number as its text, so with the gate off (mobile / reduced-motion /
// no-JS) the correct value shows; the desktop scene resets it to 0 and counts
// up when the band enters view. If there is no integer, the string renders
// unchanged.
function renderCountable(detail: string): ReactNode {
  const match = detail.match(/\d+/)
  if (!match) return detail
  const number = match[0]
  const start = match.index ?? 0
  return (
    <>
      {detail.slice(0, start)}
      <span data-count-to={number}>{number}</span>
      {detail.slice(start + number.length)}
    </>
  )
}

function Boundary({ title, items, accent = false }: { title: string; items: string[]; accent?: boolean }) {
  return <article className={`rounded-2xl border p-6 shadow-sm ${accent ? "border-amber-300 bg-amber-50" : "border-cream-100 bg-white"}`}><h3 className="text-lg font-bold text-oxblood">{title}</h3><ul className="mt-5 space-y-4 text-sm leading-6 text-slate-600">{items.map((item) => <li key={item} className="flex gap-3"><ArrowUpRight className="mt-0.5 shrink-0 text-amber-700" size={17} aria-hidden="true" />{item}</li>)}</ul></article>
}

function FitSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  const id = label.toLowerCase().replaceAll(" ", "-")
  return <label htmlFor={id} className="block text-sm font-bold text-slate-800">{label}<select id={id} value={value} onChange={(event) => onChange(event.target.value)} className="mt-2 block w-full rounded-xl border border-cream-100 bg-white px-3 py-3 text-sm font-medium text-slate-800 shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500">{options.map((option) => <option key={option}>{option}</option>)}</select></label>
}
