# 37-02 Strict-ON red list: every test that fails with ACCESS_STRICT_SCOPING=true

**Measured:** 2026-10-08, on the integration branch `phase-37-ops-readiness` at
`323ee4057cb26e2662cc118d49ea5e7a3a5913c9` (after 37-02 Tasks 1-2: no test class can leak a
strict-scoping value any more, and the booted-value instrument exists).
**Purpose:** the input to 37-03 (the scope-gate integrationTest regression recipe: run the full
suite once under the new default, enumerate every red, then convert). Nothing under `core-java/`
changed in this task.

## 1. The two runs

Both runs are the same command, driven from one script, differing only in the variable:

```
./gradlew :core-java:cleanTest :core-java:test :core-java:cleanIntegrationTest :core-java:integrationTest --continue --no-daemon
```

| Run | Variable | Start / end (UTC) | Gradle rc | `:test` | `:integrationTest` | Booted value read by the instrument |
|-----|----------|-------------------|----------:|---------|--------------------|--------------------------------------|
| strict ON | `ACCESS_STRICT_SCOPING=true` exported in the same shell as Gradle | 07:49:07 / 08:17:54 | **1** | 187 suites, 1596 tests, 0 failed, 1 skipped | 173 suites, 894 tests, **29 failed**, 0 errors, 1 skipped | `env.ACCESS_STRICT_SCOPING=true expected=true booted=true property=true` |
| default | unset | 08:18:46 / 08:48:45 | **0** | 187 suites, 1596 tests, 0 failed, 1 skipped | 173 suites, 894 tests, **0 failed**, 0 errors, 1 skipped | `env.ACCESS_STRICT_SCOPING=null expected=false booted=false property=false` |

- Both `:test` and `:integrationTest` EXECUTED in both runs (`cleanTest`/`cleanIntegrationTest` first; the log shows
  `> Task :core-java:test` and `> Task :core-java:integrationTest`, never UP-TO-DATE).
- Results were read from the LIVE dir `core-java/build-local/test-results/` and copied out after each run (the next
  run's clean step deletes them). `core-java/build/` was not read.
- The instrument line is read out of `TEST-uk.jtoye.core.security.access.StrictScopingBootedValueIntegrationTest.xml`
  of each run, so each run proves which value the context actually booted with, not which value the shell held.
- The suite shape is identical in both modes (187 + 173 suites, 1596 + 894 tests, the same single skip in each source
  set: `ProductLabelGoldenFileTest` and `FinancialSummaryGoldenFileTest`), so the 29 reds are the entire delta.
- Gradle's own summary for the strict-ON integration task: `894 tests completed, 29 failed, 1 skipped`. The sum of the
  `failures=` attributes over the nine red XML headers is 1+4+2+2+6+4+1+1+8 = 29, and the per-testcase parser below
  emits exactly 29 lines: the three counts agree.

## 2. Every class that fails with ACCESS_STRICT_SCOPING=true

All 29 failures have one cause: the request is refused by the shop-access gate with the typed
`https://jtoye.uk/errors/shop-access-denied` 403. Each class authenticates with a token whose `sub` is a fresh random
UUID and which has no realm `admin` role and no `shop_staff` row. Under OFF that caller is an implicit tenant-wide
GROUP_ADMIN (`ShopAccessService.isGroupAdminForUser`, last line: `return !strictScoping && membership.perShopRole().isEmpty();`).
Under ON it has no access. None is a crash, a context failure or a data error: every message is a status or body
assertion seeing 403. "In the 21" means the class is one of the 21 files RESEARCH 37-B.1 found that describe the
implicit-admin dependency in prose.

| Class (path) | Set | Failed / tests | Failing methods | First assertion message | In the 21? | Principal (why it is ungranted) |
|--------------|-----|---------------:|-----------------|-------------------------|------------|---------------------------------|
| `core-java/src/test/java/uk/jtoye/core/integration/TypedNotFoundBodyIntegrationTest.java` | integrationTest | 1 / 13 | `GET /shops/{unknown} carries a typed problem body` | `Status expected:<404> but was:<403>` | no | a non-admin local `jwt()` builder with a random `sub`, no `ROLE_admin` |
| `core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java` | integrationTest | 4 / 7 | `AC-4.1: a retained FAILED asset re-drives to PENDING with a fresh outbox row`; `AC-4.2: a same-key replay echoes the original response and does not double-enqueue`; `AC-4.5: no retained bytes is a typed 409 — both halves independently`; `AC-4.6: the re-drive budget is enforced (T-27-03)` | `Status expected:<202> but was:<403>` (AC-4.5: `[half 1 — the bytes were never claimed] expected: 409 but was: 403`) | no | local `vendorJwt(tenantA)` with no grant; one of the 11 teardown-leak classes (its 3 green methods are AC-4.3, AC-4.8 and AC-4.4; AC-4.4 sets strict ON itself and expects the 403 for a SHOP_MANAGER of another shop, `vendorJwt(tenantA, sm)`) |
| `core-java/src/test/java/uk/jtoye/core/media/MediaUploadControllerTest.java` | integrationTest | 2 / 5 | `validUploadReturns202WithPendingAssetAndOutboxRow()`; `nearLimitFileWithinRequestBudgetIsNotRejected()` | `Status expected:<202> but was:<403>` | **yes** | local `jwt()` with a random `sub` |
| `core-java/src/test/java/uk/jtoye/core/media/MediaUploadIdempotencyTest.java` | integrationTest | 2 / 4 | `sameKeyReplaySameFile_returnsOriginalAsset_noSecondRow()`; `sameKeyDifferentFile_returns422()` | `Status expected:<202> but was:<403>` | no | local `jwt()` with a random `sub` |
| `core-java/src/test/java/uk/jtoye/core/product/ProductMayContainIntegrationTest.java` | integrationTest | 6 / 7 | `D-16: a vendor sets mayContainMask; it is returned and stored as sent, and allergen_mask is unchanged (SQL)`; `D-16 public: mayContainAllergens lists only the undeclared may-contain bits, in catalogue order; NULL and 0 give []`; `D-17: a productionDate whose use-by has already passed is a typed 422 naming productionDate`; `D-17: a productionDate after today (London) is a typed 422 naming productionDate`; `D-17: label without productionDate is produced 'today' in London (fixed clock 4 Oct 2026) and counts use-by from it`; `#861: label for productionDate=2026-10-03 prints 'Produced: 3 October 2026', 'Use by: 5 October 2026' and 'May contain: Sesame' (Milk is declared, so omitted)` | body `{"detail":"Shop access denied","instance":"/api/v1/products","status":403,"title":"Shop Access Denied","type":"https://jtoye.uk/errors/shop-access-denied",...}` | no | local `vendorJwt()` with a random `sub` (product create is the shop-gated write) |
| `core-java/src/test/java/uk/jtoye/core/product/ProductSaveAllergenWarningIntegrationTest.java` | integrationTest | 4 / 4 | `#787: POST 'rice, butter (MILK), pepper' with mask 0 -> 201 + one UNDECLARED_INGREDIENT_ALLERGEN warning naming Milk; stored allergen_mask stays 0`; `PUT the same product with Milk ticked -> 200, allergenWarnings [] and public undeclaredIngredientAllergens []`; `PGC-787 idempotency: the same PUT twice returns the same warnings and leaves the stored mask as sent`; `Pitfall 11: GET by id and the vendor product list both carry the same Milk warning, derived on read` | body `{"detail":"Shop access denied","instance":"/api/v1/products","status":403,...}` | no | local `vendorJwt()` with a random `sub` |
| `core-java/src/test/java/uk/jtoye/core/security/ScopedCatalogAccessIntegrationTest.java` | integrationTest | 1 / 5 | `operatorScopeNotForbiddenOnCreate()` | `Expected the operator-scoped token to pass the write gate, but got 403` | **yes** | local `jwt()` with `SCOPE_catalog:write`, random `sub`, no grant |
| `core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java` | integrationTest | 1 / 12 | `writeScopedTokenReaches404OnOrderCreate()` | `Status expected:<404> but was:<403>` | **yes** | local `jwt()` with `SCOPE_orders:write`, random `sub`, no grant |
| `core-java/src/test/java/uk/jtoye/core/shop/MarketingMissingRowStatusIntegrationTest.java` | integrationTest | 8 / 12 | `PUT /promotions/{id} when the row vanishes mid-transaction is a typed 404, not a 5xx/409`; `PUT /announcements/{id} …`; `DELETE /promotions/{id} …`; `DELETE /announcements/{id} …`; `DELETE a promotion twice: 204 then a typed 404`; `DELETE an announcement twice: 204 then a typed 404`; `CONTROL: a live promotion still deletes (204) and is really gone`; `CONTROL: a live announcement still updates (200) and the new title is persisted` | `Status expected:<404> but was:<403>` (controls: `expected:<204>`/`<200>` `but was:<403>`) | no | local `jwt()` with a random `sub` |

**Totals:** 9 classes, 29 methods, all in `:integrationTest`; `:test` has no red under strict ON.
Each path above was checked with `git ls-files <path>`: 9 of 9 resolve (fail direction: a made-up
`core-java/src/test/java/uk/jtoye/core/shop/NoSuchRedClassTest.java` resolves to nothing, count 0).

### What 37-03 should take from this

- **6 of the 9 red classes are NOT in RESEARCH's 21.** The 21 were found by a prose search
  (`rg -uu -l -i 'implicit (tenant-wide )?GROUP_ADMIN|day-one implicit|implicit-GA|implicit GA'`); these six depend on the
  implicit admin without saying so. The prose list both over-counted (17 of its test classes stay green, §3) and
  under-counted (these six). This table, not the 21, is the conversion list.
- The conversion is the same in all nine: give the caller the access the test needs, explicitly —
  `ShopGrants.grantOperator(jdbc, tenant, sub, shopId, role, email)` for the vendor token's `sub` (preferred; it keeps the
  test exercising a scoped vendor), or `TenantJwts.adminJwt` where the test is not about vendor scoping. The `sub` must
  be fixed per test (most of these builders mint a fresh random `sub` per request, so a grant must be seeded for a `sub`
  the test then reuses). Do not set strict scoping false in the test.
- `MarketingMissingRowStatusIntegrationTest`'s two CONTROL methods also go red, which is correct: the controls exercise
  the same caller, so they must be converted with the rest, not separately.

## 3. RESEARCH's 21 self-described dependents, in the strict-ON run

Re-measured on this tree: the same search now prints 22 files, the 21 plus `testsupport/ShopGrants.java` (37-02 Task 1),
whose Javadoc names the rule it replaces.

| Class | Set | Strict ON | Why it does not fail (where green) |
|-------|-----|-----------|------------------------------------|
| `common.idempotency.OrderIdempotencyIntegrationTest` | integrationTest | green, 4 tests | measured green; mechanism not inspected (see note under the table) |
| `integration.LocationHeaderContractTest` | integrationTest | green, 7 | as above |
| `integration.ShopControllerIntegrationTest` | integrationTest | green, 6 | as above |
| `media.MediaUploadControllerTest` | integrationTest | **red, 2 of 5** | (in §2) |
| `onboarding.OnboardingGoLiveIntegrationTest` | integrationTest | green, 8 | as above |
| `order.AllergyNoteAckIntegrationTest` | integrationTest | green, 9 | sets strict ON explicitly where it tests scoping and seeds grants (37-02 Task 2 kept that) |
| `order.OrderSseGrantRecheckTest` | test | green, 7 | runs in `:test` (no Testcontainers); measured green, mechanism not inspected |
| `product.ProductSearchFtsIntegrationTest` | integrationTest | green, 17 | as above |
| `security.access.CrossTenantAuthzIntegrationTest` | integrationTest | green, 6 | sets strict OFF explicitly per test (it reproduces the day-one exploit); see note below |
| `security.access.ShopAccessCacheBypassIntegrationTest` | integrationTest | green, 5 | sets strict ON explicitly |
| `security.access.ShopAccessEnforcementIntegrationTest` | integrationTest | green, 14 | every test sets its posture explicitly |
| `security.access.ShopAccessJitProvisionTest` | integrationTest | green, 4 | every test sets its posture explicitly |
| `security.access.ShopAccessServiceScopingPostureTest` | test | green, 2 | unit test on a `new ShopAccessService(...)`; sets the flag itself; its WARN-text assertions change with 37-04, not with the env |
| `security.access.StaffManagementIntegrationTest` | integrationTest | green, 19 | caller is a realm admin; mode-specific tests set the posture |
| `security.access.StrictScopingTighteningIntegrationTest` | integrationTest | green, 5 | every test sets its posture explicitly |
| `security.ScopedCatalogAccessIntegrationTest` | integrationTest | **red, 1 of 5** | (in §2) |
| `security.ScopedWriteAccessIntegrationTest` | integrationTest | **red, 1 of 12** | (in §2) |
| `security.SecurityHeadersIntegrationTest` | integrationTest | green, 6 | as above |
| `sync.SyncBatchAuthorizationIntegrationTest` | integrationTest | green, 16 | as above |
| `testsupport.TenantJwts` | — | n/a, no XML | a helper, not a test class |
| `webhook.WebhookAuthzIntegrationTest` | integrationTest | green, 12 | as above |

So **3 of the 21 fail** under strict ON and 17 test classes stay green (the 21st entry is the helper). "As above" means
the same as the first row: the class mentions the implicit admin in a comment, and in the measured strict-ON run none of
its tests failed. Why each stays green (a realm-admin caller, an explicit grant, a mocked gate, or a comment that is
simply out of date) was NOT inspected class by class; the claim here is only the measured result. 37-03 does not need
to touch these classes for D-06 to stay green, but may want to correct their stale comments.

Note for 37-03/37-04: the tests that set strict OFF explicitly (CrossTenantAuthz, the `*_strictOff_*` / day-one methods
in the access classes) stay green under either env value because they no longer depend on the default; they test the
OFF posture on purpose and are not reds to convert.

## 4. Default mode stays exactly as green as before

- The default-mode full run is green: rc=0, 0 failures and 0 errors over 2490 tests (1596 + 894).
- The 37-01 baseline holds only the six guarding suites (37-01-baseline.md §2 says so explicitly). All six are in this
  run with the same counts: `KeycloakAdminClientTest` 11/0, `IdempotencyFingerprintGoldenTest` 15/0,
  `ShopAccessJitProvisionTest` 4/0, `StaffManagementIntegrationTest` 19/0, `GuestOrderAllergenAckIntegrationTest` 10/0,
  `RlsContractTest` 7/0. This run is the first full-suite reading and is the comparison point from here on.

## 5. The instruments were shown to fail before they were trusted

| Instrument | Real tree | Fail direction (run) |
|------------|-----------|----------------------|
| XML header reader (exit 0 only when tests>0, failures=0, errors=0) | default run: 187 + 173 `OK`, rc=0 | copy with `failures="1"` → `BAD … failures=1` rc=1; copy with `tests="0"` → `BAD … tests=0` rc=1; missing file → `VOID` rc=2 |
| Per-testcase failure parser (one line per `<failure>`/`<error>`) | strict ON: 29 lines; default: 0 lines | a green XML with one injected `<failure message="…expected: &lt;201&gt; but was: &lt;403&gt;…">` → exactly that method and message, rc=0; the same XML without it → 0 lines; an empty directory → `VOID` rc=2 |
| Booted-value instrument (`StrictScopingBootedValueIntegrationTest`) | green in both modes, reading `booted=true` / `booted=false` | asserting the opposite value: `expected: true but was: false` (unset) and `expected: false but was: true` (`ACCESS_STRICT_SCOPING=true`) — recorded in 37-02-SUMMARY.md |
| Strict-ON env reaching the suite at all | instrument line in the strict-ON run reads `booted=true` | the default run's line reads `booted=false` from the same test |
