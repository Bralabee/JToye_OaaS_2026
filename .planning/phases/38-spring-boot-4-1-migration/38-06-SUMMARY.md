---
phase: 38-spring-boot-4-1-migration
plan: 06
subsystem: auth
tags: [spring-boot-4, spring-security-7, oauth2-resource-server, rfc6750, rfc9728, problem-detail, jackson-3]

requires:
  - phase: 38-02
    provides: the exact-challenge assertions and the measured Boot 3.5.16 baseline for /.well-known/oauth-protected-resource (401 anonymous, 404 authenticated)
  - phase: 38-03
    provides: the Boot 4.1.1 / Spring Security 7.1.1 tree and Boot's Jackson-3 JsonMapper bean
  - phase: 38-05
    provides: the owner decision jackson3-defaults (alphabetical ProblemDetail key order)
provides:
  - "D-04: every 401 carries the plain RFC 6750 challenge again (resource_metadata stripped by an auth-param parser in a response wrapper), body written by Boot's Jackson-3 JsonMapper"
  - "D-05 (owner refinement anon-401-parity): ProtectedResourceMetadataSuppressionFilter answers GET /.well-known/oauth-protected-resource[/**] with the standard 401 when no credentials are presented and the application's 404 when they are; the framework's false tls_client_certificate_bound_access_tokens claim is unreachable"
  - "Permanent tests: ProtectedResourceMetadataSuppressionFilterTest (18), UnauthenticatedProblemDetailIntegrationTest (14, incl. same-401/same-404 parity, filter order, the pinned residual)"
affects: [38-07, 38-16, 38-17, 38-18, 38-19]

actuals:
  tokens: 21148    # chars/4 over the added lines of the realized diff e455eead..7d66328e (84593 chars, 9 files)
  tasks: 2
  commits: 8
plan_head_before: e455eeadd8cc5c4f324f9f193e556929827d08db
plan_head_after: 7d66328e627b72619958ea1c69d9b1618b6db8b9

tech-stack:
  added: []
  patterns:
    - "Suppress a framework endpoint with the framework's own matcher, rebuilt (PathPatternRequestMatcher.withDefaults(), same method and pattern), so the suppressed set is exactly the served set"
    - "Parity by measurement: compare status, every header and the body bytes with a reference route through the real chain, instead of asserting fields one by one"
    - "A pre-authentication filter answers 401 through the chain's own AuthenticationEntryPoint, so the challenge and document cannot drift from every other 401"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java
    - core-java/src/test/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilterTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-06-security.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPoint.java
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - core-java/src/test/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPointTest.java
    - core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java
    - .planning/phases/38-spring-boot-4-1-migration/38-CONTEXT.md
    - .planning/REQUIREMENTS.md

key-decisions:
  - "38-06: the owner chose anon-401-parity for D-05 (exact words \"anon-401-parity (Recommended)\", 2026-10-05): no credentials -> the standard plain-Bearer 401 through ProblemDetailAuthenticationEntryPoint; credentials present -> the 404 not-found document. No corrected metadata; the false claim is unreachable for every caller"
  - "38-06: recorded residual: the suppression answers before authentication, so a well-formed but invalid/expired bearer gets 404 on that path where Boot 3.5 gave 401 invalid_token. ADR-0006 (38-16) and the phase PR body (38-18) must name it; a test pins it"
  - "38-06: 'credentials' = what the resource server accepts: a bearer resolved by DefaultBearerTokenResolver (no custom resolver exists), or an already-authenticated context. A Basic header is anonymous (401); a malformed bearer gets the resolver's own invalid_token 401, the same refusal as any protected route"
  - "38-06: the filter is anchored after CorsFilter. addFilterBefore(OAuth2ProtectedResourceMetadataFilter) is refused at build ('does not have a registered order'), and after CorsFilter (not the plan's HeaderWriterFilter fallback) keeps the CORS Vary of every other 401"
  - "38-06: 38-17's automated live probe (anonymous curl expects 404) and 38-16's ADR wording ('is 404') encode D-05 as first written; under the owner's decision the anonymous answer is 401. Those plans must expect 401 without a token and 404 with one"

patterns-established:
  - "Framework-endpoint suppression: rebuild the framework's matcher, never call the chain for a match, answer through the application's existing error writers, pin the filter index on the BUILT chain"

requirements-completed: []  # plan declares [BOOT4-09, BOOT4-04]; both shared with plans not yet summarised (BOOT4-09: 38-17; BOOT4-04: 38-07..38-10, 38-12, 38-19); requirements.ready-ids reports 0/2 ready

coverage:
  - id: D1
    description: "D-04: a 401 carries exactly 'Bearer' (missing token) or the RFC 6750 error form without resource_metadata (bad token), and the 401 body is Boot's Jackson-3 problem document byte for byte"
    requirement: "BOOT4-09"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPointTest.java (18/18)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java#missingBearerReturnsRfc7807ProblemDocument, #garbageBearerReturnsRfc7807ProblemDocument, #unauthorizedBodyIsBootsJackson3Document"
        status: pass
    human_judgment: false
  - id: D2
    description: "D-05 with anon-401-parity: GET /.well-known/oauth-protected-resource and any path under it answers 401 (plain Bearer, the same status/headers/body as GET /api/v1/products) without credentials and 404 (the same document as an unmapped path) with them; never 200 or the false claim"
    requirement: "BOOT4-09"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilterTest.java (18/18)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java#wellKnownProtectedResource_unauthenticated_returns401, #wellKnownProtectedResource_authenticated_returns404, #..._isTheSame401AsAnyProtectedRoute, #..._isTheSame404AsAnUnmappedPath, #..._suffixPath_isSuppressedForBothCallerKinds, #..._malformedBearer_isTheSameRefusalAsAnyProtectedRoute"
        status: pass
    human_judgment: false
  - id: D3
    description: "The suppression filter sits after HeaderWriterFilter and CorsFilter and before OAuth2ProtectedResourceMetadataFilter on the built chain; its responses carry nosniff, DENY and the referrer policy"
    requirement: "BOOT4-09"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java#suppressionFilterRunsAfterHeadersAndCorsAndBeforeTheFrameworkMetadataFilter"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java (6/6)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Fail-direction arms: registration removed -> 200 + tls_client_certificate_bound_access_tokens (8 red); 404-for-all -> the anonymous expectations red; filter ahead of headers/CORS -> header/parity/order red; each restored by content, closing run green"
    requirement: "BOOT4-09"
    verification:
      - kind: other
        ref: ".planning/phases/38-spring-boot-4-1-migration/evidence/38-06-security.txt (arms R, A, H; Clean again)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Live proof on the rebuilt runtime (curl -i: anonymous 401 Bearer, with a bearer 404, never 200)"
    requirement: "BOOT4-09"
    verification: []
    human_judgment: true
    rationale: "Not run here: the plan prohibits starting, stopping or rebuilding compose services. Owned by 38-17, whose probe must be updated to the owner's decision (anonymous -> 401, not 404)"

duration: 43min
completed: 2026-10-05
status: complete
---

# Phase 38 Plan 06: D-04 plain Bearer and D-05 metadata-path suppression Summary

**Security 7's `resource_metadata` is stripped from every 401 by an RFC 7235 auth-param parser in a response wrapper, and a pre-authentication filter built on the framework's own matcher answers `/.well-known/oauth-protected-resource[/**]` with the standard 401 (no credentials) or the application's 404 (credentials), so the framework's false `tls_client_certificate_bound_access_tokens` metadata is unreachable.**

## Performance

- **Duration:** 43 min active (Task 1 08:32Z-08:46Z; Task 2 09:49Z-10:16Z). Wall clock 1h 44m including the owner-decision pause.
- **Started:** 2026-10-05T08:31:57Z
- **Completed:** 2026-10-05T10:16:00Z
- **Tasks:** 2/2
- **Files modified:** 9 (4 main/test Java modified, 2 Java created, evidence created, CONTEXT and REQUIREMENTS amended)

## Accomplishments

- **D-04 (Task 1, tracer):** `ProblemDetailAuthenticationEntryPoint` keeps the framework delegate (status and `error`/`error_description`/`error_uri` stay Spring's) but hands it a `ChallengeRewritingResponse` that drops `resource_metadata` only. The body is written by Boot's Jackson-3 `JsonMapper`. The 401s are once again the Boot 3.5 challenges: `Bearer` for a missing token, `Bearer error="invalid_token", ...` for a bad one.
- **D-05 (Task 2), as refined by the owner:** `ProtectedResourceMetadataSuppressionFilter` is not a bean. It is registered after `CorsFilter`, ahead of `OAuth2ProtectedResourceMetadataFilter`, and handles exactly `GET /.well-known/oauth-protected-resource/**`:
  - no credentials: the chain's entry point answers. Status, every header and the body bytes equal `GET /api/v1/products` without a token (measured).
  - credentials present: the 404 not-found document. Every header and every member except `instance` equal `GlobalExceptionHandler`'s 404 for an unmapped path (measured).
  - Both header sets are identical to the Boot 3.5.16 baseline (38-02).
- **Fail directions, recorded verbatim:**
  - Arm R: with the registration removed, every request to the path returns `200 {"resource":"http://localhost","bearer_methods_supported":["header"],"tls_client_certificate_bound_access_tokens":true}`, and 8 tests go red.
  - Arm A: 404 for every caller (D-05 as first written) fails only the anonymous expectations.
  - Arm H: placing the filter ahead of the header writer and CORS fails the header, parity and order tests.
- **Full unit suite:** 1385 tests (1367 + 18), with one failure: `KeycloakAdminClientTest`, which belongs to 38-07.

## Task Commits

1. **Task 1: tracer, a 401 on Boot 4 carries plain Bearer and the same problem document (D-04)**
   - `6cf6471f` test(38-06): RED
   - `fdb4651a` feat(38-06): GREEN
   - `7514eec5` docs(38-06): evidence and the D-05 stop
2. **Task 2: suppress /.well-known/oauth-protected-resource ahead of the framework filter (D-05)**
   - `2c7978cb` test(38-06): RED, with the owner's anon-401-parity expectations
   - `1e7e5ac7` feat(38-06): GREEN
   - `8f1d5c54` refactor(38-06): Javadoc wording, for AC1
   - `5bbf0d57` docs(38-06): evidence
   - `7d66328e` docs(38-06): CONTEXT D-05 addendum and BOOT4-09 wording

**Plan metadata:** recorded in the SUMMARY commit that follows `7d66328e`.

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java`: the D-05 filter, with the framework's matcher rebuilt and a 401 or 404 decided by credentials. Never calls the chain for a match.
- `core-java/src/main/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPoint.java`: D-04. Adds `stripResourceMetadata`, the auth-param parser, the `ChallengeRewritingResponse` wrapper and the Jackson-3 `JsonMapper`.
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`: a `JsonMapper` parameter, and the registration after `CorsFilter`, with the reason for the anchor.
- `core-java/src/test/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilterTest.java`: 18 unit cases covering both caller kinds, every path form, Basic, malformed and already-authenticated requests, and the pass-through table.
- `core-java/src/test/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPointTest.java`: exact challenges and a 15-row `stripResourceMetadata` table.
- `core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java`: the 38-02 baselines become the decided expectations. Adds parity, suffix, malformed, residual, filter-order and 403 cases.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-06-security.txt`: the owner decision verbatim, every changed expectation, RED/GREEN, the arms and the residual.
- `.planning/phases/38-spring-boot-4-1-migration/38-CONTEXT.md`: a dated D-05 addendum with the owner's exact words.
- `.planning/REQUIREMENTS.md`: the BOOT4-09 wording now states both answers and the residual. It stays unchecked.

## Decisions Made

- **Owner (2026-10-05), exact words "anon-401-parity (Recommended)"**:
  - no credentials → 401, plain `Bearer`, the 401 problem document, through the entry point;
  - credentials → 404 not-found;
  - the rest of D-05 stands.
- **Executor's reading of "credentials"**: a bearer token that the resource server's resolver accepts, or an already-authenticated context. It is not "any Authorization header". This keeps a `Basic` caller and a malformed bearer on exactly the refusal every other route gives. Recorded for review in the evidence (owner-visible). The literal reading would have given a `Basic` caller a 404.
- **Matcher**: the framework's own matcher (GET + `/**`), not a path-only check. The filter suppresses exactly what the framework serves, and POST and HEAD are untouched, as they are today.
- **Anchor**: after `CorsFilter` (see Deviations).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The plan's anchor was refused at build**
- **Found during:** Task 2 GREEN.
- **Issue:** `http.addFilterBefore(filter, OAuth2ProtectedResourceMetadataFilter.class)` → `IllegalArgumentException: The Filter class ...OAuth2ProtectedResourceMetadataFilter does not have a registered order`. All 13 integration tests errored on context load. The plan anticipated this.
- **Fix:** `addFilterAfter(filter, CorsFilter.class)`, not the plan's `HeaderWriterFilter` fallback. After `CorsFilter`, the 401/404 also carry the CORS `Vary` headers that every other response carries. Arm H shows that a filter placed ahead of both fails the parity tests.
- **Files modified:** SecurityConfig.java
- **Verification:** the filter-order test runs on the built chain (index after Cors, before the metadata filter). Arm H is red.
- **Committed in:** `1e7e5ac7`

**2. [Rule 1 - Bug, test] Wrong malformed-bearer expectation in RED**
- **Found during:** Task 2 GREEN.
- **Issue:** the RED unit case asserted `400 invalid_request`. Measured with javap: Security 7.1.1's `DefaultBearerTokenResolver` raises `invalid_token` "Bearer token is malformed" (401).
- **Fix:** kept the test's intent (framework parity) and corrected the value. Added an integration case that compares the answer with the real chain on `/api/v1/products`.
- **Files modified:** ProtectedResourceMetadataSuppressionFilterTest.java, UnauthenticatedProblemDetailIntegrationTest.java
- **Committed in:** `1e7e5ac7`

**3. [Rule 1 - Bug, doc vs. gate] AC1 matched the class's own Javadoc**
- **Found during:** acceptance-criteria verification.
- **Issue:** the Javadoc sentence "It is NOT a {@code @Component}" made `git grep '@Component'` print rc=0 (a rule firing on its own definition).
- **Fix:** reworded the sentence to avoid the token. The check was not weakened. A stronger form (zero annotation lines before the class declaration) is also recorded.
- **Committed in:** `8f1d5c54`

**4. [Rule 2 - Missing critical, planning artifacts] D-05's text contradicted the owner's decision**
- **Issue:** `38-CONTEXT.md` D-05 and REQUIREMENTS BOOT4-09 said "404" for every caller, and 38-16 and 38-17 read them as the source of truth.
- **Fix:** added a dated addendum quoting the owner's words, and amended the BOOT4-09 wording. Other plans' files were NOT edited (see Next Phase Readiness).
- **Committed in:** `7d66328e`

**Plan-text changes forced by the owner decision** (not executor deviations), each listed in the evidence as an expectation change:
- the method is `_unauthenticated_returns401`, not `_returns404`;
- the constructor is `(JsonMapper, AuthenticationEntryPoint)`, not `(JsonMapper)`;
- the 403 case asserts that `WWW-Authenticate` is absent, because the measured 403 comes from method security through `GlobalExceptionHandler` and carries no challenge at all.

---

**Total deviations:** 4 auto-fixed (1 blocking, 2 bugs, 1 missing-critical doc).
**Impact on plan:** none widens scope. The anchor change and the doc amendments keep the owner's decision consistent through the chain and the planning sources.

## Issues Encountered

- The machine's base-python guard blocked a scratch `/usr/bin/python3` script. JUnit XML is now parsed in the `engineering-doctrine` conda env, as the contract prescribes. The parser exits 3 (VOID) on a missing or zero-test file; this was tested with a nonexistent path.

## TDD Gate Compliance

- RED before GREEN for both tasks: `6cf6471f` → `fdb4651a` (Task 1) and `2c7978cb` → `1e7e5ac7` (Task 2). `git merge-base --is-ancestor` gives rc=0, and rc=1 in reverse.
- `gsd check tdd-red-evidence` returned RED_EVIDENCE_OK (target_test_failed) for all four RED records. In the fail direction, a record doctored to zero failures returned INVALID_RED.
- Optional REFACTOR: `8f1d5c54` (Javadoc only). The four test classes were green on it afterwards.

## Known Stubs

None. The scan for TODO/FIXME/placeholder found nothing in the files this plan touched (rc=1), and a positive control on a line containing "TODO" matched (rc=0).

## Threat Flags

None. The plan adds no endpoint; it closes the one the framework opened (T-38-13). T-38-14, T-38-15 and T-38-16 are mitigated as registered, and the evidence carries the arms.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- **For 38-17 (live runtime), must change:** its automated probe asserts `404` for an anonymous `curl` on the path. Under the owner's decision that request returns **401**, with `WWW-Authenticate: Bearer` and the 401 document. Expect 401 without a token, 404 with any well-formed bearer, and never 200.
- **For 38-16 (ADR-0006):** record D-05 as refined: the owner's exact words, both answers, and the residual. A well-formed invalid or expired bearer gets 404 on that path, where 3.5 gave `401 invalid_token`. The line that says the path "is 404" must name both caller kinds.
- **For 38-18 (PR body):** name the same residual as a contract note.
- **For 38-07:** `KeycloakAdminClientTest` is still the only red unit test.
- **Unverified:** the 403 pin (`forbiddenCarriesNoResourceMetadata`) passes on every tree in this plan. No arm here can turn it red, so its fail direction was not exercised.

## Self-Check: PASSED

- All six created/modified Java files and the evidence file exist (checked with `[ -f ]`).
- The commits `6cf6471f`, `fdb4651a`, `7514eec5`, `2c7978cb`, `1e7e5ac7`, `8f1d5c54`, `5bbf0d57` and `7d66328e` are all in HEAD's history.
- AC1 to AC5 and the plan-level verification were run in both directions; see the evidence file.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*
