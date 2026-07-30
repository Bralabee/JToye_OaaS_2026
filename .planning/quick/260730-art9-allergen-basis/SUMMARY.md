---
status: incomplete
date: 2026-07-30
branch: docs/article-9-allergen-basis
pr: 380
---

# Session record — ADR-0004 knowledge graph → Article 9 allergen basis

One session, four PRs. **Three merged, one open (#380).** This is the single reference point for
picking the thread up; it deliberately does **not** touch `HANDOFF.md`, which another session was
actively rewriting throughout (#371, #373, #374, #375, #378).

## Landed on main

| PR | Commit | What |
|---|---|---|
| #372 | `5b04c9bb` | `docs/architecture/decisions/ADR-0004-knowledge-graph-strategy.md` — adopt a relational ingredient/entity graph in the existing Postgres; reject Neo4j and Apache AGE |
| #377 | `3bec0739` | ADR-0004 status `Proposed` → **Accepted** |
| #379 | `238384c3` | `docs/legal/derivation-clause.md` — ToS Part A + DPA Part B data-derivation clause, **DRAFT, not in force** |

## Open — PR #380

Branch `docs/article-9-allergen-basis`, head `df9ddf73`. At last check: 13 SUCCESS, 1 NEUTRAL,
**0 failures**, `mergeable=MERGEABLE`, `mergeStateStatus=BLOCKED` (one check still queued), 0 behind
`origin/main`.

Contains one code change and one determination:

- **Removed** `GuestOrderRequest.customerAllergenMask` plus the dead cross-check and the orphaned
  `describeAllergens` / `ALLERGEN_NAMES` in `PublicStorefrontService`; regenerated
  `docs/api/openapi-snapshot.json`. It was special-category data on an **unauthenticated** endpoint
  with no Art. 9 condition and **no client ever sent it** (verified across frontend source, tests and
  E2E specs). `allergenWarnings` was deliberately kept — the checkout UI guards on `length > 0`, so
  nothing visible changed, and it is the seam a future consented path plugs into.
- **Added** `docs/legal/article-9-allergen-basis.md` — the vendor is the **controller**, J'Toye the
  **processor**, so J'Toye cannot obtain the Art. 9 condition; only 9(2)(a) explicit consent is
  available; Arts. 17 and 20 are already met and tested.

### To resume

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch origin
gh pr checks 380                 # expect 0 failures
gh pr view 380 --json mergeStateStatus -q .mergeStateStatus   # expect CLEAN once queued check lands
gh pr merge 380 --squash --delete-branch
```

Then verify **by content**, not ancestry — this repo squash-merges:

```bash
git show origin/main:docs/legal/article-9-allergen-basis.md | head -5
git show origin/main:core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java \
  | grep -c customerAllergenMask     # expect 1 — the explanatory comment only, no field
```

## Still open — decisions, not tasks

1. **Vendor consent mechanism for allergen data.** Nothing lets a vendor record or evidence the
   customer's consent, and no prompt says one is needed. Design sketched in
   `docs/legal/article-9-allergen-basis.md` against the existing `marketing_opt_in` (V54) precedent —
   same `(tenant_id, recipient)` keying, timestamped, withdrawal reusing the erasure path that
   already zeroes the field. **Not built** — the owner chose "document only".
2. **Vendor-facing notice** on the dashboard allergen checkboxes (~20 lines, no schema). Offered and
   deferred.
3. **Privacy notice.** None exists. `frontend/app/legal/page.tsx` carries company registration only.
4. **Companies House API terms** — confirm before relying on redistribution.
5. **ToS/DPA effectiveness gate** — `docs/legal/derivation-clause.md` lists five boxes. Until all are
   ticked: no cross-tenant derivation, and Layer B holds no tenant-derived data.

## Build order when ADR-0004 is scheduled

Nothing in the ADR is a commitment to build. Its own sequencing, unchanged:

1. Zero-infrastructure items on existing data (stored `allergenRestrictions` at checkout — **note this
   is now gated on item 1 above**; onboarding critical path).
2. **Layer B + Ingredient node + discrepancy queue** — the unlock for everything else.
3. **The shopping-basket sense** — needs *no schema change*; `durabilityType='BEST_BEFORE'` +
   `quantityInStock` already model an ambient grocery item. Highest commercial return.
4. Substitution/dietary traversals; graph-backed MCP `find_products`.
5. Recipe sense + the unmet JSON-LD / `robots.txt` contract.

## Corrections made mid-session — do not re-derive

- **Erasure was NOT a defect.** `GdprService` does set `allergenRestrictions` to 0, asserted in
  `GdprServiceTest`. My first report said otherwise; a case-sensitive grep had missed
  `setAllergenRestrictions`.
- **Natasha's Law is the wrong statute** for this platform — PPDS excludes distance selling. The
  distance-selling written-allergen duty replaces it.
- **Per-dish COGS is retracted** — `ingredients_text` carries no quantities.
- **Apache AGE *does* honour RLS.** It is rejected on different grounds: it creates label tables at
  runtime in a per-graph schema, while `RlsContractTest` sweeps only `relnamespace = 'public'`.

## Gate weaknesses found (both now in agent memory)

`scripts/check-doc-citations.sh` matches claim tokens against the cited line:

- A claim leading with a generic keyword (`private`, `const`, `function`) passes on **any sibling
  line** — measured: `Customer.java:31/:44/:58` all passed until the generic tokens were dropped.
  Break-arm against an **adjacent** line, never only line 1.
- **Bracketed Next.js paths are not extracted at all.**
  `` `frontend/app/shop/[slug]/page.tsx:88` `` pointed at line **99999** still returned rc=0, and
  `total=` stayed 7 while the file held 8 spans. Always reconcile the reported `total=` against your
  own count of `path:N` spans.
- `rc=2` is **VOID** ("zero citations discovered"), not clean. New docs are outside `DEFAULT_DOCS`,
  so CI never checks them — run scoped:
  `CITATION_DOCS="path" bash scripts/check-doc-citations.sh`.

## Verification recorded for #380

`:core-java:compileJava` rc=0 · `:core-java:test` **851 tests, 0 failures, 0 errors, 1 skipped**,
all 119 result XMLs confirmed freshly written (not cached) · all five doc gates rc=0 · ADR-0004 8/8
citations with the replacement break-armed against the adjacent line (rc=1) before being trusted.

## Environment

Worked in a git worktree from the start, per the recorded concurrent-session trap. The other session
was concurrently in its own worktree on `fix/alertmanager-scoped-mute`, so the two never collided.
This session's worktree was under `/tmp/claude-1000/.../wt-art9` and has been removed; the branch is
pushed to `origin` and is the source of truth.
