# Vertical SaaS Strategic Context (Food/Hospitality, 2025-2026)

**Analyst persona**: Vertical SaaS GTM consultant
**Date**: 2026-04-27
**Subject**: Strategic context for J'Toye OaaS — multi-tenant UK food/hospitality SaaS (shop ops, orders, KDS, marketing, payments)

---

## Headline insights

1. **Fintech is now the product, software is the wedge.** Toast's FY2025 revenue was $6.15B with ARR over $2B at 26% YoY growth, but software subscriptions only contributed roughly $936M; ~76% of Toast's revenue now comes from financial services (payments, lending, payroll). A pure-SaaS subscription model is no longer competitive at scale in restaurant tech.
2. **The hospitality SaaS multiple is depressed.** Travel/hospitality software trades at ~1.8x revenue (Oct 2025) — the bottom of the vertical SaaS pack — versus 7-9x for premium vertical SaaS. The fix is embedded payments/lending revenue and Rule-of-40 performance, not more seats.
3. **The UK independent operator base is contracting.** 3.4 net venue closures per day; independent dining is 22.7% smaller than pre-COVID; margins compressed from 15-20% to ~5%. ICP is shrinking and price-sensitive — payback periods must be measured in weeks of delivery-commission savings, not months.
4. **The aggregator-displacement thesis is real and validated.** Deliveroo charges 25-35% headline (effectively 30%+ with VAT and clawbacks); Just Eat 14-18%; Olo (NRR 114%, Q2 2025) was just acquired by Thoma Bravo for ~$2B on the strength of "own the customer relationship" positioning. This is the most defensible UK angle.
5. **AI is moving from forecasting (table-stakes) to voice (differentiating).** Crunchtime, Supy and others have made demand forecasting commodity; voice-AI drive-thru (Wendy's FreshAI rolling to 500-600 stores; Presto in 12 brands targeting thousands of locations in 2026) is where moats are forming. Local LLMs (Ollama) win on cost and privacy for high-volume narrow tasks.

---

## SaaS economics & benchmarks

**Public-comp ARPU per location (verified from 2025 filings/earnings releases):**

| Company | ARPU (annualised) | Locations | Notes |
|---|---|---|---|
| **Toast (TOST)** | ~$12k+ in subscription + payments combined per location (derived: $6.15B revenue ÷ 164k locations) | 164,000 (+30k in 2025) | SaaS NRR 109%; SaaS gross margin 80% (Q4'25); ARR >$2B, +26% YoY |
| **Lightspeed (LSPD)** | **~$545/month ARPU (~$6.5k/yr)** as of Mar 2025 | ~144,000 (down from 162k as Lightspeed culled low-ARPU customers) | ARPU +19% YoY; deliberate strategy to shrink customer count, raise revenue per |
| **Olo (OLO)** | **~$911/month ARPU (~$10.9k/yr)**, +12% YoY (Q1'25) | ~89,000 (+9% YoY in Q2'25) | NRR **111%-114%**; acquired by Thoma Bravo Jul 2025 for ~$2B equity value (~$10.25/share) |
| **Square for Restaurants (Block)** | Not separately disclosed | ~27-28% POS market share (vs. Toast ~24%) | Pivoted in 2025 to unified-app branding; added voice ordering & AI kiosk |

**LTV/CAC norms (general SaaS, 2025):** Healthy ratio is 3:1; Benchmarkit median is 3.6:1. Average B2B SaaS CAC has risen ~14% through 2025 to ~$1,200 blended. Vertical hospitality slowed >50% YoY in 2024-2025 and salaries-as-%-revenue rose 8% — meaning hospitality vertical-SaaS is *harder than median* right now (unverified for restaurant-specific LTV/CAC; treat as proxy).

**NRR benchmarks (2025):** Median 106%; best-in-class >130%; >$100M-ARR companies median 115%, $1-10M ARR companies median 98%. Toast 109%, Olo 111-114% — both above hospitality-vertical median, both well below SaaS top-quartile.

**M&A multiples (2025):** Vertical SaaS commands a 25-30% premium over horizontal at the same financial profile. Premium vertical SaaS with strong retention + Rule-of-40 trades at **7-9x revenue**. Travel/hospitality software sits at **~1.8x revenue** (Oct 2025) — the cheapest vertical, weighed down by macro headwinds. Olo's Thoma Bravo deal (~$2B at ~$340M FY24 revenue) prices to roughly **5-6x revenue** — a "good vertical-SaaS exit," not a great one.

Sources: Toast Q4 2025 earnings (Motley Fool transcript, 2026-02-12); Lightspeed FY2025 results (lightspeedhq.com, 2025); Olo Q1/Q2 2025 releases (investors.olo.com); Aventis Advisors / Software Equity Group / SaaSRise M&A reports 2025.

---

## Pricing trends in 2026

The dominant model is **hybrid (subscription + transaction) — not pure subscription.** Per Chargebee's 2025 State of Subscriptions, 43% of SaaS companies use hybrid pricing today, projected to hit 61% by end of 2026, and hybrid-priced firms post **38% higher revenue growth and 38% higher NRR** than pure-subscription peers.

**The restaurant playbook (2026):**

- **Toast:** $69/mo Core plan + 2.49% + 15¢ in-person, 3.50% + 15¢ online; free Starter Kit at 3.09% + 15¢. Most restaurants pay $300-$700/mo (cafes) or $1,000-$2,000+/mo all-in. Software is the trojan horse; payments is the P&L.
- **Square for Restaurants:** 2.6% + 10¢ flat; lower software fees but no contracts and weaker workflow depth.
- **Flipdish (Ireland-based, UK-relevant):** entry from ~$50/mo with mandatory 50p collection / 70p delivery transaction fees → classic hybrid.
- **Olo:** SaaS ARPU $911/mo at enterprise; D2C ordering as the value prop; payments and Olo Pay layered on.

**Stripe Connect** for embedded SaaS payments is now standard infra. Interchange-plus is the preferred model at high volume (large platforms negotiate revenue-share on interchange itself). Alternatives like Fiska market themselves explicitly on "all profit above interchange shared with the SaaS partner, zero fixed fees" — signalling that the take-rate ceiling is moving in the SaaS platform's favour.

**Marketplace take-rate norms** (delivery aggregators) sit at 14-35% — an order of magnitude higher than what a SaaS platform can charge in subscription. The strategic implication: every vertical SaaS sits *between* the merchant and an aggregator that taxes them at 30%, and any feature that compresses that 30% becomes the moat. Add-on/module pricing (loyalty, payroll, lending, KDS, kiosk) is how Toast went from $300/mo software to $12k+/yr ARPU per location; the modules unlock new fintech monetisation surfaces, not just new subscription line items.

---

## Dark kitchen segment

Europe cloud-kitchen market: **$16.53B (2025) → $18.24B (2026)**, with the UK as the largest single contributor at **22.4% share** in 2026. CAGR through 2032 is ~19% (Coherent Market Insights, 2025). Notable UK operators: **Karma Kitchen** (London-founded 2018, raised £252M / ~$318M, expanding to 53 sites across UK/Europe), **Foodstars** (acquired by CloudKitchens in 2019, sites in London plus Birmingham, Leeds, Manchester), and the smaller **Dabba Drop** (subscription Indian meal-kit, asset-light hybrid).

**Tech needs that differ from brick-and-mortar:**
- Multi-brand operations from one kitchen → SKU-and-brand splitting in a single POS/KDS
- Aggregator-feed orchestration (a kitchen typically receives orders from 3-5 platforms simultaneously) → consolidator/middleware is mandatory, not optional
- No front-of-house, so no need for table management; instead, batch prep and hand-off-to-courier workflows
- Hourly/short-tenure tenancy billing → operator-side reporting on revenue-per-hour-per-station

**Wedge potential for J'Toye:** Strong. The independent dark-kitchen operator inside a Karma Kitchen / Foodstars site is exactly the SMB profile (one chef, 2-3 staff, multi-aggregator) that gets crushed by Deliverect ($-quoted, US-feeling) and is too small for Toast. A KDS + multi-aggregator orchestrator + direct-ordering site bundle priced under £150/mo would have land-and-expand potential. **Caveat (unverified):** I have not seen public ARPU benchmarks for this sub-segment — sizing is directional, not banked.

---

## Aggregator vs D2C

UK delivery commission reality (2025-2026):

| Platform | Headline | Effective (with VAT, clawbacks) |
|---|---|---|
| Deliveroo | 25-35% | **30%+** for most independents |
| Uber Eats | 25-30% | ~30%+ |
| Just Eat | 14-18% | 20%+ |

**The economics of the alternative:** ChowNow / Olo-style D2C platforms have 60-75% gross margins on SaaS revenue, vs. 1-2% EBITDA on GTV for aggregators (per Olo's 2025 positioning). Restaurants on Olo pay no per-order commission and retain customer data.

**"Take back the customer relationship" thesis — real?** Yes, with caveats. Olo's NRR of 111-114% and Thoma Bravo's $2B all-cash buyout (Jul 2025) is market validation. But the thesis only works for restaurants whose customers *will* return — independents in dense urban areas with brand identity (e.g., a popular Brixton jerk-chicken spot) realise it; commodity QSR cannot. White-label app players **Flipdish** (Ireland, UK strong) and **Slerp** (UK-native) have proven the SMB-tier version of this works at £50-150/mo plus per-order fees. For J'Toye's UK target, this is the central sellable narrative: *"Stop paying Deliveroo 30%; pay us £99/mo + 1% and own the customer."*

---

## AI integration: state of play

**Table-stakes by 2026:**
- **Demand forecasting / inventory** — Crunchtime claims 99% accuracy on prep forecasts using sales + weather + events; Supy has democratised this for SMB. Any new entrant *must* offer this; it does not differentiate.
- **Menu engineering / pricing** — image-based menu generation, profitability analysis, item-mix optimisation. Now built into all major POS suites.
- **LLM customer support** — chatbot for FAQs and order modifications. Commodity.

**Differentiating in 2026:**
- **Voice AI drive-thru / phone ordering** — Wendy's FreshAI (Google Cloud) rolling to 500-600 stores by end-2025; Presto raised $10M Jan 2026 to scale across thousands of locations through 12 brands; McDonald's restarting voice-AI initiatives after IBM exit. Industry projection: **50% of US drive-thru orders AI-handled by late 2026.** UK opportunity: phone-ordering voice AI for independents (drive-thru is rare in UK; phone orders to small Caribbean/Indian/kebab takeaways are common — and a real pain point).
- **Operational alerting / store-execution AI** — Crunchtime's April 2026 launch is the bellwether: AI watches the dashboard and tells the operator what to fix.
- **Image-based menu generation and allergen labelling** — J'Toye already does this with on-prem Ollama (per CLAUDE.md context). This is genuinely differentiating for UK because of the **2021 Natasha's Law** allergen-labelling regime: PDF labels generated locally with no customer-data leakage is a compliance + cost win.

**Local LLM (Ollama) vs cloud (OpenAI/Anthropic) — strategic call:**

The 2026 consensus is **hybrid: local-default, cloud-overflow.** Local inference delivers 70-85% of frontier quality at zero marginal cost per request; cloud APIs cost $3-12 / $15-75 per million input/output tokens for frontier models. For a multi-tenant SaaS:

- **Use local (Ollama):** image classification (allergen/menu photo analysis), high-volume narrow-task inference (description generation, label rewrites), anything touching customer PII or payment data — particularly because **44% of organisations cite data privacy as the #1 LLM-adoption barrier**.
- **Use cloud:** complex reasoning, conversational customer support, code generation in the SaaS platform itself.

**J'Toye's choice of Ollama on-prem for image analysis is strategically correct** for three reasons: (1) UK GDPR + Natasha's Law gives "your image never leaves the cluster" a marketable compliance angle, (2) at 89,000-location-scale (Olo) or even 1,000-location-scale, marginal token costs would dominate gross margin, (3) image inference quality on open models is now near-parity with frontier for product photography classification. The risk is operational: you carry inference SLA on your own infra. Mitigate with a cloud-LLM circuit-breaker fallback.

---

## Defensibility patterns

1. **Embedded fintech compounding (Toast playbook).** Software → payments → lending → payroll → cards. Each layer requires data from the prior. Toast Capital underwrites $5k-$300k working-capital loans against daily card sales — only possible because Toast holds the data. This is the strongest moat in the category. *Implication for J'Toye:* the order matters — get payments live first, then card-sales-secured short-term financing, then payroll. Don't build payroll first.
2. **Workflow lock-in via integration depth.** Toast / Lightspeed / Olo all converged on POS + KDS + accounting + inventory + scheduling. Switching cost compounds with each module. *Implication:* J'Toye's KDS + ordering + marketing + payments bundle is on the right architectural arc.
3. **Network-effect data moats (chain-wide forecasting).** Crunchtime's forecast accuracy improves with chain-wide data; new operators benefit from cohort-level demand patterns. Single-tenant SaaS cannot replicate this. *Implication:* Multi-tenant RLS architecture (J'Toye already has it) is a precondition; explicit "anonymised cross-tenant insights" feature is the harvest.
4. **Two-sided marketplace flywheel.** Hardest to build, strongest moat once in place. Toast added consumer-side ordering features in 2024-2025 to tilt this way. *Implication:* J'Toye should be cautious — full marketplace takes 3-5 years and significant capital. The right intermediate move is a *vendor-side network* (cross-vendor loyalty, shared customer accounts on the storefront), not a full delivery marketplace.
5. **Compliance + regulation as moat.** UK-specific: Natasha's Law (allergens), Making Tax Digital (HMRC), Tip Allocation Code (Oct 2024). Each is a wedge against US-imported software. *Implication:* J'Toye's UK-native posture is undervalued; lean into it heavily in marketing.

---

## UK-specific dynamics

The UK hospitality sector contracted to **98,609 venues by March 2026** (-305 in Q1 2026 alone), with **3.4 net closures per day**. Independents are 22.7% smaller than pre-COVID. There are **176,685 hospitality businesses** total (March 2025), 99.6% SME, with **29,341 full-service restaurants** specifically; independents drove **66.85% of full-service spending** in 2025 (per Statista, IBISWorld, House of Commons Library 2025-2026 data). London alone has 11,400+ restaurants.

Cost pressures are structural: food & non-alcoholic beverage CPI +5.1% (Aug 2025), forecast 5.7% by Dec 2025 → 3.1% in 2026 (Food and Drink Federation). NIC + minimum-wage rises in the Autumn 2024 Budget hit hospitality disproportionately. Margins have collapsed from 15-20% pre-COVID to ~5%.

Tech adoption: **74% of UK operators use AI in some capacity, 99% report clear benefits**, but **60% of independents lack the capital or skills** to implement comparable systems to chains. This is the gap a vertical SaaS can fill — *if* the price point is sub-£200/mo for the core bundle and the payback is measurable in commission savings within one quarter.

---

## Strategic implications for a new UK food-SaaS entrant

**Where I would direct early-stage GTM and product investment, in priority order:**

1. **Lead with the anti-aggregator narrative, not features.** Every UK independent restaurateur is paying Deliveroo/Uber Eats 25-35% (effectively 30%+). A SaaS bundle priced at £99-149/mo + a small per-order fee that demonstrably moves 20% of orders from aggregator to direct ordering pays for itself in week 2. Land the customer with a single ROI calculator on the homepage, not a feature matrix.

2. **Stand up payments before any other monetisation.** Toast's revenue mix shows where this ends. Negotiate Stripe Connect interchange-plus terms now; revenue-share on interchange compounds as volume grows. Without payments, J'Toye is a sub-£200/mo SaaS in a market where the median tool is £72/mo (per PulseSignal 2026 survey) — you cannot reach $10k+ ARPU on subscription alone in this segment.

3. **Wedge into dark kitchens.** Karma Kitchen's 53-site UK/Europe expansion gives you a B2B distribution partner with thousands of multi-brand operators per year passing through the venues. They need exactly what J'Toye provides (multi-aggregator orchestration + KDS + direct ordering + Natasha's Law allergen PDFs) and they have no good incumbent option below the Deliverect/Toast tier.

4. **Pick your AI bets sharply.** Skip generic chatbots. Invest in: (a) on-prem Ollama image analysis (already done — extend to product photography auto-categorisation and allergen detection), (b) UK-accent voice ordering for phone-in takeaways (no UK incumbent has a credible product), (c) cohort-level demand forecasting from cross-tenant anonymised data once you have ≥200 active vendors. Defer voice drive-thru — it's a US/QSR-chain market.

5. **Build the lending product on day 365.** Once you have 12 months of card-sales data on ~500 vendors, launch a £1k-£25k merchant cash advance against future card receipts. This is the single largest value-creation move available — it transforms valuation multiple from 1.8x revenue (hospitality SaaS) toward 5-9x (vertical SaaS with embedded fintech).

6. **Stay disciplined on ICP.** The independent UK operator is in structural contraction. Hunt the *resilient* sub-segments: ethnic/specialty cuisine (Caribbean, West African, South Asian) where the brand is the operator and customers are loyal, dark kitchens (growth segment), and small chains (3-15 sites) that have outgrown spreadsheets but cannot afford Toast's per-terminal hardware lock-in. Avoid casual dining (the dying middle) and pure QSR (locked-in to chain franchisor systems).

The valuation maths is unforgiving: at 1.8x revenue, you need £20M ARR for a £36M exit. At 7-9x with embedded payments and lending, the same ARR is £140-180M. The product roadmap should be ruthlessly oriented toward shifting J'Toye's classification from "hospitality SaaS" (1.8x) to "vertical fintech with software wedge" (5-9x).

---

## Questions a board would ask the founder

1. What is your contractual answer to "Toast launches in the UK with a free Starter Kit"? (They will, within 18 months.)
2. What's the unit-economic payback when a vendor switches one Deliveroo order per day to direct? Show me the calculator and your A/B test data.
3. Why on-prem Ollama and not Bedrock/Vertex? What's the hidden infra cost per tenant and what's the SLA for inference outages?
4. Are you a SaaS company or a fintech? At what ARR does payments revenue exceed subscription revenue, and what does that do to your gross margin?
5. What is your defensible angle against Flipdish (Ireland, UK-strong, well-funded) and Slerp (UK-native)? Channel? Price? Vertical depth? "We're better" is not an answer.
6. Independent UK hospitality is shrinking 3+/day. Why is your TAM growing, not contracting? Show me the bottoms-up.
7. What's your CAC today, what is your projected CAC at 1,000 vendors, and at what NRR do you cross unit-economic break-even? (Vertical hospitality slowed >50% YoY in 2025 — be ready on this.)
8. Where in the Toast embedded-finance stack (payments → lending → payroll → cards → insurance) do you stop, and why? Each step requires a different licence, a different counterparty, and a different team.
9. If Thoma Bravo is consolidating the space (Olo at $2B, Jul 2025), who is your strategic acquirer at year 5, and what one capability are you building today specifically to be valuable to them?
10. What happens to the business model when Deliveroo / Uber Eats cut their commission rates by 5 percentage points in response to your traction? (They will.)

---

### Source list (with retrieval dates — all 2026-04-27)

- Toast Inc. Q4 2025 earnings call transcript — fool.com/earnings/call-transcripts/2026/02/12/toast-tost-q4-2025-earnings-call-transcript/
- Toast Inc. financials & 10-K — investors.toasttab.com/financials/quarterly-results
- Toast Capital + embedded-finance teardown — whitesight.net/reports/toast-b2b-embedded-finance-playbook/
- Lightspeed FY2025 results — lightspeedhq.com/news/lightspeed-announces-fourth-quarter-and-full-year-2025-financial-results
- Olo Q1 2025 release — investors.olo.com/news/news-details/2025/Olo-Announces-First-Quarter-2025-Financial-Results
- Olo Q2 2025 release — investors.olo.com/news/news-details/2025/Olo-Announces-Second-Quarter-2025-Financial-Results
- Olo / Thoma Bravo deal — pitchbook.com/profiles/company/54025-84
- 2025 SaaS NRR benchmarks — joinpavilion.com/resource/b2b-saas-performance-benchmarks; highalpha.com/blog/net-revenue-retention-2025
- 2025 LTV/CAC benchmarks — wearefounders.uk/saas-churn-rates-and-customer-acquisition-costs-by-industry-2025-data; lightercapital.com/blog/2025-b2b-saas-startup-benchmarks
- Vertical SaaS M&A multiples — saasrise.com/blog/the-saas-m-a-report-2025; aventis-advisors.com/saas-valuation-multiples; multiples.vc/reports/software-saas-valuation-multiples
- Toast pricing 2026 — pos.toasttab.com/pricing; merchantinsiders.com/blogs/toast-fees; upmenu.com/blog/toast-pricing
- Hybrid pricing data — saasmag.com/hybrid-pricing-saas-growth-2026 (cites Chargebee 2025 State of Subscriptions)
- Stripe Connect / interchange-plus — stripe.com/resources/more/interchange-plus-pricing-explained; fiska.com/blog/pricing-models-isv-payments
- UK delivery commission rates — merchantswitch.com/blog/delivery-platform-comparison-uk; blog.menuviel.com/deliveroo-fees-and-commissions-for-restaurants; payoutledger.co.uk/tools/commission-calculator
- Flipdish / Slerp — flipdish.com/us/pricing; slerp.com/compare/flipdish
- Olo D2C thesis — olo.com/blog/restaurant-trends-that-defined-2025-how-olo-powered-them
- UK / Europe dark kitchen market — coherentmarketinsights.com/industry-reports/europe-dark-kitchens-ghost-kitchens-cloud-kitchens-market; marketdataforecast.com/market-reports/europe-cloud-kitchen-market
- Karma Kitchen — karmakitchen.co; linkedin.com/company/karma-kitchen
- Foodstars — foodserviceequipmentjournal.com/cloud-pleasers (CloudKitchens acquisition 2019)
- Wendy's FreshAI / Presto / drive-thru voice — restauranttechnologynews.com/2026/01/presto-raises-10-million; fortune.com/2024/10/15/wendy-google-ai-drive-thru-expansion; restaurantbusinessonline.com/technology/presto-raises-10m
- Crunchtime AI 2026 — restauranttechnologynews.com/2026/04/crunchtime-expands-ai-capabilities; crunchtime.com/blog/6-must-have-ai-features-every-restaurant-needs
- Local LLM cost analysis — arxiv.org/html/2509.18101v1; freeacademy.ai/blog/local-llms-vs-cloud-llms-ollama-privacy-comparison-2026; dev.to/pooyagolchian/local-ai-in-2026-ollama-benchmarks
- UK hospitality closures — restaurantonline.co.uk/Article/2026/04/27/uk-hospitality-hits-three-closures-per-day; thecaterer.com/news/hospitality-closures-rise-sharply-in-2025; bmmagazine.co.uk/news/three-pubs-restaurants-shut-every-day
- UK hospitality count & SME mix — commonslibrary.parliament.uk/research-briefings/cbp-10333; researchbriefings.files.parliament.uk/documents/CBP-10333/CBP-10333.pdf; ibisworld.com/united-kingdom/industry/full-service-restaurants/3420
- UK restaurant tech adoption stats — restroworks.com/blog/uk-restaurant-industry-statistics

**Unverified / directional claims marked in body**: dark-kitchen ARPU sizing; UK-specific LTV/CAC for restaurant SaaS (used general SaaS proxy); Square for Restaurants ARPU (not separately disclosed by Block).
