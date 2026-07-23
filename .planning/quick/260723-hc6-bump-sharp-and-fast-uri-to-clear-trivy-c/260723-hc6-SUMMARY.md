---
quick_id: 260723-hc6
slug: bump-sharp-and-fast-uri-to-clear-trivy-c
date: 2026-07-23
status: complete
branch: feature/phase-23-vendor-scoped-access
---

# Quick Task 260723-hc6 — SUMMARY

## What

Cleared the one red CI check on PR #255 (Phase 23): **Security Scan → Trivy "fail build on fixable
CRITICAL/HIGH"**. Two newly-published (2026) transitive npm CVEs were the cause — a vuln-DB time-bomb
(PR was green at `b3fcc9c` on 2026-07-21), not a code regression.

## Changes

| File | Change |
|---|---|
| `frontend/package.json` | + `"overrides": { "sharp": "^0.35.0" }` |
| `frontend/package-lock.json` | sharp 0.34.5 → **0.35.3** (+ restructured `@img/*` platform binaries, libvips 1.3.2, `@emnapi/runtime` for wasm variants) |
| `mcp-server/package.json` | + `"overrides": { "fast-uri": "^3.1.4" }` |
| `mcp-server/package-lock.json` | fast-uri 3.1.3 → **3.1.4** |

CVEs closed: sharp/libvips CVE-2026-33327/33328/35590/35591 (fixed ≥0.35.0); fast-uri CVE-2026-16221 (fixed ≥3.1.4).
Diff verified: only sharp/fast-uri-related nodes moved — no unrelated bump (next/react/etc. untouched).

## Verification (proof)

- `mcp-server`: `npm test` (vitest) → **27/27 passed**; installed fast-uri = 3.1.4.
- `frontend`: `npm run build` (next build) → **exit 0**, all routes compiled (incl. Phase 23 `/dashboard/staff`).
- `frontend`: `npm test` (jest) → **56 suites / 360 passed**.
- `frontend`: sharp loads at runtime → libvips 8.18.3; installed sharp = 0.35.3.
- `bash scripts/docs-freshness.sh` → **OK 1574** (no test-count drift; schema V57 unchanged).
- Trivy not installed locally; CI re-run is the definitive gate (config: `scan-type: fs`, `severity: CRITICAL,HIGH`,
  `ignore-unfixed: true`, `exit-code: 1`). The npm-audit highs (next/brace-expansion/js-yaml) were already present
  at the flagged run and Trivy did NOT gate them → fixing sharp + fast-uri clears exactly the two it caught.

## Round 2 (same task) — Trivy DB ticked again 2026-07-23

After the sharp+fast-uri fix pushed (`af196dc`/`beb6666`), the Security Scan **still failed** — but on a
*different, newly-published* finding: the Trivy DB advanced within the day and now flagged **`next` 16.2.10
with 4 HIGH CVEs** (the sharp/fast-uri findings were confirmed cleared). Genuine + important, so fixed:

| CVE | Title | Fixed in |
|---|---|---|
| CVE-2026-64641 | Next.js DoS in App Router Server Actions | 16.2.11 |
| CVE-2026-64642 | Next.js middleware/proxy **bypass** in App Router (this app uses Next middleware for NextAuth) | 16.2.11 |
| CVE-2026-64645 | Next.js SSRF in rewrites | 16.2.11 |
| CVE-2026-64649 | Next.js SSRF in Server Actions | 16.2.11 |

Fix: `frontend/package.json` next `^16.2.2` → **`^16.2.11`** (explicit security floor); lockfile → next 16.2.11.
Re-verified: `next build` exit 0, jest **360/360**. Diff next-scoped only. Remaining npm-audit highs
(brace-expansion, js-yaml) are NOT gated by Trivy in any run and were left untouched.

## Outcome

- Committed atomically on `feature/phase-23-vendor-scoped-access` (PR #255 branch — no new branch, main untouched).
- Round 1 `af196dc` (sharp+fast-uri) + STATE `beb6666`; Round 2 next bump follows.
- NOTE: the Trivy fs gate scans against a daily-updating DB, so a previously-green PR can go red with no code
  change. This is a standing risk on any long-lived PR, not a Phase 23 defect.
