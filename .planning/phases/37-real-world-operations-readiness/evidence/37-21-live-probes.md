# 37-21 live proof on the rebuilt compose runtime

Script: `evidence/37-21-live.cjs` (Playwright chromium, vendor `admin-user`, password from the stack's .env). Screens: `evidence/37-21-screens/`.
Every check prints PASS/FAIL with the measured value. Fail directions are in 37-21-break-arms.txt (jest) and the pre-rebuild content checks below.

## 1. Runtime parity

```
PRE-REBUILD frontend container 8945c8306e73eb0a2335eae16ec8a2fd7e800b543ba0bdbcaae4659d59a2c40a
image=sha256:5d359e69eb485304d6758e527a1566c114a95386d0cb5033a30cae5657384120 started=2026-10-10T15:00:48.565314641Z
LastTagTime=2026-10-10 15:00:06.836061623 +0000 UTC
token 'Until I reopen' rc=1
token 'Copy Monday to every day' rc=1
token 'Open now' rc=0
--- pre-rebuild check-runtime-freshness: FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified). (frontend stale vs 013eb954)

POST-REBUILD frontend container 0740a8c3cdbaf444c184fb5fa4164ce71b59ba7f499f27e0c5023037c2581d43
image=sha256:ff4e4aa2f889af28cc006d93d525e9c2e5b159a5e43d9e8f7b041e83a0beef14 started=2026-10-10T15:41:39.856392059Z health=healthy
LastTagTime=2026-10-10 15:41:37.932655045 +0000 UTC
token 'Until I reopen' rc=0
token 'Copy Monday to every day' rc=0
token 'Customers can still order on this day at any time' rc=0
token 'Open now' rc=0
--- core-java untouched?
core-java image before: sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 LastTagTime=2026-10-10T15:00:18.165240906Z
core-java container before: 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca
core-java image after:  sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 LastTagTime=2026-10-10T15:00:18.165240906Z
core-java container after: 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca

before rebuild 2: container=0740a8c3cdbaf444c184fb5fa4164ce71b59ba7f499f27e0c5023037c2581d43 image=sha256:ff4e4aa2f889af28cc006d93d525e9c2e5b159a5e43d9e8f7b041e83a0beef14 started=2026-10-10T15:41:39.856392059Z
after rebuild 2: container=04ddea1d824e4df89e6225b2b76bd0f1344d8b746c630e8cf1521e7808f4d838 image=sha256:278d580c379b0cf9782897953c5f6483ba08962c036c17cc69dc8928e79d4c1b started=2026-10-10T15:44:49.716495964Z health=healthy
LastTagTime=2026-10-10 15:44:47.873839979 +0000 UTC
token 'Until I reopen' rc=0
token 'Copy Monday to every day' rc=0
token 'Customers can still order on this day at any time' rc=0
token 'Set opening hours for' rc=0
token 'Open now' rc=0
  core-java    FRESH  image tagged 2026-10-10 15:00:18 UTC >= newest build-input commit cb8cf9d1 (2026-10-10 14:27:29 UTC)
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     FRESH  image tagged 2026-10-10 15:44:47 UTC >= newest build-input commit d5e1490f (2026-10-10 15:44:02 UTC)
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).

after rebuild 3: container=1d637de11961f1a4f2cf54195b2c89156fc14a486260a80624166a79411f5aa5 image=sha256:cd524c092965c3a7f0487cefeaed4ccf37e976be97d3e9cd74b6b86426ee2191 started=2026-10-10T15:49:28.468426282Z health=healthy
LastTagTime=2026-10-10 15:49:26.41486073 +0000 UTC
token 'Until I reopen' rc=0
token 'Copy Monday to every day' rc=0
token 'Customers can still order on this day at any time' rc=0
token 'Set opening hours for' rc=0
  frontend     note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  frontend     FRESH  image tagged 2026-10-10 15:49:26 UTC >= newest build-input commit 7b80f302 (2026-10-10 15:48:48 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
```

Rebuilt with `docker compose -f docker-compose.full-stack.yml build frontend` then `up -d --no-deps --force-recreate frontend` (37-17's measured form: it leaves core-java's image and container untouched; core-java image 9d3b9884 and container 2128456c are identical before and after, above).

## live: kitchen

```
PASS [kitchen] control reads 'Pause orders' when not paused :: "Pause orders"
PASS [kitchen] control is 44px tall (h-11) :: 44
PASS [kitchen] 390px: header row wraps, no horizontal scroll :: 390
PASS [kitchen] 390px: control inside the viewport :: 169
PASS [kitchen] menu: 20 minutes, 40 minutes, Until I reopen :: ["20 minutes","40 minutes","Until I reopen"]
PASS [kitchen] menu rows are 44px :: 44
PASS [kitchen] PUT {minutes: 20} -> 200 :: {"status":200,"sent":{"minutes":20}}
PASS [kitchen] server answered with ordersPausedUntil :: {"until":"2026-10-10T17:09:58+01:00","uk":"17:09","browserSum":"17:09"}
PASS [kitchen] control reads the server's time :: "Paused until 17:09"
PASS [kitchen] banner: K3 timed sentence with the server's time, and the shop's name :: "Brixton Village GrillOrders paused until 17:09. Customers can see your menu but can't order.Resume orders"
PASS [kitchen] banner is white (L6: never slate-900-filled) :: "rgb(255, 255, 255)"
PASS [kitchen] public shop agrees: openNow false, pausedUntil = the banner's instant :: {"openNow":false,"pausedUntil":"2026-10-10T16:09:58Z"}
PASS [kitchen] shops table row badge 'Paused until HH:MM' :: "Brixton Village GrillUnit 74, Brixton Village Market, London SW9 8PSPublishedPaused until 17:09about 1 month ago"
PASS [kitchen] Resume orders -> DELETE 200 :: 200
PASS [kitchen] banner gone after resume :: 0
PASS [kitchen] control back to 'Pause orders' :: "Pause orders"
PASS [kitchen] public shop open again :: {"openNow":true,"pausedUntil":null}
PASS [kitchen] PUT {untilReopened: true} -> 200 :: {"status":200,"sent":{"untilReopened":true}}
PASS [kitchen] control reads 'Paused' :: "Paused"
PASS [kitchen] banner: indefinite sentence :: "Brixton Village GrillOrders paused until you reopen. Customers can see your menu but can't order.Resume orders"
PASS [kitchen] paused menu adds 'Resume orders now' :: ["20 minutes","40 minutes","Until I reopen","Resume orders now"]
PASS [kitchen] Resume orders now -> DELETE 200 :: 200
PASS [kitchen] resumed: no banner :: null
INFO shop=Brixton Village Grill slug=brixton-village-grill id=2a8c3470-7924-431d-ac2a-fd4dd1564d39
RESULT [kitchen] ALL PASS
```

## live: crossdevice

```
PASS [crossdevice] device B shows device A's pause within one 60 s poll, with the server's time :: {"seconds":60,"text":"Brixton Village GrillOrders paused until 17:30. Customers can see your menu but can't order.Resume orders"}
PASS [crossdevice] device B clears the banner within one poll of the resume :: {"seconds":60}
RESULT [crossdevice] ALL PASS
```

## live: longname

```
PASS [longname] root font-size 130% applied :: "20.8px"
PASS [longname] 60-char name wraps inside the banner (no clipping) :: {"sw":273,"cw":273,"right":316.40625}
PASS [longname] banner inside 360px :: 339.203125
PASS [longname] no horizontal scroll at 360px / 130% :: 360
RESULT [longname] ALL PASS
```

## live: dialog

```
PASS [dialog] editor pre-filled from the seeded all-day STRUCTURED schedule :: {"Monday":["00:00","00:00"],"Tuesday":["00:00","00:00"],"Wednesday":["00:00","00:00"],"Thursday":["00:00","00:00"],"Friday":["00:00","00:00"],"Saturday":["00:00","00:00"],"Sunday":["00:00","00:00"]}
PASS [dialog] free-text inputs gone :: null
PASS [dialog] save -> 200 :: 200
PASS [dialog] request carries the structured schedule with Monday 09:00-17:00 and the other days all-day :: {"mon":[{"open":"09:00","close":"17:00"}],"tue":[{"open":"00:00","close":"00:00"}],"wed":[{"open":"00:00","close":"00:00"}],"thu":[{"open":"00:00","close":"00:00"}],"fri":[{"open":"00:00","close":"00:00"}],"sat":[{"open":"00:00","close":"00:00"}],"sun":[{"open":"00:00","close":"00:00"}]}
PASS [dialog] request carries the current slug (the storefront address stays) :: {"sent":"mama-ades-kitchen","saved":"mama-ades-kitchen"}
PASS [dialog] server says STRUCTURED :: "STRUCTURED"
PASS [dialog] storefront list: Monday 09:00–17:00, Tuesday Open 24 hours :: {"Monday":"09:00–17:00","Tuesday":"Open 24 hours","Wednesday":"Open 24 hours","Thursday":"Open 24 hours","Friday":"Open 24 hours","Saturday":"Open 24 hours","Sunday":"Open 24 hours"}
PASS [dialog] public payload schedule matches :: [{"open":"09:00","close":"17:00"}]
PASS [dialog] restore save -> 200, all seven days 00:00-00:00 :: {"mon":[{"open":"00:00","close":"00:00"}],"tue":[{"open":"00:00","close":"00:00"}],"wed":[{"open":"00:00","close":"00:00"}],"thu":[{"open":"00:00","close":"00:00"}],"fri":[{"open":"00:00","close":"00:00"}],"sat":[{"open":"00:00","close":"00:00"}],"sun":[{"open":"00:00","close":"00:00"}]}
PASS [dialog] restored: public schedule is the all-day week again, slug unchanged :: {"slug":"mama-ades-kitchen","schedule":{"fri":[{"open":"00:00","close":"00:00"}],"mon":[{"open":"00:00","close":"00:00"}],"sat":[{"open":"00:00","close":"00:00"}],"sun":[{"open":"00:00","close":"00:00"}],"thu":[{"open":"00:00","close":"00:00"}],"tue":[{"open":"00:00","close":"00:00"}],"wed":[{"open":"00:00","close":"00:00"}]}}
INFO slug=mama-ades-kitchen
RESULT [dialog] ALL PASS
```

## live: backstops

```
PASS [backstops] 375px: the two time inputs sit side by side at equal width :: {"open":{"x":25,"y":-172.765625,"width":132.5,"height":44},"close":{"x":165.5,"y":-172.765625,"width":132.5,"height":44}}
PASS [backstops] 375px: no horizontal overflow inside the dialog :: {"sw":373,"cw":373}
PASS [backstops] 375px: no horizontal page scroll :: 375
PASS [backstops] 200-char quote wraps inside the 375px dialog :: {"line":{"sw":325,"cw":325,"right":350,"len":297},"dialog":{"sw":373,"cw":373,"right":375}}
PASS [backstops] dialog has no horizontal overflow with the quote :: {"sw":373,"cw":373,"right":375}
PASS [backstops] Wednesday opens as Closed :: null
RESULT [backstops] ALL PASS
```

## live: notices

```
PASS [notices] home card: legacy line :: "1 shop has no opening hoursCustomers can order from it at any time, including when you're closed.Mama Ade's KitchenSet opening hoursCheck the opening hours for Peckham Jollof Co.We couldn't read the hours you entered for Tuesday. Customers can order on that day at any time until you save your hours.Check hours"
PASS [notices] home card: needs-review line under the ruling :: "1 shop has no opening hoursCustomers can order from it at any time, including when you're closed.Mama Ade's KitchenSet opening hoursCheck the opening hours for Peckham Jollof Co.We couldn't read the hours you entered for Tuesday. Customers can order on that day at any time until you save your hours.Check hours"
PASS [notices] Set opening hours opens that shop's dialog focused on the hours fieldset :: "opening-hours"
PASS [notices] legacy info line shown :: null
PASS [notices] parameters dropped after use :: "http://localhost:3000/dashboard/shops"
PASS [notices] table badges: Hours not set and Check hours :: "NameAddressStatusCreatedActionsUnsorted legacy items—Draftabout 1 month agoBrixton Village GrillUnit 74, Brixton Village Market, London SW9 8PSPublishedabout 1 month agoPeckham Jollof Co.12 Bellenden Road, Peckham, London SE15 4BWPublishedCheck hoursabout 1 month agoMama Ade's Kitchen48 Rye Lane, Pe"
RESULT [notices] ALL PASS
```

## Seeded data restored (by content)

```
before:
Mama Ade's Kitchen|mama-ades-kitchen|STRUCTURED|{"fri": [{"open": "00:00", "close": "00:00"}], "mon": [{"open": "00:00", "close": "00:00"}], "sat": [{"open": "00:00", "close": "00:00"}], "sun": [{"open": "00:00", "close": "00:00"}], "thu": [{"open": "00:00", "close": "00:00"}], "tue": [{"open": "00:00", "close": "00:00"}], "wed": [{"open": "00:00", "close": "00:00"}]}|cfd9a7b6acf8f7451c99004460c74fcd||t|51.472435|-0.070047
after:
Mama Ade's Kitchen|mama-ades-kitchen|STRUCTURED|{"fri": [{"open": "00:00", "close": "00:00"}], "mon": [{"open": "00:00", "close": "00:00"}], "sat": [{"open": "00:00", "close": "00:00"}], "sun": [{"open": "00:00", "close": "00:00"}], "thu": [{"open": "00:00", "close": "00:00"}], "tue": [{"open": "00:00", "close": "00:00"}], "wed": [{"open": "00:00", "close": "00:00"}]}|cfd9a7b6acf8f7451c99004460c74fcd||t|51.472435|-0.070047
all shops after every live phase (no pause left, schedules identical by md5):
Brixton Village Grill|brixton-village-grill|STRUCTURED||f|cfd9a7b6acf8f7451c99004460c74fcd
Mama Ade's Kitchen|mama-ades-kitchen|STRUCTURED||f|cfd9a7b6acf8f7451c99004460c74fcd
Peckham Jollof Co.|peckham-jollof-co|STRUCTURED||f|cfd9a7b6acf8f7451c99004460c74fcd
Tenant B Probe Kitchen|tenant-b-probe|STRUCTURED||f|cfd9a7b6acf8f7451c99004460c74fcd
Unsorted legacy items|unsorted-legacy-items|STRUCTURED||f|cfd9a7b6acf8f7451c99004460c74fcd
```

## Notes

- Two earlier kitchen runs FAILED and are kept out of the PASS record above but described here: run 1 measured the menu rows during Radix's zoom-in-95 open animation (42.05px = 0.955 x 44) and then clicked the trigger while the menu was still closing; the script now waits for the animation. Run 2 read the shop name from the board header ('Showing tickets for ...'), a script bug; the banner text was correct. Run 2 also exposed a real defect: on a fresh load of a paused shop the control read an enabled 'Pause orders' before the first shop read (fixed in 7b80f302, RED 69ecdc8b).
- The NEEDS_REVIEW and LEGACY views were produced by rewriting the dashboard's shops LIST response in the browser (page.route); nothing was written. No such shop exists on the dev DB since 37-22's seeder.
- The cookie notice overlaps the lower part of two 360/390px screenshots; the measurements were taken on the elements, not read off the pictures.

## Final rebuild (after the test-only commit 05961115)

```
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
(pre-rebuild: frontend stale vs 05961115 -> the gate's fail direction on this tree)
FINAL frontend container 040c64a67e1f727e36e7f945cb90b679f4582bc3aa13ed670901ac504a0a7cb8 image=sha256:9a532285bf153c4ed803e049cca90bda4b730b460054d808ac252e7753330475 started=2026-10-10T15:55:44.02276732Z health=healthy
LastTagTime=2026-10-10 15:55:42.203290108 +0000 UTC
token 'Until I reopen' rc=0
token 'Copy Monday to every day' rc=0
token 'Customers can still order on this day at any time' rc=0
token 'Set opening hours for' rc=0
negative control (absent token) rc=1
  core-java    FRESH  image tagged 2026-10-10 15:00:18 UTC >= newest build-input commit cb8cf9d1 (2026-10-10 14:27:29 UTC)
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     FRESH  image tagged 2026-10-10 15:55:42 UTC >= newest build-input commit 05961115 (2026-10-10 15:53:47 UTC)
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
check-runtime-freshness rc=0
```

core-java was not rebuilt or restarted (no Java change), so the alert metrics were not reset and scripts/seed-order-metric.sh was not needed.
