---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 19
subsystem: infra
tags: [cost, d-03, d-34, azure, cost-management, ceiling, owner-decision]
status: complete

requires:
  - phase: 29-09
    provides: "the live estate (jtoye-stg-aks, jtoye-stg-pg, the two static IPs, Key Vault, the Blob accounts) whose cost this measures"
provides:
  - "29-COST-CHECKPOINT.md: the measured estate cost per meter, against the ceiling, with the owner's decision"
  - "evidence/29-19-cost.txt: the query body and the raw Cost Management JSON, verbatim, plus the verify block's two directions"
  - "The D-03 ceiling moved to £178/month by the owner, 2026-10-20 (option-raise)"
affects: [29-27, 29-28, 32]

actuals:
  tasks: 2
  commits: 2
plan_head_before: 40595f82a95b0f5876a88eefe60e3e1820b6ed32

requirements-completed: []

duration: about 33 h of estate life before the measurement (cluster created 2026-10-19T00:44Z, query 2026-10-20T10:30Z); about 1 h of work
completed: 2026-10-20
---

# Phase 29 Plan 19: The Measured Estate Cost, and the Owner's Ceiling

**The staging estate costs £156.00/month, measured from the subscription's own Cost Management data with at least
24 hours behind every unit rate. Neither of the two meters research left unresolved bills today: the load balancer is still on
its free meter, and `FreeTierInfrastructureCost` has no usage line. The £6.00 over the original £150 ceiling is the
fourth node (D-34). The owner moved the ceiling to £178/month.**

## What was measured

- **Source:** `POST /subscriptions/c483d353-…/providers/Microsoft.CostManagement/query?api-version=2023-11-01`,
  ActualCost, daily, grouped by Meter and ResourceId, 2026-10-18 to 2026-10-20, through `scripts/lib/azure-guard.sh`
  `az_read`. Queried at 10:30Z, first try rc 0. The legacy `az consumption usage list` returns `None` in every cost
  field and was not used.
- **Window:** day 2026-10-19 (the estate from 00:41Z) plus the posted part of 2026-10-20, which is labelled
  INCOMPLETE. Unit rates: PG compute 25.000 h, ingress IP 26.301 h, outbound IP 27.301 h, nodes 94.983 node-hours.
  An earlier query at 09:34Z had PG at 23 h, so the projection waited for 10:30Z rather than extrapolate (the plan's
  "never fewer than 24 hours").
- **Result:** B2s £0.035624/node-hour × 4 = £104.03; PG compute £41.88, storage £3.15; two IPs £5.51; disks, Key Vault
  and transfer £1.44; Blob and every `- Free` meter £0.00. **Total £156.00/month.**
- **Matches research:** the B2s, PG and IP unit rates equal the research's list prices. Research's 3-node £131.22
  plus the fourth node's £26.01 is £157.23, within £1.23 of the measurement.
- **Differs from research:** snackpass-pg's storage already bills on the paid meter (£3.40/month). Research assumed
  it stayed free until 2026-11-08. It is shown apart: it is another project's server.

## Owner decision

**option-raise, £178/month, 2026-10-20** ("raise to £178"). Headroom: £22.00 today, £8.20 if the load balancer
leaves its free meter. Either case where `FreeTierInfrastructureCost` bills (£183.52, or £197.32 with both)
breaches it and brings the decision back to the owner.

## Verification

- The plan's verify block on `29-COST-CHECKPOINT.md`: all four meter rows OK, rc 0. On a scratch copy with every
  `Load Balancer` line deleted: `row: Load Balancer MISSING`, rc 1. On the record again: rc 0. All three recorded in
  `evidence/29-19-cost.txt`.
- Every figure in the record traces to a row of the raw JSON in `evidence/29-19-cost.txt` (sha256 of the result file
  `3d7e100ada98292cd42b7e241de9c072c5eb46385e96eb75fa07cc3c6af33c26`).
- The live configuration the projection uses was read the same day: `az aks show` gives nodepool1 count 4
  Standard_B2s, sku Free; `kubectl get nodes` lists four nodes, the fourth created 2026-10-19T10:48:10Z.

## Deviations

1. **[Order] 29-27 ran before this plan.** Plan 29-27 lists 29-19 in `depends_on`. Its table and approval did not need
   the cost figure, so they went first. Its ADR-0002 D-24 amendment waited for this measurement and was written
   afterwards, with the measured figure.
2. **[Figure] The ADR amendment does not use the plan's £145.02.** That figure was the 3-node projection with the
   load balancer billing. D-34 overtook it. The amendment cites the measured £156.00, the £169.80 case, Managed Redis
   B0 HA at £19.86 (research), and the new £178 ceiling.

## Cross-Cutting Quality Contracts

| Contract | Applies? | Reason |
|----------|----------|--------|
| Web performance (mobile-first) | N/A | No page or bundle changed. |
| SEO / discoverability | N/A | No public surface changed. |
| AI agent-readiness / machine-consumability | N/A | No API surface changed. |
| Security | Applies, limited | Read-only Azure calls through `az_read`, with the subscription pinned by `az_guard_init`; the employer default subscription was never addressed. The evidence holds resource IDs and costs, no secret. |
| Client-persisted identity lifecycle | N/A | No client-side state. |
| Falsifiable evidence + runtime parity | Applies (evidence half) | The verify block was shown failing (LB row deleted, rc 1) before the pass was trusted. A shorter window was refused rather than projected. The projection uses the live node count read the same day, not the plan's number. |

## Commits

| Commit | Subject |
|--------|---------|
| `4dfb6ae2` | docs(29-19): record the measured estate cost by meter, pending the owner's ceiling decision |
| (this commit) | the owner's decision, the ADR-0002 D-24 amendment, this SUMMARY, the 29-27 SUMMARY, STATE and ROADMAP |
