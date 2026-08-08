---
phase: 33-the-consumer-product
plan: 01
subsystem: database
tags: [geo, postcode, code-point-open, ogl, provenance, md5, awk, osgb36, wgs84, ci-gate]

requires:
  - phase: 33-the-consumer-product
    provides: "33-00's Q-1 answer (q1-commit) and the A1 licence confirmation (OGL v3, commercial, no share-alike)"
provides:
  - "core-java/src/main/resources/geo/postcode-centroids.csv.gz — 1,748,230 GB postcode centroids in WGS84, Null-Island filtered"
  - "scripts/regen-postcode-centroids.sh — the only sanctioned way the artefact is produced; md5-verified, fails closed"
  - "scripts/osgb36-to-wgs84.awk — inverse Transverse Mercator on Airy 1830 + 7-parameter Helmert"
  - "core-java/src/main/resources/geo/SOURCE.md — generated provenance, limitations and accuracy record"
  - "OGL attribution rendered in the public footer, gated in CI against the committed dataset"
affects: [33-02, 33-05, 33-06, 33-07]

tech-stack:
  added: []
  patterns:
    - "External data crosses into the repo only through an md5-verified, fail-closed regeneration script"
    - "A derived artefact is validated against an INDEPENDENT reference on every regeneration, not once at authoring time"
    - "Deterministic build output (gzip -n) so an unchanged dataset costs nothing in git history"

key-files:
  created:
    - scripts/regen-postcode-centroids.sh
    - scripts/osgb36-to-wgs84.awk
    - scripts/check-geo-attribution.sh
    - core-java/src/main/resources/geo/postcode-centroids.csv.gz
    - core-java/src/main/resources/geo/SOURCE.md
  modified:
    - frontend/components/public/public-footer.tsx
    - .github/workflows/docs-freshness.yml

key-decisions:
  - "The attribution year is a frozen constant, never new Date() — it identifies a dataset release, not the current year"
  - "gzip -n, because without it an identical dataset produced different bytes on every run and would have added ~15 MB to git history per regeneration"
  - "The awk transform reads the Code-Point CSV directly rather than through a two-stage pipe, keeping parsing and filtering in one auditable place"
  - "Transform accuracy is re-validated on every regeneration against api.postcodes.io and fails closed above 10 m"

patterns-established:
  - "Sample validation points from the EXTREMES of the coordinate system, not the centre, because the fit is weakest there"
  - "A generated provenance file is never hand-edited: a hand-edited provenance record is indistinguishable from a fabricated one"

requirements-completed: [CUST-01]

duration: 45min
completed: 2026-08-08
---

# Phase 33 Plan 01: The Postcode Dataset and Its Licence — Summary

**1,748,230 GB postcode centroids committed with provable provenance, a regeneration script proven to fail closed on a single corrupted byte, and the licence obligation rendered where a user can reach it and gated so it cannot rot.**

## Performance

- **Duration:** ~45 min
- **Completed:** 2026-08-08
- **Tasks:** 2 of 2 (both automated)
- **Files:** 5 created, 2 modified

## Every RESEARCH figure reproduced — re-measured, not copied

The plan said *"Re-verify; do not assume."* Every number did reproduce, which is worth recording precisely because it means the planning measurements were sound:

| | RESEARCH (planning) | Measured now |
|---|---|---|
| `codepo_gb.zip` bytes | 14,461,176 | **14,461,176** |
| md5 | `42ecd9a7db141608dc6ab63f2dfb0bc3` | **identical** |
| Upstream rows | 1,749,109 | **1,749,109** |
| Rows after filter | 1,748,230 | **1,748,230** |
| Null Island rows | 879 | **879** |
| Derived gzip | ~15.1 MB | **15,871,149 bytes** |
| Transform time | 12.0 s | 22.7 s (whole script incl. download-verify, unzip, sort, validation) |

Release `2026-08`, and the attribution year is read out of the archive's own `Doc/licence.txt` rather than inferred from the release string — it reads **2026**.

## Accomplishments

- **The Null-Island filter reads two columns**, as the plan insisted. All 879 sentinel rows carry `PQ=90` *and* `0,0`; the sentinel is in a different column from the coordinates, and a surviving row becomes the nearest shop to every customer on the platform. Zero survive.
- **The transform is validated against something outside itself**, on every regeneration — `api.postcodes.io` (ONSPD-derived WGS84), sampled Aberdeen → Isles of Scilly because the Helmert fit is weakest at the extremes and a central-England sample would flatter it. **n=10, mean 1.74 m, max 4.77 m** against a 10 m gate that fails closed. Two orders of magnitude below the ~100 m centroid error the product already accepts.
- **Fail-closed is demonstrated, not asserted.** One byte of the archive corrupted at offset 5,000,000 with the size left unchanged, so only the md5 can catch it → `rc=1`, `"No artefact written"`, and the artefact's md5 identical before and after.
- **The licence obligation renders and is gated.** All three rights holders in the footer, tied to the committed dataset by a CI-wired gate.

## Task Commits

1. **Task 1: Derive the dataset with verifiable provenance** — `bfa0836c` (feat)
2. **Task 2: Render the attribution and gate its year in CI** — `dc025e87` (feat)

## The defect found while verifying: the artefact was not reproducible

Running the script twice over **identical** input produced **different bytes** — `d51eb59d…` then `2b702661…`. gzip stores the input file's mtime in its header, and the input is a temp file with a fresh mtime each run.

This is not cosmetic. It would have added ~15 MB to git history on **every** regeneration even when the dataset had not changed — defeating the exact cost bound Q-1 was decided on, and doing it silently, since the diff would look like a legitimate data refresh.

Fixed with `gzip -9 -n`. Proven:

```
run 1 compressed md5   e1be48e911e3db3021321d6a41b622a0
run 2 compressed md5   e1be48e911e3db3021321d6a41b622a0   <- byte-identical
run 1/2 decompressed   3612b5da5529eb26f1bdbc5775c0a65b   (both)
```

## Falsification — every criterion run in the fail direction first

**Task 1**, bracketed clean → arms → clean. Arms built in scratch; the live artefact's md5 (`e1be48e9…`) unchanged throughout.

| Arm | Result | rc |
|---|---|---|
| clean | rows=1748230, null-island=0, gb-only=1, accuracy=6 | 0 |
| inject a `(0,0)` row | `FAIL: 1 Null-Island rows` | 1 |
| add a header row | `FAIL: first line is not a data row (header present?): 'postcode'` | 1 |
| strip the GB-only sentence from `SOURCE.md` | `FAIL: SOURCE.md states no GB-only limitation` | 1 |
| clean again | identical to the first arm | 0 |

**Regeneration script:**

| Arm | Result | rc |
|---|---|---|
| one corrupted byte, size unchanged | `md5 MISMATCH … No artefact written`, artefact md5 unchanged | 1 |
| unreachable product API | `VOID: product API unreachable` | **2** |
| transform file missing | `VOID: transform not found` | **2** |

**Task 2** — committed *before* the arms ran, because `git checkout` restores from the index and would discard post-staging edits. Restores verified by `git hash-object`, never by `git diff --stat`.

| Arm | Result | rc |
|---|---|---|
| clean first | gate passes | 0 |
| footer year → 2027 | `attribution year drift — the footer renders 2027 but the committed dataset is 2026` | 1 |
| delete the Royal Mail line | `does not name 1 required rights holder(s): Royal Mail data` — **a year-only check would have passed this** | 1 |
| move `SOURCE.md` aside | `VOID: dataset provenance file not found` | **2** |
| remove the CI step | `check-gate-enforcement.sh`: *"A gate that cannot fire on a pull request does not prevent anything"* | 1 |
| clean last | gate 0, enforcement 0, all three file hashes identical to baseline | 0 |

## The year is a constant, and that is the point

The footer already computes `new Date().getFullYear()` for the copyright line. The attribution year is **not** that number: it identifies the **dataset release** and must move only when the dataset is regenerated. Rendering `{year}` there would attribute a release that does not exist, and would have looked correct on the day it was written.

`GEO_ATTRIBUTION_YEAR` carries it as a frozen constant with a comment saying why it must not be derived, and the gate asserts it is actually **interpolated** at least four times (declaration + one per rights holder) — a declared-but-unrendered constant attributes nothing.

## Deviations from Plan

**[Rule 1 - Bug] The artefact was not byte-reproducible** — Found during: Task 1 verification | Issue: gzip stores the input mtime, so identical data produced different bytes across runs, which would inflate git history on every regeneration | Fix: `gzip -9 -n`, with the measured before/after md5s recorded in the script comment so the reason survives | Files: `scripts/regen-postcode-centroids.sh` | Verification: two runs, byte-identical | Commit: `bfa0836c`

**[Rule 2 - Missing] The VOID branch was unreachable in a test** — Found during: Task 1 arms | Issue: my first VOID arm set `PATH=/nonexistent`, which broke `bash` itself and returned rc=127 from the shell rather than 2 from the script — the arm proved nothing | Fix: made the product API endpoint injectable via `CODEPO_PRODUCT_API` (also correct per the no-hardcoded-environment-values rule), which makes the VOID branch genuinely reachable; re-ran and got rc=2 | Files: `scripts/regen-postcode-centroids.sh` | Verification: rc=2 on an unresolvable host, and rc=2 on a missing transform | Commit: `bfa0836c`

**[Rule 1 - Bug] A zero-width space was written into the script** — Found during: Task 1 | Issue: an edit introduced `U+200B` (`e2 80 8b`) at the start of a line; bash would have tried to execute it | Fix: stripped, then swept both new script files for `U+200B` (0 each) with an injected-fixture control proving the detector fires (1) | Files: `scripts/regen-postcode-centroids.sh` | Verification: `bash -n` clean, script runs | Commit: `bfa0836c`

**[Rule 3 - Minor] The transform reads the CSV directly** — the plan describes a two-stage `print $3" "$4" "$1` pipe into the transform, then `sort`. Implemented as a single awk over the raw CSV. Output contract is identical — `postcode,lat,lon`, no header, sorted, both filters applied — and it keeps parsing, filtering and projection in one auditable place rather than split across a pipe.

**Total deviations:** 4 (3 auto-fixed bugs, 1 minor implementation shape). **Impact:** the reproducibility fix is the significant one; it protects the cost bound the owner's Q-1 decision rests on.

## Verification Results

| Success criterion | Result |
|---|---|
| Artefact decompresses, >1.7M rows, zero (0,0) | **PASS** — `gzip -t` OK, 1,748,230 rows, 0 Null Island; also round-trips byte-identically **out of the git object store** after commit (`e1be48e9…` both sides) |
| Regen script fails closed on md5 mismatch, demonstrated | **PASS** — rc=1, no artefact written, artefact md5 unchanged |
| `SOURCE.md` states version, md5, exact row count, attribution year, GB-only, ~100 m | **PASS** — all six present; the file is generated and marked do-not-hand-edit |
| All three OGL lines render on the public storefront | **PASS** — gate confirms all three; 8/8 existing footer tests still pass, `npm run build` rc=0, 0 type errors |
| Gate passes, fails on year drift, fails on a missing line, exits 2 on missing input, wired in CI with enforcement green | **PASS** — all five arms above; `check-gate-enforcement.sh` PASS at 30 gates / 6 workflows / 4 declared exemptions |

## Issues Encountered

None blocking.

Two product limitations are now recorded in `SOURCE.md` rather than left to be discovered as bugs:

- **Great Britain only.** A Northern Ireland vendor will not geocode, keeps their storefront, and is **absent from distance-ranked results**. This is licence containment, not oversight — ONSPD would have been more convenient but its NI data needs a separate commercial licence from Land and Property Services, exactly the sixth commercial decision D-1 exists to avoid. 33-05 and 33-06 must not treat a NULL coordinate as an error.
- **~100 m centroid accuracy, not door-level.** Two shops on the same street resolve to the same point. Fine for ranking; not fine for directions or street-granularity fee banding.

## Next Plan Readiness

`33-02` is unblocked — it owns the schema and the Java surface over this artefact, and the exact post-filter row count its importer asserts against is **1,748,230**, stated in `SOURCE.md`.
