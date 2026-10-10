# 261010-ppb live probes

Owner rulings 2026-10-10, proven on the rebuilt compose runtime (docker-compose.full-stack.yml). Every claim carries its fail direction. No secret is recorded.

## 1. Task 2: frontend rebuild and parity (rulings 2, 3, 4)

### Before the rebuild (fail direction for the content checks)

```
=== Task 2 Step 0: frontend runtime BEFORE rebuild (2026-10-10T18:28:18Z)
container 040c64a67e1f727e36e7f945cb90b679f4582bc3aa13ed670901ac504a0a7cb8 image sha256:9a532285bf153c4ed803e049cca90bda4b730b460054d808ac252e7753330475 started 2026-10-10T17:52:51.628728056Z status running
image sha256:9a532285bf153c4ed803e049cca90bda4b730b460054d808ac252e7753330475 tags [jtoye_oaas_2026-frontend:latest] LastTagTime 2026-10-10T15:55:42.203290108Z
core-java container 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca image sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 started 2026-10-10T17:52:52.070222058Z
new editor sentence in old bundle rc=1 (expected 1)
new card sentence in old bundle rc=1 (expected 1)
37-21 editor sentence in old bundle rc=0 (expected 0)
```

### After the rebuild

```
=== Task 2 Step 6: frontend rebuilt at HEAD 56808fd6 (2026-10-10T18:38:21Z)
commands: docker compose -f docker-compose.full-stack.yml build frontend (rc 0); docker compose -f docker-compose.full-stack.yml up -d --no-deps --force-recreate frontend (rc 0)
frontend state: running healthy
frontend container c5df211633c722896e9aa2b80b63b4bf4e7bd9d4066b28d05075ac180c57310f image sha256:d3a94f8c5c2f463d43786df188b3931becbdc640ad355b23b4bfbba7bd45887d started 2026-10-10T18:38:10.540431872Z
frontend image sha256:d3a94f8c5c2f463d43786df188b3931becbdc640ad355b23b4bfbba7bd45887d LastTagTime 2026-10-10T18:38:08.001456792Z
core-java BEFORE: sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 2026-10-10T15:00:18.165240906Z 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 
core-java AFTER:  sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 2026-10-10T15:00:18.165240906Z 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46
new editor sentence in running bundle rc=0 (expected 0; was 1 before)
new card sentence in running bundle rc=0 (expected 0; was 1 before)
37-21 editor sentence in running bundle rc=1 (expected 1; was 0 before)
pause manager-referral copy in running bundle rc=1 (expected 1)
--- check-runtime-freshness.sh (expected: frontend FRESH, core-java the only stale service until Task 3)
Runtime freshness gate
  compose file : /home/sanmi/IdeaProjects/JToye_OaaS_2026/docker-compose.full-stack.yml
  repo root    : /home/sanmi/IdeaProjects/JToye_OaaS_2026
  HEAD         : 56808fd6 (phase-37-ops-readiness)

  core-java    DRIFT  [image-not-rebuilt]  image tagged 2026-10-10 15:00:18 UTC / newest build-input commit 8c79c178 (2026-10-10 18:20:47 UTC)
  edge-go      note   .dockerignore kept as build inputs (not translatable exactly): *.exe *.dll *.so *.dylib *.test *.out *.swp *.swo *.log
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  frontend     FRESH  image tagged 2026-10-10 18:38:08 UTC >= newest build-input commit bf823ff1 (2026-10-10 18:33:03 UTC)
  mcp-server   note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)

DRIFT DETAIL:
  core-java (container 'jtoye_oaas_2026-core-java-1')
    [image-not-rebuilt] image 'jtoye_oaas_2026-core-java' was tagged 2026-10-10 15:00:18 UTC, but its build inputs changed later:
                        commit 8c79c178  2026-10-10T19:20:47+01:00  feat(261010-ppb): customer write floors - SHOP_MANAGER to create or edit, Group admin to delete (option E)
                        build paths: core-java/Dockerfile gradlew gradlew.bat gradle build.gradle.kts settings.gradle.kts core-java/build.gradle.kts core-java/src

Rebuild the named services from the current tree, then RECREATE their containers:
  docker compose -f docker-compose.full-stack.yml up -d --build <service>...
`docker compose start` and `restart` will NOT fix this — neither builds, and neither
replaces a container that is holding an older image ID.
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
freshness rc=1
```

The freshness FAIL above is the expected intermediate state: core-java drifts on 8c79c178 (Task 1's feat) and is rebuilt in Task 3; frontend is FRESH, and the core-java image id and container id are identical before and after the frontend rebuild.

### Live editor, card and pause (evidence/live-hours.cjs, admin-user, nothing written)

The NEEDS_REVIEW shop is produced by rewriting the GET shops list/by-id responses in the browser (no such shop exists on the dev DB since 37-22's seeder); the editor save is captured by page.route and fulfilled WITHOUT forwarding; the pause 403 is a stub of the typed shop-access-denied body.

Shops by content, as the Postgres superuser (rolsuper t, rolbypassrls t; positive control: 5 rows across two tenants), name|slug|hours_source|orders_paused_until|orders_paused_indefinitely|md5(opening_schedule)|md5(opening_hours):

```
before:
Brixton Village Grill|brixton-village-grill|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Mama Ade's Kitchen|mama-ades-kitchen|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Peckham Jollof Co.|peckham-jollof-co|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|99914b932bd37a50b983c5e7c90ae93b
Tenant B Probe Kitchen|tenant-b-probe|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Unsorted legacy items|unsorted-legacy-items|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e

$ node evidence/live-hours.cjs notices evidence/screens   (rc 0)
PASS [notices] signed in as admin-user and reached /dashboard :: "http://localhost:3000/dashboard"
PASS [notices] card, one unreadable day: the ruled singular sentence :: "Check the opening hours for Peckham Jollof Co.We could not read the hours you entered for Tuesday, so customers can order on that day at any time. Check and save your hours.Check hours"
PASS [notices] card: the 37-21 wording is gone :: null
PASS [notices] card, two unreadable days: the ruled plural sentence :: "Check the opening hours for Peckham Jollof Co.We could not read the hours you entered for Tuesday and Thursday, so customers can order on those days at any time. Check and save your hours.Check hours"
RESULT [notices] ALL PASS

$ node evidence/live-hours.cjs editor evidence/screens   (rc 0)
PASS [editor] signed in as admin-user and reached /dashboard :: "http://localhost:3000/dashboard"
PASS [editor] Tuesday (unreadable) is NOT ticked Closed :: "false"
PASS [editor] Tuesday reads 00:00 / 00:00 :: ["00:00","00:00"]
PASS [editor] Tuesday shows 'Open 24 hours' :: null
PASS [editor] Tuesday shows no overnight hint :: null
PASS [editor] Tuesday carries the ruled amber line :: "We could not read \"lunchtime till late\", so this day is open all day. Set the real times, or tick Closed."
PASS [editor] Sunday (written 'Closed') stays ticked, with no amber line :: "true"
PASS [editor] a save was captured (and not forwarded) :: ["name","address","description","email","logoUrl","tags","minimumOrderPennies","published","slug","openingSchedule"]
PASS [editor] captured PUT: tue is the all-day window :: [{"open":"00:00","close":"00:00"}]
PASS [editor] captured PUT: mon is 10:00-17:00 and there is no sun key :: {"mon":[{"open":"10:00","close":"17:00"}],"tue":[{"open":"00:00","close":"00:00"}]}
RESULT [editor] ALL PASS

$ node evidence/live-hours.cjs pause403 evidence/screens   (rc 0)
PASS [pause403] signed in as admin-user and reached /dashboard :: "http://localhost:3000/dashboard"
PASS [pause403] control reads 'Pause orders' before the tap :: "Pause orders"
PASS [pause403] the stubbed PUT was the one refused :: 1
PASS [pause403] K2 overlay shows the Access ended headline :: "Your access has ended: new orders are NOT showing"
PASS [pause403] no manager-referral or inline refusal text anywhere in the live tree :: 0
PASS [pause403] the pause control renders no role=alert of its own :: 0
RESULT [pause403] ALL PASS

after:
Brixton Village Grill|brixton-village-grill|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Mama Ade's Kitchen|mama-ades-kitchen|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Peckham Jollof Co.|peckham-jollof-co|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|99914b932bd37a50b983c5e7c90ae93b
Tenant B Probe Kitchen|tenant-b-probe|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
Unsorted legacy items|unsorted-legacy-items|STRUCTURED||false|cfd9a7b6acf8f7451c99004460c74fcd|d41d8cd98f00b204e9800998ecf8427e
cmp before after: rc 0 (identical)

fail direction of the script itself: LIVE_REVIEW_SHOP='No Such Shop' node evidence/live-hours.cjs notices  -> rc 1
PASS [notices] signed in as admin-user and reached /dashboard :: "http://localhost:3000/dashboard"
FAIL [notices] exception :: locator.waitFor: Timeout 20000ms exceeded.
Call log:
  - waiting for locator('body > div:not([hidden])').getByTestId('hours-notice-card') to be visible

RESULT [notices] 1 FAIL
```

Screenshots: evidence/screens/home-card-singular-390.png, editor-all-day-prefill-1280.png, pause403-k2-access-ended-1280.png.

