# J'Toye OaaS — Failure Modes & Edge Cases

**Last verified:** 2026-08-19 against `main @ 53d7bd7d`, via a two-round supervised tour (7 domain
reports + 6 adversarial verifications). Each entry cites where it was measured. **LIVE** = measured
against the running stack; **CODE** = read from source; **DOC** = a documentation/tracking gap.

This is a hazard map, not an incident list. It exists so the next change is made with the failure
surface in view. Ordered by operational urgency.

Companion docs: `docs/HOW_IT_WORKS.md`, `docs/architecture/ARCHITECTURE.md`.

---

## 0. Live issues that need an owner decision (surfaced by this tour)

These were found during the tour and are **not yet tracked**. They are listed first because they are
actionable now.

| # | What | Evidence | Recommended action |
|---|---|---|---|
| **F-0.1** | **The nightly E2E has failed 9 consecutive nights** (since 2026-08-11) — the compose stack never becomes healthy, so Playwright never runs — and **nothing alerts on it**. Every merge since 2026-08-10 (incl. Phase 31, 18 plans; #634) landed with zero full-suite E2E evidence. First failing night = first to include Phase 28's merge (#630). | LIVE `gh run list --workflow=e2e-nightly.yml`: last success 2026-08-10, 9 failures dying at `core-java is unhealthy`. No `workflow_run` watcher; no Prometheus rule; HANDOFF never mentions "nightly". | **Highest priority.** Diagnose the Phase-28 compose/env/healthcheck change that per-PR CI (which builds no stack) can't catch; add a missed-nightly alert. |
| **F-0.2** | **`/api/v1/sync/batch` is a within-tenant authorization gap (BOLA).** Gated only by `authenticated()` — no scope, no role, no `ShopAccessService` check. Any tenant staff member scoped to shop A can upsert **any** shop/product in their tenant, bypassing the Phase-23 second wall. Its only test runs `@WebMvcTest(addFilters=false)` — zero authz coverage. Cross-tenant reach is blocked by RLS; the gap is intra-tenant, write-side. | CODE `SecurityConfig` (`anyRequest().authenticated()`); `SyncService` writes via repositories with no access check; `SyncControllerIntegrationTest` disables filters. Live caller: edge-go `main.go:304` (no production producer exists). | File as a within-tenant BOLA. Decide: restrict to a machine scope/role, route through `ShopAccessService`, or retire `/sync`. **Add the deny-test first and watch it fail.** |
| **F-0.3** | **`olajay.co.uk` expires 2026-12-31 (~4.4 months), untracked.** Every `FRONTEND_PUBLIC_*` var, all five `/legal` pages, the DSAR contact, and the staging URLs ride on it — and its apex already has **zero A records**. | LIVE Nominet RDAP: registered 2019-12-31, expires 2026-12-31; `dig +short olajay.co.uk A` empty. | Owner action: renew (or resolve the domain question) before the GTM window. |
| **F-0.4** | **785 NULL-tenant rows across 6 `_aud` tables, all Envers delete revisions**, sit under the `tenant_id IS NULL OR …` policy arm — **cross-tenant readable, growing, and swept by nothing.** Envers nulls every column on a delete revision, `tenant_id` included. Leak is bounded (only PK UUIDs + revision id/timing + the deletion fact) but real. | LIVE (as superuser, RLS-bypass, per the RLS-blindness rule): 741 in `media_asset_aud`, 15 `orders_aud`, etc.; 100% `revtype=2`. No issue tracks it. | File. Decide whether the `IS NULL` policy arm should exclude delete revisions, or add a sweep. |
| **F-0.5** | **A third unguarded guest GUC: `app.tracking_email`** (V17), set at `PublicStorefrontService.java:601` — same class as the untracked `app.customer_email` deferral (whose issue #113 closed with no successor). Any SQL on the pooled connection can set either; the only defence is the app-side verify-order-number check. | CODE `V17__order_tracking.sql:19`; `rg -uu customer_email` over migrations shows no DB-side guard newer than V51. | Home both deferrals under one tracked issue; decide if a DB-side guard is warranted. |

---

## 1. Multi-tenancy & authorization

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-1.1 | **A worker that forgets to pin the GUC sees an empty table, not an error** — a *filtering* failure replaces an *erroring* one (V51). Reads "clean" while returning nothing. | Off-request-thread code must pin `TenantContext` + GUC manually (19 files do). A forgotten pin + `jtoye_runtime` (no RLS bypass) = 0 rows. | By design (safe direction), but it is also the root of **RLS-blind verification queries**: any ops/monitoring query that forgets the GUC reports "clean" over an empty read. Prove a verification query can *see* rows first. |
| F-1.2 | **Grant revocation is stale for up to 5 minutes across replicas.** | `shopMembership` cache (Redis, tenant-keyed) is evicted post-commit and node-locally; a revoked grant can stay honoured on another replica for the cache TTL. Applies to *every* `ShopAccessService` decision served from cache, not just SSE. | Accepted authorization-staleness SLA. Document it where SSE/STOMP revocation is reasoned about. |
| F-1.3 | **STOMP grant is checked only at SUBSCRIBE — a revoked subscriber keeps receiving.** | `TenantChannelInterceptor` validates at subscribe time, not per-message; the open session outlives the grant (bounded loosely by the 7200 s SSO session max). | Open, **#627**. |
| F-1.4 | **Tenant assignment is entirely IdP-controlled.** `JwtTenantFilter` trusts the `tenant_id`/`tenantId`/`tid` claim from any token the realm decoder validates. | A Keycloak 24 user-profile misconfiguration (unmanaged `tenant_id` stripped on create) is a known operational hazard. | Operational — realm template must declare `tenant_id` managed. |
| F-1.5 | **`jtoye.access.machine-client-ids` bypasses shop-scoping for declared client ids** (RLS still tenant-scopes). Empty by default. | A config allowlist for machine callers, guarded to stay allowlist-only. | By design; the *enumerated list of write paths inside vs outside the second wall exists nowhere* — F-0.2 is one such path. |
| F-1.6 | **DSAR idempotency key is globally unique across all anonymous callers.** A collision suppresses a second subject's lodgement (the response body is constant, so no data leaks, but the second request is silently absorbed). | `dsar_request` unique index is on `idempotency_key` alone (deliberate — a composite would let one key carry a different address and double-erase). | Accepted tradeoff (V62 design). |

---

## 2. Async, outbox & broker

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-2.1 | **Outbox dispatch trap (standing).** A new event family routed through the shared `payment_event_outbox` that isn't in `publishRow`'s closed set is deserialised as a PaymentEvent → poison → permanently FAILED. Branch order is load-bearing. | `PaymentEventOutboxFlusher.publishRow` closed-set switch; the else-branch is a poison sink by design. | Mitigation pattern of record: a **dedicated outbox** (`media_event_outbox`, V58). Any new event type must extend `publishRow` *or* get its own outbox. |
| F-2.2 | **Broker outage: onboarding messages vanish.** `onboarding.events` has **no DLX** — unroutable onboarding messages are dropped at the broker with only a counter for visibility. | `RabbitMQConfig` declares the exchange bare; the `onboarding.notifications` queue has no `x-dead-letter-exchange` (unlike the order queue). | Known. Order/payment/media events survive as PENDING outbox rows; onboarding does not. |
| F-2.3 | **RabbitMQ 4.3 has no downgrade path and a support-horizon time bomb.** 4.3 is Khepri-only; rollback is tarball-restore only (no 3.12→4.x in place). The dependency-horizon gate turns **amber ~2026-09-01** and **RED 2026-12-01** with no code change. | `infra/dependency-horizons.yaml` vendor EOL 2026-11-30; `HORIZON_WARN_DAYS=90`. | Pre-announced (ci-cd comment). Amber window opens ~13 days after this tour. |
| F-2.4 | **A fresh core-java restart blinds `NoOrdersCreated`** and reds `check-alert-metrics`. | The `http_server_requests_seconds_count` Micrometer counter is created on the first matching request and destroyed on restart. | Expected, not a defect. Remedy: `scripts/seed-order-metric.sh`. The gate-question and the alert-question can both be true at once (the alert's `increase(...[30m])` still sees pre-restart samples). |
| F-2.5 | **afterCommit choreography is a crash-window class.** Keycloak deprovisioning (V49), SyncService cache evictions, and SSE membership eviction all run *after* the owning transaction commits. A JVM death between commit and synchronisation silently drops the side effect (stale cache, un-deprovisioned IdP users) with a NULL/absent marker as the only trace. | `TransactionSynchronization.afterCommit → REQUIRES_NEW`. | Accepted (keeps the primary tx from rolling back on a best-effort side effect); enumerate as a known shape. |

---

## 3. Money path

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-3.1 | **A blank Stripe key is indistinguishable from an intentional COD deployment.** Every deployed stack silently takes the COD branch; this hid #538 across every environment. | `isConfigured()` = "`stripe.api-key` non-blank"; defaults empty everywhere. | A `@PostConstruct` WARN fires at boot. LIVE: the running API reports `acceptsCardPayments:false`. **The money path has never executed end-to-end.** |
| F-3.2 | **Orders complete with no payment.** The COD fallback violates the owner's 2026-08-02 ruling (payment link to a verified phone). | `PublicStorefrontService` COD branch, now `paymentMethod="Unpaid"` (INT-9 relabelled the literal only — E-2). | Open, **#461** (P1) — **UNCHANGED by INT-9**: the fallback is still an ungated silent default and the customer-visible "Pay on collection" copy is client-derived. Phase 30 not started. |
| F-3.3 | **WhatsApp order-create can double-create on a Meta retry.** No Idempotency-Key is sent, and core's header is `required = false` — a key-less request bypasses the V50 store entirely (no replay, no 409, no dedup). | edge `handlers.go:367-374` sends no header; `OrderController.java:75` accepts its absence. | Cosmetic today (WhatsApp unprovisioned) but ships with any provisioning. Work Order O. |
| F-3.4 | **Server does not enforce allergen acknowledgement.** A direct API caller places an order with no acknowledgement; nothing records that one was shown. | `GuestOrderRequest` has no ack field; the gate is client `useState`. | Untracked. File as an *enforcement* gap (recording the customer's allergies would re-approach the Article-9 line that deleted `customerAllergenMask`). |
| F-3.5 | **`NULL` allergen ≠ `0` allergen.** A consumer coalescing `NULL`→`0` fabricates a legal record (claims "vendor declared none" for a historic order that recorded nothing). | V63 keeps three states: declared / declared-none / NOT RECORDED (NULL). No backfill. | By design; any new consumer must respect the distinction. |

---

## 4. Frontend

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-4.1 | **`next/image` has zero importers, so `images.remotePatterns` is a latent staging/prod trap.** If anyone adopts `next/image`, the S3 `eu-west-2` hostname the overlays resolve to is not whitelisted → images break. | Every image is a plain `<img>` (`SafeImage`/`AssetImage`); `next.config.mjs` only whitelists `localhost:9000`. | Inert today. Note before adopting Next image optimisation. |
| F-4.2 | **No `app/global-error.tsx` and no root `not-found.tsx`.** An exception in the root layout (MotionProvider/Providers) falls through to Next's unbranded default page — outside the CSP nonce and brand chrome. | Error boundaries exist per-segment but not at the root. | Gap; low frequency, high visibility if hit. |
| F-4.3 | **The in-code hydration-fix tracker cites the wrong issue.** The 4 mount-time hydration suppressions that cite "#99 follow-up" point at the wrong tracker — **#99 is CI/CD deploy theatre**; the real one is **#202** (6 further `set-state-in-effect` suppressions carry unrelated justifications). An auditor following the code lands on the wrong issue. | 4 sites: `use-customer-session.ts:35`, `sidebar.tsx:63`, `mobile-tab-bar.tsx:64`, `shop/auth/callback/page.tsx:18`. | DOC. Fix the 4 comments to cite #202; #202's own body is one refactor behind (its 4th site moved into `use-customer-session`). |
| F-4.4 | **`contrast-literals` test is structurally blind to the `/legal` routes.** `SCAN_ROOTS` excludes `app/legal`, `components/legal`, `components/platform`, `/track`, `/unsubscribe`, `/for-operators`, `/competitive`. This blind spot is exactly how a 4.41:1 mobile contrast failure survived to the final plan of Phase 31. | `contrast-literals.test.ts:60-67`. | DOC/test gap. Widening it will likely surface further literals. |
| F-4.5 | **Streaming staging-buffer DOM duplication (#556/#593) is institutionalised, not fixed.** React's staging buffer briefly holds a second shell copy, doubling landmark counts by testid for ~300 ms. | Mitigated by convention: `SETTLE_MS` + `getByRole` (immune). Any new spec counting by `getByTestId` without a settle re-inherits the trap. | Convention only. |
| F-4.6 | **Silent token-rotation dependence on a next-auth beta internal.** `refreshSessionOnce()` relies on a session-endpoint GET running the jwt callback and re-issuing the cookie — core to v5's design but not semver-guaranteed on `5.0.0-beta.32`. A breaking bump would surface as a redirect loop to `/auth/signin`, with every unit test green. | `lib/api-client.ts:60-101` + `auth.ts`. | Worth one E2E that outlives an access-token expiry, or a pin-note. |
| F-4.7 | **`NEXT_PUBLIC_*` are frozen at build time.** The registered office ships empty (owner decision), so `/legal` pages publish an email-only controller contact; changing any of the 7 inlined vars requires a **frontend image rebuild**. `NEXT_PUBLIC_KEYCLOAK_URL` is deliberately left runtime-resolvable. | `frontend/Dockerfile` ARG/ENV list. | By design; a redeploy hazard to remember. |

---

## 5. Edge-Go & MCP

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-5.1 | **The rate limiter sits in front of `/health`.** A sustained 429 storm exhausts the shared token bucket and fails the Docker HEALTHCHECK (requires exactly 200) → container marked unhealthy/restarted. | edge `main.go:255` (limiter) before `:262` (`/health`). | **Compose-only exposure** (8089 on 0.0.0.0); in k8s no ingress route reaches edge-go at all, and a NetworkPolicy admits only ingress-nginx/monitoring/infra. |
| F-5.2 | **The shared breaker counts 4xx as failure.** One misbehaving client's 400s can open the single "CoreAPI" breaker and 502 the sync path for everyone. There is **no fallback** (no cache, no queue). | `orders.go` treats `>=400` as breaker failure; `handlers.go:183` returns 502. | Known. Fallback intentionally absent. |
| F-5.3 | **Unknown-`kid` → a concurrent JWKS refetch per request.** A garbage-kid flood becomes concurrent 5 s-timeout Keycloak fetches (no singleflight), bounded only by the 20-rps limiter. | edge `jwt.go:148-156`; the HTTP GET runs outside the map lock. | Amplification vector; worse than serialised. |
| F-5.4 | **The edge logs customer phone numbers in plaintext** on its one order-writing path — the PII-hygiene rule the MCP tier follows, violated at the edge. | `handlers.go:355, :383`; `ForwardWebhook` also logs the full core body on 4xx. | Cosmetic today (WhatsApp unprovisioned); ships with provisioning. |
| F-5.5 | **mcp-server has no graceful shutdown and an implicit ~100 kb body cap.** `docker stop` kills in-flight tool calls; `express.json()` uses its default limit (nobody chose it, no test asserts it). | `index.ts:72-78` (bare `app.listen`), `:18` (`express.json()` no `limit`). | Low stakes (tool calls are 10 s-bounded); posture asymmetry vs edge (which drains 10 s). |
| F-5.6 | **`/metrics` is unauthenticated by design.** In compose it moves to an unpublished management port (9101); in k8s it stays on the app port 8080 but is NetworkPolicy-guarded. | edge `main.go` management-port topology. | By design; note if adding a k8s ingress rule for edge-go (reopens F-5.1). |

---

## 6. Platform, deploy & data

| # | Failure mode | Mechanism | Mitigation / status |
|---|---|---|---|
| F-6.1 | **`docker compose start` runs stale code.** It starts existing containers with their old image IDs — no rebuild, no recreate — while returning HTTP 200 and green suites. | Phase 26 shipped exactly this past four green gates. | `check-runtime-freshness.sh` compares image `.Metadata.LastTagTime` (not `.Created`, which survives a cached rebuild) **and** the running container's image ID vs the tag's. Always `--build`; run the gate **from the main checkout** (a worktree VOIDs it — the compose project name comes from the directory). |
| F-6.2 | **Neither deploy job is armed.** Staging keys on `DEPLOY_STAGING_ENABLED`, production on `DEPLOY_ENABLED` — **both unset**. Staging/production exist only as manifests + goldens; the minikube cluster is not provisioned. | LIVE `gh variable list`. | Expected pre-GTM state; Phase 29 is paused on owner DNS + secrets. |
| F-6.3 | **The dev Compose stack — the only environment that has ever run real data — has no backup.** k8s has a daily pg-backup CronJob + restore drill; compose has none (volumes only). PITR is gated on ADR-0002 (still Proposed). | CODE `k8s/base/pg-backup-cronjob.yaml` exists; no compose backup script. | Gap for the environment that actually holds data. |
| F-6.4 | **Keycloak realm state lives in Postgres, not the volume.** Dropping `keycloak_data` is a no-op for realm config; re-import needs `kc.sh import --override true` with the server stopped. | `infra/keycloak/README.md`. | Operational trap; the compose one-shot render job regenerates the gitignored realm JSON each `up`. |
| F-6.5 | **Volume-loss asymmetries.** Grafana admin password applies only at first admin creation; Postgres init SQL runs only on a fresh volume (the `jtoye_app` role-password defect that broke the nightly came from exactly this). | monitoring/compose init semantics. | Known; `check-infra-exposure` interrogates the *running* instance, not the declared config. |
| F-6.6 | **Healthy-but-dead container class.** A healthcheck runs inside the container, so network detachment is invisible (the ollama "healthy on no network" incident); `docker network connect` restores the name but not compose's service alias — only a compose-level recreate fixes DNS. | `check-container-config-drift` D-4. | Known; only a functional probe (resolve the name, call the endpoint) reveals it. |

---

## 7. Verification & instrument failures (the meta-hazards)

The most transferable lesson in this codebase: **a confident wrong answer from a check is more
dangerous than a red one.** Documented, recurring classes — cite these before trusting a measurement:

| Class | Mechanism |
|---|---|
| **Blind search** | Plain `grep`/`rg` here honour `.gitignore` (a shell-function `rg` + ripgrep default). A count of tracked files reads as a count of existing files. Use `rg -uu`; `searchcheck` when being wrong matters. |
| **VOID read as pass** | A gate exits 2 when it *could not evaluate*. An empty result table, a stopped stack, missing tooling — all VOID, never clean. `gh pr checks` rc=1 means failed **or** unreachable. |
| **Vacuous served-page assertion** | Checking a conditionally-rendered element: the fixed and the broken pattern both return 0 when the element never renders. "0 occurrences of the bad pattern" = "fixed" = "never rendered." |
| **Skip read as pass** | "114 passed, 14 skipped" reads green; a money-path assertion had never executed. A declared skip is unverified surface, not a pass. |
| **Self-closed measurement loop** | A tree-gate and a prose-gate agreed with each other and were both wrong (README sat at 921 while the tree was 1895). A third oracle (runners→manifest) breaks the loop. |
| **mtime ≠ staleness** | Git rewrites mtime on checkout/merge; the honest question is a content digest (the skip-budget gate uses one). |
| **Stale artifact read as current** | `core-java/build/` is stale; the live results dir is `build-local`. A stale XML report reads as a pass over a failed compile. |
| **TEXT-sorted max** | `SELECT max(version) FROM flyway_schema_history` returns `9`, not `63` (the column is TEXT). |
| **Comment counts as CI wiring** | `check-gate-enforcement` counts a workflow *comment* naming a script as wiring — so `check-alert-liveness`/`-metrics` are "wired" by prose. Two gates (`check-container-config-drift`, `check-postgres-major-parity`) have only ever been observed passing. |
| **A green PR proves little** | Authenticated flows, the integration suite (path-filtered → SUCCESS when skipped), runtime parity, alert firing, and the money path are all *outside* per-PR CI (see §8 / `HOW_IT_WORKS.md` §8). |
| **Backticks in a commit/PR body execute** | A `-m` string that *mentions* a command in backticks runs it and drops the phrase. Use a quoted heredoc; read back with `git log -1 --format=%B`. |

---

## 8. Documentation drift found by this tour (fix targets)

Corrected in this pass (CLAUDE.md, AGENTS.md + `.github` charter sources): the "32 migrations" count
(→ 63), the phantom edge routes and "cached response or 503" breaker story, the `ErrorResponse` →
RFC 7807 `ProblemDetail` handler, the "no source file found" cache-key-generator note, and the two
inverted frontend claims (`hasTouch` and `hoverOnlyWhenSupported` — both **set** since #503).

Still outstanding (owner/tracking calls, not fixed here):
- `.planning/codebase/ARCHITECTURE.md`, `SECURITY_ARCHITECTURE.md`, `edge-go/README.md`,
  `.planning/codebase/STACK.md` (Go 1.22), `.planning/codebase/TESTING.md` (total 516 vs 3185) are all
  stale and read by no gate.
- `docs/CHANGELOG.md` says `jtoye.co.uk` was "never registered" — it is registered/parked (RDAP).
- `README.md` "18 hard-required variables" is 19 (the missed one is `DB_MIGRATION_PASSWORD`, added
  2026-08-10); no gate owns that number.
- The `.planning/ROADMAP.md` Phase-29 row reads "Not started 0/?" while the phase is paused at 9/16 on
  branch `phase-29-research` (knowingly stale, per HANDOFF).
