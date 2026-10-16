# J'Toye OaaS Business Model Decision Guide

**Original decision date:** 10 July 2026  
**Strategic reframing & codebase reconciliation date:** 17 October 2026 (Schema **V86**, **6,158+** logical test invocations)  
**Status:** Authoritative strategic & commercial positioning guide; governs Go-To-Market (Phases 29, 30, 32) and Multi-Model Policy Architecture ([`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md))  
**Audience:** Founders, product and engineering agents, commercial partners, advisers, and investors

## How to use this document

This is the authoritative source of truth for decisions about J'Toye OaaS's customer wedge, reframed commercial positioning, revenue model, regulatory operating boundary, competitive moat, and technical malleability. It consolidates UK market, socio-economic, regulatory, and failure-pattern research alongside a rigorous top-to-bottom audit of the shipped codebase through **Phase 39 (`V86`)**.

When this guide conflicts with older strategic material, use this guide. The [`REMEDIATION-BACKLOG-2026-07-08.md`](REMEDIATION-BACKLOG-2026-07-08.md) is historical evidence, not a live completion board. Confirm each item in current source, [`docs/CHANGELOG.md`](../CHANGELOG.md), and [`docs/architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md).

---

## 1. Reframed Business Position (October 2026)

> **Core Positioning Thesis:**  
> Build J'Toye OaaS as the **UK-Compliance-Native, Abuse-Hardened Direct-Order & Scheduled Fulfilment Operating System for Established UK West African Food Operators** — engineered to run **both the daily counter takeaway (Cohort A) and the high-margin weekend batch/catering ledger (Cohort B)** from one menu, one statutory allergen/label engine, and one VAT-aware ledger, while keeping the **vendor as the legal Merchant of Record (MoR)** on flat, transparent unit economics (`£39/location/month + 0.5% GTV`).

### Why this reframing maximises probability of commercial success (`78%–85%` in target wedge)

Critical analysis of the UK African food market (`2025–2026`), socio-economic shifts in the diaspora, and emerging food-tech competitors (`PeerPlates`, `HomeCooks`, `DabbaDrop`, `ChopNow`, `Afri-Grub`, `Chowdeck`, `Orda/Moniebook`, `Flipdish`) demonstrates that **cold two-sided consumer marketplaces fail in ethnic food unless backed by £5M+ in venture subsidies**, whereas **vertical operating software that protects direct margins and removes UK regulatory friction succeeds with high retention (`>85%` annual)**.

Our business position centres on **four structural pillars** where J'Toye OaaS holds an asymmetric, code-verified advantage:

1. **Direct-Margin Recovery Over Aggregator Extraction (`~£2,500+/mo` saved per kitchen):**
   - Established UK West African takeaways and caterers already generate repeat demand via walk-ins, Instagram, TikTok, and WhatsApp, yet lose **25%–35% commission** (`£2,800/mo` on `£10k` GTV) on Deliveroo/UberEats or **7.99%–9%+** on legacy white-label platforms (Flipdish).
   - J'Toye OaaS charges **`£39/location/month + 0.5% GTV`** (or a flat `£79–£119/month` predictable tier) plus pass-through Stripe fees (`1.5% + 20p`), returning over **24% of gross margin** directly to the operator.
2. **Statutory UK Food-Safety & Consumer-Law Native Moat (Built-in, Not Bolted-on):**
   - Unlike African-domestic POS tools (`Orda`, `Chowdeck`) or generic website builders (`Shopify`, `Wix`), J'Toye OaaS is purpose-built for UK environmental health (EHO), FSA, and Trading Standards enforcement:
     - **Server-enforced 14-bit UK FSA allergen acknowledgement (`V63`, `V69`):** Checkout refuses missing (`422`) or stale (`409`) allergen acknowledgements and snapshots the exact 14-allergen bitmask at the moment of purchase.
     - **Ingredient-to-allergen reconciliation (`AllergenConsistencyService`) & separate cross-contact mask (`V74` `may_contain_mask`):** Catches undeclared allergens in ingredient text (*egusi, groundnut, crayfish/crustaceans*) and prints separate `"May contain"` lines on **OpenPDF Natasha's Law PPDS labels** (`AllergenLabelPdfService`).
     - **Audited customer allergy notes (`V73`):** Dedicated allergy note at checkout requiring recorded staff acknowledgement (`allergy_note_ack_at`/`_ack_by`) visible on the KDS and customer tracking page.
     - **Statutory Seller & Trader Identity (`V71` `trader_identity`):** Enforces UK E-Commerce Regulations 2002 Reg 6 and Consumer Contracts Regulations 2013 Reg 28 disclosures on every storefront, checkout, and confirmation email — supporting **both Sole Traders (`WAIVED` Companies House gate) and Limited Companies**.
     - **Automated UK GDPR Art. 15 & Art. 17 Compliance (`V62`, `V67`, `V68`, `V70`, `V72`, `V75`):** Cross-tenant DSAR encrypted access exports (`AES-256-GCM`) and full guest/customer PII erasure across orders, Envers audit trails, and Keycloak accounts.
3. **Abuse-Hardened, Financially Auditable Kitchen & Multi-Shop Operations:**
   - Solves the real-world operational chaos of busy diaspora kitchens:
     - **Phantom Cash-Order Abuse Bounds (`V81`):** Enforces server-side caps on open cash orders per email (`3`), phone (`3`), and keyed client-IP HMAC-SHA256 (`5`), plus bulk junk-order rejection and unverified-contact KDS badges.
     - **Checkout Price-Change & Sanity Integrity (`RWO-007`, `RWO-042`):** Refuses stale basket prices (`409` itemising exact price changes) and absurd quantities (`OrderLimits`).
     - **Structured UK Opening Hours & 1-Tap Kitchen Overload Pause (`V80`):** Evaluates `Europe/London` schedules (including overnight windows) and lets a swamped Friday-night kitchen pause storefront ordering for `20 min`, `40 min`, or until reopened (`ShopAvailabilityPolicy`).
     - **Audited Order Voiding & Per-Shop VAT Ledger (`V78`, `V79`):** Prevents deletion of confirmed orders (`409`), requiring an audited `VOID` with a reversing `REVERSAL` ledger entry scoped per shop (`shop_id`).
     - **Multi-Branch Staff RBAC & QR Link Continuity (`V52`, `V77`, `V85`, `V86`):** Scopes staff (`GROUP_ADMIN`, `SHOP_MANAGER`, `STAFF`) and customer records (`origin_shop_id`) to specific shops, and preserves printed QR codes via permanent HTTP `301` slug aliases (`shop_slug_alias`).
4. **Unified Counter + Weekend Batch/Catering Operating Model ("One Kitchen, Two Rhythms"):**
   - In the UK West African food economy, high-margin growth is driven by **scheduled batch orders** (`2L/4L` weekend soup bowls, party jollof coolers, trays, and corporate/event catering) alongside daily hot-food takeaway.
   - Instead of splitting operations between Deliveroo (for daily meals) and chaotic WhatsApp/bank-transfer threads (for weekend bowls and catering), J'Toye OaaS unifies **Immediate Counter Fulfilment (Cohort A)** and **Scheduled Slot/Batch Pre-Orders (Cohort B)** behind one malleable policy engine ([`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md)).

---

## 2. Competitive & Market Landscape (UK Ethnic Food Tech, 2026)

| Competitor / Archetype | Model & Traction | Core Strength | Structural Weakness vs. J'Toye OaaS |
| :--- | :--- | :--- | :--- |
| **PeerPlates** (`peerplates.co.uk`, Co. `#16603623`, inc. July 2025) | Pre-launch / early pilot peer-to-peer home-cook marketplace (`15%` platform fee) | Visual discovery (video reels), advance batch scheduling (`24h`–`90d`), multi-stop delivery pooling | **High regulatory & cold-start fragility:** P2P home cooks face strict UK FSA/EHO hygiene & insurance barriers; 15% take-rate penalises established kitchens; requires massive two-sided consumer CAC. |
| **HomeCooks** (`home-cooks.co.uk`, `~$6.8M` raised; acquired *Flavers / Cook My Grub*) | Curated chef meal-box marketplace (next-day chilled nationwide delivery) | Proven consumer demand for authentic diaspora dishes; chilled box logistics bypasses hot-food 30-min couriers | **Marketplace custodian:** Owns the customer relationship, controls pricing/boxes, and takes marketplace margins; does not give brick-and-mortar restaurants their own direct-order OS or KDS. |
| **DabbaDrop** (`dabbawala` model, London) | Zero-waste weekly scheduled curry subscription | Predictable batch production (`Cook Sheet` efficiency) and scheduled delivery windows | Single-brand/curated subscription; not a multi-tenant SaaS platform for independent West African operators. |
| **ChopNow / Afri-Grub** | Niche UK Afro-Caribbean food delivery apps | Cultural alignment and community branding | **Jumia Food / cold-marketplace trap:** Competing head-on with UberEats/Deliveroo on live courier logistics in low-density suburbs destroys unit economics. |
| **Chowdeck / Orda (Moniebook)** | Nigerian domestic food delivery & cloud POS leaders | High velocity in Lagos/Abuja; strong African dish taxonomy | **Zero UK regulatory architecture:** Built for NGN (`₦`)/Paystack; lacks UK FSA 14-allergen bitmasks, Natasha's Law PPDS PDF labels, UK E-Commerce Regs 2002 trader disclosures, UK GDPR DSAR automation, and UK postcode geocoding. |
| **Flipdish** | Enterprise white-label restaurant ordering (`7.99%–9%+` fee + `£59–£99/mo`) | Mature loyalty, kiosks, POS integrations, native apps | **Punitive take-rate (`~£948/mo` on `£10k` GTV vs. J'Toye's `£89/mo`)** and lacks West African batch/catering/liter-bowl workflows and built-in PPDS PDF label generation. |

---

## 3. Confidence and Evidence Boundaries

| Proposition | Confidence | What supports it | What remains to validate in paid pilots |
|---|---|---|---|
| **UK-first, dense London cluster** (Peckham, Brixton, Deptford, Lewisham, Woolwich, Barking/Dagenham) | **High** | UK food guidance, digital-payment behaviour, Census 2021 population density, OS Code-Point Open locality (`V61`) | Highest-converting initial postcode cluster |
| **Assisted Vertical SaaS (Vendor as MoR), not Consumer Marketplace** | **High** | Lower FCA/food-safety liability, 88%+ gross margin, competitor failure evidence | Exact mix of usage (`£39 + 0.5%`) vs. flat (`£99/mo`) pricing |
| **Dual-Rhythm OS: Counter Takeaway (Cohort A) + Scheduled Batch/Catering (Cohort B)** | **High** | Mirrors how UK West African kitchens actually earn margin (weekday meals + weekend 2L/4L bowls & catering trays) | Optimal slot cut-off defaults and deposit percentages (`50%` vs `100%`) |
| **Statutory UK Food-Safety & Allergen Moat** | **High** | Shipped in code (`V63`, `V69`, `V71`, `V73`, `V74`, OpenPDF PPDS labels); mandatory under UK law | Frequency of menu/allergen edits after initial assisted setup |
| **WhatsApp-to-Checkout Pre-Order Bridge (`#208` / `PAY-04`)** | **Medium–High** | Converts unstructured WhatsApp DM haggling into verified, allergen-acknowledged, pre-paid Stripe orders | Merchant adoption of sharing checkout links vs. manual DM replies |

---

## 4. Product Truth (Code-Verified at Schema V86)

Current source on `main` (`V1`–`V86`, verified by **6,158+ logical test invocations** across Java, Jest, Go, Playwright, and MCP vitest) implements:

- **Multi-Tenant Isolation & Security:** PostgreSQL 15 Row-Level Security (`ENABLE + FORCE RLS`) across all tenant tables, verified under `NOSUPERUSER NOBYPASSRLS` (`RlsContractTest`), Keycloak 26.8.1 OIDC across vendor and customer realms, and per-tenant + per-IP rate limiting at both `edge-go` and `core-java`.
- **Storefront, Locality & SEO:** Branded tenant storefronts (`/shop/[slug]`) with permanent HTTP `301` old-slug redirects (`V86` `shop_slug_alias`), Ordnance Survey Code-Point Open (`1.75M` GB postcodes, `V61`) haversine proximity discovery (`/shop?lat=&lon=&radiusKm=`), server-rendered JSON-LD structured data, and WCAG 2.2 AA accessibility gates.
- **Statutory UK Food Safety & Legal Floor:**
  - 14-bit UK FSA allergen bitmask (`AllergenCatalog`) with write-time order line snapshots (`V63`) and mandatory pre-submit customer acknowledgement (`V69` `allergen_ack_mask`, `allergen_ack_at`).
  - Separate cross-contact risk mask (`V74` `may_contain_mask`) and automated ingredient-text-to-allergen reconciliation warnings (`AllergenConsistencyService`).
  - Customer allergy note with mandatory vendor read-receipt (`V73` `allergy_note`, `allergy_note_ack_at`, `allergy_note_ack_by`).
  - Printable **OpenPDF Natasha's Law PPDS allergen labels** (`AllergenLabelPdfService`) with `en-GB` production and use-by dates.
  - Statutory Seller & Trader Identity block (`V71` `trader_identity`) enforcing E-Commerce Regs 2002 Reg 6 and CCR Reg 28 on storefront, checkout, confirmation page, and email.
  - Automated UK GDPR Art. 15 encrypted data export (`V75` `dsar_access_export`) and Art. 17 cross-tenant erasure (`V62`, `V67`, `V68`, `V70`, `V72`).
- **Kitchen Operations, Abuse Resistance & Finance:**
  - Real-time STOMP/SSE Kitchen Display System (`/dashboard/kitchen`) with `New` order lane, looping audio/tab alerts, session-expiry alarm (`RWO-020`, `RWO-021`), fulfilment badges, and allergy-note acknowledgement.
  - Structured UK weekly opening schedules + 1-tap kitchen pause (`20 min`, `40 min`, `indefinite`, `V80` `ShopAvailabilityPolicy`) and delivery/collection toggles (`V83` `FulfilmentPolicy`).
  - Cash-order abuse bounds (`V81` `CashOrderCaps`: max 3 per email, 3 per phone, 5 per keyed IP HMAC-SHA256) and basket price-change/limit guards (`RWO-007`, `RWO-042`).
  - Audited order voiding (`V78`) and per-shop VAT ledger (`V79` `SALE | REVERSAL | MANUAL` with `shop_id`), plus multi-shop staff RBAC (`V52` `shop_staff`, `V77` `staff_invite`, `V85` `customers.origin_shop_id`).
- **Safe Media Pipeline & AI Automation:**
  - Copy-on-write `media_asset` model (`V53`, `V58`–`V60`) on **Azure Blob Storage** (`Azurite` locally) with quarantine container split, magic-byte sniffing, EXIF stripping, and WebP normalization.
  - Outbound HMAC-signed webhooks (`V55`/`V56`) and mutating MCP server tools (`create_order`, `create_customer` with `Idempotency-Key` enforcement, `V50`/`V82`).

---

## 5. Positions & Blockers Currently Standing in the Way of Success (Must Not Be Omitted)

To turn our technical foundation into repeatable commercial success — and to ensure our architecture supports multiple business models without rewrites — the following **seven execution items** are mandatory in the implementation roadmap:

| ID | Blocker / Missing Capability | Why It Stands in the Way of Commercial Success | Target Resolution (`ADR-0007` / Roadmap) |
| :--- | :--- | :--- | :--- |
| **`GTM-BLK-01`** | **Malleable Multi-Model Settlement Policy (Vendor-MoR Direct Charges vs. Marketplace Destination Charges)** | `StripeConnectService` (`StripeConnectService.java:170-198`) currently routes connected-account payments *only* for `MARKETPLACE` destination charges (where J'Toye is MoR). Our primary Go-To-Market (`WHITE_LABEL` / Direct SaaS) requires **Direct Charges on the vendor's connected account** (`DIRECT_CHARGE_VENDOR_MOR`) so the vendor is legally and technically the Merchant of Record. | Implement `SettlementStrategy` (`ADR-0007` Axis 1) in Phase 30 (`PAY-01`..`PAY-03`), supporting `DIRECT_CHARGE_VENDOR_MOR`, `DESTINATION_CHARGE_PLATFORM_MOR`, and `SPLIT_DEPOSIT_VENDOR_MOR` via tenant/shop policy config. |
| **`GTM-BLK-02`** | **Advance Pre-Order & Scheduled Fulfilment Slots (`V87`)** | `ShopAvailabilityPolicy.requireTakingOrders` (`ShopAvailabilityPolicy.java:234-242`) currently blocks storefront checkout whenever `openNow == false`. This prevents customers from placing advance weekend soup-litre, cooler, or catering orders outside live counter hours. | Ship `V87` `shop_fulfilment_slots` + `FulfilmentTimingMode` (`IMMEDIATE_ONLY`, `SCHEDULED_SLOTS_ONLY`, `HYBRID_IMMEDIATE_AND_SLOTS`) with cut-off enforcement and atomic per-slot unit caps (`ADR-0007` Axis 2). |
| **`GTM-BLK-03`** | **Consolidated Kitchen "Cook Sheet" Aggregator** | The KDS displays individual order cards. A kitchen preparing 45 Saturday pre-orders or a catering run needs an aggregated dish/portion/litre production tally (`"32x Jollof Large, 15x Egusi 2L"`) and batch PPDS label print. | Add `OrderCookSheetService` + `/dashboard/kitchen/cook-sheet` read projection (`GROUP BY product_id, scheduled_slot_id`) with 1-click batch PPDS PDF export. |
| **`GTM-BLK-04`** | **Catering Deposit / Part-Payment & Verified-Contact Payment Links (`PAY-04`)** | High-value catering and batch cooler orders (`£100–£1,000+`) require a 50% upfront deposit to buy ingredients and a single-use payment link sent to a verified phone/email for the balance. | Wire `SPLIT_DEPOSIT_VENDOR_MOR` (`deposit_amount_pennies`, `balance_due_pennies`) + single-use expiring payment links (`PAY-04`, `#461`, `#462`). |
| **`GTM-BLK-05`** | **SaaS Subscription Billing & Usage Metering (`PAY-02`)** | `TenantPlan` (`V48`) exists, but recurring Stripe Billing for the `£39/location/month` base subscription + `0.5%` application fee (or flat `£79–£149/mo` tier) is not yet wired to Stripe Invoices. | Implement `BillingPlanMode` (`ADR-0007` Axis 4) and close `PAY-02` in Phase 30 against Stripe test/live invoices. |
| **`GTM-BLK-06`** | **WhatsApp-to-Checkout Pre-Order Link Bridge (`#208` / `COMMS-07`)** | West African operators take heavy order enquiries on WhatsApp DM. Building a full conversational AI bot is fragile, but a **1-click "Send Pre-Filled Checkout & Allergen Link to WhatsApp"** from the vendor dashboard or WhatsApp webhook turns DM enquiries into paid, allergen-acknowledged orders immediately. | Activate the `COMMS-07` WhatsApp channel seam as a **Pre-Order Checkout Link Generator & Status Notifier** (`#208`). |
| **`GTM-BLK-07`** | **Production Domain Cutover (`Phase 29`) & In-Product Merchant ROI Telemetry (`Phase 32`)** | `jtoye.co.uk` / `olajay.co.uk` ingress cutover (`DPLY-01`..`DPLY-05`) is required for live trading, and vendors need a visible **"Value Saved vs. Aggregators" ROI card** on `/dashboard/finance` to anchor `>85%` retention at renewal. | Close Phase 29 staging/prod cutover and add the Merchant ROI Telemetry summary card (`Direct GTV`, `Estimated Commission Saved vs 28% Aggregator`, `Phantom Cash Orders Blocked`, `Repeat Buyer %`). |

---

## 6. Malleable Multi-Model Matrix (Go-To-Market + Future Expansion)

By governing tenant and shop behaviour through the four composable policy axes defined in [`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md), J'Toye OaaS launches with the highest-probability model while supporting adjacent models via configuration rather than rewrites:

| Commercial Model | Status | Settlement & MoR (`Axis 1`) | Fulfilment Timing (`Axis 2`) | Discovery Surface (`Axis 3`) | Billing & Pricing (`Axis 4`) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Model A: Assisted Direct-Order + Hybrid Pre-Order SaaS** | **Launch Default (Pursue Now)** | `DIRECT_CHARGE_VENDOR_MOR` (Vendor is MoR) | `HYBRID_IMMEDIATE_AND_SLOTS` | `DIRECT_PLUS_LOCAL_DIRECTORY` | `£39/mo + 0.5%` (or `£79–£119/mo` flat) + `£99` setup |
| **Model B: Catering & Weekend Batch Ledger OS** | **Launch Co-Wedge (Cohort B)** | `SPLIT_DEPOSIT_VENDOR_MOR` (50% deposit + balance link) | `SCHEDULED_SLOTS_ONLY` (cut-off + slot unit caps) | `DIRECT_PLUS_LOCAL_DIRECTORY` | `£39/mo + 0.5%` or `£99/mo` flat |
| **Model C: Weekend Pop-Up / Curated Batch Drop** *(PeerPlates / DabbaDrop style)* | **Supported via Config Preset** | `DIRECT_CHARGE_VENDOR_MOR` | `SCHEDULED_SLOTS_ONLY` | `CURATED_CAMPAIGN_DROP` (countdown + portion cap) | `BATCH_PAYG_STARTER` (`£0/mo + 6%` GTV) |
| **Model D: Multi-Branch Enterprise White-Label** | **Supported via Config Preset** | `DIRECT_CHARGE_VENDOR_MOR` | `HYBRID_IMMEDIATE_AND_SLOTS` | `DEDICATED_ONLY` | `£149–£199/location/mo` capped |
| **Model E: Managed Local Marketplace** | **Dormant / Optional Preset** *(Shipped in #102)* | `DESTINATION_CHARGE_PLATFORM_MOR` (J'Toye is MoR) | `HYBRID_IMMEDIATE_AND_SLOTS` | `DIRECT_PLUS_LOCAL_DIRECTORY` | Marketplace commission bps |

---

## 7. Commercial Model & Unit Economics to Test

| Component | Pilot term | Reason |
|---|---:|---|
| Core subscription | `£39/location/month` | Creates commitment without pretending assisted support is free |
| Usage option | `0.5%` direct platform sales | Aligns revenue with realised usage at a transparent rate |
| Fixed alternative | `£79–£119/month` | Tests preference for predictable billing and avoids percentage friction |
| Weekend Batch PAYG option | `£0/mo + 6.0%` direct sales | Captures weekend-only soup/batch kitchens (`Model C`) that resist fixed retainers, with an automatic upgrade prompt to `£39 + 0.5%` once monthly sales exceed `£710` |
| Assisted onboarding | `£99` minimum | Makes menu migration, allergen setup, and training sustainable |
| High-volume option | `£149–£199/month` | Prevents an uncapped percentage becoming punitive for multi-site groups |
| Card processing | Transparent pass-through (`Stripe 1.5% + 20p`) | Avoids speculative payment-margin assumptions |

### Illustrative economics — not forecasts

```text
monthly revenue = 39 + (0.005 × direct monthly sales)
monthly COGS = 9 + (0.001 × direct monthly sales)
monthly contribution = revenue − COGS
gross LTV = monthly contribution ÷ monthly logo churn
CAC payback = all-in CAC ÷ monthly contribution
```

| Direct monthly sales | Revenue | Assumed contribution | Payback at `£375` CAC | Operator savings vs. 28% Aggregator |
|---:|---:|---:|---:|---:|
| `£5,000` | `£64` | `£50` | `7.5` months | `~£1,240 / mo` |
| `£15,000` | `£114` | `£90` | `4.2` months | `~£3,810 / mo` |
| `£30,000` | `£189` | `£150` | `2.5` months | `~£7,660 / mo` |

Operational targets remain median assisted onboarding at or below `4` hours and steady support below `30` minutes per merchant per month.

---

## 8. Regulatory and Operating Boundary

For our primary Go-To-Market models (`Model A`, `Model B`, `Model C`, `Model D`), contract and product design establish that:

- **The vendor is the named seller, Food Business Operator (FBO), and Merchant of Record (MoR)** (`V71` `trader_identity` displayed on storefront, checkout, confirmation page, and email);
- The vendor sets food prices and controls recipes, ingredients, allergens (`V63`/`V69`), cross-contact declarations (`V74`), and substitutions;
- Customer card payments route via **Stripe Connect Direct Charges** (`DIRECT_CHARGE_VENDOR_MOR`) directly into the vendor's connected account, with J'Toye collecting its platform application fee or invoicing software subscriptions separately;
- The vendor or its directly contracted courier fulfils collection or delivery (`V83` `FulfilmentPolicy`);
- J'Toye does not hold pooled funds, wallet balances, or manual payouts in the default SaaS model;
- Customer personal data and allergy notes (`V73`) are minimised, protected under PostgreSQL `FORCE RLS`, and governed by the published retention schedule (`docs/retention-manifest.json`) and automated DSAR access/erasure pipelines (`V62`–`V75`).

---

## 9. Ninety-Day Evidence Test

Recruit `30–40` qualified prospects in our initial London cluster (Peckham / Southwark / Lewisham / Greenwich / Barking) and accept `10–12` paid pilots across Takeaway (`Cohort A`) and Scheduled Batch/Catering (`Cohort B`).

### Continue and scale if
- At least `10` make a genuine paid commitment (`£99` onboarding + subscription/usage agreement);
- At least `70%` of paid pilots go live (`7/10`, `8/11`, or `9/12`) with verified `trader_identity`, `opening_schedule`, and `ALLERGEN_DATA_COMPLETE`;
- At least `70%` of live pilots process recurring direct or scheduled batch orders (`≥ £2,500/mo` direct GTV per shop by Day 60);
- Median assisted onboarding is `≤ 4` hours and steady support is `< 30` minutes per merchant per month;
- 90-day paid retention is `≥ 80%` and monthly contribution reaches `£75+` per active merchant;
- Merchants evidence concrete outcomes in the in-product ROI dashboard: saved aggregator commissions, zero unacknowledged allergy notes, blocked phantom cash orders, and smoother weekend batch prep.

### Stop or pivot a cohort preset if
- Fewer than `5` of `30` qualified prospects commit to paid onboarding;
- 90-day retention drops below `70%`;
- The product is used once for PPDS label printing and abandoned for ordering;
- Support exceeds `1` hour per merchant per month due to bespoke menu entry.

---

## 10. Decision Rules for Future Agents

1. **Centre every commercial surface on the four core strengths:** Direct-margin recovery (`£39 + 0.5%`), UK Food-Safety & Legal compliance (`V63`/`V69`/`V71`/`V73`/`V74`), Abuse-hardened kitchen/multi-shop operations (`V78`–`V86`), and Unified Counter + Scheduled Batch/Catering fulfilment (`ADR-0007`).
2. **Never hardcode business-model assumptions into domain services:** Route all settlement, timing, surface, and billing variations through the four composable policy axes in [`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md).
3. **Do not build cold consumer-marketplace demand-generation or owned courier fleets** before the Vertical SaaS + Scheduled Batch pilot gates pass.
4. **Keep Takeaway (`Cohort A`) and Scheduled Batch/Catering (`Cohort B`) telemetry distinct** so we can measure conversion, average order value (AOV), and retention per rhythm.
5. **Revalidate all product claims against current source, `docs/metrics.json`, and CI gates** before publishing them on marketing or legal routes.

---

## 11. Primary References

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
