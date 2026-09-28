# Phase 36: Azure Blob Storage Throughout - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-28
**Phase:** 36-azure-blob-storage-throughout
**Areas discussed:** Backup destination, Staging/prod credentials, Existing local images, Public image URL origin

**Preceding owner ruling (same session):** asked where the local/nightly stack should get its S3 server now that MinIO's images are gone, the owner answered "this project is completely azure based. minio was initially cos of convinience, what alternatives do we have"; offered A (Azure Blob throughout + Azurite), B (Silo MinIO fork, keep S3 API), C (B now, A later) — chose **"go with A, Azure Blob throughout"**.

---

## Backup destination

| Option | Description | Selected |
|--------|-------------|----------|
| Separate Azure account | Dedicated storage account, other region, immutability + soft delete. One cloud; survives deletion / compromised credential, not subscription loss (D-12's case consciously given up). | ✓ |
| Keep AWS S3 for backups | Only the dump stays on AWS S3, preserving Phase 29 D-12's provider independence; keeps one AWS account and key pair. | |
| Azure now, off-Azure later | As option 1, plus an off-Azure copy deferred to Phase 32. | |

**User's choice:** Separate Azure account (Recommended)

---

## Staging/prod credentials

| Option | Description | Selected |
|--------|-------------|----------|
| Workload Identity | AKS federated identity + RBAC role scoped to the account; no stored secret. Azurite keeps its dev connection string behind one config switch. | ✓ |
| Connection string in a Secret | Account key in a sealed Secret; one code path identical to Azurite, but a long-lived full-control key. | |
| You decide | Research/planning picks within the least-privilege contract. | |

**User's choice:** Workload Identity (Recommended)

---

## Existing local images

| Option | Description | Selected |
|--------|-------------|----------|
| Reseed | Demo seeder re-uploads to Azurite; dev DB image URLs rewritten; hand-uploaded dev images lost. | ✓ |
| Migrate the bytes | Throwaway build of archived MinIO, copy objects into Azurite, rewrite URLs. | |
| Fresh dev volume | Drop local DB + media volumes; resets the V66 / 5-shop dev state. | |

**User's choice:** Reseed (Recommended)

---

## Public image URL origin

| Option | Description | Selected |
|--------|-------------|----------|
| Raw Blob endpoint | `https://<account>.blob.core.windows.net/...`; no DNS or extra service; custom domain deferred to Phase 32 as a one-off rewrite. | ✓ |
| Custom domain now | `media.olajay.co.uk` via Front Door/CDN; paid service (cost unverified), depends on the blocked DNS. | |
| Store keys, not URLs | Persist keys, compose URLs at read time; most durable, largest data-model scope. | |

**User's choice:** Raw Blob endpoint (Recommended)

---

## Claude's Discretion

- Container layout (public derivatives container + private quarantine container — Blob access is per container, and quarantine is currently only a key prefix in the public bucket).
- Regions, SKU/redundancy, Azurite digest and port, SDK version, quarantine lifecycle rule vs the application sweep, and the internal shape of the storage seam.

## Deferred Ideas

- Custom media domain (Phase 32 cutover).
- Off-Azure backup copy (explicitly not chosen; revisit before real customer data).
- Persisting keys instead of absolute URLs for the legacy flat image columns.
