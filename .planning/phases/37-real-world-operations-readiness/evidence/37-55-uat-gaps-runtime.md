# 37-55: UAT gaps G-37-12..G-37-15, proven on the delivered runtime

Plan: `.planning/phases/37-real-world-operations-readiness/37-55-PLAN.md`
Integration branch: `phase-37-ops-readiness`. Plan head before: `1d249640` (`.git/gsd-plan-head-before-37-55`).
Task 1 (the instrument, `df42be1f`): `frontend/e2e/staff-webhooks-uat.spec.ts` and `frontend/e2e/helpers/staff-invitee.ts`.
PR branch: `phase-37-uat-gaps`, cut from origin/main as one squash. Tag: `cut/phase-37-uat-gaps`. Not pushed.

**Owner answer to the Task 2 checkpoint (2026-10-15), through the orchestrator:** "proceed". The "do not touch the
shared compose stack" line was lifted for Task 3 only. The continuation started at 2026-10-15T19:49:32Z on HEAD
`df42be1f` with a clean tree, from the main checkout root (`root-pin.sh` rc 0). Compose XOR k8s holds
(`minikube status -p jtoye`: no `jtoye` container). Nothing was installed (T-37-SC). The invitee passwords lived
only in the helper's memory; none is in this file, an annotation, a log or a commit (T-37-127).

---

## 1. Pre-rebuild runtime (19:49:37Z, before any write)

| Service | Tag = running image id | `.Metadata.LastTagTime` | Container started |
|---|---|---|---|
| core-java | 4aa1f4e41742 | 2026-10-15 10:00:01 UTC | 11:58:40Z |
| frontend | 9cf245d3d468 | 2026-10-15 09:59:16 UTC | 11:58:40Z |
| edge-go | 7f0dd3615391 | 2026-10-14 21:37:32 UTC | 11:58:41Z |
| mcp-server | 9c4b57f7288d | 2026-10-14 21:37:13 UTC | 11:58:41Z |

`bash scripts/check-runtime-freshness.sh` → **rc 1** (the parity gate's own fail direction), verbatim:

```
  frontend     DRIFT  [image-not-rebuilt]  image tagged 2026-10-15 09:59:16 UTC / newest build-input commit f199e97f (2026-10-15 19:11:33 UTC)
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
```

core-java, edge-go and mcp-server were FRESH; `f199e97f` is 37-54's G-37-14 commit.

Bundle grep inside the running frontend
(`docker compose -f docker-compose.full-stack.yml exec -T frontend sh -c 'grep -rlF "Managing webhook endpoints" /app/.next; echo rc=$?'`):
**rc 1, 0 files**. The phrase is the unique new refusal body (`frontend/lib/webhooks-api.ts:157`). Control: "Pending
invitations" is in 2 files, so the grep can see the bundle.

## 2. RED on the pre-rebuild runtime (19:49:48Z to 19:50:32Z)

`(set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/staff-webhooks-uat.spec.ts --project=desktop)`:
**rc 1, 3 failed, 0 passed, 0 skipped.** Each test failed on its own gap assertion. None failed on login, Mailhog,
fixture creation or an instrument arm. All arms failed as designed in this run too, and so did every non-vacuity
control (403, selects = 5, texts = 15, options >= 2, the fixture row present).

| Test | RED failure line(s) | Non-vacuity controls that passed in the same run |
|---|---|---|
| 1 · G-37-12 | `expect(locator).toBeVisible() failed — getByRole('heading', { name: 'Group admin access required' }) — element(s) not found` (spec l.61, in `expectRefusal(invitee.page)`) | the Staff user's GET `/api/v1/webhooks` answered **403** `https://jtoye.uk/errors/shop-access-denied`. The page snapshot of the invitee (`staff-1792093790496@example.test`) shows `heading "No webhook endpoints yet"` and **two** `button "Add endpoint"`: the API refused, and the old page hid the refusal |
| 2 · G-37-13 (soft) | `G-37-13: every control and text in the two cards at 4.5:1 (dark)`: **13** failures, listed below | html.dark present; an empty email made 0 POSTs; selects 5, texts 15 |
| 2 · G-37-15 (soft, separate) | `G-37-15: no picker option leaks null/undefined/()`: `["integration-orders-rw (null)", "integration-catalog-ro (null)"]` | 15 options |
| 3 · G-37-14 (soft) | `G-37-14 at 768px`: 30 lines. `token "p1792093820809@example.test" spans 7 lines`, `token "manager" spans 2 lines`, `token "Unsorted" spans 2 lines`, every row's two actions `leaves the scroll region` / `leaves the card`, `scroll region scrolls sideways: scrollWidth 630 > clientWidth 398`. `a stacked row names its expiry at 768`: received `… 18 Oct, 20:50 …` with no "Expires". `G-37-14 at 1024px`: the email spans 6 lines, "Unsorted" 2. `G-37-14 at 1280px`: the email spans 2 lines | the fixture row present; 390 px clean (the old `sm:` stack) |

The 13 dark failures (RED), computed colour against the first opaque ancestor background:
the four labels Email, Role, Shop, Team member and the second Shop and Role (`rgb(51,65,85)` on `rgb(2,8,23)`,
**1.93**); the email error `rgb(185,28,28)`, **3.09**; the Group-admin hint, **2.64**; the four enabled selects
`#invite-role`, `#staff-user`, `#staff-shop`, `#staff-role` (`rgb(248,250,252)` on **`rgb(255,255,255)`**, **1.05**:
light text on a white control, the UAT 13 observation); the Grant card's footnote, **4.2**. Light theme in the same
run: **0** failures (selects 5, texts 15).

The fixture (`p1792093820809@example.test`) was cancelled by the test at 768. No `fixture-cleanup` annotation was
needed.

## 3. Rebuild of every image, recreate, parity

- **Build** `docker compose -f docker-compose.full-stack.yml build --progress plain` on the committed tree
  `df42be1f` (clean), 19:51:32Z to 19:52:01Z, rc 0. The frontend ran `npm run build` (`✓ Compiled successfully in
  7.7s`). core-java, edge-go and mcp-server were fully cached, so they were rebuilt `--no-cache` (19:52:08Z to
  19:54:01Z, rc 0: `> Task :core-java:bootJar`, `BUILD SUCCESSFUL in 28s`; `go mod download && go mod verify`,
  `go build`; mcp-server `npm ci`, `npm run build`), the 37-53 precedent, so that "rebuilt" is not vacuous.
- **Recreate** `docker compose -f docker-compose.full-stack.yml up -d`, 19:54:08Z, rc 0. core-java, frontend, edge-go and mcp-server
  each `Recreate → Recreated → Started`. Postgres, Keycloak, RabbitMQ, Redis, Azurite, Mailhog and Ollama stayed
  `Running`. `compose start` was never used. All four were healthy at 19:54:58Z (bounded poll, 300 s deadline).

| Service | Tag = running image id | `.Metadata.LastTagTime` | Container started |
|---|---|---|---|
| core-java | e82657535c56 | 2026-10-15 19:54:00 UTC | 19:54:26Z |
| frontend | 5231a8ab78dc | 2026-10-15 19:52:00 UTC | 19:54:48Z |
| edge-go | b1bc2b786d2b | 2026-10-15 19:52:54 UTC | 19:54:47Z |
| mcp-server | 998c3f3f7e22 | 2026-10-15 19:52:23 UTC | 19:54:47Z |

| Check | rc | Result |
|---|---|---|
| `check-runtime-freshness.sh` | **0** (was 1) | `PASS: 4 running built service(s) match the source tree (0 unverified).` frontend `FRESH image tagged 2026-10-15 19:52:00 UTC >= newest build-input commit f199e97f` |
| running id = tag id | — | EQUAL ×4 (table above) |
| bundle grep `Managing webhook endpoints` | **0** (was 1) | **14** files (7 under `server/chunks/ssr`, 7 under `static/chunks`). Control "Pending invitations": 2 files |
| container-query rule in the bundle | 0 | `(min-width:60rem)` in `/app/.next/static/chunks/2uolvmx0bd0tm.css`, the same chunk name as 37-54's GREEN build |
| core-java log | — | `Successfully validated 84 migrations`, `strict scoping ON (default, D-06)`, `Started CoreApplication in 16.008 seconds` |

## 4. GREEN on the rebuilt runtime (19:55:13Z to 19:55:27Z)

The same spec line: **rc 0, 3 passed, 0 failed, 0 skipped.**

| Test | GREEN reading |
|---|---|
| 1 · G-37-12 | 403 `…/shop-access-denied`; the invitee sees the h1 "Webhooks", the heading "Group admin access required", no "No webhook endpoints yet", no Add endpoint; the Group admin sees Add endpoint |
| 2 · G-37-13 | dark: **0** failures, selects **5**, texts **15**; light: **0** failures, selects 5, texts 15; html.dark present then absent |
| 2 · G-37-15 | 16 options, **0** failures. The two integration credentials now read `integration-orders-rw` and `integration-catalog-ro` |
| 3 · G-37-14 | 0 failures at every width (table below); "Expires" present at 768; the fixture cancelled at 768 |

### 4.1 Instrument arms (each ran in BOTH runs and failed as designed)

| Arm | GREEN-run output |
|---|---|
| refusal assertion on the Group admin's page (`expectRefusal(adminPage, 2000)`) | threw; annotation `arm-refusal: expectRefusal(adminPage) threw, as designed` |
| `#staff-role` forced to `#ffffff` / `rgb(248,250,252)` | `[{"el":"select#staff-role","text":"Staff — order ops on one shop","color":"rgb(248, 250, 252)","background":"rgb(255, 255, 255)","ratio":1.05}]` |
| injected option "Arm Member (null)" | `["Arm Member (null)"]` (RED run: the two real `(null)` options plus the arm) |
| old four-column table layout forced at 768 | 28 failures, row display `table-row` (the email spans 7 lines, "manager" 2, "Unsorted" 2, every action leaves the region and the card). Same 28 in the RED run |
| closing clean measurement (arms removed) | the contrast failures equal the first measurement and the picker options equal the originals (asserted in the test) |

### 4.2 Per-width layout (GREEN) and the threshold

| Viewport | Fixture row display | Card content width | Failures | RED (old runtime) |
|---|---|---|---|---|
| 390 | flex (stacked) | 308 px | 0 | 0 (old `sm:` stack) |
| 768 | flex (stacked) | 398 px | 0 | 30 (table-row) |
| 1024 | flex (stacked) | 654 px | 0 | 2 (table-row) |
| 1280 | flex (stacked) | 910 px | 0 | 1 (table-row) |
| 1440 | table-row | 1070 px | 0 | 0 (table-row) |

Fixture: a Shop manager invitation for the shop with the longest name, "Unsorted legacy items".

**Table max-content against the threshold.** At 1440 the spec measured `maxContent: 1017` px, row display
`table-row`, and the stylesheet's container condition `(min-width: 60rem)` = **960 px**. The RED run measured
1177 px under the old styles, and found no container rule (`thresholds: []`).

Max-content is above the threshold. The plan's Task 3 rule says to raise the threshold in that case. The continuation
made a change conditional on the measurement showing the threshold is wrong. So the threshold was measured at its own
boundary first, with two scratch probes (`frontend/e2e/zz-scratch55-threshold.spec.ts`: the same `layoutFailures`
checker, its own fixture invitation cancelled in `finally`, the file deleted after, `git status` clean):

| Probe | Card content widths | Result |
|---|---|---|
| A, real styles, around the boundary | 950, 959 (stacked); 960, 961, 970, 980, 990, 1000, 1016, 1017, 1030, 1070, 1230 (table) | **0 failures at every width**. Table width = scroll width = client width (no sideways scroll). Row height 129 px at 960 and at 1230 alike: no extra wrapping at the threshold |
| B, table layout forced below the threshold (the arm's style, actions kept in a column) | 940, 920 | 0 failures |
| | 900, 880, 860, 840, 820, 800, 780, 760, 730 | 1 failure each: `token "p…@example.test" spans 2 lines` |

So the table layout is clean down to a 920 px card and first splits a token at 900 px. The 60rem threshold sits about
40 to 60 px above the first failing width for this data. Max-content overshoots because it counts the role/shop text
on one line, and that text wraps at word boundaries without growing the row (the actions column, two stacked h-11
buttons, sets the row height). **The threshold was not changed.** It is recorded for the owner as a decision (§9).
Probe B is a lower bound: below 60rem the email cell keeps `[overflow-wrap:anywhere]`, the stacked mode's class.

## 5. The goods, and the plan's GREEN line verbatim (19:56:57Z to 19:58:10Z)

`(set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/staff-webhooks-uat.spec.ts e2e/staff-invite.spec.ts e2e/webhooks-flow.spec.ts e2e/dashboard-mobile.spec.ts --project=desktop); echo rc=$?`:
**rc 0, 29 passed, 0 failed, 0 skipped** (the vendor password reached the run).

| Spec | Passed |
|---|---|
| staff-webhooks-uat | 3 |
| staff-invite ("a Group admin invites, the invitee joins with exactly the invited access") | 1 |
| webhooks-flow ("create → secret reveal → list → detail → filter → replay → 375px") | 1 |
| dashboard-mobile (12 "usable at 390px", the 375 px shell check, 11 "no horizontal overflow at 375px") | 24 |

`git diff --quiet origin/main -- frontend/e2e/staff-invite.spec.ts` → rc 0: byte-identical to origin/main.

## 6. The native option list (left open by 37-54)

No instrument can see a native option list: the browser draws it outside the DOM. What the browser is told to draw
was read in dark and light (scratch probe, deleted after):

| Select | Dark: `color-scheme`, colour on background | Options (dark) | Light |
|---|---|---|---|
| `#invite-role`, `#invite-shop`, `#staff-user`, `#staff-shop`, `#staff-role` | **`dark`**, `rgb(248,250,252)` on `rgb(30,41,59)` | `rgb(248,250,252)`, transparent background, scheme `dark` | `normal`, `rgb(2,8,23)` on white |
| `#shop-context-select` (the shop switcher; inside `<main>`, hidden at 1440) | **`normal`**, `rgb(255,255,255)` on `rgb(30,41,59)` | `rgb(255,255,255)`, transparent background, scheme **`normal`** | `normal`, `rgb(15,23,42)` on white |

The five form selects ask Chrome for a dark list with light text, which is consistent. The shop switcher asks for a
light list (scheme `normal`) while its options carry white text: that is the input for a white-on-white open list in
the dark theme. It sits outside the two cards and 37-54's scope, so it is listed here with the residue (§7). The owner's
UAT re-run should open each select in dark, as the plan's human check says.

## 7. Dark-theme residue outside the two form cards (measured, not asserted, not restyled)

From the GREEN run's `dark-residue` annotation (`/dashboard/staff`, 1440, dark): **73 of 125** visible text elements
in `<main>` outside the two cards measure below 4.5:1. No select was visible in that scope, and no card was missing.

| Count | Colour on background | Ratio | Elements |
|---|---|---|---|
| 1 | `rgb(15,23,42)` (slate-900) on the shell's `rgb(2,6,23)` | **1.13** | the page h1 "Staff & access" |
| 1 | `rgb(71,85,105)` (slate-600) on `rgb(2,6,23)` | 2.66 | the subtitle "Who can work on which shop" |
| 31 | slate-900 on the dark card `rgb(2,8,23)` | **1.12** | People: every person's name, every grant line ("Shop manager · Brixton Village Grill" …), "Group admin · all shops", "Admin account"; Pending: the invitee email |
| 36 | slate-600 on the dark card | 2.64 | People: masked emails, "Last seen …", the first-admin and realm-admin notes; Pending: the four "Send invitation again" / "Cancel invitation" buttons |
| 4 | `rgb(51,65,85)` (slate-700) on the dark card | 1.93 | the "No access" badges |

As the plan expected, the page content uses hard-coded light-theme text colours (`text-slate-900`, `text-slate-600`,
`text-slate-700`), while the cards' backgrounds follow the theme token and go dark. In the dark theme the People and
Pending cards and the page heading are effectively unreadable (about 1.1:1). Add the shop switcher's light-scheme
option list (§6).

**Recommendation for the owner.** Make a dashboard-wide dark pass its own change, in the shape 37-54 used for the two
form cards: replace the hard-coded slate text colours with the theme tokens (`text-foreground`,
`text-muted-foreground`), give `#shop-context-select` `[color-scheme:dark]` under `.dark`, and turn this residue
measurement into an assertion over every dashboard route (the `measureContrast` scope "outside" generalises to the
whole `<main>`). Until then there are two choices. (a) Accept the dark theme as shell-only and record it as a known
limitation. (b) Hide the "Dark Mode" toggle until the pass ships, because today it turns most dashboard pages
unreadable. (b) is the smaller change and removes a user-visible defect. (a) keeps a capability some vendors may
already use. The choice is the owner's. Nothing here was restyled.

## 8. Docs and gates

| Gate | Before | After |
|---|---|---|
| `bash scripts/docs-freshness.sh` (check) | **rc 1**: the tree says `playwright_blocks 182`, `playwright_specs 43`, total **6138**; `docs/metrics.json` held 179 / 42 / 6135 (Task 1's 3 literal `test(` blocks in 1 new spec) | `--write` rc 0, then the check **rc 0** |
| `bash scripts/check-doc-metrics.sh` | **rc 1**: 10 FAIL lines (README.md ×4, CLAUDE.md ×3, AGENTS.md ×3: 6135 / 179 / 42) | **rc 0**: `PASS: all 37 prose metric claim(s) across 3 doc(s) match docs/metrics.json.` |
| `bash scripts/check-e2e-typecheck.sh` | — | **rc 0**: `PASS: 50 e2e file(s) type-check clean.` |
| the plan's third `<automated>` line | — | `rc=0 0 0`, its `test … = "000"` rc 0 |
| frontend source outside `frontend/e2e` since 37-54 | — | `git diff --stat 07d1131b HEAD -- frontend ':(exclude)frontend/e2e'` and from `f199e97f`: **empty**. Fail direction: from 37-54's RED commit `902804a3` the same diff names `pending-invites-table.tsx` (+37 / −10). Full jest, tsc and build were not re-run: nothing they read changed since 37-54's verified GREEN |
| `check-runtime-freshness.sh` (final) | 1 | **0**, 0 unverified |
| `check-branch-behind-base.sh` | — | **rc 0**: `PASS: HEAD contains every commit on origin/main (57d257f0); it is 543 commit(s) ahead and 0 behind.` Fail direction: a scratch detached worktree at `origin/main~1`, running that worktree's own copy of the script (its `repo root` names the worktree): **rc 1**, `FAIL: HEAD is 1 commit(s) behind origin/main (and 0 ahead).` The worktree was removed |

`docs/metrics.json`: `playwright_blocks` 179 → 182, `playwright_specs` 42 → 43, `total_logical_invocations` 6135 →
6138. The prose in README.md (badge, E2E line, total), CLAUDE.md and AGENTS.md (the Testing constraint) follows.

## 9. Open for the owner

1. **The dark-theme residue (§7) and the shop switcher's option list (§6):** choose (a) or (b), or schedule the dashboard-wide dark
   pass.
2. **The container threshold (§4.2):** max-content (1017 px) is above 60rem (960 px). The plan's literal rule would
   raise it to 64rem (1024 px). The boundary measurement shows the table layout clean at 960 to 1017 px, with the first
   failure below 920 px, so it was kept at 60rem. Raising it would stack the rows at card widths 960 to 1023 px, which
   is harmless but unnecessary on this data.
3. **UAT re-run:** `/gsd-verify-work 37`, tests 12 to 15, on this runtime (the plan's human check), opening each select in
   dark.

## 10. Residue on the dev stack from this plan

- Keycloak (vendor realm) gained 3 STAFF users, `staff-<ms>@example.test`, one from each run of test 1 (RED, GREEN, the
  plan's line). Each holds an accepted STAFF grant on "Unsorted legacy items". This is by design (the plan's Task 2 context).
- Fixture invitations: 3 from test 3 and 2 from the probes. Each was cancelled in the run that created it (test 3:
  no `fixture-cleanup` annotation in any run; the probes cancel in `finally` and assert the row gone, rc 0).
- The pending list already held 6 invitations from earlier specs (`invitee-…`, `screens-…@example.test`). This plan
  did not create them and left them alone.

## 11. Secrets

No token, password or client secret is in this file or the commit. The invitee passwords lived only in
`staff-invitee.ts`'s memory, and the vendor password only in the sourced environment of each run. `gitleaks protect
--staged` ran on the commit (result in the SUMMARY).

## 12. The PR branch (D-03)

`phase-37-uat-gaps`, cut from origin/main (`57d257f0`, #924) as one `git merge --squash phase-37-ops-readiness`
commit, immediately after `check-branch-behind-base.sh` passes; tag `cut/phase-37-uat-gaps` on the integration commit
it was cut from. Subject: "fix(37): UAT gaps G-37-12..G-37-15 (webhooks refusal, Staff form dark theme, pending
invitations layout, picker labels)". **Not pushed**: the owner opens it in the ship shape (push first, review
second) and runs its D3 review series, and the `docs/CHANGELOG.md` entry citing the PR number is added on that
branch. The cut's own sha, tree equality and commit count are recorded in `37-55-SUMMARY.md`, not here, because
recording them here would falsify this file.
