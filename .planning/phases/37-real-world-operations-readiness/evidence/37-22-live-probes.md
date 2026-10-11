# 37-22 live probes on the rebuilt compose runtime (WINDOWS #30, #33; 37-22 storefront)

Runtime: `docker compose -f docker-compose.full-stack.yml up -d --build frontend core-java` at HEAD `cb8cf9d1`
(phase-37-ops-readiness). Tokens: the `core-api` client, password grant (people). The ungranted vendor was created
for this run through the Keycloak admin API (`tenant_id` 0001, no grant) and deleted afterwards (204); no grant was
left behind (0 rows). Every request below printed its curl rc and HTTP code; there were no transport failures.

## 1. The rebuild: both containers recreated, freshness by content

```
== BEFORE rebuild 2026-10-10T14:59:29Z at HEAD cb8cf9d1
frontend container=c5e633c4cee67f589fcd0bcf74fdab496e5ab2421b777388ee80a166e2ef1aa9 started=2026-10-10T13:46:05.729852536Z image=sha256:809cf75cdf0a8a7f9ad52eff7692ea2a606c4bb962846325beb48e4cd93cba10
core-java container=b508508089af390299f402ce2e0ad8679957fc6687c879a2e1057cce5e2e12a5 started=2026-10-09T10:30:12.08520418Z image=sha256:94e8e7b2cad06af83571b636106ead2522e874f0a49be5e9fa177a374e53576f
OLD frontend bundle grep for the notice copy: rc=0 hits=[]
OLD frontend positive control 'Open now': [/app/.next/server/chunks/ssr/app_shop_[slug]_shop-detail-client_tsx_00p8zab._.js
/app/.next/static/chunks/3hgflrqfu18k4.js]
OLD core-java jar ShopAvailabilityPolicy/V80: rc=1 []
OLD core-java positive control V79 entries: 1

== AFTER rebuild 2026-10-10T15:01:22Z (rebuild started 2026-10-10T14:59:35Z)
frontend container=8945c8306e73eb0a2335eae16ec8a2fd7e800b543ba0bdbcaae4659d59a2c40a started=2026-10-10T15:00:48.565314641Z image=sha256:5d359e69eb485304d6758e527a1566c114a95386d0cb5033a30cae5657384120 health=healthy
core-java container=2128456c047feb2e63965ab5a1187820277216077a7cdce3a2425a8df33c01ca started=2026-10-10T15:00:27.569618012Z image=sha256:9d3b9884870a589016e27e9a91217c50be46fd449c3bacdd72e1f57c13ebfc46 health=healthy
NEW frontend bundle grep for the notice copy: [/app/.next/server/chunks/ssr/[root-of-the-server]__1mzgw2-._.js
/app/.next/server/chunks/ssr/_0mkeu42._.js
/app/.next/server/chunks/ssr/_20n-2px._.js]
NEW frontend bundle grep for the race copy: [/app/.next/server/chunks/ssr/[root-of-the-server]__1mzgw2-._.js
/app/.next/server/chunks/ssr/_0mkeu42._.js]
NEW core-java jar: [     2612  02-01-1980 00:00   BOOT-INF/classes/uk/jtoye/core/storefront/ShopAvailabilityPolicy$Availability.class
     1519  02-01-1980 00:00   BOOT-INF/classes/uk/jtoye/core/storefront/ShopAvailabilityPolicy$Reason.class
    10834  02-01-1980 00:00   BOOT-INF/classes/uk/jtoye/core/storefront/ShopAvailabilityPolicy.class
    14767  02-01-1980 00:00   BOOT-INF/classes/db/migration/V80__shop_opening_schedule_pause.sql]
```

The old frontend bundle had no notice copy (grep empty) while its positive control ("Open now") matched; the old
jar had no `ShopAvailabilityPolicy` and no `V80__` (rc 1) while its positive control (`V79__`) matched once. After the
rebuild both containers have new ids, StartedAt after the rebuild began, new image ids, and carry the new content.

## 2. V80 on the shared dev database, and the seeder's declaration (live)

```
80|shop opening schedule pause|t|2026-10-10 16:00:33.298114
79|financial transactions shop id|t|2026-10-09 10:00:34.630675
78|order void ledger reversal|t|2026-10-09 10:00:33.680972
-- core-java log lines naming V80 / NOTICE / DemoDataSeeder:
2026-10-10 16:00:33 [core-java,,] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "80 - shop opening schedule pause"
2026-10-10 16:00:33 [core-java,,] INFO  o.f.c.i.s.DefaultSqlScriptExecutor - DB: V80: classified shops: LEGACY_ALWAYS_OPEN=5, MIGRATED_FROM_TEXT=0, NEEDS_REVIEW=0
2026-10-10 16:00:33 [core-java,,] INFO  o.f.core.internal.command.DbMigrate - Successfully applied 1 migration to schema "public", now at version v80 (execution time 00:00.067s)
2026-10-10 16:00:43 [core-java,,] INFO  uk.jtoye.core.dev.DemoDataSeeder - DemoDataSeeder complete for tenant 00000000-0000-0000-0000-000000000001: 0 shop(s) created, 0 product(s) created, 0 customer(s) created, 0 non-curated product(s) quarantined, 0 shop(s) unpublished, 0 demo image(s) seeded, 0 demo trader identity(ies) created, 0 demo shop email(s) set, 0 service-account grant(s) and 0 service-account directory entry(ies) created.
2026-10-10 16:00:43 [core-java,,] INFO  uk.jtoye.core.dev.DemoDataSeeder - DemoDataSeeder tenant-B probe seeded
-- shops after V80 + seeder:
brixton-village-grill|STRUCTURED|t||f|<null>
mama-ades-kitchen|STRUCTURED|t||f|<null>
peckham-jollof-co|STRUCTURED|t||f|{}
tenant-b-probe|STRUCTURED|t||f|<null>
unsorted-legacy-items|STRUCTURED|t||f|<null>
-- live: shops whose schedule is the declared all-day week (expect 5 of 5):
5|5
-- control (the same query with a Monday 09:00-17:00 week must count 0):
0
```

V80 applied cleanly (`success t`). Its notice: `LEGACY_ALWAYS_OPEN=5, MIGRATED_FROM_TEXT=0, NEEDS_REVIEW=0` (the dev
DB had `opening_hours` NULL or `{}` on all five shops at V79). Ten seconds later the 37-22 seeder declared all five
STRUCTURED with the all-day week (5 of 5 by jsonb equality; control query with a Monday-only week counts 0). This is
the LEGACY -> STRUCTURED path the IT `aDemoShopV80MarkedLegacyIsDeclaredOnTheNextRun` proves, observed live.

## 3. The gates (verbatim)

```
scripts/check-runtime-freshness.sh -> rc=0
  repo root    : /home/sanmi/IdeaProjects/JToye_OaaS_2026
  HEAD         : cb8cf9d1 (phase-37-ops-readiness)

  core-java    FRESH  image tagged 2026-10-10 15:00:18 UTC >= newest build-input commit cb8cf9d1 (2026-10-10 14:27:29 UTC)
  edge-go      note   .dockerignore kept as build inputs (not translatable exactly): *.exe *.dll *.so *.dylib *.test *.out *.swp *.swo *.log
  edge-go      FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 5c1bb364 (2026-08-30 20:53:05 UTC)
  frontend     note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  frontend     FRESH  image tagged 2026-10-10 15:00:06 UTC >= newest build-input commit cb8cf9d1 (2026-10-10 14:27:29 UTC)
  mcp-server   note   .dockerignore kept as build inputs (not translatable exactly): npm-debug.log* yarn-debug.log* yarn-error.log* .env*.local *.swp *.swo *.log
  mcp-server   FRESH  image tagged 2026-10-09 09:23:43 UTC >= newest build-input commit 394edcda (2026-10-07 21:43:19 UTC)

PASS: 4 running built service(s) match the source tree (0 unverified).

scripts/check-openapi-snapshot-fresh.sh -> rc=0

A-1  every key the contract declares, as the running service emits it
  PASS — 0 differences across 121 declared path(s).

------------------------------------------------------------------------------
RESULT: PASS — the running service emits everything the committed contract claims.

scripts/seed-order-metric.sh -> rc=0
  series before : 0
  shop       : brixton-village-grill (minimum 1000p)
  product    : 1eafcdac-2b7f-49f5-b6a7-d2e008e6812a x 1 (declared allergen mask 1)
  placed     : HTTP 201  ORD-00000000-20261010-8E4955C0
  series after  : 1
PASS: the NoOrdersCreated counter now exists — the rule can fire.
```

Fail direction for the two gates: the pre-rebuild runtime is the broken input. Its jar lacked V80 and
`ShopAvailabilityPolicy` (section 1), and 37-20 recorded `check-openapi-snapshot-fresh.sh` reporting the 274 added
snapshot lines against that runtime (WINDOWS #33). The gates were not re-run on the old images before the rebuild
(an omission recorded here, not a claim).

## 4. The probes (command lines are in the labels; bodies cut at 330 characters)

```
create probe user d22-probe-1791644528 via Keycloak admin API: curl_rc=0 HTTP 400 {"errorMessage":"Password policy not met"}
create probe user d22-probe-1791644528 via Keycloak admin API (tenant_id ...0001, no grant): curl_rc=0 HTTP 201 
probe token obtained; claims: {"tenant_id":"00000000-0000-0000-0000-000000000001","roles":["offline_access","uma_authorization","default-roles-jtoye-dev"],"preferred_username":"d22-probe-1791644528","scope":"customers:write email catalog:read catalog:write orders:write profile"}
== WINDOWS #30 (customer gate), probe user sub=0f4751fb-713b-48e8-91ad-6064a71bb81e, shop mama-ades-kitchen=24be502b-ca1a-4d3a-b7ea-d8306dfb749b (2026-10-10T15:02:27Z)
30-1 ungranted staff/me: GET /api/v1/staff/me -> curl_rc=0 HTTP 200 {"userId":"0f4751fb-713b-48e8-91ad-6064a71bb81e","groupAdmin":false,"grantedShopIds":[],"tenantWideRole":null,"businessName":"Tenant A"}
30-2 ungranted GET customers (expect 403 shop-access-denied): GET /api/v1/customers -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/customers","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","requiredRole":"STAFF"}
30-3 CONTROL admin GET customers (expect 200): GET /api/v1/customers?size=1 -> curl_rc=0 HTTP 200 {"content":[{"id":"bbc0183a-93a8-46d0-b647-b3932799c0fc","tenantId":"00000000-0000-0000-0000-000000000001","name":"D06 Probe","email":"d06-probe@example.test","phone":null,"allergenRestrictions":0,"createdAt":"2026-10-09T09:08:36.16
== WINDOWS #33 (pause): an ungranted user cannot pause
33-0 ungranted PUT pause (expect 403): PUT /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","shopId":"24b
33-0b DB unchanged after the refused pause: GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded yam done p
33-0b public after refused pause: 
33-0b public after the refused pause (expect openNow true, no pause): GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, eg
   -> {"openNow":true,"pausedUntil":null,"pausedIndefinitely":false,"legacyAlwaysOpen":false,"hoursSource":"STRUCTURED"}
== grant STAFF on mama-ades-kitchen to the probe user (as admin-user)
30-4 admin POST staff/grant STAFF: POST /api/v1/staff/grant -> curl_rc=0 HTTP 201 {"id":"7216eb0a-a418-4bf8-8e73-f28a79391f5c","userId":"0f4751fb-713b-48e8-91ad-6064a71bb81e","shopId":"24be502b-ca1a-4d3a-b7ea-d8306dfb749b","role":"STAFF","grantSource":"OPERATOR","createdAt":"2026-10-10T16:02:36.873328+01:00","createdBy":"99d1159
30-5 STAFF GET customers, SAME token as 30-2 (expect 200): GET /api/v1/customers?size=1 -> curl_rc=0 HTTP 200 {"content":[{"id":"bbc0183a-93a8-46d0-b647-b3932799c0fc","tenantId":"00000000-0000-0000-0000-000000000001","name":"D06 Probe","email":"d06-probe@example.test","phone":null,"allergenRestrictions":0,"createdAt":"2026-10-09
== WINDOWS #33: STAFF pauses 20 minutes (2026-10-10T15:03:52Z)
33-1 STAFF PUT pause {minutes:20} (expect 200): PUT /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and 
33-2 GET public shop (expect openNow false, pausedUntil set): GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and 
   -> {"openNow":false,"nextOpenAt":"2026-10-10T16:23:52+01:00","pausedUntil":"2026-10-10T15:23:52Z","pausedIndefinitely":false,"legacyAlwaysOpen":false,"hoursSource":"STRUCTURED"}
33-3 storefront guest order while paused (expect 409 shop-closed PAUSED): POST /public/shops/mama-ades-kitchen/orders -> curl_rc=0 HTTP 409 {"detail":"Mama Ade's Kitchen isn't taking orders (paused). Reopens at 16:23.","instance":"/public/shops/mama-ades-kitchen/orders","status":409,"title":"Shop not taking orders","type":"https
   orders with this key in the DB after the refusal: 0 reservation(s), 0 order(s)
== WINDOWS #33: STAFF resumes (2026-10-10T15:07:52Z)
33-4 STAFF DELETE pause (expect 2xx): DELETE /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded
33-5 GET public shop after resume (expect openNow true): GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pound
   -> {"openNow":true,"nextOpenAt":null,"pausedUntil":null,"pausedIndefinitely":false}
33-6 the SAME guest order and Idempotency-Key after resume (expect 201): POST /public/shops/mama-ades-kitchen/orders -> curl_rc=0 HTTP 201 {"orderNumber":"ORD-00000000-20261010-AE0346A5","status":"PENDING","subtotalPennies":1100,"deliveryFeePennies":0,"vatRate":"STANDARD","vatAmountPennies":183,"totalAmountPennies":1100,"shopNam
   positive control for the 33-3 zero counts (same queries): 1 reservation(s), 1 order(s)
== WINDOWS #30: revoke the grant; the SAME token string's next request is refused
30-6 STAFF GET customers with this token (expect 200): GET /api/v1/customers?size=1 -> curl_rc=0 HTTP 200 {"content":[{"id":"bbc0183a-93a8-46d0-b647-b3932799c0fc","tenantId":"00000000-0000-0000-0000-000000000001","name":"D06 Probe","email":"d06-probe@example.test","phone":null,"allergenRestrictions":0,"createdAt":"2026-10-09T09:
30-7 admin DELETE staff/{grantId} (expect 204): DELETE /api/v1/staff/7216eb0a-a418-4bf8-8e73-f28a79391f5c -> curl_rc=0 HTTP 204 
30-8 the SAME token string as 30-6, next GET customers (expect 403 shop-access-denied): GET /api/v1/customers?size=1 -> curl_rc=0 HTTP 403 {"detail":"Shop access denied","instance":"/api/v1/customers","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied","requiredRole":"STAFF"}
== screenshot re-take (cookie banner dismissed): admin pauses until reopened (2026-10-10T15:08:20Z)
33-7 admin PUT pause {untilReopened:true} (expect 200): PUT /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, eg
33-8 GET public (expect openNow false, pausedIndefinitely true): GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi a
   -> {"openNow":false,"nextOpenAt":null,"pausedUntil":null,"pausedIndefinitely":true}
33-9 admin DELETE pause (expect 200): DELETE /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded
33-10 GET public after resume (expect openNow true): GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded y
   -> {"openNow":true,"pausedUntil":null,"pausedIndefinitely":false}
== screenshot re-take, timed: admin pauses 20 minutes (2026-10-10T15:08:40Z)
33-11 admin PUT pause {minutes:20}: PUT /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded yam 
33-12 admin DELETE pause: DELETE /api/v1/shops/24be502b-ca1a-4d3a-b7ea-d8306dfb749b/pause -> curl_rc=0 HTTP 200 {"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"createdAt":"2026-08-30T14:23:25.814874Z","deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded yam done pr
33-13 GET public after resume: GET /public/shops/mama-ades-kitchen -> curl_rc=0 HTTP 200 {"acceptsCardPayments":false,"address":"48 Rye Lane, Peckham, London SE15 5BS","bannerUrl":null,"deliveryFeePennies":350,"deliveryInfo":null,"description":"Home-style West African cooking — jollof, egusi and pounded yam done properly.","di
   -> {"openNow":true,"pausedUntil":null,"pausedIndefinitely":false}
cleanup: Keycloak DELETE probe user d22-probe-1791644528: curl_rc=0 HTTP 204 (its user_directory row stays, as 37-15's probes did)
cleanup: remaining grants for the probe user: 0
```

Reading of the probes, each with its fail direction:

| Probe | Result | Fail direction shown |
|---|---|---|
| #30 ungranted `GET /api/v1/customers` | 403 `shop-access-denied`, `requiredRole STAFF` (30-2) | the admin control on the same endpoint is 200 (30-3); the same user with STAFF is 200 (30-5) |
| #30 STAFF `GET /api/v1/customers` | 200 (30-5, 30-6) | the user before the grant (30-2) and the same token string after the revoke (30-8) are 403 |
| #30 revoke -> next request | `DELETE /api/v1/staff/{id}` 204; the same token string's next call 403 (30-6 -> 30-8) | 30-6 is the 200 immediately before |
| #33 the pause is gated | ungranted `PUT .../pause` 403 (33-0); the public shop unchanged after it (33-0b openNow true) | STAFF's `PUT .../pause` 200 (33-1) |
| #33 paused -> storefront refuses | `openNow false`, `pausedUntil` 20 min ahead (33-2); guest order 409 `shop-closed` `reason PAUSED`, `reopensAt` set (33-3); 0 reservations and 0 orders for the key | after `DELETE .../pause` (33-4, 200) the public shop reads `openNow true` (33-5) and the SAME body and Idempotency-Key are accepted 201 (33-6); the same two queries then count 1 and 1 |
| #33 `GET /public/shops/{slug}` shows openNow | `openNow`, `nextOpenAt`, `pausedUntil`, `pausedIndefinitely`, `legacyAlwaysOpen false`, `hoursSource STRUCTURED` present | open (33-0b, 33-5) vs paused (33-2) vs indefinite (33-8: `pausedIndefinitely true`, no `nextOpenAt`) |

Observed wire format (recorded because the frontend parses it): `pausedUntil` serialises in UTC (`...Z`) and
`nextOpenAt`/`reopensAt` with the UK offset (`...+01:00`). `lib/shop-availability.ts` parses both and words them in
Europe/London; the jest fixtures cover both spellings.

The probe orders left on the dev tenant: `ORD-00000000-20261010-8E4955C0` (seed-order-metric) and
`ORD-00000000-20261010-AE0346A5` (33-6), both PENDING, notes say "Safe to delete". The ~20 stale seed orders were left alone.

## 5. The storefront in a real browser (Playwright, scratch script, compose frontend on :3000)

Screens: `evidence/37-22-screens/`. Phases: open (before any pause), paused (20 minutes, STAFF), paused until reopened
(admin; files suffixed `-indefinite`), resumed. Shop: Mama Ade's Kitchen. 390x844 unless stated.

```
PASS [open] shop page HTTP :: 200
PASS [open] pill reads Open now :: "Open now"
PASS [open] no availability notice :: 0
PASS [open] JSON-LD Restaurant present :: "found"
PASS [open] JSON-LD openingHoursSpecification = declared all-day week (7 x 00:00-23:59) :: ["Monday 00:00-23:59","Tuesday 00:00-23:59","Wednesday 00:00-23:59","Thursday 00:00-23:59","Friday 00:00-23:59","Saturday 00:00-23:59","Sunday 00:00-23:59"]
PASS [open] JSON-LD carries no pause :: 0
PASS [open] /shop card badge :: "Open"
PASS [open] landing card badge :: "Open"
PASS [open] checkout Place order enabled when open :: false
PASS [open] no aria-describedby on an open shop :: null
[open] ALL PASS

PASS [paused] shop page HTTP :: 200
PASS [paused] pill reads Paused (never Open now) :: "Paused"
PASS [paused] notice shown :: 1
PASS [paused] notice headline + Back at HH:MM :: "Mama Ade's Kitchen isn't taking orders right nowBack at 16:28."
PASS [paused] notice sits below the header and above the menu :: {"notice":415.5,"h1":181,"menu":666.5}
PASS [paused] JSON-LD Restaurant present :: "found"
PASS [paused] JSON-LD openingHoursSpecification = declared all-day week (7 x 00:00-23:59) :: ["Monday 00:00-23:59","Tuesday 00:00-23:59","Wednesday 00:00-23:59","Thursday 00:00-23:59","Friday 00:00-23:59","Saturday 00:00-23:59","Sunday 00:00-23:59"]
PASS [paused] JSON-LD carries no pause :: 0
PASS [paused] /shop card badge :: "Paused"
PASS [paused] landing card badge :: "Paused"
PASS [paused] checkout Place order disabled :: true
PASS [paused] aria-describedby points at the notice :: "shop-availability-explainer"
PASS [paused] the explainer is the K3 notice text :: "Mama Ade's Kitchen isn't taking orders right nowBack at 16:28."
PASS [paused] backstop: 60-char name wraps, no clipping at 360px / 130% root :: {"nameLength":60,"noticeScroll":316,"noticeClient":316,"headScroll":233,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":360,"headLines":5}
PASS [paused] backstop: a 60-char name with no spaces also wraps (overflow-wrap anywhere) :: {"nameLength":60,"noticeScroll":316,"noticeClient":316,"headScroll":233,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":361,"headLines":5}
PASS [paused] backstop INSTRUMENT ARM: the same measure FAILS when the headline cannot wrap :: {"nameLength":60,"noticeScroll":1009,"noticeClient":316,"headScroll":947,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":1031,"headLines":1}
[paused] ALL PASS

-- the 'until reopened' run (its one FAIL is the timed-pause assertion 'Back at HH:MM' meeting an indefinite pause; the text it found is the correct K3 indefinite copy):
PASS [paused] shop page HTTP :: 200
PASS [paused] pill reads Paused (never Open now) :: "Paused"
PASS [paused] notice shown :: 1
FAIL [paused] notice headline + Back at HH:MM :: "Mama Ade's Kitchen isn't taking orders right nowCheck back later."
PASS [paused] notice sits below the header and above the menu :: {"notice":415.5,"h1":181,"menu":666.5}
PASS [paused] JSON-LD Restaurant present :: "found"
PASS [paused] JSON-LD openingHoursSpecification = declared all-day week (7 x 00:00-23:59) :: ["Monday 00:00-23:59","Tuesday 00:00-23:59","Wednesday 00:00-23:59","Thursday 00:00-23:59","Friday 00:00-23:59","Saturday 00:00-23:59","Sunday 00:00-23:59"]
PASS [paused] JSON-LD carries no pause :: 0
PASS [paused] /shop card badge :: "Paused"
PASS [paused] landing card badge :: "Paused"
PASS [paused] checkout Place order disabled :: true
PASS [paused] aria-describedby points at the notice :: "shop-availability-explainer"
PASS [paused] the explainer is the K3 notice text :: "Mama Ade's Kitchen isn't taking orders right nowCheck back later."
PASS [paused] backstop: 60-char name wraps, no clipping at 360px / 130% root :: {"nameLength":60,"noticeScroll":316,"noticeClient":316,"headScroll":233,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":360,"headLines":5}
PASS [paused] backstop: a 60-char name with no spaces also wraps (overflow-wrap anywhere) :: {"nameLength":60,"noticeScroll":316,"noticeClient":316,"headScroll":233,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":361,"headLines":5}
PASS [paused] backstop INSTRUMENT ARM: the same measure FAILS when the headline cannot wrap :: {"nameLength":60,"noticeScroll":1009,"noticeClient":316,"headScroll":947,"headClient":233,"noticeRight":339,"viewport":360,"docScroll":1031,"headLines":1}
[paused] 1 FAILED

PASS [resumed] shop page HTTP :: 200
PASS [resumed] pill reads Open now :: "Open now"
PASS [resumed] no availability notice :: 0
PASS [resumed] JSON-LD Restaurant present :: "found"
PASS [resumed] JSON-LD openingHoursSpecification = declared all-day week (7 x 00:00-23:59) :: ["Monday 00:00-23:59","Tuesday 00:00-23:59","Wednesday 00:00-23:59","Thursday 00:00-23:59","Friday 00:00-23:59","Saturday 00:00-23:59","Sunday 00:00-23:59"]
PASS [resumed] JSON-LD carries no pause :: 0
PASS [resumed] /shop card badge :: "Open"
PASS [resumed] landing card badge :: "Open"
[resumed] ALL PASS
```

- The K3 long-text backstop (UI-SPEC row, verification: backstop): at 360px with root font-size 130%, a 60-character
  shop name (spaced, and a single 60-character token) wraps to 5 lines inside the notice with no clipping
  (`scrollWidth == clientWidth` on the notice and the headline, right edge 339 <= 360). Instrument arm: the same
  measure with `white-space: nowrap` on the headline reports `headScroll 947 > headClient 233`, so the check can fail.
  `notice-60char-360-root130.png`, `notice-60char-token-360-root130.png`.
- One measure of the no-space variant read `document.scrollWidth` 364 (and 361 in the second run) at 360px; a re-run
  listing every element whose right edge passes the viewport found none, and `scrollWidth` 360, in three states
  (no change, the token, a fresh page). The notice's own right edge was 339 every time. Recorded as transient
  (measured mid re-layout after the font-size change), not as a notice overflow.
- Not proven live: the checkout race UI (a shop pausing while the checkout page is open) is proven by the API 409
  (33-3) plus jest (`shop-closed.test.tsx`); the NEEDS_REVIEW JSON-LD rule is jest-only (no NEEDS_REVIEW shop exists
  on the dev DB). Both are a WINDOWS `unrun-verify` entry.
