# PII Exposure Assessment — Public-Repo Database Dumps

**Reference:** Issue #79 / remediation item **P0-3** (`docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md`)
**Assessment date:** 2026-07-08
**Regulation:** UK GDPR (Data Protection Act 2018)
**Classification:** Internal record — Art 33(5) / Art 5(2) accountability
**Status:** Repo-side remediation shipped in this PR; git-history rewrite tracked separately (orchestrator scope)

---

## 1. Incident Summary

**What:** PostgreSQL database dumps (`.sql.gz`) were committed into the public GitHub
repository `github.com/Bralabee/JToye_OaaS_2026` under the `backups/` directory.

**When:** First introduced 2025-12-31 in commit `331da2d`.

**Where:**
- One dump tracked at `HEAD`: `backups/jtoye_jtoye_20251231_121414.sql.gz`.
- A second dump, `backups/jtoye_jtoye_20251231_120249.sql.gz`, was added in commit
  `331da2d` and later deleted from the working tree; both blobs remain reachable in
  git history under `backups/`.

The repository is public, has 0 forks, no branch protection, and a single remote branch
(`main`), with tags `v0.1.0`, `v1.2.0`, `v1.3.0`, `v2.0.0`, `v2.1`.

## 2. Exposure Window

From **2025-12-31** (commit `331da2d`) through the **planned git-history-rewrite date**.
The tracked-at-HEAD exposure is closed by this PR (untrack + relocate). Residual reachable
blobs in history persist until the history rewrite and subsequent GitHub-side garbage
collection complete (see §6).

## 3. Data Characterization

- **Data set:** synthetic/development data only. All rows belong to the seed development
  tenant `00000000-0000-0000-0000-000000000001`, with timestamps of 2025-12-31.
- **Customers table:** the `customers` table in the tracked dump is **empty**.
- **Rows carrying email addresses:** a handful only — roughly 2 `customers_aud` rows and
  ~5 `order` rows.
- **Email domains:** every email address across all 147 dump/error files uses a synthetic
  development domain (for example the domains `@test.com` and `@example.com`, among other
  non-routable dev domains). **Zero** addresses use a real consumer provider
  (no gmail / yahoo / hotmail / outlook / icloud, etc.).
- **Credentials / secrets:** none. No API keys, no password hashes. The strings containing
  the word "Password:" are `pg_dump` authentication-failure prompts emitted into the 128
  gzipped error-log files (see §8), not stored secrets.

There are no personal names and no real-data-subject identifiers in the exposed material.

## 4. UK GDPR Art 33 / Art 34 Analysis

**Personal-data test:** The exposed rows contain no personal data of any real, identifiable
data subject. All identifiers resolve to synthetic development fixtures under the seed
tenant, and all email domains are non-routable development domains.

**Art 33 (notification to the supervisory authority):** Because no personal data of real
data subjects was exposed, there is **no personal-data breach** engaging a duty to notify
the ICO. **No Art 33 notification is required.**

**Art 34 (communication to data subjects):** For the same reason, there are no affected
data subjects to communicate with. **No Art 34 communication is required.**

**Why this record exists:** This assessment is retained under the **accountability
principle (Art 5(2))** and satisfies the **Art 33(5)** requirement to document the facts,
effects, and remedial action for any incident assessed against the breach-notification
duty — including those concluded as non-notifiable.

## 5. Credential-Rotation Assessment

**Not applicable.** No credentials, tokens, API keys, or password hashes are present in any
of the 147 files. The local development database password is unchanged, is development-only,
and is out of scope for this incident.

## 6. Residual Risk

- **Dangling git objects:** After the history rewrite, the dump blobs become unreachable but
  are not immediately purged. GitHub retains unreachable objects until its own server-side
  garbage collection runs. **Follow-up:** contact GitHub Support to expedite purging the
  dangling objects after the force-push completes.
- **Pre-existing clones:** Any clone or fork taken while the repo was public during the
  exposure window may retain the blobs. The repo currently shows 0 forks, but unknown
  ad-hoc clones cannot be enumerated. Risk is low given the synthetic-only data set.

## 7. Remediation Log

**This PR (repo-side, P0-3):**
- Untracked the tracked dump (`git rm --cached`) and relocated all 147 dump/error files
  off the repository tree to `$HOME/jtoye-db-backups` (mode 700); no data deleted.
- Hardened `infra/backups/backup.sh` so the default `BACKUP_DIR` resolves off-tree
  (`$HOME/jtoye-db-backups`), stopping the nightly cron from writing into the repo; the
  `BACKUP_DIR` env override is preserved.
- Broadened `.gitignore` from `backups/*.sql.gz` to `backups/` so a reappearing backups
  directory stays fully untracked.
- Added `.github/workflows/pii-guard.yml`, a zero-tolerance CI guard that fails any push or
  PR tracking a `backups/` path or a `.sql.gz` file.

Traceability: remediation item **P0-3** in `docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md`.

**Planned (orchestrator scope, tracked separately):**
- Git-history rewrite (git-filter-repo) to strip the dump blobs from all reachable history,
  followed by a force-push and a request to GitHub Support to garbage-collect the dangling
  objects. **Out of scope for this PR.**

## 8. Side-Finding (Out of Scope — Operational Follow-Up)

Of the 147 relocated files, **128 are gzipped `pg_dump` error logs**, not real backups. The
nightly backup cron has been **silently failing since approximately February 2026** with
`fe_sendauth: no password supplied` — meaning no valid database backup has been produced for
months. Only 19 of the 147 files are genuine dumps.

This is a **separate operational reliability defect** (backup pipeline broken; no alerting on
failed backups) and should be triaged as its own follow-up: fix the cron credentials/env and
add failure alerting so a silently-failing backup surfaces. It is flagged here for visibility
but is not remediated by this PR.
