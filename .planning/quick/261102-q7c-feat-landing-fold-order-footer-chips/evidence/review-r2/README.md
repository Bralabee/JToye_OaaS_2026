# PR #1097 round 2: the collage `<picture>` is a block grid item

Reproduced on this lane's own production build of 77f8b80e (`next build` + `next start` :3437,
`../scripts/prodnet.sh`, `../scripts/measure.cjs` which now asserts the collage shape):

| Width | Before (77f8b80e, picture `contents`) | After (picture `block`) |
|---|---|---|
| 1280x800 / 1366x768 / 1440x900, collage rows | `200px 224px 192px` (3 tracks, 648px) | `224px 200px` (2 tracks, 440px) |
| same, grid items | PICTURE(contents) + 3 IMG, plus the `<source>` as an item | PICTURE(block) + 3 IMG |
| same, egusi tile | column 2, the source took cell 1,1 (`fold-1366x768-before.png`) | column 1, row 1 |
| same, gold door bottom | 764 | **759** |
| 1024x768, gold door bottom | 836 | 836 (unchanged; below the fold, as D15 records) |
| egusi tile box (rotated bounding rect) | 289x210 at 1280+ | 289x210: the same box |
| `measure.cjs` assertions (2 tracks, egusi col 1 row 1, door <= 768, at 1280/1366/1440) | FAILED, rc 3 | PASSED, rc 0 |

The before run's `egusiColumn: 1` in `measure-prod-before-77f8b80e.json` comes from a first version
of the check that read the `contents` picture's empty box. The check now reads the img's own rect,
which is what the after file records.

Requests (`network-prod-after.json`, CDP initialPriority, cache off):
- strip `jollof-rice-and-dodo` 384w: not requested at 1024, 1280, 1366, 1440 (`currentSrc` is the data URI)
- egusi 384w: High
- 390x844: the same 10 image requests (URL, width, priority) as 994b9e08, and the strip img's attributes are identical

Jest: the new "keeps each collage grid item one block box" case failed on 77f8b80e (`contents`,
rc 1) and passes after. Landing + public: 126 passed. Lint: 0 errors. Build: rc 0.
`landing-webperf` after: 7 passed, 2 failed. The two failures are the POST-GRANT case, refused by
CORS for :3437, as in every earlier run.
