"use client"

import { useMemo, useState } from "react"
import {
  ArrowDownRight,
  Check,
  Clipboard,
  Copy,
  ExternalLink,
  Printer,
  ShieldCheck,
  X,
} from "lucide-react"

type Confidence = "High" | "Working assumption" | "To validate"

type EvidenceItem = {
  title: string
  confidence: Confidence
  detail: string
}

const confidenceStyles: Record<Confidence, string> = {
  High: "bg-emerald-50 text-emerald-800",
  "Working assumption": "bg-amber-100 text-amber-800",
  "To validate": "bg-orange-50 text-orange-800",
}

const evidence: EvidenceItem[] = [
  {
    title: "The first cluster should be narrow, not pan-European",
    confidence: "High",
    detail:
      "Start UK-first with established owner-led Nigerian and West African takeaways and caterers in one serviceable cluster. Shared menus, allergen-information requests, events and repeat customers make a useful vertical wedge.",
  },
  {
    title: "Takeaway and catering have different buying moments",
    confidence: "Working assumption",
    detail:
      "Takeaways need a reliable daily order and kitchen flow; caterers need enquiries, deposits, date capacity and event handover. One account can support both, but neither journey should be flattened into the other.",
  },
  {
    title: "£39 core can be the uncomplicated entry point",
    confidence: "Working assumption",
    detail:
      "A monthly core price is legible to established operators. Test it alongside a 0.5% GTV option or £79–£119 fixed packages, with a £99 setup fee and a £149–£199 capped or fixed option for high-volume merchants.",
  },
  {
    title: "Assisted onboarding may beat a self-serve funnel",
    confidence: "To validate",
    detail:
      "Menus, opening hours, dietary information and customer migration take care. Validate whether the setup fee pays for a repeatable, bounded onboarding service rather than bespoke consulting.",
  },
]

const alternatives = [
  ["Assisted vertical SaaS", "Recommended", "Vendor retains the commercial relationship; J'Toye provides the operating system."],
  ["Marketplace", "Reject", "Would compete for the vendor’s customer relationship and pull the model towards commission dependency."],
  ["Owned delivery", "Reject", "Introduces fleet utilisation, insurance and service-quality exposure outside the operating-system thesis."],
  ["Merchant of record / pooled funds", "Reject", "Adds payment, safeguarding and settlement obligations that are not needed to create the initial value."],
  ["Full POS or super-app", "Reject", "Broadens the product before the recurring order, catering and kitchen workflows have earned expansion."],
] as const

const navItems = [
  ["the-decision", "Decision"],
  ["cohorts", "Two cohorts"],
  ["economics", "Economics"],
  ["boundaries", "Boundaries"],
  ["evidence", "Evidence"],
  ["gates", "90 days"],
  ["sources", "Sources"],
] as const

function money(value: number) {
  return new Intl.NumberFormat("en-GB", {
    style: "currency",
    currency: "GBP",
    maximumFractionDigits: 0,
  }).format(value)
}

export function BusinessModelGuide() {
  const [confidenceFilter, setConfidenceFilter] = useState<Confidence | "All">("All")
  const [gtv, setGtv] = useState(10000)
  const [feedback, setFeedback] = useState("")

  const economics = useMemo(() => {
    const revenue = 39 + 0.005 * gtv
    const costs = 9 + 0.001 * gtv
    const contribution = revenue - costs
    return { revenue, costs, contribution, margin: (contribution / revenue) * 100 }
  }, [gtv])

  const visibleEvidence = evidence.filter(
    (item) => confidenceFilter === "All" || item.confidence === confidenceFilter,
  )

  async function copyLink() {
    try {
      if (!navigator.clipboard) throw new Error("Clipboard is unavailable")
      await navigator.clipboard.writeText(window.location.href)
      setFeedback("Link copied")
    } catch {
      setFeedback("Copy is unavailable here — copy this page’s address from your browser.")
    }
  }

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 selection:bg-amber-300 selection:text-slate-900">
      <a
        href="#the-decision"
        className="sr-only z-50 rounded bg-slate-900 px-4 py-3 text-sm font-semibold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4"
      >
        Skip to guide
      </a>

      <header className="border-b-[3px] border-slate-900 bg-slate-900 text-slate-50 print:bg-white print:text-slate-900">
        <div className="mx-auto max-w-7xl px-5 py-5 sm:px-8">
          <div className="flex flex-wrap items-center justify-between gap-4 text-xs font-bold uppercase tracking-[0.18em]">
            <span className="flex items-center gap-2"><span className="h-2.5 w-2.5 bg-amber-300" /> J&apos;Toye OaaS / field guide</span>
            <span className="font-medium tracking-[0.12em] text-slate-300 print:text-slate-600">Research cutoff · 10 July 2026</span>
          </div>
          <div className="mt-10 grid gap-8 lg:grid-cols-[1.3fr_0.7fr] lg:items-end">
            <div>
              <p className="mb-4 font-mono text-xs uppercase tracking-[0.2em] text-amber-300">A decision, not a pitch</p>
              <h1 className="max-w-4xl text-4xl font-bold leading-[0.97] tracking-[-0.055em] sm:text-6xl lg:text-6xl">
                Build for the counter. <span className="text-amber-300">Not the marketplace.</span>
              </h1>
            </div>
            <p className="max-w-md border-l-2 border-amber-300 pl-5 text-base leading-7 text-slate-300">
              The operating-system model for established owner-led Nigerian and West African food businesses in one UK cluster.
            </p>
          </div>
          <div className="mt-10 grid grid-cols-3 border-t border-slate-700 text-center font-mono text-[10px] uppercase tracking-[0.12em] sm:text-xs">
            <div className="border-r border-slate-700 px-2 py-4">Daily takeaway</div>
            <div className="border-r border-slate-700 px-2 py-4">Event catering</div>
            <div className="px-2 py-4">Assisted SaaS</div>
          </div>
        </div>
      </header>

      <nav aria-label="Guide topics" className="sticky top-0 z-30 border-b border-slate-200 bg-slate-50/95 backdrop-blur print:static">
        <div className="mx-auto flex max-w-7xl items-center gap-2 overflow-x-auto px-5 py-3 sm:px-8">
          <span className="mr-2 shrink-0 font-mono text-[10px] font-bold uppercase tracking-[0.15em] text-slate-500">Read</span>
          {navItems.map(([id, label]) => (
            <a key={id} href={`#${id}`} className="shrink-0 rounded-full px-3 py-1.5 text-sm font-semibold text-slate-600 hover:bg-emerald-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500">
              {label}
            </a>
          ))}
          <div className="ml-auto hidden shrink-0 gap-2 sm:flex print:hidden">
            <a href="/for-operators" className="inline-flex items-center rounded-full border border-slate-700 px-3 py-1.5 text-sm font-semibold hover:bg-emerald-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500">Operator pitch</a>
            <button type="button" onClick={copyLink} className="inline-flex items-center gap-2 rounded-full border border-slate-700 px-3 py-1.5 text-sm font-semibold hover:bg-emerald-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500"><Copy size={15} /> Copy link</button>
            <a href="/business-model-guide.pdf" target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500"><Printer size={15} /> Print / save PDF</a>
          </div>
        </div>
      </nav>

      {feedback && <div aria-live="polite" className="fixed bottom-5 right-5 z-50 max-w-sm rounded bg-slate-900 px-4 py-3 text-sm font-semibold text-white shadow-xl print:hidden">{feedback}</div>}

      <div className="mx-auto max-w-7xl px-5 py-12 sm:px-8 sm:py-16">
        <section id="the-decision" aria-labelledby="decision-heading" className="scroll-mt-20">
          <SectionLabel number="01" label="The decision" />
          <div className="grid gap-8 lg:grid-cols-[0.8fr_1.2fr]">
            <h2 id="decision-heading" className="text-3xl font-bold leading-tight tracking-[-0.035em] sm:text-4xl">UK-first assisted vertical SaaS, deliberately narrow.</h2>
            <div className="space-y-6 text-lg leading-8 text-slate-600">
              <p>Sell a practical operating system to established, owner-led Nigerian and West African takeaways and caterers in one London cluster, where they already have recurring orders and meaningful volume. Keep the two cohorts&apos; evidence separate. The target model—not the current payment implementation—makes the vendor the <strong className="text-slate-900">seller, food business operator, merchant of record, payment recipient and fulfiller</strong>.</p>
              <div className="border-l-4 border-orange-500 bg-orange-50 p-5 text-base leading-7 text-orange-900"><strong>Commercial starting point:</strong> £39/month core. Test 0.5% GTV and £79–£119 fixed plans concurrently. Charge at least £99 once for setup, pass card fees through transparently, and test a £149–£199 monthly cap or fixed plan for high-volume merchants.</div>
            </div>
          </div>
        </section>

        <section id="cohorts" aria-labelledby="cohorts-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="02" label="Two jobs, one trusted operator" />
          <h2 id="cohorts-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">Do not make a caterer pretend to be a takeaway.</h2>
          <div className="mt-8 grid gap-5 md:grid-cols-2">
            <CohortCard title="Takeaway cohort" signal="The daily counter" problems={["Phone, WhatsApp and walk-in orders lose context", "Kitchen handover is informal at busy peaks", "Repeat customers need a direct channel, not another marketplace fee"]} capabilities={["Online ordering and direct customer records", "Live kitchen status and fulfilment handover", "Vendor-entered allergen information and trading-hour control"]} />
            <CohortCard title="Catering cohort" signal="The event ledger" problems={["Enquiries live across conversations and spreadsheets", "Deposits, headcounts and dietary requirements drift", "An event handover needs one source of truth"]} capabilities={["Validate structured enquiry and quote capture", "Validate deposits and event status tracking", "Test date, menu, guest and allergen handover"]} />
          </div>
        </section>

        <section id="economics" aria-labelledby="economics-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="03" label="Unit economics / transparent inputs" />
          <div className="grid gap-8 lg:grid-cols-[0.85fr_1.15fr] lg:items-start">
            <div>
              <h2 id="economics-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">A model that tells you what must become true.</h2>
              <p className="mt-5 max-w-md leading-7 text-slate-600">This is a planning calculator, not a forecast. It keeps the variable-price test honest: revenue = £39 + 0.5% of GTV; COGS = £9 + 0.1% of GTV.</p>
              <label htmlFor="gtv" className="mt-8 block font-mono text-xs font-bold uppercase tracking-[0.13em] text-slate-600">Monthly GTV: {money(gtv)}</label>
              <input id="gtv" aria-label="Monthly GTV" type="range" min="0" max="50000" step="1000" value={gtv} onChange={(event) => setGtv(Number(event.target.value))} className="mt-4 w-full accent-orange-600" />
              <div className="mt-3 flex justify-between font-mono text-xs text-slate-500"><span>£0</span><span>£50k</span></div>
            </div>
            <div className="overflow-hidden border-[3px] border-slate-900 bg-slate-50 shadow-[8px_8px_0_theme(colors.slate.200)]">
              <div className="flex items-center justify-between border-b border-slate-900 px-5 py-3 font-mono text-[10px] font-bold uppercase tracking-[0.14em]"><span>Monthly contribution ticket</span><Clipboard size={15} /></div>
              <dl className="grid grid-cols-2 divide-x divide-y divide-slate-200 sm:grid-cols-4 sm:divide-y-0">
                <Metric label="Revenue" value={money(economics.revenue)} />
                <Metric label="COGS" value={money(economics.costs)} />
                <Metric label="Contribution" value={money(economics.contribution)} emphasis />
                <Metric label="Contribution margin" value={`${economics.margin.toFixed(0)}%`} />
              </dl>
              <p className="border-t border-slate-200 px-5 py-3 text-xs leading-5 text-slate-500">The COGS formula is a planning assumption for allocated hosting, notifications and routine support. It excludes card processing, acquisition, tax, central engineering and exceptional support.</p>
            </div>
          </div>
        </section>

        <section id="boundaries" aria-labelledby="boundaries-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="04" label="Capability truth and boundary" />
          <div className="grid gap-8 lg:grid-cols-2">
            <div><h2 id="boundaries-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">Be unusually clear about what the product does not do.</h2><p className="mt-5 leading-7 text-slate-600">The credibility gain comes from giving operators a useful system without claiming an ecosystem. The first offer is SaaS and bounded assistance—not a substitute for legal, food-safety, payment or employment advice.</p></div>
            <div className="grid gap-3 sm:grid-cols-2">
              <BoundaryList title="We can support" icon={<Check className="text-emerald-700" />} items={["Direct ordering and kitchen coordination", "Catering workflows as a discovery track", "Vendor-controlled menus and customer data", "Configured onboarding within a clear cap"]} />
              <BoundaryList title="We reject" icon={<X className="text-orange-600" />} items={["Marketplace and commission dependency", "Owned delivery and courier fleet", "Merchant-of-record or pooled funds", "Full POS, free SaaS, bespoke bundles, pan-Europe and super-app scope"]} />
            </div>
          </div>
          <aside className="mt-8 flex gap-4 border border-emerald-200 bg-emerald-50 p-5 text-sm leading-6 text-emerald-900"><ShieldCheck className="mt-0.5 shrink-0" /><p><strong>Target boundary—not current payment capability:</strong> a documented connected-account charge flow must make the vendor the seller, merchant of record and payment recipient. The vendor sets prices, controls recipes and allergen accuracy, and fulfils directly or through its own courier; J&apos;Toye invoices software separately. The current platform-account flow must not receive or settle funds for multiple vendors until the architecture, contracts and specialist review are complete.</p></aside>
          <div className="mt-5 border border-emerald-200 bg-emerald-50 p-5 text-sm leading-6 text-emerald-900"><strong>Food-information and privacy boundary:</strong> distance sellers must provide allergen information before purchase and again on delivery; PPDS rules apply only to food prepacked for direct sale. Customer allergy requirements may reveal health information, so collection needs an appropriate Article 9 condition, clear notice, restricted access and short retention.</div>
          <div className="mt-5 border border-orange-200 bg-orange-50 p-5 text-sm leading-6 text-orange-900"><strong>Do not claim:</strong> production or enterprise readiness; guaranteed food-safety, Natasha&apos;s Law or GDPR compliance; HMRC accounting; payouts, wallets or escrow; offline KDS or ticket printing; general AI WhatsApp commerce; proven disaster recovery; self-service tenancy; or pan-European support.</div>
        </section>

        <section id="evidence" aria-labelledby="evidence-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="05" label="Evidence ledger" />
          <div className="flex flex-wrap items-end justify-between gap-5"><div><h2 id="evidence-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">What we know, what we infer, what we must earn.</h2><p className="mt-3 text-sm leading-6 text-slate-500">Confidence labels distinguish external constraints from commercial hypotheses. Filter them before making a claim.</p></div><div role="group" aria-label="Evidence confidence filter" className="flex flex-wrap gap-2 print:hidden">{(["All", "High", "Working assumption", "To validate"] as const).map((filter) => <button key={filter} type="button" aria-pressed={confidenceFilter === filter} onClick={() => setConfidenceFilter(filter)} className={`rounded-full border px-3 py-1.5 text-sm font-semibold focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500 ${confidenceFilter === filter ? "border-slate-900 bg-slate-900 text-white" : "border-slate-300 bg-slate-50 hover:bg-emerald-50"}`}>{filter}</button>)}</div></div>
          <div className="mt-8 grid gap-4 md:grid-cols-2">{visibleEvidence.map((item) => <article key={item.title} className="border border-slate-200 bg-slate-50 p-5"><span className={`inline-block rounded-full px-2.5 py-1 text-xs font-bold ${confidenceStyles[item.confidence]}`}>{item.confidence}</span><h3 className="mt-4 text-xl font-extrabold tracking-[-0.02em]">{item.title}</h3><p className="mt-3 text-sm leading-6 text-slate-500">{item.detail}</p></article>)}</div>
        </section>

        <section aria-labelledby="comparison-heading" className="mt-16">
          <h2 id="comparison-heading" className="sr-only">Alternative model comparison</h2>
          <div className="overflow-x-auto border-y border-slate-900"><table className="w-full min-w-[640px] text-left text-sm"><caption className="caption-top mb-4 text-left font-mono text-xs font-bold uppercase tracking-[0.13em] text-slate-600">Alternative model comparison</caption><thead className="border-b border-slate-900 font-mono text-[10px] uppercase tracking-[0.12em]"><tr><th className="p-3">Model</th><th className="p-3">Decision</th><th className="p-3">Why</th></tr></thead><tbody>{alternatives.map(([model, decision, reason]) => <tr key={model} className="border-b border-slate-200 last:border-b-0"><td className="p-3 font-bold">{model}</td><td className="p-3"><span className={decision === "Recommended" ? "font-bold text-emerald-700" : "font-bold text-orange-600"}>{decision}</span></td><td className="p-3 leading-6 text-slate-500">{reason}</td></tr>)}</tbody></table></div>
        </section>

        <section id="gates" aria-labelledby="gates-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="06" label="90-day validation board" />
          <h2 id="gates-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">Run a small test that can say no.</h2>
          <p className="mt-4 max-w-3xl leading-7 text-slate-600">Recruit 30–40 qualified prospects and accept 10–12 paid pilots split across takeaway and catering. Continue only on measured behaviour—not enthusiasm.</p>
          <div className="mt-8 grid gap-4 lg:grid-cols-3">
            <Gate number="Activation" title="At least 70% go live" gate="At least 70% of paid pilots go live (7/10, 8/11 or 9/12), then at least 70% of live pilots activate menu, the agreed payment arrangement and a first real order; median assisted onboarding is no more than four hours." stop="Stop if fewer than 5 of 30 qualified prospects pay or onboarding repeatedly becomes bespoke." />
            <Gate number="Retention" title="At least 80% stay paid" gate="Steady support stays below 30 minutes per merchant each month and merchants evidence saved time, fewer errors, recovered deposits, lower waste or retained direct orders." stop="Pivot if 90-day retention falls below 70%, support exceeds one hour, or use ends after setup and labels." />
            <Gate number="Economics" title="Contribution reaches £75+" gate="CAC has a credible route below £375 with five-month payback." stop="Reprice or stop if median direct sales remain below £8,000, merchants reject a higher fixed fee, or no economic outcome is demonstrated." />
          </div>
        </section>

        <section id="sources" aria-labelledby="sources-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
          <SectionLabel number="07" label="Sources and caveats" />
          <div className="grid gap-8 lg:grid-cols-[0.7fr_1.3fr]"><div><h2 id="sources-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">Read the boundary before quoting the guide.</h2><p className="mt-5 leading-7 text-slate-600">This guide is a dated strategic recommendation, not legal advice or market-size proof. Local operator interviews are intentionally a validation gate, not presented as completed evidence.</p></div><ul className="space-y-3 text-sm leading-6">{[
            ["ONS — Census 2021 ethnicity evidence", "https://www.ons.gov.uk/peoplepopulationandcommunity/culturalidentity/ethnicity/bulletins/ethnicgroupenglandandwales/census2021"],
            ["Food Standards Agency — allergen guidance", "https://www.food.gov.uk/business-guidance/allergen-guidance-for-food-businesses"],
            ["FCA — payment services and electronic money", "https://www.fca.org.uk/firms/payment-services-regulations-e-money-regulations"],
            ["UK Finance — Payment Markets 2025", "https://www.ukfinance.org.uk/system/files/2025-10/Payment%20Markets%20Report%20Summary.pdf"],
            ["ECB — SPACE 2024 payment study", "https://www.ecb.europa.eu/stats/ecb_surveys/space/html/ecb.space2024~19d46f0f17.en.html"],
            ["Jumia Food closure evidence", "https://techcrunch.com/2023/12/14/jumia-discontinues-food-delivery-across-seven-markets-shifts-focus-to-expanding-physical-goods-business/"],
          ].map(([label, href]) => <li key={href}><a className="group inline-flex items-start gap-2 font-semibold text-slate-700 underline decoration-amber-300 decoration-2 underline-offset-4 hover:text-orange-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500" href={href} target="_blank" rel="noreferrer">{label}<ExternalLink className="mt-1 shrink-0" size={14} /></a></li>)}</ul></div>
          <p className="mt-10 border-t border-dashed border-slate-300 pt-5 font-mono text-xs uppercase tracking-[0.11em] text-slate-500">Research cutoff: 10 July 2026 · Review before expanding geography, payment role or product scope.</p>
        </section>
      </div>

      <footer className="border-t-[3px] border-slate-900 bg-slate-900 px-5 py-7 text-slate-300 print:hidden"><div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 sm:px-3"><p className="font-mono text-xs uppercase tracking-[0.14em]">J&apos;Toye OaaS / decision guide</p><div className="flex gap-3"><button type="button" onClick={copyLink} className="inline-flex items-center gap-2 text-sm font-bold underline decoration-amber-300 decoration-2 underline-offset-4"><Copy size={15} /> Copy link</button><a href="/business-model-guide.pdf" target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 text-sm font-bold underline decoration-amber-300 decoration-2 underline-offset-4"><Printer size={15} /> Print / save PDF</a></div></div></footer>
    </div>
  )
}

function SectionLabel({ number, label }: { number: string; label: string }) {
  return <p className="mb-5 flex items-center gap-3 font-mono text-xs font-bold uppercase tracking-[0.15em] text-orange-600"><span>{number}</span><span className="h-px w-10 bg-orange-500" />{label}</p>
}

function CohortCard({ title, signal, problems, capabilities }: { title: string; signal: string; problems: string[]; capabilities: string[] }) {
  return <article className="border-t-[5px] border-emerald-600 bg-slate-50 p-6 shadow-[5px_5px_0_theme(colors.slate.200)]"><p className="font-mono text-xs font-bold uppercase tracking-[0.13em] text-slate-500">{signal}</p><h3 className="mt-2 text-2xl font-bold tracking-[-0.03em]">{title}</h3><div className="mt-6 grid gap-5 sm:grid-cols-2"><div><h4 className="font-mono text-xs font-bold uppercase tracking-[0.12em] text-orange-600">Problems to solve</h4><ul className="mt-3 space-y-2 text-sm leading-6 text-slate-500">{problems.map((item) => <li key={item} className="flex gap-2"><ArrowDownRight className="mt-1 shrink-0 text-orange-600" size={14} />{item}</li>)}</ul></div><div><h4 className="font-mono text-xs font-bold uppercase tracking-[0.12em] text-emerald-700">Pilot workflow</h4><ul className="mt-3 space-y-2 text-sm leading-6 text-slate-500">{capabilities.map((item) => <li key={item} className="flex gap-2"><Check className="mt-1 shrink-0 text-emerald-700" size={14} />{item}</li>)}</ul></div></div></article>
}

function Metric({ label, value, emphasis = false }: { label: string; value: string; emphasis?: boolean }) {
  return <div className="p-4"><dt className="font-mono text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">{label}</dt><dd className={`mt-2 text-2xl font-bold tracking-[-0.04em] ${emphasis ? "text-orange-600" : ""}`}>{value}</dd></div>
}

function BoundaryList({ title, icon, items }: { title: string; icon: React.ReactNode; items: string[] }) {
  return <article className="border border-slate-200 bg-slate-50 p-5"><h3 className="flex items-center gap-2 font-bold">{icon}{title}</h3><ul className="mt-4 space-y-3 text-sm leading-6 text-slate-500">{items.map((item) => <li key={item}>{item}</li>)}</ul></article>
}

function Gate({ number, title, gate, stop }: { number: string; title: string; gate: string; stop: string }) {
  return <article className="relative border border-slate-200 bg-slate-50 p-6"><p className="font-mono text-xs font-bold uppercase tracking-[0.13em] text-orange-600">{number}</p><h3 className="mt-3 text-xl font-bold tracking-[-0.02em]">{title}</h3><p className="mt-4 text-sm leading-6 text-slate-600"><strong>Gate:</strong> {gate}</p><p className="mt-4 border-t border-slate-200 pt-4 text-sm leading-6 text-orange-800"><strong>Stop criterion:</strong> {stop}</p></article>
}