# ADR-0004: Knowledge-graph strategy — relational graph model in PostgreSQL, no graph datastore

**Status:** Proposed (2026-07-30)
**Refs:** ADR-0002 (managed vs manifest datastores), ADR-0003 (broker selection — the precedent for
rejecting a second datastore that dilutes the tenant wall), #207 (pgvector spike, open), V25
(products FTS), V41 (PPDS/allergen columns), V52/V57 (shop_staff), `RlsContractTest`

## Context

The question raised was whether graph theory, a graph database, or a knowledge graph would benefit
this platform — specifically by connecting a food item to its ingredients and its vendors, across
the three senses of "food item": a cooked dish, a recipe, and a shopping basket.

That framing is structurally correct and worth stating precisely, because it is the reason the
opportunity is real: those are **three traversals of one node set**, not three products. A canonical
Ingredient node serves menu compliance, recipe commerce, and grocery retail from the same table.

### What the codebase already has

Most of the *edges* exist as shipped columns. Almost none of the *nodes* do.

| Edge | Location | Status |
|---|---|---|
| Customer ⟷ Allergen | `Customer.allergenRestrictions` (int bitmask) | shipped |
| Product ⟷ Allergen | `Product.allergenMask` (same bit space) | shipped |
| Order → Product | `OrderItem.productId` + quantity | shipped |
| Shop → Product | `Product.shopId` | shipped |
| Shop → geo | `Shop.latitude` / `longitude` | shipped |
| Customer → Shop (experience) | `Review.shopId` / `orderId` / `customerEmail` + 2 rating axes | shipped |
| Shop → legal entity → hygiene | `GateType.BUSINESS_VERIFIED`, `FOOD_HYGIENE_RATING` | shipped |
| Product ⟷ raw bytes | `media_asset (tenant_id, sha256)` | shipped |

The absent node is **Ingredient**:

- the field is `private String ingredientsText` — free text, no entity — `core-java/src/main/java/uk/jtoye/core/product/Product.java:37`
- and the parser yields only `public record ParsedIngredients(String plainText, List<AllergenSpan> spans)` — character offsets for emboldening on a printed label — `core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java:46`
- the customer side is the mirror bitmask `private Integer allergenRestrictions` — `core-java/src/main/java/uk/jtoye/core/customer/Customer.java:58`

`Product.category` and `Product.dietaryTags` are also free strings, so there is no taxonomy either.

### The load-bearing observation

On every guest checkout the storefront already runs `int conflict = customerAllergenMask & product.getAllergenMask();` — `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:449`

The allergen safety chain is therefore **live in production today**, and its integrity rests
entirely on `allergenMask` — an integer a vendor hand-types into a CSV column whose template header
is `title,sku,price_pounds,ingredients,category,description,dietary_tags,prep_time_minutes,allergen_mask,shop_id` — `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:60`

Nothing in the codebase ever reconciles that integer against the ingredients text sitting in the
adjacent column.

Two smaller verified gaps in the same path:

- the mask is taken from the request, so a signed-in customer's stored
  `Customer.allergenRestrictions` is never consulted at checkout;
- a detected conflict produces a warning string, never a block.

### The regulatory frame (corrected)

An earlier draft of this analysis anchored the compliance case on Natasha's Law. That was wrong and
the correction matters, because it moves the duty onto ground that is *more* platform-shaped:

- **Natasha's Law / PPDS does not cover most of what this platform sells.** PPDS excludes food sold
  by distance selling (phone, internet) and food packed after the customer orders it. J'Toye is a
  distance-selling platform. ([FSA — PPDS allergen labelling](https://www.food.gov.uk/allergen-labelling-changes-for-prepacked-for-direct-sale-ppds-food))
- **Distance selling carries its own, separate statutory duty.** Allergen information must be held
  **in written form by the business** and made available **in written form to the consumer at two
  points — before the purchase is concluded, and again when the food is delivered**.
  ([FSA technical guidance, Part 2 — non-prepacked food](https://www.food.gov.uk/business-guidance/food-allergen-labelling-and-information-requirements-technical-guidance-part-2-guidance-for-businesses-providing-non-prepacked))
- **FSA guidance published 5 March 2025** sets an expectation of written allergen information across
  the out-of-home sector, explicitly including online sales.
  ([FSA news](https://www.food.gov.uk/news-alerts/news/updated-industry-guidance-issued-for-food-allergen-information-in-the-out-of-home-sector))

So the duty attaches to vendors *because they sell through this platform*, and the platform is the
instrument by which they discharge it. That duty currently rests on one unvalidated integer.

### Scale

A food SME's catalogue is roughly 50–200 products. Within a single tenant, every traversal this
platform needs is depth ≤ 3 and is indistinguishable in cost from a SQL join. The traversals that
genuinely want graph machinery — allergen transitive closure over an ingredient hierarchy,
substitution paths, supply-chain contamination tracing — are either depth-bounded (closure) or not
yet backed by any data the platform holds (supply chain).

## Options

1. **Do nothing.** Keep `allergen_mask` as a hand-typed vendor declaration.
2. **Relational graph model inside the existing PostgreSQL.** Ingredient/edge tables, recursive CTEs
   for transitive closure, existing GIN/FTS, pgvector (#207) for fuzzy matching. No new datastore.
3. **Apache AGE** — openCypher as a PostgreSQL extension, same database.
4. **Dedicated graph database** (Neo4j or equivalent), synchronised from PostgreSQL.

## Decision

**Option 2 — a relational graph model inside the existing PostgreSQL. Adopt the data model; reject
the datastore.**

Reasons, in order of weight:

- **Apache AGE is disqualified by a defect specific to this repository, not a generic one.** AGE does
  integrate with PostgreSQL RLS (it compiles RLS `USING` policies into security-qual evaluation), so
  the naive objection is wrong. The disqualifying facts are different and sharper:
  - AGE creates label tables **dynamically at graph-write time**, in a **per-graph schema**, and the
    tenant key would live inside an `agtype` property blob rather than a column.
  - `everyPublicTableHasRlsAndForce` sweeps `pg_class` filtered by `AND relnamespace = 'public'::regnamespace` — `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java:130`
    AGE's tables live in a per-graph schema and are **structurally invisible
    to that sweep**. The project's own RLS drift guard would stay green over an unprotected graph —
    which is Proof Standard #5 ("a structural check can pass while the function is still broken")
    reproduced exactly, in the one subsystem where it is least affordable.
  - Adopting a datastore whose tables appear at runtime is incompatible with a model whose tenant
    isolation is *declared in a Flyway migration and swept by a contract test*.
  - Operationally, Azure Database for PostgreSQL Flexible Server lists Apache AGE among the
    extensions that **block in-place major version upgrades** (with `anon`, `dblink`, `orafce`,
    `postgres_fdw`, `timescaledb`), and supports it only to PG 16.
    ([Azure extension considerations](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-considerations))
    Adopting AGE trades the Postgres upgrade path for Cypher syntax.
- **A dedicated graph database is worse on the same axis.** Multiple databases and fine-grained
  access control are **Enterprise Edition** features in Neo4j; Community has neither.
  ([Neo4j security](https://neo4j.com/product/neo4j-graph-database/security/),
  [Neo4j 4.0 multi-tenancy](https://medium.com/neo4j/reactive-multi-tenancy-with-neo4j-4-0-and-sdn-rx-d8ae0754c35))
  Either edition means re-implementing tenant isolation in application query-building — moving this
  system's strongest invariant from *enforced by the database* to *intended by the application*.
  This platform has FORCE RLS on every tenant table, proven under a `NOSUPERUSER` downgrade. That is
  not a property to spend.
- **The scale does not justify an engine and probably never will at this shape.** See Context. The
  value that would justify a graph engine is cross-tenant, and cross-tenant is precisely what the
  security model forbids by construction.
- **What is missing is the data model, not the query engine.** Option 2 delivers 100% of the
  identified value. Options 3 and 4 deliver the same value plus a Cypher dialect, at the cost of the
  tenant guarantee.

### Architecture

Two planes, and the separation is the whole design:

**Layer A — per-tenant derived graph.** Ordinary tables in `public`, created by Flyway migrations,
tenant-scoped with `ENABLE` + `FORCE` RLS like every other table.

> The tenant story for Layer A is that **there is no new tenant story.** It inherits the existing
> wall by being unremarkable. Nothing about this ADR introduces a new isolation mechanism, and that
> is deliberate.

**Layer B — canonical reference plane.** Non-tenant data: the ingredient ontology, allergen
hierarchy, FHRS establishments, dietary/category taxonomy. Declared in
`RlsContractTest.EXEMPT_TABLES` with written justification — the mechanism that already exists for
`tenants` — rather than inventing a new exemption path.

> **Structural invariant:** no table in Layer B has a `tenant_id` column at all. It is therefore
> *incapable* of holding tenant data, rather than merely policed against it. A contract test asserts
> the absence of the column. This is a guarantee, not a policy.

Mitigations designed in from the start rather than discovered later:

1. **Generic typed edge table** (`from_id, to_id, edge_type, attrs`) so new relation types
   — `CONTAINS`, `DERIVED_FROM`, `SUBSTITUTES_FOR`, `SUPPLIED_BY` — need no migration. This is what
   makes the model extensible, and it also makes any future port to a real graph engine mechanical
   rather than a rewrite.
2. **Graph tables are projections, never sources of truth** — rebuildable from `products` /
   `orders` at any time. Populated via the existing transactional outbox pattern. Note the recorded
   `outbox_flusher_dispatch_trap`: a new event type poison-dead-letters unless the flusher's
   `publishRow` dispatch is extended in the same change.
3. **Provenance and licence on every reference node** (`source`, `licence`). The licence field gates
   what may be exposed and what may be merged. See Licence containment below.
4. **Derived allergens are advisory only.** A derived value never overwrites a vendor's declaration;
   a mismatch raises a discrepancy for human resolution. An automated system that silently alters an
   allergen claim is a liability, not a feature.
5. **Backfills loop tenants with `set_config`.** A bare `UPDATE` against a FORCE-RLS table updates
   zero rows as the migration role — recorded as recurring at V25, V44 and V57.

### Licence containment (ODbL)

Seeding an ingredient ontology from Open Food Facts creates a **Derivative Database** under ODbL.
The licence's own structure gives the containment strategy
([ODbL v1.0](https://opendatacommons.org/licenses/odbl/1-0/),
[OFF terms](https://world.openfoodfacts.org/terms-of-use)):

- §4.5(c) exempts use "internally within an organisation" — but a multi-tenant SaaS serving
  independent vendors is very unlikely to qualify, since "Publicly" means available to persons not
  under your control. **Do not rely on the internal-use exemption.**
- A rendered answer shown to a user is a **Produced Work**, requiring an attribution notice (§4.3),
  *not* share-alike of the database. Displaying derived allergen information is therefore fine with
  attribution.
- The hazard is merging ODbL data into the proprietary ontology and then publicly using the combined
  database — which triggers share-alike over the whole thing, i.e. an obligation to publish the very
  asset identified below as a moat.

**Containment:** ODbL-sourced rows live in a physically separate, provenance-tagged partition of
Layer B; the proprietary layer **references** them and never ingests them; the `licence` field gates
exposure. Decided up front because it is cheap now and unwindable-only-by-republication later.

By contrast, **FHRS data is Open Government Licence** — commercial redistribution permitted,
attribution only, no share-alike, no API key.
([FHRS API, api.gov.uk](https://www.api.gov.uk/fsa/food-hygiene-ratings-scheme-fhrs/))
The FHRS edge is legally unencumbered. Companies House terms are to be confirmed separately before
any redistribution is relied upon.

### Data protection

`Customer.allergenRestrictions` is health data, and health data is special-category data under UK
GDPR Article 9 — requiring both an Article 6 lawful basis and a separate Article 9 condition, with
explicit consent the realistic route.
([ICO — special category data](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/lawful-basis/special-category-data/what-are-the-rules-on-special-category-data/))

This exposure **exists today** and is not created by this ADR — the column is already populated and
already flows through checkout. The graph makes it more visible, not more real. Confirm the current
consent basis before Layer A ships, not after.

## Consequences

**Good**

- The single highest-risk field on the platform (`allergen_mask`) becomes checkable against evidence
  that already sits in the adjacent column.
- No new datastore, no new isolation mechanism, no new backup/DR surface, no new upgrade constraint.
- The Ingredient node unlocks substitution, dietary filtering, catalogue autofill, and a graph-backed
  MCP `find_products` tool that answers hard safety constraints **structurally** — never letting a
  language model decide whether a dish contains milk.
- Portability is preserved: the generic edge table is the same shape a graph engine would want.

**Bad / accepted costs**

- Ontology maintenance is perpetual and unglamorous. It is what kills projects of this kind.
- Ingredient extraction from free text has a long tail, and errors sit on a path with legal
  consequence — hence advisory-only, which caps the achievable automation.
- Recursive CTEs are less expressive than Cypher. Accepted; the traversals are depth-bounded.
- Layer B is a genuinely new plane to operate, even without a new engine.

**Risks**

- Scope creep from Layer A into cross-tenant analytics without a legal basis. Mitigated structurally
  by the missing `tenant_id` column, and contractually by the ToS item below.
- Vendor data-entry burden if quantities or lot numbers are ever required — see Retracted claims.

## Moat — revised ranking

1. **Verified vendor ↔ Companies House ↔ FHRS ↔ staff identity graph.** Strongest. Licence is clean
   (OGL), it is already half-built by the onboarding gates, and it requires **no new vendor data
   entry**. A competitor cannot buy it; they would have to re-onboard the vendors.
2. **Allergen-integrity graph.** Regulatory necessity rather than differentiation, but it is the
   thing that makes the platform defensible to sell into an EHO-inspected market.
3. **Canonical UK ingredient ontology.** Real network effect, but partly *rented* — the open-data
   base is available to anyone, and it is licence-hazarded (above). Defensibility is the UK-SME
   corrections layered on over time, not the base.
4. **Supply-chain traceability.** Deepest theoretical lock-in, gated on data acquisition that does
   not exist. Not bankable in this milestone.
5. **Order-affinity graph.** Proprietary but decaying; a quality edge, not a wall.

**Action independent of any code:** the right to derive aggregate and anonymised insight from tenant
data must be in the ToS/DPA **now**. Retrofitting it after the graph is populated means re-consenting
every vendor, and any refusals hole the asset permanently. This is a paragraph, not a sprint, and it
gates moat #3 entirely.

## Re-evaluation triggers

This decision is revisited only on measured evidence, not on preference:

- a production traversal with genuinely unbounded depth (> 4 hops, depth not known statically); **or**
- p95 latency of the transitive-closure CTE exceeding 200 ms on real tenant data; **or**
- a committed cross-tenant product with an executed legal basis, where the graph exceeds ~10^7 edges.

**Precondition on any future graph-engine adoption:** `RlsContractTest`'s sweep must first be widened
beyond `relnamespace = 'public'` **and shown to fail** against a deliberately unpolicied table in the
candidate engine's schema. No graph engine is adopted while the guard that would catch its failure is
incapable of failing.

## Retracted claims

Recorded so they are not re-argued. Each was asserted in the first-round analysis and falsified in
the second:

- **"Natasha's Law is the compliance hook."** False for this platform — PPDS excludes distance
  selling. The distance-selling written-information duty replaces it, and is stronger here.
- **"Ingredient graph unlocks COGS and per-dish margin."** Substantially retracted.
  `ingredients_text` is a declaration list — legally ordered by weight but carrying **no quantities**.
  COGS needs quantities *and* supplier unit prices, neither of which exists in the schema, both of
  which require sustained vendor data entry, and the platform is not the system of record for
  procurement. This is a data-acquisition problem wearing a graph costume.
- **"Traceability is the deepest moat."** Demoted. Article 18 binds every FBO including takeaways
  and is retained in UK law, but the duty is supplier→product and product→customer *"except for
  final consumers"*, discharged today via invoices. Lot-level capture needs scanners or supplier
  integrations that do not exist.
  ([legislation.gov.uk — EU 931/2011](https://www.legislation.gov.uk/eur/2011/931))
- **"Apache AGE cannot honour RLS."** False — AGE does compile RLS policies into security-qual
  evaluation. AGE is rejected on the schema-visibility and dynamic-table grounds above instead.

## Sequencing

Nothing in this ADR is a commitment to build. Suggested order when it is scheduled:

1. **Zero-infrastructure, existing data:** consult stored `Customer.allergenRestrictions` at
   checkout; order-level mask aggregation; surface conflicts on the KDS. Also the onboarding
   critical-path traversal — the gates already form a DAG and this milestone is explicitly about
   unblocking stalled onboarding.
2. **Layer B + Ingredient node + discrepancy queue.** The unlock for everything else; worth its own
   phase.
3. **Substitution and dietary traversals; graph-backed MCP `find_products`.**
4. Supply chain only if a vendor-side capture mechanism materialises.

## Sources

- [FSA — allergen labelling changes for PPDS food](https://www.food.gov.uk/allergen-labelling-changes-for-prepacked-for-direct-sale-ppds-food)
- [FSA — technical guidance Part 2, non-prepacked food](https://www.food.gov.uk/business-guidance/food-allergen-labelling-and-information-requirements-technical-guidance-part-2-guidance-for-businesses-providing-non-prepacked)
- [FSA — updated out-of-home allergen guidance, 5 Mar 2025](https://www.food.gov.uk/news-alerts/news/updated-industry-guidance-issued-for-food-allergen-information-in-the-out-of-home-sector)
- [Open Data Commons — ODbL v1.0](https://opendatacommons.org/licenses/odbl/1-0/)
- [Open Food Facts — terms of use](https://world.openfoodfacts.org/terms-of-use)
- [api.gov.uk — FHRS API](https://www.api.gov.uk/fsa/food-hygiene-ratings-scheme-fhrs/)
- [ICO — rules on special category data](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/lawful-basis/special-category-data/what-are-the-rules-on-special-category-data/)
- [Azure — PostgreSQL extension considerations](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-considerations)
- [Neo4j — graph database security](https://neo4j.com/product/neo4j-graph-database/security/)
- [Apache AGE](https://github.com/apache/age)
- [legislation.gov.uk — Commission Implementing Regulation (EU) 931/2011](https://www.legislation.gov.uk/eur/2011/931)
