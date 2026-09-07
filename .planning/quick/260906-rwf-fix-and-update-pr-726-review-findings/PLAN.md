# Fix and update PR #726

GSD quick task initialized with `gsd-sdk query init.quick` on 2026-09-06.
Source: review https://github.com/Bralabee/JToye_OaaS_2026/pull/726#pullrequestreview-5126296661.
Work in isolated `feature/fix-pr-726` worktree; preserve the original checkout's local changes.

## Execution

1. Reconcile PR branch with origin/main, retaining #733's Tomcat security pin and the PR's updated architecture documentation.
2. Frontend: preserve checkout keys across unsent edit/undo; remove logout GET session mutation; validate delivery addresses conditionally and show all errors; reject unsafe normalized return URLs. Add failing regressions before fixes.
3. Core: explicit deleted-order replay response, checked aggregate quantity arithmetic, isolated optional directory refresh, authorization before webhook replay, shop-bound checkout fingerprints and legacy replay ownership checks. Add failing regressions, real PostgreSQL tests for transactional and tenancy behavior.
4. Deployment/tooling: preserve logout preflight advisory behavior under Actions errexit with flag/probe matrix coverage; repair function-type generic test counting with negative/control fixtures.
5. Run focused suites, regenerate metrics via the repository script, check prose and operational gates. Obtain independent tenancy/security review. Rebuild all containers before any browser E2E; document any runtime constraints honestly.
6. Commit fixes on the feature branch, push a normal fast-forward update to the existing PR head branch, and post evidence plus remaining gates. Do not merge PR #726 or bypass checks.

## Ownership

- Frontend worker: `frontend/**` only.
- Core worker: `core-java/**` only, excluding `build.gradle.kts`.
- Parent: merge, `.github/**`, `scripts/**`, documentation/metrics, final integration and PR update.
- Independent reviewer: read-only core tenancy/authorization changes after authors finish.

## Verification

- Record regressions failing before correction and passing afterward.
- Exercise checkout edit/undo, logout non-mutation, hidden collection fields, malicious return URLs, deleted/foreign-shop replay, aggregate overflow, SQL failure isolation, and cached webhook authorization.
- Exercise deployment flag true/false against probe exit 0/1/2 under `bash -e -o pipefail`.
- Ensure test counter recognizes callback types rather than silently returning zero.
- Do not infer browser or Stripe verification from unit tests; real Stripe is test-mode only.