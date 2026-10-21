# Phase 30: The Money Path, Executed - Research

**Researched:** 2026-10-21
**Tree:** `docs/30-plan-money-path` @ `d289cec9` (= `origin/main`); staging images `4f3f3e14` (read from the running Deployments)
**Domain:** Stripe test-mode money path (PaymentIntents, Connect direct + destination charges, refunds, webhooks), Next.js build-time config, order state machine, CI E2E lanes
**Confidence:** HIGH for the two root causes (#957, #958) and the PAY-01/PAY-03 blockers (read from code, the running staging bundle and the staging Deployments); MEDIUM for the Stripe-side behaviours (official docs, fetched this session); LOW where marked `[ASSUMED]`.

<user_constraints>
## User Constraints (no CONTEXT.md — owner chose "continue without context"; the orchestrator's owner-set scope stands in for it, copied verbatim)

### Locked Decisions (owner-set scope for this planning pass — gap closure, not greenfield)
1. issue #957 (P0): staging card checkout never shows a payment step — Place order returns 201 and the page does nothing, leaving an unpaid Draft. Find the ROOT CAUSE on the current tree: trace frontend checkout (Next.js) → public storefront order API → PaymentService/Stripe PaymentIntent/Checkout creation → what the frontend expects back (client_secret / redirect URL) → staging config (are STRIPE keys/publishable key present in the staging overlay / Key Vault wiring / NEXT_PUBLIC_* build-time vars?). Note NEXT_PUBLIC_* are baked at build time. Check `PublicStorefrontService` around the "Unpaid" fallback (formerly "Cash on Delivery", ~lines 1186-1194) — the silent unpaid fallback when no provider is configured is a prime suspect. Read staging config only from the repo (k8s/staging, scripts) — do not query live secrets. You MAY do read-only checks against the public staging URLs (app-/api-staging.olajay.co.uk) without creating orders, and read-only kubectl with --context jtoye-stg-aks to see which env var NAMES are set (never values; never `get secret -o yaml`).
2. issue #958 (P1): an unpaid card DRAFT can be submitted and accepted in the kitchen; the customer is emailed Received and Confirmed; the refusal later only says Failed. The 30-VERIFICATION report claims a PREPARING guard (`OrderService.isAwaitingUpfrontOrDepositPayment`) — find why it does not stop DRAFT→submitted→CONFIRMED (the guard is likely only on CONFIRMED→PREPARING), what the state machine should be for card orders (no kitchen acceptance and no "Received/Confirmed" email before capture), and what the refusal message should say.
3. PAY-01: refund E2E (#61, `vendor-refund-flow.spec.ts`) is still ALLOW in `scripts/gates/e2e-skip-budget.conf`. What it needs to run for real in Stripe TEST mode (keys in CI? which job? stripe-mock vs real test mode — the requirement says against Stripe, not a mock), and how the skip-budget gate proves re-adding an ALLOW without justification + REMOVE WHEN fails.
4. PAY-03 staging limb: the full loop onboard → order → capture → payout to connected account → refund executed ON STAGING with each step read from Stripe's API (both DIRECT_CHARGE_VENDOR_MOR and DESTINATION_CHARGE_PLATFORM_MOR). Phase 29 is complete so staging exists. What config/secrets/webhook endpoint (STRIPE_WEBHOOK_SECRET, a public webhook URL on api-staging, Stripe CLI vs dashboard endpoint) are needed and how evidence should be captured.
5. PAY-02 and PAY-04 are recorded as "Evidenced, unticked pending owner acceptance" — confirm what exists on the tree and list what (if anything) the plan must add, but do not re-plan built work. #108 (outbound timeouts on Stripe calls) is in scope if not already done — check.

**Constraints:** Stripe TEST mode only (livemode=false), never live keys. Azure only via scripts/lib/azure-guard.sh --subscription c483d353-5f61-4587-a790-addb9ab5fb94. Kube context jtoye-stg-aks only. Never echo secrets. STARTTLS stays required. Python never in conda base (conda env jtoye-ops or /usr/bin/python3 stdlib). Owner decisions in ROADMAP (pay-by-link before service; no cash at fulfilment; ADR-0007 policy axes) are NOT to be re-opened. Merges to main redeploy staging automatically (DEPLOY_STAGING_ENABLED=true) — plans must be shaped as serial, separately-mergeable PRs.

### Claude's Discretion
- How each gap is decomposed into PRs/plans, test design, and the mechanism of each fix (within the locked policy).

### Deferred Ideas (OUT OF SCOPE)
- Re-opening the payment policy (pay-by-link before service, no cash at fulfilment, ADR-0007 axes).
- Re-planning PAY-02 / PAY-04 work that is already built (findings below are for owner acceptance, not re-planning).
- Live keys, production webhooks, pricing/fee decisions (escalate to owner — AGENTS.md `oaas-money`).
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description (REQUIREMENTS.md:126-129) | Status on the tree (this research) | Research support |
|----|----|----|----|
| PAY-01 | Refund E2E (#61) runs and passes; its `ALLOW` entries leave `e2e-skip-budget.conf` | **OPEN.** ALLOW present (`e2e-skip-budget.conf:44`); no Stripe key reaches any CI stack; the gate does not check justification/REMOVE WHEN | §PAY-01 below; Pitfalls 4-7 |
| PAY-02 | Recurring billing £39/location/mo + 0.5% (+ presets), test-mode invoice retrieved by id | **Evidenced (laptop, other Stripe account), unticked.** Two code findings for the owner: no per-location quantity; no billing UI | §PAY-02 confirm-only |
| PAY-03 | Full loop on staging, onboard→order→capture→payout→refund, from Stripe's API, both settlement modes | **OPEN and BLOCKED on a Connect webhook endpoint + a second signing secret** (code has one secret) | §PAY-03 below |
| PAY-04 | Unpaid order cannot be produced; single-use expiring link to a verified phone | **Evidenced at API level only, unticked.** `/pay/{token}` page does not exist (staging 404); guard misses SUBMIT/CONFIRM (#958) and `paymentStatus=NONE` orders; SMS channel not configured on staging | §PAY-04 confirm-only; §#958 |
| #957 | Card checkout shows no payment step on staging | **Root cause proven** (frontend build, not backend) | §#957 |
| #958 | Unpaid card DRAFT can be submitted/accepted; misleading emails; "Failed" toast | **Root cause proven** (guard only on PREPARING) | §#958 |
| #108 | Outbound timeouts (Stripe/SMTP/axios/S3), dead `email` breaker | **NOT done.** Stripe on SDK defaults 30 s / 80 s; refund + retrieve outside any breaker | §#108 |
</phase_requirements>

## Project Constraints (from CLAUDE.md / AGENTS.md)

- **Falsifiable evidence:** every acceptance criterion is shown to FAIL on a deliberately broken input before it is trusted; record both directions (CLAUDE.md "Falsifiable evidence + runtime parity" (a)). Guards fail closed: missing tooling / unparseable / empty output exits non-zero (VOID, exit 2), never 0. No `cmd | grep -q` under `pipefail` — use here-strings.
- **Runtime parity:** a phase is not done until the delivered runtime matches the branch; read the value out of the running artifact (for the frontend: the served JS chunks). `git log HEAD..origin/main` empty before a PR (`scripts/check-branch-behind-base.sh`).
- **Testing:** all new code has tests; Java against Testcontainers Postgres (RLS), never mocks over tenancy; counts are enforced by `docs/metrics.json` + two docs-freshness gates — any new test changes `docs/metrics.json` (`java_test_methods` 2939, `jest_blocks` 3068, `playwright_blocks` 192, `total_logical_invocations` 6381 today) [VERIFIED: docs/metrics.json read this session].
- **Rebuild ALL containers after code changes before E2E**; `docker compose start` does not rebuild.
- **Multi-tenancy:** RLS + TenantContext on every new feature; tenancy changes hand to `oaas-tenancy-security`.
- **Agent-readiness:** mutating endpoints carry Idempotency-Key; errors are typed RFC 7807 with stable codes; MCP parity or a recorded reason.
- **Security:** every plan carries a `<threat_model>` block (`security_asvs_level: 2`, `security_block_on: medium` in `.planning/config.json`).
- **Incremental Betterment:** a plan that reworks a user-visible surface enumerates the goods it displaces (checkout, kitchen board, orders list, cash E2E specs are all touched here).
- **Money specialist rules (AGENTS.md `oaas-money`):** test mode only; idempotency on every mutating call; state transitions are an enumerated state machine; refunds are asymmetric; webhooks are untrusted input; the closing artifact is a **test-mode Stripe object id + the matching local row**, not a green suite.
- **Platform rules (`oaas-platform`):** Sealed Secrets / NetworkPolicy / exposure changes escalate; a deploy is verified by reading the running artifact.
- **Feature branch, never main; no Co-Authored-By trailers** (user global rule overrides the harness attribution reminder).
- **Write boundaries:** `oaas-money` owns `**/*Payment*`, `**/*Order*`, `**/*Stripe*`, `**/*Refund*`; `oaas-frontend` `frontend/**`; `oaas-platform` `k8s/**`, compose; `oaas-release-qa` `.github/workflows/**`, `qa/**`, tests, `docs/metrics.json`.

## Summary

**#957 is a frontend build defect, not a backend one.** On staging the backend IS Stripe-configured: `core-java` mounts `STRIPE_API_KEY` and `STRIPE_WEBHOOK_SECRET` from the `stripe-credentials` Secret (keys `api-key`, `webhook-secret`), and `GET /api/v1/public/shops/staging-test-kitchen` answers `"acceptsCardPayments": true` [VERIFIED: kubectl env names + public API, this session]. So `placeGuestOrder` takes the card branch, persists a **DRAFT** with `paymentStatus=PENDING`, creates a PaymentIntent and returns a `clientSecret` (`PublicStorefrontService.java:1547-1603`). The browser then calls `getStripe()`, which reads `process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`; the running staging chunk contains that expression **un-inlined** (`t.default.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`) and zero `pk_` literals, so it is `undefined`, `loadStripe` is never called, `stripePromise` stays `null`, and the render guard `if (paymentState && stripePromise)` (`page.tsx:1245`) silently falls through to the unchanged form (`page.tsx:776-782, 1075`). The value can never be inlined because `frontend/Dockerfile` declares no `ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` and the CI `build-args` block does not pass one (`ci-cd.yaml:1859-1865`); `k8s/scripts/check-env-contract.sh:229` already records it as a reviewed omission ("Card checkout is therefore inert in k8s"). The "Unpaid" fallback the scope named as a suspect is **not** involved on staging — it fires only when `paymentService.isConfigured()` is false (`PublicStorefrontService.java:1604-1635`; it moved from the cited 1186-1194, which is now `replayConfirmation`).

**#958 is a guard placed on one edge of a three-edge path.** `OrderService.transitionOrder` checks payment only when `newStatus == PREPARING` (`OrderService.java:797-803`). `SUBMIT` (DRAFT→PENDING) and `CONFIRM` (PENDING→CONFIRMED) are unguarded in both `OrderService` and `OrderStateMachineConfig` (`:47-61`), and the customer's "Received"/"Confirmed" emails are sent from the outbox events those two transitions publish (`OrderService.java:853-855` → `EmailNotificationService.java:154-171`). The existing predicate `isAwaitingUpfrontOrDepositPayment` (`OrderService.java:1094-1102`) is already correct for card and link orders and false for vendor-created orders (`paymentStatus` default `NONE`, `Order.java:234`), so extending it to SUBMIT and CONFIRM closes the hole without touching vendor phone/MCP orders. The kitchen toast swallows the server's reason (`kitchen/page.tsx:621-631`, bare `catch {}`), while `describeLoadError` (`lib/human-error.ts:70`) already extracts RFC 7807 `detail`.

**PAY-01 and PAY-03 each have a hard blocker the 2026-10-17 report did not surface.** PAY-01: no Stripe key reaches any CI stack (no workflow references a Stripe secret), and turning keys on in the nightly flips **every** shop to card (`acceptsCardPayments` is a global `isConfigured()`), which breaks `cash-checkout-no-stripe.spec.ts` and the cash-cap arms of `abuse-bounds.spec.ts`; the skip-budget gate also does not enforce the "justification + REMOVE WHEN" the roadmap criterion requires. PAY-03: the code verifies webhooks against **one** secret (`PaymentService.java:376`), but Express onboarding completion (`account.updated`) and every direct-charge `payment_intent.*` / `refund.*` event are **Connected-accounts-scope** events that Stripe delivers only to a `connect=true` endpoint with its own signing secret [CITED: docs.stripe.com/connect/webhooks]. The only staging endpoint (`we_1USBcpDpS9ICNeRt3kIwcIvC`) is the platform-scope one. Without a Connect endpoint the staging tenant can never reach `stripeConnectStatus=ENABLED`, so routing stays empty and every staging charge is a plain platform charge — exactly what the 29-18 seed order recorded ("a plain platform charge").

**Primary recommendation:** six serial, separately-mergeable PRs — (1) #957 publishable key through the build + fail-loud checkout + CI/runtime key checks; (2) #958 payment gate on SUBMIT/CONFIRM + typed `awaiting-payment` problem + UI reason + status-preserving webhook; (3) #108 Stripe timeouts/breakers; (4) Connect webhook endpoint support (second secret, KV/SPC wiring, unknown-order ack); (5) PAY-01 nightly Stripe lane + refund spec that makes its own paid order + skip-budget S-5; (6) PAY-03 staging money-loop script with Stripe-API evidence. Owner actions (publishable key var, CI sandbox key, Connect endpoint registration, onboarding in the hosted flow) are `checkpoint:human-action` tasks before the PR that needs them merges.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|---|---|---|---|
| Publishable key availability in the browser | Frontend build (Dockerfile ARG + CI build-arg) | CI deploy gate (vars prefix check) | `NEXT_PUBLIC_*` is inlined at `next build`; no runtime env can reach the browser bundle |
| Fail-loud when card payment cannot start | Browser (checkout page) | API (public shop `acceptsCardPayments`) | The browser alone knows whether `loadStripe` can run; the server already discloses card-mode before POST |
| Payment gate on SUBMIT/CONFIRM/START_PREP | API (`OrderService.transitionOrder`) | — | Single chokepoint for dashboard, kitchen, REST and MCP; client gating is UX only |
| Customer "Received/Confirmed" emails | API (outbox event → notification listener) | — | Emails follow committed transitions; blocking the transition blocks the email |
| Refusal reason in the kitchen/orders UI | Browser | API (typed RFC 7807 problem) | Server owns the reason; UI renders `detail` |
| Webhook verification (platform + Connect) | API (`PaymentController` / `PaymentService`) | Platform (Key Vault → CSI → Secret → env) | Signature verification is server-side; secrets arrive via KV |
| Connect onboarding status | API (`account.updated` handler) | Stripe (Connect-scope endpoint) | Tenant routing reads cached `stripe_connect_status` |
| Stripe outbound timeouts / breakers | API (`PaymentService.init`, resilience4j) | — | Static SDK config + annotations |
| E2E Stripe lane | CI (`e2e-nightly.yml`) | Stripe CLI (webhook forwarding) | No public URL for a runner's compose stack |
| PAY-03 evidence | Operator script (bash, Key Vault read, Stripe API reads) | Phase evidence files | Evidence is read from Stripe's API, never an app log |

---

## #957 — Root Cause (P0)

### Trace, each step with evidence

| # | Step | Evidence |
|---|---|---|
| 1 | Staging `core-java` has Stripe configured | `kubectl --context jtoye-stg-aks -n jtoye-staging get deploy core-java` env names: `STRIPE_API_KEY` ← secret `stripe-credentials`, `STRIPE_WEBHOOK_SECRET` ← secret `stripe-credentials`; secret keys: `api-key`, `webhook-secret` [VERIFIED: kubectl, names only, this session]. Wiring: `k8s/base/core-java-deployment.yaml:632-643` (`optional: true` refs) and KV objects `stripe-credentials--api-key`, `stripe-credentials--webhook-secret` (`k8s/components/azure-keyvault/secretproviderclasses.yaml:115-119, 187-193`) [VERIFIED: read] |
| 2 | Server discloses card mode | `curl https://api-staging.olajay.co.uk/api/v1/public/shops/staging-test-kitchen` → `"acceptsCardPayments": true` [VERIFIED: read-only GET]; source `PublicStorefrontService.java:1787-1790` (`dto.setAcceptsCardPayments(paymentService.isConfigured())`) |
| 3 | Order created as DRAFT + PaymentIntent | `placeGuestOrder`: `order.setStatus(OrderStatus.DRAFT); order.setPaymentStatus(PaymentStatus.PENDING);` (`:1263-1264`); card branch `if (paymentService.isConfigured())` → `saveAndFlush` → `paymentService.createPaymentIntent(order)` → `clientSecret = intent.clientSecret()` and `order.setPaymentReference(intent.paymentIntentId())` (`:1550-1581`); no outbox event for card orders (`:1658-1662`, only when `clientSecret == null`) |
| 4 | Response carries `clientSecret` | `GuestOrderConfirmation(..., clientSecret, ...)` (`:1674-1693`); signed-in customers reach the same backend through `frontend/app/api/customer-orders/route.ts:108` (`${coreBaseUrl()}/public/shops/${shop}/orders`) |
| 5 | Browser enters the card branch | `page.tsx:1051` `if (!confirmation.clientSecret)` false → `:1075` `setStripePromise(getStripe())` → `:1076` `setPaymentState({...})` |
| 6 | `getStripe()` returns null | `page.tsx:776-782`: `const publishableKey = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY; if (!stripeRef.current && publishableKey) { stripeRef.current = loadStripe(publishableKey) } return stripeRef.current` |
| 7 | Render silently falls through | `page.tsx:1245` `if (paymentState && stripePromise) {` — false, so the step-1 form renders again; `finally { setSubmitting(false) }` (`:1227-1228`) re-enables Place order. No error, no spinner — matches the issue |
| 8 | The running bundle never had the key | Served chunk `__next_static_chunks_0ka2f28yq1wp8.js` on `app-staging` contains `ty((e=t.default.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY,!tb.current&&e&&(tb.current=(0,x.loadStripe)(e)),tb.current` — un-inlined; `pk_test_`/`pk_live_` count across all 22 checkout chunks = **0**; control: `api-staging.olajay.co.uk` (the inlined `NEXT_PUBLIC_API_URL`) found **1** time in the same scan, so the scan can see an inlined value [VERIFIED: read-only fetch of the running artifact, image `ghcr.io/bralabee/jtoye-frontend:4f3f3e14…`] |
| 9 | Why it is not inlined | `frontend/Dockerfile` declares `ARG`/`ENV` for `NEXT_PUBLIC_API_URL`, `_CUSTOMER_KEYCLOAK_URL`, `_SUPPORT_EMAIL`, `_SUPPORT_URL`, `_ONBOARDING_REVIEW_SLA_DAYS`, `_COMPANY_REGISTERED_OFFICE`, `_DATA_PROTECTION_EMAIL` (`:51-84`) — **no** `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`; CI `build-args` (`ci-cd.yaml:1859-1865`) passes six `vars.FRONTEND_PUBLIC_*` — none Stripe; `gh variable list` shows no Stripe var [VERIFIED]. The Dockerfile's own comment (`:26-34`) records that an absent name survives as a `process.env.NAME` lookup — in the browser that resolves to nothing |
| 10 | Already known, not acted on | `check-env-contract.sh:229`: `'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY|Reviewed omission ... Card checkout is therefore inert in k8s. Belongs with the payments-enablement work...'`; compose has the same defect (#814): runtime-only `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY: ${NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY:-}` (`docker-compose.full-stack.yml:609`), no build arg |

CSP is already correct for Stripe Elements on staging: `script-src … 'strict-dynamic' https://js.stripe.com https://*.js.stripe.com`, `frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com`, `connect-src … https://api.stripe.com https://*.stripe.com` [VERIFIED: `curl -sI` on app-staging, matches `frontend/lib/security-headers.ts:92-99`]. Whether `strict-dynamic` admits the `/pure` dynamic script insert is `[ASSUMED]` (a script created by a nonced script via `createElement` is trusted under CSP3) — the browser E2E is the proof.

### What the fix must contain (recommended, prescriptive)

1. **Build channel.** `frontend/Dockerfile`: `ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` + `ENV …=${…}`, **optional** (not in the CR-02 fail-fast gate — compose and local builds without keys must still build; absence must be handled loudly in the page instead). `ci-cd.yaml` build-args: `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=${{ vars.FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY }}` (a publishable key is public by design, so a repo **variable**, consistent with the CR-02 convention). `docker-compose.full-stack.yml` frontend `build.args` gets it too and the dead runtime `environment:` line goes (closes #814's frontend half). Remove the `check-env-contract.sh:229` allowlist entry in the same PR — the gate reports a STALE entry once the Dockerfile declares the ARG (`check-env-contract.sh:714`).
2. **CI deploy gate.** In `deploy-staging`, beside the CR-02 api-origin check (`ci-cd.yaml` ~2001 pattern): fail if `vars.FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY` is empty **or** does not match `^pk_test_[A-Za-z0-9]+$` (D-28: test mode only on staging). The production deploy job gets the mirror check refusing `pk_test_` — or, since production is gated off and the pipeline builds ONE frontend image per commit (`ci-cd.yaml` CR-02 comment ~2001-2030), record that a pk_test-baked image can never be promoted to production and leave per-environment images to Phase 32 (owner decision, Open Question 1).
3. **Runtime-artifact check** (the issue's acceptance: "read the key out of the running artifact"). A script that fetches the served checkout chunks from `app-staging` and asserts exactly one `pk_test_` literal and zero `process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`/`.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` residues, with the `api-staging.olajay.co.uk` control count ≥ 1 (VOID otherwise). Wire it into the `staging-edge-probe` job.
4. **Fail loud in the page, and leave no DRAFT behind** (issue acceptance arm 2). Before POST: if `shop.acceptsCardPayments === true` and the publishable key is empty, do not POST; render a `role="alert"` message ("Card payments are unavailable right now, so this order can't be placed. Nothing has been charged.") and keep Place order disabled with that message as `aria-describedby` (the `belowMinimum`/K3 precedent on this page). After POST (defence in depth): if a `clientSecret` arrives and `getStripe()` is null, render the same alert instead of the form — never fall through silently.
5. **Order page copy for DRAFT** (SW-JRD-03): `frontend/lib/order-status-announcement.ts:22-30` has no `DRAFT` entry and the order page shows "Order in Progress" for a DRAFT (`orders/[orderNumber]/page.tsx:369-376`); add an "Awaiting payment" state for `status === "DRAFT"`.

---

## #958 — Root Cause (P1)

| Fact | Evidence |
|---|---|
| The only payment guard is on PREPARING | `OrderService.java:797-803`: `if (newStatus == OrderStatus.PREPARING && isAwaitingUpfrontOrDepositPayment(order)) { throw new InvalidStateTransitionException("Order " + … + " cannot enter kitchen preparation until upfront or deposit payment is completed (#461)."); }` |
| SUBMIT and CONFIRM are unguarded | `OrderStateMachineConfig.java:47-61`: `DRAFT → PENDING` on `OrderEvent.SUBMIT`, `PENDING → CONFIRMED` on `OrderEvent.CONFIRM`, no `.guard(` anywhere in `core/order/` [VERIFIED: rg, 0 hits] |
| Every transition entry funnels through `transitionOrder` | `submitOrder` `:632-633`, CONFIRM `:641`, START_PREP `:649`, … CANCEL `:688`; `OrderController.java:324` comment "all six transitions funnel through OrderService.transitionOrder" |
| Emails come from the committed transition | `transitionOrder` publishes `eventPublisher.publishStateChange(..., oldStatus, newStatus)` after save (`:853-855`); `EmailNotificationService.sendOrderConfirmation` → template `"order-received"`, subject `"Received"` (`:154-160`); `sendOrderConfirmed` → `"order-confirmed"`, `"Confirmed"` (`:164-171`) |
| Card DRAFT state | `status DRAFT`, `paymentStatus PENDING`, `paymentReference pi_…` (`PublicStorefrontService.java:1263-1264, 1581`) |
| Predicate already right for SUBMIT/CONFIRM | `isAwaitingUpfrontOrDepositPayment` (`OrderService.java:1094-1102`): `PENDING` or `FAILED` → true; else `paymentReference != null && ps != CAPTURED && ps != AUTHORIZED`. Vendor/REST/MCP-created orders default `paymentStatus = PaymentStatus.NONE` (`Order.java:234`) and no reference → false → unchanged behaviour. Split-deposit after deposit capture → `CAPTURED` → allowed |
| Problem shape today | `GlobalExceptionHandler.java:102-108`: 400, title `"Invalid State Transition"`, type `https://jtoye.uk/errors/invalid-state-transition` [VERIFIED: read] |
| Kitchen swallows the reason | `frontend/app/dashboard/kitchen/page.tsx:621-631`: `} catch { toast({ variant: "destructive", title: "Error updating order", description: \`Failed to ${action.label.toLowerCase()}. Reverting.\` }) … }` |
| Orders page already reads `detail` | `frontend/app/dashboard/orders/page.tsx:604-617` uses `describeLoadError(error, …)` (`lib/human-error.ts:70-98`, `response.data?.detail` first) — the issue's "Error start prep order" is the toast **title**; the reason should be the description there already. Kitchen is the real gap |
| Orders list offers Submit on any DRAFT | `orders/page.tsx:215-225` `DRAFT: [{ action: "Submit", endpoint: "submit", nextStatus: "PENDING", … }]`; `OrderDto` carries `paymentStatus` (`OrderDto.java:45`, `frontend/types/api.ts:579`) so the UI can label and hide |

### Second defect found while tracing (same PR)

`PaymentService.handlePaymentIntentSucceeded` sets `order.setStatus(OrderStatus.PENDING)` **unconditionally** and publishes `DRAFT → PENDING` (`PaymentService.java:521-532`). Payment links may be issued at any non-terminal status, CONFIRMED and PREPARING included (`OrderPaymentLinkService.java:114-120` refuses only voided/CANCELLED/REFUNDED/COMPLETED). A link paid on a CONFIRMED order therefore **regresses it to PENDING** and sends a second "Received" email. Fix: change status (and publish the event) only when `order.getStatus() == OrderStatus.DRAFT`; otherwise update payment fields only. This is the asymmetric-ordering rule in AGENTS.md `oaas-money`.

### Recommended state machine for card / link orders

```
DRAFT (paymentStatus PENDING, pi_ set)  --payment_intent.succeeded (webhook only)-->  PENDING  --CONFIRM-->  CONFIRMED  --START_PREP-->  PREPARING ...
   |  SUBMIT  -> REFUSED: awaiting-payment          (no event, no "Received" email)
   |  CONFIRM -> REFUSED (state machine already denies DRAFT->CONFIRMED)
   |  CANCEL  -> allowed (vendor may clear an abandoned DRAFT)
PENDING with paymentStatus PENDING/FAILED (link issued on a vendor order)
   |  CONFIRM -> REFUSED: awaiting-payment          (no "Confirmed" email)
   |  START_PREP -> REFUSED (existing guard)
```

- Gate: in `transitionOrder`, `if ((event == SUBMIT || event == CONFIRM || newStatus == PREPARING) && isAwaitingUpfrontOrDepositPayment(order)) throw new AwaitingPaymentException(...)` — evaluated **before** `order.setStatus`, so the outbox row, the stock decrement at CONFIRMED and the emails never happen.
- Typed problem (agent-readiness contract): a new exception mapped to type `https://jtoye.uk/errors/awaiting-payment`, title `"Awaiting payment"`. Status code: keep **400** for consistency with `invalid-state-transition` unless the owner prefers 409 `[ASSUMED: either is defensible; 409 Conflict is the more accurate HTTP semantics]`. The `detail` must not leak an issue number (today's text ends "(#461)" and reaches staff UI). Recommended detail: `"Order ORD-… is awaiting payment. It can't be submitted, accepted or prepared until the customer's payment completes."` Add the type to the OpenAPI snapshot (`scripts/check-openapi-snapshot-fresh.sh` runs nightly) and to the MCP server's error mapping if it maps types `[ASSUMED: not checked]`.
- UI: kitchen `handleBump` catch uses `describeLoadError` and shows its `message`; orders list shows an "Awaiting payment" badge and hides Submit/Confirm when `paymentStatus` is `PENDING`/`FAILED` and `paymentReference` is set (UX only — server stays authoritative).
- Tests that must go red first on the current tree: `submitOrder` on a card DRAFT → today 200 (red), after → 400/409 typed; `confirmOrder` on PENDING + paymentStatus PENDING → today 200; outbox row count for the order unchanged on refusal; a vendor-created DRAFT (`NONE`) still submits (regression arm); payment-succeeded on a CONFIRMED link order keeps CONFIRMED.

---

## PAY-01 — Refund E2E against real Stripe test mode

### Current state

- `scripts/gates/e2e-skip-budget.conf:39-44` [VERIFIED: read]:
  ```
  # 1 test x 2 projects. Issues a REAL partial refund, and RefundService calls
  # Stripe.Refund.create. STRIPE_API_KEY is empty on the dev stack. Deliberately NOT faked:
  # ...
  # REMOVE WHEN: Stripe test-mode keys are provisioned for the dev/CI stack.
  ALLOW vendor-refund-flow.spec.ts › vendor logs in, opens an order, issues a partial refund
  ```
  and `MAX_SKIPS 6` (`:83`), measured as 4 (stomp-relay) + 2 (refund, desktop+mobile).
- The gate enforces S-1..S-4 only (`check-e2e-skip-budget.sh:28-33`, parse loop `:157-176`): comment lines are skipped (`''|'#'*) continue`), so **nothing checks that an ALLOW has a justification or a `REMOVE WHEN:`**. Re-adding the refund ALLOW while the test still skips passes S-2; while the test passes, S-3 ("stale ALLOW") already fails — but that is not the roadmap criterion.
- `frontend/e2e/vendor-refund-flow.spec.ts:93-183`: the refund test **looks for** a refundable row (`CONFIRMED|PREPARING|READY|COMPLETED`, not `REFUNDED`) and an "Issue refund" button, and `test.skip`s at `:119-124` / `:142-147` when either is missing. It asserts only UI (dialog closes, `Refunds (n)` heading, `£1.00`). It never reads Stripe, `processed_stripe_events` or the outbox, which #61's acceptance names.
- No workflow references any Stripe secret (`rg 'secrets\.' .github/workflows` → only `GITHUB_TOKEN`, `KUBE_CONFIG_PRODUCTION`, `SLACK_WEBHOOK_URL`, `SNYK_TOKEN`, `GITLEAKS_LICENSE`) [VERIFIED]. `gh secret list` returned empty with rc 0 (whether the token can list secrets is UNVERIFIED).
- The nightly builds its stack from `.env.example` + generated credentials (`e2e-nightly.yml:117-135`); `.env.example:287-291` leaves `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` empty.

### What "for real" requires (and why not stripe-mock)

The requirement says "against Stripe, not a mock", and `e2e-skip-budget.conf:41-42` already rejects faking a CAPTURED row. stripe-mock is stateless (it does not keep objects, does not move money, does not emit webhooks) `[ASSUMED: from stripe-mock's README; not fetched this session]`, so it cannot prove a refund against a captured charge. Use **real Stripe test mode**.

A refundable order needs a real captured PaymentIntent **and** the webhook that moves the order to `paymentStatus=CAPTURED` (`PaymentService.java:521-526`). A runner's compose stack has no public URL, so use the Stripe CLI: `stripe listen --forward-to http://localhost:9090/api/v1/public/payments/webhook` (the CLI forwards Connect events to the same URL by default; `--print-secret` prints the signing secret and exits; "The webhook signing secret provided will not change between restarts") [CITED: docs.stripe.com/cli/listen]. Get the secret first, put it in `.env` as `STRIPE_WEBHOOK_SECRET`, then start `core-java`.

### Recommended design

1. **Spec creates its own paid order** (the DRAFT test at `:185-` already creates its own order through the vendor API — same pattern): place a storefront guest order at `mama-ades-kitchen` → take the intent id from the `clientSecret` prefix in memory (29-18 pattern) → `POST https://api.stripe.com/v1/payment_intents/{id}/confirm` with `payment_method=pm_card_visa` using the runner's `sk_test_` → poll public tracking until `paymentStatus=CAPTURED` (webhook) → vendor `confirm` via API → then the UI refund walk. Assert after the UI: `GET /v1/refunds?payment_intent=pi_…` returns one refund of 100 pennies, `livemode:false`; the order detail shows `REFUNDED`. No `test.skip` on key absence — **fail** with a reason (so a keyless run is red, and an undeclared skip cannot arise). Tag it `@desktop-only` (precedent: `playwright.config.ts:88, 113`) to halve real Stripe calls per night; recount MAX_SKIPS from a fresh report, never by arithmetic (`e2e-skip-budget.conf:78-82`).
2. **A Stripe-keyed lane that does not break the cash specs.** `acceptsCardPayments` and the card branch both key on the global `paymentService.isConfigured()` (`PublicStorefrontService.java:1550, 1790`), so a keyed stack has **no cash shop**: `cash-checkout-no-stripe.spec.ts:121-124` hard-fails (`expect(shop.acceptsCardPayments …).toBe(false)`), and `abuse-bounds.spec.ts:345` skips its cash-cap test (an undeclared skip → S-2 red). Recommended: keep the main nightly run keyless; add a second phase in the same `e2e-nightly` job that (a) installs the Stripe CLI pinned by version + sha256 (the kustomize pattern, `ci-cd.yaml:2334-2341`), (b) starts `stripe listen` in the background with its PID captured, (c) recreates **only** `core-java` with `STRIPE_API_KEY` + the CLI secret (`docker compose up -d --no-deps --force-recreate core-java`), (d) runs `npx playwright test --grep @stripe`. The main run uses `--grep-invert @stripe`. Merge the two reports into one before the gate (Playwright blob reporter + `npx playwright merge-reports --reporter=json`, or a jq union) — whether `config.metadata.specDigest` survives `merge-reports` is `[ASSUMED]`; measure it, and if it does not, pass the gate each report with the same digest check. The frontend image can bake `pk_test_` for both phases: a cash confirmation calls `loadStripe` zero times regardless (`stripe-lazy-load.test.tsx:15`), and running the cash spec with the key present strengthens #793.
3. **Credentials.** GitHub Actions secret for the `sk_test_` (e.g. `STRIPE_TEST_SECRET_KEY`), variable for the `pk_test_`. **Use a separate Stripe sandbox for CI, not the staging account** (Pitfall 5). A guard step refuses anything not matching `^sk_test_` before use and never echoes it (`::add-mask::`).
4. **Gate change (S-5).** In `check-e2e-skip-budget.sh`: every `ALLOW` must be immediately preceded by a contiguous comment block that contains a non-empty justification line **and** a line matching `^#\s*REMOVE WHEN:\s*\S`; otherwise exit 1 naming the ALLOW. Add selftest arms (a stub conf with a bare ALLOW → 1; with justification but no REMOVE WHEN → 1; the real conf → 0). This is the "shown to fail if re-added without a justification and a REMOVE WHEN" criterion.
5. **Retire the ALLOW** (delete the four-line block, lower MAX_SKIPS to the measured value, likely 4) only in the PR whose nightly run shows the refund test **passed** — the S-3 stale-ALLOW check makes the order of operations self-enforcing.

---

## PAY-03 — Full loop on staging

### What exists

- Staging Stripe: TEST `sk_test_` key and `whsec_` in Key Vault `jtoye-stg-kv` (classified "external", prefix-checked: `scripts/staging-secrets.sh:236` refuses anything not starting with the expected prefix — "D-28: only Stripe TEST credentials enter staging"). Endpoint `we_1USBcpDpS9ICNeRt3kIwcIvC`, URL `https://api-staging.olajay.co.uk/api/v1/public/payments/webhook`, `api_version 2026-09-30.endive`, 10 events (`29-18` evidence line 31) [CITED: .planning/phases/29-…/evidence/29-18-stripe.txt]; the earlier endpoint `we_1USBGu…` listed `account.updated, charge.refunded, customer.subscription.deleted, invoice.paid, invoice.payment_failed, payment_intent.payment_failed, payment_intent.succeeded, refund.created, refund.failed, refund.updated` [CITED: 29-13-SUMMARY.md:139-143].
- Proven once on staging: a storefront order paid by `pm_card_visa`, settled by the platform webhook to CAPTURED in 2 s — "a plain platform charge" (`29-18-SUMMARY.md`), because the tenant has no ENABLED connected account.
- The staging shop `staging-test-kitchen` belongs to a `MARKETPLACE` tenant (`scripts/staging-seed.sh` `ONBOARDING_MODEL="MARKETPLACE"`), so its default routing is `DESTINATION_CHARGE_PLATFORM_MOR` (`StripeConnectService.java:239-241`).
- Routing needs `tenant.stripeConnectStatus == ENABLED` and a non-blank `stripeAccountId` (`:244-249`); otherwise `Optional.empty()` → platform charge (`PaymentService.java:213-225`).
- A shop-level override exists: `CreateShopRequest.settlementPolicyMode` (`CreateShopRequest.java:154, 209-210`), column `shops.settlement_policy_mode` (V87) — so **one tenant, one connected account, two shops** (the existing MARKETPLACE shop for destination; a second shop with `DIRECT_CHARGE_VENDOR_MOR`) covers both modes without a second tenant.
- Onboarding API: `POST …/{tenantId}/stripe/connect` on `TenantAdminController` (`:175-186`, platform-admin) → `createOrResumeExpressOnboarding` creates an **Express** account (`setType(EXPRESS)`, country from config, metadata `tenant_id`; no explicit `capabilities`) and an `AccountLink` with `stripe.connect.return-url`/`refresh-url` (`StripeConnectService.java:97-135`). Staging overlay sets those to `https://app-staging.olajay.co.uk/dashboard/payments/connect/{return,refresh}` (`k8s/staging/configmap-patch.yaml:152-153`) and both pages exist (`frontend/app/dashboard/payments/connect/{return,refresh}/page.tsx`).
- Status flips to ENABLED **only** in `handleAccountUpdated` (`StripeConnectService.java:152-172`), i.e. only on an `account.updated` webhook.

### Blocker: the Connect-scope webhook

Stripe splits events into two scopes: **Your account** (destination/indirect charges on the platform) and **Connected accounts** — "v1 `account.updated` events … for … connected accounts" and "Direct charges … for customers of your connected accounts". The scope is set per endpoint (`connect=false|true` on create, or "Events from" in Workbench), and each endpoint has its own signing secret ("If you're testing … with the Stripe CLI … Otherwise, find your endpoint's secret in your webhook settings") [CITED: docs.stripe.com/connect/webhooks].

Consequences on the current tree:
- `PaymentService.handleWebhookEvent` verifies with exactly one secret: `Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret())` (`:376`); `StripeProperties` has one `webhookSecret` (`:41`). The code itself says so: "the Connect webhook endpoint must be pointed at the SAME URL/secret as the platform endpoint (or this handler split per-secret — deferred to the keyed environment)" (`PaymentService.java:426-429`). Two Stripe endpoints cannot share a secret `[ASSUMED: Stripe generates the secret per endpoint; not settable]`.
- Without a Connect endpoint: onboarding never reaches ENABLED → no routing → neither settlement mode can occur on staging; direct-charge `payment_intent.succeeded` never reaches the app → a DIRECT order stays DRAFT/PENDING-payment even after the card succeeds; direct-charge `refund.*` never reaches the app.
- Destination charges' `payment_intent.succeeded`/`refund.*` are platform-scope and already arrive.

### Recommended changes (PR 4, before the loop)

1. **Code:** second property `stripe.connect-webhook-secret` (`${STRIPE_CONNECT_WEBHOOK_SECRET:}`), redacted in `StripeProperties.toString()` like the others (`:185-201`, `StripePropertiesRedactionTest` exists). A second route `POST /api/v1/public/payments/webhook/connect` (inside the already-permitted `/api/v1/public/**`, `SecurityConfig.java:164`) verifying against that secret and requiring `event.getAccount()` non-blank; the platform route requires it blank. Same dedup table, same dispatcher. Separate routes keep the two scopes observable and make the forged/unsigned negative arms per-route.
2. **Ack foreign events.** Today an event whose `order_id` is unknown throws `ResourceNotFoundException` inside the handler (`PaymentService.java:467-468`) — `PaymentController` catches only `IllegalArgumentException` (`:44-51`) — so Stripe sees non-2xx and retries. Any event from another environment sharing the Stripe account (CI, a laptop) becomes a retry storm against staging. Add `metadata.environment` (e.g. `staging`) to every PaymentIntent/Refund the app creates and acknowledge-and-skip (200, WARN log) events for other environments or unknown orders — after signature verification, before side effects.
3. **Platform wiring:** KV object `stripe-credentials--connect-webhook-secret` (external, `whsec_` prefix in `staging-secrets.sh`), SPC `objects` + `secretObjects` key `connect-webhook-secret` (`secretproviderclasses.yaml:115-119, 187-193`), core-java env `STRIPE_CONNECT_WEBHOOK_SECRET` (`optional: true`, next to `:638-643`), `check-env-contract.sh` secret count, `check-keyvault-chain.sh`/preflight re-run.
4. **Owner action:** register a **Connected accounts** endpoint in the staging TEST account at `https://api-staging.olajay.co.uk/api/v1/public/payments/webhook/connect` with `api_version=2026-09-30.endive` (stripe-java 34.0.0 pins `ApiVersion.CURRENT = "2026-09-30.endive"` [VERIFIED: stripe-java-34.0.0-sources.jar, com/stripe/ApiVersion.java]; 29-13 learned the hard way that an unpinned endpoint uses the account default `2026-07-29.dahlia`), events: `account.updated, payment_intent.succeeded, payment_intent.payment_failed, refund.created, refund.updated, refund.failed, charge.refunded` (+ `payout.paid, payout.failed` if the loop should observe them). Store the `whsec_` through `staging-secrets.sh --set-external` (write-once object; rotation runbook). Also confirm **Connect is enabled** on the staging Stripe account and Express capabilities default to `card_payments` + `transfers` for GB `[ASSUMED: not checkable without the dashboard]`.

### The loop (PR 6) and how evidence is captured

| Step | How | Stripe-side evidence (read by id, `livemode:false`) |
|---|---|---|
| Onboard | Admin API `POST /api/v1/admin/tenants/{id}/stripe/connect` → open the AccountLink URL in a browser and complete Express onboarding with Stripe's test values (human step — `checkpoint:human-action`) → wait for `account.updated` | `GET /v1/accounts/{acct}`: `charges_enabled:true`, `payouts_enabled:true`, `capabilities.card_payments/transfers = active`; local `tenants.stripe_connect_status = ENABLED` |
| Order (destination) | Browser checkout on `staging-test-kitchen` (needs PR 1) with card **4000 0000 0000 0077** (`pm_card_bypassPending`: "Funds are added directly to your available balance") [CITED: docs.stripe.com/testing] | `GET /v1/payment_intents/{pi}`: `transfer_data.destination = acct_…`, `application_fee_amount` = plan fee, `status:succeeded`, metadata `settlement_mode=DESTINATION_CHARGE_PLATFORM_MOR` |
| Order (direct) | Second shop with `settlementPolicyMode=DIRECT_CHARGE_VENDOR_MOR`; same card | `GET /v1/payment_intents/{pi}` **with `Stripe-Account: acct_…`**: `application_fee_amount`, `status:succeeded`; Connect-scope `payment_intent.succeeded` event delivered (`pending_webhooks:0`) |
| Capture | Automatic capture (PaymentIntent default) | `GET /v1/charges/{ch}` (`captured:true`, `balance_transaction`) — per account |
| Payout to the connected account | Manual payout on the connected account: `POST /v1/payouts` with `Stripe-Account` (may require `settings.payouts.schedule.interval=manual` first) `[ASSUMED: platform can create payouts for Express accounts it controls; verify before planning the step as automatic]` | `GET /v1/payouts/{po}` with `Stripe-Account`: `status` → `paid`, `destination ba_…`; for destination charges also `GET /v1/transfers/{tr}` (from `ch.transfer`) |
| Refund | Vendor dashboard "Issue refund" (or `POST /api/v1/orders/{id}/refund`), both orders | Destination: `GET /v1/refunds/{re}` + `transfer_reversal` present (`reverse_transfer=true`, `RefundService.java:214-218`); Direct: `GET /v1/refunds/{re}` with `Stripe-Account` (`:209-213`); local `refunds` row status = Stripe's |

**Evidence mechanism (reuse, don't invent):** extend `scripts/staging-seed.sh` with `--stage money-loop` (or a sibling script that reuses its helpers verbatim): `azure_guard_once` (azure-guard with the pinned subscription), `az_read` of `stripe-credentials--api-key` refused unless `^sk_test_[A-Za-z0-9]+$`, key only in a mode-600 curl `-K` file, `stripe_api` + `assert_test_mode` on every object (anything but `livemode:false` is REFUSED), one verdict line per step with object ids, selftest arms with stubbed Stripe answers each shown red by a planted defect (the 29-18 pattern: `scripts/staging-seed.sh` header and `29-18-SUMMARY.md` "patterns-established"). Write `evidence/30-pay03-staging-loop.json` in the phase dir containing only ids/amounts/statuses — no client secrets, no keys; run `scripts/staging-secrets.sh --leak-scan` and gitleaks over the evidence with their controls.

### The 2026-10-17 report (30-VERIFICATION.md) — treat as UNVERIFIED for PAY-03

- Every object id in it carries the stem `…DBRetDCIGQ…` / `…DBRe…`; every staging object carries `…DpS9ICNeRt…` (`we_1USBcpDpS9ICNeRt3kIwcIvC`, `pi_3USHS1DpS9ICNeRt1nB7iIZm`, `evt_3USHS1DpS9ICNeRt19ESTYVa`). The staging Key Vault key equals the repo `.env` key (`29-18-SUMMARY.md` key-decisions). So the report was very likely produced on **another Stripe test account** whose objects the staging key cannot read `[ASSUMED: Stripe does not document its id format; inferred from the shared stems]`. It cannot satisfy "on staging".
- Internal inconsistency to resolve before anyone cites it: the destination charge is `ch_3URXk7…` but its transfer is `tr_3URXhV…`; for a destination charge Stripe creates the transfer as part of the charge, so a transfer from an earlier stem suggests it belongs to a different charge `[ASSUMED]`. Re-read by id with the key that created it, or drop the claim.
- It was "laptop-run" (scope note); its PAY-01 row is an API refund, not the E2E the requirement names.

---

## PAY-02 — confirm only (do not re-plan)

Exists on the tree [VERIFIED: files listed/read]: `StripeBillingService`, `BillingController` (`/api/v1/billing/subscription`, `GET`, `POST`, `/complete-walk-setup`, `/cancel`), `BillingPlanMode`, V88 `tenants.billing_plan_mode` with `ck_tenants_billing_plan_mode`, `StripeProperties.Billing` defaults (`hybridMonthlyPennies = 3900L`, `hybridFeeBps = 50`, `flatMonthlyPennies = 7900L`, `flatMaxMonthlyPennies = 14900L`, `paygMonthlyPennies = 0L`, `paygFeeBps = 600`, `walkSetupMonthlyPennies = 19500L`, `walkOngoingFeeBps = 250`, `walkMonthlyCapPennies = 3900L`; `StripeProperties.java:101-123`), `StripeBillingServiceTest`. Staging endpoint is subscribed to `invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`.

Findings for the owner's acceptance (not re-planned here):
1. **Not "per location".** `Subscription.create` adds one item with `setPrice(price.getId())` and **no quantity** (`StripeBillingService.java:124-136`; no `setQuantity` in the file) — a tenant with three shops pays £39, not £117.
2. A new `Price` is created on every subscribe (`Price.create(...)` `:114-123`, no `lookup_key` reuse) and `Subscription.create` has no idempotency key — a retried subscribe can double-bill `[ASSUMED: SDK retries without a key create duplicates; the PaymentIntent path documents exactly this at PaymentService.java:140-146]`.
3. No vendor billing UI (`rg 'billing/' frontend/app frontend/components frontend/lib` → 0) — UXT-033 in #102 stands.
4. Evidence is from the other Stripe account (above). An invoice retrieved by id **on staging** would make it falsifiable on the delivered estate.

## PAY-04 — confirm only (do not re-plan built work)

Exists [VERIFIED]: `PhoneVerificationService`, `OrderPaymentLinkService` (single-use, `used_at`, TTL capped at 10080 min, prior unused links revoked, phone must be verified → 422), `OrderPaymentLinkController` `POST /api/v1/orders/{orderId}/payment-links`, `PublicPhoneAndPaymentLinkController` (`/shops/{slug}/phone-verification/send|verify`, `/payment-links/{token}/redeem`), V88 tables, RLS integration test.

Gaps the plan must at least surface (owner decides scope):
1. **The link opens a 404.** `paymentUrl = baseUrl + "/pay/" + rawToken` (`OrderPaymentLinkService.java:200`; `baseUrl` from `jtoye.payment-links.base-url`, default `notification.email.tracking-base-url`, `:69`). There is no `frontend/app/pay*` route and no consumer of `redeem` anywhere in `frontend/` (`rg` → 0); `https://app-staging.olajay.co.uk/pay/doesnotexist-probe` → **404**, control `/track` → 200 [VERIFIED: read-only GET]. A "payment request reaches the buyer … as a link" whose link cannot be paid does not meet PAY-04 in substance. Recommended minimal add: `/pay/[token]` page that calls `redeem`, mounts the Payment Element with the returned `clientSecret` (reuses the #957 key fix), and shows 410/expired copy.
2. **"An unpaid order cannot be produced" holds only for orders with a payment attempt.** `paymentStatus=NONE` orders (vendor/REST/MCP-created, and the storefront "Unpaid" fallback when Stripe is unconfigured, `PublicStorefrontService.java:1620-1634`) pass every guard. ROADMAP criterion 7(d) requires the fallback be "removed or gated deliberately". Recommended: an explicit property (e.g. `jtoye.payments.allow-unpaid-storefront-orders`, default **false**, `true` only in compose) and a prod-profile test refusing `true`; the vendor-created-order half is a policy-scope question for the owner (Open Question 3). This is implementation of a settled policy, not a re-opening of it.
3. **SMS/WhatsApp delivery is not configured on staging**: no `WHATSAPP*`/`SMS*`/`TWILIO*` env names on staging `core-java` [VERIFIED: kubectl names]; `WhatsAppProperties.enabled` defaults false (ROADMAP 7(c)). OTP and link delivery to a phone is unproven on the delivered estate (already recorded as "delivery to a real phone not shown").
4. **`jtoye.phone-verification.expose-otp-for-test`** (`PhoneVerificationService.java:58`, default `false`, returns the OTP in the response when true, `:143`) has no prod-profile refusal. Add it to the SEC-03 family (`OpenApiProdProfileGatingTest`/`SecurityHeadersProdProfileTest` pattern).

RESOLVED (scope set by 30-CONTEXT.md, 2026-10-21): gap 1 → D-03, the `/pay` page is built (plans 30-16..30-20). Gap 2 → D-01, vendor/REST/MCP `NONE` orders stay unguarded; that gap, and the storefront "Unpaid" fallback that only fires when Stripe is unconfigured server-side, are recorded with their route by plan 30-32. Gap 3 → recorded as a known gap by plan 30-32 (route: issue #461 / #208). Gap 4 → plan 30-17 adds the refusal, keyed on `stripe.environment-tag=production` because staging also runs the prod Spring profile.

## #108 — outbound timeouts (NOT done)

- No Stripe timeout is set anywhere in `core-java` (`rg setConnectTimeout|setReadTimeout|maxNetworkRetries` → only a comment at `PaymentService.java:140`) [VERIFIED]. stripe-java 34.0.0 defaults: `DEFAULT_CONNECT_TIMEOUT = 30 * 1000`, `DEFAULT_READ_TIMEOUT = 80 * 1000`, `maxNetworkRetries = 2`; setters `Stripe.setConnectTimeout(int)`, `Stripe.setReadTimeout(int)`, `Stripe.setMaxNetworkRetries(int)`, per-request `RequestOptionsBuilder.setConnectTimeout(Integer)`/`setReadTimeout(Integer)` [VERIFIED: stripe-java-34.0.0-sources.jar `com/stripe/Stripe.java:9-35,111-169`, `com/stripe/net/RequestOptions.java:306,324`]. Worst case today: 80 s × 3 attempts on a request thread at checkout.
- Breakers: `@CircuitBreaker(name = "stripe")` on `createPaymentIntent`, `createBalancePaymentIntent`, both `retrieveClientSecret`, `createOrResumeExpressOnboarding`; **not** on `StripeRefundClient.create` (`StripeRefundClient.java:22-24`), `Charge.retrieve` in the webhook (`PaymentService.java:474-478`), or `StripeBillingService` calls.
- `resilience4j.circuitbreaker.instances.email` is defined (`application.yml:994-999`) with zero `@CircuitBreaker(name = "email")` uses [VERIFIED: rg → 0] — still dead config.
- SMTP: the `spring.mail.properties.mail` block (`application.yml:147-160`) carries STARTTLS settings and no `mail.smtp.connectiontimeout/timeout/writetimeout` [VERIFIED: rg]. axios: `frontend/lib/api-client.ts:29-30` `axios.create({ baseURL: … })` with no `timeout` [VERIFIED].
- Recommended for Phase 30: the **Stripe half** (config-driven `stripe.connect-timeout-ms`/`read-timeout-ms`/`max-network-retries` applied once in `PaymentService.init()` beside `Stripe.apiKey`; breaker on refund create, `Charge.retrieve` and billing calls; a unit test that reads the static values back). The SMTP/axios/S3/email-breaker half either rides the same PR or stays on #108 with a comment — planner's call; STARTTLS must stay required when touching mail properties.

---

## Standard Stack

All already in the tree — **this phase adds no runtime library.**

| Component | Version (tree) | Purpose | Evidence |
|---|---|---|---|
| stripe-java | 34.0.0 | PaymentIntents, Connect, Refunds, Billing, webhooks | `core-java/build.gradle.kts:333` [VERIFIED]; pins API `2026-09-30.endive` [VERIFIED: sources jar] |
| @stripe/stripe-js | ^10.0.0 (registry latest 10.1.0) | `loadStripe` via `/pure` entry (lazy) | `frontend/package.json:29` [VERIFIED]; `npm view` 10.1.0 |
| @stripe/react-stripe-js | ^7.0.0 (latest 7.0.0) | `Elements`, `PaymentElement` | `frontend/package.json:28` [VERIFIED] |
| resilience4j (Spring Boot starter) | per build file | `@CircuitBreaker(name="stripe")` | `application.yml:971-979` |
| Playwright | per lockfile | E2E, two projects `mobile`/`desktop` | `frontend/playwright.config.ts:80-113` |
| Stripe CLI (CI tool only, new to CI) | local 1.54.0; pin a release + sha256 | `stripe listen` webhook forwarding in the nightly Stripe lane | local probe `stripe version 1.54.0`; docs [CITED: docs.stripe.com/cli/listen] |

### Alternatives considered

| Instead of | Could use | Why not |
|---|---|---|
| Real Stripe test mode in CI | stripe-mock | Stateless, no webhooks, no money movement `[ASSUMED]`; the requirement forbids a mock |
| Stripe CLI forwarding | A public tunnel + dashboard endpoint for the runner | New ingress surface per run; CLI is Stripe's documented local path |
| Two webhook routes | One route trying both secrets | Works, but loses per-scope observability and per-route negative arms; either is acceptable if the planner prefers fewer moving parts |
| Build-arg publishable key | Runtime config endpoint (`/api/config`) for the key | Larger change; the build-arg channel is the repo's enforced, gated channel (`check-env-contract.sh:549-560`) |
| npm `@stripe/cli` in CI | Pinned GitHub release tarball + sha256 | `@stripe/cli` runs a `postinstall` (`node scripts/postinstall.js`) and is flagged SUS (too-new); the repo already pins binaries by checksum |

**Installation:** none for runtime. CI only: Stripe CLI tarball from `github.com/stripe/stripe-cli/releases` pinned by version and sha256 (take the checksum from the release's `checksums.txt` and prove it locally first, as `ci-cd.yaml:2331-2341` did for kustomize).

## Package Legitimacy Audit

| Package | Registry | Age (latest publish) | Downloads | Source Repo | Verdict | Disposition |
|---|---|---|---|---|---|---|
| @stripe/stripe-js | npm | latest 2026-10-13 | 8,389,460/wk | github.com/stripe/stripe-js | SUS (`too-new`: the latest version is days old) | Already installed (`^10.0.0` in lockfile); **no install in this phase**. Do not bump it as part of this phase |
| @stripe/react-stripe-js | npm | latest recent | (seam) | github.com/stripe/react-stripe-js `[ASSUMED]` | SUS (`too-new`) | Already installed; no install in this phase |
| @stripe/cli | npm | latest 2026-10-19 | 32,020/wk | github.com/stripe/stripe-cli | SUS (`too-new`) + **postinstall `node scripts/postinstall.js`** | **Do not use.** Use the pinned release binary instead; if the planner nevertheless uses it, add `checkpoint:human-verify` |

**Packages removed due to [SLOP]:** none.
**Packages flagged [SUS]:** all three, only because the latest version is newer than the seam's age threshold; the two runtime ones are existing dependencies (no new install). The Stripe CLI binary is not an npm package; verify its sha256 against Stripe's published checksums (`checkpoint:human-verify` on first pin).

## Architecture Patterns

### System Architecture Diagram

```
 Browser (app-staging)                         core-java (api-staging)                          Stripe (TEST)
 ─────────────────────                         ───────────────────────                          ─────────────
 checkout page
   │ GET /public/shops/{slug} ───────────────► acceptsCardPayments = isConfigured()
   │   [NEW] card shop && no pk ─► alert, no POST
   │ POST /public/shops/{slug}/orders ───────► placeGuestOrder
   │                                            ├─ isConfigured? no ─► "Unpaid" PENDING/NONE  [gate: compose only]
   │                                            └─ yes ─► DRAFT/PENDING, saveAndFlush
   │                                                       └─ createPaymentIntent ─────────────► PI (platform | transfer_data | Stripe-Account)
   │ ◄── 201 {clientSecret} ───────────────────┘                                                   │
   │ loadStripe(pk_test_ baked at build) [FIXED]                                                    │
   │ PaymentElement.confirmPayment ────────────────────────────────────────────────────────────────►│ charge
   │                                                                                                │
   │                                  /webhook  (platform scope, secret A) ◄── payment_intent.* ────┤ (platform/destination)
   │                                  /webhook/connect [NEW] (secret B)    ◄── account.updated, ────┘ (connected accounts:
   │                                            │                              direct PI.*, refund.*   direct charges)
   │                                            ▼
   │                                 dedup processed_stripe_events → DRAFT→PENDING only if DRAFT [FIXED]
   │                                            │  outbox → "Received" email
 vendor dashboard / kitchen                     ▼
   │ submit/confirm/start-prep ────────► transitionOrder ── awaiting payment? ─► 4xx awaiting-payment [NEW]
   │ ◄── problem.detail shown in toast [FIXED]  └─ else state machine → outbox → "Confirmed" email
   │ Issue refund ─────────────────────► RefundService ─────────────────────────────────► Refund (reverse_transfer | Stripe-Account)
```

### Recommended PR sequence (serial; each merge auto-deploys staging)

| PR | Content | Depends on | Owner action before merge |
|---|---|---|---|
| 1 — #957 | Dockerfile ARG; CI build-arg; compose build arg (#814 frontend half); env-contract allowlist removal; deploy-staging `pk_test_` var check; runtime-artifact chunk check in `staging-edge-probe`; checkout fail-loud (pre-POST + post-POST); DRAFT "Awaiting payment" copy; Jest + staging browser E2E (4242 → confirmation → CAPTURED), break arm with key absent | — | Set repo var `FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY` = the **staging account's** `pk_test_` (same account as the KV `sk_test_`) |
| 2 — #958 | Gate SUBMIT/CONFIRM; typed `awaiting-payment` problem; webhook status-preserving; kitchen toast reason; orders-list badge/hide; OpenAPI snapshot | — (independent of 1) | — |
| 3 — #108 (Stripe half) | Timeouts + retries config; breakers on refund/retrieve/billing; tests | — | — |
| 4 — Connect webhook | Second secret + route; env-tagged metadata; unknown/foreign event ack; KV/SPC/secret-sync/env-contract | — | Register Connect endpoint (endive); store `whsec_` via `staging-secrets.sh --set-external`; confirm Connect enabled |
| 5 — PAY-01 | Refund spec makes its own paid order; `@stripe` tag; nightly Stripe phase with pinned CLI; report merge; S-5 gate + selftest; ALLOW retired after a green nightly | 1 (pk build-arg) | GH secret `sk_test_` of a **CI sandbox**; GH var `pk_test_` of the same sandbox |
| 6 — PAY-03 | `staging-seed.sh --stage money-loop` (or sibling) + selftest; second shop with `DIRECT_CHARGE_VENDOR_MOR`; live run; evidence JSON | 1, 4 (and 2 so unpaid drafts cannot be accepted mid-run) | Complete Express onboarding in the hosted flow (test data) |

PR 2 can go first if the owner prefers to stop unpaid acceptance before card checkout works; PR 1 is P0 and has no code dependency on 2.

### Pattern: payment gate at the single chokepoint

```java
// OrderService.transitionOrder — evaluate BEFORE mutating, so no outbox row / email / stock change happens
OrderStatus oldStatus = order.getStatus();
OrderStatus newStatus = stateMachineService.sendEvent(orderId, oldStatus, event);
boolean entersCustomerVisibleStep =
        event == OrderEvent.SUBMIT || event == OrderEvent.CONFIRM || newStatus == OrderStatus.PREPARING;
if (entersCustomerVisibleStep && isAwaitingUpfrontOrDepositPayment(order)) {
    throw new AwaitingPaymentException(order.getOrderNumber());   // -> RFC 7807, type .../errors/awaiting-payment
}
```
`OrderEvent.SUBMIT`, `OrderEvent.CONFIRM`, `OrderStatus.PREPARING` and `isAwaitingUpfrontOrDepositPayment` are as quoted from `OrderService.java:632-649, 797-803, 1094-1102`. `AwaitingPaymentException` and the `awaiting-payment` type are **new names** `[ASSUMED: proposed]`.

### Pattern: status-preserving payment success

```java
// PaymentService.handlePaymentIntentSucceeded (non-balance branch)
order.setPaymentStatus(PaymentStatus.CAPTURED);
order.setPaymentReference(intent.getId());
order.setPaymentMethod(paymentMethodDesc);
if (order.getStatus() == OrderStatus.DRAFT) {           // only a storefront card DRAFT moves
    order.setStatus(OrderStatus.PENDING);
    eventPublisher.publishStateChange(order.getId(), order.getTenantId(), order.getShopId(),
            order.getOrderNumber(), OrderStatus.DRAFT, OrderStatus.PENDING);
}
```

### Pattern: fail-loud checkout (frontend)

```tsx
// before POST — the server already told us card payments are on (PublicShopDto.acceptsCardPayments)
const publishableKey = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
const cardUnavailable = shop?.acceptsCardPayments === true && !publishableKey
// Place order: disabled={... || cardUnavailable}, aria-describedby -> the alert below
{cardUnavailable && (
  <p role="alert" id="card-unavailable">Card payments are unavailable right now, so this order can't be placed. Nothing has been charged.</p>
)}
// after POST: if (confirmation.clientSecret && !getStripe()) { setError(<same copy>); return }  — never fall through
```

### Anti-Patterns to Avoid
- **Seeding `paymentStatus=CAPTURED` with an invented `pi_`** to unskip the refund test — the conf itself forbids it (`e2e-skip-budget.conf:41-42`).
- **Adding the pk to the CR-02 required-arg gate** — it would break every keyless compose/local build; absence is handled loudly in the page instead.
- **Putting the key in a runtime `env:`** on the k8s frontend Deployment — a `NEXT_PUBLIC_*` runtime entry is dead config and `check-env-contract.sh` flags it.
- **Pointing CI at the staging Stripe account** — staging's endpoint receives every event on the account (Pitfall 5).
- **Treating the 30-VERIFICATION object ids as staging evidence.**
- **Polling/guessing Connect status** instead of handling the Connect-scope `account.updated`.

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| Webhook signature check | HMAC code | `com.stripe.net.Webhook.constructEvent` (already used) | Tolerance window, multiple `v1` signatures, constant-time compare |
| Local webhook delivery in CI | A tunnel / custom relay | `stripe listen --forward-to` (+ `--print-secret`) | Documented, stable secret across restarts |
| Immediate available balance for payouts | Waiting days / balance top-ups | `pm_card_bypassPending` / 4000 0000 0000 0077 | Documented test card |
| Retry/idempotency on Stripe creates | Custom retry loops | SDK `maxNetworkRetries` + explicit `setIdempotencyKey` (existing pattern) | Retries without a key duplicate objects |
| Circuit breaking | try/catch counters | resilience4j `@CircuitBreaker(name="stripe")` (existing instance) | Already configured and health-indicated |
| Staging Stripe reads with secrets | Ad-hoc curl with key on argv | `staging-seed.sh` helpers (`az_read`, mode-600 `-K`, `assert_test_mode`) | Proven guards, selftested, leak-scanned |
| Skip accounting | A second counter | Extend `check-e2e-skip-budget.sh` (S-5) | One gate, one conf, existing selftest harness |

## External / Runtime State Inventory (things a repo change alone does not update)

| Category | Items found | Action |
|---|---|---|
| Stored data | Staging DB holds unpaid card DRAFTs from the 2026-10-19 swarm (address, phone, allergy note) with live `requires_payment_method` PaymentIntents; count UNVERIFIED (no DB read this session) | Vendor CANCEL after PR 2 (DRAFT→CANCELLED is legal, `OrderStateMachineConfig` CANCEL from DRAFT); optionally cancel the PIs. A scheduled abandoned-DRAFT sweeper is an owner decision (Open Question 4) |
| Live service config | Stripe TEST dashboard: platform endpoint `we_1USBcpDpS9ICNeRt3kIwcIvC` (endive); **no Connect endpoint**; Connect enablement and Express capability defaults UNVERIFIED; GitHub repo vars (no Stripe var) and secrets (none referenced) | Owner registers the Connect endpoint; owner sets `FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY`; owner creates a CI sandbox + its keys |
| OS-registered state | None — verified: no cron/systemd involvement in this path; CronJob `pg-backup` unrelated | none |
| Secrets / env vars | KV `jtoye-stg-kv`: `stripe-credentials--api-key`, `--webhook-secret` present; `--connect-webhook-secret` absent; staging core-java has no `STRIPE_PLATFORM_FEE_BPS`, no `JTOYE_PAYMENT_LINKS_BASE_URL` (falls back to `notification.email.tracking-base-url` = app-staging, correct) | New KV object + SPC + env (PR 4). Platform fee per plan comes from `BillingPlanMode`, not the global bps, for routed charges (`StripeConnectService.applicationFeePennies(Tenant, …)`) |
| Build artifacts | Every frontend image built so far has no pk inlined (staging `4f3f3e14` verified) | The fix only reaches the browser after a rebuild via CI; verify by reading the served chunks, not the source |

## Common Pitfalls

### Pitfall 1: Fixing the build but leaving the silent fall-through
**What goes wrong:** a future image built without the var (fork, local, compose) recreates #957 exactly. **How to avoid:** the pre-POST/post-POST alert in the page + the deploy-staging var check + the served-chunk check. **Warning sign:** a jest test sets `process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` in every card test (it does today, e.g. `checkout-seller-block.test.tsx:257`) and no test covers the key-absent card path.

### Pitfall 2: One image, two environments
**What goes wrong:** the pipeline builds ONE frontend image per commit; baking `pk_test_` means that image must never reach production. **How to avoid:** explicit refusal in the production deploy job, recorded as the honest state (same shape as the CR-02 api-origin note). **Warning sign:** a `pk_test_` in a production chunk.

### Pitfall 3: Guarding SUBMIT breaks vendor phone/MCP orders
**What goes wrong:** keying the gate on `paymentStatus == PENDING` alone is fine, but keying on "has no capture" would refuse every vendor-created `NONE` order and the MCP `create_order submit=true` path (`OrderService.java:397-406`). **How to avoid:** reuse `isAwaitingUpfrontOrDepositPayment` verbatim; add a regression arm for a vendor DRAFT.

### Pitfall 4: Keys on the nightly flip every shop to card
**What goes wrong:** `isConfigured()` is global; `cash-checkout-no-stripe.spec.ts:121-124` fails and `abuse-bounds.spec.ts:345` skips undeclared. **How to avoid:** the two-phase nightly (keyless main run, keyed `@stripe` phase). **Warning sign:** skip count changes in a run that only meant to add the refund test.

### Pitfall 5: A shared Stripe test account turns CI into a webhook storm on staging
**What goes wrong:** every endpoint on an account receives every event for that account; CI-created intents reach staging's endpoint with unknown `order_id`s → `ResourceNotFoundException` → non-2xx → Stripe retries `[ASSUMED: Stripe retries non-2xx for up to 3 days and can disable a persistently failing endpoint]`. **How to avoid:** a separate sandbox for CI ("You must define separate webhook endpoints for your sandbox accounts" [CITED: docs.stripe.com/connect/webhooks]) **and** the foreign/unknown-event ack in PR 4.

### Pitfall 6: Connect events never arrive, and nothing errors
**What goes wrong:** with only the platform endpoint, onboarding "completes" in Stripe but the tenant stays PENDING; orders silently route as plain platform charges — a green run that proves neither settlement mode. **How to avoid:** the loop script asserts `metadata.settlement_mode` and `transfer_data.destination` / the `Stripe-Account` read on each intent, and refuses a plain platform charge (as `staging-seed.sh` refuses a cash order).

### Pitfall 7: Webhook API version drift
**What goes wrong:** an unpinned endpoint sends the account default `2026-07-29.dahlia` while stripe-java 34.0.0 deserialises as `2026-09-30.endive`; `getDataObjectDeserializer().getObject()` may come back empty → "Failed to deserialize PaymentIntent" (29-13 open item). **How to avoid:** create the Connect endpoint with `api_version=2026-09-30.endive`; if stripe-java is bumped, re-pin both endpoints.

### Pitfall 8: The kitchen toast is not the only swallowed error
**What goes wrong:** fixing kitchen but leaving other `catch {}` sites. **How to avoid:** grep the dashboard for bare `catch {` around `apiClient.post` while there; at minimum kitchen `handleBump`.

### Pitfall 9: Re-adding the ALLOW makes S-3 red, which looks like the criterion but is not
**What goes wrong:** proving "the gate fails on a re-added ALLOW" with a passing test only exercises S-3 (stale). **How to avoid:** the S-5 break arms must use a conf whose ALLOW **matches a skipping test** but lacks justification/REMOVE WHEN, so S-2/S-3 pass and only S-5 can fail.

### Pitfall 10: A refund test that charges real money twice per night forever
**What goes wrong:** desktop + mobile projects each create a paid order. **How to avoid:** `@desktop-only`; cancel/refund leftovers; keep amounts tiny (£1-£10).

## Code Examples

### Stripe timeouts (stripe-java 34.0.0)
```java
// Source: stripe-java-34.0.0-sources.jar com/stripe/Stripe.java:111-169 (static setters), read this session
@PostConstruct
void init() {
    ...
    if (STRIPE_INITIALIZED.compareAndSet(false, true)) {
        Stripe.apiKey = stripeProperties.getApiKey();
        Stripe.setConnectTimeout(stripeProperties.getConnectTimeoutMs());   // new property, e.g. 5000
        Stripe.setReadTimeout(stripeProperties.getReadTimeoutMs());         // new property, e.g. 20000
        Stripe.setMaxNetworkRetries(stripeProperties.getMaxNetworkRetries()); // keep 2; keys make retries safe
    }
}
```
Property names and values are proposals `[ASSUMED]`; the setters and defaults are verified.

### Webhook verification against two secrets (two routes)
```java
// Source pattern: PaymentService.handleWebhookEvent (PaymentService.java:372-406)
public void handleConnectWebhookEvent(String payload, String sigHeader) {
    Event event = Webhook.constructEvent(payload, sigHeader, stripeProperties.getConnectWebhookSecret()); // new property
    if (event.getAccount() == null || event.getAccount().isBlank()) throw new IllegalArgumentException("Not a Connect event");
    dispatch(event);   // the existing dedup INSERT + switch, extracted
}
```

### Stripe CLI in the nightly (shape)
```bash
# Source: docs.stripe.com/cli/listen (--print-secret, --forward-to), read this session
set -euo pipefail
case "$STRIPE_TEST_SECRET_KEY" in sk_test_*) ;; *) echo "REFUSED: not a test key" >&2; exit 1 ;; esac
echo "::add-mask::$STRIPE_TEST_SECRET_KEY"
whsec=$(stripe listen --api-key "$STRIPE_TEST_SECRET_KEY" --print-secret)   # stable across restarts
echo "::add-mask::$whsec"
printf 'STRIPE_API_KEY=%s\nSTRIPE_WEBHOOK_SECRET=%s\n' "$STRIPE_TEST_SECRET_KEY" "$whsec" >> .env
stripe listen --api-key "$STRIPE_TEST_SECRET_KEY" \
  --forward-to http://localhost:9090/api/v1/public/payments/webhook >/tmp/stripe-listen.log 2>&1 &
LISTEN_PID=$!          # kill by PID at the end, never by name
docker compose -f docker-compose.full-stack.yml up -d --no-deps --force-recreate core-java
```
`--api-key` on argv is visible in the runner's process list; prefer the `STRIPE_API_KEY` env var the CLI also reads `[ASSUMED: CLI reads STRIPE_API_KEY from env]` — verify before pinning.

## State of the Art

| Old approach | Current approach | Impact here |
|---|---|---|
| One webhook endpoint for everything | Separate "Your account" vs "Connected accounts" scopes, each its own secret | PR 4 is mandatory for PAY-03 |
| Unpinned endpoint versions | Pin `api_version` to the SDK's (`2026-09-30.endive`) | Lesson already paid for in 29-13 |
| `import { loadStripe } from "@stripe/stripe-js"` (side-effect script inject) | `@stripe/stripe-js/pure` (lazy) | Already done (#793); keep it |

**Deprecated/outdated:** the `PublicStorefrontService:1186-1194` citation in ROADMAP/REQUIREMENTS now points at `replayConfirmation`; the "Unpaid" fallback is at `:1604-1635`.

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|---|---|---|
| A1 | `strict-dynamic` CSP admits the `/pure` dynamically inserted js.stripe.com script | #957 | Payment Element blocked even with the key; the staging browser E2E detects it |
| A2 | Two Stripe endpoints cannot share a signing secret | PAY-03 | If they could, PR 4's second secret is unnecessary (route split still useful) |
| A3 | Express accounts created without `capabilities` get `card_payments`+`transfers` from platform defaults; Connect is enabled on the staging account | PAY-03 | Onboarding cannot reach `charges_enabled`; code change needed to request capabilities |
| A4 | Platform can set manual payout schedule and create payouts on Express connected accounts | PAY-03 | Payout step must wait for the automatic schedule instead |
| A5 | Stripe ids sharing a stem belong to the same account; the 30-VERIFICATION objects are from another account | PAY-03 | If the same account, the objects could be re-read with the staging key (still not "on staging") |
| A6 | The destination transfer `tr_3URXhV…` belongs to a different charge than `ch_3URXk7…` | PAY-03 | Report claim may be right; re-read by id resolves it |
| A7 | stripe-mock is stateless/no webhooks | PAY-01 | None for the plan (requirement forbids a mock anyway) |
| A8 | `merge-reports` keeps `config.metadata.specDigest` | PAY-01 | Gate VOIDs on the merged report; run the gate per report instead |
| A9 | Stripe retries non-2xx webhooks for days and may disable the endpoint | Pitfall 5 | Severity of the shared-account problem |
| A10 | SDK retries without an idempotency key can duplicate a Subscription | PAY-02 | Severity of finding 2 |
| A11 | 400 vs 409 for `awaiting-payment` | #958 | API contract choice; owner/planner decides |
| A12 | Stripe CLI reads `STRIPE_API_KEY` from env | Code Examples | Key on argv in CI process list |
| A13 | Proposed names: `AwaitingPaymentException`, `.../errors/awaiting-payment`, `stripe.connect-webhook-secret`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `/payments/webhook/connect`, `jtoye.payments.allow-unpaid-storefront-orders`, `stripe.connect-timeout-ms` etc. | several | Naming only |

## Open Questions (RESOLVED)

All seven are resolved by 30-CONTEXT.md (2026-10-21). D-01..D-04 are owner rulings; D-08..D-10 are Claude's discretion taken as defaults, which the owner may override. Each item names the plans that carry it out.

1. **Production and the pk_test-baked image.** What we know: one frontend image per commit; production deploy gated off. Unclear: whether Phase 30 should add the production refusal or per-environment images. Recommendation: refusal now, per-environment images in Phase 32.
   RESOLVED: D-08 (Claude's discretion, owner may override) — production refuses a pk_test_ image now (plan 30-01); per-environment images in Phase 32 (recorded as a known gap by plan 30-32).
2. **Which Stripe account for CI.** Recommendation: a dedicated sandbox; owner creates it and supplies `sk_test_`/`pk_test_` as GH secret/var.
   RESOLVED: D-09 (Claude's discretion, owner may override) — dedicated CI sandbox; owner checkpoint in plan 30-23, key checks in plan 30-22.
3. **Vendor-created unpaid orders (`paymentStatus=NONE`) and "an unpaid order cannot be produced".** The policy is settled (pay by link before service); the implementation scope is not: extending the PREPARING guard to NONE would require a link (and its payment) for every dashboard/MCP order. Owner decides whether that lands in Phase 30.
   RESOLVED: D-01 (owner ruling 2026-10-21) — vendor-created orders are not guarded in Phase 30; the #958 gate covers card/link orders awaiting payment (plans 30-05..30-08) and the gap is recorded with its route by plan 30-32.
4. **Abandoned card DRAFTs.** Keep (vendor cancels), or a sweeper that cancels the order and its PaymentIntent after N hours? Recommendation: cancel the swarm DRAFTs by hand now; sweeper deferred unless owner wants it.
   RESOLVED: D-02 (owner ruling 2026-10-21) — no sweeper; manual cancellation with Stripe read-back (tooling plan 30-07, run plan 30-09); the missing sweeper is recorded as a gap by plan 30-32.
5. **PAY-02 "per location".** Is £39 per tenant acceptable for acceptance, or must the subscription quantity follow active shops? Owner decision before ticking PAY-02.
   RESOLVED: D-04 (owner ruling 2026-10-21) — per location: quantity follows active shops (plans 30-25..30-27; the exact counting rule is the owner's checkpoint in plan 30-25).
6. **PAY-04 `/pay/{token}` page.** In Phase 30 (recommended — it reuses the #957 fix) or recorded as a gap?
   RESOLVED: D-03 (owner ruling 2026-10-21) — in Phase 30, payable end-to-end on staging (plans 30-16..30-20).
7. **#108 non-Stripe half** in this phase or left on the issue.
   RESOLVED: D-10 (Claude's discretion, owner may override) — left on issue #108; the Stripe half ships in plans 30-10/30-11.

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| kubectl + context `jtoye-stg-aks` | PAY-03 reads, deploy verification | ✓ | client v1.35.9; deployments listed | — |
| az CLI + `scripts/lib/azure-guard.sh` | KV reads/writes (PAY-03, PR 4) | ✓ | 2.91.0; guard present | — |
| Stripe CLI | Local PAY-01 runs; CI must install its own | ✓ (local) | 1.54.0 | Pinned release in CI |
| jq | gates, scripts | ✓ | 1.7 | — |
| Docker | compose E2E | ✓ | 29.9.1 | — |
| JDK | core-java tests | ✓ | 25.0.4.1 | — |
| Node | frontend | ⚠ local v22.23.3; project requires 24+ (Dockerfile `node:24-alpine`, CI `node-version: '24'`) | 22.23.3 | Run jest/playwright in CI or switch local Node to 24 before trusting a local frontend run |
| Stripe TEST keys (staging) | PAY-03 | ✓ in KV (names verified) | — | — |
| Stripe TEST keys (CI) | PAY-01 | ✗ | — | **Blocking — owner action** |
| Staging publishable key var | #957 | ✗ | — | **Blocking — owner action** |
| Stripe Connect endpoint (staging) | PAY-03 | ✗ | — | **Blocking — owner action (after PR 4)** |

## Validation Architecture

### Test Framework
| Property | Value |
|---|---|
| Framework | JUnit 5 + Testcontainers (Postgres 18) via Gradle 9.7.1; Jest (frontend); Playwright (E2E, projects `mobile`/`desktop`); bash selftests for scripts/gates |
| Config files | `core-java/build.gradle.kts` (build dir `build-local`, `:19`); `frontend/jest.config.*`; `frontend/playwright.config.ts` |
| Quick run | `./gradlew :core-java:test --tests 'uk.jtoye.core.order.OrderServiceTest'` ; `cd frontend && npx jest 'app/shop/\[slug\]/checkout'` |
| Full suite | `./gradlew :core-java:test` ; `cd frontend && npx jest` ; nightly Playwright (`e2e-nightly.yml`) |

### Phase Requirements → Test Map
| Req | Behaviour | Type | Command | Exists? |
|---|---|---|---|---|
| #957 | Card shop + no pk → alert, no POST; clientSecret + no pk → alert | Jest | `npx jest app/shop/\[slug\]/checkout/__tests__/card-unavailable.test.tsx` | ❌ Wave 0 |
| #957 | Served staging chunks contain `pk_test_`, no env residue; control ≥1 | script (+break arm: chunk without key) | `bash scripts/check-frontend-stripe-key-baked.sh --url https://app-staging.olajay.co.uk` | ❌ Wave 0 |
| #957 | 4242 card completes to confirmation + CAPTURED on staging | Playwright vs staging | `PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/staging-card-checkout.spec.ts --project=desktop` | ❌ |
| #957 | deploy-staging refuses empty / non-`pk_test_` var | CI step, break arm via a local run of the step's script | `bash scripts/check-stripe-pk-var.sh` | ❌ |
| #958 | SUBMIT/CONFIRM of card DRAFT refused, typed; no outbox row; vendor NONE DRAFT still submits | JUnit (Testcontainers) | `./gradlew :core-java:test --tests 'uk.jtoye.core.order.OrderServiceTest'` (+ an integration test asserting outbox rows) | partial (`:613` PREPARING arm exists) |
| #958 | Payment success keeps CONFIRMED on a link order | JUnit | `--tests 'uk.jtoye.core.payment.PaymentServiceTest'` | ❌ new case |
| #958 | Kitchen toast shows problem detail | Jest | `npx jest app/dashboard/kitchen` | ❌ |
| #108 | Static Stripe timeouts set from config; refund behind breaker | JUnit | `--tests 'uk.jtoye.core.payment.*'` | ❌ |
| PAY-03 (PR 4) | Connect route verifies only with secret B; forged/unsigned 400, 0 rows; foreign env 200, 0 side effects | JUnit integration | `--tests 'uk.jtoye.core.payment.StripeWebhookIdempotencyIntegrationTest'` (+new) | partial |
| PAY-01 | S-5: bare ALLOW → 1; no REMOVE WHEN → 1; real conf → 0 | bash selftest | `bash scripts/check-e2e-skip-budget.sh --selftest` (new flag) or `E2E_SKIP_CONF=… E2E_REPORT=…` arms | ❌ |
| PAY-01 | Refund spec passes in the keyed nightly phase; Stripe shows the refund | Playwright (nightly) | `npx playwright test --grep @stripe` | spec exists, needs rewrite |
| PAY-03 | Loop verdict lines, every object `livemode:false`, both modes | bash selftest + live | `bash scripts/staging-seed.sh --selftest`; `… --context jtoye-stg-aks --stage money-loop` | ❌ |
| env/KV | Contract + chain after new secret/ARG | gates | `bash k8s/scripts/check-env-contract.sh`; `bash scripts/staging-secrets.sh --selftest` | ✅ (to update) |

### Sampling Rate
- Per task commit: the quick command for the touched module + the relevant gate selftest.
- Per PR: full `core-java` test + full jest; `check-env-contract.sh`; `check-branch-behind-base.sh`; `docs-freshness.sh` + `check-doc-metrics.sh` after `docs/metrics.json` is updated for the new tests.
- Phase gate: one green nightly with the refund test **passed** (not skipped) and the skip-budget gate green on the merged report; the staging loop evidence file.

### Wave 0 Gaps
- [ ] `frontend/app/shop/[slug]/checkout/__tests__/card-unavailable.test.tsx` (key-absent card arms, red on today's tree).
- [ ] Served-chunk key checker script with its break arm.
- [ ] `OrderServiceTest` SUBMIT/CONFIRM refusal arms + an outbox-row integration assertion (red today: 200).
- [ ] `check-e2e-skip-budget.sh` S-5 + selftest arms.
- [ ] Nightly Stripe phase scaffolding (CLI pin, PID-captured listener, report merge).
- [ ] `staging-seed.sh` money-loop stage stubs (selftest arms red first).

## Security Domain

`security_enforcement` true, ASVS level 2, block on medium (`.planning/config.json`).

### Applicable ASVS Categories
| Category | Applies | Control |
|---|---|---|
| V2 Authentication | no (no auth change) | — |
| V3 Session | no | — |
| V4 Access control | yes | `shopAccessService.require(order.getShopId(), ShopRole.STAFF)` stays first in `transitionOrder`; admin-only Connect onboarding endpoint unchanged; `/pay/{token}` (if built) is a bearer credential — single-use + expiry already server-side |
| V5 Input validation | yes | Webhook payloads verified by signature before any parse-dependent work; `event.account` presence checked per route; key/var prefix validation (`^sk_test_`, `^pk_test_`, `^whsec_`) |
| V6 Cryptography | yes | Stripe SDK HMAC verification only; no custom crypto |
| V7 Errors/logging | yes | Typed problems without issue numbers or internals; never log keys/client secrets (existing `StripeProperties.toString` redaction; extend to the new secret) |
| V8 Data protection | yes | Evidence files: ids only; leak-scan + gitleaks with controls; client secrets held in memory only |
| V9 Communications | yes | STARTTLS stays required for mail; all Stripe calls HTTPS |
| V14 Configuration | yes | Test-mode-only guards in CI and staging; `expose-otp-for-test` refused under the prod profile |

### Known Threat Patterns
| Pattern | STRIDE | Mitigation |
|---|---|---|
| Forged/unsigned webhook on the new Connect route | Spoofing/Tampering | Signature with secret B; 400; no dedup row; negative arms on the live edge (29-18 pattern) |
| Cross-environment events (CI → staging) | Denial of service / Tampering | Separate sandbox; env-tagged metadata; 200-ack-and-skip for foreign/unknown |
| Live key slipping into staging/CI | Elevation of privilege (money) | Prefix guards in `staging-secrets.sh` (existing), CI step, deploy-staging var check |
| Publishable key confused with secret key | Information disclosure | Separate var vs secret; secret-key regex scan over built chunks (must find 0 `sk_`) |
| Unpaid order accepted / produced | Tampering (business logic) | Server gate on SUBMIT/CONFIRM/PREPARING |
| Double charge on retry | Tampering | Existing intent idempotency key; add keys where missing (billing) |
| OTP exposed by test flag in prod | Information disclosure | Prod-profile refusal test |

## Sources

### Primary (read this session)
- Repo files cited inline with line ranges (core-java payment/order/storefront/notification, frontend checkout/kitchen/orders, Dockerfile, ci-cd.yaml, e2e-nightly.yml, e2e-skip-budget conf + gate, k8s base/staging/components, staging-seed.sh, staging-secrets.sh, check-env-contract.sh, application.yml, docs/metrics.json).
- stripe-java 34.0.0 sources jar (`~/.gradle/caches/.../stripe-java-34.0.0-sources.jar`): `Stripe.java` timeouts/retries, `ApiVersion.java` `2026-09-30.endive`, `RequestOptions.java` timeout setters.
- Live read-only checks: staging Deployments env names and `stripe-credentials` key names (kubectl, no values); public shop API; served checkout chunks of image `4f3f3e14`; CSP header; `/pay/…` 404 vs `/track` 200.
- GitHub issues #957, #958, #61, #102, #461, #108, #814 (bodies + comments via `gh api`); PR #949 merged 2026-10-17.
- Phase 29 records: `29-CONTEXT.md` D-28/D-31, `29-13-SUMMARY.md`, `29-18-SUMMARY.md`, `evidence/29-18-stripe.txt`, `29-ORCHESTRATOR-LEDGER.md` PHASE CLOSE.

### Secondary (official docs via WebFetch; GSD seam tier for provider `webfetch`: LOW)
- https://docs.stripe.com/connect/webhooks — event scopes, `connect` parameter, `account` property, `--forward-connect-to`.
- https://docs.stripe.com/testing — "Send funds to your available balance" (4000000000000077 / `pm_card_bypassPending`).
- https://docs.stripe.com/cli/listen — `--forward-to`, `--forward-connect-to`, `--print-secret`, stable secret.

### Tertiary (not verified)
- Everything in the Assumptions Log.
- `docs/../30-VERIFICATION.md` and `evidence/30-live-stripe-api-proofs.json` (2026-10-17, laptop, other Stripe account) — UNVERIFIED against staging.

## Metadata

**Confidence breakdown:**
- #957 root cause: HIGH — code path read end to end and the running artifact inspected with a positive control.
- #958 root cause: HIGH — guard placement, state machine and email triggers read; repro matches.
- PAY-01 design: MEDIUM — blockers verified in code; report-merge behaviour assumed.
- PAY-03: MEDIUM — the Connect-scope blocker is verified (code + docs); onboarding/payout specifics partly assumed.
- PAY-02/04 confirm findings: HIGH (code reads, staging 404).
- #108: HIGH (code + SDK sources).

**Research date:** 2026-10-21
**Valid until:** 2026-11-04 (Stripe docs and the staging estate move; re-check the endpoint list and image sha before planning PR 4/6).
