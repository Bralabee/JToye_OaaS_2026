# 261104-public-content: persona close-out, public-content lane

Branch `fix/persona-public-content`. Frontend only (`frontend/**`), no migration, no API, no Java.

## Issues

| Issue | Claim | Fix in this PR | Not in this PR |
|---|---|---|---|
| #803 (UXT-031) | "Start your application" dead-ends at a Keycloak login; no way to reach a person | Both CTAs on /for-operators now go to `/contact#apply`, a section with the steps, a mailto/tel route when configured, the merchant terms, and a sign-in link for already-approved operators. The vendor sign-in page gains an "Apply" route. | Setting `FRONTEND_PUBLIC_CONTACT_EMAIL/PHONE` (owner repo variables); a lead-capture form (needs a backend endpoint); Keycloak vendor self-registration (ruled out of scope) |
| #804 (UXT-032) | No merchant terms, /pricing, /about; no VAT basis | New `/pricing`, `/about`, `/legal/merchant-terms` (DRAFT, solicitor review pending); VAT basis sentence in `lib/pricing.ts` shown on /pricing, /for-operators, the merchant terms; card fee stays a link to Stripe's pricing page (ruling 2026-11-01) | Solicitor review of the draft terms |
| #878 UXT-119 | No vendor-to-vendor confidentiality statement; London / West African positioning | Confidentiality statement on /for-operators and the merchant terms; positioning ruled "UK-wide, launching first in Birmingham and the West Midlands" and applied to /for-operators, its metadata and /about | UXT-114 (already fixed by PR #901) |
| SW-CLR-03 | UK-wide landing vs London pilot operator page | Same positioning line on every public page | |
| SW-CLR-06 | Pricing has no VAT basis | VAT basis sentence (owner ruling 2026-11-04) | |

## Owner rulings 2026-11-04 (cited in code headers)

1. VAT basis: prices include VAT for VAT-registered businesses and carry no VAT for businesses that are not VAT-registered. No VAT number or registration status is invented for J'Toye.
2. Positioning: UK-wide, launching first in Birmingham and the West Midlands. Not London.
3. Merchant terms: drafted by the lane, marked as a draft to be reviewed by a solicitor, not in force.

## Test plan (test first, RED recorded in SUMMARY.md)

- `app/__tests__/public-content-pages.test.tsx` (new): /pricing, /about, /legal/merchant-terms render and carry metadata (title, description, canonical, openGraph); VAT sentence on all three surfaces; draft marker; sitemap lists the new routes; footer links them; no London / West African positioning left on /for-operators; confidentiality statement; CTA destination; /contact#apply; sign-in apply link.
- Updated: `operator-pitch.test.tsx` (CTA href, positioning). `terms-page.test.tsx` is unchanged in meaning: /legal/terms still invents no legal term, and links the merchant terms.
- Gates: jest (targeted, then full), lint, build, axe public-a11y spec if the local Playwright run works, docs gates, branch-behind-base.

## Threat notes

No new input, no API, no client state. New external link is Stripe's pricing page (existing constant). `mailto:`/`tel:` come from existing build-time config. No tenant or shop id on the client. No new third-party script (CSP unaffected). The draft terms make no statement the platform cannot back: settlement, Managed Risk and the tenant-isolation sentence are each taken from ADR-0007, the Phase 30 ruling and the RLS design.

## Design

`design/` records nothing for these three surfaces. They reuse the existing legal/contact layout (PublicShell, PolicyPage, legal link classes) and the operator-page table idiom; no new visual design was introduced, so no [OWNER] design step was decided. Returned to the orchestrator in the lane report.
