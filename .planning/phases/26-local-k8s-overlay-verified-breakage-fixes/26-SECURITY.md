---
phase: 26
slug: local-k8s-overlay-verified-breakage-fixes
status: verified
threats_open: 0
asvs_level: 1
created: 2026-07-26
updated: 2026-07-26
remediation_round: 2
---

# Phase 26 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Infra/config phase — no schema change, no new API endpoint, one Spring config change (the additive
> STOMP credential chain), one new unit test. The security surface is the committed `k8s/local` overlay,
> five `k8s/base` config-defect fixes, a `BYPASSRLS` Postgres role bootstrap, the shared-dev-DB XOR
> guards, and the CI gate wiring. Register authored at plan time across all nine plans
> (`T-26-01`..`T-26-67` + `T-26-SC`); verified here in verify-mitigations mode.

**Verification stance.** No mitigation was accepted on a SUMMARY's word. Every `mitigate` threat was
checked against the artifact, and where the control is a shell assertion it was **falsified in an
isolated scratch copy of the tree** (`/tmp/.../scratchpad/tree`) by regressing the defect and observing
the gate go red. The repo working tree was never modified; minikube was never started; no DB role,
bucket, Secret or `kubectl apply` was created. Falsification results are cited inline as `FALSIFIED:`.

**Round 2 (2026-07-26).** Round 1 returned `OPEN_THREATS` with `threats_open: 1` (T-26-29) and seven
WARNING flags. `b7fcfc7` and `47e0564` closed T-26-29 and UF-26-04. Both were **re-verified here with
this auditor's own fixtures over the documented seams** (`k8s_local_compose_state`,
`k8s_local_cluster_pod_inventory`), before **and** after each commit, rather than from the fixer's
transcript — 18 compose-state fixtures + a broken-`awk` fault injection + a stale-writer cluster fixture
run against both the pre- and post-fix scripts. Scope was limited to those two items: the other 66 closed
threats were not re-audited, and UF-26-01/02/03/05/06/07 are unchanged. Two corrections to round 1's own
findings were forced by that re-verification and are recorded at
[§ Round-2 corrections](#round-2-corrections-to-this-auditors-own-findings) — round 1 **understated** the
fail-open surface and **proposed a closure that would have left two of the holes open**.

---

## Trust Boundaries

| Boundary | Description | Data / privilege crossing |
|----------|-------------|---------------------------|
| repo → cluster (`kubectl kustomize`) | Committed YAML becomes live cluster state; a wrong render is a live misconfiguration | Every env, selector, Ingress rule and TLS SAN |
| core-java pod → Postgres | The connection identity decides whether RLS applies at all | `DB_USER`/`DB_PASSWORD` — NOSUPERUSER `jtoye_app` vs the `jtoye` superuser |
| host superuser Postgres → `jtoye_backup` | A role that **bypasses every RLS policy** on the shared dev DB | `BYPASSRLS` attribute, all tenant rows |
| core-java pod → RabbitMQ STOMP relay | Broker credentials; a silent fallback authenticates as a shared anonymous principal | `stomp-login`/`stomp-passcode` vs `guest` |
| Keycloak → core / edge / frontend | `iss` + `aud` validation decides whether a foreign token is accepted | Split-horizon issuer pair (public/stamped vs pod-reachable JWKS) |
| browser → ingress-nginx → Services | The only local surface that exercises the Ingress path; TLS + security headers live here | Session cookies, authorization codes |
| committed realm template → live IdP | A redirect-URI change widens who may receive an authorization code | `redirectUris` on the `core-api` client |
| compose runtime ⊕ cluster runtime | Two writers on one shared dev Postgres — and a second session may own the compose stack | Concurrent DDL/DML on the dev database |
| developer host → kubectl context | The only context in kubeconfig is **employer AKS** — a mis-targeted apply is tampering on someone else's infrastructure | Every mutating `kubectl` call |
| CronJob → host MinIO | Full-database dumps leave the DB and land in object storage | Complete tenant dataset at rest |
| `.env` → cluster Secrets / git | Plaintext credentials cross from a gitignored file into cluster objects and (must not) into tracked docs | 13 credential values |
| PR → merged config → deploy | CI is the only automatic reviewer of manifest-vs-config drift | Whether a red gate can reach a cluster |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation (evidence) | Status |
|-----------|----------|-----------|-------------|------------------------|--------|
| T-26-01 | Elevation of Privilege | `core-java-deployment.yaml` DB env block | mitigate | `DB_USER`→`postgres-credentials/username` and `DB_PASSWORD`→`.../password` untouched (`core-java-deployment.yaml:98-107`); `DB_PORT` now `valueFrom.secretKeyRef` (`:88-92`). `check-connection-math.sh` still parses: exit 0, `133 ≤ 157`, drift + HPA-memory guards green. **FALSIFIED:** repointing `DB_USER` at the literal superuser `jtoye` → `render-golden.sh` exit 1 (named diff) **and** `check-connection-math.sh` exit 2 | closed |
| T-26-02 | Spoofing | STOMP relay credential resolution | mitigate | `application.yml:246-249` prefers `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` on all four keys; both `STOMP_CLIENT_*` refs are **non-`optional`** `secretKeyRef`s (`core-java-deployment.yaml:226-235`) so a missing key is `CreateContainerConfigError`, never a silent fallback. `StompCredentialResolutionTest` 8 tests / 0 failures on disk. **FALSIFIED:** deleting the `RABBITMQ_USER` injection makes `check-env-contract.sh` exit 1 — `RABBITMQ_USER (default: 'guest' — local-only token: 'guest')`. The `guest` net is live CI, not inspection | closed |
| T-26-03 | Tampering | kustomize `labels` transformer `fields:` list | mitigate | `base/kustomization.yaml:63-81` — `includeSelectors: false` + explicit 3-kind list. Deployment/Service/PDB selectors **keep** the labels byte-identically (`goldens/production.yaml` Deployment `matchLabels` carries all four keys) so no immutable-field apply failure; both goldens match at 1469 lines. **FALSIFIED:** reverting to the pre-phase `includeSelectors: true` shape → INV-3 exit 1 on base/local/staging/production **and** `render-golden.sh` exit 1 | closed |
| T-26-04 | Denial of Service | kube-dns egress `podSelector` | mitigate | INV-3 green on **all four** targets: `4 kube-dns selector block(s), each exactly 1 key`. **FALSIFIED:** the pre-phase transformer renders `{k8s-app, managed-by, part-of}` → `INV-3: the kube-dns podSelector at render line 1097 has 3 key(s)`, exit 1 per target | closed |
| T-26-05 | Information Disclosure | committed golden render files | accept | `grep -rc "kind: Secret" k8s/goldens/` → **none**, including all 9 `.pre/` snapshot dirs. `k8s/goldens/` has no `kustomization.yaml`, so it is not mistaken for a 5th overlay. `check-no-plaintext-secrets.sh` exit 0 on 4 targets. Logged AR-26-01 | closed |
| T-26-06 | Elevation of Privilege / Info Disclosure | `postgres-credentials` username in docs + template | mitigate | INV-5 green on all three doc sites (`QUICK_START.md:81`, `secrets-template.yaml.example:82` + `:228`) = `jtoye_app`. **FALSIFIED:** regressing each of the three to `jtoye` → exit 1 with the RLS-bypass rationale printed. Live corroboration `LOCAL.md` L2: validator counts 1/1/0 + DB-side `rolsuper=f` | closed |
| T-26-07 | Spoofing | `JWT_EXPECTED_ISSUER` / `keycloak.public.issuer.uri` | mitigate | Base + staging + production renders: `keycloak.public.issuer.uri` is **byte-identical** to `keycloak.issuer.uri` (verified on all three renders), so no environment's `iss` validation is widened. `AudienceValidator.java:26-31` **throws** on a null/blank audience, so `jwt.expected-audience` (`core-api`, identical to the `application.yml:131` default) cannot silently no-op | closed |
| T-26-08 | Information Disclosure | `cors.allowed-origins` | mitigate | One exact origin per overlay, no wildcard: base/prod `https://app.jtoye.co.uk`, staging `https://app-staging.jtoye.co.uk`, local `http://app.jtoye.local`. `grep -cE 'allowed-origins: "?\*'` = **0** on all four renders; `grep -cE 'localhost\|127.0.0.1'` = **0** on base/staging/production (2 in local, both the deliberate browser-facing stamped issuer) | closed |
| T-26-09 | Cryptography | `NOTIFICATION_UNSUBSCRIBE_SECRET` (HMAC over an empty key) | mitigate | Wired from `notification-credentials/unsubscribe-signing-secret` with `optional: true` (`core-java-deployment.yaml:376-381`) → inert-by-default, not weak-by-default. **Verified NOT allowlisted away:** `ALLOW_UNSUPPLIED_LOCAL_DEFAULT` in `check-env-contract.sh:134-138` contains only `OLLAMA_URL`, `ZIPKIN_ENDPOINT`, `CUSTOMER_KC_ISSUER_URI`. Bootstrap skips the Secret with a stated reason when the `.env` value is empty (`k8s-local-secrets.sh:291-296`) | closed |
| T-26-10 | Denial of Service | new `secretKeyRef` entries | mitigate | `grep -c "optional: true"` = **7** in `core-java-deployment.yaml`, and 7 is the exact count of new credential refs (S3×2, SMTP×2, notification×1, Stripe×2). No new non-optional ref was added, so a namespace without the Secret keeps today's behaviour instead of `CreateContainerConfigError` | closed |
| T-26-11 | Information Disclosure | placeholder material in the template | mitigate | `.gitleaks.toml:14-15` path-allowlists `secrets-template.yaml(.example)`. Template holds only `REPLACE_WITH_*` placeholders plus non-secret hostnames/ports/role **names**. `REPLACE_WITH` in the renders = 0 (base/local), 28 (staging/production) and **all 28 are the single exempted `REPLACE_WITH_DEPLOYMENT_TIMESTAMP`** annotation; `check-no-plaintext-secrets.sh:78` scopes the exemption to that literal only, not a blanket skip | closed |
| T-26-12 | Spoofing | STOMP / RabbitMQ credential env names | mitigate | `check-env-contract.sh` direction (a): 49 injected names, 48 read, 1 reasoned exemption, **0 violations**; parse-blindness guard (`:178`) fails the gate at exit 2 if extraction returns 0 names. This is the exact shape that let `RABBITMQ_USERNAME`/`STOMP_CLIENT_*` reach nothing | closed |
| T-26-13 | Information Disclosure | unsupplied local-only defaults | mitigate | Direction (b): 117 placeholders, 66 pass-by-rule, 3 reasoned exemptions, **0 local-only violations**; `LOCAL_ONLY_WORDS` (`:141-149`) covers `localhost`, `127.0.0.1`, `0.0.0.0`, `minioadmin`, `guest`, `mailhog`, `host.docker.internal`, matched **per default** (set semantics, `:254-269`) so a multi-profile default cannot hide. **FALSIFIED** (see T-26-02). ⚠ **Caveat, registered as `UF-26-01`:** the `OLLAMA_URL` exemption's stated reason is factually wrong — the reachable flag is `ai.enabled` (`application.yml:323`, default **`true`**), not `jtoye.media.vision.enabled`. The control mechanism holds; one entry's *reason* does not | closed |
| T-26-14 | Elevation of Privilege | DEF-2 recurrence via a doc edit | mitigate | INV-5, three sites, all three independently **FALSIFIED** red (see T-26-06). The invariant reads the recipe *and* the template, block-scoped to `postgres-credentials` so the legitimate RabbitMQ user `jtoye` does not false-positive | closed |
| T-26-15 | Denial of Service | kube-dns selector regression | mitigate | INV-3 asserts on the **render**, per target, closing the blind spot in `validate-networkpolicies.py` (raw-file parser, not CI-wired). **FALSIFIED** — see T-26-04 | closed |
| T-26-16 | Tampering | allowlist rot | mitigate | `check-env-contract.sh:277-326` fails on a malformed entry, a non-uppercase name, a **blank/whitespace-only reason**, a **duplicate**, and four distinct **STALE** conditions (no longer injected / now read / now supplied / default no longer local-only). Hygiene errors are a hard `VIOLATION=1` (`:408-413`) | closed |
| T-26-17 | Tampering | the gates themselves | accept | A contributor with write access can delete a CI step. Every gate header names the DEF it pins (`check-env-contract.sh:4-23`, `check-render-invariants.sh:134-189`), so removal is visibly costly. Logged AR-26-02 | closed |
| T-26-18 | Tampering / Elev. of Privilege | `configuration-snippet` admission rejection | mitigate | Local render has **0** `configuration-snippet`; staging + production each keep **1**. LOC-4 green (`2 Ingress doc(s): no snippet, no cert-manager, no rate limits, no tls`). No script sets `allow-snippet-annotations` or `annotations-risk-level` — the only occurrences in `scripts/` are the *forbid* instruction at `k8s-local-up.sh:271`. ⚠ Mechanism correction: the plan claimed "LOC-4 asserts production keeps the snippet"; LOC-4 only inspects `k8s/local`. The production half is asserted by `render-golden.sh` — **FALSIFIED:** deleting the snippet from `k8s/base` leaves the invariants gate exit 0 but drives `render-golden.sh` to exit 1 with all six `more_set_headers` lines in the diff. Control present, different name | closed |
| T-26-19 | Information Disclosure | local TLS removal + missing security headers | accept | Degraded locally by design (`tls: null`, `ssl-redirect: false`) — no cert-manager. `LOCAL.md` §6 states plainly, in a section the runbook opens with, that local proves **nothing** about TLS termination, issuance, renewal or **HSTS**, and names the six headers (`X-Frame-Options`, `X-Content-Type-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Permissions-Policy`, `Strict-Transport-Security`) it does not exercise. Disclosure judged **adequate**. Logged AR-26-03 | closed |
| T-26-20 | Information Disclosure | `secretGenerator` in the new overlay | mitigate | Asserted twice. **FALSIFIED:** adding a `secretGenerator` to `k8s/local/kustomization.yaml` → LOC-6 exit 1 (source level, `check-render-invariants.sh:1019`) **and** `check-no-plaintext-secrets.sh` exit 1 (build-output level, auto-discovered at `maxdepth 2`) | closed |
| T-26-21 | Spoofing | local split-horizon issuer values | mitigate | Local render: `keycloak.public.issuer.uri = http://localhost:8085/realms/jtoye-dev` (the issuer Keycloak **stamps**) vs `keycloak.issuer.uri = http://host.minikube.internal:8085/realms/jtoye-dev` (pod-reachable JWKS). The two genuinely **differ**, so the local run proves something about DEF-5. LOC-1 asserts all 8 shimmed keys **by name** (`:827-836`) so a lost shim cannot hide behind an added one; `keycloak.public.issuer.uri` is correctly excluded with a stated reason | closed |
| T-26-22 | Denial of Service | NetworkPolicy egress under an enforcing CNI | accept | Inert on minikube's default CNI. `LOCAL.md` §6 records the **concrete** truth a Calico follow-up needs: `0.0.0.0/0` with `10/8`, `172.16/12`, `192.168/16` in `except[]`; the host gateway sits inside `192.168/16`; the only in-cluster allow targets the non-existent `jtoye-infrastructure`; and the seven ports needed (5433/8085/6379/5672/61613/9000/1025). Disclosure judged **adequate**. Logged AR-26-04 | closed |
| T-26-23 | Tampering | wrong-cluster apply | transfer | Transferred to plan 26-05's guard, and the transferee is **verified present and live**, not merely named — see T-26-24. Transfer target: `k8s_local_assert_context` in `scripts/lib/k8s-local-guards.sh:197-254` | closed |
| T-26-24 | Tampering | applying manifests to the employer AKS cluster | mitigate | **All four refusal arms fired on this host, read-only, zero mutation:** `context-absent` (default, profile Stopped); `wrong-name` (caller asks `sipbihs2aks`); `server-host-mismatch` (`.env` repointed at `sipbihs2aks` → refused because its server `sipbihs2aks-dns-….azmk8s.io` ≠ node IP `192.168.49.2`); `unresolvable-profile-ip` (fails **closed**). Unset contract → exit 2 (VOID). `kubectl config use-context` appears **nowhere** but in comments; the only two bare `kubectl` calls (`k8s-local-up.sh:299,362`) are `kubectl kustomize` — local render, no cluster contact | closed |
| T-26-25 | Elevation of Privilege | the `BYPASSRLS` `jtoye_backup` role | mitigate | Scope minimal and defined in exactly one place: `create-backup-role.sql` grants `LOGIN BYPASSRLS` only — **not** `SUPERUSER`/`CREATEDB`/`CREATEROLE` — plus `CONNECT`, `USAGE`, and `SELECT` on tables + sequences with `SELECT`-only default privileges. **No write, no DDL.** `grep -cE "CREATE ROLE\|ALTER ROLE" scripts/k8s-local-secrets.sh` = **0** (invokes, never reimplements); the role name is parsed *out of* the SQL (`:127`) so it cannot drift. Verification step present (`:183-189`) so a silently-failed bootstrap cannot masquerade as success. **Not used by any application path:** `backup-username`/`backup-password` are consumed only by `k8s/base/pg-backup-cronjob.yaml:72,77` — no Deployment references them. Idempotent (`IF NOT EXISTS` + `ALTER`), guarded (all three guards at `:70-72` precede the first mutation at `:160`) | closed |
| T-26-26 | Information Disclosure | database dumps in the backup bucket | mitigate | Source level: `grep -cE "anonymous set\|policy set\|mc anonymous" scripts/k8s-local-secrets.sh` = **0** — the bucket is created with `mc mb --ignore-existing` and verified with `mc ls`, no public-read policy, unlike `jtoye-images`. Live half (26-07) recorded existence-first then 403: `docker exec jtoye-minio ls /data \| grep -c '^jtoye-db-backups$'` = 1, **then** `curl` → **403** against `jtoye-images` → **200** as control (`LOCAL.md:880-899`) | closed |
| T-26-27 | Information Disclosure | secret values in script output / shell history | mitigate | Every `echo`/`printf` in `k8s-local-secrets.sh` emits key **NAMES** only (`:315-329` enumerates keys per Secret, never values); credential values appear solely inside `--from-literal=` args and the `-v backup_password=` psql variable. `create-backup-role.sql:32` receives it as the properly-quoted psql variable `:'backup_password'` — not string interpolation | closed |
| T-26-28 | Information Disclosure | secret values in process `argv` | accept | `kubectl create secret --from-literal` places values in `argv`, briefly visible to a same-host `ps`. Accepted: D-01's mandated pattern, already documented in `k8s/QUICK_START.md`, single-user development host. Revisit with sealed-secrets/external-secrets (PROJECT.md:141). Logged AR-26-05 | closed |
| T-26-29 | Tampering / Denial of Service | two writers on the shared dev Postgres | mitigate | **CLOSED in round 2 by `b7fcfc7`.** The APP arm now matches the BACKING arm's direction: it asks "is this service **provably stopped**?" against the `K8S_LOCAL_APP_STOPPED_STATES="exited created"` **allow-list** (`k8s-local-guards.sh:95`), so any other state — including one that does not exist yet — counts as a writer and refuses. Implemented in `awk` over a **here-string** (`:364-417`), so the SIGPIPE class cannot return through this loop; the observed state is reported so the refusal says why. **RE-FALSIFIED with this auditor's own 18-fixture table (round 2):** all 12 hazard fixtures now `rc=1` — `running`, `restarting`, `paused`, **`dead`**, **`removing`**, `Running`, `running (healthy)`, an invented future state `quiescing`, a line with no state field, an empty state field, a non-core app (`mcp-server paused`), and a mixed pair; all 4 safe fixtures still `rc=0` (`exited`, `created`, all-four-stopped, apps absent); empty inventory still `rc=2` VOID; the negated BACKING arm unchanged at `rc=1`. **Fault injection:** a broken `awk` (exit 3, no output) against an otherwise **all-safe** inventory refuses `rc=1` naming `core-java(STATE-UNPARSEABLE) frontend(…) edge-go(…) mcp-server(…)` — the tooling-fault path fails closed, which is the exact direction that was wrong before. Live end-to-end against the real running stack: refuses naming `core-java(running) frontend(running) edge-go(running) mcp-server(running)` | closed |
| T-26-30 | Elevation of Privilege | the `/etc/hosts` step | mitigate | The single `sudo` occurrence in all three local scripts is inside an `echo` that **prints** the command for the operator (`k8s-local-up.sh:329`). No executed `sudo`, no privilege escalation attempted | closed |
| T-26-31 | Spoofing | a stale image producing a false-green rehearsal | mitigate | `k8s-local-up.sh` STEP 7 rebuilds all four with manifest-matching tags (`:408-420`), loads via `minikube image load` (`:425`), and prints identities via `image_identity()` (`:428`). `--skip-build` **refuses** when a `:local` tag is absent (`:400-405`) with the false-green rationale stated. 13 image-identity references recorded in `LOCAL.md`; `LOCAL.md` §7 PIT-4b additionally records that host and in-cluster image IDs legitimately differ and which side each digest came from | closed |
| T-26-32 | Tampering | `scripts/deploy.sh` raw base applies | mitigate | `deploy.sh:91` is now a single `kubectl apply -k "$PROJECT_ROOT/k8s/${ENVIRONMENT}"`; `rollout status` + auto `rollout undo` preserved (`:98-101`). The target guard (`:41-60`) rejects `dev`, `local` and anything else and sits **above every** `kubectl` call (first at `:66`), so a rejected target performs no cluster action | closed |
| T-26-33 | Repudiation | `k8s/PRODUCTION_READINESS_REPORT.md` | mitigate | `git diff <base>..HEAD` → **116 insertions, 0 deletions**. The dated signed audit was appended to, never rewritten | closed |
| T-26-34 | Information Disclosure | runbook content | mitigate | Independent sweep: every high-entropy `.env` credential — `DB_BACKUP_PASSWORD`(64), `MINIO_ROOT_PASSWORD`(64), `MINIO_ROOT_USER`(32), `KEYCLOAK_ADMIN_PASSWORD`(64), `KEYCLOAK_CLIENT_SECRET`(64), `NEXTAUTH_SECRET`(44), `EDGE_API_CLIENT_SECRET`(64), `NOTIFICATION_UNSUBSCRIBE_SECRET`(64), `RABBITMQ_PASSWORD`(15), `KC_SEED_USER_PASSWORD`(13), `REDIS_PASSWORD`(12) — appears **verbatim in zero** of the 44 tracked non-planning files the phase changed, including `k8s/LOCAL.md`. Credential-shape + base64 sweep of `LOCAL.md`: **0** | closed |
| T-26-35 | Spoofing | a green local run read as a production guarantee | mitigate | `LOCAL.md` §6 "What local does NOT prove" is present with the concrete facts the threat demanded: ingress-nginx **v1.12.2** on minikube v1.36.0, `allow-snippet-annotations` default `false`, `annotations-risk-level` default `High`, the three RFC1918 `except[]` CIDRs, the seven required egress ports, and an explicit "do NOT fix this by enabling snippet annotations" block | closed |
| T-26-36 | Repudiation | rehearsal evidence without code identity | mitigate | Four image identities recorded in the §11 run header; 13 tag references across the runbook. The loopback rule is present at `LOCAL.md:841`. ⚠ The naive `grep -c 'localhost:9090' == 0` criterion is **unsatisfiable** (the rule must name the string it forbids) and the phase says so at `:1696`, replacing it with a fence-scoped count over §11's 278 captured-output lines = **0**. Independently confirmed: of 6 `localhost:9090` hits, 2 are §10 post-teardown **compose-restoration** proof (correct — compose is canonical again), 1 is the rule, 1 is a `0×` negative assertion, 1 is a realm `redirectUris` value, 1 is the disclosure itself. None is rehearsal evidence | closed |
| T-26-37 | Tampering | hand-edited `docs/metrics.json` | mitigate | `scripts/docs-freshness.sh` check mode exit **0** (total 1698). Diff across the phase is **exactly 3 count fields** (`java_test_methods` 1143→1151, `java_test_files` 201→202, `total_logical_invocations` 1690→1698); a single commit touched the file | closed |
| T-26-38 | Tampering | applying to the wrong cluster (live) | mitigate | Guard ordering verified in source order: STEP 2 `k8s_local_assert_compose_xor` (`:125`) **before** `minikube start` (`:155`); `k8s_local_assert_context` (`:163`); STEP 3b `k8s_local_assert_cluster_xor` (`:192`) **before** the addon (`:206`), the image loads (`:425`), the bootstrap (`:439`) and both applies (`:453` dry-run, `:462` real). Every cluster call goes through `k8s_local_kubectl`, which always passes `--context`. See T-26-24 for the four proven arms | closed |
| T-26-39 | Elevation of Privilege | a green boot achieved with a superuser role | mitigate | Doubled proof recorded: app-side `LOCAL.md:1097-1111` — `grep -c "is NOT a superuser"` = 1, `DATABASE SECURITY VALIDATION PASSED` = 1, `… FAILED` = 0, `Database username: jtoye_app` verbatim; DB-side `SELECT rolsuper, rolbypassrls … 'jtoye_app'` → `f\|f`; plus `pg_stat_activity` attribution 16 → **0** → 5 (the zero is the control) | closed |
| T-26-40 | Tampering / Elev. of Privilege | weakening the cluster to make the apply pass | mitigate | No script sets `allow-snippet-annotations` or `annotations-risk-level` anywhere; the webhook-timeout death message explicitly forbids it (`k8s-local-up.sh:271`). PIT-1 is handled by the local overlay nulling the annotation (LOC-4 green, base retains it) | closed |
| T-26-41 | Information Disclosure | full-database dumps in MinIO | mitigate | Ordering is load-bearing and was honoured. Bucket level (`LOCAL.md:880-899`): existence asserted first (`ls /data \| grep -c '^jtoye-db-backups$'` = 1), **then** 403, contrasted with `jtoye-images` → 200. Object level (`:1203-1211`): the key was confirmed in the listing **before** the 403 was interpreted, with the "MinIO returns 403 for a nonexistent key too" reasoning recorded in place | closed |
| T-26-42 | Denial of Service (recovery) | a plausible-looking but empty dump | mitigate | Both arms recorded (`LOCAL.md` L4, `:1225-1268`): **arm A = products 0** (dump as the app role — and it still clears `MIN_BACKUP_BYTES` by 149× at 149268 bytes and passes `pg_restore --list` with 393 TOC entries, i.e. **both** of the pipeline's own content checks pass on a zero-row artifact); **arm B = products 47** matching the live DB. Upstream half `rolbypassrls\|rolsuper` = `t\|f` (`:876`). Both scratch DBs dropped | closed |
| T-26-43 | Repudiation | an anecdotal or stale-image pass | mitigate | See T-26-31 + T-26-36. Rebuild rather than reuse of the pre-Phase-23 `:2.1.0` images; identities recorded beside results; loopback rule enforced fence-scoped at 0 | closed |
| T-26-44 | Information Disclosure | secrets surfaced in captured evidence | mitigate | Zero credential-shape or base64 occurrences in `LOCAL.md` (independent sweep, 13 keys). Only non-secret decoded values are quoted — the `port` (5433) and the role **name** `jtoye_app`. ⚠ The declared literal-value grep is **vacuous for one key**: this developer's `.env` sets `DB_PASSWORD`/`POSTGRES_PASSWORD` to a 6-letter common English word, so that grep returns 32 hits inside §11 alone from ordinary prose (`kubectl create secret`, `secretKeyRef`, `secret cache`). The phase **found this itself**, disclosed it (26-09-SUMMARY §"could not pass on a clean document"), and replaced it with a credential-shape + base64 form that returns 0 on the real file and **fires at 2** against injected leaks. Stronger than declared | closed |
| T-26-45 | Denial of Service | two writers on the shared dev Postgres (live) | mitigate | The distinct control this threat names — 26-07 Task 1's blocking `checkpoint:human-action` requiring the human to confirm the app containers are down before anything is applied — is present and was exercised (`26-07-SUMMARY` human gate APPROVED); the proceed arm is recorded against real state. ~~⚠ The guard half is degraded by T-26-29~~ **Round 2: the guard half is now sound too** (`b7fcfc7`), so closure no longer rests on the human checkpoint alone. Both layers verified | closed |
| T-26-46 | Spoofing | the added `redirectUris` entry | mitigate | `git diff <base>..HEAD -- infra/keycloak/realm-export.template.json` is **one line**: `http://app.jtoye.local/*` appended to the `core-api` client. Host-scoped, path-wildcard only — **no wildcard host, no `http://*`, no protocol-relative form** — matching the four existing `http://localhost:PORT/*` entries, none of which was removed. **Not an open redirect.** Dev realm (`jtoye-dev`) only; consumed by `docker-compose.full-stack.yml`, referenced by **no** `k8s/` manifest. (`webOrigins: ["*"]` on that client is pre-existing and untouched by this phase — noted as an observation, not a Phase-26 finding) | closed |
| T-26-47 | Spoofing | split-horizon issuer misconfiguration | mitigate | The two recorded values genuinely **differ** — see T-26-21. Live: `LOCAL.md` L7 records the authorize redirect going to the **public** issuer with `client_id=core-api`, landing on `http://app.jtoye.local/dashboard`, 10/10 API calls to `api.jtoye.local`, **0** loopback requests. The expected-issuer validator is never widened (T-26-07) | closed |
| T-26-48 | Elevation of Privilege | the `keycloak.client-id` config injection | mitigate | `keycloak.client-id` renders as **`frontend`** — byte-identical to the literal it replaced — in base, staging **and production**; only `k8s/local` patches it (to `core-api`, the client the dev realm actually has). No environment silently changes which OIDC client it authenticates as | closed |
| T-26-49 | Information Disclosure | STOMP passcode in captured evidence | mitigate | Only the login **name** appears. Credential-shape + base64 sweep of `LOCAL.md` for `RABBITMQ_PASSWORD` (15 chars) and the `STOMP_CLIENT_PASSCODE` fallback source: **0 hits** | closed |
| T-26-50 | Information Disclosure | vendor credentials in captured evidence | mitigate | `KC_SEED_USER_PASSWORD` (13 chars) appears **verbatim in zero** tracked files, `LOCAL.md` included. Referenced by name only | closed |
| T-26-51 | Spoofing | a stub-cookie relay spec reported as a pass | mitigate | The spec was **not** accepted as the proof. Its four structural mismatches and two silent skip conditions are recorded in `deferred-items.md` with the file:line for each, and `LOCAL.md` §11 records them so a future green run of it is not mistaken for the proof. The real proof was broker-side identity (`auth_login=jtoye`, `guest`=0, with an MQTT non-vacuity control at 0 **and** a fixture proving the guest predicate can fire at 1) plus a real-login browser journey | closed |
| T-26-52 | Repudiation | an XOR-bypassed browser session | mitigate | See T-26-36 — fence-scoped loopback count over §11 captured output = **0**, with all 6 whole-file hits individually classified and none in rehearsal evidence | closed |
| T-26-53 | Repudiation | requirement closure without a proof | mitigate | `.planning/REQUIREMENTS.md:84-88` carries **all four** INFRA-02 sub-item citations, each with its own assertion and recorded result (a/b/c/d), not a plan number. (d) is explicitly scoped to credential wiring with the falsified functional row named in the same bullet | closed |
| T-26-54 | Tampering | a suite-wide regression shipped green | mitigate | Artifacts on disk: **104** unit-test result XMLs + **98** integrationTest XMLs, and `grep -l 'failures="[1-9]\|errors="[1-9]'` across both directories = **0 files**. This is the exact suite the `trap_scope_gate_integrationtest_regression` memory warns is easy to under-run; the closer ran the whole thing, and the artifacts corroborate it | closed |
| T-26-55 | Information Disclosure | secret values pasted into the tracked runbook | mitigate | Independently re-verified: 0 credential-shape and 0 base64 occurrences across 13 keys. The substitution of the unsatisfiable literal-value form is **disclosed, not silent** (26-09-SUMMARY decision list, and again in `LOCAL.md`) and the replacement was itself falsified (fires at 2 on injected leaks in a scratchpad copy, `git diff --numstat` 0 at that moment). See the T-26-44 caveat | closed |
| T-26-56 | Repudiation | a silently-substituted verification | mitigate | `26-VALIDATION.md:81-93` carries a named **"Substituted verification — recorded, not silently ticked (threat T-26-56)"** note explaining that `stomp-relay.spec.ts` did not cover INFRA-02d and what replaced it, with the row marked **❌ RED — FALSIFIED, and PROVEN SUBSTITUTED** rather than ticked | closed |
| T-26-57 | Repudiation | rewriting a dated record | mitigate | `docs/metrics.json` has one writing commit and a 3-count-field diff (T-26-37); `PRODUCTION_READINESS_REPORT.md` has 0 removed lines (T-26-33) | closed |
| T-26-58 | Elevation of Privilege | creating the `BYPASSRLS` role on the shared dev Postgres | mitigate | Gated behind 26-07 Task 1's blocking `checkpoint:human-action`, itemised as (c) with its `DROP ROLE` reversal; never created by an autonomous plan. Scope bounded by `create-backup-role.sql` and **not** reimplemented (T-26-25). Both attributes asserted from the DB side: `rolbypassrls\|rolsuper` = **`t\|f`** (`LOCAL.md:876`), so an over-privileged variant is caught | closed |
| T-26-59 | Denial of Service | stopping a shared dev stack a second session may be using | mitigate | `grep -rnE "docker compose .*(stop\|down\|kill\|rm)\|docker (stop\|kill\|rm) "` across all three local scripts → the only match is the read-only `docker compose … ps --all` inside `k8s_local_compose_state`. **No script stops a container.** Both guards state in-file that bringing containers down is the human's decision. The stop happened once, in 26-07, itemised as (a) with its `docker compose start` reversal | closed |
| T-26-60 | Tampering / DoS | plan 26-05 mutating a shared dev stack unattended | mitigate | Structurally verified: in `k8s-local-secrets.sh` all three guards run at `:70-72`, the value preflight + weak-value refusal at `:78-111`, and the **first** mutating call is at `:160` — with helper bodies deliberately defined *after* the guards (`:134-136`) so the "guards precede every mutation" assertion stays falsifiable by line number. The whole-script invocation was relocated to 26-07 behind its checkpoint (T-26-65) | closed |
| T-26-61 | Spoofing / DoS | the deployed `rabbitmq-credentials/username` after the rename | mitigate (out-of-band) | All three declared out-of-band legs present: the blocking **`### PRE-ROLLOUT OPERATOR CHECK ###`** block at the rename site (`core-java-deployment.yaml:173-195`) with both namespace commands, the expected value, and "change the secret, never revert the rename"; the recorded outcome **`UNAVAILABLE-FROM-THIS-HOST`** with the actual `kubectl config get-contexts` evidence (`26-01-SUMMARY:176,388,413`); and the operator-facing carry into `PRODUCTION_READINESS_REPORT.md:569` as **"1. BLOCKING — confirm `rabbitmq-credentials`/`username` before rolling out"**. Correctly recorded as unverifiable from this repository rather than assumed equal | closed |
| T-26-62 | DoS / Info Disclosure | new SMTP + media-storage base values naming possibly-unprovisioned resources | mitigate | Three-way inert-by-default confirmed on the render: `smtp.auth` = **`"false"`** in all four (byte-identical to the `application.yml` default, so no credential is offered to any relay); all 7 new credential refs `optional: true` (T-26-10); credentials live only in Secrets an operator must create. Named per-value outcomes recorded with provenance and a **`UNVERIFIABLE-FROM-THIS-HOST`** verdict + operator pre-activation action in `deferred-items.md:109-145`, plus a `NOT-PROVISIONED` entry for the Stripe Connect return route | closed |
| T-26-63 | DoS / Info Disclosure | the dangling `auth.jtoye.co.uk` → `keycloak` rule and its TLS SAN | mitigate | **Both halves removed in `k8s/base`**, not papered over locally: `ingress.yaml` `spec.tls.hosts` = `[api, app]` and `spec.rules` = `[api, app]`, with an in-file comment block recording the reasoning and the displaced in-cluster-Keycloak intent (also in `deferred-items.md:188-229`). Goldens still match at 1469 lines, so the change was reviewed, not silent drift. **FALSIFIED:** re-adding the rule → `INV-6 … routes it to Service 'keycloak', which does NOT exist in the k8s/base render`, exit 1 on base **and** staging **and** production (local correctly stays OK — it replaces `rules:`). ⚠ Coverage caveat, registered as `UF-26-06`: INV-6 asserts only the **backend** half. **FALSIFIED:** re-adding `auth.jtoye.co.uk` to `tls.hosts` **without** a rule leaves the invariants gate at exit 0; only `render-golden.sh` catches it (exit 1) — i.e. the half the in-file comment calls the more serious one is guarded by a human-reviewed diff, not an invariant | closed |
| T-26-64 | DoS / Tampering | restoring the shared local runtime at end of phase | mitigate | Performed and recorded (`LOCAL.md:665-700`). **Order honoured, cluster first:** STEP 1 `minikube stop -p jtoye` (23:40:17→23:40:29Z, status `Stopped`, still in the valid list — **not deleted**), STEP 2 `docker compose start core-java frontend edge-go mcp-server`, six backing services untouched. Only reversals (a) and (b) that 26-07's itemised approval already covered. End state independently confirmed at audit time and again in round 2: 10/10 compose services up, profile `Stopped`, one kubeconfig context. ~~⚠ inherits T-26-29~~ **Round 2: the end-state "guard refuses again" proof is now stronger than when recorded** — re-run read-only against the live stack it refuses *and names the observed state per service* | closed |
| T-26-65 | Elevation of Privilege | the first whole-script `k8s-local-secrets.sh` invocation | mitigate | Reached only through `k8s-local-up.sh` STEP 8 (`:439`), which sits after all four guards and after STEP 3b's cluster XOR (`:192`), and is covered by 26-07 Task 1's itemised approval (items c and d) with `DROP ROLE` / `mc rb` reversals. 26-05 retains only the zero-mutation halves. ~~⚠ Registered as `UF-26-04`~~ **Round 2: `UF-26-04` closed by `47e0564`** — the standalone entry point now calls all four guards, so this threat's protection no longer depends on which entry point was used | closed |
| T-26-66 | Repudiation | a false RED from an unbound variable during the human-watched rehearsal | mitigate | `26-07-PLAN.md` carries the `set -a; . ./.env; set +a` idiom **22** times and resolves the namespace through `k8s_local_namespace` **11** times. Sourcing binds names without printing values, so the names-only rule is preserved. `k8s/LOCAL.md` uses literal `<ctx>` / `jtoye-local` placeholders instead of shell variables — the same fault avoided a different way | closed |
| T-26-67 | Repudiation | a false RED (or false DEF-5 failure) from an unrunnable command | mitigate | `26-08-PLAN.md`: env-sourcing idiom **14** times, `k8s_local_namespace` **10** times, and the Playwright invocation is `set -a; . ./.env; set +a; cd frontend && PLAYWRIGHT_BASE_URL=…` (`:313`) with the password passed **by name** (`:129-131`), i.e. both recorded environment-fault shapes are structurally excluded | closed |
| T-26-SC | Tampering (package legitimacy) | npm/pip/cargo installs | n/a | `git diff --name-only <base>..HEAD` matched **no** `package.json`/lock, `go.mod`/`go.sum`, `build.gradle*`, `requirements*.txt` or `Cargo*` — zero dependency-manifest changes. Gate genuinely N/A. ⚠ Registered as `UF-26-03`: the disposition's reason ("the `minio/mc` image is already the mechanism compose uses") does not cover the phase's **new** invocation of the mutable tag `minio/mc:latest` with MinIO **root** credentials. Logged AR-26-06 | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party) · n/a*

---

## Open Threats

**None.** `threats_open: 0`. The single round-1 blocker (T-26-29) is closed and re-verified. The finding
is kept below rather than deleted, because *why it recurred* is the transferable part — and because it
forced two corrections to this auditor's own round-1 work.

---

## Round-1 BLOCKER, now CLOSED — T-26-29

> **RESOLVED 2026-07-26, `b7fcfc7`.** Re-verified independently: 18 fixtures + fault injection + a live
> read-only run (see the T-26-29 register row). The round-1 analysis is preserved verbatim below as the
> record; the resolution, and the two corrections it forced, follow it.

### The finding as written in round 1

**Declared mitigation:** *"The XOR guard refuses while any compose app container runs and refuses if a
backing service is down, converting the doc-line footgun into a guard rail (D-04)."*

**What was in the code** — `scripts/lib/k8s-local-guards.sh:315-325`, pre-`b7fcfc7`:

```bash
for svc in $K8S_LOCAL_APP_SERVICES; do
  if grep -qE "^${svc} running$" <<<"$state"; then      # <-- exact literal only
    running_apps="${running_apps} ${svc}"
  fi
done
for svc in $K8S_LOCAL_BACKING_SERVICES; do
  if ! grep -qE "^${svc} running$" <<<"$state"; then     # <-- negated: fails CLOSED
    down_backing="${down_backing} ${svc}"
  fi
done
```

The two arms have **opposite failure directions**. The BACKING arm demands the literal and so refuses on
any unexpected state — correct. The APP arm — the one that protects the shared dev Postgres from two
writers — treats *anything other than* `running` as "not a writer" and proceeds.

**Probed, not reasoned** (function-level fixture over `k8s_local_compose_state`, the documented
falsification seam; zero mutation, nothing started or stopped):

| fixture app state | guard result | correct? |
|---|---|---|
| `core-java running` | `rc=1 REFUSED [compose-apps-running]` | yes |
| `core-java exited` | `rc=0 OK` | yes |
| **`core-java restarting`** | **`rc=0 OK`** | **no — fail open** |
| **`core-java paused`** | **`rc=0 OK`** | **no — fail open** |
| `core-java Running` (capitalised) | `rc=0 OK` | no — format-drift hole (mitigated: the backing arm would then refuse, so a whole-format change fails closed) |
| `""` (empty) | `rc=2 TOOLING ERROR … VOID, not clean` | yes (the wave-5 fix holds) |

**Why `restarting` is reachable, not hypothetical.** `docker-compose.full-stack.yml` sets
`restart: unless-stopped` on `core-java`, `edge-go`, `frontend` and `mcp-server`, and `LOCAL.md` §7
documents core-java crash-looping at boot on `host.minikube.internal` DNS. A crash-looping app container
therefore legitimately sits in `restarting` — and `unless-stopped` guarantees it comes back within
seconds. A `paused` container keeps its established Postgres backends open and resumes writing on
unpause. `docker compose ps` (without `--all`) itself counts both as up; only this guard's exact-literal
match narrows them out.

**Consequence.** Exactly the condition the guard exists to prevent: `scripts/k8s-local-up.sh` proceeds to
`minikube start` → bootstrap → `apply -k`, and the compose app container comes back, putting two writers
on the shared dev Postgres. Blast radius is the **dev** database and a false-green rehearsal — not
production tenant data — but the phase's own doctrine is that a guard failing open is the defect, and the
file's comment at `:305-314` identifies *this exact asymmetry* for the SIGPIPE case ("a spurious 141
reads as 'this app service is NOT running', so the guard would FAIL OPEN") and then leaves the same
direction in place for every other non-`running` string ten lines below.

**Not a re-report.** This is a **third** instance of the class, distinct from the two fixed this phase
(the `grep -q`-under-`pipefail` SIGPIPE inversion returning 141, and empty `docker compose ps` reading as
clean). Both of those are verified fixed: here-strings are used throughout and the empty case exits 2.

**Suggested closure** (implementation is out of this audit's scope — this file is the only artifact it
may write): invert the APP arm to match the BACKING arm's direction, so any state that is not provably
terminal counts as a writer.

```bash
# writer unless PROVABLY stopped — the same direction the backing arm already uses
if ! grep -qE "^${svc} (exited|created|dead|removing)$" <<<"$state" \
   && grep -qE "^${svc} " <<<"$state"; then
    running_apps="${running_apps} ${svc}"
fi
```

Falsify both arms against the six-state fixture table above before trusting it, and keep the
`restarting`/`paused` rows as permanent probes.

**Dependents.** `T-26-45`, `T-26-59` and `T-26-64` each name this guard as one of their controls. All
three are recorded **closed** because each carries a second, independently verified control that held —
26-07 Task 1's blocking human checkpoint, and the itemised human-approved reversal list — and because the
live proceed/refuse arms were exercised against a genuinely `running` stack. Their closure is stated as
resting on the human checkpoint rather than the guard, so the dependency is visible rather than assumed.
This is recorded as **one** absent control, not four.

### The resolution — `b7fcfc7`, re-verified

The direction is inverted, and the mechanism chosen is stronger than "invert the condition": the safe
states are now a **named `readonly` allow-list** (`k8s-local-guards.sh:95`), so the structure itself makes
an unknown state refuse. `awk` reads a **here-string**, so this loop has no writer to signal and the
SIGPIPE class cannot re-enter through it; and the refusal reports the **observed state per service**, so
an operator is told why rather than merely which service.

Re-verified against the register-row fixture table: **12/12 hazard fixtures refuse, 4/4 safe fixtures
proceed, empty inventory still VOID, backing arm unchanged.** Fault injection (`awk` replaced by
`exit 3`, no output) against an **all-safe** inventory returns `rc=1 … (STATE-UNPARSEABLE)` — the path
that would previously have read as clean now fails closed and names itself. Before/after was measured on
the *same* fixtures against `b7fcfc7^` and `b7fcfc7`, not inferred from the commit message.

**Dependents re-resolved.** T-26-45, T-26-59 and T-26-64 no longer rest on the human checkpoint alone;
both layers are now sound. T-26-64's end-state proof is strictly better than when it was recorded — the
guard now refuses *and names the state*.

---

<a id="round-2-corrections-to-this-auditors-own-findings"></a>
## Round-2 corrections to this auditor's own findings

Two, both material, both surfaced by the fixer and then **independently confirmed here** against
`b7fcfc7^` with the same harness. Recorded rather than quietly reconciled, because a security audit that
edits away its own errors is worth less than one that carries them.

### Correction 1 — round 1 UNDERSTATED the fail-open surface: 5 real states, not 2 (+2 classes)

The round-1 table above tested `restarting`, `paused`, `Running` and the empty case. It **never tested
`dead` or `removing`.** Re-run against the pre-fix guard (`b7fcfc7^`) with the identical seam:

| pre-fix app state | pre-fix result | in round-1 table? |
|---|---|---|
| `running` | `rc=1` refused | yes |
| `restarting` | **`rc=0` FAIL OPEN** | yes |
| `paused` | **`rc=0` FAIL OPEN** | yes |
| `dead` | **`rc=0` FAIL OPEN** | **no — missed** |
| `removing` | **`rc=0` FAIL OPEN** | **no — missed** |
| `Running` (capitalised) | **`rc=0` FAIL OPEN** | yes (called format-drift) |
| `quiescing` (invented) | **`rc=0` FAIL OPEN** | as a class, untested |
| no state field | **`rc=0` FAIL OPEN** | **no — missed** |

So the surface was **5 real docker states** (`restarting`, `paused`, `dead`, `removing`, `Running`) plus
the malformed-line case plus every future state string — not the 2-plus-a-class the round-1 table
implied. The coordinator's count of 5 is correct and this report's was low.

Round 1 also under-rated the `Running` row as "mitigated: the backing arm would then refuse". That holds
only for a **whole-format** change affecting all ten services. A **single** app service reporting a
differently-cased state — which is what the fixture actually modelled — leaves the backing arm green and
the app arm open. The mitigation note was too generous.

### Correction 2 — round 1's suggested closure was WRONG, and would have left two of the holes open

Round 1 proposed `^${svc} (exited|created|dead|removing)$` as the safe set. That treats `dead` and
`removing` as stopped. The fixer rejected it and shipped the strictly narrower `exited created`.

**Assessment: the fixer is right, and the reasoning is sound on all three grounds.**

1. **`dead` is by definition the state where Docker's own bookkeeping failed.** It means the daemon could
   not complete teardown — typically a mount/driver failure. The container's status is *indeterminate*,
   which is the precise opposite of the guard's contract word, *provably*. Reading the state that means
   "Docker does not know" as "Docker knows it is stopped" inverts the evidence.
2. **`removing` is a transition, and one that can end in `dead`.** A guard sampling it is guessing about
   the future, and the failure branch of that guess lands in the state ground 1 already rejects.
3. **The cost asymmetry decides it.** False refusal costs one human decision ("resolve the dead
   container, re-run"). False permit costs two writers on one shared dev Postgres plus a false-green
   rehearsal — the exact hazard measured at 16 live connections on 2026-07-25. With asymmetric costs the
   allow-list must be minimal.

There is a fourth ground the fixer did not state and which this auditor considers the strongest:
**adding `dead`/`removing` to an allow-list forfeits the property that makes an allow-list worth having.**
The whole value is "unknown ⇒ refuse". Both states are rare *and* indeterminate, so admitting them buys
essentially zero operator convenience while re-opening the default-permit direction for the two states
nobody can characterise. `exited created` is the correct set; if a state is ever added, the burden is to
prove a container in it cannot hold a Postgres connection.

**The compounding error, stated plainly:** round 1 proposed allowing precisely the two states it had
never measured. Had that snippet been applied as written, `dead` and `removing` would have remained
fail-open and this file would have recorded T-26-29 as closed. The audit would have been the source of
the residual hole. `K8S_LOCAL_APP_STOPPED_STATES="exited created"` is the correct fix and this report's
suggestion is superseded — the code comment at `:79-92` documents the rejection, which is the right place
for it.

---

## Unregistered Flags (WARNING — new attack surface with no threat mapping)

Non-blocking. Each is real surface that appeared or changed during implementation and that no
`<threat_model>` entry covers, or covers on a reason that does not hold.

| Flag | Surface | Why it is unmapped | Evidence |
|------|---------|--------------------|----------|
| UF-26-01 | `ai.enabled` defaults **`true`** while `OLLAMA_URL` is an allowlisted omission | T-26-13's exemption reason names `jtoye.media.vision.enabled` (default false) and accounts for one of three consumers. `ImageAnalysisService` is injected into `ProductController` and `BulkImportService` too and never checks reachability, so in staging/production `POST /api/v1/products/{id}/image/analyze` skips the clean 503 guard and dials the pod's **own loopback** :11434, burning the resilience4j `ai` retry budget and tripping the circuit breaker. Not an SSRF (the URL is fixed config), but a reviewed-inventory entry recorded on a false premise | `application.yml:323-328`; `check-env-contract.sh:135`; review WR-03 |
| UF-26-02 | `DB_PORT` is now Secret-driven but the NetworkPolicy egress port is a hardcoded `5432` | Coupling **created by this phase's own T-26-01 change**. Under the enforcing CNI staging/production ship policies for, exercising the advertised capability (move Postgres to 5433 via one Secret edit) denies every core-java DB connection; Hikari times out, `DatabaseConfigurationValidator` never runs, all replicas CrashLoop, `pg-backup` fails identically. Invisible locally (default CNI does not enforce). No threat covers the new coupling | `networkpolicies/20-core-java.yaml:79`, `40-datastores.yaml:57`; review WR-02 |
| UF-26-03 | `scripts/k8s-local-secrets.sh:217-234` runs an **unpinned** `minio/mc:latest`, joined to the compose network, with MinIO **root** credentials in its environment | T-26-SC dispositioned package legitimacy `n/a` because "no packages are installed" and "`minio/mc` is already the mechanism compose uses". That reasoning does not reach the new combination: a mutable Docker Hub tag resolved at run time, handed root credentials for the MinIO holding both the media bucket and the database dumps, on a network that also reaches Postgres/Redis/RabbitMQ/Keycloak. `MINIO_MC_IMAGE_TAG` is absent from `.env.example`, so every run is `latest`, and nothing records which image ran. The repo already treats mutable tags as an audit finding elsewhere (`ci-cd.yaml:472-476`, `k8s/local/kustomization.yaml:130-132`) | `k8s-local-secrets.sh:221`; review WR-08 |
| ~~UF-26-04~~ **CLOSED** | ~~`k8s_local_assert_cluster_xor` reachable through only one of two entry points~~ | **Closed in round 2 by `47e0564`** and re-verified independently. `scripts/k8s-local-secrets.sh:98` now calls `k8s_local_assert_cluster_xor` as its **fourth** STEP 1 guard, placed last because `assert_context` must first establish *which* cluster is being inventoried (the only other context on this host is employer infrastructure). **Both** entry points now call all four guards — verified by enumerating every file that sources the library. **Ordering re-verified by line number**, which is the property this audit checks: last guard `:98` < first mutating call `:172`/`:186`, so a refusal is provably a no-op. **Before/after probed over the documented `k8s_local_cluster_pod_inventory` seam** with a stale-writer fixture (`jtoye-staging`, 2 live pods on `:2.1.0`), earlier guards stubbed, and `docker`/`kubectl`/`k8s_local_kubectl` replaced by loud aborts so mutation was impossible: **pre-fix** (`47e0564^`) execution passed all three guards and reached STEP 1b — `cluster-writers-present` fired **0** times, i.e. the bypass was real; **post-fix** it refuses at guard 4 naming the namespace, live pod count, phase and **image tags**, `cluster-writers-present` fires **1**, and no mutation was attempted in either run. `jtoye-local`'s own running pod correctly **not** flagged, so D-14 re-runnability is intact | `k8s-local-secrets.sh:69-98`; `k8s-local-guards.sh:424-521`; review IN-03 |
| UF-26-05 | The local overlay ships `pg-backup` **unsuspended**, and the local namespace is XOR-exempt by design | `scale-patch.yaml` covers HPAs and PDBs; nothing sets `spec.suspend`. A `jtoye-local` namespace therefore runs a nightly 02:00 UTC **full dump of the shared dev Postgres** into host MinIO for as long as the profile exists — and `k8s_local_assert_cluster_xor` exempts that namespace, so a forgotten rehearsal namespace is invisible to the very guard added because a restored namespace's `pg-backup` CronJob fired on start. Information-disclosure-at-rest surface with no threat mapping | `k8s/local/kustomization.yaml:147-151`; review IN-04 |
| UF-26-06 | Two Ingresses claim the same `secretName: jtoye-tls` with divergent SAN lists; and `tls.hosts ⊆ rules.host` is asserted by no invariant | T-26-63's TLS reasoning treats `jtoye-ingress`'s SAN list as the single source of truth. `sse-ingress.yaml:47-51` requests `[api]` into the same secret with the same `cluster-issuer`, so cert-manager's ingress-shim has two claimants and produces recurring ownership-conflict events. Separately **falsified here**: re-adding a dangling SAN with no rule leaves `check-render-invariants.sh` at exit 0 — only the human-reviewed golden diff catches it, while the in-file comment calls the certificate half the more serious one | `base/ingress.yaml:62-66`; `base/sse-ingress.yaml:47-51`; `check-render-invariants.sh:76-93`; review IN-02 + WR-07 |
| UF-26-07 | Staging runs the `prod` Spring profile (D-10) but the staging deploy job asserts Swagger is reachable | `k8s/staging` renders `SPRING_PROFILES_ACTIVE: prod` with no override, so `application-prod.yml`'s `${SWAGGER_ENABLED:false}` applies and no manifest supplies `SWAGGER_ENABLED`. The job nevertheless runs `EXPECT_SWAGGER=true ./scripts/smoke-test.sh`, which requires a 302. Enabling `DEPLOY_STAGING_ENABLED` would auto-`rollout undo` every correct release — and the tempting "fix" is to expose Swagger in staging, which is a surface decision no threat covers | `ci-cd.yaml:598-602`; `check-env-contract.sh:123`; review WR-06 |

### Already-tracked, correctly excluded from the flags above

- **Issue #266 (KDS relay `/topic` destination).** Human-decided: record, not fix. No security
  consequence found beyond loss of realtime function — the rejected SUBSCRIBEs never create a queue and
  the reconnect storm degrades to authenticated polling on the tenant's own `/api/v1/orders`. **But the
  flag the brief asked about is agreed and worth carrying forward:** the fix must change
  `TenantChannelInterceptor.java:123`, which enforces tenant isolation by parsing the destination
  (`split("/")`, `parts[3]` as the tenant UUID). Changing the delimiter changes what that parser sees, so
  #266 is a **tenant-isolation-sensitive** change and its cross-tenant subscribe test must be re-run, not
  assumed. That requirement is already written into #266's four-part acceptance test (item 4) and into
  `LOCAL.md` §7 A3.
- **`LOCAL.md` §6 disclosed non-proofs** (no TLS/HSTS, no PIT-1 header snippet locally, no NetworkPolicy
  enforcement). Judged **adequately disclosed** and scored as accepted risks AR-26-03/AR-26-04, not as
  concealed gaps. The section is concrete (controller version, default flag values, the three RFC1918
  CIDRs, the seven ports, the six header names) and carries an explicit instruction not to weaken the
  cluster to make the apply pass.

### Observations (no action required, recorded so their absence is not an omission)

- **`scripts/deploy.sh` has no context assertion.** Unlike the local path's four-arm
  `k8s_local_assert_context`, the staging/production script uses the ambient kubectl context. Acceptable
  in its intended CI setting (KUBECONFIG from a secret) and mitigated on this host by `current-context`
  being unset, but the asymmetry is worth knowing.
- **This developer's `.env` uses a 6-letter dictionary word for `DB_PASSWORD`/`POSTGRES_PASSWORD`.**
  Gitignored and never shipped, so not a phase finding — but it is what makes the declared
  literal-value leak grep vacuous for that key (see T-26-44), and it would defeat the same check in any
  future phase that copies the criterion verbatim.
- **`webOrigins: ["*"]` on the `core-api` dev-realm client** is pre-existing and untouched by this phase.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-26-01 | T-26-05 | Committed golden renders contain `secretKeyRef` **names** and non-secret ConfigMap values only. Verified: zero `kind: Secret` anywhere under `k8s/goldens/` including all 9 `.pre/` snapshots; the directory carries no `kustomization.yaml` so it is not a discoverable overlay; `check-no-plaintext-secrets.sh` exit 0 on all 4 targets. Secret **values** can never appear in a kustomize render by construction. | gsd-security-auditor (verified) | 2026-07-26 |
| AR-26-02 | T-26-17 | The five static gates can be deleted by anyone with repo write access; only review prevents it. Mitigating factor verified: every gate header names the specific defect it pins (DEF-1/2/4/6, D-17, Adjudication B), so a reviewer sees the cost of removal. Branch-protection required-checks state is not verifiable from this repository — noted rather than assumed. | gsd-security-auditor (verified) | 2026-07-26 |
| AR-26-03 | T-26-19 | Local proves nothing about TLS termination, certificate issuance/renewal, HSTS or the six nginx security headers: `tls: null` + `ssl-redirect: false` are required because no cert-manager runs locally, and ingress-nginx v1.12.2 rejects the `configuration-snippet` annotation class by default. ASVS V9 deliberately degraded locally; loopback-resolved `.local` hosts, no real data. Base/staging/production retain both TLS and the snippet (verified on the renders and by golden falsification). Disclosure in `LOCAL.md` §6 judged adequate. | gsd-security-auditor (verified) | 2026-07-26 |
| AR-26-04 | T-26-22 | NetworkPolicy **enforcement** is unproven locally — minikube's default CNI does not enforce them; the 6 policies are validated as manifests only. Under an enforcing CNI the rendered set would deny the entire local traffic pattern. Accepted per D-11 for this phase; the concrete CIDR/port truth a Calico follow-up needs is written out in `LOCAL.md` §6 and `deferred-items.md`, and the D-17 prerequisite is now cleared. | gsd-security-auditor (verified) | 2026-07-26 |
| AR-26-05 | T-26-28 | `kubectl create secret --from-literal` places credential values in `argv`, briefly visible to `ps` on the same host. Accepted: D-01's mandated pattern, already documented in `k8s/QUICK_START.md`, single-user development machine, local-only path. Revisiting belongs with the deferred sealed-secrets / external-secrets adoption (`PROJECT.md:141`), which `deferred-items.md` records. | gsd-security-auditor (verified) | 2026-07-26 |
| AR-26-06 | T-26-SC | Package Legitimacy Gate **N/A** — verified zero dependency-manifest changes vs `main` (`package.json`/lock, `go.mod`/`go.sum`, `build.gradle*`, `requirements*.txt`, `Cargo*`). The four application images are built from committed Dockerfiles. Accepted **with the exception carved out as UF-26-03**: the new bootstrap's `minio/mc:latest` invocation with MinIO root credentials is container-supply-chain surface the `n/a` disposition does not cover. | gsd-security-auditor (verified) | 2026-07-26 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-07-26 (round 1) | 68 | 67 | **1** (T-26-29) | gsd-security-auditor (opus, ASVS L1, verify-mitigations mode) |
| 2026-07-26 (round 2) | 68 | **68** | **0** | gsd-security-auditor (opus, ASVS L1, re-verify T-26-29 + UF-26-04 only) |

**Round-2 scope, stated so it is auditable.** Only T-26-29 and UF-26-04 were re-verified, plus the four
register rows that referenced them as a dependency (T-26-45, T-26-59, T-26-64, T-26-65). The other 66
closed threats were **not** re-audited and their round-1 evidence stands unchanged. UF-26-01, 02, 03, 05,
06 and 07 are unchanged and remain open WARNINGs. Round-2 verification used this auditor's own fixtures
over the two documented seams (`k8s_local_compose_state`, `k8s_local_cluster_pod_inventory`), run against
**both** the pre- and post-fix revisions of each file, plus fault injection (broken `awk`) and a
mutation-impossible harness — not the fixer's transcript. All five static gates and `docs-freshness` exit
0 on the real tree after the fixes; environment confirmed untouched (10/10 compose services up, minikube
`jtoye` still `Stopped`, one kubeconfig context).

**Method note.** 68 = 67 numbered threats (`T-26-01`..`T-26-67`, all present, no gaps) + `T-26-SC`
(shared across all nine plans, counted once). Dispositions as authored: 61 `mitigate`, 5 `accept`
(T-26-05/17/19/22/28), 1 `transfer` (T-26-23), 1 `n/a` (T-26-SC).

Nine controls were **falsified by regression** in an isolated scratch copy rather than accepted on a
grep match: INV-1 (single-quoted `value: '5432'` — the WR-01 evasion is genuinely fixed, the invariant now
reads the render and fails on all four targets), INV-3 (pre-phase label transformer), INV-5 (all three
doc sites independently), INV-6 (dangling backend), LOC-6 + `check-no-plaintext-secrets` (secretGenerator),
`check-env-contract` direction (b) (the `guest` fallback net), `render-golden` (DB_USER→superuser, and the
deleted security-header snippet), and the compose XOR guard's six-state fixture table. Two of those
falsifications produced **negative** results that are recorded as caveats rather than smoothed over:
the TLS-SAN half of INV-6 (UF-26-06) and the production-snippet half of LOC-4 (T-26-18).

Three declared criteria were found to be **unsatisfiable as written** and were replaced during execution
with falsifiable forms; all three substitutions are disclosed in the phase's own record, and this audit
independently re-ran the replacements: the literal secret-value grep (T-26-44/55), the
`grep -c 'localhost:9090' == 0` rule (T-26-36/52), and the `list_connections | grep -ci stomp` relay
assertion. This is the phase's stated central lesson applied to itself, and it held.

**Live-cluster note.** The minikube profile `jtoye` was `Stopped` and all ten compose services were up
at audit time — the canonical runtime the phase restored. Live rehearsal evidence (`LOCAL.md` §11 rows
L1-L7 + 5 supplementary) was verified as *recorded and internally consistent*, not re-executed: nothing
was started, stopped, applied, created or dropped.

---

## Sign-Off

- [x] All 68 threats have a disposition (mitigate / accept / transfer / n/a) and a recorded status
- [x] Accepted risks documented in the Accepted Risks Log (AR-26-01..06)
- [x] Unregistered flags recorded (UF-26-01..07); UF-26-04 **closed** in round 2, six remain open as
      WARNING class, non-blocking
- [x] `threats_open: 0` — **met in round 2**, T-26-29 closed by `b7fcfc7` and independently re-verified
- [x] `status: verified` set in frontmatter
- [x] Round-1 errors corrected in place rather than reconciled away (see Round-2 corrections)
- [x] No implementation file created or modified by this audit, in either round

**Verdict:** `SECURED` — 68/68 threats closed, `threats_open: 0`. The gate clears, and the number is the
one measured: T-26-29's fix was re-verified across 18 fixtures, fault injection and a live read-only run
against both revisions, and UF-26-04's across a before/after mutation-impossible harness. Six WARNING-class
unregistered flags remain open by design (UF-26-01, 02, 03, 05, 06, 07) — they are new attack surface with
no threat mapping, not absent mitigations, and none blocks this phase.

**One thing to carry out of this phase.** The fail-open class recurred **three** times here, and the third
instance survived a code review, a verification pass and the first round of this audit. What caught it was
not reading the guard but *executing it against fixtures it was not written for*. Round 1 then proposed a
closure that would have left two of the five holes open, because it reasoned about states it had not
measured. Both halves of that are the same lesson: for a control whose job is to refuse, the only evidence
that counts is a fixture table with a row per state — including the states nobody has seen yet.

*Audited: 2026-07-26 (round 1) · re-verified 2026-07-26 (round 2) · gsd-security-auditor · ASVS L1 ·
verify-mitigations mode*
