---
phase: quick
plan: 261102-qfo
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/public/marketing/landing/beeslens-spaghetti-chicken-plantain.webp
  - frontend/public/marketing/landing/beeslens-jollof-rice-meal-box.webp
  - frontend/public/marketing/landing/beeslens-rice-beans-plantain-stew.webp
  - frontend/public/marketing/landing/beeslens-plantain-yam-meal-prep.webp
  - frontend/public/marketing/landing/beeslens-puff-puff.webp
  - frontend/public/marketing/landing/beeslens-catering-soup-buffet.webp
  - frontend/public/marketing/landing/beeslens-fruit-punch-dispenser.webp
  - frontend/public/marketing/landing/credits.json
  - frontend/components/marketing/landing-photos.ts
  - frontend/app/page.tsx
  - frontend/components/marketing/__tests__/landing-photos.test.ts
  - frontend/app/__tests__/landing.test.tsx
  - frontend/components/marketing/__tests__/shop-card.test.tsx
  - design/decisions.md
  - design/identity.md
  - design/brief.md
  - design/runs/2026-11-02-landing-beeslens/direction.md
  - design/runs/2026-11-02-landing-beeslens/before-after.jpg
  - docs/CHANGELOG.md
  - docs/metrics.json
  - CLAUDE.md
  - AGENTS.md
  - README.md
files_deleted:
  - frontend/public/marketing/landing/egusi-soup-with-pounded-yam.webp
  - frontend/public/marketing/landing/jollof-rice-with-vegetable.webp
  - frontend/public/marketing/landing/jollof-rice-and-dodo.webp
  - frontend/public/marketing/landing/puff-puff.webp
autonomous: true
requirements: [QUICK-261102-qfo]
branch: "design/landing-beeslens-photos, worktree /home/sanmi/IdeaProjects/JToye_OaaS_2026-landing-beeslens, cut from origin/main 03eb939a. Never touch /home/sanmi/IdeaProjects/JToye_OaaS_2026 (another session's checkout)."
decision: "Owner (Sanmi) approved 2026-11-02, design-run step 6 build: the owner's own BeesLens photographs replace the open-collection dish pictures, mapped exactly as in the planning context. Recorded by this plan as design D16 (amends D10)."

estimate:
  tokens: 60000
  raw_tokens: 120000
  tasks: 3
  confidence: high

must_haves:
  truths:
    - "On a phone, the 'Order food near you' door strip shows the owner's spaghetti photo (alt 'Spaghetti with chicken and fried plantain on a tray'), still loading='eager' and fetchpriority='high' inside the same <picture> whose (min-width: 1024px) source is the 1x1 data URI."
    - "On desktop the collage shows, in order, the jollof meal box, the rice/beans/plantain/stew tray, the plantain and yam boxes and the puff-puff. The first tile is eager with fetchpriority high and the other three are lazy, exactly as today."
    - "The Jollof chip shows the jollof meal box and the Desserts chip the owner's puff-puff. Grill, Caribbean, South Asian and Vegan keep their Wikimedia pictures (src unchanged from 03eb939a)."
    - "A kitchen card with no banner gets the owner's photos by cuisine (nigerian -> jollof box, rice/stew tray, spaghetti; west african/ghanaian -> plantain+yam, jollof box; jollof -> jollof box, spaghetti; dessert -> puff-puff). New cuisines are added: catering -> buffet soup, drinks/juice -> fruit punch. Every one is still captioned '<Cuisine> — example photo' (D10/D13)."
    - "The Photo credits <details> holds one line 'Photography: BeesLens' plus the CC credits for exactly the four remaining open-collection pictures, CC wording unchanged. Every WebP in frontend/public/marketing/landing has exactly one credits.json record, every record has a file, and each file sits at the pixel size the code declares."
    - "No shipped BeesLens WebP carries an EXIF or XMP chunk. No 'www.beeslens.com' watermark text remains in the bottom-left of the three cropped derivatives. No original JPEG from ~/Downloads enters the repo."
    - "The strip file is no heavier than the file it replaces (53,586 B), and the first collage tile is no heavier than its predecessor (189,454 B). Every new WebP is under 200,000 B."
  artifacts:
    - path: "frontend/public/marketing/landing/beeslens-*.webp"
      provides: "Seven metadata-free web derivatives of the owner's photos. The three watermarked sources have their bottom 6% cropped."
    - path: "frontend/public/marketing/landing/credits.json"
      provides: "Per-picture ledger with a `kind` discriminator: 'open-collection' (4 CC records, unchanged apart from the added kind) or 'own' (7 BeesLens records)"
    - path: "frontend/components/marketing/landing-photos.ts"
      provides: "PHOTOS (11 entries), PHOTO_CREDITS (CC only), OWN_PHOTOS + OWN_PHOTO_CREDIT ('Photography: BeesLens'), CUISINES remapped + catering/drinks/juice"
    - path: "frontend/app/page.tsx"
      provides: "heroTiles, categories and the door strip pointed at the new PHOTOS keys; the BeesLens credit line inside the existing details"
    - path: "design/decisions.md"
      provides: "D16 · 2026-11-02 · Imagery: the owner's own BeesLens photography leads"
    - path: "design/runs/2026-11-02-landing-beeslens/direction.md"
      provides: "The run record: mapping, processing recipe, measured bytes, traced to brief imagery and D10/D16"
  key_links:
    - from: "frontend/app/page.tsx"
      to: "frontend/components/marketing/landing-photos.ts"
      via: "PHOTOS.<key> in heroTiles/categories/strip, OWN_PHOTO_CREDIT and PHOTO_CREDITS in the credits details"
      pattern: "PHOTOS\\.(jollofBox|riceBeansStew|plantainYam|puffPuff|spaghetti)"
    - from: "frontend/components/marketing/landing-photos.ts"
      to: "frontend/public/marketing/landing/credits.json"
      via: "landing-photos.test.ts bijection: PHOTOS src set == credits.json src set == *.webp on disk; PHOTO_CREDITS == kind open-collection; OWN_PHOTOS == kind own"
      pattern: "kind"
    - from: "frontend/components/marketing/shop-card.tsx"
      to: "frontend/components/marketing/landing-photos.ts"
      via: "assignExamplePhotos -> CUISINES (near-you-row.tsx), caption '<Cuisine> — example photo'"
      pattern: "assignExamplePhotos"
---

# Quick 261102-qfo: the landing leads with the owner's own BeesLens photographs

<objective>
Replace the landing's open-collection dish pictures with the owner's own BeesLens photography,
mapped exactly as Sanmi approved on 2026-11-02 (design-run step 6 build). This amends D10 and is
recorded as D16. Open-collection pictures stay only where no owner photo exists yet: Grill (suya),
Caribbean (rice and peas), South Asian (biryani) and Vegan (chana).

Purpose: the page's food becomes the business's own photography. A kitchen card still captions any
picture that is not the kitchen's own as an example (D10/D13), and the LCP wiring from D15 / PR #1097
is kept as it is.

Output: 7 WebP derivatives. The credit ledger gains a `kind` field. landing-photos.ts and page.tsx
are remapped and tests are extended. The 4 replaced Wikimedia files are retired along with their
credit records. The records are D16, the identity palette table traced to tokens, the run folder,
the CHANGELOG entry and the test-count metrics.

**Goods displaced and how each is accounted for (Incremental Betterment Doctrine):**

| Good today | After | Accounting |
|---|---|---|
| Egusi + pounded yam, collage tile 1 (the desktop LCP tile, eager + high) | Owner's jollof meal box in the same slot, same wiring | Owner's mapping; own photography over a found picture (D16). Egusi as a dish is no longer pictured on the landing (owner's choice) |
| Suya, collage tile 2 | Owner's rice/beans/plantain/stew tray | Suya is still shown on the Grill chip and on grill/suya/bbq kitchen cards |
| Jollof with vegetable: collage tile 3, Jollof chip, west african/jollof cards | Owner's plantain+yam boxes (tile 3); jollof meal box (chip, cards) | Same cuisines are still pictured, now with own photos |
| Wikimedia puff-puff: tile 4, Desserts chip, dessert cards | Owner's puff-puff | Same dish, own photo |
| Jollof and dodo: phone door strip (eager + high), nigerian/west african/jollof cards | Owner's spaghetti (strip); jollof box, rice/stew, spaghetti (cards) | `<picture>` + BLANK_PIXEL wiring kept byte-for-byte; only the PHOTOS key changes |
| CC credits for the 4 retired pictures | Removed with the files | The ledger must list only pictures the page can show (bijection tests) |
| Kitchens tagged catering / drinks / juice: no example picture | Owner's buffet soup / fruit punch | Additive |
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@design/decisions.md
@design/identity.md
@.claude/skills/proof-standards/SKILL.md
@frontend/components/marketing/landing-photos.ts
@frontend/components/marketing/__tests__/landing-photos.test.ts
@frontend/public/marketing/landing/credits.json

Ground truth measured while planning (2026-11-02, on 03eb939a):
- Tools: `cwebp` is NOT installed. ImageMagick 6.9.12 (`/usr/bin/convert`) has WEBP rw via libwebp 1.3.2, and `exiftool` is present. All 7 sources are sRGB IEC61966-2.1 with no EXIF Orientation, so `-strip` (which also drops ICC) leaves the colours unchanged.
- Sources (`/home/sanmi/Downloads/OaaS_food_Photos/`, outside the repo): _03A9208 5816x3877, _03A9210 5812x3874, _03A9212 6000x4000 (these three are watermarked "www.beeslens.com" bottom-left), 7B8O2357-98 1999x2999 portrait, 7B8O2461-187 2999x1999, 7B8O7315 3819x2546, 7B8O7649 3808x2539. EXIF Artist is beeslens / BeesLens Photography, and an unstripped encode also carries the camera SerialNumber.
- Probe recipe `convert SRC -auto-orient [-crop 100%x94%+0+0 +repage] -resize 1000x1000> -strip -quality 78 -define webp:method=6 OUT` gave: _03A9210 cropped 1000x627, 52,598 B, RIFF chunks ["VP8 "] only; 7B8O7315 84,376 B; 7B8O2357-98 667x1000, 36,162 B. The same encode without `-strip` gave chunks ["VP8X","ICCP","VP8 ","EXIF","XMP "] (Artist + SerialNumber readable by exiftool). That is the metadata check's proven fail direction. In the cropped probe the bottom-left corner showed no text, while the same corner of an uncropped resize showed "www.beeslens.com". That is the watermark check's proven both directions.
- Replaced files' bytes at 03eb939a: egusi-soup-with-pounded-yam 189,454 · jollof-rice-with-vegetable 149,440 · puff-puff 76,264 · jollof-rice-and-dodo 53,586.
- `frontend/node_modules` is ABSENT in this worktree.
- Baselines: `node ~/.claude/skills/taste-skill/scripts/lint.mjs frontend/app/page.tsx frontend/components/marketing/landing-photos.ts` rc 0, "0 FAIL, 1 WARN" (em-dash, 14). `scripts/docs-freshness.sh` rc 0 (7027). `scripts/check-doc-metrics.sh` rc 0 (37 claims). docs/metrics.json jest_blocks 3464.
- design/identity.md: every hex in it already traces to `frontend/tailwind.config.ts` (brand/gold/warm at lines ~109-118) or `frontend/app/globals.css` (`--trust: #047857` line ~65). What is stale is the ground assignment D15 changed: the warm-100 row still lists "How it works". The table also has no token/source column. The footer is `bg-oxblood text-cream` (`frontend/components/public/public-footer.tsx` ~L85, legacy `oxblood` = #3A0B0D).
- Changelog: docs/CHANGELOG.md under `## [Unreleased]`. `scripts/check-changelog-cites-pr.sh` (P-1) requires a feat/fix PR's number in an entry HEADING. The number exists only once the PR is opened.
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Tracer, one BeesLens photo end-to-end: source JPEG to door strip to own-photo credit line</name>
  <files>frontend/public/marketing/landing/beeslens-spaghetti-chicken-plantain.webp, frontend/public/marketing/landing/credits.json, frontend/components/marketing/landing-photos.ts, frontend/app/page.tsx, frontend/components/marketing/__tests__/landing-photos.test.ts, frontend/app/__tests__/landing.test.tsx</files>
  <precondition>frontend/node_modules exists. If absent, run `npm ci` in frontend/ first (it restores the committed lockfile, no new package). Then `git diff --exit-code frontend/package-lock.json` must return rc 0.</precondition>
  <read_first>frontend/app/page.tsx lines 70-100 (categories, BLANK_PIXEL, heroTiles), 355-390 (door strip picture), 420-465 (collage), 537-590 (trust band + credits details); frontend/app/__tests__/landing.test.tsx lines 420-470 and 515-560; frontend/public/marketing/landing/credits.json (record shape)</read_first>
  <behavior>
    - landing-photos.test: every credits.json record carries `kind`, either "open-collection" or "own". PHOTO_CREDITS equals the open-collection records byte-for-byte on {src,title,creator,page,license,licenseUrl} (the review 1085 test, now kind-filtered). OWN_PHOTOS equals the own records on {src,title,creator}. Each own record has creator "BeesLens", license "© BeesLens Photography, used with permission", page null, licenseUrl null, and a derivation matching /metadata stripped/. OWN_PHOTO_CREDIT === "Photography: BeesLens".
    - landing-photos.test (new it): each own photo's WebP has no "EXIF" and no "XMP " RIFF chunk. Control in the same test: the chunk walker finds a "VP8 ", "VP8L" or "VP8X" chunk in every file, so an empty walk cannot pass.
    - landing.test: the door strip img has alt "Spaghetti with chicken and fried plantain on a tray", a src containing beeslens-spaghetti-chicken-plantain, fetchpriority "high" and loading "eager", and it stays inside the <picture> with the (min-width: 1024px) source.
    - landing.test (new it): inside details[data-photo-credits] an element's text is exactly "Photography: BeesLens". The /^Photographs/ CC paragraph does not contain "BeesLens". The credits test iterates only kind "open-collection" records, and the own records' titles are not links.
  </behavior>
  <action>
RED first (tdd_mode). Write the behavior assertions above into frontend/components/marketing/__tests__/landing-photos.test.ts and frontend/app/__tests__/landing.test.tsx. Update the existing door-alt assertion in "shows dish photographs" to the spaghetti alt. In "credits every photograph…", replace `credits.length toBe(8)` with per-kind assertions. Add the RIFF chunk walker next to `webpSize`: start at offset 12, read fourcc + uint32LE size, advance by 8 + size + (size & 1). Run `cd frontend && npx jest components/marketing app/__tests__/landing.test.tsx`, confirm the new and changed assertions fail, and save the failing lines for the SUMMARY. Commit the red tests (`test(quick-261102-qfo): …`).

Process ONE photo. Encode `/home/sanmi/Downloads/OaaS_food_Photos/_03A9210.jpg` to frontend/public/marketing/landing/beeslens-spaghetti-chicken-plantain.webp with ImageMagick: `-auto-orient`, `-crop 100%x94%+0+0 +repage` (the bottom 6% removes the watermark), `-resize 1000x1000>`, `-strip`, `-quality 78`, `-define webp:method=6`. cwebp is not installed; do not install it. The file must be at most 53,586 B, the size of the file it replaces. If it is larger, step quality down by 4 until it fits and record the value. Read its real size with `identify -format '%wx%h'` (the probe gave 1000x627).

Check the watermark both ways. Using scratchpad files only, crop the bottom-left 40%x15% of the new WebP and of an UNCROPPED `-resize 1000x1000>` of the same source (`-gravity SouthWest -crop 40%x15%+0+0 +repage -resize 200%`). View both with the Read tool. Record "uncropped: www.beeslens.com visible" and "derivative: no text".

credits.json: add `"kind": "open-collection"` to each of the 8 existing records. Add nothing else and change nothing else. Append one record with: file, media "image", kind "own", title = the alt text, creator "BeesLens", creatorUrl null, license "© BeesLens Photography, used with permission", licenseUrl null, creditRequired true, page null, via "supplied by the owner, 2026-11-02", sourceFile "_03A9210.jpg" (basename only, never the ~/Downloads path), source width/height/bytes, saved (ISO time), derivation "bottom 6% cropped to remove the photographer's watermark, resized to 1000px on the long edge, re-encoded as WebP, all metadata stripped", src.

landing-photos.ts: add `spaghetti` to PHOTOS with the measured width/height and the literal alt. Add an `OwnPhotoCredit` interface {src,title,creator}, an `OWN_PHOTOS` array mirroring the own records, and `export const OWN_PHOTO_CREDIT = "Photography: BeesLens"`. Keep `PhotoCredit` / PHOTO_CREDITS for the CC records only. Leave jollofDodo and its record in place for now; CUISINES still uses it until Task 2, so the bijection holds at 9. Update the header comment to say the set is now the owner's own photographs (D16) plus credited open-collection pictures. Add NO em dashes; the lint baseline counts 14.

page.tsx: in the door strip `<Image>`, change only the four `PHOTOS.jollofDodo` references to `PHOTOS.spaghetti`. Leave every other attribute and the `<picture>`/`<source>` untouched (D15 / PR #1097 wiring). In the credits `<details>`, add `<p className="mt-2">{OWN_PHOTO_CREDIT}</p>` before the existing `Photographs (…), via Wikimedia Commons:` paragraph and leave that paragraph exactly as it is (CC BY-SA wording verbatim). Import OWN_PHOTO_CREDIT.

Run the same jest command. GREEN. Commit `feat(quick-261102-qfo): …`. Commit messages carry no AI-attribution lines (owner ruling 2026-08-30, enforced by hooks).
  </action>
  <verify>
    <automated>cd frontend && npx jest components/marketing app/__tests__/landing.test.tsx</automated>
  </verify>
  <done>Tests that were RED before the change (output recorded) are GREEN. The strip renders the owner's spaghetti photo with eager + high priority inside the unchanged picture. "Photography: BeesLens" appears inside the Photo credits details. The new WebP is at most 53,586 B, carries only a VP8 chunk, and has no watermark text (both directions viewed and recorded). Two commits: red, then green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Expand: the other six BeesLens photos into the collage, chips and kitchen cards; retire the four replaced Wikimedia pictures</name>
  <files>frontend/public/marketing/landing/beeslens-jollof-rice-meal-box.webp, frontend/public/marketing/landing/beeslens-rice-beans-plantain-stew.webp, frontend/public/marketing/landing/beeslens-plantain-yam-meal-prep.webp, frontend/public/marketing/landing/beeslens-puff-puff.webp, frontend/public/marketing/landing/beeslens-catering-soup-buffet.webp, frontend/public/marketing/landing/beeslens-fruit-punch-dispenser.webp, frontend/public/marketing/landing/credits.json, frontend/components/marketing/landing-photos.ts, frontend/app/page.tsx, frontend/components/marketing/__tests__/landing-photos.test.ts, frontend/app/__tests__/landing.test.tsx, frontend/components/marketing/__tests__/shop-card.test.tsx (the 7 binary files cost no context; 6 code files)</files>
  <read_first>frontend/components/marketing/__tests__/shop-card.test.tsx lines 120-175 (example-photo size/src/alt assertions, makeShop tags "Jollof, West African, Halal")</read_first>
  <behavior>
    - landing-photos.test bijection: PHOTOS has 11 entries. The set of `*.webp` files in public/marketing/landing equals the PHOTOS src set equals the credits.json src set (new: an orphan file left on disk fails). By kind: 4 open-collection (suya, riceAndPeas, biryani, chana) and 7 own.
    - assignExamplePhotos: mama-ades-kitchen (Nigerian) gets jollofBox and peckham-jollof-co gets riceBeansStew. a-kitchen gets jollofBox in both orders. A "Catering" kitchen gets cateringSoup. A kitchen tagged "Juice" gets fruitPunch, and one tagged "drinks" gets fruitPunch, each with cuisine spelled as the kitchen wrote it (new it).
    - landing.test: the collage's 4 imgs have srcs in order jollof-rice-meal-box, rice-beans-plantain-stew, plantain-yam-meal-prep, puff-puff (beeslens-), and items[0] keeps fetchpriority high. The Jollof chip src contains beeslens-jollof-rice-meal-box and the Desserts chip beeslens-puff-puff. The Grill, Caribbean, South Asian and Vegan chip srcs contain suya-with-pepper-sauce, jamaican-rice-and-peas, mutton-biryani and chana-masala. The credits details holds exactly 2 x (open-collection count) = 8 links, replacing the old `>= 16`.
    - shop-card.test: the Jollof-tagged card shows beeslens-jollof-rice-meal-box at its real width/height (identify's values, not 800/532), with alt equal to PHOTOS.jollofBox.alt and the caption "Jollof — example photo".
  </behavior>
  <action>
RED first. Update the assertions above in the three test files. Also update the fixed names and dimensions in shop-card.test.tsx lines ~130-157 and in the landing.test.tsx comment "the egusi tile is still the first one" (it becomes the jollof meal-box tile). Run jest, record the failures, and commit the red tests.

Encode six photos with Task 1's recipe, into frontend/public/marketing/landing/:
- 7B8O7315.jpg to beeslens-jollof-rice-meal-box.webp, key jollofBox, alt "Jollof rice with chicken and stewed beef in a takeaway meal box"
- _03A9212.jpg to beeslens-rice-beans-plantain-stew.webp, key riceBeansStew, alt "White rice, beans and fried plantain on a tray with a bowl of pepper stew", WITH the 6% crop
- 7B8O7649.jpg to beeslens-plantain-yam-meal-prep.webp, key plantainYam, alt "Fried plantain, yam and pepper sauce in two meal-prep boxes"
- _03A9208.jpg to beeslens-puff-puff.webp, key puffPuff (the key is reused and the file is new), alt "Puff-puff in a wooden bowl with cocktail sticks", WITH the 6% crop
- 7B8O2461-187.jpg to beeslens-catering-soup-buffet.webp, key cateringSoup, alt "Soup being served into a bowl at a catering buffet"
- 7B8O2357-98.jpg to beeslens-fruit-punch-dispenser.webp, key fruitPunch, alt "A glass drinks dispenser of red fruit punch with cucumber and orange slices" (literal; do not name the drink)

View each derivative once with the Read tool and confirm the alt describes what is shown. If it does not, correct the alt to what is literally visible and record that. For _03A9212 and _03A9208, run Task 1's two-way bottom-left watermark check and record it. Gates: every file under 200,000 B, and beeslens-jollof-rice-meal-box.webp (the first tile) at most 189,454 B. Build an old-vs-new byte table for the SUMMARY; read old sizes with `git cat-file -s 03eb939a:<path>`.

credits.json: append six own records shaped like Task 1's, with the crop clause only on the two watermarked ones. Delete the four retired open-collection records (the PHOTOS keys egusi, jollofVeg, jollofDodo and the old puffPuff), then `git rm` their four WebP files.

landing-photos.ts: PHOTOS becomes suya, riceAndPeas, biryani, chana plus the 7 own keys. Remove the four retired PHOTO_CREDITS rows and list all 7 in OWN_PHOTOS. Set CUISINES exactly to the owner's mapping:
- nigerian: [jollofBox, riceBeansStew, spaghetti]
- west african / ghanaian: [plantainYam, jollofBox]
- jollof: [jollofBox, spaghetti]
- grill / suya / bbq: [suya] (unchanged)
- caribbean / jamaican: [riceAndPeas] (unchanged)
- south asian / indian / pakistani / bangladeshi / biryani: [biryani] (unchanged)
- vegan / vegetarian: [chana] (unchanged)
- dessert / desserts / bakery: [puffPuff]
- Append { words: ["catering"], photos: [cateringSoup] } and { words: ["drinks", "juice"], photos: [fruitPunch] }.
Captions stay "<Cuisine> — example photo" (shop-card.tsx is not edited; D10/D13).

page.tsx:
- heroTiles photos in order jollofBox, riceBeansStew, plantainYam, puffPuff. Keep each `extra` tilt class and the `i === 0` eager/high/picture logic untouched.
- categories: Jollof becomes PHOTOS.jollofBox and Desserts becomes PHOTOS.puffPuff. The other four are unchanged.
- In the collage comment (~L426), "egusi tile" becomes "jollof meal-box tile".
- Nothing under frontend/app or frontend/components, comments included, may still name a retired file's basename.

Run jest and get GREEN. Run `npm run build`. Commit (`feat(quick-261102-qfo): …`).

Fail-direction arms, AFTER the commit (proof-standards rule 1: clean, arms, clean). Run each arm alone, run jest, record the RED line, restore, and run jest GREEN again:
(a) set PHOTOS.jollofBox.width to its value + 1; the size test goes RED.
(b) copy any webp to frontend/public/marketing/landing/stray.webp; the orphan-file test goes RED; delete it.
(c) overwrite beeslens-puff-puff.webp with a scratchpad encode made WITHOUT `-strip`; the metadata test goes RED. Restore with `git checkout HEAD -- <file>` and prove the restore by content: `git hash-object <file>` must equal `git rev-parse HEAD:<file>`.
(d) delete one own record from credits.json; the bijection test goes RED; restore by checkout and confirm the same way.
End with `git status --porcelain` empty.

Also:
- Run `node ~/.claude/skills/taste-skill/scripts/lint.mjs frontend/app/page.tsx frontend/components/marketing/landing-photos.ts`: rc 0, 0 FAIL, em-dash count not above 14.
- Run `cd frontend && npx eslint` on the 5 changed TS/TSX files and read the rc, not the summary line.
- Web-perf (CLAUDE.md standing contract). After the build, start `npx next start -p 3441` in the background and run `PLAYWRIGHT_BASE_URL=http://localhost:3441 npx playwright test e2e/landing-webperf.spec.ts --grep "holds LCP and CLS|reserves its box|desktop CLS"`. POST-GRANT is excluded because core's CORS refuses a non-3000 origin (q7c precedent). If a test fails only because the core API or the shops are unreachable from this worktree, record it as VOID with the reason, never as PASS. Then stop the server.
  </action>
  <verify>
    <automated>cd frontend && npx jest components/marketing app/__tests__/landing.test.tsx && npm run build && cd .. && node ~/.claude/skills/taste-skill/scripts/lint.mjs frontend/app/page.tsx frontend/components/marketing/landing-photos.ts && { rg -n -uu -F -e egusi-soup-with-pounded-yam -e jollof-rice-with-vegetable -e jollof-rice-and-dodo -e 'landing/puff-puff.webp' frontend/app frontend/components frontend/public/marketing/landing/credits.json; rc=$?; echo "retired-name rc=$rc (want 1; at 03eb939a it is 0)"; test $rc -eq 1; } && { ctl=$(rg -c -uu -F chana-masala.webp frontend/components/marketing/landing-photos.ts); crc=$?; echo "positive control rc=$crc count=$ctl (want rc 0)"; test $crc -eq 0; } && { gd=$(git diff -U0 03eb939a -- frontend/app/page.tsx); grc=$?; test $grc -eq 0 && test -n "$gd" || { echo "VOID: git diff rc=$grc or empty diff"; exit 2; }; d=$(/usr/bin/grep -cE '^[-+][^-+].*(fetchPriority|loading=|sizes=|srcSet|BLANK_PIXEL)' <<< "$gd"); echo "LCP-wiring lines changed: $d (want 0; arm: edit one sizes= value and it reads 1)"; test "$d" = "0"; }</automated>
  </verify>
  <done>PHOTOS has 11 entries, and disk == PHOTOS == credits.json, 4 open-collection + 7 own. The collage, chips, strip and kitchen cards map exactly as the owner approved, and catering/drinks/juice cards get pictures. The four retired files and records are gone, with no name left in frontend/app, frontend/components or credits.json. Arms (a) to (d) are each recorded RED, then GREEN, then a clean tree. Jest, build and lint.mjs (0 FAIL) pass and eslint rc is 0. The byte table shows the strip ≤ 53,586, the first tile ≤ 189,454 and all files < 200,000. Every watermark check is recorded both ways. Web-perf is PASS or explicit VOID with its reason.</done>
</task>

<task type="auto">
  <name>Task 3: Records: D16, the identity palette traced to its tokens, the run folder, CHANGELOG and test-count metrics</name>
  <files>design/decisions.md, design/identity.md, design/brief.md, design/runs/2026-11-02-landing-beeslens/direction.md, design/runs/2026-11-02-landing-beeslens/before-after.jpg, docs/CHANGELOG.md, docs/metrics.json, CLAUDE.md, AGENTS.md, README.md</files>
  <read_first>design/decisions.md (header + D10, D13-D15); design/identity.md (whole file, 59 lines); design/runs/2026-11-01-landing/direction.md lines 1-30 (run record shape); frontend/tailwind.config.ts lines 95-125; frontend/app/globals.css lines 20-70; docs/CHANGELOG.md lines 1-30 (entry shape)</read_first>
  <action>
**design/decisions.md.** Append `## D16 · 2026-11-02 · Imagery: the owner's own BeesLens photography leads` in the house shape: date, decision, source, Status, Review when.
- Body: Sanmi supplied seven of his own BeesLens photographs on 2026-11-02 and approved the mapping. Cite the run folder and its before-after.jpg board.
- **Status: KEEP (Sanmi).** It amends D10: own photography now, and open-collection pictures only where none exists yet (Grill, Caribbean, South Asian, Vegan). D10/D13's rule stands: on a kitchen card any such picture is captioned "<Cuisine> — example photo" and never presented as that kitchen's dish.
- Credit: one line "Photography: BeesLens" in the Photo credits disclosure, with the CC wording unchanged for the rest. The watermark was cropped (bottom 6%) on three photos, and all metadata was stripped.
- Evidence: the owner's answers of 2026-11-02.
- **Review when:** more owner photos arrive for the remaining cuisines, or kitchens upload their own through the Phase 24 media pipeline.
- Add a second bullet, "Also ruled 2026-11-02": the chip rail's mouse affordance at 1024-1279 stays the D14 "peek", and the owner chose no change.
- On D15's Status line, append a parenthetical noting that the no-fade-mask deviation was confirmed for the chip rail by Sanmi on 2026-11-02 (D16). Leave the rest of D15 unchanged.

**design/identity.md.** Fix the stale palette table:
- Read each hex from `frontend/tailwind.config.ts` / `frontend/app/globals.css`, never from identity.md. Add a column naming the token and its source file (for example `brand.900`, tailwind.config.ts; `--trust`, globals.css). If a hex ever disagrees, the source file wins; planning found none that disagree.
- Correct the "where" column to the D15 grounds. White: header, How it works and the kitchen row (one white field), controls on stone. warm-100: hero, kitchen cards, trust band (its chips white).
- Add the footer's oxblood field: `bg-oxblood` in public-footer.tsx, the legacy token with the same hex as brand-900.
- Rewrite the "Imagery (D10)" section and the photograph sentence in "The one memorable device" to D16: the owner's own photography first, credited open-collection pictures where none exists yet.
- In the Status line, mention D15/D16 as later amendments.
- Invent no hex.

**design/brief.md.** In "Imagery that truly exists", replace "No food photography" with the seven BeesLens photographs (D16, 2026-11-02), keeping the D10 history line. Claude's discretion: the inventory would otherwise be false.

**Run folder.** Create design/runs/2026-11-02-landing-beeslens/:
- before-after.jpg: copied from `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/f980f964-ca21-4b68-9a76-c2b7dfea5388/scratchpad/photos/board.jpg` (outside the repo). Remove any metadata losslessly with `exiftool -all= -overwrite_original`.
- direction.md: surface `/`, step 6 build (elevate, not redesign: rubric R11), owner approval 2026-11-02. Include a mapping table (source file to web file to PHOTOS key to every place it appears) and the "stays open-collection" list. Include the processing recipe, the measured bytes table from Task 2, and the watermark/metadata evidence. Trace the mapping to brief.md "Imagery that truly exists" and D10/D16. Name the gates run and their results.

**docs/CHANGELOG.md.** At the top of `## [Unreleased]`, add `### Landing: The Owner's Own BeesLens Photographs Lead — 2026-11-02` with 3-5 bullets: what changed, what stays Wikimedia, credits, LCP wiring kept with bytes, proof. The PR number does not exist yet. Record in the SUMMARY that ` (#<PR>)` must be appended to this heading in a commit pushed right after `gh pr create`, before any review round (P-1 reads entry headings; the review record must postdate the head).

**Metrics.** Run `scripts/docs-freshness.sh` (expect rc 1 now that Jest blocks were added). Then run `scripts/docs-freshness.sh --write`. Then update every quoted count that `scripts/check-doc-metrics.sh` reports: CLAUDE.md L15, AGENTS.md L15, README.md badge + Jest + Total lines. Re-run both gates to rc 0. Record the before/after numbers.
  </action>
  <verify>
    <automated>bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh && /usr/bin/grep -c '^## D16 · 2026-11-02 · Imagery: the owner' design/decisions.md && { step=$(/usr/bin/grep -E '^\| ground step \|' design/identity.md); ground=$(/usr/bin/grep -E '^\| ground \|' design/identity.md); test -n "$step" && test -n "$ground" || { echo "VOID: the 'ground' / 'ground step' rows were not found (keep those first-cell labels)"; exit 2; }; ! /usr/bin/grep -qF 'How it works' <<< "$step" && /usr/bin/grep -qF 'How it works' <<< "$ground"; } && { hx=$(/usr/bin/grep -oE '#[0-9A-Fa-f]{6}' design/identity.md); test -n "$hx" || { echo "VOID: no hex found in identity.md"; exit 2; }; for h in $(sort -u <<< "$hx"); do /usr/bin/grep -qiF "$h" frontend/tailwind.config.ts frontend/app/globals.css || { echo "UNTRACED $h"; exit 1; }; done; } && test -s design/runs/2026-11-02-landing-beeslens/direction.md && { meta=$(exiftool -s3 -Artist -GPSLatitude -SerialNumber design/runs/2026-11-02-landing-beeslens/before-after.jpg); erc=$?; test $erc -eq 0 && test -z "$meta"; } && echo RECORDS-OK</automated>
  </verify>
  <done>D16 and the chip-rail ruling are recorded, and D15's status is cross-referenced. The identity palette table carries a token source per row and the D15 grounds. At 03eb939a the "ground step" row lists How it works, so the check is RED there; it is GREEN after. Fail direction: inserting an invented hex reads UNTRACED. brief.md lists the photos. The run folder holds direction.md and a metadata-free before-after.jpg. The CHANGELOG entry is written, with the PR-number follow-up recorded. docs-freshness and check-doc-metrics are rc 0 with the new counts.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| owner's camera originals -> public web assets | Full-resolution JPEGs carry the photographer's EXIF (Artist, camera SerialNumber) and a watermark; only processed derivatives may cross into frontend/public, which is served to anyone |
| repo -> git history | A pushed binary is costly to remove (history rewrite; memory: p0 PII purge) |
| third-party licences -> page credits | The remaining CC BY-SA pictures must keep a compliant attribution |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-qfo-01 | Information disclosure | frontend/public/marketing/landing/beeslens-*.webp | medium | mitigate | `-strip` on every encode; the landing-photos.test RIFF walk fails on any EXIF/XMP chunk (fail direction proven in planning: unstripped encode = EXIF+XMP with Artist and SerialNumber 559518); arm (c) re-proves it in Task 2 |
| T-qfo-02 | Information disclosure | git history | medium | mitigate | Only derivatives are written into the repo, and credits.json records the source basename only, never a local path. `git diff --cached --name-only` before each commit must show no `.jpg` except design/runs/2026-11-02-landing-beeslens/before-after.jpg, which is metadata-stripped by exiftool and checked in Task 3 verify |
| T-qfo-03 | Repudiation (licence) | Photo credits details, PHOTO_CREDITS | medium | mitigate | CC paragraph left verbatim; the review 1085 byte-for-byte test now runs kind-filtered over the 4 remaining CC records; bijection + orphan-file test prevents a shown picture without a credit or a credit without a picture |
| T-qfo-04 | Spoofing (misrepresentation) | kitchen card example photos | low | mitigate | shop-card.tsx is unchanged; the caption stays "<Cuisine> — example photo" (D10/D13) and is asserted in shop-card.test and landing.test |
| T-qfo-05 | Denial of service (LCP) | first collage tile, door strip | low | mitigate | Byte gates (strip ≤ 53,586 B, tile ≤ 189,454 B, all < 200,000 B); the LCP-wiring diff gate reads 0 changed fetchPriority/loading/sizes/srcSet/BLANK_PIXEL lines; web-perf spec run (or explicit VOID) |
| T-qfo-SC | Tampering | npm installs | low | accept | No new package. `npm ci` restores the committed lockfile into a fresh worktree, and `git diff --exit-code frontend/package-lock.json` must be rc 0. No package-legitimacy checkpoint is needed because nothing new is installed |
</threat_model>

<verification>
Phase-level, from the checkout root, after Task 3:
1. `cd frontend && npx jest components/marketing app/__tests__/landing.test.tsx`: green. The RED-before-GREEN outputs of Tasks 1 and 2 and the four fail-direction arms are recorded.
2. `cd frontend && npm run build`: rc 0. This is the TS typecheck; a leftover reference to a removed PHOTOS key fails it.
3. `node ~/.claude/skills/taste-skill/scripts/lint.mjs frontend/app/page.tsx frontend/components/marketing/landing-photos.ts`: rc 0, 0 FAIL, em-dash count ≤ 14 (baseline 14).
4. Watermark: for the three cropped derivatives, the bottom-left corner was viewed and shows no text, against the positive control of the uncropped corner showing "www.beeslens.com". Both are recorded.
5. Retired-name gate rc 1 with its positive control (rc 0 at 03eb939a). LCP-wiring diff gate = 0 (arm: one sizes= edit reads 1).
6. `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh`: rc 0.
7. `git log HEAD..origin/main --oneline` is empty before a PR, or a merge from main is recorded. After any merge, re-run steps 1, 2 and 6, because counts move when main moves.
8. Cross-cutting dimensions, recorded in the SUMMARY. Web-perf: byte table + web-perf spec PASS/VOID. SEO: meta/canonical/JSON-LD untouched; new alts are literal; decorative tiles/chips keep alt="". Agent-readiness: N/A (no API surface). Client-persisted identity: N/A (no stored user state). Security: the threat register above. Falsifiability: both directions recorded for every gate.
</verification>

<success_criteria>
- All seven must_haves truths hold, each with its evidence named in the SUMMARY.
- The owner's mapping is implemented exactly: heroTiles, strip, chips, CUISINES (including the additive catering and drinks/juice). The Grill, Caribbean, South Asian and Vegan Wikimedia pictures and their CC credits are untouched.
- D16 is in design/decisions.md, the identity palette is traced to its token sources, and the run folder and CHANGELOG entry exist. The metrics gates are green.
- Commits are atomic per task (red, then green, then records), with no AI-attribution lines. Nothing was written outside /home/sanmi/IdeaProjects/JToye_OaaS_2026-landing-beeslens except scratchpad files.
- Out of scope: frontend/components/marketing/shop-card.tsx, near-you-row.tsx, any motion hook (`data-hero-*`), core-java/, infra/, k8s/, design/runs/2026-11-01-* (history, kept as-is), docs/CREDITS-demo-images.md (the seeder's separate image set).
</success_criteria>

<output>
Create `.planning/quick/261102-qfo-landing-elevate-beeslens-photos-replace-/261102-qfo-SUMMARY.md` when done. Include:
- a gate table, before vs after, with rc for each;
- the old-vs-new byte table;
- the watermark and metadata evidence, both directions;
- the four arms;
- the web-perf PASS or VOID;
- the CHANGELOG heading's pending ` (#<PR>)` follow-up;
- the commit SHAs.
</output>
