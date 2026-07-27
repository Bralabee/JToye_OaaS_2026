# 27-01 Task 4 — acceptance-criterion arms, both directions

Every arm run through `baselines/runcheck.sh <expected_rc> "<label>" -- <cmd>`, which exits 1 when
observed ≠ expected. An arm that failed to break could not have been recorded here as a pass.

Restore after each arm was `git checkout -- <file>`, which is safe here **only because the
implementation was committed first** (`a94ce77`) — the handoff's trap 1 (`git checkout` restores from
the INDEX and silently discards post-staging edits) bit three times in Task 3.

---

## AC-4.1 — 202 + the asset returns to PENDING with a fresh outbox row

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | `tests="7" failures="0" errors="0"` |
| BREAK | delete `mediaEventOutboxRepository.save(...)` from `redriveFromQuarantine` | 1 | `[exactly one fresh media_event_outbox row] expected: 1 but was: 0` |

**The outbox count is the load-bearing half, and the arm proves it.** Under the break the status
assertions (`PENDING`, `process_attempts=1`, `failure_reason IS NULL`) *all still passed*. A criterion
asserting only the status flip would have been green over a row that sits in PENDING forever with
nothing enqueued to act on it.

AC-4.2 also went RED on this arm (it shares the outbox assertion). Recorded rather than hidden — two
failures, both on the outbox count, no other assertion disturbed.

---

## AC-4.2 — idempotent replay returns the original response, no second enqueue

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | both POSTs 202, same assetId, outbox 1, attempts 1 |
| BREAK | bypass `idempotencyService.execute`; call `redriveFromQuarantine` directly | 1 | `[a retried click enqueues once, not twice] expected: 1 but was: 2` |

Only AC-4.2 failed — the independence proof. Breaking the idempotency wrapper does not disturb any
other criterion.

---

## AC-4.3 — a foreign-tenant asset is 404, never a 403 oracle

**The plan's stated break is not implementable, and would not have produced its stated RED.** The
plan says "move `shopAccessService.require(...)` **above** the `findById`" expecting
`expected: 404 but was: 403`. Under RLS there is no asset above the `findById` to resolve an owning
shop from — `require(null, SHOP_MANAGER)` is a different criterion (the null-shop GROUP_ADMIN rule),
and the request still 404s afterwards. Recorded as a withdrawn arm, with two substitutes that DO
falsify the property.

What actually delivers the 404 is **the RLS wall**, not the ordering. The ordering is what AC-4.4
proves. Two arms, run separately:

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | 404; body contains neither shopB nor productB; asset unmutated; outbox 0 |
| VOID | remove the datasource downgrade (app runs as the Testcontainers superuser), VOID guard intact | 1 | **all 7 tests fail**: `[VOID: the app datasource is not the downgraded role] expected: "rls_redrive_role" but was: "test"` |
| BREAK | downgrade removed **and** the VOID guard bypassed in this test only | 1 | `Status expected:<404> but was:<202>` |

The third row is the real finding. With the wall not filtering, a caller in tenant A does not merely
get a 403 oracle — **the re-drive SUCCEEDS on another tenant's asset (202)**. That is strictly worse
than the leak the plan anticipated, and it is why the downgrade is load-bearing rather than
decorative: without it this criterion would have reported 202 *on a correct tree*, the
"expected-0 that is 1 on the correct tree" shape.

The VOID row is equally load-bearing: it proves the guard observes the datasource under test. AC-3.6
shipped a first version of this guard that opened its own `DriverManager` connection with the
downgraded role's own credentials and therefore always saw the downgrade — decorative. This one
probes the injected `DataSource`.

---

## AC-4.4 — a SHOP_MANAGER of a different shop in the same tenant is 403

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | 403, `type = .../errors/shop-access-denied`, asset unmutated, outbox 0 |
| BREAK | delete `shopAccessService.require(resolveOwningShopId(asset), SHOP_MANAGER)` from the re-drive path only | 1 | `Status expected:<403> but was:<202>` |

Only AC-4.4 failed. The identical `require` call in `dismissFlag` was left in place, so this arm is
specific to the new surface.

---

## AC-4.5 — no retained bytes is a typed 409, both halves independently

The first form of this test looped both fixtures through `andExpect(status().isConflict())`. That
**could not discharge the criterion**: `andExpect` aborts at the first failure and reports an
unlabelled `expected 409 but was 202`, identical whichever fixture broke — while the criterion's
stated RED is specifically one-sided. Rewritten (`fbfedb9`) so both requests run before any
assertion and each half carries its own label.

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS (loop form) | tree as committed | 0 | both 409 — but unable to name a failing half |
| BREAK (loop form) | delete the `quarantineReclaimedAt != null` half | 1 | `Status expected:<409> but was:<202>` — **does not say which fixture** |
| PASS (labelled form) | tree as committed | 0 | `tests="7" failures="0"` |
| BREAK (labelled form) | same break | 1 | `[half 2 — the bytes were claimed and have since been reclaimed] expected: 409 but was: 202` |

Half 1's assertion ran first and passed. The break is visibly one-sided, which is what the criterion
claims.

---

## AC-4.6 — the re-drive budget is enforced (T-27-03)

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | 409, `code = media.redrive_budget_exhausted`, outbox 0, attempts still 3 |
| BREAK | neutralize the `processAttempts >= budget` check | 1 | `Status expected:<409> but was:<202>` |

---

## AC-4.7 — the OpenAPI snapshot matches the live contract (PRE-EXISTING gate)

Recorded as pre-existing, **not claimed as new evidence** — the gate was built in an earlier phase.

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | `./gradlew :core-java:updateOpenApiSnapshot` then `OpenApiSnapshotTest` | 0 | `docs/api/openapi-snapshot.json \| 106 +++`, `reprocess` present twice |
| BREAK | revert the snapshot to `HEAD~1` while keeping the controller | 1 | `OpenApiSnapshotTest > apiDocsMatchCommittedSnapshot() FAILED` — "The OpenAPI spec served by /v3/api-docs no longer matches the reviewed snapshot"; the emitted diff names `reprocess` (4 occurrences in the failure body) |

The diff carries the new path, both new `MediaAssetDto` components (`redrivable`, `delayed`), and
the three stable 409 codes.

---

## AC-4.8 — `redrivable`/`delayed` reach the wire; the queue carries the stalled row

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | three fixtures differ as specified |
| BREAK (a) | hardcode `redrivable = false` in the mapping | 1 | `JSON path "$[?(@.assetId=='112fe869…')].redrivable" expected:<true> but was:<false>` — **the first fixture only** |
| BREAK (a), unit layer | same break, `MediaAssetDtoMappingTest` | 1 | `[retained bytes are re-drivable] Expecting value to be true but was false` |
| BREAK (b) | revert `findReviewQueue` to the pre-D-10 query (drop the PENDING disjunct) | 1 | `No matching value at JSON path "$[?(@.assetId=='6f670e34…')].delayed"` — the stalled row is absent |

Break (a) fires on the **first** fixture (`redrivable: true`) and leaves the second
(`redrivable: false`) untouched — proving the two fixtures genuinely differ rather than both being
trivially false. It is RED at both layers, which is the useful signal: the derivation is one
expression, exercised by a pure unit test at the boundary AND end-to-end on the wire.

---

## Regression surface

| run | result |
|---|---|
| `:core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.*'` | classes=5 tests=38 failures=0 errors=0 |
| `:core-java:cleanIntegrationTest :core-java:integrationTest --tests 'uk.jtoye.core.media.*'` | classes=18 tests=63 failures=0 errors=0 |

Baseline at the Task 3 handoff was unit 36 / integ 56 → **+2 unit** (the two new
`MediaAssetDtoMappingTest` boundary tests) and **+7 integration** (`MediaRedriveControllerTest`).

**`MediaReviewQueueIntegrationTest` needed NO edit** — recorded because the plan explicitly expected
one ("expected to need a fixture assertion update"). Its `pendingId` fixture is created at `now()`,
which is well inside the 15-minute `reaper-grace-ms`, so the D-10 widening does not select it. The
widening is genuinely additive at that fixture rather than merely being asserted to be. The file is
unchanged since `74c2846` (Phase 24).
