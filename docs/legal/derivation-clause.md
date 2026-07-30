# Data derivation clause — ToS and DPA

**Status:** DRAFT — not legally effective. See *Effectiveness gate* below.
**Drafted:** 2026-07-30
**Refs:** ADR-0004 (knowledge-graph strategy) — this clause is the precondition on its Layer B.

> **This is a draft for review by a qualified solicitor, not legal advice.** It was written by an
> engineer to be precise about what the platform technically does, so that a lawyer has something
> concrete to correct rather than a blank page. Do not publish or rely on it unreviewed.

## Why this exists, and why now

ADR-0004 identified the right to derive aggregate and reference data from tenant data as a
**precondition** rather than a follow-up. The reasoning is timing, not law:

- Adding it before vendors accumulate data costs one paragraph in a document that does not yet exist.
- Adding it afterwards requires notifying and re-obtaining acceptance from every existing vendor,
  and **any vendor who declines puts a permanent hole in the derived asset** — the ontology and the
  benchmark cohorts would have to exclude them retroactively.

There is currently **no Terms of Service and no Data Processing Agreement in this repository**. The
only legal surface is the operator's Companies House trading disclosure — `export default function LegalPage()` at `frontend/app/legal/page.tsx:21` —
which carries registration details and nothing contractual. So this clause has nowhere to live yet; it is drafted now so that it
is ready the moment those documents are written, and so the engineering invariants it depends on can
be built alongside ADR-0004 rather than retrofitted.

## The legal architecture

The clause deliberately separates three categories, because collapsing them is the usual mistake:

| Category | Example | Basis | Treatment |
|---|---|---|---|
| **Vendor Business Data** — not personal data | product titles, ingredient lists, allergen declarations, categories, prices, prep times, opening hours | contractual licence | ToS clause (Part A) |
| **Personal data** | customer names, emails, addresses, order histories | UK GDPR applies | DPA clause (Part B) — usable only once genuinely anonymised |
| **Special category data (Art. 9)** | `Customer.allergenRestrictions` — health data | Art. 9 condition required | **Excluded entirely.** See Part C. |

The commercially valuable material — the ingredient ontology, the category taxonomy, the
establishment/identity resolution — sits almost entirely in the **first** row. Ingredient lists and
allergen declarations are product data, not personal data. That is what makes the derived asset
obtainable without leaning on consumer consent at all.

---

## Part A — Terms of Service (vendor-facing)

> ### N. Platform data and derived datasets
>
> **N.1 Your data stays yours.** You retain all ownership of the content you upload to the platform,
> including your catalogue, product descriptions, ingredient lists, allergen declarations, images and
> business details ("**Vendor Business Data**").
>
> **N.2 Licence to operate and improve the platform.** You grant us a non-exclusive, worldwide,
> royalty-free licence to host, reproduce, adapt and display your Vendor Business Data so far as
> necessary to provide the platform to you, and to analyse it in order to create Derived Data as
> described below.
>
> **N.3 Derived Data.** "**Derived Data**" means aggregated, statistical, structural or reference
> datasets that we create by analysing Vendor Business Data across the platform — for example a
> canonical ingredient and allergen reference, a product category taxonomy, or aggregate market
> statistics. Derived Data does not identify you, your business, or any individual, and does not
> reproduce your Vendor Business Data.
>
> **N.4 Ownership of Derived Data.** We own all Derived Data and may use it for any lawful purpose,
> including improving the platform, developing new products, and providing services to third parties.
>
> **N.5 What we will not do.** We will not publish, license or sell Vendor Business Data in a form
> attributable to you or your business, other than the catalogue information you have chosen to make
> publicly visible on your storefront, without your prior written consent.
>
> **N.6 Survival.** Sections N.3 and N.4 survive termination of this agreement in respect of Derived
> Data created before termination. Termination does not require us to delete or unwind Derived Data,
> which by its nature cannot be attributed back to you.
>
> **N.7 Personal data.** Where Vendor Business Data contains personal data, our processing is
> governed by the Data Processing Agreement, which takes precedence over this section.

**Drafting notes for the reviewer**

- N.6 is the commercially load-bearing sentence. Without it, one vendor offboarding could be argued
  to require unwinding the shared ontology — which is not technically possible once a canonical
  ingredient node has been merged from many sources, and would make the asset unfinanceable.
- N.5 is the counterweight that makes N.4 acceptable to a vendor: we take the *statistics*, never the
  attributable content.
- N.3's "does not identify" is a promise the engineering must actually keep — see Part D.

---

## Part B — Data Processing Agreement (personal data)

> ### M. Anonymous statistical data
>
> **M.1** We process personal data on your behalf as your processor, on your documented instructions,
> for the purposes set out in this Agreement.
>
> **M.2** You additionally instruct us to produce **Anonymous Statistical Data** from personal data
> processed under this Agreement. Anonymous Statistical Data is data rendered anonymous in such a
> manner that the data subject is not, or is no longer, identifiable — such that it does not
> constitute personal data under Article 4(1) UK GDPR and falls outside the scope of the Regulation.
>
> **M.3** In producing Anonymous Statistical Data we will:
>
> a. aggregate across a minimum cohort, and suppress any output cell below that threshold;
> b. never publish or disclose a value derived from fewer than the threshold number of businesses or
>    data subjects;
> c. exclude free-text fields capable of carrying identifying content;
> d. not attempt, and contractually require our recipients not to attempt, to re-identify any
>    individual or to single out any individual from Anonymous Statistical Data;
> e. exclude entirely the special categories of data described in clause M.5.
>
> **M.4** Once produced, Anonymous Statistical Data is not personal data, and clauses in this
> Agreement concerning deletion, return, and data subject rights do not apply to it. Personal data
> from which it was derived remains subject to this Agreement in full.
>
> **M.5 Special category data excluded.** We will not derive Anonymous Statistical Data from, and
> will exclude from all derivation processes, any special category data within the meaning of
> Article 9 UK GDPR, including data concerning health such as customer-declared allergies,
> intolerances or dietary medical requirements.
>
> **M.6** Nothing in this clause authorises us to process personal data for our own purposes as a
> controller, other than as expressly permitted by applicable law.

**Drafting notes for the reviewer**

- M.2 is deliberately framed as **anonymisation**, not pseudonymisation. Pseudonymised data remains
  personal data; genuinely anonymised data does not (Recital 26). The ICO's test is whether
  identification is reasonably likely by any means reasonably likely to be used — which is why M.3
  carries operative guarantees rather than an assertion.
- M.5 is the clause most likely to be dropped as boilerplate. It should not be.
  the field `private Integer allergenRestrictions` already exists and defaults to 0 — `core-java/src/main/java/uk/jtoye/core/customer/Customer.java:58`
  — and is populated in production. A **vendor cannot
  consent on its own customers' behalf** to Article 9 processing, so no ToS or DPA wording can make
  allergen data derivable. It requires explicit consent from the data subject, obtained separately.
- M.6 guards the processor boundary: a processor that starts using personal data for its own
  purposes becomes a controller for those purposes, with all the obligations that follow.

---

## Part C — What this clause does *not* cover

Recorded explicitly so it is not assumed later:

1. **Article 9 allergen data.** Excluded by M.5. The separate question of the lawful basis for
   *storing and using* `Customer.allergenRestrictions` at checkout today is a live item and is
   **not** resolved by this clause.
2. **Companies House data redistribution.** Governed by the Companies House API terms, which are a
   separate check.
3. **Open Food Facts derived data.** Governed by ODbL share-alike, addressed architecturally in
   ADR-0004 (provenance tagging, no merge into the proprietary layer). A contract with vendors
   cannot override a licence granted by a third party.
4. **Existing vendors.** A clause in a new ToS binds those who accept it. Any vendor already on the
   platform requires notice and fresh acceptance.

## Part D — Engineering invariants this clause commits us to

The clause promises things the code must actually do. These become acceptance criteria on the
ADR-0004 Layer B work, and each must be shown to **fail** before it is trusted:

| Promise | Invariant | Enforced by |
|---|---|---|
| N.3 "does not identify you" | Layer B has no `tenant_id` column | contract test (ADR-0004) |
| M.3(a)(b) cohort threshold | aggregate queries suppress cells below threshold, **fail closed** | query-level, not reporting-level |
| M.3(c) no free-text | derivation reads an allow-list of columns, never `SELECT *` | projection code review + test |
| M.5 Art. 9 excluded | `allergen_restrictions` on a deny-list the projection cannot read | contract test asserting absence |
| N.5 no attributable disclosure | reference API role holds zero `GRANT`s on tenant tables | database role, not application logic |

**Threshold values are deliberately not fixed here.** They are a configuration decision, and per the
project's config rule must live in the config layer rather than as literals. A common starting point
is a minimum of 5 contributing businesses and 20 underlying data subjects per published cell, but the
defensible number depends on the cohort's uniqueness and should be set with the reviewer.

## Effectiveness gate

This clause is **not in force**. It becomes effective only when all of the following are true:

- [ ] A Terms of Service document exists and incorporates Part A.
- [ ] A Data Processing Agreement exists and incorporates Part B.
- [ ] Both have been reviewed by a qualified solicitor with UK data-protection competence.
- [ ] Vendors have accepted them — new vendors at signup, existing vendors by notice and acceptance.
- [ ] The Part D invariants are implemented and their tests shown to fail against a broken input.

Until every box is ticked, **no cross-tenant derivation may be performed and Layer B must contain no
data derived from tenant data.** Reference data sourced independently (FHRS, Companies House, vendor
catalogue content already public on a storefront) is not blocked by this gate.
