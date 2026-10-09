---
phase: 37-real-world-operations-readiness
plan: 15
subsystem: gate
tags: [runtime-parity, compose, keycloak, d-06, d-07, d-08, d-26, staff-access, finance, playwright, pr-cut, uxt-003, uxt-004, uxt-018]

requires:
  - phase: 37-08
    provides: "vendor-realm user profile with tenant_id admin-only (template), the /invite fragment-token contract"
  - phase: 37-09
    provides: "e2e/staff-invite.spec.ts and the inverted dashboard-interface-corrections test 2, listed for this gate"
  - phase: 37-12
    provides: "per-shop finance UI, void dialog and Voided badges (jest-only until this gate)"
  - phase: 37-14
    provides: "VAT display rule, VAT rate select, import target and copy-to-shop (jest-only until this gate)"
provides:
  - "37-B proven on the delivered compose runtime: every image rebuilt, realm re-imported, V76..V79 applied, freshness 0 unverified, OpenAPI live 120/120"
  - "Live RED (pre-rebuild) and GREEN (rebuilt) for staff-invite.spec.ts and interface-corrections test 2"
  - "Live D-06/D-07/D-08/D-26 proofs, MCP create_order, every 37-B good re-verified"
  - "financeShopId: a one-shop manager's finance reads their pinned shop (found live, fixed test-first)"
  - "Local PR branch phase-37-b-staff-access (24d58c1b), one squash commit on origin/main, tree equal to tag cut/phase-37-b-staff-access"
affects: [gsd-ship-37-B, 37-C, 37-16, phase-37-uat, customer-access-ruling]

actuals:
  tokens: 28919
  tasks: 3
  commits: 5
plan_head_before: 67c0b9b4c7db4f312a6f67d8e3ad88725322925a
plan_head_after: 96d5a94b5a007138864cb9948fbee2b033f9e024

tech-stack:
  added: []
  patterns:
    - "A cache-hit rebuild still re-tags an image with a new id; compose up --build does not recreate an unchanged container, so the freshness gate's mode B fires and --force-recreate is the remedy"
    - "Dashboard screenshots: grow the viewport to the inner scroller's measured height, since Playwright fullPage only captures one viewport of an inner-scrolling shell"
    - "An account-console refusal is proven with the bearer the console itself sends, plus a control edit that succeeds"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-15-screens/
  modified:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-15-gate-37B.md
    - frontend/lib/finance-api.ts
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
    - frontend/e2e/staff-invite.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/WINDOWS.md

key-decisions:
  - "37-15 Task 2: proceed — 37-B may drive the shared compose stack and deploy D-06; dev-stack access-loss query found nobody loses access, owner ruling 2026-10-09"
  - "37-15: a non-admin with no tenant-wide role whose read-scoped shop list holds exactly one shop reads that shop on finance (financeShopId), the same condition under which the switcher pins the label; Group admins and tenant-wide roles keep All shops"
  - "37-15: /api/v1/customers stays readable and writable by an ungranted tenant user (Phase 23 left CustomerController tenant-scoped); not changed here because choosing the customer access rule is architectural (Rule 4); WINDOWS #29, owner ruling needed before the 37-B PR merges"
  - "37-15: the PR branch carries no upstream (origin/main tracking unset after the cut) so a bare push cannot target main; /gsd-ship sets it"

patterns-established:
  - "A spec that has only ever been listed fails first on its instrument: assert toast text inside the toast region and scope list assertions to the row of this run's data"

requirements-completed: [RWO-004, RWO-018, RWO-026, RWO-027, RWO-046, RWO-047]

coverage:
  - id: G1
    description: "Every static gate green on the 37-B head (docs-freshness, check-doc-metrics, render-invariants, env-contract, no-create-extension, strict-scoping-default, gate-enforcement, doc-citations, residue) and the full core-java, jest and build suites"
    verification:
      - kind: other
        ref: "evidence Part 1 (993229eb) and 2.9 final run: every gate rc=0; core-java 1621 unit + 1006 integration 0 failed; jest 205/2383 passed; build rc=0"
        status: pass
    human_judgment: false
  - id: G2
    description: "Owner decision recorded before any stack action"
    verification:
      - kind: other
        ref: "Task 2 blocking-human checkpoint answered 'proceed' (2026-10-09); Part 1.6 read-only impact list: nobody loses access"
        status: pass
    human_judgment: false
  - id: G3
    description: "Runtime parity: pre-rebuild identity, every image rebuilt, check-runtime-freshness 0 unverified, image id = tag id, application.yml and V76..V79 read out of the running jar, flyway V76..V79 applied, OpenAPI live check 120/120"
    requirement: "RWO-004"
    verification:
      - kind: other
        ref: "evidence 2.1, 2.3, 2.4: freshness rc=1 before (core-java, frontend image-not-rebuilt), rc=0 after; the gate's mode B fired on a real cache-hit retag (mcp-server), cleared by --force-recreate; OpenAPI rc=1 (114 paths) before, rc=0 (120) after"
        status: pass
    human_judgment: false
  - id: G4
    description: "staff-invite.spec.ts RED on the pre-rebuild runtime and GREEN after the rebuild and realm re-import; interface-corrections test 2 likewise"
    requirement: "RWO-003"
    verification:
      - kind: e2e
        ref: "evidence 2.2 (live RED, no 'Invite someone' region; test 2 Received 1 for 'cannot send … invite') and 2.5 (GREEN 1 passed; test 2 passed); instrument fix 425b7e67"
        status: pass
    human_judgment: false
  - id: G5
    description: "Live D-07/D-26: tenant_id admin-only in the realm profile, the invitee's admin representation and token carry tenant_id, the account-console change of tenant_id is refused while a firstName change is accepted"
    requirement: "RWO-003"
    verification:
      - kind: other
        ref: "evidence 2.6: profile tenant_id view/edit ['admin']; token claim tenant_id …0001; account POST 400 error-user-attribute-read-only; control 204; admin re-read unchanged"
        status: pass
    human_judgment: false
  - id: G6
    description: "Live D-06/D-08: an ungranted login sees 'You don't have access yet'; a granted manager whose last grant is revoked gets 403 on the next call with the same token and sees 'You no longer have access'; MCP create_order works through the seeded service-account grant"
    requirement: "RWO-004"
    verification:
      - kind: e2e
        ref: "evidence 2.6: staff/me grantedShopIds [] and shop-scoped write 403; grant → 200, revoke → same token 403; browser headings for both states; MCP create_order status 201 (ORD-00000000-20261009-B7DCBF11)"
        status: pass
    human_judgment: false
  - id: G7
    description: "Goods P2-KEM-20, P2-KEM-21, P2-KEM-22 (cross-shop product, last-admin guard, kitchen one shop, ledger VAT), F1, F4, P2-TUN-15, P2-RAV-19 hold on the rebuilt runtime"
    verification:
      - kind: other
        ref: "evidence 2.7: 14/14 other-shop attempts refused; revoke-one next-request 403 with the other shop 200; last-admin 409; cross-tenant product 404×3 with a 200 control; MCP tenant-B shop 403; money/GDPR 403×4; VAT 1690 = per-row round-down"
        status: pass
    human_judgment: false
  - id: G8
    description: "37-12/37-14 owed live proofs: void dialog, Voided badges in detail, list and finance with the reversal; manager banner, figures and export filename; B7 note for a sole trader and the VAT row for a registered seller; Standard default and Zero persisted; import target; copy counts"
    requirement: "RWO-018"
    verification:
      - kind: e2e
        ref: "evidence 2.6, 2.7 (browser and API); WINDOWS #25 and #28 closed"
        status: pass
    human_judgment: false
  - id: G9
    description: "A one-shop manager can reach their own finance figures (found live at this gate)"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "finance-scope.test.tsx 'a manager with exactly ONE shop on All shops reads that shop': RED a1d41e5a, GREEN f4c53fb4; the two-shop 'Choose a shop' test unchanged and green"
        status: pass
      - kind: e2e
        ref: "evidence 2.8 live RED (Choose a shop, no call) and 2.6 live GREEN on the rebuilt image (banner 'Showing Peckham Jollof Co. only', export jtoye-finance-peckham-jollof-co-start-2026-10-09.csv)"
        status: pass
    human_judgment: false
  - id: G10
    description: "PR branch phase-37-b-staff-access cut fresh from origin/main as one squash commit, tree equal to cut/phase-37-b-staff-access, immediately after check-branch-behind-base passed; not pushed"
    verification:
      - kind: other
        ref: "plan verify block rc=0: behind-base PASS (119 ahead, 0 behind); tree c010219c… equal; rev-list count 1 (24d58c1b); fail direction: branch tree vs origin/main tree differ; ls-remote 0 heads"
        status: pass
    human_judgment: false
  - id: H1
    description: "Owner sign-off on the VAT helper copy (UI-SPEC open item 1)"
    requirement: "RWO-046"
    verification: []
    human_judgment: true
    rationale: "Copy approval is the owner's; the shipped wording is quoted in evidence 2.11 item 1 and shown in product-form-vat-1280.jpg. workflow.human_verify_mode is end-of-phase, so it is harvested into the phase UAT."
  - id: H2
    description: "Screen review at 390/768/1280: Staff page (People, Invite, Pending), invite page, both no-access states, finance banner (admin and manager), void dialog and voided detail, import page; the invitation email"
    verification: []
    human_judgment: true
    rationale: "Visual judgement is a person's. Screens are in evidence/37-15-screens/. Two items are flagged for the reviewer: a 44px clip in the People card at 390 (document overflow 0) and a double full stop in the email when the shop name ends with a period. Motion cannot be judged from a screenshot."

duration: 162 min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 15: Sub-theme 37-B gate — runtime proof and PR cut Summary

**37-B is proven on the rebuilt compose runtime. The images were pre-37-B before the rebuild and are fresh after it, with 0 unverified services and V76..V79 applied. The staff-invite spec went RED live and then GREEN, and the live D-06/D-07/D-08/D-26 and MCP proofs pass, along with every 37-B good. A dead end in the one-shop manager's finance view was found and fixed test-first. The PR branch `phase-37-b-staff-access` is cut as a single squash commit (`24d58c1b`).**

## Performance

- **Duration:** about 162 minutes over two sessions. Task 1 ran from 01:47Z to roughly 02:30Z; Task 3 ran from 08:56Z to 09:40Z.
- **Started:** 2026-10-09T01:47Z
- **Completed:** 2026-10-09
- **Tasks:** 3 (Task 2 was the owner's decision)
- **Files modified:** 44, including 32 screenshots

## Accomplishments
- **RED, measured live on the pre-rebuild runtime.** All four images were tagged on 2026-10-07, before the first 37-B commit. The running jar had strict scoping set to false, and the schema stood at V75. The staff-invite spec failed because the page had no "Invite someone" region, and the inverted test 2 failed because the old "cannot send an invite" copy was present.
- **Rebuild and parity.** Every image was rebuilt and the containers recreated. Flyway applied V76..V79, and core-java logged `strict=true`. The vendor realm was re-imported with `--override true`, which made `tenant_id` an admin-only attribute. The freshness gate passed with 0 unverified services; it also caught a real case where a container was not recreated, and that was fixed. The OpenAPI check read 120/120 against the live service, up from 114.
- **GREEN.** The staff-invite spec passed after two instrument fixes. Kitchen-flow, storefront-flows, dashboard-a11y-nightly and interface-corrections passed as well; the one failure in the final combined run came from rate limiting and passed when re-run on its own.
- **Live proofs.** An ungranted user gets an empty access result and a 403 on writes; after a grant and a revoke, the same token gets a 403 on its next call. Both no-access headings render in the browser. An invitee's `tenant_id` appears both on the admin side and in their token, and their own account-console attempt to change it gets a 400, while a firstName change succeeds as the control. MCP `create_order` returns 201.
- **Goods.** All 37-B goods hold. The void, finance, VAT display, product VAT rate, import target and copy behaviours owed by 37-12 and 37-14 are now proven live.

## Task Commits

1. **Task 1: static gates and full suites on the 37-B head:** `993229eb` (docs)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "proceed". No commit.
3. **Task 3: RED, rebuild, GREEN, live proofs, goods and the PR branch pointer**
   - `425b7e67` (test): the staff-invite spec's toast and pending assertions are scoped to their own nodes.
   - `a1d41e5a` (test): RED for the one-shop manager's finance.
   - `f4c53fb4` (fix): a one-shop manager's finance reads the shop it is pinned to.
   - `96d5a94b` (docs): the Part 2 evidence, screenshots, refreshed metrics and the WINDOWS ledger.
   - PR branch `phase-37-b-staff-access` at `24d58c1b` (a squash of `cut/phase-37-b-staff-access` = `96d5a94b`; local only, not pushed).

**Plan metadata:** this SUMMARY, plus STATE, ROADMAP and REQUIREMENTS, go in the docs commit that follows.

## Files Created/Modified
- `evidence/37-15-gate-37B.md`: Part 2 (sections 2.1 to 2.11)
- `evidence/37-15-screens/`: 32 JPEGs at 390, 768 and 1280, plus the redacted invitation email
- `frontend/lib/finance-api.ts`, `frontend/app/dashboard/finance/page.tsx`: `financeShopId`
- `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx`: the one-shop manager test
- `frontend/e2e/staff-invite.spec.ts`: assertions scoped to their own nodes
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: 5141 logical invocations, 2383 Jest blocks
- `.planning/WINDOWS.md`: entries 19, 20, 22, 23, 24, 25 and 28 are now fixed; 29 is added

## Decisions Made
See `key-decisions`. The one with consequences beyond this plan is WINDOWS #29: customers can be read by a tenant user who has no grant.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The staff-invite spec failed on its own instrument the first time it ran live**
- **Found during:** Task 3 GREEN
- **Issue:** Radix renders every toast twice, so `getByText` matched two nodes. Separately, a leftover pending invitation for the same shop duplicated the "Shop manager · {shop}" text. In both cases the product had behaved correctly.
- **Fix:** toast assertions now read inside the Notifications region, and the Pending assertion is scoped to this run's row with `toHaveCount(1)`.
- **Files modified:** frontend/e2e/staff-invite.spec.ts
- **Commit:** 425b7e67

**2. [Rule 1 - Bug] A one-shop manager could not reach their finance figures**
- **Found during:** Task 3 live proofs, on the rebuilt frontend
- **Issue:** the switcher shows a pinned label with no `<select>` while the shop context stays "all". Finance then asked the manager to pick a shop and made no request. 37-12's jest test used a list of two shops, which a manager with one grant never has.
- **Fix:** `financeShopId`, written test-first (RED `a1d41e5a`, GREEN `f4c53fb4`). The images were rebuilt afterwards, and the live run now shows the banner, the figures and the export.
- **Files modified:** frontend/lib/finance-api.ts, frontend/app/dashboard/finance/page.tsx, finance-scope.test.tsx
- **Commits:** a1d41e5a, f4c53fb4

**3. [Rule 3 - Blocking] Containers were not recreated after a cache-hit retag**
- **Found during:** Task 3, both rebuilds
- **Issue:** `up -d --build` re-tagged images that had not changed with new ids but left their containers running, so the freshness gate reported `container-not-recreated` with rc=1.
- **Fix:** `up -d --no-deps --force-recreate` for the affected services, after which the gate returned rc=0.
- **Commit:** none (runtime only)

**4. [Rule 3 - Blocking] The prose metrics drifted after the new test**
- **Fix:** ran `docs-freshness.sh --write` and updated the counts in CLAUDE.md, AGENTS.md and README.md; both gates now return rc=0. Committed in 96d5a94b.

### Not fixed (Rule 4, owner ruling needed)
- **`/api/v1/customers` is open to an ungranted tenant user.** Such a user can GET all 8 tenant A customers (200) and POST a new one (201). Phase 23 left CustomerController scoped to the tenant rather than to shops, and with strict scoping off that was never visible. Deciding the customer access rule changes the access model, so this goes to the owner as WINDOWS #29. It should be ruled on before the 37-B PR merges.

### Observations (recorded only)
- After a revoke, pages that make a tenant-wide, read-scoped call, such as Orders on All shops, return 200 with filtered rows and show no refusal. "You no longer have access" appears on the first refused shop-scoped call. No data is exposed.
- The running stack had been created from a checkout that has since been deleted (`JToye_OaaS_2026-phase37`). The rebuild re-homed it to this checkout under the same compose project, and the volumes were kept.
- The final combined live run hit the per-tenant rate limit (100/min) on one test, which then passed when run on its own.

**Total deviations:** 4 auto-fixed (2 bugs, 2 blocking) and 1 escalated. **Impact:** both bugs only became visible on the live runtime, which is what this gate is for.

## Issues Encountered
- The first account-API attempt used an `admin-cli` token, and both the edit and the control returned 401, so it proved nothing. It was replaced by a bearer captured from the real account console, with a control edit that succeeds.
- My first ledger-VAT recompute rounded to the nearest penny and gave 1692. The code rounds down per row (HMRC Notice 700 §17.5.1), which gives 1690. The recompute was corrected against the code, and the API value was right all along.

## Known Stubs
None introduced.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: authz | core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java | Under D-06, a tenant user with no grant can read and create tenant customers (PII). This predates 37-B; D-06 is what made it visible. WINDOWS #29. |

## User Setup Required
None for the dev stack. For staging and production, the operator steps from 37-USER-SETUP.md and the D-06 impact query (Part 1.6) still apply before any k8s rollout.

## Next Phase Readiness
- **The owner** opens the 37-B PR from `phase-37-b-staff-access` with `/gsd-ship`. The order is push first, then review; review fixes, merges from main and the CHANGELOG entry citing the PR number all land on that branch. WINDOWS #29 needs a ruling before merge.
- **D-04 hand-off:** origin/main (`1773fe53`) had nothing new at the cut (0 behind), so merging it into `phase-37-ops-readiness` was a no-op. The next sub-theme starts from current main.
- **Phase UAT:** H1 (VAT helper copy) and H2 (screens, the 390px People-card clip, the email's double full stop).
- **Requirements:** RWO-004, -018, -026, -027, -046 and -047 are marked complete (ready-ids 6/7). RWO-003 is blocked because a later plan also declares it.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

- FOUND: evidence/37-15-gate-37B.md (contains "check-runtime-freshness" and "LastTagTime"), evidence/37-15-screens/ (32 JPEGs + invite-email.txt), frontend/lib/finance-api.ts (financeShopId)
- FOUND commits (ancestors of HEAD): 993229eb, 425b7e67, a1d41e5a, f4c53fb4, 96d5a94b; `git rev-list --count 67c0b9b4..96d5a94b` = 5
- FOUND: phase-37-b-staff-access 24d58c1b, one commit above origin/main, tree equal to cut/phase-37-b-staff-access
