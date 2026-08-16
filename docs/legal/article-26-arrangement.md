# Article 26 joint-controller arrangement — platform and vendor

**Status:** DRAFT — not legally effective. See *Effectiveness gate* below.
**Drafted:** 2026-08-16
**Refs:** Phase 31 decisions D-14 (the allocation), D-15 (the layered notice), D-16/D-17 (the single
point of contact, built not promised), D-18 (this document is authored here; acceptance is Phase 32).
Sits beside [`article-9-allergen-basis.md`](article-9-allergen-basis.md) and
[`derivation-clause.md`](derivation-clause.md); it does not restate either.

> **This is a draft for review by a qualified solicitor, not legal advice.** It was written by an
> engineer to be precise about what the platform technically does, so that a lawyer has something
> concrete to correct rather than a blank page. Do not publish or rely on it unreviewed.

> **Every legal position in this document requires adviser confirmation.** Each one is marked
> **[ADVISER]** where it appears and all of them are collected in *Positions requiring adviser
> confirmation* at the end. Nothing here is settled. The engineering facts — what is stored, what
> runs, what is gated — are measured from the repository and are stated as facts; the legal
> characterisation of those facts is not.

---

## Why this document exists

Article 26(1) UK GDPR: where two or more controllers **jointly determine** the purposes and means of
processing, they are joint controllers, and they *shall* in a transparent manner determine their
respective responsibilities for compliance by means of an **arrangement** between them.

Article 26(2): the **essence** of that arrangement *shall be made available to the data subject*.

Two consequences shape everything below:

1. The arrangement is a document that must exist. It exists as this file. **[ADVISER]**
2. Part of it must be published in plain language. That is the *Essence, for publication* section,
   and it is the deliverable rather than an appendix — the privacy notice reproduces it or links to
   it, and must not paraphrase it, because a paraphrase of an Article 26(2) essence is a second,
   differently worded arrangement.

There is currently **no Terms of Service and no Data Processing Agreement in this repository** —
the same starting position `derivation-clause.md` records. This arrangement is drafted now so that it
is ready the moment those documents are written, and so that the engineering it depends on is built
alongside rather than retrofitted.

---

## The parties

| Party | Identity |
|---|---|
| **The platform operator** ("we", "the platform") | **J'Toye Digital Ltd**, company number **16471464**, registered in **England & Wales**. |
| **The vendor** ("you") | The business operating a shop on the platform, under its own legal identity, supplied at onboarding. |

**Company identity is disambiguated by number, deliberately.** The registered name is not unique on
the Companies House register — a **dissolved** company of a closely similar name exists, and this
platform's own public marketing site has already cited the wrong one once. This arrangement is made
by company number **16471464** and by no other; any number that is not 16471464 in a J'Toye legal
document is a defect, not a variant. The number is held in code at
`frontend/lib/company.ts:33` as `DEFAULT_COMPANY_NUMBER`, alongside a comment recording the
dissolved namesake so the next reader does not have to rediscover it.

**Open item — the registered office is not recorded in this repository.** `getCompanyInfo()` returns
an empty string for it unless `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is supplied as a build
argument, and no default exists in code. An Article 26 arrangement and a privacy notice both need a
postal address for the controller. **The owner must supply it**; it is not invented here.

**The vendor's identity is captured but optional.** `vendor_onboarding.company_number` exists and is
verified against Companies House by the `BUSINESS_VERIFIED` gate, but the field is deliberately
nullable because a sole trader has no company number. So the vendor party to this arrangement is
identified by its trading identity and, where it has one, its registered company number. **[ADVISER]**
— whether a nullable company number is sufficient identification of a joint controller for Article
26 purposes is a question for review.

---

## The two lines, and why they are different questions

`/legal` — the platform's public Companies House disclosure page — already draws a line, and it is a
**trading** line:

> *"Individual vendor shops listed on the platform are run by their own businesses and remain
> responsible for their own trading disclosures."*

The line drawn in this document is a **data-protection** line, and it is a different line with a
different answer. Both are true at the same time:

| Question | Answer | Where it is drawn |
|---|---|---|
| Who is responsible for **trading disclosures** — the business identity, the terms of sale, the food a consumer receives? | **The vendor**, for its own shop. | `/legal` |
| Who is responsible for **personal data** generated when a consumer orders? | **Both**, jointly, for consumer order data. | This document |

They do not conflict because they answer different statutory questions. A vendor can be solely
responsible for what it sells and jointly responsible with the platform for the personal data
created by selling it. A regulator will read both pages, so this paragraph exists to say so
explicitly rather than leave the reconciliation to the reader. **[ADVISER]**

---

## The allocation, by data category

Controller roles are determined **per processing operation**, not per company and not per table. The
same vendor is a joint controller for one category on this list and the sole controller for another.
Stating the allocation in the abstract — "we are joint controllers" — is unactionable, so it is
stated by category. **[ADVISER]** on every row.

| Data category | What it is, concretely | Role |
|---|---|---|
| **Consumer order data** | The order record and its contents: `customer_name`, `customer_email`, `customer_phone`, the UK delivery address (`address_line1`, `address_line2`, `address_city`, `address_postcode`), order notes, line items, totals and VAT, fulfilment type, order status history, and the payment status/reference held against the order. | **Joint controllers** — platform and vendor. |
| **Consumer storefront account** | The consumer's login identity in the platform-operated `jtoye-customers` Keycloak realm, and the order history surfaced back to them across shops. | **Platform, sole controller.** |
| **Vendor staff accounts** | Staff login identities in the platform-operated staff realm, and the per-shop access grants (`shop_staff`, `user_directory`) that scope what each member of staff can see. | **Platform, sole controller** of the account records; the vendor determines who is granted access. |
| **Vendor's own customer records** | The vendor's CRM rows in `customers` — name, email, phone, notes — entered by the vendor on its own dashboard for its own purposes. | **Vendor controller, platform processor.** |
| **Customer allergen profile** (`Customer.allergenRestrictions`) | Special category data. | **Vendor controller, platform processor** — unchanged. See *Special category data* below. |
| **Reviews** | Consumer-submitted ratings and comments attached to an order. | **Joint controllers** — published on the vendor's storefront, hosted and moderated by the platform. |
| **Marketing communications** | Marketing opt-in state and suppression, per tenant and recipient. | **Vendor determines the purpose**; the platform acts on its instruction through the consent gate. |
| **Payment card details** | Not held. Card data is entered into Stripe's own hosted `PaymentElement` in the consumer's browser and never traverses the platform; the platform creates a payment intent and, for marketplace vendors with an enabled connected account, routes funds as a Stripe destination charge. | **Stripe acts in its own right** for the card data. **[ADVISER]** — Stripe's own controller/processor characterisation must be taken from Stripe's terms, not asserted here. |

### The one row that must not be misread

The joint-controller allocation for **consumer order data** does **not** displace the determination
already recorded in [`article-9-allergen-basis.md`](article-9-allergen-basis.md) for the stored
allergen field. That document's Finding 1 holds unchanged: the vendor populates
`Customer.allergenRestrictions` for its own purpose, so **the vendor is the controller and the
platform is the processor**, and the platform can neither hold nor obtain the Article 9(2) condition.
Different category, different operation, different answer — which is exactly why this section is a
table of categories and not a sentence.

### Why "joint" is the honest answer for order data

The platform determines a substantial part of the *means*: the checkout flow, what fields are
collected, the storefront, the order state machine, where the data is stored, how long it is kept,
and how payment is routed. The vendor determines the *purpose* of fulfilling the order and
determines what it does with the customer afterwards. Neither party can characterise the other as a
mere processor for this category without the characterisation being false in practice. **[ADVISER]**

---

## The single point of contact for data subjects

Article 26(1) permits, and Article 26(3) makes practically necessary, a **contact point** for data
subjects — and Article 26(3) is the reason it matters: irrespective of the terms of the arrangement,
**the data subject may exercise their rights against each controller**. An arrangement that routes
requests badly does not limit the data subject; it only makes the controllers slower.

**The platform runs the single point of contact.** A consumer who has ordered from several vendors on
the platform lodges one request, not one per shop.

### How that is compatible with the tenant wall

This is a genuine design tension and it is worth publishing rather than hiding, because the
resolution is the unusual part.

A single cross-tenant request desk looks like it requires a platform employee who can read across
every vendor's data. **This platform does not have that identity and has deliberately refused to
create one, twice.** The reconciliation is:

- **Intake is a request path.** A data subject lodges a request over a public, unauthenticated,
  rate-limited endpoint. It is answered with an acknowledgement that is deliberately identical
  whether or not any vendor on the platform holds that person's data — otherwise the contact point
  would itself become a way to ask "which of your vendors knows this person?", which is precisely
  what the tenant separation exists to withhold. The stored identifier is a one-way **SHA-256 hash**
  of the email address, never the plaintext, following the rule already carried by `ErasureRecord`
  at `core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java:39` — `subject_email_sha256`.
  A privacy feature must not create a new store of plaintext contact details.
- **Execution is a background path.** A scheduled job, not a person, works through the tenants one
  at a time — pinning each tenant in turn and acting only within that tenant — and writes one
  durable erasure record per tenant that held the subject. Row-level security still scopes every read
  and write to the pinned tenant; the background declaration is an authorisation marker, **not** a
  tenancy escape.
- **The boundary between the two is a written engineering rule, not a convention.** The rule that a
  request thread never enters `asSystem` is recorded at `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:640`.
  It reads: *"A request thread never enters `asSystem` (only background entry points that act as the
  system do)"*.

**No human at the platform holds cross-tenant read of vendor data.** Only a background job crosses
tenants, and only to execute a request a data subject lodged. This is a real property of the design
and it is stated in the published essence because a data subject and a regulator are both entitled to
know how a single desk is reconciled with a claim of separation between vendors.

### What exists today, and what this phase builds

Stating this precisely matters more than stating it favourably: an arrangement that describes
capabilities that do not exist is worse than one that describes fewer.

| Capability | Status at the date of this draft |
|---|---|
| Article 17 erasure and Article 20 export, per customer, per tenant | **Shipped**, in `uk.jtoye.core.gdpr`, behind the vendor-admin role. Implemented and tested. |
| Durable, PII-free record that an erasure happened | **Shipped** — `erasure_records`, storing a hashed subject identifier and counts. |
| Consumer-facing intake for a data-subject request | **Built in this phase** (Phase 31). It did not exist before it; the shipped endpoints are vendor-admin, single-tenant and keyed by an internal customer id. |
| Background cross-tenant execution of a lodged request | **Built in this phase** (Phase 31). |
| Published privacy notice pointing at the contact route | **Built in this phase** (Phase 31). |

Until the two "built in this phase" rows are live in the delivered runtime, the contact-point
commitment in this arrangement is a commitment and not a description. That is why it appears in the
effectiveness gate below.

---

## Respective responsibilities

Article 26(1) requires the allocation of responsibilities, and **transparency in particular** — the
duty under Articles 13 and 14 must be allocated explicitly. **[ADVISER]** on every row.

| Responsibility | Platform | Vendor |
|---|---|---|
| **Transparency (Arts. 13–14)** for consumer order data | Maintains **one** platform privacy notice covering the shared processing, and makes the essence of this arrangement available in it. | Is **named to the consumer at the point of order** as the other party, so the consumer knows which business they are buying from. Remains responsible for any additional notice it gives in its own channels. |
| **First response to a data subject** | Runs the single point of contact; acknowledges and executes. | Must forward to the platform contact point any request it receives directly, and must not action a cross-vendor request alone. |
| **Right of access / portability (Arts. 15, 20)** | Provides the export machinery and executes it per tenant. | Responsible for the accuracy and completeness of the records it holds. |
| **Erasure (Art. 17)** | Executes the anonymisation across every tenant that held the subject and records that it happened. | Must not re-create erased records from its own exports or offline copies. |
| **Rectification (Art. 16)** | Provides the means. | Performs it for the records it controls. |
| **Lawful basis for the vendor's own purposes** | None claimed. | The vendor's, entirely — including any marketing to its customers, and including the Article 9(2) condition for the allergen field. |
| **Security (Art. 32)** | Tenant separation at the database, transport security, access control, secret handling, backup and restore of the platform. | Access hygiene within its own shop: who is granted staff access, and to which shops. |
| **Personal data breach (Arts. 33–34)** | Detects and assesses breaches of the platform; notifies the ICO where the platform is the controller or the breach is platform-wide; notifies affected vendors without undue delay. | Notifies the platform without undue delay of any breach it becomes aware of; notifies the ICO where it is the controller. |
| **Retention** | Publishes and enforces the platform retention schedule. | May not instruct a retention period longer than the published schedule without an agreed variation. |
| **Sub-processors** | Maintains the list and gives notice of changes. | — |

---

## Special category data

The processor-side commitment is **not restated here in new words**. It is the block already drafted
in [`article-9-allergen-basis.md`](article-9-allergen-basis.md) § *"DPA wording to add"*, reproduced
verbatim so that one wording exists rather than two:

> We process special category data (including customer allergen and dietary-health information) only
> on your documented instructions and solely to provide the platform to you. You are the controller
> of that data and are responsible for establishing and evidencing an Article 9(2) condition for it.
> We will not use it for our own purposes, and it is excluded from Anonymous Statistical Data under
> clause M.5.

The derivation exclusion referenced in that block is **clause M.5** of
[`derivation-clause.md`](derivation-clause.md) — Article 9 data is excluded from all derivation. It
is cross-referenced, not restated. Two differently worded versions of one exclusion is how a
contradiction gets created, and M.5's exclusion is the one that must survive: a vendor cannot consent
on its own customers' behalf, so no wording in any agreement can make allergen data derivable.

A further determination, recorded and dated in the Article 9 document, applies to this arrangement:
**the platform does not consult the stored customer allergen profile at checkout.** What a consumer
is shown at checkout is the aggregated allergen set of the **order** they are placing — product data,
not health data — which they acknowledge. The platform never learns a consumer's allergies.

---

## Essence, for publication

> The section between the two markers below is the Article 26(2) **essence**. It is written for a
> consumer, uses no defined terms, and is the text the platform privacy notice reproduces or links
> to. **Reproduce it; do not paraphrase it** — a paraphrase is a second arrangement in different
> words, and the two will drift.

<!-- ESSENCE:BEGIN -->

### Who is responsible for your information when you order

When you order food through J'Toye, two businesses are involved: **J'Toye Digital Ltd** (company
number 16471464), which runs the platform, and **the shop you ordered from**, which makes and
supplies your food. The shop is named to you when you order.

For the information created by your order — your name, contact details, delivery address, what you
ordered and what you paid — **J'Toye and the shop are jointly responsible**. J'Toye decides how the
platform collects and stores it; the shop decides what it needs in order to serve you.

Some things are J'Toye's responsibility alone. Your J'Toye storefront account and password are ours,
not the shop's. Records a shop keeps about you for its own reasons — including any note it makes of
your allergies — are the shop's responsibility, and J'Toye only stores them on the shop's behalf.
**J'Toye does not check your order against any allergy information a shop has recorded, and does not
hold allergy information about you.** What you are shown at checkout is what the shop has declared
about the food in that order.

**You can contact J'Toye about your information, once, for every shop you have ordered from.** You do
not need to contact each shop separately. We will act on your request across every shop that holds
your details. You can also contact any shop directly, and you can complain to the Information
Commissioner's Office at any time.

**No J'Toye employee can browse across shops to look at your details.** Requests are carried out by
an automated process that works through one shop at a time. That is deliberate: it is how we can
offer you a single place to ask while keeping each shop's records separate from every other shop's.

The full arrangement between J'Toye and the shops is a written document. This is its essence, which
we publish because the law requires us to make it available to you.

<!-- ESSENCE:END -->

---

## What is deliberately NOT in scope of this document

Recorded explicitly so nothing is inferred from silence:

1. **Acceptance is not in scope.** This phase authors the arrangement. **Tracking a vendor's
   acceptance of it, presenting it during onboarding, and e-signature are Phase 32** (decision D-18).
   **No acceptance flow exists, no vendor has signed this, and no signature is recorded anywhere in
   this platform.** A reader must not infer one from the existence of this document.
2. **This is not the Data Processing Agreement.** The DPA and the Terms of Service still do not exist
   in this repository. This arrangement is drafted to sit alongside them and to be incorporated by
   them.
3. **It does not vary the derivation clause.** `derivation-clause.md` remains a separate DRAFT with
   its own effectiveness gate. Nothing here brings it into force.
4. **It does not change the Article 9 determination.** That document is authoritative for the
   allergen field; this one cross-references it.
5. **Existing vendors.** An arrangement binds those who enter into it. Any vendor already on the
   platform requires notice and fresh acceptance — which is Phase 32's problem, and is named here so
   it is not forgotten.

---

## Positions requiring adviser confirmation

Every item below is **LOW confidence by design** and is written to be corrected. None of it is
settled, and none of it should be relied on before review.

1. That the platform and vendor are **joint controllers** for consumer order data at all, rather than
   controller/processor in one direction.
2. The **category-by-category allocation** in the table above — in particular the storefront account
   and staff account rows, and the reviews row.
3. That the **Article 9 allergen field** stays vendor-controller / platform-processor while order
   data is joint — i.e. that the two determinations genuinely coexist rather than one overriding the
   other.
4. Whether a **nullable vendor company number** sufficiently identifies a joint controller.
5. The **transparency allocation**: one platform notice plus naming the vendor at the point of order.
6. The **breach-notification split**, which is the row most likely to be wrong in practice.
7. **Stripe's** role for card data, which must be taken from Stripe's own terms.
8. Whether the **essence** as drafted is sufficient for Article 26(2), and whether it must also be
   made available in the ordering flow rather than only in the privacy notice.
9. Whether the **single point of contact** as designed satisfies Article 26(1)'s contact-point
   provision given that Article 26(3) preserves the data subject's right to proceed against either
   controller regardless.

---

## Effectiveness gate

This arrangement is **not in force**. It becomes effective only when all of the following are true:

- [ ] It has been reviewed by a qualified solicitor with UK data-protection competence, and every
      position in *Positions requiring adviser confirmation* has been confirmed or corrected.
- [ ] The registered office address has been supplied by the owner and appears in this document and
      in the published notice.
- [ ] A Terms of Service and a Data Processing Agreement exist and incorporate it.
- [ ] An **acceptance mechanism exists** and vendors have accepted — new vendors at onboarding,
      existing vendors by notice and acceptance. **This is Phase 32 work and does not exist today.**
- [ ] The single point of contact is **live in the delivered runtime** — both the intake and the
      background execution — not merely merged. A published commitment to a contact point backed by
      nothing that can execute it is the failure shape this project has paid for before.
- [ ] The essence is published in the platform privacy notice, reproduced rather than paraphrased.

Until every box is ticked, this document is an engineering draft describing an intended arrangement,
and **must not be presented to a vendor, a consumer or a regulator as an arrangement that is in
force.**
