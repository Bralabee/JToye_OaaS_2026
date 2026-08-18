# Validation of record — PR #512 `integrationTest` parallel forks

**QA-council run `20260815-173801`, 2026-08-15 (audit mode — discovery only, no product-code changes).**
Subject: PR #512 (`d95239dc`, merged 2026-08-03) — `maxParallelForks = (availableProcessors()/2).coerceIn(1, 4)`
on `:core-java:integrationTest`, `forkEvery(4)` retained, `doFirst` fork-count logging, and the CI
"Runner shape" step in `ci-cd.yaml`.

## Verdict: validated — gains reproduced slightly above claim, run validity intact

| Question | Verdict | Measured |
|---|---|---|
| Gains, local (16-core box) | Reproduced | serial 3007s → parallel 1113s = **2.70×** (claim was 2.57×) |
| Gains, CI (GitHub 4-core runner) | Reproduced | suite-step median **46.8 → 31.8 min (−32%)**, 14+14 main-push runs, non-overlapping distributions |
| Validity, local | Valid run | exact arm parity: tests=562, skipped=1 (same deliberate golden-file capture test), failures=0, errors=0, suites=127, 0 OOM in both arms; no parallel-only failures |
| Validity, CI logging | Contract holds | real runner logged `nproc: 4` → `availableProcessors=4, maxParallelForks=2` — divisor active on CI |

Both local arms ran above the 2026-08-03 absolutes because the suite grew 416 → 562 tests (+35%);
pro-rata, today's serial arm is *under* the old baseline, and the CI after-window carried the larger
population — the measured gains therefore understate the per-test improvement. The
`-PitMaxParallelForks` override was falsified in three directions (default→4, `=1`→1, `=2`→2).

Full report: [QA-COUNCIL-REPORT.md](QA-COUNCIL-REPORT.md). Key extracts under [evidence/](evidence/);
the as-run probe scripts under [probes/](probes/) (`parity-check.sh` is generic and armed in
fail/VOID/pass directions; `run-arm.sh` is as-run and would need parameterising before reuse).
The complete run directory (arm logs, baseline/contract/oracles, findings.json) lives in the
git-excluded `.qa-council/20260815-173801/` on the machine that ran the audit.

## Known limits of this validation (recorded, not hidden)

- n=1 per local arm; variance uncharacterised (magnitudes cross-checked pro-rata instead).
- Local arms used the Gradle daemon; CI uses `--no-daemon` — arms are internally comparable,
  not CI-comparable.
- CI comparison is adjacent 14-run windows around the merge, deliberately; the long-horizon
  trend past 2026-08-04 was not re-measured.
- Correctness graded at the historical-drift + invariant tier — no stronger oracle exists for a
  timing claim.

## Findings disposition

**0 Critical / 0 High / 0 Medium / 1 Low.**

- **F-01 (Low, docs-broken) — DEFERRED with this note.** The in-repo figures behind this suite have
  drifted ~35% behind the tree: the `core-java/build.gradle.kts` comment block still cites
  416 tests / 2337s / 911s (true on 2026-08-03, now 562 tests), and `docs/metrics.json` carries no
  integration-suite subset count for a gate to check them against. Nothing is functionally wrong;
  the next person re-deriving expectations from those numbers inherits stale ones. Fix shape when
  picked up: add an integration-subset key to `docs/metrics.json` (owned by the docs-freshness
  loop) and date-stamp or refresh the build-file comment's figures.
- Also deferred: promoting `probes/parity-check.sh` into the repo's verify suite
  (`run-arm.sh` needs parameterising first).
