---
phase: 37-real-world-operations-readiness
plan: 44
subsystem: api
tags: [keycloak, integrator-credentials, service-account, jackson3, d-22, rwo-035, uxt-035]
status: complete

requires:
  - phase: 38 (38-07)
    provides: "KeycloakAdminClient on Boot 4 with Jackson 3 tools.jackson request bodies, and the by-content MockRestServiceServer test pattern (GARBAGE_KEYS)"
  - phase: 37-08
    provides: "createUser's Location-header id parse and its by-content create test, reused for the client UUID"
provides:
  - "KeycloakClientSpec(clientId, tenantId, scopes)"
  - "KeycloakAdminClient.createClient: a confidential service-account client with a hard-coded tenant_id claim (the issuing tenant, access token only) and aud=core-api; read back after create and repaired (scopes by id, unrequested scopes removed, missing mappers added); a failed check deletes the half-made client"
  - "KeycloakAdminClient.findClientUuid, getServiceAccountUserId, getClientSecret, regenerateClientSecret, deleteClient, assignDefaultClientScopes"
  - "KeycloakClientExistsException (409 on create), KeycloakClientNotFoundException (404 on a client call; delete returns false)"
affects: [37-45 credential service, 37-46 Developers page, 37-47 the 37-E gate live Keycloak proof]

actuals:
  tokens: 26640
  tasks: 2
  commits: 6
plan_head_before: 763db369c798a52cab91d89a890a00a19866e483
plan_head_after: daf2124157b135f09c24406cf68a5c3f901ba576

tech-stack:
  added: []
  patterns:
    - "Create, then read back and repair: an admin API whose honouring of an inline field is undocumented is verified after every create, and only a difference triggers the fallback; the path taken is logged"
    - "A secret-bearing response that cannot be parsed raises WITHOUT a cause, so a parser that quotes its input cannot carry the secret into a log"
    - "A leak check is run against a mapper that WOULD leak (INCLUDE_SOURCE_IN_LOCATION), otherwise the default redaction makes it unable to fail"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakClientSpec.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakClientExistsException.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakClientNotFoundException.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-44-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-44-red-task2.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-44: the credential client copies integration-orders-rw's flags field by field (fullScopeAllowed true included) but its tenant_id is an oidc-hardcoded-claim-mapper set to the issuing tenant, in the access token only, so it needs no service-account user attribute (the KC24 admin-API strip, security-scopes §5)"
  - "37-44: default client scopes are exactly the requested ones and optionalClientScopes is empty; the template's realm defaults (roles, profile, email, acr, web-origins) and optional scopes (offline_access, address, phone, microprofile-jwt) are NOT copied, so a credential token carries no realm roles and cannot ask for offline access"
  - "37-44: every create reads back default scopes, optional scopes and mappers; the fallback (assign by id, remove the unrequested, POST missing mappers) runs only on a difference, and a failed check deletes the client before the error propagates"
  - "37-44: 409 on create is KeycloakClientExistsException, 404 on any client call is KeycloakClientNotFoundException except delete (false); every message names the realm only, and a secret response that cannot be used raises without a cause"

patterns-established:
  - "Read back what an admin API was asked to create, and repair the difference, when the API's honouring of the request is not documented"

requirements-completed: [RWO-035]

coverage:
  - id: D1
    description: "createClient POSTs a confidential service-account client (integration-orders-rw's flags) with a hard-coded tenant_id = issuing tenant in the access token and the aud=core-api mapper, exactly the requested default scopes, no optional scopes and no secret, asserted field by field and as a whole; UUID from Location, else by exact clientId"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java#createClient_postsAServiceAccountClient_withTenantAndAudienceMappers_byContent_andReturnsTheUuidFromLocation, #createClient_withoutLocation_readsTheUuidBackByClientId, #findClientUuid_returnsOnlyTheExactClientIdsUuid_andEmptyWhenNone (RED 4/4 at cbef64cb; arm A audience mapper dropped and arm B wrong tenant each red the target)"
        status: pass
    human_judgment: false
  - id: D2
    description: "getServiceAccountUserId returns the SA user's id (the token sub D-23 keys the grant on); 404 is the typed not-found"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "KeycloakAdminClientTest#getServiceAccountUserId_readsTheServiceAccountUsersId, #getServiceAccountUserId_404_isTypedNotFound"
        status: pass
    human_judgment: false
  - id: D3
    description: "RESEARCH A2/A4 fallback: each create reads back default scopes, optional scopes and mappers; absent requested scopes are PUT by the id looked up by name, unrequested scopes are DELETEd, missing mappers are POSTed by content; an unknown scope is refused; a failed check deletes the half-made client"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "KeycloakAdminClientTest#createClient_whenKeycloakIgnoredTheInlineLists_assignsScopesById_removesTheRest_andAddsTheMappers, #assignDefaultClientScopes_looksIdsUpByName_andPutsEach, #assignDefaultClientScopes_aScopeTheRealmDoesNotDefine_throwsNamingTheRealm, #createClient_postCreateCheckFails_deletesTheHalfMadeClient_andThrows (arms C2, C3 red)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Secret read and rotate return the CredentialRepresentation value; 404 typed; no exception in the chain carries the secret even under a source-quoting mapper; delete 204 true, 404 false, 5xx typed naming the realm; 409 on create typed"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "KeycloakAdminClientTest#getClientSecret_readsTheCredentialValue, #regenerateClientSecret_postsAndReturnsTheNewValue, #secretCalls_404_areTypedNotFound, #secretResponse_unusable_neverPutsTheSecretInTheExceptionChain, #deleteClient_sendsDelete_andReturnsTrueOn204, #deleteClient_returnsFalseOn404_alreadyGone, #deleteClient_serverError_propagatesAsKeycloakAdminException_namingTheRealm, #createClient_409_isTypedClientExists_namingTheRealm (arms C1, C4 red)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Live Keycloak 24.0.5 proof: which path (inline or fallback) the real server takes, and a minted token decoded for tenant_id, aud=core-api and the scopes, then 200/403 per scope and a failed mint after delete"
    requirement: "RWO-035"
    verification: []
    human_judgment: true
    rationale: "Owed to the 37-E gate 37-47 by plan design (VALIDATION manual-only row): MockRestServiceServer cannot tell what Keycloak 24.0.5 does with the inline lists, and the compose stack runs images BEHIND the branch (37-38) so a live check here would test stale code"

duration: 12min
completed: 2026-10-13
---

# Phase 37 Plan 44: Keycloak credential-client admin calls Summary

**Core can now mint an integrator credential as a Keycloak service-account client.** The client's tokens carry a hard-coded `tenant_id` (the issuing tenant) and `aud=core-api`. Every create reads the client back and repairs any difference. Core can also read the secret once, rotate it and delete the client. Every call is proven by content on the Boot 4 / Jackson 3 tree (D-22, RWO-035).

## Performance

- **Duration:** about 12 min
- **Started:** 2026-10-13T02:54:17Z
- **Completed:** 2026-10-13T03:05:50Z
- **Tasks:** 2. Both were TDD; Task 1 was a tracer.
- **Files:** 11. That is 3 new main classes, 1 modified main class, 1 test class, 2 RED records, metrics, README, CLAUDE.md and AGENTS.md.

## Accomplishments

- **`createClient(realm, spec, token)`** posts a ClientRepresentation that copies `integration-orders-rw` (`infra/keycloak/realm-export.template.json`) field by field:
  - The client is client-credentials only. `serviceAccountsEnabled` is true; `standardFlowEnabled`, `implicitFlowEnabled`, `directAccessGrantsEnabled` and `publicClient` are false.
  - It uses the `client-secret` authenticator and `fullScopeAllowed true`.
  - No secret is sent. Keycloak generates one.
- **The two protocol mappers:**
  - `tenant-id-mapper` is an `oidc-hardcoded-claim-mapper`. Its config is `claim.name=tenant_id`, `claim.value=<issuing tenant>`, `jsonType.label=String` and `access.token.claim=true`, with the id-token and userinfo claims false.
  - `core-api-audience-mapper` is the template's own `oidc-audience-mapper` (`included.client.audience=core-api`, `access.token.claim=true`).
- **Scopes:** the default client scopes are exactly `spec.scopes()`, and the optional scopes are empty.
- **Finding the UUID:** it comes from the `Location` header. Without one it comes from `GET ?clientId=`, which accepts only an exact clientId match.
- **Post-create read-back** (RESEARCH A2/A4). After every create, core reads `.../default-client-scopes`, `.../optional-client-scopes` and `.../protocol-mappers/models`. If they match the request it does nothing more. If they differ:
  - Each absent requested scope is assigned with `PUT .../default-client-scopes/{id}`. The ids come from `GET /client-scopes` by name, through `assignDefaultClientScopes`.
  - Every scope that was not requested is removed with `DELETE`.
  - Each missing mapper is added with `POST .../protocol-mappers/models`.
  - The path taken is logged: INFO when Keycloak honoured the inline lists, WARN when the fallback ran. The log names the realm only.
  - If the check itself fails, core deletes the client before rethrowing, so no half-configured credential is left in Keycloak.
- **The other calls:** `getServiceAccountUserId` (the token `sub` for the D-23 grant), `getClientSecret` and `regenerateClientSecret` (both read the CredentialRepresentation `value`), `deleteClient` and `findClientUuid`.
- **Typed errors:** a 409 on create raises `KeycloakClientExistsException`. A 404 raises `KeycloakClientNotFoundException`, except on delete, which returns `false`. Any other failure raises `KeycloakAdminException`, and every message names the realm only. If a secret response cannot be used, the exception is raised **without a cause**.

## Task Commits

1. **Task 1 (tracer): create a service-account client with tenant and audience mappers, and read its service-account user**
   - `cbef64cb` (test, RED)
   - `2a7c5c21` (feat, GREEN)
   - Tracer gate: the mode is end-of-phase and the verify is automated only. I re-ran the verify on the clean committed tree: `KeycloakAdminClientTest` 20/0. Then I expanded to Task 2.
2. **Task 2: secret read, rotate and delete, with typed errors and the scope fallback**
   - `35ea59eb` (test, RED)
   - `6fe19ddd` (feat, GREEN)
   - `ad6842c3` (test: the strengthened secret-leak check; see Deviations)
3. **Metrics:** `daf21241` (docs: 5837 → 5854)

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction arms |
|------|------------------------|-------|---------------------|
| 1 | `cbef64cb`: `37-44-red-task1.json` RED_EVIDENCE_OK (JUnit: 20 tests, 4 failed) | `2a7c5c21`: KeycloakAdminClientTest 20/0 | A, B |
| 2 | `35ea59eb`: `37-44-red-task2.json` RED_EVIDENCE_OK (JUnit: 33 tests, 15 failed; package 48/15) | `6fe19ddd`: Keycloak package 48/0 | C1 (after `ad6842c3`), C2, C3, C4 |

There is no separate REFACTOR commit. The javadoc operation list was updated inside the Task 2 GREEN commit, before it was committed.

**RED semantic assessments.**
- **Task 1.** The target failed at its first planned content assertion: the captured `/clients` body was expected to be not blank but was null. Core had no create call; the RED stubs return null without calling Keycloak. The Location-fallback, `findClientUuid` and service-account-user arms failed on their planned value assertions. The 16 pre-existing cases passed, so the fixture loads. There were no compile, load or fixture failures.
- **Task 2** (run on the Task 1 GREEN tree). The target was the ignored-inline-lists fallback. It failed on the ordered verify: only the POST ran and 12 expectations were left unsatisfied, because there was no post-create check and no fallback. The two Task 1 create tests failed the same way, with their three read-back GETs unsatisfied. Every other new arm failed on its planned assertion:
  - The secret read and rotate returned null.
  - The delete returned false without making a request.
  - A 409 came back as the generic exception, not the typed one. So did the service-account 404.
  - The unknown-scope, cleanup and leak arms threw nothing.
  - `CustomerAccountDeletionServiceTest` 11/0 and `KeycloakDeprovisionServiceTest` 4/0 stayed green.

## Verification (both directions)

I ran every arm on a committed tree, restored it with `cp` from a scratch copy, and confirmed `git diff --quiet HEAD -- core-java` (rc 0) afterwards. For the C arms I also compared sha256 against the backup, and they matched.

| Arm | Break | Fail direction (real output) | Pass direction |
|-----|-------|------------------------------|----------------|
| RED T1 | RED stubs | 20 tests, 4 failed ("[captured POST /clients body] Expecting not blank but was: null"; UUID null; Optional empty; SA id null) | `2a7c5c21`: 20/0 |
| A (plan's arm) | `mappers.add(audienceMapper())` removed | 20 tests, 1 failed: the target, "no protocol mapper named core-api-audience-mapper in [{"name":"tenant-id-mapper",...}]" | restored; 20/0 |
| B (T-37-104) | `claim.value` set to a random UUID | 20 tests, 1 failed: "[claim.value = the issuing tenant] expected: 6f1c2b8e-… but was: 8980a8b4-…" | restored; 20/0 |
| RED T2 | RED stubs | 33 tests, 15 failed (target: "Further request(s) expected leaving 12 unsatisfied expectation(s). 1 request(s) executed") | `6fe19ddd`: 33/0, package 48/0 |
| C1 (T-37-106) | the secret parse failure keeps the parser exception as its cause | **first run: 33/0, so the check could NOT fail** (see Deviations); after `ad6842c3`: 33 tests, 1 failed: "[exception chain message] Expecting actual: "Unexpected end-of-input … [Source: (String)…" not to contain" the fixture secret | restored; 33/0 |
| C2 | the fallback never deletes unrequested scopes | 33 tests, 1 failed: the fallback test, "Request URI expected:<…/default-client-scopes/cs-roles> but was:<…/optional-client-scopes>" | restored; 33/0 |
| C3 | no cleanup delete after a failed post-create check | 33 tests, 1 failed: "Further request(s) expected leaving 1 unsatisfied expectation(s). 2 request(s) executed" | restored; 33/0 |
| C4 | the secret `value` check removed | 33 tests, 1 failed: leak test, "expected KeycloakAdminException but was NullPointerException" | restored; 33/0 |
| Metrics | 17 new `@Test` before `--write` | `docs-freshness.sh` rc 1 (2732 vs 2749); after `--write`, `check-doc-metrics.sh` FAIL on 7 prose claims | both PASS (37/37) after the prose moved |

**Suites on the final tree (`daf21241`):**
- Full core-java unit suite (`cleanTest test`): **1821 tests, 0 failures, 0 errors, 1 skipped**, in 210 JUnit reports. All 210 were written after the run started (0 older), so no report was stale.
- The integration suite was not run. The only production classes changed are the Keycloak admin client and its new spec and exception types, all in `uk.jtoye.core.tenant.keycloak`. `KeycloakAdminClient`'s constructor and bean wiring are unchanged.
- gitleaks found no leaks in either RED record or in the test file. The fixture secret is a low-entropy, plainly fake string (`fixture-only-not-a-real-client-secret`).

## Files Created/Modified

- `KeycloakClientSpec.java`: the credential spec. It refuses a blank clientId and an empty or blank scope, and copies the scope list.
- `KeycloakAdminClient.java`: the seven new calls and the post-create read-back and repair. The class javadoc now lists 15 operations and T-37-106.
- `KeycloakClientExistsException.java`, `KeycloakClientNotFoundException.java`: the typed 409 and 404 exceptions. Both are subclasses of `KeycloakAdminException`, so existing catch sites still catch them.
- `KeycloakAdminClientTest.java`: 17 new cases, the shared `assertClientBodyByContent` and `expectedClientRep`, the `expectInlineHonoured` helper, and `newFixture(JsonMapper)`.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: the test counts (5837 → 5854).

## Decisions Made

See `key-decisions` above. The two worth a sentence each:
- **No realm default scopes and no optional scopes.**
  - The template client lists `roles`, `profile`, `email`, `acr` and `web-origins` as defaults, and `offline_access` among its optional scopes. A credential issued to a vendor's integrator should carry only the #206 scopes it was granted.
  - Without `roles`, no `realm_access` claim is emitted. That matters because the D-09 bridge treats a token with the realm `admin` role as a Group admin.
- **The read-back runs on every create, not only on failure.** A Keycloak that ignored the inline list would not fail. It would silently attach the realm defaults, `roles` included. Only reading the client back can tell.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] Two typed exception classes, not in `files_modified`**
- **Found during:** Task 2 RED
- **Issue:** The plan requires a typed 409 and a typed not-found, but lists only three files.
- **Fix:** Added `KeycloakClientExistsException` and `KeycloakClientNotFoundException`, in the shape of the 37-08 `KeycloakUserExistsException`.
- **Committed in:** `35ea59eb`

**2. [Rule 2 - Security] The fallback also REMOVES unrequested scopes, checks optional scopes and repairs missing mappers; a failed check deletes the client**
- **Found during:** Task 2
- **Issue:** The plan's action names only a PUT of the absent requested scopes. A Keycloak that ignored the inline list would leave the realm defaults attached, `roles` included, and adding the requested scopes would not remove them. Must-have 4 also names the protocol-mappers fallback. And a create whose check failed would leave an orphan client behind.
- **Fix:** Reconcile both scope kinds in both directions. POST each missing mapper to `.../protocol-mappers/models`. Make a best-effort `deleteClient` on a failed check, with any cleanup failure recorded as suppressed.
- **Verification:** the fallback test and arms C2 and C3.
- **Committed in:** `6fe19ddd`

**3. [Rule 1 - Unfalsifiable check] The secret-leak test could not fail**
- **Found during:** Task 2 arm C1
- **Issue:** Attaching the parser exception as the cause left the leak test green, 33/0. Jackson 3's default mapper redacts the source in error locations, so the parser never put the secret anywhere the test could see it.
- **Fix:** The test now builds the client with `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` enabled. C1 then reds it with the secret visible in the cause message, and the committed tree passes 33/0. The original, weaker form is replaced, not kept beside it.
- **Committed in:** `ad6842c3`

**4. [Rule 3 - Blocking] Metrics regenerated**
- **Issue:** The 17 new `@Test` methods red `docs-freshness.sh` and `check-doc-metrics.sh`.
- **Fix:** Regenerated with `--write` and moved the prose counts, as in 37-43.
- **Committed in:** `daf21241`

---

**Total deviations:** 4 auto-fixed (2 Rule 2, 1 Rule 1, 1 Rule 3).
**Impact on plan:** All four serve correctness or least privilege, and the scope is unchanged. The plan's own break arm (A) behaved as specified.

## Issues Encountered

- The RED classifier rejected the first Task 1 record as `no_target_test_failure`, because the target name lacked the `()` suffix that JUnit XML emits. Re-pointed at `KeycloakAdminClientTest#…()`, it returned RED_EVIDENCE_OK. No test or report changed.

## Notes for 37-45 / 37-47 (record, decide)

- **Which path a real Keycloak takes is unknown until 37-47.** Its live proof should grep the core log for "honoured the inline credential client representation" (INFO) or "did not honour" (WARN). It should also decode a minted token for `tenant_id`, `aud=core-api` and `scope`.
- **Credential tokens carry no `preferred_username`, `email` or `realm_access`.** That follows from having no profile, email or roles scope. 37-45 and 37-47 must confirm core accepts such a token: `sub` and `azp` are present, and `tenant_id` comes from the hard-coded mapper. Anything in core that keys on `preferred_username` for a machine caller would see null.
- **`KeycloakClientExistsException`:** the credential service should generate a new `jtoye-int-<random>` and retry once rather than surface it.
- **`createClient` may throw after Keycloak created the client**, if the UUID lookup fails. In that case no cleanup is possible from here, because there is no UUID. 37-45 could sweep by clientId if it wants that covered.

## Threat model

- **T-37-104:** the claim value is set server-side from the spec. Arm B reds on a wrong tenant. The live decode is owed to 37-47.
- **T-37-105:** the audience mapper is mandatory. The plan's arm A reds without it, and the fallback re-adds it if Keycloak dropped it.
- **T-37-106:** no body or secret is logged. Messages name the realm only. An unusable secret response raises without a cause, and the leak test is shown able to fail (C1).
- **T-37-SC:** no package was installed.
- No new network endpoint, auth path or schema; the surface is outbound admin calls only, as the threat model already covers.

## User Setup Required

None.

## Next Phase Readiness

Ready for 37-45, the credential service, table and endpoints. The live Keycloak proof of every call is 37-47's job, on rebuilt images.

## Self-Check: PASSED

- All created files exist: `KeycloakClientSpec.java`, `KeycloakClientExistsException.java`, `KeycloakClientNotFoundException.java`, `37-44-red-task1.json` and `37-44-red-task2.json`.
- All six commits are ancestors of HEAD on `phase-37-ops-readiness`: `cbef64cb`, `2a7c5c21`, `35ea59eb`, `6fe19ddd`, `ad6842c3` and `daf21241`.
- I re-ran the acceptance criteria. Task 1 went RED then GREEN, with arm A recorded. Task 2's verify (`uk.jtoye.core.tenant.keycloak.*`) gives 48/0 on the final tree, and every 31.1 and 37-08 case is green.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*
