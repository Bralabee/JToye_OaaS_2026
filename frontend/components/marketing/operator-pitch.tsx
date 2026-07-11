"use client"

import { useMemo, useState, type ReactNode } from "react"
import { ArrowDown, ArrowUpRight, Check, ClipboardCheck, Copy, Download, MessageCircle, Package, Store } from "lucide-react"

type BusinessShape = "Takeaway" | "Catering" | "Both"

const pilotSteps = [
  ["01", "Map your service", "Work through your menu, trading hours and the customer journey you already run."],
  ["02", "Set up the direct path", "Prepare your storefront and ordering flow together, rather than handing over a blank dashboard."],
  ["03", "Run a real service", "Use the browser kitchen display for online orders while you remain responsible for fulfilment."],
  ["04", "Review before expanding", "Look at what worked in the pilot and agree the next operating step together."],
] as const

function reducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
}

export function OperatorPitch() {
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
    <div className="min-h-screen bg-slate-50 text-slate-900 selection:bg-amber-300 selection:text-slate-900">
      <a href="#main-pitch" className="sr-only z-50 rounded-full bg-slate-900 px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to operator pitch</a>

      <header className="border-b-4 border-slate-900 bg-emerald-50">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-600">J&apos;Toye / operator pilot</p>
          <p className="text-right text-xs font-semibold text-slate-600">Built for the service you already run</p>
        </div>
      </header>

      <section id="main-pitch" className="overflow-hidden border-b-4 border-slate-900 bg-slate-900 text-slate-50">
        <div className="mx-auto grid max-w-7xl gap-10 px-5 py-14 sm:px-8 sm:py-20 lg:grid-cols-[1.2fr_0.8fr] lg:items-end">
          <div>
            <p className="mb-5 inline-flex rounded-full border border-slate-600 px-3 py-1.5 font-mono text-xs font-bold uppercase tracking-[0.14em] text-emerald-100">One London cluster · owner-led food businesses</p>
            <h1 className="max-w-4xl text-5xl font-bold leading-[0.93] tracking-[-0.055em] sm:text-6xl lg:text-6xl">Keep the order. <span className="text-amber-300">Keep the customer.</span> Keep the kitchen moving.</h1>
            <p className="mt-7 max-w-2xl text-lg leading-8 text-slate-300 sm:text-xl">An assisted pilot for established Nigerian and West African takeaway and catering operators with recurring order volume — built around your direct customer relationship, not a marketplace.</p>
            <div className="mt-9 flex flex-wrap gap-3">
              <a href="/dashboard/onboarding" className="inline-flex items-center gap-2 rounded-full bg-orange-500 px-6 py-3.5 text-base font-bold text-slate-900 transition hover:bg-amber-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300 motion-reduce:transition-none">Start your application <ArrowUpRight size={18} aria-hidden="true" /></a>
              <button type="button" onClick={revealFitCheck} className="inline-flex items-center gap-2 rounded-full bg-orange-500 px-6 py-3.5 text-base font-bold text-slate-900 transition hover:bg-amber-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300 motion-reduce:transition-none">Check your pilot fit <ArrowDown size={18} aria-hidden="true" /></button>
              <a href="/jtoye-operator-pilot-pack.pdf" download className="inline-flex items-center gap-2 rounded-full border-2 border-emerald-200 px-6 py-3 text-base font-bold text-slate-50 hover:bg-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-300">Download vendor pack <Download size={18} aria-hidden="true" /></a>
            </div>
          </div>
          <div className="border-2 border-emerald-200 bg-slate-800 p-5 shadow-[10px_10px_0_theme(colors.orange.500)] sm:p-7">
            <div className="flex items-center justify-between border-b border-slate-600 pb-4 font-mono text-xs font-bold uppercase tracking-[0.15em] text-emerald-100"><span>Service rail</span><span>Online orders</span></div>
            <div className="space-y-4 pt-5">
              <RailItem icon={<Store size={18} />} title="Your direct storefront" detail="A customer path you control." />
              <RailItem icon={<Package size={18} />} title="Your browser kitchen display" detail="For online orders while connected." />
              <RailItem icon={<ClipboardCheck size={18} />} title="Your service decisions" detail="You set the menu, hours and handover." />
            </div>
          </div>
        </div>
      </section>

      <div className="mx-auto max-w-7xl px-5 py-14 sm:px-8 sm:py-20">
        <section aria-labelledby="pressure-heading" className="grid gap-6 border-b-2 border-slate-200 pb-14 lg:grid-cols-[0.8fr_1.2fr]">
          <div><p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-500">The service leak</p><h2 id="pressure-heading" className="mt-4 text-4xl font-bold leading-none tracking-[-0.045em] sm:text-5xl">One change. Three channels. Two versions of the order.</h2></div>
          <div className="grid gap-3 sm:grid-cols-3"><Impact title="The counter checks messages" detail="Changes sit beside the order instead of becoming the order." /><Impact title="The kitchen works from memory" detail="Availability, portions and handover details become verbal." /><Impact title="The owner carries the risk" detail="The customer still expects one accurate promise." /></div>
          <p className="lg:col-start-2 text-base font-bold leading-7 text-slate-700">J&apos;Toye&apos;s pilot tests one practical result: can a direct order move from customer agreement to kitchen handover with fewer places to check?</p>
        </section>

        <section aria-labelledby="paths-heading">
          <p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-500">Two jobs. Different pressure.</p>
          <div className="mt-4 flex flex-wrap items-end justify-between gap-5"><h2 id="paths-heading" className="max-w-3xl text-3xl font-bold leading-tight tracking-[-0.04em] sm:text-5xl">Choose the part of service you need to steady first.</h2><p className="max-w-sm text-base leading-7 text-slate-500">One pilot can learn from both paths, but they should not be sold as the same job.</p></div>
          <div className="mt-9 grid gap-5 md:grid-cols-2">
            <CohortCard icon={<Store size={28} />} label="DAILY SERVICE" title="Takeaway: protect the regular order" tone="white"><p>Give repeat customers a direct way to order, and give the counter a clearer handover when the kitchen is busy.</p><ul className="mt-6 space-y-3 text-sm leading-6"><li><CheckItem>Direct storefront, menus and trading hours</CheckItem></li><li><CheckItem>Online-order visibility in the browser kitchen display</CheckItem></li><li><CheckItem>Vendor-controlled customer and menu information</CheckItem></li></ul></CohortCard>
            <CohortCard icon={<MessageCircle size={28} />} label="EVENT SERVICE" title="Catering: make the handover legible" tone="green"><p>Explore how event details, dietary notes and a final handover could be kept clearer for the people doing the work.</p><p className="mt-6 border-l-4 border-orange-500 pl-4 text-sm font-semibold leading-6 text-slate-700">Catering and WhatsApp are validation tracks. They are not current, guaranteed production workflows.</p></CohortCard>
          </div>
        </section>

        <section aria-labelledby="pilot-heading" className="mt-20 border-t-2 border-slate-200 pt-12">
          <div className="grid gap-8 lg:grid-cols-[0.7fr_1.3fr]"><div><p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-500">Assisted, not abstract</p><h2 id="pilot-heading" className="mt-4 text-3xl font-bold tracking-[-0.04em] sm:text-4xl">A four-step pilot that respects the shift.</h2></div><ol className="grid gap-3 sm:grid-cols-2">{pilotSteps.map(([number, title, detail]) => <li key={number} className="border border-slate-300 bg-white p-5"><span className="font-mono text-xs font-bold text-orange-500">{number}</span><h3 className="mt-5 text-lg font-bold">{title}</h3><p className="mt-2 text-sm leading-6 text-slate-500">{detail}</p></li>)}</ol></div>
        </section>

        <section aria-labelledby="terms-heading" className="mt-20 border-2 border-slate-900 bg-slate-900 p-6 text-slate-50 sm:p-9">
          <div className="border-b border-slate-700 pb-5"><p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-emerald-100">Pilot terms, plainly stated</p><h2 id="terms-heading" className="mt-2 text-3xl font-bold tracking-[-0.04em]">No mystery maths at the counter.</h2></div>
          <div className="mt-7 grid gap-5 sm:grid-cols-2 lg:grid-cols-4"><Terms title="Core" detail="£39 per location, per month" /><Terms title="Assisted setup" detail="From £99, scoped to the work agreed" /><Terms title="Pricing test" detail="0.5% of direct sales or £79–£119 fixed" /><Terms title="High volume" detail="£149–£199 option, agreed for higher-volume operations" /></div>
          <p className="mt-6 border-t border-slate-700 pt-5 text-sm leading-6 text-slate-300">Card fees are shown transparently. Payment arrangements must be confirmed before taking payments; the current platform is not production connected-account settlement.</p>
        </section>

        <section aria-labelledby="boundaries-heading" className="mt-20 border-t-2 border-slate-200 pt-12">
          <p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-500">A responsible pilot is clear about the edges</p><h2 id="boundaries-heading" className="mt-4 text-3xl font-bold tracking-[-0.04em] sm:text-4xl">Useful help does not remove operator responsibility.</h2>
          <div className="mt-8 grid gap-4 md:grid-cols-2"><Boundary title="What the pilot can support" items={["A direct storefront does not generate demand; you remain responsible for bringing customers to it.", "The browser kitchen display works for online orders while it is connected; it is not an offline KDS.", "You retain responsibility for your menu, prices, fulfilment and customer relationship."]} /><Boundary accent title="What needs your judgement" items={["Allergen information is vendor-entered assistance, not a compliance service or legal advice.", "Catering and WhatsApp are validation tracks, not promised production capabilities.", "Payment arrangements must be confirmed; this is not production connected-account settlement."]} /></div>
        </section>

        <section id="fit-check" aria-labelledby="fit-check-heading" className="mt-20 scroll-mt-6 border-2 border-slate-900 bg-emerald-50 p-6 sm:p-9">
          <div className="flex flex-wrap items-start justify-between gap-5"><div><p className="font-mono text-xs font-bold uppercase tracking-[0.16em] text-slate-500">No form. No lead capture.</p><h2 id="fit-check-heading" className="mt-3 text-3xl font-bold tracking-[-0.04em] sm:text-4xl">Check your pilot fit.</h2><p className="mt-3 max-w-2xl leading-7 text-slate-600">Make a short local summary for your own discussion. Nothing is sent anywhere.</p></div>{!fitCheckOpen && <button type="button" onClick={revealFitCheck} className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-5 py-3 font-bold text-white hover:bg-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-slate-900">Open fit check <ArrowDown size={17} aria-hidden="true" /></button>}</div>
          {fitCheckOpen && <div className="mt-8 grid gap-8 border-t-2 border-emerald-200 pt-7 lg:grid-cols-[1fr_0.9fr]"><div className="grid gap-5 sm:grid-cols-2"><FitSelect label="Your main service" value={businessShape} options={["Takeaway", "Catering", "Both"]} onChange={(value) => setBusinessShape(value as BusinessShape)} /><FitSelect label="Your operating area" value={location} options={["Yes, in one London cluster", "Not in one London cluster yet"]} onChange={setLocation} /><FitSelect label="Your order pattern" value={orders} options={["Yes, recurring orders", "Still building recurring orders"]} onChange={setOrders} /><FitSelect label="What you want to steady first" value={priority} options={["Keep daily takeaway orders moving", "Make catering handover clearer", "Understand both paths"]} onChange={setPriority} /></div><div className="border-2 border-slate-900 bg-white p-5"><label htmlFor="fit-summary" className="font-mono text-xs font-bold uppercase tracking-[0.13em] text-slate-500">Your copyable fit summary</label><textarea id="fit-summary" readOnly value={summary} rows={7} className="mt-3 w-full resize-none border border-slate-300 bg-slate-50 p-3 text-sm leading-6 text-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500" /><button type="button" onClick={copySummary} className="mt-4 inline-flex items-center gap-2 rounded-full bg-orange-500 px-4 py-2.5 text-sm font-bold text-slate-900 hover:bg-amber-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-900"><Copy size={16} aria-hidden="true" /> Copy fit summary</button>{copyFeedback && <p role="status" className="mt-3 text-sm font-semibold text-slate-700">{copyFeedback}</p>}<a href="/jtoye-operator-pilot-pack.pdf" download className="mt-4 inline-flex items-center gap-2 text-sm font-bold text-slate-900 underline decoration-orange-500 decoration-2 underline-offset-4"><Download size={16} aria-hidden="true" /> Download the pack to share this conversation</a></div></div>}
          <div className="mt-8 border-t-2 border-emerald-200 pt-6"><p className="text-lg font-bold">If this sounds like your service, return this pack to the person who shared it and ask for a pilot-fit conversation.</p><p className="mt-2 text-sm leading-6 text-slate-600">Bring your current order channels, a typical busy-service example, and the one handover problem you most want to remove. No acceptance or payment is implied by the conversation.</p></div>
        </section>
        <footer className="mt-8 flex flex-wrap justify-between gap-3 border-t border-slate-300 pt-5 font-mono text-xs font-bold uppercase tracking-[0.12em] text-slate-500"><span>J&apos;Toye operator pilot pack</span><span>Edition 10 July 2026 · UK pilot hypothesis</span></footer>
      </div>
    </div>
  )
}

function RailItem({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) {
  return <div className="flex gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-50 text-slate-900">{icon}</span><div><p className="font-bold">{title}</p><p className="text-sm text-slate-300">{detail}</p></div></div>
}

function Impact({ title, detail }: { title: string; detail: string }) {
  return <article className="border-l-4 border-orange-500 bg-white p-4"><h3 className="text-base font-bold">{title}</h3><p className="mt-2 text-sm leading-6 text-slate-500">{detail}</p></article>
}

function CohortCard({ icon, label, title, tone, children }: { icon: ReactNode; label: string; title: string; tone: "white" | "green"; children: ReactNode }) {
  return <article className={`border-2 border-slate-900 p-6 sm:p-8 ${tone === "white" ? "bg-white" : "bg-emerald-50"}`}><div className="flex items-center justify-between"><span className="text-orange-500">{icon}</span><span className="font-mono text-xs font-bold text-slate-500">{label}</span></div><h3 className="mt-9 text-2xl font-bold tracking-[-0.03em]">{title}</h3><div className="mt-4 leading-7 text-slate-600">{children}</div></article>
}

function CheckItem({ children }: { children: ReactNode }) {
  return <span className="flex gap-3"><Check className="mt-0.5 shrink-0 text-emerald-600" size={17} aria-hidden="true" />{children}</span>
}

function Terms({ title, detail }: { title: string; detail: string }) {
  return <div><p className="font-mono text-xs font-bold uppercase tracking-[0.15em] text-emerald-100">{title}</p><p className="mt-2 text-lg font-bold leading-6">{detail}</p></div>
}

function Boundary({ title, items, accent = false }: { title: string; items: string[]; accent?: boolean }) {
  return <article className={`border p-6 ${accent ? "border-orange-500 bg-orange-50" : "border-slate-300 bg-white"}`}><h3 className="text-lg font-bold">{title}</h3><ul className="mt-5 space-y-4 text-sm leading-6 text-slate-600">{items.map((item) => <li key={item} className="flex gap-3"><ArrowUpRight className="mt-0.5 shrink-0 text-orange-500" size={17} aria-hidden="true" />{item}</li>)}</ul></article>
}

function FitSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  const id = label.toLowerCase().replaceAll(" ", "-")
  return <label htmlFor={id} className="block text-sm font-bold text-slate-800">{label}<select id={id} value={value} onChange={(event) => onChange(event.target.value)} className="mt-2 block w-full border-2 border-slate-900 bg-white px-3 py-3 text-sm font-medium text-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500">{options.map((option) => <option key={option}>{option}</option>)}</select></label>
}