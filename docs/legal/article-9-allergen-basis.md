# Article 9 basis for allergen data

**Status:** Determination recorded 2026-07-30. One code change shipped with it; one item remains open.
**Refs:** ADR-0004 (knowledge-graph strategy), [`derivation-clause.md`](derivation-clause.md) (Art. 9 excluded from derivation)

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

1. **Confirm this determination with an adviser** — specifically the controller/processor split, which
   drives everything else.
2. **Add the vendor-facing notice** on the allergen checkboxes (small frontend change, no schema),
   so the duty is visible at the point of entry.
3. **Build the consent record** when the vendor-facing allergen feature is next touched.
4. **Write the privacy notice** — there is currently none; the operator's `/legal` page carries
   company registration details only.
