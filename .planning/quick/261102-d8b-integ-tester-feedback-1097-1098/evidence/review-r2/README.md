# Review round 2 finding: V89 failed open under a malformed tenant setting

Finding (admitted, reproduced by the reviewer on the dev DB as `jtoye_app`): V89 keyed "anonymous" on
`current_tenant_id() IS NULL`, and V51's helper also returns NULL for a non-UUID
`app.current_tenant_id`, so a malformed setting read as anonymous and saw every published menu.

Fix: the policy's first conjunct is now
`COALESCE(current_setting('app.current_tenant_id', true), '') IN ('', 'default')`, compared as text and
never cast. V89 is unmerged, so it was edited in place (no V90).

## New arm: `ProductsPublicReadRlsIntegrationTest.malformedTenantSettingFailsFiltered`

As `rls_test_role` (NOSUPERUSER NOBYPASSRLS), with `app.current_tenant_id = 'not-a-uuid'`, it counts the
seeded products and expects 0. It also reads the setting back inside the same connection and asserts it is
`not-a-uuid` when the read runs.

That read-back is load-bearing. The first version of the arm set the value through separate JdbcTemplate
calls, and `TenantSetLocalAspect` resets the setting to DEFAULT before every JdbcTemplate call when no
tenant is pinned. Its read-back failed (`expected "not-a-uuid" but was ""`). Without the read-back, that
arm would have passed vacuously on the defective V89. The arm now runs on one raw connection through a
`ConnectionCallback`, the pattern `RlsUuidCastSafetyIntegrationTest` uses.

| Run | Tree | Result |
|---|---|---|
| RED | commit a625cac7, the current V89 (helper guard) | 13 tests, 1 failed: `expected: 0L but was: 1L`; read-back passed; the other 12 green. `red-malformed-setting-on-v89-helper-guard.txt` |
| GREEN | the raw-setting guard | rc 0: ProductsPublicReadRls 13/13, ShopSearchMatchesDishes 7/7, ProductSearchFts 17/17, RlsContractTest 7/7, 0 skipped. `green-four-suites-after-raw-setting-guard.txt` |

Results directory cleared before each run; each Testcontainers class migrates a fresh database, so the run
applies the edited V89.
