---
phase: 37-real-world-operations-readiness
plan: 28
subsystem: api
tags: [cash-order-caps, client-ip, hmac-sha256, pseudonymisation, retention, gdpr, article-17, rls, tenant-pinned-sweep, secrets, compose, k8s, env-contract, d-18, d-19, d-27, rwo-041, rwo-006]

requires:
  - phase: 37-27
    provides: "V81's orders.client_ip_digest (@NotAudited, no audit mirror) and idx_orders_open_cash_ip; CashOrderCaps with per-ip bound; the refusal-inside-the-reserved-work shape"
  - phase: 37-24
    provides: "ClientIpResolver (D-19): the one trusted-proxy resolution of the client address, now digested on the storefront order path"
  - phase: 31.1-07
    provides: "the DSAR_ENCRYPTION_KEY precedent: a 64-hex secret validated at bean construction, required by compose, verify-env.sh, the nightly and a non-optional k8s secretKeyRef"
provides:
  - "ClientIpDigester: HMAC-SHA256 under jtoye.storefront.client-ip-hmac-key (CLIENT_IP_HMAC_KEY, exactly 64 hex, refused at construction naming the property and never the value) over ClientIpResolver.canonicalLiteral(ip); 64 lower-case hex; null for nothing. The address is never stored, logged or returned"
  - "The per-IP axis: CashOrderCaps.checkIp over OrderRepository.countOpenCashOrdersByClientIpDigest (idx_orders_open_cash_ip verbatim); the storefront controller resolves the peer, the five-argument createGuestOrder digests it before any write, placeGuestOrder stores the digest on every storefront order and refuses the sixth open cash order from one connection 429 order-cap-exceeded axis IP cap 5 inside the reserved idempotency work (no row, no reservation)"
  - "A declared lifetime: OrderService.transitionOrder nulls the digest at COMPLETED / CANCELLED / REFUNDED; ClientIpDigestRetentionSweep nulls any digest older than jtoye.storefront.client-ip-digest.retention-hours (CLIENT_IP_DIGEST_RETENTION_HOURS, 72) daily in every tenant, each in its own transaction with TenantContext + GUC pinned and SystemPrincipal declared; GdprService.anonymiseSubjectInTenant nulls it with the rest of the order PII"
  - "The key in every runtime: compose ${CLIENT_IP_HMAC_KEY:?...}, .env.example, verify-env.sh (required + 64-hex format rule), e2e-nightly.yml (DERIVED + openssl rand -hex 32), k8s Secret client-ip-credentials/hmac-key with no optional flag, secrets-template, QUICK_START, DEPLOYMENT, sealed-secrets runbook, k8s-local-secrets.sh, check-env-contract.sh MUST_SUPPLY_CORE; the three caps and the retention window as app-config keys read by the deployment; goldens regenerated"
affects: [37-29, 37-30, 37-31, 37-D-gate, retention-register, k8s-operators]

actuals:
  tokens: 29550
  tasks: 3
  commits: 8
  plan_head_before: 5423a35e0ad02da510bab99a8f0d063465050867
  plan_head_after: d7e72448

tech-stack:
  added: []
  patterns:
    - "A pseudonymous identifier is HMAC under a runtime-required 64-hex key (never a plain hash of an enumerable space), stored on the live row only, and carries three ends: terminal state, a tenant-pinned daily sweep, and erasure"
    - "A retention sweep over a FORCE-RLS table iterates the tenants registry and pins each tenant in its own transaction; the break arm that drops the pin must leave every tenant's rows untouched under NOSUPERUSER"
    - "An IT that predicts a keyed digest pins its own fixture key through DynamicPropertySource: the test profile's ${random.value}${random.value} is re-drawn per resolution and cannot be read twice"
    - "Every MockMvc IT that places more than per-ip open cash orders at one shop sends each arm from its own RFC 5737 peer (RESEARCH Pitfall 9, third axis)"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/storefront/ClientIpDigester.java
    - core-java/src/main/java/uk/jtoye/core/storefront/ClientIpDigestRetentionSweep.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ClientIpDigesterTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ClientIpCapIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ClientIpDigestRetentionIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-28-red-task1-unit.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-28-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-28-red-task2.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/CashOrderCaps.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/resources/application-test.yml
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/AllergyNoteAckIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/CustomerRecordedAllergenSetIntegrationTest.java
    - docker-compose.full-stack.yml
    - .env.example
    - scripts/verify-env.sh
    - .github/workflows/e2e-nightly.yml
    - k8s/base/core-java-deployment.yaml
    - k8s/base/configmap.yaml
    - k8s/base/secrets-template.yaml.example
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/scripts/check-env-contract.sh
    - scripts/k8s-local-secrets.sh
    - k8s/QUICK_START.md
    - k8s/DEPLOYMENT.md
    - docs/runbooks/sealed-secrets.md
    - scripts/gates/object-store-residue-allowlist.conf
    - .gitleaks.toml
    - .planning/phases/37-real-world-operations-readiness/37-USER-SETUP.md

key-decisions:
  - "The digest input is the resolver's canonical text (ClientIpResolver.canonicalLiteral, public, added to 37-24's bean): one spelling rule owned by the resolver, so the rate limiter and the cap key on the same client; the digester HMACs its UTF-8 bytes"
  - "The digest is written on EVERY storefront order, card included; the IP axis is consulted on the cash branch only (card is the customer's own commitment); vendor / REST / MCP orders record NULL (no customer connection)"
  - "A null or blank resolved address digests to null and skips the IP cap; the resolver's 'unknown' sentinel (a request with no peer at all) digests as written, one count, as the rate limiter keys it to one bucket"
  - "The sweep's cron is a config literal (03:20 daily); the window (retention-hours) is the env tunable wired through the configmap — an env placeholder for the hour would demand a configmap key for an incidental"
  - "A new Secret client-ip-credentials/hmac-key rather than a second key on dsar-credentials: the key is not DSAR data and has its own rotation story (rotation only changes which open orders a client is counted against; nothing becomes unreadable)"
  - "The three caps became ${CASH_CAP_PER_EMAIL:3} etc. in place and app-config keys (the 37-27 deferred item), compose keeps the defaults by design (Pitfall 9)"

patterns-established:
  - "Keyed-pseudonym lifecycle: write at the one entry point, null at terminal states on the one transition path, sweep per tenant daily, null on erasure; no audit mirror"
  - "One-way NOSUPERUSER fixture: downgrade once per container and run every seed and read inside a tenant-pinned transaction, with a 'the pinned session sees the row' assertion so a NULL from a hidden row cannot read as a nulled value"

requirements-completed: [RWO-041, RWO-006]

coverage:
  - id: D1
    description: "ClientIpDigester: same key + same IP -> one 64-hex digest equal to an independent HMAC-SHA256; different key -> different digest; mapped IPv6 and IPv6 spellings fold; invalid key refused at construction naming the property, never the value"
    requirement: "RWO-041"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ClientIpDigesterTest.java (RED 7d0d3a5e: 12 ran / 11 failed, 'Expecting actual not to be null' / 'Expecting code to raise a throwable', evidence/37-28-red-task1-unit.json RED_EVIDENCE_OK; GREEN a54b679a 12/0; tracer gate re-run on fbcc43d4 12/0 fresh report)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Five open cash orders from one connection (fresh email and phone each) are 201; the sixth from the same client spelled ::ffff:… is 429 order-cap-exceeded axis IP cap 5, no Retry-After, no row, no reservation, the address never echoed; a sixth from another peer is 201; every row holds HMAC-SHA256(key, ip) and no column equals or contains the address; completing one frees the slot and the refused key is 201; a vendor order records NULL"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ClientIpCapIntegrationTest.java (RED 7d0d3a5e: 3 ran / 2 failed — client_ip_digest NULL, the sixth order ACCEPTED HTTP 201, evidence/37-28-red-task1.json RED_EVIDENCE_OK; GREEN a54b679a 3/0 after the IT pinned a fixture key; tracer gate re-run 3/0)"
        status: pass
      - kind: integration
        ref: "regression on the committed tree d7e72448: storefront.* + order.* + review.* + RlsContractTest + OpenApiSnapshotTest 30 classes / 208 / 0 (after fbcc43d4 gave three ITs a peer per arm: 15 failures before, all '429 order-cap-exceeded (IP)' from MockMvc's shared 127.0.0.1); OpenAPI snapshot unchanged (hidden HttpServletRequest parameter)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The digest's lifetime: completing nulls it (CONFIRMED keeps it) and cancelling nulls it, through the KDS transition endpoints; the sweep nulls a 73 h digest on a PENDING order in tenants A and B under NOSUPERUSER and leaves a 71 h one in both, touching nothing else; erasure nulls it with the rest of the PII; the sweep break arm (pin dropped) leaves both tenants' digests in place"
    requirement: "RWO-006"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ClientIpDigestRetentionIntegrationTest.java (RED 8d2c9c8a: 3 ran / 3 failed on the planned assertions after their controls passed — 'expected: 2 but was: 0', 'expected: null but was: <digest>' x2, evidence/37-28-red-task2.json RED_EVIDENCE_OK; GREEN 65ba6565 3/0; break arm: TenantContext.set and pinTenantGuc removed -> 3 ran / 1 failed exactly the sweep arm 'expected: 2 but was: 0' with the other two green; restore by cp verified md5 f218df79defb3d350274a0a1f43e8618 before and after and an empty git status for the file; closing clean 3/0)"
        status: pass
      - kind: integration
        ref: "uk.jtoye.core.gdpr.* in the same verify run: 11 classes / 89 / 0 (92/0 with the retention IT); check-retention-enforcement PASS 14 rows"
        status: pass
    human_judgment: false
  - id: D4
    description: "The key is required and wired in every runtime and the caps are declared in k8s: compose refuses to interpolate without it, .env.example documents it, verify-env.sh requires it and checks the 64-hex shape, the nightly generates it, the k8s deployment reads it from a non-optional Secret and the four app-config keys, the templates and three operator docs describe the Secret, the local overlay script creates it, the env-contract gate's must-supply list names it"
    requirement: "RWO-041"
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-env-contract.sh rc 0 (PASS core-java 81 injected / 179 read); break arm: the CLIENT_IP_HMAC_KEY injection removed from the deployment -> rc 1 naming CLIENT_IP_HMAC_KEY, restored by md5 6e4779e70fcf1d2bdcfcedcfb8321cc5, clean rc 0"
        status: pass
      - kind: other
        ref: "bash -n scripts/verify-env.sh && grep -c CLIENT_IP_HMAC_KEY -> .env.example 1, verify-env.sh 2, compose 1, e2e-nightly.yml 3, core-java-deployment.yaml 1, rc 0; verify-env.sh .env (64-hex key) rc 0 'All 20 required credential variables are set'; verify-env.sh .env.example rc 1 (CHANGE_ME: weak deny-list + 'must be exactly 64 hexadecimal characters'); a scratch copy with a 63-hex value rc 1 on the format rule; check-env-example-contract PASS 20"
        status: pass
      - kind: other
        ref: "render-golden.sh --write then check rc 0 (+29 lines per golden: the Secret ref and four configMapKeyRefs, 5 hits each); check-render-invariants PASS (INV-12 WARN on the empty trusted-proxies, the 37-24 operator input, not a failure); check-no-plaintext-secrets PASS 25 resources / 0; docker compose config rc 0 with the key, rc 1 without it naming CLIENT_IP_HMAC_KEY; ConfigKeyContractTest 9/0 and CashOrderCapsTest 20/0 after the yml placeholders"
        status: pass
    human_judgment: false
  - id: D5
    description: "Nothing else moved: the full unit suite, the broad IT regression and every repository gate are green; the two runtime-parity gates and the docs-metrics drift are recorded as expected until the 37-D gate rebuilds"
    verification:
      - kind: unit
        ref: "./gradlew :core-java:cleanTest :core-java:test -> 206 classes / 1753 tests / 0 failures / 1 skipped (1741 after 37-27 + 12 new)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests storefront.* order.* review.* security.RlsContractTest integration.OpenApiSnapshotTest -> 30 classes / 208 / 0"
        status: pass
      - kind: other
        ref: "check-doc-citations PASS 29 / 0 (four application.yml citations re-pointed three times, +9 / +12 / +1, each with the measured anchor; the pre-existing terminal-states.yaml VOID printed, rc 0); check-doc-metrics PASS 37/37; check-no-create-extension PASS 81; check-strict-scoping-default PASS; check-no-object-store-residue PASS 51 after two line-pinned entries moved by the .env.example block were renumbered (the gate's stale-entry FAIL recorded as its fail direction); gitleaks over 5423a35e..HEAD 0 leaks after the content allowlist (4 before: the four 64-hex fixture keys; near-miss break arm rc 1). Recorded, not fixed here: check-openapi-snapshot-fresh FAIL rc 1 on A-1 (compose still 6a825865); docs-freshness rc 1 (counts drift: java_test_methods 2626, files 380, 5573 invocations)"
        status: pass
    human_judgment: false

duration: 37 min
completed: 2026-10-11
status: complete
---

# Phase 37 Plan 28: Keyed client-IP digest, the per-IP cash-order cap, and the digest's declared lifetime Summary

**One connection can no longer flood a shop with cash orders by inventing a fresh email and phone per order, and nobody's IP address is stored to achieve it. `ClientIpDigester` turns the address `ClientIpResolver` resolved (D-19) into an HMAC-SHA256 under a key core-java refuses to start without (`CLIENT_IP_HMAC_KEY`, exactly 64 hex, the `DSAR_ENCRYPTION_KEY` precedent) over the resolver's canonical spelling, so `::ffff:198.51.100.7` and `198.51.100.7` are one client and a different key gives unrelated digests. The storefront writes that digest on every order and the cash branch asks `CashOrderCaps.checkIp` over V81's `idx_orders_open_cash_ip`: the sixth open cash order from one connection is 429 `order-cap-exceeded` axis `IP`, no `Retry-After`, inside the reserved idempotency work, so a refusal leaves no row and no reservation and the same key is 201 once one order completes. Because a pseudonymous identifier then lives with the order (D-27), it has three ends, each proven under a NOSUPERUSER role: `OrderService` nulls it at COMPLETED / CANCELLED / REFUNDED on the one transition path; `ClientIpDigestRetentionSweep` nulls, once a day, every digest older than 72 hours in every tenant, one pinned at a time (the break arm that drops the pin leaves both tenants' digests in place); and Article 17 erasure nulls it with the rest of the PII. The key is required in every runtime — compose's `:?` guard, `.env.example`, `verify-env.sh`, the nightly's generator, a non-optional k8s Secret `client-ip-credentials`, the operator docs, the local overlay script and the env-contract gate's must-supply list — and the three caps and the retention window are declared in app-config and read by the deployment.**

## Performance

- **Duration:** 37 min of code commits (plan start 2026-10-11T23:16:04Z; last code commit d7e72448 at 23:52Z); SUMMARY after
- **Started:** 2026-10-11T23:16:04Z
- **Completed:** 2026-10-11
- **Tasks:** 3 of 3; Task 1 (tracer) and Task 2 each RED (`test(37-28)`, RED_EVIDENCE_OK) then GREEN (`feat(37-28)`); one test-only follow-up; Task 3 a `chore(37-28)` with two gate-maintenance commits; no REFACTOR commit was needed
- **Files modified:** 41 across the eight commits (1583 insertions, 22 deletions), plus this SUMMARY and deferred-items.md

## Accomplishments

- **Task 1 (tracer).** `ClientIpDigesterTest` (context-free) and `ClientIpCapIntegrationTest` (Testcontainers, the real storefront endpoint with the socket peer set, trusted-proxies empty so the peer is the client).
  - **RED** (`7d0d3a5e`; `37-28-red-task1-unit.json` 12 ran / 11 failed, `37-28-red-task1.json` 3 ran / 2 failed, both RED_EVIDENCE_OK): the scaffold digester returned null and validated nothing; on Postgres the five rows read `client_ip_digest` NULL and the sixth order from one connection was ACCEPTED (`HTTP 201 … ORD-00000000-20261012-8549CDFE PENDING`). The vendor-NULL arm and the null-or-blank arm passed on the scaffold, as planned.
  - **GREEN** (`a54b679a`): `ClientIpDigester` (HMAC-SHA256, hex-decoded key validated at construction with the property name and the generation hint, never the value; per-call `Mac`); `ClientIpResolver.canonicalLiteral` (public, the one spelling rule); `OrderRepository.countOpenCashOrdersByClientIpDigest`; `CashOrderCaps.checkIp` (null digest = no identity, not counted); the controller resolves the peer and passes it to the five-argument `createGuestOrder`, which digests before any write; `placeGuestOrder` stores the digest on every storefront order and asks the IP axis on the cash branch after email and phone; `application.yml` `client-ip-hmac-key: ${CLIENT_IP_HMAC_KEY:}`; the test profile's per-run random key; the unit test's fixture-keyed digester. 12/0, ConfigKeyContractTest 9/0, IT 3/0.
  - **Tracer gate** (interactive, `end-of-phase`, automated-only `<verify>`): both verify commands re-run on the committed tree `fbcc43d4`, rc 0, fresh reports, the test tasks executed (not UP-TO-DATE).
- **Task 2.** `ClientIpDigestRetentionIntegrationTest`: NOSUPERUSER from the first arm (one-way per container), every seed and read inside a tenant-pinned transaction with a "the pinned session sees the row" assertion. **RED** (`8d2c9c8a`; 3 ran / 3 failed on the planned assertions after their controls passed, RED_EVIDENCE_OK). **GREEN** (`65ba6565`): the terminal-state null in `transitionOrder`; the sweep (tenants registry, `TransactionTemplate` per tenant, `TenantContext` + `set_config` + `SystemPrincipal.asSystem`, `UPDATE … WHERE tenant_id = :tenantId AND client_ip_digest IS NOT NULL AND created_at < now() - make_interval(hours => :hours)`, cron from config, counts only in logs, a value below 1 refuses to start); the erasure null; `retention-hours: ${CLIENT_IP_DIGEST_RETENTION_HOURS:72}` and `sweep-cron`. 3/0; `gdpr.*` 11 classes / 89 / 0. **Break arm (recorded):** `TenantContext.set` and `pinTenantGuc` removed → 3 ran / **1 failed**, exactly the sweep arm (`expected: 2 but was: 0` — the unpinned UPDATE matched zero rows in both tenants under FORCE RLS) with the terminal-state and erasure arms still green; restore by `cp` verified by md5 `f218df79…` before and after and an empty `git status`; closing clean 3/0.
- **Task 3** (`7b811046`): every `DSAR_ENCRYPTION_KEY` site mirrored for `CLIENT_IP_HMAC_KEY`, plus the four app-config keys (`storefront.cash-caps.per-email` 3 / `per-phone` 3 / `per-ip` 5 / `client-ip-digest.retention-hours` 72) and their `configMapKeyRef`s, the three caps as `${CASH_CAP_*:n}` in place, the Secret in `secrets-template`, `QUICK_START`, `DEPLOYMENT`, the sealed-secrets runbook and `k8s-local-secrets.sh` (required, 64-hex validated, created), `check-env-contract.sh` `MUST_SUPPLY_CORE` (with its break arm), goldens regenerated. Gates: env-contract PASS, render check 0, render invariants PASS, no-plaintext-secrets PASS, env-example contract PASS 20, `verify-env.sh` PASS on `.env` and FAIL on `.env.example` and on a 63-hex copy, `docker compose config` 0 with the key and 1 without. The operator step is in `37-USER-SETUP.md` (the sealed `client-ip-credentials` Secret per environment, with the Secret-length and six-order probes).
- **Regression** on `d7e72448`: full `:core-java:test` **206 classes / 1753 / 0 / 1 skipped**; `:core-java:integrationTest` over `storefront.*`, `order.*`, `review.*`, `RlsContractTest`, `OpenApiSnapshotTest`: **30 classes / 208 / 0**. Gates: doc citations PASS 29/0, doc metrics PASS 37/37, no-create-extension PASS 81, strict-scoping PASS, residue PASS 51, retention enforcement PASS 14, gitleaks 0 over the range.

## Task Commits

1. **Task 1: the sixth open cash order from one connection is refused, with only a digest stored** — `7d0d3a5e` (test, RED), `a54b679a` (feat, GREEN), `fbcc43d4` (test: three ITs send each arm from its own peer, Rule 1)
2. **Task 2: the digest's declared lifetime** — `8d2c9c8a` (test, RED), `65ba6565` (feat, GREEN)
3. **Task 3: the key is required and wired in every runtime** — `7b811046` (chore), `3206551a` (chore: two residue-allowlist entries renumbered), `d7e72448` (chore(gitleaks): the four fixture keys allowlisted by content)

**Plan metadata:** this SUMMARY and `deferred-items.md` in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's.

`commits: 8` is `git rev-list --count 5423a35e..d7e72448`; `gsd_run check evaluation-scope --plan 37-28 --commits-only` → `status: resolved`, 7 commits (the `chore(gitleaks)` commit carries its own scope, as 37-27's did).

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `7d0d3a5e` test(37-28), RED_EVIDENCE_OK x2 (`37-28-red-task1-unit.json` 12 / 11; `37-28-red-task1.json` 3 / 2) | `a54b679a` feat(37-28) | none needed |
| 2 | `8d2c9c8a` test(37-28), RED_EVIDENCE_OK (`37-28-red-task2.json` 3 / 3) | `65ba6565` feat(37-28) | none needed |
| 3 | not a TDD task (config / infra; its checks are the gates and both-direction runs above) | `7b811046` chore(37-28) | — |

Semantic assessment for both TDD tasks: the target executed and failed on its planned assertion for the intended reason (no digest, no validation, no IP count; no terminal-state null, no sweep, no erasure null) with the context and Postgres up and the sibling controls passing in the same run; no compile, load, fixture or syntax fault in the recorded runs. Task 2's first two runs were VOID on fixture faults (Issues) and are not the record.

## Files Created/Modified

- `storefront/ClientIpDigester.java`: the keyed digest; `storefront/ClientIpDigestRetentionSweep.java`: the daily per-tenant null; `security/ClientIpResolver.java`: `canonicalLiteral`
- `order/OrderRepository.java`: `countOpenCashOrdersByClientIpDigest`; `storefront/CashOrderCaps.java`: `checkIp`; `storefront/PublicStorefrontService.java`: digester bean, five-argument `createGuestOrder`, digest written, IP axis on the cash branch; `storefront/PublicStorefrontController.java`: resolver bean, peer resolved on the guest-order POST (hidden parameter; OpenAPI unchanged)
- `order/OrderService.java`: terminal-state null; `gdpr/GdprService.java`: erasure null
- `application.yml`: `client-ip-hmac-key`, `client-ip-digest.{retention-hours, sweep-cron}`, the caps as placeholders; `application-test.yml`: per-run random key
- `ClientIpDigesterTest` (12), `ClientIpCapIntegrationTest` (3), `ClientIpDigestRetentionIntegrationTest` (3): new; `PublicStorefrontServiceTest` (bean), `CashOrderCapIntegrationTest`, `AllergyNoteAckIntegrationTest`, `CustomerRecordedAllergenSetIntegrationTest` (a peer per arm)
- `docker-compose.full-stack.yml`, `.env.example`, `scripts/verify-env.sh`, `.github/workflows/e2e-nightly.yml`, `k8s/base/{core-java-deployment,configmap}.yaml`, `k8s/base/secrets-template.yaml.example`, `k8s/goldens/{staging,production}.yaml` (regenerated), `k8s/scripts/check-env-contract.sh`, `scripts/k8s-local-secrets.sh`, `k8s/QUICK_START.md`, `k8s/DEPLOYMENT.md`, `docs/runbooks/sealed-secrets.md`
- `scripts/gates/object-store-residue-allowlist.conf` (two entries renumbered), `.gitleaks.toml` (content allowlist), `k8s/LOCAL.md`, `.planning/codebase/{STACK,INTEGRATIONS}.md` (citations re-pointed), `37-USER-SETUP.md`, `evidence/37-28-red-task{1-unit,1,2}.json`, `deferred-items.md` (five entries added, two 37-27 entries resolved)

## Decisions Made

See `key-decisions`. Two depart from the plan's letter: `ClientIpResolver` (37-24's file, not in the list) gained one public static helper so the digester shares the resolver's spelling rule instead of carrying a second canonicaliser; and the sweep's cron has no env placeholder (the window is the tunable).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The IT could not predict the digest under the test profile's random key**
- **Found during:** Task 1 GREEN (`expected: "5c92…" but was: "cc19…"`)
- **Issue:** `${random.value}${random.value}` is re-drawn on every resolution, so the IT's second `@Value` read gave a different key from the bean's.
- **Fix:** the IT pins a fixture key through `DynamicPropertySource` and computes the expected digest independently with `javax.crypto`; the random key stays for every other test.
- **Committed in:** `a54b679a`

**2. [Rule 1 - Bug] Three integration tests assumed an unbounded connection**
- **Found during:** the storefront/order/review regression after Task 1 GREEN (15 failures, every one `429 order-cap-exceeded (IP)`: `CashOrderCapIntegrationTest` 7, `AllergyNoteAckIntegrationTest` 5, `CustomerRecordedAllergenSetIntegrationTest` 3 — MockMvc sends every request from 127.0.0.1, so one "client" held more than five open cash orders across a class run)
- **Fix:** each arm draws its own RFC 5737 peer in the class's `perform` helper (never a raised default, Pitfall 9).
- **Verification:** the three classes 12/0, 9/0, 10/0; the 30-class group 208/0.
- **Committed in:** `fbcc43d4`

**3. [Rule 3 - Blocking] Four `application.yml` citations and two residue-allowlist entries moved**
- **Found during:** Task 1, 2 and 3 GREEN (`check-doc-citations` 4 violations each time the yml grew: +9, +12, +1) and the final gates (`check-no-object-store-residue` 2 stale entries after the 13-line `.env.example` block)
- **Fix:** re-pointed by the measured anchor each time (`grep -F` — this machine's `grep` is ugrep and the `${…}` anchor read empty on the first try); `STACK.md`'s prose anchor `resilience4j:` 893 → 906; the two allowlist entries 238 → 251 and 553 → 566.
- **Committed in:** `a54b679a`, `65ba6565`, `7b811046`, `3206551a`

**4. [Rule 2 - Missing critical] `check-env-contract.sh` MUST_SUPPLY_CORE, `k8s-local-secrets.sh` and the three operator docs**
- **Found during:** Task 3 (not in the file list)
- **Issue:** without the must-supply entry, deleting the Secret injection would pass the gate "by rule" (the gate's own note for the DSAR key); without the local-secrets entry, `k8s/local` pods would hold in CreateContainerConfigError on a non-optional ref; the docs list every required Secret.
- **Fix:** all four mirrored; the gate's break arm recorded.
- **Committed in:** `7b811046`

**5. [Rule 3 - Blocking] gitleaks on the four fixture keys**
- **Found during:** the pre-SUMMARY gitleaks run (4 `generic-api-key` hits: `KEY_A`, `KEY_B`, `TEST_HMAC_KEY`, `HMAC_KEY`)
- **Fix:** a content allowlist in the 37-20 / 37-27 shape (exact values on the secret; never by path), proven by a near-miss break arm (last character changed still fires); its own `chore(gitleaks):` commit.
- **Committed in:** `d7e72448`

### Plan wording that did not hold (recorded, not relabelled)
- **"UP-TO-DATE" in `<fails_when>`.** Gradle prints `UP-TO-DATE` for `compileJava` and friends on every incremental run; the check applied is that the `:test` / `:integrationTest` tasks themselves executed (fresh report timestamps), which they did on every recorded run.
- **"`--tests 'uk.jtoye.core.gdpr.*'`"** matched 11 classes / 89 tests; all green.
- **"IP count asked for cash orders"** — asked on the cash branch, after email and phone, as the plan's `<interfaces>` and 37-27's placement require.

**Total deviations:** 5 auto-fixed (3 blocking, 1 bug, 1 missing critical), 3 plan-wording records. **Impact:** no scope creep; every fix was required for a gate to read the tree correctly, for a runtime to start, or for an existing test to stop assuming the behaviour this plan removes.

## Issues Encountered

- **Task 2's first two RED runs were VOID**: (1) a role downgraded to NOSUPERUSER inside an arm cannot restore itself (`ALTER ROLE … SUPERUSER` is a superuser's statement), so the restore in a `finally` failed and the later arms' superuser seeds hit RLS; (2) `GuestOrderAcknowledgements.currentMask` ran outside the pinned transaction and saw no product. The fixture now downgrades once per container and runs every seed and read inside a tenant-pinned transaction; the third run (3 / 3 on the planned assertions) is the record.
- **ugrep braces**: `grep -n 'mode: ${STOMP_BROKER_MODE:in-memory}'` returned nothing on the first citation re-point because `{}` are metacharacters to this machine's grep; `grep -F` found it at 610. Recorded because an empty anchor would have re-pointed by the wrong shift.
- `scripts/check-doc-citations.sh` prints the pre-existing VOID for `docs/ops/terminal-states.yaml` (37-24's deferred item); rc 0.
- `scripts/docs-freshness.sh` rc 1 (5573 invocations): this plan's three new Java test files on top of the 37-24..37-27 drift; `check-doc-metrics` PASS 37/37; owed to the 37-D PR docs step (deferred-items).
- `scripts/check-openapi-snapshot-fresh.sh` FAIL rc 1 on A-1: the running stack is still `6a825865` (expected until the 37-D gate rebuilds; the local `.env` now carries the key the rebuild needs).

## Known Stubs
None. The retention register row for the digest (R-16) is 37-30's with the customer-facing copy (plan probe note; deferred-items).

## Threat Flags

None new. T-37-71 (IP stored or reversible) is mitigated by D1 and D2 — keyed HMAC, the key refused when absent or malformed, the IT scans every column of the row for the address, no audit mirror (37-27's assertion still green in the group run); T-37-72 (indefinite retention) by D3 — terminal-state null, the tenant-pinned 72 h sweep with its break arm, the erasure null; T-37-73 (weak or default key) by D4 — 64-hex validation at startup, the compose `:?` guard, `verify-env.sh`'s format rule, the non-optional Secret, the must-supply entry with its break arm; T-37-SC held — nothing installed (`build.gradle.kts`, `package-lock.json` untouched). New surface, recorded: the guest-order POST now reads the socket peer through the existing resolver; the only thing stored is the digest, and the response never carries it or the address. Residual, by design: a flood that rotates connections (a botnet, a CGNAT pool shared with real customers) is bounded per connection only; the per-IP cap is deliberately the loosest of the three (5), and D-18 chose no captcha.

## User Setup Required

**External services require manual configuration.** See [37-USER-SETUP.md](./37-USER-SETUP.md) (appended) for:
- the sealed `client-ip-credentials` Secret (`hmac-key`, 64 hex from `openssl rand -hex 32`, one per environment) in staging and production — core-java refuses to start without it and the pod holds in CreateContainerConfigError
- the verification commands (Secret length, pod state, the six-order probe)

## Next Phase Readiness

- **37-29 (bulk reject):** `orders.cancel_reason_code` is in place; cancelling through `transitionOrder` now also nulls the digest, so a bulk reject needs nothing for D-27.
- **37-30 (kitchen badge + retention copy):** add the R-16 row for `jtoye.storefront.client-ip-digest.retention-hours` (consumer `ClientIpDigestRetentionSweep`) with the customer-facing wording so `check-retention-enforcement.sh` gates the 72.
- **37-31 (E2E):** a spec that trips the IP cap places six cash orders from one browser with fresh contact details each; the sixth is 429 axis `IP`. Every other spec that places more than five cash orders at one shop from one browser must complete or cancel them, or use another shop.
- **37-D gate:** the compose rebuild REQUIRES `CLIENT_IP_HMAC_KEY` in `.env` (present, untracked; `verify-env.sh` PASS). Live probes are in deferred-items. `check-openapi-snapshot-fresh.sh` stays FAIL on A-1 until then.
- **Requirements:** RWO-041 and RWO-006 are declared by this plan; the orchestrator's `requirements.ready-ids` gate decides whether a sibling plan also declares them.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-11*

## Self-Check: PASSED

- FOUND: `ClientIpDigester.java` (contains `HmacSHA256`), `ClientIpDigestRetentionSweep.java` (contains `retention-hours`), `GdprService.java` (contains `setClientIpDigest`), the three test classes, the three `evidence/37-28-red-*.json`, this SUMMARY
- FOUND commits (ancestors of HEAD): `7d0d3a5e`, `a54b679a`, `fbcc43d4`, `8d2c9c8a`, `65ba6565`, `7b811046`, `3206551a`, `d7e72448`; `git rev-list --count 5423a35e..d7e72448` = 8; `gsd_run check evaluation-scope --plan 37-28 --commits-only` → `status: resolved`, 7 commits
- TDD gate: 3 `test(37-28)` commits (two RED gates each preceding its `feat(37-28)` GREEN, one follow-up), 2 `feat(37-28)`, 0 `refactor(37-28)`
