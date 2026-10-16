# Phase 39 (Track 39-G / Lane 3): Open Dependabot PRs Triage & Execution Plan

**Measured:** 2026-10-16 via `gh pr list` / `gh api` against `Bralabee/JToye_OaaS_2026`
**Total Open Dependabot PRs:** 8 (`#904`, `#905`, `#907`, `#908`, `#909`, `#910`, `#911`, `#915`)

---

## 1. Executive Root-Cause Diagnosis

Every one of the 8 open Dependabot PRs is currently RED in CI for **structural repository-gate reasons** that Dependabot cannot satisfy on its own:

1. **`review-record` Gate (`8/8` PRs failing):**
   - `.github/workflows/review-record.yml` requires a committed, SHA-matched review record for every PR branch. Unattended Dependabot branches never carry one.
2. **`docs-freshness` / `scripts/check-doc-versions.sh` Gate (`5/8` PRs failing: `#904`, `#905`, `#907`, `#915`, plus `#909`/`#910` once installed):**
   - [`scripts/check-doc-versions.sh`](../../../scripts/check-doc-versions.sh) enforces exact version-string parity between `core-java/build.gradle.kts` / `frontend/package.json` and the prose tables in [`CLAUDE.md`](../../../CLAUDE.md), [`AGENTS.md`](../../../AGENTS.md), and [`README.md`](../../../README.md).
   - Whenever Dependabot bumps a dependency whose version is cited in those docs (`azure-identity`, `stripe-java`, `framer-motion`, `@stripe/react-stripe-js`, `@stripe/stripe-js`, `next`, `@playwright/test`), `docs-freshness` fails `rc=1` because Dependabot only edits the manifest file.
3. **Split Peer-Dependency Major Bumps (`2/8` PRs failing at `npm ci`: `#909` & `#910`):**
   - `#909` bumps `@stripe/react-stripe-js` `6.12.0 → 7.0.0` alone; `#910` bumps `@stripe/stripe-js` `9.17.0 → 10.0.0` alone.
   - `@stripe/react-stripe-js@7.0.0` declares a peerDependency on `@stripe/stripe-js@^10.0.0`. Running `npm ci` on either PR alone fails at Step 14 (`Install frontend dependencies`). They **must** be combined into a single commit/PR.
4. **Stale Base Branch / Trivy Gate (`6/8` PRs failing `Security Scan`: `#904`, `#905`, `#908`, `#909`, `#910`, `#911`):**
   - Those 6 PRs were branched on `2026-10-09` before `origin/main` received the subsequent security floor updates; Step 6 (`Trivy gate — fail build on fixable CRITICAL/HIGH`) failed on the `2026-10-09` base and `Branch Not Behind Base` will also block them until rebased or rolled into a fresh branch off `origin/main`.

---

## 2. Per-PR Inventory & Verification Matrix

| PR | Title / Package Bump | Ecosystem | CI Failures Observed | `check-doc-versions.sh` Prose Impact | Disposition Recommendation |
|---|---|---|---|---|---|
| [`#904`](https://github.com/Bralabee/JToye_OaaS_2026/pull/904) | `com.azure:azure-identity` `1.18.6 → 1.18.7` | Gradle (`/core-java`) | `docs-freshness`, `Security Scan` (stale base), `review-record` (Unit + Testcontainers RLS **PASSED**) | `CLAUDE.md` & `AGENTS.md`: `Azure Identity (1.18.6)` → `(1.18.7)` | **ACCEPT in Batch 1** (patch bump; tests already green in CI) |
| [`#905`](https://github.com/Bralabee/JToye_OaaS_2026/pull/905) | `com.stripe:stripe-java` `33.4.2 → 34.0.0` | Gradle (`/core-java`) | `docs-freshness`, `Security Scan` (stale base), `review-record` (Unit + Testcontainers RLS **PASSED**) | `CLAUDE.md` & `AGENTS.md`: `Stripe Java SDK 33.4.2` → `34.0.0` | **ACCEPT in Batch 1** (major bump already proven compiling & passing all 2,815 Java unit + IT tests in CI run `37952261225`) |
| [`#908`](https://github.com/Bralabee/JToye_OaaS_2026/pull/908) | `azure/setup-kubectl` `5.1.0 → 5.2.0` | GitHub Actions (`.github/workflows/ci-cd.yaml`) | `Security Scan` (stale base), `review-record` (`docs-freshness` & `Operational Contracts` **PASSED**) | None ( check `H-5` line numbers in `infra/dependency-horizons.yaml` after edit) | **ACCEPT in Batch 1** (safe CI action minor bump) |
| [`#911`](https://github.com/Bralabee/JToye_OaaS_2026/pull/911) | `github.com/Azure/azure-sdk-for-go/sdk/storage/azblob` `1.8.1 → 1.8.2` | Go (`/infra/backups/blobctl`) | `Security Scan` (stale base), `review-record` (`Run Tests` & `Lint` **PASSED**) | None | **ACCEPT in Batch 1** (patch bump in `blobctl`; Go tests green) |
| [`#915`](https://github.com/Bralabee/JToye_OaaS_2026/pull/915) | `minor-and-patch` group across `/frontend` (15 updates: `@radix-ui/*`, `lucide-react` `1.48.0 → 1.53.0`, `next` `16.3.8 → 16.4.0`, `eslint-config-next` `16.3.8 → 16.4.0`, `@playwright/test` `1.63.0 → 1.64.0`, `@types/node` `26.6.3 → 26.6.4`, `axe-core` `4.13.0 → 4.14.0`) | npm (`/frontend`) | `docs-freshness`, `review-record` (`Run Tests`, `Frontend E2E`, `Security Scan`, `Lint` **ALL PASSED**) | `CLAUDE.md`, `AGENTS.md`, `README.md`: `Next.js 16.3.8` → `16.4.0`, `@playwright/test 1.63.0` → `1.64.0` | **ACCEPT in Batch 2** (all Jest, E2E, Lint, and Trivy checks already green on `#915`! Note: `axe-core 4.14.0` also relaxes `scrollable-region-focusable` on negative tabindex, relevant to Phase 37-52 deferred item) |
| [`#909`](https://github.com/Bralabee/JToye_OaaS_2026/pull/909) + [`#910`](https://github.com/Bralabee/JToye_OaaS_2026/pull/910) | `@stripe/react-stripe-js` `6.12.0 → 7.0.0` (`#909`) **AND** `@stripe/stripe-js` `9.17.0 → 10.0.0` (`#910`) | npm (`/frontend`) | `Run Tests` (Step 14 `npm ci` `ERESOLVE`), `Frontend E2E`, `Lint`, `Security Scan`, `review-record` | `CLAUDE.md` & `AGENTS.md`: `Stripe React/JS 6.12.0, 9.17.0` → `7.0.0, 10.0.0` | **COMBINE in Batch 3** (`feature/deps-stripe-js-v10-v7`): upgrade both packages in one `package.json` / `package-lock.json` commit, update `CLAUDE.md`/`AGENTS.md`, verify `npm test` + `npm run build` |
| [`#907`](https://github.com/Bralabee/JToye_OaaS_2026/pull/907) | `framer-motion` `13.4.6 → 14.0.0` | npm (`/frontend`) | `docs-freshness`, `review-record` (`Run Tests`, `Frontend E2E`, `Security Scan`, `Lint` **ALL PASSED**) | `CLAUDE.md` & `AGENTS.md`: `Framer Motion 13.4.6` → `14.0.0` | **ACCEPT in Batch 3** (all unit, E2E, and lint checks already passed in CI; requires only `CLAUDE.md`/`AGENTS.md` version sync + motion spot-check) |

---

## 3. Recommended Roll-Up Strategy (Zero Duplicate CI Wait)

Rather than rebasing and running full 45-minute CI + review-record cycles 8 separate times (and watching `#909` and `#910` fail `npm ci` forever), execute **one consolidated dependency roll-up branch** (`feature/phase-39-10-dependabot-rollup` off `origin/main`) in an isolated Antigravity worktree after Phase 37 merges (so `CLAUDE.md` and `AGENTS.md` do not conflict with Phase 37's pre-PR `docs/metrics.json` regeneration):

1. **Step 1 (Backend + CI + Go):** Apply `#904` (`azure-identity 1.18.7`), `#905` (`stripe-java 34.0.0`), `#908` (`azure/setup-kubectl 5.2.0`), and `#911` (`azblob 1.8.2`).
2. **Step 2 (Frontend minor/patch + coupled Stripe JS + Framer Motion 14):** Apply `#915`, `#909` + `#910` together, and `#907`.
3. **Step 3 (Doc-version & horizon lockstep):** Update the version prose in `CLAUDE.md`, `AGENTS.md`, and `README.md`, then verify:
   - `bash scripts/check-doc-versions.sh` (`rc=0`)
   - `bash scripts/check-dependency-horizons.sh` (`rc=0`)
   - `./gradlew :core-java:test` & `cd edge-go && go test ./...` & `cd infra/backups/blobctl && go test ./...` & `cd frontend && npm test` (`rc=0`).
4. **Step 4:** Open a single PR with `Closes #904, Closes #905, Closes #907, Closes #908, Closes #909, Closes #910, Closes #911, Closes #915` so GitHub automatically closes all 8 Dependabot PRs upon merge.
