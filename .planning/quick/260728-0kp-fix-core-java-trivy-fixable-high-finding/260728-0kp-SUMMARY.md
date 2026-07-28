---
quick_id: 260728-0kp
slug: fix-core-java-trivy-fixable-high-finding
date: 2026-07-27
status: complete
branch: fix/trivy-core-java-highs
base: origin/main @ 4da6e0f
commits:
  - 9b0f601  fix(security): clear 7 fixable HIGH jar CVEs on the core-java image
  - cb6f7e5  fix(security): clear 5 fixable HIGH Alpine CVEs on the core-java image
  - 63edb25  docs(260728-0kp): quick-task plan for the core-java Trivy gate fix
files_modified:
  - core-java/build.gradle.kts
  - core-java/Dockerfile
---

# Summary: core-java Trivy image gate — 12 fixable HIGH → 0

## Outcome

The only red job on `main`'s `CI/CD Pipeline` — **`Build and Push Images (core-java)`** — was
failing at its post-push Trivy gate (`.github/workflows/ci-cd.yaml:721`). All findings are cleared.

**HANDOFF.md §4 recorded 2 findings. That was stale — there were 12.** The live job log and a local
reproduction agree. The handoff named only `libexpat` and `commons-beanutils`; it missed `p11-kit`,
`p11-kit-trust`, `netty-codec`, `netty-codec-http` and `jasperreports`. Anyone fixing only the two
named packages would have rebuilt, rescanned, and still been red.

## Both directions recorded

Same command, same flags as the CI gate (`--severity CRITICAL,HIGH --ignore-unfixed --exit-code 1`),
run via `aquasec/trivy:latest` against a real image:

| arm | image | rc | alpine | jar |
|---|---|---|---|---|
| **FAIL** (pre-fix) | `jtoye_oaas_2026-core-java:latest` | **1** | **5** | **7** |
| **PASS** (post-fix) | `trivy-verify-core-java:fixed` | **0** | **0** | **0** |

The fail arm was captured **before** any edit. The exit-0 is only meaningful because the identical
command was observed exiting 1 on the same tree first.

## Proven by content, not by verdict

Read out of the delivered image rather than trusting the scanner's summary — and out of *inside*
`app.jar`, since a filesystem `find` would return a misleading 0:

```
libexpat-2.8.2-r0        p11-kit-0.26.2-r0        p11-kit-trust-0.26.2-r0
jasperreports entries inside app.jar:     0
commons-beanutils entries inside app.jar: 0
netty-codec{,-dns,-http,-http2,-socks}-4.1.136.Final.jar   (none left at 4.1.135)
```

## What changed and why

**1. Removed `net.sf.jasperreports:jasperreports:6.21.3`** (`core-java/build.gradle.kts`)

Not a version bump — a deletion, because the dependency was never used:
- 0 imports across `core-java/src`
- 0 `.jrxml` / `.jasper` templates anywhere in the repo
- `docs/status/SYSTEMS_ENGINEERING_REVIEW.md:776` already flagged it as unused and recommended removal

`dependencyInsight` proved it was the **sole** source of `commons-beanutils` — by both paths (direct,
and via `commons-digester:2.1`). So one deletion cleared three CVEs (CVE-2025-48734, CVE-2025-10492,
CVE-2026-6009) and avoided dragging an unused library into JasperReports 7.x, which changes artifact
coordinates and licensing for no benefit. PDF generation here is OpenPDF, not JasperReports.

**2. Pinned netty to 4.1.136.Final** (`core-java/build.gradle.kts`)

Netty is not declared in the build; every artifact showed as `(selected by rule)`, pinned by
`io.spring.dependency-management` from Boot 3.5.16's BOM, arriving via `reactor-netty`
(starter-webflux) and `awssdk:netty-nio-client`. Setting `extra["netty.version"]` re-points the
imported `netty-bom`, so the whole family moves together — verified: 14 netty artifacts all at
4.1.136.Final, none left behind. Forcing only the two flagged artifacts would have left them out of
step with their siblings. Clears CVE-2026-59901, CVE-2026-55831, CVE-2026-55833, CVE-2026-56745.

**3. Targeted `apk upgrade` of `libexpat` + `p11-kit`/`p11-kit-trust`** (`core-java/Dockerfile`)

`eclipse-temurin:21-jre-alpine` ships Alpine 3.23 packages predating the fixes. Availability of
`2.8.2-r0` and `0.26.2-r0` in Alpine 3.23 `main` was **confirmed before** choosing this approach —
had they not been published, `apk upgrade` would have been a silent no-op that looked like a fix.
Packages are named rather than a blanket `apk upgrade`, which would move all ~85 packages on every
rebuild and make a future regression untraceable. Runtime stage only; Trivy scans the final image.
Clears CVE-2026-56131, CVE-2026-56407, CVE-2026-56408, CVE-2026-2100.

## Regression evidence

| check | result |
|---|---|
| `:core-java:cleanTest :core-java:test` | **BUILD SUCCESSFUL 47s — 114 classes / 820 tests / 0 fail / 0 err / 1 skip** |
| `:core-java:compileJava :compileTestJava` | BUILD SUCCESSFUL (34 pre-existing deprecation warnings, unchanged) |
| `scripts/docs-freshness.sh` | rc=0 — 1818 |
| `scripts/check-branch-behind-base.sh` | rc=0 — 3 ahead, 0 behind |

Counts read from `core-java/build-local/test-results/`, never `core-java/build/` (stale). `cleanTest`
included — without it the task reports `UP-TO-DATE`/`BUILD SUCCESSFUL` while executing zero tests.
114/820 is an exact match to the unit baseline recorded for 27-01, so nothing regressed.

## Not done here — deliberately

- **Integration tests (`:core-java:integrationTest`, ~40m)** were not run locally. Netty underpins
  the reactive HTTP client and the S3 async client, so this is worth real coverage — the PR's
  `Integration Tests (Testcontainers RLS)` job runs it. Stated rather than skipped silently.
- **The local compose stack was not rebuilt**, so `check-runtime-freshness.sh` will report `core-java`
  DRIFT until `docker compose -f docker-compose.full-stack.yml up -d --build core-java` runs. The
  verification image (`trivy-verify-core-java:fixed`) was a throwaway.
- **Issue #276** (`fail-fast: false` on the image matrix) is untouched — separate concern, still open.
- The **frontend** and **edge-go** images were already passing and were not scanned.

## Recurrence note

This is the `trap_trivy_daily_db_timebomb` pattern: no code change caused it. The daily vuln DB
ingested newly-fixable HIGHs and flipped a green job red. It will recur. The durable lesson from this
instance is narrower and worth carrying: **read the live job log for the finding list, never a
previously-written summary of it** — the handoff's count was 2 and the truth was 12.
