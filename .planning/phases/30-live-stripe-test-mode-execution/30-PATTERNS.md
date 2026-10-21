# Phase 30: The Money Path, Executed - Pattern Map

**Mapped:** 2026-10-21
**Files analyzed:** 44 (new + modified, across the six serial PRs of 30-RESEARCH.md)
**Analogs found:** 41 / 44
**Tracked-source gate:** every analog path below was confirmed with `git ls-files` (all tracked; no mirror paths).

## File Classification

| New/Modified File | PR | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|---|
| `frontend/Dockerfile` (mod: `ARG/ENV NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`) | 1 | config | build-time | same file `:70-84` (`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE`, optional ARG) | exact |
| `.github/workflows/ci-cd.yaml` (build-arg; deploy-staging `pk_test_` check; production pk_test refusal; edge-probe chunk step) | 1 | config/CI | batch | same file `:1859-1865` build-args; `:2346-2372` CR-02 baked-var check | exact |
| `docker-compose.full-stack.yml` (frontend `build.args`; drop runtime line `:609`) | 1 | config | build-time | same file, existing frontend `build.args` | exact |
| `k8s/scripts/check-env-contract.sh` (remove `:229` allowlist row; later add connect secret) | 1, 4 | gate | batch | same file `:226-231` | exact |
| `scripts/check-staging-frontend-stripe-key.sh` (NEW served-chunk check) | 1 | gate script | request-response (HTTP read) | `scripts/check-staging-edge.sh` (arms PASS/FAIL/VOID; wired in `staging-edge-probe` `ci-cd.yaml:2289-2307`) | role-match |
| `frontend/lib/stripe-loader.ts` (NEW: `publishableKey`, `loadStripeOnce`, `STRIPE_APPEARANCE`) | 1 | utility | lazy-load | `frontend/app/shop/[slug]/checkout/page.tsx:770-782` (`getStripe` memo) | exact (extraction) |
| `frontend/components/storefront/card-payment-form.tsx` (NEW, moved `PaymentForm`) | 1 | component | request-response | `checkout/page.tsx:387` `function PaymentForm` | exact (move) |
| `frontend/app/shop/[slug]/checkout/page.tsx` (fail-loud pre/post POST, `stripeAccountId`, colorPrimary) | 1 | page | request-response | itself: `ShopAvailabilityNotice` slot `:1430-1432`, disabled+describedby `:1836-1840`, render guard `:1245`, `colorPrimary` `:1358` | exact |
| `frontend/lib/order-status-announcement.ts` (add `DRAFT`) | 1 | utility | transform | same file `:22-30` `ORDER_STATUS_COPY` | exact |
| `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` (DRAFT "Awaiting payment") | 1 | page | read | itself `:369-376` | exact |
| `core-java/.../storefront/dto/GuestOrderConfirmation.java` (add nullable `stripeAccountId`) | 1/4 | DTO | response | same file `:30` `clientSecret` + `@Schema` fields | exact |
| `core-java/.../exception/AwaitingPaymentException.java` (NEW) | 2 | exception | — | `core-java/src/main/java/uk/jtoye/core/exception/InvalidStateTransitionException.java` | exact |
| `core-java/.../common/GlobalExceptionHandler.java` (handler → `errors/awaiting-payment`) | 2 | middleware (advice) | request-response | same file `:102-108` | exact |
| `core-java/.../order/OrderService.java` (gate SUBMIT/CONFIRM/PREPARING) | 2 | service | state-machine | same file `:795-803`, predicate `:1094-1102` | exact |
| `core-java/.../payment/PaymentService.java` (status-preserving success; timeouts in `init`; connect secret) | 2,3,4 | service | event-driven (webhook) | same file `:515-532`, `:106-118`, `:370-380` | exact |
| `frontend/lib/awaiting-payment.ts` (NEW predicate + problem type const) | 2 | utility | transform | `OrderService.java:1094-1102` (mirror) + `kds-new-lane.tsx` `INSUFFICIENT_STOCK_TYPE` const | role-match |
| `frontend/components/dashboard/orders/awaiting-payment-badge.tsx` (NEW) | 2 | component | display | `frontend/components/dashboard/kitchen/unverified-contact-badge.tsx` (+ `orders/voided-badge.tsx` border-2) | exact |
| `frontend/app/dashboard/kitchen/page.tsx` (`handleBump` catch → `describeLoadError`) | 2 | page | request-response | itself `:621-631`; `frontend/lib/human-error.ts:70-98`; orders page `:604-617` usage | exact |
| `frontend/components/dashboard/kitchen/kds-new-lane.tsx` (`describeAcceptFailure` new branch) | 2 | component | request-response | itself `:129-143` (stock-type branch) | exact |
| `frontend/app/dashboard/orders/page.tsx` (hide Submit/Confirm, badge) | 2 | page | CRUD | itself `:215-225` transition map; `UnverifiedContactBadge` placement | exact |
| `core-java/.../payment/StripeRefundClient.java` (`@CircuitBreaker`) | 3 | client seam | request-response | `PaymentService.java:164` `@CircuitBreaker(name = "stripe")` | exact |
| `core-java/.../payment/StripeBillingService.java` (breaker; per-location `setQuantity`; idempotency) | 3 / D-04 | service | request-response | same file `:113-136`; idempotency pattern `PaymentService.java:140-146` | exact |
| `core-java/.../payment/StripeProperties.java` (timeouts, `connectWebhookSecret`, redaction) | 3,4 | config | — | same file `:41`, `:150-165` redacted `toString` | exact |
| `core-java/src/main/resources/application.yml` (stripe.* keys) | 3,4 | config | — | existing `stripe:` block + `resilience4j.circuitbreaker.instances.stripe` `:971-979` | exact |
| `core-java/.../payment/PaymentController.java` (`/webhook/connect` route) | 4 | controller | event-driven | same file `:30-53` `/webhook` | exact |
| `k8s/components/azure-keyvault/secretproviderclasses.yaml` (+ `connect-webhook-secret`) | 4 | config | — | same file `:115-119`, `:187-193` | exact |
| `k8s/base/core-java-deployment.yaml` (+ `STRIPE_CONNECT_WEBHOOK_SECRET`) | 4 | config | — | same file `:632-643` | exact |
| `scripts/staging-secrets.sh` (register `whsec_` external object) | 4 | ops script | file-I/O | same file `:228-240` prefix validator | exact |
| `core-java/.../payment/PublicPhoneAndPaymentLinkController.java` (GET preview) | S3/D-03 | controller | request-response | same file `:89-98` redeem | exact |
| `core-java/.../payment/OrderPaymentLinkService.java` (preview, `stripeAccountId` on redeem) | S3/D-03 | service | CRUD (read) | same file redeem path `:293-306` | exact |
| `frontend/app/pay/[token]/page.tsx` (NEW server shell) | S3 | page | static | `frontend/app/invite/page.tsx` | exact |
| `frontend/app/pay/[token]/pay-client.tsx` (NEW island state machine) | S3 | component | request-response | `frontend/app/invite/invite-client.tsx` (`Phase` union `:86-93`, preview `:139-165`, `replaceState` `:177`) | exact |
| `frontend/app/pay/page.tsx` (NEW Stripe return target) | S3 | page | request-response | `invite/page.tsx` + checkout PaymentIntent retrieve | role-match |
| `frontend/app/robots.ts` (add `/pay`) | S3 | config | — | same file `:35-38` `DISALLOW` | exact |
| `frontend/next.config.mjs` (`/pay/:path*` no-referrer) | S3 | config | — | same file `:73-76` `/invite/:path*` | exact |
| `frontend/__tests__/header-snapshot.test.ts` | S3 | test | — | same file `:51-76` `/invite` arm | exact |
| `scripts/gates/ssr-routes.conf` | S3 | gate conf | — | same file `:134` `STATIC invite/page.tsx …` | exact |
| `scripts/check-e2e-skip-budget.sh` (S-5 justification + REMOVE WHEN; selftest) | 5 | gate | batch | same file `:157-176` parse loop | exact |
| `scripts/gates/e2e-skip-budget.conf` (retire refund ALLOW, MAX_SKIPS) | 5 | gate conf | — | same file `:39-44`, `:78-83` | exact |
| `frontend/e2e/vendor-refund-flow.spec.ts` (own paid order, `@stripe @desktop-only`, Stripe read-back, no skip) | 5 | test (E2E) | request-response | same file `:185-` DRAFT test (creates its own order via vendor API); `playwright.config.ts:88,113` tag pattern | exact |
| `.github/workflows/e2e-nightly.yml` (Stripe phase: pinned CLI, `stripe listen`, recreate core-java, merge reports) | 5 | CI | batch | `e2e-nightly.yml:117-135` stack build; `ci-cd.yaml:1990-1992` sha256-pinned binary | role-match |
| `scripts/staging-seed.sh` `--stage money-loop` (or sibling script) | 6 | ops script | request-response | same file `:797-855` (`azure_guard_once`, `stripe_api`, `stripe_fail`, `assert_test_mode`) | exact |
| `.planning/.../evidence/30-pay03-staging-loop.json` | 6 | evidence | — | 29-18 evidence files | partial |
| Shop-count → subscription quantity sync (ShopService hooks, D-04) | D-04 | service | event-driven | `core-java/.../shop/ShopService.java` create/update/deactivate | partial |

## Pattern Assignments

### `AwaitingPaymentException.java` + handler (PR 2)

**Analog:** `core-java/src/main/java/uk/jtoye/core/exception/InvalidStateTransitionException.java` (whole file)
```java
package uk.jtoye.core.exception;

/**
 * Exception thrown when attempting an invalid state transition.
 * Results in HTTP 400 Bad Request response.
 */
public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String message) { super(message); }
    public InvalidStateTransitionException(String message, Throwable cause) { super(message, cause); }
}
```
**Handler analog** `GlobalExceptionHandler.java:102-108` (import at `:40`):
```java
@ExceptionHandler(InvalidStateTransitionException.class)
public ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Invalid State Transition");
    problem.setType(URI.create("https://jtoye.uk/errors/invalid-state-transition"));
    return problem;
}
```
Copy with title `"Awaiting payment"`, type `https://jtoye.uk/errors/awaiting-payment`. If `AwaitingPaymentException` extends `InvalidStateTransitionException`, Spring picks the most specific handler — declare it explicitly. Detail copy is the UI-SPEC "Server refusal detail" row verbatim (no `(#461)`).

### `OrderService.java` gate (PR 2)

**Current guard** `:795-803`:
```java
OrderStatus newStatus = stateMachineService.sendEvent(orderId, oldStatus, event);
if (newStatus == OrderStatus.PREPARING && isAwaitingUpfrontOrDepositPayment(order)) {
    throw new InvalidStateTransitionException(
            "Order " + order.getOrderNumber()
                    + " cannot enter kitchen preparation until upfront or deposit payment is completed (#461).");
}
// Mutate in memory first; the save happens AFTER stock bookkeeping ...
```
**Predicate (unchanged, reuse)** `:1094-1102` — `PENDING|FAILED` → true; else `paymentReference != null && ps != CAPTURED && ps != AUTHORIZED`. Widen the condition to `event == SUBMIT || event == CONFIRM || newStatus == PREPARING` per 30-RESEARCH "Pattern: payment gate", throwing before any mutation.

### `PaymentService.java` (PRs 2, 3, 4)

**Webhook success (fix target)** `:521-532`:
```java
order.setPaymentStatus(PaymentStatus.CAPTURED);
order.setPaymentReference(intent.getId());
order.setPaymentMethod(paymentMethodDesc);
order.setStatus(OrderStatus.PENDING); // DRAFT → PENDING on successful payment
order.setUpdatedAt(java.time.OffsetDateTime.now());
orderRepository.save(order);
eventPublisher.publishStateChange(
        order.getId(), order.getTenantId(), order.getShopId(), order.getOrderNumber(),
        OrderStatus.DRAFT, OrderStatus.PENDING);
```
Wrap the status set + publish in `if (order.getStatus() == OrderStatus.DRAFT)`.

**Static SDK config site (timeouts, #108)** `:106-118`:
```java
@PostConstruct
void init() {
    if (stripeProperties.getApiKey() == null || stripeProperties.getApiKey().isBlank()) {
        log.warn("Stripe API key not configured — payment processing will fail");
        return;
    }
    if (STRIPE_INITIALIZED.compareAndSet(false, true)) {
        Stripe.apiKey = stripeProperties.getApiKey();
        log.info("Stripe API key configured (key redacted)");
    }
}
```
Add `Stripe.setConnectTimeout/ setReadTimeout/ setMaxNetworkRetries` inside the same `compareAndSet` block.

**Signature verification (connect route)** `:373-380`:
```java
event = Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
} catch (SignatureVerificationException e) {
    log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
    throw new IllegalArgumentException("Invalid Stripe signature");
```
Parameterise the secret (platform vs connect), then the same dedup/dispatch. Unknown-order ack: today `:467-468` throws `ResourceNotFoundException`, which `PaymentController` does not catch → make it ack-and-skip (WARN) after verification.

### `PaymentController.java` `/webhook/connect` (PR 4)

**Analog** `:40-53`:
```java
@PostMapping("/webhook")
@Operation(summary = "Stripe webhook", description = "Handles Stripe webhook events. Verifies signature before processing.")
public ResponseEntity<Map<String, String>> handleWebhook(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String sigHeader) {
    try {
        paymentService.handleWebhookEvent(payload, sigHeader);
        return ResponseEntity.ok(Map.of("status", "ok"));
    } catch (IllegalArgumentException e) {
        log.warn("Webhook rejected: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
```

### `StripeProperties.java` (PRs 3, 4)

Fields `:24-34` (`private String webhookSecret = "";`) + getter/setter pairs `:41-42`. **Redaction** `:150-165` — every new secret goes through `mask(...)` in `toString()`; extend `core-java/src/test/java/uk/jtoye/core/payment/StripePropertiesRedactionTest.java`.

### `StripeRefundClient.java` / `StripeBillingService.java` (PR 3, D-04)

`StripeRefundClient` is a 4-line seam whose Javadoc already names "circuit breaker … all wrap here": add `@CircuitBreaker(name = "stripe")` on `create` exactly as `PaymentService.java:164`.
Billing subscription item `StripeBillingService.java:124-128`:
```java
SubscriptionCreateParams.Builder subParams = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder()
                .setPrice(price.getId())
                .build())
```
D-04: add `.setQuantity(activeShopCount)`; add `RequestOptions` idempotency key (PaymentService `:140-146` pattern). Quantity sync on shop add/remove/deactivate hooks into `ShopService.java` (partial analog — no existing Stripe side-effect from ShopService; use an after-commit hook like the V49 Keycloak deprovision pattern).

### `PublicPhoneAndPaymentLinkController.java` preview (S3)

**Analog** `:89-98`:
```java
@PostMapping("/payment-links/{token}/redeem")
@Operation(summary = "Redeem a single-use expiring payment link",
        description = "Marks the payment link token as used (single-use) and returns the live Stripe PaymentIntent client secret (#461 / PAY-04).")
public ResponseEntity<OrderPaymentLinkService.RedeemedPaymentLinkDto> redeemPaymentLink(
        @PathVariable String token) {
    return ResponseEntity.ok(paymentLinkService.redeemPaymentLink(token));
}
```
Class is mapped `@RequestMapping({"/public", "/api/v1/public"})` (`:26`). Add `@GetMapping("/payment-links/{token}")` returning a new `PaymentLinkPreviewDto` record; service method must NOT call `markUsedIfUnused` (`OrderPaymentLinkService.java:293`).

### Frontend: `stripe-loader.ts` + `card-payment-form.tsx` + checkout (PR 1)

**Memo to extract** `checkout/page.tsx:775-782`:
```tsx
const stripeRef = useRef<StripePromise | null>(null)
const [stripePromise, setStripePromise] = useState<StripePromise | null>(null)
const getStripe = (): StripePromise | null => {
  const publishableKey = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  if (!stripeRef.current && publishableKey) {
    stripeRef.current = loadStripe(publishableKey)
  }
  return stripeRef.current
}
```
Move to a module-level `Map<accountId|"", Promise>` memo; keep `loadStripe` from `@stripe/stripe-js/pure` only (#793). `PaymentForm` at `:387` moves byte-identical into `card-payment-form.tsx`. Fail-loud notice goes after `<ShopAvailabilityNotice shop={shop} id={SHOP_AVAILABILITY_EXPLAINER_ID} className="mt-6" />` (`:1432`); Place order follows the `:1836-1840` rule (`aria-describedby={... ? ID : undefined}`). Render guard `:1245` becomes `if (paymentState)`. `colorPrimary: "#f97316"` `:1358` → `STRIPE_APPEARANCE`.

**Copy table analog** `lib/order-status-announcement.ts:22-30`:
```ts
export const ORDER_STATUS_COPY: Readonly<Record<string, { label: string; desc: string }>> = {
  PENDING: { label: "Received", desc: "Order sent to shop" },
  CONFIRMED: { label: "Confirmed", desc: "Shop accepted your order" },
```
Add `DRAFT: { label: "Awaiting payment", desc: "Waiting for your payment" }`.

### `awaiting-payment-badge.tsx` (PR 2)

**Analog** `frontend/components/dashboard/kitchen/unverified-contact-badge.tsx` (whole file):
```tsx
import { UserX } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

export const UNVERIFIED_CONTACT_COPY = "Unverified contact"
export const UNVERIFIED_CONTACT_TITLE = "This customer's email or phone hasn't been confirmed."

export function UnverifiedContactBadge({ contactVerified, className }: { contactVerified?: boolean | null; className?: string }) {
  if (contactVerified !== false) return null
  return (
    <Badge variant="outline" data-testid="unverified-contact" title={UNVERIFIED_CONTACT_TITLE}
      className={cn("flex w-fit items-center gap-1 border border-dashed border-slate-500 bg-white text-slate-700", className)}>
      <UserX className="h-3 w-3" aria-hidden="true" />
      <span>{UNVERIFIED_CONTACT_COPY}</span>
      <span className="sr-only">{UNVERIFIED_CONTACT_TITLE}</span>
    </Badge>
  )
}
export default UnverifiedContactBadge
```
Swap glyph to `Hourglass`, classes to `border-2 border-amber-700 bg-white text-amber-800`, `data-testid="awaiting-payment"`, null-return when `paymentStatus` absent. Test analog: `frontend/components/dashboard/kitchen/__tests__/unverified-contact-badge.test.tsx`.

### Kitchen toast + KDS accept (PR 2)

**Current bare catch** `kitchen/page.tsx:621-631`:
```tsx
try {
  await apiClient.post(`/api/v1/orders/${orderId}/${action.endpoint}`)
} catch {
  toast({ variant: "destructive", title: "Error updating order",
    description: `Failed to ${action.label.toLowerCase()}. Reverting.` })
  fetchOrders()
}
```
Use `describeLoadError(error, fallback)` from `lib/human-error.ts:70-98` (reads `response.data.detail` first, then `.message`, then fallback).
**KDS branch analog** `kds-new-lane.tsx:129-143`:
```tsx
async function describeAcceptFailure(orderId: string, error: unknown): Promise<string> {
  const response = (error as ProblemResponse | null)?.response
  if (!response) return ACCEPT_FAILED_COPY
  const data = response.data
  if (data?.type === INSUFFICIENT_STOCK_TYPE && typeof data.available === "number" && ...) {
    return acceptRefusedStockCopy(data.available, data.productName)
  }
  const moved = await movedElsewhere(orderId)
  return moved ? alreadyMovedCopy(moved) : ACCEPT_FAILED_COPY
}
```
Insert an `AWAITING_PAYMENT_PROBLEM_TYPE` branch returning `data.detail` before `movedElsewhere`.

### `/pay` route (S3)

**Server shell analog** `frontend/app/invite/page.tsx:1-40`: `export const metadata: Metadata = { title, description, robots: { index: false, follow: false } }` and `<PublicShell><InviteClient /></PublicShell>`; fetches nothing (STATIC in ssr-routes.conf).
**Island analog** `frontend/app/invite/invite-client.tsx`: discriminated `Phase` union (`:86-93` `{ kind: "checking" } | { kind: "unreachable" } | …`), preview fetch mapping 404/409/other to phases (`:139-165`), `window.history.replaceState` (`:177`), amber alert `<p role="alert" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">` (`:245`). Test analog: `frontend/app/invite/__tests__/invite-client.test.tsx`.
**Headers** `next.config.mjs:73-76` (`source: '/invite/:path*'` after `'/:path*'` at `:44`) and test `__tests__/header-snapshot.test.ts:51-76` (index-after-default + `pathToRegexp` match/control arms).
**robots** `app/robots.ts:35` `const DISALLOW = ["/api/", "/auth/", "/dashboard", …]`.
**SSR conf** `scripts/gates/ssr-routes.conf:134`:
```
STATIC invite/page.tsx robots-noindex transactional page; the staff-invitation token lives in the URL fragment and is POSTed by the client island in a JSON body
```

### Build channel (PR 1)

**Dockerfile** `:70-84` — optional ARG block with the "Deliberately NOT added to the CR-02 fail-fast gate" comment:
```dockerfile
ARG NEXT_PUBLIC_DATA_PROTECTION_EMAIL
ENV NEXT_PUBLIC_DATA_PROTECTION_EMAIL=${NEXT_PUBLIC_DATA_PROTECTION_EMAIL}
```
**CI build-args** `ci-cd.yaml:1859-1865`:
```yaml
build-args: |
  NEXT_PUBLIC_API_URL=${{ vars.FRONTEND_PUBLIC_API_URL }}
  ...
  NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=${{ vars.FRONTEND_PUBLIC_COMPANY_REGISTERED_OFFICE }}
```
**Deploy var check** `ci-cd.yaml:2346-2372` (CR-02): `env: BAKED_API_URL: ${{ vars.… }}`, `set -euo pipefail`, empty → `FATAL … exit 2`, mismatch → `FATAL` with both values. Copy for `vars.FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY` with a `^pk_test_[A-Za-z0-9]+$` case match (staging) and a `pk_test_` refusal (production, D-08). Use `case`/here-strings, never `| grep -q`.
**Allowlist row to delete** `check-env-contract.sh:229` (`'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY|Reviewed omission ...'`); the gate reports STALE at `:714` once the ARG exists.

### Served-chunk check (PR 1)

**Analog** `scripts/check-staging-edge.sh` — header doc block naming "THE ARMS (each PASS / FAIL / VOID)", wired as `run: bash scripts/check-staging-edge.sh --skip-burst` in `staging-edge-probe` (`ci-cd.yaml:2289-2307`). New script: count `pk_test_` literal (=1… ≥1), residue `env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` (=0), control `api-staging.olajay.co.uk` ≥1 else VOID exit 2.

### Connect secret wiring (PR 4)

`secretproviderclasses.yaml:115-119` objects and `:187-193` secretObjects:
```yaml
- |
  objectName: stripe-credentials--webhook-secret
  objectType: secret
...
- secretName: stripe-credentials
  type: Opaque
  data:
  - key: webhook-secret
    objectName: stripe-credentials--webhook-secret
```
`core-java-deployment.yaml:638-643`:
```yaml
- name: STRIPE_WEBHOOK_SECRET
  valueFrom:
    secretKeyRef:
      name: stripe-credentials
      key: webhook-secret
      optional: true
```
`staging-secrets.sh:228-240` external validator refuses values not starting with the declared prefix (`whsec_`) with the D-28 message.

### PAY-01 gate + spec + nightly (PR 5)

**Parse loop to extend (S-5)** `check-e2e-skip-budget.sh:157-176`:
```bash
while IFS= read -r raw; do
    line="${raw%%$'\r'}"
    case "$(printf '%s' "$line" | sed 's/^[[:space:]]*//')" in
        ''|'#'*) continue ;;
    esac
    directive=$(printf '%s' "$line" | awk '{print $1}')
    ...
        ALLOW)     ALLOWS+=("$value") ;;
        *)         void "unknown directive '$directive' in $CONF — refusing to guess" ;;
```
Track the contiguous preceding comment block (reset on blank/non-comment); on `ALLOW` require a non-empty justification line and `^#\s*REMOVE WHEN:\s*\S`, else exit 1 naming it. Add selftest arms alongside the existing selftest.
**Skip to remove** `vendor-refund-flow.spec.ts:119-124` (`test.skip(true, "No CONFIRMED+CAPTURED order seeded …")`) — replace with an explicit `throw`/`expect` failure on missing key; create the order itself like the DRAFT test from `:185`. Tag pattern: `playwright.config.ts:88` `grepInvert: /@desktop-only/`.
**Pinned binary** `ci-cd.yaml:1990-1992`:
```bash
KUSTOMIZE_SHA256="54e4…b5148f"
echo "${KUSTOMIZE_SHA256}  kustomize.tar.gz" | sha256sum -c -
```

### PAY-03 loop (PR 6)

**Analog** `scripts/staging-seed.sh` helpers: `void`/`refuse` exit codes `:122-124`, `azure_guard_once` `:797`, `stripe_api` `:826-835` (key only in `$WORKDIR/stripe.curlrc` via `curl -K`), `stripe_fail` `:837-843`, `assert_test_mode` `:847-855`:
```bash
assert_test_mode() {
  local verdict
  verdict=$(jq -r 'if .object == "list" then (...) else (if .livemode == false then "false" else "livemode=\(.livemode)" end) end' "$1" 2>/dev/null || echo unparseable)
  case "$verdict" in
    false|empty) return 0 ;;
    *) refuse stripe-mode "$2 does not read livemode false ($verdict); a live object must never exist in staging (D-28)" ;;
  esac
}
```
Add `--stage money-loop` next to `vendor|order|all` (usage `:15-37`), with `--selftest` arms. Connected-account reads need an extra `Stripe-Account` header in `stripe_api`.

## Shared Patterns

### RFC 7807 typed problems
**Source:** `GlobalExceptionHandler.java:94-157` — `ProblemDetail.forStatusAndDetail` + `setTitle` + `setType(URI.create("https://jtoye.uk/errors/<code>"))`. Apply to awaiting-payment and any preview 404/409/410. Update OpenAPI snapshot (`scripts/check-openapi-snapshot-fresh.sh`).

### Resilience4j on Stripe calls
**Source:** `@CircuitBreaker(name = "stripe")` at `PaymentService.java:164, 272, 343, 354`; instance config `application.yml:971-979`. Apply to refund create, webhook `Charge.retrieve` (`:474-478`), billing calls.

### Fail-closed gates (VOID = exit 2)
**Source:** `scripts/staging-seed.sh:122-125` and `check-e2e-skip-budget.sh` `void`. Every new script/CI step: missing tooling / empty / unparseable → exit 2; refusals exit 1; here-strings not `| grep -q`.

### Error text in UI
**Source:** `frontend/lib/human-error.ts:70-98` `describeLoadError`. All refusal toasts.

### Word-plus-shape badges
**Source:** `unverified-contact-badge.tsx`, `voided-badge.tsx` — `Badge variant="outline"`, lucide glyph `h-3 w-3 aria-hidden`, sr-only sentence.

### Token pages hygiene
**Source:** `/invite` set: `app/invite/page.tsx` metadata, `next.config.mjs:76`, `robots.ts:35`, `ssr-routes.conf:134`, `header-snapshot.test.ts:51-76`.

### Secret redaction
**Source:** `StripeProperties.toString()` `:150-165` + `StripePropertiesRedactionTest`.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `e2e-nightly.yml` Stripe phase (`stripe listen` background + PID, report merge) | CI | event-driven | No workflow runs a background forwarder or merges Playwright reports; use 30-RESEARCH PAY-01 design (blob reporter + `merge-reports`, measure `specDigest` survival) |
| Shop-count → subscription quantity sync | service | event-driven | No ShopService code calls Stripe; nearest shape is the after-commit REQUIRES_NEW hook (V49 Keycloak deprovision) |
| `evidence/30-pay03-staging-loop.json` | evidence | — | Format to define in plan; follow 29-18 evidence (ids/amounts/statuses only, leak-scanned) |

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{order,payment,storefront,exception,common,shop}`, `frontend/{app,components,lib,e2e,__tests__}`, `k8s/`, `scripts/`, `.github/workflows/`
**Files scanned:** ~45
**Pattern extraction date:** 2026-10-21
