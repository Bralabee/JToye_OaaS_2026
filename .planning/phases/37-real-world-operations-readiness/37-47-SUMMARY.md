---
phase: 37-real-world-operations-readiness
plan: 47
subsystem: gate
tags: [runtime-parity, compose, keycloak, client-credentials, integration-credential, v82, edge, sync, mcp, webhooks, auto-pause, held, developers-page, staff, playwright, pr-cut, d-03, d-04, d-22, uxt-016, uxt-017, uxt-035, uxt-036, uxt-037, uxt-038, uxt-040]
status: complete

requires:
  - phase: 37-38
    provides: "the sub-theme gate shape and the PR-cut procedure; PR 918 (37-C) merged, reconciliation a proven no-op"
  - phase: 37-39
    provides: "trackInventory semantics and the narrow product PATCH (UXT-016)"
  - phase: 37-40
    provides: "shop-scoped /sync/batch with per-item results (UXT-017/037)"
  - phase: 37-41
    provides: "the edge relays core's results and 4xx problems"
  - phase: 37-42
    provides: "MCP create_order lands PENDING; list_products by shop with allergen names (UXT-036/040)"
  - phase: 37-43
    provides: "delivery-level webhook auto-pause, HELD events, owner email (UXT-038)"
  - phase: 37-44
    provides: "KeycloakAdminClient credential-client admin calls"
  - phase: 37-45
    provides: "V82 integration_credential and /api/v1/credentials"
  - phase: 37-46
    provides: "the Developers page and Staff Integration rows"
provides:
  - "Every static gate rc 0 and the full suites green on the 37-E head (unit 1821/0, integration 1221/0, jest 2790/240, build, tsc, lint, go 112, vitest 70)"
  - "RED on the 37-C runtime for every integrator proof, every image rebuilt, freshness PASS 4/4 with 0 unverified, V82 applied out of order, GREEN live"
  - "The D-22 credential lifecycle against the real Keycloak: create, a decoded token (tenant_id, aud core-api, exactly the chosen scopes), scoped 200/403/403, rotate, delete, credential-revoked on a token minted before the delete; the inline client representation was honoured"
  - "The 37-E goods re-verified live; the Developers and Staff pages at 390/768/1280 with the secret masked; the plan's Playwright line 66/0"
  - "Local PR branch phase-37-e-integrators (b0f26126), one squash commit on origin/main bfef0f6e, tree equal to tag cut/phase-37-e-integrators (1a481448); not pushed"
affects: [gsd-ship-37-E, 37-48, phase-37-uat, k8s-staging-rollout, k8s-production-rollout]

actuals:
  tokens: 20991
  tasks: 3
  commits: 5
plan_head_before: cb29dc2428259717323bc647ec990ed5ffa6cdae
plan_head_after: 2c9e7ec26a343fa85d57e96ab60cbfb16dea005c

tech-stack:
  added: []
  patterns:
    - "A gate probe script runs unchanged against the old and the rebuilt runtime, so its RED run is the fail direction of every GREEN row"
    - "A webhook receiver for a live probe is a public HTTPS endpoint with fixed behaviour (httpbin /status/503, /status/200, /status/200,503); the SSRF guard refuses anything local, as it should"
    - "Secrets seen during a live proof live only in 0600 scratch files; the evidence records length and a sha256 prefix, screens mask the field, and a secret-absence search runs with rg -uu plus a positive control"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-47-screens/ (18 PNGs)
    - .planning/phases/37-real-world-operations-readiness/37-47-SUMMARY.md
  modified:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-47-gate-37E.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - k8s/LOCAL.md
    - docs/ops/terminal-states.yaml
    - scripts/gates/object-store-residue-allowlist.conf
    - docs/architecture/layout-tiers.tsv
    - scripts/gates/ssr-routes.conf
    - AGENTS.md

key-decisions:
  - "37-47 Task 2: 'proceed' - 37-E may take the shared compose stack, including creating, rotating and deleting a test credential client in the jtoye-dev realm (owner, 2026-10-13). The core-api serviceAccountsEnabled question was not answered and the client was left unchanged"
  - "37-47: D-03 reconciliation of PR 918 is a proven no-op (squash-merged; origin/main is an ancestor of HEAD; the PR head's tree equals main's)"
  - "37-47: five static gates red from 37-E drift were fixed at their source (stale citations, the Developers route missing from two manifests, an allowlist line), not in the gates"
  - "37-47: findings at the gate (an MCP order for an unavailable product lands PENDING; the Developers header overflows at 768; revoked credentials stay on the Staff page) are recorded for the owner, not fixed at the gate"

patterns-established:
  - "The live credential proof decodes the minted token and calls one allowed endpoint, one other-shop endpoint and one unchosen-scope endpoint, then rotates and deletes, then replays a token minted before the delete"

requirements-completed: [RWO-016, RWO-017, RWO-035, RWO-036, RWO-037, RWO-038, RWO-040]

coverage:
  - id: D1
    description: "Static gates and full suites green on the 37-E head; five 37-E drift reds fixed at their source"
    verification:
      - kind: other
        ref: "evidence/37-47-gate-37E.md Part 1 (tracer line rc=0 0 0 0 0 0 0 on the rebuilt runtime, section 2.3)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:cleanTest :core-java:test :core-java:cleanIntegrationTest :core-java:integrationTest (1821/0, 1221/0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Runtime parity: every image rebuilt and recreated, freshness PASS 4/4 with 0 unverified, V82 applied and read out of the running jar, OpenAPI gate rc 0"
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh (rc 1 before, rc 0 after)"
        status: pass
      - kind: other
        ref: "bash scripts/check-openapi-snapshot-fresh.sh (rc 1 before, rc 0 after)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The D-22 credential lifecycle against the real Keycloak, including the decoded token claims"
    requirement: RWO-035
    verification:
      - kind: manual_procedural
        ref: "evidence/37-47-gate-37E.md 2.4 (cred.sh, cred2.sh)"
        status: pass
    human_judgment: true
    rationale: "VALIDATION manual-only row: a person reads the decoded tenant_id, aud and scope against the credential created"
  - id: D4
    description: "Integrator proofs RED on the old runtime and GREEN after: UXT-016, UXT-017/037 through the edge, UXT-036, UXT-040, UXT-038"
    requirement: RWO-016
    verification:
      - kind: manual_procedural
        ref: "evidence/37-47-gate-37E.md 2.2 and 2.5 (probes.sh red/green), 2.6 (webhook W1-W5)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The Developers page and show-once dialog at 390/768/1280, the Staff Integration row, long text, and the 37-E goods"
    verification:
      - kind: automated_ui
        ref: "playwright: evidence/37-47-screens/developers-show-once-{390,768,1280}.png and the plan's spec line 66/0"
        status: pass
    human_judgment: true
    rationale: "VALIDATION manual-only row (screens review); the 768 header overflow in deferred-items is a judgment call for the owner"
  - id: D6
    description: "The PR branch phase-37-e-integrators: one squash on origin/main, tree equal to cut/phase-37-e-integrators"
    verification:
      - kind: other
        ref: "bash scripts/check-branch-behind-base.sh && tree equality && rev-list count 1 (rc 0)"
        status: pass
    human_judgment: false

duration: "about 2h 10m over two sessions (Part 1 05:39Z to 06:44Z; Task 3 07:08Z to 07:46Z)"
completed: 2026-10-13
---

# Phase 37 Plan 47: Sub-theme 37-E gate (the integrator surface) and the PR cut Summary

**37-E is proven on the rebuilt runtime against the real Keycloak. On the 37-C images the gate found the old behaviour every 37-E plan set out to remove: a PUT turned stock tracking off; the edge answered `202 SUCCESS`, stored products with no shop and turned core's 403 into a 502; MCP ignored the shop and placed DRAFTs; and two failing webhook events paused the endpoint in 37 seconds and were dropped. After every image was rebuilt, the same script reported tracking kept at 12, `200 PARTIAL` with per-item results, a byte-identical 403, one shop's products with allergen names, and a PENDING order in the kitchen's New lane. A Group admin then issued a credential. Its Keycloak client-credentials token decoded to `tenant_id …0001`, `aud core-api` and exactly `catalog:read orders:write`. It got 200 on its shop, 403 on another shop and 403 on an unchosen scope. After rotation the old secret was `unauthorized_client`. After deletion the next mint was `invalid_client`, and a token minted before the delete was refused `credential-revoked`. The PR branch `phase-37-e-integrators` (`b0f26126`) is one squash commit on origin/main.**

## Performance

- **Duration:** about 2h 10m over two sessions. Part 1 (Task 1) started at 05:39:08Z. Task 3 ran from 07:08:27Z to 07:46Z.
- **Started:** 2026-10-13T05:39:08Z
- **Completed:** 2026-10-13
- **Tasks:** 3 (Task 2 was the owner's decision)
- **Files modified:** 11 text files and 18 PNGs in this plan's commits, plus this summary

## Accomplishments
- **Task 1 (Part 1, earlier session):** PR 918 is MERGED and the D-03 reconciliation is a proven no-op. Five static gates were red from 37-E drift and were fixed at their source. Unit 1821/0, integration 1221/0, jest 2790/240, the cold build, tsc, lint, go 112 and vitest 70 were all green.
- **RED on the 37-C images (2.1, 2.2):**
  - freshness was rc 1 with 4 of 4 images stale, and the OpenAPI gate showed 10 absent keys;
  - the jar had no credential classes and no V82, and there was no Developers route;
  - `/credentials` returned 404;
  - a PUT that omitted stock set `quantity_in_stock` to NULL;
  - the edge answered 202 SUCCESS and stored both products with `shop_id` NULL;
  - a `catalog:read` token got a 502 from the edge where core answers 403;
  - MCP `list_products` returned 3 shops and no allergens, and `create_order` gave a DRAFT;
  - two failing webhook events auto-paused the endpoint at 10 failed attempts, and both events were dropped at 5 of 8 attempts.
- **Rebuild and parity (2.3):** every image was built (executed, not cached) and recreated with `up -d`, never `start`. Freshness PASS 4/4 with 0 unverified. Each running image id equals its tag. V82 was applied `[out of order]`, and its sha256 read out of the running jar equals the committed file. The OpenAPI gate is rc 0 and the tracer line is `rc=0 0 0 0 0 0 0`.
- **D-22 credential lifecycle (2.4):**
  - the human-only (403), scope (400) and role (400) refusals hold;
  - create returned 201 with `no-store`, and a same-key replay returned 200 with no secret;
  - the core log showed the honoured INFO line and no fallback WARN;
  - the decoded claims are right, and the token carries no `preferred_username`, `email` or `realm_access`;
  - scoped calls: 200 on the granted shop, 403 on another shop, 403 for `customers:write`, 403 for `catalog:write`, and 403 at the edge;
  - the Staff 409 `staff-target-is-credential` holds;
  - rotate gave a new 32-character secret, and the old secret then got 401;
  - delete returned 204 twice, the next mint was `invalid_client`, and the earlier token was refused 403 `credential-revoked`.
  - A second credential, used through the edge, gave PARTIAL with `SHOP_ACCESS_DENIED` for both the ungranted shop and the Tenant B shop. MCP reads of Tenant B's shop got core's 403 (F4).
- **GREEN integrator proofs (2.5):** UXT-016 tracking stays at 12. UXT-017/037: PARTIAL with `SHOP_REQUIRED` and `UNKNOWN_TYPE`, and only the shop's row written. The edge's 403 is byte-identical to core's. UXT-040: 7 of 7 products from one shop, each with allergens. UXT-036: the order is PENDING, a browser showed its card in the New lane, and Accept moved it to CONFIRMED.
- **UXT-038 webhooks (2.6):**
  - two exhausted deliveries left the endpoint ACTIVE with cf 2;
  - the third exhausted delivery paused it, the fourth was HELD and a fifth was written HELD;
  - one plain-text owner email arrived in Mailhog;
  - the banner's "Resume and deliver held events" re-queued the held rows with a fresh budget;
  - a queued event held by a manual pause was DELIVERED on resume;
  - a replay was idempotent and was DELIVERED;
  - two flaky events, both delivered, left the endpoint ACTIVE.
- **Goods (2.7):**
  - P2-RAV-19: the contract matches the live service.
  - P2-RAV-20: same key, same id, one row; a different body gets 422.
  - P2-RAV-21: covered by 2.6.
  - P2-RAV-22: typed field errors on create, PATCH and sync.
  - F2: seven SSRF URLs refused.
  - F4: covered by 2.4.
  - P2-REG-15: the per-line allergen snapshot shows on the vendor's order detail.
  - P2-KYL-P1: markup in a credential name renders inert on both pages; a positive control shows the detector can fire.
  - P2-KYL-P3: refusals hold on its own storefront surface; the vendor path is finding 1 below.
- **Developers and Staff pages (2.8):**
  - the show-once dialog was shot at 1280/768/390 with the secret masked;
  - Esc and outside clicks are ignored;
  - the secret occurs 0 times in the DOM, inputs, `localStorage` and `sessionStorage` after "I've saved it" and after a reload; the control while the dialog was open found it once in the HTML and once in an input;
  - long text wraps at 375 and 640 with no page scroll;
  - rotate and delete work through the UI;
  - the Staff row reads "Integration: {name}" with no "Remove access".
- **Playwright line (2.9):** the plan's three specs ran verbatim, 66 passed, 0 failed, 0 skipped. `/dashboard/developers` had 0 a11y violations.
- **PR cut (2.14):** tag `cut/phase-37-e-integrators` = `1a481448`, and the branch `phase-37-e-integrators` = `b0f26126`. It is one commit above origin/main `bfef0f6e`, with equal trees, no upstream and nothing pushed. The plan's third `<automated>` line is rc 0. The D-04 merge-back was "Already up-to-date".

## Task Commits

1. **Task 1: static gates and full suites (earlier session):** `a1237bdc` (fix: 37-E drift that reds five static gates), `b62fe1ac` (docs: Part 1 evidence)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "proceed" (2026-10-13). No commit.
3. **Task 3: live RED, rebuild, GREEN, the credential lifecycle, goods, screens, the branch pointer:**
   - `1a481448` (docs: Part 2 evidence, 18 screens, deferred items; **this is the cut tag**)
   - `2c9e7ec2` (docs: the 2.14 branch pointer, after the cut)
   - PR branch `phase-37-e-integrators` at `b0f26126` (a squash of `cut/phase-37-e-integrators`; local only, not pushed)

`commits: 5` is `git rev-list --count cb29dc24..2c9e7ec2`, measured when this summary was written. It includes `6fc00e0a`, the orchestrator's HANDOFF commit made between the two sessions. This summary's own commit comes after it.

## Files Created/Modified
- `evidence/37-47-gate-37E.md`: Part 1 (Task 1) and Part 2 (2.0 to 2.14: RED, rebuild, the credential lifecycle, GREEN, webhooks, goods, pages, the spec line, findings, screens, residue, the secret check and the branch pointer).
- `evidence/37-47-screens/`: 18 PNGs, listed in evidence 2.11.
- `deferred-items.md`: "From 37-47" Task 1 (the `check-doc-citations.sh` VOID-as-PASS instrument finding) and Task 3 (the three findings below).
- Task 1 sources fixed for 37-E drift: `STACK.md`, `INTEGRATIONS.md`, `k8s/LOCAL.md`, `terminal-states.yaml`, the object-store allowlist, `layout-tiers.tsv`, `ssr-routes.conf` and `AGENTS.md`.

## Decisions Made
- **The owner's answer (2026-10-13), verbatim:** Task 2: "proceed". The `core-api` `serviceAccountsEnabled` question was not answered. The client was not touched, and a read-back shows it is still `true`.
- Live webhook receivers were public HTTPS (`httpbin.org`), because the SSRF guard refuses every local target, as F2 requires. The dev tenant's `contact_email` was set by SQL for the email proof and restored to NULL.
- Findings that change product behaviour were recorded for the owner, not fixed at the gate (the 37-38 precedent for anything beyond spec hygiene).

## Deviations from Plan

### Auto-fixed Issues (Task 1, recorded in its own commit)

**1. [Rule 3 - Blocking] Five static gates red from 37-E drift**
- **Found during:** Task 1
- **Issue:** four stale `application.yml` citations and one stale YAML locator; the allowlist line moved by 37-39; `/dashboard/developers` missing from `layout-tiers.tsv` and `ssr-routes.conf`.
- **Fix:** renumbered and registered at the source. No gate was edited.
- **Commit:** `a1237bdc`

### Instrument errors of my own, corrected before the record
1. The first Developers-page run read the secret with a regex filter that matched nothing (length 0), so its occurrence counts were meaningless. Its three credentials were revoked unread and the run was discarded.
2. The second run's selector `getByLabel(/client secret/i)` matched two elements, and Playwright's strict-mode error printed the input's `value` (a live secret) into a scratch log outside the repository. That credential was revoked about 12 seconds later and the log was scrubbed. The script now uses an exact role selector and redacts values in errors. Evidence 2.8 records this.
3. Webhook W4 assumed a manually paused endpoint records new events. By design it does not (`WebhookFanoutListener:146`; 37-43's UAT item), so W4 was replaced by W4b, a queued event held by the pause. Both are recorded.
4. The `check-branch-behind-base.sh` fail direction was first run from this checkout's copy of the script, which resolves its repo from its own path, so it passed. It was re-run with the worktree's own copy: rc 1.
5. A credential SQL read used a non-existent `status` column. It was corrected in place in the scratch log and the evidence.

**Total deviations:** 1 auto-fixed class in Task 1, plus 5 instrument corrections in Task 3. No product code changed in Task 3.

## Issues Encountered
Three findings at the gate, all in deferred-items "From 37-47 (Task 3)" and evidence 2.10:
1. **An unavailable product can be ordered by REST/MCP; through MCP it lands PENDING on the kitchen board.** This is decide-before-merge. `createOrder` has never checked `available` (origin/main is the same). 37-42 removed the vendor-review DRAFT step for agents. Recommended: refuse with the shop-less 404, at least when `submit` is true, as a review-fix commit on the PR branch.
2. **The Developers page header overflows at 768 px.** "Create credential" ends at x=815, and `main` scrolls 559 in 512. Sibling pages measure 512/512. Candidate review-fix: `lg:flex-row`, or `flex-wrap` with `min-w-0`.
3. **Revoked credentials stay on the Staff page** as "Integration: {name} · No access". The owner should decide whether revoke drops the `user_directory` row.

Observations for the phase UAT (37-43 items, not new defects): the paused email says "1 event is held. They'll be delivered", and the banner repeats "HTTP 503 · HTTP 503". The mobile nightly a11y scan of the kitchen New lane reports color-contrast x20, and the desktop scan reports 0. The P2-RAV-20 replay body differs from the original only in the offset rendering of the same instants (the known Jackson UTC round-trip).

## Known Stubs
None introduced. Task 3 changed no product code.

## Threat Flags
None. No new endpoint, auth path or schema. T-37-114 (secret in evidence) was mitigated: lengths and sha256 prefixes only, masked screens, `rg -uu` absence checks with a control, and gitleaks rc 0 with a planted control rc 1 (evidence 2.13). The one secret that reached a scratch log was revoked at once (2.8).

## Human checks harvested for the phase UAT (human_verify_mode end-of-phase)
- Read the decoded credential token claims in evidence 2.4 (`tenant_id`, `aud`, `scope`, `azp`) and confirm they match the credential created (Peckham, SHOP_MANAGER, `catalog:read` + `orders:write`).
- Review the Developers page and show-once dialog screens at 390/768/1280 (`evidence/37-47-screens/developers-*`), including the 768 header overflow (finding 2), and the Staff Integration row (`staff-integration-row-1280.png`). This is the VALIDATION manual-only row.

## Notes for the PR body (orchestrator)
- **Title shape:** "feat(37-E): integrator surface". Plans 37-39..37-47; RWO-016, RWO-017, RWO-035, RWO-036, RWO-037, RWO-038, RWO-040.
- **Say plainly:**
  - this PR carries **V82** `integration_credential`, which applies out of order after V84 (`out-of-order` is on in every profile);
  - staging and production need `ACCESS_HUMAN_CLIENT_IDS` to resolve to the dashboard's real client id (`app-config` `keycloak.client-id`). That is 37-45's owner question 1; HANDOFF records it as answered by construction;
  - creating credentials needs the Keycloak admin connection enabled in each environment.
- **Decide before merge:** finding 1 (unavailable products via MCP), and finding 2 if the screens review agrees.
- **Carried owner items:** `core-api` `serviceAccountsEnabled` (unanswered; left `true`); the 37-31 H4 proxy-peer IP-cap question; staging/production trusted proxies and `CLIENT_IP_HMAC_KEY`; Phase 39 planning before 2026-10-26; WINDOWS row 43; the 37-43 UAT items.
- **Changelog:** the `docs/CHANGELOG.md` entry citing the PR number goes on `phase-37-e-integrators` after the PR exists (D-03).
- **Do not add AI-attribution lines** (owner ruling).

## Next Phase Readiness
- `phase-37-e-integrators` is ready to push and open. Review rounds land on that branch.
- The runtime is current: all four images were built from `6fc00e0a`. The two docs commits after it change only `.planning`, which no image builds from.
- 37-48 is next by index. V83 is still reserved for it, and the next free migration number is V85.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*

## Self-Check: PASSED

- FOUND: `evidence/37-47-gate-37E.md` (contains "phase-37-e-integrators" ×5, "client_credentials", "check-runtime-freshness", "LastTagTime") and `evidence/37-47-screens/` (18 PNGs, including `developers-show-once-{390,768,1280}.png`)
- FOUND commits (ancestors of HEAD): a1237bdc, b62fe1ac, 1a481448, 2c9e7ec2; `git rev-list --count cb29dc24..2c9e7ec2` = 5
- FOUND: phase-37-e-integrators b0f26126, one commit above origin/main bfef0f6e, tree e384b453 equal to cut/phase-37-e-integrators (1a481448), no upstream, 0 remote heads
