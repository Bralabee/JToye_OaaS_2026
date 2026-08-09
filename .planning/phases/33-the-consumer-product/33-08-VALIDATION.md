---
phase: 33
slug: the-consumer-product
scope: "plans 33-08 + 33-09 (additive — issue #619 postcode-proximity search)"
status: planned
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-09
---

# Phase 33 (plans 33-08 + 33-09) — Validation Strategy

> Per-plan validation contract for feedback sampling during execution.
> Scope is **only** the additive postcode-proximity work (#619). Plans 33-00..33-07 shipped via
> PR #620 and are covered by `33-VERIFICATION.md`; nothing here re-validates them.

**Why `wave_0_complete: true` with no Wave 0 plan.** Every test harness these two plans need already
exists on the tree: `PostcodeGeocoderTest` (fixture-driven, 187 lines),
`PublicStorefrontDistanceIntegrationTest` (575 lines, the exact analog),
`CorsExposedHeadersTest` (drives the real `CorsFilter`), Jest under `frontend/`, and
`frontend/e2e/storefront-flows.spec.ts`. Two files are net-new
(`PublicStorefrontPostcodeSearchIntegrationTest`, `shop-discovery-client.test.tsx`) but neither is
scaffolding — each is a test written alongside the code it verifies, inside the plan that ships that
code. **There is no `<verify>` in either plan whose `<automated>` limb is `MISSING`.**

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + AssertJ (`./gradlew :core-java:test`) · JUnit 5 + Testcontainers (real Postgres, RLS) (`./gradlew :core-java:integrationTest`) · Jest 29.7 + @testing-library/react (`cd frontend && npm test`) · Playwright 1.62.1 (`cd frontend && npx playwright test`) · bash gates under `scripts/` |
| **Config file** | `core-java/build.gradle.kts` (the `integrationTest` and `updateOpenApiSnapshot` tasks) · `frontend/jest.config.js` · `frontend/playwright.config.ts` (baseURL from `PLAYWRIGHT_BASE_URL`) · gate exit codes: 0 = clean, 1 = violation, 2 = VOID (tooling/stack failure) |
| **Quick run command** | `./gradlew :core-java:test --tests '*PostcodeGeocoderTest*'` (~40s) · `cd frontend && npm test -- search-interpretation shop-discovery-client` (~20s) |
| **Full suite command** | `./gradlew :core-java:test && ./gradlew :core-java:integrationTest` (~40 min) then, after `docker compose -f docker-compose.full-stack.yml up -d --build`, `cd frontend && npm run build && npm test && npx playwright test storefront-flows` |
| **Estimated runtime** | ~2 min unit (both languages) · ~40 min integration · ~10 min rebuild + E2E |
| **Runtime the E2E arms run against** | Docker **Compose** (`docker-compose.full-stack.yml`) — the canonical local dev/E2E runtime. `docker compose start` rebuilds nothing; `--build` is mandatory after any source change |

**Definition of "validated" for these two plans:** every criterion is either (a) a deterministic
assertion CI re-runs on every PR, or (b) a live observation captured as **named, falsifiable
evidence** — the command, the break direction, and both actual outputs recorded in the plan's SUMMARY.
Nothing is marked complete on "it worked once", and **no criterion is reported as satisfied until it
has been observed failing.**

---

## Sampling Rate

- **After every task commit:** that task's `<verify><automated>` limb, plus
  `scripts/check-branch-behind-base.sh`.
- **After Task 2 of 33-08** (the shared-service constructor change): the **full**
  `./gradlew :core-java:test`, because a change to `PublicStorefrontService` breaks distant tests that
  this plan's own verifies never touch — 33-06's recorded reason.
- **After Task 3 of 33-08:** the full `./gradlew :core-java:integrationTest` (`OpenApiSnapshotTest`
  lives there and reds on any contract change), then `docs-freshness.sh --write` +
  `check-doc-metrics.sh`.
- **After every task commit in 33-09:** `cd frontend && npm run build` — jest does **not** type-check,
  so this is the only TypeScript gate.
- **Before the owner gate (33-09 Task 3):** `scripts/check-runtime-freshness.sh` rc=0 with every built
  service FRESH, immediately before the walkthrough is offered.
- **Max feedback latency:** ~40s unit / ~40 min integration / one rebuild cycle for E2E.

---

## Per-Task Verification Map

Requirement is **CUST-01** throughout (this work extends the locality capability CUST-01 closed;
`#619` is the issue). `Exists?` reflects the harness state at planning time (2026-08-09).

| Req | Behaviour | Type | Automated command | Exists? | Status |
|-----|-----------|------|-------------------|---------|--------|
| CUST-01 / #619 | `locateSearchTerm("SE22")` resolves to a district centroid; `locate("SE15")` still returns empty | unit | `./gradlew :core-java:test --tests '*PostcodeGeocoderTest*'` | ✅ file exists (187 lines, fixture-driven) | ⬜ pending — 33-08 Task 1 |
| CUST-01 / #619 | the district bounds are computed correctly in Java: `M1` -> `M10AA` / `M19ZZ` / length 5 | unit (ArgumentCaptor) | same | ✅ Mockito already in use | ⬜ pending — 33-08 Task 1 |
| CUST-01 / #619 | the district query uses the PK index, not a seq scan over 1,748,230 rows | **live** | `EXPLAIN (COSTS OFF)` on `jtoye-postgres`, shipped predicate vs `LIKE 'SE22%'` | ❌ live, manual | ⬜ pending — **CA-G**, both directions required |
| CUST-01 / #619 | the length guard excludes `M11`'s units from an `M1` lookup | integration (real Postgres) | `./gradlew :core-java:integrationTest --tests '*PostcodeSearch*'` | ❌ new file | ⬜ pending — **CA-F**, break = delete the guard |
| CUST-01 / #619 | `?q=SE22` returns >0 published kitchens with non-null ascending `distanceKm` | integration | same | ❌ new file | ⬜ pending — **CA-B**, break = empty projection |
| CUST-01 / #619 | `?q=jollof` is unchanged: FTS page, header `text`, `distanceKm` null on every shop | integration | same | ❌ new file | ⬜ pending — **CA-A** |
| CUST-01 / #619 | a GB-absent postcode (NI `BT1 5GS`) and `"SE22 pizza"` both fall through to FTS with header `text` | integration | same | ❌ new file | ⬜ pending |
| CUST-01 / #619 | no unpublished shop leaks, in content **or in the total** | integration | same, `.param("size","2")` | ❌ new file | ⬜ pending — page size **must** be < row count or `PageableExecutionUtils` never issues the count query (33-06 lost an arm to this) |
| CUST-01 / #619 | the proximity result spans **>= 2 tenants** (no tenant filter crept in) | integration | same | ✅ analog at `PublicStorefrontDistanceIntegrationTest:529-537` | ⬜ pending — assert `>= 2`, never `> 0` |
| CUST-01 / #619 | `?q=SE22&lat=&lon=` is still a typed 400 on `$.type`, not merely a 400 | integration | same | ✅ analog at `:466-513` | ⬜ pending |
| CUST-01 / #619 | a text match never carries a proximity claim, at the API | unit (MockMvc) | `./gradlew :core-java:test --tests '*PublicStorefrontControllerTest*'` | ✅ file exists | ⬜ pending — **CA-C(api)**, break = emit proximity unconditionally |
| CUST-01 / #619 | `headerValue()` cannot emit CR/LF or `;` from a hostile key | unit | `./gradlew :core-java:test` | ❌ new class | ⬜ pending — T-33-08-05 |
| CUST-01 / #619 | `X-Search-Interpretation` is in the shipped CORS allowlist, and #412's six are not displaced | unit (drives the real `CorsFilter`) | `./gradlew :core-java:test --tests '*CorsExposedHeadersTest*'` | ✅ file exists, 6 methods | ⬜ pending — **new method only** (W-5); `shippedDefaultNamesAllFourHeaders` must not be edited |
| CUST-01 / #619 | the committed OpenAPI snapshot matches the source tree, then the running service | integration + gate | `./gradlew :core-java:updateOpenApiSnapshot`; `scripts/check-openapi-snapshot-fresh.sh` | ✅ both exist | ⬜ pending — record rc against the **pre-rebuild** runtime too; that is what proves the clean pass needed a rebuild |
| CUST-01 / #619 | the header parser degrades every malformed input to `text` | unit (Jest) | `cd frontend && npm test -- search-interpretation` | ❌ new file | ⬜ pending — incomplete disclosure is not a disclosure |
| CUST-01 / #619 | the UI renders a proximity heading **only** on a server-disclosed proximity response | unit (Jest, RTL) | `cd frontend && npm test -- shop-discovery-client` | ❌ new file | ⬜ pending — **CA-D**, permanent two-direction test |
| CUST-01 / #619 | a 429 or network failure resets the interpretation to text | unit (Jest) | same | ❌ new file | ⬜ pending — a non-answer carries no claim |
| CUST-01 / #619 | no UK-postcode regex exists under `frontend/app/shop/` outside `[slug]/checkout/page.tsx` | static | `rg -uu` over the three named files, plus the same pattern over the checkout route | ✅ pattern exists to control against | ⬜ pending — **CA-E**, self-controlling |
| CUST-01 / #619 | the radius copy reads the literal `3.1 miles`, derived from the 5 km actually sent | unit + E2E | Jest literals + Playwright `/within 3\.1 miles of SE22/i` | ✅ `lib/distance.ts` is the single conversion | ⬜ pending — never `\d+ miles`, which accepts `5 miles` |
| CUST-01 / #619 | typing `SE22` on `/shop` shows kitchens **and** the proximity line | **live E2E** | `cd frontend && npx playwright test storefront-flows` | ⚠ spec exists (thin), needs extending | ⬜ pending — the headline arm; run from `frontend/`, never `npm --prefix` |
| CUST-01 / #619 | `Nigerian` shows no proximity wording | **live E2E** | same | ⚠ spec exists | ⬜ pending — **CA-C(ui)**; count 0 is evidence only because the SE22 arm is its positive control |
| CUST-01 / #619 | `/shop?q=SE22` discloses in the SERVER-RENDERED HTML | **live E2E** | `page.content()` after `domcontentloaded` | ❌ new arm | ⬜ pending — **CA-I** |
| CUST-01 / #619 | the header is readable by **browser JavaScript**, not just present on the wire | **live E2E, two-arm** | in-page `fetch` under `page.evaluate`, vs `curl -sI` | ❌ new arm | ⬜ pending — **CA-H**. `curl` cannot answer this question; #412 is the recorded scar |
| CUST-01 / #619 | the rate limiter is unchanged and `X-RateLimit-Remaining` is still browser-readable | **live E2E** | same spec | ✅ 429 handling exists in the island | ⬜ pending — T-33-09-02 |
| Regression | the delivered runtime matches the branch | **live gate** | `scripts/check-runtime-freshness.sh` | ✅ exists, fails closed at 2 | ⬜ pending — after the rebuild AND after the final commit |
| Regression | no `CREATE EXTENSION` in any migration | static gate | `scripts/check-no-create-extension.sh` | ✅ exists, wired in CI | ⬜ pending — trivially green (no migration ships) but asserted, not assumed |
| Regression | the counted test totals match the tree, and the prose matches the counts | static gates | `scripts/docs-freshness.sh` then `--write`; `scripts/check-doc-metrics.sh`; `scripts/check-claims.sh` | ✅ both gates wired in `docs-freshness.yml` | ⬜ pending — **CA-J**, run in BOTH plans; rc must be **1 before** `--write` |
| Regression | the branch is not behind its base | static gate | `scripts/check-branch-behind-base.sh` | ✅ exists, wired in CI | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

None. No harness is missing (see the frontmatter note). The two net-new test files ship inside the
plans that ship the code they verify — 33-06 recorded the opposite arrangement as a deviation worth
avoiding: *"The plan lists the test file under Tasks 1 and 3 but not 2, which would have left Task 2
verified only by greps. The arms were added with the code they verify."*

Two **fixture** prerequisites, both handled inside 33-08 Task 1 and Task 3 rather than as a Wave 0:

| Fixture | Why it is needed | Where |
|---|---|---|
| one real `M11`-district unit appended to `postcode-centroids-fixture.csv` | the existing 7 rows contain `M11AE` (M1, 5 chars) but nothing 6 chars long, so the length guard is unfalsifiable against the fixture | 33-08 Task 1 — coordinate read out of the committed `postcode-centroids.csv.gz`, never invented |
| `postcode_centroid` seeded inside the integration test | `application-test.yml` sets `jtoye.geo.postcode-import.enabled: false`, so the table is **EMPTY in every integration test** — an unseeded arm would pass by returning nothing | 33-08 Task 3 — `INSERT … ON CONFLICT DO NOTHING`, real Code-Point Open values |

---

## Manual-Only Verifications

| What | Why it cannot be automated | Where it is captured |
|---|---|---|
| **D-A** — should a full postcode matching a shop's address be a *text* match or a *locality* question? | A product judgement about what a customer expects, not about what the code does. #619 itself exists because the 33-07 walkthrough asked exactly this class of question and no automated arm in that phase could | 33-09 Task 3, verbatim in `33-09-SUMMARY.md` |
| **The wording** — *"3 kitchens within 3.1 miles of SE22"* and the generic exclusion line | The miles-not-kilometres correction came from the same gate; copy is judged, not asserted | 33-09 Task 3 |
| **First-paint feel** on `/shop?q=SE22` and on a phone-width viewport | CA-I proves the copy is in the HTML; it cannot prove the transition reads as correct. A screenshot cannot verify motion | 33-09 Task 3, steps 5-6 |
| **The `EXPLAIN` plans** (CA-G) | Requires the live 1.7 M-row table; the test database holds a handful of seeded rows and would show a seq scan as *cheapest*, inverting the finding | 33-08 Task 1, both plans recorded verbatim in `33-08-SUMMARY.md` |

---

## Anti-Anecdote Rules

Binding on both plans. Each is a shape this repository has been burned by.

1. **Run the fail direction first, and record both outputs.** A criterion observed only passing may be
   incapable of failing. 33-06 shipped two such arms and neither was visible from the passing side.
2. **When a break arm passes, measure *why*.** Do not accept the pass. 33-06's `acos` arm was silent
   because 96% of latitudes cannot distinguish the two formulations.
3. **Pair every absence assertion with a positive control** over the same corpus. A zero from a search
   is a statement about the pattern until something proves the pattern can match.
4. **Verify restores by content** (`git hash-object`), never by `git diff --stat`, which is empty both
   when a file is restored and when it was never written. **Commit before running arms** — `git
   checkout` restores from the index. Restore from a scratchpad copy when the file holds uncommitted
   work (the regenerated OpenAPI snapshot is exactly that case).
5. **Assert the clean state LAST as well as first.** The restore is the part nothing watches.
6. **Prove the runtime, not the source.** `docker compose start` rebuilds nothing; compare
   `.Metadata.LastTagTime` (not `.Created`, which survives a fully-cached rebuild) and read values out
   of the running artifact.
7. **`rg`/`grep` here honour `.gitignore`.** Use `rg -uu` whenever a count is evidence.
8. **A gate that measures a structure can be green over a dead feature.** After any repair, re-run the
   functional path, not the check that motivated it.
9. **Never compute a metrics total by arithmetic.** Regenerate with `scripts/docs-freshness.sh --write`
   and read the number out of `docs/metrics.json`.

---

## Validation Sign-Off

| Plan | Gate | Signed off when |
|---|---|---|
| 33-08 | `./gradlew :core-java:test` + full `:core-java:integrationTest` green; CA-A/B/C(api)/F/G/J(backend) each recorded in both directions; `check-doc-metrics`, `check-claims`, `check-no-create-extension`, `check-branch-behind-base` rc=0 **on 33-08's own commits** | `33-08-SUMMARY.md` written |
| 33-09 | `npm test` + `npm run build` + `npx playwright test storefront-flows` green against a runtime rebuilt with `--build`; CA-C(ui)/D/E/H/I/J(frontend) each recorded in both directions; `check-runtime-freshness` and `check-openapi-snapshot-fresh` rc=0; owner verdict received and quoted verbatim | `33-09-SUMMARY.md` written |

**Neither plan may borrow the other's greenness.** 33-08 regenerates `docs/metrics.json` for its Java
`@Test` methods and 33-09 regenerates it again for its Jest and Playwright blocks. That is not
duplication: a plan whose commits red CI until its sibling lands is the failure this repository's
gates exist to catch.
