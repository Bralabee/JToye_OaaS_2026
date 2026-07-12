---
phase: quick-260712-pzi
plan: 01
subsystem: ci-cd / k8s-deploy / security / frontend-tooling
tags: [ci-cd, kustomize, actuator-probes, eslint, smoke-test, issue-99]
requirements: [ISSUE-99-DO-NOW-REMAINDER]
status: complete
requires:
  - "GitHub Actions runner with Docker (build-and-push), a Kubernetes cluster (deploy jobs — not exercised here; no cluster available)"
provides:
  - "Honest deploy pipeline: no dead develop trigger, gated staging, immutable full-sha image tag, checksum-verified kustomize-overlay deploys with pre-apply render assertion, a real lint job, prod-shape-safe smoke"
  - "permitAll /actuator/health/** so kubelet probes + smoke Tests 4/5 return 200 (pods can go Ready), pinned by a regression test"
  - "Working ESLint v9 flat-config gate (frontend eslint . at 0 errors) + edge-go gofmt/vet"
affects:
  - ".github/workflows/ci-cd.yaml deploy-staging / deploy-production / new lint job / build-and-push metadata-action"
  - "core-java SecurityConfig authorization matcher"
  - "k8s base deployments + staging/production overlays image refs"
tech-stack:
  added:
    - "kustomize v5.6.0 (pinned + sha256-verified in both deploy jobs)"
    - "ESLint v9 flat config (frontend/eslint.config.mjs)"
  patterns:
    - "checksum-verified pinned-binary install (mirrors the oasdiff step)"
    - "pre-apply kustomize render assertion (grep :<sha> on all three images before kubectl apply)"
    - "EXPECT_SWAGGER env switch for prod-vs-staging smoke shape"
key-files:
  created:
    - frontend/eslint.config.mjs
  modified:
    - .github/workflows/ci-cd.yaml
    - scripts/smoke-test.sh
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java
    - core-java/src/main/resources/application-dev.yml
    - docs/metrics.json
    - k8s/base/core-java-deployment.yaml
    - k8s/base/edge-go-deployment.yaml
    - k8s/base/frontend-deployment.yaml
    - k8s/staging/kustomization.yaml
    - k8s/production/kustomization.yaml
    - k8s/DEPLOYMENT.md
    - frontend/package.json
    - frontend/tailwind.config.ts
    - frontend/app/shop/auth/callback/page.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/storefront/storefront-nav.tsx
    - docs/CHANGELOG.md
  deleted:
    - frontend/.eslintrc.json
decisions:
  - "kustomize pinned to v5.6.0, linux_amd64 sha256 54e4031ddc4e7fc59e408da29e7c646e8e57b8088c51b84b3df0864f47b5148f — fetched from the official kustomize/v5.6.0 checksums.txt and proven locally before pinning"
  - "T-pzi-05 disposition = accept: /actuator/health/** permitAll is required for unauthenticated kubelet probes; health-group endpoints expose only aggregate status (show-details=when-authorized)"
metrics:
  duration: ~35min
  tasks: 3
  files: 19 (1 created, 17 modified, 1 deleted)
  completed: 2026-07-12
---

# Quick 260712-pzi: Issue #99 do-now remainder — CI/CD deploy honesty Summary

Closed six latent lies/bugs in the deploy half of the CI/CD pipeline so future deploys don't `ImagePullBackOff`, pods actually go Ready, healthy prod releases don't auto-roll-back, and frontend regressions are caught — all committed atomically across three tasks. Deploy jobs remain unexercised end-to-end (no cluster available); the agreed proof standard (checksum-verified binary + stub-server smoke + Testcontainers regression + kustomize render) is met.

## What shipped

### Task 1 — Honest deploy workflow + probe-401 fix + prod-safe smoke (commit `2132062`)
- **ci-cd.yaml**: dropped the dead `develop` push/PR trigger; added the immutable full-sha image tag (`type=raw,value=${{ github.sha }}`) to `docker/metadata-action`; gated `deploy-staging` on `main && vars.DEPLOY_STAGING_ENABLED == 'true'` (mirrors the production `DEPLOY_ENABLED` gate); replaced both jobs' `kubectl set image` steps with **checksum-verified kustomize v5.6.0** installs + `kustomize edit set image` → **pre-apply render assertion** (grep `:<sha>` on all three jtoye images, fail loud on a name-key mismatch) → `kubectl apply -k`; staging keeps rollout/smoke(+`EXPECT_SWAGGER=true`)/rollback, production keeps rollout/health-check/smoke/Slack/rollback; added a new **`lint` job** (frontend `eslint .` + edge-go `gofmt` offender-listing check + `go vet ./...`).
- **SecurityConfig.java**: added `/actuator/health/**` to the `permitAll` matcher (was exact-path only) with a rationale comment — kubelet's unauthenticated liveness/readiness probes and the smoke tests hit those subpaths.
- **SecurityHeadersIntegrationTest.java**: +2 regression tests (unauthenticated liveness + readiness → 200), probes enabled via `@SpringBootTest(properties = "management.endpoint.health.probes.enabled=true")`.
- **smoke-test.sh**: Tests 4/5 now assert liveness/readiness → 200; swagger checks made `EXPECT_SWAGGER`-conditional (prod default asserts swagger NOT publicly exposed — the fix that stops false rollbacks of good prod releases).
- **application-dev.yml**: added a `management` block enabling health probes for dev-stack parity.
- **docs/metrics.json**: resynced via `docs-freshness.sh --write` (Java `@Test` 849 → 851; total logical invocations 1185).

### Task 2 — Unify k8s image names + immutable staging tag (commit `1897ba2`)
- Base deployments + both overlays now reference `ghcr.io/bralabee/jtoye-<service>` (what CI actually pushes; precedent `pg-backup-cronjob.yaml`); no `ghcr.io/jtoye/` string remains under `k8s/`.
- Staging `newTag` changed from the **mutable** `staging` to the immutable `"2.1.0"` (CI pins the exact full-sha at deploy time).
- `DEPLOYMENT.md`: unified documented image names + a sentence documenting the new `DEPLOY_STAGING_ENABLED` staging gate (no develop-flow prose existed to remove).

### Task 3 — Working ESLint flat-config gate + CHANGELOG (commit `a58f3fd`)
- **frontend/eslint.config.mjs**: ESLint v9 flat config spreading the native `eslint-config-next/core-web-vitals` (4 entries) + `/typescript` (5 entries) arrays directly (verified both are arrays; FlatCompat avoided — it crashes circular-structure), plus global `ignores`, a test-file override, and a `jest.config.js` override.
- Deleted the legacy `.eslintrc.json`; `package.json` `lint` = `eslint .` (was the removed-in-Next-16 `next lint`).
- 0-error fixes: `tailwind.config.ts` `require()` → top-level import; `<a href="/shop">` → `next/link` `<Link>`; four SSR-safe mount-time theme/session hydrations annotated `eslint-disable-next-line react-hooks/set-state-in-effect` (no behavior change).
- `docs/CHANGELOG.md`: `[Unreleased]` entry covering A–E incl. the latent full-sha-tag `ImagePullBackOff` bug, the probe-401 `SecurityConfig` bug, and the metrics resync.

## Verification (one line per gate)

| Gate | Verdict |
|------|---------|
| Task 1 static (bash -n, workflow greps, SecurityConfig `/actuator/health/**`) | PASS (all greps; sha-tag confirmed via `grep -F` — the plan's BRE grep is a GNU `${{ }}` brace quirk, content present at ci-cd.yaml:407) |
| actionlint ci-cd.yaml | PASS (clean) |
| Task 1 probe regression + metrics | PASS — `:core-java:integrationTest --tests "uk.jtoye.core.security.*"` BUILD SUCCESSFUL 5m41s; SecurityHeadersIntegrationTest 6/6 (was 4, +2), full security suite 34/34; docs-freshness --write then check-mode exit 0 (1185) |
| Task 1 stub-server smoke (ports 19090/19091) | PASS — prod-shape default 10/10 exit 0; staging-shape `EXPECT_SWAGGER=true` 10/10 exit 0 |
| Task 2 k8s unify | PASS — no `ghcr.io/jtoye/` remains; `kubectl kustomize` staging+production build clean rendering `ghcr.io/bralabee/jtoye-*:2.1.0`; check-no-plaintext-secrets + check-connection-math exit 0; DEPLOYMENT.md documents DEPLOY_STAGING_ENABLED |
| Task 3 eslint/build/test | PASS — `npx eslint .` exit 0 / **0 errors** (24 warnings, all pre-existing/out-of-scope); `npm run build` compiled successfully; `npm test` 32 suites / 226 tests exit 0; docs-freshness check-mode exit 0; CHANGELOG Unreleased present |

## Deviations from Plan

**None affecting scope.** The plan's Task-1 static-verify grep for the full-sha tag (`grep -q "type=raw,value=\${{ github.sha }}"`) returns non-zero on GNU grep because `${{ … }}` is parsed as a BRE interval expression — a quirk of the grep pattern, not the content. The tag is genuinely present at `.github/workflows/ci-cd.yaml:407` (proven by `grep -F` and by a brace-escaped BRE). No file or content change was needed; the honest verdict uses `grep -F` as the literal-presence test. One incidental fix: the initial `deploy-staging` explanatory comment literally contained the string `refs/heads/develop`, which tripped the plan's `! grep develop` gate — reworded the comment (no functional change).

## Environment notes for the reviewer / next agent
- kustomize pin proven locally: v5.6.0, linux_amd64 sha256 `54e4031ddc4e7fc59e408da29e7c646e8e57b8088c51b84b3df0864f47b5148f` (from the official `kustomize/v5.6.0` `checksums.txt`).
- Deploy jobs (`kubectl apply -k`) are NOT exercised — no reachable cluster. The pre-apply render assertion is the loud guard that catches an images-transformer name-key mismatch before anything reaches a cluster; `kubectl kustomize` proves the overlays render the unified names locally.
- gitleaks pre-commit did not flag anything (the kustomize sha256 hex is fine, matching the oasdiff precedent).
- Stub servers were written to `/home/sanmi/.claude/jobs/d4f4d0a4/tmp` (uncommitted, killed after use).

## Known Stubs
None. All changes are wired end-to-end; the only "stub" artifacts (Node HTTP smoke stubs) live in an uncommitted job-tmp dir and are verification tooling, not shipped code.

## Self-Check: PASSED
- Files created/modified all exist on disk (confirmed).
- Commits `2132062`, `1897ba2`, `a58f3fd` present in `git log 822d11f..HEAD`.
- Working tree clean; only intentional deletion is `frontend/.eslintrc.json`.
