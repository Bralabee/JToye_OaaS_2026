---
phase: quick
plan: 261102-qfo
subsystem: frontend/landing
tags: [landing, imagery, design-run, D16, credits, web-perf]
status: complete
requires: []
provides:
  - "Seven metadata-free BeesLens WebP derivatives in frontend/public/marketing/landing/"
  - "credits.json kind discriminator (own | open-collection); OWN_PHOTOS + OWN_PHOTO_CREDIT"
  - "D16 in design/decisions.md; identity palette traced to tokens; run record 2026-11-02-landing-beeslens"
affects: [frontend/app/page.tsx, frontend/components/marketing/landing-photos.ts, kitchen cards via assignExamplePhotos]
tech-stack:
  added: []
  patterns: ["RIFF chunk walk asserts no EXIF/XMP in shipped photos", "disk == PHOTOS == credits.json bijection (orphan file fails)"]
key-files:
  created:
    - frontend/public/marketing/landing/beeslens-spaghetti-chicken-plantain.webp
    - frontend/public/marketing/landing/beeslens-jollof-rice-meal-box.webp
    - frontend/public/marketing/landing/beeslens-rice-beans-plantain-stew.webp
    - frontend/public/marketing/landing/beeslens-plantain-yam-meal-prep.webp
    - frontend/public/marketing/landing/beeslens-puff-puff.webp
    - frontend/public/marketing/landing/beeslens-catering-soup-buffet.webp
    - frontend/public/marketing/landing/beeslens-fruit-punch-dispenser.webp
    - design/runs/2026-11-02-landing-beeslens/direction.md
    - design/runs/2026-11-02-landing-beeslens/before-after.jpg
  modified:
    - frontend/public/marketing/landing/credits.json
    - frontend/components/marketing/landing-photos.ts
    - frontend/app/page.tsx
    - frontend/components/marketing/__tests__/landing-photos.test.ts
    - frontend/app/__tests__/landing.test.tsx
    - frontend/components/marketing/__tests__/shop-card.test.tsx
    - design/decisions.md
    - design/identity.md
    - design/brief.md
    - docs/CHANGELOG.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
  deleted:
    - frontend/public/marketing/landing/egusi-soup-with-pounded-yam.webp
    - frontend/public/marketing/landing/jollof-rice-with-vegetable.webp
    - frontend/public/marketing/landing/jollof-rice-and-dodo.webp
    - frontend/public/marketing/landing/puff-puff.webp
decisions:
  - "D16: the owner's own BeesLens photography leads; open-collection only for Grill, Caribbean, South Asian, Vegan (amends D10)"
  - "Jollof meal box alt says 'stewed meat', not the drafted 'stewed beef': the meat cannot be told to be beef from the photograph"
metrics:
  duration: "~70 min"
  completed: 2026-11-02
actuals:
  tokens: 27700
  tasks: 3
  commits: 6
plan_head_before: 03eb939a1ddf75832f6590aaa2016c3cc4e11454
plan_head_after: 501c95f57f0b11f3911f37a34bfce4fff903cf4a
---

# Phase quick Plan 261102-qfo: the landing leads with the owner's own BeesLens photographs Summary

Seven of the owner's BeesLens photographs, cropped where watermarked and stripped of all metadata, now carry the phone door strip, the desktop collage, the Jollof and Desserts chips and the kitchen cards (plus new catering and drinks/juice cuisines). The four replaced Wikimedia pictures are retired with their credit records, and D15's LCP wiring is unchanged.

**Status: complete.** No push and no PR were made, as the orchestrator instructed. The CHANGELOG PR number is still pending (see below).

## Commits

| # | SHA | Message |
|---|---|---|
| 0 | 367d3b1b | docs(quick-261102-qfo): plan |
| 1 RED | 067c94b3 | test(quick-261102-qfo): red tests for the owner's spaghetti photo in the door strip and the BeesLens credit line |
| 1 GREEN | 72e81d1b | feat(quick-261102-qfo): the owner's spaghetti photograph leads the phone's door strip, credited "Photography: BeesLens" |
| 2 RED | 11bad539 | test(quick-261102-qfo): red tests for the owner's six photographs in the collage, chips and kitchen cards |
| 2 GREEN | c6144c80 | feat(quick-261102-qfo): the owner's BeesLens photographs fill the collage, the Jollof and Desserts chips and the kitchen cards |
| 3 | 501c95f5 | docs(quick-261102-qfo): D16, the identity palette traced to its tokens, the BeesLens run record, CHANGELOG and test counts |

The SUMMARY/STATE commit follows these. No commit message carries an attribution line: `git log --format=%B 03eb939a..HEAD | grep -ciE 'co-authored|claude-session|generated with'` printed `0`.

## Precondition

`frontend/node_modules` was absent. `npm ci` returned rc 0 and installed no new package. `git diff --exit-code frontend/package-lock.json` returned rc 0.

## Gate table (before vs after, rc each)

| Gate | Fail direction (real output) | Pass direction (real output) |
|---|---|---|
| Jest, Task 1 (`npx jest components/marketing app/__tests__/landing.test.tsx`) | rc 1, `Tests: 8 failed, 155 passed, 163 total`. Includes `Expected: 9 Received: 8`; `Received string: ".../jollof-rice-with-vegetable.webp undefined"` (no kind); `Expected: "Photography: BeesLens" Received: undefined`; `TypeError: _landingphotos.OWN_PHOTOS is not iterable`; door alt `Expected: "Spaghetti with chicken and fried plantain on a tray" Received: "A plate of jollof rice with fried plantain"` | rc 0, `Tests: 163 passed, 163 total` |
| Jest, Task 2 | rc 1, `Tests: 11 failed, 153 passed, 164 total`. Includes `Expected: 11 Received: 9`; Nigerian card `Received: {"alt": "Egusi soup with pounded yam", ...}`; `Expected: {"cuisine": "Catering", ...} Received: undefined`; shop-card `Expected: "1000" Received: "800"`; Jollof chip `Received string: "Jollof /_next/image?url=%2Fmarketing%2Flanding%2Fjollof-rice-with-vegetable.webp..."`; collage tile 0 `Received string: "0 ...egusi-soup-with-pounded-yam.webp..."`; credits links `Expected: 4 Received: 8` | rc 0, `Tests: 164 passed, 164 total`. Re-run after the arms and on the final head: rc 0, 164 passed |
| `npm run build` (TS typecheck) | not armed separately. A leftover removed key would fail TS; Jest's SWC does not typecheck | rc 0, `Compiled successfully`, `Finished TypeScript` |
| eslint on the 5 changed TS/TSX files | arm: a scratch file `const x: any = 1` in components/marketing gave rc 1 (removed afterwards) | rc 0 |
| taste-skill `lint.mjs` page.tsx + landing-photos.ts | baseline at 03eb939a (planning): rc 0, 0 FAIL, 1 WARN, em-dash 14 | rc 0, `0 FAIL, 1 WARN`, em-dash `14` (not above baseline) |
| Retired-name search (`rg -uu -F` over frontend/app, frontend/components, credits.json) | at 03eb939a: `git grep` of the same patterns found 22 lines, rc 0 | rc 1 (none). Positive control `chana-masala.webp` in landing-photos.ts: rc 0, count 2 |
| LCP-wiring diff vs 03eb939a (`fetchPriority/loading=/sizes=/srcSet/BLANK_PIXEL` changed lines) | arm: one `sizes=` edit (300px to 301px) read `2` (the - and + lines; the plan expected 1, and any non-zero fails the gate). Restored, hash equal | `0` |
| `scripts/docs-freshness.sh` | after the new Jest blocks: rc 1 (`jest_blocks 3464` vs computed `3469`) | after `--write`: rc 0 |
| `scripts/check-doc-metrics.sh` | after `--write`: rc 1, 7 FAIL lines (README/CLAUDE/AGENTS at 7027/3464) | after updating the quoted counts: rc 0, `PASS: all 37 prose metric claim(s)` |
| Task 3 records verify block | at 03eb939a the grounds check gave rc 1 ("ground step" lists How it works); invented `#ABC123` row gave `UNTRACED #ABC123`, rc 1; D16 heading grep at base gave 0 / rc 1; board metadata check against a source thumbnail read `beeslens 147303200363` (non-empty) | `RECORDS-OK`, rc 0 |
| `git log HEAD..origin/main` (after `git fetch`) | n/a | 0 commits behind |

Metrics before/after: `jest_blocks` 3464 to 3469; `total_logical_invocations` 7027 to 7032 (CLAUDE.md L15, AGENTS.md L15, README badge + Jest + Total lines).

## Byte table (old vs new)

Old sizes come from `git cat-file -s 03eb939a:<path>`; new sizes from `stat`.

| Slot | Before | After | Gate |
|---|---|---|---|
| Phone door strip | jollof-rice-and-dodo.webp 53,586 B | beeslens-spaghetti-chicken-plantain.webp 52,598 B (1000x627) | <= 53,586 PASS |
| Collage tile 1 | egusi-soup-with-pounded-yam.webp 189,454 B | beeslens-jollof-rice-meal-box.webp 84,376 B (1000x667) | <= 189,454 PASS |
| Collage tile 2 | suya-with-pepper-sauce.webp 177,778 B (kept for Grill) | beeslens-rice-beans-plantain-stew.webp 52,350 B (1000x627) | < 200,000 PASS |
| Collage tile 3 / Jollof chip | jollof-rice-with-vegetable.webp 149,440 B | beeslens-plantain-yam-meal-prep.webp 75,096 B (1000x667) / jollof box | < 200,000 PASS |
| Collage tile 4 / Desserts chip | puff-puff.webp 76,264 B | beeslens-puff-puff.webp 23,976 B (1000x627) | < 200,000 PASS |
| Catering cards (new) | none | beeslens-catering-soup-buffet.webp 44,586 B (1000x667) | < 200,000 PASS |
| Drinks/juice cards (new) | none | beeslens-fruit-punch-dispenser.webp 36,162 B (667x1000) | < 200,000 PASS |

Totals: 468,744 B retired and 369,144 B added. Every file was encoded at quality 78, so no quality step-down was needed.

## Watermark evidence (both directions, viewed with the Read tool)

| Source | Uncropped resize, bottom-left 40%x15% at 2x | Shipped derivative, same corner |
|---|---|---|
| _03A9210 (spaghetti) | "www.beeslens.com" visible | no text |
| _03A9212 (rice/beans/stew) | "www.beeslens.com" visible | no text |
| _03A9208 (puff-puff) | "www.beeslens.com" visible | no text |

The AFTER panels of the before/after board were checked the same way and show no text.

## Metadata evidence (both directions)

- **Fail direction:** an encode of _03A9208 made without `-strip` has RIFF chunks `VP8X,ICCP,VP8 ,EXIF,XMP `, and exiftool reads `Artist: beeslens` and `SerialNumber: 147303200363`.
- **Pass direction:** every shipped `beeslens-*.webp` carries only the `VP8 ` chunk (spaghetti walked by hand: `[ 'VP8 ' ]`). exiftool over all seven files printed 0 Artist/SerialNumber lines.
- **Board:** `before-after.jpg` was put through `exiftool -all= -overwrite_original`. `exiftool -s3 -Artist -GPSLatitude -SerialNumber` then printed nothing, rc 0. Fail arm: the same command on a source thumbnail printed `beeslens 147303200363`.
- No `.jpg` was staged in any commit apart from the board. Each commit's `git diff --cached --name-only | grep -c '\.jpg$'` read 0, and Task 3 staged only `before-after.jpg`. credits.json records only the source basename.

## The four break arms (clean -> arm -> clean)

The tree was clean before the arms (`git status --porcelain` empty).

| Arm | Break | RED (real) | Restore |
|---|---|---|---|
| (a) | `PHOTOS.jollofBox.width` 1000 -> 1001 | jest rc 1: `Expected: ".../beeslens-jollof-rice-meal-box.webp 1001x667"` `Received: "...1000x667"` | `git checkout HEAD --`; `git hash-object` == `HEAD:` blob: yes |
| (b) | `stray.webp` copied into landing/ | jest rc 1: orphan-file test, `+ "/marketing/landing/stray.webp"` | file deleted; porcelain empty |
| (c) | unstripped encode over `beeslens-puff-puff.webp` | jest rc 1: `Received string: "/marketing/landing/beeslens-puff-puff.webp VP8X,ICCP,VP8 ,EXIF,XMP "` | checkout; hash equal: yes |
| (d) | the fruit-punch own record deleted from credits.json | jest rc 1: 3 failed (bijection, kind count, OWN_PHOTOS mirror) | checkout; hash equal: yes |

The run after the arms gave jest rc 0 with 164 passed, and `git status --porcelain` was empty.

## Web-perf (CLAUDE.md standing contract)

The production build was served with `npx next start -p 3441` and tested with `PLAYWRIGHT_BASE_URL=http://localhost:3441 npx playwright test e2e/landing-webperf.spec.ts --grep "holds LCP and CLS|reserves its box|desktop CLS"`. Result: rc 0, **5 passed. PASS, not VOID**. The served page included kitchen cards: the HTML had 3 "example photo" captions plus Mama Ade's and Peckham Jollof.

- Mobile project: LCP 1004 ms, CLS 0.0165 (throttled 375px, 4x CPU). Card pictures reserve their box.
- Desktop project: LCP 1008 ms, CLS 0.0165 in the throttled-mobile test. Desktop CLS test: CLS 0.0007 at 1440x900, against a control of 0.1316 and a record of 0.0362.
- The POST-GRANT test was excluded as planned, because core's CORS refuses a non-3000 origin. The server was stopped afterwards (port 3441 no longer listening).

## Cross-cutting dimensions

- **Web-perf:** byte table above; web-perf spec PASS.
- **SEO:** metadata, canonical and JSON-LD were not touched. The new alts describe what each photo literally shows, and the decorative collage tiles and chips keep `alt=""`.
- **Agent-readiness:** N/A, no API surface.
- **Client-persisted identity:** N/A, no stored user state.
- **Security:** T-qfo-01 is mitigated (`-strip`, the RIFF test, arm c). T-qfo-02 is mitigated (only derivatives and the stripped board are committed, basenames only). T-qfo-03 is mitigated (CC paragraph verbatim; the review 1085 byte-for-byte test runs kind-filtered over the 4 CC records). T-qfo-04 is mitigated (`shop-card.tsx` unchanged; the caption is asserted). T-qfo-05 is mitigated (byte gates, the LCP diff gate at 0, web-perf PASS).
- **Falsifiability:** every gate above lists its fail direction.

## Must-haves evidence

1. **Strip spaghetti, eager/high, inside the lg-blank picture:** landing.test "shows dish photographs", GREEN after being RED.
2. **Collage order with the first tile eager/high:** landing.test collage test.
3. **Jollof/Desserts chips use own photos, the other four src unchanged:** chip-map assertions in landing.test.
4. **Kitchen cards by cuisine, plus catering/drinks/juice, with the captions:** landing-photos.test assignExamplePhotos tests, plus the shop-card and landing caption tests.
5. **Credits:**
   - A "Photography: BeesLens" line plus the 4 CC credits (8 links): landing.test.
   - Disk == PHOTOS == credits.json, with sizes read from the files: landing-photos.test.
6. **No EXIF/XMP and no watermark; no original JPEG in the repo:** metadata and watermark sections above.
7. **Byte gates:** byte table above.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Jollof meal box alt corrected from "stewed beef" to "stewed meat"**
- **Found during:** Task 2 (viewing each derivative)
- **Issue:** The photograph cannot show that the meat is beef (it may be gizzard). The plan says to correct an alt to what is literally visible.
- **Fix:** PHOTOS.jollofBox.alt, OWN_PHOTOS and the credits.json title now read "Jollof rice with chicken and stewed meat in a takeaway meal box".
- **Commit:** c6144c80

**2. [Recorded, not a code change] LCP-wiring gate arm reads 2, not 1**
- **Detail:** A one-line `sizes=` edit counts as both a `-` and a `+` line. The gate still fails on any non-zero value, so the change is only to the plan's expected number.

**3. [Rule 3] The CHANGELOG heading carries a literal `(#PR-PENDING)` placeholder**
- **Detail:** The orchestrator instructed no `gh pr create`, so the PR number does not exist yet. The placeholder is there so it cannot be missed.

The other six alts matched what each derivative shows when viewed, and were kept as drafted.

## Pending (for the orchestrator)

- **CHANGELOG:** in `docs/CHANGELOG.md`, replace `(#PR-PENDING)` in the heading `### Landing: The Owner's Own BeesLens Photographs Lead — 2026-11-02 (#PR-PENDING)` with ` (#<PR>)`. Do this in a commit pushed straight after `gh pr create` and before any review round: P-1 `check-changelog-cites-pr.sh` reads entry headings, and the review record must postdate the head.
- No `git push` and no `gh pr create` were done, as instructed. The owner-facing visual check comes first.
- Branch freshness: 0 behind origin/main at 19:24Z. Re-check before opening the PR.

## Known Stubs

None. Every PHOTOS entry points at a real file with a credit record. The bijection test enforces this.

## Self-Check: PASSED

- All 7 `beeslens-*.webp` files, `direction.md` and `before-after.jpg` exist.
- Commits 367d3b1b, 067c94b3, 72e81d1b, 11bad539, c6144c80 and 501c95f5 are ancestors of HEAD (checked below before the final commit).
