# Landing run 2026-11-02: the owner's own BeesLens photographs (design-run step 6, build)

Surface: `/` (page). Branch `design/landing-beeslens-photos`, cut from `origin/main` `03eb939a`.
Kind of change: **elevate, not redesign** (rubric R11): the D14/D15 page, its structure, palette,
motion and LCP wiring stay exactly as they are; only the pictures change.
Decision: **D16** in `design/decisions.md` (amends D10). Owner approval: Sanmi, 2026-11-02, the
mapping below as given. Board: `before-after.jpg` (this folder; metadata removed with exiftool).
Execution record: `.planning/quick/261102-qfo-landing-elevate-beeslens-photos-replace-/`.

## Trace

- `design/brief.md`, "Imagery that truly exists": until today it read "No food photography"; it now
  lists the seven BeesLens photographs and keeps the D10 history line.
- D10 (open-collection now, the kitchens' own later) is amended, not reversed: the owner's own
  photography leads, open-collection pictures stay only where no own photograph exists yet, and
  D10/D13's caption rule for kitchen cards ("<Cuisine> — example photo") is untouched
  (`shop-card.tsx` was not edited).

## Mapping

| source (owner's file) | web file (`frontend/public/marketing/landing/`) | `PHOTOS` key | where it appears |
|---|---|---|---|
| `_03A9210.jpg` (watermarked) | `beeslens-spaghetti-chicken-plantain.webp` | `spaghetti` | phone Order-food door strip (eager, high priority); Nigerian and Jollof kitchen cards |
| `7B8O7315.jpg` | `beeslens-jollof-rice-meal-box.webp` | `jollofBox` | collage tile 1 (desktop, eager, high priority); Jollof chip; Nigerian, West African/Ghanaian and Jollof kitchen cards |
| `_03A9212.jpg` (watermarked) | `beeslens-rice-beans-plantain-stew.webp` | `riceBeansStew` | collage tile 2; Nigerian kitchen cards |
| `7B8O7649.jpg` | `beeslens-plantain-yam-meal-prep.webp` | `plantainYam` | collage tile 3; West African/Ghanaian kitchen cards |
| `_03A9208.jpg` (watermarked) | `beeslens-puff-puff.webp` | `puffPuff` | collage tile 4; Desserts chip; dessert/bakery kitchen cards |
| `7B8O2461-187.jpg` | `beeslens-catering-soup-buffet.webp` | `cateringSoup` | catering kitchen cards (new cuisine) |
| `7B8O2357-98.jpg` | `beeslens-fruit-punch-dispenser.webp` | `fruitPunch` | drinks / juice kitchen cards (new cuisines) |

Kitchen-card order within a cuisine (slug order, `assignExamplePhotos`): Nigerian jollofBox,
riceBeansStew, spaghetti; West African / Ghanaian plantainYam, jollofBox; Jollof jollofBox, spaghetti.

**Stays open-collection** (no own photograph yet; CC credits unchanged): Grill chip and
grill/suya/bbq cards (suya), Caribbean (Jamaican rice and peas), South Asian (mutton biryani),
Vegan (chana masala).

**Retired** with their `credits.json` records: `egusi-soup-with-pounded-yam.webp`,
`jollof-rice-with-vegetable.webp`, `jollof-rice-and-dodo.webp`, the Wikimedia `puff-puff.webp`.
Egusi as a dish is no longer pictured on the landing (the owner's mapping).

Alt text was checked against each derivative by eye. One correction: the jollof meal box's alt says
"stewed meat", not the drafted "stewed beef", because the meat in the photograph cannot be told to be
beef (it may be gizzard); the alt states only what is visible.

## Processing

ImageMagick 6.9.12 with libwebp 1.3.2 (`cwebp` is not installed and was not):

```
convert SRC -auto-orient [-crop 100%x94%+0+0 +repage] -resize '1000x1000>' -strip -quality 78 -define webp:method=6 OUT
```

The crop (the bottom 6%) is applied to the three watermarked sources only. `-strip` removes EXIF,
XMP and ICC; all sources are sRGB with no EXIF Orientation, so colours are unchanged. No quality step
down was needed: every file met its byte gate at quality 78. The originals stay outside the repo;
`credits.json` records the source basename only.

## Bytes

| slot | before (file, bytes at 03eb939a) | after (file, bytes) | gate |
|---|---|---|---|
| phone door strip | jollof-rice-and-dodo 53,586 | beeslens-spaghetti-chicken-plantain 52,598 (1000x627) | <= 53,586 PASS |
| collage tile 1 (desktop LCP candidate) | egusi-soup-with-pounded-yam 189,454 | beeslens-jollof-rice-meal-box 84,376 (1000x667) | <= 189,454 PASS |
| collage tile 2 | suya-with-pepper-sauce 177,778 (stays on the Grill chip) | beeslens-rice-beans-plantain-stew 52,350 (1000x627) | < 200,000 PASS |
| collage tile 3, Jollof chip | jollof-rice-with-vegetable 149,440 | plantain-yam 75,096 (1000x667) / jollof box 84,376 | < 200,000 PASS |
| collage tile 4, Desserts chip | puff-puff 76,264 | beeslens-puff-puff 23,976 (1000x627) | < 200,000 PASS |
| catering cards (new) | none | beeslens-catering-soup-buffet 44,586 (1000x667) | < 200,000 PASS |
| drinks/juice cards (new) | none | beeslens-fruit-punch-dispenser 36,162 (667x1000) | < 200,000 PASS |

Landing pictures directory: 4 retired files 468,744 B out, 7 new files 369,144 B in.

## Evidence

- **Watermark, both directions** (bottom-left 40%x15% of the frame, enlarged 2x, viewed): for each
  of `_03A9210`, `_03A9212` and `_03A9208` an UNCROPPED resize shows "www.beeslens.com"; the shipped
  derivative's same corner shows no text. The board's AFTER panels were checked the same way: no text.
- **Metadata, both directions:** an encode of `_03A9208` without `-strip` carries RIFF chunks
  `VP8X,ICCP,VP8 ,EXIF,XMP ` and exiftool reads `Artist: beeslens` and the camera `SerialNumber`;
  every shipped BeesLens WebP carries `VP8 ` only and exiftool reads no Artist or SerialNumber.
  `landing-photos.test.ts` walks the RIFF chunks of every own photograph and fails on EXIF or XMP
  (break arm: the unstripped encode in place of `beeslens-puff-puff.webp` reds it).
- **Gates:** Jest (landing suites) RED before each change, GREEN after (164 tests); four break arms
  (declared size +1, orphan `stray.webp`, unstripped EXIF, a missing own record) each RED, restored
  and proven by `git hash-object`; `npm run build` rc 0 (the TypeScript check); eslint on the five
  changed files rc 0; taste-skill `lint.mjs` 0 FAIL, 1 WARN (em-dash 14, the baseline); the
  retired-name search rc 1 in `frontend/app`, `frontend/components` and `credits.json`; the
  LCP-wiring diff against `03eb939a` 0 changed lines (break arm: one `sizes=` edit reads 2); the
  landing web-perf spec on the production build: mobile LCP 1004 ms CLS 0.0165, desktop CLS 0.0007,
  every card picture reserves its box, PASS.
