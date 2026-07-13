# QA Surface-Parity Ledger

`surface-ledger.json` is the recorded **"what is good"** baseline for the J'Toye OaaS product surface — the observable capabilities a real user relies on, not test greenness.

## Why this exists
Tests only protect what someone once encoded. A "pristine rebuild" can ship green while silently dropping a previously-good capability (this happened in Phase 19-09: the demo catalogue lost all its product images, every gate passed, and only a user caught it). This ledger closes that gap: it records routes, per-page navigation destinations, content density (e.g. % of products with images), and core journey outcomes so that **any disappearance or material decrease is a detectable regression-by-omission** — even when all tests are green.

## How it's used
- **QA council** (`/qa-discover`) emits a fresh `surface-inventory.json` each run and **diffs it against this ledger**. A route gone, a nav destination lost from a page, content density collapsed (100% imaged → 0%), or a journey that completed before and doesn't now = a finding (min. High for a lost capability) unless a recorded decision (ADR/plan/changelog) retired it.
- **Per-increment gates** (phase verification, CI smoke) can diff against it between council runs — the council is episodic, but omissions ship with increments.

## Updating the ledger
Update **only** when an improvement is proven (raise the baseline) or a retirement is explicitly decided (record why). Never edit it silently to make a diff pass — that defeats its purpose. Tie every change to a commit/PR that explains it.

## Provenance
Seeded by QA-council run `20260713-152124` on branch `feature/ux-mobile-nav-rsc-fixes`. See that run's `QA-COUNCIL-REPORT.md` (under the gitignored `.qa-council/`) for the full evidence, including the correction of a rate-limit measurement artifact in the brixton census.
