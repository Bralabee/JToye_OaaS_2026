# Live runtime evidence — quick 261016-edv (forced light on the dashboard)

Compose stack `docker-compose.full-stack.yml`, frontend on http://localhost:3000, Chrome (Playwright
1.63). The spec under test is `frontend/e2e/dashboard-forced-light.spec.ts` at sha256
`cc17e2f0338b891c11f54db566968aa76192805de590952e0ecbb06725ff17a2` in BOTH the RED and the GREEN run
(no edit between them; the base-URL fail arm below was restored to this hash).

## 1. Precondition: the running frontend predates the feat commit

Feat commit 6f7da333 committed 2026-10-16T09:32:49Z. Images before the rebuild (09:35:47Z):

```
frontend: sha256:c1e233e7456ba0e2dde0ab614e8daf2ec472125e6aae4ff848325b7b4cd3659b 2026-10-16T08:46:35.92917391Z container-image=sha256:c1e233e7456ba0e2dde0ab614e8daf2ec472125e6aae4ff848325b7b4cd3659b
core-java: sha256:e82657535c562d6b5ea63f55a1c806cf7182820821e677b493f95ae1f1249c63 2026-10-15T19:54:00.466311246Z container-image=sha256:e82657535c562d6b5ea63f55a1c806cf7182820821e677b493f95ae1f1249c63
edge-go: sha256:b1bc2b786d2b913662a62a4ec54736a70cf0b727c9c18ce29c47b70f6c1597cc 2026-10-15T19:52:54.537832013Z container-image=sha256:b1bc2b786d2b913662a62a4ec54736a70cf0b727c9c18ce29c47b70f6c1597cc
mcp-server: sha256:998c3f3f7e2236350c59529916270cc5e9de93887764760ea6dc094817e357b8 2026-10-15T19:52:23.904174513Z container-image=sha256:998c3f3f7e2236350c59529916270cc5e9de93887764760ea6dc094817e357b8
```

Freshness BEFORE the rebuild (rc=1, the gate names the stale frontend; this is its fail direction):

```
  core-java    FRESH  image tagged 2026-10-15 19:54:00 UTC >= newest build-input commit 57d257f0 (2026-10-15 09:57:48 UTC)
  edge-go      FRESH  image tagged 2026-10-15 19:52:54 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     DRIFT  [image-not-rebuilt]  image tagged 2026-10-16 08:46:35 UTC / newest build-input commit 6f7da333 (2026-10-16 09:32:49 UTC)
  mcp-server   FRESH  image tagged 2026-10-15 19:52:23 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
DRIFT DETAIL:
    [image-not-rebuilt] image 'jtoye_oaas_2026-frontend' was tagged 2026-10-16 08:46:35 UTC, but its build inputs changed later:
                        commit 6f7da333  2026-10-16T10:32:49+01:00  feat(quick-261016-edv): force the light theme on the dashboard and withhold the theme control
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
```

## 2. RED on the pre-rebuild runtime (frontend image c1e233e7, tagged 08:46:35Z)

`(set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/dashboard-forced-light.spec.ts --reporter=list,json)` — rc=1, 2 failed.
Both tests fail on their FORCED-LIGHT assertions; the controls (emulated dark reached the page, the stored
choice is present) hold, and both instrument arms behave as designed (they are relative to the count before
injection, so they prove themselves on a build that still renders a control):

```
  ✘  1 [mobile] › e2e/dashboard-forced-light.spec.ts:189:7 › dashboard forced light (quick 261016-edv) › mobile: the More sheet offers Sign Out and no theme control, and the dashboard stays light @mobile-only (15.6s)
  ✘  2 [desktop] › e2e/dashboard-forced-light.spec.ts:153:7 › dashboard forced light (quick 261016-edv) › desktop: stored dark and a dark system still give the light dashboard, with no theme control @desktop-only (31.2s)
    test-results/dashboard-forced-light-das-b8d47-ard-stays-light-mobile-only-mobile/test-failed-1.png
    test-results/dashboard-forced-light-das-fda20--theme-control-desktop-only-desktop/test-failed-1.png
  2 failed

## mobile failed mobile: the More sheet offers Sign Out a
   dashboard-prefers-dark = true
   dashboard-stored-theme = dark
   dashboard-html-dark = true
   sheet-theme-buttons = 1
   arm-html-class = {"armed":true,"after":false}
   arm-theme-button = {"before":1,"armed":2,"after":1}
   ERR: Error: quick 261016-edv: the dashboard must leave no `dark` class on <html> (the seeded stale one must be removed within 15 s)
   ERR: Error: the More sheet offers no Dark Mode / Light Mode control
   ERR: Error: still no dark class with the sheet open
## desktop failed desktop: stored dark and a dark system s
   dashboard-prefers-dark = true
   dashboard-stored-theme = dark
   dashboard-html-dark = true
   dashboard-theme-buttons = {"footer":1,"page":1}
   arm-html-class = {"armed":true,"after":false}
   arm-theme-button = {"before":1,"armed":2,"after":1}
   staff-prefers-dark = true
   staff-stored-theme = dark
   staff-html-dark = true
   staff-theme-buttons = 1
   ERR: Error: quick 261016-edv: the dashboard must leave no `dark` class on <html> (the seeded stale one must be removed within 15 s)
   ERR: Error: the sidebar footer offers no Dark Mode / Light Mode control
   ERR: Error: no button on the page, hidden ones included, is a theme control
   ERR: Error: quick 261016-edv: the dashboard must leave no `dark` class on <html> (the seeded stale one must be removed within 15 s)
   ERR: Error: no theme control on /dashboard/staff
```

## 3. Rebuild

`docker compose -f docker-compose.full-stack.yml up -d --build frontend` (09:39Z, rc=0) recreated the
frontend AND re-tagged core-java without recreating its container (the known compose trap). Freshness
then (rc=1):

```
  core-java    DRIFT  [container-not-recreated]  image tagged 2026-10-16 09:39:09 UTC / newest build-input commit 57d257f0 (2026-10-15 09:57:48 UTC)
  edge-go      FRESH  image tagged 2026-10-15 19:52:54 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     FRESH  image tagged 2026-10-16 09:39:33 UTC >= newest build-input commit 6f7da333 (2026-10-16 09:32:49 UTC)
  mcp-server   FRESH  image tagged 2026-10-15 19:52:23 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
DRIFT DETAIL:
    [container-not-recreated] container 'jtoye_oaas_2026-core-java-1' is executing image sha256:e82657535c56, but the tag 'jtoye_oaas_2026-core-java' now points at sha256:17061dce0ccf.
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
```

`docker compose ... up -d --build core-java` built yet another core-java ID and still did not recreate the
container (rc=1 again, `[container-not-recreated]`). `docker compose ... up -d --no-deps --force-recreate
core-java` recreated it; healthy after ~31 s. `scripts/seed-order-metric.sh` (the standing follow-up after a
core-java recreate) returned VOID rc=2: the seed order was refused 429 `order-cap-exceeded` (axis EMAIL,
cap 3 open cash orders for the seed address) — a dev-stack data state, not touched here (recorded in the
SUMMARY as residue). Images after:

```
frontend: sha256:a69cbd04feba4a4666a21277b5d2e8a63e30cefc06346e42cc1a2367d69bba71 2026-10-16T09:39:33.206146097Z container-image=sha256:a69cbd04feba4a4666a21277b5d2e8a63e30cefc06346e42cc1a2367d69bba71
core-java: sha256:670ea6ae50eac9fecb49e98fae9c222803891d6fd487a701fb16b51023218b70 2026-10-16T09:39:53.421074349Z container-image=sha256:670ea6ae50eac9fecb49e98fae9c222803891d6fd487a701fb16b51023218b70
edge-go: sha256:b1bc2b786d2b913662a62a4ec54736a70cf0b727c9c18ce29c47b70f6c1597cc 2026-10-15T19:52:54.537832013Z container-image=sha256:b1bc2b786d2b913662a62a4ec54736a70cf0b727c9c18ce29c47b70f6c1597cc
mcp-server: sha256:998c3f3f7e2236350c59529916270cc5e9de93887764760ea6dc094817e357b8 2026-10-15T19:52:23.904174513Z container-image=sha256:998c3f3f7e2236350c59529916270cc5e9de93887764760ea6dc094817e357b8
```

Freshness AFTER (rc=0):

```
  core-java    FRESH  image tagged 2026-10-16 09:39:53 UTC >= newest build-input commit 57d257f0 (2026-10-15 09:57:48 UTC)
  edge-go      FRESH  image tagged 2026-10-15 19:52:54 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
  frontend     FRESH  image tagged 2026-10-16 09:39:33 UTC >= newest build-input commit 6f7da333 (2026-10-16 09:32:49 UTC)
  mcp-server   FRESH  image tagged 2026-10-15 19:52:23 UTC >= newest build-input commit d6c3d308 (2026-10-14 08:57:07 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
```

## 4. GREEN on the rebuilt runtime (frontend image a69cbd04, tagged 09:39:33Z >= 6f7da333)

New spec, both projects — rc=0, 2 passed, 0 skipped:

```
  ✓  1 [mobile] › e2e/dashboard-forced-light.spec.ts:189:7 › dashboard forced light (quick 261016-edv) › mobile: the More sheet offers Sign Out and no theme control, and the dashboard stays light @mobile-only (2.0s)
  ✓  2 [desktop] › e2e/dashboard-forced-light.spec.ts:153:7 › dashboard forced light (quick 261016-edv) › desktop: stored dark and a dark system still give the light dashboard, with no theme control @desktop-only (2.2s)
  2 passed (5.2s)

## mobile passed mobile: the More sheet offers Sign Out and no theme control, and the d
   dashboard-prefers-dark = true
   dashboard-stored-theme = dark
   dashboard-html-dark = false
   sheet-theme-buttons = 0
   arm-html-class = {"armed":true,"after":false}
   arm-theme-button = {"before":0,"armed":1,"after":0}
## desktop passed desktop: stored dark and a dark system still give the light dashboard,
   dashboard-prefers-dark = true
   dashboard-stored-theme = dark
   dashboard-html-dark = false
   dashboard-theme-buttons = {"footer":0,"page":0}
   arm-html-class = {"armed":true,"after":false}
   arm-theme-button = {"before":0,"armed":1,"after":0}
   staff-prefers-dark = true
   staff-stored-theme = dark
   staff-html-dark = false
   staff-theme-buttons = 0
stats {"startTime":"2026-10-16T09:40:59.425Z","duration":5174.474,"expected":2,"skipped":0,"unexpected":0,"flaky":0}
```

37-55 UAT spec, desktop project — rc=0, 3 passed, 0 skipped. The dark runs now first assert the stored dark
choice does not reach <html> (the seeded stale class is removed), then apply the class by hand; the
`html.dark must be set` controls hold, and the dark-card measurement is unchanged (0 failures, 5 selects,
15 texts in both themes):

```
  ✓  1 [desktop] › e2e/staff-webhooks-uat.spec.ts:400:7 › UAT gaps G-37-12..G-37-15, live (37-55) @desktop-only › G-37-12: a Staff user is refused on Webhooks; the Group admin still manages endpoints (7.1s)
  ✓  2 [desktop] › e2e/staff-webhooks-uat.spec.ts:473:7 › UAT gaps G-37-12..G-37-15, live (37-55) @desktop-only › G-37-13 and G-37-15: the Staff form cards read at 4.5:1 in both themes; the picker shows no null (2.6s)
  ✓  3 [desktop] › e2e/staff-webhooks-uat.spec.ts:570:7 › UAT gaps G-37-12..G-37-15, live (37-55) @desktop-only › G-37-14: pending invitations keep every token on one line and both actions in reach, 390 to 1440 (2.2s)
  3 passed (12.7s)

## desktop passed G-37-12: a Staff user is refused on Webhooks; the Group admin still ma
   g-37-12-refusal = {"status":403,"type":"https://jtoye.uk/errors/shop-access-denied"}
   g-37-12-refusal-dark = {"failures":[],"selects":0,"texts":2,"missing":[]}
   arm-refusal = expectRefusal(adminPage) threw, as designed
## desktop passed G-37-13 and G-37-15: the Staff form cards read at 4.5:1 in both themes
   arm-contrast = [{"el":"select#staff-role","text":"Staff — order ops on one shop","color":"rgb(248, 250, 252)","background":"rgb(255, 255, 255)","ratio":1.05}]
   arm-picker = ["Arm Member (null)"]
## desktop passed G-37-14: pending invitations keep every token on one line and both act
stats {"startTime":"2026-10-16T09:41:10.868Z","duration":12650.497000000001,"expected":3,"skipped":0,"unexpected":0,"flaky":0}
contrast-dark  : texts 15, selects 5, failures 0, missing []
contrast-light : texts 15, selects 5, failures 0, missing []
dark-residue   : texts 155, selects 0, failures 97 (below 4.5:1), missing [] — measured, not asserted; the input for the Phase 39 candidate dark pass (was 73/125 at 37-55; more Staff rows on the page now)
```

Dev-stack residue from this run: G-37-12 created the STAFF account `staff-1792143672255@example.test`
(invitation e-mailed 09:41:12Z, accepted by the helper) and G-37-14 the fixture invitation
`p1792143682396@example.test` (cancelled by the test).

Goods check `e2e/dashboard-mobile.spec.ts`, both projects — rc=0:

```
  48 passed (1.6m)
```

## 5. Base-URL contract and e2e type-check

`scripts/check-e2e-baseurl-contract.sh` — rc=0, scanned 44 spec files (43 + the new one, the positive
control that it saw the file). Fail arm: a `PLAYWRIGHT_BASE_URL || "http://localhost:3100"` line appended to
the new spec gave rc=1 ("declares http://localhost:3100, config says http://localhost:3000"); restored by
copy and verified by sha256 (cc17e2f0…); closing run rc=0.
`scripts/check-e2e-typecheck.sh` — rc=0, "PASS: 51 e2e file(s) type-check clean" (next build does not
check e2e files). `npx eslint` on both specs — rc=0, no findings.
`scripts/check-e2e-skip-budget.sh` — rc=2 VOID ("report describes a DIFFERENT spec set than the tree"): it
needs a full-suite report from this tree, which this task did not run; not a pass.
