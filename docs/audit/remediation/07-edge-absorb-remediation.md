# 07 — Edge-Go Absorption Remediation

**Pair**: Gateway Migration Engineer (specialist) + Migration Risk Reviewer (assistant)
**Date**: 2026-04-27
**Source audit**: `docs/audit/sources/07-edge-go.md`
**Council synthesis**: `docs/audit/COUNCIL-AUDIT-2026-04-27.md` §D, §"What I would NOT do"
**Scope**: confirm or reject the "delete edge-go and absorb into Core" verdict; produce a wave-by-wave migration plan with explicit rollbacks; map the WhatsApp orchestrator to Spring; inventory deletions; provide Option B (Kong) as a fallback.

---

## Principles (governing the whole plan)

1. **Honour load-bearing facts before honouring the audit's verdict.** The audit assumed edge-go carries production traffic. It does not (see §1 below). The decision changes shape accordingly: this is not a "cutover", it is a **graveyard cleanup with one orchestrator port**.
2. **Reversibility at every wave.** No wave depends on a deletion. Deletions happen only after the wave-N+1 absorber has been live and observed.
3. **Don't lose silent capabilities.** edge-go contains six behaviours (HMAC verify, JWT validate, rate limit, circuit-break, JWKS cache, single-tenant WhatsApp shop mapping). Each must be accounted for explicitly in the absorbed Spring code OR explicitly retired.
4. **Per-tenant isolation is non-negotiable.** Anything that re-introduces a single-tenant assumption (the current `WHATSAPP_DEFAULT_SHOP_ID` env var) is a regression, not a port.
5. **Idempotency is added, not preserved.** Meta retries on socket errors. The current code has no idempotency. Absorbing without adding it leaves a known data-corruption window open.
6. **Ingress topology is the rollback lever.** Whichever wave we are in, the rollback action is "swing the ingress back" — measured in seconds, not minutes.

---

## Finding 1 — The absorb decision

### Specialist proposal
Confirm. Three independent facts make this stronger than the audit:

- **No frontend caller references edge-go.** `grep -rn "edge-go\|EDGE_API_URL\|sync/batch\|/whatsapp" frontend/lib frontend/app` returns nothing (the only matches sit in a Playwright e2e comment at `frontend/e2e/stomp-relay.spec.ts:11` and a Spring integration test).
- **The production ingress doesn't route to edge-go.** `k8s/base/ingress.yaml:54-63` sends `api.jtoye.co.uk/` straight to `service/core-java:9090`. There is no path rule, host rule, or annotation that ever lands traffic on `service/edge-go:8080`.
- **The sync passthrough is broken end-to-end and nobody noticed.** edge-go forwards to `/api/v1/sync/batch` (`internal/core/client.go` SyncBatch path). Spring mounts the controller at `/sync/batch` (`SyncController.java:20,32` — `@RequestMapping("/sync")` + `@PostMapping("/batch")`). Even the Spring integration test posts to the wrong path (`SyncControllerIntegrationTest.java:51` posts `/api/v1/sync/batch` against a controller that lives at `/sync/batch`). Either MockMvc is silently 404ing in green CI or there is unseen rewrite — either way, no production traffic survives this hop today.

That means edge-go is currently a 5-replica HPA-up-to-20 deployment (`k8s/base/edge-go-deployment.yaml:10,117-118`) with a PodDisruptionBudget (`:138` `minAvailable: 3`) running and consuming resources for **zero customer requests**. The "absorb risk" the audit warned about is much smaller than feared because there is no live cutover to perform.

### Assistant deliberation
1. **"No traffic today" ≠ "no traffic ever".** Meta's webhook URL lives in Meta's admin console, not `frontend/lib`. No `WHATSAPP_APP_SECRET` in `k8s/` (verified) confirms no live tenant, but a partner could be pointed at undocumented DNS. Mitigation: `kubectl logs deployment/edge-go --since=720h | grep -c "WhatsApp order created"` before deletion — if zero, absorb has zero live impact.
2. **Language firewall.** Go-in-front-of-JVM has no *security* value (audit is right) but does give CVE-blast-radius separation. Moot here because edge-go is not in the request path; JVM is already the only public-internet code.
3. **Reversibility cost.** Deleting ~1153 LOC of Go tests is a bet that a Go edge is never needed again. Mitigation: tag `edge-go-final-v2.0.0` before purge.

### Reconciled position
**Confirm absorb.** Audit's plan stands and is lower-risk than the audit assumed because there is no live cutover.

---

## Finding 2 — WhatsApp handler port to Spring

### Specialist proposal
Create `core-java/src/main/java/uk/jtoye/core/whatsapp/` with three files: `WhatsAppController.java`, `WhatsAppOrderService.java`, `WhatsAppMessageParser.java` (port of `internal/whatsapp/parser.go`). Add `whatsapp_tenant_config` table for per-phone-number-id routing.

**(a) PermitAll the webhook; HMAC is the authn.** Append to `SecurityConfig.java:71`:

```java
.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/whatsapp").permitAll()
```

HMAC inside the controller is the only authn — matches Meta's actual model (audit §WhatsApp #2 flagged the JWT mount as nonsense since Meta sends no Bearer).

**(b) Per-tenant phone_number_id → shop mapping.** Migration `V36__whatsapp_tenant_config.sql`:

```sql
CREATE TABLE whatsapp_tenant_config (
    phone_number_id   TEXT        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    shop_id           UUID        NOT NULL REFERENCES shops(id)   ON DELETE CASCADE,
    app_secret_enc    TEXT        NOT NULL,  -- AES-GCM encrypted via Spring's TextEncryptor
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE whatsapp_tenant_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE whatsapp_tenant_config FORCE ROW LEVEL SECURITY;
CREATE POLICY whatsapp_tenant_select ON whatsapp_tenant_config FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
-- The webhook path INSERTS via service-role bypass after HMAC verify;
-- writes from app code go through the tenant policy.
CREATE POLICY whatsapp_tenant_modify ON whatsapp_tenant_config FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
CREATE INDEX idx_whatsapp_tenant ON whatsapp_tenant_config(tenant_id);
```

Handler resolves `phone_number_id` (from `entry[].changes[].value.metadata.phone_number_id` — DTO addition; Go parser ignores it) → service-role lookup → decrypt `app_secret_enc` → verify HMAC → `TenantContext.set(tenantId)` → create order under scope.

**(c) Idempotency.** `processed_whatsapp_messages(message_id PRIMARY KEY, tenant_id UUID, processed_at TIMESTAMPTZ DEFAULT now())`. `INSERT ... ON CONFLICT DO NOTHING`; `affectedRows == 0` → return 200. Same pattern as council pre-prod blocker #3 (Stripe).

**(d) Tests.** Port `parser_test.go` (151 LOC, 11 cases) to `WhatsAppMessageParserTest`. Drop Go gateway plumbing tests (no Spring equivalent). Keep one Newman collection (`docs/postman/whatsapp-webhook.postman_collection.json`) for staging smoke.

Controller sketch (`core-java/src/main/java/uk/jtoye/core/whatsapp/WhatsAppController.java`):

```java
@RestController
@RequestMapping("/api/v1/webhooks/whatsapp")
@RequiredArgsConstructor
@Slf4j
public class WhatsAppController {

    private final WhatsAppOrderService orderService;
    private final WhatsAppTenantConfigRepository configRepo;
    private final TextEncryptor secretEncryptor;
    private final ProcessedWhatsAppMessageRepository idempotencyRepo;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] rawBody) {

        WhatsAppWebhookPayload payload = parsePayload(rawBody);
        String phoneNumberId = payload.firstPhoneNumberId();
        if (phoneNumberId == null) {
            log.warn("WhatsApp webhook missing phone_number_id");
            return ResponseEntity.ok().build();   // never echo back, never retry-loop
        }

        WhatsAppTenantConfig cfg = configRepo.findById(phoneNumberId).orElse(null);
        if (cfg == null) {
            log.warn("Unknown WhatsApp phone_number_id={}", phoneNumberId);
            return ResponseEntity.ok().build();
        }

        String secret = secretEncryptor.decrypt(cfg.getAppSecretEnc());
        if (!HmacVerifier.verifySha256(rawBody, signature, secret)) {
            log.warn("Invalid HMAC for phone_number_id={}", phoneNumberId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // HMAC has authenticated the tenant; safe to scope from here.
        TenantContext.set(cfg.getTenantId());
        try {
            String messageId = payload.firstMessageId();
            if (messageId != null && !idempotencyRepo.recordIfNew(messageId, cfg.getTenantId())) {
                log.info("WhatsApp message {} already processed; skipping", messageId);
                return ResponseEntity.ok().build();
            }
            orderService.handle(payload, cfg);
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }
}
```

`HmacVerifier.verifySha256` is a 12-LOC port of `verifyWhatsAppSignature` (`edge-go/cmd/edge/main.go:216-228`) via `javax.crypto.Mac` + `MessageDigest.isEqual`. `WhatsAppMessageParser` ports the regex (`internal/whatsapp/parser.go:48`) verbatim. Product-resolution loop (single-hit or exact case-insensitive title — `handlers.go:259-277`) ports unchanged.

### Assistant deliberation
1. **HMAC body must be raw bytes, not Jackson-deserialised JSON.** Specialist's `byte[] rawBody` is correct; verify no filter (gzip, encoding normalisation) mutates the body. Add integration test that signs externally then POSTs through full filter chain.
2. **`phone_number_id` lookup runs before `TenantContext` is set** — `findById` has no tenant scope. Specialist's policy is broken (a `tenant_id = NULL::uuid` predicate returns no rows). Fix: run the lookup under a `JTOYE_WEBHOOK_ROLE` GRANTed `BYPASSRLS`, OR mark the table service-role-only and gate at the application layer. Pick one explicitly.
3. **Idempotency record after HMAC verify** — otherwise an attacker who guesses a `message_id` can squat it. Specialist's ordering is right; enforce in code review.
4. **Meta `verify_token` GET handshake.** Go code doesn't handle it. Add `@GetMapping` returning the `hub.challenge` param now, or accept re-registration breaks.
5. **`WHATSAPP_DEFAULT_SHOP_ID` migration.** The current single-tenant env value must backfill into `whatsapp_tenant_config` in V36 or wave-2 loses the ability to route the first tenant. Either inline `INSERT ... SELECT` in the migration body or document as an operator runbook step.

### Reconciled position
Specialist plan is sound; assistant's five points all merit fixes. Final controller path is `/api/v1/webhooks/whatsapp` (per existing edge-go URL — Meta has it registered there, no need to change). `byte[] rawBody` parameter is mandatory. Add a `@GetMapping` for the verify-token handshake. Fix the policy with the `NULLIF` guard. Backfill the existing default shop id in the V36 migration body with a clear operator-overridable hook (`/* TODO operator: replace with real phone_number_id when first WhatsApp tenant onboards */`). Idempotency record after HMAC.

---

## Finding 3 — `SyncBatch` decommission

### Specialist proposal
Delete it. The audit calls it a "thin pass-through" but the verification above shows it's worse: **the URL doesn't even map** between edge and Core (`/api/v1/sync/batch` vs `/sync/batch`). No one has called it since the migration that introduced the mismatch — there is no production caller to break.

Action: remove `protected.POST("/api/v1/sync/batch", h.SyncBatch)` from `edge-go/cmd/edge/main.go:172`. Remove `SyncBatch` from `edge-go/cmd/edge/handlers.go:108-143`. Remove `SyncBatch` from `internal/core/client.go`. **No** Ingress rewrite needed because no Ingress rule routes `/api/v1/sync/batch` anywhere today.

If the Spring integration test (`SyncControllerIntegrationTest.java:51`) is currently passing it is doing so via a path that does not exist in production. Fix the test to post to `/sync/batch` *or* deliberately move the controller mapping to `/api/v1/sync/batch` for forward-compat with documented planning. Pick one — the test is currently a placeholder.

### Assistant deliberation
1. **Partner integrations we haven't seen?** A "sync batch" endpoint sounds POS-shaped. Verify with 30-day request logs and partner contracts before deletion. Any non-empty 200 → alias instead of delete.
2. **The test/controller URL mismatch is itself a bug.** A test posting to a path the controller doesn't serve is passing somehow — read it before deleting either side.
3. **Keep `SyncController` in Spring.** POS-batch is a legitimate future surface. The empty controller costs nothing and reserves the URL.

### Reconciled position
Delete edge-go's pass-through. Keep `SyncController` in Spring. **Move** the Spring mapping to `/api/v1/sync/batch` to match (a) the existing test, (b) the audit's documented URL, (c) the future partner integration story. One-line change to `SyncController.java:20`. Run a 30-day log audit against edge-go pods before final deletion (search for any 200 response from `/api/v1/sync/batch` in `kubectl logs deployment/edge-go --since=720h`); if any hits surface, treat them as a real caller and add an Ingress rewrite rule from `/api/v1/sync/batch` → `service/core-java:9090/api/v1/sync/batch` before deleting edge-go.

---

## Finding 4 — Rate limiting absorption

### Specialist proposal
Already covered. `core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java:36-117` is Bucket4j+Redis, distributed by design. It is wired through `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java:37-40`. After absorb, edge-go's per-pod rate limiter (`edge-go/cmd/edge/main.go:71-102`) is gone — the audit's "broken at horizontal scale" concern goes with it.

Coverage check: Spring's interceptor scopes by `TenantContext.get()` (`RateLimitInterceptor.java:73-77`) and skips when no tenant is set with a WARN log. After absorb, the WhatsApp webhook is the only Core endpoint that runs without `TenantContext` set at filter time (because HMAC sets it inside the controller). Three implications:

1. The webhook escapes tenant rate limiting today. Acceptable — Meta is the only caller and they have their own ceilings.
2. **Per-IP fallback for `/public/**`** is not currently implemented in `RateLimitInterceptor`. Anonymous storefront traffic is unbounded. Add an IP-based bucket fallback for paths matching `/public/**`:

```java
if (tenantIdOpt.isEmpty()) {
    if (requestPath.startsWith("/public/")) {
        String ip = extractClientIp(request);  // X-Forwarded-For first, fall back to remoteAddr
        return tryConsumeIpBucket(ip, response);
    }
    logger.warn("Rate limiting skipped - no tenant context for {}", requestPath);
    return true;
}
```

`tryConsumeIpBucket` mirrors the tenant path but with key `RATE_LIMIT_KEY_PREFIX + "ip::" + ip` and a tighter limit (say 60 rpm per IP).

3. The Ingress already has nginx-level rate limits (`k8s/base/ingress.yaml:13-15` — `limit-rps: "100"`, burst x5, `limit-connections: "50"`). Per-IP fallback at the application layer is therefore defence in depth, not the only line. Acceptable to defer the per-IP add to a follow-up unless the storefront load profile demands it earlier.

### Assistant deliberation
1. **Ingress nginx rate-limit is per-IP/connection, not per-tenant.** A tenant behind corporate NAT shares 100 rps with their office. Acceptable now; watch for support tickets.
2. **`/api/v1/webhooks/whatsapp` has no `TenantContext` at interceptor-time** — `RateLimitInterceptor` WARN-spams every Meta retry. Add `path.startsWith("/api/v1/webhooks/")` to `isExcludedPath()` (`RateLimitInterceptor.java:147`). DDoS protection for that path comes from HMAC.
3. **100/min + burst 20 is generous for a £49/mo tier.** Wire `getTenantTier()` (stub at `RateLimitInterceptor.java:162`) when pricing tiers come online.

### Reconciled position
Spring's existing rate limiter covers the absorb without code changes; **add two small things** as part of the wave-1 PR: (a) the `/public/**` IP-fallback bucket, (b) the `/api/v1/webhooks/` exclusion. Both are <30 LOC each.

---

## Finding 5 — Circuit breaker absorption

### Specialist proposal
Resilience4j is wired in `application.yml:181-209` with three instances: `stripe`, `email`, `ai`. The audit's claim that one global breaker for four Core operations was "ironic since Core-internal calls don't need a breaker" is exactly right — after absorb, three of the four edge-go breaker call sites (`SyncBatch`, `SearchProducts`, `CreateOrder`) become in-process calls, no breaker needed.

The fourth (`ForwardWebhook` — there is no such method in the source actually; the audit is hand-waving — the real four are `SyncBatch`, `SearchProducts`, `CreateOrder`, `HealthCheck`) is also moot.

What *does* need a breaker after absorb:

- **Keycloak JWKS fetch.** Today `NimbusJwtDecoder` (`SecurityConfig.java:47-49`) has a 5s connect/read timeout (`SecurityConfig.java:38-39`) but no breaker. If Keycloak goes down, every request blocks 5s waiting for JWKS-refresh. Wrap the `JwtDecoder` bean in a Resilience4j `@CircuitBreaker(name = "keycloak-jwks", fallbackMethod = "decodeWithCachedKeys")` — the fallback uses the last-known-good JWK set held in a `ConcurrentHashMap<String, JWK>` populated on startup and on every successful refresh.
- **MinIO/S3 image upload** — `core-java/src/main/java/uk/jtoye/core/storage/` (verify class names; the package exists per the directory listing). Add `@CircuitBreaker(name = "s3", fallbackMethod = "queueForRetry")`.
- **Stripe is already protected** (`@CircuitBreaker(name = "stripe")` per the audit, confirmed by `application.yml:184`).

Add to `application.yml` under `resilience4j.circuitbreaker.instances`:

```yaml
keycloak-jwks:
  sliding-window-size: 10
  failure-rate-threshold: 50
  wait-duration-in-open-state: 30s
  permitted-number-of-calls-in-half-open-state: 2
  register-health-indicator: true
s3:
  sliding-window-size: 20
  failure-rate-threshold: 60
  wait-duration-in-open-state: 60s
  permitted-number-of-calls-in-half-open-state: 3
  register-health-indicator: true
```

### Assistant deliberation
1. **Verify the Stripe `@CircuitBreaker` annotation exists at call sites** before wave 3 — council day-2 item #16 implies the fallback is missing, so the annotation may be too.
2. **Wrapping `JwtDecoder` with `@CircuitBreaker` requires a delegating bean.** `NimbusJwtDecoder` isn't directly annotatable. Specialist must write a ~40-LOC `JwtDecoder` wrapper that holds a Nimbus delegate and is itself annotated.
3. **Keycloak down at startup.** With breaker, first request fast-fails 503 for 30s → thundering 503 on every pod restart during Keycloak outage. Pre-load JWKS in `@PostConstruct` and accept startup latency.

### Reconciled position
Add `keycloak-jwks` and `s3` breakers in `application.yml`. Implement the `JwtDecoder` wrapper class (~40 LOC) — specialist owns this. Verify the Stripe `@CircuitBreaker` annotation exists at the call site as part of wave-1 prep work. Pre-load JWKS in `@PostConstruct` of the wrapper bean.

---

## Finding 6 — JWT audience check

### Specialist proposal
edge-go has no `aud` check (audit §JWT #1). Spring's default `NimbusJwtDecoder.withJwkSetUri(...)` doesn't add one either. After absorb, configure a `JwtAudienceValidator`:

```java
@Bean
public JwtDecoder jwtDecoder(RestTemplateBuilder restTemplateBuilder,
                             @Value("${jwt.expected-audience}") String expectedAudience) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
    requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
    RestOperations rest = restTemplateBuilder.requestFactory(() -> requestFactory).build();

    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
        .restOperations(rest)
        .build();

    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
    OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD,
        aud -> aud != null && aud.contains(expectedAudience));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
    return decoder;
}
```

Add to `application.yml`: `jwt: { expected-audience: "${JWT_AUDIENCE:jtoye-core}" }`. Configure Keycloak client so the `jtoye-frontend` client mints tokens with `aud: jtoye-core` (Keycloak realm-config: add an audience mapper to the `jtoye-frontend` client → `jtoye-core` audience).

### Assistant deliberation
1. **Keycloak config change is the risky bit, not the Java.** Wrong order = total 401 outage. Order: (a) add audience mapper in Keycloak (existing tokens still valid — extra aud values aren't forbidden), (b) verify new tokens carry the aud in staging, (c) only then ship the validator.
2. **WhatsApp webhook is permitAll'd**, no JWT validation runs — audience check has no effect there. Confirm.
3. **Single-vs-list `aud` claim.** Keycloak emits a string when there's exactly one audience and an array otherwise. Cast to `List<String>` blows up on the single case. Use `Object` and type-check inside the lambda.

### Reconciled position
Specialist code is right. Order of operations is non-negotiable: Keycloak audience mapper first, verify token contents in staging, then ship validator. Use `Object` claim type to defend against single-string `aud`. Document `JWT_AUDIENCE` env var in `k8s/base/configmap.yaml`.

---

## Finding 7 — Cutover plan

### Specialist proposal — wave breakdown

| Wave | Action | Validation | Rollback action | Rollback time |
|------|--------|-----------|----------------|---------------|
| **0 — Prep (1 day)** | Ship `WhatsAppController`, `WhatsAppMessageParser`, V36 migration, idempotency table, audience validator, `keycloak-jwks` + `s3` breakers, IP-fallback rate limiting, fix `SyncController` URL to `/api/v1/sync/batch`. All deployed to Spring; **no Ingress change yet**. Backfill `whatsapp_tenant_config` with the single existing tenant if any. | (a) `curl -X POST -H 'X-Hub-Signature-256: sha256=<computed>' https://api.jtoye.co.uk/api/v1/webhooks/whatsapp -d @synthetic.json` returns 200. (b) `kubectl logs deployment/core-java | grep "WhatsApp order created"` shows the synthetic order. (c) Spring metrics endpoint shows `http_server_requests_seconds_count{uri="/api/v1/webhooks/whatsapp"}` incremented. (d) Bad-signature test returns 401. | `kubectl rollout undo deployment/core-java -n jtoye-production` (the new code is additive — the old paths still work). | <2 min |
| **1 — DNS / Ingress swing (15 min window)** | Update Meta admin console: webhook URL changes from `https://edge.jtoye.co.uk/api/v1/webhooks/whatsapp` (if it ever existed) to `https://api.jtoye.co.uk/api/v1/webhooks/whatsapp`. Since the council finding shows no Ingress route ever sent traffic to edge-go, this is a "make it official" step rather than a "swing". If a separate `edge.jtoye.co.uk` host exists in DNS, update its Ingress to point at `core-java`. | Send Meta's "Test webhook" button. Watch `kubectl logs -f deployment/core-java -n jtoye-production` for the test event. Spring `http_server_requests` counter for the webhook path increments. No 5xx in the 5 min after swing. | Revert Meta admin console URL. (Or revert the Ingress change with `kubectl apply -f k8s/base/ingress.yaml.bak`.) | <5 min |
| **2 — Edge-go drained (24h soak)** | Scale edge-go to 1 replica. HPA min/max set to 1/1 (`kubectl scale deployment/edge-go --replicas=1 -n jtoye-production && kubectl patch hpa edge-go-hpa --patch '{"spec":{"minReplicas":1,"maxReplicas":1}}'`). Watch for any inbound connection. PDB temporarily relaxed (`minAvailable: 0`) so the pod can be safely evicted later. | 24h period: `kubectl logs deployment/edge-go --since=24h -n jtoye-production` shows zero requests other than `/health` and `/ready` from kubelet. If a request *does* surface, identify the caller before proceeding. | `kubectl scale deployment/edge-go --replicas=5` and revert PDB / HPA. | <2 min |
| **3 — Decommission (5 min)** | `kubectl delete -f k8s/base/edge-go-deployment.yaml -f k8s/base/networkpolicies/30-edge-go.yaml`. Remove edge-go from `k8s/base/kustomization.yaml`. Remove edge-go from `docker-compose.full-stack.yml:179-194`. Remove edge-go matrix entries from `.github/workflows/ci-cd.yaml:159, :239, :249, :260, :299-302, :341`. Add `edge-go/` to `.gitignore` then `git rm -r edge-go/` in a separate PR titled `chore(edge): remove decommissioned gateway`. Tag the deletion commit's parent as `edge-go-final-v2.0.0` for archival reference. | Spring metrics steady, no 5xx surge, no `WhatsApp order created` log gap. | `git revert <decom-commit>` brings the manifests back; `kubectl apply -k k8s/base/` redeploys edge-go from the same image tag (`ghcr.io/jtoye/edge-go:2.0.0`). | <10 min |

### Assistant deliberation
1. **<5 min rollback only holds if GHCR image is still pullable.** Pin `:2.0.0` explicitly; verify `docker pull` works a week post-wave-3.
2. **Wave-2 soak must cover Meta's 3-day retry window.** Extend to **72 hours**; 24h misses retries from 48h-old failures.
3. **No compound rollbacks.** Ship pre-prod blockers (council #1–#5) in a *separate* release before wave 0 — otherwise a wave-0 rollback also reverts the Stripe idempotency fix.
4. **Wave-1 rollback requires Meta admin console access.** If credentials aren't in on-call's 1Password, the "<5 min" rollback becomes "however long to find someone". Prerequisite before wave 1.

### Reconciled position
Wave plan is sound. Three changes:
- Wave 2 soak extended to **72 hours** (Meta's full retry window).
- Pre-prod blockers (council items #1–#5) ship in a *separate* release before wave 0; no compounding.
- Meta admin credentials confirmed accessible to on-call before wave 1 starts.

---

## Finding 8 — Observability gap during migration

### Specialist proposal
Three signals during wave 1–2:

1. **Counter `http_server_requests_seconds_count{uri="/api/v1/webhooks/whatsapp", outcome="SUCCESS"}` on Core** should grow from zero. Track delta over 1h windows.
2. **Counter on edge-go for the same path** should fall to zero within wave-2's soak window. If non-zero, identify the caller via `kubectl logs deployment/edge-go | grep -E '(WhatsApp|sync/batch)'`.
3. **Order-creation count** (`orders_created_total{source="whatsapp"}` — needs to be added as a Micrometer `Counter` in `WhatsAppOrderService`) should be approximately equal pre/post wave 1 over a comparable hour. A 50% drop signals lost messages.

Pair these with three alarms in `infra/monitoring/alertmanager` (or wherever Prometheus rules live):

```yaml
- alert: WhatsAppWebhookSuddenDrop
  expr: |
    rate(http_server_requests_seconds_count{uri="/api/v1/webhooks/whatsapp",outcome="SUCCESS"}[15m])
    < 0.5 * rate(http_server_requests_seconds_count{uri="/api/v1/webhooks/whatsapp",outcome="SUCCESS"}[15m] offset 1d)
  for: 30m
  labels: { severity: warning }
  annotations:
    summary: "WhatsApp webhook traffic dropped >50% vs same time yesterday"

- alert: WhatsAppWebhook5xxSpike
  expr: |
    rate(http_server_requests_seconds_count{uri="/api/v1/webhooks/whatsapp",status=~"5.."}[5m]) > 0.1
  for: 5m
  labels: { severity: critical }
  annotations:
    summary: "WhatsApp webhook returning 5xx — likely HMAC, parser, or Core failure"

- alert: EdgeGoStillReceivingTraffic
  expr: |
    sum(rate(http_server_requests_seconds_count{job="edge-go",uri!~"/health|/ready"}[15m])) > 0
  for: 1h
  labels: { severity: warning }
  annotations:
    summary: "edge-go still receiving non-probe traffic during decom soak"
```

### Assistant deliberation
1. **edge-go has no `/metrics`** (audit §Observability), so "rate on edge-go" is unmeasurable. Use `nginx_ingress_controller_requests_total{service="edge-go"}` instead — same signal, no edge-go code change for a soon-to-die service.
2. **`orders_created_total{source="whatsapp"}` doesn't exist** — make it a wave-0 deliverable, not a wave-1 ask.
3. **Day-over-day alert fires continuously day 1** (no "yesterday" baseline). Mute or use absolute thresholds for the first 48h.

### Reconciled position
Use Ingress-level metrics for edge-go traffic (`nginx_ingress_controller_requests_total`). Add `orders_created_total{source="whatsapp"}` as a wave-0 deliverable. Mute the day-over-day alert for 48h post-cutover.

---

## Finding 9 — What gets deleted

### Specialist proposal — exact inventory

| Category | Items | Approx LOC / count |
|---|---|---|
| Source code | `edge-go/cmd/`, `edge-go/internal/`, `edge-go/docs/` (swagger), `edge-go/Dockerfile`, `edge-go/go.mod`, `edge-go/go.sum`, `edge-go/README.md` | ~1,028 prod LOC + ~1,153 test LOC + ~530 generated swagger = **~2,711 LOC removed** |
| K8s manifests | `k8s/base/edge-go-deployment.yaml` (141 lines), `k8s/base/networkpolicies/30-edge-go.yaml` (85 lines), edge-go entries in `k8s/base/kustomization.yaml` | **2 files + 1 kustomization edit** |
| CI | `.github/workflows/ci-cd.yaml` lines `:49 cache-dependency-path`, `:67-89` swag CLI install + OpenAPI validation, `:110 coverage upload`, `:159 matrix entry`, `:239 build loop iteration`, `:248-260 staging rollout/rollback`, `:299-304 production update`, `:341 production rollback` | **~8 CI block edits, ~50 CI lines removed** |
| Docker compose | `docker-compose.full-stack.yml:179-194` (the entire `edge-go:` service block, ~16 lines including healthcheck) | **1 service block** |
| Sealed Secrets | None — `grep -rn 'WHATSAPP\|edge-go' k8s/` returns zero matches in `secrets-template.yaml` and no Sealed Secrets exist for these yet | **0** |
| ConfigMap entries | `edge-go-deployment.yaml:54-57` references `app-config` key `keycloak.issuer.uri` — keep, used by core-java too. No edge-specific config keys to delete | **0** |
| Container images | `ghcr.io/jtoye/edge-go:*` — keep `:2.0.0` for archival rollback, prune all others after wave-3 + 30 days | **N-1 image tags pruned** |
| Tests | All Go tests (counted in source LOC above), plus the broken `SyncControllerIntegrationTest.java:51` URL fix (not a deletion, an edit) | (covered above) |
| Documentation | `.planning/phases/16-go-edge-openapi/` — keep as historical record; mark `STATUS: archived/decommissioned` at the top of each phase doc | **No file deletions, ~3 status banners added** |

**Net effect**: ~2,700 LOC purged, 5 replicas of compute reclaimed (HPA up to 20 → 0), one CI matrix dimension removed (build/test cycle ~5–8 min faster per commit), one fewer dashboard to monitor, one fewer language toolchain to keep current (Go 1.22 pin gymnastics from audit §"Cost estimate" gone).

### Assistant deliberation
1. Future engineers reading `.planning/phases/16-go-edge-openapi/` will assume edge-go is live. Add `ARCHIVED-2026-04-XX.md` pointing at this doc.
2. `docker-compose.full-stack.yml:2` lists edge-go in the include-comment. Delete that too or it goes stale.
3. **Five idle pods today.** Decom isn't only about LOC — it's compute, log volume, and scrape cardinality reclaimed.

### Reconciled position
Specialist inventory is complete. Add: (a) `ARCHIVED-2026-04-XX.md` in `.planning/phases/16-go-edge-openapi/`, (b) update the comment at `docker-compose.full-stack.yml:2`, (c) note the 5-pod compute reclaim in the change-summary commit message.

---

## Finding 10 — Fallback Option B (Kong / Envoy)

### Specialist proposal
If the founder rejects absorb (e.g., wants a public-edge separation for blast-radius reasons or to support multiple upstream services later), drop in **Kong Gateway OSS** as a sidecar to ingress-nginx (not in place of it — Kong as a service mesh is a much larger commitment).

Kong declarative config (`kong.yaml`):

```yaml
_format_version: "3.0"
services:
  - name: core-java
    url: http://core-java.jtoye-production.svc.cluster.local:9090
    routes:
      - name: api
        paths: ["/"]
        strip_path: false
plugins:
  - name: rate-limiting
    config:
      minute: 100
      policy: redis
      redis_host: redis.jtoye-infrastructure.svc.cluster.local
      redis_port: 6379
      fault_tolerant: true
      hide_client_headers: false
  - name: jwt
    config:
      key_claim_name: iss
      claims_to_verify: ["exp", "aud"]
      maximum_expiration: 3600
  - name: prometheus
    config:
      per_consumer: true
      status_code_metrics: true
      latency_metrics: true
      bandwidth_metrics: true
  - name: zipkin
    config:
      http_endpoint: http://zipkin.jtoye-monitoring:9411/api/v2/spans
      sample_ratio: 0.1
consumers:
  # Per-tenant consumers populated by an operator script that reads from the tenants table
  # and creates a Kong consumer per tenant_id with a JWT credential carrying that tenant.
```

For per-tenant rate limiting, Kong's `rate-limiting` plugin keys by `consumer` when `consumer.id` is set (which the JWT plugin sets when the token's `iss` matches a registered consumer's `key`). That maps to per-tenant if you create one Kong consumer per tenant — workable up to ~10k tenants without trouble.

**Cost compare (steady state, year 1)**:

| Option | Infra cost | Eng cost | Notes |
|---|---|---|---|
| **Kong Gateway OSS, self-host** | ~$80/mo (2× t4g.small for HA) | ~10h initial, ~2h/mo | Free software, Konga UI optional |
| **Kong Konnect SaaS** | $250/mo entry tier | ~5h initial, ~1h/mo | Managed control plane, declarative config still required |
| **Envoy + Istio service mesh** | ~$40/mo (already runs in cluster sidecars) | ~80h initial, ~8h/mo | Massive learning curve; only worth it if you need full mesh anyway |
| **Status quo edge-go (with all the audit's fixes)** | ~$40/mo (5 pods today) | ~30h initial fix, ~6h/mo | Fixes the docs-broken state but still bespoke gateway |
| **Absorb (this plan)** | ~$0 (compute reclaimed) | ~24h migration, ~0h/mo | Recommended path |

### Assistant deliberation
1. **"Per-consumer = per-tenant" requires real glue** — a reconciler watching the `tenants` table, or a manual onboarding step. Not free.
2. **Konnect at $250/mo = 2.5 tenants of pre-revenue runway gone.** Bad fit given £39–£179/mo target tiers (council §commercial).
3. **Envoy/Istio is a non-starter** at two services. Mesh cognitive overhead exceeds absorb cost by an order of magnitude.
4. **"Multiple upstreams later" is real but premature.** Kong can be added in 2–3 weeks when that day comes; running it now to "reserve the option" is sunk cost.

### Reconciled position
Option B is included for completeness. Recommended only if absorb is rejected; the council audit's verdict and our verification both point to absorb. **Do not run Option B in parallel with absorb** — pick one.

---

## Finding 11 — The eng-cost / risk verdict

### Specialist proposal — final reckoning

| Path | Eng-hours | Elapsed weeks | Blast radius if wrong |
|---|---|---|---|
| **Absorb (this plan)** | 24h (1 senior eng × 3 days) — wave 0: 16h, waves 1–3: 8h spread over ~5 days | 1 calendar week + 72h soak | Low — every wave is `kubectl rollout undo` reversible in <10 min; no data loss path identified; ingress was never routing to edge-go anyway |
| **Harden in place** | ~30h initial — distributed rate limiter via Redis (~12h), `aud` validator (~2h), JWKS mutex + singleflight + stale-while-revalidate (~6h), per-route circuit breakers (~4h), per-tenant WhatsApp shop mapping (~6h) | 2 weeks | Medium — every fix is a Go deploy with its own bug-introduction risk; you still own the codebase forever |
| **Replace with Kong** | ~40h initial — Kong deploy + Helm chart (~8h), declarative config authoring (~6h), per-tenant consumer reconciliation pipeline (~12h), JWT plugin tuning (~4h), staging cutover + soak (~10h) | 3 weeks | Medium-high — new infra component to operate; per-tenant consumer pipeline is bespoke glue with its own bugs; if it goes wrong, every API call 502s |

### Assistant deliberation
1. **24h is optimistic.** Wave 0 alone has 6 artefacts + tests; closer to 3 focused days realistic.
2. **"Low blast radius" assumes no in-flight WhatsApp traffic.** Confirmed today. If a tenant onboards mid-waves 0–2, blast rises to Medium. Run migration in a known no-WhatsApp window (this week).
3. **Hidden cost: context-reacquisition.** Absorb has lowest long-term maintenance because team already has Spring expertise; harden-in-place keeps Go burden; Kong adds a new dialect.

### Reconciled position
Numbers stand with one upward adjustment: **call it ~32 eng-hours** (4 focused days) for absorb to account for assistant's point #1. Path recommendation: **Absorb**. Calendar timing: this week, before any WhatsApp onboarding lands.

---

## Dependency graph

```
Pre-prod blockers (council #1-#5)         (separate release, not this plan)
        |
        v
Finding 6 (audience validator) ----+
                                   |
Finding 4 (rate-limit additions) --+
                                   |
Finding 5 (jwks/s3 breakers) ------+--> Wave 0 (Spring-side ready)
                                   |
Finding 2 (WhatsApp controller    -+
            + V36 + idempotency)   |
                                   |
Finding 3 (SyncController URL fix) +
                                   |
                                   v
                            Wave 1 (Meta admin URL swing / Ingress confirm)
                                   |
                                   v
                            Wave 2 (72h soak — observability per Finding 8)
                                   |
                                   v
                            Wave 3 (delete per Finding 9)
                                   |
                                   v
                            Tag: edge-go-final-v2.0.0 (archive ref)
```

Option B (Finding 10) is an alternative **branch** that replaces waves 0–3, not a parallel path.

---

## Open questions for the founder

1. **Has any partner ever been pointed at `edge.jtoye.co.uk` or any non-`api` host for the WhatsApp webhook?** If yes, treat that DNS record as a real cutover and add it to the wave-1 plan.
2. **Is there a live WhatsApp tenant today?** Council answer: no (no `WHATSAPP_APP_SECRET` in k8s, no `WHATSAPP_DEFAULT_SHOP_ID` in k8s). Confirm before wave 1.
3. **Is the GHCR image `ghcr.io/jtoye/edge-go:2.0.0` set to be retained indefinitely?** Required for wave-3 rollback to be real.
4. **Do you want to keep `SyncController` in Spring as a future POS-batch endpoint?** Reconciled position is yes — costs nothing, reserves the URL. Confirm.
5. **Does Meta admin console access live in 1Password and is it shared with the on-call rotation?** Required prerequisite for wave 1.
6. **Pre-prod blockers (council #1-#5) — when are they shipping?** This plan assumes they ship in a separate release *before* wave 0 to avoid compound rollback risk.

---

## One-line summary

The audit said "delete and absorb"; verifying the code and topology shows there is **less to delete than feared** (no live traffic, no live ingress route, no live partners), the **port is mostly straightforward** (~150 LOC controller + a 4-column table + an idempotency table), and the **rollback is genuinely fast at every wave** because the ingress topology change is the lever. Recommend executing this week, in the order specified, with the 72h soak gate before deletion.
