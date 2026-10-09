---
phase: 37-real-world-operations-readiness
plan: 08
subsystem: auth
tags: [d-07, d-26, rwo-004, rwo-003, staff-invite, keycloak-admin-api, user-profile, tenant-id, rfc7807, fragment-token, rate-limit, testcontainers, openapi, k8s-config]

requires:
  - phase: 37-07
    provides: "V77 staff_invite (token held as SHA-256), StaffInviteService.issue + sha256Hex, StaffInvite.statusAt, the emailed link, StaffManagementService.persistNewGrant"
  - phase: 37-05
    provides: "ShopAccessService.evictMembershipAfterCommit; tenant-wide role grants honoured"
  - phase: 37-04
    provides: "strict scoping ON by default (D-06): an accepted invitation is the only way a new person gets access"
provides:
  - "KeycloakAdminClient.createUser(vendorRealm, email, firstName, lastName, password, tenantId, token): Jackson-3 body by content (username = email, emailVerified true, attributes.tenant_id [tenant], one non-temporary password credential), id from Location with an exact-email fallback, 400 -> KeycloakUserRejectedException (Keycloak's message only), 409 -> KeycloakUserExistsException; the caller's password array is zeroed"
  - "KeycloakAdminClient.findVendorUsersByEmail -> VendorRealmUser {id, tenantId}; jtoye.keycloak.admin.vendor-realm (KC_VENDOR_REALM, code default jtoye-dev) wired per runtime"
  - "StaffInviteAcceptService.preview / accept: pins the tenant named in the link (TenantContext + GUC, own TransactionTemplate, cleared in finally), reads by digest under RLS, classifies NEW | EXISTS_HERE | OTHER_BUSINESS, claims with the single-use conditional UPDATE (rowcount 1), creates the user, upserts user_directory, writes the invitation's OPERATOR grant by the inviter, marks the invite accepted, all in one transaction"
  - "POST /api/v1/public/staff-invites/preview {ref} and POST /api/v1/public/staff-invites/accept {ref, firstName, lastName, password}; token in the JSON body, Cache-Control no-store, under the per-IP public rate limit"
  - "The emailed link is {accept-base-url}#token={tenantId}.{token}: the token rides in the URL fragment and never reaches a request line"
  - "Four typed refusals: 404 staff-invite-unavailable (one byte-identical body for every unusable cause), 409 staff-invite-email-in-other-business, 422 staff-invite-password-rejected (Keycloak's message, invite stays open), 503 staff-invite-account-service-unavailable (Retry-After; admin seam off or unreachable)"
  - "Vendor realm template: declarative user profile with the four Keycloak 24.0.5 default attributes plus tenant_id view/edit admin-only; unmanagedAttributePolicy never ENABLED"
  - "OpenAPI snapshot: additive only (258 insertions, 0 deletions); k8s goldens regenerated; 37-USER-SETUP.md for staging/production"
affects: [37-09-invite-page-and-staff-ui, 37-15-runtime-gate, phase-pr-docs-metrics, gsd-secure-phase]

actuals:
  tokens: 48000
  tasks: 3
  commits: 8
plan_head_before: 0d8bc26100f08474b01fee580ec1df14a28e9e86
plan_head_after: 610d9bbe8af8ce1b0ead154b4702043427cabb17

tech-stack:
  added: []
  patterns:
    - "A secret that must reach a browser page rides in the URL FRAGMENT and is posted back in a JSON body, never in a path: ProblemDetail.instance echoes the request path into every error body and RateLimitInterceptor logs it on every public 429 (the V75 rule, applied to invitations)"
    - "A public endpoint that acts in one tenant for an anonymous caller pins the tenant from the credential it was handed (context + GUC + own TransactionTemplate, cleared in finally), declares no system authority, and reads under RLS"
    - "Every unusable-credential cause throws ONE exception type with no reason, mapped to ONE constant body, and the test compares the bodies byte for byte"
    - "A refusal that must leave a row untouched (the 422) is thrown INSIDE the transaction after the claim, so the claim rolls back; a refusal that must write nothing (409, 503) is thrown before it"
    - "A log-hygiene arm asserts on a short unique marker the secret starts with, under TRACE for the body-reading logger, so a truncating logger cannot hide the leak"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteAcceptService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/PublicStaffInviteController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffInvitePreviewDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffInviteRefRequest.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/AcceptStaffInviteRequest.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffInviteAcceptedDto.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffInviteUnavailableException.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffInviteEmailTakenException.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffInvitePasswordRejectedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffInviteAccountServiceUnavailableException.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/VendorRealmUser.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakUserExistsException.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakUserRejectedException.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteAcceptIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/37-USER-SETUP.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-08-red-kc-create-user.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-08-red-accept-new.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-08-red-outcomes.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminProperties.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteRepository.java
    - core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceStaffInviteTest.java
    - infra/keycloak/realm-export.template.json
    - infra/keycloak/README.md
    - docs/security-scopes.md
    - docs/api/openapi-snapshot.json
    - docker-compose.full-stack.yml
    - .env.example
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/LOCAL.md
    - .planning/codebase/INTEGRATIONS.md
    - scripts/gates/object-store-residue-allowlist.conf
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "The token moved from the URL path to the fragment, and the public API takes the reference in a JSON body (POST preview / POST accept), not in a path. Measured reasons beyond access logs: ProblemDetail's instance member echoes the request path into every error body, and RateLimitInterceptor logs the path on every public 429. 37-09's /invite/[token] route and GET /{ref} wording are superseded (WINDOWS.md entry 21)"
  - "The 404 for an unusable link carries no reason by construction: StaffInviteUnavailableException has no detail parameter, the handler writes the constant DETAIL, and the test compares eight preview bodies and eight accept bodies byte for byte"
  - "A password-policy refusal is a 422 thrown after the claim inside the transaction, so the claim rolls back and the invitation stays open for a retry; Keycloak 24.0.5's message is generic ('Password policy not met'), so the invitee is not told which rule failed (deferred-items §7)"
  - "No system authority is declared for the accept: it is a request thread in the pinned tenant; the grant is written by persistNewGrant with created_by = the inviter, so the audit names the Group admin who invited, not the invitee"
  - "A de-honoured JIT row on the same shop becomes the invited OPERATOR grant; an escalating accept body (role, shopId, tenantId, createdBy) changes nothing, because the grant is read from the invitation row, never from the request"
  - "KC_VENDOR_REALM is wired per runtime (base jtoye-prod, staging jtoye-staging, local jtoye-dev) because the code default jtoye-dev does not exist in staging or production; a Keycloak admin seam that is off answers 503 and writes nothing"
  - "The realm template's user profile copies the four default attributes from GET /admin/realms/{realm}/users/profile on a running Keycloak 24.0.5 rather than authoring them from memory, and adds tenant_id view/edit ['admin']; unmanagedAttributePolicy is left unset (never ENABLED, T-37-19)"

patterns-established:
  - "Public tenant-less action on a credential: parse ref -> pin tenant -> read by digest under RLS -> classify -> claim by conditional UPDATE (rowcount 1) -> external side effect -> local writes, one transaction, one typed refusal per outcome"

requirements-completed: [RWO-004, RWO-003]

coverage:
  - id: D1
    description: "A new invitee follows the emailed link (token in the fragment), previews as NEW, and accepting creates the vendor-realm Keycloak user with emailVerified true and the admin-only tenant_id, writes the user_directory row and exactly the invited OPERATOR grant by the inviter, and marks the invitation accepted; read back by SQL under the pinned GUC"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteAcceptIntegrationTest tracer: RED 1/1 on ad9279a6 (RED_EVIDENCE_OK, evidence/37-08-red-accept-new.json), GREEN on 9b23c83f; re-run in the closing session 2026-10-08 16:11 BST: 11/0"
        status: pass
    human_judgment: false
  - id: D2
    description: "KeycloakAdminClient.createUser sends the full representation by content (username = email, emailVerified true, attributes.tenant_id [tenant], one non-temporary password credential), returns the id from Location with an exact-email fallback, types 400 with Keycloak's errorMessage and 409 as exists, and zeroes the caller's password array"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "KeycloakAdminClientTest: RED 5/16 on ad9279a6 (RED_EVIDENCE_OK, evidence/37-08-red-kc-create-user.json), GREEN 16/0 on 9b23c83f; break arm (attributes.tenant_id dropped from the body) in the closing session: RED 1/16 (findVendorUsersByEmail_readsIdAndTheTenantAttribute red; gradle rc=1), restore verified by sha256; closing clean 16/0"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every other outcome is typed: EXISTS_HERE grants without a password and an escalating body changes nothing; OTHER_BUSINESS is 409 and writes nothing; eight unusable causes (expired, used, cancelled, tenant swapped, unknown, malformed, missing, offboarded business) on preview AND accept answer one byte-identical 404 with no-store; two concurrent accepts give one 201, one 404, one grant, createUser once; password policy 422 with Keycloak's message leaves the invite open; missing names or password 400; 429 from the per-IP public limiter; Keycloak unreachable 503"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteAcceptIntegrationTest: RED 5/11 on f3273fb9 (RED_EVIDENCE_OK, evidence/37-08-red-outcomes.json), GREEN on 0fde6a12 + f0004faf; closing session 11/0; break arm (a non-OPEN link throws a different 404) in the closing session: RED 2/11 (both byte-identical-404 arms, preview and accept, red; gradle rc=1), restore verified by sha256; closing clean 11/0"
        status: pass
    human_judgment: false
  - id: D4
    description: "No log line of the whole IT run carries the password or the token (positive control proves the capture; the body-reading logger at TRACE; a short unique marker the password starts with)"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteAcceptIntegrationTest log-hygiene arm: green at 0fde6a12 but the toString-prints-the-password break arm stayed green (MVC cut the DEBUG body log at 100 characters inside the password); f0004faf raises the logger to TRACE and asserts the marker, after which the break arm reds (recorded in f0004faf's message); 11/0 in the closing session"
        status: pass
    human_judgment: false
  - id: D5
    description: "The vendor realm template declares tenant_id as a managed user-profile attribute with view and edit for admin only, beside the four default attributes, and unmanagedAttributePolicy is not ENABLED"
    requirement: "RWO-003"
    verification:
      - kind: other
        ref: "plan Task 3 python check on infra/keycloak/realm-export.template.json: rc=0, prints ['email','firstName','lastName','tenant_id','username'] {'view':['admin'],'edit':['admin']}; fail direction on the pre-change template (git show 0d8bc261:…): rc=1, TypeError 'NoneType' object is not subscriptable (no profile component) — both re-run in the closing session"
        status: pass
    human_judgment: false
  - id: D6
    description: "On a live Keycloak 24.0.5 importing the rendered template, an admin-API user keeps tenant_id, its token carries the claim, and the user's own account-API change is refused 400 error-user-attribute-read-only; the pre-change template strips it"
    requirement: "RWO-003"
    verification: []
    human_judgment: true
    rationale: "Proven by the executing session on a THROWAWAY Keycloak 24.0.5 (recorded in 835fe80e's message and infra/keycloak/README.md); not re-run in the closing session and not yet true of the shared compose Keycloak, which needs kc.sh import --override true at the 37-15 rebuild (WINDOWS.md entry 20). Staging and production need the operator step in 37-USER-SETUP.md."
  - id: D7
    description: "KC_VENDOR_REALM reaches every runtime and the published contract is additive: compose, .env.example, k8s app-config (keycloak.admin.vendor-realm) + Deployment env, staging and local overlays; goldens match; env contract and .env.example contract pass; OpenAPI snapshot +258/-0 adding only /api/v1/public/staff-invites/preview and /accept"
    verification:
      - kind: other
        ref: "closing session: k8s/scripts/check-env-contract.sh PASS (core-java 71 injected / 169 read); k8s/scripts/render-golden.sh OK staging + production (1657 lines each); scripts/check-env-example-contract.sh PASS 19/19; grep -c KC_VENDOR_REALM = 1 in compose, .env.example, Deployment; configmap carries keycloak.admin.vendor-realm (the Deployment reads it by configMapKeyRef); misspelt control KC_VENDOR_REALMZ = 0; git diff --numstat 0d8bc261..HEAD docs/api/openapi-snapshot.json = 258 0; OpenApiSnapshotTest 1/0"
        status: pass
    human_judgment: false
  - id: D8
    description: "The branch's line-pinned gates are green again after 37-07/37-08 inserted lines: scripts/check-doc-citations.sh and scripts/check-no-object-store-residue.sh"
    verification:
      - kind: other
        ref: "closing session, fail direction first: check-doc-citations.sh rc=1 'FAILED: 5 citation(s)' (application.yml:520→541, :815-820→837-842; configmap.yaml:173→177; core-java-deployment.yaml:318-322→324-328) and check-no-object-store-residue.sh rc=1 'FAIL: 2 residue line(s), 2 stale' (.env.example:205→215, :510→520); after re-pointing: citations PASS 29 verified / 7 uncheckable, residue PASS 0 violations / 0 stale"
        status: pass
    human_judgment: false
  - id: D9
    description: "No regression across core-java"
    verification:
      - kind: integration
        ref: "610d9bbe8af8 (closing session): :test 188 files / 1610 tests / 0 failed / 0 errors / 1 skipped; :integrationTest 182 files / 946 tests / 0 failed / 0 errors / 1 skipped"
        status: pass
    human_judgment: false
  - id: D10
    description: "An invitation accepted against the running compose stack creates a user whose token carries tenant_id and who lands on the dashboard with exactly the invited grant"
    verification: []
    human_judgment: true
    rationale: "Owed to the 37-15 gate: the shared runtime is rebuilt there, the realm is re-imported there, and the /invite page is 37-09's (WINDOWS.md entries 20 and 21)."

duration: 95min
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 08: Accept a staff invitation (D-07, D-26) Summary

**An invite link now becomes an account with exactly the invited grant. `POST /api/v1/public/staff-invites/preview` tells the holder what the link offers and which account path applies; `POST .../accept` claims the link once (conditional UPDATE, rowcount 1), creates the vendor-realm Keycloak user through the admin API with the admin-only `tenant_id` attribute, writes the directory row and the OPERATOR grant by the inviter, and marks the invitation accepted, all in one transaction in the pinned tenant. Every unusable link answers one byte-identical 404. The token now rides in the URL fragment, and the vendor realm template declares `tenant_id` so Keycloak 24 cannot strip it. Operators of staging and production have two steps in `37-USER-SETUP.md`.**

## Performance

- **Duration:** ~95 min across two sessions (executing session 2026-10-08T14:09Z to T14:40Z for code, evidence and docs; closing session re-verification and gate repair T15:05Z to T15:40Z)
- **Started:** 2026-10-08T14:09:32Z (first RED run)
- **Completed:** 2026-10-08
- **Tasks:** 3
- **Files modified:** 44 (18 created, 26 modified; 39 in the task commits, 5 in the closing commits)

## Accomplishments

- **Task 1 (tracer).** `KeycloakAdminClient.createUser` and `findVendorUsersByEmail`, the `vendor-realm` property, `StaffInviteAcceptService` (pin, read by digest, classify, claim, create, grant, accept), the two public endpoints and the three DTOs. The 37-07 tests were moved to the fragment link shape.
- **Task 2.** Four typed exceptions and their handlers; the eleven-arm integration test covering EXISTS_HERE, OTHER_BUSINESS, the eight unusable causes on both endpoints, the concurrent race, the password-policy 422, validation 400s, the per-IP 429 on real Redis, the 503 and log hygiene. The log-hygiene arm was made able to fail (TRACE + marker).
- **Task 3.** The realm template's declarative user profile with `tenant_id` admin-only; the README's local re-import and operator step; `security-scopes.md` §5 no longer offers `unmanagedAttributePolicy ENABLED`; `KC_VENDOR_REALM` in every runtime; goldens and OpenAPI regenerated; `37-USER-SETUP.md`.
- **Closing.** Re-verified every automated gate, ran the two acceptance-criteria break arms the executing session had not recorded, and re-pointed five doc citations and two residue-allowlist lines that 37-07/37-08's inserted lines had shifted.

## Task Commits

1. **Task 1 RED: createUser by content + the new-invitee accept tracer** - `ad9279a6` (test)
2. **Task 1 GREEN: createUser, vendor-realm property, accept service, public endpoints, fragment link** - `9b23c83f` (feat)
3. **Task 2 RED: every other accept outcome** - `f3273fb9` (test)
4. **Task 2 GREEN: typed refusals, one body for every unusable cause** - `0fde6a12` (feat)
5. **Task 2 follow-up: the log-hygiene arm made able to fail** - `f0004faf` (test)
6. **Task 3: realm user profile, README, KC_VENDOR_REALM everywhere, goldens, OpenAPI** - `835fe80e` (feat)
7. **Closing: five citations and two allowlist lines re-pointed after the line shifts** - see the closing commit (fix)
8. **Plan metadata:** this SUMMARY, `37-USER-SETUP.md`, deferred-items §7, WINDOWS.md entries 20 and 21 (docs). STATE.md and ROADMAP.md are the orchestrator's.

## Evidence (both directions)

| Check | Pass direction | Fail direction |
|-------|----------------|----------------|
| New-invitee tracer (Task 1) | 11/0 (closing session, 16:11 BST) | RED 1/1 on `ad9279a6`: the link still carried the token in the path and the endpoints did not exist (RED_EVIDENCE_OK) |
| createUser by content | `KeycloakAdminClientTest` 16/0 | RED 5/16 on `ad9279a6` (RED_EVIDENCE_OK); closing-session arm, `attributes.tenant_id` dropped from the body: RED 1/16 (findVendorUsersByEmail_readsIdAndTheTenantAttribute red; gradle rc=1), restore verified by sha256 |
| Typed outcomes (Task 2) | 11/0 | RED 5/11 on `f3273fb9` (RED_EVIDENCE_OK); closing-session arm, a non-OPEN link throws a different 404 (`ResourceNotFoundException("expired")`): RED 2/11 (both byte-identical-404 arms, preview and accept, red; gradle rc=1), restore verified by sha256 |
| Log hygiene | marker absent from every captured line; positive control present | toString-prints-the-password arm was GREEN under the 100-character DEBUG cut; red after `f0004faf` (TRACE + marker), as its message records |
| Realm user profile | python check rc=0, five attributes, tenant_id view/edit `['admin']` | pre-change template (`0d8bc261`): rc=1 `TypeError: 'NoneType' object is not subscriptable` |
| Env contract | `check-env-contract.sh` PASS, core-java 71 injected / 169 read | n/a in this session (the gate's own fail direction is recorded in 37-07) |
| Goldens | `render-golden.sh` OK staging + production, 1657 lines each | n/a (committed goldens were regenerated by `835fe80e`) |
| `.env.example` contract | PASS 19/19 required | n/a |
| OpenAPI | `OpenApiSnapshotTest` 1/0; diff 258/0, only `/preview` and `/accept` added | n/a (the gate reds on any un-snapshotted change, 37-07) |
| Doc citations | PASS 29 verified / 7 uncheckable after re-pointing | rc=1 before: 5 citations shifted by 37-07/37-08's inserted lines |
| Object-store residue | PASS 0 violations / 0 stale after re-pointing | rc=1 before: 2 stale `.env.example` allowlist lines (205→215, 510→520) |
| Full core-java suite | 610d9bbe8af8 (closing session): :test 188 files / 1610 tests / 0 failed / 0 errors / 1 skipped; :integrationTest 182 files / 946 tests / 0 failed / 0 errors / 1 skipped | n/a; the arms above are the falsifying runs |

Every arm in the closing session ran on a committed tree; each restore was `git checkout --` of the committed file and verified by sha256 against the hash recorded before the arm.

### OpenAPI diff (additive only: 258 insertions, 0 deletions)

```
paths /api/v1/public/staff-invites/preview   NEW  POST previewStaffInvite  {ref} -> StaffInvitePreviewDto (200 / 404 / 429 / 503)
paths /api/v1/public/staff-invites/accept    NEW  POST acceptStaffInvite   {ref, firstName, lastName, password} -> StaffInviteAcceptedDto (201 / 400 / 404 / 409 / 422 / 429 / 503)
components.schemas                           NEW  StaffInviteRefRequest, AcceptStaffInviteRequest (password write-only), StaffInvitePreviewDto, StaffInviteAcceptedDto
```

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Security] The token left the URL path for the fragment, and the public API went from `GET /{ref}` + `POST /{ref}/accept` to `POST /preview` + `POST /accept` with the ref in the body**
- **Found during:** Task 1. 37-07's threat flag said the path token reaches request lines and the Referer; measuring showed two more sinks: `ProblemDetail.instance` echoes the path into every error body, and `RateLimitInterceptor` logs it on every public 429.
- **Fix:** `{accept-base-url}#token={tenantId}.{token}`; `StaffInviteRefRequest {ref}` on both endpoints. 37-07's tests updated. 37-09 is bound by WINDOWS.md entry 21 and deferred-items §7.
- **Commit:** `9b23c83f`

**2. [Rule 2 - Missing critical] A 503 for the admin seam being off or unreachable**
- The plan typed three refusals. With `keycloak.admin.enabled=false` (the `.env.example` default) or Keycloak down, the accept had no typed answer and would have been a 500. `StaffInviteAccountServiceUnavailableException` → 503 with Retry-After, thrown before any write.
- **Commit:** `0fde6a12`

**3. [Rule 1 - Bug] The log-hygiene arm could not fail**
- MVC's DEBUG body log is cut at 100 characters, and the password sat past the cut, so printing the password in `toString` stayed green. The test now raises `org.springframework.web` to TRACE and asserts on a marker the password starts with.
- **Commit:** `f0004faf`

**4. [Rule 3 - Blocking] `KC_VENDOR_REALM` per overlay, not only the base**
- The code default `jtoye-dev` exists only locally. Base carries `jtoye-prod`, staging `jtoye-staging`, local `jtoye-dev`, with the Deployment reading the configmap key. Compose and `.env.example` carry it too.
- **Commit:** `835fe80e`

**5. [Rule 3 - Blocking] Two branch gates were red from line shifts (closing session)**
- 37-07 and 37-08 inserted lines into `application.yml`, `.env.example`, `k8s/base/configmap.yaml` and `k8s/base/core-java-deployment.yaml`. Five line-pinned citations in `k8s/LOCAL.md` and `.planning/codebase/INTEGRATIONS.md`, and two `.env.example` lines in `scripts/gates/object-store-residue-allowlist.conf`, pointed at the old numbers. Re-pointed; both gates green. The same trap bit 31.1 (its memory note says to run `check-doc-citations.sh` before pushing any yml edit).
- **Commit:** the closing fix commit

---

**Total deviations:** 5 auto-fixed (2 security/missing-critical, 1 bug, 2 blocking).
**Impact on plan:** the plan's truths are kept and one is strengthened (the token never reaches a request line). The public contract differs from the plan's wording, which 37-09 must follow; it is recorded where 37-09 will read it.

## Issues Encountered

- **The plan was finished across two sessions.** The executing session committed Tasks 1 to 3 and wrote `37-USER-SETUP.md`, deferred-items §7 and WINDOWS.md entries 20 and 21, then stopped before the SUMMARY. The closing session found no integration-test result file on disk for the final tree (each RED run used `cleanIntegrationTest`), so it re-ran the IT and the OpenAPI test (11/0, 1/0), ran the two acceptance-criteria break arms the executing session had not recorded, re-ran the Task 3 python check both ways, and ran every branch gate before writing this.
- **Keycloak 24.0.5's password-policy message is generic** (`Password policy not met`); the 422 shows it verbatim and cannot name the rule (deferred-items §7).
- **Six of the eleven Task 2 arms were green at RED**: the Task 1 tracer already shipped the claim, the directory upsert and the grant; those arms assert outcomes the tracer produced. The five genuinely red arms were the typed contract (404 type/code/body identity, 409, 422, 503) and the escalating-body arm.

## Known Stubs

None in this plan's code. The link points at `{accept-base-url}#token=…`, and the `/invite` page that reads the fragment is 37-09's.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: information-disclosure (closed) | StaffInviteService.java / StaffInviteAcceptService.java | 37-07's path-token flag is closed: the token is in the fragment and the ref in a POST body; the 404 for every unusable cause is one constant body; the 422 carries Keycloak's message, never the password. |
| threat_flag: privilege | infra/keycloak/realm-export.template.json | `tenant_id` is admin-only in the TEMPLATE. The shared compose Keycloak and the staging/production realms do not have it until re-imported (`37-USER-SETUP.md`, WINDOWS.md entry 20). Until then an admin-API user is created with no tenant and sees nothing (fail-closed), and `unmanagedAttributePolicy` must never be set to ENABLED (T-37-19). |
| threat_flag: abuse | PublicStaffInviteController.java | Preview and accept are anonymous; the per-IP public limiter (30/min) bounds guessing, and the token is 32 random bytes, single-use, 72 h. No per-token attempt cap was added. |
| canon referral: GDPR retention (from 37-07, unchanged) | V77 `email_normalised` | Accepted, cancelled and expired invitations keep the address. Recorded for `/gsd-secure-phase`. |

## TDD Gate Compliance

- **Task 1:** RED `ad9279a6` `test(37-08)` (RED_EVIDENCE_OK ×2: `evidence/37-08-red-kc-create-user.json` 5/16, `evidence/37-08-red-accept-new.json` 1/1) → GREEN `9b23c83f` `feat(37-08)`.
- **Task 2:** RED `f3273fb9` `test(37-08)` (RED_EVIDENCE_OK, `evidence/37-08-red-outcomes.json` 5/11) → GREEN `0fde6a12` `feat(37-08)` → `f0004faf` `test(37-08)` (arm hardening).
- **Task 3:** `type="auto"`, no TDD. Its falsifying runs are the python check on the pre-change template and the gates above.
- No REFACTOR commits.

## User Setup Required

**External services require manual configuration.** See [37-USER-SETUP.md](./37-USER-SETUP.md) for:
- `keycloak.admin.enabled` (`KC_ADMIN_ENABLED`) true in each environment that accepts invitations, with the `keycloak-credentials` sealed secret
- The vendor realm's user profile: `tenant_id` declared, view/edit admin only, unmanaged attributes never ENABLED (staging `jtoye-staging`, production `jtoye-prod`)
- The verification commands (profile GET, accepted invitee's `attributes.tenant_id`)

## Next Phase Readiness

**37-09 (invite page and staff UI):**
- The page is `/invite` (not `/invite/[token]`). Read `location.hash`, drop it with `history.replaceState`, and `POST /api/v1/public/staff-invites/preview {ref}` then `POST .../accept {ref, firstName, lastName, password}` (deferred-items §7, WINDOWS.md entry 21).
- States: `accountState` NEW shows the name + password form; EXISTS_HERE shows accept only; OTHER_BUSINESS shows the 409 copy; every 404 is UI-SPEC B2 state 4. After a 201 start the ordinary Keycloak sign-in with `login_hint` = the invited email (D-26 interpretation, 37-01 checkpoint).
- The 422 detail is Keycloak's generic message; the realm's rule may be shown as static help copy.

**37-15 gate:** rebuild, `kc.sh import --override true` on the shared Keycloak, then the live chain (profile GET, accept one invitation, token carries `tenant_id`, account-console edit refused) — WINDOWS.md entry 20 — plus `scripts/check-runtime-freshness.sh`.

**Before the phase PR:** docs metrics (`StaffInviteAcceptIntegrationTest` 11 tests new; `KeycloakAdminClientTest` +5), and `scripts/check-doc-citations.sh` + `scripts/check-no-object-store-residue.sh` after every later yml / `.env.example` insert.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: StaffInviteAcceptService.java, PublicStaffInviteController.java, the four DTOs, the four exceptions, VendorRealmUser.java, the two Keycloak exceptions, StaffInviteAcceptIntegrationTest.java, 37-USER-SETUP.md, the three evidence JSONs (all FOUND by `git ls-files`)
- Task commits on this branch: ad9279a6, 9b23c83f, f3273fb9, 0fde6a12, f0004faf, 835fe80e (`git log 0d8bc261..835fe80e` = 6)
- Acceptance re-run in the closing session: IT 11/0, OpenAPI 1/0, unit 16/0, python check rc=0 (pre-change rc=1), env contract PASS, goldens OK, citations PASS, residue PASS; break arms RED 1/16 (findVendorUsersByEmail_readsIdAndTheTenantAttribute red; gradle rc=1), restore verified by sha256 / RED 2/11 (both byte-identical-404 arms, preview and accept, red; gradle rc=1), restore verified by sha256; full suite 610d9bbe8af8 (closing session): :test 188 files / 1610 tests / 0 failed / 0 errors / 1 skipped; :integrationTest 182 files / 946 tests / 0 failed / 0 errors / 1 skipped
