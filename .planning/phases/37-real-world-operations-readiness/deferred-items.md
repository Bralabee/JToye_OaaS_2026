# Phase 37 deferred items

Out-of-scope discoveries logged by executors. Each names who found it, what was measured, and
where it must be resolved.

## 1. docs-freshness gate is red on the integration branch (found in 37-03)

- **Measured 2026-10-08 on `739b69d7`:** `bash scripts/docs-freshness.sh` exits 1:
  `docs/metrics.json` says `java_test_methods 2322 / java_test_files 346 / total 4868`; the
  source computes `2330 / 349 / 4876`.
- **Who moved it:** 37-02 added 3 test methods in 2 files (`ShopGrantsIntegrationTest` 2,
  `StrictScopingBootedValueIntegrationTest` 1); 37-03 added 5 in 1
  (`DemoDataSeederServiceAccountGrantIntegrationTest`).
- **Why deferred:** the fix is `scripts/docs-freshness.sh --write` plus the prose counts that
  `scripts/check-doc-metrics.sh` checks in `CLAUDE.md`, `AGENTS.md` and `README.md`. Every later
  37 plan adds tests too, so regenerating per plan only churns those files.
- **Must be resolved before the phase PR opens** (both gates run in
  `.github/workflows/docs-freshness.yml` and fail the build on drift). Re-measure at that point;
  do not copy the numbers above.
- **37-05 adds a second dimension:** migration `V76__user_directory_realm_admin_seen.sql`. So
  `schema_version` in `docs/metrics.json` (computed by `docs-freshness.sh` from the tracked
  migration files) moves from 75 to 76. The prose in `CLAUDE.md` / `AGENTS.md` that names the current
  schema version and the migration count ("V75", "75 Flyway migrations, V1 through V75") needs a V76
  entry at the same pre-PR step. 37-05 also added Java test methods
  (`StaffEffectiveAccessIntegrationTest` 9 in a new file, `MembershipSerializerRoundTripTest` 2,
  `StaffManagementIntegrationTest` 2 in nested classes). Re-measure; do not copy these numbers.
- **37-07 adds V77** (`V77__staff_invite.sql`, a new tenant table), so `schema_version` moves to 77
  and the prose naming the current schema / migration count needs a V77 entry at the same pre-PR
  step. 37-07 also added three test files (`StaffInviteIntegrationTest`,
  `StaffInviteRlsIntegrationTest`, `EmailNotificationServiceStaffInviteTest`). Re-measure.

## 6. 37-07 invites are not on the shared runtime, and the accept page does not exist yet

- 37-07 changed core-java, config and manifests only; the compose stack was not rebuilt (the
  strict-scoping flip and everything layered on it reach the shared runtime at the 37-15 gate,
  §4). So V77, the four `/api/v1/staff/invites` endpoints and the invitation email through
  Mailhog are proven by integration tests only (a mocked `JavaMailSender` whose message is
  serialised and re-parsed). The live send through Mailhog is owed to the 37-15 rebuild, with
  `scripts/check-runtime-freshness.sh`. Recorded in `.planning/WINDOWS.md`.
- The emailed link `{STAFF_INVITE_ACCEPT_BASE_URL}/{tenantId}.{token}` points at the frontend
  `/invite/[token]` page, which 37-08/37-09 build. Until then an invitation emailed from a
  rebuilt stack opens a 404 page. No staff-page UI was added in 37-07, so the 37-06 jest test and
  e2e spec that assert "no invite control" are unchanged; they change with the UI plan.

## 2. `GET /api/v1/media/review-queue` is not shop-scoped (found in 37-03)

- **Measured:** `MediaRedriveControllerTest` AC-4.8 stays green under
  `ACCESS_STRICT_SCOPING=true` with a fresh, ungranted vendor subject: the review queue returns the
  tenant's FAILED / flagged / stalled assets to a caller with no shop access.
- **Source:** `MediaAssetService.reviewQueue()` calls `mediaAssetRepository.findReviewQueue(...)`
  with no `ShopAccessService` call; its Javadoc says it is tenant-scoped by RLS only.
- **Why deferred:** a production authorization change, not a test conversion; outside 37-03's
  files. Under D-06 an ungranted user can still read this tenant-wide list (asset ids, product ids,
  failure reasons). Route to the 37-B shop-read work or a dedicated plan.
- **RESOLVED in 37-04** (deviation, Rule 2), because 37-04's flip made it live:
  RED `b2ff028a` (`MediaReviewQueueShopScopeIntegrationTest`, RED_EVIDENCE_OK), fix `4c55677b`.
  The queue now follows the product-list read rule: GROUP_ADMIN sees the tenant, any other caller
  only its granted shops' assets, an ungranted caller nothing, a shop-less asset GROUP_ADMIN only.
  AC-4.8 now reads as shopA's manager. The companion `POST /api/v1/media/{assetId}/keep` was
  checked and already required SHOP_MANAGER on the owning shop (WR-03,
  `MediaKeepShopScopeIntegrationTest`); no change. Evidence: 37-04-SUMMARY.md.

## 3. Comments in the frontend still describe the old OFF default (found in 37-04)

- `frontend/lib/shops-api.ts:9-12` ("under the default strict-scoping = false is every
  JIT-provisioned user"), `frontend/lib/staff-api.ts:19` and
  `frontend/app/dashboard/staff/page.tsx:~639` (copy about "before enabling strict-scoping").
- **Why deferred:** comment/copy only, no behaviour; RESEARCH §37-B.1 routes this copy to the
  D-07 / D-09 Staff-page work, which rewrites the same lines. Leaving it to them avoids two
  rewrites of one paragraph.
- **RESOLVED in 37-06.** `shops-api.ts` and `staff-api.ts` now describe strict scoping ON by
  default; the staff-page block (the automatic-grant badge and its comment) is gone, replaced
  by the server-computed access column (`916d101a`).

## 5. 37-06 frontend checks owed to the runtime gate

- 37-06 changed only frontend code. Two staff-page assertions in
  `frontend/e2e/dashboard-interface-corrections.spec.ts` were edited to follow the new page
  (no invite-denial sentence; the CLS test now waits for the "People" heading), and the B3
  backstop (Playwright at 375px: no horizontal overflow on the no-access page) was not
  written or run. Both need the rebuilt stack, because the People column reads `people[]`,
  which the running core-java does not serve until the 37-15 rebuild. Recorded in
  `.planning/WINDOWS.md` (entries 16 and 17).
- 37-06 also moved the Jest count (staff-page, no-access-page, confirm-action-dialog,
  interceptor and webhooks cases). The full suite reads 197 suites / 2270 tests at `034328bd`.
  Fold this into the §1 regeneration; re-measure, do not copy.

## 4. The flip is not on the shared runtime yet (by design, 37-04)

- 37-04 changed code and config only. The running compose stack was NOT rebuilt: per the plan's
  must-have, the flip reaches a shared runtime only at the 37-15 gate, after the owner has seen
  the list of existing users who hold only an automatic grant.
- So MCP `create_order` / `list_products` under strict ON with real Keycloak client-credentials
  tokens (37-03 coverage D4) is still proven by integration tests only; the live check is owed
  to the 37-15 gate, together with `scripts/check-runtime-freshness.sh`.

## 7. 37-08 accept flow: what binds 37-09 and 37-15

- **The invite link changed shape (37-08 deviation, the V75 rule).** It is now
  `{STAFF_INVITE_ACCEPT_BASE_URL}#token={tenantId}.{token}`: the token rides in the URL
  **fragment**, which a browser never sends to a server. The public API takes the reference in a
  JSON body, never in a path: `POST /api/v1/public/staff-invites/preview {ref}` and
  `POST /api/v1/public/staff-invites/accept {ref, firstName, lastName, password}`.
  **37-09 must follow:** the page is `/invite` (not `/invite/[token]`), reads `location.hash`
  client-side, drops it with `history.replaceState`, and POSTs the ref. 37-09-PLAN's
  `GET /api/v1/public/staff-invites/{ref}` and `POST .../{ref}/accept` wording is superseded
  (WINDOWS.md entry 21). Measured reasons the path form was unsafe, beyond access logs: the
  ProblemDetail `instance` member echoes the request path, so a path token would be written back
  into every error body, and `RateLimitInterceptor` logs the request path on every public 429.
- **Shared-runtime Keycloak is stale.** The vendor-realm user profile (`tenant_id` admin-only)
  reaches the running compose Keycloak only by `kc.sh import --override true` at the 37-15
  rebuild. Until then an invitation accepted against the shared stack would create a user whose
  `tenant_id` is stripped. The chain is proven on a throwaway Keycloak 24.0.5 only
  (`infra/keycloak/README.md`, "User profile"); the live proof is owed to 37-15 (WINDOWS.md
  entry 20). Staging/production need the operator step in `37-USER-SETUP.md`.
- **`.env.example` ships `KC_ADMIN_ENABLED=false`** while compose defaults it to `true`. An
  operator who copies `.env.example` verbatim turns the admin seam off, and then every preview
  and accept answers 503 `staff-invite-account-service-unavailable` (DSAR account deletion is
  already NOT_CONFIGURED in the same state). Pre-existing; not changed here.
- **Keycloak 24.0.5's password-policy refusal is generic.** Measured against the shared
  `jtoye-dev` realm: `{"errorMessage":"Password policy not met"}`, with no rule named. The 422
  shows it verbatim, as UI-SPEC B2 requires, so the invitee is not told which rule failed.
  37-09 may add the realm's rule text as static help copy; the server cannot name the rule.
- **Docs metrics (§1).** 37-08 adds `StaffInviteAcceptIntegrationTest` (11 tests, new file) and
  5 test methods to `KeycloakAdminClientTest`. Re-measure at the pre-PR step.
- **GDPR residual (from 37-07, unchanged).** A cancelled or expired invitation keeps the
  invitee's address in `staff_invite.email_normalised` indefinitely; an accepted one keeps it
  too. Recorded for `/gsd-secure-phase`; not redesigned here.
