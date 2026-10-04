## Coordinator adjustments

Applied 2026-10-04 before filing. This file is hand-maintained and appended verbatim to issue-plan.md by `consolidate.cjs` (so a regeneration cannot drop it); the priority moves are also recorded per cluster as `coordinatorAdjustment` in `catalogue.json`. These supersede the priorities and labels in sections B and D above.

- **UXT-008 P0 → P1.** The '20% OFF' promotion the testers saw is E2E seed data; the real defect is that promotions are display-only and never applied to an order.
- **UXT-015 P0 → P1.** A PECR cookie-consent issue (Stripe JS and fraud cookies on a cash-only checkout), not direct harm to a user.
- **UXT-019 P0 → P1.** A trading-disclosure gap for the platform company, possibly fixable by configuration (`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is declared but set in no runtime).
- **#727 (UXT-017) P3 → P0.** Comment posted AND the priority label changed from `P3` to `P0`: pass 2 reproduced the sync orphan live and showed a wrong-allergen safety consequence (an order line for the orphan records allergenMask 0 where the real dish declares 256). It is no longer a latent data-hygiene defect.
- **Other "same" matches (#102, #208, #452, #460, #587):** the `ux-persona-test` label is added and the comment posted; their other labels are unchanged.
- **Type labels:** `bug` for bug, `enhancement` for gap/enhancement and env/tooling, `compliance` for content/legal. The plan once proposed `tech-debt` for env/tooling (UXT-045, UXT-068, UXT-090); those three were filed with `enhancement` (#814, #837, #859), and `consolidate.cjs`'s TYPE_LABEL now maps env/tooling to `enhancement`, so section B matches GitHub.
- **Regression-of-closed (UXT-001 → #84, UXT-006 → #88, UXT-074 → #465):** filed as new issues titled "Regression of #N: …"; the closed issue gets a one-line pointer comment and is not reopened.
- **Epic title:** "[UXT-EPIC] Persona user-testing 2026-10-03: real-world readiness findings (pass 1 + 2)".
- **Resulting counts:** P0 16 · P1 32 · P2 49 · P3 26 (was 19 / 29 / 49 / 26).
- **Filing ledger:** `filed-issues.json` (clusterIds, title, number, url) is the record of what was filed.
