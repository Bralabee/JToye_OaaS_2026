# 37-01 Baseline: merge record, guarding suites, migration reservations, topology

**Measured:** 2026-10-08 07:22-07:26 UTC, on the integration branch `phase-37-ops-readiness`
(linked worktree `/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37`).
**Plan:** 37-01. Every claim below gives the command, its rc (captured on the same line), and the
fail-direction run where one exists.

---

## 1. Merge record (D-04: Phase 37 is written against the Phase 38 tree)

| Item | Value |
|------|-------|
| `git fetch origin` | rc=0 (non-zero would have been VOID: stop, write nothing) |
| origin/main tip at fetch | `cc860dc5ed2e1339832a9fa5d13c438580bd339c` (Phase 31.1 merge, PR #901) |
| Integration HEAD before this plan | `d903df2ed529fe15364acc4ea3c8039228e952a2` |
| **Merge commit carrying origin/main** | **`394edcda672dc222b230e2049d8d67bacbe49bcd`** — `Merge remote-tracking branch 'origin/main' into phase-37-ops-readiness` (2026-10-07 22:43 +0100), parents `5052def9…` + `cc860dc5…` |
| `git merge origin/main` (this plan) | `Already up-to-date.` rc=0 — origin/main had not moved since 394edcda, so no new merge commit was made (a `git merge` that has nothing to merge creates none) |
| `git log --oneline HEAD..origin/main` | empty |
| `git merge-base --is-ancestor origin/main HEAD` | rc=0 (PASS) |
| Fail direction: `git merge-base --is-ancestor HEAD origin/main` | rc=1 (the same check refuses a non-ancestor) |
| `git rev-list --count origin/main..HEAD` | 9 (the planning commits on top of main) |
| `git log -1 --merges --format=%s` | `Merge remote-tracking branch 'origin/main' into phase-37-ops-readiness` |

Merged with `git merge` (never rebase, never squash). No conflicts: the merge was a no-op, so no
`core-java/**`, `frontend/**`, `edge-go/**` or `mcp-server/**` conflict arose and the tree is in the
state planning assumed.

### Tree identity

| Check | Command | Result | Fail direction |
|-------|---------|--------|----------------|
| Boot 4.1.x | `grep -nE 'id\("org\.springframework\.boot"\) version "4\.1\.' core-java/build.gradle.kts` | `2:    id("org.springframework.boot") version "4.1.1"` rc=0 | same grep for `version "3\.` → rc=1 |
| V75 present | `test -f core-java/src/main/resources/db/migration/V75__dsar_access_export.sql` | rc=0 | `test -f …/V76__nonexistent.sql` → rc=1 |
| V69..V75 present (31.1) | `ls core-java/src/main/resources/db/migration/` | V69__order_allergen_acknowledgement, V70__dsar_request_subject_address, V71__trader_identity, V72__dsar_request_account_deletion, V73__order_allergy_note, V74__product_may_contain, V75__dsar_access_export; 75 files total | — |

37-C's dependency on Phase 31.1 (D-04) is satisfied on this tree.

---

## 2. Guarding suites on the merged tree (before any Phase 37 change)

Compile: `./gradlew :core-java:compileJava :core-java:compileTestJava --no-daemon -q` → rc=0.

Unit: `./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.tenant.keycloak.KeycloakAdminClientTest' --tests 'uk.jtoye.core.common.idempotency.IdempotencyFingerprintGoldenTest' --no-daemon`
→ rc=0, `> Task :core-java:test` EXECUTED (not UP-TO-DATE; `cleanTest` forces a fresh run), BUILD SUCCESSFUL in 8s.

Integration: `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests 'uk.jtoye.core.security.access.ShopAccessJitProvisionTest' --tests 'uk.jtoye.core.security.access.StaffManagementIntegrationTest' --tests 'uk.jtoye.core.storefront.GuestOrderAllergenAckIntegrationTest' --tests 'uk.jtoye.core.security.RlsContractTest' --no-daemon`
→ rc=0, `> Task :core-java:integrationTest` EXECUTED (Testcontainers Postgres), BUILD SUCCESSFUL in 52s.

Results read from the LIVE build dir `core-java/build-local/test-results/` (never `build/`, which is stale).
XML timestamps 2026-10-08T07:24:10Z..07:24:30Z (this run).

| Suite | Source set | tests | failures | errors | skipped |
|-------|-----------|------:|---------:|-------:|--------:|
| `uk.jtoye.core.tenant.keycloak.KeycloakAdminClientTest` | test | 11 | 0 | 0 | 0 |
| `uk.jtoye.core.common.idempotency.IdempotencyFingerprintGoldenTest` | test | 15 | 0 | 0 | 0 |
| `uk.jtoye.core.security.access.ShopAccessJitProvisionTest` | integrationTest | 4 | 0 | 0 | 0 |
| `uk.jtoye.core.security.access.StaffManagementIntegrationTest` | integrationTest | 19 | 0 | 0 | 0 |
| `uk.jtoye.core.storefront.GuestOrderAllergenAckIntegrationTest` | integrationTest | 10 | 0 | 0 | 0 |
| `uk.jtoye.core.security.RlsContractTest` | integrationTest | 7 | 0 | 0 | 0 |
| **Total** | | **66** | **0** | **0** | **0** |

Result reader (a bash checker over the six TEST-*.xml: exit 0 only when tests>0, failures=0,
errors=0; exit 2 VOID on a missing or unparseable file):

- Real tree: six `OK` lines, rc=0.
- Fail direction, run: a copy of the KeycloakAdminClientTest XML with `failures="1"` → `BAD … failures=1`, rc=1;
  a copy of the ShopAccessJitProvisionTest XML with `tests="0"` → `BAD … tests=0`, rc=1;
  a missing file → `VOID: no testsuite in …`, rc=2.

**Scope of this baseline.** It is the six guarding suites only, not the full `:core-java:test` /
`:core-java:integrationTest` run. 37-02's "the full suite stays exactly as green as the 37-01
baseline" must take its own full-suite reading with `ACCESS_STRICT_SCOPING` unset as the comparison
point; this file does not contain one.

---

## 3. Migration reservations V76..V83

Head on this tree: **V75** (`V75__dsar_access_export.sql`, 75 files). `spring.flyway.out-of-order=true`
is set in every profile, so independently merged sub-theme PRs may land out of numeric order — which is
why the numbers are reserved now, one per owning plan, in sub-theme execution order B, A, D, C, E, G, F
(37-F carries no migration).

| V | Sub-theme | Owning plan | Content |
|---|-----------|-------------|---------|
| V76 | 37-B | 37-05 | `user_directory.realm_admin_seen_at` |
| V77 | 37-B | 37-07 | `staff_invite` (new tenant table: ENABLE+FORCE RLS via `current_tenant_id()`, NOSUPERUSER proof) |
| V78 | 37-B | 37-10 | ledger `financial_transactions.entry_kind` (+ reversal link, V40 unique-index swap) + `orders.voided_*` + `_aud` |
| V79 | 37-B | 37-11 | `financial_transactions.shop_id` + `_aud` + tenant-loop backfill |
| V80 | 37-A | 37-19 | `shops` opening_schedule / hours_source / pause columns + `shops_aud` + tenant-loop backfill |
| V81 | 37-D | 37-27 | `orders.contact_verified` / `client_ip_digest` / `cancel_reason_code` + `orders_aud` |
| V82 | 37-E | 37-45 | `integration_credential` (new tenant table: ENABLE+FORCE RLS, NOSUPERUSER proof) |
| V83 | 37-G | 37-48 | `shops.offers_delivery` / `offers_collection` + `shops_aud` |

Each owning plan names its file (`rg -uu -o 'V(7[6-9]|8[0-3])__[a-z_]+'` over the eight plans):
37-05 `V76__user_directory_realm_admin_seen`, 37-07 `V77__staff_invite`, 37-10 `V78__order_void_ledger_reversal`,
37-11 `V79__financial_transactions_shop_id`, 37-19 `V80__shop_opening_schedule_pause`, 37-27 `V81__order_abuse_bounds`,
37-45 `V82__integration_credential`, 37-48 `V83__shop_fulfilment_offered` — one number per plan, no number named twice.

(RESEARCH § "Migration numbering" proposed V76-V82 by sub-theme; the plan set re-keyed them to one number
per owning plan, V76-V83, as above. This table is the authority.)

### Proof the numbers are unused on every ref

Per ref: `git -C <worktree> ls-tree -r --name-only <ref> > refs/<name>.txt` (each rc=0), then
`/usr/bin/grep -cE '(^|/)V(7[6-9]|8[0-3])__[^/]*$'` for the reservation and
`/usr/bin/grep -cE '(^|/)V75__dsar_access_export\.sql$'` for the positive control. Remote refs from
`git for-each-ref refs/remotes/origin` after the rc=0 fetch (`origin` = origin/HEAD alias of main).

| Ref | files | migration files | highest V | V76..V83 hits (grep rc) | V75 control (grep rc) |
|-----|------:|----------------:|-----------|-------------------------|-----------------------|
| origin/main | 3038 | 75 | V75 | 0 (rc=1) | **1 (rc=0)** |
| origin/phase-37-ops-readiness | 3042 | 75 | V75 | 0 (rc=1) | **1 (rc=0)** |
| origin/phase-31.1-persona-gap-closure | 3038 | 75 | V75 | 0 (rc=1) | **1 (rc=0)** |
| origin/docs/changelog-phase-38 | 2766 | 67 | V67 | 0 (rc=1) | 0 (rc=1) — predates V75 |
| origin/fix/deps-trivy-2026-10-07 | 2766 | 67 | V67 | 0 (rc=1) | 0 (rc=1) — predates V75 |
| origin/feature/archify-architecture-diagrams | 2430 | 66 | V66 | 0 (rc=1) | 0 (rc=1) — predates V75 |
| origin/phase-29-research | 2131 | 61 | V61 | 0 (rc=1) | 0 (rc=1) — predates V75 |
| local HEAD (integration) | 3098 | 75 | V75 | 0 (rc=1) | **1 (rc=0)** |

- **Positive control:** V75 is found on origin/main and on every ref that has reached it. The four refs
  without V75 are older branches; their own highest migration (V61..V67, read from the same listing) is
  their per-ref control, so the listing is proven non-empty and the migration directory visible on each.
- **Fail direction, run:** the origin/main listing with one planted line
  `core-java/src/main/resources/db/migration/V79__planted_collision.sql` → reservation search `hits=1`, rc=0.
  The search can find a collision; it found none on any real ref.

---

## 4. Git topology every sub-theme follows (D-02, D-03, D-04)

All plans execute on the integration branch `phase-37-ops-readiness`, one sub-theme at a time, in the
order **B, A, D, C, E, G, F** (D-02: 37-B first, because every other sub-theme's tests assert "staff see
only what they were granted" under the new default).

Each sub-theme ends with a **gate plan** that:

1. unless it is 37-B, first requires the previous sub-theme's PR to be **MERGED** — PR state read with
   `gh`, because a squash merge leaves no ancestry — and merges that PR branch's final reviewed head and
   then origin/main into the integration branch;
2. runs every gate on the reconciled head;
3. cuts the PR branch `phase-37-<letter>-<slug>` fresh from origin/main as **ONE squash commit** of the
   integration branch's net change (tree equal to the integration commit it was cut from, one commit
   above main), so each PR holds its own change and none of another PR's commits;
4. merges origin/main into the integration branch again before the next sub-theme starts.

Review-fix and merge-from-main commits land on the PR branch and flow back at the next gate. Each PR runs
its own D3 review series (push first, review second) and merges on its own (D-03). The phase completes
when all **seven** PRs have merged. Merging `main` before each sub-theme is D-04.

---

## 5. Shared compose stack owner (this plan does not touch the stack)

`docker compose ls` (rc=0):

| Project | Status | Config files |
|---------|--------|--------------|
| `jtoye_oaas_2026` | running(11) | `/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37/docker-compose.full-stack.yml`, `/home/sanmi/IdeaProjects/JToye_OaaS_2026/docker-compose.full-stack.yml` |
| `monitoring` | running(5) | `/home/sanmi/IdeaProjects/JToye_OaaS_2026/infra/monitoring/docker-compose.monitoring.yml` |

Container start times (`docker inspect --format '{{.State.StartedAt}}'`, rc=0):

| Container | Started (UTC) | compose working_dir |
|-----------|---------------|---------------------|
| jtoye-edge-go | **2026-10-07T21:46:23Z (newest)** | `…/JToye_OaaS_2026-phase37` |
| jtoye-mcp-server | 2026-10-07T21:46:07Z | `…/JToye_OaaS_2026-phase37` |
| jtoye-frontend | 2026-10-07T21:46:07Z | `…/JToye_OaaS_2026-phase37` |
| jtoye_oaas_2026-core-java-1 | 2026-10-07T21:45:45Z | `…/JToye_OaaS_2026-phase37` |
| jtoye-keycloak | 2026-10-07T21:45:27Z | `…/JToye_OaaS_2026-phase37` |
| jtoye-postgres | 2026-10-07T21:45:16Z | `…/JToye_OaaS_2026-phase37` |
| jtoye-rabbitmq | 2026-10-07T21:45:16Z | `…/JToye_OaaS_2026-phase37` |
| jtoye-ollama | 2026-10-04T00:20:35Z | `…/JToye_OaaS_2026` (main checkout) |
| jtoye-redis / jtoye-azurite / jtoye-mailhog | 2026-10-02T18:53:29Z | `…/JToye_OaaS_2026` (main checkout) |

**Owner now:** the Phase 37 worktree. The seven application/data services were last (re)created from
`/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37` at 2026-10-07 21:45-21:46 UTC, i.e. after the
394edcda merge (21:43 UTC); redis, azurite, mailhog and ollama are older containers created from the
main checkout under the same project name. No Phase 37 code exists yet, so nothing is stale relative
to Phase 37; each sub-theme gate must rebuild every image and run `scripts/check-runtime-freshness.sh`
(compose project name comes from the directory — see the memory trap) before handing the runtime back.

---

## 6. Out of scope for Phase 37 (D-01 deferrals, recorded, not planned)

- The remaining P2/P3 persona clusters of sub-themes 37-A..37-E and 37-G (to a 37.x follow-up phase),
  including **UXT-049** (double-tap skips a status — SC-1's clause moves with it), **UXT-050** (mute
  survives sign-out) and **UXT-052** (unanswered orders pending forever / auto-reject timeout).
- The two unassigned clusters **UXT-120** (#873) and **UXT-121** (#874).
- A real **promotions engine** (D-17 only stops rendering unapplied promotions).
- **Web Push** notifications for new orders (D-12).
- Ending the Keycloak session on revoke (D-08); a longer vendor-realm SSO lifetime (D-14); a per-tenant
  strict-scoping switch (D-06); email verification gating cash orders (D-18).
- 37-F's six P2 clusters (UXT-080..085) stay **in** scope by D-05.
- 37-B's D-07 invite closes issue #452's gap 2 (UXT-025, homed to Phase 33 in the catalogue), so
  Phase 33 does not rebuild it.
