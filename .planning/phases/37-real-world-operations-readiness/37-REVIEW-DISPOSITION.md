---
phase: 37
review: 37-REVIEW.md
titles: json
findings:
  - id: WR-01
    severity: warning
    disposition: open
    title: "Pagination tiers overflow the card again once page labels reach four digits"
  - id: WR-02
    severity: warning
    disposition: open
    title: "The pagination instrument arm cannot show that the containment check fails"
  - id: IN-01
    severity: info
    disposition: open
    title: "The Java parity test's input is not a Gradle input, so a TypeScript-only edit leaves `:core-java:test` UP-TO-DATE"
  - id: IN-02
    severity: info
    disposition: open
    title: "The sentence-end source guard is a per-line deny-list of identifier tokens"
  - id: IN-03
    severity: info
    disposition: open
    title: "The onboarding email's HTML and text paths can disagree on trailing whitespace; the \"after sanitise\" contract is not followed there"
  - id: IN-04
    severity: info
    disposition: open
    title: "One vendor-typed sentence end in email copy still bypasses the rule (onboarding gate reason)"
  - id: IN-05
    severity: info
    disposition: open
    title: "Stale or over-claiming coverage comments"
open: 7
total: 7
recorded: 2026-10-16T15:58:06.435Z
---

# Phase 37: Code Review Disposition

| Finding | Severity | Disposition | Source |
|---------|----------|-------------|--------|
| WR-01 | warning | open | - |
| WR-02 | warning | open | - |
| IN-01 | info | open | - |
| IN-02 | info | open | - |
| IN-03 | info | open | - |
| IN-04 | info | open | - |
| IN-05 | info | open | - |

Dispositions: `open` (recorded, not yet triaged), `fixed`, `skipped`, `deferred`.
Set `deferred` by hand and put the reason in the Source cell; both are preserved. A `|` in the reason is kept as prose and escaped on the next run.
Re-running the gate keeps every row it can. A row the current review no longer reports is kept and its Source cell flagged, so a finding does not leave this record silently. ONE exception: when a finding id is REUSED by a different finding, the earlier decision cannot keep a row — the id is taken — and it is dropped. A RECORDED decision (anything but `open`) is named on the console when that happens; a row still at `open` is replaced silently, because `open` records no decision to lose.
