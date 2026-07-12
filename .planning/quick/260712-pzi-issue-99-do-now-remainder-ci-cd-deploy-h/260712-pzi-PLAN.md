---
phase: quick-260712-pzi
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [ISSUE-99-DO-NOW-REMAINDER]
files_modified:
  - .github/workflows/ci-cd.yaml
  - scripts/smoke-test.sh
  - core-java/src/main/resources/application-dev.yml
  - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
  - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java
  - docs/metrics.json
  - k8s/base/core-java-deployment.yaml
  - k8s/base/edge-go-deployment.yaml
  - k8s/base/frontend-deployment.yaml
  - k8s/staging/kustomization.yaml
  - k8s/production/kustomization.yaml
  - k8s/DEPLOYMENT.md
  - frontend/eslint.config.mjs
  - frontend/.eslintrc.json
  - frontend/package.json
  - frontend/tailwind.config.ts
  - frontend/app/shop/auth/callback/page.tsx
  - frontend/components/dashboard/mobile-tab-bar.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/storefront/storefront-nav.tsx
  - docs/CHANGELOG.md

must_haves:
  truths:
    - "The CI/CD pipeline no longer triggers on the non-existent `develop` branch (push + PR)."
    - "deploy-staging is off by default: it runs only on main AND when vars.DEPLOY_STAGING_ENABLED == 'true' (mirrors the deploy-production vars.DEPLOY_ENABLED gate)."
    - "Every push/release build pushes an immutable full-sha image tag (type=raw,value=${{ github.sha }}) that both deploy jobs reference — no more guaranteed ImagePullBackOff."
    - "Both deploy jobs pin the full-sha image via `kustomize edit set image` and apply the overlay with `kubectl apply -k`, replacing the old `kubectl set image` steps; staging keeps its rollout-status/smoke-test/rollback steps, production additionally keeps its health-check + Slack steps."
    - "Both deploy jobs assert the RENDERED overlay (kustomize build) pins :<full github.sha> on all three jtoye image refs BEFORE kubectl apply — a silent name-key mismatch falling back to the static 2.1.0 tag fails the job loudly."
    - "kustomize is installed pinned + sha256-checksum-verified (oasdiff-step precedent) before it is used."
    - "A new `lint` job runs frontend `eslint .` and edge-go gofmt-check + `go vet ./...` as a real gate."
    - "Unauthenticated GET /actuator/health/liveness AND /actuator/health/readiness return 200 — SecurityConfig permits /actuator/health/** so kubelet probes (k8s/base/core-java-deployment.yaml:181-198) and smoke Tests 4/5 no longer 401 → pods can go Ready; guarded by a regression test."
    - "The prod-shape smoke test passes a healthy prod deployment (probes 200; swagger NOT publicly exposed) so good releases are never auto-rolled-back; the staging invocation asserts swagger reachable via EXPECT_SWAGGER=true."
    - "kubectl kustomize builds clean for both overlays using unified image names ghcr.io/bralabee/jtoye-<service>; no `ghcr.io/jtoye/` string remains under k8s/."
    - "`npm run lint` runs `eslint .` with exit 0 and 0 errors; `npm run build` and `npm test` stay green; docs/metrics.json is RESYNCED via scripts/docs-freshness.sh --write (Java @Test count grows by the probe regression tests) and check-mode passes afterwards — docs-freshness gate stays green."
  artifacts:
    - path: ".github/workflows/ci-cd.yaml"
      provides: "Honest deploy pipeline: no dead develop trigger, gated staging, immutable sha tag, kustomize-overlay deploys with pre-apply render assertion, lint job, EXPECT_SWAGGER on staging smoke."
      contains: "type=raw,value=${{ github.sha }}"
    - path: "scripts/smoke-test.sh"
      provides: "Prod-shape-safe smoke test (probe liveness/readiness + conditional EXPECT_SWAGGER swagger assertions)."
      contains: "EXPECT_SWAGGER"
    - path: "core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java"
      provides: "permitAll for /actuator/health/** so unauthenticated kubelet probes and smoke tests get 200 (health group exposes only aggregate status — nil surface risk)."
      contains: "/actuator/health/**"
    - path: "core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java"
      provides: "Regression guard: unauthenticated GET /actuator/health/liveness + /actuator/health/readiness → 200 (extend this closest analog; a sibling class mirroring its annotations is acceptable if extending is impractical — then also add that file to the commit)."
      contains: "actuator/health/liveness"
    - path: "docs/metrics.json"
      provides: "Resynced test counts (Java @Test grows) via scripts/docs-freshness.sh --write — the single source of truth the docs-freshness CI gate enforces."
    - path: "core-java/src/main/resources/application-dev.yml"
      provides: "Dev-parity Kubernetes health probes so the smoke script works against the local dev stack after next rebuild."
      contains: "probes"
    - path: "k8s/staging/kustomization.yaml"
      provides: "Unified image name keys + immutable newTag 2.1.0 (was mutable 'staging')."
      contains: "ghcr.io/bralabee/jtoye-core-java"
    - path: "k8s/production/kustomization.yaml"
      provides: "Unified image name keys, immutable newTag 2.1.0 retained."
      contains: "ghcr.io/bralabee/jtoye-core-java"
    - path: "frontend/eslint.config.mjs"
      provides: "ESLint v9 flat config wiring native eslint-config-next core-web-vitals + typescript subpath arrays; replaces the silently-dead next lint."
      contains: "eslint-config-next/core-web-vitals"
    - path: "frontend/package.json"
      provides: "`lint` script points at `eslint .` (was the removed-in-Next-16 `next lint`)."
      contains: "\"lint\": \"eslint .\""
    - path: "docs/CHANGELOG.md"
      provides: "[Unreleased] entry covering A–E incl. the latent full-sha-tag ImagePullBackOff bug AND the probe-401 SecurityConfig bug + metrics resync."
      contains: "Unreleased"
  key_links:
    - from: ".github/workflows/ci-cd.yaml (deploy jobs, kustomize edit set image)"
      to: "docker/metadata-action tags list (type=raw,value=${{ github.sha }})"
      via: "the full-sha tag must be pushed for the overlay reference to resolve"
      pattern: "type=raw,value=\\$\\{\\{ github.sha \\}\\}"
    - from: ".github/workflows/ci-cd.yaml (pre-apply render assertion in both deploy jobs)"
      to: "k8s/*/kustomization.yaml images[].name keys"
      via: "kustomize build output grepped for :${{ github.sha }} on all three jtoye images — catches images-transformer name-key mismatch before anything reaches the cluster"
      pattern: "kustomize build k8s/"
    - from: ".github/workflows/ci-cd.yaml (deploy-staging Run smoke tests step)"
      to: "scripts/smoke-test.sh EXPECT_SWAGGER env switch"
      via: "EXPECT_SWAGGER=true prefix on the staging invocation only"
      pattern: "EXPECT_SWAGGER=true .*smoke-test.sh"
    - from: ".github/workflows/ci-cd.yaml (lint job, `npm run lint` in frontend/)"
      to: "frontend/eslint.config.mjs"
      via: "`eslint .` discovers the flat config created in Task 3; both land in the same PR so the job is first exercised on the PR's own CI run"
      pattern: "npm run lint"
    - from: "k8s/base/core-java-deployment.yaml probe paths (:181-198) + scripts/smoke-test.sh Tests 4/5"
      to: "SecurityConfig.java permitAll matcher (/actuator/health/**)"
      via: "kubelet and curl are unauthenticated — without the permitAll subpath match every probe 401s and no pod ever goes Ready"
      pattern: "actuator/health/\\*\\*"
    - from: "k8s/*/kustomization.yaml images[].name + k8s/base/*-deployment.yaml image:"
      to: "build-and-push IMAGE_PREFIX (ghcr.io/${{ github.repository_owner }}/jtoye-<service>)"
      via: "image name keys must equal the pushed repo names for kustomize to override the tag"
      pattern: "ghcr.io/bralabee/jtoye-(core-java|edge-go|frontend)"
    - from: "frontend/package.json lint script"
      to: "frontend/eslint.config.mjs"
      via: "eslint . discovers the flat config"
      pattern: "eslint \\."
---

<objective>
Make the Issue #99 "do-now remainder" of the CI/CD deploy half honest. Today the pipeline has six latent lies/bugs verified live this session:

1. It triggers on a `develop` branch that does not exist on the remote (dead trigger), and `deploy-staging` is gated `if: github.ref == 'refs/heads/develop'` (ci-cd.yaml:5,7,405) so it can never run.
2. Both deploy jobs run `kubectl set image ...:${{ github.sha }}` (ci-cd.yaml:428,477,488,495) but docker/metadata-action only pushes `main-<short-sha>`, branch, semver, and `latest` (ci-cd.yaml:350-356) — the full-sha tag is NEVER pushed → guaranteed ImagePullBackOff on every deploy.
3. The smoke test asserts `/swagger-ui.html`→302 (smoke-test.sh:70) and `/v3/api-docs`→200 (smoke-test.sh:77), but both are disabled in prod (application-prod.yml:120-124, `${SWAGGER_ENABLED:false}`) → a healthy prod release fails smoke → automatic rollback of good releases.
4. k8s base/overlays reference `ghcr.io/jtoye/<service>` (core-java-deployment.yaml:45, edge-go-deployment.yaml:44, frontend-deployment.yaml:40, staging/production kustomization images) while CI pushes `ghcr.io/bralabee/jtoye-<service>` — image-name mismatch. Plus staging pins the MUTABLE `newTag: staging` (staging/kustomization.yaml:26).
5. Frontend linting is silently non-functional: `npm run lint` = `next lint`, removed in Next 16; ESLint installed is v9 (flat-config only) with only a legacy .eslintrc.json.
6. USER-APPROVED SCOPE ADDITION — probe-401 bug: SecurityConfig.java:126 permits only the EXACT paths "/", "/health", "/actuator/health", "/actuator/info" (no subpath matching), but k8s/base/core-java-deployment.yaml:181-198 points startupProbe/livenessProbe/readinessProbe at /actuator/health/liveness and /actuator/health/readiness → kubelet's unauthenticated probes would 401 → pods never go Ready → every real rollout fails, and this plan's new smoke Tests 4/5 would inherit the same 401.

Purpose: A green pipeline that actually deploys and actually gates, so future deploys don't ImagePullBackOff, pods actually go Ready, good releases don't auto-rollback, and frontend regressions are caught.
Output: Rewritten deploy workflow (incl. a new lint job + pre-apply render assertion), permitAll'd probe endpoints with a regression test + resynced metrics, prod-shape-safe smoke test, unified immutable-tag k8s manifests, a working ESLint flat config with the codebase at 0 errors, and a CHANGELOG entry.

Part 1 (integration-tests path filter) already shipped as PR #200 — do NOT touch the integration-tests job.
NOTE: this PR now touches core-java/** → its own CI run executes the full Testcontainers integration suite (~24.5 min). Expected; no action needed.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md

Scratchpad (for the smoke-test stub servers in Task 1 — NOT committed):
/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/c6fdcfae-6392-4ff0-86b2-2ebbe8e851f1/scratchpad

EXECUTOR-PROCESS NOTES (read before running any gate):
- The isolated worktree has NO node_modules — run `npm ci` in frontend/ BEFORE the eslint/build/jest gates in Task 3 (and before `npm run lint` locally if exercised).
- The two verification stub servers MUST bind high ports (use 19090 for prod-shape, 19091 for staging-shape) — NEVER 3000/9090; the live local stack owns those.
- docs/metrics.json is the single source of truth enforced by the docs-freshness CI gate; after adding the Java regression tests, `scripts/docs-freshness.sh --write` is the arbiter (metrics-conflict recipe from project memory).
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<interfaces>
<!-- Verified live this session. Executor uses these directly — no exploration needed. -->

ci-cd.yaml existing precedents to MIRROR (do not re-invent):
- Checksum-verified pinned binary install (oasdiff), ci-cd.yaml:245-252 — same shape for kustomize:
  set VERSION + SHA256 vars, `curl -sSfL -o file`, `echo "${SHA}  file" | sha256sum -c -`, extract, `--version`.
- Production deploy gate pattern, ci-cd.yaml:456:
  `if: (github.ref == 'refs/heads/main' || github.event_name == 'release') && vars.DEPLOY_ENABLED == 'true'`
- docker/metadata-action tags block, ci-cd.yaml:350-356 (ADD one line, keep the rest).
- IMAGE_PREFIX, ci-cd.yaml:13: `${{ github.repository_owner }}/jtoye` → metadata-action lowercases owner → pushes `ghcr.io/bralabee/jtoye-<service>`.
- pg-backup precedent already uses the unified prefix: pg-backup-cronjob.yaml:35 `ghcr.io/bralabee/jtoye-pg-backup:15`.
- Toolchain setup precedents for the new lint job: setup-node@v4 node 20 + npm cache on frontend/package-lock.json (ci-cd.yaml:55-60); setup-go@v5 go 1.25 + cache-dependency-path edge-go/go.sum (ci-cd.yaml:45-53).

Deploy-job step inventory (verified — the two jobs are NOT symmetric):
- deploy-staging has: checkout, setup-kubectl, kubeconfig, image-update, rollout-status, smoke-test, rollback. NO health-check, NO Slack steps.
- deploy-production has: checkout, setup-kubectl, kubeconfig, per-service image-update+rollout, health-check (:481-484), smoke-test, Slack success (:505), rollback (:524), Slack failure (:533).

SecurityConfig probe-401 bug (verified):
- core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:126:
  `auth.requestMatchers("/", "/health", "/actuator/health", "/actuator/info").permitAll()` — EXACT paths only.
- k8s/base/core-java-deployment.yaml:181-198: startupProbe + livenessProbe → /actuator/health/liveness :9090; readinessProbe → /actuator/health/readiness.
- Closest test analog: core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java —
  @SpringBootTest + @AutoConfigureMockMvc + @Testcontainers (throwaway postgres:15) + @ActiveProfiles("test") + @Tag("testcontainers"),
  unauthenticated MockMvc `get(...)` assertions. Being @Tag("testcontainers") it runs under `./gradlew :core-java:integrationTest`, NOT the fast test task.
- The test profile does NOT enable probes — the regression test MUST set `management.endpoint.health.probes.enabled=true` (e.g. `@SpringBootTest(properties=...)` or @TestPropertySource) or the probe endpoints 404.

Health/probe/springdoc facts (verified):
- application-staging.yml: probes enabled (:95 region), springdoc api-docs+swagger-ui enabled (:110-116).
- application-prod.yml: probes enabled (:105 region), springdoc `${SWAGGER_ENABLED:false}` (:120-124).
- application.yml (base, :174-211): management block sets endpoints exposure + health show-details but does NOT set endpoint.health.probes.enabled; springdoc paths /v3/api-docs and /swagger-ui.html.
- application-dev.yml: NO management block at all (top-level keys are only logging: and jtoye:) — Task 1 ADDS a fresh top-level management block.

eslint-config-next@16.2.2 (verified live): exports NATIVE flat-config arrays at subpaths
`eslint-config-next/core-web-vitals` (4 entries) and `eslint-config-next/typescript` (5 entries).
FlatCompat wrapping CRASHES (circular-structure) — do NOT use FlatCompat. Spread the arrays directly.

Exact remaining eslint errors after config (verified today; 0 errors required after fixes):
- tailwind.config.ts:75 — require() → top-level import.
- app/shop/auth/callback/page.tsx:36 — `<a href="/shop">` (no trailing slash) → `<Link href="/shop">` (next/link); reported twice, ONE element.
- react-hooks/set-state-in-effect ×4 — app/shop/auth/callback/page.tsx:17, components/dashboard/mobile-tab-bar.tsx:64, components/dashboard/sidebar.tsx:48, components/storefront/storefront-nav.tsx:28 → eslint-disable-next-line each (do NOT refactor behavior).

Local lint baselines (verified clean today): edge-go `gofmt -l .` prints nothing; `go vet ./...` clean; frontend eslint 0 errors AFTER the Task 3 fixes.

k8s image references to unify (grep -rn "ghcr.io/jtoye/" k8s/ verified):
- k8s/base/core-java-deployment.yaml:45, edge-go-deployment.yaml:44, frontend-deployment.yaml:40
- k8s/staging/kustomization.yaml:25,27,29 ; k8s/production/kustomization.yaml:25,27,29
- k8s/DEPLOYMENT.md:189,191,193 (doc — must also change so the grep gate returns nothing)

k8s/DEPLOYMENT.md staging-flow prose (verified): `grep -i develop` returns NOTHING — no "push to develop → staging" flow is documented. The Staging Deployment section (:250-258) documents manual `kubectl apply -k k8s/staging` only.

Tooling on this box: kubectl at /usr/local/bin/kubectl (use `kubectl kustomize` for local verify); kustomize CLI NOT installed locally.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Honest deploy workflow + lint job + probe permitAll fix + prod-shape-safe smoke test + dev probes (Decisions A & B + probe-401 scope addition)</name>
  <files>.github/workflows/ci-cd.yaml, scripts/smoke-test.sh, core-java/src/main/resources/application-dev.yml, core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java, core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java, docs/metrics.json</files>
  <action>
  WORKFLOW (.github/workflows/ci-cd.yaml) — per Decision A:
  1. Remove `develop` from both `on.push.branches` (:5) and `on.pull_request.branches` (:7). Keep main and 'phase-*' on push; keep main on PR.
  2. In docker/metadata-action tags (:350-356) ADD `type=raw,value=${{ github.sha }}` so an immutable full-sha tag is pushed on every push/release build (fixes the latent ImagePullBackOff). Keep all existing tag lines.
  3. deploy-staging `if:` (:405) → `github.ref == 'refs/heads/main' && vars.DEPLOY_STAGING_ENABLED == 'true'` (mirror the deploy-production vars.DEPLOY_ENABLED gate at :456; off until the var is set — cluster-safe).
  4. In BOTH deploy jobs, add a kustomize install step BEFORE the deploy step, mirroring the oasdiff checksum-verified install at :245-252: pin KUSTOMIZE_VERSION to a specific kubernetes-sigs/kustomize release (tag form `kustomize/vX.Y.Z`), download the linux_amd64 tarball from that release, and verify it against the sha256 taken from that release's official `checksums.txt` (fetch the real sha; do NOT invent one) via `sha256sum -c -`, extract, run `kustomize version`.
  5. Replace the `kubectl set image` deploy steps (staging :423-430; production :474-498 three services) with kustomize-overlay deploys: for staging, `(cd k8s/staging && kustomize edit set image ghcr.io/bralabee/jtoye-core-java=ghcr.io/bralabee/jtoye-core-java:${{ github.sha }} ghcr.io/bralabee/jtoye-edge-go=...:${{ github.sha }} ghcr.io/bralabee/jtoye-frontend=...:${{ github.sha }})` then `kubectl apply -k k8s/staging`; production identical against k8s/production. PREMORTEM GUARD — between `kustomize edit set image` and `kubectl apply -k`, add an assertion step in BOTH jobs: render the overlay (`kustomize build k8s/<env>`) and grep the output for `:${{ github.sha }}` on ALL THREE jtoye image refs (jtoye-core-java, jtoye-edge-go, jtoye-frontend); if any is missing, print the rendered image lines and FAIL the job loudly — this catches an images-transformer name-key mismatch that would otherwise silently deploy the static 2.1.0 default. Keep each job's EXISTING follow-on steps as-is — the two jobs are NOT symmetric: staging keeps its rollout-status, smoke-test, and rollback steps (it has NO health-check or Slack steps — do not add any); production keeps its rollout-status waits, health-check, smoke-test, Slack success/failure, and rollback steps.
  6. Staging "Run smoke tests" step: prefix the invocation with `EXPECT_SWAGGER=true` env. Production smoke invocation: leave unchanged (defaults to false).
  7. Add a NEW `lint` job (Decision A.5; no services block, runs-on ubuntu-latest, checkout first). Two logical parts in one job:
     - Frontend: actions/setup-node@v4 with node-version '20', cache 'npm', cache-dependency-path frontend/package-lock.json (mirror :55-60); then `npm ci` and `npm run lint`, both with working-directory frontend. (`npm run lint` becomes `eslint .` in Task 3 — both tasks land in the same PR, so the job is first exercised on the PR's own CI run; no ordering hazard.)
     - edge-go: actions/setup-go@v5 with go-version '1.25', cache-dependency-path edge-go/go.sum (mirror :45-53); then a gofmt check that FAILS listing offenders when any file is unformatted (idiom: capture `gofmt -l .`, if non-empty print the offending files and exit 1 — i.e. `test -z "$(gofmt -l .)"` with an offender-printing failure branch) and `go vet ./...`, both with working-directory edge-go.
     Both baselines verified clean locally today (gofmt: clean; vet: clean; eslint: 0 errors after Task 3 fixes), so the job goes green on this PR.
  Do NOT touch the test, integration-tests, k8s-validate, openapi-compat, security-scan, or build-and-push jobs beyond the metadata-action tag addition.

  SECURITY CONFIG — probe-401 fix (user-approved scope addition):
  1. core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:126 — add `"/actuator/health/**"` to the permitAll matcher list (currently exact-path only: "/", "/health", "/actuator/health", "/actuator/info"). Add a comment citing WHY: kubelet's unauthenticated startup/liveness/readiness probes hit /actuator/health/liveness and /actuator/health/readiness (k8s/base/core-java-deployment.yaml:181-198) and the deploy smoke tests assert the same paths — without subpath matching every probe 401s and no pod ever goes Ready. Health group endpoints expose only aggregate status (show-details default), so surface risk is nil.
  2. Regression guard: extend core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java (closest analog — unauthenticated MockMvc GETs, Testcontainers postgres, @ActiveProfiles("test"), @Tag("testcontainers")) with two tests asserting unauthenticated GET /actuator/health/liveness → 200 AND /actuator/health/readiness → 200. CRITICAL: the test profile does NOT enable probes — set `management.endpoint.health.probes.enabled=true` via @SpringBootTest properties/@TestPropertySource or the endpoints 404. If extending that class is impractical (property change conflicts with its existing context), create a sibling class in the same package mirroring its annotations (and include it in the commit + files list).
  3. Gates: run the modified test class (`./gradlew :core-java:integrationTest --tests "uk.jtoye.core.security.*"` — it is @Tag("testcontainers"), NOT in the fast test task); then run `scripts/docs-freshness.sh --write` to resync docs/metrics.json (the Java @Test count grows; metrics.json is the arbiter — commit the resynced file).
  NOTE: this makes the PR touch core-java/** → the PR's own CI runs the full Testcontainers suite (~24.5 min). Expected; no action.

  SMOKE TEST (scripts/smoke-test.sh) — per Decision B:
  1. Test 4 (:69-74): assert `/actuator/health/liveness` → 200. Test 5 (:76-81): assert `/actuator/health/readiness` → 200.
  2. Move swagger checks into a CONDITIONAL block driven by env `EXPECT_SWAGGER` (default false): when true, assert `/swagger-ui.html`→302 AND `/v3/api-docs`→200; when false, assert BOTH are NOT publicly exposed — status must NOT be 2xx/3xx (accept 401 or 404; do not over-pin which). Increment the same pass/fail counters used elsewhere.
  3. Keep Tests 1-3 (/health, /actuator/health, /actuator/info) and Tests 6-8 (401 shops, invalid endpoint, CORS) unchanged. Keep the pass/fail counter idiom and summary/exit logic.

  DEV PROBES (application-dev.yml) — per Decision B.4:
  Add a top-level `management:` block with `endpoint.health.probes.enabled: true` (dev currently has NO management block; base does not enable probes) so the smoke script works against the local dev stack after its NEXT rebuild. Do NOT rebuild containers here — config takes effect on next rebuild, zero runtime risk now.
  </action>
  <verify>
  <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && \
  bash -n scripts/smoke-test.sh && \
  python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci-cd.yaml')); yaml.safe_load(open('core-java/src/main/resources/application-dev.yml')); print('YAML OK')" && \
  grep -q "type=raw,value=\${{ github.sha }}" .github/workflows/ci-cd.yaml && \
  grep -q "vars.DEPLOY_STAGING_ENABLED == 'true'" .github/workflows/ci-cd.yaml && \
  grep -q "kubectl apply -k k8s/staging" .github/workflows/ci-cd.yaml && \
  grep -q "kubectl apply -k k8s/production" .github/workflows/ci-cd.yaml && \
  grep -q "kustomize edit set image" .github/workflows/ci-cd.yaml && \
  grep -q "kustomize build k8s/" .github/workflows/ci-cd.yaml && \
  grep -q "sha256sum -c" .github/workflows/ci-cd.yaml && \
  grep -q "EXPECT_SWAGGER=true" .github/workflows/ci-cd.yaml && \
  grep -qE "^  lint:" .github/workflows/ci-cd.yaml && \
  grep -q "npm run lint" .github/workflows/ci-cd.yaml && \
  grep -q "gofmt -l" .github/workflows/ci-cd.yaml && \
  grep -q "go vet ./..." .github/workflows/ci-cd.yaml && \
  ! grep -qE "refs/heads/develop" .github/workflows/ci-cd.yaml && \
  ! grep -q "kubectl set image" .github/workflows/ci-cd.yaml && \
  grep -q "actuator/health/liveness" scripts/smoke-test.sh && \
  grep -q "actuator/health/readiness" scripts/smoke-test.sh && \
  grep -q "EXPECT_SWAGGER" scripts/smoke-test.sh && \
  grep -q "probes" core-java/src/main/resources/application-dev.yml && \
  grep -q '"/actuator/health/\*\*"' core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java && \
  echo "STATIC CHECKS PASS"</automated>
  <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && \
  ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.security.*" --no-daemon && \
  ./scripts/docs-freshness.sh --write && \
  ./scripts/docs-freshness.sh && \
  echo "PROBE REGRESSION TEST + METRICS RESYNC OK"</automated>
  <automated>SCRATCH=/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/c6fdcfae-6392-4ff0-86b2-2ebbe8e851f1/scratchpad; \
  Write two throwaway Node HTTP stub servers to $SCRATCH (NOT committed), binding HIGH ports — 19090 (prod-shape) and 19091 (staging-shape); NEVER 3000/9090 (live stack owns them): \
  (a) prod-shape on :19090 — GET /health,/actuator/health,/actuator/health/liveness,/actuator/health/readiness,/actuator/info →200; /swagger-ui.html & /v3/api-docs →404; GET /shops →401; OPTIONS /shops →204; anything else →404. \
  (b) staging-shape on :19091 — same as prod-shape PLUS /swagger-ui.html →302 and /v3/api-docs →200. \
  Run `./scripts/smoke-test.sh http://localhost:19090` against (a) with default env → ALL tests pass, exit 0. \
  Run `EXPECT_SWAGGER=true ./scripts/smoke-test.sh http://localhost:19091` against (b) → ALL tests pass, exit 0. \
  This is the agreed proof standard (no cluster available). Stubs stay in scratchpad, uncommitted.</automated>
  </verify>
  <done>develop trigger + kubectl-set-image gone; full-sha tag added to metadata-action; deploy-staging gated on main+DEPLOY_STAGING_ENABLED; both deploy jobs install checksum-verified kustomize, assert the rendered overlay pins :github.sha on all three images, then apply overlays (staging keeps rollout/smoke/rollback; production keeps rollout/health-check/smoke/Slack/rollback); new lint job present (frontend npm run lint + edge-go gofmt/vet); SecurityConfig permits /actuator/health/** with rationale comment; probe regression tests pass under integrationTest with probes property enabled; docs/metrics.json resynced via docs-freshness --write and check-mode clean; staging smoke passes EXPECT_SWAGGER=true; smoke Tests 4/5 assert probes; swagger checks conditional and prod-safe; dev yml enables probes; both stub-server smoke runs pass on ports 19090/19091; bash -n and YAML parse clean.</done>
</task>

<task type="auto">
  <name>Task 2: Unify k8s image names to ghcr.io/bralabee/jtoye-* + immutable staging tag (Decision C)</name>
  <files>k8s/base/core-java-deployment.yaml, k8s/base/edge-go-deployment.yaml, k8s/base/frontend-deployment.yaml, k8s/staging/kustomization.yaml, k8s/production/kustomization.yaml, k8s/DEPLOYMENT.md</files>
  <action>
  Per Decision C — align k8s image references with what CI actually pushes (ghcr.io/bralabee/jtoye-<service>; precedent pg-backup-cronjob.yaml:35):
  1. Base deployment images: core-java-deployment.yaml:45 → `ghcr.io/bralabee/jtoye-core-java:2.1.0`; edge-go-deployment.yaml:44 → `ghcr.io/bralabee/jtoye-edge-go:2.1.0`; frontend-deployment.yaml:40 → `ghcr.io/bralabee/jtoye-frontend:2.1.0`.
  2. k8s/staging/kustomization.yaml images[].name keys (:25,27,29) → the three new names; change each `newTag` from the MUTABLE `staging` (the audit finding, :26/28/30) to immutable `newTag: "2.1.0"` (CI pins the full-sha tag at deploy time via `kustomize edit set image` from Task 1).
  3. k8s/production/kustomization.yaml images[].name keys (:25,27,29) → the three new names; newTag `2.1.0` stays.
  4. k8s/DEPLOYMENT.md:189,191,193 → update the documented image names to the unified `ghcr.io/bralabee/jtoye-<service>` so the grep gate below returns nothing.
  5. k8s/DEPLOYMENT.md staging-flow prose: verified today that NO "push to develop → staging" flow is documented (`grep -i develop` → nothing; the Staging Deployment section :250-258 documents manual `kubectl apply -k k8s/staging` only). Re-check after edits; if any develop→staging prose surfaces, rewrite it to the new gate (CI deploys staging from main when repo var DEPLOY_STAGING_ENABLED == 'true'). Otherwise add ONE sentence to the Staging Deployment section noting CI auto-deploys staging from main when `DEPLOY_STAGING_ENABLED` is set (replacing the old never-functional develop-branch gate).
  Do NOT change replicas, patches, labels, namespaces, or any other overlay field.
  </action>
  <verify>
  <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && \
  test -z "$(grep -rl 'ghcr.io/jtoye/' k8s/ 2>/dev/null)" && \
  ! grep -qi "push to develop" k8s/DEPLOYMENT.md && \
  grep -q "DEPLOY_STAGING_ENABLED" k8s/DEPLOYMENT.md && \
  kubectl kustomize k8s/staging >/dev/null && \
  kubectl kustomize k8s/production >/dev/null && \
  kubectl kustomize k8s/staging | grep -q "ghcr.io/bralabee/jtoye-core-java" && \
  kubectl kustomize k8s/production | grep -q "ghcr.io/bralabee/jtoye-core-java" && \
  chmod +x k8s/scripts/check-no-plaintext-secrets.sh k8s/scripts/check-connection-math.sh && \
  ./k8s/scripts/check-no-plaintext-secrets.sh && \
  ./k8s/scripts/check-connection-math.sh && \
  echo "K8S UNIFY OK"</automated>
  </verify>
  <done>No `ghcr.io/jtoye/` string anywhere under k8s/; base deployments + both overlays reference ghcr.io/bralabee/jtoye-<service>; staging newTag is immutable "2.1.0"; DEPLOYMENT.md documents the main+DEPLOY_STAGING_ENABLED staging gate (no develop-flow prose); both overlays build clean via kubectl kustomize; check-no-plaintext-secrets and check-connection-math both pass.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 3: Enable a working frontend ESLint flat-config gate + CHANGELOG (Decisions D & E)</name>
  <files>frontend/eslint.config.mjs, frontend/.eslintrc.json, frontend/package.json, frontend/tailwind.config.ts, frontend/app/shop/auth/callback/page.tsx, frontend/components/dashboard/mobile-tab-bar.tsx, frontend/components/dashboard/sidebar.tsx, frontend/components/storefront/storefront-nav.tsx, docs/CHANGELOG.md</files>
  <action>
  Per Decision D — resurrect linting (currently silently dead: `npm run lint`=`next lint` removed in Next 16; ESLint v9 flat-config-only with a legacy .eslintrc.json):
  1. Create frontend/eslint.config.mjs: spread the native flat-config arrays from `eslint-config-next/core-web-vitals` and `eslint-config-next/typescript` (import + spread the arrays directly — FlatCompat CRASHES with a circular-structure error, do NOT use it). Add a global `ignores`: [".next/**", "node_modules/**", "coverage/**", "playwright-report/**", "test-results/**", "next-env.d.ts", "public/**"]. Add a test-file override (files: ["**/__tests__/**", "**/*.test.*", "**/*.spec.*"]) turning OFF `@typescript-eslint/no-explicit-any` and `react-hooks/globals` (tests legitimately use any + harness globals). Add an override for ["jest.config.js"] turning OFF `@typescript-eslint/no-require-imports` (CJS file).
  2. DELETE frontend/.eslintrc.json (superseded — keeping both invites drift).
  3. frontend/package.json: set `"lint": "eslint ."`.
  4. Fix the remaining verified errors (0 errors required after):
     - tailwind.config.ts:75 — convert the require() to a top-level import.
     - app/shop/auth/callback/page.tsx:36 — replace `<a href="/shop">` with `<Link href="/shop">` importing Link from next/link (one element; reported twice).
     - The 4× react-hooks/set-state-in-effect at app/shop/auth/callback/page.tsx:17, components/dashboard/mobile-tab-bar.tsx:64, components/dashboard/sidebar.tsx:48, components/storefront/storefront-nav.tsx:28: add on the line above each offending setState `// eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up`. Do NOT refactor these components' behavior (theme/session hydration is behaviorally load-bearing).
  WORKTREE NOTE: the isolated worktree has NO node_modules — run `npm ci` in frontend/ BEFORE any eslint/build/jest gate.
  Per Decision E — add ONE docs/CHANGELOG.md [Unreleased] entry covering all of A–E, explicitly calling out (a) the latent full-sha-tag ImagePullBackOff bug and (b) the probe-401 SecurityConfig bug (pods could never go Ready under the k8s probe paths) that were fixed, and noting the metrics resync (Java @Test count grows by the probe regression tests; docs/metrics.json resynced via scripts/docs-freshness.sh --write in Task 1).
  </action>
  <verify>
  <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend && \
  test ! -f .eslintrc.json && \
  test -f eslint.config.mjs && \
  grep -q '"lint": "eslint ."' package.json && \
  npm ci && \
  npx eslint . 2>&1 | tee /tmp/eslint-out.txt; ESLINT_EXIT=${PIPESTATUS[0]}; \
  test "$ESLINT_EXIT" = "0" && ! grep -qiE "[1-9][0-9]* error" /tmp/eslint-out.txt && \
  npm run build && \
  npm test -- --ci --watchAll=false && \
  echo "LINT+BUILD+TEST OK"</automated>
  <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && \
  ./scripts/docs-freshness.sh && echo "metrics in sync (post Task-1 --write resync)" && \
  grep -q "Unreleased" docs/CHANGELOG.md && echo "CHANGELOG entry present"</automated>
  </verify>
  <done>frontend/eslint.config.mjs exists (native subpath arrays, no FlatCompat); .eslintrc.json deleted; package.json lint = `eslint .`; after `npm ci`, `npx eslint .` exits 0 with 0 errors; `npm run build` and `npm test` green; docs-freshness check-mode passes against the Task-1-resynced metrics.json; CHANGELOG [Unreleased] entry added covering A–E incl. the full-sha-tag bug, the probe-401 bug, and the metrics resync.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| GitHub Actions runner → external release host (github.com/kubernetes-sigs/kustomize) | CI downloads a binary that will run with cluster-deploy privileges |
| CI deploy job → Kubernetes cluster | kustomize-pinned image tags become running production/staging workloads |
| Unauthenticated network → /actuator/health/** | kubelet probes and smoke tests require anonymous access to health subpaths |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-pzi-01 | Tampering | kustomize binary download (deploy jobs) | mitigate | Pin KUSTOMIZE_VERSION + verify sha256 from the release checksums.txt via `sha256sum -c -` before use, mirroring the oasdiff step (ci-cd.yaml:245-252). |
| T-pzi-02 | Tampering | Mutable `newTag: staging` image ref | mitigate | Replace with immutable `newTag: "2.1.0"`; CI pins the exact full-sha tag at deploy time (Task 2 + Task 1) AND asserts the rendered overlay actually carries the sha before apply (premortem guard). |
| T-pzi-03 | Elevation of Privilege | deploy-staging firing on unintended refs | mitigate | Gate on `main && vars.DEPLOY_STAGING_ENABLED == 'true'` (off by default). |
| T-pzi-04 | Information Disclosure | Swagger/api-docs exposed in prod | mitigate (verify) | Smoke test asserts /swagger-ui.html and /v3/api-docs are NOT publicly reachable in prod (EXPECT_SWAGGER=false path). |
| T-pzi-05 | Information Disclosure | permitAll on /actuator/health/** | accept | Health group endpoints expose only aggregate status (show-details defaults keep details gated); anonymous access is REQUIRED for kubelet probes. Regression test pins the 200 contract; no detail leakage added. |
| T-pzi-SC | Tampering | npm/go/binary installs in CI | mitigate | No NEW npm/pip/cargo packages introduced (eslint-config-next already in package.json); only the kustomize binary is added, covered by T-pzi-01. No [ASSUMED]/[SUS] package installs → no legitimacy checkpoint required. |
</threat_model>

<verification>
- Task 1: `bash -n` + YAML parse clean; workflow grep assertions (sha tag, gated staging, kustomize apply + pre-apply render assertion, lint job key + npm run lint + gofmt/vet, no develop/no set-image, EXPECT_SWAGGER); SecurityConfig grep for /actuator/health/**; probe regression tests pass under `:core-java:integrationTest`; `docs-freshness.sh --write` then check-mode clean; both scratchpad stub-server smoke runs pass on ports 19090/19091 (prod-shape default, staging-shape EXPECT_SWAGGER=true).
- Task 2: `grep -rl 'ghcr.io/jtoye/' k8s/` empty; DEPLOYMENT.md documents the DEPLOY_STAGING_ENABLED gate with no develop-flow prose; `kubectl kustomize` builds both overlays; check-no-plaintext-secrets + check-connection-math pass.
- Task 3: after `npm ci`, `npx eslint .` exit 0 / 0 errors; `npm run build` + `npm test` green; docs-freshness check-mode passes (metrics resynced in Task 1); CHANGELOG entry present.
- Whole-repo sanity before commit: `git status` shows only the intended files (docs/metrics.json diff is EXPECTED — Java @Test count grows); the pre-existing k8s-validate + docs-freshness gates stay green.
</verification>

<success_criteria>
- The deploy pipeline is honest: no dead develop trigger, staging gated off by default, an immutable full-sha image tag is pushed and referenced, deploys use checksum-verified kustomize overlays with a pre-apply render assertion, and a real lint job gates frontend (`npm run lint` = `eslint .`) + edge-go (gofmt + go vet).
- Pods can actually go Ready: /actuator/health/** is permitAll'd so kubelet's unauthenticated probes get 200, pinned by a regression test; metrics resynced via docs-freshness --write.
- A healthy prod deployment passes smoke (probes 200, swagger not exposed) → no false rollback; staging asserts swagger reachable.
- k8s manifests reference the images CI actually pushes, with an immutable default tag, and DEPLOYMENT.md documents the new staging gate.
- `eslint .` runs at 0 errors, build + tests green, and the CHANGELOG records all of A–E incl. the latent ImagePullBackOff bug and the probe-401 bug.
</success_criteria>

<output>
Create `.planning/quick/260712-pzi-issue-99-do-now-remainder-ci-cd-deploy-h/260712-pzi-SUMMARY.md` when done.
</output>
