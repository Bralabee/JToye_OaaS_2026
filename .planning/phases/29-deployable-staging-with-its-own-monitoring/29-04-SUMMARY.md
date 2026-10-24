---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 04
subsystem: auth
tags: [keycloak, realm, jq, staging, kubernetes, port-forward, key-vault, bash, tdd, blocker-h]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-01: scripts/lib/azure-guard.sh (az_guard_init, az_read with the pin appended last, az_guard_stub)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-03: scripts/lib/staging-kube-guard.sh (staging_kube_guard CONTEXT IDENTITY_FILE: 2 USAGE ERROR / 1 REFUSED [employer-context|identity-file|server-url] / 2 VOID)"
provides:
  - "infra/keycloak/staging-realm.jq: one jq program (--arg kind vendor|customer, --arg host) deriving jtoye-staging and jtoye-customers from the ONE template each"
  - "scripts/staging-realm-import.sh: --render-only, --assert (offline|apply|live|live-existing), --list-kv-objects, --apply --context [--identity-file] [--recreate], --selftest (9 arms)"
  - "The Key Vault object list 29-13's writer must generate: keycloak-credentials--{admin-password,admin-username,frontend-client-secret}, keycloak-realm--{edge-api-client-secret,integration-catalog-ro-secret,integration-orders-rw-secret}"
  - "ci-cd.yaml k8s-validate step running the selftest"
  - "infra/keycloak/README.md: no frontend client (Blocker A), 'Staging realm (Kubernetes)' section"
affects: [29-06, 29-10, 29-12, 29-13, 29-14, 29-22]

actuals:
  tokens: 18644
  tasks: 2
  commits: 3
plan_head_before: 68f52d068c5e8f7836e8960b85492b0823ddd877
plan_head_after: b8073aeaa221de83d8721cba1842c37280c6fff2

tech-stack:
  added: []
  patterns:
    - "A staging realm is a committed jq transform over the dev template plus an assertion program; no second realm file is ever committed"
    - "The SAME jq assertion program runs on the rendered file and on a document reconstructed from the Keycloak admin API (mode live)"
    - "Secrets reach jq only through $ENV and curl only through @file / -K config files; never argv"
    - "A positive-control selftest arm (guard satisfied) proves the stub logs can record az/kubectl calls, so the refusal arms' zeros are not vacuous"

key-files:
  created:
    - infra/keycloak/staging-realm.jq
    - scripts/staging-realm-import.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-04-t1-red-transformed.json
  modified:
    - infra/keycloak/README.md
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "The staging realms are imported by an operator-run script through kubectl port-forward (admin API at http://127.0.0.1:18080 only), never by --import-realm in the pod: the templates are outside the kustomize load restrictor, the customer template is not JSON until rendered, and the proof is the live read-back"
  - "The transform rewrites only the named realm paths (/realms/jtoye-dev/, /admin/jtoye-dev/) and default-roles-jtoye-dev; any other dev-shaped string is left for the assertion suite to refuse rather than silently renamed"
  - "The customer Google IdP's _note_* operator prose (which quotes localhost and Mailhog) is not copied into the staging realm; the template keeps it"
  - "A realm that already exists is skipped (not replaced) and asserted in mode live-existing, which allows post-import users (the 29-06 test vendor, storefront registrations) but never a template seed user; --recreate deletes first and says which users are lost"
  - "The transform fails (jq error) when the template loses a client, its user profile or its realm name, instead of guessing"

patterns-established:
  - "Realm assertion modes: offline (render), apply (render + no TEST-ONLY value), live (fresh import read-back), live-existing (skipped realm read-back)"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "The staging vendor realm (jtoye-staging) derived from realm-export.template.json passes every Blocker H assertion offline, and the raw template is refused naming realm jtoye-dev, 4 non-service users, localhost in core-api redirectUris, webOrigins *, mailhog in smtpServer"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "bash scripts/staging-realm-import.sh --selftest (arms raw-template, transformed, survived-placeholder, builtin-count)"
        status: pass
      - kind: other
        ref: "bash scripts/staging-realm-import.sh --assert infra/keycloak/realm-export.template.json --kind vendor (rc 1, 24 defects)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The staging customer realm (jtoye-customers) with storefront-client on the staging host, verifyEmail true, Google disabled; the compose-shaped customer render is refused"
    requirement: "DPLY-02"
    verification:
      - kind: other
        ref: "bash scripts/staging-realm-import.sh --selftest (arms raw-customer-template, transformed, builtin-count)"
        status: pass
    human_judgment: false
  - id: D3
    description: "--apply refuses no context (rc 2), sipbihs2aks (rc 1 [employer-context]) and a stale server (rc 1 [server-url]) before the Azure guard library is sourced and with no az/curl call; --list-kv-objects prints the six objects sorted"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "bash scripts/staging-realm-import.sh --selftest (arms apply-no-context, apply-employer-context, apply-stale-server, apply-kv-read-control)"
        status: pass
      - kind: other
        ref: "bash scripts/staging-realm-import.sh --list-kv-objects (plan verify line, exact_sorted_list=yes)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The live import and the live read-back assertions against the staging Keycloak"
    requirement: "DPLY-01"
    verification: []
    human_judgment: true
    rationale: "Not run in this plan by design: the live run is plan 29-14 (tracer). The import, admin-token, POST/DELETE and read-back code paths have never executed against a Keycloak; only the offline and refusal paths are proven here."

duration: 13min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 04: Staging realm transform and import Summary

**A committed jq transform derives the staging realms `jtoye-staging` and `jtoye-customers` from the one template each: no seed users, URIs pinned to app-staging.olajay.co.uk, no wildcard origins, Mailpit with STARTTLS, core-api service account off, tenant_id admin-only, verifyEmail on. An assertion suite refuses the dev template and runs the same checks on the realm Keycloak returns after import. The import script reaches the admin API only through a port-forward and runs the shared kube-context guard before it even loads the Azure guard.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-10-18T22:21:09Z
- **Completed:** 2026-10-18T22:34:29Z
- **Tasks:** 2
- **Files modified:** 5 (3 created, 2 modified)

## Accomplishments

- `infra/keycloak/staging-realm.jq` (vendor) sets realm `jtoye-staging` and rewrites the named `/realms/`, `/admin/` and default-role strings. It keeps only the service-account users. `core-api` gets redirect `https://app-staging.olajay.co.uk/*`, origin `https://app-staging.olajay.co.uk` and the same post-logout URI. `edge-api` and both `integration-*` clients get empty URIs; the `integration-*` clients are disabled (#301). SMTP goes to Mailpit `:1025` with `starttls "true"`, and `tenant_id` is kept admin-only in the user profile.
- The customer branch puts `storefront-client` on the same host, sets `verifyEmail: true` (#859), and ships Google disabled with empty credentials and without the `_note_*` dev prose.
- `scripts/staging-realm-import.sh --assert` is one jq program covering every must_haves check plus the built-in multiset and the managed `tenant_id`. Its modes: `offline`, `apply` (also refuses TEST-ONLY values), `live` and `live-existing`.
- `--apply` order: `staging_kube_guard` → `STEP: sourcing azure-guard` → `az_guard_init` → 6 × `az_read` → render and apply-mode assert of both realms → `kubectl port-forward svc/keycloak 18080:8080 19000:9000` (PID captured) → readiness on `:19000/health/ready` (5 min deadline). Then, per realm: GET, then POST, skip, or DELETE+POST under `--recreate`; finally a read-back of realm, clients, users, profile and IdPs, asserted again.
- `--list-kv-objects` prints the six Key Vault object names for plan 29-13.
- The README no longer claims a `frontend` client. It has a new "Staging realm (Kubernetes)" section, and its session, user-profile and SMTP sections now say how staging gets each setting.

## Task Commits

1. **Task 1 RED: assertion suite against the identity transform**: `c5af00f1` (test)
2. **Task 1 GREEN: the staging transform**: `d3a47641` (feat)
3. **Task 2: --apply through a port-forward, live read-back, KV list, README, CI step**: `b8073aea` (feat)

**Plan metadata:** see the docs commit that adds this SUMMARY.

## TDD: RED, GREEN

- **RED** (`c5af00f1`): the script carried `--assert`, `--render-only` and the selftest, with `staging-realm.jq` as the identity program `.`. `STAGING_REALM_SELFTEST_FORMAT=tap … --selftest` exited 1 with `not ok 3 - transformed`. The vendor render was refused for realm `jtoye-dev`, 4 non-service users, `webOrigins "*"` on core-api and edge-api, and the localhost / app.jtoye.local redirects. In the same run raw-template, raw-customer-template, survived-placeholder and builtin-count passed. Classifier: `RED_EVIDENCE_OK` (`target_test_failed`, TAP, 5 tests). Record: `evidence/29-04-t1-red-transformed.json`.
  - *Semantic assessment:* the target arm ran and failed on the planned assertions; it was not a harness, syntax or fixture fault. Both renders exited 0, so the render path and substitution worked; only the missing transform was refused.
- **GREEN** (`d3a47641`): the real transform; the selftest passed 5/5.
- **REFACTOR:** none.
- During Task 2, a quote in the jq program comment broke the shell (`syntax error near unexpected token`, rc 2). That was a defect I introduced, fixed before commit, and not counted as RED evidence. The apply arms' fail direction is the break arms below.

## Verification (both directions)

**Task 1, fail direction (acceptance 1), verbatim:**
```
$ out=$(bash scripts/staging-realm-import.sh --assert infra/keycloak/realm-export.template.json --kind vendor 2>&1); rc=$?
DEFECT: realm is jtoye-dev, expected jtoye-staging
DEFECT: 4 non-service users: admin-user, tenant-a-user, tenant-b-user, admin-user-b
DEFECT: webOrigins "*" on client(s): core-api, edge-api
DEFECT: redirect URI(s) outside https://app-staging.olajay.co.uk/: core-api http://localhost:8080/*, … edge-api http://localhost:8090/*
DEFECT: 5 string(s) contain "localhost" (first at clients.4.redirectUris.0)
DEFECT: 2 string(s) contain "jtoye.local" (first at clients.4.redirectUris.4)
DEFECT: 1 string(s) contain "mailhog" (first at smtpServer.host)
DEFECT: 13 string(s) contain "jtoye-dev" (first at realm)
DEFECT: mailhog in smtpServer (host mailhog)
DEFECT: smtpServer host is mailhog, expected mailpit.jtoye-staging.svc.cluster.local
DEFECT: smtpServer starttls is false, expected true (D-20)
DEFECT: substitution-list placeholder survived: ${EDGE_API_CLIENT_SECRET}   (and the other three secrets, and ${KC_SEED_USER_PASSWORD})
DEFECT: localhost in core-api redirectUris
DEFECT: core-api redirectUris are [...], expected ["https://app-staging.olajay.co.uk/*"]
DEFECT: core-api webOrigins are ["*"], expected ["https://app-staging.olajay.co.uk"]
DEFECT: core-api post.logout.redirect.uris is "+", expected "https://app-staging.olajay.co.uk/*"
DEFECT: edge-api redirectUris / webOrigins …, expected []
DEFECT: integration-catalog-ro / integration-orders-rw is enabled; … expected enabled false
REFUSED: … fails the vendor staging assertions (24 defect(s), mode offline)
rc=1
```
All five named defects are present.

**Task 1, pass direction:** the transformed vendor render passes with rc 0. `jq '[.users[].username]'` gives `["service-account-core-api","service-account-edge-api","service-account-integration-catalog-ro","service-account-integration-orders-rw"]`.

**Built-in tokens (acceptance 3):** vendor template 53, render 53; customer template 11, render 11. The comparison is by sorted multiset, not by count alone. Negative control in the arm: dropping one built-in from the render is refused ("built-in token count 52, template 53"). In a separate run, an emptied `components` map was refused with 4 tokens missing.

**Task 1 verify line:** rc 0, missing=0, fail_lines=0. On the RED tree the same line gave rc 1 (`not ok - transformed`).

**More break arms, each run on scratch copies of a passing render (rc 1, the named defect):** `tenant_id` made user-editable → `tenant_id edit permission is ["admin","user"]`; core-api with a service account → `serviceAccountsEnabled is true, expected false (D-02)`; customer `verifyEmail` false → `(#859)`; Google enabled → `ADR-0005 ships it disabled`; user profile removed → `0 declarative-user-profile component(s)`; an offline render asserted in mode `apply` → `4 TEST-ONLY render value(s) present`; a vendor render asserted as `--kind customer` → 4 defects; a missing file → VOID rc 2. The clean render gave rc 0.

**Secrets through the environment:** `bash -x … --render-only --secrets-from-env` with four distinct values gave rc 0, and **0** of the values appeared in the trace or the output. Apply-mode assert rc 0. With one value empty, the run was refused: `REFUSED [secret-missing]: INTEGRATION_CATALOG_RO_SECRET is unset or empty`, rc 1.

**Task 2 verify line 1 (`--list-kv-objects`):** rc 0, exact_sorted_list=yes. Fail direction: a copy with `KC_ADMIN_USERNAME` removed from `KV_MAP` gives exact_sorted_list=no.

**Task 2 verify line 2 (selftest, 7 named arms):** rc 0, missing=0, fail_lines=0. Selftest output:
```
PASS raw-template rc=1 defects=24
PASS raw-customer-template rc=1 defects=10
PASS transformed vendor+customer rc=0 users=[service-account-* x4]
PASS survived-placeholder rc=1
PASS builtin-count template/render vendor=53/53 customer=11/11 drop-one-control=refused
PASS apply-no-context rc=2 az_calls=0 curl_calls=0 kubectl_calls=0
PASS apply-employer-context rc=1 az_calls=0 curl_calls=0 kubectl_calls=0 azure_guard_sourced=no
PASS apply-stale-server rc=1 az_calls=0 curl_calls=0 kubectl_calls=1(config view) azure_guard_sourced=no
PASS apply-kv-read-control rc=1 az_calls=7 kv_reads=6 pinned=7 port_forward=1 admin_calls=0 values_printed=0
```
Fail direction for the apply arms (scratch copies of the script and libraries; the tracked files were not touched):
- A, guard call removed: `FAIL apply-no-context rc=1 (want 2): STEP: sourcing … REFUSED [subscription]`; `FAIL apply-employer-context stderr lacks 'REFUSED [employer-context]'`; `FAIL apply-stale-server stderr lacks 'REFUSED [server-url]'`.
- B, Azure library sourced before the guard: all three arms fail with `the Azure guard library was sourced before the refusal`.
- D, a Key Vault value echoed: `FAIL apply-kv-read-control 1 Key Vault value(s) printed`.

The control arm is what makes the refusal arms' "az_calls=0" meaningful: with the guard satisfied, the same stubs record 7 az calls, all ending in the pin, and the port-forward through the named context.

**Task 2 verify line 3:** `--apply` with no context gave rc 2 and `USAGE ERROR: --context is required …`. Fail direction: `--apply --context sipbihs2aks` gave rc 1, so the line's condition fails.

**Acceptance (port-forward / auth host):** `port-forward` appears 12 times. `https://auth-staging` appears 0 times. `ADMIN_BASE="http://127.0.0.1:18080"` is a single line. All four curl call sites target `${ADMIN_BASE}` (three) or `${MGMT_BASE}` (the readiness poll), both on 127.0.0.1. The zero is only as good as the grep: a planted `https://auth-staging` URL would make it 1, but no such plant was run, so the absence check itself is unfalsified.

**Acceptance (README `frontend` review), each hit read line by line:**
- 19: "There is **no client named `frontend`**: the frontend app signs in through `core-api`". Denies the client; the second use is the app.
- 20: "`k8s/base/configmap.yaml` still names `frontend`". The configmap VALUE (Blocker A), not a realm client.
- 37: "core-api — the client the frontend app (NextAuth) signs in through". The app.
- 50–52: "Until plan 29-04 this README also described a public `frontend` client. No version of either tracked realm file has ever held one … the deployed configmap still names `frontend`". Historical and denying. Evidence: `git log -G'"clientId" *: *"frontend"'` over both realm files finds 0 commits; positive control: the same query for `test-client` finds 3 (bb2ae65d, 9d02e297, a7401040).
- 65: "the 3 k8s frontend replicas". The app.
- 355, 357: `frontend/tailwind.config.ts`, `frontend/app/globals.css`. App paths.
- 790–791: the Version History entry, reworded to say no `frontend` client ever existed.

No remaining line says a `frontend` client exists.

**Gates touched:**
- `check-az-subscription-pin.sh`: rc 0, findings 0. The new script is discovered: discovered 3→4 and wrapper_calls 15→16, against a HEAD-tree control. A raw `$(az keyvault list)` planted in a copy is a FINDING, rc 1.
- `check-doc-citations.sh`, under the jtoye-ops env PATH: PASS, 46 verified, 0 violations.
- `check-gate-enforcement.sh`: PASS.
- `actionlint` on ci-cd.yaml: rc 0. The YAML parses, and k8s-validate has 12 steps including the new one.

**The live run happens in plan 29-14 (tracer).** This plan proves the offline and refusal paths only. The admin token, GET/POST/DELETE, readiness and read-back code has never run against a Keycloak (coverage D4, human_judgment true).

## Files Created/Modified

- `infra/keycloak/staging-realm.jq`: the staging transform, both realms.
- `scripts/staging-realm-import.sh`: render, assert, list-kv-objects, apply, selftest.
- `infra/keycloak/README.md`: Blocker A correction, staging section, staging notes in three sections.
- `.github/workflows/ci-cd.yaml`: k8s-validate step `Prove the staging realm transform and import guards (29-04 --selftest)`.
- `.planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-04-t1-red-transformed.json`: the RED record.

## Decisions Made

See key-decisions in the frontmatter. In addition:
- The Mailpit `from` is `no-reply@olajay.co.uk`. The dev value's `.local` domain is not promotable, as the README already said.
- The service-account users of the disabled `integration-*` clients keep the dev tenant id `…0001`. Their clients cannot mint tokens, so it is inert, and the transform does not edit it.
- The Google client secret in mode `live*` may read back as the admin API mask `**********`. Everywhere else it must be empty.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] The selftest is wired into CI**
- **Found during:** Task 2
- **Issue:** The plan's files list does not include `ci-cd.yaml`. Unwired, the selftest would run nowhere on pull requests, so a later template edit could break the staging realm with every check green. 29-03 wired its bootstrap selftest the same way.
- **Fix:** One k8s-validate step after the 29-03 bootstrap selftest.
- **Files modified:** `.github/workflows/ci-cd.yaml`
- **Verification:** actionlint rc 0; YAML parses; step present.
- **Committed in:** `b8073aea`

**2. [Rule 2 - Missing critical] Extra selftest arms beyond the plan's seven**
- **Found during:** Tasks 1–2
- **Issue:** The plan's arms prove refusal on the vendor template and three apply refusals. Nothing proved the customer template is refused, and nothing showed the stub logs could record a call at all, so the refusals' zero counts were unfalsifiable.
- **Fix:** Added `raw-customer-template` (compose-shaped customer render refused: mailhog, verifyEmail false, localhost storefront URIs) and `apply-kv-read-control` (positive control). The plan's seven named arms are unchanged.
- **Committed in:** `c5af00f1`, `b8073aea`

**3. [Rule 1 - Bug] README claims corrected beyond the `frontend` client**
- **Found during:** Task 2
- **Issue:** The README also stated that core-api has its service account enabled (false since #947) and that staging does not consume the templates. Two first drafts of my own were wrong as well: one said staging already patches `client-id` to core-api (it does not; Blocker A is still open), the other that plan 28-05 removed a frontend client (it removed `test-client`). Both were measured and corrected before commit.
- **Fix:** Every one of these claims now matches what the repository holds.
- **Committed in:** `b8073aea`

**4. [Rule 2 - Missing critical] Assertion mode live-existing**
- **Found during:** Task 2
- **Issue:** The plan reads back and asserts a skipped (already present) realm under the strict users check. That check would refuse the realm as soon as 29-06 creates the test vendor, or a customer registers.
- **Fix:** A skipped realm is asserted in mode `live-existing`. Post-import users are allowed, but a template seed user is still refused. A fresh or recreated import stays strict (`live`).
- **Committed in:** `b8073aea`

---

**Total deviations:** 4 auto-fixed (3 missing critical, 1 bug). **Impact:** no scope creep; each one either makes a check falsifiable or keeps it true after later plans run.

## Issues Encountered

- A quote inside the single-quoted jq program comment broke the shell parse (rc 2). Found by running the selftest, fixed by rewording, before any commit.

## Known Stubs

None. The `TEST-ONLY-NOT-A-SECRET-*` values are deliberate offline fixtures, and apply mode refuses them.

## Threat Flags

None beyond the plan's register. T-29-12 and T-29-13 are asserted on the render and on the read-back. T-29-14 is covered by the admin API at 127.0.0.1 only; the ingress half belongs to 29-10. T-29-15 is covered by env, @file and -K inputs, a mode-700 workdir with a trap, and 0 values printed (measured).

## User Setup Required

None. The live import runs in plan 29-14 from a shell that exports `JTOYE_AZURE_SUBSCRIPTION_ID`.

## Next Phase Readiness

- **29-13 (KV writer):** generate exactly the six objects `--list-kv-objects` prints. `keycloak-credentials--frontend-client-secret` is shared with the frontend SecretProviderClass (29-12).
- **29-14 (tracer):** first live `--apply`. Watch these unmeasured points:
  - KC 26 `/health/ready` on management port 9000 needs `KC_HEALTH_ENABLED=true` in the 29-xx Keycloak manifest.
  - Whether `GET /users` returns service-account users (either way passes).
  - Whether the realm representation the admin API returns contains any string matching the four forbidden substrings (it would be refused, by design).
  - The master admin-cli token lifetime (a fresh token is taken per realm).
- **29-22 (Mailpit):** Keycloak itself will STARTTLS to Mailpit, so the Keycloak pod must trust the cert-manager CA as well. That is the same class as D-21/D-30, but for Keycloak, not core-java.
- **Blocker A** is not closed here. The staging overlay must still patch `keycloak.client-id` to `core-api`.
- **29-06:** the vendor realm's `tenant_id` stays managed (asserted on the live read-back too), so its admin-API user create keeps the attribute.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-18*

## Self-Check: PASSED

- FOUND: infra/keycloak/staging-realm.jq, scripts/staging-realm-import.sh, evidence/29-04-t1-red-transformed.json, infra/keycloak/README.md, .github/workflows/ci-cd.yaml
- FOUND (ancestors of HEAD): c5af00f1, d3a47641, b8073aea
- commits measured: `git rev-list --count 68f52d06..HEAD` = 3 (before the SUMMARY commit)
