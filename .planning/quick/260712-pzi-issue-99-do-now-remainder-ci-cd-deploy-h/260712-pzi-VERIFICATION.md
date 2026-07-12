---
phase: quick-260712-pzi
verified: 2026-07-12T20:30:00Z
status: human_needed
score: 11/11 must-haves verified (static/code/wiring level)
overrides_applied: 0
human_verification:
  - test: "Run `./gradlew :core-java:integrationTest --tests \"uk.jtoye.core.security.*\" --no-daemon` (or observe the PR's own CI run of the integration-tests job)"
    expected: "BUILD SUCCESSFUL; SecurityHeadersIntegrationTest 6/6 passing, including the two new tests livenessProbeIsPubliclyAccessible and readinessProbeIsPubliclyAccessible asserting unauthenticated GET /actuator/health/liveness and /actuator/health/readiness -> 200"
    why_human: "Verifier was explicitly instructed not to re-run the ~24.5min Testcontainers gradle integrationTest task. Code inspection confirms the test correctly sets management.endpoint.health.probes.enabled=true via @SpringBootTest properties and asserts status().isOk() on both exact probe paths SecurityConfig now permits, but only an actual run proves the Spring context + RLS Testcontainers Postgres wiring behaves as expected at runtime."
  - test: "Run `cd frontend && npm run build`"
    expected: "Next.js build compiles successfully (TypeScript strict-mode type-check clean) per project memory's frontend-typecheck-gate lesson"
    why_human: "Verifier was explicitly instructed not to re-run npm build. eslint . was independently re-run and confirmed 0 errors/exit 0, but the build's tsc type-check is a separate gate jest does not cover."
  - test: "Run `cd frontend && npm test -- --ci --watchAll=false`"
    expected: "32 suites / 226 tests passing (per SUMMARY.md claim), no new failures introduced by the 4 eslint-disable annotations or the Link/require() fixes"
    why_human: "Verifier was explicitly instructed not to re-run npm test."
---

# Quick Task: Issue #99 do-now remainder — CI/CD deploy honesty Verification Report

**Task Goal:** Make the CI/CD deploy half honest: real staging gate (main + vars.DEPLOY_STAGING_ENABLED), kustomize deploys with immutable full-sha tags (+ rendered-manifest assertion), prod-shape-safe smoke test (EXPECT_SWAGGER conditional + probe asserts), probe-401 SecurityConfig fix with regression test, unified ghcr.io/bralabee/jtoye-* image names, and a WORKING CI-enforced ESLint gate.

**Verified:** 2026-07-12T20:30:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | CI/CD pipeline no longer triggers on non-existent `develop` branch (push+PR) | VERIFIED | `.github/workflows/ci-cd.yaml:4-7` — `on.push.branches: [main, 'phase-*']`, `on.pull_request.branches: [main]`. `grep -n "develop" .github/workflows/ci-cd.yaml` returns zero matches anywhere in the file. |
| 2 | deploy-staging off by default, gated on `main && vars.DEPLOY_STAGING_ENABLED == 'true'` | VERIFIED | `ci-cd.yaml:461`: `if: github.ref == 'refs/heads/main' && vars.DEPLOY_STAGING_ENABLED == 'true'` — mirrors deploy-production's `vars.DEPLOY_ENABLED` gate at line 538. |
| 3 | Immutable full-sha image tag pushed every push/release, referenced by both deploy jobs | VERIFIED | `ci-cd.yaml:407`: `type=raw,value=${{ github.sha }}` in `docker/metadata-action` tags block; referenced at `:495-497` (staging) and `:572-574` (production) `kustomize edit set image ...:${{ github.sha }}`. |
| 4 | Both deploy jobs use kustomize edit set image + kubectl apply -k (kubectl set image removed); staging keeps rollout/smoke/rollback only, production additionally keeps health-check+Slack | VERIFIED | `grep -n "kubectl set image" ci-cd.yaml` = 0 matches. Staging job (`:453-531`) has checkout/kubectl-setup/kustomize-install/kubeconfig/apply/rollout-wait/smoke-test/rollback — no health-check or Slack. Production job (`:534-654`) additionally has health-check (`:593-596`) + Slack success (`:608-625`) + Slack failure (`:636-653`). |
| 5 | Both deploy jobs assert rendered overlay pins `:<sha>` on all three jtoye images BEFORE apply | VERIFIED | `ci-cd.yaml:498-510` (staging) and `:575-587` (production): `kustomize build k8s/<env>` piped to a loop grepping each of core-java/edge-go/frontend for `:${{ github.sha }}`, `exit 1` + diagnostic print on any miss, before `kubectl apply -k`. |
| 6 | kustomize installed pinned + sha256-checksum-verified before use | VERIFIED | `ci-cd.yaml:474-485` (staging), `:551-562` (production): `KUSTOMIZE_VERSION=5.6.0`, `KUSTOMIZE_SHA256=54e40...`, `curl -sSfL` download, `sha256sum -c -` verify before extract/install — mirrors the oasdiff precedent. |
| 7 | New `lint` job runs frontend `eslint .` + edge-go `gofmt` + `go vet ./...` as a real gate | VERIFIED | `ci-cd.yaml:323-363` (`lint:` job). Independently re-ran locally: `cd frontend && npx eslint .` → exit 0, **0 errors**, 24 pre-existing warnings; `cd edge-go && gofmt -l .` → empty (clean); `go vet ./...` → clean. All three baselines confirmed green by the verifier directly, not just SUMMARY claim. |
| 8 | Unauthenticated GET /actuator/health/liveness + /readiness return 200; SecurityConfig permits `/actuator/health/**`; regression test guards it | VERIFIED (static/code) — execution not independently re-run, see human_verification | `SecurityConfig.java:134`: `auth.requestMatchers("/", "/health", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()` with rationale comment. `SecurityHeadersIntegrationTest.java` diff (`git diff 822d11f HEAD`) confirms +2 new tests `livenessProbeIsPubliclyAccessible`/`readinessProbeIsPubliclyAccessible` asserting `status().isOk()`, and `@SpringBootTest(properties = "management.endpoint.health.probes.enabled=true")` was added (was bare `@SpringBootTest`) — correctly addresses the documented "test profile doesn't enable probes" trap. k8s probe paths (`k8s/base/core-java-deployment.yaml:180-198`) confirmed to hit exactly `/actuator/health/liveness` and `/actuator/health/readiness`, matching the permitAll matcher. Verifier did NOT re-run `./gradlew :core-java:integrationTest` (explicitly excluded — ~24.5min Testcontainers suite); SUMMARY claims BUILD SUCCESSFUL 6/6. |
| 9 | Prod-shape smoke test passes healthy prod (probes 200, swagger NOT exposed); staging asserts swagger reachable via EXPECT_SWAGGER=true | VERIFIED | Independently re-verified by the verifier (not just SUMMARY claim): wrote two throwaway Node HTTP stub servers (ports 19292/19293, high ports, uncommitted, killed after use) replicating prod-shape and staging-shape response contracts, then ran `bash scripts/smoke-test.sh http://localhost:19292` (prod-shape, default env) → **10/10 PASS, exit 0**; ran `EXPECT_SWAGGER=true bash scripts/smoke-test.sh http://localhost:19293` (staging-shape) → **10/10 PASS, exit 0**. `ci-cd.yaml:523` confirms `EXPECT_SWAGGER=true` prefix on staging invocation only; production invocation (`:606`) unprefixed (defaults false). |
| 10 | `kubectl kustomize` builds clean for both overlays with unified `ghcr.io/bralabee/jtoye-<service>` names; no `ghcr.io/jtoye/` under k8s/ | VERIFIED | Independently ran `kubectl kustomize k8s/staging` and `kubectl kustomize k8s/production` → both build clean, rendered images are `ghcr.io/bralabee/jtoye-{core-java,edge-go,frontend}:2.1.0`. `grep -rl 'ghcr.io/jtoye/' k8s/` → empty. Staging `newTag` confirmed changed from mutable `"staging"` to immutable `"2.1.0"` (`k8s/staging/kustomization.yaml:30-35`). `k8s/DEPLOYMENT.md:250-258` documents the new `DEPLOY_STAGING_ENABLED` gate; `grep -qi "push to develop"` → no match. |
| 11 | `npm run lint` = `eslint .` exit 0/0 errors; `npm run build`+`npm test` green; `docs/metrics.json` resynced via `docs-freshness.sh --write`, check-mode passes | VERIFIED (eslint + metrics independently confirmed); build/test not independently re-run, see human_verification | `frontend/package.json:9`: `"lint": "eslint ."`. Independently ran `npx eslint .` → exit 0, 0 errors. Independently ran `bash scripts/docs-freshness.sh` (check mode) → `docs-freshness OK: metrics match source (total logical invocations: 1185)`, exit 0. `docs/metrics.json` shows `java_test_methods: 851` (up from 849 per SUMMARY, +2 for the probe regression tests) and `total_logical_invocations: 1185`. `npm run build` / `npm test` NOT independently re-run per explicit instruction. |

**Score:** 11/11 must-haves verified at the artifact/wiring/static level. 2 of the 11 (items 8, 11) have a sub-component (actual gradle/npm test execution) that the verifier was explicitly instructed not to re-run — routed to human/CI verification below rather than marked as gaps, since the implementation itself is substantive and correctly wired.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `.github/workflows/ci-cd.yaml` | Honest deploy pipeline | VERIFIED | actionlint clean (`/home/sanmi/go/bin/actionlint` exit 0); all grep assertions from the plan's static-verify block pass when literal-matched (`grep -F`) — the plan's own BRE grep for `${{ github.sha }}` has a GNU-grep brace-interval quirk unrelated to content, correctly identified by the executor. |
| `scripts/smoke-test.sh` | Prod-shape-safe smoke test | VERIFIED | `bash -n` clean; independently exercised against two stub servers, both pass (see Truth 9). |
| `core-java/.../SecurityConfig.java` | permitAll `/actuator/health/**` | VERIFIED | Line 134 confirmed, with rationale comment. |
| `core-java/.../SecurityHeadersIntegrationTest.java` | Regression guard for liveness/readiness | VERIFIED (code); execution CI-covered | +2 tests confirmed via diff; correctly enables probes property. |
| `docs/metrics.json` | Resynced test counts | VERIFIED | `java_test_methods: 851`, `total_logical_invocations: 1185`; `docs-freshness.sh` check-mode exit 0. |
| `core-java/.../application-dev.yml` | Dev-parity probes | VERIFIED | `management.endpoint.health.probes.enabled: true` block present, correctly scoped comment. |
| `k8s/staging/kustomization.yaml` | Unified image names + immutable tag | VERIFIED | `ghcr.io/bralabee/jtoye-*` names, `newTag: "2.1.0"` (was mutable `staging`). |
| `k8s/production/kustomization.yaml` | Unified image names | VERIFIED | `ghcr.io/bralabee/jtoye-*` names, `newTag: "2.1.0"`. |
| `frontend/eslint.config.mjs` | ESLint v9 flat config | VERIFIED | Native `eslint-config-next/core-web-vitals` + `/typescript` arrays spread directly (no FlatCompat); confirmed functional by independent `npx eslint .` run (0 errors). |
| `frontend/package.json` | `lint` = `eslint .` | VERIFIED | Line 9 confirmed. |
| `docs/CHANGELOG.md` | `[Unreleased]` entry covering A-E | VERIFIED | Entry present at line 8-25, explicitly covers the ImagePullBackOff bug (B) and probe-401 bug (F/#99 scope addition), plus metrics resync note. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| ci-cd.yaml deploy jobs `kustomize edit set image` | metadata-action tags (`type=raw,value=github.sha`) | full-sha tag must be pushed for overlay ref to resolve | WIRED | Tag present at `:407`; both deploy jobs reference `:${{ github.sha }}` at `:495-497`/`:572-574`. |
| ci-cd.yaml pre-apply render assertion | k8s kustomization.yaml `images[].name` keys | `kustomize build` grepped for `:sha` on all 3 images | WIRED | Both overlays' `images[].name` keys match `ghcr.io/bralabee/jtoye-{core-java,edge-go,frontend}` exactly, matching the assertion's grep targets. |
| ci-cd.yaml deploy-staging smoke step | smoke-test.sh `EXPECT_SWAGGER` switch | `EXPECT_SWAGGER=true` prefix on staging invocation only | WIRED | Confirmed via independent stub-server execution — behavior correctly diverges staging vs prod. |
| ci-cd.yaml lint job `npm run lint` | frontend/eslint.config.mjs | `eslint .` discovers flat config | WIRED | Confirmed via independent `npx eslint .` run in frontend/ — 0 errors. |
| k8s probe paths + smoke-test.sh Tests 4/5 | SecurityConfig permitAll matcher | unauthenticated GET must not 401 | WIRED | k8s probe paths (`/actuator/health/liveness`, `/actuator/health/readiness`) exactly match SecurityConfig's `/actuator/health/**` matcher and smoke-test.sh Tests 4/5 target paths. |
| k8s images[].name + deployment `image:` | build-and-push `IMAGE_PREFIX` | image name keys must equal pushed repo names | WIRED | `IMAGE_PREFIX: ${{ github.repository_owner }}/jtoye` → `ghcr.io/bralabee/jtoye-<service>`, matches all k8s image references exactly. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Frontend eslint gate is real and green | `cd frontend && npx eslint .` | exit 0, 0 errors, 24 warnings | PASS |
| edge-go gofmt gate is real and green | `cd edge-go && gofmt -l .` | empty output (clean) | PASS |
| edge-go go vet gate is real and green | `cd edge-go && go vet ./...` | clean, no output | PASS |
| Workflow YAML is well-formed and passes actionlint | `actionlint .github/workflows/ci-cd.yaml` | exit 0, no findings | PASS |
| Both k8s overlays render without error | `kubectl kustomize k8s/staging`, `kubectl kustomize k8s/production` | both build clean | PASS |
| k8s secret/connection-math guards pass | `./k8s/scripts/check-no-plaintext-secrets.sh`, `./k8s/scripts/check-connection-math.sh` | both exit 0 | PASS |
| Prod-shape smoke test passes against a synthetic prod-shape server | Node stub on :19292 + `bash scripts/smoke-test.sh http://localhost:19292` | 10/10 PASS, exit 0 | PASS |
| Staging-shape smoke test passes against a synthetic staging-shape server | Node stub on :19293 + `EXPECT_SWAGGER=true bash scripts/smoke-test.sh http://localhost:19293` | 10/10 PASS, exit 0 | PASS |
| docs-freshness gate is currently green | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1185)`, exit 0 | PASS |
| bash syntax of smoke-test.sh is valid | `bash -n scripts/smoke-test.sh` | no output, exit 0 | PASS |

### Probe Execution

Not applicable — this task does not use `scripts/*/tests/probe-*.sh` conventional probes; no such files exist for this quick task.

### Requirements Coverage

`requirements: [ISSUE-99-DO-NOW-REMAINDER]` declared in PLAN frontmatter. This is a quick task (not a full ROADMAP phase), and `.planning/REQUIREMENTS.md` has no entry for `ISSUE-99-DO-NOW-REMAINDER` (grep returned no matches) — this is expected for quick-task workflow, which tracks scope via the PLAN's own `must_haves` rather than the formal REQUIREMENTS.md ledger. No orphaned requirements identified.

### Anti-Patterns Found

None. Scanned all 19 files in `files_modified` (SUMMARY frontmatter) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` and empty-implementation patterns — zero matches in any phase-touched file. (One incidental match for the substring "TODO" appears in `docs/CHANGELOG.md` line 438, in unrelated historical prose about a different, already-shipped WhatsApp feature — not a debt marker in this phase's work.)

### Deviation Assessment (per launch instructions)

The executor's SUMMARY documents one deviation: the initial `deploy-staging` explanatory comment literally contained the string `refs/heads/develop` (as prose describing what the OLD gate used to be), which tripped the plan's `! grep -qE "refs/heads/develop"` static-verify gate. The executor reworded the comment.

**Verified: this is cosmetic-only, zero behavior change.** Confirmed via `git show 2132062:.github/workflows/ci-cd.yaml` — commit `2132062` (Task 1's own commit) already contains the final reworded comment ("the old branch-name gate could never fire — that branch does not exist on the remote") with the functional gate unchanged: `if: github.ref == 'refs/heads/main' && vars.DEPLOY_STAGING_ENABLED == 'true'`. `grep -n "develop" .github/workflows/ci-cd.yaml` today returns zero matches anywhere in the file (comments included). This does not affect Truth 1 or Truth 2 — both pass independent of this wording change.

### Human Verification Required

1. **Gradle integrationTest actual execution (probe regression tests)**
   **Test:** Run `./gradlew :core-java:integrationTest --tests "uk.jtoye.core.security.*" --no-daemon` (or observe the PR's own CI run of the `integration-tests` job, since this PR touches `core-java/**` and will trigger the full suite per the plan's own note).
   **Expected:** BUILD SUCCESSFUL; `SecurityHeadersIntegrationTest` 6/6 passing (was 4, +2 new: `livenessProbeIsPubliclyAccessible`, `readinessProbeIsPubliclyAccessible`).
   **Why human:** Verifier was explicitly instructed not to re-run this ~24.5min Testcontainers suite. Code inspection (matcher + test assertions + probes-enabled property) is correct and substantive, but only an actual run proves runtime behavior.

2. **Frontend `npm run build` stays green**
   **Test:** `cd frontend && npm run build`
   **Expected:** Next.js/tsc build compiles successfully with the four `eslint-disable-next-line` additions, the `Link` import, and the `tailwind.config.ts` require()->import conversion.
   **Why human:** Verifier was explicitly instructed not to re-run npm build. `eslint .` was independently re-run (0 errors), but `next build`'s TypeScript type-check is a distinct gate (project memory: "jest doesn't type-check").

3. **Frontend `npm test` stays green**
   **Test:** `cd frontend && npm test -- --ci --watchAll=false`
   **Expected:** 32 suites / 226 tests passing per SUMMARY claim, no regressions from the touched files.
   **Why human:** Verifier was explicitly instructed not to re-run npm test.

### Gaps Summary

No gaps found. All 11 must-have truths are substantively implemented and correctly wired at the static/code/config level — independently re-verified by the verifier wherever the launch instructions permitted (actionlint, `kubectl kustomize` x2, k8s guard scripts x2, `npx eslint .`, `gofmt -l .`, `go vet ./...`, `docs-freshness.sh` check mode, `bash -n`, and two independent stub-server smoke-test runs replicating prod-shape and staging-shape). The only items not independently re-executed (gradle Testcontainers integration test, `npm run build`, `npm test`) were explicitly excluded from this verification pass per the launching instructions and are routed to CI/human confirmation — the code implementing them is correct and non-stub. Status is `human_needed` rather than `passed` solely because those three execution-confirmations are outstanding, not because any implementation gap was found.

---

_Verified: 2026-07-12T20:30:00Z_
_Verifier: Claude (gsd-verifier)_
