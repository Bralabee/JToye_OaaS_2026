---
phase: 37-real-world-operations-readiness
plan: 24
subsystem: security
tags: [rate-limiting, x-forwarded-for, trusted-proxies, client-ip, bucket4j, spring-security, ipaddressmatcher, kustomize, render-invariants, d-19, uxt-006, rwo-006]

requires:
  - phase: 37-23
    provides: "the 37-A gate: origin/main (#916) merged into phase-37-ops-readiness, so this plan starts on the current tree"
  - phase: 31-05
    provides: "DsarIntakeRateLimiter, the second tenant-less caller of the client-IP resolution"
  - phase: issue-88
    provides: "the IP-keyed public bucket (rl:public:) in RateLimitInterceptor and the original ClientIpResolver"
provides:
  - "ClientIpResolver as a @Component bound to jtoye.security.trusted-proxies (TRUSTED_PROXY_CIDRS, default empty = trust none): X-Forwarded-For is read only when the socket peer is a trusted proxy, walking the hops from the right and skipping trusted ones; a malformed hop or an all-trusted chain answers with the socket peer; literals only (no DNS); IPv4-mapped folded to the dotted quad; IPv6 rendered RFC 5952; 0.0.0.0/0 and ::/0 refused at startup naming the key"
  - "RateLimitInterceptor and DsarIntakeRateLimiter key on the bean by constructor; the static first-hop method is gone; the DSAR javadoc no longer presents first-hop trust as intentional"
  - "The value declared in application.yml, compose (empty), .env.example, k8s/base/configmap.yaml (\"\" + the TRUSTED_PROXY_CIDRS env wiring) and every overlay patch: local 10.244.0.0/16 (minikube default), staging/production \"\" pending the operator"
  - "INV-12 in k8s/scripts/check-render-invariants.sh: the key must be present in every render; every entry a private CIDR (RFC 1918 or fc00::/7), /8 or narrower, never /0; empty WARNs on staging/production and never fails; a self-test judges 16 dangerous and 9 private shapes before any render"
  - "PublicRateLimitXffSpoofIntegrationTest (UXT-006 through the real interceptor, with a no-header control) and ClientIpResolverTest rewritten around the plan's case table (29 cases)"
affects: [37-28, 37-D-gate, 37-E, k8s-staging-rollout, k8s-production-rollout, phase-29]

actuals:
  tokens: 51739
  tasks: 3
  commits: 6
plan_head_before: 6c7bcf36184640ba8719d65089272e69a8645075
plan_head_after: 806bee136c14cb4b13676ac5af327c58ebcbec99

tech-stack:
  added: []
  patterns:
    - "Client identity is the socket peer unless the peer is a config-declared trusted proxy; a header is never a fallback, because any header-derived fallback is a value the client can write"
    - "Addresses from the wire are parsed with InetAddress.ofLiteral, so a rate-limit check can never be turned into a DNS lookup the attacker chose"
    - "A render invariant that judges values carries its own self-test over positive and negative controls, so a blind judge is a parse error rather than a pass"
    - "A test that needs a per-request client identity sets the socket peer (setRemoteAddr), never X-Forwarded-For"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitXffSpoofIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-24-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-24-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-24-red-task2-config.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java
    - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeRateLimiter.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java
    - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/ (eight DSAR ITs: identity by socket peer; GdprControllerTest and ten other @WebMvcTest slices import the bean)
    - docker-compose.full-stack.yml
    - .env.example
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/scripts/check-render-invariants.sh
    - scripts/gates/object-store-residue-allowlist.conf
    - k8s/LOCAL.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/phases/37-real-world-operations-readiness/37-USER-SETUP.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "37-24: a malformed hop or an all-trusted chain resolves to the SOCKET PEER, not to the last trusted hop the plan's wording allowed: a client behind the proxy can spell in-range addresses, so any header-derived fallback would let it choose its bucket again"
  - "37-24: CIDR containment is Spring Security's IpAddressMatcher (the import compiles on spring-security-web 7.1.1); every string handed to it is first proven an IP literal with InetAddress.ofLiteral, because the matcher's own String API resolves hostnames"
  - "37-24: IPv6 results are rendered RFC 5952 (compact, lower-case) so one IPv6 client keys one bucket whatever spelling the proxy wrote; IPv4-mapped literals fold to the dotted quad"
  - "37-24: INV-12 accepts only RFC 1918 and fc00::/7 and nothing shorter than /8 (fc00::/7 itself is refused as too wide); the bean refuses only /0 — render time is stricter than runtime because a wide-but-private range is a deployment mistake the gate can see and the application cannot"
  - "37-24: k8s/local declares the minikube default pod CIDR 10.244.0.0/16 without measuring it (the jtoye profile had no container); recorded in the patch and in deferred-items, to be narrowed when the cluster is up"
  - "37-24: ClientIpResolverTest's three first-hop assertions were replaced in Task 1's GREEN (the :test source set must compile once the static method is deleted) by their trust-none inverse, and the case table was added in Task 2; replaced, not weakened"

patterns-established:
  - "Trusted-proxy identity: resolve(request) is the one answer to 'what is this client' for every limiter and cap"
  - "A gate that reads a list value splits it, judges each entry, and parse-fails on a key that is absent or a splitter that yields nothing"

requirements-completed: [RWO-006]

coverage:
  - id: D1
    description: "A rotated X-Forwarded-For from an untrusted peer lands every request in the same public bucket and is 429 once the configured capacity is spent (UXT-006), proven through the real RateLimitInterceptor with a no-header control at the same count"
    requirement: "RWO-006"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitXffSpoofIntegrationTest.java#rotatingXffFromAnUntrustedPeerSharesOneBucket_andIs429OnceTheBurstIsSpent (RED: 40 allowed; GREEN: 7 allowed, first 429 at index 7) + #control_noXffFromAnUntrustedPeerIs429AtTheSameCount"
        status: pass
    human_judgment: false
  - id: D2
    description: "ClientIpResolver is a bean configured by jtoye.security.trusted-proxies; RateLimitInterceptor and DsarIntakeRateLimiter use it; the static method is gone; server.forward-headers-strategy stays unset"
    requirement: "RWO-006"
    verification:
      - kind: other
        ref: "rg -uu -n 'ClientIpResolver\\.resolveClientIp' core-java/src/main/java -> rc 1 (fail direction: the same pattern on HEAD's pre-GREEN RateLimitInterceptor.java -> rc 0, line 322); rg -uu 'forward-headers' application*.yml -> only the pre-existing DSAR comment"
        status: pass
      - kind: unit
        ref: "./gradlew :core-java:test --tests 'uk.jtoye.core.security.RateLimitInterceptor*' --tests 'uk.jtoye.core.gdpr.*' --tests 'uk.jtoye.core.boot4.ConfigKeyContractTest' --tests '*Controller*Test' -> 22 classes, 149 tests, 0 failures; full :core-java:test 1705 / 0 failures / 1 skipped"
        status: pass
    human_judgment: false
  - id: D3
    description: "The right-most-untrusted walk, trusted-hop skipping, malformed and hostname hops, all-trusted chains, IPv6 and IPv4-mapped peers and hops, canonical spellings, and the /0 refusal naming the key"
    requirement: "RWO-006"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java (29 cases; RED 11 failed, GREEN 29/29; break arm: walk left-to-right -> 5 right-most cases red, restore by md5, clean 29/0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every runtime declares its trusted proxies: application.yml, compose (empty), .env.example, k8s base key + TRUSTED_PROXY_CIDRS env wiring, each overlay patch; goldens regenerated by script only"
    requirement: "RWO-006"
    verification:
      - kind: other
        ref: "bash k8s/scripts/render-golden.sh --write then bash k8s/scripts/render-golden.sh -> rc 0 (12 added lines: the key and its env wiring); bash k8s/scripts/check-env-contract.sh -> PASS 72 injected / 170 read; bash scripts/check-env-example-contract.sh -> PASS 19; bash scripts/check-strict-scoping-default.sh -> PASS"
        status: pass
    human_judgment: false
  - id: D5
    description: "INV-12 refuses a rendered overlay whose trusted-proxies carries /0, a prefix shorter than /8 or a public range, and warns (never fails) while staging or production is empty"
    requirement: "RWO-006"
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-render-invariants.sh: clean PASS rc 0 with WARN on staging and production; arm A staging 0.0.0.0/0 -> FAIL rc 1; arm B staging 8.8.8.0/24 -> FAIL rc 1; arm C production '10.0.0.0/7, fd00::/8' -> FAIL rc 1 on the /7; restores verified by md5; closing clean PASS rc 0; the inv12_selftest refuses 16 shapes and accepts 9 on every run"
        status: pass
    human_judgment: false
  - id: D6
    description: "The DSAR intake and public rate-limit integration tests keep their per-request identity under D-19 by setting the socket peer"
    verification:
      - kind: integration
        ref: "./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.gdpr.*' --tests 'uk.jtoye.core.security.PublicRateLimit*' -> 13 classes, 92 tests, 0 failures"
        status: pass
    human_judgment: false

duration: 28 min
completed: 2026-10-11
status: complete
---

# Phase 37 Plan 24: Trusted-proxy client IP Summary

**The public rate limit, and the DSAR intake's bucket, now key on an identity the client cannot forge. `ClientIpResolver` is a bean bound to `jtoye.security.trusted-proxies` (default empty: trust none); it reads `X-Forwarded-For` only when the socket peer is a declared proxy, walks the hops from the right skipping trusted ones, parses literals only, folds IPv4-mapped and canonicalises IPv6, falls back to the socket peer on anything malformed, and refuses `/0` at startup. Forty rotated headers from one untrusted peer used to be forty buckets (UXT-006, the #88 regression); through the real interceptor they are now one bucket, 429 from the eighth request, exactly where the no-header control lands. Every runtime declares its range, the k8s render gate (INV-12) refuses `/0`, anything shorter than `/8` and any public range, and staging/production carry the operator step.**

## Performance

- **Duration:** 28 min (plan start 2026-10-11T20:29:08Z; code commits through 20:54Z; SUMMARY after)
- **Started:** 2026-10-11T20:29:08Z
- **Completed:** 2026-10-11
- **Tasks:** 3 of 3; Tasks 1 and 2 RED (`test(37-24)`, RED_EVIDENCE_OK) then GREEN (`feat(37-24)`); no REFACTOR commit was needed
- **Files modified:** 44 across the six code commits (plus this SUMMARY, 37-USER-SETUP.md and deferred-items.md)

## Accomplishments

- **Task 1 (tracer).** `PublicRateLimitXffSpoofIntegrationTest` drives the real `RateLimitInterceptor` (dev profile, Testcontainers Postgres + Redis, the `PublicRateLimitIntegrationTest` pattern) from one untrusted peer (`203.0.113.9`) with 40 different `X-Forwarded-For` values at capacity 5 + burst 2.
  - **RED** (`5c4bb8f9`, `evidence/37-24-red-task1.json`, JUnit, RED_EVIDENCE_OK): `expected: 7L but was: 40L` — every rotated header minted a fresh bucket. The control (second untrusted peer, no header) passed in the same run: 7 allowed, first 429 at index 7, so the limiter was live and the failure was about the header.
  - **GREEN** (`fb91684e`): `ClientIpResolver` is a `@Component` taking `@Value("${jtoye.security.trusted-proxies:}") List<String>`; `RateLimitInterceptor` and `DsarIntakeRateLimiter` take it by constructor and call `resolve(request)`; the static `resolveClientIp` is deleted; `application.yml` declares `trusted-proxies: ${TRUSTED_PROXY_CIDRS:}` with the trust-none default and the `/0` refusal stated; the DSAR javadoc paragraph that presented first-hop trust as intentional now states D-19. The IT is 2/2 on both arms.
  - **Tracer gate** (interactive, `end-of-phase`, automated-only `<verify>`): the IT re-ran on the committed tree, BUILD SUCCESSFUL, 2/2; expansion continued.
- **Task 2.** `ClientIpResolverTest` rewritten around the plan's case table (29 cases in four groups).
  - **RED** (`ac75c800`; `evidence/37-24-red-task2.json` on the walk report, `37-24-red-task2-config.json` on the configuration report, both RED_EVIDENCE_OK): 11 of 28 failed on their planned assertions — trusted hops not skipped (`10.9.9.9` for `1.2.3.4`), a malformed or hostname hop returned as the client, an all-trusted chain returning a hop, empty hops not skipped, mapped IPv4 not folded, `0.0.0.0/0` and `::/0` accepted. The mapped-peer and IPv6-CIDR cases were already green (java.net folds a mapped literal to `Inet4Address`), recorded as such.
  - **GREEN** (`03205b37`): the right-to-left walk; `InetAddress.ofLiteral` on every range, peer and hop (a hostname hop stops the walk and is never resolved); the socket peer as the only fallback; dotted-quad folding; an RFC 5952 canonicaliser (`canonical`) so `2001:0DB8:0000:0000:0000:0000:0000:0005` and `2001:db8::5` key the same; refusals naming `jtoye.security.trusted-proxies` for `/0`, an out-of-range prefix, a non-numeric prefix and a non-literal entry. 29/29.
  - **Break arm:** the walk reversed to left-to-right reds 5 right-most cases with the attacker's leftmost `1.2.3.4` winning (`expected "198.51.100.7" but was "1.2.3.4"`); restore from a scratch copy verified by md5 (`a00bc43f…` before and after); clean 29/0.
- **Task 3.** Compose sets `TRUSTED_PROXY_CIDRS` empty with the reason (the browser reaches core directly; all host clients share the bridge gateway's bucket, which is why the public limit is widened there); `.env.example` documents it; `k8s/base/configmap.yaml` declares `security.trusted-proxies: ""` and `core-java-deployment.yaml` wires it as `TRUSTED_PROXY_CIDRS` (a key with no `configMapKeyRef` is the silently-ignored-value class); `k8s/local` declares `10.244.0.0/16`; staging and production stay `""` with the user_setup step named in the patch. `check-render-invariants.sh` gains INV-12 on every target with `tp_entry_verdict` (pure bash integer math, no external tool) and `inv12_selftest`. Goldens regenerated by `render-golden.sh --write` (12 added lines). `check-env-contract.sh` PASS (72 injected / 170 read).
  - **Break arms recorded:** staging `0.0.0.0/0` → `FAIL [k8s/staging] INV-12: … is the whole IPv4 address space (/0)` rc 1; staging `8.8.8.0/24` → `… is not inside a private range (RFC 1918 …)` rc 1; production `10.0.0.0/7, fd00::/8` → `… has a prefix shorter than /8` rc 1 on the first entry only; both patches restored by md5; closing clean PASS rc 0 with the two WARN lines.
  - **SSR finding (recorded, no change):** `rg -uu -n -i 'x-forwarded-for|forwarded|x-real-ip' frontend/lib/storefront-server.ts` → rc 1 (positive control `fetch\(` → rc 0, line 95): the storefront's server-side requests do not forward the browser's address, so SSR traffic keys the public bucket on the frontend container's socket address, as it did before this plan.
  - **Local range not measured:** `minikube profile list` → "No such container: jtoye"; `kubectl --context jtoye` → context does not exist. The minikube default `10.244.0.0/16` is declared and the measurement step is in the patch comment and deferred-items.
- **Regression:** full `:core-java:test` 1705 tests, 0 failures, 1 skipped (1683 before this plan + 22 new); `:core-java:integrationTest` over `uk.jtoye.core.gdpr.*` and `security.PublicRateLimit*`: 13 classes, 92 tests, 0 failures.

## Task Commits

1. **Task 1: a rotated X-Forwarded-For from an untrusted peer no longer mints a new bucket** — `5c4bb8f9` (test, RED), `fb91684e` (feat, GREEN)
2. **Task 2: the right-most-untrusted rule, IPv6 and malformed hops** — `ac75c800` (test, RED), `03205b37` (feat, GREEN)
3. **Follow-up to Tasks 1–2 (Rule 1):** `9ee2cdd5` (test: nine ITs set the socket peer instead of a header)
4. **Task 3: every runtime declares its trusted proxies, and the render invariant refuses dangerous ranges** — `806bee13` (feat)

**Plan metadata:** this SUMMARY, `37-USER-SETUP.md` and `deferred-items.md` go in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's.

`commits: 6` is `git rev-list --count 6c7bcf36..806bee13`.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `5c4bb8f9` test(37-24), RED_EVIDENCE_OK (`37-24-red-task1.json`, target_test_failed, 2 tests / 1 failed) | `fb91684e` feat(37-24) | none needed |
| 2 | `ac75c800` test(37-24), RED_EVIDENCE_OK (`37-24-red-task2.json` 9 / 6; `37-24-red-task2-config.json` 5 / 3) | `03205b37` feat(37-24) | none needed |

Semantic assessment for both: the target executed and failed on its planned assertion for the intended reason; no load, fixture or syntax fault; the already-green siblings are named in each record.

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java`: the bean (trust list, walk, literal parsing, canonical spellings, refusals)
- `core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java`: constructor takes the bean; `clientIpResolver.resolve(request)` keys the public bucket
- `core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeRateLimiter.java`: constructor takes the bean; the S1 javadoc states D-19
- `core-java/src/main/resources/application.yml`: `jtoye.security.trusted-proxies: ${TRUSTED_PROXY_CIDRS:}` (15 lines incl. the comment)
- `core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitXffSpoofIntegrationTest.java`: UXT-006 through the interceptor + control
- `core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java`: the 29-case table
- `core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java`, `RateLimitInterceptorFailOpenTest.java`: construct the bean; public tests key on the socket peer and assert a spoofed header is inert
- `core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java` and eight `gdpr/*IntegrationTest.java`: identity by `setRemoteAddr`
- twelve `@WebMvcTest` slices (`GdprControllerTest`, `PublicStorefrontController{,MyOrders,Idor}Test`, `OrderController{ShopFilter,Pagination}Test`, `MarketingControllerShopFilterTest` x2, `ProductControllerShopFilterTest`, `SyncControllerIntegrationTest`, `PaymentControllerTest`, `RefundControllerIntegrationTest`): `@Import` the bean
- `docker-compose.full-stack.yml`, `.env.example`: `TRUSTED_PROXY_CIDRS` (empty) with the reason
- `k8s/base/configmap.yaml`, `k8s/base/core-java-deployment.yaml`: the key and its env wiring
- `k8s/{local,staging,production}/configmap-patch.yaml`: the per-environment value
- `k8s/goldens/{staging,production}.yaml`: regenerated by script
- `k8s/scripts/check-render-invariants.sh`: INV-12, `tp_entry_verdict`, `inv12_selftest`, the header entry and summary lines
- `scripts/gates/object-store-residue-allowlist.conf`: two `.env.example` pins moved +10
- `k8s/LOCAL.md`, `.planning/codebase/INTEGRATIONS.md`: three `application.yml` citations re-pointed (+15)
- `evidence/37-24-red-task1.json`, `37-24-red-task2.json`, `37-24-red-task2-config.json`
- `37-USER-SETUP.md`: the staging/production operator step (table row, checklist item, verification 3 and 4)
- `deferred-items.md`: the citation gate's pyshim VOID (pre-existing) and the unmeasured local CIDR

## Decisions Made

See `key-decisions`. The one that departs from the plan's letter: on a malformed hop the plan said "the last address known good (the peer or the previous trusted hop's predecessor per the cases)"; the cases themselves resolve to the peer, and "the last trusted hop" would be a value the client can spell (`10.0.0.1, 10.0.0.2, …` behind a trusted proxy mints buckets again), so the resolver falls back to the socket peer in both the malformed and the all-trusted case, and the test says why.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Twelve `@WebMvcTest` slices could not load their context**
- **Found during:** Task 1 GREEN verify (`GdprControllerTest` 5 failures: `No qualifying bean of type 'uk.jtoye.core.security.ClientIpResolver'`)
- **Issue:** the slice filter includes `RateLimitInterceptor` (a `HandlerInterceptor`), which now needs the resolver bean, and a plain `@Component` is outside the slice.
- **Fix:** each slice imports the real bean (`@Import({GlobalExceptionHandler.class, uk.jtoye.core.security.ClientIpResolver.class})`, or a new `@Import` where none existed), with a one-line comment naming the reason.
- **Verification:** the 22-class unit run 149/0 and the full suite 1705/0.
- **Committed in:** `fb91684e`

**2. [Rule 3 - Blocking] Three doc citations into `application.yml` moved**
- **Found during:** Task 1 GREEN (`scripts/check-doc-citations.sh` 3 violations)
- **Issue:** the 15-line insertion at `jtoye.security` shifted every later line; `k8s/LOCAL.md:609`, `:1634` (`:541` → `:556`) and `.planning/codebase/INTEGRATIONS.md:14` (`:837-842` → `:854-858`) pointed at the wrong lines. The HANDOFF predicted this class.
- **Fix:** re-pointed to the measured lines; gate PASS 29 verified, 0 violations.
- **Committed in:** `fb91684e`

**3. [Rule 1 - Bug] Nine integration tests minted identity through the header**
- **Found during:** Task 2 close-out survey (`rg -uu 'X-Forwarded-For' core-java/src/test/java`)
- **Issue:** eight DSAR ITs and `PublicRateLimitIntegrationTest` varied the client per request by writing `X-Forwarded-For`; under D-19 the header is inert from MockMvc's loopback peer, so they would have shared one in-process DSAR IP bucket (5/hour) and throttled each other.
- **Fix:** each site sets `setRemoteAddr` (a `with(req -> …)` post-processor on MockMvc, the request itself in the direct-interceptor test); the `DsarIntakeIntegrationTest` javadoc explains the change.
- **Verification:** `integrationTest` over `gdpr.*` + `security.PublicRateLimit*`: 92/0.
- **Committed in:** `9ee2cdd5`

**4. [Rule 3 - Blocking] Two object-store-residue allowlist pins into `.env.example` moved**
- **Found during:** Task 3 gates (`scripts/check-no-object-store-residue.sh` rc 1: `.env.example:530`, `:225`)
- **Issue:** the 10-line `TRUSTED_PROXY_CIDRS` block shifted the two allowlisted historical lines (`:215` → `:225`, `:520` → `:530`).
- **Fix:** pins moved; gate PASS, 51 live entries.
- **Committed in:** `806bee13`

**5. [Rule 2 - Missing critical] `core-java-deployment.yaml` env wiring (a file the plan did not list)**
- **Found during:** Task 3
- **Issue:** the plan listed the configmap key "and the core-java env wiring if the contract requires it". A configmap key nothing consumes is the DEF-6 class the local patch header warns about; without `TRUSTED_PROXY_CIDRS` on the container the overlay value would never reach core.
- **Fix:** the env entry with `configMapKeyRef: security.trusted-proxies`, in the same change as the key; the goldens carry it.
- **Committed in:** `806bee13`

### Plan wording that did not hold (recorded, not relabelled)
- **"ClientIpResolverTest rewritten in Task 2."** Deleting the static method in Task 1's GREEN made the old test file uncompilable (`:test` compiles the whole source set), so its three first-hop assertions were replaced by their trust-none inverse in `fb91684e`; Task 2 added the case table. Replaced, not weakened; the Task 2 RED record names the already-green cases.
- **"Measure the minikube ingress pod CIDR when the cluster exists."** It did not exist; the default is declared and the step is recorded (deferred-items).

**Total deviations:** 5 auto-fixed (1 bug, 1 missing critical, 3 blocking), 2 plan-wording records. **Impact:** no scope creep; every fix was required for the suite to compile and run or for a gate to read the tree correctly.

## Issues Encountered

- **The RED-evidence record could not be written with Python.** `block-base-python` refused `/usr/bin/python3 script.py` and the multi-argument `-I -` stdin form (the single-argument stdin form had passed earlier). Not probed further; the records were built with `jq -n --rawfile` (the unchanged XML in `output`, mtime in UTC), which the classifier accepted (RED_EVIDENCE_OK x3).
- **`scripts/check-doc-citations.sh` prints a VOID for `docs/ops/terminal-states.yaml`** because its extractor runs a bare `python3 -c` that the machine's pyshim refuses. Pre-existing and independent of this plan (the gate still exits 0 when the other citations verify); recorded in deferred-items, not fixed here.
- `rg -h` prints ripgrep's help rather than suppressing filenames (one survey command had to be re-issued with `--no-filename`); no evidence was taken from the misfire.

## Known Stubs
None introduced.

## Threat Flags

None new. T-37-59 (XFF rotation) is mitigated by D1/D3; T-37-60 (an operator configuring /0 or a public range) by INV-12 and the startup refusal (D5, D3); T-37-61 (every client sharing the ingress bucket while staging/production are unset) stays ACCEPTED with the WARN and the user_setup step; T-37-SC held: nothing was installed (the `IpAddressMatcher` already ships in spring-security-web 7.1.1, `InetAddress.ofLiteral` is JDK 22+ on the JDK 25 toolchain).

## User Setup Required

**External configuration is required before a staging or production rollout.** See [37-USER-SETUP.md](./37-USER-SETUP.md): declare each AKS cluster's ingress-nginx pod CIDR in `security.trusted-proxies` (a private range, never `/0`); until then INV-12 WARNs and every client behind the ingress shares one public bucket. None for the dev stack.

## Next Phase Readiness

- **37-28 (cash-order per-IP cap, D-18/D-27)** keys its HMAC on `ClientIpResolver.resolve(request)`; the bean is in place.
- **37-D gate:** the compose runtime (built from `6a825865`) does NOT yet carry this change; the gate's rebuild and the live probe in 37-USER-SETUP.md verification 4 (40 rotated headers → capacity `200`s then `429`) prove it on the delivered runtime. Full `integrationTest` was not run here (the plan's filtered sets and the full unit suite were); the gate runs it.
- **UXT-039 (edge per-tenant limiter)** is 37-D.2, a separate plan.
- **Requirements:** RWO-006 is ready to mark (no sibling plan declares it).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-11*

## Self-Check: PASSED

- FOUND: `PublicRateLimitXffSpoofIntegrationTest.java` (contains "X-Forwarded-For"), `ClientIpResolver.java` (contains "trusted-proxies"), `ClientIpResolverTest.java`, `evidence/37-24-red-task1.json`, `37-24-red-task2.json`, `37-24-red-task2-config.json`, `check-render-invariants.sh`, `k8s/goldens/staging.yaml`; `RateLimitInterceptor.java` contains `clientIpResolver.resolve`
- FOUND commits (ancestors of HEAD): `5c4bb8f9`, `fb91684e`, `ac75c800`, `03205b37`, `9ee2cdd5`, `806bee13`; `git rev-list --count 6c7bcf36..806bee13` = 6; `gsd_run check evaluation-scope --plan 37-24 --commits-only` → `status: resolved`, 6 commits
- TDD gate: 3 `test(37-24)` and 3 `feat(37-24)` commits present; RED precedes GREEN in both tasks
