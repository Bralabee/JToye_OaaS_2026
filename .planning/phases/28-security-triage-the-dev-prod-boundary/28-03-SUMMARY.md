---
phase: 28-security-triage-the-dev-prod-boundary
plan: 03
subsystem: media / object-storage security
tags: [security, media, minio, gate, deferred-plan, sanitized-issue, SEC-02]
requires:
  - MediaNormalizer magic-byte allowlist (#445/#479)
  - V60 quarantine_expires_at / quarantine_reclaimed_at columns
  - scripts/check-gate-enforcement.sh default-deny registration
provides:
  - permanent gate holding stored Content-Types inside the upload allowlist
  - the dated D-06/D-07/D-08 specification for the deferred full-catalogue sweep
  - issue #626 (anonymous s3:ListBucket) for plan 28-05's triage doc and plan 28-09's fix
affects:
  - plan 28-05 (triage doc records #626)
  - plan 28-09 (bucket-policy fix; must not break the credentialed gate)
  - plan 28-11 (manifest owner — deliberately untouched here)
tech-stack:
  added: []
  patterns:
    - relation-with-a-denominator gate (never a census)
    - fail-closed VOID on missing input, empty listing, or unparseable output
    - allowlist parsed at runtime from the code that enforces it
    - credentialed enumeration chosen for forward-compatibility with 28-09
key-files:
  created:
    - scripts/check-media-content-types.sh
    - docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md
  modified:
    - scripts/gates/gate-enforcement.conf
decisions:
  - "#488's urgent limb is closed by MEASUREMENT (0 of 768), not by a re-pipeline run over an empty input set"
  - "#488's GDPR/EXIF framing is corrected: 0 of 37 legacy objects carry EXIF/GPS, so the deferred residual is Core Web Vitals over 37 objects, not data protection"
  - "The gate enumerates credentially although anonymous listing currently works, so plan 28-09's fix does not turn it VOID"
  - "mc is reached via docker exec into the MinIO container rather than a one-shot minio/mc container, because a compose network name is derived from the project DIRECTORY name"
  - "The allowlist is parsed at runtime from MediaNormalizer.LEGACY_SYNC_INPUT_TYPES (the wider of its two sets) rather than hardcoded"
metrics:
  duration: ~35 min
  completed: 2026-08-10
  tasks: 2
  commits: 2
---

# Phase 28 Plan 03: Media Backfill Measurement + Deferred Sweep Plan Summary

Measured #488's population against the live object store (0 of 768 outside the allowlist,
0 of 37 legacy objects carrying EXIF — both with controls), replaced the scheduled
re-pipeline with a permanent relation-shaped gate proven to fail three ways, wrote the dated
D-06/D-07/D-08 specification for the deferred sweep, and filed the newly-found anonymous
`s3:ListBucket` exposure as sanitized issue **#626**.

## What was built

| Task | Artifact | Commit |
|---|---|---|
| 1 | `scripts/check-media-content-types.sh` + its `gate-enforcement.conf` entry | `1f742bfc` |
| 2 | `docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md` + issue #626 | `544b3404` |

## The three censuses, with their controls

Measured **2026-08-10 02:13Z**, credentialed, against the live `jtoye-images` bucket.
**Denominator: 768 objects** (prefix census: `media` 731, `products` 33, `shops` 4).
No truncating filter anywhere in the enumeration path.

### Census 1 — stored Content-Type

| Value | Objects |
|---|---|
| `image/webp` | 731 |
| `image/jpeg` | 35 |
| `image/png` | 2 |
| **Total** | **768** |

**Outside the allowlist: 0 of 768.**
**Control:** one object stored as `text/html` made the same probe report **1 of 769**, naming the
key; deleting it returned **0 of 768**. The probe can report a non-allowlist type.

### Census 2 — EXIF / GPS on legacy objects

**0 of 37** non-WebP (non-pipeline) objects carry any `[EXIF]` or `[GPS]` tag.
**Control:** the identical `exiftool -q -s -G` census over a copy of one of those same objects
with GPS injected reported **8 `[EXIF]`/`[GPS]` tag lines** — `GPSVersionID`, `GPSLatitudeRef`,
`GPSLongitudeRef`, and `[Composite]` `GPSPosition`. The instrument reports tags when tags exist,
so the 0 is a fact about the objects.

### Census 3 — quarantine prefix

**0** objects under a `*/quarantine/*` prefix.

## Gate break arms — all three run, real rc values

Bracketed **clean → break → observe → restore → clean again**; the closing arm is the only thing
that proves the restore happened.

| Arm | Break | rc observed | Expected | Restore verified |
|---|---|---|---|---|
| Opening clean | none | **0** (0 of 768) | 0 | — |
| FD1 invariant | one object stored as `text/html` | **1**, key NAMED, 1 of 769 | 1 | object deleted, `mc stat` rc=1 → gate **0**, 0 of 768 |
| FD2 fail-closed | `docker stop jtoye-minio` | **2** (VOID) | 2, never 0 | `docker start` → healthy → gate **0** |
| FD3 denominator | `MEDIA_BUCKET=no-such-bucket-xyz` | **2** (VOID) | 2, never 0 | parameter only |
| Closing clean | none | **0** (0 of 768) | 0 | — |

**Registration fail direction:** with the script present but no conf entry,
`check-gate-enforcement.sh` exited **1**, naming `check-media-content-types.sh` as unwired
(default-deny works). After the entry it exits **0**.

**Gate count after registration:** `check-gate-enforcement.sh` reports
**34 gates · 6 workflows · 6 exemptions declared · rc=0** (exemptions were 5 before this plan).

## Source assertions on the gate, each with a positive control

| Claim | Count | Control |
|---|---|---|
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` referenced | 4 / 4 | — |
| `MC_HOST_mediagate` alias referenced | 3 | — |
| no `list-type=2` anonymous URL | 0 | `mc stat` = 1 |
| no `curl` (no HTTP path at all) | 0 | `mc stat` = 1 |
| literal credential VALUES absent | 0 / 0 | `jtoye-images` = 1 |
| `\| grep -q` in **code** | 0 | `docker exec` = 4 |
| `\| head` in **code** | 0 | `\|\| true` = 2 |

The raw counts for `| grep -q` and `| head` were each **1**, both on header comment lines 86 and
92 — the script's own prohibition text. That is the recorded "a rule must name the token it
forbids" shape; code-only counts (full-line comments stripped) are 0, with positive controls.

## Issue #626 — the DEC-5 exposure, for plan 28-05's triage doc

**#626 — OPEN** — "Public image bucket grants anonymous s3:ListBucket, so the whole object
inventory is enumerable without a credential."

Re-measured live before filing: anonymous LIST returned **HTTP 200 with `KeyCount` 768**, and the
declared policy grants `s3:GetBucketLocation` + `s3:ListBucket` to Principal `AWS: ["*"]` on the
bucket ARN alongside `s3:GetObject` on `/*`. Anonymous GET of a key returned 200.

Body written via `--body-file` (never an interpolating double-quoted string) and verified **as
stored** with `gh issue view --json body`:

| Leakage grep | Count | Control (must match) |
|---|---|---|
| `list-type=2` | 0 | `s3:ListBucket` = 2 |
| `MINIO_ROOT` | 0 | `jtoye-images` = 1 |
| `localhost:9000` | 0 | `#270` = 1 |
| `max-keys` | 0 | `check-media-content-types.sh` = 1 |
| `.webp` (object-key shape) | 0 | — |
| tenant-uuid key prefix | 0 | — |
| literal credential values | 0 / 0 | — |

`## Impact`, `## Fix`, `## Acceptance` all present as stored.

## Deviations from Plan

### 1. [Rule 3 - Blocking] `mc` reached via `docker exec`, not a one-shot `minio/mc` container

- **Found during:** Task 1
- **Issue:** The plan specified running `minio/mc` as a one-shot container **on the compose
  network**. That network's name is derived from the project **directory** name — measured here as
  `jtoye_oaas_2026_jtoye-network` — which is the recorded trap that makes runtime-freshness and
  config-drift gates unrunnable from a worktree. A gate naming it would break from a worktree and
  from any renamed checkout.
- **Fix:** `docker exec` into the running MinIO container, which ships `mc` at `/usr/bin/mc`
  (verified `RELEASE.2025-08-13`). Same credentialed client, no network-name dependency, no image
  pull. VOIDs with a named reason if `mc` is ever absent from the image.
- **Why it satisfies the criterion:** the acceptance criterion allows "an equivalent credentialed
  alias". Credentialed-ness — not the transport — is the property plan 28-09 depends on.
- **Recorded in:** the script header, not made silently.

### 2. [Rule 2 - Strictly stronger] Allowlist parsed at runtime instead of named in a comment

- **Found during:** Task 1
- **Issue:** The plan asked for the allowlist to be read out of `MediaNormalizer` and named in a
  comment "so a future divergence is visible". Visible is weaker than caught.
- **Fix:** the script parses `MediaNormalizer.LEGACY_SYNC_INPUT_TYPES` at runtime and enforces the
  parsed set, printing it beside the documented set and flagging any disagreement. It VOIDs if the
  parse yields nothing or loses `image/webp` — without that anchor a broken parse would red the
  entire bucket and read as a catastrophic finding rather than a broken gate.
- **Choice of set:** `LEGACY_SYNC_INPUT_TYPES` (jpeg/png/webp/**gif**) rather than
  `STRICT_INPUT_TYPES` (jpeg/png/webp), because it is the **wider** of the normaliser's two sets —
  the widest thing the upload path will ever admit. A narrower allowlist could red on an object the
  application legitimately accepted; the wider one still catches every non-image type
  (`text/html`, `image/svg+xml`, `application/octet-stream`) that makes a public origin dangerous.

### 3. [Rule 1 - Instrument defect, caught and fixed] My own self-check inverted on success

- **Found during:** self-check
- **Issue:** `git log --oneline --all | grep -qF "$h"` under `set -o pipefail` reported **MISSING**
  for both commits that `git log` was simultaneously printing. `grep -q` exits at the first match,
  `git log` takes SIGPIPE, pipefail promotes it to 141, and the `&&` branch fails — so the check
  **inverts on the success case**.
- **Fix:** re-run with a here-string (`grep -cF "$h" <<< "$LOG"`), plus a `deadbeef` control proving
  the corrected check can still report absence. Both commits FOUND.
- **Note:** this is the exact shape the gate's own header forbids; it appeared in the verification
  scaffolding rather than in the delivered gate, which contains no such construct (0 in code, with
  controls).

## Threat model dispositions

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-28-09 stored XSS via Content-Type | **mitigated** | 0 of 768 measured; `check-media-content-types.sh` holds it, proven to red on `text/html` |
| T-28-10 anonymous `s3:ListBucket` | **filed, UNMITIGATED TODAY** | #626 open; fix in plan 28-09; recorded as unmitigated in §8 of the dated plan |
| T-28-11 EXIF/GPS on legacy objects | **accept, time-boxed** | 0 of 37 with a positive control; residual reclassified CWV not GDPR; carried with a target |
| T-28-12 the gate failing OPEN | **mitigated** | FD2 (stopped stack) and FD3 (missing bucket) both rc=2, proven by break arm |

Cross-cutting: web-perf **deferred with a number** (37 non-WebP objects); SEO **N/A**;
agent-readiness **N/A** (no API surface change); falsifiability — 3 gate break arms, 1 registration
break arm, 1 EXIF positive control, and every sanitization grep paired with a matching control.

## Known Stubs

None. No UI surface, no data path, no placeholder values.

## Notes for downstream plans

- **Plan 28-05** — record **#626** in `docs/security/PENTEST-TRIAGE.md`.
- **Plan 28-09** — the bucket-policy fix must keep a **credentialed** listing working;
  `scripts/check-media-content-types.sh` must still exit 0 afterwards. Removing anonymous
  `s3:ListBucket` unblocks D-06's prefix-scoped quarantine, which is the deferred sweep's
  precondition.
- **Plan 28-11** — `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md` deliberately
  untouched here (`git diff --name-only` was empty at Task 2). The new gate is bash, which
  `docs-freshness.sh` does not count, so it contributes **0** to the test-count manifest.

## Self-Check: PASSED

- `scripts/check-media-content-types.sh` — FOUND, tracked at HEAD
- `docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md` — FOUND, tracked at HEAD
- `scripts/gates/gate-enforcement.conf` — FOUND, registration present (count 1)
- Commit `1f742bfc` — FOUND (verified with a here-string after the piped form inverted; `deadbeef` control = 0)
- Commit `544b3404` — FOUND (same)
- Working tree clean; issue **#626 OPEN**
