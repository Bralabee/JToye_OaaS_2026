# Article 9 basis for allergen data

**Status:** Determination recorded **2026-07-30**. Revisited and **extended 2026-08-16** (Phase 31,
decision D-01) — see the final section, *Extension, 2026-08-16*. **Nothing in the original
determination has been withdrawn, reworded or replaced**; the extension is additive and records a
further decision that rests on Finding 1 rather than reopening it. One code change shipped with the
original; the consent record remains open, and the extension explains why this phase does not close it.
**Refs:** ADR-0004 (knowledge-graph strategy), [`derivation-clause.md`](derivation-clause.md) (Art. 9 excluded from derivation), [`article-26-arrangement.md`](article-26-arrangement.md) (the joint-controller allocation, which leaves this determination intact)

> **Not legal advice.** Written by an engineer to state precisely what the platform does with this
> data, so a qualified adviser can correct a concrete position rather than start from a blank page.

## The question

Customer allergen data is **data concerning health** and therefore special category data under
Article 9 UK GDPR. Processing it is prohibited unless an Article 9(2) condition applies, *in addition*
to an Article 6 lawful basis. The platform holds it in two places, and they turn out to have
different answers.

## Finding 1 — the stored field: the vendor is the controller, not us

`allergenRestrictions` on the customer record — `core-java/src/main/java/uk/jtoye/core/customer/Customer.java:58`
— is populated by the **vendor**, through allergen checkboxes on their own dashboard customers page
(`frontend/app/dashboard/customers/page.tsx`). The vendor decides to record it, for their own
purpose of serving that customer safely.

That makes the **vendor the controller** and **J'Toye the processor**. The consequence is the useful
part of this determination:

- **J'Toye does not need, and cannot obtain, the Article 9 condition.** A platform cannot consent on
  a vendor's customer's behalf any more than a vendor can.
- The vendor must hold it. Realistically that is **Article 9(2)(a) explicit consent** — see below.
- J'Toye's obligations are the processor's: process only on documented instructions, don't use it for
  our own purposes, support the data subject's rights, and cover it in the DPA.

### Why explicit consent, and not another condition

| Art. 9(2) condition | Applies? |
|---|---|
| (a) explicit consent | **Yes — the realistic route.** Must be explicit, informed, specific, freely given and withdrawable. |
| (c) vital interests | No. Available only where the data subject is *physically or legally incapable* of giving consent. Ordering food does not meet that. |
| (h) health or social care | No. Requires a health/social-care context and a professional obligation of secrecy. A takeaway is neither. |
| (e) manifestly made public | No. Telling one restaurant is not making data public. |

A separate Article 6 basis is still required alongside — for the vendor that is most likely 6(1)(b)
performance of a contract, or 6(1)(f) legitimate interests.

> **The trap worth naming:** recording an allergy in order to keep someone safe feels self-evidently
> benign, and benign intent is not a lawful basis. The safety purpose is what makes explicit consent
> easy to obtain; it does not remove the need for it.

## Finding 2 — the guest checkout field: removed

The public guest-order endpoint accepted `customerAllergenMask` — special category data, over an
**unauthenticated** endpoint, with no Article 9 condition recorded and no consent captured anywhere
in the flow.

Verified before removing it: **no client ever sent it.** No frontend source, test or E2E spec
referenced the field; every `allergenMask` reference in the frontend is *product* allergen data. It
was never persisted either — it was read into a local variable and used to build warning strings.

So it was an Article 9 intake channel with no consumer. Removed 2026-07-30 on data-minimisation
grounds (Art. 5(1)(c)):

- `GuestOrderRequest.customerAllergenMask` — deleted, with a comment pointing here.
- the cross-check block and the now-dead `describeAllergens` / `ALLERGEN_NAMES` in
  `PublicStorefrontService` — deleted.
- `docs/api/openapi-snapshot.json` — regenerated; the field is gone from the published contract.

**`allergenWarnings` was deliberately kept** on the confirmation DTO. The checkout UI renders it
(`frontend/app/shop/[slug]/checkout/page.tsx`) behind a `length > 0` guard, so an empty list renders
nothing and no user-visible behaviour changed. It remains as the seam a future *consented* warning
path plugs into.

## What is already satisfied

Both data-subject rights over this field are implemented **and tested**, which is better than the
starting assumption:

| Right | Implementation | Test |
|---|---|---|
| Art. 17 erasure | `customer.setAllergenRestrictions(0)` in `GdprService.eraseCustomerData` | `GdprServiceTest` asserts it reads 0 after erasure |
| Art. 20 portability | included in the customer export payload | `GdprServiceTest` asserts the exported value |

Article 9 data is also **excluded from all derivation** by clause M.5 of
[`derivation-clause.md`](derivation-clause.md) — a vendor cannot consent on its customers' behalf, so
no ToS or DPA wording can ever make this field derivable.

## What remains open

**The vendor has no mechanism to record the customer's consent, and no prompt telling them one is
needed.** Nothing in the platform captures or evidences it today. Until that exists, a vendor using
the allergen checkboxes is relying on a consent they may have obtained verbally, or not at all — and
J'Toye has no record either way.

### Design for the consent record (not built)

There is an established precedent to mirror rather than invent: `marketing_opt_in` (V54) is already a
positive-consent record keyed by tenant and recipient, with the suppression/opt-in split documented
in that migration. An allergen consent record should follow the same shape:

- keyed `(tenant_id, recipient)` like `marketing_opt_in`, tenant-scoped `ENABLE` + `FORCE` RLS;
- timestamped, so the vendor can evidence *when* consent was given;
- withdrawable — withdrawal must clear `allergen_restrictions`, reusing the erasure path that already
  sets it to 0;
- surfaced on the vendor's customers page so the checkboxes are gated on it.

### DPA wording to add

The processor-side commitments belong in the DPA alongside the derivation clause:

> We process special category data (including customer allergen and dietary-health information) only
> on your documented instructions and solely to provide the platform to you. You are the controller
> of that data and are responsible for establishing and evidencing an Article 9(2) condition for it.
> We will not use it for our own purposes, and it is excluded from Anonymous Statistical Data under
> clause M.5.

## Recommended next steps

*Original list of 2026-07-30, annotated 2026-08-16. **No step has been deleted** — a completed step
struck through still records what was asked for, which is the useful half of the record.*

1. **Confirm this determination with an adviser** — specifically the controller/processor split, which
   drives everything else.
   > **STILL OPEN (2026-08-16).** Nothing in this document has been reviewed by a qualified adviser.
   > The [Article 26 arrangement](article-26-arrangement.md) drafted alongside it carries the same
   > standing caveat and lists the controller/processor split among the positions needing
   > confirmation.
2. ~~**Add the vendor-facing notice** on the allergen checkboxes (small frontend change, no schema),
   so the duty is visible at the point of entry.~~
   > **DELIVERED.** The notice renders inside the same dialog as the checkboxes it qualifies, and it
   > is asserted — not merely written — by four tests in
   > `frontend/app/dashboard/customers/__tests__/allergen-consent-notice.test.tsx`: that it names
   > allergy details as health data, that it names explicit consent and places responsibility on the
   > vendor, that it says what to do on withdrawal, and that it lives in the same dialog as an
   > allergen checkbox. The test file states its own reason for existing: the notice is pure copy, so
   > nothing else would fail if a redesign deleted it.
3. **Build the consent record** when the vendor-facing allergen feature is next touched.
   > **STILL OPEN (2026-08-16) — deliberately, and it must not be recorded as delivered.** D-01 is a
   > decision *not to process* the stored profile, so this phase creates no new consent, withdrawal
   > or audit obligation and therefore does not require the consent record. The gap the original
   > determination named is unchanged: a vendor using the allergen checkboxes today is still relying
   > on a consent the platform holds no evidence of. The design sketched above is still the design.
4. ~~**Write the privacy notice** — there is currently none; the operator's `/legal` page carries
   company registration details only.~~
   > **CLOSED by Phase 31.** The privacy notice is written in this phase and published at
   > `/legal/privacy`, with `/legal` becoming an index over it plus `/legal/cookies`,
   > `/legal/retention` and `/legal/accessibility` (decision D-06). It carries the essence of the
   > [Article 26 arrangement](article-26-arrangement.md) and describes the Article 20 export
   > accurately, including that the allergen field is part of it.
   > *Falsifiable on its face: if `/legal/privacy` does not resolve, this annotation is wrong and the
   > phase did not finish — treat that as the defect, not as a documentation nicety.*

---

## Extension, 2026-08-16 — the decision not to consult the stored profile

**Recorded:** 2026-08-16 · **Source:** Phase 31 (Consumer-Safety and Legal Floor), decisions D-01,
D-02, D-03 · **Status of the original determination:** unchanged, and relied upon below.

> The same caveat applies to this section as to everything above it: **not legal advice**, written by
> an engineer, and the whole of it needs adviser confirmation. It is written to be corrected.

### The decision

**The platform does not consult the stored customer allergen profile — `Customer.allergenRestrictions`
— at checkout, or anywhere else in the ordering flow.**

This is a decision, taken on 2026-08-16, not an omission that has been discovered and rationalised
afterwards. The roadmap criterion for this work offers two limbs — implement the lawful-basis chain
**or** record an explicit decision not to — and this is the second limb, exercised deliberately and
dated so that a later reader can tell which it was.

### The reasoning

Consulting the field at checkout would be the platform processing special category data in a context
where **the platform has no Article 9(2) condition of its own** — which is exactly what **Finding 1**
above establishes, and it is cited here rather than re-argued. The vendor is the controller of that
field and holds (or fails to hold) the condition for it; a platform cannot consent on a vendor's
customer's behalf. Reading the field in order to warn a consumer at checkout would be the platform
using it for a purpose the platform determined, which is the step that turns a processor into a
controller — see clause M.6 of [`derivation-clause.md`](derivation-clause.md).

The practical consequences are deliberately small, and that is the point:

- The bitmask stays **vendor-entered exactly as today**. No field is added, removed or repurposed.
- **No new consent capture, withdrawal or audit obligation is created by this decision.** A decision
  not to process creates no processing to obtain a condition for.
- Nothing about the vendor's existing duty changes, and the vendor-facing notice that states that
  duty is unchanged.

### What the consumer sees instead (D-02)

The consumer is shown the **order's own aggregated allergen set** — "this order contains: milk,
gluten, sesame" — assembled from what the vendor has declared about the *products* in that order, and
they must explicitly acknowledge having read it before proceeding.

This is **product data, not health data**. No health data is read, stored or inferred at any point:

- the platform never learns the customer's allergies;
- there is **no customer-versus-product comparison anywhere** in this design;
- nothing is written back to `Customer.allergenRestrictions` by the ordering flow.

The consumer-facing wording is fixed, and it is quoted here verbatim so that this determination and
the product say the same words rather than two compatible-sounding paraphrases:

> **We do not store your allergies and we cannot check this order against them.**

That single sentence is this decision stated to the person it affects. Wording that would contradict
it — "allergen-free", "safe for you", "no allergens present", "matches your profile", or anything
else implying the platform knows or has checked the consumer's allergies — is prohibited on the
consumer surface, because it would claim a processing operation this document records as not
happening.

It also agrees, from the other side, with the vendor-facing notice asserted by
`frontend/app/dashboard/customers/__tests__/allergen-consent-notice.test.tsx`: the vendor is told the
Article 9 duty is theirs, and the consumer is told the platform is not doing the checking. Two sides
of one determination.

### The reconciliation added (D-03)

Alongside the decision above, this phase adds a **product-level** reconciliation. Where a product's
free-text ingredients name an allergen that the product's declared allergen mask omits, the order
surfaces a **"Check"** flag naming the product and the allergen.

Three properties of that flag matter to this determination:

1. **It is product data on both sides.** Ingredients text and the declared mask are both attributes
   of the product, entered by the vendor. Neither is special category data, and the comparison is
   product-to-product. It is not, and must never become, product-to-customer.
2. **It is advisory. It never rewrites the vendor's declaration.** The declared mask remains the
   vendor's statement; the flag says the two vendor-entered fields disagree, and asks. The legally
   operative PPDS / Natasha's-Law label pipeline continues to read the vendor's declaration.
3. **It attacks the real defect.** Every allergen statement in the product today resolves to an
   integer a vendor hand-types, never reconciled against the ingredients text sitting beside it —
   and the failing direction is *under*-declaration, which is the direction that injures someone.

### What is unchanged

**The Article 20 export still includes the field.** `GdprController.CustomerExport` carries
`Integer allergenRestrictions`, and this document already records that as correct and tested — a data
subject is entitled to receive the data held about them, including this. **D-01 does not change
that**, and the two are not in tension: not consulting a field for the platform's own purposes is a
different thing from withholding it from its own subject.

The privacy notice must therefore describe the export **accurately**, and must not imply the field is
never disclosed to the person it is about.

The Article 17 erasure path is likewise unchanged: `GdprService.eraseCustomerData` sets the bitmask
to 0, and `GdprServiceTest` asserts it reads 0 afterwards.

### What remains open

**The consent record (recommended next step 3) is still open.** It is not built, it is not required
by this phase, and it must not be recorded anywhere as delivered. A decision not to process does not
retrospectively supply the vendor with the consent evidence the original determination said was
missing — it only means this phase adds no new processing that would need one.

**Adviser confirmation (recommended next step 1) is still open** and covers this extension too.
