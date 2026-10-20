---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 16
subsystem: notification
tags: [mail, starttls, tls, d-20, spring-profiles, tdd]
status: complete

requires:
  - phase: 29-14
    provides: "the running staging tracer (prod profile in every k8s environment)"
provides:
  - "spring.mail.properties.mail.smtp.starttls.enable: true and .required: true as LITERALS in application-prod.yml and application-staging.yml"
  - "Six profile-layered MailStartTlsPropertyTest cases (8 total) proving D-20 under prod/staging, the SMTP_STARTTLS knob unable to switch it off, and default/dev left opportunistic"
affects: [29-15, 29-17, 29-19, 29-21, 29-22, 29-18]

actuals:
  tokens: 9120
  tasks: 1
  commits: 2
plan_head_before: aba5e2e8632c1bd82997ed43124d179bc3014a43
plan_head_after: ed07f053fe2e9b12f2de35ae3ccf472c0574414c

tech-stack:
  added: []
  patterns:
    - "Profile-layered ApplicationContextRunner: the shipped application-<profile>.yml added ABOVE the shipped application.yml, both BELOW withPropertyValues (where an env var sits), OS env and JVM props removed"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-16-red-evidence.json
  modified:
    - core-java/src/main/resources/application-prod.yml
    - core-java/src/main/resources/application-staging.yml
    - core-java/src/test/java/uk/jtoye/core/notification/MailStartTlsPropertyTest.java
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - k8s/LOCAL.md

key-decisions:
  - "D-20 literal placed in application-prod.yml as the value governing every cluster (k8s runs prod everywhere); the staging copy protects only a host run with `staging`"
  - "Profile source inserted between the withPropertyValues source and application.yml (addLast, profile then base) rather than addFirst: Boot's real order, and an env var still outranks the profile file as it does at runtime"

requirements-completed: [DPLY-01]

duration: 9min
completed: 2026-10-19
---

# Phase 29 Plan 16: Required STARTTLS under the deployed profiles (D-20) Summary

**STARTTLS `enable: true` + `required: true` written as literals in application-prod.yml and application-staging.yml, proven test-first against the shipped YAML through Boot's real MailSenderAutoConfiguration, so neither a missing ConfigMap entry nor `SMTP_STARTTLS=false` can fall back to opportunistic TLS.**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-10-19T10:43Z
- **Completed:** 2026-10-19T10:52Z
- **Tasks:** 1 (RED + GREEN commits)
- **Files modified:** 9

## Accomplishments

- `application-prod.yml` (the profile `k8s/base/core-java-deployment.yaml` sets for every environment) and
  `application-staging.yml` now carry, merged under the existing top-level `spring:` key:
  `mail.properties.mail.smtp.starttls.enable: true` and `.required: true`, as literals, with a comment citing D-20,
  the owner ruling of 2026-10-18 and #952, and forbidding `mail.smtp.ssl.trust` (D-21).
- `MailStartTlsPropertyTest` grows from 2 to 8 cases: prod, prod + `SMTP_STARTTLS=false`, staging,
  staging + `SMTP_STARTTLS=false` (all must read enable=`true`, required=`true`); default and dev (required must be
  absent, enable `true`). The two DEF-29-6 cases are unchanged.
- Default and dev profiles are untouched: compose and the nightly stay opportunistic.

## TDD evidence (both directions)

Verify command (run from the repo root; the stale report deleted first so a run that executed nothing leaves no file):
`./gradlew :core-java:test --tests uk.jtoye.core.notification.MailStartTlsPropertyTest --rerun`, then the
plan's report regex over `core-java/build-local/test-results/test/TEST-uk.jtoye.core.notification.MailStartTlsPropertyTest.xml`.

**RED** (commit `23ae71bc`, before any YAML change) — gradle rc=1, `8 tests completed, 4 failed`, report
`tests="8" skipped="0" failures="4" errors="0"`:

| Case | Failure |
|------|---------|
| prod profile requires STARTTLS | `[mail.smtp.starttls.required …] expected: "true" but was: null` |
| prod + SMTP_STARTTLS=false | `[mail.smtp.starttls.enable …] expected: "true" but was: "false"` |
| staging profile requires STARTTLS | `[mail.smtp.starttls.required …] expected: "true" but was: null` |
| staging + SMTP_STARTTLS=false | `[mail.smtp.starttls.enable …] expected: "true" but was: "false"` |

The other four (two DEF-29-6, default, dev) passed. Classifier: `gsd_run check tdd-red-evidence
evidence/29-16-red-evidence.json` → `RED_EVIDENCE_OK` (reason `target_test_failed`, format junit, tests 8, fail 4).
Semantic assessment: VALID RED — every case executed, no load/fixture faults, exactly the four D-20 targets failed
on the planned assertion for the intended reason (no literal yet: required absent, enable governed by the
env-defaulted base key).

**GREEN** (commit `ed07f053`) — gradle rc=0, report `tests="8" skipped="0" failures="0" errors="0"`, verify
script prints `executed=8`, rc=0.

**Verify-script fail directions:** the same report check run against the RED report → rc=1 ("testsuite line does
not read tests=N skipped=0 failures=0 errors=0", failures="4"); run with no report present → rc=1 (`No such file`).

**Break arms (committed state first; clean → arms → clean):** planted, in one run, `required: true` in the BASE
application.yml and `enable: ${SMTP_STARTTLS:true}` in application-prod.yml (token `BREAK-ARM-29-16`). Result:
gradle rc=1, `8 tests completed, 3 failed` — exactly the three expected: prod + SMTP_STARTTLS=false
(`expected "true" but was "false"`), default and dev (`required … expected: null but was: "true"`). So the
opportunistic cases, which never failed in RED, are shown capable of failing. Restored with `git checkout --`;
verified by content: sha256 `aae586a8…` (application.yml) and `0dd6cc50…` (application-prod.yml) equal the pre-arm
values, `BREAK-ARM-29-16` count 0. Closing clean run: rc=0, `tests="8" … failures="0"`, executed=8.

## Other acceptance evidence

- **No rendered env can override the literal via relaxed binding** (local `kubectl kustomize k8s/staging` render
  only, no cluster): render rc=0, 177936 bytes, `SMTP_STARTTLS` lines 1 (positive control: the search sees env
  names), `SPRING_MAIL` lines 0 → rc=0. Fail direction (the same statement with `SMTP_STARTTLS` in the negated
  test) → rc=1. Residual: an env named `SPRING_MAIL_*` added later would override the literal; it would show in the
  render and the golden diff. The staging golden feeds `SMTP_STARTTLS` from `app-config` `smtp.starttls`, which the
  literal now ignores under prod/staging.
- **Doc metrics:** `scripts/docs-freshness.sh` failed before regeneration (java_test_methods 2918 vs computed
  2924, total 6332 vs 6338, rc=1). Regenerated with the repo's own generator `scripts/docs-freshness.sh --write`;
  `scripts/check-doc-metrics.sh` then failed on 7 prose claims (rc=1) until the counts in CLAUDE.md, AGENTS.md and
  README.md were brought to the manifest (no prose generator exists; exact-token sed `6332→6338`, `2918 →2924 `).
  After: check-doc-metrics PASS 37/37 rc=0, docs-freshness OK rc=0. **The orchestrator will regenerate again after
  merging the parallel lanes** rather than hand-merge these numbers.
- **Neighbouring profile readers still green:** unit `RenamedConfigKeysBindingTest` 8/8,
  `ActiveProfileValidatorTest` 6/6, `StagingRedisPasswordFailClosedTest` 4/4, `UnsubscribeLinkRoutingTest` 3/3,
  `TenantIsolationProfileGatingTest` 2/2; integrationTest (Testcontainers, full prod/staging context boot)
  `StagingActuatorPortIsolationTest` 4/4, `SecurityHeadersProdProfileTest` 2/2, `SecurityHeadersDevProfileTest`
  2/2, `OpenApiProdProfileGatingTest` 3/3 — all failures=0 errors=0. `k8s/scripts/check-connection-math.sh` rc=0,
  `check-consumer-thread-budget.sh` rc=0.

## Ordering consequence — read before treating a staging mail failure as a regression

**Staging app mail FAILS between this plan and plan 29-22, by design.** This plan makes STARTTLS required on the
client (D-20); plan 29-22 (wave 8) deploys Mailpit with its cert-manager certificate and
`MP_SMTP_REQUIRE_STARTTLS`. From the first staging deploy of a core-java image built from `ed07f053` (or later)
until 29-22's Mailpit TLS is live, every core-java mail send fails: the SMTP server at
`mailpit.jtoye-staging.svc.cluster.local:1025` is either not deployed (unknown host / connection refused) or offers
no STARTTLS (JavaMail `MessagingException` "STARTTLS is required but host does not support STARTTLS"). The overall
`/actuator/health` on :9091 may show the mail contributor DOWN for the same reason; readiness
(`/actuator/health/readiness`, the k8s probe) is unaffected, so pods stay Ready, and the deploy job's overall-health
exec check does not exist until 29-27. Executors in the window (29-15, 29-17, 29-19, 29-21, and 29-22 before its TLS
step) must record such a failure as this ordering, NOT a regression, and must never relax the literal, set
`SMTP_STARTTLS`, or add `mail.smtp.ssl.trust` to make mail work (D-20, D-21). The live two-arm proof (D-22) is 29-22's.

## Task Commits

1. **RED** — `23ae71bc` `test(29-16): add failing D-20 profile-layered STARTTLS cases`
2. **GREEN** — `ed07f053` `feat(29-16): require STARTTLS as a literal under the prod and staging profiles (D-20)`

No REFACTOR: the second runner is small and shares the YAML loader.

## TDD Gate Compliance

`test(29-16)` (`23ae71bc`) precedes `feat(29-16)` (`ed07f053`); RED evidence classified `RED_EVIDENCE_OK`. Compliant.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] k8s/LOCAL.md:370 line citation shifted by the new mail block**
- **Found during:** Task 1 (GREEN)
- **Issue:** inserting 17 lines into application-prod.yml moved `logging.file.name` from line 100 to 117;
  `scripts/check-doc-citations.sh` (run under the jtoye-ops env PATH) failed `C-3 k8s/LOCAL.md:370 cites
  application-prod.yml:100, but that line says nothing the claim names`, violations 1, rc=1.
- **Fix:** repointed the citation to `:117`. After: 46 verified, 0 violations, rc=0.
- **Files modified:** k8s/LOCAL.md (not in the plan's files list)
- **Commit:** `ed07f053`

**2. [Minor - test construction] profile source ordering**
- The plan's interface note says add the profile sources "FIRST (addFirst)". Implemented as addLast(profile) then
  addLast(application.yml) after removing OS env/JVM props: the profile file still sits ABOVE application.yml (the
  plan's stated intent), and stays BELOW the `withPropertyValues` source, matching Boot, where an environment
  variable outranks a profile file. With addFirst the profile file would have outranked the simulated env, which
  would make the `SMTP_STARTTLS=false` cases pass for the wrong structural reason if anyone later put the knob in
  the profile file. The break arm shows those cases do fail when the profile reads the knob.

## Deferred / out of scope (recorded, not fixed)

Pre-existing stale line citations into application-prod.yml / application-staging.yml that no gate checks were
already wrong at the base commit (e.g. `application-prod.yml:91` and `:106-107` in k8s/base/core-java-deployment.yaml,
k8s/local/configmap-patch.yaml, .env.example, infra/monitoring/prometheus/*; `application-staging.yml:81`; several
docs/audit files). At base, line 91 was `pattern:`, 100 was the log path, 106-107 were `max-file-size`/`max-history`.
This plan's insertion shifts them a further 17 (prod) / 16 (staging) lines. Not logged to deferred-items.md to avoid a
merge conflict between parallel lanes; the orchestrator may transfer it.

## Known Stubs

None.

## Threat Flags

None. T-29-61 (STARTTLS downgrade via missing config) mitigated as planned: literal `required: true` in both deployed
profiles, test-proven against the env knob; live break arm is plan 29-22's.

## Next Phase Readiness

- 29-22 can deploy Mailpit with required STARTTLS; core-java built from `ed07f053`+ will refuse a plaintext server.
- Mail on staging is expected to fail until 29-22's TLS step (see Ordering consequence).

## Self-Check: PASSED

- FOUND: core-java/src/main/resources/application-prod.yml (`required: true`)
- FOUND: core-java/src/main/resources/application-staging.yml (`required: true`)
- FOUND: core-java/src/test/java/uk/jtoye/core/notification/MailStartTlsPropertyTest.java (`mail.smtp.starttls.required`)
- FOUND: .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-16-red-evidence.json
- FOUND: 23ae71bc and ed07f053 are ancestors of HEAD
