# Phase 39 (Track 39-G / Lane 3): Open Dependabot PRs Triage & Execution Plan

**Measured:** 2026-10-16 via `gh pr list` / `gh api` against `Bralabee/JToye_OaaS_2026`
**Total Open Dependabot PRs Consolidated in Plan 39-10:** 9 (`#905`, `#907`, `#909`, `#910`, `#928`, `#929`, `#930`, `#931`, `#932`; noting that `#930` superseded `#904`, `#931` superseded `#908`, `#929` superseded `#911`, and `#932` superseded `#915`)

---

## 1. Executive Root-Cause Diagnosis

Every one of the 9 open Dependabot PRs is RED in CI for **structural repository-gate reasons** that Dependabot cannot satisfy on its own:

1. **`review-record` Gate (`9/9` PRs failing):**
   - `.github/workflows/review-record.yml` requires a committed, SHA-matched review record for every PR branch. Unattended Dependabot branches never carry one.
2. **`docs-freshness` / `scripts/check-doc-versions.sh` & `scripts/check-doc-citations.sh` Gates (`#905`, `#907`, `#930`, `#932`, plus `#909`/`#910` once installed):**
   - [`scripts/check-doc-versions.sh`](../../../scripts/check-doc-versions.sh) and [`scripts/check-doc-citations.sh`](../../../scripts/check-doc-citations.sh) enforce exact version-string parity between `core-java/build.gradle.kts` / `frontend/package.json` and the prose tables in [`CLAUDE.md`](../../../CLAUDE.md), [`AGENTS.md`](../../../AGENTS.md), [`.planning/codebase/STACK.md`](../../../.planning/codebase/STACK.md), and [`docs/architecture/ESSENTIAL_ARCHITECTURE.md`](../../../docs/architecture/ESSENTIAL_ARCHITECTURE.md).
   - Measured RED: `check-doc-versions.sh` flagged 34 stale version claims across `CLAUDE.md` (11), `AGENTS.md` (11), `.planning/codebase/STACK.md` (11), and `docs/architecture/ESSENTIAL_ARCHITECTURE.md` (1), and `check-doc-citations.sh` failed `C-3` on `.planning/codebase/STACK.md:73` (`PostgreSQL JDBC Driver 42.7.13` vs `42.7.14`).
3. **Split Peer-Dependency Major Bumps (`2/9` PRs failing at `npm ci`: `#909` & `#910`):**
   - `#909` bumps `@stripe/react-stripe-js` `6.12.0 → 7.0.0` alone; `#910` bumps `@stripe/stripe-js` `9.17.0 → 10.0.0` alone.
   - `@stripe/react-stripe-js@7.0.0` declares a peerDependency on `@stripe/stripe-js@>=10.0.0 <11.0.0`. Running `npm ci` on either PR alone fails at Step 14 (`Install frontend dependencies`). They **must** be combined into a single commit/PR.

---

## 2. Per-PR Inventory & Verification Matrix

| PR | Title / Package Bump | Ecosystem | CI Failures Observed | `check-doc-versions.sh` / `check-doc-citations.sh` Impact | Disposition |
|---|---|---|---|---|---|
| [`#905`](https://github.com/Bralabee/JToye_OaaS_2026/pull/905) | `com.stripe:stripe-java` `33.4.2 → 34.0.0` | Gradle (`/core-java`) | `docs-freshness`, `Security Scan` (stale base), `review-record` (Unit + Testcontainers RLS **PASSED**) | `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`: `Stripe Java SDK 33.4.2` → `34.0.0` | **MERGED in 39-10** |
| [`#930`](https://github.com/Bralabee/JToye_OaaS_2026/pull/930) | `minor-and-patch` in `/core-java` (5 updates: `azure-storage-blob` `12.35.1 → 12.36.0`, `azure-identity` `1.18.6 → 1.18.7`, `twelvemonkeys imageio-webp/core` `3.15.2 → 3.15.3`, `postgresql` `42.7.13 → 42.7.14`; supersedes `#904`) | Gradle (`/core-java`) | `docs-freshness`, `Operational Contracts` (`C-3` citation on `STACK.md:73`), `review-record` (Unit + Integration Tests **PASSED**) | `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`: `PostgreSQL JDBC Driver 42.7.14`, `Azure Storage Blob SDK 12.36.0`, `Azure Identity 1.18.7`, `TwelveMonkeys ImageIO 3.15.3` | **MERGED in 39-10** |
| [`#928`](https://github.com/Bralabee/JToye_OaaS_2026/pull/928) | `github.com/prometheus/client_golang` `v1.24.1 → v1.25.0` | Go (`/edge-go`) | `review-record` (All tests & contract gates **PASSED**) | `.planning/codebase/STACK.md`: `prometheus/client_golang v1.25.0` | **MERGED in 39-10** |
| [`#929`](https://github.com/Bralabee/JToye_OaaS_2026/pull/929) | `minor-and-patch` in `/infra/backups/blobctl` (2 updates: `azcore` `v1.23.2 → v1.23.3`, `azblob` `v1.8.1 → v1.8.2`; supersedes `#911`) | Go (`/infra/backups/blobctl`) | `review-record` (All tests & contract gates **PASSED**) | None | **MERGED in 39-10** |
| [`#931`](https://github.com/Bralabee/JToye_OaaS_2026/pull/931) | `minor-and-patch` in `/` (2 updates: `azure/setup-kubectl` `v5.1.0 → v5.2.0`, `github/codeql-action` `v4.38.2 → v4.38.3`; supersedes `#908`) | GitHub Actions (`.github/workflows/ci-cd.yaml`) | `review-record` (All tests & contract gates **PASSED**) | None | **MERGED in 39-10** |
| [`#932`](https://github.com/Bralabee/JToye_OaaS_2026/pull/932) | `minor-and-patch` in `/frontend` (19 updates: `@radix-ui/*`, `@stomp/stompjs` `7.3.0 → 7.4.0`, `lucide-react` `1.48.0 → 1.56.0`, `next` `16.3.8 → 16.4.0`, `react-hook-form` `7.89.0 → 7.90.0`, `zod` `4.6.5 → 4.6.7`, `@playwright/test` `1.63.0 → 1.64.0`, `@testing-library/user-event` `14.6.7 → 14.6.8`, `@types/node` `26.6.3 → 26.6.5`, `axe-core` `4.13.0 → 4.14.0`, `eslint-config-next` `16.3.8 → 16.4.0`; supersedes `#915`) | npm (`/frontend`) | `docs-freshness`, `review-record` (`Run Tests`, `Frontend E2E`, `Security Scan`, `Lint` **ALL PASSED**) | `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md`: `Next.js 16.4.0`, `React Hook Form 7.90.0`, `Zod 4.6.7`, `@playwright/test 1.64.0`, `axe-core 4.14.0`, `@stomp/stompjs 7.4.0`, `eslint-config-next@16.4.0` | **MERGED in 39-10** |
| [`#909`](https://github.com/Bralabee/JToye_OaaS_2026/pull/909) + [`#910`](https://github.com/Bralabee/JToye_OaaS_2026/pull/910) | `@stripe/react-stripe-js` `6.12.0 → 7.0.0` (`#909`) **AND** `@stripe/stripe-js` `9.17.0 → 10.0.0` (`#910`) | npm (`/frontend`) | `Run Tests` (Step 14 `npm ci` `ERESOLVE`), `Frontend E2E`, `Lint`, `Security Scan`, `review-record` | `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`: `Stripe React/JS 7.0.0, 10.0.0` | **COMBINED & MERGED in 39-10** |
| [`#907`](https://github.com/Bralabee/JToye_OaaS_2026/pull/907) | `framer-motion` `13.4.6 → 14.0.0` | npm (`/frontend`) | `docs-freshness`, `review-record` (`Run Tests`, `Frontend E2E`, `Security Scan`, `Lint` **ALL PASSED**) | `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`: `Framer Motion 14.0.0` | **MERGED in 39-10** |

---

## 3. Executed Roll-Up Strategy (`39-10-PLAN.md`)

All 9 open Dependabot PRs were consolidated into `feature/phase-39-10-dependabot-rollup` with lockstep doc-version and doc-citation updates so `Closes #905, Closes #907, Closes #909, Closes #910, Closes #928, Closes #929, Closes #930, Closes #931, Closes #932` closes all 9 PRs upon merge.
