---
phase: 38-spring-boot-4-1-migration
plan: 17
subsystem: infra
tags: [spring-boot-4, docker-compose, runtime-parity, keycloak, tracing, prometheus, redis-cache, openapi]
status: complete

requires:
  - phase: 38-06
    provides: D-04 plain Bearer challenge and the D-05 /.well-known suppression filter (anon-401-parity decision)
  - phase: 38-07
    provides: KeycloakAdminClient on Jackson 3 with the by-content disable PUT
  - phase: 38-09
    provides: v4: cache key format and the jtoye.cache.errors metric
  - phase: 38-11
    provides: renamed config keys (spring.web.error, management.tracing.export.zipkin, rollingpolicy)
  - phase: 38-14
    provides: OpenAPI snapshot regenerated on springdoc 3.1.1
  - phase: 38-16
    provides: docs and version gates on the Boot-4 tree
provides:
  - The shared compose stack (project jtoye_oaas_2026) running the Boot-4 branch, proven by content
  - Branch merged with origin/main (0 behind) before the rebuild
  - Live proof of the Keycloak offboard, the 401/404 behaviour, tracing, metrics and the v4 cache
  - A 47-gate runtime sweep with every non-zero explained
affects: [38-18]

actuals:
  tokens: 21086      # chars/4: evidence file 15665 + merge-resolution diff of the 4 conflicted files 5421
  tasks: 3           # Task 1 resolved by the owner before dispatch; Tasks 2 and 3 executed here
  commits: 7         # git rev-list --count ff3b1e59..dc899f0f: 3 authored here + 4 main commits brought in by the merge
plan_head_before: ff3b1e59d1949e94852a6e783b3b7d0f68565ceb
plan_head_after: dc899f0f277f14d4af90bb0cddf46ef331f80b44

tech-stack:
  added: []
  patterns:
    - "Drive the shared compose project from a worktree with COMPOSE_PROJECT_NAME + COMPOSE_ENV_FILES, proven first by a row count and by equal compose config-hashes"
    - "Rebuild only the built services with --no-deps --force-recreate when the worktree's bind-mount paths differ from the main checkout's"
    - "Create Keycloak users with a tenant_id through partialImport; the admin-API create strips the unmanaged attribute on KC 24"

key-files:
  created:
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-17-runtime.txt
  modified:
    - CLAUDE.md
    - AGENTS.md
    - docs/architecture/ESSENTIAL_ARCHITECTURE.md
    - .planning/ROADMAP.md

key-decisions:
  - "38-17: the owner chose takeover-from-worktree and Stay on Boot-4; the stack stays on the branch's images and main is not rebuilt"
  - "38-17: /.well-known is verified per the 38-06 ruling (anonymous 401 plain Bearer, credentialed 404, never 200); the plan's anonymous-404 verify is superseded"
  - "38-17: origin/main merged here (25566e2c), as the plan says when behind; conflicts kept the Boot-4 claims and took main's Next.js 16.3.7"
  - "38-17: rebuild scoped to the four built services with --no-deps --force-recreate; keycloak, postgres and rabbitmq keep their main-checkout bind mounts"
  - "38-17: throwaway Keycloak users created by partialImport, because the KC24 admin-API create strips the unmanaged tenant_id; no realm configuration changed"
  - "38-17: jtoye_cache_errors_total is register-on-demand, so 0 errors means the series is absent; a corrupted v4 entry proved it can reach 1"

patterns-established:
  - "Runtime proof from a worktree: positive control by rows and config-hash, never by rc"
  - "Keycloak read-back of tenant_id through the list endpoint (briefRepresentation=false); the single-user GET hides unmanaged attributes"

requirements-completed: [BOOT4-14, BOOT4-05, BOOT4-09, BOOT4-07]  # plan's list, verbatim; the shared-ID gate (requirements.ready-ids) decides which are marked now

coverage:
  - id: D1
    description: "The running stack is the Boot-4 branch by content: all four built services rebuilt, freshness PASS 4/0 unverified with a stopped-service VOID arm, 0 behind origin/main, /app/app.jar lists spring-boot-4.1.1.jar and no 3.5 or jackson2 module, every application*.yml sha256-equal to the branch"
    requirement: BOOT4-14
    verification:
      - kind: other
        ref: "COMPOSE_PROJECT_NAME=jtoye_oaas_2026 COMPOSE_ENV_FILES=<main .env> bash scripts/check-runtime-freshness.sh (rc 0; arm rc 2; before rc 1) — evidence §1.5, §3"
        status: pass
      - kind: other
        ref: "bash scripts/check-branch-behind-base.sh (rc 0 after merge; rc 1 before) — evidence §2"
        status: pass
      - kind: other
        ref: "docker exec <core-java> unzip -l / unzip -p /app/app.jar — evidence §4"
        status: pass
    human_judgment: false
  - id: D2
    description: "Live D-04/D-05 on the rebuilt runtime: anonymous 401 with WWW-Authenticate exactly Bearer, credentialed /.well-known 404 not-found, garbage token without resource_metadata; doctored inputs fail the same checks"
    requirement: BOOT4-09
    verification:
      - kind: other
        ref: "curl -si probes + chk_anon/chk_404/chk_garbage — evidence §5"
        status: pass
    human_judgment: false
  - id: D3
    description: "Live Keycloak offboard: the throwaway tenant's user reads enabled:false with id, username and tenant_id intact and no Jackson-2 node keys; the control user stays enabled; keycloak_deprovisioned_at stamped; KC_ADMIN_ENABLED restored to false"
    requirement: BOOT4-05
    verification:
      - kind: other
        ref: "Keycloak admin API read-back + jq assertion (true; 3 arms false) — evidence §6.5-6.13"
        status: pass
    human_judgment: false
  - id: D4
    description: "Tracing, metrics and the versioned cache are live: request log lines carry a 32-hex traceId, /actuator/prometheus serves the http_server_requests histogram, v4:products and v4:shops keys appear, jtoye.cache.errors stays 0; NoOrdersCreated cleared and check-alert-metrics PASS"
    requirement: BOOT4-07
    verification:
      - kind: other
        ref: "evidence §6.11 (arm), §7.1-7.8, §8.3; scripts/check-alert-metrics.sh rc 0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Runtime gate sweep: 47 gates, 39 rc 0; 6 environment VOIDs carried unchanged from 38-16; 2 worktree VOIDs re-run with the main .env (infra-exposure PASS; container-config-drift full-stack 11 MATCH / 0 drift, monitoring part unaddressable from a worktree)"
    requirement: BOOT4-14
    verification:
      - kind: other
        ref: "HANDOFF gate loop — evidence §8"
        status: pass
    human_judgment: true
    rationale: "Eight non-zero gates are explained as environment or worktree VOIDs rather than passed; a verifier should accept or reject those explanations"

duration: 21min
completed: 2026-10-05
---

# Phase 38 Plan 17: Runtime Parity on the Rebuilt Stack Summary

**The shared compose stack now runs the Boot-4 branch, proven by content. The jar holds Spring Boot 4.1.1 with byte-identical config, and live probes cover the 401 and /.well-known answers, a Keycloak offboard, tracing, metrics and the v4: cache.**

## Performance

- **Duration:** 21 min
- **Started:** 2026-10-05T16:58:25Z
- **Completed:** 2026-10-05T17:19:30Z
- **Tasks:** 3 (Task 1 was resolved by the owner before dispatch; Tasks 2 and 3 ran here)
- **Files modified:** 5 (1 evidence file created, 4 merge conflicts resolved)

## Accomplishments

- **Positive control first.** From the worktree, `COMPOSE_PROJECT_NAME=jtoye_oaas_2026` with `COMPOSE_ENV_FILES=<main .env>` listed all 11 running containers. Without the project name, the same command listed none and still exited 0. For the four built services, compose's config-hash from the worktree matched the label on the running containers.
- **BEFORE, recorded on Boot 3.5.16.** The jar was `spring-boot-3.5.16.jar`, and its application.yml equalled the pre-phase file. An anonymous request to `/.well-known` got 401 `Bearer`; one with a token got 404. The freshness gate showed DRIFT (rc 1).
- **Merged origin/main.** The branch was 4 commits behind. The merge is `25566e2c`, and the behind-base gate now reads 0. Every doc gate is green after the merge, and blobctl builds and tests clean.
- **Rebuilt and recreated all four built services.** core-java is on :9090 and healthy. The freshness gate passed 4/0. Stopping edge-go turned it VOID (rc 2), and it passed again after restart.
- **Read the running jar.** It lists `spring-boot-4.1.1.jar`, has no 3.5 jar and no `spring-boot-jackson2-*` (the Jackson-3 module `spring-boot-jackson-4.1.1.jar` is present). It carries Jackson 3.1.7 and 2.22.3, Framework 7.0.9 and Security 7.1.1. All six `application*.yml` are sha256-equal to the branch, and the renamed keys are present: `management.tracing.export.zipkin` and `spring.web.error` (the pre-phase file is the arm).
- **AFTER probes, per the owner's 38-06 ruling.** An anonymous request gets 401 with the exact `Bearer` challenge and the 401 document. A request with credentials gets 404 not-found. A garbage token gets `invalid_token` with no `resource_metadata`. Each check fails on a doctored input. `check-openapi-snapshot-fresh` passes on the rebuilt runtime, and the pre-phase snapshot arm gives rc 1.
- **Live Keycloak offboard.** Offboarding the throwaway tenant disabled its user within 1 s. The read-back shows `enabled:false` with id, username and tenant_id intact and none of the garbage keys `nodeType`, `array`, `bigDecimal` or `containerNode`. The control user and the four realm users stay enabled. `keycloak_deprovisioned_at` is stamped.
- **Proof setting restored.** `KC_ADMIN_ENABLED` is back to `false`, read inside the container. The deprovision endpoint now answers 400 "not configured".
- **Tracing and metrics.** Order-request log lines carry a 32-hex traceId. Prometheus serves 148 bucket lines.
- **Versioned cache.** `jtoye_cache_errors_total` stays at 0, read before, after and at close. `v4:products` and `v4:shops` keys appear, with no un-prefixed region keys. A corrupted v4 entry pushed the counter to 1 on the proof container, so the counter is able to go non-zero.
- **NoOrdersCreated cleared.** Two orders were placed, and `check-alert-metrics` passes.
- **Gate sweep: 47 gates, 39 rc 0.** Every non-zero gate is explained in evidence §8.1.

## Task Commits

1. **Task 1: owner coordination decision.** Resolved before dispatch; it is recorded verbatim in evidence §0 and has no separate commit.
2. **Task 2: BEFORE probes, merge, rebuild, parity, jar read-back, AFTER probes.** Commits `25566e2c` (merge from origin/main) and `c97ed536` (docs).
3. **Task 3: live Keycloak offboard, tracing, metrics and cache, gate sweep.** Commit `dc899f0f` (docs).

**Plan metadata:** see the docs(38-17) completion commits.

## Files Created/Modified

- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-17-runtime.txt`: the decision, before and after probes, positive control, merge, rebuild log, freshness with arms, jar read-back, Keycloak read-back, tracing, metrics and cache, and the gate sweep.
- `CLAUDE.md`, `AGENTS.md`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md`: merge resolution. They keep the Boot 4.1.1, Jackson 3 and tracing claims and take main's Next.js 16.3.7.
- `.planning/ROADMAP.md`: merge resolution. It keeps the branch's Phase 38 list and main's Phase 39 section.
- Runtime state (not tracked): rebuilt images for core-java, frontend, edge-go and mcp-server; throwaway tenant `eda9d895-c55c-4f71-8eb0-35591fad1acb` (OFFBOARDED; it stays by design); two seed orders `ORD-00000000-20261005-F939BCB6` and `ORD-00000000-20261005-0336A661`.

## Decisions Made

See `key-decisions` in the frontmatter. The owner's exact words are "takeover-from-worktree (Recommended)" and "Stay on Boot-4". The Boot-4 stack stays up, and main is not rebuilt.

## Deviations from Plan

### Owner-decision deviations

**1. `/.well-known/oauth-protected-resource` anonymous answer is 401, not 404 (owner decision 38-06)**
- **Found during:** Task 2.
- **Issue:** The plan's must-have and its automated verify expect 404 for an anonymous `curl`. Both predate the "anon-401-parity (Recommended)" ruling.
- **Fix:** Verified 401 with plain `Bearer` and the 401 document for an anonymous caller, 404 for a caller with credentials, and never 200. The plan's command is recorded as superseded (evidence §5).

### Auto-fixed Issues

**2. [Rule 3 - Blocking] Rebuild scoped to the built services with `--no-deps --force-recreate`**
- **Found during:** Task 2, positive control.
- **Issue:** The plan says `up -d --build`. From the worktree, the config-hashes for keycloak, postgres and rabbitmq differ from the running containers, because their bind mounts would move into the worktree. `infra/keycloak/realm-export.json` is untracked and exists only in the main checkout. A plain `up` would have recreated those services against the wrong mounts.
- **Fix:** `up -d --build --no-deps --force-recreate core-java frontend edge-go mcp-server`. Every built service is still rebuilt and recreated. `--force-recreate` follows sync-runtime.sh's measured cached-build trap.
- **Verification:** The non-built services still read "Up 2 days". Freshness passes 4/0.

**3. [Rule 3 - Blocking] KC24 user profile stripped `tenant_id` on the admin-API user create**
- **Found during:** Task 3.
- **Issue:** `POST /users` with `attributes.tenant_id` stored no attribute, and the service's `q=tenant_id:` search returned `[]`. The offboard would have found no user, a vacuous proof. docs/security-scopes.md §5 records this trap.
- **Fix:** Deleted both users (re-read 404) and re-created them through `partialImport`, the import path the realm users came from. No realm configuration was changed. The read-back uses the list endpoint, because the single-user GET hides unmanaged attributes.

**4. [Rule 1 - Bug, own check] The awk counter for `spring.web.error` gave a false 0**
- **Found during:** Task 2, jar read-back.
- **Issue:** The counter looked for a nested `spring:` / `web:` / `error:` block, but the file writes `spring.web.error:` dotted at top level.
- **Fix:** A corrected grep counts 1 on the rebuilt file and 0 on the pre-phase file (the arm). Both counts are recorded in evidence §4.

**5. [Rule 2 - Strengthening] The plan's "jtoye_cache_errors_total is present and 0" cannot be satisfied as written**
- **Issue:** The counter registers on demand, so at 0 errors the series is absent.
- **Fix:** Recorded the series as absent (sum 0) before, after and at close. Added an arm on the proof container: a corrupted v4 entry, read through the API, returned 200 and moved the counter to `{cache="products",operation="get"} 1.0`. The key was then deleted.

**6. [Rule 3 - Blocking] The traceId check moved to the order request**
- **Issue:** The plan's cached GETs, and the 404 and 400 calls, write no log line on this service.
- **Fix:** Used the order request from seed-order-metric. Its three request-scoped lines carry trace `6ac3da94…` (the regex arm on an empty-trace line gives 0).
- **Second order:** The first order created the counter series in a fresh process, so `increase()` had no rise. A second order cleared NoOrdersCreated.

**7. Smaller plan-text adjustments**
- The throwaway tenant was created through `POST /api/v1/admin/tenants`, not the dev path, because `/dev/tenants/ensure` only ensures the caller's own tenant.
- The "management port" is 9090: no `management.server.port` is set.
- `check-infra-exposure` and `check-container-config-drift` VOID from a worktree because they read `REPO_ROOT/.env`. Both were re-run with the main .env, read-only. Infra-exposure passed. Config-drift graded the full-stack file at 11 MATCH and 0 drift, but cannot address the monitoring project from here; this plan never touched that project.

---

**Total deviations:** 7: 1 owner-decision supersession, 3 blocking (2, 3, 6), 1 own-check bug (4), 1 strengthening (5), and 1 group of small plan-text adjustments (7).
**Impact on plan:** No code changed; the only tracked changes are the merge resolution and the evidence file. Every must-have is met, with the /.well-known truth as the owner ruled it.

## Issues Encountered

Observations recorded here and not filed (none breaks a gate, loses data or is an observed failure):
- On Boot 3.5, the credentialed `/.well-known` 404 carried `X-RateLimit-*` headers. On Boot 4 the suppression filter answers before the rate limiter, so the headers are gone; status and body are as documented.
- On the 201 response, `POST /api/v1/admin/tenants` returns `createdAt: null`; a GET returns it.
- RabbitMQ listener log lines show `[core-java,,]`. Neither the pre-phase tree nor the branch sets AMQP observation, so this is not a phase change. It was not re-measured on the replaced 3.5 container.
- On the public storefront path, Spring Data logs its "Serializing PageImpl instances as-is" WARN, through `SpringDataJackson3Configuration`.
- My own blobctl `go build ./...` left an untracked binary, which I deleted. curl's CRLF header lines were normalised to LF in the evidence file.

## Known Stubs

None.

## Threat Flags

None beyond the plan's threat model:
- **T-38-40** is mitigated by the by-content read-back with a control user, and garbage-key absence is asserted.
- **T-38-41** is mitigated by the owner decision, the positive control and the VOID arm.
- **T-38-42** is mitigated: the restore is shown by printenv (false) and functionally by the deprovision endpoint's 400. All four throwaway users are deleted (404).
- **T-38-SC:** no package was installed. The frontend `npm ci` ran inside its image build from the committed lockfile.

## User Setup Required

None.

## Next Phase Readiness

- **38-18:**
  - The branch is merged with origin/main at `25566e2c` (0 behind as of the 17:01 UTC fetch). Re-check before push.
  - The PR body should name the contract notes from ADR-0006 and cite this evidence for the runtime half of BOOT4-14 (freshness, jar read-back) and the live BOOT4-05/09/07 proofs.
  - The compose stack is on the branch (owner: Stay on Boot-4). The main checkout's own freshness gate will report DRIFT until main is rebuilt after merge.
- **Unverified, carried:** the nightly E2E on the branch's runtime (38-18), and Temurin CI runs of both suites (38-18).

## Self-Check: PASSED

- FOUND: .planning/phases/38-spring-boot-4-1-migration/evidence/38-17-runtime.txt
- FOUND: 25566e2c, c97ed536, dc899f0f (git log). Control: an absent hash reports MISSING.
- Plan verification re-run at close (evidence §8.3):
  - health UP;
  - `docker compose -p jtoye_oaas_2026 ps -q core-java` cid_rc=0;
  - printenv `KC_ADMIN_ENABLED` is `false`;
  - port 9090;
  - freshness PASS rc=0.
- commits: 7, measured as `git rev-list --count ff3b1e59..dc899f0f`.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*
