---
phase: quick-260713-kds
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [REGRESSION-19-09-DEMO-IMAGES]
files_modified:
  - core-java/src/main/resources/dev/demo-images/manifest.json
  - core-java/src/main/resources/dev/demo-images/*.jpg
  - core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
  - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
  - core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java
  - docs/CREDITS-demo-images.md
  - docs/metrics.json
  - CLAUDE.md

must_haves:
  truths:
    - "All 21 curated demo products across the 3 published demo shops render a real dish photo (<img> naturalWidth>0) in the storefront, including Peri Peri Chicken and Suya Platter (previously legacy MinIO URLs)."
    - "The dev seeder idempotently uploads each bundled image to MinIO at a deterministic seed key and stamps a browser-reachable seed URL on the matching curated product."
    - "A genuine vendor product-image upload is NEVER clobbered by the seeder on restart (seeder only owns null/blank, prior-seed, or foreign-legacy image slots)."
    - "CC-BY/BY-SA attribution duty is discharged via docs/CREDITS-demo-images.md generated from the manifest."
    - "docs/metrics.json and CLAUDE.md counts are honest — docs-freshness.sh (check mode) passes."
    - "prod/test behavior is unchanged: dev-profile-only bean, no Flyway migration, schema stays V50."
  artifacts:
    - path: "core-java/src/main/resources/dev/demo-images/manifest.json"
      provides: "Classpath manifest (21 entries: dish, shop, filename, author, license, license_url, source_url)"
      contains: "\"filename\""
    - path: "core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java"
      provides: "Jackson manifest loader + explicit shop-name->slug normalization"
      min_lines: 40
    - path: "core-java/src/main/java/uk/jtoye/core/storage/StorageService.java"
      provides: "putSeedImage(tenantId, filename, bytes, contentType) with deterministic seed key + existence-check idempotency"
      contains: "products/seed"
    - path: "core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java"
      provides: "Additive image-seeding step + seeder-owns overwrite policy (reverses the 19-09 no-image_url design note)"
      contains: "products/seed"
    - path: "docs/CREDITS-demo-images.md"
      provides: "Attribution table (dish, author, license+link, source link)"
      contains: "| Dish |"
    - path: "core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java"
      provides: "Unit test: parse manifest, 21 entries, every entry maps to a curated product, every image on classpath"
      min_lines: 40
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java"
      to: "core-java/src/main/java/uk/jtoye/core/storage/StorageService.java"
      via: "putSeedImage (deterministic key, reuses S3Client + StorageProperties.publicUrl)"
      pattern: "putSeedImage"
    - from: "core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java"
      to: "uk.jtoye.core.product.Product.setImageUrl"
      via: "seeder-owns policy"
      pattern: "setImageUrl"
    - from: "manifest (shop,dish)"
      to: "curated product (slug,title)"
      via: "explicit shop-name->slug map, period/apostrophe tolerant"
      pattern: "peckham-jollof-co"
---

<objective>
Restore product photography to the dev demo catalog. Phase 19-09 deliberately shipped
the three curated demo storefronts with NO product `image_url` (SafeImage branded
fallback tile only) — a regression-by-omission that leaves the demo catalog looking
empty. This plan reverses that single design decision by bundling 21 license-verified
(CC0/CC-BY/CC-BY-SA, zero NC/ND) Wikimedia dish photos on the classpath and having the
dev-profile `DemoDataSeeder` idempotently upload each to MinIO via the existing
`StorageService` and stamp the matching curated product's `imageUrl`.

Purpose: the dev demo catalog must again render real dish photos so storefront
UX/QA reflects production reality — WITHOUT weakening any of the seeder's existing,
well-designed invariants, and WITHOUT touching prod/test behavior.

Output: 21 classpath images + manifest, a `DemoImageManifest` loader, a
`StorageService.putSeedImage` seam, an additive seeder step + seeder-owns overwrite
policy, `docs/CREDITS-demo-images.md`, a focused unit test, reconciled docs counts,
and live browser proof (naturalWidth>0 per shop).
</objective>

<incremental_betterment>
This plan reworks a user-visible surface (the seeder that owns the demo catalog).
Per the Incremental Betterment Doctrine, it must BETTER what is already good and
account for every displaced good. `DemoDataSeeder` currently does the following well
— ALL of it MUST be preserved intact; the image step is purely ADDITIVE:

1. Idempotent upsert-by-SKU / upsert-by-slug (create-or-update, never duplicates).
2. `TenantContext.set(...)` OUTSIDE a `TransactionTemplate` so `TenantSetLocalAspect`
   pins the RLS GUC on every repo op — avoids the self-invocation proxy NULL-tenant trap.
3. `quarantineNonCurated` — moves orphan/legacy/NULL-shop products into the hidden
   `unsorted-legacy-items` archive shop and sets `available=false` (defence-in-depth CR-01).
4. `unpublishNonCurated` with the WR-10 exception — never un-publishes a shop the
   Phase-18 onboarding state machine currently holds LIVE (SM is the sole authorised
   writer of `Shop.published`).
5. The documented dev-only bootstrap exception that force-publishes the 3 curated shops.
6. Unconditional enrichment (allergen mask/spans, durability) that REPAIRS pre-existing
   dev rows in place on restart rather than skipping them.
7. The tenant-B probe fixture (#203 cross-tenant RLS proof) in its own transaction.
8. `@Profile("dev")` gating + deliberately-not-a-Flyway-migration.

The ONLY good being displaced is item "No product photography (#15)" (the `image_url`
stays null design note on line ~435 and the class Javadoc paragraph). It is displaced
DELIBERATELY and replaced with license-verified, attributed imagery plus a seeder-owns
policy that is strictly SAFER than the old behavior (it can never clobber a real vendor
upload). The Javadoc MUST be updated to reflect the reversal.
</incremental_betterment>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
@core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
@core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java

<interfaces>
<!-- Contracts the executor needs. Do NOT re-explore the codebase for these. -->

Product entity (uk.jtoye.core.product.Product):
  - String getSku() / setSku(String)
  - String getImageUrl() / setImageUrl(String)      // @Column(name="image_url")
ProductRepository:
  - Optional<Product> findBySku(String sku)          // already used by the seeder

StorageService (uk.jtoye.core.storage.StorageService):
  - fields: private final S3Client s3Client; private final StorageProperties properties;
  - existing key shape for VENDOR uploads (StorageService.upload):
        <tenantId>/<pathPrefix>/<entityId>/<random-UUID><ext>   (pathPrefix == "products")
    => vendor product URLs contain "/products/<uuid>/" and a random-UUID filename.
  - existing public URL construction: properties.getS3().getPublicUrl() + "/" + key
  - private String detectContentType(byte[])         // reusable inside the class
  - PutObjectRequest / RequestBody.fromBytes already imported (AWS SDK v2, no new dep)

StorageProperties.S3Properties (config prefix "storage"):
  - getBucket()      -> dev default "jtoye-images"
  - getPublicUrl()   -> dev default "http://localhost:9000/jtoye-images"  (browser-reachable)
  (Container env in docker-compose.full-stack.yml: S3_ENDPOINT=http://minio:9000 for the
   S3 client, S3_PUBLIC_URL=http://localhost:9000/jtoye-images for the stamped URL.)

DemoDataSeeder curated shops (name -> slug), tenant 00000000-0000-0000-0000-000000000001:
  - "Mama Ade's Kitchen"    -> mama-ades-kitchen
  - "Peckham Jollof Co."    -> peckham-jollof-co
  - "Brixton Village Grill" -> brixton-village-grill
Each shop has 7 curated MenuItems; MenuItem.title() == the manifest "dish".
</interfaces>

<landmines>
<!-- Verified this session. Ignore at your peril. -->
- SHOP-NAME MISMATCH: manifest "shop" for the 7 Peckham dishes is "Peckham Jollof Co"
  (NO trailing period); the seeder shop name is "Peckham Jollof Co." (WITH period).
  A naive exact string match FAILS for 7/21 products.
- SLUGIFY IS NOT SAFE: a generic slugify of "Mama Ade's Kitchen" yields
  "mama-ade-s-kitchen" (apostrophe -> dash), NOT the real slug "mama-ades-kitchen".
  => Use an EXPLICIT shop-name -> slug map (3 entries), matched case-insensitively and
  tolerant of a trailing '.'. Do NOT hand-roll a slugify for the shop match.
- Manifest "shop" values in the file: "Brixton Village Grill", "Mama Ade's Kitchen",
  "Peckham Jollof Co" (no period). Dish titles match the seeder MenuItem titles exactly
  (case aside): e.g. "Pounded Yam & Egusi", "Peri Peri Chicken", "Suya Platter".
- StorageService.upload*() take MultipartFile; there is NO MockMultipartFile in the MAIN
  runtime (spring-test is test-scope). Do NOT wrap classpath bytes in a MultipartFile —
  add a byte[]-based method instead (Task 1).
- Use ONLY the 21 top-level *.jpg + manifest.json from the scratchpad seed-images dir.
  IGNORE the work/ subdirectory entirely.
- CLAUDE.md count prose is ALREADY stale vs docs/metrics.json (prose total 1235 / Jest
  231-across-32; metrics.json total 1238 / Jest 234-across-33). docs-freshness.sh only
  gates metrics.json, NOT the prose — you must reconcile the prose by hand (Task 2).
</landmines>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Bundle images + seed them idempotently via StorageService (feat)</name>
  <files>
    core-java/src/main/resources/dev/demo-images/manifest.json,
    core-java/src/main/resources/dev/demo-images/*.jpg (21),
    core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java,
    core-java/src/main/java/uk/jtoye/core/storage/StorageService.java,
    core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java,
    docs/CREDITS-demo-images.md
  </files>
  <action>
    STEP A — Copy assets. `mkdir -p core-java/src/main/resources/dev/demo-images` then copy
    the 21 top-level `*.jpg` AND `manifest.json` (ONLY these; ignore `work/`) from
    `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/5581c3e3-1926-4520-90ab-11439487cfb7/scratchpad/seed-images/`
    into that resources dir. `git add` them so they are tracked.

    STEP B — Manifest loader. Create `DemoImageManifest.java` (package uk.jtoye.core.dev).
    Define an immutable record `ManifestEntry` mirroring the manifest fields (dish, shop,
    filename, author, license, licenseUrl, sourceUrl — map JSON snake_case via
    `@JsonProperty` or ObjectMapper naming strategy; ignore unknown fields). Provide a
    static loader that reads `dev/demo-images/manifest.json` from the classpath with a
    Jackson `ObjectMapper` (Jackson ships with spring-boot-starter-web — no new dependency)
    and returns `List<ManifestEntry>`. Provide `static String slugForShop(String manifestShop)`
    backed by an EXPLICIT map (see landmines): normalize the input by trim + strip one
    trailing '.' + lowercase, then map "brixton village grill"->brixton-village-grill,
    "mama ade's kitchen"->mama-ades-kitchen, "peckham jollof co"->peckham-jollof-co
    (accept the period variant too). Throw a clear IllegalStateException on an unmapped shop
    so drift fails loudly. Also expose the classpath base path constant `dev/demo-images/`
    so both seeder and test resolve image resources consistently.

    STEP C — Storage seam. Add to StorageService:
    `public String putSeedImage(UUID tenantId, String filename, byte[] bytes, String contentType)`.
    Build the DETERMINISTIC key `tenantId + "/products/seed/" + filename` (filename already
    ends in `.jpg`). Reuse the existing private `detectContentType(bytes)` as a sanity guard
    (reject if not an allowed image type). Idempotency: issue a HeadObject against
    (properties.getS3().getBucket(), key); if it exists, SKIP the put; otherwise putObject
    with the same bucket + `cacheControl("public, max-age=31536000, immutable")` used by the
    existing upload methods, wrapping bytes in `RequestBody.fromBytes(bytes)`. Return
    `properties.getS3().getPublicUrl() + "/" + key` (the SAME public-URL mechanism vendor
    uploads use — no hardcoded host). Import `HeadObjectRequest` + catch `NoSuchKeyException`
    from the AWS SDK (already on the classpath). Log one INFO per uploaded/skipped seed image.

    STEP D — Seeder image step (ADDITIVE — see <incremental_betterment>; touch NOTHING in
    quarantine/unpublish/tenant-B/upsert logic). Inject `StorageService` via the constructor.
    In `run()`/`seed()`, after products are upserted, add a step that, for each of the 21
    manifest entries: resolve the target shop slug via `DemoImageManifest.slugForShop`, find
    the curated MenuItem by (shop menu for that slug, title == entry.dish) to get its SKU,
    load the persisted Product by SKU (`findBySku`), load the classpath image bytes for
    `entry.filename`, call `storageService.putSeedImage(DEMO_TENANT, entry.filename, bytes,
    "image/jpeg")`, then apply the SEEDER-OWNS OVERWRITE POLICY before setting imageUrl:
      - current imageUrl is null/blank            -> set seed URL (fill the gap);
      - current contains "/products/seed/"         -> set seed URL (prior seed, idempotent re-affirm);
      - current does NOT start with the vendor product-upload prefix
        `<publicUrl>/<DEMO_TENANT>/products/`      -> set seed URL (foreign/legacy env-only URL
                                                       of unverifiable provenance — "ours to
                                                       overwrite"; covers Peri Peri Chicken +
                                                       Suya Platter);
      - current DOES start with `<publicUrl>/<DEMO_TENANT>/products/` AND lacks
        "/products/seed/"                          -> LEAVE UNTOUCHED (genuine vendor upload wins).
    Check "/products/seed/" BEFORE the vendor-prefix test so a prior seed URL is never
    misread as a vendor upload. Save the Product only when the policy set/changed the URL.
    Count seeded images for the run-summary log line.

    STEP E — Reverse the stale design note. Update the DemoDataSeeder class Javadoc paragraph
    "<strong>No product photography (#15)</strong> ... deliberately carry no image_url ..."
    and the inline `// No image_url ...` comment in `upsertProduct` (~line 435) to describe
    the reversal: curated products now carry seeded, license-verified imagery under
    `/products/seed/`, governed by the seeder-owns policy; SafeImage remains the fallback only
    when no seed image maps to a product.

    STEP F — Attribution doc. Generate `docs/CREDITS-demo-images.md` FROM the manifest: a short
    intro (dev-only demo imagery, license-verified, CC0/CC-BY/CC-BY-SA) + a Markdown table with
    columns: Dish | Shop | Author | License (linked to license_url) | Source (linked to source_url).
    One row per manifest entry (21 rows). This discharges the CC-BY / CC-BY-SA attribution duty.

    Commit as a single atomic commit: `feat(seed): restore license-verified demo catalog images`.
  </action>
  <verify>
    <automated>cd core-java && ls src/main/resources/dev/demo-images/*.jpg | wc -l | grep -qx 21 && test -f src/main/resources/dev/demo-images/manifest.json && ./gradlew :compileJava -q</automated>
    Also: `grep -c '| ' ../docs/CREDITS-demo-images.md` shows >=21 table rows; `grep -q 'products/seed' src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java` and same in StorageService.java.
  </verify>
  <done>
    21 jpgs + manifest.json on the classpath; DemoImageManifest + StorageService.putSeedImage +
    seeder image step compile; Javadoc/comment reversed; docs/CREDITS-demo-images.md has 21
    attribution rows. Quarantine/unpublish/tenant-B/upsert logic byte-for-byte unchanged except
    the additive image step + injected StorageService.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Unit test + docs-freshness reconciliation + doctrine (test + docs)</name>
  <files>
    core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java,
    docs/metrics.json,
    CLAUDE.md
  </files>
  <behavior>
    - Test: manifest parses from the classpath and contains EXACTLY 21 entries.
    - Test: every entry resolves via DemoImageManifest.slugForShop to one of the 3 curated
      slugs AND its dish matches a curated product title for that shop (i.e. maps to a real
      curated product constant — this is the guard that catches the Peckham-period /
      Mama-Ade's-apostrophe mismatch).
    - Test: every entry.filename exists as a classpath resource under dev/demo-images/.
    - (Optional) Test: every license is one of CC0 / CC BY* / CC BY-SA* (no NC/ND).
    No Spring context, no MinIO, no Testcontainers — pure classpath + POJO assertions.
  </behavior>
  <action>
    STEP A — Test. Create `DemoImageManifestTest.java` (core-java/src/test/...). To assert
    "every entry maps to a curated product", expose the curated (slug -> set of dish titles)
    mapping from the production code in a test-visible way: add a package-private static
    accessor on DemoDataSeeder (or DemoImageManifest) that returns the curated titles per
    slug, derived from the existing menu definitions (do NOT duplicate the 21 titles as a
    second literal list — derive from the single source so the test cannot silently rot).
    Implement the `<behavior>` cases. `git add` the new test file (docs-freshness counts
    tracked + untracked-not-ignored files, so add it before the --write in Step B).
    Commit: `test(seed): assert demo-image manifest maps to curated products`.

    STEP B — Reconcile docs counts (SAME commit as the doctrine below). Run
    `bash scripts/docs-freshness.sh --write` to regenerate docs/metrics.json from source
    (this picks up the new Java @Test methods + new test file). Then open the regenerated
    docs/metrics.json and update the single count sentence in CLAUDE.md (currently line ~15)
    so EVERY component and the total EXACTLY equal metrics.json:
      - `872 Java @Test methods across 143 files`   -> the new java_test_methods across java_test_files (files 143 -> 144);
      - `231 Jest it/test blocks across 32 files`    -> `234 Jest it/test blocks across 33 files` (pre-existing branch drift);
      - `1235 logical invocations`                    -> the new total_logical_invocations;
      - leave Go (77/9), Playwright (28/6), MCP (27/6) as-is (already correct).
    Take the exact numbers from `docs-freshness.sh --write` output — do NOT hand-calculate.

    STEP C — Doctrine. Add a short new section `## Incremental Betterment Doctrine` to CLAUDE.md
    stating: improvements must better what is already good; any plan reworking an existing
    user-visible surface MUST list the displaced goods and account for each; regression by
    omission is a defect even when every test is green.

    Commit docs together: `docs: reconcile metrics + add Incremental Betterment Doctrine`.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests 'uk.jtoye.core.dev.DemoImageManifestTest' -q && cd .. && bash scripts/docs-freshness.sh</automated>
    docs-freshness.sh (check mode) exits 0 (metrics.json == source). CLAUDE.md contains
    `## Incremental Betterment Doctrine` and its count sentence matches metrics.json.
  </verify>
  <done>
    DemoImageManifestTest green (21 entries, all map to curated products, all images on
    classpath). docs/metrics.json regenerated; CLAUDE.md count sentence matches metrics.json
    exactly and Incremental Betterment Doctrine section present. docs-freshness check passes.
  </done>
</task>

<task type="auto">
  <name>Task 3: Live rebuild + browser proof (naturalWidth>0 for all 21)</name>
  <files>(no source changes — live verification; evidence goes in the SUMMARY)</files>
  <action>
    Rebuild ONLY the core-java container and restart it:
      `docker compose -f docker-compose.full-stack.yml build core-java`
      `docker compose -f docker-compose.full-stack.yml up -d core-java`
    Wait until the core-java container is healthy (poll `docker compose -f
    docker-compose.full-stack.yml ps core-java` / its healthcheck), then confirm the seeder
    ran and seeded images: `docker compose -f docker-compose.full-stack.yml logs core-java
    | grep -iE 'DemoDataSeeder|products/seed|seed image'` — expect the image-seeding INFO lines.

    Determine the live storefront base URL (the frontend container is NOT rebuilt): try
    http://localhost:3000 first, fall back to http://localhost:3100 (MCP may hold 3000) —
    curl each `/` and use whichever serves the app.

    Browser-verify with playwright-core, borrowing the probe pattern from
    `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/5581c3e3-1926-4520-90ab-11439487cfb7/scratchpad/repro-images.js`.
    Run node with the MAIN repo's modules on NODE_PATH (absolute, works even from a worktree):
      `NODE_PATH=/home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend/node_modules node <probe.js>`
    The probe must visit each of the 3 demo shop pages —
      /shop/mama-ades-kitchen, /shop/peckham-jollof-co, /shop/brixton-village-grill —
    wait for images to settle, and for every product `<img>` read `naturalWidth`. Assert every
    curated product image on each page has naturalWidth>0 (7 per shop, 21 total), explicitly
    including Peri Peri Chicken and Suya Platter (the two replaced legacy-URL products). Capture
    the per-shop count of `<img>` with naturalWidth>0 and a screenshot per shop.

    Record per-shop counts (e.g. "mama-ades-kitchen: 7/7 naturalWidth>0") + total 21/21 as
    evidence in the SUMMARY. If any product renders naturalWidth==0, that is a defect — inspect
    (seed URL 404? next.config remotePatterns? MinIO anon-read? seeder-owns policy skipped the
    legacy URL?) and fix in Task 1 before declaring done.
  </action>
  <verify>
    <automated>Probe script prints a machine-checkable summary; assert total products with naturalWidth>0 == 21 across the 3 demo shop pages (grep the probe output for "21/21" or an equivalent explicit count).</automated>
    <human-check>Screenshots of the 3 demo shop pages show real dish photos on every card (not the SafeImage fallback).</human-check>
  </verify>
  <done>
    core-java rebuilt + healthy; seeder logs show image seeding; all 21 curated products across
    the 3 demo shop pages render <img> with naturalWidth>0 (incl. Peri Peri Chicken + Suya
    Platter); per-shop counts captured in the SUMMARY as evidence.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| classpath assets -> MinIO object store | Static, license-vetted, visually-verified JPGs bundled at build time. No runtime/user input crosses here. |
| seeder -> Product.imageUrl | The seeder writes imageUrl only for demo-tenant curated products, gated by the seeder-owns policy. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-KDS-01 | Tampering | seeder overwriting a real vendor upload | mitigate | Seeder-owns policy: never overwrite a URL under `<publicUrl>/<tenant>/products/` that lacks `/products/seed/` (vendor uploads win). |
| T-KDS-02 | Elevation/Scope | seeding running outside dev | mitigate | Bean stays `@Profile("dev")`; not a Flyway migration; test/prod boot never instantiates it. Schema stays V50. |
| T-KDS-03 | Information disclosure | attribution / licence duty | mitigate | docs/CREDITS-demo-images.md carries author + licence + source per image (CC-BY / BY-SA duty); manifest asserts zero NC/ND. |
| T-KDS-SC | Tampering | dependencies | accept | No new dependency added (Jackson + AWS SDK v2 already on the classpath); no package-manager install → no legitimacy gate needed. |
</threat_model>

<verification>
- `./gradlew :compileJava` and `./gradlew test --tests 'uk.jtoye.core.dev.DemoImageManifestTest'` pass.
- `bash scripts/docs-freshness.sh` (check mode) exits 0; CLAUDE.md count sentence matches docs/metrics.json.
- Live: core-java container healthy; seeder INFO logs show image seeding; playwright-core probe reports 21/21 curated product `<img>` with naturalWidth>0 across the 3 demo shop pages.
- No Flyway migration added (schema stays V50); DemoDataSeeder remains `@Profile("dev")`.
</verification>

<success_criteria>
- 21 license-verified dish photos bundled on the classpath + manifest; docs/CREDITS-demo-images.md attributes each.
- DemoDataSeeder idempotently uploads each image to MinIO via StorageService and stamps the matching curated product's imageUrl, without clobbering genuine vendor uploads.
- Peri Peri Chicken and Suya Platter legacy env-only URLs are replaced with seeder-owned URLs.
- Unit test green; docs-freshness green; CLAUDE.md counts honest + Incremental Betterment Doctrine added.
- Browser proof: all 21 curated products render naturalWidth>0 across the 3 demo shop pages.
- prod/test behavior unchanged; the seeder's existing invariants (idempotent upsert, RLS-GUC transaction, quarantine, WR-10 unpublish exception, tenant-B probe) preserved intact.
- Atomic commits on branch `feature/ux-mobile-nav-rsc-fixes` (do NOT create a new branch):
  `feat(seed): ...`, `test(seed): ...`, `docs: ...`.
</success_criteria>

<output>
Create `.planning/quick/260713-kds-restore-demo-catalog-images/260713-kds-SUMMARY.md` when done,
including per-shop naturalWidth>0 counts (e.g. mama-ades-kitchen 7/7, peckham-jollof-co 7/7,
brixton-village-grill 7/7 = 21/21) as browser evidence, the regenerated total_logical_invocations,
and the three commit SHAs.
</output>
