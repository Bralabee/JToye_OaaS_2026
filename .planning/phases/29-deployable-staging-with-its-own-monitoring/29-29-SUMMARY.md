---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 29
subsystem: api
tags: [storefront, media, rls, nosuperuser, playwright, staging, live, d-34b, d-03a]
status: complete

requires:
  - phase: 29-14
    provides: "scripts/deploy.sh --context/--image-tag/DEPLOYED_SHA, check-deploy-digest-parity.sh, the staging vendor and its Pelau product, and the defect itself (WINDOWS 50)"
  - phase: 29-22
    provides: "the live Mailpit STARTTLS + cluster-CA truststore manifests this deploy kept, and the efab17d7 running tag IMAGE_SHA descends from"
provides:
  - "ProductMediaRepository.findPublicPrimaryObjectKeys(Collection<UUID>) with the nested PrimaryObjectKey projection: findPrimaryActiveObjectKey's predicate batched, plus a.flagged = false"
  - "uk.jtoye.core.media.PublicProductImageResolver.primaryUrlsFor: MANDATORY transaction, tenant pin required, per-row quarantine refusal (WARN event=public_media_key_refused, product id only)"
  - "PublicStorefrontService: menu and featured config serve the ACTIVE, unflagged pipeline derivative as imageUrl and imageUrls[0], flat columns as the fallback"
  - "PublicStorefrontMediaImageIntegrationTest: 14 cases as NOSUPERUSER NOBYPASSRLS, six break arms"
  - "frontend/e2e/staging-storefront-image.spec.ts: an anonymous live proof, excluded from every default run"
  - "Staging at DEPLOYED_SHA 6e95ee6c with the photo rendering anonymously on mobile and desktop"
affects: [29-26, 29-27, 29-18, 29-20]

actuals:
  tokens: 59204
  tasks: 4
  commits: 3
plan_head_before: 25727576d4324c51d3c1a562f66c5e2c562a5eb4
plan_head_after: 2288229e043d0832d1db4c49ab1582454495ee8d

tech-stack:
  added: []
  patterns:
    - "The public read reuses the vendor's resolution rule, batched, with one extra visibility predicate. It does not get a second rule."
    - "A storefront media read runs inside the slug tenant's TenantContext window under the existing FORCE RLS policy. No NULL-tenant read policy is added."
    - "A live deploy from a checkout whose manifests differ from the image commit is gated by a recorded kubectl diff in which only image tags may change, and that gate is shown failing on the image commit's own tree"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/media/PublicProductImageResolver.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontMediaImageIntegrationTest.java
    - frontend/e2e/staging-storefront-image.spec.ts
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-29-red-evidence.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-29-storefront-image.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/WINDOWS.md
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md

key-decisions:
  - "Flagged ACTIVE primaries stay off the public storefront until the vendor's Keep clears the flag (one predicate, reversible). Nothing was public before, so no public good is displaced."
  - "No migration and no RLS policy: case 12 measured as the runtime role. Unpinned reads see 0 media_asset and 0 product_media rows. Pinned to A they see 8 and 9 rows, and B's asset is invisible. The Flyway head stays V88."
  - "The deploy kept HEAD's manifests rather than IMAGE_SHA's tree. HEAD's manifests are 29-22's live Mailpit and truststore manifests. IMAGE_SHA's tree would have reverted them, which the diff gate showed with 76 non-image lines."
  - "Pelau's primary changed under the plan: 29-22 replaced asset 1475a89c with 14add455 through the vendor pipeline. The after-state is proven against the current primary. The literal 1475a89c commands are kept as recorded fail arms."

patterns-established:
  - "When a live data constant goes stale, the literal command is run and recorded red. A named substitute is then run with only the stale input changed. The substitute is never reported as the literal."

requirements-completed: [DPLY-01]

coverage:
  - id: D1
    description: "Anonymous menu and featured config serve the ACTIVE, unflagged pipeline derivative by the vendor's own rule; legacy and seeded products unchanged"
    requirement: DPLY-01
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontMediaImageIntegrationTest.java (14 cases, NOSUPERUSER NOBYPASSRLS; RED on cases 1,2,3,10,14 at 9212fb7b; closing run tests=14 failures=0 errors=0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "No non-public image can leak (PENDING/FAILED/flagged/quarantine-key/foreign-tenant/unpublished); one query per menu"
    requirement: DPLY-01
    verification:
      - kind: integration
        ref: "PublicStorefrontMediaImageIntegrationTest cases 5-9, 12, 13 with break arms BA-1..BA-6 each red on its named cases and restored by hash (evidence/29-29-storefront-image.txt 'break-arm summary'); case 11 has no break arm, see Decisions"
        status: pass
    human_judgment: false
  - id: D3
    description: "Staging runs the fix by identity: DEPLOYED_SHA = IMAGE_SHA 6e95ee6c, three Deployments on the tag, digest parity, in-jar class 0 -> 1"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "Task 4 first verify rc=0 (listed=3 off-tag=0 parity rc=0); parity --plant-stale rc=1; unzip -l /app/app.jar resolver=1 control=1 (Task 2: resolver=0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The vendor's pipeline photo renders anonymously on https://app-staging.olajay.co.uk/shop/staging-test-kitchen (mobile and desktop) with a matching Product JSON-LD image"
    requirement: DPLY-01
    verification:
      - kind: e2e
        ref: "frontend/e2e/staging-storefront-image.spec.ts with STAGING_MEDIA_ASSET_ID=14add455-db95-4fa8-82dc-b952d60792a1 (substitute: 29-22 replaced 1475a89c): 2 passed, naturalWidth=640 x2, JSONLD_IMAGE = src x2; red at a784e7e2 (NO_MEDIA_IMG x2) and red with the stale 1475a89c id (MEDIA_SRC_MISMATCH x2)"
        status: pass
    human_judgment: false

duration: 2h37m wall (lane B 11:19-11:56Z; CI/handoff wait; lane A 13:47-13:56Z)
completed: 2026-10-19
---

# Phase 29 Plan 29: Storefront Serves Vendor-Uploaded Photos Summary

**The anonymous storefront menu, its featured config and the Product JSON-LD now carry a vendor's ACTIVE, unflagged pipeline photo. A batched form of the vendor's own D-03a rule resolves it under the slug tenant's existing FORCE RLS pin, so no migration or policy was needed. The test runs as a NOSUPERUSER role with six break arms. Staging is deployed at 6e95ee6c, and Pelau's photo renders at naturalWidth 640 on mobile and desktop.**

## Performance

- **Duration:** about 2h37m wall clock. Lane B ran Tasks 1-2 from 11:19 to 11:56Z. The orchestrator merged and waited for CI images (Task 3). Lane A ran Tasks 3-4 from 13:47 to 13:56Z.
- **Started:** 2026-10-19T11:19:26Z (PLAN_BASE 93a63f69)
- **Completed:** 2026-10-19T13:56Z
- **Tasks:** 4 of 4
- **Files modified:** 15, counting the code, test, spec, docs counts, ledger and evidence

## Accomplishments

- `PublicStorefrontService.getShopProducts` and `getShopConfig` call `PublicProductImageResolver.primaryUrlsFor` once per request. The call sits inside the existing try and runs before the finally that clears TenantContext.
  - `imageUrl` is the resolved derivative, with `products.image_url` as the fallback.
  - `imageUrls` is that value followed by the unchanged flat gallery.
- Nothing non-public reaches an anonymous response. PENDING, FAILED, flagged, quarantine-keyed, foreign-tenant and unpublished-shop assets are each absent. Every negative except case 11 has a break arm that turned it red.
- Measured as the runtime role (case 12):

  | Read | media_asset | product_media | Other tenant's asset |
  |---|---|---|---|
  | Unpinned | 0 | 0 | — |
  | Pinned to tenant A | 8 | 9 | B's invisible |
  | Pinned to tenant B | 1 | 1 | A's invisible |

  So the public read needs no NULL-tenant policy.
- One query per menu (case 13): the prepared-statement delta is 3 for a 10-product menu with 8 media-bearing products, and 3 for a 1-product menu. A per-product loop (BA-5) made it 12.
- Live on staging at 6e95ee6c:
  - The anonymous menu returns Pelau's derivative as `imageUrl` and `imageUrls[0]`. It holds 0 quarantine-shaped strings and 2 media strings.
  - The spec passes in both projects (naturalWidth 640, JSON-LD image equal to the src).
  - The menu title list is unchanged.
  - core-java health is 200 UP with Mailpit STARTTLS untouched.

## Task Commits

1. **Task 1: NOSUPERUSER IT RED, the batched vendor rule, six break arms (lane B).** RED `9212fb7b` (test), GREEN `40ceffbc` (feat), evidence `a60898d6` (docs).
2. **Task 2: The committed anonymous staging spec and the before-state (lane B).** Spec `81073fdf` (test), before-state `c2fe6120` (docs). Merged into phase-29-deployable-staging by `75045071`.
3. **Task 3: Checkpoint (orchestrator handoff), verified on resume (lane A).** `ab7cf439` (docs). IMAGE_SHA=6e95ee6c44e357e3ff88964ed9674bf066fd7b61 was built by CI run 39248332647. It contains GREEN 40ceffbc, efab17d7 and a784e7e2. All three GHCR images exist. Every check's fail direction is recorded.
4. **Task 4: Live deploy and after-state (lane A).** Deploy and proofs `fcc1fb09` (docs). WINDOWS 50 and the deferred item closed in `2288229e` (docs).

## Before / After (live, anonymous)

| Measure | Before (a784e7e2, Task 2) | After (6e95ee6c, Task 4) |
|---|---|---|
| In-jar `PublicProductImageResolver.class` | 0 | 1 |
| Menu: Pelau `imageUrl` / `imageUrls` | `null` / `[]` | the 14add455 derivative / `[that URL]` |
| Spec (mobile, desktop) | failed with NO_MEDIA_IMG in both | 2 passed, naturalWidth=640 in both |
| Mobile CWV (budgets: LCP 8000 ms, CLS 0.1) | LCP 2500 ms, CLS 0.0000 | LCP 2612 ms, CLS 0.0000. The literal run measured 4004 ms; both are under budget. |
| Desktop CWV | LCP 2436 ms, CLS 0.0006 | LCP 1760 ms, CLS 0.0006 |
| Menu titles | `Pelau` | `Pelau` (equal) |

## Decisions Made

- Every choice in the plan's design held under measurement. The decisions are listed in the frontmatter.
- **Case 11 has no break arm.** It guards the pre-existing SEC-01 slug gate: `resolvePublicShopForSlug` returns 404 before any media read. That gate is not this plan's code, so its fail direction was not constructed here. Case 11 is not counted as falsified.
- **29-14's unmet truth is now met.** In 29-14-SUMMARY, coverage D5's second verification ("storefront renders the uploaded image with naturalWidth > 0", human_judgment) was the unmet storefront half. It is met by D4 above. 29-14's SUMMARY is not edited.

## Deviations from Plan

### Precondition and data substitutions (recorded, not silent)

**1. [Rule 3 - Blocking] Task 4's precondition clause "k8s/ equals IMAGE_SHA's" was false. The deploy used HEAD's manifests.**
- **Found during:** Task 3 resume.
- **Issue:** `git diff --quiet IMAGE_SHA -- k8s scripts/deploy.sh scripts/lib` gave rc=1. The difference is 29-22's 18 committed files: Mailpit, the cluster-CA truststore and the mail UI. 29-22 had already applied them LIVE. The same diff against 29-22's head 2b021754 gave rc=0. Deploying IMAGE_SHA's tree would have reverted live STARTTLS mail.
- **Fix:** Per the orchestrator's dispatch, the deploy ran from HEAD with `--image-tag IMAGE_SHA`. The app-source diff between IMAGE_SHA and HEAD is 0 lines.
  - The recorded kubectl diff was the gate. It showed only the three image tags plus generation, with 0 other changed lines.
  - The gate's fail direction: the IMAGE_SHA-tree render diffed against live showed 76 non-image lines, including the removal of the truststore initContainer and JAVA_TOOL_OPTIONS.
  - Post-apply diff: rc=0.
- **Committed in:** ab7cf439, fcc1fb09.

**2. [Rule 1 - Stale constant] Pelau's primary is no longer 1475a89c.**
- **Found during:** Task 4 step 5.
- **Issue:** At 13:18:54Z, 29-22's D-30 arm uploaded a new Pelau image as the staging vendor, asset 14add455 (ACTIVE, unflagged; evidence/29-22-mail-tls.txt:141-142). The 1475a89c derivative now returns 404.
  - The plan's second and third verifies hard-code 1475a89c, so they can never pass again. Both were run verbatim and recorded red. The API verify returned the 14add455 URL, rc=1. The spec failed with MEDIA_SRC_MISMATCH in both projects. That spec run is itself a useful fail arm: it proves the src check discriminates.
- **Fix:** The same two commands were re-run with only the asset id changed to 14add455, named as substitutes: API rc=0 and spec 2 passed.
  - The red on the pre-fix runtime (NO_MEDIA_IMG) does not depend on the asset id, so it remains a valid fail direction.
  - The menu serves the vendor's current primary, which is exactly the D-03a rule. IT case 14 is the vendor-rule oracle.
- **Committed in:** fcc1fb09.

**3. Running tag changed between Task 2 and Task 3.**
- 29-22 deployed efab17d7 after Task 2's a784e7e2 read.
- Both commits are ancestors of IMAGE_SHA (rc=0).
- efab17d7 was used as RUNNING for ancestry and continuity.

**Total deviations:** 3, none of which changed code. **Impact:** none on the fix. The deploy kept 29-22's live state rather than reverting it.

## Issues Encountered

- apply printed "configured" for `configmap/keycloak-theme-*` and the three PDBs. The pre-apply diff showed no hunk for any of them, and the post-apply diff is empty. The same four lines appeared in 29-14's deploys, so no live value changed beyond the image tags.
- Mail: there was no gap between 29-16 and 29-22. IMAGE_SHA runs on 29-22's Mailpit TLS and truststore:
  - truststore-builder reports 118+1 entries;
  - 29-22's aggregate-health verify reads 200 UP;
  - 0 mail, STARTTLS or PKIX failure lines in a 134-line core-java log (positive control: the pattern matches 9 lines in 29-22's evidence);
  - Mailpit unchanged, with MP_SMTP_REQUIRE_STARTTLS=true.

## Quality dimensions

- **SEO:** Product JSON-LD `image` is now populated for pipeline images, asserted live.
- **Web performance:** within budget; see the table above. One residual is recorded, not fixed: cards fetch the full derivative, not the 400 px thumbnail.
- **Agent-readiness:** N/A. No MCP tool reads the public menu, and the contract is unchanged.
- **Client-persisted identity lifecycle:** N/A.
- **Security:** T-29-114..120 mitigated as planned. No new surface.
- **Falsifiability:** every check has its fail direction recorded in `evidence/29-29-storefront-image.txt`.

## User Setup Required

None.

## Next Phase Readiness

- 29-26 and 29-27 (persona testing) can now judge a storefront that shows vendor photos.
- Staging runs 6e95ee6c. Undo with `deploy.sh --image-tag efab17d79483b01f4397c9f82f34a36d1a2b4c11`.
- Executors do not push. The three lane-A commits are local on phase-29-deployable-staging.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-19*
