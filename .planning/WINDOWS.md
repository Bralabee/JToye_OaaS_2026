---
schema_version: 1
open_count: 2
waived_count: 0
fixed_count: 13
total_count: 15
last_updated: 2026-10-07T15:36:39.930Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 36 | unrun-verify | .planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md |  | 36-05: az storage account check-name gave no verdict for the four D-11 names (Microsoft.Storage NotRegistered -> SubscriptionNotFound); DNS NXDOMAIN only; register the RP and re-run (runbook 2.1) | open |  | 2026-09-28T22:52:29.731Z |  |
| 2 | 36 | unrun-verify | .github/workflows/e2e-nightly.yml |  | 36-08: the nightly drill step (build pg-backup :15-blob, ephemeral DB_BACKUP_PASSWORD, check-backup-restore-drill.sh) was validated statically only (actionlint 0, bash -n on all 18 run blocks, append logic simulated); it has never run on a GitHub runner. 36-18 runs the nightly. | fixed |  | 2026-09-29T01:01:30.322Z | 2026-09-29T11:34:43.738Z |
| 3 | 36 | unrun-verify | k8s/staging/workload-identity-patch.yaml |  | 36-09: Workload Identity is proven at render level (INV-8/9/10) and against the real validators (core-java validateShape, blobctl config) only; that the AKS webhook mutates the labelled pods, projects its token with automountServiceAccountToken false (A6) and the Entra exchange succeeds needs a WI-enabled cluster and the Phase 29 client-id annotations (jtoye-staging-aks has WI off and is stopped) | open |  | 2026-09-29T06:23:37.497Z |  |
| 4 | 31.1 | unrun-verify | scripts/openapi-gate.sh |  | 31.1-14: oasdiff-based OpenAPI compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest byte-equality ran green after regeneration | fixed |  | 2026-10-06T17:35:41.148Z | 2026-10-07T15:36:39.818Z |
| 5 | 31.1 | unrun-verify | frontend/e2e/allergen-ack-race.spec.ts |  | 31.1-15 #785 race spec listed only; RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30 | fixed |  | 2026-10-06T18:25:22.970Z | 2026-10-07T11:12:28.951Z |
| 6 | 31.1 | unrun-verify | core-java/src/main/java/uk/jtoye/core/gdpr/DsarOutcomeMailer.java |  | 31.1-16 ACCESS link email proven only against a mocked JavaMailSender; live Mailhog capture, live customer-realm lookup and a click-through to /data-request/download (page built by 31.1-17) owed to 31.1-30 | fixed |  | 2026-10-06T19:52:27.849Z | 2026-10-07T11:12:29.131Z |
| 7 | 31.1 | stub | frontend/app/data-request/download/download-client.tsx |  | Unavailable state links 'Request a new copy' to /shop/account, which 31.1-26 builds; until then the link 404s | fixed |  | 2026-10-06T22:47:38.670Z | 2026-10-07T11:12:29.314Z |
| 8 | 31.1 | unrun-verify | frontend/app/data-request/download/page.tsx |  | Live click-through of the emailed Article 15 link (Mailhog -> /data-request/download -> Show my data once, second open unavailable) not run: shared stack not rebuilt; owed to 31.1-30 | fixed |  | 2026-10-06T22:47:38.806Z | 2026-10-07T11:12:29.500Z |
| 9 | 31.1 | unrun-verify | frontend/e2e/dsar-verify-link.spec.ts |  | 31.1-20 #839 compose-stack spec (Mailhog link -> /data-request/confirm, press, already confirmed) listed only; live RED/GREEN owed to 31.1-30 after the stack rebuild | fixed |  | 2026-10-07T00:04:51.201Z | 2026-10-07T11:12:29.675Z |
| 10 | 31.1 | unrun-verify | frontend/e2e/storefront-dish-modal-a11y.spec.ts |  | 31.1-21: 'Dish modal allergen section (31.1-21)' (200% zoom placement + three statements on fixture SKU E2E-31121-DISH-ALLERGENS) listed only; live RED/GREEN owed to 31.1-30 on the rebuilt stack | fixed |  | 2026-10-07T00:21:26.304Z | 2026-10-07T11:12:29.863Z |
| 11 | 31.1 | unrun-verify | frontend/e2e/kitchen-allergy-note.spec.ts |  | 31.1-22 kitchen allergy note browser spec (4 tests: board note + 44px button + ack round trip, order detail UK dates + channel line) written, type-checked and listed; not run, the live stack rebuild and click-through are owed to 31.1-30 | fixed |  | 2026-10-07T00:42:00.356Z | 2026-10-07T11:12:30.045Z |
| 12 | 31.1 | unrun-verify | frontend/e2e/seller-identity.spec.ts |  | 31.1-24 #789 seller-identity spec listed only (3 tests); live RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30; its Mailhog half stays red until 31.1-25 ships the email seller block | fixed |  | 2026-10-07T02:03:55.160Z | 2026-10-07T11:12:30.220Z |
| 13 | 31.1 | unrun-verify | scripts/openapi-gate.sh |  | 31.1-24: oasdiff compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest red on the old snapshot, green on the regenerated one (additive: 49 insertions, 0 deletions) | fixed |  | 2026-10-07T02:03:55.293Z | 2026-10-07T15:36:39.930Z |
| 14 | 31.1 | unrun-verify | core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java |  | 31.1-25 #789/#785 order emails proven by MimeMessage content tests (unit + Testcontainers, mocked sender) and by OrderNotificationListenerIntegrationTest landing one Confirmed email in the local Mailhog from its own test context; the compose-stack Mailhog capture (rebuilt core-java, seller-identity.spec.ts email half) is owed to 31.1-30 | fixed |  | 2026-10-07T03:00:22.755Z | 2026-10-07T11:12:30.404Z |
| 15 | 31.1 | unrun-verify | frontend/e2e/customer-account.spec.ts |  | 31.1-26 #838 customer-account spec listed only (3 tests: served-HTML wall on mobile+desktop, @desktop-only signed-in click-through lodging ACCESS+ERASURE with one Mailhog email each); live RED (pre-rebuild runtime has no /shop/account) and GREEN owed to 31.1-30 | fixed |  | 2026-10-07T03:21:29.218Z | 2026-10-07T11:12:30.592Z |

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
  },
  {
    "id": 2,
    "kind": "unrun-verify",
    "phase": "36",
    "file": ".github/workflows/e2e-nightly.yml",
    "line": null,
    "description": "36-08: the nightly drill step (build pg-backup :15-blob, ephemeral DB_BACKUP_PASSWORD, check-backup-restore-drill.sh) was validated statically only (actionlint 0, bash -n on all 18 run blocks, append logic simulated); it has never run on a GitHub runner. 36-18 runs the nightly.",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-09-29T01:01:30.322Z",
    "resolved_at": "2026-09-29T11:34:43.738Z",
    "milestone": "v2.3"
  },
  {
    "id": 3,
    "kind": "unrun-verify",
    "phase": "36",
    "file": "k8s/staging/workload-identity-patch.yaml",
    "line": null,
    "description": "36-09: Workload Identity is proven at render level (INV-8/9/10) and against the real validators (core-java validateShape, blobctl config) only; that the AKS webhook mutates the labelled pods, projects its token with automountServiceAccountToken false (A6) and the Entra exchange succeeds needs a WI-enabled cluster and the Phase 29 client-id annotations (jtoye-staging-aks has WI off and is stopped)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-29T06:23:37.497Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 4,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "scripts/openapi-gate.sh",
    "line": null,
    "description": "31.1-14: oasdiff-based OpenAPI compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest byte-equality ran green after regeneration",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T17:35:41.148Z",
    "resolved_at": "2026-10-07T15:36:39.818Z",
    "milestone": "v2.3"
  },
  {
    "id": 5,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/allergen-ack-race.spec.ts",
    "line": null,
    "description": "31.1-15 #785 race spec listed only; RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T18:25:22.970Z",
    "resolved_at": "2026-10-07T11:12:28.951Z",
    "milestone": "v2.3"
  },
  {
    "id": 6,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "core-java/src/main/java/uk/jtoye/core/gdpr/DsarOutcomeMailer.java",
    "line": null,
    "description": "31.1-16 ACCESS link email proven only against a mocked JavaMailSender; live Mailhog capture, live customer-realm lookup and a click-through to /data-request/download (page built by 31.1-17) owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T19:52:27.849Z",
    "resolved_at": "2026-10-07T11:12:29.131Z",
    "milestone": "v2.3"
  },
  {
    "id": 7,
    "kind": "stub",
    "phase": "31.1",
    "file": "frontend/app/data-request/download/download-client.tsx",
    "line": null,
    "description": "Unavailable state links 'Request a new copy' to /shop/account, which 31.1-26 builds; until then the link 404s",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T22:47:38.670Z",
    "resolved_at": "2026-10-07T11:12:29.314Z",
    "milestone": "v2.3"
  },
  {
    "id": 8,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/app/data-request/download/page.tsx",
    "line": null,
    "description": "Live click-through of the emailed Article 15 link (Mailhog -> /data-request/download -> Show my data once, second open unavailable) not run: shared stack not rebuilt; owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T22:47:38.806Z",
    "resolved_at": "2026-10-07T11:12:29.500Z",
    "milestone": "v2.3"
  },
  {
    "id": 9,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/dsar-verify-link.spec.ts",
    "line": null,
    "description": "31.1-20 #839 compose-stack spec (Mailhog link -> /data-request/confirm, press, already confirmed) listed only; live RED/GREEN owed to 31.1-30 after the stack rebuild",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:04:51.201Z",
    "resolved_at": "2026-10-07T11:12:29.675Z",
    "milestone": "v2.3"
  },
  {
    "id": 10,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/storefront-dish-modal-a11y.spec.ts",
    "line": null,
    "description": "31.1-21: 'Dish modal allergen section (31.1-21)' (200% zoom placement + three statements on fixture SKU E2E-31121-DISH-ALLERGENS) listed only; live RED/GREEN owed to 31.1-30 on the rebuilt stack",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:21:26.304Z",
    "resolved_at": "2026-10-07T11:12:29.863Z",
    "milestone": "v2.3"
  },
  {
    "id": 11,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/kitchen-allergy-note.spec.ts",
    "line": null,
    "description": "31.1-22 kitchen allergy note browser spec (4 tests: board note + 44px button + ack round trip, order detail UK dates + channel line) written, type-checked and listed; not run, the live stack rebuild and click-through are owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:42:00.356Z",
    "resolved_at": "2026-10-07T11:12:30.045Z",
    "milestone": "v2.3"
  },
  {
    "id": 12,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/seller-identity.spec.ts",
    "line": null,
    "description": "31.1-24 #789 seller-identity spec listed only (3 tests); live RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30; its Mailhog half stays red until 31.1-25 ships the email seller block",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T02:03:55.160Z",
    "resolved_at": "2026-10-07T11:12:30.220Z",
    "milestone": "v2.3"
  },
  {
    "id": 13,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "scripts/openapi-gate.sh",
    "line": null,
    "description": "31.1-24: oasdiff compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest red on the old snapshot, green on the regenerated one (additive: 49 insertions, 0 deletions)",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T02:03:55.293Z",
    "resolved_at": "2026-10-07T15:36:39.930Z",
    "milestone": "v2.3"
  },
  {
    "id": 14,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java",
    "line": null,
    "description": "31.1-25 #789/#785 order emails proven by MimeMessage content tests (unit + Testcontainers, mocked sender) and by OrderNotificationListenerIntegrationTest landing one Confirmed email in the local Mailhog from its own test context; the compose-stack Mailhog capture (rebuilt core-java, seller-identity.spec.ts email half) is owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T03:00:22.755Z",
    "resolved_at": "2026-10-07T11:12:30.404Z",
    "milestone": "v2.3"
  },
  {
    "id": 15,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/customer-account.spec.ts",
    "line": null,
    "description": "31.1-26 #838 customer-account spec listed only (3 tests: served-HTML wall on mobile+desktop, @desktop-only signed-in click-through lodging ACCESS+ERASURE with one Mailhog email each); live RED (pre-rebuild runtime has no /shop/account) and GREEN owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T03:21:29.218Z",
    "resolved_at": "2026-10-07T11:12:30.592Z",
    "milestone": "v2.3"
  }
]
````
