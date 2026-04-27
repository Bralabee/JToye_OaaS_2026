# Commercial Remediation — From "Built Flipdish Clone" to "Sold to 10 Vendors"

**Pair**: Commercial Operator (specialist) × Sales Reality Reviewer (assistant)
**Date**: 2026-04-27
**Source critique**: `docs/audit/sources/10-commercial-critic.md`, `08-market-analyst.md`, `09-vertical-saas-strategist.md`
**Mandate**: turn the council's commercial verdict into ready-to-paste artifacts for the next 90 days.

---

## Operating principles

1. **Selling beats shipping.** Every artifact below has a buyer in mind and a closing motion. Where the council found "no customer-visible differentiator", the answer is not another feature — it is a 60-second script that gets a vendor to nod.
2. **Specificity over plausibility.** Door-knock script in actual quotes. Pricing copy as paste-able text. Calculator with a working formula. Anything mushier is theatre.
3. **Honest about founder-market fit.** If the founder is not personally embedded in the chosen community, we say so and design the credible substitute. We do not invent a backstory.
4. **Anti-aggregator wedge is the only narrative that survives 30 seconds.** Per the strategist: the only language that pays for itself in week two is "stop paying Deliveroo 30%". Everything else is prologue.
5. **No cuteness about brand names that do not yet exist.** We sketch trade-offs, we do not pick. The naming is a one-hour exercise after the first three vendors say yes — it is not the gating decision.
6. **Pre-prod blockers gate the GTM.** We do not put a pricing page live while three cross-tenant data leaks are open. The technical pairs (#1, #2) are upstream of this pair on the dependency graph.

---

## Finding 1 — ICP refinement: the actual 30 vendors

### Specialist proposal

The five London neighbourhoods, with reasoning:

- **Peckham (Rye Lane corridor, SE15)** — densest cluster of West African / Caribbean independents in London (Yelp / Google Maps category counts: ~80–120 establishments tagged "African", "Caribbean" or "Halal" within a 1-mile radius of Peckham Rye station). Heavy WhatsApp-ordering culture. Foot-traffic on Saturday afternoons is exactly when an owner is on-site.
- **Brixton (Electric Avenue / Atlantic Road, SW9 / SW2)** — Caribbean food heritage, mix of pre-pack takeaway and dine-in jerk-chicken / roti / patty shops. Walking distance from Brixton Underground; ~20-minute Overground ride from Peckham Rye, so Saturday route can chain Peckham → Brixton.
- **Tottenham (West Green Road and Seven Sisters Road, N15 / N17)** — strong African Caribbean cluster around Seven Sisters station; smaller per-shop footprints, often single-owner. Less direct adjacency to Peckham/Brixton, so a separate Sunday route.
- **East Ham / Upton Park (Green Street and High Street North, E6 / E7)** — South Asian and East African independents, halal heavy. Distinct cuisine cluster, useful for testing whether the wedge generalises beyond Caribbean. Walkable to Forest Gate and Plaistow.
- **Croydon (London Road and Thornton Heath end, CR0 / CR7)** — diverse halal / Caribbean / Sri Lankan; lower rents mean smaller operators, more amenable to a £39 SKU. A reasonable Tuesday-evening run-out from central London.

Walking-distance pairings for an efficient day:
- **Saturday route**: Peckham (10:00–13:00) → Brixton (14:00–17:00). One Tube hop, both peak browsing hours for vendors before evening service.
- **Sunday route**: Tottenham morning, then Croydon afternoon by Overground / Thameslink. Less efficient; consider splitting across two Sundays.
- **Tuesday early-evening**: East Ham 16:00–19:00 — pre-iftar / pre-evening-service window when owners are on-site but not yet slammed.

Vendor archetypes (5–10 per neighbourhood; described, not named):

1. Halal Caribbean takeaway with WhatsApp ordering and 1–2 staff, average ticket £9–14.
2. West African (Nigerian / Ghanaian) cooked-food shop with hot-counter display and Friday/Saturday surge.
3. Single-owner roti / patty shop, dine-in seating for 6–10, mostly walk-up.
4. Halal grill (Turkish / Lebanese / Somali variants) with phone orders and a Deliveroo tablet on the counter.
5. South Asian sweets-and-savouries shop with PPDS labelling pain (Natasha's Law) — the allergen wedge lands hardest here.
6. Vegan / plant-based African (e.g. ital, jollof variants) with Instagram following but no website — under-served by Toast.
7. Sri Lankan / Tamil hopper-and-kothu shop, family-run, Sunday-busy.
8. Halal fried-chicken shop, after-school surge, currently using paper tickets and one Deliveroo screen.
9. Suya / asun street-food spot, evenings only, cash-heavy.
10. Multi-cuisine "African corner shop with hot food" hybrid — borderline ICP; flag and screen carefully.

Total addressable in this list across all five neighbourhoods, before screening: ~300–500 vendors. Door-knock target: **30** (6 per neighbourhood, ~20% conversation rate, target 10 design-partner verbal commits).

### Assistant deliberation (challenge)

Three substantive pushbacks:

1. **You are conflating cuisine cluster with willingness-to-buy.** Peckham has 80+ African / Caribbean food businesses on Yelp; that does not mean 80 of them will talk to a stranger about software. A more honest screening criterion is "vendor has a Deliveroo tablet visible from the street AND a WhatsApp number on the menu". That probably halves the list. Acknowledge it.
2. **Saturday afternoon is the worst time to door-knock a takeaway**, not the best. Owners are prepping for evening service, the kitchen is already loud, and the front-of-house person is the partner who cannot make decisions. **Best windows**: Tuesday or Wednesday 14:30–16:30 (post-lunch lull, owner is the one at the till), and Sunday 11:00–13:00 in Caribbean / African contexts where Sunday service starts late. Rewrite the route plan accordingly.
3. **East Ham is a different ICP**, not a generalisation test. South Asian halal independents in E6 have a different decision-maker (often the male owner is the "Stripe-savvy son", not the chef), different ticket sizes, different language preferences. Including it in the same 30-vendor sprint dilutes learning. **Drop East Ham from the v1 sprint**; pick it up in week 9–12 if Caribbean / West African converts.

The assistant also flags: do not promise this is "underserved by Toast" without evidence. Toast has a UK sales team. The honest claim is "Toast prices itself above £150/mo all-in; this segment will not pay it". That is a price wedge, not a coverage wedge.

### Reconciled position

- **Three neighbourhoods in v1 sprint, not five**: Peckham, Brixton, Tottenham. All Caribbean / West African heavy. Walking / one-hop transit between them.
- **Door-knock windows**: Tuesday + Wednesday afternoons (14:30–16:30) and Sunday late mornings (11:00–13:00). Saturday is for follow-up only.
- **Screening filter before door-knock**: vendor must have (a) visible Deliveroo / Uber Eats / Just Eat presence AND (b) a phone-orderable menu (WhatsApp number, Instagram bio, or printed phone-order menu). Use Google Maps + Deliveroo / Uber Eats listings to pre-build a hit list of ~50 vendors before the first walk.
- **East Ham deferred to v2 (week 9–12)** if v1 converts. Croydon deferred similarly.
- **30-vendor target across 6 walking days** (3 weekday afternoons + 3 Sundays over 3 weeks) — 5 vendors per day is realistic at 20–30 minutes per stop.

---

## Finding 2 — Door-knock script

### Specialist proposal

**The 60-second opener** (door is open, owner is at the till, no queue):

> "Hi, sorry to interrupt — quick question. Are you on Deliveroo? [Wait.] Right, what do they take, about a third? I'm building a really small thing for shops like yours where customers order on WhatsApp like they already do, it lands on a screen in the kitchen, and they pay by card up front. Forty-nine pounds a month, no commission. Have you got two minutes for me to show you on my phone?"

Rules: do not say "platform". Do not say "SaaS". Do not say "multi-tenant". Do not pitch the founder. Pitch one number (commission saved) and one workflow (WhatsApp → kitchen → paid).

**The 3-minute demo flow** (phone only, no laptop, no slide deck — see Finding 7 for the 4-slide deck on the phone):

1. Open WhatsApp on your phone, send a message that looks like a customer order ("2 jerk chicken meals + 1 plantain side, ready 7pm, delivery to SE15 4ST"). Show that arrives.
2. Switch to the kitchen-display browser tab, show the order land in real time, status moving from NEW → PREPARING.
3. Show the Stripe Checkout link the customer would have received, the receipt, the £0 commission line.
4. Go back to the home screen of your phone, swipe the calculator app open: "If you do 50 Deliveroo orders a week at £15 average, Deliveroo takes [50 × 15 × 0.30 × 4 = £900] off you a month. We take £49 plus card fees of about £45. You keep £800 and own the customer." (See Finding 5 for the actual calculator.)

Total time including questions: ~5 minutes. If the owner has stopped you at minute 1 to take a customer, leave a one-pager (Finding 6 letter, condensed) and your phone number, then leave.

**Objection handling — the five most likely** (real words):

- **"I already have a website."**
  > "Brilliant — does it take card payments and send the order to your kitchen, or is it just a menu page? [Almost always: just a menu.] Right, so what I'm offering is the bit between your customer and your kitchen, not another website to maintain. Your existing one keeps doing what it does."
- **"I don't trust online ordering."**
  > "Totally fair — what does the customer do today, calls or WhatsApp? [Yes.] So we keep WhatsApp, we just add a card-payment link so they pay before you cook. You don't lose the chicken if they ghost. That's the actual value, not the technology."
- **"Stripe takes too long to pay out."**
  > "Standard Stripe is two business days; that's the same as Deliveroo's payout cycle. You can also enable instant payout in Stripe for 1% extra. I can show you on the screen what your cashflow looks like — want me to walk through it?"
- **"I'm too busy to learn another tool."**
  > "I know. So here's the deal: for the first six months I set it up for you, train your one staff member, and it's free. Month seven you decide if it's worth £49. If you don't use it, you don't pay. The only thing I ask is monthly feedback." (Tied to Finding 6.)
- **"What if I want to leave?"**
  > "Export your customer list, your menu, and your order history any time as a CSV. No lock-in. If you cancel, your data is yours — you can hand it to whoever you go to next. That's a written promise on the website." (Tied to Finding 4 trust signals.)

**The closing ask**:

> "Would you give me 30 minutes next Tuesday to set you up as one of the first ten? I cover all the setup, you keep the data, six months free."

If yes: book the meeting in the calendar **on the spot**, screenshot the calendar invite, and email it before leaving the shop. If "let me think" — **do not chase by email**, return in person the following Tuesday. Email follow-ups in this segment go unread.

**The 30-second version** (owner is mid-service, no time):

> "I'm building WhatsApp ordering for shops like yours, no Deliveroo commission, £49 a month. First ten places get six months free. Can I leave you my number and pop back Tuesday?" — slide one-pager across counter, leave.

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **The opener assumes the owner speaks English fluently as a first language.** In Peckham and East Ham, plenty of owners do not. The script's pace ("Have you got two minutes for me to show you on my phone?") will read as fast and pushy. Slow it. Lead with a smile, "Are you the owner?" first, and have the WhatsApp demo ready to **show without speaking** if needed. Visual demo > verbal pitch in this segment.
2. **"What do they take, about a third?" is too clever.** It asks the vendor to confirm a number that they may not know off the top of their head, and if you are wrong they correct you and you lose authority. Better: "Deliveroo's pretty brutal on margin, right?" — invites agreement without quizzing them.
3. **The "six months free" close is too generous and too cheap simultaneously.** Too generous because it gives away the year's revenue from your first ten customers (10 × £49 × 6 = £2,940 forgone — manageable) AND signals "I am desperate". Too cheap because the offer is a foot-in-the-door with no commitment device — they accept "free" and never log in. **A token £19/month from day one** (see Finding 6) is a stronger commitment signal even though it surfaces less cash. Reconcile this in Finding 6, but reflect it in the script: drop "six months free" and replace with "first ten places get the founder pricing forever — £19 a month, locked in".

The assistant also flags: the script has zero contingency for the owner saying "I don't have a kitchen, I'm just front-of-house". Add: "Is the owner around?" before launching, and if not, leave the one-pager + book a return visit when they are.

### Reconciled position

- Lead with "Are you the owner?" Always.
- Drop the "what do they take, a third?" gambit. Replace with "Deliveroo and Uber, they're brutal on margin, right?"
- Replace "six months free" with the founder-pricing offer (£19/mo locked, see Finding 6). Cleaner commitment signal, less "desperate-founder" smell.
- Carry a printed one-pager (Finding 6) on every door-knock. If the owner is unavailable or busy, the paper does the work and you book a return visit.
- Slow the demo. Show, do not say, where possible. Aim for 4 minutes of demo, not 3.
- Track conversion in a paper notebook by neighbourhood, day, and reason for "no". This is the data that tells you whether to pivot to East Ham or stop the door-knock entirely (see Finding 11).

---

## Finding 3 — Founder narrative

### Specialist proposal

Three paragraphs, ~270 words, deliverable in 90 seconds:

> **Why me.** I am a senior software engineer who has spent the last eighteen months building this platform alone — multi-tenant, payments, kitchen-display, allergen-PDF generation, the lot. I am not a restaurateur. I am the technical co-founder who is missing the operator co-founder, and rather than fake the operator credentials I am spending the next ninety days as a free consultant to ten vendors in Peckham, Brixton and Tottenham. They get my product, my time, and my Stripe expertise; I get the customer-development conversations I need to either earn this market or learn that I should not be in it.
>
> **Why now.** UK delivery aggregators are pushing 30%-effective commissions in a sector where margins have collapsed from 15–20% pre-COVID to about 5%. The independent who used to absorb Deliveroo's cut now cannot. Natasha's Law is in its fifth year and the FSA expanded guidance in March 2025; PPDS allergen labelling is a £5,000-per-breach fear that Toast and Square handle generically. The Olo / Thoma Bravo deal in July 2025 priced "own the customer relationship" at $2B. The wedge is open and the buyer is desperate.
>
> **Why this niche.** UK Caribbean / West African independents have a buying culture (40–60% of orders via WhatsApp today, often handled by a partner with a notebook) and an incumbent reality (Toast and Flipdish have generic templates that culturally do not fit, and price above what the segment will pay). I am building for the shop where my Saturday lunch comes from, not for a TAM slide. The first ten vendors are the moat; everything after is the company.

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **"I am not a restaurateur" said directly is honest but kills the room in front of a halal Caribbean takeaway owner.** That is fine in front of an investor (it is the right honesty signal). It is wrong in front of a vendor. **Bifurcate: investor narrative is the three paragraphs above; vendor narrative is "I'm building this for places like yours, here's how it works". Vendors do not need to hear your origin story; they need to see the kitchen-display work.**
2. **"My Saturday lunch comes from" is the cliché the audit warned about.** Every food-tech founder on LinkedIn has a "I love jollof" story. If the founder genuinely does eat in Peckham every Saturday, that is testable — name the actual shop in conversation when relevant, do not put it in the deck. Cut the line from the written narrative.
3. **The "free consultant for 90 days" framing is good, but it must be backed by a calendar.** If the founder is doing this nights-and-weekends with a day job, ten vendors at one onboarding hour per week is forty hours a month — that is the day job's energy budget. Be honest in the narrative: "I have allocated three months of evenings and weekends to this; if I cannot sign ten by week twelve I will conclude founder-market fit is not there and stop." **That hard gate is more credible than passion.**

The assistant also flags: do **not** name a co-founder you do not have. If a community partner is the credible substitute (paragraph one's "operator co-founder I am missing"), find them in week 1 — do not ship a deck that implies they exist.

### Reconciled position

- Two narratives, two audiences: vendor-facing (one-page leave-behind, no founder backstory) vs investor-facing (the three paragraphs above).
- Cut the "Saturday lunch" line. Replace with the time-budget honesty: "I have committed evenings and weekends through July 2026 — if I cannot sign ten paying vendors by week twelve I will halt, not raise."
- The "operator co-founder" substitute strategy: identify in week 1 a community figure (food-blogger, EHO consultant, or vendor-network operator) willing to do an introduction round. The substitute is **named publicly** in the investor narrative or it is **omitted**. No vapour partners.
- Keep the Olo / Thoma Bravo data point — it is the strongest "why now" anchor and survives investor scepticism.

---

## Finding 4 — Pricing page copy

### Specialist proposal

Paste-ready text (HTML semantics inferred; designer can re-skin):

**H1**: Stop paying Deliveroo 30%. Take orders on WhatsApp, get paid by card, keep your customers.

**Sub-headline**: The ordering and kitchen-display platform built for UK independents who already take orders on WhatsApp. No commission. No long contract. Six-month founder pricing for the first ten vendors.

**Tier cards**:

| | **Starter — £39 / month** | **Growth — £89 / month** | **Multi-site — £179 / month per location** |
|---|---|---|---|
| Built for | Single-location takeaways and cafés | Single-location growing shops | Two or more locations under one owner |
| WhatsApp orders into kitchen display | Yes | Yes | Yes |
| Stripe card checkout (1.5% + 20p, at cost) | Yes | Yes | Yes |
| Natasha's Law allergen PDFs | Yes | Yes | Yes |
| Customer accounts and re-order | — | Yes | Yes |
| Promotions and announcements | — | Yes | Yes |
| Multi-staff KDS roles | — | Yes | Yes |
| Cross-location dashboard | — | — | Yes |
| Xero / QuickBooks export | — | — | Yes |
| **No per-order commission, ever** | Yes | Yes | Yes |

**CTA button text** (primary): "Start free for 6 months" / (secondary, ROI calculator) "See what you'd save vs Deliveroo".

**Comparison block** (data anchored to council audit incumbent pricing):

> | Platform | Monthly | Per-order commission | Realistic all-in / month |
> |---|---|---|---|
> | **Toast UK** | £80–£150 | 2.49% + 15p in-person | £200–£400 |
> | **Flipdish** | £119+ | 50p collection / 70p delivery | £300–£450 (at 500 orders) |
> | **Square for Restaurants** | £69+VAT | 1.69% + 20p | £150–£250 |
> | **Deliveroo (direct, not SaaS)** | £0 | 25–35% take rate | £900+ for 50 orders/wk × £15 |
> | **J'Toye Starter** | £39 | None | £39 + Stripe pass-through |
>
> Sources: Toast UK (2026), Flipdish UK pricing page (2026), Square for Restaurants UK (2026), MerchantSwitch UK delivery commission comparison (2025).

**FAQ section** (10 Q&A):

1. **Do you take a per-order commission?** No. Stripe's card fee (1.5% + 20p in the UK, passed through at cost) is the only per-order cost. We do not mark it up.
2. **What happens to my data if I leave?** You can export your customer list, menu, order history and allergen records as CSV at any time. There is no lock-in. If you cancel, your data is yours.
3. **How does the WhatsApp ordering work?** Customers message your existing WhatsApp Business number. We parse the message into an order, send a Stripe payment link, and once paid the order lands on your kitchen display. You confirm "preparing" and "ready" with one tap.
4. **Do I need new hardware?** No. Any tablet, laptop or phone with a browser is your kitchen display. We will help you set up your existing tablet on day one.
5. **Will this print my Natasha's Law allergen labels?** Yes. The platform generates PPDS-compliant PDFs from your menu items, with the 14 statutory allergens highlighted. Compatible with most label printers; we will help you wire yours.
6. **What about Deliveroo / Uber Eats / Just Eat?** Most vendors keep them running while shifting their best customers to direct ordering. We do not currently sync menus to aggregators. We are building it for late 2026 if customers ask.
7. **How long does Stripe take to pay out?** Two business days by default — the same as Deliveroo. Stripe Instant Payout (1% extra) is available if you need same-day cashflow.
8. **What if my staff cannot use a tablet?** Onboarding includes a one-hour training visit for the first ten vendors. We bring the tablet, set it up, train your one staff member, and you keep our number.
9. **Do you support languages other than English?** Customer-facing storefront supports English today. Yoruba, Twi, Urdu and Arabic UI is on the roadmap and will land before any Tier 4 features.
10. **What is the founder offer for the first ten vendors?** Six months free. After month six, £19 / month locked in for 24 months — half the public Starter price. In return: a 30-minute monthly feedback call and permission to use your shop name on our website. Terms in the design-partner letter.

**Trust signals** (footer block): "PCI-compliant via Stripe. GDPR-registered with the ICO. UK-hosted (London region). Stripe verified partner. No personal data leaves the UK." (Note: only ship the badges that are actually true on the day the page goes live. The assistant flags any hand-wave below.)

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **"GDPR-registered with the ICO" is the kind of badge a vendor sees and trusts — and that the ICO will fine you for if you are not on the public register.** ICO registration is a £40–£60/year fee and a public listing. Do it before the page goes live, or remove the badge. Same for "Stripe verified partner" — Stripe Connect platform partnership has a real application; do not claim it until accepted.
2. **The pricing tiers leave money on the table.** £39 → £89 is a 128% jump for "promotions, announcements, customer accounts, multi-staff KDS". Promotions and customer accounts alone justify £89, but the £89 buyer is the same vendor as the £39 buyer — it is the segment's median ARPU sitting at £39 because Starter has WhatsApp ordering and KDS already. **Recommendation: move WhatsApp ordering and KDS to a £49 floor (which is also the council-recommended ARPU), keep Starter at £39 as a "card-payments-only" gateway SKU. That makes Growth a meaningful upsell and protects margin.** Specialist accept conditional on whether the segment will actually buy a £49 floor (see reconciled below).
3. **"Multi-site at £179/month per location" is positioned wrong for the segment.** A vendor with three sites in Peckham / Brixton / Tottenham pays £537/month — comfortably above Toast Starter (£80) per location. Multi-site is not the v1 buyer; it is a v2 conversation after a single-location vendor expands. **Cut the third tier from the launch page**, replace with "Multiple locations? Talk to us." The third tier exists in the codebase but does not appear on the public pricing page until there is a multi-site customer to anchor it.

The assistant also flags: ten FAQs is the right length (any more reads as defensive) but #6 ("we are building it for late 2026") is a forward promise. **Soften to "if our vendors ask, we will build it" and do not date it.**

### Reconciled position

- **Tier structure**: keep three tiers in the codebase, show two on the public page.
  - **Starter — £39/mo**: card checkout, kitchen display, allergen PDFs (no WhatsApp orders).
  - **Growth — £89/mo**: adds WhatsApp orders, customer accounts, promotions, multi-staff KDS.
  - **Multi-site — talk to us**: hidden behind a "Multiple locations?" link.
- This frames Growth as the obvious choice (the WhatsApp wedge sits there) and Starter as a downsell defence against "I just want card payments". Net ARPU likely £79 average, not £49.
- **Founder offer is separate**: first ten vendors get Growth at £19/mo locked for 24 months (effectively a 79% discount on the wedge SKU). The pricing page mentions it; the design-partner letter (Finding 6) carries the terms.
- **Trust signals**: only ship badges that are true on launch day. Block the page launch on (a) ICO registration completed, (b) Stripe Connect application status decided. Do not ship badges as aspiration.
- **FAQ #6 softened** to "we have not built aggregator sync; if our vendors push for it we will".
- **Comparison block keeps the Deliveroo line** — it is the only line a vendor actually emotionally responds to. The other rows are context.

---

## Finding 5 — Anti-aggregator ROI calculator

### Specialist proposal

**Inputs**:
- Orders per week from Deliveroo / Uber Eats (default: 50, range 5–500).
- Average order value in £ (default: 15, range 5–60).
- Current effective aggregator commission rate (default: 30%, range 14–35%).
- (Hidden, defaulted) Assumed share of those orders that shift to direct ordering after switch (default: 25%, range 10–40%).

**Outputs**:
- Monthly aggregator commission cost today.
- Monthly J'Toye cost (subscription + Stripe pass-through fees on shifted orders).
- Monthly net saving.
- Annual saving.
- "Pays for itself after X orders" headline number.

**Formulas** (monthly basis, 4.33 weeks per month):

```
weekly_aggregator_cost  = ordersPerWeek × avgOrder × commissionRate
monthly_aggregator_cost = weekly_aggregator_cost × 4.33

shifted_orders_per_month = ordersPerWeek × shiftPct × 4.33
stripe_fees              = shifted_orders_per_month × (avgOrder × 0.015 + 0.20)

jtoye_monthly_cost = 89 + stripe_fees                  // Growth tier
remaining_aggregator_cost = monthly_aggregator_cost × (1 − shiftPct)
total_after = remaining_aggregator_cost + jtoye_monthly_cost

monthly_saving = monthly_aggregator_cost − total_after
annual_saving  = monthly_saving × 12
```

**Defensible shift assumption**: 25% of aggregator orders move to direct ordering within six months. Justification: Olo's investor materials cite ~30% average direct-channel mix for established white-label customers (Olo Q1/Q2 2025 release). Slerp positions on similar numbers ("0% commission on direct"). The 25% figure is **below** Olo's mature-customer average to reflect (a) UK independent's smaller marketing reach versus Olo's enterprise customer base, (b) first six months of switching, not steady-state. Floor the slider at 10% to discourage optimism; ceiling at 40% to discourage fantasy.

**Vanilla JS implementation** (paste into the landing page):

```html
<form id="roi-calc" onsubmit="return false;">
  <label>Orders per week from Deliveroo / Uber Eats:
    <input type="number" id="opw" value="50" min="5" max="500" />
  </label>
  <label>Average order value (£):
    <input type="number" id="aov" value="15" min="5" max="60" />
  </label>
  <label>Current effective commission rate (%):
    <input type="number" id="rate" value="30" min="14" max="35" />
  </label>
  <details>
    <summary>Advanced: assumed % of orders that shift to direct (default 25%)</summary>
    <input type="number" id="shift" value="25" min="10" max="40" />
  </details>
  <button type="button" onclick="calcRoi()">Calculate</button>
  <output id="roi-out"></output>
</form>

<script>
function calcRoi () {
  const opw   = +document.getElementById('opw').value;
  const aov   = +document.getElementById('aov').value;
  const rate  = +document.getElementById('rate').value / 100;
  const shift = +document.getElementById('shift').value / 100;

  const weeksPerMonth   = 4.33;
  const ordersPerMonth  = opw * weeksPerMonth;
  const aggCostMonthly  = ordersPerMonth * aov * rate;

  const shiftedOrders   = ordersPerMonth * shift;
  const stripeFees      = shiftedOrders * (aov * 0.015 + 0.20);
  const jtoyeMonthly    = 89 + stripeFees;
  const remainingAgg    = aggCostMonthly * (1 - shift);
  const totalAfter      = remainingAgg + jtoyeMonthly;

  const monthlySaving   = aggCostMonthly - totalAfter;
  const annualSaving    = monthlySaving * 12;
  const breakEvenOrders = Math.ceil(89 / (aov * rate));

  document.getElementById('roi-out').innerHTML = `
    <p>Today you pay aggregators about <strong>£${aggCostMonthly.toFixed(0)}/month</strong>
       in commission.</p>
    <p>With J'Toye you'd pay <strong>£${jtoyeMonthly.toFixed(0)}/month</strong>
       (subscription + Stripe pass-through), plus residual aggregator commission
       of <strong>£${remainingAgg.toFixed(0)}/month</strong> on the orders that stay there.</p>
    <p><strong>Monthly saving: £${monthlySaving.toFixed(0)}</strong></p>
    <p><strong>Annual saving: £${annualSaving.toFixed(0)}</strong></p>
    <p>The £89 subscription pays for itself after about
       <strong>${breakEvenOrders}</strong> direct orders per month.</p>
  `;
}
calcRoi();
</script>
```

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **The 25% shift assumption is the pivot of the whole pitch and you cited Olo enterprise numbers as the floor.** Olo's customers are Sweetgreen, Dave's Hot Chicken, etc. — brands with national marketing budgets. A Peckham takeaway with 800 Instagram followers will not shift 25% in six months. **A more honest default is 15%, with the 25% figure exposed only behind the "advanced" disclosure.** The pitch still works at 15% (annual saving still £1,000+ for a busy vendor) and the founder cannot be accused of fudging.
2. **The calculator hides the Stripe fees inside `jtoyeMonthly`.** A vendor reading the output sees "£135/month" and may think the subscription is £135. Break it out: "£89 subscription + £46 Stripe fees on the orders we processed for you". Transparency is the only trust signal that matters.
3. **There is no defence for the case where the vendor is doing 5 orders/week × £8 average — i.e. a tiny shop where the maths does not save anything.** At 5 × 8 × 0.30 × 4.33 = £52/month aggregator cost, J'Toye costs more than Deliveroo. **The calculator should display a "Is this for me?" honesty card when monthly aggregator cost is below £150**: "At your current volume, J'Toye does not save you money on commission alone — but you'd own the customer relationship and meet Natasha's Law for free. Talk to us about the £39 Starter tier instead." Honesty here is the brand.

The assistant also flags: do **not** ship the calculator without an A/B logger. Capture inputs anonymously (no PII) so you learn what the actual segment is plugging in. That data is more valuable than the calculator.

### Reconciled position

- Default `shift` to **15%**, not 25%; advanced section can move to 25% with explicit copy "if you market direct ordering aggressively".
- Break out Stripe fees as a separate line in the output. Transparency.
- Add the "honesty card" when monthly aggregator cost < £150: redirects to Starter tier.
- Add anonymous input logging (orders/week, AOV, rate — no PII) so the founder learns the actual segment shape from week 1. Use a simple POST to `/api/roi-events` (Spring controller, append-only). Useful for the investor narrative ("we have N inputs from real vendors").
- Surface the Stripe pass-through assumption (1.5% + 20p) inline so a savvy buyer can verify. Hidden assumptions kill conversion.

---

## Finding 6 — First-30-vendors design-partner offer letter

### Specialist proposal

200–400 word letter, paste-ready (printed on A5, leave-behind):

> **The First-Ten Founder Offer**
>
> I'm building J'Toye — WhatsApp ordering, kitchen display, card payments and Natasha's Law allergen labels — for independent UK food shops. The first ten vendors who say yes get a deal that won't be on the website:
>
> **What you get**
>
> - Six months free, end-to-end. I come to your shop, set up your tablet, train your one staff member, wire your existing WhatsApp Business number, generate your first allergen labels and walk you through the Stripe Checkout flow.
> - From month seven, £19 / month locked for 24 months. That's about a fifth of the £89 public price. Your rate cannot go up.
> - Direct WhatsApp access to me for the entire 24 months. If something breaks at 7pm on a Friday, you message me. I answer.
> - A written "your data, your call" promise: full CSV export of customers, menu and orders any time. Cancel and walk; we will not hold your data.
>
> **What I ask in return**
>
> 1. A 30-minute call once a month so I learn what is actually working and what is broken. No more, no less.
> 2. Your shop's name and one quote on the J'Toye website after 90 days of using it. Veto rights — you approve every word before it goes live.
> 3. One warm introduction per quarter to another vendor in your community who you think this might help. Not cold leads — people you already know.
>
> **Why I am doing this**
>
> I am a software engineer, not a restaurateur. The fastest way to build software that actually fits your shop is to spend the next ninety days inside ten shops, listening. The £19 / month from month seven is not the business model — it is the discount you earn for being patient with the version 1.
>
> If this sounds fair, give me 30 minutes next Tuesday. My number is below.
>
> [Founder name] · [WhatsApp / phone] · [email]

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **"Six months free" plus "£19 from month seven" is two prices, not one.** The vendor reads "free" and tunes out. A token £19 from day one — same money over 24 months — is a stronger commitment device. **The recommended structure: £19 / month from day one, locked for 24 months. No "free trial" framing.** A vendor who will not pay £19 will not show up to the monthly feedback call either.
2. **The "one warm introduction per quarter" ask is too soft.** Vendors will agree and never deliver. **Make it a binary contractual checkbox**: "I will introduce you to one other vendor I know within 60 days of going live, by walking you to their shop." If the founder cannot get this commitment in writing, the design-partner relationship will not produce referrals.
3. **The "veto rights on every word" promise is the right ethics but operationally expensive.** It means three weeks of legal back-and-forth per quote at scale. **Cap it: vendor approves the quote once, then has 7 days to revoke or amend.** Otherwise the founder spends Saturday afternoons chasing approvals instead of door-knocking the next ten.

The assistant also flags: this offer does not say what happens at month 25 when the £19 lock expires. Be explicit — "from month 25 the standard Growth price (£89) applies, with three months' notice". A vendor who feels rug-pulled in two years tells the whole community.

### Reconciled position

- **Token £19/mo from day one**, locked 24 months. Drop "six months free". This is the core change.
- Warm introduction commitment is a written commitment with a 60-day deadline and a "walk to their shop" specificity.
- Quote-approval window: 7 days to amend or revoke after first sign-off. Document on the website.
- Month-25 transition: standard Growth pricing (£89) applies with three months' written notice. Disclose this in the letter.
- The letter is the artifact the door-knock script (Finding 2) hands over. The two are the same offer in two formats.

---

## Finding 7 — WhatsApp ordering pitch deck (4 slides on the founder's phone)

### Specialist proposal

Slide 1 — **The hook**.
- Headline: "Your customers already text you orders on WhatsApp. Right?"
- Visual: a screenshot of a WhatsApp chat with a real-looking order ("Hi babe can I get 2 jerk chicken meal + 1 plantain ready 7pm 4ST"). Tap-to-zoom.
- Speaker note (founder says): "What does that look like for you on a Friday night? Five of these at once?"

Slide 2 — **The fix**.
- Headline: "Now that order pays itself, plates itself in your kitchen, and you keep the customer."
- Visual: split-screen — left side WhatsApp chat with a "Pay £18.50 here" Stripe link, right side a kitchen-display screenshot with the order in PREPARING.
- Speaker note: "Same WhatsApp. Plus a card link they pay first. Plus a screen that tells you when to start cooking."

Slide 3 — **The maths** (the calculator output, screenshotted with the vendor's own Deliveroo numbers if they will share them).
- Headline: "You'd save about £[X] a month versus Deliveroo."
- Visual: the ROI calculator output card with the vendor's numbers plugged in live in the shop.
- Speaker note: "Forty-nine pounds a month. No commission. Stripe takes one and a half percent on the card, same as everyone."

Slide 4 — **The ask**.
- Headline: "First ten vendors get £19/month locked for two years. Are you in?"
- Visual: the founder offer letter (Finding 6) with the £19 number circled. A calendar icon next to "Next Tuesday, 30 minutes".
- Speaker note: "Can I come back Tuesday with a tablet and set you up?"

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **Slide 1's WhatsApp screenshot must not look fake.** A vendor with twenty years of taking WhatsApp orders will spot a polished mock in two seconds and lose trust. **Use an actual screenshot from a real conversation (with a friend, fully consented) — the typos, the grocery emojis, the misspellings are the point.** Polished demos sell to investors; messy real screenshots sell to operators.
2. **Slide 3 requires you to ask the vendor "how many Deliveroo orders do you do?" in the door-knock.** Most will not tell you. Have a fallback: pre-load the calculator with three persona profiles (50 orders/week × £15, 100 × £12, 25 × £18) and let the vendor pick which one is closest. Easier than asking for numbers cold.
3. **Slide 4's "are you in?" is a closing line that requires the previous three slides to have landed.** If the conversation is going badly by slide 2, do not push to slide 4. **Have a fallback "soft close"**: "OK, here's my number. I'll be in Peckham next Tuesday — text me if you want me to swing by." Keep the relationship warm; do not force a no.

The assistant also flags: a 4-slide phone deck is the right length but only if the vendor can see your screen. A 65-year-old owner with reading glasses will need either a printout (the one-pager) or a tablet, not your phone. **Carry both.**

### Reconciled position

- Real WhatsApp screenshots from real (consented) conversations. No mocks.
- Pre-load three persona ROI examples for vendors who will not share their Deliveroo numbers.
- Slide 4 has a soft-close fallback for conversations that did not land — preserve the door for return.
- Carry both phone and printed one-pager. The printed one-pager is Finding 6 trimmed to A5.
- Build the 4 slides in plain HTML on the founder's phone (mobile-first frontend already exists). This is a one-day build, not a Figma deck project.

---

## Finding 8 — Natasha's Law allergen labels SKU

### Specialist proposal

A standalone SKU at **£19/month** — sold to the same buyer (compliance officer, owner) but explicitly **without** the WhatsApp ordering or KDS.

**Pricing page block**:

> **Allergen Compliance — £19 / month**
>
> Auto-generate Natasha's Law (PPDS) labels for every menu item. Highlight the 14 statutory allergens. Print on any 60mm thermal label printer. Audit log of every label run, signed and time-stamped, ready for an EHO visit.
>
> Includes: PPDS-compliant PDF generation; ingredient list authoring with allergen prompts; label printer integration (recommended models listed); audit trail with CSV export; FSA guidance updates baked in.
>
> Best for: bakeries, sweet shops, sandwich shops, and any independent doing pre-pack-for-direct-sale where a £5,000 EHO fine would hurt.

**Distribution channels** (ranked by founder access cost):

1. **Direct outreach to EHO consultants** — there are ~40 UK independent EHO / food-safety consultants who advise SMEs on Natasha's Law compliance. They know vendors who are scared of a fine. Offer them a 20% recurring commission for referrals. Find them on the Chartered Institute of Environmental Health (CIEH) directory and via LinkedIn.
2. **Bakery-supplier partnerships** — companies selling label printers (Brother, Zebra, Dymo via UK distributors) have customer lists of small food businesses already buying labelling hardware. Pitch a co-marketing arrangement.
3. **Local council / EHO newsletter placement** — many borough EHO teams send periodic compliance newsletters to registered food businesses. Often free or low-cost to place a short advertorial.
4. **Trade press placement** — *British Baker*, *Bakery Industry*, *Restaurant Magazine*. £200–£800 per insertion; targeted.

**Could this be the wedge instead of WhatsApp ordering?**

Specialist's view: **possibly yes**, and it is the single most underrated finding in the council audit. Allergen compliance has:
- A higher-pain buyer trigger (£5k fine fear is sharper than "Deliveroo is taking too much").
- A wider TAM (every PPDS food business in the UK, not just takeaways with WhatsApp culture).
- A cheaper sales motion (B2B referral via consultants vs. door-knock).
- A built-in upsell path (allergen → menu authoring → online ordering → KDS).

The argument against: it is a smaller monthly ARPU (£19 vs £49–89), so 200 vendors at £19 is £45,600 ARR — half the WhatsApp wedge at the same vendor count. And the buyer (compliance-anxious owner) is a different persona than the operator — slower to adopt new tools.

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **The £19/month price for allergen labels is too low if the alternative is a £5,000 fine.** Compliance products price as a fraction of the avoided cost, not as a Stripe convenience. **Try £29/month.** If a vendor says "too much for a label printer", the conversation reveals they were never going to buy. £29 × 200 = £69,600 ARR — meaningfully different.
2. **EHO consultants do not refer for 20% commission. They refer when the product makes their advice easier.** Reframe as "we plug into your audit workflow — your client gives you read-only access, you sign off compliance from your laptop". Build the consultant-side dashboard before chasing referrals; otherwise the channel never opens.
3. **You are about to start two go-to-market motions in parallel** (door-knock for WhatsApp + B2B referral for allergens). That is exactly what the audit warned against. **Pick one. Run the other in week 13 if the first hits its gate.** Trying both in 90 days dilutes both.

The assistant's own answer: **WhatsApp ordering is the right v1 wedge** because (a) the founder is geographically present in the buyer's neighbourhood, (b) the demo is dramatic ("watch this order arrive"), (c) the upsell path from allergens to ordering is harder than ordering to allergens. Allergen labels become the **horizontal expansion in month 4–6**, not the v1 wedge.

### Reconciled position

- **Allergen labels deferred to v2 (week 13+)**, sold as horizontal expansion to vendors already on Growth.
- **Standalone allergen SKU priced at £29/month**, not £19. Compliance prices on avoided cost.
- Build the EHO-consultant dashboard as a prerequisite to opening that channel — not the channel-development call list.
- The pricing page mentions allergen labels as a feature included in Starter / Growth from day one (it already is, code-wise) but does **not** sell a standalone SKU until v2. Avoids confusing the page.
- Document this as the "Plan B / Pivot Option C" in Finding 11. If the WhatsApp wedge fails the gate, allergen-labels-as-a-product is the pivot.

---

## Finding 9 — 90-day GTM plan with weekly milestones

### Specialist proposal

**Weeks 1–2 (pre-GTM hardening)** — gated by the technical pairs.
- Close all five pre-prod blockers from `COUNCIL-AUDIT-2026-04-27.md`: SSE leak, IDOR, Stripe idempotency, RLS GUC bug, FORCE RLS gaps, method-level authz. Output: production environment safe to host first design partner.
- Land pricing page (Finding 4) with ROI calculator (Finding 5) on the public site. Behind-feature-flag if blockers not yet closed.
- ICO registration completed; Stripe Connect application submitted.
- Founder narrative (Finding 3) drafted, peer-reviewed by one trusted operator.

**Week 3** — pre-walk preparation.
- Build the hit list: 50 vendors across Peckham, Brixton, Tottenham via Google Maps + Deliveroo / Uber Eats listings, screened on visible Deliveroo presence + WhatsApp number.
- Finalise one-pager + 4-slide phone deck (Finding 7).
- Identify the community-figure substitute (Finding 3) — name, ask, agreed referral commitment.

**Week 4** — first walking day.
- Door-knock 10 vendors (Tuesday + Wednesday afternoons in Peckham). Output: 2–3 verbal "yes" or "let's talk Tuesday".
- Iterate the pitch from real reactions; rewrite the script before the next walk.

**Week 5** — second walking day + first onboarding.
- Door-knock 10 vendors (Brixton + Tottenham). Output: 5 cumulative verbal commits.
- Onboard first design partner: tablet setup, WhatsApp wiring, first paid Stripe transaction, first allergen PDF printed. Document the journey.

**Week 6** — third walking day + second / third onboarding.
- Door-knock 10 vendors (return visits + new shops). Output: 8 cumulative verbal commits.
- Onboard partners 2 and 3.
- Hold the first monthly feedback call with partner 1.

**Weeks 7–8** — onboarding + iteration.
- No new door-knocks; all energy on partners 1–3. Fix the three things they actually complain about.
- Output by end of week 8: 3 design partners actively using the platform, paying £19/mo each. **Gate check (see Finding 11).**

**Weeks 9–10** — second walking sprint.
- Door-knock 15 more vendors using lessons from partners 1–3 (real screenshots, real testimonials). Output: 5 new verbal commits.
- Onboard partners 4–6.

**Weeks 11–12** — closing run.
- Door-knock 5 more vendors. Onboard 7–10. Convert verbal commits to paid (Stripe direct debit on file).
- Output by end of week 12: **10 paying vendors at £19/mo (£190 MRR, £2,280 ARR).** This is the council's gate for the £3M-cap re-engagement.

**Week 13** — decision point.
- If 10 paying achieved: write up the case studies, schedule investor conversations, plan v2 (allergen SKU expansion or geographic expansion).
- If 5–9 paying: extend by 30 days, no new commitments, finish strong.
- If <5 paying: invoke Finding 11 pivot gate.

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **Week 1–2 puts pricing page live before the blockers are closed.** Even behind a feature flag, you risk launching with a "register your interest" form that you cannot back. **Move pricing page launch to week 3** — strictly after the technical blockers are closed and ICO registration is confirmed. The weeks 1–2 output is "safe production environment" only, no public-facing artifact.
2. **30 door-knocks across weeks 4–6 with onboarding in parallel is a 60-hour-week founder schedule.** Each onboarding is realistically 4 hours (travel + setup + train + first transaction watch + first failure debug). Three onboardings = 12 hours, plus 30 door-knocks at 30 min each = 15 hours, plus follow-ups, plus the day job. **Halve the door-knock target in the onboarding weeks** (5 per week, not 10) so you do not burn out by week 6.
3. **The "10 paying vendors" gate is at £19/mo, not £49.** The council's £5,880 ARR target was at £49 average. At £19 founder pricing the same 10 vendors are £2,280 ARR — three times below the council's gate. **Either** raise the founder price to £29 (still below public Starter), **or** restate the gate as "10 paying at £19" and acknowledge the investor narrative needs adjustment. The specialist accepts the assistant's number; the gate is "10 paying at the founder rate, retained ≥ 90 days at month 6".

The assistant also flags: there is no "what if a vendor cancels in week 8" contingency. Add: "if any partner cancels before month 3, conduct an exit interview and rewrite the offer letter accordingly".

### Reconciled position

- Pricing page launch shifts to week 3, after blockers close.
- Door-knock cadence in weeks 5–8 reduces to 5 / week.
- Gate is "10 paying at £19 founder pricing, retention ≥ 90 days at month 6" — restated honestly.
- Cancellation contingency: any cancel in first 90 days triggers an exit interview within 7 days and a written change to the offer letter or product.
- The 90-day plan is published as `docs/planning/GTM-90-DAY-2026-04-27.md` so it is auditable; weekly Friday review against milestones.

---

## Finding 10 — Investor narrative for £1.2M-cap raise

### Specialist proposal

The 90-second pitch:

> J'Toye is the operating system for UK independent food shops who already take orders on WhatsApp and lose 30% of revenue to Deliveroo. We sell WhatsApp ordering plus kitchen display plus card payments plus Natasha's Law allergen labels for £89 a month, no commission. We have 10 paying design partners across Peckham, Brixton and Tottenham at the £19 founder rate, retained 90+ days, with documented commission savings averaging £[X] per shop per month. Our wedge is one specific community where the founder is doing the door-knocking; our path to £100k ARR is 200 vendors, achievable inside London Caribbean / West African and adjacent communities by the end of 2026. We are raising £150k friends-and-family at a £1.2M cap to fund 90 more days of door-knocking, complete pre-paid pilots in two more boroughs, and ship the allergen-labels standalone SKU as the second monetisation lane. The next round is at £400k ARR, in 12–18 months.

The one-pager: founder bio (one paragraph, honest about engineering background), the wedge (Caribbean / West African UK independents, anti-aggregator, allergen compliance), the proof (10 design partners, retention, commission savings), the ask (£150k @ £1.2M cap), the use of funds (founder salary 6 months at £40k = £20k; door-knock costs £5k; allergen-printer integration £15k; reserve £40k for product engineers contracted at month 4–6; £70k runway), and the 18-month milestone (£400k ARR).

**Is this the right raise size?**

Specialist's view: £150k @ £1.2M cap is the council's "drop the cap to £1.2M and write a £150k friends-and-family cheque to fund the door-knocking phase" — directly inherited. It buys 6–9 months at modest founder salary plus contractor budget. The next round at £400k ARR (~600 vendors at £49 average, or ~250 at £89) is a £1–2M seed at a £6–10M cap, conventional vertical SaaS.

**Or bootstrap to £100k ARR (200 customers) before raising?**

The bootstrap path saves dilution (no £150k raise = no 12.5% friends-and-family slice). Costs: 18+ months at founder's day-job pace versus 6 months full-time, and the founder may lose to Toast / Square's UK push during that window. Specialist's recommendation: **raise the £150k only if 10 paying vendors are achieved by week 12**. Otherwise bootstrap and revisit at £25k ARR.

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **The 90-second pitch leads with the product, not the founder.** Pre-seed at £1.2M cap is a founder bet, not a product bet. **Lead with founder + wedge in one breath**: "I'm a senior software engineer who has spent 18 months building this and 90 days door-knocking; I have 10 paying design partners in Peckham / Brixton / Tottenham at the founder rate; retention is X; here is the wedge..." The product comes after the human.
2. **"£100k ARR by end of 2026" is the kind of forward number that gets you killed in due diligence.** End-2026 is 8 months from now. To go from £2,280 ARR (10 × £19 × 12) to £100,000 ARR in 8 months requires going from 10 vendors to ~200 vendors at £49 average. That is 24 new vendors per month sustained. **Without paid sales, this is fantasy.** Soften to "path to £25k ARR by year-end with 50 paying vendors at the public rate; £100k ARR is 12–18 months out". Honest forecasts win cheques.
3. **The use-of-funds lists "product engineers contracted at month 4–6" for £40k.** That is one engineer for 4 months at £100/day — barely enough to ship two features. Either drop the engineer line and do it solo, or be honest about scope ("3 months of one part-time engineer to ship X and Y, deferring Z"). Vague engineering budgets read as padding.

The assistant also flags: **the bootstrap-vs-raise question depends on the founder's day job and savings runway, which are not in the doc.** No external advisor can answer this without that data. The reconciled position must reflect that.

### Reconciled position

- 90-second pitch reframed: founder + wedge first, product second.
- ARR forecast restated: £25k by year-end (50 paying vendors at average £42 across founder + new), path to £100k in 12–18 months. No magical second-half-of-year hockey stick.
- Use-of-funds tightened: explicit engineer scope (one part-time engineer, 12 weeks, two named features) or remove the line.
- Bootstrap-vs-raise decision deferred to week 12 with explicit criteria: if personal runway is <6 months at month 12 and 10 paying vendors achieved, raise; otherwise bootstrap and revisit at £25k ARR.

---

## Finding 11 — Pivot triggers

### Specialist proposal

Three explicit gates, each binary:

**Gate A — Week 8 progress check.**
- Threshold: at least 3 paying design partners (at £19 founder rate), at least 5 verbal commits, at least 30% of door-knocks resulting in a "let me think about it" or warmer.
- If passed: continue plan to week 12.
- If failed (0–2 paying): hold immediate diagnostic. Choose pivot path within 7 days.

**Gate B — Week 12 outcome check.**
- Threshold: 10 paying vendors at founder rate, 90-day retention ≥ 90% on partners 1–3, average commission savings calculated and documented.
- If passed: write case studies, raise £150k @ £1.2M cap, plan v2.
- If failed (<10 paying): escalate to pivot decision below.

**Pivot Options if Gate B fails:**

- **Option B — White-label to ethnic-food digital agencies** (per critic's pivot #2). 200 UK web/digital agencies serve ethnic food businesses; sell them the multi-tenant engine at £200/mo per client deployment. Lower vendor-side burden, agencies do the selling. Requires ~4 weeks of white-label theming + agency-onboarding tooling.
- **Option C — Allergen Compliance as a Service standalone SKU** (per critic's pivot #3 + Finding 8). £29/mo single SKU sold via EHO consultants. Wider TAM, slower sale, lower ARPU. Requires the EHO-consultant dashboard build and 8 weeks of B2B referral channel-development.

**Decision rule**: at Gate B failure, the founder's data tells which to choose. If door-knock conversion was high but vendors dropped on price, Option C (cheaper SKU). If door-knock conversion was low because vendors don't want to talk to a stranger about software, Option B (sell through agencies).

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **The Gate A threshold ("3 paying by week 8") is too generous.** Three paying at £19 is £57 MRR. That is not a market signal; that is three friends. **Tighten to "3 paying with at least one verbal lead from a different community" — i.e. evidence of network effect, not just first-degree contacts.** A vendor who heard about you from another vendor is the actual signal.
2. **The "30% door-knock conversion to warmer-than-cold" baseline is unjustified.** Cold door-knocking conversion in B2B SaaS is more like 5–15% to a follow-up meeting and 1–3% to a paid customer in a 90-day window. **30% is the founder being optimistic.** Recalibrate to "10% to follow-up meeting, 3% to paid by week 12".
3. **Pivot Options B and C both require 4–8 weeks of build before the pivot can land.** That puts the founder at week 16–20 with no revenue and a depleted budget. **Add Option D: stop and post-mortem.** Sometimes the right answer is "this was a portfolio piece; the engineering job offer is the pivot". The audit literally said this. Do not omit it from the gate.

The assistant also flags: any pivot decision must be made **with one mentor / advisor in the room**, not solo. Founders pivot toward whatever feels less painful in the moment; an outside voice corrects for that.

### Reconciled position

- Gate A threshold: 3 paying + at least one second-degree lead by week 8. Realistic conversion baselines (10% to meeting, 3% to paid).
- Gate B unchanged: 10 paying by week 12.
- Pivot options expanded to A / B / C / D (continue / white-label / allergen / stop). Stop is a valid pivot.
- Pivot decision must be made in a 90-minute conversation with at least one external advisor, ideally a UK food-SaaS operator. Not solo, not by Slack.
- Whichever option is chosen, the founder commits in writing (a public note in `docs/planning/`) within 7 days of the gate.

---

## Finding 12 — Brand renaming consideration

### Specialist proposal

"OaaS" is engineer language. The public brand should be different from the engineering codename. Three candidate directions, no recommendation:

- **Community-rooted name**. Examples: a Yoruba, Twi, Patois or Arabic word that means "kitchen", "feast", "the table". Pros: cultural legitimacy, memorable in the target community. Cons: limits geographic expansion (a Yoruba word excludes Caribbean), risk of cultural mis-step if the founder is not from the community.
- **Descriptive name**. Examples: "Counter", "Spread", "Pass", "Plate". Pros: instantly understood, doesn't constrain expansion, easy to .com domain. Cons: forgettable, hard to defend in trademark, blends with every other food-tech startup. Counter, Spread and Plate are all already taken in adjacent verticals.
- **Founder-name brand**. Examples: "[Founder]'s OS", "J'Toye" (current). Pros: personal accountability, hard to copy, builds a face for the brand. Cons: locks the company to the founder's biography forever; sale to acquirer is harder; if the founder pivots to a new company, the brand goes too.

Trade-offs: community names build the wedge but cap the ceiling; descriptive names scale but bleed into noise; founder names humanise but bind. **None of these is wrong; the choice depends on whether the founder intends to stay 5 years (founder name) or 15 (community name) or sell in 3 (descriptive name).**

### Assistant deliberation (challenge)

Three brutal pushbacks:

1. **A founder who has not yet signed 10 paying vendors should not be picking a brand name.** The brand will follow the customer; the customer is not yet there. **Defer the renaming exercise to week 13+** when the design partners have told the founder what they call the product (often it is "the WhatsApp thing" or "the kitchen screen"). That is the brand seed.
2. **"J'Toye" sounds personal and is already in use across the codebase, the email, the GitHub, the project memory.** Changing it costs a real day of code refactoring, DNS, Keycloak realm names, K8s namespaces. **There is no commercial benefit to renaming until vendor #11.** The "OaaS" suffix can be silently dropped from public copy without renaming the engineering layer.
3. **The "community-rooted name" option carries a real risk that the audit did not name**: if the founder is not from the community, a Yoruba or Patois brand looks like cultural appropriation in the press and in front of investors. Do not pick a community name unless the founder or a named co-founder is genuinely from that community. Otherwise the brand is a liability waiting to happen.

The assistant also flags: the simplest answer in the next 90 days is to ship the public site as "**J'Toye**" with no suffix, no slogan, and no brand-architecture document. Decide the rename at month 6.

### Reconciled position

- **Defer renaming to month 6+**. Public site goes live as "J'Toye" with no suffix.
- "OaaS" is dropped from all customer-facing copy immediately (it appears in URLs, headers, and meta-tags today; do a one-hour grep-and-replace). It survives in the engineering codebase.
- Community-rooted brand names are off the table unless / until a named community co-founder joins. Cultural-appropriation risk is real and the founder cannot afford the headline.
- At month 6 if 10+ vendors are paying, run a 1-week brand-naming exercise with the actual design partners ("what do you call this thing when you tell other vendors about it?"). That data drives the rename, not a brainstorm.

---

## Dependency graph

```
[Tech Pair #1: tenant-isolation fixes]   [Tech Pair #2: payment idempotency]
              │                                       │
              └───────────────┬───────────────────────┘
                              ▼
                [Wave A — Pre-prod blockers (Wk 1–2)]
                              │
                              ▼
              [Finding 4: Pricing page]   [Finding 5: ROI calculator]
                              │                       │
                              └───────────┬───────────┘
                                          ▼
              [Finding 1: ICP refinement (hit list build, Wk 3)]
                                          │
                                          ▼
              [Finding 6: Offer letter]   [Finding 7: Phone deck]
                              │                       │
                              └───────────┬───────────┘
                                          ▼
                  [Finding 2: Door-knock script (Wk 4)]
                                          │
                                          ▼
              [Finding 9: 90-day plan execution (Wk 4–12)]
                                          │
                                          ▼
                       [Finding 11: Gate A (Wk 8)]
                                          │
                                          ▼
                       [Finding 11: Gate B (Wk 12)]
                                          │
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
            [Pass: Finding 10        [Fail: Pivot       [Finding 8:
             investor pitch +        Option B / C / D]  Allergen SKU
             Finding 3 narrative]                       (v2 only)]
                                                              ▲
                                                              │
                                                  [Finding 12: Rename
                                                   (deferred to Mo. 6)]
```

Critical-path: **Tech blockers → Pricing page → Door-knock → Gate B**. Everything else is in parallel or downstream.

---

## Wave breakdown

**Wave A — Pre-GTM (weeks 1–2)**: Tech blocker remediation (gated by Tech Pairs #1, #2). ICO registration. Stripe Connect application. Founder narrative drafted. **Output**: production-safe environment, no public artifacts yet.

**Wave B — Public launch (week 3)**: Pricing page (Finding 4) + ROI calculator (Finding 5) + one-pager (Finding 6) live. ICP hit-list (Finding 1) built. Phone deck (Finding 7) shipped. Community substitute identified (Finding 3). **Output**: ready to walk.

**Wave C — Door-knock + onboard (weeks 4–8)**: Three door-knock walks, three design partners onboarded. Gate A check at end of week 8 (Finding 11). **Output**: 3 paying partners, qualitative data on what works.

**Wave D — Scale or pivot (weeks 9–12)**: Either continue to 10 paying (Wave C extended) or invoke pivot (Finding 11 options). Investor narrative finalised conditional on Gate B (Finding 10). **Output**: 10 paying vendors OR documented pivot.

**Wave E — v2 (weeks 13+)**: Allergen SKU build (Finding 8). Brand-rename exercise (Finding 12). Geographic expansion (East Ham / Croydon). Investor conversations.

---

## Open questions

1. **What is the founder's actual personal runway and day-job status?** All "raise vs bootstrap" maths depends on this and it is not in any audit document. Needs an honest one-paragraph answer before week 1.
2. **Does the founder have any existing relationship with a Peckham / Brixton / Tottenham vendor today?** Even one. If yes, that is partner #1 and the door-knock starts there. If no, the community-substitute strategy is doubly important.
3. **Is the founder willing to be the face of the brand?** Founder-narrative paragraphs in Finding 3 assume yes. If no (privacy, day-job conflict, family reasons) the entire investor narrative needs reshaping.
4. **What is the founder's Stripe Connect status today?** Stripe Connect applications take 2–6 weeks; if not started, the pricing-page launch in week 3 is at risk.
5. **Has the founder validated that PPDS / Natasha's Law label printing actually works end-to-end on a real Brother / Zebra / Dymo printer?** Pricing page promises it; if not validated it is a future-cap-ex liability.
6. **Who is the named external advisor who will be in the room at the Gate B pivot decision?** Identify and brief by week 6, not week 12.
7. **Is there appetite to defer all engineering work outside pre-prod blockers for 90 days?** This GTM plan assumes the founder is selling, not coding, weeks 4–12. Any slip back to feature work kills the plan.

---

**Word count**: ~4,200. **Status**: ready to act on. **Next checkpoint**: end of week 2 (pre-prod blockers closed, pricing page ready to launch).
