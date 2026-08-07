---
quick_id: 260807-jj6
slug: triage-all-57-open-issues-into-an-explic
date: 2026-08-07
status: complete
scope: docs-only
files_changed: 4
---

# Summary — triage all 57 open issues into an explicit disposition

## Result

**57/57 open issues now have a home.** `ROADMAP.md` alone went **15 → 47**; the remaining 10 are the
deferrals and the post-GTM hardening backlog, which live in `ISSUE-DISPOSITION.md` because putting
them in a phase would be a false promise.

| | before | after |
|---|---:|---:|
| open issues named in `ROADMAP.md` | 15 | 47 |
| named in `ROADMAP.md` **or** `ISSUE-DISPOSITION.md` | 15 | **57** |
| open issues in **zero** `.planning/` files | 6 | 0 |

## Disposition

| Bucket | n |
|---|---:|
| Phase 28 — Security Triage + the Dev/Prod Boundary | 9 |
| Phase 29 — Deployable Staging, With Its Own Monitoring | 12 |
| Phase 30 — The Money Path, Executed | 4 |
| Phase 31 — Consumer-Safety and Legal Floor | 3 |
| Phase 32 — Production Cutover + First Tenant | 1 |
| **Phase 33 — The Consumer Product** *(new)* | 10 |
| **Phase 34 — Rendering + Test Truthfulness** *(new)* | 6 |
| Deferred, with a dated reason and a revival condition | 5 |
| Post-GTM hardening backlog | 6 |
| Immediate — a shipped defect (#587), no phase needed | 1 |

Two new phases, both made entirely of already-filed, already-prioritised open issues. Phase 33 gates
Phase 32; Phase 34 does not.

## Three findings the sweep produced that were not the point of it

**1. #286 is mostly satisfied and nobody noticed.** Measured against nightly run 31138225934
(182 total / 175 passed / 7 skipped): the `/dashboard/staff` click-through it asks for already runs
live — `dashboard-interface-corrections.spec.ts` does a real `vendorLogin` (3 refs, **0** route
stubs) and is not among the 7 skips. What remains is that `dashboard-mobile` runs at **390 × 844**
(`frontend/playwright.config.ts:84`), not the 375px the issue names, and carries **9** route stubs —
which is #542's complaint, not a separate one. Narrow it; do not close it whole.

**2. #110's second criterion is already met.** "Playwright runs in CI" is satisfied by the nightly
job, which is what closed #420. Only the coverage half remains.

**3. #461 is upstream of the entire money phase and was in no plan.** Phase 30's three criteria all
assume an order that took money; #461 says orders complete without taking any.

## A fourth finding, and a correction to this document, from the owner the same day

This summary first said #461 *"needs a product decision before it can be planned."* **That was
wrong.** The decision was made on 2026-08-02 and is quoted verbatim in the issue body: the payment
request goes to the buyer's **verified telephone number**, or the social channel they engaged on;
pay-on-collection is not permitted, because a customer can order and not collect, leaving the vendor
with produced stock and no payment.

The sweep classified #461 from its title rather than its body — the same failure the sweep exists to
fix, one layer in. It rescued six issues the roadmap could not see, then mis-read one of the six.

Measuring what *"verified telephone number"* actually requires produced the more consequential
finding:

| | dependency | state on the tree |
|---|---|---|
| 1 | phone **captured** | `Customer.phone` is `@Column(length = 50)`, **optional**, free text |
| 2 | phone **verified** | **nothing.** No `phone_verified` column in any of the 60 migrations, no OTP, no flow. Control: `emailVerified` → 4 files incl. `CustomerJwtVerifier`, so this is a true absence |
| 3 | a **channel** | `WhatsAppSmsChannel` exists, `WhatsAppProperties.enabled` defaults **false**, inbound parser incomplete |
| 4 | a **link** | Stripe test-mode keys |

**The platform verifies email and does not verify phone — and the design routes on phone.**

Consequences, all applied:

- **#462 moved from Phase 33 to Phase 30.** Its verified-contact-channel half is the address #461
  sends to; it cannot trail the money path. Phase 30 → 5 issues, Phase 33 → 9.
- **#208 reclassified as a *critical-path* deferral.** Its reason (a WhatsApp Business API account)
  is unchanged and still true — but it is the delivery mechanism for a P1, not an optional AI
  feature, and it should be obtained on the same trip as the Stripe keys. A fifth commercial
  blocker, effectively.
- **New requirement PAY-04** in `REQUIREMENTS.md`, plus a new falsifiable criterion 7 on Phase 30.
- **Requirement-ID drift closed.** Adding PAY-04 exposed that the earlier commit cited `CUST-*` and
  `TRUTH-*` in `ROADMAP.md` without ever defining them in `REQUIREMENTS.md`. All six are now
  defined; every ID cited in the roadmap resolves, the sole exception being `AI-01`, which is
  pre-existing and deliberate (absorbed into Phase 22's COMMS-04/05/06, traced but not counted).
  Requirements 46 → **53** across 15 categories.

## The verification control failed twice, and both failures were mine

The acceptance check is *"is every open issue number named?"*. Its control arm is a six-digit token
that is not an issue, which must return **rc=1**.

- **Run 1 — control returned rc=0.** The token was written into `ISSUE-DISPOSITION.md`'s own
  methodology section. A two-file sweep found it and reported "named" for a number that does not
  exist. The same run reported **49/57**, which looked like an ordinary near-miss.
- **Run 2 — control returned rc=0 again.** The fix for run 1 was a warning naming a *second*, fresh
  token — which wrote that token into the file too. Identical failure, five minutes later.
- **Run 3 — control returned rc=1**, using a token constructed at run time and never written to
  disk. Only then was the 57/57 result trustworthy.

This is the repo's recorded recursive-self-break trap: **a verification example and the material it
verifies must not share a namespace.** The warning in `ISSUE-DISPOSITION.md` now states the rule
rather than naming a token, because naming one is what broke it both times.

The real 8-issue miss underneath was mundane and would have been invisible without a working
control: `#107 #109 #111 #114 #209 #296 #303 #499` were written as bare numbers in two table columns
rather than `#N`, so the digit-boundary pattern could not see them.

## Files changed

| File | Change |
|---|---|
| `.planning/ISSUE-DISPOSITION.md` | new — all 57, one row each |
| `.planning/ROADMAP.md` | Phases 28/29/30 name their issues; Phases 33 + 34 added; Phase 32 now depends on 33; progress table + pointer |
| `.planning/STATE.md` | Quick Tasks row |
| `.planning/quick/260807-jj6-.../PLAN.md` | this task |

No source file touched. No issue closed, opened or relabelled — the sweep assigns homes, and closing
`#286` or `#110` on the measurements above is a separate, deliberate act.

## What this does not do

It does not estimate, re-prioritise, or promise a date. **One** issue — `#453` — needs a product
decision before it can be planned at all: who adjudicates onboarding MANUAL_REVIEW, given there is
no cross-tenant operator identity.

The four blocking commercial decisions for Phases 29–32 are unchanged and still gate everything
downstream: the production domain, the hosting target, Stripe test-mode keys, and ADR-0002 sign-off.
**A fifth now sits beside them in practice** — a WhatsApp Business API account (#208), because #461's
payment request has to be delivered on the channel the customer used.
