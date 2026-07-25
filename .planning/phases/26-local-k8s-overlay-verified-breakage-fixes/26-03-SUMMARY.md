---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 03
subsystem: infra
tags: [kubernetes, ci, static-gates, env-contract, kustomize-render, networkpolicy, rls, falsifiability, allowlist-hygiene]

# Dependency graph
requires:
  - phase: 26-local-k8s-overlay-verified-breakage-fixes
    plan: "01"
    provides: "the three verified k8s/base fixes this gate now pins (DB_PORT secretKeyRef, RABBITMQ_USER rename, the D-17 label-transformer fields list), the STOMP credential chains in application.yml, and render-golden.sh — whose CI placement this plan owns"
  - phase: 26-local-k8s-overlay-verified-breakage-fixes
    plan: "02"
    provides: "the 19 app-config keys + 26 core-java env entries that reduce the direction-(b) allowlist to three reviewed omissions, and the DEF-2 jtoye_app recipe/template state INV-5 asserts on"
provides:
  - "k8s/scripts/check-env-contract.sh — two-direction core-java env contract (D-07/D-08) with a reasoned, self-policing allowlist"
  - "k8s/scripts/check-render-invariants.sh — INV-1..INV-5 render/source/docs assertions pinning DEF-1, PIT-2, D-17, DEF-6 and DEF-2"
  - "k8s-validate now runs FIVE static gates instead of two, including render-golden.sh — which makes the Incremental Betterment golden contract enforceable rather than advisory"
  - "k8s/DEPLOYMENT.md 'K8s static gates' — the five scripts, the shared 0/1/2 exit-code convention, the one-command local run, and the required golden --write workflow"
  - "an INV-6.. extension point in check-render-invariants.sh that plan 26-04 adds the local-overlay assertions to (rather than adding a sixth gate)"
affects: [26-04, 26-05, 26-06, 26-07, 26-08, 26-09]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "every new invariant is demonstrated RED against a deliberately broken input AND green on the current tree — a gate that only ever passes is not a gate"
    - "block-scoped-by-indentation assertions instead of forward `grep -A N` scans: kustomize sorts map keys alphabetically, so an injected key lands ABOVE the anchor line and a forward scan returns 0 on the poisoned baseline too"
    - "non-vacuity guards: a render-level assertion that cannot find its subject exits 2 (blind parser) instead of passing silently"
    - "allowlist hygiene as part of the gate — blank reason, duplicate, and now-unnecessary (STALE) entries all FAIL, so the allowlist cannot become a permanent excuse-store"
    - "an assertion that is already-true on the correct tree, or already-true on the broken tree, is replaced with a strictly stronger falsifiable form and BOTH forms recorded"

key-files:
  created:
    - k8s/scripts/check-env-contract.sh
    - k8s/scripts/check-render-invariants.sh
  modified:
    - .github/workflows/ci-cd.yaml
    - k8s/DEPLOYMENT.md

key-decisions:
  - "26-03: INV-5 as literally specified would have made the gate RED on a CORRECT tree. Measured on the post-26-02 tree: `from-literal=username=jtoye` (token-end) = 1 and `username: \"jtoye\"` (exact) = 1 — both hits are the LEGITIMATE rabbitmq-credentials broker user that 26-01's PRE-ROLLOUT OPERATOR CHECK expects. Replaced with a block-scoped-per-Secret-name assertion that names the file, line and value of every postgres-credentials username site."
  - "26-03: INV-3 tightened from the plan's 'no app.kubernetes.io/ or environment: key' to 'ONLY k8s-app'. Real kube-dns pods carry exactly one label the selector can match, so ANY injected key — whatever its prefix — narrows the selector to zero pods. Strictly stronger and it expresses the actual defect."
  - "26-03: INV-2 generalised from 'the core-java DB_PORT EnvVar' to EVERY rendered EnvVar, with DB_PORT presence kept as the non-vacuity anchor. There are two DB_PORT EnvVars per render (core-java Deployment + pg-backup CronJob); the general rule covers both plus any future one."
  - "26-03: full-line YAML comments are stripped before placeholder extraction. application.yml:228 is a comment containing the literal text `${RABBITMQ_USER:guest}` — without stripping, a comment can make direction (a) believe a dead env IS read, which is exactly the DEF-4 masking case the gate exists to catch. Three further comments use `${ENV:default}` as prose and injected a phantom env name."
  - "26-03: Task 3's 'confirm no tabs and eyeball the indentation' criterion is a weak proxy for 'the YAML parses'. Replaced with two real parsers (yamllint with key-duplicates enabled, and actionlint), each run on the changed file, on the pre-change HEAD version, and on a deliberately mis-indented copy."
  - "26-03: INFRA-02 still NOT marked complete (anti-false-green) — this plan adds recurrence prevention only. Every live proof (02b's role, 02c's HPA minimums, the backup rehearsal) belongs to plans 26-04..26-07."

patterns-established:
  - "Two-direction contract gates: when a defect class is 'the two sides never got compared', the durable fix is a gate that compares them, plus a reasoned allowlist that is itself gated against rot"
  - "Falsifiability evidence table: for every invariant, record the GREEN output on the current tree AND the RED output on a named deliberate break, with the restore re-confirmed"

requirements-completed: []

# Metrics
duration: ~22min
completed: 2026-07-25
---

# Phase 26 Plan 03: Recurrence-Prevention Gates + CI Wiring Summary

**The two bug classes that survived every review, every CI run and a live cluster rehearsal — a manifest env name no config reads (DEF-4) and a local-only default no manifest supplies (DEF-6) — are now structurally unable to recur: `k8s-validate` runs five static gates instead of two, the env contract is compared in both directions against a three-entry allowlist that fails on its own rot, five render/source/docs invariants pin the Phase 26 fixes, and every one of the eight new assertions is demonstrated RED against a deliberate break rather than merely green on today's tree.**

## Performance

- **Duration:** ~22 min of task work (18:28 → 18:48 +0100 across three commits, plus verification sweeps)
- **Tasks:** 3 of 3
- **Files:** 4 (2 created, 2 modified) — exactly the plan's `files_modified` set, nothing outside it

## Task Commits

1. **Task 1: `check-env-contract.sh` — two directions, local-only-default rule, reasoned allowlist** — `7896857` (feat)
2. **Task 2: `check-render-invariants.sh` — INV-1..INV-5** — `258e473` (feat)
3. **Task 3: wire five gates into `k8s-validate` + document the set** — `e2c1aba` (ci)

No commit deleted a tracked file (`git diff --diff-filter=D` empty for all three).

---

## The final allowlist, verbatim (both directions, with reasons)

**Direction (a) — injected by k8s, read by no `application*.yml`. Exactly ONE entry**, which is the outcome `26-RESEARCH.md` predicted after D-05 and is strong evidence the gate is correctly scoped rather than permissive:

```bash
ALLOW_INJECTED_UNREAD=(
  'SPRING_PROFILES_ACTIVE|Spring relaxed-binding environment variable, not a ${} placeholder. Spring Boot binds it directly onto spring.profiles.active before any property source is read, so it correctly appears in no application*.yml. It is load-bearing (26-CONTEXT.md D-10 keeps every k8s environment on the prod profile) and must not be removed to satisfy direction (a).'
)
```

**Direction (b) — read by Spring, supplied by no manifest, local-only default. Exactly THREE entries**, the three the plan named:

```bash
ALLOW_UNSUPPLIED_LOCAL_DEFAULT=(
  'OLLAMA_URL|Reviewed omission: there is no in-cluster Ollama, and the media vision stage is advisory-only behind jtoye.media.vision.enabled, which defaults false (Phase 24 IMG-03 — a vision failure never rejects an upload, it only flags for review). Supplying this would point core-java at a host that does not exist; leaving the unreachable default keeps the stage inert, which is the intended k8s behaviour.'
  'ZIPKIN_ENDPOINT|Reviewed omission: no in-cluster Zipkin/OTLP collector is deployed, and Micrometer tracing export is best-effort — spans are dropped silently and no request path degrades. Revisit when an observability phase actually adds a collector; until then a supplied-but-wrong endpoint would be worse than an unreachable default.'
  'CUSTOMER_KC_ISSUER_URI|Reviewed omission, explicitly deferred in 26-CONTEXT.md <deferred> ("Customer-storefront realm in k8s"). The whole customer-storefront realm is unconfigured in EVERY k8s environment, so supplying only this one issuer would half-wire it and make a broken realm look configured. Belongs with the storefront/CID work, not with this phase.'
)
```

**`NOTIFICATION_UNSUBSCRIBE_SECRET` is NOT allowlisted, and the string does not appear in the script at all** (T-26-09 honoured):

```
$ grep -c 'NOTIFICATION_UNSUBSCRIBE_SECRET' k8s/scripts/check-env-contract.sh
0
```

**No stale allowlist entries were carried for the three names the upstream correction flagged.** `RABBITMQ_USER`, `STOMP_CLIENT_LOGIN` and `STOMP_CLIENT_PASSCODE` are now genuinely read by `application.yml` (26-01's D-05 chains), so they are in neither block — and if anyone re-added them, the STALE rule would fail the gate.

---

## The classification summary the gate prints

```
core-java env contract (D-07 / D-08)
  manifest : k8s/base/core-java-deployment.yaml
  config   : 6 application*.yml file(s) under core-java/src/main/resources/

Direction (a) — injected by k8s, read by no application*.yml (the DEF-4 shape):
  injected env names                         49
  read by some application*.yml              48
  allowlisted (reasoned omission)            1
  VIOLATIONS                                 0

Direction (b) — expected by Spring, supplied by no manifest (the DEF-6 shape):
  distinct ${} placeholders                  117
  supplied by the manifest                   48
  allowlisted (reasoned omission)            3
  pass by rule (safe non-local default)      66
  VIOLATIONS (no default at all)             0
  VIOLATIONS (local-only default)            0

PASS: 49 injected env names all read by application*.yml (1 reasoned exemption(s)); 117 placeholders carry no unsupplied local-only or missing default (3 reasoned exemption(s)).
```

The arithmetic closes: 49 injected = 48 read + 1 allowlisted; 117 placeholders = 48 supplied + 3 allowlisted + 66 pass-by-rule + 0 violations. `26-RESEARCH.md` recorded 116 placeholder names; 26-01 added the two STOMP chain names (118 with comments), and stripping full-line comments removes the one phantom (`ENV`, from three `${ENV:default}` prose comments) → **117**.

---

## Falsifiability evidence — every new assertion, RED and GREEN

**Eight assertions, eight demonstrated breaks.** Each probe was made on the working tree, run, then reverted with `git checkout -- <file>` (or a file copy for the untracked script) and re-confirmed green.

### Direction (a) — injected-but-unread

Probe: add `- name: DEADBEEF_UNREAD` / `value: "probe"` to `k8s/base/core-java-deployment.yaml`.

```
  injected env names                         50
  read by some application*.yml              48
  allowlisted (reasoned omission)            1
  VIOLATIONS                                 1

DIRECTION (a) VIOLATION — env injected by the manifest but read by NO application*.yml:
  - DEADBEEF_UNREAD
exit=1
```
Restored → `PASS: 49 injected env names ...`, `exit=0`.

### Direction (b) — unsupplied local-only default

Probe: delete the `CORS_ALLOWED_ORIGINS` env entry from `k8s/base/core-java-deployment.yaml`.

```
  VIOLATIONS (local-only default)            1

DIRECTION (b) VIOLATION — placeholder whose default is LOCAL-ONLY and that no manifest supplies:
  - CORS_ALLOWED_ORIGINS  (default: 'http://localhost:3000'  — local-only token: 'localhost')
exit=1
```
Restored → `exit=0`. (`grep -c CORS_ALLOWED_ORIGINS` on the manifest confirmed 0 during the probe.)

### Allowlist hygiene — four separate breaks

| Break | RED output | exit |
|---|---|---|
| blank reason | `allowlist (a): entry 'SPRING_PROFILES_ACTIVE' has a blank reason. D-08 requires a REASONED allowlist — an unexplained entry is indistinguishable from a forgotten defect.` — and, because a rejected entry is never stored, the same run ALSO reports `DIRECTION (a) VIOLATION ... SPRING_PROFILES_ACTIVE` | 1 |
| duplicate entry (`OLLAMA_URL` twice) | `allowlist (b): duplicate entry 'OLLAMA_URL'` | 1 |
| STALE — allowlist an already-injected name (`S3_ENDPOINT`) | `allowlist (b): STALE entry 'S3_ENDPOINT' — a manifest now SUPPLIES it, so it is no longer an unsupplied omission. Remove the entry rather than leaving a standing excuse for a variable that is already fixed.` | 1 |
| STALE — allowlist a name whose default is not local-only (`ANTHROPIC_API_KEY`) | `allowlist (b): STALE entry 'ANTHROPIC_API_KEY' — its default(s) are no longer local-only and it is not default-less, so it would pass by rule without an exemption. Remove the entry.` | 1 |

All four re-verified after the late nameref refactor of `parse_allowlist`; all restored to `exit=0`.

### Nested-default parsing proof (interfaces rule 2 + 5)

The shipped parser's own classification for the six nested-default names, dumped by temporarily instrumenting the script (instrumentation then removed):

```
DEBUG JWT_EXPECTED_ISSUER            read=1 nodef=0 defaults=[${spring.security.oauth2.resourceserver.jwt.issuer-uri};]
DEBUG CUSTOMER_JWT_EXPECTED_ISSUER   read=1 nodef=0 defaults=[${jtoye.security.customer-jwt.issuer-uri};]
DEBUG STOMP_CLIENT_LOGIN             read=1 nodef=0 defaults=[${RABBITMQ_USER:guest};]
DEBUG STOMP_CLIENT_PASSCODE          read=1 nodef=0 defaults=[${RABBITMQ_PASSWORD:guest};]
DEBUG RABBITMQ_USER                  read=1 nodef=0 defaults=[jtoye;guest;]
DEBUG RABBITMQ_PASSWORD              read=1 nodef=0 defaults=[;guest;]
```

None is reported as no-default or malformed. `RABBITMQ_USER` carries the **set** `{jtoye, guest}` — `guest` recovered by the one-level inner scan, which is interfaces rule 5's per-default (not joined-string) requirement working.

**Fail direction for the same rule** — swap in the naive `\$\{([A-Z_]+):([^}]*)\}` regex the interfaces block warns about:

```
  distinct ${} placeholders                  111        (was 117 — six names LOST)
  VIOLATIONS                                 6
DIRECTION (a) VIOLATION — env injected by the manifest but read by NO application*.yml:
  - S3_ACCESS_KEY
  - S3_BUCKET
  - S3_ENDPOINT
  - S3_PUBLIC_URL
  - S3_REGION
  - S3_SECRET_KEY
exit=1
```

Two independent defects in one probe: the missing `0-9` in the character class loses all six `S3_*` names (six SPURIOUS direction-(a) violations), and the `[^}]*` default mis-terminates on the nested chains — proven directly:

```
naive        : ${JWT_EXPECTED_ISSUER:${spring.security.oauth2.resourceserver.jwt.issuer-uri}
                ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}
nesting-tolerant: ${JWT_EXPECTED_ISSUER:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}
                ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}
```

### INV-1 + INV-2 (DEF-1, PIT-2)

Probe: re-add `value: "5432"` above the `valueFrom` on the base `DB_PORT` entry.

```
INV-1 (DEF-1 / INFRA-02a, source): no hardcoded Postgres port in k8s/base/core-java-deployment.yaml
89:          value: "5432"
  FAIL: a hardcoded Postgres port literal is back in k8s/base/core-java-deployment.yaml.
  FAIL [k8s/base]       INV-2: EnvVar 'DB_PORT' (render line 180) carries BOTH 'value:' and 'valueFrom:'.
  FAIL [k8s/production] INV-2: EnvVar 'DB_PORT' (render line 211) carries BOTH 'value:' and 'valueFrom:'.
  FAIL [k8s/staging]    INV-2: EnvVar 'DB_PORT' (render line 211) carries BOTH 'value:' and 'valueFrom:'.
exit=1
```
Both invariants trip, in all three targets. Restored → `PASS: INV-1..INV-5 hold across 3 kustomize target(s).`, `exit=0`.

### INV-3 (D-17) — and the block-scoping correction proven live

Probe: revert `k8s/base/kustomization.yaml`'s `labels` entry to `includeSelectors: true`, deleting the explicit `fields:` list.

```
  FAIL [k8s/base] INV-3: the kube-dns podSelector at render line 1105 has 3 key(s): app.kubernetes.io/managed-by,app.kubernetes.io/part-of,k8s-app
  FAIL [k8s/base] INV-3: the kube-dns podSelector at render line 1212 has 3 key(s): app.kubernetes.io/managed-by,app.kubernetes.io/part-of,k8s-app
  FAIL [k8s/base] INV-3: the kube-dns podSelector at render line 1286 has 3 key(s): app.kubernetes.io/managed-by,app.kubernetes.io/part-of,k8s-app
  FAIL [k8s/base] INV-3: the kube-dns podSelector at render line 1378 has 3 key(s): app.kubernetes.io/managed-by,app.kubernetes.io/part-of,k8s-app
  ... identical for k8s/production and k8s/staging (4 blocks each, 12 FAIL lines total)
exit=1
```

**The key ORDER in that output is the proof that the upstream correction was right.** The injected labels come out as `app.kubernetes.io/managed-by, app.kubernetes.io/part-of, k8s-app` — kustomize sorts map keys alphabetically, so both poisoned keys sit **ABOVE** the `k8s-app: kube-dns` anchor. A forward `grep -A N 'k8s-app: kube-dns'` scan would therefore return **0 on the poisoned baseline** and be unfalsifiable. The shipped assertion walks each `matchLabels:` block by indentation and inspects that block's own key set, so direction does not matter.

Restored → `INV-3 OK (4 kube-dns selector block(s), each exactly 1 key)` in base, staging and production — the post-fix expectation the correction stated (1 key × 4 blocks × 3 targets).

### INV-4 (DEF-6 recurrence)

Probe: set `k8s/base/configmap.yaml`'s `s3.endpoint` to `http://localhost:9000`.

```
  FAIL [k8s/base]       INV-4: forbidden local-only literal 'localhost' in the render:
        25:  s3.endpoint: http://localhost:9000
  FAIL [k8s/production] INV-4: forbidden local-only literal 'localhost' in the render:
        35:  s3.endpoint: http://localhost:9000
  FAIL [k8s/staging]    INV-4: forbidden local-only literal 'localhost' in the render:
        35:  s3.endpoint: http://localhost:9000
exit=1
```
Restored → `exit=0`.

### INV-5 (DEF-2) — two probes, one of which proves the block-scoping

Probe A: revert `k8s/QUICK_START.md:73` to `--from-literal=username=jtoye`.

```
  FAIL [k8s/QUICK_START.md:73] create-secret recipe names the DB SUPERUSER 'jtoye' as the postgres-credentials username.
exit=1
```

Probe B (block-scoping): set ONLY the template's `postgres-credentials` `stringData.username` to `"jtoye"`, leaving the `rabbitmq-credentials` `username: "jtoye"` on line 147 exactly as it is — so both lines read identically:

```
  OK   [k8s/QUICK_START.md:73] create-secret recipe -> postgres-credentials username='jtoye_app'
  OK   [k8s/base/secrets-template.yaml.example:228] comment-block recipe -> postgres-credentials username='jtoye_app'
  FAIL [k8s/base/secrets-template.yaml.example:82] stringData names the DB SUPERUSER 'jtoye' as the postgres-credentials username.
exit=1
```

Line 82 fails, line 147 does not — the assertion genuinely distinguishes the defect from the legitimate broker user. Both restored → `exit=0`.

### The golden CI step (the Incremental Betterment enforcement)

Probe: an unreviewed `k8s/base` edit (`redis.host: "redis-probe"`), then run the gate CI now runs.

```
FAIL [staging]: render DRIFTED from k8s/goldens/staging.yaml
FAIL [production]: render DRIFTED from k8s/goldens/production.yaml
The staging/production render no longer matches its committed golden.
exit=1
```
Restored → `OK [staging]: render matches k8s/goldens/staging.yaml (1476 lines)` / `OK [production]: ... (1476 lines)`, `exit=0`.

---

## Green state on the current tree

```
$ bash k8s/scripts/check-render-invariants.sh
INV-1 (DEF-1 / INFRA-02a, source): no hardcoded Postgres port in k8s/base/core-java-deployment.yaml
  OK   [k8s/base/core-java-deployment.yaml]: no 'value: "5432"' line

OK   [k8s/base]:       INV-2 OK (72 EnvVars, DB_PORT present, 0 with both value+valueFrom) | INV-3 OK (4 kube-dns selector block(s), each exactly 1 key) | INV-4 OK (0 localhost / 127.0.0.1 / minioadmin literals)
OK   [k8s/production]: INV-2 OK (72 EnvVars, DB_PORT present, 0 with both value+valueFrom) | INV-3 OK (4 kube-dns selector block(s), each exactly 1 key) | INV-4 OK (0 localhost / 127.0.0.1 / minioadmin literals)
OK   [k8s/staging]:    INV-2 OK (72 EnvVars, DB_PORT present, 0 with both value+valueFrom) | INV-3 OK (4 kube-dns selector block(s), each exactly 1 key) | INV-4 OK (0 localhost / 127.0.0.1 / minioadmin literals)

INV-5 (DEF-2 / INFRA-02b, docs): the DB superuser is never the postgres-credentials app username
  OK   [k8s/QUICK_START.md:73] create-secret recipe -> postgres-credentials username='jtoye_app'
  OK   [k8s/base/secrets-template.yaml.example:228] comment-block recipe -> postgres-credentials username='jtoye_app'
  OK   [k8s/base/secrets-template.yaml.example:82] stringData -> postgres-credentials username='jtoye_app'

PASS: INV-1..INV-5 hold across 3 kustomize target(s).
```

**All five gates, individually:**

| Gate | exit |
|---|---|
| `check-no-plaintext-secrets.sh` | **0** |
| `check-connection-math.sh` | **0** |
| `check-env-contract.sh` | **0** |
| `check-render-invariants.sh` | **0** |
| `render-golden.sh` | **0** |

The plan's combined command prints **`ALL_GATES_GREEN`**.

**Determinism** (a discovery loop must not reorder): two consecutive runs of each new gate on an unchanged tree are **byte-identical** (`cmp -s` true for both).

---

## CI wiring — the two pre-existing steps and the kubectl pin are untouched

| Criterion | Result |
|---|---|
| `grep -c 'check-env-contract.sh' .github/workflows/ci-cd.yaml` | **3** (≥1) |
| `grep -c 'check-render-invariants.sh' …` | **3** (≥1) |
| `grep -c 'render-golden.sh' …` | **4** (≥1) |
| `awk '/^  k8s-validate:/,/^  [a-z-]+:$/' … \| grep -c 'chmod +x'` | **5** |
| `awk '/^  k8s-validate:/,/^  [a-z-]+:$/' … \| grep -c "version: 'v1.33.3'"` | **1** (pin unchanged) |
| `git diff … \| grep '^-' \| grep -vE '^---' \| grep -c 'check-no-plaintext-secrets\|check-connection-math'` | **0** (neither existing step modified or removed) |
| `grep -c $'\t' .github/workflows/ci-cd.yaml` | **0** |
| Added-line indentation histogram | `6 × [10 spaces]`, `3 × [8]`, `3 × [6]`, `3 × [blank]` — exactly the shape of the two pre-existing steps (`      - name:` / `        run: \|` / `          <cmd>`) |
| `grep -c 'K8s static gates' k8s/DEPLOYMENT.md` | **1** (≥1); the section names all five scripts |
| `grep -c 'render-golden.sh --write' k8s/DEPLOYMENT.md` | **1** (≥1) |
| `git diff --quiet k8s/PRODUCTION_READINESS_REPORT.md` | **TRUE** (dated signed audit untouched) |

The job key `k8s-validate`, its display name, the runner and the `azure/setup-kubectl` SHA pin are all unchanged; only the header comment block and three appended steps were added.

---

## Decisions Made

1. **The direction-(a) allowlist is ONE entry, the direction-(b) allowlist is THREE.** No name was added to make anything pass. The one-entry outcome is exactly what `26-RESEARCH.md` predicted after D-05's rename, which is the best available evidence that the gate is scoped rather than permissive.
2. **Allowlist hygiene is enforced by the gate itself, in four ways** (blank reason, duplicate, now-supplied/now-read, no-longer-local-defaulted). D-08 asks for a *reasoned* allowlist; an allowlist that can rot silently is not reasoned six months later. All four are proven RED.
3. **Full-line YAML comments are stripped before placeholder extraction, trailing comments are not.** Stripping full-line comments is required (a comment can otherwise mask a dead env). Stripping trailing comments is *riskier* than the risk it removes, because a `#` can appear inside a quoted value — and a scan confirmed no placeholder currently sits in a trailing comment in any `application*.yml`. Both halves documented in the script header.
4. **INV-2 asserts on every EnvVar, not just `DB_PORT`.** PIT-2 is a general `EnvVar` trap; naming one variable would leave the class open everywhere else. `DB_PORT` presence is retained as the non-vacuity anchor so the assertion cannot pass by finding nothing.
5. **`LOCAL_ONLY_TARGETS` is matched on the exact repo-relative path**, so a future `k8s/local-staging` overlay is not silently excluded by a `k8s/local` substring. It is pre-seeded with `k8s/local` (created by 26-04) with the reason stated in a comment.
6. **Plan 26-04 extends `check-render-invariants.sh` as INV-6.., rather than adding a sixth gate.** Stated as an explicit extension point in the script header so the next executor does not add a parallel script.
7. **`INFRA-02` is still NOT marked complete.** This plan adds recurrence prevention; 02b's live role proof, 02c's HPA minimums and the backup rehearsal all belong to later plans. Marking it here would be exactly the false-green this phase exists to eliminate.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] INV-5 as literally specified would have made the gate RED on a CORRECT tree**

- **Found during:** Task 2, before writing the assertion — measured first rather than assumed.
- **Issue:** the plan specifies "no `from-literal=username=jtoye` at end-of-token, and no `username: "jtoye"` exact value". Both are non-zero on the post-26-02 tree, and both hits are legitimate:
  ```
  $ grep -cE 'from-literal=username=jtoye($|[^_a-zA-Z0-9])' k8s/QUICK_START.md      1   # rabbitmq broker user
  $ grep -cE '^\s+username: "jtoye"\s*$' k8s/base/secrets-template.yaml.example     1   # rabbitmq-credentials
  ```
  `rabbitmq-credentials.username` **is** `jtoye`, and 26-01's `PRE-ROLLOUT OPERATOR CHECK` explicitly expects that value. A gate shipped in that form would be red on a correct tree, and the natural "fix" would be to rename the broker user and break AMQP auth in staging and production. This is the same trap 26-02 hit on the same two files.
- **Fix:** implemented INV-5 **block-scoped per Secret name** — each `kubectl create secret generic <name>` recipe (including one living inside a comment block) and each YAML document is attributed to its Secret, and only `postgres-credentials` is asserted on. The gate now prints the file, line and value of every `postgres-credentials` username site, so the reviewer sees what was checked. Strictly stronger: it asserts *which* username is wrong, where a whole-file count cannot, and it is proven to fail on line 82 while ignoring the identical text on line 147.
- **Files modified:** none beyond the intended new script.
- **Committed in:** `258e473`

**2. [Rule 1 - Bug] Task 3's "the full-file YAML parses" criterion was a weak proxy**

- **Found during:** Task 3 verification.
- **Issue:** the criterion substitutes "no tabs + the diff hunk indentation matches" for an actual parse. Neither catches a wrongly-indented `run:` body, a duplicated key, or an invalid workflow schema — and the no-tabs count was already `0` before the change, so it is unfalsifiable on its own.
- **Fix:** ran two real parsers, in both directions, and kept the no-tabs check as a supplement:
  ```
  yamllint (key-duplicates enabled) on the changed file      exit 0
  actionlint on the changed file                             exit 0
  actionlint on git show HEAD:.github/workflows/ci-cd.yaml    exit 0   (no pre-existing failure hidden)
  actionlint on a copy with one run-body line de-indented     exit 1
      .github/workflows/ci-cd.yaml:237:8: could not parse as YAML: could not find expected ':' [syntax-check]
  ```
  The pre-change baseline run matters: without it, "actionlint is green" could not distinguish "my change is valid" from "actionlint was already green and says nothing".
- **Files modified:** none (verification-only).
- **Committed in:** `e2c1aba` (evidence recorded here)

**3. [Rule 2 - Missing critical] Non-vacuity guards added to INV-2, INV-3 and INV-5**

- **Found during:** Task 2.
- **Issue:** the plan requires each invariant to be demonstrably falsifiable, but a render-level assertion has a second silent-pass mode the plan does not cover: passing because it found **nothing to check**. If `DB_PORT` were renamed, or the DNS egress rules deleted, or the parser broken by a format change, every one of these invariants would report OK while asserting nothing — the exact green-by-construction failure this phase is fighting.
- **Fix:** each render-level invariant asserts it located its subject and exits **2** (parse failure — "the parser is blind, fix the parser, do not delete the invariant") if not: ≥1 EnvVar and a `DB_PORT` EnvVar per target, ≥1 kube-dns `matchLabels` block per target, and ≥3 `postgres-credentials` username sites across the two docs. `check-env-contract.sh` carries the same guard on both of its extractions.
- **Files modified:** `k8s/scripts/check-render-invariants.sh`, `k8s/scripts/check-env-contract.sh`
- **Committed in:** `258e473`, `7896857`

**4. [Rule 2 - Missing critical] Full-line YAML comments stripped before placeholder extraction**

- **Found during:** Task 1, while validating the extraction against the real files.
- **Issue:** `application.yml:228` is a comment that contains the literal text `` `${RABBITMQ_USER:guest}` `` (26-01 wrote it to explain the D-05 chain). Un-stripped, a **comment** can make direction (a) believe an injected env is read — which is precisely the DEF-4 masking case the gate exists to catch. Three further comments use `${ENV:default}` as prose, injecting a phantom env name `ENV` into the inventory (118 names vs the true 117).
- **Fix:** strip `^\s*#` lines before extraction; deliberately do **not** strip trailing comments (a `#` can legitimately appear inside a quoted value, and a scan confirmed no placeholder currently sits in one). Both halves of that reasoning are in the script header as PARSING NOTE 4.
- **Files modified:** `k8s/scripts/check-env-contract.sh`
- **Committed in:** `7896857`

**5. [Rule 2 - Missing critical] INV-3 tightened to "only `k8s-app`"; INV-2 generalised to every EnvVar**

- **Found during:** Task 2.
- **Issue:** the plan's INV-3 names two key families (`app.kubernetes.io/`, `environment:`) — but the defect is that a kube-dns podSelector must match pods carrying exactly one label, so **any** extra key breaks it, whatever its prefix. Likewise the plan's INV-2 names the core-java `DB_PORT` EnvVar, while PIT-2 is a general `EnvVar` trap and there are in fact **two** `DB_PORT` EnvVars per render (core-java Deployment + pg-backup CronJob).
- **Fix:** INV-3 fails on any key other than `k8s-app` in a kube-dns block; INV-2 fails on any EnvVar carrying both fields, anywhere in the render. Both are strictly stronger and both are proven RED. The named families and `DB_PORT` remain in the failure text and the header so the original defect stays legible.
- **Files modified:** `k8s/scripts/check-render-invariants.sh`
- **Committed in:** `258e473`

**6. [Rule 3 - Blocking] `eval`-based allowlist map writes replaced with bash namerefs**

- **Found during:** Task 1 review before commit.
- **Issue:** the first draft wrote the allowlist maps through `eval`, and left an unused `fail()` helper (dead code against the stated house style).
- **Fix:** `parse_allowlist` now takes a `local -n` nameref; the tooling preflight requires bash ≥ 4.3 accordingly (ubuntu-latest ships 5.x, local is 5.2.21); the final verdict uses `fail()`. All four hygiene probes were re-run **after** the refactor and still trip.
- **Files modified:** `k8s/scripts/check-env-contract.sh`
- **Committed in:** `7896857`

**Total deviations:** 6 auto-fixed — 2 broken/weak acceptance criteria replaced with strictly stronger falsifiable forms, 3 missing-critical strengthenings of the assertions themselves, 1 code-quality/blocking cleanup. **No scope creep:** no file outside the plan's `files_modified` was touched. Two of the six are the anti-false-green class, caught by *measuring the criterion before implementing it* rather than after.

## Issues Encountered

- **`python3` is blocked in this environment** (a base-conda guard hook) and `yq`/`ruby` are absent, so all YAML walking is awk-based — the same constraint 26-01 and 26-02 worked under. `yamllint` and `actionlint` *are* available and were used for the workflow validation, which is why Task 3's proof is stronger than the plan's proxy.
- **The temporary instrumentation used for the nested-default dump was added to the shipped script and then removed** (restored from a byte-identical copy, then re-verified green). It is recorded here as instrumentation, not as a shipped feature — the committed script has no debug hook.
- **No `render-golden.sh --snapshot` / `--write` was needed.** This plan makes **no `k8s/base` edit**, so both goldens are unchanged at 1476 lines and check mode passes as-is. The probes that touched `k8s/base` were all reverted with `git checkout -- <file>` and the golden gate re-confirmed green afterwards. The `--diff-since` normal-diff-format convention (`^>` added / `^<` removed) is therefore documented in `k8s/DEPLOYMENT.md` for the next base edit but not exercised here.

## Constraint compliance

- **Static side only.** No `minikube start`, no compose container stopped, no DB role / bucket / Secret created, no `kubectl apply`. Only `kubectl kustomize` (pure local render) and local file reads. Every mutation remains plan 26-07's, behind its human-action checkpoint.
- **`docs/metrics.json` untouched** — 26-06 is its single writer this phase (`git diff --quiet docs/metrics.json` TRUE). Both new gates are bash and `scripts/docs-freshness.sh` counts no bash, so this plan contributes **0** to the metrics total; the header of `check-env-contract.sh` states that explicitly. `docs-freshness` check mode stays known-RED from 26-01's new Java test file until 26-06.
- **`k8s/PRODUCTION_READINESS_REPORT.md` untouched** (`git diff --quiet` TRUE) — dated signed audit; 26-06 owns its appended note.
- **No allowlist widened to make a gate pass.** Every entry names a reviewed omission with a stated reason, and the gate fails on its own rot. `NOTIFICATION_UNSUBSCRIBE_SECRET` is absent from the script entirely.
- **The `k8s-validate` job's existing behaviour is intact** — same job key and display name, same runner, same pinned kubectl SHA, both pre-existing steps byte-unchanged (0 removed lines mention either script). No new job-level `permissions` block was needed: all five gates are local file reads plus a client-side `kubectl kustomize`, so the restricted default `GITHUB_TOKEN` is sufficient.
- **Every probe reverted and re-confirmed.** `git status --short` is clean after each; the final tree contains only the four intended files.
- **Sequential-executor rules honoured:** main working tree, branch `feature/phase-26-local-k8s-overlay` throughout, normal commits with hooks (no `--no-verify`), no `git stash`, no branch switch, no worktree, no `git clean`.

## Threat model disposition

| Threat | Disposition | Evidence |
|---|---|---|
| T-26-12 (Spoofing — STOMP/RabbitMQ credential env names) | **mitigated** | Direction (a) fails CI on any injected-but-unread env — proven RED with `DEADBEEF_UNREAD`. The three names that were the original defect (`RABBITMQ_USERNAME`, `STOMP_CLIENT_LOGIN`, `STOMP_CLIENT_PASSCODE`) are now read by `application.yml`, are NOT allowlisted, and would fail the STALE rule if anyone tried to allowlist them. |
| T-26-13 (Information disclosure — unsupplied local-only defaults) | **mitigated** | Direction (b)'s word-list rule fails CI unless the omission is allowlisted WITH a reason — proven RED by removing `CORS_ALLOWED_ORIGINS` (named, with its `http://localhost:3000` default and the matching token). INV-4 adds the render-side non-regression half, proven RED on an `s3.endpoint` localhost literal. |
| T-26-14 (Elevation of privilege — DEF-2 recurrence in docs) | **mitigated** | INV-5 checks all three `postgres-credentials` username sites (recipe, template comment-block recipe, template `stringData`) and is proven RED on two of them independently, while provably ignoring the legitimate `rabbitmq-credentials` `jtoye`. The failure message states that a superuser bypasses every RLS policy and that `DatabaseConfigurationValidator` fails boot on one. |
| T-26-15 (DoS — kube-dns egress selector regression) | **mitigated** | INV-3 asserts on the RENDER, block-scoped by indentation, and is proven RED (12 FAIL lines, 4 blocks × 3 targets) by reverting the transformer. The probe output also *demonstrates* why the forward-scan form would have been unfalsifiable: the poisoned keys sort above the anchor. Closes the blind spot in `validate-networkpolicies.py` (raw-file parser, still not wired into CI). |
| T-26-16 (Tampering — allowlist rot) | **mitigated** | Four hygiene breaks proven RED: blank reason, duplicate, now-supplied STALE, no-longer-local-defaulted STALE. |
| T-26-17 (Tampering — the gates themselves) | **accepted** | A contributor with write access can delete a CI step; only review prevents it. Mitigated as far as is possible in-repo: each script header names the specific defect its invariant pins, the plan that fixed it, and why the assertion cannot live anywhere else, so a reviewer sees the cost of removal. `k8s/DEPLOYMENT.md` documents the whole set as an operator obligation. |
| T-26-SC (supply chain) | **n/a** | Zero packages installed — bash, YAML and Markdown only. |

**Other quality contracts:** web performance **N/A** (no user-facing page, route or bundle changed); SEO **N/A** (no public/unauthenticated surface changed); AI agent-readiness **N/A** (no endpoint, contract or OpenAPI change).

**Threat flags:** none. No new network endpoint, auth path, file-access pattern or schema change. Both new scripts are read-only: they run `kubectl kustomize` (client-side, no cluster credentials), read repo files, and write only to a `mktemp -d` cleaned up by a trap.

## Known Stubs

None. Both scripts are complete, executable, and green on the current tree with every assertion demonstrated in both directions. `LOCAL_ONLY_TARGETS` is pre-seeded with a path (`k8s/local`) that does not exist yet — that is deliberate forward-compatibility with plan 26-04, not a stub: the exclusion list is inert until that directory exists, and the reason is stated in a comment beside it.

## User Setup Required

None. Both gates are client-side and need no credentials. On CI, `check-env-contract.sh` requires GNU `grep -P`, which `ubuntu-latest` provides; on a developer machine without it the gate exits **2** with a message naming the requirement rather than silently passing.

## Next Phase Readiness

**Ready.** Notes for the plans that build on this:

- **26-04** (local overlay + ingress host cleanup): add the local-overlay assertions (endpoint-shim count, the D-09 scale triple, the backup endpoint) as **INV-6..** inside `k8s/scripts/check-render-invariants.sh` — the extension point is stated in its header. Creating `k8s/local` automatically brings it under all four per-target invariants via the `find -maxdepth 2` discovery loop, and `LOCAL_ONLY_TARGETS` already excludes it from INV-4 only. Any `k8s/base` edit needs its **own** `--snapshot` label (`26-02` is spent) and a `--write` golden regeneration committed in the same change — CI now enforces that.
- **26-05** (`.env` keys + `k8s-local-up.sh`): if a new core-java env is added, `check-env-contract.sh` will demand a matching `${PLACEHOLDER}` in some `application*.yml`. Fix the name, do not allowlist it.
- **26-06** (docs): owns `docs/metrics.json` (1690 → 1698, unchanged by this plan) and the dated `PRODUCTION_READINESS_REPORT.md` note. The five-gate set is now documented in `k8s/DEPLOYMENT.md`; if 26-06 restates it anywhere, cross-reference rather than duplicate.
- **26-07** (live): none of these gates touches a cluster, so nothing here changes the live checklist. 26-01's RabbitMQ `PRE-ROLLOUT OPERATOR CHECK` remains genuinely un-gateable (secret VALUES never appear in a render, by design) and is still carried as an operator step.

**Concerns:**

- **`docs-freshness` check mode stays RED until 26-06.** Expected; do not hand-fix.
- **The gate covers core-java only.** `edge-go` (`os.Getenv`) and the frontend (`process.env`) have no env contract, and 26-02 already found a real defect on the edge-go side (`JWT_EXPECTED_ISSUER` read since #87, never supplied). That extension is a recorded deferred idea and the limitation is stated in the script header so nobody assumes wider coverage.
- **INV-4 is a literal scan, not a semantic one.** A private-range IP or an internal hostname that is wrong for the target environment would pass. The word list is the DEF-6 signature, not a complete definition of "wrong endpoint".

## Self-Check: PASSED

All 4 files exist on disk (2 created, 2 modified); all 3 commits (`7896857`, `258e473`, `e2c1aba`) resolve in `git log`; `git status --short` is clean.

| must_haves truth | Proof |
|---|---|
| CI fails if a k8s manifest injects a core-java env name that no `application*.yml` reads (D-07) | Direction (a) wired as a `k8s-validate` step. Proven RED: `DEADBEEF_UNREAD` → `VIOLATIONS 1`, exit 1, name printed. Green: 49 injected / 48 read / 1 reasoned exemption / 0 violations. |
| CI fails if a Spring placeholder whose defaults are local-only is left unsupplied by every manifest and is not on an explicit allowlist (D-08) | Direction (b) wired as the same step. Proven RED: removing `CORS_ALLOWED_ORIGINS` → `CORS_ALLOWED_ORIGINS (default: 'http://localhost:3000' — local-only token: 'localhost')`, exit 1. Green: 117 placeholders / 48 supplied / 3 reasoned exemptions / 66 pass-by-rule / 0 violations. |
| Every allowlisted omission carries a non-empty human reason, and a stale entry fails the gate rather than rotting silently | All 4 entries carry multi-sentence reasons (verbatim above). Four hygiene breaks each proven RED: blank reason, duplicate, STALE-now-supplied (`S3_ENDPOINT`), STALE-no-longer-local (`ANTHROPIC_API_KEY`). Re-verified after the nameref refactor. |
| CI fails if any rendered core-java `DB_PORT` EnvVar carries both `value` and `valueFrom`, or a hardcoded 5432 returns to the base | INV-1 + INV-2, both wired. Proven RED together by re-adding `value: "5432"`: INV-1 names the source line, INV-2 names `DB_PORT` at render line 180/211/211 in all three targets. Generalised to every EnvVar; `DB_PORT` presence is the non-vacuity anchor (exit 2 if absent). |
| CI fails if the kustomize label transformer ever re-injects common labels into the kube-dns DNS-egress podSelector | INV-3, wired, **block-scoped by indentation** per the upstream correction. Proven RED by reverting to `includeSelectors: true`: 4 blocks × 3 keys × 3 targets, 12 FAIL lines. The probe output confirms the poisoned keys sort ABOVE `k8s-app`, so the forward `grep -A` form really would have been unfalsifiable. Green: 4 blocks × 1 key in base, staging and production. |
| CI fails if a staging or production render reintroduces a `localhost` / `127.0.0.1` / `minioadmin` literal | INV-4, wired, per target except an exactly-matched `LOCAL_ONLY_TARGETS` entry. Proven RED by pointing `s3.endpoint` at `http://localhost:9000`: fails in base, staging and production with the offending render line quoted. |
| CI fails if the secret recipe or template goes back to naming the DB superuser as the app username | INV-5, wired, block-scoped per Secret name across three sites. Proven RED twice — the QUICK_START recipe and the template `stringData` — and proven NOT to trip on the byte-identical legitimate `rabbitmq-credentials` `username: "jtoye"` on the very next document. |
| CI fails if a `k8s/base` edit changes the staging or production render without a reviewed golden regeneration | `render-golden.sh` is now the fifth `k8s-validate` step. Proven RED by an unreviewed base edit (`redis.host`): both targets report DRIFT, exit 1. Green afterwards at 1476 lines each. The required `--write` workflow is documented in `k8s/DEPLOYMENT.md`. |
| `k8s-validate` runs five gates instead of two | `chmod +x` count inside the job range = **5**; kubectl pin count = **1**; removed lines mentioning either pre-existing script = **0**; `yamllint` and `actionlint` both exit 0 on the changed file, `actionlint` exit 0 on the pre-change HEAD version, and exit 1 on a deliberately de-indented copy. |

**Nothing is claimed as proven that was not run.** Two acceptance criteria were wrong or weak as written and are reported above with the measurement that shows it and a strictly stronger replacement. `INFRA-02` is deliberately left un-marked: this plan closes recurrence prevention, not the live proofs.

---
*Phase: 26-local-k8s-overlay-verified-breakage-fixes*
*Plan: 03*
*Completed: 2026-07-25*
