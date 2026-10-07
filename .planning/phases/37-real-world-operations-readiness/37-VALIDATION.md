---
phase: "37"
slug: "real-world-operations-readiness"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-10-07"
---

# Phase 37 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `37-RESEARCH.md` § Validation Architecture. tdd_mode is on: every ❌ row is written RED on the pre-fix tree first.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (Java); Jest 30 (frontend); Playwright 1.63 + axe-core (E2E); `go test` (edge-go); vitest (mcp-server) |
| **Config file** | `core-java/build.gradle.kts` (`test` excludes tag `testcontainers`; `integrationTest` includes it; outputs in `core-java/build-local/`, NOT `build/`); `frontend/jest.config.js`; `frontend/playwright.config.ts`; `mcp-server/package.json` |
| **Quick run command** | `./gradlew :core-java:test --tests '<FQCN>'` · `./gradlew :core-java:integrationTest --tests '<FQCN>'` · `cd frontend && npx jest <path>` · `cd edge-go && go test ./...` · `cd mcp-server && npm test` |
| **Full suite command** | `./gradlew :core-java:test :core-java:integrationTest && (cd frontend && npx jest && npm run build && npx playwright test) && (cd mcp-server && npm test) && (cd edge-go && go test ./...)` |
| **Type check** | `cd frontend && npm run build` (jest does not type-check) |
| **Estimated runtime** | quick: ~30–120 s per class/dir; full: ~40–60 min |

Gates run per sub-theme PR: `scripts/docs-freshness.sh` (+`--write`), `scripts/check-doc-metrics.sh`, `./gradlew :core-java:updateOpenApiSnapshot` then `scripts/check-openapi-snapshot-fresh.sh`, `k8s/scripts/render-golden.sh` + `check-render-invariants.sh` + `check-env-contract.sh`, `scripts/check-no-create-extension.sh`, `scripts/check-branch-behind-base.sh`, `scripts/check-runtime-freshness.sh` (after a rebuild of all images).

---

## Sampling Rate

- **After every task commit:** the touched class (`--tests`) plus `npx jest <touched dir>` / `go test` / `npx vitest run <file>` as touched
- **After every plan wave:** `./gradlew :core-java:test`, the wave's integration classes, `npx jest`, `npm run build`, `go test ./...` / `npm test` where touched
- **Per sub-theme PR (D-03):** full suite + all gates above + rebuild + `check-runtime-freshness.sh` + Playwright on the rebuilt compose stack
- **Before `/gsd-verify-work`:** Full suite must be green; the 40 goods in `goods-to-preserve.md` re-verified (SC-7)
- **Max feedback latency:** 120 seconds per task-level command

---

## Per-Task Verification Map

Task IDs are assigned by the planner; this map is keyed by requirement (UXT cluster) until plans exist.

| Requirement | Behavior | Test Type | Automated Command | File Exists | Status |
|-------------|----------|-----------|-------------------|-------------|--------|
| UXT-004 | ungranted vendor token → 403 on writes; no JIT row | IT | `./gradlew :core-java:integrationTest --tests '*StrictScopingDefaultIntegrationTest'`; `ShopAccessJitProvisionTest` | ❌ W0 / ✅ update | ⬜ pending |
| UXT-003 | revoke last grant → own token gets 403 next request | IT | `--tests '*StaffManagementIntegrationTest'` (new arm) | ✅ update | ⬜ pending |
| D-06 config | no profile/compose/k8s manifest sets strict-scoping false | gate | `scripts/check-strict-scoping-default.sh` (red on a broken copy) | ❌ W0 | ⬜ pending |
| D-09 | Staff list shows computed effective access incl. "No access" | IT + jest | `--tests '*StaffEffectiveAccessIntegrationTest'`; `npx jest app/dashboard/__tests__/staff-page.test.tsx` | ❌ W0 / ✅ update | ⬜ pending |
| D-07 | invite → accept → KC user with tenant_id → exact grant; expired/used/wrong-tenant → one 404 | IT + unit + live | `--tests '*StaffInviteIntegrationTest'`, `*StaffInviteRlsIntegrationTest` (NOSUPERUSER); `test --tests '*KeycloakAdminClientTest'` | ❌ W0 | ⬜ pending |
| UXT-018 | COMPLETED delete → 409; void → reversal row; ledger nets 0; order GET 200 | IT | `--tests '*OrderVoidIntegrationTest'`, `*LedgerEntryKindMigrationIntegrationTest` | ❌ W0 | ⬜ pending |
| UXT-027 | per-shop finance summary/export; manager own shop 200, other 403 | IT + jest | `--tests '*FinanceShopScopeIntegrationTest'`, `*LedgerShopBackfillMigrationIntegrationTest` | ❌ W0 | ⬜ pending |
| UXT-026 | CSV import default shop; copy-menu | IT + jest | `--tests '*BulkImportShopDefaultIntegrationTest'` | ❌ W0 | ⬜ pending |
| UXT-046/047 | VAT select in form; VAT line hidden without vatNumber | jest | `npx jest app/dashboard/products app/shop` | ❌ W0 | ⬜ pending |
| UXT-011/020 | PENDING on board (New lane); repeating alert; notes + fulfilment on card | unit + jest + e2e | `test --tests '*OrderServiceKitchenStatusesTest'`; `npx jest app/dashboard/kitchen`; `npx playwright test e2e/kitchen-flow.spec.ts` | ✅ update | ⬜ pending |
| UXT-021 | session lapse → "board stopped", never "Live"; return to kitchen | jest + e2e | `npx jest app/dashboard/kitchen`; `npx playwright test e2e/kitchen-session-lapse.spec.ts` | ❌ W0 | ⬜ pending |
| UXT-022 | pause refuses storefront + checkout; hours migration preserves render; unparseable → closed | IT + jest | `--tests '*ShopPauseIntegrationTest'`, `*OpeningScheduleMigrationIntegrationTest`; `npx jest lib/__tests__/opening-hours.test.ts` | ❌ W0 / ✅ update | ⬜ pending |
| UXT-007 | vendor reprice between render and submit → typed 409 with diff; composes with 31.1 allergen 409 | IT + jest + e2e | `--tests '*GuestOrderBasketRevalidationIntegrationTest'`; `IdempotencyFingerprintGoldenTest`; `npx playwright test e2e/basket-revalidation.spec.ts` | ❌ W0 | ⬜ pending |
| UXT-008 | no promotion rendered or returned | IT + jest | `--tests '*PublicPromotionsHiddenTest'`; `npx jest app/shop` | ❌ W0 | ⬜ pending |
| UXT-029 | COD → route; focus + title + announcement; refresh no re-POST | jest + e2e | `npx jest app/shop`; `npx playwright test e2e/public-a11y.spec.ts` | ✅ update | ⬜ pending |
| UXT-044 | public reviewer name "First L." | unit | `test --tests '*ReviewServiceTest'` | ✅ update | ⬜ pending |
| UXT-005 | cross-shop review → 400 | IT | `--tests '*ReviewCrossShopIntegrationTest'` | ❌ W0 | ⬜ pending |
| UXT-006 | rotated XFF from untrusted peer → same bucket; trusted peer → right-most untrusted hop | unit | `test --tests '*ClientIpResolverTest'` | ❌ W0 | ⬜ pending |
| UXT-039 | per-tenant typed 429 + rate headers after auth; other tenant unaffected | go | `cd edge-go && go test ./cmd/edge/ -run RateLimit` | ✅ update | ⬜ pending |
| UXT-041 | over-cap open cash orders → 429; unverified flag stored; bulk reject | IT + jest | `--tests '*CashOrderCapIntegrationTest'`, `*OrderBulkCancelIntegrationTest` | ❌ W0 | ⬜ pending |
| UXT-042 | qty over cap → 422 on storefront AND vendor/MCP paths | IT | `--tests '*OrderLimitsIntegrationTest'` | ❌ W0 | ⬜ pending |
| UXT-016 | PUT without stock preserves tracking; PATCH keeps allergens | IT + jest | `--tests '*ProductPartialUpdateIntegrationTest'`; `npx jest app/dashboard/products` | ❌ W0 | ⬜ pending |
| UXT-017/037 | sync requires shopId; per-item results; edge passes problem+json | IT + go | `--tests '*SyncBatchShopRequiredIntegrationTest'`; `cd edge-go && go test ./...` | ❌ W0 | ⬜ pending |
| UXT-036 | MCP create_order → PENDING, outbox event, on kitchen board | IT + vitest | `--tests '*OrderCreateSubmitIntegrationTest'`; `cd mcp-server && npx vitest run src/tools/create-order.test.ts` | ❌ W0 / ✅ update | ⬜ pending |
| UXT-040 | list_products shopId/availableOnly; allergen names in DTO | vitest + IT | `npx vitest run src/tools/list-products.test.ts`; `--tests '*ProductDtoAllergenNamesTest'` | ✅ update / ❌ W0 | ⬜ pending |
| UXT-038 | transient failures do not pause; exhausted pauses; owner emailed once | IT | `--tests '*WebhookAutoPauseIntegrationTest'` | ❌ W0 | ⬜ pending |
| UXT-035 | create/rotate/delete credential; token carries tenant_id + scopes; SA grant enforced | unit + IT + live | `test --tests '*KeycloakAdminClientTest'`; `--tests '*IntegrationCredentialIntegrationTest'`, `*IntegrationCredentialRlsIntegrationTest` | ❌ W0 | ⬜ pending |
| UXT-080..085 | live regions, accessible names, focus, titles, banner overlap, large text | e2e + jest | `npx playwright test e2e/a11y-persona-gaps.spec.ts`, `e2e/public-a11y.spec.ts` | ❌ W0 | ⬜ pending |
| UXT-023 | fee/threshold editable; collection-only refuses DELIVERY on all writers | IT + jest | `--tests '*FulfilmentOfferedIntegrationTest'` | ❌ W0 | ⬜ pending |
| UXT-028 | edit without slug keeps slug | IT | `--tests '*ShopControllerIntegrationTest'` (new arm) | ✅ update | ⬜ pending |
| SC-7 | 40 goods re-verified | mixed | existing suites per `37-RESEARCH.md` goods map | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] 37-B: strict-default IT; fix the 11 test classes whose teardown resets strict-scoping to a literal `false` (capture/restore instead); `scripts/check-strict-scoping-default.sh`; invite/void/finance ITs; stepwise migration tests
- [ ] 37-A: kitchen session-lapse spec; pause/hours ITs; hours migration stepwise test (2 tenants; NULL/closed/unparseable/overnight rows)
- [ ] 37-C: basket-revalidation IT + e2e (vendor-reprice race, modelled on `allergen-ack-race.spec.ts`)
- [ ] 37-D: `ClientIpResolverTest` (IPv4/IPv6/mapped, trusted/untrusted peer, rotation); cap ITs; limits IT across both writers
- [ ] 37-E: sync, credential and webhook ITs; edge passthrough Go tests; MCP vitest arms
- [ ] 37-F: new a11y spec (CDP `Accessibility.getFullAXTree` + MutationObserver live-region recorder) with an INSTRUMENT arm proving it can fail
- [ ] 37-G: fulfilment-offered IT
- [ ] Metrics: `docs/metrics.json` regenerated after each PR (`scripts/docs-freshness.sh --write`) and prose counts updated

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Repeating kitchen alert is audible in a noisy room | UXT-011 / D-12 | Loudness and audibility cannot be asserted by a browser test (autoplay needs a user gesture; Playwright asserts the loop fires, not that it is heard) | On the compose stack, tap "Enable sound", place a storefront order, confirm the sound repeats until Accept and the mute indicator shows when muted |
| Invite email → Keycloak registration → landing with the grant | D-07 | Crosses Mailhog + live Keycloak user-profile config | Compose checkpoint: send invite, open link from Mailhog, register, confirm the Staff page shows exactly the invited grant |
| Credential token issued by live Keycloak carries tenant_id and scopes | UXT-035 / D-22 | Live Keycloak admin API on the merged tree | Compose checkpoint: create credential, client-credentials grant, decode token, call one scoped endpoint |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
