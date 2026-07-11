---
status: complete
created: 2026-07-10
scope: documentation-and-frontend
---

# Vendor pitch and guide audit

## Goal

Create a separate, truthful public pitch for prospective Nigerian and West African takeaway and
catering operators, while cross-checking and correcting the internal business-model guide against
its evidence, product maturity, and regulatory boundaries.

## Deliverables

- Public vendor pitch route with plain-language benefits, separate takeaway and catering journeys,
  transparent pilot terms, a fit check, and safe capability boundaries.
- Focused component tests for the pitch interactions and calls to action.
- Evidence and wording corrections in the authoritative guide and its interactive version.
- Discoverability links and documentation metrics updated where required.

## Verification

- Focused Jest tests passed: `7/7` across both public marketing artifacts.
- All Docker application images and the frontend production build passed.
- Documentation freshness passed at `782` logical invocations.
- Chromium verified both routes at desktop and mobile sizes with no overflow or page errors; the
  regenerated PDF returned `200`, `application/pdf`, and a valid `%PDF-` signature.