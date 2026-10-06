## Deferred Items

- A DSAR request whose sweep dies mid-run (JVM crash, pod eviction) stays `IN_PROGRESS` forever
  status: open
  **Found by:** 31.1-11 (Task 2), while proving "a sweep interrupted after the erasure commit is released".
  **What:** `DsarFanoutWorker.CLAIM_SQL` moves a row to `IN_PROGRESS` and claims only `VERIFIED` rows, and nothing in `src/main` ever reclaims an `IN_PROGRESS` row (`rg -uu "IN_PROGRESS" core-java/src/main/java` finds two hits: the claim's `SET status` and the `DsarRequest` enum constant). An exception inside the sweep is handled: 31.1-11 releases the request as OUTSTANDING, and `DsarAccountDeletionIntegrationTest#aSweepInterruptedAfterTheErasureCommitIsReleased_andTheRetryFinishesIt` proves it. A process that dies between the claim and the release is not handled. The request then never completes, never fails, and is never retried, and the subject is never told.
  **Why deferred:** this predates 31.1. It comes from 31-09's claim design, and 31.1-11 did not change it. The fix is a stale-claim reclaim (`IN_PROGRESS AND claimed_at < NOW() - interval`, bounded by `process_attempts`), which is a separate behavioural change to the claim.
