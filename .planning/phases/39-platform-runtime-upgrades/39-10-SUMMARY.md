---
phase: 39-platform-runtime-upgrades
plan: 10
subsystem: dependencies, build, ci, frontend, core-java, edge-go, blobctl
tags: [dependabot, stripe, azure-sdk, postgresql, nextjs, framer-motion, radix-ui, playwright, prometheus]
requires: []
provides:
  - "Consolidated Dependabot roll-up closing all 9 open PRs (#905, #907, #909, #910, #928, #929, #930, #931, #932)"
  - "Paired major upgrade of @stripe/react-stripe-js (^7.0.0) and @stripe/stripe-js (^10.0.0) resolving ERESOLVE peer-dependency failure"
  - "Lockstep doc-version and doc-citation sync across .planning/codebase/STACK.md, CLAUDE.md, AGENTS.md, and docs/architecture/ESSENTIAL_ARCHITECTURE.md"
affects: [core-java, frontend, edge-go, infra/backups/blobctl, .github/workflows/ci-cd.yaml]
tech-stack:
  added: []
  patterns:
    - "Consolidated Dependabot roll-up with check-doc-versions.sh and check-doc-citations.sh lockstep updates"
key-files:
  created:
    - .planning/phases/39-platform-runtime-upgrades/39-10-PLAN.md
    - .planning/phases/39-platform-runtime-upgrades/39-10-SUMMARY.md
  modified:
    - core-java/build.gradle.kts
    - edge-go/go.mod
    - edge-go/go.sum
    - infra/backups/blobctl/go.mod
    - infra/backups/blobctl/go.sum
    - .github/workflows/ci-cd.yaml
    - frontend/package.json
    - frontend/package-lock.json
    - .planning/codebase/STACK.md
    - CLAUDE.md
    - AGENTS.md
    - docs/architecture/ESSENTIAL_ARCHITECTURE.md
    - .planning/phases/39-platform-runtime-upgrades/39-DEPENDABOT-TRIAGE.md
    - .planning/ROADMAP.md
key-decisions:
  - "Combine #909 (@stripe/react-stripe-js 6.12.0 -> 7.0.0) and #910 (@stripe/stripe-js 9.17.0 -> 10.0.0) into one lockfile update because @stripe/react-stripe-js@7.0.0 requires peer @stripe/stripe-js@>=10.0.0 <11.0.0."
  - "Update all 34 version claims across CLAUDE.md, AGENTS.md, .planning/codebase/STACK.md, and docs/architecture/ESSENTIAL_ARCHITECTURE.md in the same commit so check-doc-versions.sh and check-doc-citations.sh pass."
requirements-completed: [39-G]
completed: 2026-10-16
---

# Phase 39 Plan 10: Consolidated Dependabot Roll-Up Summary

**Consolidated all 9 open Dependabot PRs (`#905`, `#907`, `#909`, `#910`, `#928`, `#929`, `#930`, `#931`, `#932`) across Gradle (`core-java`), Go (`edge-go`, `blobctl`), GitHub Actions (`ci-cd.yaml`), and npm (`frontend`) with paired Stripe JS v7/v10 resolution and lockstep doc-version/doc-citation updates.**

## Accomplishments

- **Backend Gradle Updates (`#905`, `#930`):**
  - `com.stripe:stripe-java`: `33.4.2 -> 34.0.0` (`#905`)
  - `com.azure:azure-storage-blob`: `12.35.1 -> 12.36.0` (`#930`)
  - `com.azure:azure-identity`: `1.18.6 -> 1.18.7` (`#930`, JNA exclusion verified via `WorkloadIdentityCredentialBuildTest`)
  - `com.twelvemonkeys.imageio:imageio-webp` & `imageio-core`: `3.15.2 -> 3.15.3` (`#930`)
  - `org.postgresql:postgresql`: `42.7.13 -> 42.7.14` (`#930`)
- **Go Module Updates (`#928`, `#929`):**
  - `edge-go`: `github.com/prometheus/client_golang` `v1.24.1 -> v1.25.0` (`#928`)
  - `infra/backups/blobctl`: `github.com/Azure/azure-sdk-for-go/sdk/azcore` `v1.23.2 -> v1.23.3` and `sdk/storage/azblob` `v1.8.1 -> v1.8.2` (`#929`)
- **GitHub Actions Updates (`#931`):**
  - `.github/workflows/ci-cd.yaml`: `azure/setup-kubectl` `v5.1.0 -> v5.2.0` (`bda439f5f36b99b262ebdcd3613869bddb064c8e`) and `github/codeql-action/upload-sarif` `v4.38.2 -> v4.38.3`
- **Frontend npm Updates (`#932`, `#909`, `#910`, `#907`):**
  - 19 minor/patch updates from `#932` (`next ^16.4.0`, `eslint-config-next ^16.4.0`, `@playwright/test ^1.64.0`, `axe-core 4.14.0`, `lucide-react ^1.56.0`, `react-hook-form ^7.90.0`, `zod ^4.6.7`, `@stomp/stompjs ^7.4.0`, `@radix-ui/*`, `@testing-library/user-event ^14.6.8`, `@types/node 26.6.5`)
  - Coupled major bump of `@stripe/react-stripe-js` (`^6.12.0 -> ^7.0.0`, `#909`) and `@stripe/stripe-js` (`^9.17.0 -> ^10.0.0`, `#910`)
  - `framer-motion` (`^13.4.6 -> ^14.0.0`, `#907`)
- **Doc-Version & Citation Lockstep:**
  - Observed `scripts/check-doc-versions.sh` failing (`rc=1`, 34 stale version claims) and `scripts/check-doc-citations.sh` failing (`rc=1`, `C-3` on `.planning/codebase/STACK.md:73`) before updating the docs, then updated `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`, and `docs/architecture/ESSENTIAL_ARCHITECTURE.md` and verified both gates pass (`rc=0`, 153 version claims and 46 verified citations).
