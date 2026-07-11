# J'Toye OaaS Business Model Decision Guide

**Decision date:** 10 July 2026  
**Research cut-off:** 10 July 2026  
**Status:** Authoritative strategic guide; commercial hypothesis awaiting paid-market validation  
**Audience:** Founders, product and engineering agents, commercial partners, advisers, and investors

## How to use this document

This is the source of truth for decisions about J'Toye's initial customer, offer, revenue model,
operating boundary, and validation gates. It consolidates market, product, economics, regulatory,
and failure-pattern research. It is deliberately sceptical: public evidence can identify the best
model to test, but only paid merchant behaviour can validate the business.

When this guide conflicts with older strategic material, use this guide. The
[`REMEDIATION-BACKLOG-2026-07-08.md`](REMEDIATION-BACKLOG-2026-07-08.md) is historical evidence,
not a live completion board. Confirm each item in current source and [`docs/CHANGELOG.md`](../CHANGELOG.md);
in particular, payment architecture and tenant settlement remain unresolved.

## Decision

> Build J'Toye as **UK-first, assisted direct-order and fulfilment software for established,
> owner-led Nigerian and West African food operators in one dense city cluster**. Treat takeaway
> and catering as separate discovery cohorts. Differentiate through pre-order/catering operations
> and consistent food-information workflows. Do not become a marketplace, delivery operator,
> payment custodian, merchant of record, complete POS, accounting system, or compliance guarantor.

The recommended commercial test is:

- `£39` per location per month;
- test either `0.5%` of direct platform sales or a fixed `£79–£119` monthly alternative;
- `£99` minimum assisted onboarding;
- offer a capped/fixed `£149–£199` option for high-volume merchants;
- pass card-processing costs through transparently;
- target a model in which the vendor is seller, food business operator, merchant of record,
  payment recipient, and fulfiller; this is not the current payment implementation.

This is the best-supported **business hypothesis**, not a proven business case.

## Confidence and evidence boundaries

| Proposition | Confidence | What supports it | What remains unknown |
|---|---|---|---|
| UK-first, not pan-European | High | UK food guidance, digital-payment behaviour, measurable Nigerian population proxy, EU country fragmentation | Best city and neighbourhood |
| Vendor SaaS, not marketplace | High | Product fit, lower regulatory exposure, failure evidence | Exact contract and payment implementation |
| Takeaway and catering cohorts | Medium | Clear workflows and current product adjacency | Which cohort pays and retains better |
| Direct ordering and fulfilment core | Medium–high | Product capability and merchant margin pressure | Incremental direct demand and retained usage |
| Catering/pre-order as a wedge | Medium | Deposits, amendments, quantities, and timed fulfilment have attributable value | Complete workflow coverage and willingness to pay |
| Compliance assistance differentiates | High | UK registration/allergen duties and current product capability | Frequency of sustained use after setup |
| `£39 + 0.5%` price | Low–medium | Coherent scenarios and public competitor anchors | Segment-specific willingness to pay |
| WhatsApp as a lead differentiator | Low | A constrained product path exists | Prevalence and cost of the workflow in this cohort |

### Observed or authoritative evidence

- Census 2021 recorded approximately `271,000` usual residents in England and Wales who wrote in
  “Nigerian” as their ethnic group. This is a cultural-demand proxy, not an operator count,
  nationality count, or TAM.
- UK hospitality is overwhelmingly small-business based and was under severe margin and labour
  pressure in 2025.
- UK food registration and allergen obligations include home, online, takeaway, catering, and
  market-stall operators.
- UK payments are strongly card/contactless oriented; euro-area point-of-sale behaviour is more
  cash-heavy and varies materially by country.
- The repository evidences a useful supported pilot, but not production-wide resilience,
  self-service tenancy, vendor settlement, complete accounting, or guaranteed compliance.

### Hypotheses requiring primary research

- the proportion of target operators using WhatsApp, Instagram, phone, web, or aggregators;
- monthly direct-order value and actual aggregator terms;
- amendment, error, refund, waste, and failed-collection costs;
- ingredient-import and substitution frequency;
- acceptable subscription, usage, setup, and settlement terms;
- the highest-density reachable cluster and the best-performing cohort.

## Initial customer—narrowly defined

Start with:

> Established, owner-led Nigerian and West African takeaways and caterers in one London cluster
> that already process recurring digital, telephone, or message-based orders and can demonstrate
> meaningful order volume.

Do not combine restaurants, home caterers, market stalls, and grocers into one persona. Their
service rhythms, stock, compliance evidence, hardware needs, and ability to pay differ.

### Cohort A: takeaways

Test repeat-direct ordering, menu availability, kitchen execution, collection/delivery handoff,
and daily reconciliation. J'Toye should initially complement aggregators and help migrate repeat
customers gradually; a direct storefront does not create consumer demand by itself.

### Cohort B: caterers

Test quotations or order capture, deposits, amendments, headcount and quantity changes, cut-offs,
production lists, ingredient purchasing, cancellation rules, and timed fulfilment. Catering may
produce more attributable value than ordinary takeaway ordering and is an equal discovery priority.

Do not blend cohort results. They may support different products, prices, and support models.

## Jobs worth solving

1. **One authoritative order record.** Preserve choices and later amendments across web, phone,
   and message-originated orders.
2. **Pre-orders and deposits.** Reduce ingredient-purchase risk, failed collections, and confusion
   over balances, cut-offs, and cancellations.
3. **Kitchen production.** Translate dish, protein, side, spice, quantity, and timing choices into
   a reliable preparation queue.
4. **Food-information consistency.** Maintain written ingredients and allergens through recipe or
   supplier substitutions. Software assists; the vendor remains responsible for accuracy.
5. **Availability and substitutions.** Update what can be sold without leaving stale menus across
   channels.
6. **Daily reconciliation.** Explain what was ordered, paid, refunded, prepared, and outstanding.
7. **Direct-margin protection.** Retain repeat direct orders while recognising that aggregators can
   continue to provide discovery.

For distance sales, the vendor must make allergen information available before the purchase is
completed and again when the food is delivered. PPDS labelling (“Natasha's Law”) applies only to
food prepacked for direct sale. J'Toye can surface vendor-entered information, but the vendor must
keep it accurate, provide required handover information, and control cross-contamination.

## Product truth

Current source supports a controlled, assisted UK pilot with:

- branded storefront, menu/catalogue, and guest ordering;
- UK-oriented checkout and order tracking;
- order lifecycle and browser-based kitchen display;
- promotions and announcements;
- customer and order records;
- allergen-aware product information and label generation;
- VAT-oriented management reporting;
- constrained, configured WhatsApp order intake.

The commercial offer must **not** claim:

- production-ready or enterprise-grade operation;
- guaranteed Natasha's Law, food-safety, or GDPR compliance;
- HMRC accounting, reconciliation, or filing;
- vendor payouts, escrow, wallets, or regulated marketplace payments;
- offline kitchen operation or kitchen-ticket printing;
- general conversational or AI WhatsApp commerce;
- proven multi-replica reliability or disaster recovery;
- self-service tenant onboarding and offboarding;
- pan-European tax, payment, language, or regulatory support.

The largest product/model mismatch is payments. **Target payment model—not current capability:**
before live multi-vendor trading, use a documented vendor-connected-account charge flow so the
vendor's role as seller, merchant of record, payment recipient, and fulfiller is contractually and
technically true. The current single-platform GBP Stripe flow must not be used to receive or settle
funds for multiple vendors. Resolve the intended architecture, contracts, refunds, disputes, and
regulatory position first.

## Commercial model to test

| Component | Pilot term | Reason |
|---|---:|---|
| Core subscription | `£39/location/month` | Creates commitment without pretending assisted support is free |
| Usage option | `0.5%` direct platform sales | Aligns revenue with realised usage at a transparent rate |
| Fixed alternative | `£79–£119/month` | Tests preference for predictable billing and avoids payment dependency |
| Assisted onboarding | `£99` minimum | Makes menu migration, setup, and training visible |
| High-volume option | `£149–£199/month` | Prevents an uncapped percentage becoming punitive |
| Card processing | Transparent pass-through | Avoids speculative payment-margin assumptions |

Test the usage and fixed offers concurrently. Do not infer that merchants prefer a percentage fee.

### Illustrative economics—not forecasts

The working model is:

```text
monthly revenue = 39 + (0.005 × direct monthly sales)
monthly COGS = 9 + (0.001 × direct monthly sales)
monthly contribution = revenue − COGS
gross LTV = monthly contribution ÷ monthly logo churn
CAC payback = all-in CAC ÷ monthly contribution
```

The COGS formula, `£25` fully loaded service hour, `£375` CAC, and churn scenarios are planning
assumptions—not observed segment data.

| Direct monthly sales | Revenue | Assumed contribution | Payback at `£375` CAC |
|---:|---:|---:|---:|
| `£5,000` | `£64` | `£50` | `7.5` months |
| `£15,000` | `£114` | `£90` | `4.2` months |
| `£30,000` | `£189` | `£150` | `2.5` months |

At approximately `£90` contribution and `3.5%` monthly churn, expected replacement-acquisition cost
is `3.5% × £375 = £13.13` per merchant-month, leaving approximately `£76.88`. A lean operation
costing `£4,500/month` therefore needs roughly `59` active merchants; a founder plus implementation
capacity at `£8,500/month` needs roughly `111`. These are scenarios, not attainable-customer
forecasts.

Operational targets are median assisted onboarding at or below `4` hours and steady support below
`30` minutes per merchant per month. If typical accounts require bespoke data entry, device support,
or recurring menu administration, price the work as a managed service or narrow the customer.

## Alternatives and explicit decisions

| Model | Decision | Reason |
|---|---|---|
| Hybrid direct-ordering SaaS | Pursue now | Best alignment between merchant value, current product, and scalable contribution |
| Catering/pre-order package | Discover in parallel | Potentially clearer attributable value; coverage must be validated |
| Compliance-assistance tier | Use as differentiator/entry | Real need but may be low-frequency and data-entry heavy |
| Community/agency reseller | Consider after `10–20` retained merchants | Can improve distribution, but requires repeatable onboarding and partner economics |
| Configured WhatsApp intake | Optional add-on after evidence | Current path is constrained and segment demand is unproven |
| Consumer African-food marketplace | Reject for launch | Consumer acquisition, liquidity, promotions, support, and marketplace regulation |
| Owned delivery fleet | Reject | Density, labour, insurance, and service-failure economics |
| Merchant of record / pooled funds | Reject | Payments, VAT, refunds, safeguarding, and product-liability exposure |
| Full POS/hardware replacement | Reject | Offline, terminals, printers, installation, repairs, and entrenched competitors |
| Free or ultra-cheap SaaS | Reject | Human onboarding and support consume contribution |
| Bespoke work inside subscription | Reject | Silently turns SaaS into a low-margin agency |
| Pan-European or super-app launch | Reject | Country fragmentation and several unproven businesses at once |

### Failure patterns behind the rejections

- Jumia closed Jumia Food across seven markets after saying it had never been profitable.
- Lightspeed's restructuring is a warning about broad product and support complexity, not proof
  that POS cannot work.

These cited cases are cautionary examples, not predictive evidence or proof that every adjacent
model fails. Other case studies require separately cited evidence before use in a decision. Each
future adjacent business needs its own contribution ledger, regulatory model, owner, and stop rule.

## Regulatory and operating boundary

For the initial model, contract and product design should establish that:

- the vendor is the named seller and food business operator;
- the vendor sets food prices and controls recipes, ingredients, allergens, and substitutions;
- the target payment architecture makes the vendor the documented merchant of record and sends
  customer funds into its own supported connected account;
- J'Toye invoices its software/platform charges separately;
- the vendor or its directly contracted courier fulfils collection or delivery;
- J'Toye does not hold pooled funds, wallet balances, escrow, or manual payouts;
- vendor onboarding records registration evidence and responsible food-business contact;
- order and allergy information is minimised, access-controlled, and retained under a documented
  schedule;
- continental expansion is approved one country at a time.

Stripe Connect is infrastructure, not a regulatory exemption. Obtain written Stripe architecture
confirmation and specialist UK payments advice before processing transactions for multiple vendors.
Obtain food-law, privacy, VAT, and contract advice before public launch; this guide is not legal or
tax advice.

Do not collect customer allergy requirements by default. If a vendor chooses to collect them,
obtain privacy advice on the applicable UK-GDPR Article 9 condition, provide a clear privacy notice,
restrict access, and apply a short documented retention/deletion rule.

## Ninety-day evidence test

Recruit `30–40` qualified prospects and accept approximately `10–12` paid pilots split between the
takeaway and catering cohorts.

Before onboarding, record:

- existing order channels and source of truth;
- monthly direct order value and actual aggregator statements where applicable;
- amendment, error, refund, deposit, cancellation, and failed-collection frequency;
- preparation, availability, substitution, and allergen workflows;
- current tools, fees, decision maker, onboarding time, and support time.

### Continue only if

- at least `10` make a genuine paid commitment;
- at least `70%` of paid pilots go live (`7/10`, `8/11`, or `9/12`);
- at least `70%` of live pilots activate menu, the agreed payment arrangement, and a first real
  order;
- median assisted onboarding is no more than `4` hours;
- steady support is below `30` minutes per merchant per month;
- 90-day paid retention is at least `80%`;
- contribution approaches `£75+` per active merchant per month;
- CAC has a credible path below `£375` and five-month payback;
- merchants evidence saved time, fewer errors, recovered deposits, reduced waste, or retained
  direct orders.

### Stop or materially pivot if

- fewer than `5` of `30` qualified prospects pay;
- 90-day retention is below `70%`;
- the product is used only for setup/labels and then abandoned;
- onboarding repeatedly becomes bespoke;
- support exceeds one hour per merchant per month;
- median direct sales remain below `£8,000` while merchants reject a higher fixed price;
- the platform only displaces an already efficient direct channel without creating savings.

## Decision rules for future agents

1. Do not justify a feature by a top-down “African food market” TAM; build a named, bottom-up list
   for one cluster.
2. Do not treat assumptions in the economics table as observed facts.
3. Do not build marketplace, payout, delivery, hardware, credit, or pan-European scope before the
   paid-pilot gates pass and the adjacent model has independent economics.
4. Keep takeaway and catering evidence separate.
5. Revalidate product claims against source and current tests before using them commercially.
6. Prefer measured operator outcomes over feature usage or GMV alone.
7. Update this document with dated evidence when the decision changes; preserve the reason and data.

## Primary references

### Market and payments

- [ONS: Ethnic group, England and Wales, Census 2021](https://www.ons.gov.uk/peoplepopulationandcommunity/culturalidentity/ethnicity/bulletins/ethnicgroupenglandandwales/census2021)
- [UK Finance: UK Payment Markets 2025](https://www.ukfinance.org.uk/system/files/2025-10/Payment%20Markets%20Report%20Summary.pdf)
- [ECB: SPACE 2024 payment study](https://www.ecb.europa.eu/stats/ecb_surveys/space/html/ecb.space2024~19d46f0f17.en.html)
- [House of Lords Library: hospitality and retail sectors](https://lordslibrary.parliament.uk/hospitality-and-retail-sectors-impact-of-government-policy/)

### Food, privacy, payments, and Europe

- [GOV.UK: Starting a food business](https://www.gov.uk/guidance/starting-a-food-business)
- [FSA: Allergen guidance for food businesses](https://www.food.gov.uk/business-guidance/allergen-guidance-for-food-businesses)
- [FSA: Food safety for food delivery](https://www.food.gov.uk/business-guidance/food-safety-for-food-delivery)
- [ICO: UK GDPR guidance](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/)
- [FCA: Payment services and e-money](https://www.fca.org.uk/firms/payment-services-regulations-e-money-regulations)
- [Stripe Connect documentation](https://docs.stripe.com/connect)
- [EU Digital Services Act](https://eur-lex.europa.eu/eli/reg/2022/2065/oj)
- [EU food-information regulation](https://eur-lex.europa.eu/eli/reg/2011/1169/oj/eng)

### Pricing and failure evidence

- [Square UK restaurant pricing](https://squareup.com/gb/en/point-of-sale/restaurants/pricing)
- [Stripe UK pricing](https://stripe.com/gb/pricing)
- [TechCrunch: Jumia Food closure](https://techcrunch.com/2023/12/14/jumia-discontinues-food-delivery-across-seven-markets-shifts-focus-to-expanding-physical-goods-business/)
- [Reuters: Lightspeed restructuring](https://www.reuters.com/technology/lightspeed-cut-280-jobs-it-looks-turn-profitable-2024-04-03/)

## Maintenance

Review this guide after the first `10`, `20`, and `50` paid merchants, whenever payment flow or
seller status changes, and before entering another country. Record observed cohort metrics in a
dated appendix or linked decision record rather than silently replacing assumptions with new
numbers.
