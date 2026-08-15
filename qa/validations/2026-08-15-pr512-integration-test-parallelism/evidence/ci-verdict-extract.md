# CI-side evidence extract (all from Bralabee/JToye_OaaS_2026 Actions history via gh, account Bralabee)

## Sampling design
- Event filter: **push runs only** — the suite step's condition is
  `github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'`
  (ci-cd.yaml:218), so push runs ALWAYS execute the suite: the path-filter confound is absent.
- BEFORE: 14 successful main-push runs 2026-08-01T19:38Z .. 2026-08-03T18:02Z (pre-merge).
- AFTER: 14 successful push runs 2026-08-03T21:04Z .. 2026-08-04T18:20Z; the first,
  run 30853076827, has head_sha = d95239dc (the merge push itself).
- All 28 sampled Integration-Tests jobs concluded success with the suite step executed
  (verified per-row in ci-job-timings.jsonl; no skipped suite steps in the sample).

## Durations ("Run Testcontainers integration suite" step, minutes)
| window | n | min | median | mean | max |
|--------|---|-----|--------|------|-----|
| BEFORE | 14 | 40.3 | 46.8 | 46.4 | 50.3 |
| AFTER  | 14 | 30.2 | 31.8 | 32.1 | 38.9 |

- Distributions do not overlap except one AFTER outlier (38.9, run 30853704049) still below the
  BEFORE minimum (40.3). Median delta: **-15.0 min (-32%)**.
- Whole-job medians: 47.0 → 32.2 min.

## Drift control (test population at window boundary SHAs, git grep -l 'Tag("testcontainers")')
| SHA | when | tagged files |
|-----|------|--------------|
| ace6604b (BEFORE first) | 08-01 | 105 |
| 97b701f8 (BEFORE last)  | 08-03 | 114 |
| d95239dc (AFTER first)  | 08-03 | 114 |
| 909c5568 (AFTER last)   | 08-04 | 116 |
- The AFTER window carries an EQUAL-OR-LARGER test population than the BEFORE window;
  drift direction works AGAINST the speedup, so the -32% understates the per-test gain.

## Serial-before proof (inertness context)
- `git show 97b701f8:core-java/build.gradle.kts | grep maxParallelForks` → **no match (rc=1)**:
  before #512 the task had no maxParallelForks setting at all → Gradle default **1** (serial).
  (The inert `/4` divisor was an earlier UNPUSHED attempt per the commit message; what main ran
  before #512 was plain serial.)

## Post-merge job log (run 30853076827, job 91817570155 — the d95239dc merge push)
- Runner shape step output (log lines 220-222):
  `nproc            : 4`
  `memory           : 15Gi total, 14Gi available`
  `disk (workspace) : 88G available`
- doFirst line (log line 376):
  `integrationTest: availableProcessors=4, maxParallelForks=2, forkEvery=4`
- Arithmetic check: (4 / 2) = 2, coerceIn(1,4) = **2** — matches the printed value. The divisor
  is ACTIVE on CI (the very defect #512 fixed): 4-core runner moved 1 → 2 forks.
- Suite Gradle summary: `BUILD SUCCESSFUL in 31m 57s` (log line 21880) — consistent with the
  32.0-min step timing for this run.
- Full log archived: evidence/ci-after-job-30853076827.log (4.0 MB).

## Files
- ci-runs-raw.jsonl        — 1000 completed ci-cd runs 2026-06-20..08-15 (id/event/created/branch/conclusion)
- ci-job-timings.jsonl     — 28 sampled Integration-Tests jobs (run_id/job_id/steps/head_sha, pinned)
- ci-job-durations.json    — computed per-run durations + window stats (jq-derived)
- ci-after-job-30853076827.log — full post-merge job log
