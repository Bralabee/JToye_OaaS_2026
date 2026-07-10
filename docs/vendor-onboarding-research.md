# Vendor Onboarding & Compliance — UK Field Guide

> **Prepared for:** J'Toye OaaS · **Scope:** UK (England-weighted, nation differences flagged) · **Compiled:** 10 July 2026
> **Model assumptions:** Hybrid (marketplace + white-label) · Delivery model undecided (both covered)
> **Companion artifact:** <https://claude.ai/code/artifact/08b0a561-b44c-4e96-8459-685645645406>

How the established UK platforms (Uber Eats, Just Eat, Deliveroo) onboard, vet and approve food vendors; what UK law requires of the **platform** versus the **vendor**; the target onboarding process; and how to automate it while staying compliant.

> [!IMPORTANT]
> **This is a research synthesis to inform product & legal scoping — it is NOT legal advice.** Items marked `[INTERPRETATION]` are reasoned legal analysis applied to J'Toye's facts, not direct source quotes. Items marked `[UNVERIFIED]` came from secondary sources and should be confirmed before external use. The FBO-status classification, the payments fund-flow, and the data-protection role should each be confirmed with a regulated adviser against J'Toye's exact operating model.

---

## Bottom line up front — five decisions this report is built around

1. **The FHRS hygiene gate is the industry's single hard rule.** Every major platform refuses vendors below a food-hygiene rating threshold (Deliveroo & Uber Eats: **2+**; Just Eat: **3+**). It is checkable for free via the FSA's open API — make it the first automated gate.
2. **Your legal weight hinges on "seller vs software".** In the **marketplace** role you inherit consumer-law, payments and data-controller duties. In the **white-label** role almost all of it sits with the vendor. Keep the two paths architecturally distinct.
3. **Running your own couriers is the expensive fork.** It pulls in worker-status risk (the Uber ruling), gig-economy right-to-work checks (being made mandatory), and food-transport hygiene law. Vendor-arranged delivery / collection keeps all of that off your books.
4. **Use a licensed PSP so you never touch the money.** Stripe Connect (or equivalent) holds and disburses funds and carries the KYC/AML duty — the standard way a marketplace avoids needing its own FCA authorisation.
5. **Two free government APIs do most of the vetting.** FSA FHRS + Companies House cost nothing and anchor a staged onboarding: verify automatically, let vendors build their shop in parallel, gate go-live on green.

---

## 1 · How Uber Eats, Just Eat & Deliveroo onboard and vet vendors

All three run the same shape: **express interest → verify the business → capture bank & hygiene details → build the menu → photography → ship hardware → go live.** They differ on the strictness of the hygiene gate, up-front cost, and how much is self-serve vs sales-assisted.

### Onboarding process, side by side

| Dimension | Uber Eats | Just Eat | Deliveroo |
|---|---|---|---|
| Sign-up model | Self-serve form + rep-assisted onboarding | Self-serve; unique `FSA ID` required at signing | Online application + dedicated onboarding associate |
| Up-front fee | **£650** activation, excl. VAT ([1]) *(periodically waived)* | No joining fee ([2]) | Onboarding fee covers photography *(amount unpublished)* |
| Hardware shipped | Tablet + printer + welcome kit | **Orderpad** — locked to the registered business, non-transferable | Tablet with integrated receipt printer |
| Photography | Included in activation fee | Not detailed officially | Package chosen at sign-up; images ~48h after shoot |
| Documented go-live | "<48h" fast-track (2020 promo) / "a few days" | Days *(no official figure)* | "as little as **7 days**" ([3]) |
| Payout cycle | Weekly | Weekly | Weekly, paid every Thursday |
| Headline commission | 30% delivery · 13% self-delivery · 13% pickup ([1]) | ~30% + VAT delivery · ~14% + VAT self-delivery | ~25–35% *(no public rate card)* |

> All commissions are quoted **ex-VAT**; 20% VAT is charged on top, materially raising the effective rate. Uber's tiered "Lite/Plus/Premium" plans circulate in industry write-ups but are **not shown on the official UK pricing page** — treat as `[UNVERIFIED]`.

### The vetting gate: food-hygiene rating is the hard rule

The decisive, universal filter is the **Food Hygiene Rating Scheme (FHRS)** score, set by local-authority inspection on a 0–5 scale. Since October 2022 the three leaders operate under the FSA's voluntary **Aggregators Food Safety Charter**, covering ~170,000 food businesses, committing them to four things: only list businesses *registered* with their local authority; enforce a *minimum* hygiene rating; push FSA safety messaging; and support customers with allergies ([4]).

```
FHRS 0–5 rating scale — platform listing thresholds
┌──────┬──────┬────────────┬───────────┬──────┬──────────┐
│  0   │  1   │     2      │     3     │  4   │    5     │
│URGENT│MAJOR │ ▲ Deliveroo│ ▲ Just Eat│ GOOD │VERY GOOD │
│      │      │  & Uber    │           │      │          │
│  ✕ blocked  │  listable on Deliveroo/Uber, Just Eat needs 3+  │
└──────┴──────┴────────────┴───────────┴──────┴──────────┘
  0–1  = cannot list on any major platform
  2    = Deliveroo / Uber Eats OK, Just Eat blocks
  3–5  = listable everywhere
```

- **Scotland** uses the separate **Food Hygiene Information Scheme (FHIS)** — outcomes are "Pass" / "Improvement Required", not 0–5 — and platforms accept a "Pass".
- Businesses **awaiting** first inspection can usually be admitted on proof of registration.
- The precise "2 / 2 / 3" figures are consistent across FSA-aligned sources and platform statements but are **not published as a single numbered FSA table** ([5]).

### What they require at sign-up, and keep checking

- **Proof of local-authority food-business registration** is the baseline gate everywhere (Just Eat ties the listing to your FSA ID; Deliveroo accepts registration proof if awaiting inspection).
- **Bank / payout details** and a **menu with prices and descriptions**; Uber additionally asks for a copy of your business or food licence.
- **Ongoing monitoring:** platforms re-sync FHRS scores and **suspend or delist** vendors whose rating drops below threshold; Just Eat and Deliveroo let customers filter by hygiene rating. Re-inspections are done by the local authority, not the platform.

> [!WARNING]
> **`[UNVERIFIED]` at primary level:** Uber Eats' public help text is *global* (menu, hours, business/food licence) — a UK-specific KYC/identity & bank-verification checklist wasn't confirmable on the official help centre. Deliveroo's "exclusivity for lower commission" and its 25–35% commission band come from industry sources, not primary T&Cs. Treat all such figures as indicative and confirm against a live partner contract before quoting.

---

## 2 · What UK law requires of the vendor

These duties fall on the food business itself. In **both** models the vendor carries them — but a good onboarding flow *verifies* the load-bearing ones (registration, hygiene rating) and *prompts* the rest, because a non-compliant vendor is your reputational and, in the marketplace role, partly your legal problem.

| Requirement | What the law says | Enforce at onboarding? |
|---|---|---|
| **Food business registration** | Register with the local authority **≥28 days before trading**. **Free, cannot be refused**; trading unregistered is a criminal offence. Applies to home-based & online/distance sellers ([6]). | Verify — hard gate |
| **Food Hygiene Rating (FHRS)** | 0–5 from LA inspection (hygiene, premises, management). Display **mandatory in Wales & NI**, **voluntary in England**; Scotland runs FHIS (Pass/Improvement Required) ([7]). | Verify via FSA API |
| **HACCP food-safety management** | Permanent HACCP-based procedures under assimilated **Reg (EC) 852/2004, Art 5**. FSA's free **Safer Food, Better Business** pack is the standard tool for small caterers ([8]). | Attest / prompt |
| **Allergens — Natasha's Law & the 14** | The 14 allergens must be declared (assimilated Reg 1169/2011). **PPDS** foods need full ingredient + emphasised-allergen labels since **1 Oct 2021**. Distance selling: allergen info **before purchase completes AND again at delivery** ([9]). | Data fields required |
| **Insurance** | **Employers' liability legally required if you have staff** — min £5m (EL(CI) Act 1969); up to £2,500/day uninsured. **Public liability not statutory** but commercially expected / often contractually required ([10]). | Collect certificate |
| **VAT / HMRC** | VAT registration at **£90,000** turnover (since Apr 2024). **Hot takeaway food standard-rated 20%**; most cold takeaway zero-rated. Companies pay corporation tax (19%/25%) ([11]). | Capture VAT no. if given |
| **Alcohol & age-restricted goods** | Selling alcohol needs a **premises licence + personal licence** (Licensing Act 2003, England & Wales). Delivery is an "off-sale"; offence to deliver to under-18s. Operate **Challenge 25**. Scotland/NI separate regimes ([12]). | Gate alcohol categories |
| **Data protection** | Vendor is a **controller** of its customer data under UK GDPR / DPA 2018 and usually owes the ICO an annual **data-protection fee** (most small vendors: Tier 1, ~£40) ([13]). | Inform in terms |

> [!NOTE]
> **Nation differences that bite:** Hygiene-rating **display** is mandatory in Wales (2013 Act) and NI (2016 Act) but voluntary in England; Scotland uses Pass/Improvement-Required, not 0–5. **Natasha's Law** shares a 1 Oct 2021 commencement UK-wide but rides on four separate SIs. **Alcohol licensing** under the Licensing Act 2003 is England & Wales only — Scotland (2005 Act) and NI (1996 Order) differ. If J'Toye onboards outside England, branch these rules by the vendor's nation.

**Flagged for a final check before relying on them:** exact SI citations for the **Wales** and **NI** Natasha's Law regulations (the 1 Oct 2021 commencement is confirmed); the specific SI under the NI Food Hygiene Rating Act 2016; and the **ICO fee tiers** (£40 / £60 / £2,900, £5 direct-debit discount), read from a search snippet because the ICO page blocked direct fetch. All consistent with the long-standing regimes but worth a quick confirmation.

---

## 3 · What UK law requires of the platform — marketplace vs white-label

This is where the hybrid model matters most. The same feature carries different legal weight depending on whether J'Toye is **the seller's channel** (marketplace) or **the seller's software** (white-label). The classification turns on *what you actually do*, not what the contract calls it.

### Are you a food business? (FBO registration)

| Marketplace / aggregator | White-label SaaS |
|---|---|
| **Generally not an FBO** — a pure listing/order/payment intermediary is the channel; the FSA charter puts FBO registration on the *vendors*. **BUT** if you touch the food (dark kitchens, consolidation hubs, cold storage, repackaging) you cross into "distribution" and must register as an FBO and meet food-hygiene law yourself. `[INTERPRETATION]` from the FBO definition (Reg 178/2002) — confirm with your LA/FSA for your exact model. | **Not an FBO** — you never handle food, orders or (in the pure model) money. The vendor is unambiguously the FBO. |

### Allergen information (distance selling)

| Marketplace | White-label |
|---|---|
| The legal *accuracy* duty stays with the vendor, and the "at delivery" limb is theirs. But if **your UI is the point of sale**, you must ensure it can display the 14-allergen data and doesn't obstruct/truncate it — you share practical responsibility for the "before purchase" limb. `[INTERPRETATION]` | Vendor is the responsible party. Your exposure is limited to providing the fields and display mechanism. |

> J'Toye already has PPDS/allergen structures in the schema (V41: `allergen_spans`, `shelf_life_days`, `durability_type`) — that's the mechanism both models need.

### Consumer protection

Applies to the party that contracts with the consumer — you (as seller/agent) in the marketplace role, the vendor in white-label.

- **Consumer Contracts Regs 2013:** the 14-day cooling-off right is *disapplied* for perishable food and time-specific catering (reg 28(1)(c),(h)) — hot takeaways aren't cancellable. **But the pre-contract information duties still apply**: identity, full price inclusive of taxes & delivery, main characteristics must be disclosed before the order ([14]).
- **DMCC Act 2024 (in force 6 Apr 2025)** replaced CPUT and bites hardest on a marketplace: **"reasonable steps" to verify reviews are genuine** and remove fakes; headline prices must include unavoidable fees up front (**no drip pricing** on service/delivery/small-order fees). CMA can fine up to **10% of global turnover** ([15]).

### Payments, KYC & AML

> [!TIP]
> **The standard pattern — use it.** Collecting customer money and paying vendors is a regulated payment service (PSR 2017) / possibly e-money (EMR 2011). You avoid needing your own FCA authorisation by using a **licensed PSP as the payment provider of record**: funds settle into *Stripe's* safeguarded account, Stripe pays out to sellers, and you only send pre-agreed payout instructions. Because you never control the funds, you stay outside the regulatory perimeter — and **Stripe carries the KYC/AML (MLR 2017) duty**, performing identity checks on each connected vendor ([16]).

> [!CAUTION]
> **The one architecture mistake that breaks it:** the exemption **only holds if money never lands in your own account.** If you route customer funds into a J'Toye account and then pay vendors, you risk becoming a regulated payment institution, outside the commercial-agent exclusion (PSR 2017 Sch 1). Use Stripe Connect **destination charges / separate charges & transfers** so the PSP holds and disburses. Confirm the fund-flow with counsel — this is the biggest payments risk on the marketplace path.

### Data protection — the sharpest legal difference

| Marketplace | White-label |
|---|---|
| **Controller / joint controller.** You decide why & how customer data is used (accounts, order history, analytics, fraud, marketing) — a **controller** in your own right. Where you and the vendor both determine purposes over the same order, likely **joint controllers** needing an **Article 26** arrangement. | **Processor (usually).** Vendor is the controller; you host/process on their instructions under an **Article 28** DPA. **Caution:** the moment you use the data for your *own* purposes (cross-client analytics, product training, your marketing) you become a controller too ([17]). |

### The platform's duties at a glance

| Area | Marketplace / aggregator | White-label SaaS |
|---|---|---|
| FBO registration | Not an FBO unless it handles/stores/transports food | Never an FBO |
| Allergen 2-point duty | Vendor's duty; you must present "before purchase" info accurately | Vendor's duty; you provide the data fields |
| Consumer Contracts Regs | You (seller/agent) give pre-contract info; cancellation exempt for food | Vendor is the trader and bears it |
| DMCC 2024 (reviews / pricing) | **High exposure** — you host reviews & control checkout | Low; features must be *configurable* to comply |
| Payments (PSR / e-money) | Triggered — neutralised via licensed-PSP model | None — money flows customer → vendor's own PSP |
| AML / KYC (MLR 2017) | Statutory duty on the PSP; you KYC vendors operationally | None |
| Data protection role | Controller / joint controller (Art 26) | Processor (Art 28) — unless you use data yourself |

> [!IMPORTANT]
> **Not legal advice.** Cited items are drawn from statute and FSA/ICO/FCA guidance; the marketplace-vs-white-label allocation is `[INTERPRETATION]`. Confirm the FBO-status line, the payments fund-flow, and your data-protection role with a regulated adviser against your exact architecture.

---

## 4 · The delivery fork: the moment you run couriers, three bodies of law switch on

Everything here applies **only if J'Toye engages couriers itself.** A marketplace that leaves delivery to vendors — or collection-only — and the white-label path both sit entirely outside it. "Who delivers" is the most cost-sensitive decision in the whole design.

### 1 · Worker status — the Uber / Deliveroo contrast

| Uber — *workers* | Deliveroo — *self-employed* |
|---|---|
| **Uber BV v Aslam [2021] UKSC 5:** drivers are "workers" (NMW + holiday) from when the app is on and they're ready to accept trips. The court looked at the *reality* — Uber set fares, terms, routes, ratings — showing control, subordination, dependence. | **IWGB v CAC [2023] UKSC 43:** riders were *not* in an employment relationship, decisively because of a genuine, virtually unfettered **right of substitution** — incompatible with the personal service employment requires. Decided under Art 11 ECHR (union recognition), so not blanket authority for all purposes. |

**The rule that emerges:** personal service + control ⇒ worker; a *real* substitution right ⇒ self-employed. Status is fact-specific. If your couriers can't genuinely substitute and you control pay/allocation/discipline, an Uber-style worker finding is likely. `[INTERPRETATION grounded in both judgments]`

### 2 · Right-to-work checks are being extended to couriers

> [!WARNING]
> **Near-term mandatory — in flux.** Historically RTW checks applied only to employees, so gig platforms sat outside the illegal-working regime. On **30 March 2025** the Home Office announced extending it to the gig economy — food delivery included — targeting the substitution loophole. Enforcement is stiff: civil penalties up to **£45,000 per illegal worker** (£60,000 repeat). As of late 2025 this was still moving through the Border Security, Asylum & Immigration Bill and a Home Office consultation, so precise commencement isn't settled `[UNVERIFIED / in-flux]` — but design courier onboarding (ID + substitute registration) as if it's coming ([18]).

Deliveroo, Just Eat and Uber Eats already run biometric ID / direct verification on account holders and substitutes in anticipation.

### 3 · Food transport hygiene

If you (or your couriers) transport food, **Reg (EC) 852/2004, Annex II, Chapter IV** applies to the party doing the transport: conveyances/containers kept clean and in good repair; effective separation of products; temperature maintained and, where needed, monitored; food positioned to minimise contamination. This is the legal basis for insulated bags and cleaning between loads — and it reinforces that running your own delivery is a "distribution" activity that can itself make you an FBO ([19]).

> [!TIP]
> **The trade-off, stated plainly.** Keeping delivery **vendor-arranged or collection-only** removes worker-status liability, gig RTW-check duties, and food-transport-hygiene obligations in one move — and keeps the marketplace clearly outside "distribution", protecting the not-an-FBO position. Running your own fleet buys control and margin but takes on all three. If you ever do it, structure it deliberately (real substitution rights, ID checks, temperature-controlled equipment) rather than drifting into it.

---

## 5 · What J'Toye's onboarding should resemble

Borrow the incumbents' shape but improve on their weakest point: manual verification and slow go-live. The winning pattern is **staged, progressive onboarding** — let a vendor self-serve and build their shop immediately, run verification in parallel, and **gate go-live (publish) on every check returning green.** This maps onto J'Toye's multi-tenant model: a *pending* tenant builds data under RLS while a `published`/`active` flag holds the storefront back.

| Marketplace path | White-label path |
|---|---|
| Full identity + business + FHRS verification before listing | Lighter-touch: verify the business is real; vendor self-attests compliance |
| Stripe Connect (Express) onboarding → KYC/AML + payouts handled | Vendor connects *their own* PSP account (money never touches you) |
| Commission agreement e-signed | SaaS subscription agreement e-signed |
| Allergen data mandatory before publish (you present it) | Allergen & pricing tools provided; vendor configures & owns them |
| Review-authenticity & all-in pricing enforced (DMCC) | You are processor — Art 28 DPA is the contract you need |
| You are controller — privacy notice & Art 26 terms in place | Provide compliance *prompts*, not gates, beyond identity |

### The common gate chain

1. **Express interest & create a pending tenant.** Capture business name, address/postcode, company number (if incorporated), contact. Tenant created with `published = false`. Vendor can start building immediately.
2. **Automated verification (background).** Company existence & active status; food-hygiene rating pulled from the FSA API and checked against your threshold; food-business-registration proof. → *free FSA FHRS API + free Companies House API*
3. **Identity, payments & KYC.** Marketplace: Stripe Connect Express does identity + KYC/AML + payout setup in one hosted/embedded flow. White-label: vendor links their own PSP.
4. **Agreement e-signed.** Commission agreement (marketplace) or SaaS subscription (white-label). E-signatures are UK-valid; use the signature-complete webhook as a gate.
5. **Shop & menu build — in parallel from step 1.** Catalogue, prices, photos, opening hours, and — required before publish — allergen data on every item (V41 fields).
6. **Go-live gate.** Publish blocked until every check is green: verified business, hygiene rating ≥ threshold, KYC passed, agreement signed, allergen data complete. Then flip `published = true`.

Set internal SLAs (e.g. identity ≤24h, menu review ≤48h) and surface progress-triggered messages ("Verification approved", "Payout setup incomplete") so the vendor always knows the one thing blocking them.

---

## 6 · How to streamline & automate it

Two UK government APIs are **free, need no contract, and remove the bulk of manual review.** Anchor onboarding on them, add a PSP for payments+KYC, and e-sign for the paperwork.

| Tool | What it verifies / does | Cost | Integration note |
|---|---|---|---|
| **FSA FHRS Open Data API** `api.ratings.food.gov.uk` | Look up an establishment by name/address/postcode; read back the 0–5 rating (or FHIS Pass) to auto-gate the hygiene threshold. | **Free · no key** | Anonymous GET. **Must send `x-api-version: 2`** or it returns no data. For mass nightly re-checks, use bulk XML downloads rather than hammering the API ([20]). |
| **Companies House API** `api.company-information.service.gov.uk` | Confirm a company exists, is `active`, its registered office, incorporation date, directors & PSCs (beneficial-owner cross-check). | **Free · API key** | HTTP Basic (key as username). 600 requests / 5-min window. Only applies to incorporated vendors — sole traders have no record ([21]). |
| **Stripe Connect** | Onboards connected vendor accounts with KYC/AML + identity verification, then handles payouts — shifting the regulated burden to Stripe. | Per-transaction *(check UK pricing)* | Use **Express** (or equivalent controller-properties config) so Stripe collects KYC and owns payout compliance while you keep the fee & vendor relationship. Embedded onboarding gives white-label-ish UX without Custom-account compliance load ([22]). |
| **Standalone KYC / IDV** *(optional)* | Document + biometric identity proofing & AML screening — Stripe Identity, Onfido, Persona, GBG, ComplyAdvantage. | Per-verification *(~$1.50–$5 self-serve; enterprise quoted)* `[UNVERIFIED pricing]` | Often unnecessary if Connect already bundles KYC. Reach for it only when you need identity proofing *independent of* payments, or richer AML ([23]). |
| **E-signature** | Sign vendor agreements; the signature-complete webhook becomes an onboarding gate. | Per-seat / per-envelope | DocuSign / Dropbox Sign / Adobe Sign — all REST APIs. E-signatures legally valid in the UK (Electronic Communications Act 2000 / UK eIDAS); simple/advanced tiers suffice for commercial agreements ([24]). |

> [!TIP]
> **Sequencing for speed.** Fire the two **free** checks (Companies House on company-number entry, FHRS on name/postcode) the instant those fields are filled — the vendor sees a green tick before finishing setup. Run Stripe onboarding and e-sign in parallel while they build the menu. Because name/address matching against the FHRS API is fuzzy, keep a **human fallback** for no-match / ambiguous cases rather than hard-failing the vendor.

---

## 7 · Applied to J'Toye OaaS — where this lands against your stack

### You already have the foundations

- **Multi-tenant + RLS** is exactly the substrate the staged "pending tenant builds under RLS, `published` flag gates go-live" pattern needs.
- **Allergen/PPDS schema (V41)** — `allergen_spans`, `shelf_life_days`, `durability_type` — is the mechanism both models require to satisfy Natasha's Law and the distance-selling allergen rule.
- **Stripe** is already in the stack (payments UI + Java SDK), so Connect is an extension, not a new vendor relationship.
- **VAT fields (V40)** align with the hot-vs-cold rating distinction the vendor must get right.

### Decisions to make before building the onboarding flow

| Decision | Why it's on the critical path |
|---|---|
| Do you ever run couriers? | Determines whether §4 (worker status, RTW checks, transport hygiene) applies at all. Cheapest compliant answer is vendor-arranged / collection. |
| Confirm the fund-flow with counsel | Stripe-holds-funds keeps you outside FCA authorisation; J'Toye-holds-funds does not. Verify your Connect charge type reflects the former. |
| Pin the FHRS threshold policy | 2+ (like Deliveroo/Uber) or 3+ (like Just Eat)? And how to treat "awaiting inspection" and Scotland's FHIS Pass. |
| Marketplace vs white-label data-protection posture | Drives whether you need Art 26 joint-controller terms or Art 28 processor terms — and a hard rule against reusing white-label vendors' customer data for your own purposes. |
| Which nations you onboard | England-only is simplest; Wales/NI/Scotland each branch hygiene-display, Natasha's-Law and alcohol-licensing rules. |

> [!NOTE]
> **Suggested first slice (vertical MVP):** pending-tenant onboarding + the two free API gates (Companies House + FHRS) + a `published` go-live flag + mandatory allergen data before publish. Delivers the industry's hard vetting rule and the biggest compliance win with zero third-party cost, and works identically for both the marketplace and white-label paths.

---

## Sources & verification notes

Primary/official UK sources were prioritised (FSA / food.gov.uk, gov.uk, legislation.gov.uk, ICO, FCA, HMRC, Companies House, and the platforms' own partner pages), plus law-firm briefings for recent case law and the 2024–25 statutory changes. Figures marked *indicative* / `[UNVERIFIED]` came from secondary sources and should be confirmed before external use.

### Platform onboarding & vetting
- [1] Uber Eats UK — pricing & activation fee — <https://merchants.ubereats.com/gb/en/pricing/>
- [2] Just Eat fees & commissions — <https://blog.menuviel.com/just-eat-fees-and-commissions-for-restaurants/>
- [3] Deliveroo merchant FAQ (go-live, FSA 2+, payouts) — <https://merchants.deliveroo.com/faqs>
- [4] FSA Aggregators Food Safety Charter (Oct 2022) — <https://www.foodauthenticity.global/blog/new-fsa-food-safety-charter-for-online-food-platforms>
- [5] Per-platform FHRS thresholds & ratings filtering — <https://www.foodsafetynews.com/2021/01/deliveroo-and-uber-eats-listing-outlets-with-hygiene-ratings-of-2-and-lower/>
- Just Eat sign-up (FSA ID, thresholds, suspension) — <https://foodsafetyguru.co.uk/how-to-sign-up-to-just-eats-food-safety-compliance-guide/>
- FSA Training Aide Memoire for aggregator onboarding — <https://www.gov.uk/government/publications/training-aide-memoire-for-aggregator-onboarding>
- Uber — documents needed to sign up (global) — <https://help.uber.com/en/merchants-and-restaurants/article/what-documents-do-i-need-to-complete-sign-up?nodeId=aa38b316-1406-46fa-8d9b-1177c549eeae>

### Vendor legal requirements
- [6] Starting a food business (registration, allergens, delivery) — <https://www.gov.uk/guidance/starting-a-food-business>
- [7] Food Hygiene Rating Scheme — <https://www.food.gov.uk/safety-hygiene/food-hygiene-rating-scheme>
- [8] Reg (EC) 852/2004, Art 5 — HACCP procedures — <https://www.legislation.gov.uk/eur/2004/852/article/5>
- [9] PPDS / Natasha's Law allergen labelling — <https://www.food.gov.uk/business-guidance/introduction-to-allergen-labelling-changes-ppds>
- [10] Employers' liability insurance (£5m, compulsory) — <https://www.gov.uk/employers-liability-insurance>
- [11] Catering, takeaway food & VAT (Notice 709/1) — <https://www.gov.uk/guidance/catering-takeaway-food-and-vat-notice-7091>
- [12] Alcohol licensing (premises + personal licence) — <https://www.gov.uk/guidance/alcohol-licensing>
- [13] ICO data protection fee — <https://ico.org.uk/for-organisations/data-protection-fee/data-protection-fee/>
- Allergens for distance selling — best practice — <https://www.food.gov.uk/business-guidance/allergen-information-for-non-prepacked-foods-best-practice-distance-selling-and-pre-ordering>
- Natasha's Law — England (SI 2019/1218) — <https://www.legislation.gov.uk/uksi/2019/1218/contents/made>
- VAT registration threshold £90,000 (Apr 2024) — <https://www.gov.uk/government/publications/vat-increasing-the-registration-and-deregistration-thresholds/increasing-the-vat-registration-threshold>

### Platform / marketplace legal duties
- [14] Consumer Contracts Regs 2013, reg 28 (exemptions) — <https://www.legislation.gov.uk/uksi/2013/3134/regulation/28>
- [15] DMCC Act 2024 — consumer provisions in force 6 Apr 2025 — <https://cms.law/en/gbr/legal-updates/the-dmcc-act-consumer-elements-come-into-force-from-6-april-2025>
- [16] Stripe Connect & PSD2 — licensed-PSP model — <https://stripe.com/guides/frequently-asked-questions-about-stripe-connect-and-psd2>
- [17] ICO — controller vs processor determination — <https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/controllers-and-processors/controllers-and-processors/how-do-you-determine-whether-you-are-a-controller-or-processor/>
- FCA — Payment Services & E-Money Regulations — <https://www.fca.org.uk/firms/payment-services-regulations-e-money-regulations>
- FSA — food in the platform economy (research) — <https://www.food.gov.uk/research/emerging-challenges-and-opportunities/food-in-the-platform-economy>
- DMCCA — drip pricing rules — <https://www.taylorwessing.com/en/insights-and-events/insights/2025/04/dmcca-drip-pricing>

### Delivery / couriers
- [18] Gig-economy right-to-work extension (2025) — <https://knowledge.dlapiper.com/dlapiperknowledge/globalemploymentlatestdevelopments/2025/expansion-of-illegal-working-regime-to-gig-economy>
- [19] Reg (EC) 852/2004, Annex II — food transport — <https://www.legislation.gov.uk/eur/2004/852/annex/II>
- IWGB v CAC [2023] UKSC 43 (Deliveroo) — <https://www.bailii.org/uk/cases/UKSC/2023/43.html>
- Uber BV v Aslam [2021] UKSC 5 — <https://en.wikipedia.org/wiki/Uber_BV_v_Aslam>
- Home Office RTW consultation (Nov 2025) — <https://www.taylorwessing.com/en/insights-and-events/insights/2025/11/law/home-office-consults-on-expanding-right-to-work-checks-to-gig-economy-and-zero-hours-workers>

### Automation & tooling
- [20] FSA FHRS Open Data API — help & endpoints — <https://api.ratings.food.gov.uk/help>
- [21] Companies House Public Data API — <https://developer.company-information.service.gov.uk/>
- [22] Stripe Connect — account types & controller properties — <https://docs.stripe.com/connect/accounts>
- [23] Stripe Identity (+ Onfido / Persona / GBG) — <https://stripe.com/identity>
- [24] E-signature UK legal validity — <https://www.docusign.com/en-gb/products/electronic-signature/legality>
- Stripe Connect — onboarding flows — <https://docs.stripe.com/connect/onboarding>
- FSA — bulk FHRS open data downloads — <https://ratings.food.gov.uk/open-data>

---

*Compiled 10 July 2026 for J'Toye OaaS from a fan-out of four parallel research agents (platform onboarding & vetting · platform legal duties · vendor legal duties · automation tooling), each web-searching primary UK sources with unverified claims flagged. Research synthesis to inform product and legal scoping — not legal advice.*
