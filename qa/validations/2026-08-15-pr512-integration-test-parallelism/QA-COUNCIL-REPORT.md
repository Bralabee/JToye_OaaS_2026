# QA Council — Discovery Report (run 20260815-173801)

Scope: validate PR #512 (`d95239dc`, 2026-08-03) — `integrationTest` maxParallelForks divisor + CI Runner-shape logging.
Mode: **AUDIT** (discovery only; no product-code changes made; nothing committed or filed).

## 1. Pre-flight & baseline
See `baseline.md`, `contract.md`, `oracles.md`. Highlights: JDK 21.0.6 verified pre-Gradle; 16-core box, Docker up with the 16-container compose dev stack as recorded load context; live Gradle output dir proven to be `core-java/build-local/` (`build.gradle.kts:15`; `core-java/build/` mtime 2025-12-27 = stale, never read); arms run strictly sequentially via `probes/run-arm.sh` (armed to fail: `probes/run-arm.FAIL-DIRECTION.txt`); `gh` as Bralabee (owner). Roles N/A by scope: browser/UX, a11y, SEO, web-perf.

## 2. Verdicts per question

### GAINS — local: **REPRODUCED, slightly stronger than claimed**
| arm | wall (Gradle) | epoch bracket | doFirst line | tests |
|-----|--------------|---------------|--------------|-------|
| serial `-PitMaxParallelForks=1` | 50m 7s = 3007s | 3007s (17:41:22→18:31:29) | `availableProcessors=16, maxParallelForks=1, forkEvery=4` (`arm-serial.log:9`) | 562/1 skip/0 fail |
| parallel (default) | 18m 32s = 1112s | 1113s (19:19:23→19:37:56) | `availableProcessors=16, maxParallelForks=4, forkEvery=4` (`arm-parallel.log:9`) | 562/1 skip/0 fail |

- Speedup **2.70×** vs claimed 2.57× (2337s→911s). Magnitude sanity (O1/O2): both arms above the 2026-08-03 figures, explained by tree growth — 416→562 tests (+35%), 114→128 tagged files — and the dev-stack load; serial 3007s is *below* the pro-rata expectation 2337×562/416≈3157s. Not a falsification; the ratio on today's tree is the primary oracle and it holds.
- Load context per arm in `probes/arm-*.context` (serial started at loadavg 2.36, parallel at 0.83 — the parallel arm's advantage is not load-flattered; it ran on the quieter box and still its own 4 forks drove load to 8.5).

### GAINS — CI: **REPRODUCED** (evidence: `evidence/ci-verdict-extract.md`)
- Push-runs-only sampling (suite step unconditional on push — ci-cd.yaml:218), 14 runs per window, all suite steps executed successfully: suite-step median **46.8 min → 31.8 min (−32%)**; distributions non-overlapping (BEFORE min 40.3 > AFTER max 38.9).
- Drift control: tagged files 105→114 (BEFORE window) vs 114→116 (AFTER) — the AFTER window ran an equal-or-larger population, so −32% *understates* the per-test gain.
- Before-window provably serial: `git show 97b701f8:core-java/build.gradle.kts` contains no `maxParallelForks` (Gradle default 1).

### VALIDITY — local: **VALID RUN**
- `probes/parity-check.sh` (armed: mismatch rc=1, VOID rc=2, pass rc=0 — `parity-check.FAIL-DIRECTION.txt`): **tests=562 skipped=1 failures=0 errors=0 suites=127 identical in both arms; 0 OOM signatures** in either XML set or either Gradle log (`evidence/parity-verdict.txt`). The one skip is identical in both arms: `FinancialSummaryGoldenFileTest.captureGoldenOnce()` (deliberate capture-mode test). No parallel-only failures → no cross-fork interference observed.
- Isolation story intact: per-class `@Container` Postgres contract (IntegrationTestSupport javadoc, a static utility with no shared container); only `withReuse(true)` in tree is in `RateLimitIntegrationTest.java.disabled` (not compiled); no `testcontainers.properties` in repo; `~/.testcontainers.properties` has no reuse flag (rg -uu, rc recorded).
- Override falsification (O3): three distinct inputs → three distinct printed values — default→4, `=1`→1, `=2`→2 (`arm-parallel.log:9`, `arm-serial.log:9`, `arm-falsify2.log:9`). `--dry-run` does NOT fire doFirst (`falsify-dryrun.log`) — the falsify arm therefore executed one real class (19s).

### VALIDITY — CI logging: **CONTRACT HOLDS**
- Post-merge job log (run 30853076827 = the d95239dc merge push, job 91817570155): Runner shape printed `nproc: 4`, `15Gi total, 14Gi available`, `88G disk`; doFirst printed `availableProcessors=4, maxParallelForks=2, forkEvery=4`. Arithmetic (4/2).coerceIn(1,4)=2 **verified on the real runner** — the divisor is active on CI, i.e. the inertness defect #512 targeted is fixed.

## 3. Findings (jq-derived from findings.json)
- **Low: 1** (Critical/High/Medium: 0) — F-01 (docs-broken): no current-tree declared expectation for the integration-suite population (metrics.json lacks the subset; the in-repo figures 416/2337s/911s have drifted 35% behind the tree). Correctness-finding verdicts: wrong-value 0, wrong-magnitude 0, untested 0.

## 4. Coverage gaps (explicit)
- Correctness tier: GAINS both sides graded at **historical-drift** tier (own declared priors); VALIDITY at **invariants + config-bound** tier — no stronger oracle exists for this claim type; not upgraded to "correct" beyond that.
- CI sample is 14+14 push runs around the merge; PR-event runs were deliberately excluded (path-filter conditional would confound). Long-horizon CI trend (post-Aug-04 growth to 128 files) not re-measured — the adjacent-window design controls drift better.
- Both local arms used the Gradle daemon (CI uses `--no-daemon`); arms are internally comparable, but local wall-times are not directly comparable to CI wall-times (different machine class anyway).
- Single measurement per local arm (n=1 per arm) — variance not characterised; magnitudes cross-checked against pro-rata priors instead.
- Legacy run-state debris in `.qa-council/` (`LATEST` marker, `disc-*`/`qa-*` dirs) predates the pinned naming rule; ignored by artifact-based selection, noted for hygiene.

## 5. Disposition proposal (audit mode — awaiting orchestrator/user checkpoint)
1. **F-01 → small follow-up**: add an integration-subset count (e.g. `java_integration_test_methods`) to `docs/metrics.json` (owner: oaas-release-qa lane), or append a one-line "figures are point-in-time; derive current population via git grep" note to the build.gradle.kts block. Defer-with-note is also reasonable — severity Low.
2. **Probes worth promoting**: `parity-check.sh` (arm-vs-arm JUnit parity + OOM scan) is generic enough for the repo's verify suite; `run-arm.sh` is run-dir-specific (hardcoded run path) — promote only if parameterised.
3. **No issues need filing for the headline claims** — #512's gains and validity are confirmed with evidence; recommend recording this run-id in the phase notes as the validation of record.

Next: `/qa-plan 20260815-173801` (or stop here — audit mode).
