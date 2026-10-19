---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 26
subsystem: infra
tags: [k8s, kustomize, keycloak, customer-realm, rfc-8058, list-unsubscribe, render-invariants, env-contract, staging, live, tdd, "592", "299", "859"]
status: complete

requires:
  - phase: 29-22
    provides: "Mailpit with STARTTLS required, read through its in-cluster API (port-forward svc/mailpit 8025)"
  - phase: 29-18
    provides: "the TEST-paid seed order ORD-1AAC0CDE-20261019-4D3B23A2 (PENDING / CAPTURED) moved here to CONFIRMED"
  - phase: 29-29
    provides: "staging at 6e95ee6c, the image tag this deploy kept (no image-baked change in this plan)"
  - phase: 29-14
    provides: "scripts/deploy.sh --context/--image-tag/DEPLOYED_SHA, check-deploy-digest-parity.sh, staging-login.spec.ts"
  - phase: 29-04
    provides: "the jtoye-customers staging realm (storefront-client, verifyEmail true, Mailpit SMTP)"
provides:
  - "core-java env NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL from app-config notification.unsubscribe.one-click-base-url = each overlay's api.url (#592)"
  - "The customer realm in every k8s environment: core-java CUSTOMER_KC_ISSUER_URI / CUSTOMER_JWT_EXPECTED_ISSUER, frontend CUSTOMER_KEYCLOAK_ISSUER / _INTERNAL / _CLIENT_ID, from app-config keycloak.customer.{issuer.uri,public.issuer.uri,client-id} (#299)"
  - "The five OPEN DEFECT #299 allowlist rows removed from check-env-contract.sh"
  - "INV-17 in check-render-invariants.sh plus --selftest-inv17 (6 arms), wired into the k8s-validate CI job (#859)"
  - "UnsubscribeLinkRoutingTest #592 case"
  - "staging-login.spec.ts second test: the storefront customer sign-in reaches the jtoye-customers realm"
  - "Staging redeployed at 6e95ee6c with the new manifests; seed order now CONFIRMED"
affects: [29-23, 29-24, 29-25, 29-27, 29-28, 32]

actuals:
  tokens: 26721
  tasks: 3
  commits: 5
plan_head_before: fea03fd4e10eee7ea33085ac8d080c457c983807
plan_head_after: 473e4d606472dda16fb803afea8201cc668aabdf

tech-stack:
  added: []
  patterns:
    - "A config-only fix to a live environment is proven by a functional before/after on the live stack, with only the deploy changed between the two runs (same image tag, same probe, same customer)"
    - "A render invariant that reads a Spring property follows relaxed binding: any env whose lower-cased alphanumerics end in the property name binds it, and a configMapKeyRef is judged by its RENDERED value"
    - "A one-click unsubscribe target is probed live with an invalid token, so the probe proves routing without unsubscribing anyone"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-26-config.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-26-t1-red-one-click.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-26-t2-red-inv17.json
  modified:
    - core-java/src/test/java/uk/jtoye/core/notification/dispatch/UnsubscribeLinkRoutingTest.java
    - k8s/base/core-java-deployment.yaml
    - k8s/base/frontend-deployment.yaml
    - k8s/base/configmap.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/scripts/check-env-contract.sh
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - .github/workflows/ci-cd.yaml
    - frontend/e2e/staging-login.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md

key-decisions:
  - "The deploy kept image tag 6e95ee6c: the only app-path change since that tag is a core-java TEST file, which is not in the image, and the frontend's NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL was already baked for staging (5 .next files name the staging customer realm, 0 the production one)"
  - "Repo variable FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL was READ, not set: it already equals https://auth-staging.olajay.co.uk/realms/jtoye-customers, and the dispatch forbids changing repo vars"
  - "INV-17 is stronger than the plan's wording: it also refuses relaxed-binding spellings of the property, envFrom on a core-java container, and an active profile (dev) whose yml defaults the property off when no env overrides it"
  - "#299's runtime proof is a live functional before/after (customer order history 401 -> 200), because the customer REDIRECT is client-side and baked at build time, so it passed before the deploy and cannot fail on it"
  - "The #592 after-state is read from the vendor dispatch mail: a seed-order state change sends the CUSTOMER only the legacy EmailNotificationService mail, which never carries List-Unsubscribe"

patterns-established:
  - "Pre-apply kubectl diff of the deploy render, with every changed line listed, is the gate for a same-tag config deploy; its fail direction is the same render at another tag"

requirements-completed: [DPLY-02]

coverage:
  - id: D1
    description: "Every deployed core-java render sets NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL from app-config, equal to that overlay's api.url (#592)"
    requirement: DPLY-02
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/notification/dispatch/UnsubscribeLinkRoutingTest.java#592 (RED e5b03e30 tests=4 failures=1 RED_EVIDENCE_OK; GREEN tests=4 failures=0; break arms staging-key-on-app-origin and optional:true each red)"
        status: pass
      - kind: other
        ref: "kubectl kustomize k8s/{staging,production,local}: env count 0 before, 1 after, per overlay (evidence/29-26-config.txt)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The customer-storefront realm is configured in every k8s environment as one set; the five OPEN DEFECT #299 rows are gone (#299)"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "check-env-contract.sh rc 0; wiring with rows kept -> 5 STALE rc 1; scratch copy with the 5 envs removed -> rc 1 naming all five; OPEN DEFECT #299 grep: 5 rows at e5b03e30^, 0 now"
        status: pass
    human_judgment: false
  - id: D3
    description: "INV-17 refuses any deployed render that turns require-verified-email off, and application.yml's default must read true (#859)"
    requirement: DPLY-02
    verification:
      - kind: unit
        ref: "check-render-invariants.sh --selftest-inv17: RED 8f1ea2ff 0/6 caught (RED_EVIDENCE_OK), GREEN 6/6 + clean rc 0; control with an explicit true env rc 0; selftest-inv7/15/16/18 still pass"
        status: pass
    human_judgment: false
  - id: D4
    description: "Live: staging runs the new manifests at 6e95ee6c; the running pods carry the six envs with the staging values"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "deploy.sh rc 0 DEPLOYED_SHA=6e95ee6c...; pre-apply diff only the 4 keys and 6 envs (fail direction: another tag -> 8 image lines); post-apply diff rc 0; env read back from core-java-869648bdf-zrkcb and frontend-654b56c684-lb79f; digest parity rc 0, --plant-stale rc 1"
        status: pass
    human_judgment: false
  - id: D5
    description: "Live: the storefront customer sign-in reaches auth-staging/realms/jtoye-customers with storefront-client and the staging redirect_uri; the login page renders with the jtoye theme"
    requirement: DPLY-02
    verification:
      - kind: e2e
        ref: "frontend/e2e/staging-login.spec.ts (STAGING_E2E=1, desktop): 2 passed rc 0 after the deploy; realm and client break arms each 1 failed"
        status: pass
    human_judgment: false
  - id: D6
    description: "Live #299 functional: an email-verified storefront customer can read their order history"
    requirement: DPLY-02
    verification:
      - kind: e2e
        ref: "scratch probe (not committed): GET /api/customer-orders as customer-299-probe.staging@olajay.co.uk -> 401 'Customer authentication required' before the deploy, 200 after"
        status: pass
    human_judgment: false
  - id: D7
    description: "Live #592: staging notification mail carries List-Unsubscribe on api-staging plus List-Unsubscribe-Post"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "Mailpit 3jLROdjRSusLEXqD8JEN0c (vendor, after CONFIRMED) List-Unsubscribe <https://api-staging.olajay.co.uk/api/v1/public/unsubscribe?...> + List-Unsubscribe-Post: List-Unsubscribe=One-Click; before: 3NxeR70CxLMw5ecNc88l6L app-origin page link, no Post; invalid-token POST to the target -> 200 {status: invalid}, app origin -> 404"
        status: pass
    human_judgment: true
    rationale: "The plan asked for a CUSTOMER mail carrying the header. A state change sends the customer only the legacy EmailNotificationService mail, which carries no List-Unsubscribe. The after-state was therefore read from the vendor dispatch mail, which is built by the same recipient-agnostic code. A customer-addressed after-state needs a new payment or refund event, and neither was in this plan's live authority. A human should confirm that this substitute is acceptable."

duration: 31min
completed: 2026-10-19
---

# Phase 29 Plan 26: One-Click Unsubscribe Origin and the Customer Realm, Wired and Live on Staging

**Every k8s render now gives core-java the RFC 8058 one-click origin, equal to that overlay's api.url (#592). The customer storefront realm is wired as one split-horizon set in every environment, and the five OPEN DEFECT #299 rows are gone from the env contract (#299). A new render invariant, INV-17, refuses any deployed render that switches off the customer email_verified gate (#859). Staging was redeployed at 6e95ee6c. A verified storefront customer's order history went from 401 before the deploy to 200 after. Staging notification mail now carries `List-Unsubscribe` on api-staging plus `List-Unsubscribe-Post`.**

## Performance

- **Duration:** about 31 min (14:32:47Z to 15:03Z)
- **Tasks:** 3 of 3
- **Commits:** 5, measured from the plan ledger (`fea03fd4..473e4d60`)
- **Files:** 20 changed (911 insertions, 27 deletions)
- **Live estate touched:** kube-context `jtoye-stg-aks` only, plus Key Vault reads through `az_read`.
  - One `deploy.sh` run at `6e95ee6c`.
  - One order transition (PENDING to CONFIRMED) as the vendor, through the order API.
  - One test customer registered through the storefront's own sign-up.
  - No az mutation. No repo variable change. No push.

## Accomplishments

- **#592 (Task 1, test-first).**
  - `UnsubscribeLinkRoutingTest` gained a #592 case. In every overlay, the core-java container must carry the env from app-config: not optional, not a literal. The key must be set explicitly and equal api.url.
  - Why the old tests missed it: the #516 cases passed through `apiOrigin()`'s fallback to api.url, so they stayed green while no deployed pod had the env.
  - Values: `https://api.olajay.co.uk` for base and production, `https://api-staging.olajay.co.uk` for staging, `http://api.jtoye.local` for local.
- **#299 (Task 2).** The full customer set is wired. In base it carries production's public values, the same in both halves. Staging splits the in-cluster Service from auth-staging, and local splits host.minikube.internal from localhost:8085. All five allowlist rows were removed in the same commit as the wiring.
- **INV-17 (Task 2, #859).**
  - `application.yml`'s default must read true. A missing line is a PARSE ERROR.
  - No container running the core-java image may set the property off by any of these routes:
    - an env literal;
    - an app-config value (the rendered value is read);
    - a relaxed-binding spelling;
    - envFrom;
    - an active profile whose yml defaults it off.
  - The selftest arms run in CI.
- **Live (Task 3).**
  - Deployed. The pre-apply diff showed only the 4 keys and 6 envs; the post-apply diff is empty.
  - The env was read back from the running pods, and digest parity passes in both directions.
  - The spec passes 2 of 2. The customer functional proof went 401 to 200. The one-click headers were read from Mailpit.

## Task Commits

1. **Task 1 RED:** `e5b03e30` (test). The #592 case, failing on base: env absent.
2. **Task 1 GREEN:** `6dd2e65d` (feat). The env, four keys, goldens, metrics.
3. **Task 2 RED:** `8f1ea2ff` (test). `--selftest-inv17` arms, 0 of 6 caught.
4. **Task 2 GREEN:** `901f7fdd` (feat). The #299 set, rows removed, INV-17, CI step, goldens.
5. **Task 3:** `473e4d60` (feat). The customer spec test, metrics, the live evidence.

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| #592 routing case | e5b03e30: tests=4, failures=1. The #592 case failed on its first assertion, "base: the core-java container has no env NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL". The three #516 cases passed. | 6dd2e65d: tests=4, failures=0 | RED_EVIDENCE_OK (`29-26-t1-red-one-click.json`). Target named by the report's display name. |
| INV-17 | 8f1ea2ff: TAP 1..7, arms 1-6 not ok. Each planted copy's gate exited 0. Clean ok. | 901f7fdd: 6/6 caught, CLEAN rc=0 | RED_EVIDENCE_OK (`29-26-t2-red-inv17.json`) |

Semantic assessment: both REDs executed their target and failed on the planned assertion because the feature was absent. There was no compile, load or fixture fault.

The first classifier run on the #592 record returned INVALID_RED (`no_target_test_failure`). The cause was that the record named the test by its method name while the JUnit report uses the @DisplayName. After renaming the target the classifier returned RED_EVIDENCE_OK. No REFACTOR commits.

## Both Directions, Recorded (evidence/29-26-config.txt)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| T1 verify (gradle, build-local report newer than the stamp) | RED rc=1, failures=1. Arm with the staging key on the app origin: failures=2. Arm with optional: true: failures=1. | rc=0, n592=1, failures/errors/skipped 0 |
| T1 render count per overlay | 0 in staging, production and local (rg ONE_CLICK k8s: rc 1) | 1 in each |
| T1 goldens | render-golden.sh rc=1 before --write | rc 0 (+6 lines per golden) |
| T2 env contract | wiring with the rows kept: 5 STALE, rc 1. Scratch copy of 901f7fdd with the 5 envs removed: rc 1, naming all five. | rc 0 (the unmodified copy: rc 0) |
| T2 OPEN DEFECT grep | e5b03e30^: rc=0, rows 163, 164, 230, 231, 232 | now: grep rc 1, no lines |
| T2 INV-17 | RED 0/6 caught. GREEN arms: literal, configmap, relaxed spelling, dev profile, default false (all rc 1) and line missing (rc 2) | 6/6 caught. Explicit true env control: rc 0. |
| T2 repo variable read-back | an unset name: rc 1 "not found" | rc 0, value is the staging realm |
| T3 customer spec | realm arm and client arm each 1 failed (Expected/Received recorded) | 2 passed, before and after the deploy |
| T3 pre-apply diff gate | the same render at efab17d7: 8 image lines | only the 4 keys and 6 envs. Post-apply: rc 0. |
| T3 digest parity | --plant-stale rc 1 | rc 0 |
| T3 #299 functional | 401 "Customer authentication required" (before the deploy) | 200 (after) |
| T3 #592 headers | before: app-origin page link, no Post | after: api-staging one-click, Post present |
| T3 one-click target | the same path on the app origin: 404 (Next.js) | api-staging: 200 {"status":"invalid"} for an invalid token |
| T3 metrics / oracle | docs-freshness before --write: rc 1. Oracle with the manifest at 191: rc 1. | rc 0 / rc 0 (runner 192 = manifest 192) |
| Leak scan | a file holding one real secret: 1 found, rc 1 | evidence and spec log: 0 found. Probe password in evidence: 0 (control 1). Unredacted tokens: 0 (control 1). |

## Decisions Made

See `key-decisions` in the frontmatter. The owner may want to look at two of them:
- **Same-tag deploy.** No code baked into any image changed.
- **No repo-variable write.** The variable already held the staging realm.

## Deviations from Plan

### Plan adaptations

**1. [Plan assumption wrong] A state change does not send the customer a mail carrying List-Unsubscribe**
- **Found during:** Task 3.
- **Measured:** `RecipientResolver` routes order.state.* to the VENDOR only on the dispatch path. The customer gets the legacy `EmailNotificationService` mail, which sets no List-Unsubscribe. Both the "Received" mail (14:22) and the "Confirmed" mail (15:00) had none.
- **What was read instead:** the vendor dispatch mail from the same transition. It carries `<https://api-staging.olajay.co.uk/api/v1/public/unsubscribe?...>` plus `List-Unsubscribe-Post: List-Unsubscribe=One-Click`. Its before-state was the app-origin page link with no Post, as was the customer's own dispatch mail (Payment received, 14:22).
- **Why no customer-addressed after-state:** it needs a payment.* or order.refunded event, meaning a new TEST order or a refund. A new order would change the order count that 29-20 compares, and neither action is in this plan's live authority.
- **Coverage:** D7 is marked human_judgment.

**2. [Plan assumption wrong] The customer redirect cannot fail on the deploy**
- **Cause:** the authorize URL is built in the browser from the build-time NEXT_PUBLIC value, which was already in the image.
- **Result:** the new test passed before the deploy as well.
- **Stronger replacement, recorded beside the literal:**
  - planted spec arms, for the test's own fail direction;
  - the env read back from the running pods;
  - a functional before/after of the server-side half #299 actually fixes: core-java's customer token verification, 401 to 200.

**3. [Plan adaptation] Repo variable not set**
- The plan said to `gh variable set` it.
- Measured: it already holds the target value, and the dispatch forbids changing repo vars.
- Only the read-back verify was run, with its fail direction.

**4. [Rule 2 - Missing] `--selftest-inv17` wired into the k8s-validate CI job**
- Without it, the selftest would run nowhere (the 29-22 precedent).
- `selftest_stage` now also copies `application*.yml`, which INV-17 reads. The other four selftests still pass.

**5. [Rule 1 - Bug, my test] The customer test missed the login document**
- **Cause:** `waitForResponse` was registered after the click.
- **Fix:** listeners are registered before the click. The first run's failure is in the log, not in the commit.

**6. [Plan adaptation] Production configmap-patch keeps only the one-click key**
- The customer keys are inherited from base, which holds production's public values. This is the plan's file list for Task 2.

**Total deviations:** 2 wrong plan assumptions, each replaced by a stronger check. 2 plan adaptations. 1 missing item auto-added. 1 bug in my own test, fixed before commit.

**Impact:** none on scope. The one open question for a human is D7's customer-addressed header.

## Issues Encountered

- **I killed my own shell once.** While fetching a Mailpit link, I used `pgrep -f` with bracket self-exclusion to find the port-forward for killing. It matched the enclosing shell, as CLAUDE.md warns, and killed it (exit 144). The orphaned port-forward was found and killed by PID. Every other port-forward in this plan captured `$!` when it started.
- **The public api host returns 404 for /actuator.** That is by design. Readiness was read in-cluster instead: the pod is Ready, with 0 restarts, on `/actuator/health/readiness:9091`.

## Staging State Changed (for the orchestrator and persona testers)

- **Seed order** `ORD-1AAC0CDE-20261019-4D3B23A2` is now **CONFIRMED** (still CAPTURED).
- **New test customer** `customer-299-probe.staging@olajay.co.uk` in realm jtoye-customers, email verified.
  - Its password was generated, held in a mode-600 scratch file, and deleted after use.
  - Recover it with Forgot Password; the mail arrives in Mailpit.
  - An admin can delete the user.
- **Mailpit** gained the probe's three Keycloak mails plus the transition's two order mails.
- **Running pods:** core-java-869648bdf-zrkcb and frontend-654b56c684-lb79f, both at 6e95ee6c.

## Findings (recorded, not filed)

- **Customer registration has no password field.** The jtoye-customers sign-up asks only for email, first name and last name. Keycloak then requires email verification followed by UPDATE_PASSWORD.
  - It works, but a tester who closes the tab between those two steps is left with an account they cannot sign in to until they use Forgot Password. That is what happened to the probe the first time.
  - Persona testers should be told.
- **A stale comment.** `scripts/check-keycloak-logout-uri.sh` lines 26 and 30 still say "Open #299". It is only a comment, but it is now stale.
- **What closes at PR time:** #592, #299 and #859 are fixed in this phase. Closing them is for the PR.

## Threat Flags

None beyond the plan's threat model:
- **T-29-95:** INV-17 is in place, and the live realm has verifyEmail true (the probe was forced through verification).
- **T-29-96:** the split-horizon keys are live, and the authorize URL is recorded.
- **T-29-97:** accepted. The one-click probe used an invalid token and unsubscribed no one.

## User Setup Required

None.

## Next Phase Readiness

- **29-23 can start.** It depends on this plan: its baselines include these render changes, which are in both goldens.
- **Persona testing:** customers can now sign in and see their order history on staging. Undo with `git revert 901f7fdd 6dd2e65d`, then redeploy at the same tag.
- **No push.** The five commits are local on phase-29-deployable-staging.

## Self-Check: PASSED

- FOUND: evidence/29-26-config.txt, evidence/29-26-t1-red-one-click.json, evidence/29-26-t2-red-inv17.json, and every key-files.modified path
- FOUND, ancestors of HEAD: e5b03e30, 6dd2e65d, 8f1ea2ff, 901f7fdd, 473e4d60
- `git rev-list --count fea03fd4..473e4d60` = 5
- Final re-run on HEAD 473e4d60:
  - T1 verify: rc 0, tests=4, failures 0, n592=1.
  - Quick static set: all five rc 0.
  - `--selftest-inv17`: CLEAN rc=0.
  - Every overlay: one-click env count 1, customer envs 5.
  - OPEN DEFECT #299 rows: 0.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-19*
