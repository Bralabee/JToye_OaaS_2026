---
phase: 33
phase_name: "the-consumer-product"
project: "J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved"
generated: "2026-08-09"
counts:
  decisions: 8
  lessons: 7
  patterns: 10
  surprises: 6
missing_artifacts:
  - "33-UAT.md (no UAT file — the human-verify verdict is recorded in 33-07-SUMMARY.md: owner approved with the miles corrections)"
---

# Phase 33 Learnings: the-consumer-product

## Decisions

### Miles for the customer, kilometres on the wire
Distances and radii shown to a customer are miles; `distanceKm`/`radiusKm` and the `jtoye.geo.*`
config stay metric. Conversion lives in one module (`frontend/lib/distance.ts`). The radius copy
reads "3.1 miles", not a rounder "3 miles" — 3 miles is 4.83 km, a radius nothing applied.

**Rationale:** Owner checkpoint correction ("miles not kilometers"); the machine contract and the
committed OpenAPI snapshot stay untouched, and the honesty rule (never claim a radius that was not
applied) extends to units.
**Source:** 33-07-SUMMARY.md

### `asin` haversine, never unclamped `acos`
The distance query uses the `asin` haversine form.

**Rationale:** The `acos` form overflows its domain on floating-point rounding for near-identical
points — an unauthenticated 500 reachable by a customer standing on a shop's centroid. Measured on
the live PostgreSQL: 3,600 of 90,001 sampled latitudes (4.0%) push `sin²+cos²` past 1.0.
**Source:** 33-06-PLAN.md, 33-06-SUMMARY.md

### Leakproof bounding-box prefilter under RLS
`findPublishedNear` prefilters with `float8` `BETWEEN` comparisons (all `proleakproof = t`,
measured) so the box is pushable below the row-security barrier; the trig functions (all
`proleakproof = f`) run only over the pre-filtered rows.

**Rationale:** Without it, RLS forces the haversine over every row; with a non-leakproof prefilter
the planner cannot push it down at all.
**Source:** 33-06-PLAN.md

### The OpenAPI snapshot gate asserts subsumption, wired where a runtime exists
`check-openapi-snapshot-fresh.sh` requires the committed snapshot to be subsumed by the running
service (not byte-equal), and runs in `e2e-nightly.yml` rather than being declared exempt.

**Rationale:** Compose runs the `dev` profile, the snapshot generates under `test`, so live
legitimately carries two dev-only paths — equality would be permanently red on a correct tree. And
the plan's premise that no CI job has a running service was false: e2e-nightly brings the stack up.
**Source:** 33-06-SUMMARY.md

### `fetch`, not the axios client, on the LCP-critical public route
The near-you island issues its one GET with `fetch`; `app/shop` keeps `publicApiClient` (axios).

**Rationale:** Measured on the rebuilt stack: axios 1,005,834 bytes vs fetch 958,988 — 46,846 bytes
of unused HTTP client on the landing route, against the standing web-perf criterion. `app/shop`
keeps axios correctly because it consumes the axios-shaped 429s the retry helper inspects.
**Source:** 33-07-SUMMARY.md

### No coordinate persisted to any browser storage
The device coordinate lives in React state only — no cookie, no localStorage, no sessionStorage.

**Rationale:** Precise geolocation is personal data under UK GDPR; a cookie is sent on every
matched request; and #116's cookie banner has not shipped (Phase 31, LGL-01) — persisting would
open a PECR consent question the phase cannot close. State-only sidesteps it entirely.
**Source:** 33-07-PLAN.md

### CUST-02/CUST-04 formally deferred by owner override, not gap-planned
Verification returned `gaps_found`; the owner accepted all three gaps as intentionally deferred
(extending the dated D-2/D-3 scope decisions), recorded as `overrides_applied: 3` with per-gap
annotations. Nothing was reclassified as done.

**Rationale:** The gaps trace to scope decisions made before any plan was written and were already
self-disclosed in REQUIREMENTS.md and the SUMMARYs; re-opening settled scope via gap plans would
re-litigate a decision, not close a defect. The substantive loose end — who adjudicates
`MANUAL_REVIEW` — is carried explicitly to the next phase's decision queue.
**Source:** 33-VERIFICATION.md

### Checkpoint search findings filed, not built
Postcode-proximity search (a customer postcode with no string match returns 0 shops) was filed as
#619; semantic food-term matching stays on the pgvector track (#207).

**Rationale:** Owner's explicit routing ("file as issue, close phase") — both are new feature
slices outside 33-07's plan, and 3a is now cheap precisely because this phase shipped its
ingredients (postcode_centroid + findPublishedNear).
**Source:** 33-07-SUMMARY.md, 33-VERIFICATION.md

---

## Lessons

### A SUMMARY's claim about the runtime can be stale against the branch
33-06 recorded CA-2 (`geolocation=()`) as a live blocker for 33-07 — but 33-03 had already fixed
it; 33-06 had read a runtime older than the branch. The correction was appended under the original
paragraph rather than rewriting it, because a silently-corrected blocker looks identical to one
that never existed.

**Context:** The phase's own recurring lesson (runtime parity) applied to its own documentation;
verify runtime claims against the tree before propagating them.
**Source:** 33-07-SUMMARY.md, .planning/STATE.md

### A Spring Data page larger than the row count never runs the countQuery
33-06's countQuery break arm (deleting `published = true` from the count only) left the suite
green: `PageableExecutionUtils.getPage` skips the count query when offset is 0 and page size
exceeds the row count — three rows in a page of twenty. The assertion re-measured content it had
already asserted. At page size 2 the same break fires.

**Context:** A total assertion in that shape is vacuous by construction; the count-leak criterion
had to be pinned where Spring Data actually executes the count.
**Source:** 33-06-SUMMARY.md

### `String.replace` break arms can edit a comment and report the criterion unfalsifiable
33-07's header break arm targeted `geolocation=(self)` — and hit the first match, which was inside
33-03's explanatory comment 15 lines above the real header. The arm reported "criterion cannot
fail" while the live header was untouched.

**Context:** A break arm needs an ambiguity guard on its target; the corrected arm presents
identically to a user denial, exactly as the control-arm sheet warned.
**Source:** 33-07-SUMMARY.md

### Rebase-merge strips PR numbers and the changelog gate can only VOID on that
PR #620 landed the phase's 64 commits by rebase; no subject carries "(#NNN)", so
`check-changelog-contract.sh` VOIDs on main — `EXEMPT` keys on a PR number extracted from the
subject and structurally cannot express an unattributable commit. The PR-time run is green by
construction (the range ends at origin/HEAD), so the first symptom appears on the next unrelated PR.

**Context:** Remedied by writing the #620 entry anyway, moving `FLOOR` with a dated in-file note,
and squash-merging #621 with an explicit `fix(...) (#621)` subject. Prefer squash for multi-commit
PRs.
**Source:** scripts/gates/changelog-contract.conf (FLOOR note, 2026-08-09), docs/CHANGELOG.md (#620 entry)

### Handoff figures rot the moment a phase adds a gate
The HANDOFF resume block promised "EXPECT 32 x rc=0"; the phase added two gate scripts and the
checker counts 34 — red at PR time. The claim must equal the checker's own measurement
(`ls scripts/check-*.sh scripts/docs-freshness.sh | wc -l`), and its provenance trail is now part
of the comment.

**Context:** The recorded handoff-residue trap firing exactly as designed; the fix carries the
count's derivation so the next bump is mechanical.
**Source:** HANDOFF.md (resume block note)

### Two page-truncated samples are not totals
WR-01: the exclusion disclosure computed `serverShops.length - nearby.length - unranked.length`
from two lists both capped at 8, from different orderings — past 8 published shops it claims
in-radius shops are "further than 3.1 miles away". Invisible with 3 seeded shops.

**Context:** Fixed by gating the arithmetic on both totals confirming the pages are complete; the
control arm proves the clause still fires when the page IS the census.
**Source:** 33-REVIEW.md, 33-REVIEW-FIX.md

### `npm --prefix` does not change the working directory
The plan's own verify limb `npm --prefix frontend exec -- playwright test …` exits 1 with
`ReferenceError: jest is not defined`: Playwright ran from the repo root with no config, defaulted
its testDir to the cwd, and collected a Jest file whose name matched the filter. Run from
`frontend/` it passes.

**Context:** Both readings were recorded rather than silently substituting the plan's form.
**Source:** 33-07-SUMMARY.md

---

## Patterns

### Break the conversion constant; hardcode the expectations
A unit conversion lives in one module so a break arm on the constant is decisive — and every test
expectation is a literal, never derived from `MILES_PER_KM`, because a derived assertion passes
for any factor including 1 (the exact defect it exists to catch). Factor→1 reds 12 tests.

**When to use:** Any display-layer conversion or formatting constant.
**Source:** 33-07-SUMMARY.md

### Playwright geolocation emulation makes the failure path real
`context.grantPermissions(['geolocation'])` + `context.setGeolocation(...)` make granted, denied,
and far-away all deterministic — the failure path is reachable, not simulated.

**When to use:** Any device-capability UI (location, camera, notifications) with divergent
grant/deny behaviour.
**Source:** 33-07-SUMMARY.md

### Per-arm exact-once verification, with a control per absence claim
Control-arm sheets are verified per-arm (exact-once structure), not by a whole-file count a legend
could satisfy; every absence claim carries a control proving the pattern can match over the same
corpus.

**When to use:** Any phase-level falsifiability harness.
**Source:** 33-00-SUMMARY.md

### External data enters only through a fail-closed regeneration script
The postcode dataset crosses into the repo via an md5-verified script that dies on mismatch, is
re-validated against an independent reference on every regeneration, and builds deterministically
(`gzip -n`) so an unchanged dataset costs nothing in git history.

**When to use:** Any committed derived artefact sourced from external data.
**Source:** 33-01-SUMMARY.md

### The bulk update IS the RLS proof
The coordinate backfill writes with a bulk JPQL UPDATE because the affected-row count is the RLS
evidence — a managed-entity flush would hide the same fact inside a swallowed exception.

**When to use:** Any migration/backfill on a FORCE-RLS table where the write count is load-bearing
evidence.
**Source:** 33-05-SUMMARY.md

### A runtime gate asserts a relation, never a census
`check-live-shop-coordinates.sh` asserts a relation with an explicit denominator — a census reds on
legitimate new data, and a gate that reds on legitimate data gets `|| true`'d into oblivion.

**When to use:** Any live-data gate whose corpus legitimately grows.
**Source:** 33-05-SUMMARY.md

### Contract-vs-runtime gates use subsumption across profile boundaries
When the committed contract and the live surface are generated under different Spring profiles,
the gate asserts subsumption (committed ⊆ live), never byte equality — and it gets wired into the
one workflow that actually has a runtime rather than exempted.

**When to use:** Any committed-artifact-vs-running-service check where profiles differ.
**Source:** 33-06-SUMMARY.md

### Pin count assertions where the count query actually executes
Assert pagination totals at a page size smaller than the row count (page size 2 over 3 rows), so
the countQuery genuinely runs and a count-side leak can red.

**When to use:** Any paginated endpoint whose total must not leak filtered rows.
**Source:** 33-06-SUMMARY.md

### A shift budget that would forbid the feature is replaced, not raised
The post-grant layout bound is VERTICAL pixels only, because the horizontal movement is the reorder
the visitor asked for. 0.00 px clean, 20 px under the break — the criterion can still fire.

**When to use:** Any CWV-style bound over an interaction whose purpose is movement.
**Source:** 33-07-SUMMARY.md

### Absolute ceilings, not growth multipliers
The `/` bundle is held to an absolute byte ceiling that a spec imports and asserts. A growth
multiplier ratchets — each plan measuring against the last plan's total lets a route gain 50%
three times and never fail; an unconsumed constant enforces nothing.

**When to use:** Any budget meant to survive several phases.
**Source:** 33-07-SUMMARY.md, 33-07-PLAN.md

---

## Surprises

### The `acos` hazard is latitude-dependent
4.0% of latitudes (3,600 of 90,001 sampled on the live database) overflow the `acos` domain — so a
coincident-point test at an arbitrary coordinate cannot distinguish `asin` from `acos`. The fixture
had to move to a hostile latitude (54.900003) before the break arm could fire.

**Impact:** The plan's own break arm was vacuous until measured; the test suite now pins the
hostile-latitude fixture permanently.
**Source:** 33-06-SUMMARY.md

### The "no CI job has a running service" premise was false
The plan exempted the snapshot gate from CI on the belief no workflow has a runtime — but
`e2e-nightly.yml` brings the full stack up with `--build`. The gate was wired there instead, and
`ci-cd.yaml` was deliberately not modified.

**Impact:** One planned file untouched, one deviation recorded, a runtime gate that actually runs.
**Source:** 33-06-SUMMARY.md

### The first post-grant CLS measurement flagged the feature itself
The initial layout-shift criterion measured 0.0687 and red — from a single, purely horizontal card
move (`x=136 → x=16`, same y, same height): the reorder the visitor just asked for.

**Impact:** The criterion was replaced (vertical displacement), not raised — an over-strict
criterion is as much a defect as a vacuous one.
**Source:** 33-07-SUMMARY.md, 33-05-SUMMARY.md (the recorded principle)

### The human gate caught exactly what automation could not ask
The owner's walkthrough surfaced miles-vs-kilometres and search expectations (postcode proximity,
semantic matching) — none of which any automated arm in the phase asked about, because they are
questions about what a customer *expects*, not what the code *does*.

**Impact:** Two corrections shipped same-day; two issues filed with evidence; the blocking
human-verify checkpoint justified its cost.
**Source:** 33-07-SUMMARY.md

### The "broken" postcode search was a capability boundary made visible
Verified live: `SE15`→2 shops, `SE15 5BS`→1, `jollof`→2 — the FTS path is healthy. `SE22` (1.5 km
from two shops)→0, because search string-matches a shop's own text and proximity was never wired
to it. Shops had no coordinates before this phase, so it could not have worked before; the phase
made proximity real everywhere except search, which made search feel newly broken.

**Impact:** Filed as #619 rather than hot-patched; the evidence table travels with the issue.
**Source:** 33-07-SUMMARY.md, 33-VERIFICATION.md

### A concurrent session edited STATE.md mid-execution
The 33-06 executor found `.planning/STATE.md` already modified in the working tree (rewritten
position counters) by another session driving the same checkout. It corrected the position line,
committed the rest as found, and said so in the file.

**Impact:** Reinforces the standing rule: `git diff --staged` before every commit on this shared
checkout; a named-path `git add` once swept another session's work into a commit.
**Source:** 33-06-SUMMARY.md, .planning/STATE.md
