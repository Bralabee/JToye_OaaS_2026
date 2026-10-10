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

## 2. Task 3: core-java rebuilt to parity

```
=== Task 3 Step 1: core-java BEFORE rebuild (2026-10-10T18:43:52Z, HEAD 56808fd6)
core-java container 2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca image sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 started 2026-10-10T17:52:52.070222058Z
core-java image sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 LastTagTime 2026-10-10T15:00:18.165240906Z
frontend container c5df211633c722896e9aa2b80b63b4bf4e7bd9d4066b28d05075ac180c57310f image sha256:d3a94f8c5c2f463d43786df188b3931becbdc640ad355b23b4bfbba7bd45887d
--- check-openapi-snapshot-fresh.sh BEFORE (expected rc 1: the running core-java lacks the floor descriptions)
             "content": {
@@ -6671,7 +6671,7 @@
         ]
       },
       "put": {
-        "description": "Updates a customer. Requires SHOP_MANAGER on any shop, or Group admin.",
+        "description": "Updates an existing customer for the authenticated tenant",
         "operationId": "update_3",
         "parameters": [
           {
@@ -6724,7 +6724,7 @@
                 }
               }
             },
-            "description": "Missing customers:write scope, or shop-access-denied: requiredRole SHOP_MANAGER (the caller is not a shop manager or Group admin)"
+            "description": "Missing customers:write scope, or shop-access-denied: the caller holds no shop grant (D-06)"
           },
           "404": {
             "content": {

  Either the snapshot was hand-edited, or the running image predates the branch.
  Regenerate with ./gradlew :core-java:updateOpenApiSnapshot, then REBUILD the service
  — docker compose start does not rebuild.
------------------------------------------------------------------------------
RESULT: FAIL
openapi-fresh rc=1

=== Task 3 Step 1: core-java AFTER rebuild (2026-10-10T18:45:41Z)
commands: docker compose -f docker-compose.full-stack.yml build core-java (rc 0); up -d --no-deps --force-recreate core-java (rc 0); healthy at 18:45:31Z
core-java container e74c71e6a1c8bea7bdcce29857299d05c129418dee6cb9c5450711828705dcc6 image sha256:7cfe073318160800ce20fc5ec1b0161dc8df0fa4b30563b16c7ebbd212b3126e started 2026-10-10T18:45:05.202513411Z
core-java image sha256:7cfe073318160800ce20fc5ec1b0161dc8df0fa4b30563b16c7ebbd212b3126e LastTagTime 2026-10-10T18:44:58.775078283Z
frontend container c5df211633c722896e9aa2b80b63b4bf4e7bd9d4066b28d05075ac180c57310f image sha256:d3a94f8c5c2f463d43786df188b3931becbdc640ad355b23b4bfbba7bd45887d (unchanged by this rebuild)
GET /v3/api-docs curl rc=0 bytes=229110
live POST /api/v1/customers 403: Missing customers:write scope, or shop-access-denied: requiredRole SHOP_MANAGER (the caller is not a shop manager or Group admin)
live DELETE 403: Missing customers:write scope, or shop-access-denied: requiredRole GROUP_ADMIN
--- check-openapi-snapshot-fresh.sh AFTER
openapi-fresh rc=0

------------------------------------------------------------------------------
RESULT: PASS — the running service emits everything the committed contract claims.
--- check-runtime-freshness.sh AFTER
Runtime freshness gate
  compose file : /home/sanmi/IdeaProjects/JToye_OaaS_2026/docker-compose.full-stack.yml
  repo root    : /home/sanmi/IdeaProjects/JToye_OaaS_2026
  HEAD         : 56808fd6 (phase-37-ops-readiness)

  core-java    FRESH  image tagged 2026-10-10 18:44:58 UTC >= newest build-input commit 8c79c178 (2026-10-10 18:20:47 UTC)
  edge-go      note   .dockerignore kept as build inputs (not translatable exactly): *.exe *.dll *.so *.dylib *.test *.out *.swp *.swo *.log
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  frontend     FRESH  image tagged 2026-10-10 18:38:08 UTC >= newest build-input commit bf823ff1 (2026-10-10 18:33:03 UTC)
  mcp-server   note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)

PASS: 4 running built service(s) match the source tree (0 unverified).
freshness rc=0
```

Both gates failed before the rebuild on exactly the new floor (check-openapi-snapshot-fresh rc 1 on the customer descriptions; check-runtime-freshness reported core-java DRIFT on 8c79c178 in section 1) and pass after (rc 0, PASS: 4). The frontend container is the one Task 2 created, unchanged by this rebuild.

## 3. Task 3: the customer floor live (option E), probe user via the Admin API (37-22 section 4 recipe)

```
user-profile config sha256 before: ec2ff1bd2f5b929c
create probe user ppb-probe-1791658020 via admin API (tenant_id ...0001, no grant): curl_rc=0 HTTP 201 
probe sub=5c3e48d3-f0fe-4600-ae57-e0bfb5c9c8ac; claims: {"tenant_id": "00000000-0000-0000-0000-000000000001", "preferred_username": "ppb-probe-1791658020", "scope": "customers:write email catalog:read catalog:write orders:write profile"}
== 2026-10-10T18:47:01Z probe email ppb-floor-1791658021@example.test, shop mama-ades-kitchen=24be502b-ca1a-4d3a-b7ea-d8306dfb749b
F-0 probe staff/me (touch directory): GET /api/v1/staff/me -> curl_rc=0 HTTP 200 {"userId":"5c3e48d3-f0fe-4600-ae57-e0bfb5c9c8ac","groupAdmin":false,"grantedShopIds":[],"tenantWideRole":null,"businessName":"Tenant A"}
F-1 admin grant STAFF on mama-ades-kitchen: POST /api/v1/staff/grant -> curl_rc=0 HTTP 201 {"id":"955c9f04-3ee2-4c75-b57b-1e9e26ae4fd6","userId":"5c3e48d3-f0fe-4600-ae57-e0bfb5c9c8ac","shopId":"24be502b-ca1a-4d3a-b7ea-d8306dfb749b","role":"STAFF","grantSource":"OPERATOR","createdAt":"2026-10-10T19:47:02.34029+01:00","createdBy":"99d11593-ea98-4891-a136-220884094283"}
F-2 STAFF GET customers (expect 200): GET /api/v1/customers?size=1 -> curl_rc=0 HTTP 200 {"content":[{"id":"bbc0183a-93a8-46d0-b647-b3932799c0fc","tenantId":"00000000-0000-0000-0000-000000000001","name":"D06 Probe","email":"d06-probe@example.test","phone":null,"allergenRestrictions":0,"createdAt":"2026-10-09T09:08:36.164107Z","updatedAt":"2026-10-09T09:08:36.161922Z"}],"empty":false,"first":true,"last":false,"number
F-3 STAFF POST customer (expect 403 SHOP_MANAGER): POST /api/v1/customers -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/customers","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","requiredRole":"SHOP_MANAGER"}
   customers with the probe email after F-3: 0
F-4 STAFF PUT existing customer, unchanged body (expect 403 SHOP_MANAGER): PUT /api/v1/customers/bbc0183a-93a8-46d0-b647-b3932799c0fc -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/customers/bbc0183a-93a8-46d0-b647-b3932799c0fc","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","requiredRole":"SHOP_MANAGER"}
   existing customer after F-4: D06 Probe|d06-probe@example.test|2026-10-09 09:08:36.161922+00
F-5 admin revoke the STAFF grant (expect 204): DELETE /api/v1/staff/955c9f04-3ee2-4c75-b57b-1e9e26ae4fd6 -> curl_rc=0 HTTP 204 
F-6 admin grant SHOP_MANAGER on mama-ades-kitchen: POST /api/v1/staff/grant -> curl_rc=0 HTTP 201 {"id":"59a48956-2f87-41a5-a820-6de762140ff1","userId":"5c3e48d3-f0fe-4600-ae57-e0bfb5c9c8ac","shopId":"24be502b-ca1a-4d3a-b7ea-d8306dfb749b","role":"SHOP_MANAGER","grantSource":"OPERATOR","createdAt":"2026-10-10T19:47:02.96607+01:00","createdBy":"99d11593-ea98-4891-a136-220884094283"}
F-7 SHOP_MANAGER POST customer, same body as F-3 (expect 201): POST /api/v1/customers -> curl_rc=0 HTTP 201 {"id":"0a111c94-a802-4899-a5bf-b6d6d15bc863","tenantId":"00000000-0000-0000-0000-000000000001","name":"PPB Floor Probe","email":"ppb-floor-1791658021@example.test","phone":"07700900123","allergenRestrictions":0,"createdAt":"2026-10-10T19:47:03.009126+01:00","updatedAt":"2026-10-10T19:47:03.006960053+01:00"}
   customers with the probe email after F-7 (positive control for F-3's 0): 1
F-8 SHOP_MANAGER PUT the new customer (expect 200): PUT /api/v1/customers/0a111c94-a802-4899-a5bf-b6d6d15bc863 -> curl_rc=0 HTTP 200 {"id":"0a111c94-a802-4899-a5bf-b6d6d15bc863","tenantId":"00000000-0000-0000-0000-000000000001","name":"PPB Floor Renamed","email":"ppb-floor-1791658021@example.test","phone":null,"allergenRestrictions":0,"createdAt":"2026-10-10T18:47:03.009126Z","updatedAt":"2026-10-10T19:47:03.12782411+01:00"}
   name by SQL after F-8: PPB Floor Renamed
F-9 SHOP_MANAGER DELETE the new customer (expect 403 GROUP_ADMIN): DELETE /api/v1/customers/0a111c94-a802-4899-a5bf-b6d6d15bc863 -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/customers/0a111c94-a802-4899-a5bf-b6d6d15bc863","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","requiredRole":"GROUP_ADMIN"}
   rows for the new customer after F-9: 1
F-10 admin DELETE the new customer (expect 204): DELETE /api/v1/customers/0a111c94-a802-4899-a5bf-b6d6d15bc863 -> curl_rc=0 HTTP 204 
   rows for the new customer after F-10: 0
cleanup: admin revoke the SHOP_MANAGER grant (expect 204): DELETE /api/v1/staff/59a48956-2f87-41a5-a820-6de762140ff1 -> curl_rc=0 HTTP 204 
cleanup: Keycloak DELETE probe user: curl_rc=0 HTTP 204
cleanup check: Keycloak GET probe user: HTTP 404 (expect 404)
residue by SQL as the superuser: customers with the probe email=0; shop_staff rows for the probe sub=0; positive control (the existing customer)=1; positive control (shop_staff rows in tenant 0001)=9
user_directory row for the probe sub (stays, as 37-15/37-22 probes did): 1
user-profile config sha256 after: ec2ff1bd2f5b929c (unchanged: yes)
```

| Claim | Result | Fail direction shown |
|---|---|---|
| STAFF reads customers | F-2 GET 200 | F-3/F-4: the same STAFF token's writes are refused |
| STAFF cannot create | F-3 POST 403 shop-access-denied, requiredRole SHOP_MANAGER; 0 rows with the email | F-7: the same user as SHOP_MANAGER, same body, 201; 1 row |
| STAFF cannot edit | F-4 PUT 403 SHOP_MANAGER; the row's updated_at unchanged | F-8: SHOP_MANAGER PUT 200, name re-read by SQL |
| SHOP_MANAGER cannot delete | F-9 DELETE 403 requiredRole GROUP_ADMIN; row present (1) | F-10: admin-user (realm admin) DELETE 204; 0 rows |

Residue: 0 customers with the probe email and 0 shop_staff rows for the probe sub (positive controls 1 and 9 from the same queries); the Keycloak user is 404; the jtoye-dev user-profile configuration is byte-identical before and after (sha256 prefix). Left behind, as 37-15/37-22's probes did: the probe's user_directory row, and the customers_aud revisions of the created-then-deleted probe customer (audit history, append-only).

## 4. Task 3: the vendor realm (D-14 amended, owner ruling 2026-10-10)

### Before, read from the running server

```
[before] GET /admin/realms/jtoye-dev: HTTP 200 bytes=4800
[before] jtoye-dev ssoSessionMaxLifespan/revokeRefreshToken/ssoSessionIdleTimeout = 7200 / False / 1800 (refreshTokenMaxReuse 0)
[before] jtoye-customers ssoSessionMaxLifespan/revokeRefreshToken = 7200 / True
[before] double redeem: refresh with RT1, attempt 1 -> HTTP 200
[before] double redeem: refresh with RT1, attempt 2 -> HTTP 200
control: refresh with a bogus token -> HTTP 400 (expected 400: the instrument does report a refused redeem)
```

### Render (docker compose -f docker-compose.full-stack.yml up --no-deps keycloak-realm-render, rc 0)

```
rendered infra/keycloak/realm-export.json (gitignored): realm jtoye-dev ssoSessionMaxLifespan 50400 revokeRefreshToken False ssoSessionIdleTimeout 1800
```

### Inventory: what an override import would reset, and the path chosen

```
template users: 8 ['admin-user', 'admin-user-b', 'service-account-core-api', 'service-account-edge-api', 'service-account-integration-catalog-ro', 'service-account-integration-orders-rw', 'tenant-a-user', 'tenant-b-user']
live users: 7
   TEMPLATE 99d11593-ea98-4891-a136-220884094283 admin-user None
   TEMPLATE ad3f1b02-b0b0-4c37-9d96-000000000b02 admin-user-b None
   OUTSIDE  e15bba70-053d-4645-899e-9b54357e8a39 d06-fresh-1791536915 1791536915619
   OUTSIDE  6c959abd-9a25-478f-b032-87c59aa469a1 invitee-1791536648006@example.test 1791536650239
   OUTSIDE  38a1ea20-ffee-4e62-99b1-846e229e96c4 invitee-1791538275270@example.test 1791538277247
   TEMPLATE b24ef5ca-82d6-4cc6-900f-82db92e10ab7 tenant-a-user None
   TEMPLATE 2ef125c0-fc94-4c37-9d96-073ba4d98433 tenant-b-user None
live users outside the template: 3 ['d06-fresh-1791536915', 'invitee-1791536648006@example.test', 'invitee-1791538275270@example.test']
```

Three live users are outside the template (one D-06 probe and two 37-08 invitees, all created at runtime). `kc.sh import --override true` replaces the realm wholesale, so it would delete those three users and end every live SSO session in jtoye-dev; that is the recorded answer to "what --override resets". The apply therefore went through the Admin API with a body of exactly the one field, taken from the rendered file. The override import was NOT run and Keycloak was not restarted.

```
PUT /admin/realms/jtoye-dev: curl_rc=0 HTTP 204 
[after] GET /admin/realms/jtoye-dev: HTTP 200 bytes=4801
[after] jtoye-dev ssoSessionMaxLifespan/revokeRefreshToken/ssoSessionIdleTimeout = 50400 / False / 1800 (refreshTokenMaxReuse 0)
[after] jtoye-customers ssoSessionMaxLifespan/revokeRefreshToken = 7200 / True
[after] double redeem: refresh with RT1, attempt 1 -> HTTP 200
[after] double redeem: refresh with RT1, attempt 2 -> HTTP 200
realm JSON key-by-key diff before->after: changed keys = ['ssoSessionMaxLifespan']
   ssoSessionMaxLifespan : 7200 -> 50400
users/count after apply: 7 (7 before)
fail direction (before vs before): changed keys = []
diff fail-direction rc=1 (expected 1)
```

Reading: jtoye-dev reads 50400 / False / 1800 (7200 / False / 1800 before); the key-by-key diff of the full realm JSON lists exactly ssoSessionMaxLifespan (its fail direction, the same diff of the before file against itself, lists nothing and exits 1); jtoye-customers is 7200 / True before and after; the user count is 7 before and after; a refresh token redeemed twice is 200 then 200 before AND after the apply, so rotation stays off (the instrument reports a refused redeem as 400: the bogus-token control).

## 5. Task 3: sign-in after the change

```
=== Task 3 Step 4: sign-in after the realm change (2026-10-10T18:48:26Z)
password grant core-api admin-user: curl_rc=0 HTTP 200 (access_token present refresh_expires_in 1800)
password grant core-api tenant-a-user: curl_rc=0 HTTP 200 (access_token present refresh_expires_in 1800)
$ node evidence/live-hours.cjs notices (re-run after the realm apply)
PASS [notices] signed in as admin-user and reached /dashboard :: "http://localhost:3000/dashboard"
PASS [notices] card, one unreadable day: the ruled singular sentence :: "Check the opening hours for Peckham Jollof Co.We could not read the hours you entered for Tuesday, so customers can order on that day at any time. Check and save your hours.Check hours"
PASS [notices] card: the 37-21 wording is gone :: null
PASS [notices] card, two unreadable days: the ruled plural sentence :: "Check the opening hours for Peckham Jollof Co.We could not read the hours you entered for Tuesday and Thursday, so customers can order on those days at any time. Check and save your hours.Check hours"
RESULT [notices] ALL PASS
live-hours notices rc=0
```

## 6. Step 7 close: runtime parity once more (2026-10-10T19:00:18Z, HEAD 88b81aec)

```
  core-java    FRESH  image tagged 2026-10-10 18:44:58 UTC >= newest build-input commit 8c79c178 (2026-10-10 18:20:47 UTC)
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     FRESH  image tagged 2026-10-10 18:38:08 UTC >= newest build-input commit bf823ff1 (2026-10-10 18:33:03 UTC)
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)
PASS: 4 running built service(s) match the source tree (0 unverified).
freshness rc=0
```

No commit after 8c79c178 (core-java) and bf823ff1 (frontend) touched a build path; the later commits are .planning/ and infra/keycloak records.
