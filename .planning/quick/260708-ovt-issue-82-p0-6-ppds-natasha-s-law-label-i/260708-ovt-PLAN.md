---
phase: quick-260708-ovt
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [ISSUE-82-P0-6]
files_modified:
  - core-java/src/main/resources/db/migration/V41__ppds_label_compliance.sql
  - core-java/src/main/java/uk/jtoye/core/product/Product.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
  - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
  - core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java
  - core-java/src/main/java/uk/jtoye/core/product/AllergenSpan.java
  - core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java
  - core-java/src/main/java/uk/jtoye/core/product/LabelRenderModel.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  - core-java/src/main/java/uk/jtoye/core/exception/IncompleteLabelDataException.java
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/test/java/uk/jtoye/core/product/IngredientMarkupParserTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductLabelGoldenFileTest.java
  - core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json
  - docs/ppds-label-markup.md
  - docs/metrics.json
  - CLAUDE.md

must_haves:
  truths:
    - "Allergens are emphasised INLINE within the ingredients list on the generated PDF label (bold runs interleaved with normal text), with NO standalone CONTAINS block"
    - "The label prints a computed durability date line ('Use by:' or 'Best before:') derived from products.shelf_life_days + products.durability_type at generation time"
    - "The label prints the business name and address resolved from the product's owning shop"
    - "No 'No allergens declared' or other misleading-compliant fallback path remains in ProductLabelService"
    - "Label generation throws IncompleteLabelDataException (HTTP 422) naming the missing field(s) when required PPDS data is absent, instead of emitting a non-compliant PDF"
    - "The generated label's FSA-compliant format is protected by a committed golden reference (reviewed against FSA PPDS guidance) plus inline positive/negative PDF-text assertions, so any regression that reintroduces a CONTAINS block, drops the durability date, or omits the business identity fails the build"
  artifacts:
    - path: "core-java/src/main/resources/db/migration/V41__ppds_label_compliance.sql"
      provides: "products.allergen_spans (jsonb), shelf_life_days (int), durability_type (varchar+CHECK) + products_aud mirror"
      contains: "ADD COLUMN IF NOT EXISTS allergen_spans"
    - path: "core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java"
      provides: "Pure helper: ingredients-text-with-markup -> {plainText, List<AllergenSpan>}; single source of truth; fail-soft"
      min_lines: 40
    - path: "core-java/src/main/java/uk/jtoye/core/product/LabelRenderModel.java"
      provides: "Pure render data (Jackson-serializable record): productName, sku, pricePennies, ingredient runs w/ emphasis flags, durabilityLine, businessName, businessAddress"
      min_lines: 15
    - path: "core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java"
      provides: "Testable buildRenderModel(product,shop,generationDate) + thin OpenPDF renderer; NO CONTAINS block, NO fallback; empty/cross-tenant shop -> 422"
      contains: "IncompleteLabelDataException"
    - path: "core-java/src/main/java/uk/jtoye/core/exception/IncompleteLabelDataException.java"
      provides: "Domain exception for missing PPDS fields -> 422"
    - path: "core-java/src/test/java/uk/jtoye/core/product/IngredientMarkupParserTest.java"
      provides: "Exact-span + edge-case parser unit tests (TDD — written in Task 2 before the parser passes)"
    - path: "core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java"
      provides: "Mock-wired (ProductRepository+ShopRepository, TenantContext set/cleared) generateLabel tests: PDF-text negative asserts + fail-loud (missing address/durability + non-null-but-empty shop); minimal render-model happy-path created in Task 3, expanded in Task 4"
    - path: "core-java/src/test/java/uk/jtoye/core/product/ProductLabelGoldenFileTest.java"
      provides: "AC3 golden-file test — serializes buildRenderModel output for a FIXED compliant fixture at a FIXED generationDate and asserts recursive equality with a committed golden JSON reviewed against FSA PPDS guidance; @Disabled capture-bootstrap to regenerate"
    - path: "core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json"
      provides: "Committed golden reference LabelRenderModel JSON for the compliant fixture (AC3)"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java"
      to: "core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java"
      via: "findByIdAndTenantId for tenant-safe business identity (empty Optional -> 422, never 500)"
      pattern: "findByIdAndTenantId"
    - from: "core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java"
      to: "core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java"
      via: "render-time parse of ingredients_text (authoritative)"
      pattern: "IngredientMarkupParser"
    - from: "core-java/src/main/java/uk/jtoye/core/product/ProductService.java"
      to: "core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java"
      via: "parse-on-save persists spans to allergen_spans cache"
      pattern: "IngredientMarkupParser"
    - from: "core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java"
      to: "core-java/src/main/java/uk/jtoye/core/exception/IncompleteLabelDataException.java"
      via: "@ExceptionHandler -> 422 UNPROCESSABLE_ENTITY"
      pattern: "IncompleteLabelDataException"
---

<objective>
Make the PPDS (Natasha's Law) allergen label FSA-compliant. Today `ProductLabelService.generateLabel` renders a PROHIBITED format: a raw ingredients paragraph followed by a standalone bold "CONTAINS:" allergen block (or "No allergens declared"). The FSA requires allergens to be emphasised INLINE within the ingredients list, plus a durability date ("Use by" / "Best before") and the business name + address. This plan rewrites the label to the compliant format, removes the non-compliant fallback, and makes generation fail LOUDLY (HTTP 422) when required PPDS data is missing rather than emitting a misleading label.

Purpose: Legal compliance. A non-compliant PPDS label is a food-safety and regulatory liability.
Output: V41 schema (allergen_spans, shelf_life_days, durability_type + _aud mirror), a pure IngredientMarkupParser, a testable LabelRenderModel + thin OpenPDF renderer, an IncompleteLabelDataException (422), full parser + render-model + PDF-text tests, an AC3 golden-file test (committed reference reviewed against FSA PPDS guidance), refreshed docs/metrics, and a live-stack proof.

LOCKED USER DECISIONS honoured:
- (A) Inline markup + parser: keep `products.ingredients_text`; adopt ONE unambiguous markup delimiter; store parsed emphasis spans in a NEW nullable column `allergen_spans`; renderer emboldens those spans WITHIN the ingredients list; NO standalone CONTAINS block. (See DECIDED below for delimiter + storage reconciliation.)
- (B) Per-product shelf-life: add `products.shelf_life_days` (INT nullable) + `durability_type` (VARCHAR CHECK IN ('USE_BY','BEST_BEFORE') nullable). Label prints 'Use by: <generationDate + shelf_life_days>' or 'Best before:' computed at generation time.
</objective>

<decisions>
Planner decisions this plan locks in (stated per the quick-task constraints):

- **DECIDED — markup delimiter: `**...**` (double asterisk).** Chosen over `[[...]]` because `**` never collides with real ingredient punctuation (parentheses `()`, commas `,`, percentages `%` are common: "Yam (100%)", "Milk, Sugar") and reads as "bold" to vendors. Vendors wrap allergen words: `Wheat flour, **milk**, sugar, **egg**`.

- **DECIDED — storage/render reconciliation: render-time parse is AUTHORITATIVE; `allergen_spans` is a persisted cache written on save.** Rationale: `ingredients_text` is mutable; storing ONLY spans risks stale offsets pointing at the wrong characters after an edit -> wrong emphasis -> non-compliant label. So: (1) `ProductService.createProduct`/`updateProduct` parse `ingredients_text` and persist the resulting spans into `allergen_spans` (satisfies LOCKED decision A "store the parsed spans" + enables future consumers e.g. a storefront allergen badge). (2) The label renderer RE-PARSES `ingredients_text` fresh via `IngredientMarkupParser` and NEVER trusts the stored column for rendering. `IngredientMarkupParser` is the single source of truth for both paths. This is the "cache-on-save with render-time re-parse" reading of decision A.

- **DECIDED — durability is a REQUIRED PPDS field -> fail-loud.** A compliant PPDS label MUST carry a durability date, so `generateLabel` throws `IncompleteLabelDataException` when `shelf_life_days` OR `durability_type` is null (alongside the missing-business-address case). Existing products (all have null shelf-life until a vendor sets it) will correctly 422 until the vendor supplies the data — this is the intended compliance behaviour and directly replaces the removed non-compliant fallback.

- **DECIDED — business identity read is tenant-safe AND empty-Optional-safe.** Resolve the shop via `ShopRepository.findByIdAndTenantId(shopId, TenantContext.get())`, NOT plain `findById`. The `shops_public_read` RLS policy (V16) permits `published=true`, so a plain `findById` could print ANOTHER tenant's published shop name/address onto the label (cross-tenant leak). This mirrors the BE-03 lesson already baked into `ShopRepository`. Additionally: `products.shop_id` is `ON DELETE SET NULL` and a client-supplied `shopId` is NOT tenant-validated on create/update (FK checks bypass RLS), so `findByIdAndTenantId(shopId, tenant)` can legitimately return `Optional.empty()` for a NON-NULL `shopId`. That empty result is treated IDENTICALLY to a missing business identity -> `IncompleteLabelDataException` -> 422 (see Task 3), NEVER an unhandled `NoSuchElementException`/500.

- **DECIDED — the render-model build takes an INJECTABLE generationDate (already the case) so the golden is deterministic.** `buildRenderModel(Product, Shop, LocalDate generationDate)` is a pure package-visible method; the AC3 golden-file test (Task 4) calls it with a FIXED `LocalDate` so `'Use by' = generationDate + shelf_life_days` is byte-stable. `generateLabel(UUID)` keeps calling `buildRenderModel(product, shop, LocalDate.now())` — no Clock bean needs to be injected into the service.

- **DECIDED — `allergen_mask` is retained but the label no longer reads it.** The bitmask column/field stays for other consumers (storefront filtering, existing API contract), but the CONTAINS block and the hardcoded `ALLERGEN_NAMES[14]` array are deleted from the label service. `allergen_mask` remains a required `CreateProductRequest` field (unchanged); out of scope to remove.

- **DECIDED — frontend scope: BACKEND-ONLY for this PR.** The "mark allergens" WYSIWYG editor is a UX enhancement in the large `frontend/app/dashboard/products/page.tsx` product form and is scoped to a FAST-FOLLOW issue. The compliance-critical path (parser + storage + render + fail-loud) is fully backend and complete in this PR. The `**...**` markup convention is documented for vendors in `docs/ppds-label-markup.md` and surfaced in the `CreateProductRequest.ingredientsText` Swagger `@Schema` description. No frontend code changes ship in this PR.

- **NOTE — no separate `UpdateProductRequest` exists.** `ProductController` uses `CreateProductRequest` for both create and update. "Thread new fields" therefore applies to `CreateProductRequest` + `ProductDto` + `ProductMapper` + `Product` only.

- **NOTE — no new dependencies.** OpenPDF 2.0.3 is already present and bundles `com.lowagie.text.pdf.PdfReader` + `com.lowagie.text.pdf.parser.PdfTextExtractor` for the byte->text end-to-end test. No PDFBox, no npm/pip/cargo installs -> no package-legitimacy gate needed.
</decisions>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md

<!-- Current PROHIBITED implementation to replace -->
@core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
@core-java/src/main/java/uk/jtoye/core/product/ProductController.java

<!-- Entity + DTO/mapper to thread new fields through -->
@core-java/src/main/java/uk/jtoye/core/product/Product.java
@core-java/src/main/java/uk/jtoye/core/product/ProductService.java
@core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
@core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
@core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java

<!-- Business identity resolution -->
@core-java/src/main/java/uk/jtoye/core/shop/Shop.java
@core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java

<!-- Conventions to mirror -->
@core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql
@core-java/src/test/java/uk/jtoye/core/finance/FinancialSummaryGoldenFileTest.java
@core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java
@core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java
@core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
@scripts/docs-freshness.sh
@docs/metrics.json

<interfaces>
<!-- Contracts the executor needs. Extracted from the codebase — no exploration required. -->

JSONB precedent (Shop.java) — use the SAME mapping for allergen_spans:
```java
@Column(name = "opening_hours", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private Map<String, String> openingHours;
```

products_aud audit table (V4) — mirror new columns here, ALWAYS nullable, no default/CHECK:
```
CREATE TABLE products_aud ( id, rev, revtype, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, ... )
```

Tenant-safe shop read (ShopRepository.java) — USE THIS, not plain findById:
```java
Optional<Shop> findByIdAndTenantId(UUID id, UUID tenantId);  // avoids shops_public_read cross-tenant leak
```
Shop getters: getName() (NOT NULL), getAddress() (nullable — may be blank/null).
NOTE: this Optional CAN be empty for a non-null shopId (shop_id is ON DELETE SET NULL; a client-supplied shopId is not tenant-validated on create/update — FK checks bypass RLS). Handle with `.orElse(null)`, never `.get()`/`.orElseThrow()`.

TenantContext (uk.jtoye.core.security.TenantContext): `static void set(UUID)`, `static Optional<UUID> get()`, `static void clear()`. Tests mirror ProductServiceTest: `TenantContext.set(tenantId)` in @BeforeEach, `TenantContext.clear()` in @AfterEach.

Custom-exception -> HTTP status pattern (GlobalExceptionHandler.java) — plain RuntimeException + a dedicated @ExceptionHandler:
```java
@ExceptionHandler(InsufficientStockException.class)
public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    ...
    return problem;
}
```
New handler goes BEFORE the catch-all `@ExceptionHandler(Exception.class)` at the end of the class.

OpenPDF inline-emphasis primitives (already imported in ProductLabelService): build ONE Paragraph from Chunks, alternating fonts per run:
`new Chunk(runText, run.emphasised() ? boldFont : bodyFont)` added to the same Paragraph -> inline bold within flowing text.
Text-extraction for tests: `com.lowagie.text.pdf.PdfReader` + `com.lowagie.text.pdf.parser.PdfTextExtractor` (both bundled in openpdf:2.0.3).

Golden-file mechanics to mirror (FinancialSummaryGoldenFileTest.java): committed JSON at `core-java/src/test/resources/fixtures/*.golden.json`; an active test that reads the golden and asserts `usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(expected)`; a `@Test @Disabled("one-shot bootstrap")` capture method that writes the golden via `objectMapper().writerWithDefaultPrettyPrinter()`. The label golden is PURE (buildRenderModel is a package-visible pure method) — NO Testcontainers/Spring needed, unlike the finance one.

docs-freshness counting (scripts/docs-freshness.sh): counts `@Test\b` across git-tracked+untracked `core-java/src/test/**.java`; `schema_version` = max `V<N>__` migration; run `--write` to regenerate `docs/metrics.json`.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: V41 migration + thread shelf-life/durability/spans fields through entity, mapper, DTO, request</name>
  <files>
core-java/src/main/resources/db/migration/V41__ppds_label_compliance.sql,
core-java/src/main/java/uk/jtoye/core/product/AllergenSpan.java,
core-java/src/main/java/uk/jtoye/core/product/Product.java,
core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java,
core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java,
core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
  </files>
  <action>
Create migration `V41__ppds_label_compliance.sql` (V41 is the next free version; V40 is latest). Header comment: PPDS/Natasha's Law compliance (Issue #82 P0-6); all statements additive, nullable, forward-only, safe no-op on a fresh/zero-row schema; mirror the _aud convention per V40/V38. Statements:
- `ALTER TABLE products ADD COLUMN IF NOT EXISTS allergen_spans jsonb;` (nullable, no default — render-time cache of parsed emphasis spans)
- `ALTER TABLE products ADD COLUMN IF NOT EXISTS shelf_life_days integer;` (nullable)
- `ALTER TABLE products ADD COLUMN IF NOT EXISTS durability_type varchar(20) CHECK (durability_type IN ('USE_BY','BEST_BEFORE'));` (nullable — a NULL value satisfies the CHECK in Postgres, matching V40's inline-CHECK style)
- Envers mirror (ALWAYS nullable, no default/CHECK, per V4/V40 convention — without these the next audited products write fails at the audit INSERT):
  `ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS allergen_spans jsonb;`
  `ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS shelf_life_days integer;`
  `ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS durability_type varchar(20);`

Create `AllergenSpan.java` — a Jackson-serializable record `public record AllergenSpan(int start, int end)` (offsets into the parser's plainText; start inclusive, end exclusive). This is the JSON shape stored in `allergen_spans`.

`Product.java`: add three fields with getters/setters:
- `allergenSpans` mapped as `List<AllergenSpan>` with `@Column(name="allergen_spans", columnDefinition="jsonb")` + `@JdbcTypeCode(SqlTypes.JSON)` (mirror Shop.openingHours precedent; import org.hibernate.annotations.JdbcTypeCode + org.hibernate.type.SqlTypes). Nullable.
- `shelfLifeDays` -> `Integer` `@Column(name="shelf_life_days")` nullable.
- `durabilityType` -> String `@Column(name="durability_type", length=20)` nullable. (Keep as String to match the varchar+CHECK; a DurabilityType enum is optional but a plain String avoids a JPA enum-mapping decision — String is fine and matches the CHECK values 'USE_BY'/'BEST_BEFORE'.)
Since Product is `@Audited`, all three are audited by default — the products_aud mirror above satisfies that.

`ProductDto.java`: add `shelfLifeDays` (Integer), `durabilityType` (String), and `allergenSpans` (List<AllergenSpan>) with getters/setters so the API round-trips them.

`CreateProductRequest.java`: add `shelfLifeDays` (Integer, optional) and `durabilityType` (String, optional, `@Pattern(regexp="USE_BY|BEST_BEFORE")` when present) with getters/setters + `@Schema` descriptions. Do NOT add allergenSpans to the request (spans are derived server-side, never client-supplied). UPDATE the existing `ingredientsText` `@Schema` description to document the `**allergen**` inline markup convention (e.g. example `"Wheat flour, **milk**, sugar"`), pointing vendors at docs/ppds-label-markup.md.

`ProductMapper.java`: MapStruct maps matching field names automatically, so `shelfLifeDays`/`durabilityType` flow through `toEntity`/`updateEntity`/`toDto` with no extra mapping lines. Add `@Mapping(target="allergenSpans", ignore=true)` to BOTH `toEntity` and `updateEntity` (spans are set by the service after parsing, not by the mapper). `toDto` maps allergenSpans through normally.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew compileJava -q 2>&1 | tail -20; grep -q "allergen_spans" src/main/resources/db/migration/V41__ppds_label_compliance.sql && grep -q "products_aud ADD COLUMN IF NOT EXISTS allergen_spans" src/main/resources/db/migration/V41__ppds_label_compliance.sql && echo "V41_OK"</automated>
  </verify>
  <done>V41 migration exists with all 3 product columns + 3 products_aud mirrors; AllergenSpan record + Product/ProductDto/CreateProductRequest fields compile; ProductMapper ignores allergenSpans on write paths; ingredientsText Schema documents the markup convention.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: IngredientMarkupParser — pure, fail-soft single source of truth (test-first); persist spans on save</name>
  <files>
core-java/src/test/java/uk/jtoye/core/product/IngredientMarkupParserTest.java,
core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java,
core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  </files>
  <behavior>
Parser (`ingredients_text` with `**...**` markup -> ParsedIngredients{ String plainText; List<AllergenSpan> spans }):
- "Wheat flour, **milk**, sugar" -> plainText "Wheat flour, milk, sugar"; spans [{13,17}] (substring(13,17)=="milk").
- No markup "Yam (100%)" -> plainText unchanged, spans empty (real punctuation ( ) % , preserved, never treated as markup).
- Adjacent markup "**milk****egg**" -> plainText "milkegg"; two spans [{0,4},{4,7}].
- Multiple spans "**milk**, sugar, **egg**" -> two spans over "milk" and "egg".
- Unmatched/naked delimiter "Milk ** and egg" (odd/dangling) -> FAIL-SOFT: no throw; stray "**" kept literal in plainText, spans empty.
- Empty pair "****" -> delimiters removed, NO zero-length span produced.
- null / blank input -> plainText == input (or ""), spans empty; never throws.
- Left-to-right, non-nested pairing: each "**" opens, the next "**" closes.
  </behavior>
  <action>
TDD (this is a `tdd="true"` task — write the test FIRST, watch it fail, then implement): Create `IngredientMarkupParserTest` (pure JUnit 5, no Spring/Testcontainers) covering EVERY behaviour in the behaviour list above — one @Test per case, asserting exact plainText and exact AllergenSpan offsets (verify `substring(start,end)` equals the allergen word). This is the canonical parser test; Task 4 does NOT re-create it.

Then create `IngredientMarkupParser` as a pure helper (no Spring bean needed — expose a `static ParsedIngredients parse(String raw)`; define `ParsedIngredients` as a nested/public record `record ParsedIngredients(String plainText, List<AllergenSpan> spans)`). Scan left-to-right for `**` pairs; content between an open and the next `**` is one emphasised run recorded as an AllergenSpan over offsets into the accumulated plainText. Delimiters are stripped from plainText. Robust + fail-soft per the behaviour list — NEVER throw on vendor input (a dangling/naked `**` is emitted as literal text with no span; empty pairs produce no span). This is the SINGLE SOURCE OF TRUTH used by both the save path (this task) and the render path (Task 3).

`ProductService.java`: in BOTH `createProduct` and `updateProduct`, after the mapper populates the entity, call `IngredientMarkupParser.parse(product.getIngredientsText())` and `product.setAllergenSpans(parsed.spans())` BEFORE the `save`/`saveAndFlush`. This persists the parsed spans into the `allergen_spans` cache column (honours LOCKED decision A "store the parsed spans"). Do NOT change how ingredients_text is stored — the raw text (WITH `**` markup) stays in ingredients_text; the parser is the canonical transform to plainText+spans. Keep it minimal: two call sites, no new dependencies injected (parser is static).
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests "uk.jtoye.core.product.IngredientMarkupParserTest" -q 2>&1 | tail -25 && grep -q "IngredientMarkupParser.parse" src/main/java/uk/jtoye/core/product/ProductService.java && echo "PARSER_TESTED_AND_WIRED_OK"</automated>
  </verify>
  <done>IngredientMarkupParserTest is GREEN (proves the tdd cycle) and covers all behaviour-list cases incl. fail-soft edge cases; IngredientMarkupParser.parse is a pure static helper returning {plainText, spans}; createProduct + updateProduct persist parsed spans to allergen_spans before save.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Rewrite ProductLabelService — LabelRenderModel + inline-emphasis renderer (test-first render-model); remove CONTAINS/fallback; fail loud on missing/empty-shop PPDS data</name>
  <files>
core-java/src/main/java/uk/jtoye/core/product/LabelRenderModel.java,
core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java,
core-java/src/main/java/uk/jtoye/core/exception/IncompleteLabelDataException.java,
core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java,
core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java
  </files>
  <behavior>
buildRenderModel(Product, Shop, LocalDate generationDate) — pure, no I/O, INJECTABLE date (deterministic for a fixed generationDate — this is what the AC3 golden relies on):
- ingredientRuns: from IngredientMarkupParser.parse(ingredientsText) -> ordered List<IngredientRun{String text, boolean emphasised}> covering the whole plainText (non-emphasised segments + emphasised spans interleaved). e.g. "Wheat flour, **milk**, sugar" -> [("Wheat flour, ",false),("milk",true),(", sugar",false)].
- durabilityLine: durability_type USE_BY -> "Use by: " + (generationDate + shelf_life_days), BEST_BEFORE -> "Best before: " + date; date formatted "d MMM yyyy" Locale.UK (e.g. "5 Jul 2026"). Deterministic for a fixed generationDate.
- businessName = shop.getName(); businessAddress = shop.getAddress().
- NEGATIVE: no run/line ever equals or contains "CONTAINS:" or "No allergens declared".
Fail-loud: generateLabel throws IncompleteLabelDataException naming EVERY missing required PPDS field when: shop_id null; shop RESOLVES TO EMPTY (findByIdAndTenantId returns Optional.empty() for a non-null shopId); shop.address null/blank; shelf_life_days null; durability_type null.
Minimal co-located test (this task, tdd="true"): a render-model happy-path unit test proving buildRenderModel emits an emphasised "milk" run + "Use by: <fixed date>" for a FIXED generationDate.
  </behavior>
  <action>
TDD (this is a `tdd="true"` task): FIRST replace the OLD `ProductLabelServiceTest` — its 10 tests assert removed behaviour (byte-size deltas from the CONTAINS block, the single-arg constructor, products with no shop) and will NOT compile once the constructor gains a ShopRepository param. Replace them with a single minimal happy-path render-model @Test: construct a compliant fixture Product (shopId set, ingredients "Wheat flour, **milk**, sugar", shelf_life_days=3, durability_type="USE_BY", pricePennies set — use reflection for id like ProductServiceTest) and a Shop with name+address, then call the package-visible `buildRenderModel(product, shop, LocalDate.of(2026,7,5))` DIRECTLY (pure — no mocks, no TenantContext needed) and assert an IngredientRun with text=="milk" && emphasised==true exists and durabilityLine=="Use by: 8 Jul 2026". Watch it fail (RED), then implement to GREEN. Task 4 EXPANDS this file with the mock-wired generateLabel/PDF/fail-loud/golden coverage.

Create `IncompleteLabelDataException extends RuntimeException` in the exception package (mirror InsufficientStockException javadoc style; note it maps to HTTP 422). Message must NAME the missing fields, e.g. "Cannot generate PPDS label for product <id>: missing business identity, missing durability date (shelf_life_days/durability_type)".

`GlobalExceptionHandler.java`: add `@ExceptionHandler(IncompleteLabelDataException.class)` returning `ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage())` with title "Incomplete Label Data" and a type URI (mirror existing handlers). Place it BEFORE the catch-all `@ExceptionHandler(Exception.class)`. 422 is the precise semantic: the request is well-formed but the product's persisted data cannot be turned into a compliant label.

Create `LabelRenderModel` — a Jackson-serializable record: `productName, sku, Long pricePennies, List<IngredientRun> ingredientRuns, String durabilityLine, String businessName, String businessAddress`, with nested `record IngredientRun(String text, boolean emphasised)`. (Records serialize cleanly for the AC3 golden.)

Rewrite `ProductLabelService.java`:
- Inject `ShopRepository` alongside `ProductRepository` (new constructor param).
- DELETE the `ALLERGEN_NAMES[14]` array, the `CONTAINS:` block, and the `"No allergens declared"` else-branch entirely.
- `generateLabel(UUID)`: load product via productRepository.findById (RLS-scoped, 404 via ResourceNotFoundException unchanged). Resolve shop: if `product.getShopId()==null` -> collect "business identity" as missing; else `Shop shop = shopRepository.findByIdAndTenantId(shopId, TenantContext.get().orElseThrow(...)).orElse(null);` and if `shop == null` collect "business identity" as missing TOO. CRITICAL (WARNING 4): the Optional CAN be empty for a NON-NULL shopId (shop_id is ON DELETE SET NULL; a client-supplied shopId is not tenant-validated — FK checks bypass RLS), so you MUST use `.orElse(null)` and treat null/empty IDENTICALLY to shop_id==null. NEVER `.get()`/`.orElseThrow()` on that lookup — an empty Optional must become a 422, never an unhandled NoSuchElementException/500. Then validate the remaining required PPDS fields (business address non-blank, shelf_life_days, durability_type); if ANY are missing -> throw IncompleteLabelDataException listing ALL missing fields (do not emit a PDF). Otherwise call `buildRenderModel(product, shop, LocalDate.now())` then `renderPdf(model)`.
- Extract `LabelRenderModel buildRenderModel(Product, Shop, LocalDate generationDate)` as a package-visible PURE method (unit-testable + golden-serializable, no repo/PDF I/O) that builds runs (via IngredientMarkupParser — render-time authoritative re-parse, NOT the stored allergen_spans column), the durability line, and business identity. The generationDate param stays INJECTABLE (do NOT hardcode LocalDate.now() inside it) so the golden test can pin it.
- Extract `byte[] renderPdf(LabelRenderModel)` — the thin OpenPDF layer. Keep the 100x60mm (283x170pt) Rectangle. Render: title (bold, centered), sku, price (if present). Then a bold "Ingredients:" heading, then ONE Paragraph composed of Chunks iterating ingredientRuns — emphasised runs in bold font, others in body font — producing INLINE bold within the flowing ingredients text. Then the durabilityLine, then businessName + businessAddress. NO CONTAINS block anywhere.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests "uk.jtoye.core.product.ProductLabelServiceTest" -q 2>&1 | tail -25 && ! grep -q "No allergens declared" src/main/java/uk/jtoye/core/product/ProductLabelService.java && ! grep -q "CONTAINS" src/main/java/uk/jtoye/core/product/ProductLabelService.java && grep -q "findByIdAndTenantId" src/main/java/uk/jtoye/core/product/ProductLabelService.java && grep -q "orElse(null)" src/main/java/uk/jtoye/core/product/ProductLabelService.java && grep -q "UNPROCESSABLE_ENTITY" src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java && echo "LABEL_REWRITE_OK"</automated>
  </verify>
  <done>Minimal render-model happy-path test is GREEN (tdd cycle honoured); ProductLabelService renders inline-emphasised ingredients with NO CONTAINS block and NO "No allergens declared"; resolves business identity via findByIdAndTenantId with `.orElse(null)` and treats null/empty shop as missing (never 500); throws IncompleteLabelDataException naming missing PPDS fields; GlobalExceptionHandler maps it to 422; buildRenderModel is a pure testable method with an injectable generationDate.</done>
</task>

<task type="auto">
  <name>Task 4: Expand label tests (mock-wired generateLabel + PDF-text + AC3 golden-file + fail-loud) and docs/metrics sync</name>
  <files>
core-java/src/test/java/uk/jtoye/core/product/ProductLabelServiceTest.java,
core-java/src/test/java/uk/jtoye/core/product/ProductLabelGoldenFileTest.java,
core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json,
docs/ppds-label-markup.md,
docs/metrics.json,
CLAUDE.md
  </files>
  <action>
EXPAND `ProductLabelServiceTest` (Task 3 created the minimal happy-path version; IngredientMarkupParserTest already exists from Task 2 — do NOT re-create it here). Add mock-wired generateLabel coverage: `@ExtendWith(MockitoExtension.class)` with `@Mock ProductRepository` + `@Mock ShopRepository` (the service now takes both).
- TenantContext wiring (WARNING 3): the service's generateLabel calls `TenantContext.get()`. In @BeforeEach do `TenantContext.set(tenantId)` and in @AfterEach do `TenantContext.clear()` (mirror ProductServiceTest). Stub `shopRepository.findByIdAndTenantId(shopId, tenantId)` using the SAME `tenantId` that was put in TenantContext.set (and the compliant fixture's shopId) — otherwise the lookup returns empty and the happy-path test would wrongly 422.
- Build a compliant fixture: product with shopId set, ingredients "Wheat flour, **milk**, sugar", shelf_life_days=3, durability_type="USE_BY"; shop with name + address. Cover:
  - buildRenderModel (already partly covered in Task 3): assert ingredientRuns contain an emphasised run whose text=="milk" and non-emphasised neighbours; durabilityLine == "Use by: " + expected date for a FIXED generationDate; businessName + businessAddress populated. NEGATIVE (model): no IngredientRun text and no field contains "CONTAINS" or "No allergens declared".
  - generateLabel (mock-wired, TenantContext set): returns non-empty bytes starting with "%PDF".
  - End-to-end PDF text assert: extract text with `PdfReader` + `PdfTextExtractor` (openpdf 2.0.3); assert extracted text CONTAINS productName, "Use by:", businessName, businessAddress; and does NOT contain "CONTAINS" or "No allergens declared". (If PdfTextExtractor is somehow unresolvable at compile time, fall back to asserting on the render model for content + a non-empty-%PDF smoke test — but it IS bundled, so prefer the real extraction.)
  - Fail-loud (WARNING 4 — explicit): generateLabel throws IncompleteLabelDataException (a) when shop has null/blank address; (b) when shelf_life_days/durability_type is null; (c) when shopId is NON-NULL but `shopRepository.findByIdAndTenantId(shopId, tenantId)` returns `Optional.empty()` (cross-tenant/orphaned shop) — assert it 422s with a business-identity message, NOT a NoSuchElementException/500. Assert each message names the missing field(s).
  - Product-not-found still throws ResourceNotFoundException (keep this case).

Create `ProductLabelGoldenFileTest` (AC3 — BLOCKER FIX). Mirror the mechanics of FinancialSummaryGoldenFileTest, but PURE (no Testcontainers/Spring — buildRenderModel is a package-visible pure method): the test lives in `uk.jtoye.core.product` so it can call `buildRenderModel` directly. Steps:
- Fixed compliant fixture: a Product (sku, title, ingredients "Wheat flour, **milk**, sugar", pricePennies, shelf_life_days=3, durability_type="USE_BY"; id via reflection like ProductServiceTest) + a Shop (fixed name + address). Fixed `LocalDate GEN = LocalDate.of(2026,7,5)` so "Use by:" is stable at "8 Jul 2026".
- Active test `renderModelMatchesCommittedGolden()`: call `buildRenderModel(fixture, shop, GEN)`, read the committed `core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json`, deserialize to `LabelRenderModel` via a shared ObjectMapper, and assert `assertThat(actual).usingRecursiveComparison().isEqualTo(expected)`. Use the same `locateGolden()` CWD-fallback trick as the finance test.
- `@Test @Disabled("one-shot bootstrap — re-enable manually to regenerate, then re-disable") void captureGoldenOnce()`: serialize buildRenderModel(fixture, shop, GEN) to the golden path via `objectMapper().writerWithDefaultPrettyPrinter().writeValue(...)`.
- COMMIT the generated golden artifact `ppds-label-compliant.golden.json` (run the capture once locally to produce it, then re-disable — the active test must be green against the committed file in CI).
- Add a class/method Javadoc comment CITING the specific FSA PPDS guidance the format was reviewed against: (1) allergens emphasised within the ingredients list (e.g. bold/CAPS), (2) name of the food, (3) durability (use-by / best-before) date, (4) food business name + address. This is the human "reviewed against FSA PPDS guidance" record AC3 requires.

Create `docs/ppds-label-markup.md` — vendor-facing doc: the `**allergen**` inline markup convention, an example, the fail-soft rules, and that a compliant label REQUIRES ingredients (with allergens marked), a shelf life + durability type, and a shop with an address (else the label 422s). Note the frontend "mark allergens" editor is a documented fast-follow.

Sync docs: run `scripts/docs-freshness.sh --write` from repo root to regenerate `docs/metrics.json` (bumps schema_version 40->41 automatically and recomputes java_test_methods + total from the new/rewritten tests, INCLUDING the golden + parser tests). Then surgically sync the two drifting figures in `CLAUDE.md` prose to match the regenerated metrics.json: the "Current schema version: V..." line -> V41 (with a one-line note that V41 adds allergen_spans/shelf_life_days/durability_type), and the test-count figure in the Testing constraint -> the regenerated total_logical_invocations / java @Test count. Keep the CLAUDE.md edit minimal and factual.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests "uk.jtoye.core.product.IngredientMarkupParserTest" --tests "uk.jtoye.core.product.ProductLabelServiceTest" --tests "uk.jtoye.core.product.ProductLabelGoldenFileTest" -q 2>&1 | tail -30 && cd .. && scripts/docs-freshness.sh && echo "TESTS_AND_DOCS_OK"</automated>
  </verify>
  <done>Expanded ProductLabelServiceTest (mock-wired w/ TenantContext, PDF-text negative asserts, fail-loud incl. non-null-but-empty shop -> 422) is green; ProductLabelGoldenFileTest asserts buildRenderModel output matches the COMMITTED golden JSON reviewed against FSA PPDS guidance (@Disabled capture-bootstrap present, active test green); docs/ppds-label-markup.md exists; docs-freshness passes in check mode (metrics.json schema_version=41, counts updated); CLAUDE.md schema+count prose synced.</done>
</task>

<task type="auto">
  <name>Task 5: Full integration suite + live-stack proof (no commit)</name>
  <files>(verification only — no source changes)</files>
  <action>
Run the FULL Java suite INCLUDING integration tests (a prior FINANCE task shipped a controller integration test that `:core-java:test` missed and CI caught — do not repeat that): `cd core-java && ./gradlew test integrationTest`. Both must be green. Also grep-confirm NO test anywhere still asserts the removed behaviour: `grep -rn "No allergens declared\|CONTAINS" core-java/src/test/` should return only intentional NEGATIVE assertions (i.e. `assertThat(...).doesNotContain("CONTAINS")` style), never a positive expectation.

Then LIVE-STACK PROOF (rebuild core-java only per the project's rebuild-after-code-change rule; do NOT break the running stack, do NOT commit):
1. Rebuild + restart core-java only (its docker image), let Flyway apply V41. Confirm V41 applied: query flyway_schema_history for version 41 = success.
2. Choose/seed a COMPLIANT product for a tenant that owns a shop WITH an address: set ingredients with `**...**` markup, shelf_life_days, durability_type='USE_BY', shopId -> the tenant's shop. Seed via the authenticated API (POST/PUT /api/v1/products) or direct SQL under the tenant GUC, whichever is fastest on the live DB.
3. `GET /api/v1/products/{id}/label` (authenticated) -> save the PDF; extract text with the host `pdftotext` (confirmed at /usr/bin/pdftotext). CONFIRM in the extracted text: the marked allergen word(s) appear INLINE within the ingredients region (not in a separate block), a 'Use by:' (or 'Best before:') line with a real date is present, and the business name + address are present. CONFIRM ABSENT: 'CONTAINS:' and 'No allergens declared'. (Inline bold is a font attribute pdftotext cannot show; the render-model unit test in Task 4 + the AC3 golden guard the emphasis flag — here confirm the textual content + ordering + absences.)
4. NEGATIVE live check: `GET /api/v1/products/{id}/label` for a product whose shop has NO address (or missing shelf_life, or a non-null shopId pointing at no tenant-owned shop) -> confirm HTTP 422 with a clear error naming the missing field, NOT a bad/blank PDF and NOT a 500.
Record the pdftotext output + both HTTP statuses in the SUMMARY. This task makes NO code change and NO commit (Tasks 1-4 each commit atomically; see output).
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test integrationTest -q 2>&1 | tail -20 && echo "SUITE_GREEN"; cd .. && grep -rn "No allergens declared" core-java/src/main/ && echo "LEAK" || echo "NO_FALLBACK_IN_MAIN"</automated>
    <human-check>Live PDF text (via pdftotext) shows allergens inline in the ingredients region + a Use by/Best before date + business name & address, and shows NO 'CONTAINS:' / 'No allergens declared'; the missing-address (or empty/cross-tenant shop) product returns HTTP 422 with a clear message (not 500). Outputs recorded in SUMMARY.</human-check>
  </verify>
  <done>`./gradlew test integrationTest` fully green; no main-source fallback remains; live label PDF for a compliant product is FSA-compliant (inline allergens + durability + business identity, no CONTAINS/no-fallback); missing-PPDS-data / empty-shop product returns a clear 422 (never 500); V41 applied on the live DB; findings recorded. No commit in this task.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client -> ProductController | Authenticated vendor requests (JWT + tenant); untrusted `ingredients_text` markup + a client-supplied `shopId` cross here |
| ProductLabelService -> shops (RLS) | Business identity read; `shops_public_read` RLS permits `published=true` cross-tenant |
| Product entity -> products/products_aud | New persisted columns cross the app->DB boundary |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-ovt-01 | Information Disclosure | ProductLabelService business-identity read | mitigate | Resolve shop via `findByIdAndTenantId(shopId, TenantContext.get())`, NOT plain `findById` — the V16 `shops_public_read` policy would otherwise leak another tenant's PUBLISHED shop name/address onto the label. A non-null `shopId` whose tenant-scoped lookup returns EMPTY (orphaned/cross-tenant; shop_id is ON DELETE SET NULL and not tenant-validated on write) is handled with `.orElse(null)` -> treated as missing business identity -> 422, never a leaked shop and never an unhandled 500. |
| T-ovt-02 | Tampering / Injection | IngredientMarkupParser over untrusted `ingredients_text` | mitigate | Parser is fail-soft and NEVER throws; delimiters are the only control chars; OpenPDF `Chunk` renders text as literal data (no PDF-structure injection). Naked/unmatched `**` treated as literal. |
| T-ovt-03 | Spoofing (compliance) | Non-compliant fallback label | mitigate | Remove the CONTAINS block + "No allergens declared" fallback; `generateLabel` throws `IncompleteLabelDataException` (422) rather than emitting a misleading "compliant-looking" PDF when PPDS data is missing. The AC3 golden-file test pins the compliant format so a regression fails the build. |
| T-ovt-04 | Denial of Service | Parser on pathological input (many/adjacent `**`) | accept | Single left-to-right O(n) scan over a `@Size(max=2000)`-bounded ingredients string; no backtracking/regex catastrophe. Bounded input, linear cost. |
| T-ovt-05 | Elevation / IDOR | GET /{id}/label | accept | Existing RLS on `products.findById` scopes to tenant (404 for others); unchanged by this plan. |
| T-ovt-SC | Tampering | npm/pip/cargo installs | n/a | No new dependencies added (OpenPDF 2.0.3 already present; PdfReader/PdfTextExtractor bundled). No package-legitimacy gate required. |
</threat_model>

<verification>
- `cd core-java && ./gradlew test integrationTest` fully green (Task 5 runs BOTH — integrationTest is mandatory, not optional). Includes IngredientMarkupParserTest, ProductLabelServiceTest, and the AC3 ProductLabelGoldenFileTest.
- `scripts/docs-freshness.sh` passes in check mode (schema_version=41, java counts + total updated).
- No main source contains "No allergens declared" or a "CONTAINS:" allergen block.
- A non-null but tenant-empty shopId lookup returns 422, never a 500.
- Live: compliant product label PDF (pdftotext) shows inline allergens + durability date + business name/address, and shows neither "CONTAINS:" nor "No allergens declared"; a product missing PPDS data returns HTTP 422 with a clear message.
</verification>

<success_criteria>
- V41 applies on fresh AND seeded schema (additive nullable columns + products_aud mirror; safe on zero rows); verified applied on the live DB.
- Allergens render INLINE (bold) within the ingredients list; no standalone CONTAINS block; no "No allergens declared" fallback anywhere in main source.
- Label prints a computed 'Use by:'/'Best before:' durability date and the business name + address.
- generateLabel throws IncompleteLabelDataException -> HTTP 422 (naming missing fields) when shop_id/address/shelf_life_days/durability_type is missing OR when a non-null shopId resolves to no tenant-owned shop (empty Optional), instead of emitting a non-compliant PDF or an unhandled 500.
- IngredientMarkupParser is a pure, fail-soft single source of truth; parsed spans are persisted to `allergen_spans` on save; the renderer re-parses at render time (authoritative).
- AC3: a committed golden-file test (ProductLabelGoldenFileTest) serializes buildRenderModel output for a FIXED compliant fixture at a FIXED generationDate and asserts recursive equality with a golden JSON reviewed (in-test citation) against FSA PPDS guidance; an @Disabled capture-bootstrap regenerates it.
- IngredientMarkupParserTest + rewritten/expanded ProductLabelServiceTest (render-model + PDF-text negative asserts + fail-loud) + ProductLabelGoldenFileTest pass; full `test integrationTest` green.
- docs/metrics.json regenerated (schema_version=41); CLAUDE.md schema/count prose synced; docs/ppds-label-markup.md documents the vendor markup convention; frontend "mark allergens" editor explicitly scoped to a fast-follow.
</success_criteria>

<commits>
Atomic commits, conventional prefix, NO Co-Authored-By trailers (per user Git policy — work on a feature branch, open a PR):
- Task 1: `feat(product): V41 shelf-life/durability/allergen-spans schema + thread fields (#82)`
- Task 2: `feat(product): fail-soft ingredient markup parser + persist allergen spans on save (#82)`
- Task 3: `feat(product): PPDS-compliant inline-emphasis label + fail-loud on missing/empty-shop data (#82)`
- Task 4: `test(product): parser + render-model + PDF-text + AC3 golden-file compliance tests; docs/metrics sync (#82)`
- Task 5: live-stack proof only — NO commit.
</commits>

<output>
Create `.planning/quick/260708-ovt-issue-82-p0-6-ppds-natasha-s-law-label-i/260708-ovt-SUMMARY.md` when done — include the live pdftotext output, both HTTP statuses (compliant 200 + missing-data 422), and confirmation that V41 applied on the live DB.
</output>
</content>
</invoke>
