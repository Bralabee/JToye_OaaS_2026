---
phase: 33-the-consumer-product
plan: 08
subsystem: storefront-search
tags: [postcode, proximity, search, geo, openapi, cors, rls]
requires:
  - "postcode_centroid (V61, 33-02) — 1,748,230 GB units, PK-only index"
  - "ShopRepository.findPublishedNear (33-06) — asin haversine over a leakproof BETWEEN prefilter"
  - "jtoye.geo.default-radius-km (33-02) — READ, never added to"
provides:
  - "PostcodeGeocoder.locateSearchTerm — search-only entry point, outward code OR full unit"
  - "PostcodeCentroidRepository.findDistrictCentroid — closed range + unit-length guard, index-eligible"
  - "SearchInterpretation + the X-Search-Interpretation header grammar (consumed by 33-09)"
  - "PublicStorefrontService.SearchOutcome — page + server-asserted interpretation"
affects:
  - "GET /api/v1/public/shops?q= — a third search tier, reached only on a zero-result text search"
  - "docs/api/openapi-snapshot.json — header declared on both path aliases"
  - "cors.exposed-headers — one added name, none displaced"
tech-stack:
  added: []
  patterns:
    - "Spring Data interface projection over a native aggregate, with boxed Double + a count gate"
    - "Range bounds computed in Java so the predicate stays index-eligible (as GeoBounds does)"
    - "Server-asserted disclosure in a response header rather than a wrapper DTO or client inference"
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/geo/DistrictCentroid.java
    - core-java/src/main/java/uk/jtoye/core/storefront/SearchInterpretation.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontPostcodeSearchIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
    - core-java/src/main/java/uk/jtoye/core/config/CorsConfig.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/geo/PostcodeGeocoderTest.java
    - core-java/src/test/java/uk/jtoye/core/geo/PostcodeCentroidImportIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java
    - core-java/src/test/java/uk/jtoye/core/config/CorsExposedHeadersTest.java
    - core-java/src/test/resources/geo/postcode-centroids-fixture.csv
    - core-java/src/test/resources/geo/postcode-centroids-nullisland.csv
    - core-java/src/test/resources/geo/fixture-SOURCE.md
    - core-java/src/test/resources/geo/nullisland-SOURCE.md
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
decisions:
  - "D-A upheld: the postcode tier runs THIRD, so every query that returns results today takes an untouched path"
  - "D-B upheld: the disclosure is a response header, not a wrapper DTO and not client re-derivation"
  - "D-C upheld: no new config key — the radius is jtoye.geo.default-radius-km"
  - "Header schema declared as implementation = String.class; type = \"string\" renders an EMPTY schema"
metrics:
  duration: "~65 min"
  completed: 2026-08-09
  commits: 6
---

# Phase 33 Plan 08: Postcode-Proximity Search (API half) Summary

`GET /api/v1/public/shops?q=SE22` now answers with kitchens near the SE22 district centroid instead
of nothing, and the server states in a machine-readable header which of the two readings it applied.

---

## What shipped

A **third search tier** on `PublicStorefrontService.searchPublishedShops`, reached **only when the
full-text search and the LIKE fallback have both returned an empty page**. On that path the term is
offered to a new, search-only geocoder entry point; if it resolves to a GB postcode the distance
query from 33-06 is reused through one shared projection tail, at the platform radius.

Alongside it, `X-Search-Interpretation` — the server's own statement about how `q` was read, emitted
on `?q=` responses only.

### The header grammar as shipped (33-09 parses this verbatim)

```
X-Search-Interpretation: text
X-Search-Interpretation: proximity; postcode=SE22; precision=district; radiusKm=5.0
X-Search-Interpretation: proximity; postcode=SE220AA; precision=unit; radiusKm=5.0
```

Single space after each `;`. `precision` is lowercased. `radiusKm` is `String.valueOf(double)`, so
the platform default renders `5.0`, never `5`. The name is declared once, as
`SearchInterpretation.HEADER`, and referenced from the controller, the CORS default and the tests.

**Absent entirely** from the plain listing and from the `lat`/`lon` distance path — the header
answers "how did you read my `q`?", and with no `q` there is no question. **Present even when the
proximity page is empty**, which is the case that motivated choosing a header over inferring
proximity from `distanceKm != null`.

---

## Control arms — every one run in the FAIL direction, both outputs recorded

Commits were made **before** every break arm, every restore was verified **by `git hash-object`
against a pre-arm baseline**, and the clean direction was re-run **last**.

### CA-G — the index is used (live, against the 1,748,230-row `jtoye-postgres`)

Both plans verbatim, `EXPLAIN (COSTS OFF)`, 2026-08-09:

**Shipped predicate, SE22 — CLEAN:**
```
 Aggregate
   ->  Index Scan using postcode_centroid_pkey on postcode_centroid
         Index Cond: ((postcode >= 'SE220AA'::text) AND (postcode <= 'SE229ZZ'::text))
         Filter: (length(postcode) = 7)
```

**`LIKE 'SE22%'` — BREAK:**
```
 Finalize Aggregate
   ->  Gather
         Workers Planned: 2
         ->  Partial Aggregate
               ->  Parallel Seq Scan on postcode_centroid
                     Filter: (postcode ~~ 'SE22%'::text)
```

**Shipped predicate, M1 — CLEAN:**
```
 Aggregate
   ->  Index Scan using postcode_centroid_pkey on postcode_centroid
         Index Cond: ((postcode >= 'M10AA'::text) AND (postcode <= 'M19ZZ'::text))
         Filter: (length(postcode) = 5)
```

**`LIKE 'M1%'` — BREAK:** `Parallel Seq Scan on postcode_centroid, Filter: (postcode ~~ 'M1%')`.

`Seq Scan` is absent from both shipped plans and present in both `LIKE` plans. The length guard is
applied as a `Filter` on top of the `Index Cond`, so it costs nothing.

**Count cross-check — the pattern map's 507 / 548 both confirmed, and it matters that they were
re-measured rather than inherited:**

| query | rows | mean |
|---|---|---|
| `postcode_centroid` total | 1,748,230 | — |
| SE22, shipped predicate | **507** | (51.454445, −0.072403) |
| SE22, `LIKE 'SE22%'` control | **507** | (agrees — the fast form is not a different answer) |
| M1, shipped predicate | **548** | (53.477526, −2.236137) — central Manchester |
| M1, **without** the length guard | **6,422** | (53.459673, −2.220227) — *not* Manchester |
| M1, `LIKE 'M1%'` control | **6,422** | (the `LIKE` form IS precisely the unguarded range) |

No disagreement with the pattern map on any figure.

### CA-A — the fall-through is unchanged

Asserted at unit level as `verifyNoInteractions(postcodeGeocoder)` rather than as "the
interpretation is TEXT". That distinction is load-bearing: the weaker form still passes if tier 3
ran and lost.

- **CLEAN:** `q=jollof` -> the FTS page, header exactly `text`, `distanceKm` **null on every shop**,
  and the geocoder is never invoked. The jollof fixture **has real coordinates** (asserted by
  reading `latitude` back out of the database), so the null is a statement about the code path, not
  about missing data.
- **BREAK (CA-C's arm, which subsumes it):** with `headerValue()` emitting proximity
  unconditionally, the `q=jollof` header arm reds — see below.

### CA-B — the postcode tier is doing the work

**BREAK:** `AND 1 = 0` appended to `findDistrictCentroid`, i.e. an empty projection. **5 of 13
integration arms red**, including all three the plan named (1, 3, 4). Real output:

```
[a bare outward code returns nearby published kitchens...] FAILED
Expecting actual:
  []
to contain exactly (and in same order):
  ["dulwich-near-kitchen", "dulwich-mid-kitchen", "dulwich-third-kitchen"]
```

`?q=SE22` returns `[]` — **exactly the pre-fix behaviour #619 describes.** And:

```
[SE15 4QA...] Response header 'X-Search-Interpretation'
  expected:<proximity; postcode=SE15; precision=district; radiusKm=5.0> but was:<text>
```

Worth noting: the header stayed **honest** in the broken state. With the district lookup dead the
server correctly reported `text`, because it genuinely had not made a proximity reading. The
disclosure describes what happened rather than what was intended.

The unit-hit arm (2) **stayed green** under CA-B, because it resolves through `findById`. That
discrimination is itself evidence the two lookups are separate paths.

**CLEAN (re-run last):** 13/13 green. Restore verified by content —
`a77018523f8fe58091a76f50d53b922e00837d99`, identical to the pre-arm hash.

### CA-C(api) — a text match never carries a proximity claim

**BREAK:** `headerValue()` returning `proximity; postcode=ZZ99; precision=district; radiusKm=5.0`
unconditionally. **6 arms red**, including `CA-C(api): a text match carries the literal header
'text'` and all four grammar arms.

The `legitimateKeyIsNotRejected` **control** exists precisely for this shape: without it, the
hostile-key arm would be satisfied by a `headerValue()` that returns `text` unconditionally — which
is exactly the RED stub it was first written against.

**CLEAN (re-run last, `--rerun-tasks` so it genuinely executed):** green. Restore verified by
content — `bdaee8a723f9ea5fa08f26339634e43772e010bb`.

### CA-F — the unit-length guard is real

Run at **two levels**, because the guard lives in two places.

**Java half (Task 1):** `unitLength = outward.length() + 4`. **5 unit arms red**, including the
`ArgumentCaptor` bounds arm and the M1 centroid arm. Restore verified —
`4b4a0c844d928f803244fe89ddabe412b2b8a270`.

**SQL half (Task 3), the plan's named arm:** `AND length(postcode) = :unitLength` removed from the
query. **1 integration arm red**, and it reds by returning a **different set of shops**, not
different decimals:

```
[only the shop on the true M1 centroid is within 5 km of it]
Expecting actual:
  ["manchester-centre-kitchen", "manchester-eastern-kitchen"]
to contain exactly (and in same order):
  ["manchester-centre-kitchen"]
but some elements were not expected:
  ["manchester-eastern-kitchen"]
```

That is the fixture working as designed: `MCR_BEYOND` sits **5.8 km** from the correct centroid and
**~4.3 km** from the wrong one, so deleting the guard admits it. A separate arm,
`theM1FixtureCanActuallyDistinguishTheTwoCentroids`, asserts that property **of the fixture itself**
— so this arm cannot quietly stop being decisive if someone later moves a coordinate.

### CA-J(backend) — the new `@Test` methods are counted

- **FAIL direction, `scripts/docs-freshness.sh` before `--write`: rc=1.** Diff it printed:
  `java_test_methods 1534 -> 1578`, `java_test_files 255 -> 256`,
  `total_logical_invocations 2642 -> 2686`.
- **After `--write`: rc=0** — `docs-freshness OK: metrics match source (total logical invocations: 2686).`

rc=1 first is what proves the counter can see the new methods. The totals were taken **from the
regenerated `docs/metrics.json`**, never computed by arithmetic.

### Additional controls run beyond the named set

Because a zero from a search is a statement about the pattern until something proves the pattern can
match, every absence assertion below was paired with a positive control over the same corpus:

| Absence claim | Result | Positive control |
|---|---|---|
| no removed line mentions `TRAILING_POSTCODE` | 0 | the same scan finds **3** *added* lines mentioning it |
| no `LIKE` inside the `@Query` body | 0 | the same scan finds **5** in the file's comment block |
| no coordinate reaches a log in `PublicStorefrontService` | 0 | the same pattern over a scratch copy with a coordinate deliberately added to a log line finds **1** |
| `shippedDefaultNamesAllFourHeaders` unmodified (W-5) | 0 removals in `CorsExposedHeadersTest` | the same scan finds **1** removal in `PublicStorefrontServiceTest`, which I did edit in place |
| no dependency manifest touched (T-33-08-SC) | 0 | the same pattern matches **2** when fed those paths |

---

## Live "before" measurement, taken rather than inherited

Against the running (**not yet rebuilt**) `jtoye-postgres` + core-java at `:9090`, before anything
in this plan reaches the runtime:

| query | totalElements | header |
|---|---|---|
| `?q=SE22` | **0** | none |
| `?q=SE15` | 2 | none |
| `?q=jollof` | 2 | none |

This reproduces 33-07's gate measurements exactly and is the control for success criterion 1. **The
live "after" limb is explicitly NOT claimed here** — see Open Items.

---

## Verification

| Suite / gate | Result |
|---|---|
| `./gradlew :core-java:test` (full, `--rerun-tasks`) | **145 classes / 1065 tests / 0 failures** |
| `./gradlew :core-java:integrationTest` (full) | **120 classes / 540 tests / 0 failures** (33-06 baseline 119/527 — the delta is exactly this plan's 13) |
| `OpenApiSnapshotTest` | green — the committed snapshot matches the source tree |
| `RlsContractTest` | green (5) — no policy or exemption disturbed |
| `scripts/docs-freshness.sh` | rc=0 |
| `scripts/check-doc-metrics.sh` | rc=0 — 37 claims / 3 docs |
| `scripts/check-claims.sh` | rc=0 — 43 claims / 5 docs |
| `scripts/check-no-create-extension.sh` | rc=0 — 61 migrations, this plan ships none |
| `scripts/check-branch-behind-base.sh` | rc=0 — 6 ahead, **0 behind** `origin/main` |
| `scripts/check-openapi-snapshot-fresh.sh` | **rc=1 — expected, and recorded rather than hidden.** See Open Items |

Gradle reported `BUILD SUCCESSFUL` twice without printing a test summary; both were verified to have
genuinely executed by reading the result-XML timestamps and re-running with `--rerun-tasks`, because
a build that reports success while executing nothing is a recorded trap here.

---

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 1 - Bug] One fixture row silently broke three coupled invariants in a test this plan
never mentions**

- **Found during:** Task 3, by the **full** `:core-java:integrationTest` — not by any of this plan's
  own `<verify>` limbs, which is 33-06's recorded reason for running the full suite.
- **Issue:** appending `M111AA` to `postcode-centroids-fixture.csv` (Task 1, required to make the
  length guard falsifiable) reds **5 arms** in `PostcodeCentroidImportIntegrationTest`. Two failures
  were the obvious kind — `FIXTURE_ROWS = 7` and `fixture-SOURCE.md`'s declared count. **The third
  is the one worth recording:** `rowCountMismatchAborts` points the *nullisland* manifest (declaring
  8) at the *fixture* CSV (holding 7) **precisely so the two disagree**. An 8th fixture row made
  them agree, which would have turned that arm into a no-op **that still passed**. This is the
  "expected-mismatch that is satisfied on a changed tree" shape — the harm is silent.
- **Fix:** moved all three together so the one-row GAP is preserved — `FIXTURE_ROWS` 7 -> 8,
  `fixture-SOURCE.md` 7 -> 8, and `postcode-centroids-nullisland.csv` + `nullisland-SOURCE.md` 8 -> 9
  (the nullisland CSV is "the fixture plus one injected Null Island row", so it has to track).
  The requirement is now stated in `nullisland-SOURCE.md` where the next person will hit it. It also
  remains **executable, not merely documented**: the Null Island arm asserts the abort message is
  about `(0,0)` and the mismatch arm asserts it is about a `partial postcode table`, so either
  relationship breaking changes which message fires.
- **Files:** `PostcodeCentroidImportIntegrationTest.java`, `fixture-SOURCE.md`,
  `nullisland-SOURCE.md`, `postcode-centroids-nullisland.csv`
- **Commit:** `4f0579ab`

**2. [Rule 2 - Missing functionality] The declared header had an empty schema in the contract**

- **Found during:** Task 3, reading the regenerated snapshot rather than assuming it.
- **Issue:** `@Header(schema = @Schema(type = "string"))` renders as `"schema" : { }` — an empty
  object. The header was declared but its type was not, which is a weaker machine contract than the
  standing agent-readiness criterion requires.
- **Fix:** `@Schema(implementation = String.class)`, verified by regenerating both ways; the
  snapshot now carries `"schema" : { "type" : "string" }`. The reason is in a code comment so the
  next person does not "simplify" it back.
- **Commit:** `4f0579ab`

### Deliberate substitution, recorded rather than silently made

**The unit-level district probe is `SE15`, not the `SE22` named in Task 1's `<behavior>` list.** The
8-row geocoder fixture holds no SE22 unit, so an SE22 arm there could only ever assert *empty* and
would prove nothing about the district path. SE15 has three units in the fixture **and** is the exact
term the committed `locate("SE15") -> empty` assertion covers — so using it makes the two entry
points disagree on one input, which is the property Task 1 actually has to hold
(`locateStillRejectsBareOutwardCode` asserts both directions). SE22 is proven against real data by
CA-G and by the integration test. A `ZZ99` arm covers "well-formed outward code, absent from the
table -> empty", which is what the SE22 arm would have measured.

### Not a deviation, but worth stating

Two corrections the plan itself made to `33-PATTERNS.md` were re-verified rather than trusted: the
`LIKE` form really is a Parallel Seq Scan (measured above), and the 7-row fixture really does have
three SE15 units (used as the district probe).

---

## Threat model outcomes

| Threat | Disposition | Evidence |
|---|---|---|
| T-33-08-01 DoS via regex | mitigated | `MAX_SEARCH_TERM_LENGTH = 12` short-circuits before the matcher; proven by `verify(never())` on **both** repository methods, not by timing — timing alone passes on a slow regex too. Integration arm sends a 400-character `q` |
| T-33-08-02 DoS via the district query | mitigated | CA-G: Index Scan, no Seq Scan, on 1,748,230 rows; closed bounds computed in Java, named parameters only, no `LIKE` |
| T-33-08-03 unpublished / cross-tenant leakage | mitigated | `findPublishedNear` reused unchanged; unpublished shop seeded on the nearest shop's **exact** coordinates and asserted absent from content AND total **at page size 2**, where Spring Data actually issues the count; result asserted to span **>= 2** tenants, ids read from the DB and slugs from the response |
| T-33-08-04 coordinates in the header | mitigated | no coordinate in the record's header output; asserted, and the absence scan has a positive control |
| T-33-08-05 response splitting | mitigated | `headerValue()` gates on `^[A-Z0-9]{2,8}$`; arms with embedded `\r\n`, `\n`, `;`, lowercase and too-short keys all degrade to `text`, **paired with a control proving a legitimate key is not degraded** |
| T-33-08-06 customer postcode in logs | mitigated | the district miss logs the normalised key only; the raw term never reaches a log |
| T-33-08-07 CORS widening | accepted | one name added in both places; `preExistingExposuresRetained` and `shippedDefaultNamesAllFourHeaders` both untouched and green (W-5 honoured — three NEW methods) |
| T-33-08-08 stray migration/extension | mitigated | no migration ships; `check-no-create-extension.sh` rc=0 |
| T-33-08-SC dependencies | accepted | zero manifests touched, asserted with a positive control |

No new threat surface was found beyond the register.

---

## Open items for 33-09

**1. The delivered runtime does NOT yet match this branch, and that is stated rather than glossed.**
`scripts/check-openapi-snapshot-fresh.sh` returns **rc=1** against the running service, with the
gate's own diagnosis: *"the running image predates the branch"*. That is the correct answer today —
this plan changed source, not the runtime. **33-09 must rebuild with `--build` (not `start`) and
re-run it, plus `check-runtime-freshness.sh`, before its owner gate.** Success criterion 1's *live*
limb is therefore **not claimed here**: it is proven against real PostgreSQL in Testcontainers, and
the live "before" (0 shops) is recorded above as its control.

**2. D-A is the question for the owner, and it is genuinely open.** The postcode tier runs *third*,
so a full unit that matches a shop's address is answered as **text**, not as locality. Concretely,
`?q=SE15 5BS` returns the one kitchen at that address rather than every kitchen near it — and
`?q=SE22` returns nearby kitchens because nothing matches the string. **A customer cannot tell those
two behaviours apart from the input; only from the header.** The inconsistency is disclosed rather
than hidden, and 33-09 renders the two cases differently, but it is still an inconsistency a real
person will meet. Flipping to interpretation-first is a single-statement change and **every test in
this plan still applies** — the arms assert the tier's behaviour, not its position. Worth putting to
the owner in exactly those terms.

**3. A shop named "SE22 Kitchen" wins a search for `SE22`.** This is D-A working as intended and is
the reason tier 3 runs last, but it is the concrete case that separates the two orderings, so it
belongs in the walkthrough script.

**4. The header is emitted; whether a *browser* can read it is unproven here.** `CorsExposedHeadersTest`
proves the filter emits `Access-Control-Expose-Headers` containing the name, and that is the limit of
what a servlet-level test or `curl` can establish — #412 is the recorded scar where a header was
genuinely on the wire and `null` in every browser. **CA-H (an in-page `fetch` under `page.evaluate`)
is 33-09's and is not optional.**

---

## Self-Check: PASSED

Files asserted present on disk:

- `core-java/src/main/java/uk/jtoye/core/geo/DistrictCentroid.java` — FOUND
- `core-java/src/main/java/uk/jtoye/core/storefront/SearchInterpretation.java` — FOUND
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontPostcodeSearchIntegrationTest.java` — FOUND (531 lines, min_lines 200 satisfied)

Commits asserted present in `git log`:

| Commit | Message |
|---|---|
| `a5b05236` | `test(33-08)`: RED — locateSearchTerm district/unit arms fail against a stub |
| `eda9fd06` | `feat(33-08)`: GREEN — locateSearchTerm resolves a district or a unit, offline |
| `a6b169d6` | `test(33-08)`: RED — the interpretation grammar, the third tier and the CORS name all fail |
| `2466a057` | `feat(33-08)`: GREEN — the third search tier, server-asserted in a response header |
| `b5b528f9` | `test(33-08)`: Testcontainers proof — 13 arms over real PostgreSQL |
| `4f0579ab` | `docs(33-08)`: regenerate the contract and the counted docs, and repair the fixture-count invariant |

TDD gate sequence: `test` -> `feat` for Task 1 and again for Task 2; both RED commits recorded a real
assertion-failure count (6 and 11) rather than a compile error.

Clean state asserted **last** as well as first — all three break-arm targets are at their pre-arm
content hashes, no `BREAK ARM` marker survives anywhere under `core-java/src/main/java/`, the working
tree is clean against HEAD, no commit deleted a tracked file, and no untracked file was left behind.

`STATE.md` and `ROADMAP.md` were **not** modified: `git diff --name-only a5b05236~1 HEAD` matches
nothing under `.planning/`.
