---
phase: 37-real-world-operations-readiness
plan: 45
subsystem: api
tags: [integrator-credentials, keycloak, service-account, rls, human-only, idempotency, d-22, d-23, d-24, rwo-035, uxt-035]
status: complete

requires:
  - phase: 37-44
    provides: "KeycloakAdminClient.createClient / getServiceAccountUserId / getClientSecret / regenerateClientSecret / deleteClient / findClientUuid, KeycloakClientSpec, KeycloakClientExistsException, KeycloakClientNotFoundException"
  - phase: 37-05
    provides: "tenant-wide non-admin roles honoured; UserDirectoryToucher best-effort REQUIRES_NEW pattern"
  - phase: 37-07
    provides: "the V77 staff_invite shape (who/when columns, no _aud) and StaffInviteController"
  - phase: 37-38
    provides: "V82 reserved for this plan; out-of-order apply after V84 proven"
provides:
  - "V82 integration_credential: ENABLE + FORCE RLS via current_tenant_id(), kc_client_id UNIQUE + jtoye-int- CHECK, role CHECK STAFF/SHOP_MANAGER, scopes CHECK (subset of the six #206 scopes), who/when pair CHECKs, idempotency_key, no secret column, no _aud"
  - "POST /api/v1/credentials (201, secret once, Cache-Control no-store; Idempotency-Key replay 200 secretShown false), GET /api/v1/credentials (with lastUsedIntervalMinutes), POST /api/v1/credentials/{id}/rotate, DELETE /api/v1/credentials/{id}"
  - "IntegrationCredentialService: OPERATOR grant for the service-account user through StaffManagementService.persistNewGrant; a rollback deletes the Keycloak client and the grant again"
  - "CredentialLastUsedRecorder: last_used_at at most once per jtoye.credentials.last-used-interval (PT15M), best-effort REQUIRES_NEW; a revoked credential's still-valid token is refused 403 credential-revoked"
  - "HumanCallerGuard + jtoye.access.human-client-ids (ACCESS_HUMAN_CLIENT_IDS, default core-api; k8s from app-config keycloak.client-id): credential and staff grant/revoke/invite mutations are human-only"
  - "StaffManagementService.grant refuses a credential's service-account user (409 staff-target-is-credential)"
  - "Problem types: credentials-not-configured (400), credential-scope-invalid (400), credential-role-invalid (400), human-only-action (403), credential-revoked (403 filter / 409 rotate), credential-client-missing (409), credential-service-unavailable (503), staff-target-is-credential (409)"
affects: [37-46 Developers page, 37-47 the 37-E gate live Keycloak proof, staging/production operator config]

actuals:
  tokens: 52840      # chars/4 over the added lines of source, config, tests and docs (211,360 chars); excludes the regenerated OpenAPI snapshot and the RED evidence JSON (all files: 662,937 chars, ~165,700)
  tasks: 3
  commits: 12        # MEASURED: git rev-list --count b31b4f64..478faff2
plan_head_before: b31b4f644f131156dc71ab4bbb70df127cc125cd
plan_head_after: 478faff226221ea5c896852acf1c4a29d12d11ac

tech-stack:
  added: []
  patterns:
    - "A credential is a person to the access model and a machine to the management model: ShopAccessService decides for it through an ordinary OPERATOR grant; HumanCallerGuard (azp allowlist) refuses it on credential and staff management"
    - "Keycloak and the database cannot share a transaction: create in Keycloak first, register an afterCompletion hook that deletes the client (and the REQUIRES_NEW grant) when the transaction does not commit; on revoke, flush the database writes first and call Keycloak last inside the transaction"
    - "A secret-bearing create under Idempotency-Key: executeWithoutStoringResponse plus the key recorded on the created row, so a replay re-reads the row and returns it without the secret"
    - "A servlet filter that needs JPA takes its repository and transaction manager through ObjectProvider, because every @WebMvcTest slice instantiates Filter beans"
    - "Log event names avoid the gitleaks generic-api-key keywords (credential, token, key, secret, auth) before a key=value pair, or every captured RED report trips the pre-push scan"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V82__integration_credential.sql
    - core-java/src/main/java/uk/jtoye/core/integration/credential/IntegrationCredential.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/IntegrationCredentialRepository.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/IntegrationCredentialService.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/IntegrationCredentialController.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/CredentialLastUsedRecorder.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/dto/CreateCredentialRequest.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/dto/CredentialDto.java
    - core-java/src/main/java/uk/jtoye/core/integration/credential/dto/CredentialSecretDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/HumanCallerGuard.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialsNotConfiguredException.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialScopeInvalidException.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialRoleInvalidException.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialServiceUnavailableException.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialRevokedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/CredentialClientMissingException.java
    - core-java/src/main/java/uk/jtoye/core/exception/HumanOnlyActionException.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffTargetIsCredentialException.java
    - core-java/src/test/java/uk/jtoye/core/integration/credential/IntegrationCredentialIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/credential/IntegrationCredentialRlsIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-45-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-45-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-45-red-task3.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-45-red-task3b.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/testsupport/TenantJwts.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteAcceptIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/VanishedRowStatusIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/customer/CustomerAccessGateIntegrationTest.java
    - k8s/base/core-java-deployment.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - docker-compose.full-stack.yml
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-45: credential and staff management are human-only by the token's azp (jtoye.access.human-client-ids), refused BEFORE the rank check with 403 human-only-action; staging/production read the dashboard client id from the same app-config key the frontend does (keycloak.client-id, 'frontend' there), so the two cannot drift; the application.yml default core-api is the compose/k8s-local client"
  - "37-45: a revoked credential's still-valid token is refused at once (403 credential-revoked) by the last-used filter, on top of the grant deletion, so revocation does not wait for token expiry and holds even on endpoints no shop gate guards"
  - "37-45: StaffManagementService.grant refuses any credential's service-account user (409 staff-target-is-credential): once a credential has called the API it sits in user_directory, and without this a Group admin could make it GROUP_ADMIN through the Staff API, bypassing the Q7 ceiling (measured 201 at RED)"
  - "37-45: Idempotency-Key replay is keyed on integration_credential.idempotency_key (a column the plan did not list) because executeWithoutStoringResponse stores no response and the replay must return the credential it created"
  - "37-45: credential handler methods are named createCredential/listCredentials/rotateCredential/revokeCredential so springdoc renumbers no existing operationId; the snapshot diff is additive"

patterns-established:
  - "Human-only management: HumanCallerGuard.requireHuman() at the top of a controller method, before the service's rank check"
  - "Credential tokens in tests: TenantJwts.serviceAccountJwt(sub, tenant, clientId, scopes...) builds the 37-44 shape (no realm_access, email or preferred_username); person tokens carry azp=core-api (TenantJwts.DASHBOARD_CLIENT_ID)"

requirements-completed: [RWO-035]

coverage:
  - id: D1
    description: "A Group admin issues a SHOP_MANAGER credential on shop A: 201, generated jtoye-int- clientId, the secret once with Cache-Control exactly no-store, Keycloak createClient with the caller's tenant and scopes, an OPERATOR grant for the service-account user on A, and no column of the row holds the secret; the credential's token reads shop A and is refused shop B"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/credential/IntegrationCredentialIntegrationTest.java#groupAdmin_createsCredential_secretShownOnce_andTheServiceAccountIsGrantedLikeAPerson, #credentialToken_isLimitedToItsGrant (RED 8/8 on 404 at 980ce74f; arms A2, B, C red)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Role ceiling and scope allow-list: GROUP_ADMIN is 400 credential-role-invalid, a scope outside the six is 400 credential-scope-invalid naming invalidScopes and allowedScopes; a non-admin is 403; Keycloak is never called; the V82 CHECKs refuse the same values at the database"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#groupAdminRole_isRefused, #scopeOutsideTheSix_isRefused, #nonGroupAdmin_isRefused (arm D: without the service check the DB CHECK refuses, 409)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Seam off: create and list are the typed 400 credentials-not-configured; a Keycloak failure after the client exists is 503 credential-service-unavailable with Retry-After, the client deleted again, nothing written"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#seamNotConfigured_isTyped400, #keycloakFailureAfterCreate_deletesTheClientAgain_andWritesNothing (arm M red)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Idempotency-Key: a same-key replay is 200 with the credential, secretShown false and no secret; idempotency_keys.response_body stays NULL; Keycloak called once"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#idempotentReplay_returnsTheCredentialWithoutTheSecret (arm E: storing the response reds it)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Rotate returns the new secret once (no-store) and stamps rotated_by/at; delete removes the KC client and the grant, stamps revoked_by/at, repeats as 204, and the credential's next call is 403 credential-revoked; a revoked credential cannot be rotated (409); unknown ids 404; a SHOP_MANAGER is 403"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#rotate_returnsTheNewSecretOnce_andStampsWhoAndWhen, #delete_revokesEverywhere_andTheNextCallIsRefused, #rotateRevoked_is409_andUnknownIs404, #rotateAndDelete_byNonGroupAdmin_areRefused (RED 13/5 at 5ccf7af9; arms G, H red)"
        status: pass
    human_judgment: false
  - id: D6
    description: "last_used_at is written at most once per interval (PT15M, stated in the list) and again after it, best-effort in its own transaction, through FORCE RLS under a NOSUPERUSER role"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#lastUsed_isWrittenAtMostOncePerInterval, #list_showsTheCredential; IntegrationCredentialRlsIntegrationTest#lastUsedIsWrittenUnderForceRls (arms I, K, L red)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Tenant wall (T-37-109): under NOSUPERUSER, A's GUC hides B's credential (read 0, UPDATE 0, foreign INSERT 42501 beside own-tenant controls); the API never crosses tenants; RlsContractTest green with no exemption"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialRlsIntegrationTest (4/0; arm J FORCE dropped reds the DB arm, arm J2 FORCE dropped + no tenant predicate reds the API arm); uk.jtoye.core.security.RlsContractTest 7/0"
        status: pass
    human_judgment: false
  - id: D8
    description: "Human-only (Q7, T-37-107): a credential's token, a declared machine client and a Group admin's token issued to integration-orders-rw are refused 403 human-only-action on every credential endpoint and on staff grant, revoke and invite; the dashboard token passes; a staff grant to a credential's service account is 409"
    requirement: "RWO-035"
    verification:
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#credentialToken_cannotManageCredentialsOrStaff, #declaredMachineClient_cannotManageStaffOrCredentials, #groupAdminOutsideTheDashboardClient_isRefused_andThroughItPasses, #staffGrant_toACredentialServiceAccount_isRefused (RED 17/3 and 18/1; arms N, O, P red)"
        status: pass
    human_judgment: false
  - id: D9
    description: "Live Keycloak 24.0.5 proof: a minted credential token is accepted by core without preferred_username, email or realm_access; 200/403 per grant; the dashboard azp in staging/production is really 'frontend'"
    requirement: "RWO-035"
    verification: []
    human_judgment: true
    rationale: "Owed to the 37-E gate 37-47 by plan design (<implementation>: the live proof is 37-47). Every IT here mocks KeycloakAdminClient, and the compose stack runs images BEHIND the branch (37-38), so a live check now would test stale code; the staging/production client id lives in a realm that is not in this repository"

duration: 83min
completed: 2026-10-13
---

# Phase 37 Plan 45: Scoped, revocable integrator credentials Summary

**A Group admin can now issue, list, rotate and revoke a scoped API credential instead of sharing the owner's password (UXT-035, D-22..D-24).**
- Each credential is a Keycloak service-account client in the vendor realm.
- Its service account holds an ordinary OPERATOR shop grant, so `ShopAccessService` limits it exactly like a person.
- The secret is shown once and never stored.
- Only people signed in through the dashboard client can manage credentials or staff.

## Performance

- **Duration:** about 83 min. Started 2026-10-13T03:13:48Z, finished 2026-10-13T04:36:45Z.
- **Tasks:** 3, all TDD. Task 1 was the tracer.
- **Commits:** 12 before this SUMMARY.
- **Files:** 44 changed, against `b31b4f64`.

## Accomplishments

- **V82 `integration_credential`.**
  - RLS: ENABLE + FORCE through `current_tenant_id()`. `RlsContractTest` sweeps the table and needs no exemption.
  - `kc_client_id` is UNIQUE, generated server-side, and must start with `jtoye-int-` (CHECK).
  - Two more CHECKs: the role must be STAFF or SHOP_MANAGER, and the scopes must be a non-empty subset of the six #206 scopes.
  - Who and when must be written as pairs (rotated, revoked).
  - There is an `idempotency_key` column, no secret column and no `_aud`.
  - It applies out of order after V84, as proven at 37-38.
- **`POST /api/v1/credentials`.** The service checks, in order:
  1. The Keycloak admin seam is configured; if not, a typed 400.
  2. The caller is a Group admin.
  3. The role is not GROUP_ADMIN (typed 400).
  4. Every scope is one of the six (typed 400, naming the refused scopes and the allowed list).
  5. The shop belongs to the tenant; if not, a non-disclosing 404.
- **What a successful create does:**
  - Keycloak `createClient` with `jtoye-int-` plus 12 base32 characters. On a clientId collision it retries once with a new id.
  - It reads the service-account user and the secret once.
  - It writes the credential row.
  - It writes the OPERATOR grant through `StaffManagementService.persistNewGrant` and evicts the membership after commit.
  - The response is 201 with `clientSecret`, `secretShown:true` and `tokenEndpoint`, and `Cache-Control: no-store`.
  - With an `Idempotency-Key`, the create runs through `executeWithoutStoringResponse`. A replay returns 200 with `secretShown:false` and no secret.
- **Rollback safety.** If the transaction does not commit after the Keycloak client exists, an afterCompletion hook deletes the client and the grant again. A `createClient` that fails after Keycloak made the client is swept by its clientId (the 37-44 note). Keycloak failures are the typed 503.
- **`POST /{id}/rotate`.**
  - It regenerates the secret, returns it once with no-store, and stamps `rotated_by` and `rotated_at`.
  - A revoked credential gets 409 `credential-revoked`.
  - A client deleted directly in Keycloak gets 409 `credential-client-missing`.
- **`DELETE /{id}`.**
  - It deletes the grant, evicts after commit, and stamps `revoked_by` and `revoked_at`.
  - Keycloak `deleteClient` runs last, inside the transaction, so a Keycloak failure rolls the revocation back.
  - A repeat returns 204.
- **`GET`** lists every credential, newest first, revoked ones included. The response states `lastUsedIntervalMinutes`.
- **`CredentialLastUsedRecorder`** is a servlet filter at order 300, after the security chain.
  - It acts when the token's `azp` starts with `jtoye-int-`.
  - It looks the credential up in the token's tenant in its own transaction.
  - It stamps `last_used_at` at most once per `jtoye.credentials.last-used-interval` (PT15M). The condition is in SQL, so two pods do not both write it.
  - Every failure is swallowed.
  - It refuses a revoked credential's still-valid token with 403 `credential-revoked`.
- **`HumanCallerGuard`.** Every credential endpoint and every staff grant, revoke, invite issue, resend and cancel calls it first.
  - It refuses with 403 `human-only-action` unless the token's `azp` is in `jtoye.access.human-client-ids`.
  - The default is `core-api`.
  - k8s injects the value from `app-config/keycloak.client-id`, the key the frontend reads; that is `frontend` in staging and production. Compose reads `KEYCLOAK_CLIENT_ID`.
- **`StaffManagementService.grant`** refuses a credential's service-account user with 409 `staff-target-is-credential`. This closes a GROUP_ADMIN escalation found during execution (see Deviations).

## Task Commits

1. **Task 1 (tracer): a Group admin creates a credential that is limited like a person**
   - `980ce74f` test (RED)
   - `ea0561f7` feat (GREEN)
   - `2c58dea7` test: no-store made falsifiable
   - `76d200c5` test: the rollback-cleanup arm, added after GREEN
   - Tracer gate: the mode is end-of-phase and the verify is automated only. I re-ran the verify on the committed tree: 8/0. "Tracer verified end-to-end — expanding."
2. **Task 2: rotate, revoke, last-used, and the tenant wall**
   - `5ccf7af9` test (RED)
   - `f65964b3` feat (GREEN)
3. **Task 3: only humans manage credentials and staff**
   - `3bf897fe` test (RED)
   - `5b32856e` feat (GREEN)
   - `99b80fab` test (RED, the staff-grant escalation)
   - `71c68425` fix (GREEN)
4. **Metrics and docs:** `f11f32e5` docs. Counts went from 5854 to 5876, and V82 is recorded as shipped.
5. **Unit-suite repair:** `478faff2` fix. The filter now boots in `@WebMvcTest` slices.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction arms |
|------|------------------------|-------|---------------------|
| 1 | `980ce74f`: `37-45-red-task1.json` RED_EVIDENCE_OK (8 tests, 8 failed) | `ea0561f7`: 8/0 | A (vacuous, replaced), A2, B, C, D, E, M |
| 2 | `5ccf7af9`: `37-45-red-task2.json` RED_EVIDENCE_OK (13/5; RLS IT 4/1) | `f65964b3`: 13/0, RLS 4/0, RlsContractTest 7/0 | G, H, I, J, J2, K, L |
| 3 | `3bf897fe`: `37-45-red-task3.json` RED_EVIDENCE_OK (17/3) and `99b80fab`: `37-45-red-task3b.json` RED_EVIDENCE_OK (18/1) | `5b32856e`, `71c68425`: 23 classes 170/0, then 147/0 | N, O, P, Q, R, S |

There is no separate REFACTOR commit.

**RED semantic assessments.**
- **Task 1.** Every arm failed on its first status assertion, because `/api/v1/credentials` answered 404 `errors/not-found` (no route). That is the UXT-035 repro: there was no way to issue a scoped credential. There were no compile, load or fixture faults.
- **Task 2** (run on the Task 1 tree).
  - The target (delete) and the rotate arms failed with 404 on their routes.
  - Both last-used arms failed on "the first call is recorded" (still NULL).
  - The DB-wall arms already passed, because V82 shipped in Task 1. Their fail direction is arms J and J2.
  - One RLS arm, `apiNeverCrossesTenants`, passed vacuously at RED, because a missing route also answers 404. Arm J2 is its fail direction.
- **Task 3.**
  - The target got `shop-access-denied` instead of `human-only-action`.
  - **A declared machine client created a tenant-wide GROUP_ADMIN grant (201).** That is the T-37-107 escalation, live on the old tree.
- **Task 3b.**
  - A Group admin made a credential's service account a GROUP_ADMIN through `POST /api/v1/staff/grant` (201).
  - The precondition held: the service account was in `user_directory` after one credential call.
- **Evidence hygiene.** INFO lines named `event=credential_*` were trimmed from the captured system-out of `37-45-red-task2.json`, `37-45-red-task3.json` and `37-45-red-task3b.json`.
  - gitleaks generic-api-key reads `credential… tenant=<uuid>` or `credential=<uuid>` as a secret.
  - The trim is recorded inside each file. No testcase, failure or count changed, and the classifier still returns RED_EVIDENCE_OK.
  - The production event names are now `integration_client_*` and the row id is `id=`.
  - gitleaks over the whole plan range (`b31b4f64..HEAD`) finds no leaks.

## Verification (both directions)

Every arm ran on a committed tree. Each file was restored with `cp` from a scratch copy and checked by sha256. `git diff --quiet HEAD` was clean before and after each batch.

| Arm | Break | Fail direction (real output) | Pass |
|-----|-------|------------------------------|------|
| A | no-store removed, with `contains("no-store")` | **8/0, so the check could NOT fail.** Spring Security stamps `no-cache, no-store, max-age=0, must-revalidate` on every authenticated response | — |
| A2 | same break, check now `isEqualTo("no-store")` | 1 failed: "but was: no-cache, no-store, max-age=0, must-revalidat…" | 8/0 |
| B | the secret written into `idempotency_key` | 1 failed: "[no column of the credential row holds the secret]" | 8/0 |
| C | grant written for all shops | 2 failed: shop B returned 200 with orders; grant shop expected A, was null | 8/0 |
| D | role-ceiling check removed | 1 failed: 409 "Data integrity constraint violated" (the V82 CHECK refused it) instead of 400 | 8/0 |
| E | `execute` (stores the response) instead of `executeWithoutStoringResponse` | 1 failed: the replay body carried `clientSecret` | 8/0 |
| M | rollback hook not registered | 1 failed: "Wanted but not invoked: keycloakAdminClient.deleteClient" | 14/0 |
| G | revoke keeps the grant | 1 failed: "[the service account's grant is gone] expected 0 but was 1" | 13/0 |
| H | filter does not refuse revoked tokens | 1 failed: credential-revoked expected, shop-access-denied actual (the grant deletion still refused it) | 13/0 |
| I | every call stamps last_used_at | 1 failed: "[a second call inside the interval writes nothing]" | 13/0 |
| J | `FORCE ROW LEVEL SECURITY` removed from V82 | RLS IT 1 failed: "[A's session cannot see B's credential] expected 0 but was 1" | 4/0 |
| J2 | FORCE removed, finders without a tenant predicate | RLS IT 2 failed, including the API arm ("B never sees A's credential") | 4/0 |
| K | stamp run with TenantContext cleared | superuser IT 13/0; NOSUPERUSER IT 1 failed: "recorded under the NOSUPERUSER role… not to be null" | 4/0 |
| L | default interval PT30M | 2 failed: "expected 15 but was 30", and the re-stamp after 16 minutes did not happen | 13/0 |
| N | guard always passes | 3 failed (shop-access-denied; the machine client and the integration-orders-rw admin both granted, 201) | 17/0 |
| O | guard removed from staff grant only | 3 failed | 17/0 |
| P | `azp` dropped from `TenantJwts.vendorJwt` | 16 of 17 failed with human-only-action, so the default is load-bearing | 17/0 |
| Q | old OpenAPI snapshot | OpenApiSnapshotTest 1 failed ("no longer matches the reviewed snapshot") | 1/0 |
| R | k8s injection removed | `render-golden.sh` rc 1, showing `- name: ACCESS_HUMAN_CLIENT_IDS` in both renders | rc 0 |
| S | the pre-fix filter (hard repository dependency) | **unit suite 1821 tests, 72 failures** in 11 `@WebMvcTest` classes | 1821/0 |
| metrics | 22 new `@Test` before `--write` | `docs-freshness.sh` rc 1 (2749 vs 2771); `check-doc-metrics.sh` FAIL on 9 prose claims after `--write` | both rc 0 (37/37) |

**Suites on the final committed tree (`478faff2`):**
- **Full core-java unit suite** (`cleanTest test`): **1821 tests, 0 failures, 0 errors, 1 skipped**, in 210 reports. None was older than the run.
- **Full core-java integrationTest** (`cleanIntegrationTest integrationTest`, 04:06:15Z to 04:36:28Z): **1219 tests, 0 failures, 0 errors, 1 skipped**, in 211 reports. None was older than the run. This is the first full integration run since 37-23/37-31, so it also covers 37-39 to 37-44, including the 37-44 Keycloak client changes.
- **Static gates:** all rc 0.
  - `k8s/scripts/check-env-contract.sh`: 82 injected, 181 read.
  - `k8s/scripts/check-render-invariants.sh`.
  - `k8s/scripts/render-golden.sh`.
  - `scripts/check-no-create-extension.sh`: 83 migrations.
  - `scripts/docs-freshness.sh`.
  - `scripts/check-doc-metrics.sh`.
  - gitleaks over the plan range.
- **Two plan checks that cannot fail by construction:**
  - `ConfigKeyContractTest` (9/0) checks only `spring.*`, `management.*`, `server.*` and `logging.*` keys, so it cannot fail for `jtoye.credentials.*` or `jtoye.access.*`. Arm L is the stronger replacement: the list test reads the bound interval.
  - `check-env-contract.sh` cannot detect a missing `ACCESS_HUMAN_CLIENT_IDS` injection, because `core-api` is not a local-only default. The committed goldens pin it instead (arm R).

## Decisions Made

See `key-decisions` above. Two more:
- **Revoke order.** The database writes are flushed first and Keycloak is called last, inside the transaction.
- **Rotate order.** Keycloak is called first and the stamp written after. A failed stamp therefore leaves a rotated secret that the caller rotates again, which is safe. The other order could record a rotation that never happened.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Security] A staff grant could make a credential GROUP_ADMIN**
- **Found during:** Task 3, while checking the plan's assumption-delta invariant.
- **Issue:** The D-09 directory touch puts the credential's service account in `user_directory` on its first call. `grant()` checks only that, so a Group admin could grant the service account GROUP_ADMIN. RED showed it: 201.
- **Fix:** `StaffManagementService.grant` refuses any credential's service-account user with 409 `staff-target-is-credential`. It goes through the new `IntegrationCredentialRepository.existsByTenantIdAndServiceAccountUserId`.
- **Files:** `StaffManagementService`, the repository, the exception, the handler, `StaffController`'s OpenAPI 409 text.
- **Commits:** `99b80fab` (RED), `71c68425` (GREEN).

**2. [Rule 2 - Security] Revoked tokens refused at once**
- **Issue:** Under strict scoping ON, revocation already refuses every shop-gated endpoint by deleting the grant. It did not cover endpoints with no shop gate, the token's remaining lifetime, or the strict-OFF override.
- **Fix:** The last-used filter answers a revoked credential's token with 403 `credential-revoked`. Arms H and G show the two layers are independent.
- **Commit:** `f65964b3`.

**3. [Rule 2 - Security] Rollback cleanup and the orphan sweep**
- **Issue:** Keycloak and Postgres cannot share a transaction, and the plan says nothing about a create that fails halfway. Without cleanup that would leave a working client whose secret nobody holds.
- **Fix:** An afterCompletion hook deletes the client and the grant when the transaction does not commit. A `createClient` that fails after Keycloak made the client is swept by its clientId.
- **Verified by:** arm M.
- **Commits:** `ea0561f7`, `76d200c5`.

**4. [Rule 3 - Blocking] `idempotency_key` column on V82**
- **Issue:** A replay has to return the credential it created, and `executeWithoutStoringResponse` stores nothing.
- **Fix:** The key is recorded on the row, with a partial unique index.
- **Commit:** `ea0561f7`.

**5. [Rule 2 - Correctness] k8s and compose wiring for `ACCESS_HUMAN_CLIENT_IDS`**
- **Issue:** Staging and production run the dashboard as `frontend`. The application.yml default `core-api` would have refused staff management to every person there.
- **Fix:** core-java now reads `app-config/keycloak.client-id`, the frontend's own key. The goldens were regenerated (+5 lines each). Compose reads `KEYCLOAK_CLIENT_ID`.
- **Commit:** `5b32856e`.

**6. [Rule 1 - Bug] The filter broke every `@WebMvcTest` slice**
- **Issue:** 72 unit failures on `f65964b3`..`71c68425`, because a Filter bean is part of every slice and the slices have no JPA.
- **Fix:** The repository and the transaction manager now come through `ObjectProvider`.
- **Commit:** `478faff2`.

**7. [Rule 1 - Unfalsifiable check] `Cache-Control` `contains("no-store")`**
- **Issue:** The check could not fail (arm A).
- **Fix:** It is replaced by `isEqualTo("no-store")`. Arm A2 reds that form.
- **Commit:** `2c58dea7`.

**8. [Rule 3 - Blocking] Five existing ITs built person tokens without `azp`**
- **Issue:** StaffManagement, StaffInvite, StaffInviteAccept, VanishedRowStatus and CustomerAccessGate built their own person JWTs.
- **Fix:** Each one now carries `azp=core-api`, the plan's "azp default" applied to their own builders. All are green.
- **Commit:** `5b32856e`.

**9. [Rule 3 - Blocking] Small additions outside the plan**
- **Log events renamed.** The `integration_client_*` names keep gitleaks clean on captured evidence.
- **operationIds.** The credential handler methods have unique names, so springdoc renumbers no existing operationId.
- **Eight typed exception classes.** These are in `uk.jtoye.core.exception`, where the project keeps them.
- **Metrics regenerated.** The 22 new `@Test` methods moved the counts; V82 is recorded as shipped in CLAUDE.md and AGENTS.md.
- **Commits:** `5b32856e`, `f11f32e5`.

---

**Total deviations:** 9 (4 Rule 2, 2 Rule 1, 3 Rule 3).
**Impact on plan:** Every behaviour in `<feature>` shipped as specified. Deviations 1, 2 and 5 close real security or availability gaps. None widens scope beyond the credential feature.

## Issues Encountered

None beyond the deviations.

## Known Stubs

None.

## Threat model

- **T-37-107:** mitigated.
  - `HumanCallerGuard` is on every credential and staff mutation.
  - The role ceiling is enforced in the service and by the DB CHECK.
  - The new staff-grant refusal closes the remaining path.
  - Arms N, O, P and D cover it.
- **T-37-108:** mitigated.
  - The secret appears only in the create and rotate responses, with an exact no-store header.
  - It is in no column, `idempotency_keys` stores no response, and `CredentialSecretDto.toString()` is redacted.
  - No log line carries it.
  - Arms B, E and A2 cover it.
- **T-37-109:** mitigated. FORCE RLS and the NOSUPERUSER proof are in place (arms J, J2, K).
- **T-37-110:** mitigated. The clientId is generated server-side, is UNIQUE, and has its prefix pinned by a CHECK.
- **T-37-111:** accepted, as the plan says. Core still uses the master-realm admin credentials, now for client management too. A least-privilege realm-management account is a follow-up.
- **T-37-SC:** no package was installed.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: new-endpoint | IntegrationCredentialController.java | Four new authenticated endpoints under /api/v1/credentials, covered by T-37-107..110 |
| threat_flag: filter | CredentialLastUsedRecorder.java | A new servlet filter on every authenticated request. It acts only for `azp` jtoye-int-*, does one indexed SELECT per such request, and fails open on a database error; the grant deletion is the primary wall |
| threat_flag: residual | infra/keycloak/realm-export.template.json (unchanged) | `core-api` has `serviceAccountsEnabled: true` in the dev realm, so its client-credentials token carries the dashboard `azp` and passes the human-only guard. It still needs a Group-admin grant, which no service account is given |

## For the orchestrator and 37-46 / 37-47 (record, decide)

- **Runtime.** The compose stack still runs images BEHIND the branch. V82, the new endpoints and the compose `ACCESS_HUMAN_CLIENT_IDS` take effect only after the 37-E gate (37-47) rebuilds and recreates them.
- **37-47 live proof.**
  - Decode a minted credential token: `tenant_id`, `aud=core-api`, `scope`, `azp=jtoye-int-…`.
  - Confirm core accepts it without `preferred_username`, `email` or `realm_access`. The ITs used that shape, against mocks.
  - Check 200 on the granted shop and 403 on another.
  - After a revoke, check that an already-minted token gets 403 `credential-revoked` and that a new mint fails.
  - Grep the core log for the 37-44 "honoured the inline" INFO or "did not honour" WARN.
- **37-46 (Developers page).**
  - It has eight problem types to render: credentials-not-configured (hide creation), credential-scope-invalid (uses `allowedScopes`), credential-role-invalid, human-only-action, credential-revoked, credential-client-missing, credential-service-unavailable (uses Retry-After) and staff-target-is-credential.
  - It should show the secret once, with copy-to-clipboard, and never cache it.
  - `lastUsedAt` is only as precise as `lastUsedIntervalMinutes`.
- **The Staff page.**
  - After a credential's first call, its service account appears in `user_directory` with no email or name, and in the Staff page "people" list.
  - The Staff page can still REVOKE a credential's grant. That only narrows access, but it leaves the credential ACTIVE with no grant, so the Developers page would overstate what it may do.
  - Hide credential service accounts from the Staff page, or label them; 37-46 or the owner decides.
- **Not covered by a test.** The grant half of the rollback cleanup (a failure after `persistNewGrant` commits) has no test. The client half has one (arm M).

## Owner questions

1. **Staging and production `keycloak.client-id`.** It is `frontend` in `app-config` today, and the human-only rule now depends on it being the dashboard's real client id in those realms. The realms are not in this repository. Please confirm before the 37-E PR deploys anywhere; if it is wrong, no person there can manage staff.
2. **Disable service accounts on `core-api`?** In the dev realm, `core-api` has `serviceAccountsEnabled: true`. Turning it off would remove the one machine identity that carries the dashboard `azp`. First check that nothing uses `core-api`'s client-credentials grant.

## User Setup Required

None new for local runs. For staging and production, see owner question 1. The value comes from the existing `app-config` key, so nothing new needs to be set if that key is right.

## Next Phase Readiness

Ready for 37-46, the Developers page, against these endpoints and problem types. The live Keycloak proof belongs to 37-47.

## Self-Check: PASSED

- All 24 created files exist on disk. That is 18 production and test sources, V82 and the four RED records; this SUMMARY is the 24th.
- All 12 plan commits are ancestors of HEAD on `phase-37-ops-readiness`, from `980ce74f` to `478faff2`.
- Acceptance criteria re-run on the final tree:
  - Task 1: RED then GREEN; Cache-Control is exactly no-store; the SQL scan finds no secret.
  - Task 2: each arm went RED then GREEN, and the FORCE break arm reds the cross-tenant arms (J, J2).
  - Task 3: RED then GREEN; every access suite is green with the `azp` default.
- The full unit suite (1821/0) and the full integration suite (1219/0) passed on `478faff2`.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*
