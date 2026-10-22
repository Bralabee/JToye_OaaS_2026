## Deferred Items

- check-gate-enforcement.sh pass 1 counts a COMMENT mention of a check script as wiring
  status: open
  **Found by:** plan 30-01, Task 2 break arm (2026-10-22).
  **What:** with every `bash scripts/check-stripe-pk-var.sh …` line deleted from
  `.github/workflows/ci-cd.yaml`, the one remaining comment that named the file kept
  `scripts/check-gate-enforcement.sh` at rc 0; deleting the comment too gave rc 1 naming the
  script. Pass 2 (`<name>.sh --selftest` on a non-comment line) strips comments; pass 1 ("a
  check script is referenced by some workflow") apparently does not. So a check script is
  reported wired while no workflow runs it, as long as some comment names it.
  **Done in 30-01:** the 30-01 comment no longer names the file (commit 90df31a8), and the arm
  was re-run: invocations removed now gives rc 1. The gate itself is unchanged (outside this
  plan's files and scope).
  **Route:** apply pass 2's `strip_comments` to pass 1's reference search, with a selftest arm
  that plants a comment-only reference.

- The primary checkout's `frontend/node_modules` is stale: tailwindcss 3.4.19 installed, 4.3.3 locked
  status: open
  **Found by:** plan 30-01 (2026-10-22). `node -p "require('tailwindcss/package.json').version"`
  prints 3.4.19 while `frontend/package.json` says ^4.3.3 and `package-lock.json` pins 4.3.3
  (Phase 39 moved to Tailwind v4). Effects on this checkout only: full-program `tsc --noEmit`
  reports 5 errors in `frontend/lib/__tests__/layout-widths-css.test.ts` (`tailwindcss` has no
  `compile`, plus four implicit-any parameters that follow from it), and the full Jest run fails
  15 tests in 3 suites (`contrast-literals`, `layout-widths-css`,
  `dashboard-pending-badge-contrast`), all because the v3 palette resolves to hex where the tests
  pin v4 oklch values. 30-01 touches no package, Tailwind or style file; the tracer's
  `docker build` runs `npm ci` from the lock and is unaffected. Not fixed here: `npm ci` in a
  checkout another session may be driving is an environment change, not a plan task.
  **Route:** `npm --prefix frontend ci` on the primary checkout when no other session is using
  it, then re-run the three suites.
