---
status: complete
date: 2026-07-30
commit: a60d7e33
---

# ADR-0004: knowledge-graph strategy

Doc-only. No code, no migration, no schema change.

## Outcome

`docs/architecture/decisions/ADR-0004-knowledge-graph-strategy.md` (Proposed), indexed in
`docs/DOCUMENTATION_INDEX.md`.

**Decision: adopt the data model, reject the datastore.** A relational ingredient/entity graph in the
existing PostgreSQL; Apache AGE and Neo4j assessed and rejected.

## What the second research round overturned

The first-round analysis was re-run adversarially against the tree and the law. Five claims failed:

1. **Natasha's Law was the wrong statute** — PPDS excludes distance selling, which is most of what
   this platform sells. Replaced by the distance-selling written-information duty (two stages:
   before purchase completes, and at delivery), which is stronger here because it attaches to
   vendors *through* the platform.
2. **COGS / per-dish margin retracted** — `ingredients_text` is a declaration list with no
   quantities; COGS needs quantities and supplier unit prices, neither in the schema, and the
   platform is not the system of record for procurement.
3. **Traceability demoted** — Art. 18 binds every FBO, but the duty stops at "except for final
   consumers" and lot capture needs scanners/integrations that do not exist.
4. **"AGE cannot honour RLS" was false** — it does compile RLS policies into security-qual
   evaluation. AGE is rejected on different, sharper grounds (below).
5. **FHRS is OGL, not encumbered** — which promotes the verified-identity graph to strongest moat.

## The disqualifying finding for AGE

AGE creates label tables dynamically at write time in a per-graph schema, with the tenant key inside
an `agtype` blob. `RlsContractTest.everyPublicTableHasRlsAndForce` filters
`relnamespace = 'public'::regnamespace` (`RlsContractTest.java:130`), so those tables are
structurally invisible to the project's own RLS drift sweep — a green guard over an unprotected
graph, Proof Standard #5 in the subsystem least able to afford it.

## Verification

| Gate | Result |
|---|---|
| `check-doc-citations.sh` (scoped to the ADR) | rc=0, 6/6 citations verified |
| `check-doc-citations.sh` (default docs) | rc=0, 62/62 |
| `check-doc-versions.sh` | rc=0, 84 claims, drift=0 |
| `check-doc-metrics.sh` | rc=0, 37 claims |
| `docs-freshness.sh` | rc=0, 1851 unchanged |

**Falsified both ways.** The scoped citation run first returned **rc=2 (VOID — zero citations
discovered)**, which is why the six `path:line` citations were added: the gate refused to certify a
doc it could not check. Break arm: repointing the `RlsContractTest` citation from `:130` to `:46`
produced **rc=1, violations=1, `cited: @Testcontainers`**. Restored, verified by content
(`:130` count 1, `:46` count 0 — not by `git diff --stat`), closing clean arm rc=0, tree clean
against the commit.

## Follow-ups (not started, not committed to)

- ToS/DPA clause granting the right to derive aggregate/anonymised insight — gates the ontology moat
  and is expensive to retrofit once populated.
- Confirm the Art. 9 consent basis for `Customer.allergenRestrictions`; the exposure exists today
  and is not created by this ADR.
- Confirm Companies House API terms before relying on redistribution.
