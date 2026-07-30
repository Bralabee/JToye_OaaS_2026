# ADR-0004: Knowledge-graph strategy — relational graph model in PostgreSQL, no graph datastore

**Status:** Accepted (2026-07-30)
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

The three senses are developed in full below (see *The three senses of a food item*). They are the
origin of this ADR and its largest product opportunity; the compliance and identity material that
follows is what makes them *safe* to build, not a replacement for them.

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

> **Amended 2026-07-30.** This section originally cited a guest-checkout cross-check,
> `customerAllergenMask & product.getAllergenMask()`, as proof the safety chain was live. That code
> has since been **removed** — it took special category data over an unauthenticated endpoint with no
> Article 9 condition, and no client ever sent it. See
> [`docs/legal/article-9-allergen-basis.md`](../../legal/article-9-allergen-basis.md). The observation
> below is restated on the ground that survives, and the ADR's decision is unaffected.

Every allergen statement the platform makes to a consumer resolves to `allergen_mask` — an integer a
vendor hand-types into a CSV column whose template header is
`title,sku,price_pounds,ingredients,category,description,dietary_tags,prep_time_minutes,allergen_mask,shop_id` — `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:60`

Nothing in the codebase ever reconciles that integer against the ingredients text sitting in the
adjacent column. That is the load-bearing gap, and removing the guest cross-check did not narrow it:
the written-allergen duty under distance selling is discharged from this same unverified field, and
`hasAllergen(product.allergenMask, a.bit)` is what the storefront renders — `frontend/components/storefront/product-detail-modal.tsx:65`

Both of the smaller gaps recorded here originally — that the mask came from the request rather than
the stored customer profile, and that a conflict warned rather than blocked — described the removed
guest cross-check and no longer exist as written. There is now **no consumer-allergen matching at
checkout at all**, which is the correct position until a consented path exists.

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

## The three senses of a food item

This is the question the ADR started from, and it is the largest product opportunity in it. An
earlier draft acknowledged it in a paragraph and then drifted into compliance and vendor identity —
that drift is corrected here.

### The schema already supports two of the three

Verified, not assumed:

- `Product` carries `private Integer quantityInStock` where NULL means untracked/unlimited — `core-java/src/main/java/uk/jtoye/core/product/Product.java:82`
- and `private String durabilityType` — `core-java/src/main/java/uk/jtoye/core/product/Product.java:111` — constrained to `CHECK (durability_type IN ('USE_BY', 'BEST_BEFORE'))` in V41.
- `category` is a free string. **Nothing in the model forces a product to be a cooked dish.**

`USE_BY` is the perishable-prepared-food durability; `BEST_BEFORE` is the shelf-stable one. So the
distinction between a cooked dish and an ambient grocery item is *already representable*, and the
shopping-basket sense needs **no schema change** — only an ingredient node to relate the two.

The recipe sense has no representation at all: there is no Recipe concept anywhere in the tree.

### Why the three senses are strategically different, not just three views

They have different economics, and the difference is the actual finding:

| Sense | Marginal cost | Perishable | Reach |
|---|---|---|---|
| Cooked dish | high (food + labour) | yes (`USE_BY`) | **delivery radius** |
| Recipe | ~zero (content) | no | unbounded — organic search |
| Shopping basket | stock cost | no (`BEST_BEFORE`) | **postable — escapes the radius** |

**Cooked food is radius-bound; dry goods are postable.** That is the structural unlock. A Birmingham
Caribbean kitchen cannot deliver jerk chicken to Glasgow, but it can post scotch bonnet sauce,
allspice and ackee there — same vendor, same platform, same catalogue data, an addressable market
that is no longer capped by drive time. Delivery radius is one of the reasons the standalone
market analysis for this sector read pessimistically; this is the mechanism that relaxes it.

The ingredient graph is precisely what converts an existing menu into a shoppable dry-goods
inventory, because **the vendor has already declared the ingredients**. No new data-entry burden —
which is exactly the wall that killed the COGS and traceability ideas (see *Retracted claims*).

### The recipe sense is also the unmet SEO contract

Verified: there is **no schema.org JSON-LD anywhere in the frontend source**, and no `robots.txt` /
`app/robots.ts` (a `sitemap.ts` does exist). The standing cross-cutting contract in `CLAUDE.md`
requires JSON-LD on public product/shop surfaces; it is currently unmet.

That matters here because `schema.org/Recipe` is a first-class Google rich-result type. Recipe
content derived from menu data the vendor has already entered is the cheapest organic-acquisition
asset available to a food platform, and building it would discharge an existing contract rather
than add a new obligation.

> Caution: a recipe page asserts *how to make* something. Publishing derived recipes without vendor
> review would be both a quality and a liability problem. Recipes are vendor-authored content the
> graph *assists*, exactly as derived allergens are advisory rather than authoritative.

### Which traversals actually need graph structure

Honestly graded, because this is where the datastore decision could have been overturned:

1. **dish → ingredients → which of those does this vendor also stock as goods?** Two hops. A join.
2. **dish ↔ dish sharing ≥ N ingredients.** Co-occurrence aggregate. A join with `HAVING`.
3. **ingredient → substitutes → dish variants** (vegan, gluten-free). Bounded closure. Recursive CTE.
4. **basket ⊇ recipe.ingredients — "what can I cook from this?"** *This* is the genuinely
   graph-shaped one: set containment over a bipartite graph, not a simple join.

Even (4) is comfortably Postgres-expressible — a bridge table with `HAVING COUNT(*) = n`, or arrays
with `@>` and a GIN index. **So restoring the origin question does not overturn the Decision above;
it survives it.** That is worth recording: the rejection of a graph datastore is not an artifact of
having drifted onto compliance, because the food traversals are depth-bounded and set-shaped too.

### One node, three projections

```
                          ┌──────────────────────────┐
                          │  ingredient (canonical)  │   Layer B — non-tenant
                          └────────────┬─────────────┘
                 used_in │        sold_as │        component_of │
        ┌─────────────────┴──┐  ┌─────────┴────────┐  ┌─────────┴──────────┐
        │ DISH               │  │ GOODS            │  │ RECIPE             │
        │ Product            │  │ Product          │  │ vendor-authored    │
        │ durability=USE_BY  │  │ =BEST_BEFORE     │  │ content, not sold  │
        │ prep_time set      │  │ quantityInStock  │  │ public / SEO       │
        │ radius-bound       │  │ postable         │  │ unbounded reach    │
        └────────────────────┘  └──────────────────┘  └────────────────────┘
                          Layer A — tenant-scoped, RLS unchanged
```

Dish and goods are the *same table* distinguished by durability. Recipe is new, tenant-owned (a
vendor's recipe is their content), and is the only one of the three requiring new storage.

## Commercial position

ADR-0004 is not only a defensive decision about a datastore. It is also the enabling structure for
several revenue surfaces, and the architecture above is what makes them reachable without
weakening tenant isolation.

### The reference plane is independently operable

Layer B holds no tenant data and has no `tenant_id` column. That permits a second consumer —
a reference API with its own database role holding **zero `GRANT`s on Layer A tables**. This is
deliberately stronger than RLS for that consumer: RLS filters which rows a role may ask about; a
missing grant means the question cannot be asked at all. A compromise of the reference API holds no
credential that reaches a tenant table, so the reference plane can be operated, sold or spun out
without touching the SaaS.

### Revenue surfaces, mapped to substrate already captured

| Surface | Runs on | Blocker |
|---|---|---|
| Catalogue expansion — menu → postable dry goods | `Product` as-is + ingredient node | ingredient node |
| Recipe content / organic acquisition | recipe entity + JSON-LD | unmet SEO contract |
| Compliance attestation (per site) | Layer A + ontology | ingredient node |
| Verified-trust API | Layer B only | vendor coverage |
| Embedded finance | resolved identity + ledger + Connect | FCA permissions / partner lender |
| Insurance distribution | Layer B + attestation | underwriter appetite |
| Benchmarking (anonymised cohorts) | aggregates, k-anonymous | **ToS/DPA clause absent** |

### The identity graph is already being built and discarded

`FhrsClient` resolves a shop's name and address to an FSA establishment;
`VendorOnboarding.companyNumber` captures the legal entity; `VendorOnboardingGate.externalRef`
documents itself as holding the "FHRS establishment id / CH number / Stripe acct"; and
`Tenant.stripeAccountId` + `stripeConnectStatus` complete the path to money. The platform therefore
performs **entity resolution over UK food SMEs once per vendor, at onboarding, and stores the answer
as a gate audit field it never reads again**.

FHRS is Open Government Licence and Companies House data is public, so the public data is not the
asset — **the join is**. That resolution is also the precondition for underwriting, which is why the
identity graph sits above cash-flow history in the moat ranking rather than beside it.

*No revenue modelling underpins any of this and no figures are asserted; lending and insurance
distribution carry regulatory scope that needs professional advice before either becomes a plan.*

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

**PRECONDITION, not a follow-up:** the right to derive aggregate and anonymised insight from tenant
data must be in the ToS/DPA **before Layer B accumulates data**. Retrofitting it afterwards means
re-consenting every vendor, and any refusals hole the asset permanently. It is a paragraph, not a
sprint, and it gates moat #3 plus the benchmarking surface outright — which is why it is promoted
here from the follow-up list to a blocking condition on that part of the work. It does **not** gate
the ingredient node, the three senses, or anything in Layer A.

**Status (2026-07-30):** drafted at [`docs/legal/derivation-clause.md`](../../legal/derivation-clause.md)
— ToS Part A, DPA Part B, the Article 9 exclusion, and the engineering invariants the wording commits
us to. It is **not in force**: no ToS or DPA exists in this repository yet, and the draft carries an
effectiveness gate requiring solicitor review and vendor acceptance. Until that gate is met, Layer B
must hold no data derived from tenant data.

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
3. **The shopping-basket sense.** Needs no schema change — `durabilityType='BEST_BEFORE'` plus
   `quantityInStock` already model an ambient grocery item. The work is UI, the ingredient links,
   and fulfilment/postage; the reward is a vendor catalogue that is no longer capped by delivery
   radius. This is the highest commercial return of any item on this list.
4. **Substitution and dietary traversals; graph-backed MCP `find_products`.**
5. **The recipe sense**, together with the unmet JSON-LD/`robots.txt` contract — vendor-authored,
   graph-assisted, never auto-published.
6. Supply chain only if a vendor-side capture mechanism materialises.

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
