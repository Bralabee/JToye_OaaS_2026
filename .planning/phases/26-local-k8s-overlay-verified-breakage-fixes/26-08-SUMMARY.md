---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 08
status: AWAITING-HUMAN-VERIFICATION
subsystem: infrastructure
tags: [kubernetes, minikube, keycloak, oidc, split-horizon, stomp, rabbitmq, relay, evidence, live-e2e]
requires: ["26-01", "26-02", "26-03", "26-04", "26-05", "26-06", "26-07"]
provides:
  - "k8s/LOCAL.md §11 rows L6 and L7 filled with verbatim captured output"
  - "DEF-5 closed: a real vendor login through the ingress lands on a dashboard"
  - "an additive core-api redirect URI accepting the ingress callback, live and committed"
  - "KEYCLOAK_CLIENT_ID config-injected via app-config/keycloak.client-id, base value unchanged"
  - "broker-side STOMP identity proof: authenticated as the dedicated login, zero guest connections"
  - "A NEW PRODUCTION DEFECT, proven and falsified: the STOMP relay rejects the KDS topic"
affects: ["26-09"]
tech-stack:
  added: []
  patterns:
    - "additive Keycloak admin-API client update instead of a realm-destructive kc.sh import --override"
    - "frame-census proof for realtime: a visibly-updating board is not evidence of a relayed event"
    - "two-arm destination-shape falsification over a raw STOMP socket (control arm proves the credentials)"
    - "git diff --numstat deletions, never `git diff | grep -c '^-'` (the `--- a/path` header is a hyphen line)"
key-files:
  created:
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-08-SUMMARY.md
  modified:
    - infra/keycloak/realm-export.template.json
    - k8s/base/configmap.yaml
    - k8s/base/frontend-deployment.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/LOCAL.md
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
decisions:
  - "The live realm was updated ADDITIVELY via the Keycloak admin API rather than by the plan's kc.sh import --override true, because --override replaces the whole realm and the change is one client attribute (Incremental Betterment)"
  - "staging/production get a comment recording the deliberate NON-override of keycloak.client-id rather than a duplicate line that adds a second place to keep in sync"
  - "The KDS relay defect is surfaced for decision, NOT fixed (Rule 4): the fix spans the Java publisher, the TS subscriber and TenantChannelInterceptor's tenant-isolation parsing"
  - "L6 is recorded as FALSIFIED rather than unproven — a falsification is a stronger result and is exactly what D-06 was written to obtain"
metrics:
  duration: ~1h35m
  completed: 2026-07-25
  tasks: 3 (2 complete, 1 automated with its human gate OPEN)
  commits: 3
  docs_metrics_json: untouched (26-06 owns it; 0 counted invocations added)
---

# Phase 26 Plan 08: DEF-5 Ingress Login + D-06 Relay Proof Summary

**DEF-5 is closed by a real vendor login through the ingress, and D-06's functional half is
FALSIFIED — the STOMP relay is reachable and correctly authenticated, and it rejects the KDS topic
because a RabbitMQ `/topic` destination cannot contain `/`. That is a production-affecting defect
(`k8s/base` sets `stomp.broker.mode: relay`), and it is the finding this whole phase existed to make
possible.**

> **Status: Task 3's `checkpoint:human-verify` gate is OPEN.** Everything automatable is done and
> recorded; the human's browser judgement has not been given and is not claimed anywhere. This
> document exists because the plan's `<output>` requires it at this path — it is **not** a claim that
> the plan is signed off. Plan 26-09 owns marking INFRA-01/INFRA-02 complete.

## Task 1 — the two live blockers (commit `5ae3051`)

Both blockers were confirmed **against the running systems** before being fixed, not just against the
files.

**Blocker 1 — the realm rejected the ingress callback.** The live realm's `core-api` client held only
localhost redirect URIs, and `GET /clients?clientId=frontend` returned **0 results**: the client the
manifest named does not exist. Appended `http://app.jtoye.local/*`.

Read back from the **running** Keycloak:

```
.redirectUris = [ "http://localhost:8080/*", "http://localhost:3100/*",
                  "http://localhost:3000/*", "http://localhost:9090/*",
                  "http://app.jtoye.local/*" ]
.webOrigins   = [ "*" ]        (unchanged)
post.logout.redirect.uris = "+"  (unchanged)
publicClient = false   standardFlowEnabled = true
```

Four pre-existing localhost entries retained, one added — so the compose dev flow on `:3000` / `:3100`
is untouched. Falsified in **both** directions so the acceptance is not blanket-acceptance
(T-26-46): an authorize request with `redirect_uri=http://app.jtoye.local/api/auth/callback/keycloak`
returns **HTTP 200** (the login page renders), while an unlisted host still returns
`Invalid parameter: redirect_uri`.

**Blocker 2 — the OIDC client id was a hardcoded literal naming a nonexistent client.**
`KEYCLOAK_CLIENT_ID: value: "frontend"` became a `configMapKeyRef` to a new
`app-config/keycloak.client-id` whose base value is the byte-identical string `frontend`. The local
overlay patches it to `core-api`.

Static criteria, all measured against a **pre-edit baseline that could fail** (0→1, 1→0):

| criterion | before | after | expected |
|---|---|---|---|
| `app.jtoye.local/*` in the realm template | 0 | **1** | 1 |
| `localhost:3000/*` in the realm template | 1 | **1** | unchanged |
| `value: "frontend"` in `frontend-deployment.yaml` | 1 | **0** | 0 |
| `keycloak.client-id: "frontend"` in `k8s/base/configmap.yaml` | 0 | **1** | 1 |
| production render `key: keycloak.client-id` under the env | 0 | **1** | 1 |
| production render `keycloak.client-id: frontend` | 0 | **1** | 1 (value unchanged) |
| staging render `keycloak.client-id: frontend` | 0 | **1** | 1 (value unchanged) |
| local render `keycloak.client-id: core-api` | 0 | **1** | 1 |
| local render inline `value:` under `KEYCLOAK_CLIENT_ID` | 1 | **0** | 0 |

**Golden diff, anchored to snapshot `26-08-task1`** (taken BEFORE any edit):

```
resolve_exit=0        test -s: TRUE (non-empty)
'<' removed lines: 2   (one `value: frontend` per target — nothing else)
'>' added lines:  10   (per target: 1 ConfigMap key + a 4-line configMapKeyRef)

19a20
>   keycloak.client-id: frontend
686c687,690
<           value: frontend
---
>           valueFrom:
>             configMapKeyRef:
>               key: keycloak.client-id
>               name: app-config
   ... identical block for the second target ...
```

Goldens **1465 → 1469** lines each. Every `<` line is accounted for by the
`value:` → `configMapKeyRef:` conversion; there are no others.

**Live result:** `deploy/frontend` env is a `configMapKeyRef`, the pod resolves
`KEYCLOAK_CLIENT_ID=core-api`, rollout succeeded, pod `READY 1/1` with restartCount 0. core-java's
generation stayed **1** (metadata-only apply), so it did **not** restart and its STOMP session survived
into Task 2.

## Task 2 — broker-side STOMP identity (commit `82e899f`)

`jtoye`, not `guest`, proven at the broker on two independent views, with a control and a
falsification:

```
STOMP connections                      1
STOMP rows with user == guest           0
auth_login == jtoye  (plugin CLI)       1
NON-VACUITY (protocol nothing uses)     0   startswith("MQTT") — the filter selects, not passes-all
PREDICATE CAN FIRE (fixture w/ guest)   1   so 0 on live data is a real negative
```

`172.18.0.1:54520 -> 172.18.0.14:61613` · `STOMP 1.2` · `connected_at 1785013837263`.

**The plan's assertion form was unsatisfiable and was replaced with a stronger one.**
`rabbitmqctl list_connections` lists **AMQP readers only** on RabbitMQ 3.12 — its sole row is the Spring
AMQP pool at protocol `{0,9,1}` — so `list_connections … | grep -ci stomp` can never return ≥ 1 no
matter how healthy the relay is, and would have been recorded as a DEF-4 failure against a working
relay. `list_stomp_connections` additionally **rejects** the `user` info key
(`Info key(s) user are not supported`). The identity columns that do exist are the plugin's
`auth_login` and the management API's `user`; both were used.

**`peer_host` cannot be on the minikube bridge** — the same double NAT 26-07 measured for Postgres — so
attribution is by **elimination** (all four compose app services `exited`) and **correlation**: broker
`connected_at 21:10:37.263` against the pod's `"System" session connected.` at `21:10:37.267`, a **4 ms**
delta inside the current container's lifetime (`startedAt 21:10:05Z`), with
`BrokerAvailabilityEvent[available=true]` two milliseconds later.

DEF-4 re-asserted on the current pod: `Access refused for user` = **0**,
`In-memory simple broker` = **0**, `STOMP broker relay configured` = **1**,
`STOMP_BROKER_MODE=relay`. Decoded `stomp-passcode` in `k8s/LOCAL.md`: **0**, with the predicate proven
able to fire against a fixture.

Also recorded, with file:line, why `frontend/e2e/stomp-relay.spec.ts` is **not** the ingress proof: the
stub cookie at `:61-63`/`:149-151` against the server-side `auth()` gate at
`frontend/app/dashboard/layout.tsx:19`; an `EDGE_URL` target at `:29` that the local ingress does not
route (its two rules are core-java and frontend only); `networkidle` at `:76`/`:167` on a page holding
SSE and STOMP open; and two silent skips at `:46` and `:80-85`. Deferred with a suggested closure per
item.

## Task 3 — the live journey (commit `9a7327b`) · HUMAN GATE OPEN

### L7 — DEF-5: PROVEN

One verbatim URL carries most of the proof — the redirect the SSO button produced:

```
http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/auth
  ?scope=openid+profile+email&response_type=code
  &client_id=core-api
  &redirect_uri=http%3A%2F%2Fapp.jtoye.local%2Fapi%2Fauth%2Fcallback%2Fkeycloak
  &code_challenge=…&code_challenge_method=S256
```

The browser went to the **public** issuer host; `client_id` is the realm's **real** client resolved from
`app-config`; the callback is the **ingress** origin the realm now accepts.

```
POST-LOGIN URL   : http://app.jtoye.local/dashboard
rendered <h1>    : "Dashboard"        (not /auth/signin)
login wall time  : 591 ms             (the feedback_port3100 symptom is ~10s then 401)
redirect_uri errors in the frontend pod log : 0
```

**The two issuer values, recorded and DIFFERENT — that difference IS DEF-5:**

| | value |
|---|---|
| frontend `KEYCLOAK_ISSUER` | `http://localhost:8085/realms/jtoye-dev` |
| frontend `KEYCLOAK_ISSUER_INTERNAL` | `http://host.minikube.internal:8085/realms/jtoye-dev` |
| core-java `JWT_EXPECTED_ISSUER` | `http://localhost:8085/realms/jtoye-dev` |
| core-java `KC_ISSUER_URI` | `http://host.minikube.internal:8085/realms/jtoye-dev` |

A token minted by this realm carries `iss=http://localhost:8085/realms/jtoye-dev`, `aud=core-api`,
`tenant_id=00000000-…-000000000001`, and core-java **accepted it through the ingress** (HTTP 200 on
`/api/v1/orders`). So `iss` is validated against the public value while JWKS is fetched from the
pod-reachable one. Collapsing them would make the login pass while proving nothing (T-26-47).

**API origin, measured:** `/api/v1` requests by host = `{"api.jtoye.local": 10}`; loopback **app**
requests = **0**; `/api/v1` responses ≥ 400 = **0**. Real seeded data rendered (4 shops / 46 products /
23 orders / 12 customers; the switcher listed Brixton Village Grill, Peckham Jollof Co., Mama Ade's
Kitchen) — an empty catalogue would have been a green-looking regression.

**API base baked into the running frontend image:** `api.jtoye.local` appears **10×** in the static
chunks, `localhost:9090` **0×** — D-18's build-arg wiring works.

### L6 — the functional relay proof: FALSIFIED

What worked, which is what isolates the fault:

```
websocket           : ws://api.jtoye.local/ws
CONNECTED
server:RabbitMQ/3.12.14
session:session-ZnxI-6gaL4s4Qmg47iMARQ
version:1.2
user-name:99d11593-ea98-4891-a136-220884094283
```

`server:RabbitMQ/3.12.14` **in a frame delivered to the browser** is the single strongest line for D-06:
the browser's STOMP session is served by the host broker through the relay, not by Spring's in-memory
simple broker. The `user-name` is the authenticated vendor's subject.

What failed:

```
SUBSCRIBE destination:/topic/kitchen/00000000-…-000000000001/97d95aa4-…   x14
ERROR message:Invalid destination                                          x14
  '/kitchen/00000000-…/97d95aa4-…' is not a valid topic destination
frame census: open 14 | CONNECT 14 | CONNECTED 14 | SUBSCRIBE 14 | ERROR 14 | MESSAGE 0
```

**Both directions**, not just the subscriber. The publish side fails on the relay's own `_system_`
session, 43 ms after the state change was accepted:

```
21:44:24.511 INFO  OrderStateChangeListener        CONFIRMED -> PREPARING (ORD-…-23C4097F)
21:44:24.548 ERROR StompBrokerRelayMessageHandler  Received ERROR {message=[Invalid destination]…}
                                                   session=_system_ payload='/kitchen/…/97d95aa4-…'
```

The trigger itself was healthy: `POST /api/v1/orders/afe90b6d-…/start-preparation` → **HTTP 200**,
`status: PREPARING`.

**The trap this row exists to catch fired.** The board **did** visibly change with no manual refresh
(`Confirmed` → `Preparing`, 0 navigations). A human watching would call that a pass. It is not: **0**
MESSAGE frames, and the same 30-second window contains **24** `/api/v1/orders…` requests — three per
redial, because each rejected SUBSCRIBE closes the session, `@stomp/stompjs` redials on
`reconnectDelay: 5000`, and `useStomp`'s `onReconnect` fires a full `fetchOrders()`. The visible update
is a **refetch caused by the failure**. Reconnect-driven polling is indistinguishable from realtime by
eye and distinguishable only by frame census.

**The UI does surface it, if you look at the right pixel:** `kitchen/page.tsx:376-386` renders the status
dot `bg-green-500` when `connected` and `bg-yellow-500` when `reconnecting`. In the captured screenshot
it is **amber**, and it never goes green. That is the human-checkable tell.

**Diagnosis falsified in two arms** over a raw socket against the same broker, port and credentials
(read-only — a SUBSCRIBE creates an auto-delete queue that vanishes on DISCONNECT; nothing published):

| arm | destination | CONNECTED | SUBSCRIBE | ERROR |
|---|---|---|---|---|
| A control | `/topic/kitchen.<tenant>.<shop>` (dots, one segment) | true | **ok (RECEIPT)** | none |
| B app shape | `/topic/kitchen/<tenant>/<shop>` (slashes) | true | **rejected** | `Invalid destination` |

**Arm A is the load-bearing half.** Without it, `Invalid destination` could have been read as another
credential or connectivity fault; with it, the broker/port/login/passcode are all proven correct — DEF-4
really is fixed — and the fault is isolated to the **destination shape** alone.

Why it was **not** fixed here (**Rule 4 — architectural**): the change spans the Java publisher
(`OrderStateChangeListener.java:109`), the TypeScript subscriber
(`kitchen/page.tsx:277` via `hooks/use-stomp.ts`) and `TenantChannelInterceptor.java:123`, whose
tenant-isolation prefix check parses those very segments. None is in this plan's `files_modified`,
Phase 26's boundary is "no application behaviour change" beyond its authorised additive edits, and
getting the interceptor wrong is a **tenant-isolation** risk. Recorded as `k8s/LOCAL.md` §7 **A3** plus a
deferred item carrying a suggested direction and a **must-fail-first** four-part acceptance test — and an
explicit warning **not** to "fix" it by flipping `stomp.broker.mode` to `in-memory`, which would hide the
defect and break multi-replica correctness (the simple broker is per-JVM).

### Visual / mobile

```
375px horizontal overflow                : scrollWidth 375 == clientWidth 375 -> none
<img> with naturalWidth === 0            : 0
<img> elements on /dashboard and /kitchen: 0   (a legitimate zero, stated as such —
                                            the s3.public-url image path is NOT exercised)
mobile-tab-bar elements in the live DOM  : 1, visible
console errors                           : 15, ALL accounted for (14 = the A3 cascade,
                                            1 = an authjs getSession race at redirect)
```

Seven screenshots were captured (landing, signin, Keycloak form, dashboard, kitchen before/after,
375px). The dashboard and kitchen render correctly with real data. **One observation left for the human's
eye rather than adjudicated here:** in the full-page captures the sticky mobile topbar's shop switcher
and its "Apply to all shops" hint appear to straddle the topbar's bottom border at 390px. `fullPage`
screenshots routinely mis-place `sticky`/absolute elements, and the 375px overflow measurement is clean,
so this may be a capture artefact rather than a defect — it needs a live browser, which is what the gate
is for. Recorded, not silently dropped.

### The repo spec's result, reported exactly as measured

`10 passed / 3 failed`, then `11 passed / 2 failed` on a re-run with the spec's own
`NEXT_PUBLIC_API_URL` supplied so its `route()` stubs intercept. Exact command as run (password bound
by name from `.env`, never echoed):

```
cd frontend && PLAYWRIGHT_BASE_URL=http://app.jtoye.local \
  E2E_VENDOR_USERNAME=admin-user E2E_VENDOR_PASSWORD="$KC_SEED_USER_PASSWORD" \
  npx playwright test --project=mobile e2e/dashboard-mobile.spec.ts
```

**This is neither a DEF-5 failure nor an environment fault, and the plan's criteria say so explicitly.**
Every failure is at line 268 —
`expect(page.getByTestId("mobile-tab-bar")).toBeVisible()` — which runs **after** `vendorLogin` has
already succeeded in `beforeEach`; a login failure would have thrown there instead. So all 13 tests
performed a real Keycloak login through the ingress, on both runs. The locator is not `.first()`, and
during an App Router transition two shells are briefly mounted, so it resolves 2 elements (the first
measuring `hidden`). It is **flaky** — different routes failed on each run — and it does **not** reproduce
in the unstubbed journey, which measured exactly 1 visible tab bar on the same build through the same
ingress. Pre-existing, unrelated to this plan's change, outside its file list: deferred, not fixed
(SCOPE BOUNDARY).

## Deviations from plan

### Mechanism changed for safety (Incremental Betterment)

**1. The live realm was updated ADDITIVELY via the Keycloak admin API, not by
`kc.sh import --override true`.** `--override` replaces the **entire realm**, discarding any realm state
not in the file (JIT-provisioned users, sessions) and requiring a restart mid-rehearsal — for a change
that is one additive attribute on one client. CLAUDE.md's Incremental Betterment Doctrine directs the
additive path. A read-modify-write PUT of only `redirectUris` was used, with the array recorded before
and after, and a **control on the client secret**: a `client_credentials` grant returned HTTP 200 both
before and after, proving the partial PUT did not clobber the secret (the real risk, since a GET can mask
it as asterisks). The gitignored `realm-export.json` was still re-rendered through the compose sidecar so
a future fresh import carries the URI.

### Unsatisfiable / already-failing acceptance criteria, replaced with strictly stronger forms

**2. `git diff <file> | grep -c '^-'` returns 1 — WRONG, it returns 2.** `git diff`'s own
`--- a/<path>` header begins with a hyphen, so a one-line modification scores **2**. Measured: 2.
This is a recurrence of the exact trap 26-06 recorded. Replaced with `git diff --numstat` deletions:
`1  1  infra/keycloak/realm-export.template.json` — one insertion, one deletion, which *does*
distinguish a one-line edit from a two-line removal.

**3. `list_connections … | grep -ci 'stomp' >= 1` is UNSATISFIABLE** on RabbitMQ 3.12 (AMQP readers
only). Replaced with the plugin CLI's `auth_login` column and the management API's `user` field, plus a
non-vacuity control and a fixture proving the guest predicate can fire. See Task 2.

**4. "its `peer_host` is on the minikube bridge subnet" CANNOT HOLD on a healthy run** — double NAT, the
same finding 26-07 made for Postgres. Replaced with elimination + millisecond correlation.

**5. `grep '^<' "$D" | grep -c 'value: "frontend"'` is VACUOUS.** kustomize emits unquoted scalars, so
the *quoted* string never appears in a render and the criterion scores 0 for free. The meaningful form is
the unquoted `value: frontend`, which scores **2** (one per target). Asserted in the stronger form:
total `<` lines = 2, both are `value: frontend`, and there are no others.

**6. "the spec … PASSES" was not met, and the classification is stated rather than the criterion
quietly dropped.** See above: a pre-existing flaky strict-mode assertion, downstream of 13/13 successful
real logins.

### Findings recorded, not fixed

**7. [Rule 4] The STOMP relay rejects `/topic/kitchen/{tenantId}/{shopId}`.** Production-affecting.
Surfaced for a decision; §7 A3 + a deferred item with a must-fail-first acceptance test.

**8. [SCOPE BOUNDARY] `dashboard-mobile.spec.ts:268` strict-mode flake.** Deferred.

**9. PIT-4b — the host image ID is NOT the in-cluster image ID for the same build.** Measured for all
four tags (`frontend:local` is `3286c715…` on the host and `def4382b…` in the node, same
`CreatedAt 20:10:19 UTC`). `minikube image load` re-imports and the node's daemon recomputes the ID.
§11's header records the host side; a reader comparing a pod's `imageID` to it would find a mismatch and
reasonably conclude the evidence came from a different image. Recorded as a §7 rule: say which side a
digest came from; the `CreatedAt` minute is what answers PIT-4.

## Evidence integrity

The `localhost:9090`-inside-§11's-fences invariant was **re-measured, and re-falsified before being
trusted**: **0** over 531 captured-output lines (up from 278). A fence carrying the forbidden string
injected **inside** §11 takes the count 0 → **1**; the same fence appended at end-of-file leaves it at 0
— which is the awk scoping working, not the check going blind. My first falsification attempt was the
end-of-file one and **it returned 0**, i.e. the probe was wrong, not the check; it was redone inside §11.
Restoration was by `cp` from a scratchpad copy and verified byte-identical with `cmp`; **`git checkout --`
was never used on an uncommitted file** (26-04's recorded process incident).

The live realm's `redirectUris` array is recorded **de-fenced** because it legitimately contains a
pre-existing loopback entry on the core-java port; fencing it would make the document fail its own check
on a string that predates the phase. Content verbatim and complete, fence omitted, reason stated in the
document.

Secret sweep across both edited documents, literal **and** base64: `stomp-passcode` 0,
`KC_SEED_USER_PASSWORD` 0/0, `KEYCLOAK_CLIENT_SECRET` 0/0, `RABBITMQ_PASSWORD` 0/0,
`DB_BACKUP_PASSWORD` 0/0, `NEXTAUTH_SECRET` 0/0, `MINIO_ROOT_PASSWORD` 0/0; pasted-JWT prefix 0 in
both. The seed password is referenced only as the `.env` key **name** and was never echoed.

The same sweep run over **this document** scores 1 for the pasted-JWT prefix, and that hit is this
paragraph's own check name — the prose-vs-grep trap this phase has now hit in every wave: a rule that
must name the string it forbids cannot score 0. Classified, not a leak; the check name is therefore
written out in words above rather than as the literal.

## Gate status

All five static gates exit 0 (`check-no-plaintext-secrets`, `check-connection-math`,
`check-env-contract`, `check-render-invariants`, `render-golden`) and `docs-freshness` is green.
`docs/metrics.json` **untouched** — 26-06 is its single writer and this plan adds 0 counted invocations
(YAML, JSON and Markdown only; the journey ran as a standalone script deliberately so it would not add a
counted Playwright `test()` block). The employer AKS context `sipbihs2aks` was never targeted;
`--context jtoye` was explicit on every call.

## State left behind

The cluster is **up** and the compose app containers are **down** — the required XOR state, verified by
both guards after the run (`cluster XOR satisfied — 11 live pods, all inside jtoye-local or the system
set`; `compose XOR k8s satisfied`). Namespace list unchanged (6, no scratch). Ingress smoke still
200/200. No scratch pod or namespace was created at any point.

Residue introduced by this plan, all deliberate:
- the `core-api` client in the live jtoye-dev realm carries `http://app.jtoye.local/*` (persisted in
  Keycloak's Postgres; committed in the template so a re-render/re-import keeps it);
- order `ORD-00000000-20260712-23C4097F` (`afe90b6d-…`) moved `CONFIRMED → PREPARING` in the shared dev
  DB. **Forward-only and not reverted** — the state machine has no backward transition. A second
  `CONFIRMED` order (`ORD-00000000-20260715-0D1B0653`) was deliberately left untouched as a control;
- the frontend pod was rolled once (new pod, restartCount 0) to pick up the config-injected client id;
- gitignored Playwright artefacts under `frontend/test-results/` and `frontend/playwright-report/`.

Pod restart counts are **4/3/2** — core-java 4 and edge-go 3 are §7 A2's explained history (the
`minikube profile list` idempotence defect bounced them between 26-07 and this plan), and frontend is 0
because it was recreated here.

## Requirements

**INFRA-01 and INFRA-02 are deliberately NOT marked complete** (anti-false-green, and the plan says
26-09 owns it). A3 is now an input to that decision: the KDS realtime path is proven broken in relay
mode, which is what every k8s environment runs.

## Self-Check: PASSED

Files:
- `FOUND: k8s/LOCAL.md` (§11 L6/L7 filled; §7 A3 + PIT-4b added; sign-off marks the human gate OPEN)
- `FOUND: infra/keycloak/realm-export.template.json` (1 additive redirect URI)
- `FOUND: k8s/base/configmap.yaml` (`keycloak.client-id`)
- `FOUND: k8s/base/frontend-deployment.yaml` (`configMapKeyRef`, no inline `value`)
- `FOUND: k8s/local/configmap-patch.yaml` / `k8s/staging/configmap-patch.yaml` / `k8s/production/configmap-patch.yaml`
- `FOUND: k8s/goldens/staging.yaml` / `k8s/goldens/production.yaml` (1469 lines each)
- `FOUND: .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md` (+146 lines, 0 deletions)
- `FOUND: .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-08-SUMMARY.md`
- `test ! -f .planning/deferred-items.md` — passes

Commits: `5ae3051`, `82e899f`, `9a7327b` — all verified present. No file deletions in any of the three.
