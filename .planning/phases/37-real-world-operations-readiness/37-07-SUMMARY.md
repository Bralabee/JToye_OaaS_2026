---
phase: 37-real-world-operations-readiness
plan: 07
subsystem: auth
tags: [d-07, rwo-004, rwo-003, staff-invite, flyway-v77, rls, nosuperuser, token-digest, email, openapi, k8s-config, testcontainers]

requires:
  - phase: 37-05
    provides: "requireGroupAdmin + currentVendorUserId on ShopAccessService; tenant-wide role grants honoured (a NULL-shop STAFF / SHOP_MANAGER invitation is a meaningful grant)"
  - phase: 37-04
    provides: "strict scoping ON by default (D-06): an invitation is the only way a new person gets access"
provides:
  - "V77 staff_invite: tenant-scoped, ENABLE + FORCE RLS via current_tenant_id(), token held only as SHA-256, ck_staff_invite_ga_all_shops, ck_staff_invite_accept_pair, partial index on live rows per (tenant, email); no _aud, no backfill"
  - "StaffInvite entity (status OPEN | EXPIRED | ACCEPTED | CANCELLED derived at read time), StaffInviteRepository (explicit tenant predicates)"
  - "StaffInviteService.issue / list / cancel / resend, every method behind requireGroupAdmin"
  - "POST /api/v1/staff/invites (201 created / 200 replay), GET /api/v1/staff/invites, DELETE /api/v1/staff/invites/{id} (204, repeatable), POST /api/v1/staff/invites/{id}/resend (201)"
  - "EmailNotificationService.sendStaffInvite: @Async, plain text, UI-SPEC copy, expiry in Europe/London, after commit"
  - "Config jtoye.staff.invite.ttl-hours (STAFF_INVITE_TTL_HOURS, 72) and jtoye.staff.invite.accept-base-url (STAFF_INVITE_ACCEPT_BASE_URL) wired into compose, .env.example, k8s app-config + Deployment, every overlay"
  - "OpenAPI snapshot: additive only (313 insertions, 0 deletions)"
affects: [37-08-invite-accept, 37-09-invite-page-and-staff-ui, 37-15-runtime-gate, phase-pr-docs-metrics]

actuals:
  tokens: 25731
  tasks: 3
  commits: 5
plan_head_before: 70a5a695cd2af1796d850215de35c56556244fcb
plan_head_after: 28710c62cf4c093d6d1bea6a7ccecaf4a6ec21e0

tech-stack:
  added: []
  patterns:
    - "A side effect that carries a secret (the emailed link) runs in TransactionSynchronization.afterCommit, so a rolled-back write never emails a credential for a row that does not exist"
    - "A triple (email, shop, role) never has two live invitation rows: a fresh issue revokes every live row for the triple; an OPEN one is replayed instead"
    - "An integration test sets a NON-default value for each config key it relies on, so a key that failed to bind falls back to the default and reds the test"
    - "Controller methods get names unique across the app (issueInvite, listInvites) so springdoc's generated operationIds for existing operations do not renumber"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V77__staff_invite.sql
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInvite.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffInviteDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/CreateStaffInviteRequest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffInviteRlsIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceStaffInviteTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-07-red-invite-issue.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-07-red-invite-lifecycle.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java
    - core-java/src/main/resources/application.yml
    - docker-compose.full-stack.yml
    - .env.example
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - docs/api/openapi-snapshot.json
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "The issuer must be an identifiable person (UUID sub) as well as a Group admin: created_by is NOT NULL, and a declared machine client is refused with the same typed 403 (an agent must not mint human staff, RESEARCH 37-B.3)"
  - "The address is trimmed and lower-cased, then strictly parsed as one bare address (jakarta.mail InternetAddress); @Email on the raw field would have refused the plan's own 'New@Example.com ' case. The 400 never quotes the address"
  - "The shop 404 says 'Shop not found in this business' with no id, so another tenant's shop and a missing shop give byte-identical bodies (StaffManagementService.grant echoes the id; the invite path does not)"
  - "Issuing a triple whose only live row has expired revokes that row and issues a new one, so a triple never has two live rows; cancelling a cancelled or accepted invitation is a 204 no-op; resending an accepted one is a 400"
  - "StaffInviteDto returns the address in full (not masked like the directory, WR-10): the Group admin typed it, and the pending list and the cancel confirmation must name it. Neither the token nor its digest is ever returned"
  - "The invitation email is sent From J'Toye (no shop Reply-To); tenant-controlled names (inviter, business, shop) pass through sanitise() before the subject header and body"
  - "Expiry formatted 'd MMMM yyyy, HH:mm' in Europe/London (e.g. '10 October 2026, 14:32 (UK time)')"
  - "GET /api/v1/staff/invites returns a JSON array of every invitation, including cancelled and accepted ones; the client filters"
  - "accept-base-url is supplied per overlay (staging, production, local web origins) as well as in base, the DSAR link precedent; STAFF_INVITE_TTL_HOURS is injected too so every render states it"

patterns-established:
  - "Invitation tokens: SecureRandom 32 bytes, unpadded base64url, SHA-256 at rest, link {accept-base-url}/{tenantId}.{token}; the accept side (37-08) pins the tenant from the link and reads by digest under RLS"

requirements-completed: [RWO-004, RWO-003]

coverage:
  - id: D1
    description: "A Group admin's invite is stored only as a SHA-256 digest with its grant, issuer and a config-driven expiry, and the token arrives once, in one plain-text email link to the normalised address; the emailed token hashes to the stored digest and appears nowhere in the row or the response"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteIntegrationTest#issue_storesDigestOnly_andEmailsOneLink: RED 405 on 89faca39 (RED_EVIDENCE_OK, evidence/37-07-red-invite-issue.json), GREEN on ab632086, tracer gate re-run green; ttl-hours unbound arm red (expiry now+72 not now+48); accept-base-url unbound arm red (link not found)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Issuing the same (email, shop, role) while open replays it: 200, same id, no second email, one row"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteIntegrationTest#issue_sameTriple_replays_withoutSecondEmail: green; replay loop disabled arm red (201 and a second email)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Validation mirrors grant(): GROUP_ADMIN with a shop 400; another tenant's shop 404 with a body identical to a non-existent shop; invalid address 400 without quoting it"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffInviteIntegrationTest GA+shop / foreign-shop / invalid-email arms: green; arm a (GA rule removed) 409 from ck_staff_invite_ga_all_shops; arm b (findById instead of tenant finder) 201 for a foreign shop; arm d (id echoed in the 404) bodies differ"
        status: pass
    human_judgment: false
  - id: D4
    description: "Only a Group admin may issue, list, resend or cancel; a SHOP_MANAGER gets 403 shop-access-denied on all four and changes nothing; another tenant's Group admin cannot see, cancel or resend"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffInviteIntegrationTest#shopManager_isRefused_everywhere and #otherTenantsAdmin_cannotReachTheInvite: RED on 89ab518a (routes absent), GREEN on 7c90fe77; arm c (requireGroupAdmin removed from list) red, SHOP_MANAGER list 200"
        status: pass
    human_judgment: false
  - id: D5
    description: "Lifecycle: list derives OPEN, EXPIRED (expiry passed), CANCELLED, ACCEPTED at read time; DELETE 204 and repeatable keeping the first who/when; unknown id 404; resend revokes the old row and issues a new link (201); an accepted invitation is final; re-issue after cancel or expiry creates a new row"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffInviteIntegrationTest lifecycle arms (list, cancel, resend, accepted, issue-after-cancel, issue-after-expiry): RED 7/14 on 89ab518a (RED_EVIDENCE_OK, evidence/37-07-red-invite-lifecycle.json), GREEN 14/0 on 7c90fe77, closing clean 14/0"
        status: pass
    human_judgment: false
  - id: D6
    description: "staff_invite is walled per tenant by the database under a NOSUPERUSER role: A sees A not B, UPDATE of B matches 0, INSERT stamped B refused 42501, each beside an own-tenant control; no GUC reads nothing; RlsContractTest passes with no exemption"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffInviteRlsIntegrationTest 2/0 + RlsContractTest 7/0; arm 1 (FORCE removed): read arm red 'expected 0 but was 1', no-GUC arm red, RlsContractTest red 'FORCE ROW LEVEL SECURITY missing on public.staff_invite'; arm 1b (WITH CHECK true): read and UPDATE arms pass, INSERT arm red 'must be REFUSED'"
        status: pass
    human_judgment: false
  - id: D7
    description: "The invitation email: UI-SPEC subject and body, link, 'works once', expiry in UK time (BST and GMT), plain text only, header-safe tenant-controlled names, no address or link in any log line, nothing sent when mail is off"
    verification:
      - kind: unit
        ref: "EmailNotificationServiceStaffInviteTest 6/0; arm (UTC zone) BST arm red, GMT arm green as designed; arm (setText html) plain-text arm red 'text/html'; arm (log recipient) log arm red"
        status: pass
    human_judgment: false
  - id: D8
    description: "Config keys in every runtime and the published contract: both keys in application.yml, compose, .env.example, k8s app-config + Deployment env, staging/production/local overlays; goldens regenerated; env contract PASS; OpenAPI additive"
    verification:
      - kind: other
        ref: "check-env-contract.sh rc=1 with the yml key read but unsupplied (STAFF_INVITE_ACCEPT_BASE_URL local-only default), rc=0 wired (70 injected / 168 read); render-golden.sh check rc=1 before --write, rc=0 after; OpenApiSnapshotTest red on the old snapshot, green on the new (313 insertions, 0 deletions); grep of each key in compose / .env.example / configmap rc=0, misspelt control rc=1"
        status: pass
    human_judgment: false
  - id: D9
    description: "No regression across core-java"
    verification:
      - kind: integration
        ref: "28710c62: :test 188 files / 1605 tests / 0 failed / 1 skipped; :integrationTest 181 files / 935 tests / 0 failed / 1 skipped"
        status: pass
    human_judgment: false
  - id: D10
    description: "The invitation reaches Mailhog from the running compose stack, and its link opens the accept page"
    verification: []
    human_judgment: true
    rationale: "Not rebuilt here by design: the shared runtime is rebuilt at the 37-15 gate (deferred-items §4, §6), and the /invite page is built by 37-08/37-09. Recorded as WINDOWS.md entry 19."

duration: 56min
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 07: Staff invitations (D-07) Summary

**A Group admin can now invite a person by email with the exact grant they will receive. `POST /api/v1/staff/invites` stores only the SHA-256 of a 32-byte random token in the new FORCE-RLS table `staff_invite` (V77), and emails one plain-text link `{accept-base-url}/{tenantId}.{token}` after commit. Issuing the same (email, shop, role) while it is open replays it. Invitations can be listed (status derived at read time), cancelled and re-sent, all Group-admin-only. Both config keys are wired into every runtime, and the OpenAPI snapshot grew additively. Accepting an invitation is 37-08.**

## Performance

- **Duration:** 56 min (28 of them the full integration suite)
- **Started:** 2026-10-08T13:02:25Z
- **Completed:** 2026-10-08T13:58:32Z
- **Tasks:** 3
- **Files modified:** 26 (12 created, 14 modified)

## Accomplishments

- **Task 1 (tracer).**
  - **V77** creates `staff_invite` as the plan specifies, with ENABLE + FORCE RLS through `current_tenant_id()` and no exemption.
  - **`StaffInviteService.issue`:**
    - Requires a Group admin who is an identifiable person.
    - Normalises and strictly parses the address.
    - Applies the `grant()` rules: GROUP_ADMIN means all shops, and the shop must belong to the caller's tenant.
    - Replays an open invitation for the same triple.
    - Otherwise mints the token, stores its digest, and emails the link after commit.
  - **`sendStaffInvite`** sends the UI-SPEC copy as plain text, with the expiry in UK time. It logs event names only.
  - Tracer gate: the `<verify>` was re-run on the committed tree (2/0) before expansion.
- **Task 2.**
  - `list` derives the status at read time.
  - `cancel` revokes, and calling it again is a no-op.
  - `resend` revokes the old row and issues a new link in one transaction.
  - A NOSUPERUSER RLS proof and an email unit test were added.
- **Task 3.**
  - The two config keys are in `application.yml`, compose, `.env.example`, the k8s base app-config, the Deployment env, and each of the three overlays.
  - The goldens were regenerated, and the env contract passes.
  - The OpenAPI snapshot was regenerated and is additive only.

## Task Commits

1. **Task 1 RED: issue as digest + one emailed link** - `89faca39` (test)
2. **Task 1 GREEN: V77, model, issue, email, POST** - `ab632086` (feat)
3. **Task 2 RED: lifecycle, authorisation, RLS, email tests** - `89ab518a` (test)
4. **Task 2 GREEN: list, cancel, resend** - `7c90fe77` (feat)
5. **Task 3: config in every runtime, goldens, OpenAPI snapshot** - `28710c62` (feat)

**Plan metadata:** the commit that adds this SUMMARY, the deferred-items note and WINDOWS.md entry 19 (docs). STATE.md and ROADMAP.md are not touched; the orchestrator owns them.

## Evidence (both directions)

| Check | Pass direction | Fail direction |
|-------|----------------|----------------|
| Issue (Task 1) | 2/0 on `ab632086`; tracer gate re-run 2/0 | RED 2/2 on `89faca39`: 405, no route (RED_EVIDENCE_OK) |
| Lifecycle + authz (Task 2) | 14/0 on `7c90fe77`; closing clean 14/0 after every arm | RED 7/14 on `89ab518a`: 404/405, routes absent (RED_EVIDENCE_OK). The 7 green-at-RED arms are explained below. |
| GA rule | 400 `invalid-argument`, nothing stored | arm a (check removed): 409 from `ck_staff_invite_ga_all_shops`. This also proves the DB backstop. |
| Tenant check on the shop | foreign 404, body identical to a missing shop | arm b (`findById`): 201, an invitation to another tenant's published shop. arm d (id echoed): the bodies differ, and the arm goes red. |
| Group-admin gate | SHOP_MANAGER gets 403 on all four calls | arm c (gate removed from `list`): SHOP_MANAGER list 200 |
| Replay | 200, same id, one email | arm e (replay loop off): 201 and a second email |
| RLS (NOSUPERUSER) | 2/0; RlsContractTest 7/0 | arm 1 (FORCE removed): read arm `expected 0 but was 1`, no-GUC arm red, RlsContractTest `FORCE ROW LEVEL SECURITY missing on public.staff_invite`. arm 1b (`WITH CHECK (true)`): read and UPDATE arms pass, INSERT arm `must be REFUSED` red. |
| Email | 6/0 | UTC instead of London: the BST arm is red, the GMT arm stays green (by design, it is the control). html: `text/html`, red. Logging the recipient: the log arm is red. |
| Config binding | the IT runs with ttl-hours 48 and a test base URL | `ttl-hourz`: expiry was now+72, red. `accept-base-urlz`: link not found, 2 arms red. |
| Env contract | rc=0 (70 injected / 168 read) | rc=1 with the yml key read but not supplied: `STAFF_INVITE_ACCEPT_BASE_URL (default 'http://localhost:3000/invite' — local-only token)` |
| Goldens | `render-golden.sh` rc=0 after `--write` | rc=1 before `--write` (diff shows the new env) |
| OpenAPI | OpenApiSnapshotTest 1/0 on the new snapshot | red on the previous snapshot |
| V77 FORCE grep | line 75 `ALTER TABLE staff_invite FORCE ROW LEVEL SECURITY;` rc=0 | a copy with the line removed: rc=1 |
| No email/token in a log line | `rg -uu 'email_normalised\|token' StaffInviteService.java \| rg 'log\.'` rc=1. The first rg is a positive control (rc 0, 10 lines). | a copy that logs `token`: rc=0, the line printed |
| Full suite | `:test` 188 / 1605 / 0 failed; `:integrationTest` 181 / 935 / 0 failed (head `28710c62`) | n/a; the arms above are the falsifying runs |

Every break arm ran on a committed tree. Each restore came from the committed state and was verified by sha256 against hashes recorded before the arms (`V77`, `StaffInviteService`, `EmailNotificationService`).

**Two of the plan's criteria could not fail as written; each was replaced with a stronger form:**

- **ConfigKeyContractTest** checks only `spring.* / management.* / server.* / logging.*` keys against Boot metadata. With `ttl-hours` deliberately unbound it stayed green (9/0), so "both keys bound (ConfigKeyContractTest green)" proves nothing for `jtoye.*` keys. It still passes and is reported. The binding is now proven by the IT running non-default values and by the two unbind arms above.
- **The log-grep criterion** only sees lines that name `email_normalised` or `token`. A `log.info("{}", email)` would pass it. The stronger form is an assertion over the captured application log of the whole IT run: 32 `event=staff_invite*` lines (positive control), and 0 occurrences of any invitee address. The email unit test also asserts that the address and the link are absent from every log line, sent or failed.

### OpenAPI diff (additive only: 313 insertions, 0 deletions)

```
components.schemas.CreateStaffInviteRequest  NEW  {email string (1..320), role ShopRole, shopId uuid|null}
components.schemas.StaffInviteDto            NEW  {id, email, role, shopId, status enum[OPEN,EXPIRED,ACCEPTED,CANCELLED],
                                                   expiresAt, createdAt, createdBy, acceptedAt, revokedAt}
paths /api/v1/staff/invites                  NEW  GET listInvites, POST issueInvite (201 / 200 / 400 / 403 / 404)
paths /api/v1/staff/invites/{id}             NEW  DELETE cancelInvite (204 / 403 / 404)
paths /api/v1/staff/invites/{id}/resend      NEW  POST resendInvite (201 / 400 / 403 / 404)
```

The first regeneration was not additive. It had 8 deletions: springdoc renumbered the generated operationIds `list_1..list_8` of existing endpoints, because the new controller method was also called `list`. It also added a second `Staff` tag with a different description. The methods were renamed and the existing tag description was reused before the snapshot was committed.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The contract gate needs the Deployment env and the overlays, not only the base configmap**
- **Found during:** Task 3. `check-env-contract.sh` failed rc=1 on `STAFF_INVITE_ACCEPT_BASE_URL`, which has a local-only default.
- **Fix:** both keys are injected in `k8s/base/core-java-deployment.yaml`. `accept-base-url` is patched per overlay to its own web origin: `k8s/staging/`, `k8s/production/` and `k8s/local/configmap-patch.yaml`. This is the DSAR-link precedent. The plan's file list named only the base configmap; its action foresaw the Deployment ("if the contract script requires it") and the per-overlay hosts.
- **Commit:** `28710c62`

**2. [Rule 1 - Bug] The first OpenAPI regeneration was a breaking change**
- **Issue:** a controller method named `list` renumbered springdoc's generated operationIds for eight existing endpoints. A second `Staff` tag also appeared in the tags array.
- **Fix:** the methods were renamed `issueInvite` / `listInvites` / `resendInvite` / `cancelInvite`, and the existing tag description was reused. The diff is now 313/0.
- **Commit:** `28710c62`

**3. [Rule 2 - Missing critical] The issuer must be identifiable**
- `created_by` is NOT NULL, and an invitation that cannot name who issued it cannot be audited. A declared machine client passes `requireGroupAdmin()` but has no UUID subject, so it is refused with the same typed 403. This matches the recorded reason that no MCP tool exists for invites.
- **Commit:** `ab632086`

**4. [Rule 2 - Missing critical] No two live rows per triple; accepted is final**
- Re-issuing a triple whose live row has expired revokes that row. Resend revokes every live row for the triple. Resending an accepted invitation is a 400, and cancelling one is a 204 no-op. Without these rules, two links could be live for one grant, and an accepted record could be overwritten.
- **Commits:** `ab632086`, `7c90fe77`

**5. [Plan wording] Break arms ran on the working tree, not on a scratch commit**
- The plan says to drop FORCE "in a scratch commit". Instead, each arm edited the working tree over a committed state. That state was the restore target, verified by sha256. This keeps scratch commits out of the branch history. The protection is the same: commit first, restore by content.

**6. [Rule 3 - Blocking] Python-free verification; compound commands split**
- No python step was needed. Runs used the scratchpad scripts `p07/run.sh`, `xml.sh`, `sum.sh`, `envc.sh` and `golden.sh`. The session's isolation guard refused compound git or variable-argument commands, so they were split.

---

**Total deviations:** 4 auto-fixed (1 bug, 2 missing critical, 1 blocking), plus 2 procedural notes.
**Impact on plan:** each fix keeps the plan's own truths: the contract is additive, every runtime has a working link, and every invitation has an issuer and exactly one live link per grant. No refusal was weakened.

## Issues Encountered

**Seven Task 2 arms were green at RED.** They were not ignored; each has a reason:
- Task 1's `issue()` already carried the `grant()` rules (GA+shop 400, GA all shops 201, foreign shop 404, invalid email 400) and the revoke-expired-on-reissue rule.
- The two Task 1 arms were in the same class.
- `StaffInviteRlsIntegrationTest` was green because V77 already had FORCE.
- `EmailNotificationServiceStaffInviteTest` was green because `sendStaffInvite` shipped in Task 1.

The fail direction for each of these is a break arm (table above). The lifecycle target and six other arms were genuinely RED.

**The plan ledger** is kept in the session scratchpad (`p07/plan-head-before` = `70a5a695…`), as in 37-02..37-06.

## Known Stubs

None in this plan's code. The emailed link points at `/invite/{tenantId}.{token}`, which 37-08/37-09 build. Until then, a link sent from a rebuilt stack would open the frontend's 404 page. This is recorded in deferred-items §6 and is not a stub in this plan's files.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: information-disclosure | StaffInviteService.java / V77 | The link token travels in the URL **path** (`/invite/{tenantId}.{token}`, as the plan and RESEARCH 37-B.3 specify). The DSAR links use a **fragment**, which a browser never sends to a server. A path token reaches the frontend's request line and access logs, and the `Referer` header of any subresource. The UI-SPEC already requires `Referrer-Policy: no-referrer` and no third-party resources on `/invite`. 37-08/37-09 must also keep the token out of frontend server logs, or move it to a fragment. The token is single-use (37-08) and expires in 72 h. |
| threat_flag: abuse | StaffInviteController.java | A Group admin can call `resend` without limit, and each call emails the same address. This is bounded only by the tenant rate limiter (100/min by default) and by needing the Group admin role. No per-address cap was added: none was in the plan, and the caller is a trusted tenant role. |
| canon referral: GDPR retention | V77 `email_normalised` | An unaccepted invitation keeps the invitee's address until a Group admin cancels or re-sends it, and a cancelled row keeps it too, because the row is never deleted. Recorded as a residual for `/gsd-secure-phase`, as the plan's probe notes say. It is not minted here. |

## TDD Gate Compliance

- **Task 1:** RED `89faca39` `test(37-07)` (RED_EVIDENCE_OK, `evidence/37-07-red-invite-issue.json`) → GREEN `ab632086` `feat(37-07)`.
- **Task 2:** RED `89ab518a` `test(37-07)` (RED_EVIDENCE_OK, `evidence/37-07-red-invite-lifecycle.json`, 7/14 red) → GREEN `7c90fe77` `feat(37-07)`.
- **Task 3:** `type="auto"`, no TDD. Its falsifying runs are the env-contract, golden, OpenAPI and binding arms above.
- No REFACTOR commits.

## User Setup Required

None. The new keys carry safe defaults, and every manifest supplies its own web origin.

## Next Phase Readiness

**37-08 (accept):**
- Pin the tenant from the link's `{tenantId}` and read by `token_sha256` under RLS.
- Use one non-disclosing 404 for every unusable cause.
- Accept once, with `UPDATE … WHERE accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()` and a row-count check. Set `accepted_at` and `accepted_user_id` together (`ck_staff_invite_accept_pair`).
- `StaffInvite.statusAt` and `StaffInviteService.sha256Hex` are ready to reuse.

**37-09 / staff UI:**
- `GET /api/v1/staff/invites` returns every invitation. Filter to OPEN and EXPIRED for the Pending table.
- The cancel confirm uses `cancelLabel="Keep invitation"`.
- The 37-06 staff-page jest test and e2e spec that assert "no invite control" change with that UI.

**37-15 gate:** V77 applies on the rebuild. Send one invitation through Mailhog and prove parity with `scripts/check-runtime-freshness.sh` (WINDOWS.md entry 19).

**Before the phase PR:** docs metrics. `schema_version` is now 77 and the test counts moved (deferred-items §1). Re-measure.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: V77__staff_invite.sql, StaffInvite.java, StaffInviteRepository.java, StaffInviteService.java, StaffInviteController.java, StaffInviteDto.java, CreateStaffInviteRequest.java, StaffInviteIntegrationTest.java, StaffInviteRlsIntegrationTest.java, EmailNotificationServiceStaffInviteTest.java, evidence/37-07-red-invite-issue.json, evidence/37-07-red-invite-lifecycle.json (all FOUND)
- Commits on this branch: 89faca39, ab632086, 89ab518a, 7c90fe77, 28710c62 (`git rev-list --count 70a5a695..HEAD` = 5 at SUMMARY write)
- Acceptance re-run: V77 FORCE grep (line 75, broken copy rc 1); log grep rc 1 (broken copy rc 0); key greps rc 0 (misspelt control rc 1); env contract rc 0; goldens rc 0; OpenAPI 313/0; full suite green at `28710c62`
