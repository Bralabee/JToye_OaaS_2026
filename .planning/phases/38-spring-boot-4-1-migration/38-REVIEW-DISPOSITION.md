---
phase: 38
review: 38-REVIEW.md
titles: json
findings:
  - id: WR-01
    severity: warning
    disposition: open
    title: "The versioned cache prefix splits evictions across Boot versions during a rolling deploy and a rollback (stale authorization for up to 5 min)"
  - id: WR-02
    severity: warning
    disposition: open
    title: "`IdempotencyJson` does not reproduce Boot 3.5's parameter-name detection, so it is not the \"frozen Boot-3.5 format\" it claims to be"
  - id: IN-01
    severity: info
    disposition: open
    title: "The CI gate step sets database env vars the script says it does not need"
  - id: IN-02
    severity: info
    disposition: open
    title: "`spring-retry` is pinned by hand, outside every BOM and horizon row"
  - id: IN-03
    severity: info
    disposition: open
    title: "The suppression filter's 404 `instance` uses the raw request URI"
  - id: IN-04
    severity: info
    disposition: open
    title: "HANDOFF.md's live block still says Phase 38 is \"planned, ready to execute\""
open: 6
total: 6
recorded: 2026-10-05T19:30:35.981Z
---

# Phase 38: Code Review Disposition

| Finding | Severity | Disposition | Source |
|---------|----------|-------------|--------|
| WR-01 | warning | open | - |
| WR-02 | warning | open | - |
| IN-01 | info | open | - |
| IN-02 | info | open | - |
| IN-03 | info | open | - |
| IN-04 | info | open | - |

Dispositions: `open` (recorded, not yet triaged), `fixed`, `skipped`, `deferred`.
Set `deferred` by hand and put the reason in the Source cell; both are preserved. A `|` in the reason is kept as prose and escaped on the next run.
Re-running the gate keeps every row it can. A row the current review no longer reports is kept and its Source cell flagged, so a finding does not leave this record silently. ONE exception: when a finding id is REUSED by a different finding, the earlier decision cannot keep a row — the id is taken — and it is dropped. A RECORDED decision (anything but `open`) is named on the console when that happens; a row still at `open` is replaced silently, because `open` records no decision to lose.
