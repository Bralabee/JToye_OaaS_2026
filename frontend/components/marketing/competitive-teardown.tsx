"use client"

import { useMemo, useState } from "react"
import { m, MotionConfig } from "framer-motion"
import { TEARDOWN_CHART } from "@/lib/competitive-teardown-colors"
import {
  Legend,
  PolarAngleAxis,
  PolarGrid,
  Radar,
  RadarChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts"

// ---------------------------------------------------------------------------
// THE DATASET — single source of truth. Flipdish verified from public product
// pages / G2 / Capterra / Tracxn; J'Toye verified against THIS repo's source
// tree. Do NOT invent, add or re-score any row: every number and verdict is
// factual. See docs/analysis/flipdish-vs-jtoye-teardown.md for the .md view.
// ---------------------------------------------------------------------------

type Status = "full" | "partial" | "none"
type Verdict = "jtoye" | "flipdish" | "parity"
type Tag = "J'Toye leads" | "Flipdish leads" | "Hard gap" | "Parity"

interface Feature {
  id: string
  group: string // one of the 8 category groups (drives the radar)
  name: string
  flipdish: Status
  jtoye: Status
  verdict: Verdict
  tag: Tag
  note: string // one-line verdict rationale
  evidence: string // J'Toye code evidence or "Absent — searched, not built"
}

const FEATURES: Feature[] = [
  // A. Customer ordering channels
  { id: "web-order", group: "Ordering channels", name: "Branded web ordering", flipdish: "full", jtoye: "full", verdict: "flipdish", tag: "Flipdish leads", note: "Both ship it; Flipdish's is AI-optimised and battle-tested at scale.", evidence: "core-java PublicStorefrontController.java + frontend app/shop/[slug]/page.tsx" },
  { id: "native-apps", group: "Ordering channels", name: "Native mobile apps (iOS/Android)", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "Flipdish ships branded native apps; J'Toye is web-only.", evidence: "Absent — searched, not built (no RN/Flutter/native; Next.js web only)" },
  { id: "kiosk", group: "Ordering channels", name: "Self-service kiosk", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "In-store kiosk ordering — not present in J'Toye.", evidence: "Absent — searched, not built" },
  { id: "qr-table", group: "Ordering channels", name: "QR / at-table ordering", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "J'Toye fulfilment is DELIVERY/COLLECTION only.", evidence: "Absent — FulfilmentType has DELIVERY/COLLECTION only; no table/dine-in" },
  { id: "whatsapp", group: "Ordering channels", name: "WhatsApp ordering", flipdish: "none", jtoye: "partial", verdict: "jtoye", tag: "J'Toye leads", note: "J'Toye has an inbound WhatsApp order parser + notify channel.", evidence: "edge-go/internal/whatsapp/parser.go + notification WhatsAppSmsChannel.java" },
  { id: "ai-phone", group: "Ordering channels", name: "AI phone-order agent", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Flipdish leads", note: "Flipdish AI Phone Agent answers/takes calls; J'Toye has none.", evidence: "Absent — searched, not built" },

  // B. Payments & monetization
  { id: "card-pay", group: "Payments", name: "Card payments", flipdish: "full", jtoye: "full", verdict: "parity", tag: "Parity", note: "Flipdish Pay vs Stripe — functional parity.", evidence: "core-java payment/PaymentService.java (Stripe)" },
  { id: "marketplace-pay", group: "Payments", name: "Marketplace / multi-vendor payouts", flipdish: "partial", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "J'Toye has native Stripe Connect destination charges + application_fee.", evidence: "payment/PaymentService.java (transfer_data[destination] + application_fee_amount) + StripeConnectService.java" },
  { id: "wl-ownkey", group: "Payments", name: "White-label vendor-own-key payments", flipdish: "partial", jtoye: "partial", verdict: "parity", tag: "Parity", note: "Neither fully ships this; J'Toye's own-key path is a stubbed future slice.", evidence: "payment/PaymentService.java (WHITE_LABEL own-key marked future slice)" },

  // C. Kitchen & operations
  { id: "kds", group: "Kitchen & ops", name: "Kitchen Display System (real-time)", flipdish: "full", jtoye: "full", verdict: "flipdish", tag: "Flipdish leads", note: "J'Toye's KDS is genuinely built (SSE+STOMP); Flipdish's is POS-linked + multi-station.", evidence: "core-java OrderController /orders/stream (SSE) + websocket/WebSocketConfig.java + frontend dashboard/kitchen" },
  { id: "pos-till", group: "Kitchen & ops", name: "POS / EPOS till (front-of-house)", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "All-in-one + handheld POS vs nothing.", evidence: "Absent — searched, not built" },
  { id: "inventory", group: "Kitchen & ops", name: "Inventory / stock cost control", flipdish: "full", jtoye: "partial", verdict: "flipdish", tag: "Flipdish leads", note: "Flipdish: stock + food-cost + wastage + purchasing. J'Toye: basic order-driven stock decrement only.", evidence: "Partial — order-driven stock decrement; no cost/wastage/purchasing module" },
  { id: "osm", group: "Kitchen & ops", name: "Order state machine / fulfilment", flipdish: "full", jtoye: "full", verdict: "parity", tag: "Parity", note: "Both robust; J'Toye adds outbox + idempotent events.", evidence: "order/OrderService.java + OrderStatus/OrderEvent + processed_order_events" },

  // D. Growth & retention
  { id: "loyalty", group: "Growth & retention", name: "Loyalty / points / stamps", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "AI-driven loyalty vs entirely absent.", evidence: "Absent — searched, not built (no entity/controller/migration)" },
  { id: "mktg-auto", group: "Growth & retention", name: "Marketing automation (campaigns/CRM/segmentation)", flipdish: "full", jtoye: "partial", verdict: "flipdish", tag: "Flipdish leads", note: "Flipdish: email/SMS/PPC campaigns + managed team. J'Toye: static promotions + announcements + consent only.", evidence: "Partial — shop/PromotionController.java + AnnouncementController.java + consent; no campaign/CRM/abandoned-cart engine" },
  { id: "promo-codes", group: "Growth & retention", name: "Redeemable promo codes / vouchers / gift cards", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Flipdish leads", note: "Flipdish vouchers vs shop-wide promotions only (no redeemable codes).", evidence: "Absent — only shop-wide % / flat promotions; no code redemption/gift cards" },
  { id: "reviews", group: "Growth & retention", name: "Reviews / ratings", flipdish: "partial", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "J'Toye has first-class food + delivery ratings on the storefront.", evidence: "review/Review.java + ReviewService.java + storefront GET /shops/{slug}/reviews" },

  // E. Delivery / fulfilment
  { id: "courier-int", group: "Delivery", name: "3rd-party courier integration (Uber Direct, Stuart)", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Hard gap", note: "Native courier integrations vs none.", evidence: "Absent — searched, not built" },
  { id: "dispatch", group: "Delivery", name: "Dispatch / driver assign / live tracking", flipdish: "full", jtoye: "partial", verdict: "flipdish", tag: "Flipdish leads", note: "Flipdish full dispatch; J'Toye has an order-tracking page but no courier orchestration.", evidence: "Partial — frontend app/track + order tracking; no dispatch engine" },
  { id: "aggregator", group: "Delivery", name: "Aggregator management (Deliveroo/Just Eat/Uber Eats)", flipdish: "full", jtoye: "none", verdict: "flipdish", tag: "Flipdish leads", note: "Centralised marketplace management vs none.", evidence: "Absent — searched, not built" },
  { id: "addr-fee", group: "Delivery", name: "Delivery-address + fee capture", flipdish: "full", jtoye: "full", verdict: "parity", tag: "Parity", note: "Address + flat delivery fee — parity at the basic level.", evidence: "order/FulfilmentType.java DELIVERY + V26__shop_delivery_fee.sql" },

  // F. Analytics
  { id: "analytics", group: "Analytics", name: "Reporting / BI", flipdish: "full", jtoye: "partial", verdict: "flipdish", tag: "Flipdish leads", note: "Real-time + AI analytics vs finance/VAT + rating summaries only.", evidence: "Partial — finance/FinancialTransactionController summary + rating summaries; no BI layer" },

  // G. UK compliance & regulatory
  { id: "ppds", group: "UK compliance", name: "PPDS / Natasha's-Law allergen PDF labels", flipdish: "none", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "J'Toye generates printable allergen labels + gates on completeness; Flipdish leaves compliance to the vendor.", evidence: "product/ProductLabelService.java (OpenPDF) + AllergenCompletenessGate.java + V41__ppds_label_compliance.sql" },
  { id: "vat", group: "UK compliance", name: "VAT ledger (append-only, per-order)", flipdish: "partial", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "Purpose-built immutable VAT ledger vs generic reporting.", evidence: "finance/FinancialTransaction.java + VatCalculator.java + V40__vat_ledger_correctness.sql" },
  { id: "fhrs-ch", group: "UK compliance", name: "FHRS + Companies House onboarding gating", flipdish: "none", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "State machine blocks go-live on live FSA hygiene + CH checks.", evidence: "onboarding VendorOnboardingStateMachine + FhrsClient.java + CompaniesHouseClient.java + V43__vendor_onboarding.sql" },
  { id: "gdpr", group: "UK compliance", name: "GDPR erasure / DSAR", flipdish: "partial", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "Dedicated erasure controller + audit-history scrub.", evidence: "gdpr/GdprController.java + erasure_records (V42)" },

  // H. Platform / architecture
  { id: "rls-engine", group: "Platform", name: "Multi-tenant white-label engine (RLS)", flipdish: "none", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "J'Toye runs as a resellable RLS-isolated multi-tenant engine; Flipdish is the SaaS, not the engine.", evidence: "V2__rls_policies.sql + security/JwtTenantFilter.java + security/access/ShopStaff.java" },
  { id: "mcp", group: "Platform", name: "AI agent interface (MCP write API)", flipdish: "partial", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "Agent-operable read+write tools (create_order/create_customer, idempotency-keyed); Flipdish's AI is customer-facing, not developer/agent-facing.", evidence: "mcp-server/src/tools/create-order.ts + create-customer.ts (idempotency-keyed)" },
  { id: "webhooks", group: "Platform", name: "Outbound webhooks (HMAC)", flipdish: "none", jtoye: "full", verdict: "jtoye", tag: "J'Toye leads", note: "First-class HMAC-signed outbound webhook subscriptions + delivery tracking.", evidence: "webhook/WebhookSubscriptionController.java + WebhookDelivery.java (V55/56)" },
]

// The 8 category groups in radar order.
const GROUP_ORDER = [
  "Ordering channels",
  "Payments",
  "Kitchen & ops",
  "Growth & retention",
  "Delivery",
  "Analytics",
  "UK compliance",
  "Platform",
] as const

// Monetization / scale infographic — embedded verbatim.
const SCALE = {
  flipdish: [
    ["Reach", "5,000+ brands"],
    ["Countries", "25 countries"],
    ["Valuation", "~$1.25B (Tencent-led, Jan 2022)"],
    ["Raised", "~$157M"],
    ["Pricing", "£99–139/site/mo + setup fee + 2–7% commission + add-ons"],
  ],
  jtoye: [
    ["Stage", "pre-market"],
    ["Raised", "£0"],
    ["Team", "solo/small build"],
    ["Monetization", "Stripe Connect per-order platform fee"],
    ["Billing", "no SaaS subscription billing built"],
  ],
} as const

const WINS = [
  { title: "UK-compliance-native", detail: "PPDS labels · VAT ledger · FHRS/CH gating · GDPR" },
  { title: "Native marketplace payments", detail: "Stripe Connect destination charges" },
  { title: "Agent-operable MCP write API", detail: "create_order / create_customer, idempotency-keyed" },
  { title: "RLS white-label engine", detail: "Resellable RLS-isolated multi-tenant engine" },
] as const

const GAPS = [
  "Native mobile apps",
  "POS/EPOS till",
  "Loyalty",
  "Courier integrations",
  "Self-service kiosk",
  "Marketing automation",
] as const

const FILTERS: (Tag | "All")[] = ["All", "J'Toye leads", "Flipdish leads", "Hard gap", "Parity"]

const NAV = [
  ["overview", "Overview"],
  ["radar", "Radar"],
  ["matrix", "Feature matrix"],
  ["wins", "J'Toye wins"],
  ["gaps", "Hard gaps"],
  ["scale", "Scale & money"],
  ["wedge", "The wedge"],
  ["sources", "Sources"],
] as const

function scoreOf(status: Status): number {
  return status === "full" ? 100 : status === "partial" ? 50 : 0
}

const statusMeta: Record<Status, { icon: string; label: string; short: string; cls: string }> = {
  full: { icon: "✅", label: "full support", short: "Full", cls: "border-emerald-600 bg-emerald-50 text-emerald-800" },
  partial: { icon: "🟡", label: "partial support", short: "Partial", cls: "border-amber-500 bg-amber-100 text-amber-800" },
  none: { icon: "❌", label: "not built", short: "None", cls: "border-orange-300 bg-orange-50 text-orange-800" },
}

const tagCls: Record<Tag, string> = {
  "J'Toye leads": "border-emerald-600 bg-emerald-600 text-white",
  "Flipdish leads": "border-oxblood bg-oxblood text-white",
  "Hard gap": "border-orange-600 bg-orange-600 text-white",
  Parity: "border-slate-400 bg-slate-100 text-slate-700",
}

export function CompetitiveTeardown() {
  const [tagFilter, setTagFilter] = useState<Tag | "All">("All")
  const [query, setQuery] = useState("")

  const counts = useMemo(() => {
    const byTag = (t: Tag) => FEATURES.filter((f) => f.tag === t).length
    return {
      total: FEATURES.length,
      jtoye: byTag("J'Toye leads"),
      flipdish: byTag("Flipdish leads"),
      hardGap: byTag("Hard gap"),
      parity: byTag("Parity"),
    }
  }, [])

  const radarData = useMemo(
    () =>
      GROUP_ORDER.map((group) => {
        const rows = FEATURES.filter((f) => f.group === group)
        const avg = (pick: (f: Feature) => Status) =>
          Math.round(rows.reduce((sum, f) => sum + scoreOf(pick(f)), 0) / rows.length)
        return { group, flipdish: avg((f) => f.flipdish), jtoye: avg((f) => f.jtoye) }
      }),
    [],
  )

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return FEATURES.filter((f) => {
      const matchesTag = tagFilter === "All" || f.tag === tagFilter
      const matchesQuery = q === "" || `${f.name} ${f.group} ${f.note}`.toLowerCase().includes(q)
      return matchesTag && matchesQuery
    })
  }, [tagFilter, query])

  // MotionConfig reducedMotion="user" (below) auto-disables these transform
  // micro-interactions when the visitor prefers reduced motion, so they can
  // always be applied — no manual gating / mount-effect needed.
  const hover = { whileHover: { y: -3 }, whileTap: { scale: 0.99 } }

  return (
    <MotionConfig reducedMotion="user">
      <div className="min-h-screen bg-cream text-slate-900 selection:bg-amber-300 selection:text-slate-900">
        <a
          href="#overview"
          className="sr-only z-50 rounded bg-oxblood px-4 py-3 text-sm font-semibold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4"
        >
          Skip to teardown
        </a>

        {/* Hero */}
        <header className="border-b-4 border-oxblood bg-oxblood text-slate-50">
          <div className="mx-auto max-w-7xl px-5 py-6 sm:px-8">
            <div className="flex flex-wrap items-center justify-between gap-3 text-xs font-bold uppercase tracking-[0.16em]">
              <span className="flex items-center gap-2">
                <span className="h-2.5 w-2.5 bg-amber-300" /> J&apos;Toye OaaS / competitive teardown
              </span>
              <span className="font-medium tracking-[0.12em] text-slate-300">Last updated · 24 July 2026</span>
            </div>
            <div className="mt-10 grid gap-8 lg:grid-cols-[1.3fr_0.7fr] lg:items-end">
              <div>
                <p className="mb-4 text-xs uppercase tracking-[0.2em] text-amber-300">
                  Flipdish vs J&apos;Toye — the honest scoreboard
                </p>
                <h1 className="max-w-4xl text-4xl font-bold leading-[0.97] tracking-[-0.055em] sm:text-6xl">
                  Don&apos;t out-feature Flipdish. <span className="text-amber-300">Win the wedge.</span>
                </h1>
              </div>
              <p className="max-w-md border-l-2 border-amber-300 pl-5 text-base leading-7 text-slate-300">
                Flipdish is a full restaurant OS that wins most functional categories and decisively on scale. J&apos;Toye
                wins a narrow, coherent cluster: UK-compliance-native, native marketplace payments, agent-ready, RLS
                white-label engine.
              </p>
            </div>
          </div>
        </header>

        {/* Jump nav */}
        <nav
          aria-label="Teardown sections"
          className="sticky top-0 z-30 border-b border-slate-200 bg-cream/95 backdrop-blur"
        >
          <div className="mx-auto flex max-w-7xl items-center gap-2 overflow-x-auto px-5 py-3 sm:px-8">
            <span className="mr-2 shrink-0 text-xs font-bold uppercase tracking-[0.15em] text-slate-500">
              Jump
            </span>
            {NAV.map(([id, label]) => (
              <a
                key={id}
                href={`#${id}`}
                className="shrink-0 rounded-full px-3 py-1.5 text-sm font-semibold text-slate-600 hover:bg-cream focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
              >
                {label}
              </a>
            ))}
          </div>
        </nav>

        <div className="mx-auto max-w-7xl px-5 py-12 sm:px-8 sm:py-16">
          {/* Overview + stat tiles */}
          <section id="overview" aria-labelledby="overview-heading" className="scroll-mt-20">
            <SectionLabel number="01" label="The scoreboard" />
            <h2 id="overview-heading" className="max-w-4xl text-3xl font-bold leading-tight tracking-[-0.035em] sm:text-4xl">
              29 tracked features across 8 categories, verified against the source tree.
            </h2>
            <p className="mt-5 max-w-3xl text-lg leading-8 text-slate-600">
              Flipdish is verified from public product pages, G2, Capterra and Tracxn. J&apos;Toye is verified against
              this repo — every row below carries a code path or an explicit &ldquo;Absent — searched, not built&rdquo;.
            </p>
            <dl className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
              <StatTile {...hover} label="Tracked features" value={counts.total} accent="slate" />
              <StatTile {...hover} label="J'Toye leads" value={counts.jtoye} accent="emerald" />
              <StatTile {...hover} label="Flipdish leads" value={counts.flipdish} accent="slate" />
              <StatTile {...hover} label="Hard gaps" value={counts.hardGap} accent="orange" />
              <StatTile {...hover} label="Parity" value={counts.parity} accent="amber" />
            </dl>
          </section>

          {/* Radar */}
          <section id="radar" aria-labelledby="radar-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="02" label="Category coverage" />
            <div className="grid gap-8 lg:grid-cols-[0.8fr_1.2fr] lg:items-center">
              <div>
                <h2 id="radar-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
                  Where each platform is strong.
                </h2>
                <p className="mt-5 max-w-md leading-7 text-slate-600">
                  Each axis averages that category&apos;s feature coverage (full = 100, partial = 50, none = 0). Flipdish
                  dominates ordering, kitchen, growth, delivery and analytics; the two are roughly level on payments;
                  J&apos;Toye spikes on UK compliance and platform.
                </p>
              </div>
              <figure className="border-[3px] border-oxblood bg-white p-4 shadow-[8px_8px_0_theme(colors.slate.200)]">
                <figcaption className="mb-2 text-xs font-bold uppercase tracking-[0.14em] text-slate-600">
                  Category coverage radar
                </figcaption>
                <div
                  role="img"
                  aria-label="Radar chart comparing Flipdish and J'Toye average feature coverage across eight categories. Flipdish leads ordering channels, kitchen and operations, growth and retention, delivery and analytics; J'Toye leads UK compliance and platform; payments are roughly level."
                >
                  <ResponsiveContainer width="100%" height={360}>
                    <RadarChart data={radarData} outerRadius="72%">
                      <PolarGrid />
                      <PolarAngleAxis dataKey="group" tick={{ fontSize: 11, fill: TEARDOWN_CHART.axisTick }} />
                      <Radar name="Flipdish" dataKey="flipdish" stroke={TEARDOWN_CHART.flipdish} fill={TEARDOWN_CHART.flipdish} fillOpacity={0.28} />
                      <Radar name="J'Toye" dataKey="jtoye" stroke={TEARDOWN_CHART.jtoye} fill={TEARDOWN_CHART.jtoye} fillOpacity={0.32} />
                      <Legend />
                      <Tooltip />
                    </RadarChart>
                  </ResponsiveContainer>
                </div>
                {/* Accessible data-table fallback for the chart. */}
                <table className="sr-only">
                  <caption>Average feature-coverage score per category (0–100)</caption>
                  <thead>
                    <tr>
                      <th scope="col">Category</th>
                      <th scope="col">Flipdish</th>
                      <th scope="col">J&apos;Toye</th>
                    </tr>
                  </thead>
                  <tbody>
                    {radarData.map((row) => (
                      <tr key={row.group}>
                        <th scope="row">{row.group}</th>
                        <td>{row.flipdish}</td>
                        <td>{row.jtoye}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </figure>
            </div>
          </section>

          {/* Feature matrix */}
          <section id="matrix" aria-labelledby="matrix-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="03" label="Interactive feature matrix" />
            <div className="flex flex-wrap items-end justify-between gap-5">
              <div>
                <h2 id="matrix-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
                  Every row, with the evidence.
                </h2>
                <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-500">
                  Filter by verdict or search by name, category or rationale. Expand a row to read the one-line verdict
                  and the J&apos;Toye code path (or why it&apos;s absent).
                </p>
              </div>
            </div>

            <div className="mt-8 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
              <div role="group" aria-label="Filter features by verdict" className="flex flex-wrap gap-2">
                {FILTERS.map((filter) => (
                  <button
                    key={filter}
                    type="button"
                    aria-pressed={tagFilter === filter}
                    onClick={() => setTagFilter(filter)}
                    className={`rounded-full border px-3 py-1.5 text-sm font-semibold focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500 ${
                      tagFilter === filter
                        ? "border-oxblood bg-oxblood text-white"
                        : "border-slate-300 bg-cream hover:bg-cream"
                    }`}
                  >
                    {filter}
                  </button>
                ))}
              </div>
              <div className="w-full md:w-72">
                <label htmlFor="matrix-search" className="sr-only">
                  Search features
                </label>
                <input
                  id="matrix-search"
                  type="search"
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="Search features…"
                  className="w-full rounded-full border-2 border-oxblood bg-white px-4 py-2 text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
                />
              </div>
            </div>

            <p className="mt-4 text-xs uppercase tracking-[0.12em] text-slate-500" aria-live="polite">
              Showing {visible.length} of {FEATURES.length} features
            </p>

            {/* Column header (aligned columns at md+) */}
            <div className="mt-4 hidden grid-cols-[1.6fr_0.7fr_0.7fr_0.9fr] gap-3 border-b-2 border-oxblood px-4 pb-2 text-xs font-bold uppercase tracking-[0.12em] text-slate-500 md:grid">
              <span>Feature</span>
              <span>Flipdish</span>
              <span>J&apos;Toye</span>
              <span>Verdict</span>
            </div>

            <div className="mt-4 space-y-3 overflow-x-auto md:mt-0 md:space-y-0">
              {visible.length === 0 ? (
                <p className="border-2 border-dashed border-slate-300 bg-white p-8 text-center text-sm font-semibold text-slate-500">
                  No features match that filter and search. Try &ldquo;All&rdquo; or clear the search box.
                </p>
              ) : (
                visible.map((f) => <FeatureRow key={f.id} feature={f} />)
              )}
            </div>
          </section>

          {/* J'Toye wins */}
          <section id="wins" aria-labelledby="wins-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="04" label="The coherent cluster" />
            <h2 id="wins-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
              Where J&apos;Toye genuinely wins.
            </h2>
            <div className="mt-8 grid gap-4 sm:grid-cols-2">
              {WINS.map((win) => (
                <m.article
                  key={win.title}
                  {...hover}
                  className="border-t-[5px] border-emerald-600 bg-white p-6 shadow-[5px_5px_0_theme(colors.slate.200)]"
                >
                  <h3 className="text-xl font-extrabold tracking-[-0.02em]">{win.title}</h3>
                  <p className="mt-3 text-sm leading-6 text-slate-600">{win.detail}</p>
                </m.article>
              ))}
            </div>
          </section>

          {/* Hard gaps */}
          <section id="gaps" aria-labelledby="gaps-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="05" label="The honest gaps" />
            <h2 id="gaps-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
              What Flipdish has that J&apos;Toye does not.
            </h2>
            <p className="mt-4 max-w-3xl leading-7 text-slate-600">
              These are &ldquo;searched, not built&rdquo; — no code, no wiring. Do not try to close them all head-on.
            </p>
            <div className="mt-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {GAPS.map((gap) => (
                <m.div
                  key={gap}
                  {...hover}
                  className="flex items-center gap-3 border-l-4 border-amber-500 bg-white p-4 text-base font-bold text-slate-800"
                >
                  <span aria-hidden="true" className="text-amber-600">
                    ✕
                  </span>
                  {gap}
                </m.div>
              ))}
            </div>
          </section>

          {/* Scale & money */}
          <section id="scale" aria-labelledby="scale-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="06" label="Scale, funding & pricing" />
            <h2 id="scale-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
              The maturity gap is real — and not the battleground.
            </h2>
            <div className="mt-8 grid gap-5 md:grid-cols-2">
              <ScaleColumn title="Flipdish" tone="slate" rows={SCALE.flipdish} />
              <ScaleColumn title="J'Toye" tone="orange" rows={SCALE.jtoye} />
            </div>
            <p className="mt-6 border-l-4 border-oxblood bg-white p-5 text-sm leading-6 text-slate-600">
              Flipdish monetizes via SaaS subscription + setup + per-order commission + add-on modules. J&apos;Toye&apos;s
              only wired monetization is a Stripe Connect per-order platform fee; there is no SaaS subscription billing
              built.
            </p>
          </section>

          {/* Wedge conclusion */}
          <section id="wedge" aria-labelledby="wedge-heading" className="mt-20 scroll-mt-20">
            <div className="border-2 border-oxblood bg-oxblood p-6 text-slate-50 sm:p-9">
              <p className="text-xs font-bold uppercase tracking-[0.16em] text-amber-300">The strategic wedge</p>
              <h2 id="wedge-heading" className="mt-3 text-3xl font-bold tracking-[-0.04em] sm:text-4xl">
                Compete on compliance + marketplace-engine + agent-ready.
              </h2>
              <p className="mt-5 max-w-3xl text-lg leading-8 text-slate-300">
                Flipdish is a full restaurant OS sold to individual restaurants and wins ~14 of ~20 functional categories
                plus decisively on maturity, scale and capital. J&apos;Toye wins a narrow, coherent cluster —
                UK-compliance-native, native marketplace payments, agent-ready, RLS white-label engine.
              </p>
              <p className="mt-5 max-w-3xl border-l-4 border-amber-300 pl-5 text-lg font-bold leading-8">
                Compete on the compliance + marketplace-engine + agent-ready wedge; do not try to out-feature Flipdish
                head-on.
              </p>
            </div>
          </section>

          {/* Sources + caveat */}
          <section id="sources" aria-labelledby="sources-heading" className="mt-20 scroll-mt-20 border-t border-slate-200 pt-12">
            <SectionLabel number="07" label="Sources & caveat" />
            <div className="grid gap-8 lg:grid-cols-[0.7fr_1.3fr]">
              <div>
                <h2 id="sources-heading" className="text-3xl font-bold tracking-[-0.035em] sm:text-4xl">
                  Read the caveat before quoting a row.
                </h2>
                <p className="mt-5 leading-7 text-slate-600">
                  This is a dated competitive analysis, not a benchmark. The Flipdish side is public research; the
                  J&apos;Toye side is source-tree verified.
                </p>
              </div>
              <ul className="space-y-2 text-sm leading-6 text-slate-700">
                {[
                  "flipdish.com/gb",
                  "flipdish.com/products-overview",
                  "flipdish.com/pos-system",
                  "G2 Flipdish reviews",
                  "Capterra UK Flipdish pricing",
                  "Tracxn Flipdish profile",
                ].map((source) => (
                  <li key={source} className="font-semibold">
                    {source}
                  </li>
                ))}
              </ul>
            </div>
            <aside className="mt-8 border border-orange-200 bg-orange-50 p-5 text-sm leading-6 text-orange-900">
              <strong>Caveat:</strong> For J&apos;Toye, &ldquo;PRESENT&rdquo; means the code/wiring exists in the repo,
              not that every path is proven end-to-end at production scale (e.g. the WHITE_LABEL own-key payment path is a
              stub). Flipdish&apos;s features are live across 5,000+ brands. <strong>Last updated: 2026-07-24.</strong>
            </aside>
            <div className="mt-8 flex flex-wrap items-center gap-4 border-t border-dashed border-slate-300 pt-6 text-sm">
              <span className="text-xs uppercase tracking-[0.12em] text-slate-500">
                Full write-up: docs/analysis/flipdish-vs-jtoye-teardown.md
              </span>
              <a
                href="/business-model-guide"
                className="inline-flex items-center gap-2 rounded-full border-2 border-oxblood px-4 py-2 font-semibold hover:bg-cream focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
              >
                Business model guide
              </a>
              <a
                href="/for-operators"
                className="inline-flex items-center gap-2 rounded-full border-2 border-oxblood px-4 py-2 font-semibold hover:bg-cream focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
              >
                Operator pitch
              </a>
            </div>
          </section>
        </div>

        <footer className="border-t-4 border-oxblood bg-oxblood px-5 py-7 text-slate-300">
          <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 sm:px-3">
            <p className="text-xs uppercase tracking-[0.14em]">J&apos;Toye OaaS / competitive teardown</p>
            <p className="text-xs uppercase tracking-[0.12em] text-slate-400">Last updated 24 July 2026</p>
          </div>
        </footer>
      </div>
    </MotionConfig>
  )
}

function SectionLabel({ number, label }: { number: string; label: string }) {
  return (
    <p className="mb-5 flex items-center gap-3 text-xs font-bold uppercase tracking-[0.15em] text-amber-600">
      <span>{number}</span>
      <span className="h-px w-10 bg-amber-500" />
      {label}
    </p>
  )
}

function StatTile({
  label,
  value,
  accent,
  ...motionProps
}: {
  label: string
  value: number
  accent: "slate" | "emerald" | "orange" | "amber"
  whileHover?: { y: number }
  whileTap?: { scale: number }
}) {
  const accents: Record<string, string> = {
    slate: "border-oxblood text-oxblood",
    emerald: "border-emerald-600 text-emerald-700",
    orange: "border-orange-600 text-orange-700",
    amber: "border-amber-500 text-amber-700",
  }
  return (
    <m.div {...motionProps} className={`border-[3px] bg-white p-4 shadow-[5px_5px_0_theme(colors.slate.200)] ${accents[accent]}`}>
      <dt className="text-xs font-bold uppercase tracking-[0.12em] text-slate-500">{label}</dt>
      <dd className="mt-2 text-3xl font-bold tracking-[-0.04em]">{value}</dd>
    </m.div>
  )
}

function StatusCell({ status }: { status: Status }) {
  const meta = statusMeta[status]
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-bold ${meta.cls}`}>
      <span aria-hidden="true">{meta.icon}</span>
      <span>{meta.short}</span>
      <span className="sr-only">— {meta.label}</span>
    </span>
  )
}

function FeatureRow({ feature }: { feature: Feature }) {
  return (
    <details className="group border-2 border-slate-200 bg-white open:border-oxblood md:border-x-0 md:border-t-0 md:border-b md:border-slate-200">
      <summary className="grid cursor-pointer list-none gap-3 p-4 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500 md:grid-cols-[1.6fr_0.7fr_0.7fr_0.9fr] md:items-center">
        <span className="flex items-center gap-2 font-bold">
          <span aria-hidden="true" className="text-xs text-slate-400 transition group-open:rotate-90">
            ▸
          </span>
          <span>
            {feature.name}
            <span className="mt-0.5 block text-xs font-medium uppercase tracking-[0.1em] text-slate-400">
              {feature.group}
            </span>
          </span>
        </span>
        <span className="flex items-center gap-2 md:block">
          <span className="text-xs uppercase tracking-[0.1em] text-slate-400 md:hidden">Flipdish</span>
          <StatusCell status={feature.flipdish} />
        </span>
        <span className="flex items-center gap-2 md:block">
          <span className="text-xs uppercase tracking-[0.1em] text-slate-400 md:hidden">J&apos;Toye</span>
          <StatusCell status={feature.jtoye} />
        </span>
        <span>
          <span className={`inline-block rounded-full border px-2.5 py-1 text-xs font-bold ${tagCls[feature.tag]}`}>
            {feature.tag}
          </span>
        </span>
      </summary>
      <div className="border-t border-slate-200 bg-cream px-4 py-4 text-sm leading-6 md:pl-9">
        <p className="text-slate-700">
          <span className="text-xs font-bold uppercase tracking-[0.12em] text-slate-500">Verdict · </span>
          {feature.note}
        </p>
        <p className="mt-3 text-slate-500">
          <span className="text-xs font-bold uppercase tracking-[0.12em] text-slate-500">J&apos;Toye evidence · </span>
          <code className="break-words rounded bg-white px-1.5 py-0.5 text-xs text-slate-700">{feature.evidence}</code>
        </p>
      </div>
    </details>
  )
}

function ScaleColumn({
  title,
  tone,
  rows,
}: {
  title: string
  tone: "slate" | "orange"
  rows: readonly (readonly [string, string])[]
}) {
  const head = tone === "slate" ? "bg-oxblood text-slate-50" : "bg-amber-500 text-slate-900"
  return (
    <div className="overflow-hidden border-[3px] border-oxblood bg-white shadow-[8px_8px_0_theme(colors.slate.200)]">
      <div className={`px-5 py-3 text-sm font-bold uppercase tracking-[0.14em] ${head}`}>{title}</div>
      <dl className="divide-y divide-slate-200">
        {rows.map(([label, value]) => (
          <div key={label} className="grid grid-cols-[0.6fr_1.4fr] gap-3 px-5 py-3">
            <dt className="text-xs font-bold uppercase tracking-[0.1em] text-slate-500">{label}</dt>
            <dd className="text-sm font-semibold text-slate-800">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
