---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 0
total_count: 1
last_updated: 2026-09-28T22:52:29.731Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 36 | unrun-verify | .planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md |  | 36-05: az storage account check-name gave no verdict for the four D-11 names (Microsoft.Storage NotRegistered -> SubscriptionNotFound); DNS NXDOMAIN only; register the RP and re-run (runbook 2.1) | open |  | 2026-09-28T22:52:29.731Z |  |

````json
[
  {
    "id": 1,
    "kind": "unrun-verify",
    "phase": "36",
    "file": ".planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md",
    "line": null,
    "description": "36-05: az storage account check-name gave no verdict for the four D-11 names (Microsoft.Storage NotRegistered -> SubscriptionNotFound); DNS NXDOMAIN only; register the RP and re-run (runbook 2.1)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-28T22:52:29.731Z",
    "resolved_at": null,
    "milestone": "v2.3"
  }
]
````
