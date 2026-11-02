# PR #1097 round 1: the three fix items

## 1. Desktop LCP: reproduced, NO regression in the LCP element, no code change

Probe: `../scripts/lcpprobe.cjs` (buffered `largest-contentful-paint` observer, last entry, 3 s after
`load`), 5 fresh contexts per viewport. Production numbers come from `../scripts/prodlcp.sh`
(`next build` + `next start` on :3437 for each tree); dev numbers from `next dev`.

| Build | Tree | LCP element, 10 runs (1366x768 + 1440x900) | Tile LCP startTime ms | h1 LCP ms |
|---|---|---|---|---|
| prod | origin/main 9b737e61 | lazy egusi collage tile 6, h1 4 | 220, 224, 228, 252, 260, 376 | 72, 104, 104, 108 |
| prod | branch 994b9e08 | lazy egusi collage tile 5, h1 5 | 216, 220, 240, 240, 248 | 64, 68, 100, 104, 108 |
| prod | candidate fix (first tile eager+high, strip in a picture), NOT applied | eager egusi tile 4, h1 6 | 184, 196, 200, 224 | 64, 64, 68, 72, 100, 108 |
| dev | origin/main | h1 11, lazy tile 5 (of 16) | 496-532 | 160-176 |
| dev | branch 994b9e08 | lazy tile 9, h1 7 (of 16) | 480-564 | 148-384 |

On origin/main the door strip (eager, `fetchpriority=high`) is **never** the LCP element at either
desktop size: it is 120px tall and loses to the h1 (size ~70k) and the 300x200 collage tile. The
LCP element on both trees is the same pair, the h1 or the lazily loaded egusi tile, which one being
decided by run-to-run timing. So the review's condition (the LCP candidate moved from the strip to a
lazy tile) is not met, and per the round-1 instruction no code change is made for this item. Dev mode
does show the tile winning more often on the branch (9 of 16 against 5 of 16), but there the split
tracks when the streamed document lets images start (~150 ms or ~450 ms, for every image alike,
origin/main included); the production builds, the measure that matters for LCP, show no shift
(tile 6 of 10 on origin/main, 5 of 10 on the branch, at the same ~220-250 ms).

Found while reproducing, recorded and not fixed here: the hidden strip is not free on a desktop. Its
`sizes` gives `lg` a 1px slot, but next/image drops every srcset candidate below the smallest vw share
(45vw x 640 = 288 -> 384w is the smallest kept), so a 1366 desktop still selects the 384w strip
(`currentSrc` in `lcp-prod-branch-994b9e08.json`, heroImages). The page.tsx comment that says the
browser takes "the smallest candidate" is therefore wrong. A candidate fix was measured
(`lcp-*-candidate-fix-not-applied.json`) and, on the coordinator's follow-up, applied: section 4.

## 4. Follow-up: the strip is no longer requested on a desktop (applied)

`frontend/app/page.tsx`:
- The strip's `<Image>` sits in a `<picture>` whose `(min-width: 1024px)` `<source>` is a 1x1 data URI
  (CSP `img-src` allows `data:`). The img's own attributes are unchanged.
- The first collage tile is `loading="eager"` + `fetchPriority="high"`. It also sits in a `<picture>`
  (`display: contents`), whose `(max-width: 1023px)` source is the same data URI. Without that, the
  first variant measured an extra 32w High request for the hidden tile on a 390 phone.
- The comment now says what the browser does.

Network capture (`../scripts/netprobe.cjs`, CDP `Network.requestWillBeSent` `initialPriority`, cache
disabled, production build on :3437, `../scripts/prodnet.sh`):

| Viewport | Before (994b9e08) | After |
|---|---|---|
| 1366x768 strip `jollof-rice-and-dodo` 384w | requested, **High** | **not requested** (`currentSrc` is the data URI) |
| 1366x768 egusi tile 384w | requested, Low (lazy) | requested, **High** (eager) |
| 1366x768 all `/_next/image` requests | 14 | 13 |
| 390x844 strip 384w | requested, High | requested, High |
| 390x844 all `/_next/image` requests | 10 | 10, the identical set (URL, width, priority) |
| 390x844 strip img attributes (`srcset`, `sizes`, `loading`, `fetchpriority`, `currentSrc`) | — | identical to before |

At 1366 Chrome also gave the kitchen card's 640w example photo High instead of Low after the change
(its own heuristic for early large images; no attribute on it changed).

LCP (production, 5 loads x 2 desktop viewports, `lcp-prod-before-994b9e08-run2.json`, `lcp-prod-after.json`):
- before: h1 8, lazy tile 2 (228, 268 ms)
- after: h1 7, eager tile 3 (220, 284, 368 ms)

Together with the earlier runs (tile 216-260 ms before; 184-224 ms for the first fix variant), the
desktop LCP is neutral within run-to-run noise on this shared machine. The change is justified by
the request it removes, not by an LCP gain.

`landing-webperf.spec.ts` on the production build gave the same result before and after: 7 passed,
2 failed. Both failures are the POST-GRANT case, refused by core's CORS for origin :3437
(`../cors-control.txt`).

## 2. Attribution year no longer pinned in landing.test.tsx

- GEO_ATTRIBUTION_YEAR bumped to 2027, old literal-2026 test: RED (`year-test-red-literal-2026-with-constant-2027.txt`)
- same bump, new test (year matched as `\d{4}`, the same year required in all three): GREEN (`year-test-green-matched-with-constant-2027.txt`)
- Royal Mail statement given a different year (2025): new test RED (`year-test-red-mismatched-statement-year.txt`)
- footer restored to HEAD (`GEO_ATTRIBUTION_YEAR = "2026"`) before committing.

## 3. Fold claim scoped to 1280 and up

D15 and the CHANGELOG entry now say 759px holds from 1280x800 up and record 836px at 1024x768
(was 1014), from `../after/measure-after.json`.
