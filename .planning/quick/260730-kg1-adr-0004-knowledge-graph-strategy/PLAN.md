---
task: ADR-0004 knowledge-graph strategy
type: doc-only
created: 2026-07-30
---

# ADR-0004: knowledge-graph strategy

## Scope

Doc-only. Record the decision to adopt a **relational** ingredient/entity graph model inside the
existing PostgreSQL, and to reject a dedicated graph datastore (Neo4j, Apache AGE) — with the
mitigations, licence containment, moat ranking, and falsifiable re-evaluation triggers written down
so the datastore question is not re-litigated every six months.

No code, no migration, no schema change in this task.

## Tasks

1. Write `docs/architecture/decisions/ADR-0004-knowledge-graph-strategy.md` following the
   ADR-0003 structure (Context / Options / Decision / Consequences).
2. Add it to `docs/DOCUMENTATION_INDEX.md` if that index lists ADRs.
3. Commit atomically on `docs/adr-0004-knowledge-graph-strategy`.

## Evidence gathered before writing (round 2, adversarial)

Repo facts verified by reading source, not by recall:

- `Product.ingredientsText` is a free-text `String`; `IngredientMarkupParser.parse()` returns only
  `(plainText, List<AllergenSpan>)` — character offsets for emboldening, not entities.
- `Product.allergenMask` and `Customer.allergenRestrictions` are `Integer` bitmasks over the same
  bit space (`DemoDataSeeder`: `A_GLUTEN=1`, `A_MILK=1<<6`, `A_CELERY=1<<8`).
- `PublicStorefrontService` ~line 446 already runs `customerAllergenMask & product.getAllergenMask()`
  on every guest checkout — warning only, and the mask comes from the request, not from the stored
  `Customer.allergenRestrictions`.
- `BulkImportService` CSV template exposes `allergen_mask` as a hand-typed column alongside
  `ingredients`; nothing reconciles the two.
- `RlsContractTest.everyPublicTableHasRlsAndForce` filters `relnamespace = 'public'::regnamespace`.
- `products.search_vector` (tsvector + GIN) exists since V25; pgvector is open as #207.

External facts verified by source, with URLs recorded in the ADR.

## Acceptance

- ADR exists, follows house structure, and states a decision plus explicit re-evaluation triggers.
- Every external claim carries a source link.
- Claims retracted from the first-round analysis are recorded, not silently dropped.
