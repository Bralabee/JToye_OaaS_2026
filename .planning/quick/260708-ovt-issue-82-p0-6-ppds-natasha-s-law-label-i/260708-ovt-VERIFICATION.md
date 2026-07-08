---
task: 260708-ovt-issue-82-p0-6-ppds-natasha-s-law-label-i
verified: 2026-07-08T17:55:31Z
status: passed
score: 7/7 must-haves verified
overrides_applied: 0
---

# Issue #82 [P0-6] PPDS/Natasha's Law Compliant Allergen Label — Verification Report

**Task Goal:** Replace the FSA-PROHIBITED allergen label format (plain ingredients + standalone bold CONTAINS block + "No allergens declared" fallback) with a compliant format: allergens emphasised INLINE within the ingredients list, a computed durability date, business name+address, and fail-loud (422, not a bad PDF) when required PPDS fields are missing.

**Verified:** 2026-07-08T17:55:31Z
**Status:** passed
**Branch:** `fix/82-ppds-natashas-law-label`

**Verification method:** Goal-backward, source read directly (not SUMMARY narrative). Independently re-ran the three targeted Gradle test classes and `scripts/docs-freshness.sh` from a clean invocation (not reusing SUMMARY's claimed output) and cross-checked the resulting JUnit XML result files for exact pass counts. Read every artifact file named in must_haves end-to-end.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Allergens emphasised INLINE in ingredients list; NO standalone CONTAINS block; NO "No allergens declared" fallback | ✓ VERIFIED | `ProductLabelService.renderPdf` builds ONE `Paragraph` of `Chunk`s over `model.ingredientRuns()`, alternating `boldFont`/`bodyFont` per run (lines 208-214) — genuinely inline, not a separate block. `grep -rn "No allergens declared\|CONTAINS" core-java/src/main/` returns **zero hits** (re-ran independently). The old `ALLERGEN_NAMES[14]` array and CONTAINS/no-allergens branches are gone from the file. |
| 2 | IngredientMarkupParser is a single fail-soft source of truth (never throws); render-time parse is authoritative; allergen_spans is a cache | ✓ VERIFIED | `IngredientMarkupParser.parse` (static, pure, no Spring) handles null/empty (line 57-59), dangling delimiter fail-soft (line 70-73), empty pair no-span (line 81-83) — no throw path exists anywhere in the method. `ProductLabelService.buildRenderModel` line 128 calls `IngredientMarkupParser.parse(product.getIngredientsText())` directly on the raw text at render time (re-parse, not reading `allergen_spans`). `ProductService.createProduct`/`updateProduct` (lines 75-76, 139-140) call the same parser and persist `spans()` into `allergen_spans` before save — cache-only, never read back for rendering. Re-ran `IngredientMarkupParserTest` independently: **11 tests, 0 failures** (`TEST-...IngredientMarkupParserTest.xml`, `tests="11" failures="0" errors="0"`). |
| 3 | Durability line computed from shelf_life_days + durability_type; missing → fail loud | ✓ VERIFIED | `ProductLabelService.durabilityLine` (line 162-166): `generationDate.plusDays(shelfLifeDays)`, `"BEST_BEFORE"` → "Best before: ", else "Use by: ", UK date format. `validatePpdsData` (line 92-111) adds "shelf life (shelf_life_days)" / "durability type (durability_type)" to the missing list and throws `IncompleteLabelDataException` when either is null — proven by `generateLabelFailsWhenDurabilityMissing` test (asserts both message fragments), part of the independently re-run 9/0 `ProductLabelServiceTest` suite. |
| 4 | Business name+address via tenant-scoped `ShopRepository.findByIdAndTenantId(...).orElse(null)`; null/empty shop OR blank address → `IncompleteLabelDataException` → 422, never 500, never a non-compliant PDF | ✓ VERIFIED | `ProductLabelService.generateLabel` line 79: `shopRepository.findByIdAndTenantId(product.getShopId(), tenantId).orElse(null)` — no `.get()`/`.orElseThrow()` on this call. `validatePpdsData` treats `shop == null` identically for both "business identity" and "business address" checks (lines 94-99). `GlobalExceptionHandler.handleIncompleteLabelData` (line 282-289) maps `IncompleteLabelDataException` → `HttpStatus.UNPROCESSABLE_ENTITY` (422), placed *before* the catch-all `@ExceptionHandler(Exception.class)` (line 291) so it isn't shadowed. `ShopRepository.findByIdAndTenantId(UUID,UUID)` confirmed to exist (line 30 of ShopRepository.java). Covered by `generateLabelFailsWhenShopResolvesEmpty` and `generateLabelFailsWhenAddressBlank` tests, both green in the independent re-run. |
| 5 | V41 additive nullable + products_aud mirror; applies fresh + seeded | ✓ VERIFIED | `V41__ppds_label_compliance.sql`: `ADD COLUMN IF NOT EXISTS allergen_spans jsonb`, `shelf_life_days integer`, `durability_type varchar(20) CHECK (... IN ('USE_BY','BEST_BEFORE'))` — all nullable, no default, no backfill. `products_aud` mirror for all three, nullable, no CHECK (correct per V4/V40 convention — CHECK on an append-only audit table would break historical rows). Entity `Product.java` has matching `@Column`/`@JdbcTypeCode(SqlTypes.JSON)` fields + getters/setters (lines 97-164). Orchestrator independently confirmed schema=41 live+seeded; not re-run here per task scope (no Docker). |
| 6 | AC3 golden-file test exists (committed fixtures/ppds-label-compliant.golden.json + capture bootstrap + FSA-guidance citation), guards the compliant format with positive + negative (no CONTAINS) asserts | ✓ VERIFIED | `ProductLabelGoldenFileTest.java`: class Javadoc cites all 4 FSA PPDS requirements (name of food, allergens emphasised within the list, durability date, business name+address) at lines 28-46. Active test `renderModelMatchesCommittedGolden()` calls `buildRenderModel` directly (pure, no Spring/Testcontainers) and asserts `usingRecursiveComparison()` against the committed `ppds-label-compliant.golden.json`, which contains the expected inline-emphasis structure (milk run `emphasised:true`, `durabilityLine:"Use by: 8 Jul 2026"`). `@Disabled("One-shot bootstrap...")` `captureGoldenOnce()` present (line 129-137). Re-ran independently: **2 tests, 1 skipped (the disabled bootstrap), 0 failures.** Explicit negative "no CONTAINS/no-allergens-declared" assertions live in the companion `ProductLabelServiceTest` (`buildRenderModelHasNoProhibitedContent`, `generateLabelPdfTextIsCompliant`) which is part of the same committed AC3 test suite for this change — together the golden (positive structural pin) + service test (explicit negative string asserts) satisfy the truth. |
| 7 | Vendor markup convention documented (docs/ppds-label-markup.md) | ✓ VERIFIED | `docs/ppds-label-markup.md` exists, documents the `**...**` delimiter choice/rationale, a fail-soft rules table matching the parser's actual behaviour exactly (dangling `**`, empty pair `****`, single `*`, adjacent pairs), durability fields, business-identity requirement, the 422 conditions, and explicitly scopes the frontend "mark allergens" editor as a fast-follow. `CreateProductRequest.ingredientsText` `@Schema` (lines 32-34) cross-references this doc and gives a live example matching the markup convention. |

**Score:** 7/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/resources/db/migration/V41__ppds_label_compliance.sql` | 3 product cols + 3 products_aud mirrors, additive/nullable | ✓ VERIFIED | Present, contains `ADD COLUMN IF NOT EXISTS allergen_spans` (both tables), CHECK constraint on `durability_type`. |
| `core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java` | Pure helper, fail-soft, single source of truth | ✓ VERIFIED | 96 lines, static `parse()`, no throw paths, used by both save (ProductService) and render (ProductLabelService) paths. |
| `core-java/src/main/java/uk/jtoye/core/product/LabelRenderModel.java` | Pure Jackson-serializable record | ✓ VERIFIED | Record with `productName, sku, pricePennies, ingredientRuns, durabilityLine, businessName, businessAddress` + nested `IngredientRun` record. |
| `core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java` | Testable `buildRenderModel` + thin renderer, NO CONTAINS/fallback, 422 on empty/cross-tenant shop | ✓ VERIFIED | `buildRenderModel` is `static`, package-visible, pure (no I/O); `validatePpdsData` throws `IncompleteLabelDataException`; grep confirms no CONTAINS/fallback string in main source. |
| `core-java/src/main/java/uk/jtoye/core/exception/IncompleteLabelDataException.java` | Domain exception → 422 | ✓ VERIFIED | Simple `RuntimeException` subclass, Javadoc documents the 422 mapping. |
| `core-java/src/test/java/uk/jtoye/core/product/IngredientMarkupParserTest.java` | Exact-span + edge-case tests | ✓ VERIFIED | 11 tests, independently re-run: 11/0 failures. |
| `core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java` | Mock-wired generateLabel + negative + fail-loud | ✓ VERIFIED | 9 tests, independently re-run: 9/0 failures. Covers happy path, BEST_BEFORE wording, prohibited-content negative, PDF bytes, PDF-text extraction (real `PdfTextExtractor`), 3 fail-loud 422 cases, product-not-found. |
| `core-java/src/test/java/uk/jtoye/core/product/ProductLabelGoldenFileTest.java` | AC3 golden-file test | ✓ VERIFIED | Independently re-run: 2 tests, 1 skipped (disabled bootstrap by design), 0 failures. |
| `core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json` | Committed golden reference | ✓ VERIFIED | Present, content matches the compliant render-model shape (inline emphasised "milk" run, "Use by: 8 Jul 2026", business identity). |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `ProductLabelService.java` | `ShopRepository.java` | `findByIdAndTenantId` | ✓ WIRED | Called at line 79 with `.orElse(null)`; method confirmed present in `ShopRepository.java` line 30. |
| `ProductLabelService.java` | `IngredientMarkupParser.java` | render-time parse (authoritative) | ✓ WIRED | `buildRenderModel` line 128 calls `IngredientMarkupParser.parse(product.getIngredientsText())` directly, never reads `allergen_spans`. |
| `ProductService.java` | `IngredientMarkupParser.java` | parse-on-save → `allergen_spans` cache | ✓ WIRED | Both `createProduct` (line 75-76) and `updateProduct` (line 139-140) call `parse(...).spans()` and `setAllergenSpans(...)` before `save`/`saveAndFlush`. |
| `GlobalExceptionHandler.java` | `IncompleteLabelDataException.java` | `@ExceptionHandler` → 422 | ✓ WIRED | Handler at line 282-289, `HttpStatus.UNPROCESSABLE_ENTITY`, positioned before the catch-all `Exception.class` handler (line 291) so it is not shadowed. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| IngredientMarkupParserTest actually passes (not just claimed) | `./gradlew :core-java:test --tests "uk.jtoye.core.product.IngredientMarkupParserTest"` (independent re-run) | `tests="11" failures="0" errors="0"` in JUnit XML | ✓ PASS |
| ProductLabelServiceTest actually passes | same invocation, `ProductLabelServiceTest` | `tests="9" failures="0" errors="0"` in JUnit XML | ✓ PASS |
| ProductLabelGoldenFileTest actually passes | same invocation, `ProductLabelGoldenFileTest` | `tests="2" skipped="1" failures="0" errors="0"` in JUnit XML | ✓ PASS |
| docs-freshness passes in check mode | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 726).` | ✓ PASS |
| Main source has zero CONTAINS/"No allergens declared" strings | `grep -rn "No allergens declared\|CONTAINS" core-java/src/main/` | no output | ✓ PASS |
| Test source only has negative (`doesNotContain`) uses of those strings | `grep -rn "No allergens declared\|CONTAINS" core-java/src/test/` | 5 hits, all `assertThat(...).doesNotContain(...)` | ✓ PASS |

Live-stack Docker rebuild + full `test integrationTest` suite were NOT re-run in this verification pass per the task instructions (orchestrator already independently confirmed schema=41 live+seeded, ProductLabelServiceTest 9/0, IngredientMarkupParserTest 11/0, ProductLabelGoldenFileTest 2 (1 disabled), and metrics schema=41/java=527/total=726). This verification independently re-confirmed the same three test classes and docs-freshness from a clean invocation and additionally read every artifact file end-to-end at the source level, rather than trusting the SUMMARY narrative.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| ISSUE-82-P0-6 | 260708-ovt-PLAN.md | PPDS/Natasha's Law compliant allergen label | ✓ SATISFIED | All 7 must-have truths verified against source; independently re-run tests green. |

### Anti-Patterns Found

None. Scanned all 12 main-source files listed in `files_modified` for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` — zero hits. No empty implementations, no hardcoded-empty stub patterns in the render/parse/validation paths (the parser's `List.of()` return on null/empty input is a documented, tested fail-soft behavior, not a stub — it is exercised by dedicated `IngredientMarkupParserTest` cases).

### Human Verification Required

None. All must-haves are structurally/behaviorally verifiable via source inspection and automated test re-execution. The live-stack pdftotext proof (compliant 200 PDF + two negative 422 cases) was already executed and recorded by the executor in SUMMARY.md with concrete request/response evidence (seeded product, actual pdftotext output, actual HTTP status codes and error bodies) rather than a bare claim — the orchestrator's parallel independent confirmation (schema=41 live+seeded) corroborates this was actually run against the live stack, not merely narrated. No further human action needed.

### Gaps Summary

No gaps. All 7 must-have truths are verified directly against source code (not SUMMARY claims), all 9 required artifacts exist and are substantive (none are stubs — `ProductLabelService`, `IngredientMarkupParser`, `ProductLabelGoldenFileTest` were read in full), all 4 key links are wired correctly (including the tenant-safety detail of `.orElse(null)` never `.get()`/`.orElseThrow()` on the shop lookup — the specific regression this plan was designed to prevent), and 3 test classes were independently re-executed (not just re-read from SUMMARY) with matching pass counts (11/0, 9/0, 2 with 1 intentionally-disabled bootstrap). `docs-freshness.sh` passes in check mode confirming metrics.json and CLAUDE.md are in sync with the actual test/schema counts.

---

_Verified: 2026-07-08T17:55:31Z_
_Verifier: Claude (gsd-verifier)_
