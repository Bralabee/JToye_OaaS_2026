# 37-59: UAT gap G-37-6, proven on the delivered runtime

Plan: `.planning/phases/37-real-world-operations-readiness/37-59-PLAN.md`
Integration branch: `phase-37-ops-readiness`. Plan head before: `8498425e` (`.git/gsd-plan-head-before-37-59`).
Gap-set base (G376_BASE, `evidence/37-56-g376-base.txt`): `36f9aee3`, committed 2026-10-16T14:56:23+01:00.
Task 1 (the instrument, `6423fb86`): `frontend/e2e/screens-copy-uat.spec.ts`.
PR branch: `phase-37-uat-g376`, tag `cut/phase-37-uat-g376` (Task 4, section 9 onward). Not pushed.

**Owner answer to the Task 2 checkpoint (2026-10-16), through the orchestrator:** "proceed", covering Tasks 3 and 4.
The continuation started at 2026-10-16T14:50:04Z on HEAD `6423fb86` with a clean tree (only the two untracked
paths that belong to another session: `.planning/milestone.lock`, `.planning/ui-reviews/`), from the main checkout
root (root pin rc 0). Precondition checked read-only: `docker compose -f docker-compose.full-stack.yml ps` showed all
11 services running, including core-java, frontend, keycloak and mailhog; the root `.env` holds one
`KC_SEED_USER_PASSWORD=` line (count only, value not read out). Compose XOR k8s holds: `minikube status -p jtoye`
finds no `jtoye` container. Nothing was installed (T-37-SC).

---

## 1. Pre-rebuild runtime (14:50:04Z, before any write)

Identities (image id, `.Metadata.LastTagTime`, running container's image id):

| Service | Image | LastTagTime | Container image |
|---|---|---|---|
| frontend | `a69cbd04feba` | 2026-10-16T09:39:33Z | `a69cbd04feba` |
| core-java | `670ea6ae50ea` | 2026-10-16T09:39:53Z | `670ea6ae50ea` |
| edge-go | `b1bc2b786d2b` | 2026-10-15T19:52:54Z | `b1bc2b786d2b` |
| mcp-server | `998c3f3f7e22` | 2026-10-15T19:52:23Z | `998c3f3f7e22` |

Both G-37-6 images were tagged at 10:39 BST, before G376_BASE (14:56 BST), so the runtime carried none of 37-56..37-58.

`bash scripts/check-runtime-freshness.sh` **rc 1** (the gate's own fail direction), verbatim summary:

```
  core-java    DRIFT  [image-not-rebuilt]  image tagged 2026-10-16 09:39:53 UTC / newest build-input commit 9d1397c8 (2026-10-16 14:34:22 UTC)
  edge-go      FRESH  image tagged 2026-10-15 19:52:54 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     DRIFT  [image-not-rebuilt]  image tagged 2026-10-16 09:39:33 UTC / newest build-input commit bfab66dd (2026-10-16 14:20:10 UTC)
  mcp-server   FRESH  image tagged 2026-10-15 19:52:23 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
FAIL: 2 of 4 running built service(s) do not match the source tree (0 unverified).
```

Content probes, rc read on the same line:

```
== frontend stylesheet probes (pre-rebuild)
probe (min-width:55rem): rc=1 files=[]
control (min-width:60rem): rc=0 files=[.next/server/chunks/ssr/app_dashboard_staff_page_tsx_0ziuwdl._.js
.next/static/chunks/2uolvmx0bd0tm.css
.next/static/chunks/3-cajwpxn0f0v.js]
== core-java jar probes (pre-rebuild)
unzip -l rc=0 entries=1205
probe Sentences.class count=0
control EmailNotificationService.class count=1
```

## 2. RED on the pre-rebuild runtime (14:52:24Z, the final RED run)

`set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/screens-copy-uat.spec.ts --project=desktop`:
**rc 1, 1 passed, 4 failed, 0 skipped.** Each of the four failed on its gap assertions; none on login, Mailhog, a
fixture or an arm:

```
Error: G-37-6 defect 1, live summary, 768px: every figure on one line inside its own card
  "stat[0] \"£400.48\" leaves its card: text 313..412 vs card 288..388"   (+6 more: 100 px cards, figures 67-99 px wide in a 50 px content box)
Error: G-37-6 defect 1, bound summary, 768px: every figure on one line inside its own card
Error: G-37-6 defect 1, bound summary, 1024px: every figure on one line inside its own card
Error: G-37-6 defect 3 at 375px | 390px | 768px | 1024px | 1280px: every control inside the card at 44 x 44
  "\"Go to first page\" is 32 x 32 px, under 44 x 44"  ... (at 375/390 also "leaves the card", "leaves the viewport")
Error: G-37-6 defect 2 (TypeScript): one full stop after the shop name
  Expected: "These items are prepared by Peckham Jollof Co. Based on what the kitchen has declared, this order contains:"
  Received: "These items are prepared by Peckham Jollof Co.. Based on what the kitchen has declared, this order contains:"
Error: no double full stop in the panel
Error: G-37-6 defect 2 (Java): "for Peckham Jollof Co." then a line break     Expected: true  Received: false
  invite-line: "Admin User has invited you to help run Tenant A on J'Toye as Staff for Peckham Jollof Co.."
Error: no double full stop in the invitation
```

The pre-37-58 frontend carries no `finance-*` test ids; the finance test recorded `"selector":"structural"` (it
found the figures by class), so its RED is about layout, not "no figures found". At 768 the old grid was four columns
of 100 px cards (`md:grid-cols-4` on the viewport). The pagination's 32 px controls fail the 44 px floor at every
width, and at 375/390 the old nine buttons also leave the card and the viewport.

### 2.1 The void dialog (item 4): found clean, branch 2b NOT taken

The first RED run (14:50:26Z) also failed the void-dialog test at all three widths with
`the title text leaves the content box: 35..355 vs 25..365`, where the printed span was INSIDE the box. With both axes
printed, the cause was vertical and an instrument fault:
`x 34.9..355.1 y 258.0..279.0 vs x 25.0..365.0 y 260.0..640.0`. `DialogTitle` is `leading-none`, so the title's text
rect (the font's content area, 21 px for 18 px text) overhangs its 18 px line box by about 2 px into the 24 px
padding. No ink leaves the box. The instrument now measures the title horizontally against the content box (the
defect class: a title running past the edge) and vertically against the dialog's border box. Because the 600 px arm
is caught by the viewport check, a second arm forces an unwrapped title at 390 so the title check proves its own fail
direction. Commit `6ebd7616` (test(37-59)); frontend/e2e is `.dockerignore`'d (frontend/.dockerignore l.76), so it
does not touch an image input.

On the pre-fix runtime the corrected test **passed** at 390, 768 and 1280: `failures [] soft [] descendants 15`, dialog
390 / 448 / 448 px wide, target `ORD-00000000-20261012-41834038` (source `api status COMPLETED`), opener proven (a
list row click opened `/dashboard/orders/{uuid}`), 0 void POSTs. 37-56..37-58 changed only the dialog's copy, not
its geometry, so this old-runtime result is its result. `void-order-dialog.tsx` and its test were left untouched by
37-59.

## 3. Rebuild of every image, recreate, parity

`docker compose -f docker-compose.full-stack.yml build` (14:53:19Z) **rc 0**, then `up -d` **rc 0**: core-java and
frontend were recreated. edge-go and mcp-server were rebuilt to NEW image ids (`88680ff78f6a`, `df5356b3f44e`) but
compose left their containers running on the old ones, and freshness said so:

```
  edge-go      DRIFT  [container-not-recreated]  image tagged 2026-10-16 14:53:22 UTC / newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  mcp-server   DRIFT  [container-not-recreated]  image tagged 2026-10-16 14:53:22 UTC / newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
FAIL: 2 of 4 running built service(s) do not match the source tree (0 unverified).
```

`up -d --force-recreate --no-deps edge-go mcp-server` **rc 0** (the 31.1-30 precedent), then freshness **rc 0**:
`PASS: 4 running built service(s) match the source tree (0 unverified).` After the step-4 threshold raise (section
5) the frontend was rebuilt once more (`build frontend` rc 0, `up -d --no-deps frontend` rc 0). Final freshness
on HEAD `b54daa1e`, **rc 0**:

```
  core-java    FRESH  image tagged 2026-10-16 14:54:15 UTC >= newest build-input commit 9d1397c8 (2026-10-16 14:34:22 UTC)
  edge-go      FRESH  image tagged 2026-10-16 14:53:22 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     FRESH  image tagged 2026-10-16 15:03:13 UTC >= newest build-input commit b54daa1e (2026-10-16 15:02:05 UTC)
  mcp-server   FRESH  image tagged 2026-10-16 14:53:22 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
```

Final identities (15:08:34Z): frontend `46316acb718d` (15:03:13Z), core-java `6adeb37b511b` (14:54:15Z), edge-go
`88680ff78f6a`, mcp-server `df5356b3f44e`; each running container's image id equals its tag's.

Content probes after the first rebuild (37-58's thresholds):

```
probe (min-width:55rem): rc=0 files=[.next/server/chunks/ssr/app_dashboard_finance_page_tsx_095aojv._.js
.next/static/chunks/35ixt6gfk8v1s.js
.next/static/chunks/1rp_m978d9z4b.css]
control (min-width:60rem): rc=0 files=[... .next/static/chunks/1rp_m978d9z4b.css]
container rules in the served CSS: 19rem 21rem 26rem 27rem 52rem 55rem (one each)
unzip -l rc=0 entries=1206
probe Sentences.class count=1
control EmailNotificationService.class count=1
```

After the threshold raise the plan's literal `(min-width:55rem)` is superseded by 57rem, so both are recorded, read
from the served stylesheet (`.next/static/chunks/*.css`) of container image `46316acb718d`:

```
served CSS (min-width:57rem): rc=0 [.next/static/chunks/3xej_85jh_n-1.css]
served CSS (min-width:28rem): rc=0 [.next/static/chunks/3xej_85jh_n-1.css]
served CSS (min-width:54rem): rc=0 [.next/static/chunks/3xej_85jh_n-1.css]
served CSS (min-width:55rem): rc=1 []
served CSS (min-width:60rem): rc=0 [.next/static/chunks/3xej_85jh_n-1.css]
all container conditions: 19rem 21rem 23rem 27rem 28rem 54rem 57rem 60rem
```

So: freshness 1 then 0; the finance container rule absent before (rc 1) and present after (rc 0); the jar class 0 then 1;
both controls unchanged.

## 4. GREEN on the rebuilt runtime

First GREEN run (14:56:03Z): **4 passed, 1 failed.** Every gap assertion passed. What failed was the finance
INSTRUMENT ARM: `the box checker must catch the old four-column grid at 768`, `Received: 0`. This was an instrument
bug. `measureFinance` sets the `data-e2e-stat-grid` marker only at its end, and the arm loads a fresh page first, so the
forced rule matched nothing while the boxes were measured. The column count is read after the marker lands, so it
showed 4. On the pre-fix runtime the arm "fired" only because the old grid was natively four columns at 768, so it had
been vacuous there too. The RED failures in section 2 do not depend on the arm. Fix: one throwaway measurement
marks the grid before the style is injected, and the arm asserts exactly one grid was marked (`1debebf8`). Re-run: the arm
reports `stat[0..3] spans 4 / 4 / 5 / 4 lines`.

The final GREEN, the plan's verify line verbatim (15:03:45Z to 15:05:04Z), on the final runtime above:
`npx playwright test e2e/screens-copy-uat.spec.ts e2e/staff-webhooks-uat.spec.ts e2e/staff-invite.spec.ts
e2e/basket-revalidation.spec.ts --project=desktop` → **rc 0, `11 passed (1.3m)`**, stats
`{"expected":11,"skipped":0,"unexpected":0,"flaky":0}`. The five screens-copy-uat tests are all ✓.

### 4.1 Instrument arms (each ran in every run and failed as designed)

| Test | Arm | Failed-as-designed output (GREEN) |
|---|---|---|
| 1 finance | old four equal columns forced at 768, bound figures | `columns 4`; `stat[0] "£999,999.99" spans 4 lines`, `stat[1]` 4, `stat[2] "−£999,999.99" spans 5 lines`, `stat[3]` 4 |
| 2 pagination | nine 32 px buttons, compact status hidden, unwrapped, at 390 | `"Go to first page" is 32 x 32 px, under 44 x 44` ... `"Go to page 5" leaves the card: 361..393 vs 16..374`, `leaves the viewport: 361..393 vs 0..390`, `"Go to next page" leaves the card: 397..429` ... |
| 3 void dialog | 600 px min-width at 390 | `the dialog leaves the viewport: -105..495 x 254..646 vs 390 x 900` |
| 3 void dialog (added) | unwrapped title at 390 | `the dialog scrolls sideways: 436 > 388`, `h2 "Void order ORD-00000000-20261012-4183403" leaves the dialog sideways: 25..437 vs 0..390` ... and the asserted `the title text leaves the content box` line |
| 4 checkout copy | planted `prepared by Peckham Jollof Co.. Based` | `[" prepared by Peckham Jollof Co.. Based on "]` |
| 5 invitation email | planted `as Staff for Peckham Jollof Co..` + line break | `["as Staff for Peckham Jollof Co..\nAccept"]` |

The same arms in the final RED run: finance (bound, natively four columns) failures listed; pagination the same 32 px
list; void `the dialog leaves the viewport: -105..495 ...` and the title arm `the dialog scrolls sideways: 436 > 388 ...`;
both copy arms identical. Every arm failed as designed in both runs.

### 4.2 Per-width record (GREEN)

Finance, live summary (`£400.48 / £0.00 / £400.48 / £66.66`, one VAT row) and bound summary (`£999,999.99 ×2,
−£999,999.99, £999,999.99`; four VAT rows), `selector testid`, every `boxFailures/overlaps/bannerFailures` empty:

| Viewport | Stat container | Columns | Card width | VAT container |
|---|---|---|---|---|
| 390 | 358 | 1 | 358 | 308 |
| 768 | 448 | 2 | 216 | 398 |
| 1024 | 704 | 2 | 344 | 654 |
| 1280 | 960 | 4 | 228 | 910 |

RED for comparison (pre-fix, `selector structural`): 768 → 4 columns of 100 px; 1024 → 4 of 164 px (bound figures fail).

Orders pagination (GREEN, every `failures` empty):

| Viewport | Root (container) | Visible controls | Nav max-content |
|---|---|---|---|
| 375 | 293 | previous, next | 188 |
| 390 | 308 | first, previous, next, last | 284 |
| 768 | 398 | first, previous, pages 1-3, next, last | 332 |
| 1024 | 654 | first, previous, pages 1-5, next, last | 428 |
| 1280 | 910 | first, previous, pages 1-5, next, last | 428 |

At 1280 `pagination-window-1280 {"pages":5,"current":1}`. At 390 "Go to next page" showed "Showing 21-40" and
"Go to first page" returned "Showing 1-20" (hard assertions, passed).

Void dialog (GREEN): 390 → 390 px, 768 → 448 px, 1280 → 448 px; `failures []`, `soft []` (the title clears the Close
control) at each; Keep order closed it each time; `no void request was ever sent` → `[]`.

Copy (GREEN): `checkout-intro` = `These items are prepared by Peckham Jollof Co. Based on what the kitchen has declared,
this order contains:` (shop name read from `/api/v1/public/shops/peckham-jollof-co` = `Peckham Jollof Co.`, product
`Grilled Tilapia`, basket key removed, 0 guest-order POSTs). `invite-line` = `Admin User has invited you to help run
Tenant A on J'Toye as Staff for Peckham Jollof Co.` then a line break; subject `Admin User invited you to Tenant A on
J'Toye`.

## 5. Thresholds: the finance grids were RAISED (step 4 taken); pagination held

Measured need at the declared bound (Chrome, rebuilt runtime): the widest figure `−£999,999.99` is **164 px**
max-content at text-2xl bold tabular, so a stat card needs 164 + 48 padding + 2 border = **214 px**, and a VAT
tile (badge + gap + figure + padding + border) needs **205 px**. As a container width:

| Grid | Tier | Need | In rem | 37-58 threshold | Raised to | Margin after |
|---|---|---|---|---|---|---|
| stat (gap 16) | 2 columns | 2 × 214 + 16 = 444 px | 27.75 | 27rem (432) | **28rem** (448) | card 216 vs 214: 2 px |
| stat (gap 16) | 4 columns | 4 × 214 + 48 = 904 px | 56.5 | 55rem (880) | **57rem** (912) | card 216 vs 214: 2 px |
| VAT (gap 12) | 2 columns | 2 × 205 + 12 = 422 px | 26.375 | 26rem (416) | **27rem** (432) | tile 210 vs 205: 5 px |
| VAT (gap 12) | 4 columns | 4 × 205 + 36 = 856 px | 53.5 | 52rem (832) | **54rem** (864) | tile 207 vs 205: 2 px |

**Why:** the four plan widths never put a container on a threshold, so a threshold below the need passed them all.
The measurement that triggered the raise is the one the plan names: "a test fails only at a width next to a
threshold". Test 1 now steers each grid's container to exactly each of its own @container thresholds, read from the
stylesheet, and runs the box checker with the bound figures. On the 37-58 build (before the raise) all four failed:

```
finance-edge-vat-416  (26rem) viewport 786  container 416 columns 2  ["vat[0] \"£999,999.99\" spans 2 lines"]
finance-edge-stat-432 (27rem) viewport 464  container 432 columns 2  ["stat[2] \"−£999,999.99\" spans 2 lines"]
finance-edge-vat-832  (52rem) viewport 1202 container 832 columns 4  ["stat[2] \"−£999,999.99\" spans 2 lines","vat[0] \"£999,999.99\" spans 2 lines"]
finance-edge-stat-880 (55rem) viewport 1200 container 880 columns 4  ["stat[2] \"−£999,999.99\" spans 2 lines"]
```

After the raise (final GREEN) all four hold:

```
finance-edge-vat-432  (27rem) viewport 802  container 432 columns 2 cards 233  boxFailures []
finance-edge-stat-448 (28rem) viewport 768  container 448 columns 2 cards 216  boxFailures []
finance-edge-vat-864  (54rem) viewport 1234 container 864 columns 4 cards 217  boxFailures []
finance-edge-stat-912 (57rem) viewport 1232 container 912 columns 4 cards 216  boxFailures []
```

The raise, TDD order: the jest literals in `finance-scope.test.tsx` first → `Tests: 2 failed, 21 passed` (the two grid
tests); then `page.tsx` (both class literals and both derivation comments, now stating the measured numbers) →
`Tests: 23 passed`, jest rc 0. No 26/27/52/55rem finance literal remains except the new VAT 27rem and the comments'
history. 37-58's Task 1 verify, re-run: `npm --prefix frontend test -- app/dashboard/finance/__tests__` rc 0;
`npm run build` rc 0 with `(min-width:57rem)` and `(min-width:28rem)` in `.next/static/chunks/3xej_85jh_n-1.css`
(37-58: the planned `.next/static/css` path does not exist on Next 16); tsc rc 0; lint rc 0 (0 errors, 29
pre-existing warnings). Because Tailwind also scans `__tests__`, the falsifiable form is the per-file emission probe
(`evidence/37-58-tw-emits.js`, page.tsx alone): 28 / 57 / 27 / 54rem `EMITTED` rc 0; 55 / 26 / 52rem `MISSING` rc 1.
Commits: `1debebf8` test(37-59) (the arm fix and the edge measurement), `b54daa1e` fix(37-59) (the raise). Then the
frontend was rebuilt and freshness re-run (section 3), and GREEN re-run (section 4).

Pagination **held**, nav max-content in each tier against its condition (read from the served CSS): 19rem tier
(first/previous/next/last) 284 px vs 304 px, margin 20; 21rem tier (three pages) 332 vs 336, margin 4; 27rem
tier (five pages) 428 vs 432, margin 4. The box checker passed at all five widths.

## 6. The goods, on the same runtime

- Desktop: the plan's line above, `11 passed`, 0 skipped: staff-webhooks-uat 3/3, staff-invite 1/1,
  basket-revalidation 2/2 (incl. its INSTRUMENT test), screens-copy-uat 5/5.
- `npx playwright test e2e/dashboard-mobile.spec.ts` (both projects, 15:05:22Z to 15:08:10Z): **rc 0, `48 passed
  (2.8m)`**, 24 `[desktop]` + 24 `[mobile]`, stats `{"expected":48,"skipped":0,"unexpected":0,"flaky":0}`.
- `frontend/e2e/staff-invite.spec.ts` and `frontend/e2e/helpers/staff-invitee.ts` are unchanged against origin/main
  (`git diff --quiet origin/main -- <each>`, re-run after the GREEN: rc 0 0). `rg -uu -n -i authorization` on the spec
  names it on l.48 (header prose), l.826 (comment), l.833 (the local variable), l.847 and l.850 (the guarded
  `request.get` headers) and nowhere else: never annotated, attached, logged or written.

## 7. Open for the owner

1. **UAT re-run:** `/gsd-verify-work 37`, test 6, on this runtime (the plan's human check): finance at about 768 px with
   the sidebar; orders pagination at 390; the void dialog on a completed order at 390/768/1280 (open it, press Keep
   order); Peckham Jollof Co.'s checkout allergen panel and a Mailhog invitation for that shop (one full stop after
   the name); and **NoAccessPage at 390/768/1280**, which is not a G-37-6 defect but is on test 6's list and no
   instrument here measures it.
2. **The finance margins are 2 px at three of the four tiers.** They follow the plan's rule (next whole rem above the
   measured need). A font-metric change, or a figure wider than the declared bound, wraps inside its own card
   (`[overflow-wrap:anywhere]`) instead of overlapping. The edge measurement in test 1 reds the next time a threshold
   sits below the need.

## 8. Residue on the dev stack from this plan

- Invitations: test 5 ran in 5 full runs (two RED, two GREEN, the plan's line). Each created one Staff invitation at
  Peckham Jollof Co. (`g376-<ms>@example.test`) and cancelled it, hard-asserting that the row left the list. No run
  recorded a `fixture-cleanup` annotation. 5 Mailhog messages to those `example.test` addresses remain in Mailhog.
- Baskets: test 4 ran in the same 5 runs, in a fresh anonymous context each time; every run recorded
  `basket key removed`. No order was placed (0 guest-order POSTs, asserted).
- Orders: nothing voided (0 void POSTs, asserted in every run of test 3). basket-revalidation places and cancels
  its own cash orders at Peckham Jollof Co. (its own design, unchanged).
- Images: `docker compose build` replaced every built image. The old image ids above are dangling, not removed.

## 9. Secrets

No token, password or client secret is in this file or any commit. Test 3 copies the page's Authorization header
into one local variable passed only to `request.get` (the orders-by-status reads). The vendor password lived only in
the sourced environment of each run.

---

## 10. Docs and gates (Task 4 step 1, integration branch)

| Gate | Before | After |
|---|---|---|
| `bash scripts/docs-freshness.sh` (check) | **rc 1**: `docs/metrics.json != source reality` | `--write` rc 0, then the check **rc 0** |
| `bash scripts/check-doc-metrics.sh` | after `--write`, before the prose: **rc 1**, 22 FAIL lines (README.md ×8, CLAUDE.md ×7, AGENTS.md ×7), e.g. `README.md [total_logical_invocations]: doc says 6148, docs/metrics.json says 6208` | **rc 0**: `PASS: all 37 prose metric claim(s) across 3 doc(s) match docs/metrics.json.` |
| `bash scripts/check-e2e-typecheck.sh` | — | **rc 0**: `PASS: 52 e2e file(s) type-check clean.` |
| `bash scripts/check-doc-citations.sh` | — | first run, plain shell: **rc 0** `PASS: all 29 verified citation(s) ... (7 uncheckable)`, but **VOID-masked**: it listed `docs/ops/terminal-states.yaml citations=0` because the YAML extraction's bare `python3 -c` is refused by this machine's python shim with no env active, and the pre-#926 script swallowed that `void`. After the merge of origin/main (#926, which makes that void terminate the gate) the plain shell gives **rc 2** `VOID: cannot extract YAML citations from docs/ops/terminal-states.yaml`. In the project env (`conda activate jtoye-ops`, PyYAML 6.0.3): **rc 0**, `terminal-states.yaml citations=17`, `total=53 verified=46 uncheckable=7 violations 0`. No citation needed re-pointing |
| the plan's first Task 4 `<automated>` line | — | `rc=0 0 0 0` |
| frontend (excl. e2e) and core-java source since the last commit whose own verification ran (`b54daa1e`, the step-4 raise) | — | `git diff --stat b54daa1e HEAD -- frontend ':(exclude)frontend/e2e' core-java`: **empty**. Fail direction: from 37-58's last commit `bfab66dd` the same diff names 9 files (37-57's seven Java files, which landed after 37-58, and the two finance files of the raise). Nothing that jest, tsc, lint, build or the Gradle tests read has changed since each was last run green |

`docs/metrics.json`: `java_test_methods` 2815 → 2826, `java_test_files` 402 → 403, `jest_blocks` 2967 → 3011,
`jest_files` 250 → 253, `playwright_blocks` 184 → 189, `playwright_specs` 44 → 45, `total_logical_invocations`
6148 → 6208. This regeneration counts the whole integration tree, including the unmerged light-theme quick task.
The PR branch regenerates its own counts on its own tree (section 12). The prose in README.md (badge, the four
suite lines, total), CLAUDE.md and AGENTS.md (the Testing constraint) follows.

## 11. Tag, path set, range checks (Task 4 step 2)

The tag `cut/phase-37-uat-g376` was placed on the integration HEAD after the section-10 docs commit and BEFORE any
fetch or merge. The G-37-6 path set (`git diff --name-only G376_BASE cut/phase-37-uat-g376`, excluding `.planning`,
`HANDOFF.md`, `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`) has **25 paths**, written to
`evidence/37-59-g376-paths.txt`: 7 core-java (Sentences, its test, the two notification classes and their three
tests), 17 frontend source and test files from 37-56 and 37-58, and `frontend/e2e/screens-copy-uat.spec.ts`.

- Every set path is in the files_modified of 37-56..37-59 (37-59's taken conditionals: finance `page.tsx` and
  `finance-scope.test.tsx`), so the set has no path outside them. Every non-.planning path of 37-56, 37-57 and 37-58,
  plus the spec, is in the set (25 required, 0 missing). Fail direction: with `frontend/hooks/use-theme.ts` (a
  light-theme path) planted, the outside check names it.
- **(i)** `git log --format=%s G376_BASE`: 1567 subjects, gap-scoped (37-56..37-59) count **0**. Control: (37-55)
  count 3.
- **(ii)** `git log --no-merges G376_BASE..TAG -- <set>`: **20 commits**, every one scoped 37-56 (7), 37-57 (5),
  37-58 (4) or 37-59 (4); unscoped `[]`. Fail direction: with the accepted scopes narrowed to `(37-56)` alone, the
  same list flags the 13 commits scoped 37-57, 37-58 and 37-59.
- **(iii)** merges in G376_BASE..TAG: **0**. (iii) had nothing to judge.

## 12. origin/main moved; merge, rebuild, goods (Task 4 step 3)

`git fetch origin` rc 0. `check-branch-behind-base.sh` on the integration branch: **rc 1**, `FAIL: HEAD is 1
commit(s) behind origin/main (and 595 ahead).` The new commit is `b43d1ae9` (#926, phase-39 planning, horizon
gate, `scripts/check-doc-citations.sh` and `k8s/scripts/check-env-contract.sh` hardening). Its first-parent diff
over every image build input (`frontend core-java gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts`)
is empty. Merged into `phase-37-ops-readiness` (no conflict, the tag unmoved). Then behind-base **rc 0**: `PASS:
HEAD contains every commit on origin/main (b43d1ae9); it is 596 commit(s) ahead and 0 behind.`

Freshness after the merge: **rc 1**, core-java and frontend `DRIFT [image-not-rebuilt]` against the merge commit
itself. The gate uses `--full-history` by design (its l.49-61), so a merge is the bar even when it changes nothing in
that image's inputs. It over-reports here and cannot under-report. The plan's rule still applies: rebuild until rc 0.
`build` and `up -d` rc 0 (frontend recreated). Then freshness rc 1 again, with core-java, edge-go and mcp-server
`DRIFT [container-not-recreated]`. `up -d --force-recreate --no-deps core-java edge-go mcp-server` rc 0, then
**rc 0**:

```
  HEAD         : db2887ca (phase-37-ops-readiness)
  core-java    FRESH  image tagged 2026-10-16 15:12:10 UTC >= newest build-input commit db2887ca (2026-10-16 15:11:42 UTC)
  edge-go      FRESH  image tagged 2026-10-16 15:12:11 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     FRESH  image tagged 2026-10-16 15:12:36 UTC >= newest build-input commit db2887ca (2026-10-16 15:11:42 UTC)
  mcp-server   FRESH  image tagged 2026-10-16 15:12:11 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
```

Identities (15:13:50Z), each container on its tag's image: frontend `9d00d7480b92`, core-java `f4d32b1add5c`, edge-go
`603b533dc57e`, mcp-server `9ac028f818a8`. Content: `Sentences.class` count 1 (control 1); served CSS
`(min-width:57rem)` rc 0 in `.next/static/chunks/3xej_85jh_n-1.css`, the same content-hashed chunk name as before
the merge, which corroborates that no frontend input changed.

Goods on the post-merge runtime:

- `dashboard-mobile.spec.ts`, both projects (15:15:15Z): **rc 0, 48 passed** (24 + 24), 0 skipped.
- The plan's combined desktop line, run three times: **rc 1 each**. 15:14Z: staff-invite failed, a People row missing
  after 15 s. 15:17Z: staff-webhooks-uat G-37-13/15 and G-37-14 failed, the page showing `Couldn't load staff: The
  server is busy right now` and the admin's own `No shop access` banner. 15:20Z: staff-invite again. core-java logged
  `RateLimitInterceptor - Rate limit exceeded for tenant 00000000-0000-0000-0000-000000000001` exactly then: 17 at
  15:14, 1 at 15:15, 7 at 15:16, 9 at 15:17, 13 at 15:18, and 12 inside the 15:20 run (6 each at 15:20:36 and 15:20:54).
  The dev stack keeps the per-tenant bucket at `RATE_LIMIT_PER_MINUTE` 100 (docker-compose.full-stack.yml l.282-287:
  only the nightly sizes it for a whole suite as one tenant). screens-copy-uat's authenticated page loads, directly
  before staff-invite and staff-webhooks-uat, run it dry. The same line passed once (15:03Z, section 4) on a bucket
  that happened to be fuller. Each failure is a 429 at the tenant limiter, not a product change: none of the three
  specs touches a G-37-6 path except the Staff page's remove-access sentence, and staff-invite passed twice alone in
  between (6.8 s each).
- Each spec alone, on a rested bucket, with core-java's rate-limit events counted over the run:

| Spec | Start | rc | Result | Rate-limit events |
|---|---|---|---|---|
| screens-copy-uat | 15:24:46Z | 0 | 5 passed, 0 skipped | 0 |
| staff-webhooks-uat | 15:25:10Z (1 s after the previous, bucket drained) | 1 | 2 passed, 1 failed | 16 |
| staff-invite | 15:27:01Z | 0 | 1 passed | 0 |
| basket-revalidation | 15:27:09Z | 0 | 2 passed | 0 |
| staff-webhooks-uat | 15:28:38Z (after 70 s idle) | 0 | 3 passed, 0 skipped | 0 |

The staff-webhooks-uat row with 16 events is the fail direction of the same explanation: started on a drained bucket,
the failure appears with the events; started on a full bucket, it does not. Logged in deferred-items.md (the plan's
combined verify line is not runnable on the dev stack's default tenant bucket).

Docs gates on the merged tree: docs-freshness rc 0, check-doc-metrics rc 0, e2e typecheck rc 0, doc citations rc 0
in the jtoye-ops env (section 10). The merge changed no test count.

## 13. The PR branch (D-03), Task 4 steps 4 and 5

`phase-37-uat-g376`, made with `git switch -c phase-37-uat-g376 origin/main` in the main checkout (origin/main =
`b43d1ae9`, #926). The upstream that `switch -c` set to origin/main was removed (`git branch --unset-upstream`), so
the branch cannot be pushed onto main by accident. Then, for each of the 25 set paths in order,
`git diff --binary G376_BASE cut/phase-37-uat-g376 -- <p>` was applied with `git apply --index` (no `--3way`, full
context): **25 of 25 applied cleanly**, no stop.

Per path, the blob ids at G376_BASE, at origin/main and at the tag (10 hex characters), the case, and the two assertions.
(a) is the added and removed lines of `git diff --cached origin/main -- <p>` against those of the patch, both diffs
run with `--output-indicator-new='>' --output-indicator-old='<'`. (b) is the staged blob against the tag's blob, where
the case is "same base":

| Path | G376_BASE | origin/main | tag | Case | (a) | (b) |
|---|---|---|---|---|---|---|
| `core-java/src/main/java/uk/jtoye/core/common/Sentences.java` | absent | absent | 6b3c03beaa | same base | PASS | PASS |
| `core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java` | 5f7dc83ca7 | 5f7dc83ca7 | e2d6d891ff | same base | PASS | PASS |
| `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java` | c12beea90d | c12beea90d | 6a44ddc256 | same base | PASS | PASS |
| `core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java` | absent | absent | 7c1a884b0b | same base | PASS | PASS |
| `core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceStaffInviteTest.java` | 4e01612dfa | 4e01612dfa | 127f3ac6c2 | same base | PASS | PASS |
| `core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceTest.java` | f47c842b56 | f47c842b56 | f4bc88a3a7 | same base | PASS | PASS |
| `core-java/src/test/java/uk/jtoye/core/notification/template/EmailTemplateRendererTest.java` | 51a65eb73d | 51a65eb73d | 3d858e70ec | same base | PASS | PASS |
| `frontend/__tests__/sentence-end-call-sites.test.ts` | absent | absent | 5c7aa78ce6 | same base | PASS | PASS |
| `frontend/app/dashboard/__tests__/staff-page.test.tsx` | 7b69119557 | 7b69119557 | 75f59d09ef | same base | PASS | PASS |
| `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx` | 5bb9c73c41 | 5bb9c73c41 | 4db2c6ebca | same base | PASS | PASS |
| `frontend/app/dashboard/finance/page.tsx` | a11c456cb3 | a11c456cb3 | 634c059dff | same base | PASS | PASS |
| `frontend/app/dashboard/products/__tests__/import-copy.test.tsx` | 7c6cac0d15 | 7c6cac0d15 | c2699bc1ba | same base | PASS | PASS |
| `frontend/app/dashboard/products/page.tsx` | 041637d914 | 041637d914 | 22c9075b35 | same base | PASS | PASS |
| `frontend/app/dashboard/staff/page.tsx` | 09e4c7ca14 | 09e4c7ca14 | 8992bb59bc | same base | PASS | PASS |
| `frontend/components/dashboard/orders/void-order-dialog.tsx` | 8f9fbd477a | 8f9fbd477a | d25991b428 | same base | PASS | PASS |
| `frontend/components/dashboard/products/copy-to-shop-dialog.tsx` | d11655cc0e | d11655cc0e | 86abc79c6d | same base | PASS | PASS |
| `frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx` | c7add02ade | c7add02ade | 4902c8faf2 | same base | PASS | PASS |
| `frontend/components/storefront/order-allergen-panel.tsx` | df2f731e36 | df2f731e36 | 302326994a | same base | PASS | PASS |
| `frontend/components/storefront/recorded-allergen-set.tsx` | 6da50fb3ab | 6da50fb3ab | ea3495c4a1 | same base | PASS | PASS |
| `frontend/components/ui/__tests__/pagination.test.tsx` | absent | absent | a070f4d707 | same base | PASS | PASS |
| `frontend/components/ui/pagination.tsx` | bdd9327ed0 | bdd9327ed0 | 8c5593008b | same base | PASS | PASS |
| `frontend/e2e/screens-copy-uat.spec.ts` | absent | absent | 921bb64063 | same base | PASS | PASS |
| `frontend/lib/__tests__/sentence-end.test.ts` | absent | absent | cf2f614787 | same base | PASS | PASS |
| `frontend/lib/joined-toast.ts` | d61197f268 | d61197f268 | f8c4ff49e6 | same base | PASS | PASS |
| `frontend/lib/sentence-end.ts` | absent | absent | f4e5153a01 | same base | PASS | PASS |

**All 25 are "same base"**: G376_BASE and origin/main held the same blob for every set path (or neither had it), so
for every path the staged content is exactly the tag's and no foreign hunk could ride along.

On the branch: `docs-freshness.sh` (check) **rc 1** before `--write`, then rc 0. `check-doc-metrics.sh` after
`--write` and before the prose: **rc 1**, 22 FAIL lines (e.g. `README.md [total_logical_invocations]: doc says 6138,
docs/metrics.json says 6198`). After the prose: **rc 0**, and docs-freshness rc 0. This tree's own counts are total 6138 →
**6198**, Java 2815/402 → 2826/403, Jest 2959/250 → 3003/253, Playwright 182/43 → 187/44 (origin/main lacks
the light-theme quick task, so its numbers differ from the integration branch's 6208 by exactly that change).
`(cd frontend && npx tsc --noEmit)` **rc 0**. Full jest (a superset of every suite 37-56 and 37-58 name; 37-56
itself ran full jest): **rc 0, `Test Suites: 253 passed`, `Tests: 3003 passed`**, which equals this tree's
`jest_files` 253 and `jest_blocks` 3003. `./gradlew :core-java:cleanTest :core-java:test --tests
'uk.jtoye.core.common.*' --tests 'uk.jtoye.core.notification.*' --no-daemon` **rc 0**, `> Task :core-java:test`
executed (compile UP-TO-DATE is correct: core-java is identical between the tag and this branch), 18 fresh
reports in `core-java/build-local/test-results/test`, **146 tests, 0 failures, 0 errors, 0 skipped** (SentencesTest 8,
EmailNotificationServiceTest 29, EmailNotificationServiceStaffInviteTest 7, EmailTemplateRendererTest 17, …).

ONE commit, message from a file, subject `fix(37): UAT gap G-37-6 (finance figures, pagination, one sentence-end
rule per language, void dialog proven)`, no attribution lines. `gitleaks protect --staged`: no leaks found. Then:

- `git rev-list --count origin/main..phase-37-uat-g376` = **1**.
- `check-branch-behind-base.sh` on the PR branch: **rc 0**, `PASS: HEAD contains every commit on origin/main
  (b43d1ae9); it is 1 commit(s) ahead and 0 behind.` On the integration branch (section 12): rc 0, 596 ahead and 0 behind.
- `git diff --name-only origin/main phase-37-uat-g376` = the 25-path set plus `docs/metrics.json`, `README.md`,
  `CLAUDE.md`, `AGENTS.md`: `diff` against that expected list **rc 0, empty**, 29 names.
- The plan's third verify (assertion (a) on the COMMITTED branch, origin/main against `phase-37-uat-g376`, each path
  against its `G376_BASE..cut/phase-37-uat-g376` patch): **`paths=25 bad=0 void=0`, rc 0**. Fail direction: a
  throwaway branch `scratch-37-59-arm`, made from `phase-37-uat-g376` by plumbing (a temporary index and
  `commit-tree`, with the working tree and HEAD untouched), had one planted line `// PLANTED 37-59 arm line`
  committed to `frontend/lib/sentence-end.ts`. The same loop with it in place printed `DIFFERS:
  frontend/lib/sentence-end.ts`, `paths=25 bad=1 void=0`, **rc 1**. It was deleted (`git branch -D`), and
  `git rev-parse -q --verify refs/heads/scratch-37-59-arm` gives rc 1 (absent). The loop re-run on
  `phase-37-uat-g376` is clean: `paths=25 bad=0 void=0`.

**Light-theme case: origin/main does NOT have it, so this cut excludes it by design.** `git diff --name-only origin/main
cut/phase-37-uat-g376` (excluding .planning and HANDOFF.md) is 40 names, not the set plus the metric files. Beyond
those it names the light-theme quick task 261016-edv (`6f7da333` and its follow-ups): `frontend/hooks/use-theme.ts`,
`frontend/hooks/__tests__/use-theme.test.tsx`, `frontend/components/dashboard/sidebar.tsx`,
`frontend/components/dashboard/mobile-tab-bar.tsx`,
`frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx`, `frontend/e2e/dashboard-forced-light.spec.ts`,
`frontend/e2e/staff-webhooks-uat.spec.ts`. All 7 differ between origin/main and the tag, and none of them differs between
origin/main and `phase-37-uat-g376` (`git diff --name-only` empty). It also names #926's four files
(`docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md`, `infra/dependency-horizons.yaml`,
`k8s/scripts/check-env-contract.sh`, `scripts/check-doc-citations.sh`). These differ only because the tag predates the
merge of #926, and the PR branch carries main's versions.

Step 5: the main checkout switched back to `phase-37-ops-readiness`; `git fetch origin` rc 0 (main still `b43d1ae9`);
`git merge origin/main`: `Already up-to-date`. `git ls-remote --heads origin phase-37-uat-g376`: 0 lines, **not
pushed**. The owner opens the PR in the ship shape (push first, review second) and runs its D3 review series. The
`docs/CHANGELOG.md` entry citing the PR number is added on that branch. Whichever of this PR and the light-theme PR
merges second must regenerate `docs/metrics.json` (the docs-freshness CI gate will say so). The cut's own sha is
recorded in `37-59-SUMMARY.md`, not here, because recording it here would falsify this file.
