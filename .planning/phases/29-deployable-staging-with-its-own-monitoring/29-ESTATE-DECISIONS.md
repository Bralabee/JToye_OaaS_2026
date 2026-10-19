# Phase 29: Staging Estate Decisions (plan 29-09, Task 1 checkpoint)

This file is the decision record that `scripts/azure-staging-provision.sh`,
`scripts/azure-staging-datastores.sh` and `scripts/azure-staging-storage.sh` read before
they make any Azure call. Each script refuses (`REFUSED [decisions]`, rc 1) unless the
`APPROVED:` line below is present and the `KEY=value` lines parse.

## The approval

APPROVED: option-a

- **Date:** 2026-10-19
- **Approved by:** the owner, in-session, answering the Task 1 checkpoint as a direct question.
- **The owner's words:** "option-a, approved"
- **What was approved:**
  - Cilium overlay networking with pod CIDR 10.244.0.0/16.
  - PostgreSQL 18 with public access and exactly one firewall rule (the cluster outbound IP).
  - Admin login `jtoyepgadmin`.
  - Key Vault `jtoye-stg-kv` with purge protection ON and 90-day retention.
  - The billable creation, at about £131 to £145 a month as costed in the plan (RESEARCH, Measured
    estate cost: £131.22/month now and £145.02 once the load-balancer free meter ends, against a
    ceiling of about £150).

Options not chosen: option-b (purge protection OFF, 7-day retention) and option-c (PostgreSQL
private access, which would have stopped this plan for a re-plan of 29-02 and 29-07).

## One-way create-time parameters (read by the scripts)

POD_CIDR=10.244.0.0/16
KV_PURGE_PROTECTION=true
KV_RETENTION_DAYS=90
PG_ADMIN_LOGIN=jtoyepgadmin
PG_PUBLIC_ACCESS=public

| Key | Value | Door it walks | Undo cost |
|-----|-------|---------------|-----------|
| POD_CIDR | 10.244.0.0/16 | AKS Azure CNI overlay pod address space | Delete and recreate the cluster |
| (engine) | `--network-plugin azure --network-plugin-mode overlay --network-dataplane cilium --network-policy cilium` | DPLY-05's enforcing engine (#297) | Delete and recreate the cluster |
| KV_PURGE_PROTECTION | true | Once on it cannot be turned off; a deleted vault's DSAR key and credentials cannot be purged by accident (D-23, "secrets survive") | None. The name `jtoye-stg-kv` is held for the retention period if the vault is ever deleted |
| KV_RETENTION_DAYS | 90 | Soft-delete retention | Fixed at create |
| PG_ADMIN_LOGIN | jtoyepgadmin | Flexible Server administrator login | Fixed at create |
| PG_PUBLIC_ACCESS | public | Public endpoint plus one firewall rule equal to the cluster outbound IP (T-29-34) | Changing it rebuilds the network design (option-c) |
| (PostgreSQL major) | 18 | Flexible Server major version | A major-version upgrade or a rebuild |

## Already ruled (recorded here, not asked again)

- **D-32:** cluster access is Entra ID with Azure RBAC, and local accounts are disabled
  (`--enable-aad --enable-azure-rbac --disable-local-accounts`). No static admin kubeconfig exists.
  Operators and CI reach the cluster through kubelogin.
- **Phase 36 storage names:** `jtoyestgmedia` (uksouth, public blob access for the `media`
  container) and `jtoyestgbackup` (ukwest, private, holding `jtoye-db-backups` with an
  UNLOCKED retention policy). Locking the retention policy is a human checkpoint in plan 29-20.
  If a name is taken, the remedy is runbook §9, which is an owner decision, never a substitute name.

## Items still open after this approval (owned by later plans)

- Plan 29-19, after the cluster has run for 24 hours: whether the AKS
  `FreeTierInfrastructureCost Uptime SLA` meter bills (+£27.52/month if so), and what to do with
  `snackpass-pg`.
- Plan 29-20: the WORM lock on `jtoye-db-backups`.
