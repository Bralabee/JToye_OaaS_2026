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

- `scripts/check-doc-citations.sh` is red on six stale line citations, none of them from 31.1-27
  status: resolved by 31.1-29 (`c3213802`): each citation re-pointed at the line that now carries the claim; 6 C-3 violations before, 0 after
  **Found by:** 31.1-27 (Task 3), running the citation gate after editing `docs/legal/article-26-arrangement.md`.
  **What:** six C-3 failures — `.planning/codebase/STACK.md:84` and `INTEGRATIONS.md:14` (application.yml:749-800 / 752-757), `k8s/LOCAL.md:606` (configmap.yaml:166), `k8s/LOCAL.md:609` and `:1634` (application.yml:456), `k8s/LOCAL.md:1590` (core-java-deployment.yaml:312-316). The identical six fail on the plan base commit `ecee486a` (gate run in a detached worktree; `diff` of the two FAIL lists is empty), so line drift from earlier plans moved them, not this one.
  **Why deferred:** out of scope for 31.1-27 (no cited line moved because of it). The fix is to re-point each citation at the line that now carries the claim; 31.1-29 (docs/metrics regeneration) is the natural owner.

- Two docs still describe the public accessibility claim as WCAG 2.1 AA
  status: resolved by 31.1-29 (`497e245b`): both now say WCAG 2.2 AA; the PRD line also says five dated exceptions and 17 claimed surfaces
  **Found by:** 31.1-28 (Task 2), grepping for "WCAG 2.1" after the statement and its axe gate moved to WCAG 2.2 AA.
  **What:** `docs/PRD.md:137` ("WCAG 2.1 AA — partial", "seven dated exceptions", "12 public surfaces") and `docs/architecture/LAYOUT_WIDTH_CONTRACT.md:336` ("WCAG 2.1 AA conformance") predate 31.1-28. After it, the statement names WCAG 2.2 AA with five dated exceptions, and the per-PR gate scans 17 surfaces (plus two instruments and the scope/standard coupling test) on both viewports. The statement, its tests, the spec and the `ci-cd.yaml` comments were corrected in 31.1-28.
  **Why deferred:** the PRD line carries counts that 31.1-29's docs/metrics regeneration owns; correcting the prose there keeps one owner for those numbers.

- `scripts/check-doc-citations.sh` exits 0 when it cannot read the YAML citations at all
  status: open
  **Found by:** 31.1-29 (Task 2), during the static gate sweep.
  **What:** where `python3` is refused or missing (here, the machine's python shim refuses a bare `python3` outside an env), the gate prints `VOID: cannot extract YAML citations from docs/ops/terminal-states.yaml`, reports `citations=0` for that file, and still exits 0 with PASS. The `void` helper exits 2, but `yaml_citations` runs inside a command substitution, so the exit ends only the subshell. Measured: without an env, 29 citations verified and rc 0; inside the `jtoye-ops` conda env (PyYAML 6.0.3), 46 verified (17 of them in terminal-states.yaml) and rc 0. So the plain run said PASS while it had not checked 17 citations. CI has `python3` and PyYAML, so CI is not affected today.
  **Why deferred:** the gate predates 31.1, and 31.1-29 edits no gate script (T-31.1-98). The fix is to propagate the VOID out of the subshell (for example, check the substitution's status and call `void` in the parent) and add a break arm with `python3` removed from PATH. It needs its own change and its own fail-direction proof.

