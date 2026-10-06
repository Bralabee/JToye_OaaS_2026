## Deferred Items

- A DSAR request whose sweep dies mid-run (JVM crash, pod eviction) stays `IN_PROGRESS` forever
  status: open
  **Found by:** 31.1-11 (Task 2), while proving "a sweep interrupted after the erasure commit is released".
  **What:** `DsarFanoutWorker.CLAIM_SQL` moves a row to `IN_PROGRESS` and claims only `VERIFIED` rows, and nothing in `src/main` ever reclaims an `IN_PROGRESS` row (`rg -uu "IN_PROGRESS" core-java/src/main/java` finds two hits: the claim's `SET status` and the `DsarRequest` enum constant). An exception inside the sweep is handled: 31.1-11 releases the request as OUTSTANDING, and `DsarAccountDeletionIntegrationTest#aSweepInterruptedAfterTheErasureCommitIsReleased_andTheRetryFinishesIt` proves it. A process that dies between the claim and the release is not handled. The request then never completes, never fails, and is never retried, and the subject is never told.
  **Why deferred:** this predates 31.1. It comes from 31-09's claim design, and 31.1-11 did not change it. The fix is a stale-claim reclaim (`IN_PROGRESS AND claimed_at < NOW() - interval`, bounded by `process_attempts`), which is a separate behavioural change to the claim.

- The PPDS label overflows its 100x60mm page once the ingredients run past about one line
  status: open
  **Found by:** 31.1-14 (Task 1), while adding the "May contain" and "Produced" lines.
  **What:** `ProductLabelService.renderPdf` lays the label out on a fixed `Rectangle(283, 170)` page with fixed font sizes and leading, and OpenPDF starts a second page when the content does not fit. Measured by replaying the pre-31.1-14 layout verbatim: a 24-character ingredients list fits one page, an 89-character list (two lines) prints the business address on page 2. After 31.1-14 (Ingredients heading inline, dates on one line) a two-line list fits without a may-contain line and overflows with one; one line plus may-contain fits (asserted by `ProductLabelServiceTest#generateLabelWithMayContainFitsOnePage`). A real ingredients list is often three or more lines, so the business identity can land on a second label.
  **Why deferred:** this predates 31.1-14 (the original layout already overflowed at two lines) and 31.1-14 did not make it worse for the cases it adds. The fix is a layout decision (scale the body font to fit, a taller label stock, or a fit check that fails loudly) that changes every printed label, which needs its own plan and an owner choice of label stock.

- An Article 17 erasure leaves the subject's address on `marketing_opt_in` (and `notification_suppression`)
  status: open
  **Found by:** 31.1-16 (Task 2), while making the Article 15 export a superset of every store that carries the address.
  **What:** V54's `marketing_opt_in.recipient` and `notification_suppression.recipient` hold the subject's email address in plain text, per tenant. The 31.1-16 export now reports both (matched by DSAR digest, tenant-pinned). `GdprService.anonymiseSubjectInTenant` touches neither: `rg -uu -l "marketing_opt_in|notification_suppression" core-java/src/main/java` finds only the consent package and `WebhookRetentionCleanup`. So after a completed erasure, a subject who had opted in to marketing still has their address in that tenant's opt-in table.
  **Why deferred:** this predates 31.1 (V54, Phase 22) and 31.1-16 is the read side. The fix is not a one-liner: an unsubscribe (suppression) row is arguably data the controller must KEEP to go on honouring the opt-out after erasure (the ICO suppression-list position), while an opt-in row should go. That split is an owner decision, then a change to the shared erasure core with its own break arms.
