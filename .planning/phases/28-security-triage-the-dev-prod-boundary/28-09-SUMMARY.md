---
phase: 28-security-triage-the-dev-prod-boundary
plan: 09
subsystem: object-storage / supply-chain (MinIO bootstrap)
tags: [security, minio, supply-chain, digest-pin, bucket-policy, SEC-04, "#270", "#626"]
requires:
  - the running full-stack MinIO (jtoye-minio) with the jtoye-images bucket seeded (768 objects)
  - scripts/check-media-content-types.sh (28-03) — the credentialed enumerator that must survive this change
  - scripts/check-image-supply-chain.sh (#276) — must stay green under the pin
provides:
  - a digest-pinned minio/mc bootstrap whose output names the image it ran, and a wrong-digest loud failure
  - a GetObject-only anonymous bucket policy — anonymous reads by URL preserved, anonymous enumeration closed
  - the precondition D-06's deferred prefix-scoped quarantine depends on (anonymous s3:ListBucket removed)
affects:
  - plan 28-03's deferred sweep (docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md §5 blocker cleared)
  - any future minio/mc upgrade (must re-resolve the digest; recipe recorded in .env.example)
tech-stack:
  added: []
  patterns:
    - image pinned by name:tag@sha256 through the existing MINIO_MC_IMAGE_TAG indirection
    - anonymous policy as least-privilege set-json (GetObject-only), not the two-privilege download preset
    - exec-list entrypoint so an env-var JSON policy expands under one /bin/sh with no nested-quote/glob hazard
    - every acceptance criterion run FAIL-direction first; before/after measured on the same request and key
key-files:
  created:
    - .planning/phases/28-security-triage-the-dev-prod-boundary/28-09-SUMMARY.md
  modified:
    - docker-compose.full-stack.yml
    - .env.example
    - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
decisions:
  - "Pinned minio/mc by DIGEST via name:tag@sha256 (keeps the human-readable release AND the immutable digest on one reviewable .env line); the :-latest fallback fires only on an EMPTY var, never on a wrong one"
  - "RESEARCH assumption A6 SETTLED: mc anonymous set-json is the mechanism — the built-in download preset bundles s3:ListBucket and has no GetObject-only form; no explicit Deny needed, the ListBucket statement is simply omitted"
  - "Converted the entrypoint from a folded-string (double-sh) to exec-list (single sh): the policy JSON contains * which the double-sh form would glob-expand and re-parse; exec-list expands it safely from one env var"
  - "Pinned ONLY minio/mc, not minio/minio — check-image-supply-chain.sh does not require it and the plan scoped the second pin conditionally on that gate; recorded as a residual rather than expanding scope"
  - "core-java deliberately NOT rebuilt — the only core-java touch is a comment-only javadoc correction; 28-08's measured image ID is unchanged"
metrics:
  duration: ~45 min
  completed: 2026-08-10
  tasks: 2
  commits: 2
requirements: [SEC-04]
---

# Phase 28 Plan 09: MinIO Bootstrap — Digest Pin + GetObject-only Anonymous Policy Summary

Digest-pinned the `minio/mc` bootstrap that holds root object-storage credentials (#270) and
replaced its two-privilege `mc anonymous set download` with a `set-json` GetObject-only policy
(#626), so a storefront image stays anonymously readable by URL while the 768-key object
inventory is no longer anonymously enumerable — both proven in both directions against the
running stack, and both issues closed with the measurements.

## What was built

| Task | Artifact | Commit |
|---|---|---|
| 1 | `docker-compose.full-stack.yml` minio-init digest pin + image-ref/`mc --version` echo; `.env.example` pin | `49b45e2f` |
| 2 | `docker-compose.full-stack.yml` GetObject-only `set-json` policy (exec-list entrypoint); `MediaProperties.java` javadoc correction | `48969b0f` |

The pinned reference (recorded as the bootstrap ran it):
`minio/mc:RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727`

## Task 1 — digest pin, measured both directions

- **compose config** rc=0; the resolved `image:` and echoed `MINIO_MC_IMAGE_REF` are the `name:tag@sha256` reference.
- **`.env.example`** `@sha256:` count **0 -> 1** (git grep on the tracked file). The value line carries no trailing comment (detector `^MINIO_MC_IMAGE_TAG=.*[[:space:]]#`: real=0, deliberately-malformed copy=1 — proven able to fire).
- **Bootstrap on the pin** (force-recreate minio-init): exit 0, output NAMES what it ran:
  `minio-init-image-ref=minio/mc:RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349...` and
  `mc version RELEASE.2025-08-13T08-35-41Z`.
- **FAIL ARM — wrong digest** (`@sha256:0000…0000`): `up` rc=**1**, `failed to resolve reference "docker.io/minio/mc@sha256:0000…": not found`. It attempted the digest and failed loudly; it did **not** fall back to `latest`. Restored to the correct pin (exit 0), re-verified.
- **FAIL ARM — required credential**: with `MINIO_ROOT_PASSWORD` empty, `config` rc=**1**, `required variable MINIO_ROOT_PASSWORD is missing a value: MINIO_ROOT_PASSWORD must be set`. Restored.
- **`scripts/check-image-supply-chain.sh`** rc=0 (before and after — the gate asserts the Trivy/dependabot/freshness workflow shape and is orthogonal to the image pin; it was green before this plan too).
- **core-java image ID unchanged**: `sha256:01c182bc7aa1c668685226580d7a840891dfc6825b77d04c9cdb49ddf14214f7` (LastTagTime `2026-08-10 05:09:14Z`, plan 28-08's rebuild) — recorded at start, unchanged at end.

## Task 2 — GetObject-only anonymous policy, measured both directions on the SAME request/key

| Check | Before | After |
|---|---|---|
| Effective bucket policy (read back from the RUNNING minio) | `s3:ListBucket`+`s3:GetBucketLocation` on bucket ARN **and** `s3:GetObject` on `/*` | `s3:GetObject` on `/*` **only** |
| Anonymous LIST (unauthenticated S3 v2 list) | **HTTP 200, KeyCount 768** | **HTTP 403 AccessDenied** |
| Anonymous GET, same key `<tenant-uuid>/media/<uuid>_thumb.webp` | **HTTP 200, 9786 bytes** | **HTTP 200, 9786 bytes** |
| Credentialed `check-media-content-types.sh` | rc=0, 768 objects | rc=0, 768 objects |

- **Read-back is proven to reflect state, not a constant**: the effective policy differs before vs after (ListBucket present -> absent), and a mid-plan dry-run additionally flipped it GetObject-only -> back to `download` and the anonymous LIST followed 403 -> 200, so the instrument tracks state in both directions.
- **Mechanism shipped**: `mc anonymous set-json` reading an inline env-var policy (`ANON_BUCKET_POLICY`). RESEARCH assumption **A6 is settled** — `set-json` is correct; the `download` preset bundles `ListBucket` and has no GetObject-only form (confirmed live against `mc` RELEASE.2025-08-13 and MinIO docs via Context7). No explicit `Deny` was needed; the `ListBucket` statement is simply omitted.
- **Idempotent**: minio-init recreated three times, exit 0 each, effective policy read back byte-identical each time; anonymous LIST stayed 403 throughout.
- **Regression-by-omission guard held**: the anonymous GET on a real storefront object returned 200 with an identical body before and after — no existing test fetches a storefront image anonymously from MinIO, so this was measured by hand, per the plan.

## Deviations from Plan

### 1. [Rule 3 - Blocking] Local `.env` pin added by inline env, not by editing `.env` (hook-blocked, correctly)
- **Found during:** Task 1.
- **Issue:** compose reads `.env` (not `.env.example`) at runtime, so the live pin recreate needs `MINIO_MC_IMAGE_TAG` in `.env`. The secret-path hook blocked editing `.env` — the correct behaviour for a secret file, and not rerouted around.
- **Fix:** passed `MINIO_MC_IMAGE_TAG=<pin>` inline on every validation `docker compose` invocation (shell env overrides `.env`), which proved the pinned digest runs. The committed source of truth is `.env.example`.
- **Residual (recorded):** a developer's machine-local `.env` must carry `MINIO_MC_IMAGE_TAG` copied from `.env.example`; the value is non-secret, so it can be added by hand. Until then compose falls back to `:latest` for that developer.

### 2. [Rule 2 - Strictly stronger] Entrypoint converted from folded-string to exec-list form
- **Found during:** Task 2.
- **Issue:** the GetObject-only policy JSON contains `*` (in `["*"]` and `/*`). Under the existing `entrypoint: > /bin/sh -c "…"` folded/double-sh form, a variable-expanded `*` is glob-expanded and the JSON's own double-quotes are re-parsed by the second shell — a corruption path that would break anonymous GET (a storefront regression).
- **Fix:** rewrote the entrypoint as an exec-list (`["/bin/sh","-c", <block>]`, `set -e`), a single shell, so the policy expands from one env var (`ANON_BUCKET_POLICY`) inside its own double-quotes with no glob and no re-parse. Proven live: exit 0, exact policy applied, idempotent.

### 3. [Rule 1 - Corrected a now-false statement] `MediaProperties.java` javadoc
- **Found during:** Task 2 (explicitly listed in the task read_first).
- **Issue:** the javadoc said the `jtoye-images` bucket is `mc anonymous set download` — false after this change.
- **Fix:** corrected the mechanism phrase to `s3:GetObject` (GetObject-only since #626), while PRESERVING the still-true, still-load-bearing consequence (a quarantine object is anonymously readable BY KEY) and noting that is why D-06 still needs a prefix-scoped policy. Comment-only; **core-java deliberately NOT rebuilt** (zero runtime effect; 28-08's image ID unchanged; the change is absorbed at the next core-java rebuild / phase close-out).

### 4. [Scope note] Pinned only `minio/mc`, not `minio/minio`
- The plan pinned `minio/minio` conditionally — "if `check-image-supply-chain.sh` expects it". It does not (that gate asserts the Trivy/dependabot/freshness workflow shape, nothing about these images). So the second pin was not taken, per the plan's own condition. `minio/minio` currently runs `RELEASE.2025-09-07T16-13-09Z@sha256:14cea493…`; pinning it is a low-risk future follow-up but out of this plan's scope.

## Threat model dispositions

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-28-44 Tampering (supply chain) — `minio/mc:latest` with root creds | **mitigated** | digest pin; bootstrap echoes the resolved ref + `mc --version`; wrong-digest arm rc=1 "not found", no `latest` fallback |
| T-28-45 Information Disclosure — anonymous `s3:ListBucket` | **mitigated** | anonymous LIST 200/768 -> 403; effective policy read back GetObject-only |
| T-28-46 Information Disclosure — quarantine prefix assumed private by name | **precondition cleared** | anonymous enumeration removed; D-06's prefix-scoped policy is now achievable (MEDIA-BACKFILL-PLAN §5 blocker cleared). Read-by-key still open by design — recorded |
| T-28-47 DoS (regression by omission) — storefront images 404/403 after the change | **mitigated** | anonymous GET on the SAME real key 200/9786 bytes before AND after |
| T-28-48 Spoofing — bootstrap credentials defaulted | **mitigated** | `${VAR:?}` retained; empty-password arm refuses to start, names the key |
| T-28-49 Repudiation — policy asserted from compose, not the running service | **mitigated** | effective policy read back from the running MinIO; read-back proven to differ before vs after |

Cross-cutting: web-perf **relevant and honoured** — the anonymous-GET arm proves LCP storefront
assets still load; SEO **N/A** (no page markup); agent-readiness **N/A** (no API surface). Falsifiability:
two Task-1 break arms (wrong digest, empty password) + a falsifiable trailing-comment detector, and a
before/after pair on both anonymous requests plus a dry-run that flipped state both ways.

## Residuals (recorded, not silently dropped)

- **Root bootstrap identity (#270 residual):** `mc anonymous`/admin ops require admin authority, so the bootstrap stays root and on the shared network; the pin + required-credential are the supply-chain half this issue centred on. Recorded on the closed issue.
- **Anonymous read-by-key remains (by design):** a known object key is still anonymously GETtable — that is the point of a public storefront origin — so D-06 still needs a prefix-scoped policy; key obscurity is not a substitute now that enumeration is closed.
- **`minio/minio` unpinned:** see Deviation 4.
- **Local `.env`:** see Deviation 1.

## Known Stubs

None. No UI surface, no data path, no placeholder values — a compose bootstrap change and one comment correction.

## Issues closed

- **#626** — CLOSED (completed), with the before/after LIST + GET measurements and the read-back policy, sanitized (no credential values, no object-key literals, no `list-type`/`max-keys`/host strings — verified on the stored comment: all 6 leak tokens 0, control `s3:GetObject` 3). `closes #626` also in commit `48969b0f`.
- **#270** — CLOSED (completed), with the pin acceptance + the root/network residual. `closes #270` also in commit `49b45e2f`.

## Self-Check: PASSED

- `docker-compose.full-stack.yml` — modified, tracked at HEAD (both commits)
- `.env.example` — modified, tracked at HEAD (`49b45e2f`), `@sha256:` present
- `core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java` — modified, tracked at HEAD (`48969b0f`)
- `.planning/phases/28-security-triage-the-dev-prod-boundary/28-09-SUMMARY.md` — created
- Commit `49b45e2f` (Task 1) — present
- Commit `48969b0f` (Task 2) — present
- Running stack in the delivered state: anonymous LIST 403, anonymous GET 200, effective policy GetObject-only
- Issues #626 and #270 — CLOSED
