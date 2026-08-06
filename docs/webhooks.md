# Outbound Webhooks — Integrator Guide

For engineers building a **receiver** for J'Toye outbound webhooks. It tells you exactly
what we send, exactly what bytes we sign, and gives you a **worked signature test vector**
you can run against your own verifier before you go live.

If you only need one thing from this page, it is [The signature test vector](#the-signature-test-vector).

Related: [`idempotency.md`](idempotency.md) (the `Idempotency-Key` contract on our inbound
API). The internal architecture view lives in
[`architecture/SYSTEM_DESIGN_V2.md`](architecture/SYSTEM_DESIGN_V2.md) — this page is the
contract, that page is the rationale.

---

## 1. Registering an endpoint

`POST /api/v1/webhooks` (JWT-authenticated, scoped to your tenant).

```json
{
  "targetUrl": "https://vendor.example.com/hooks/jtoye",
  "eventTypes": ["ORDER_STATE_CHANGED", "ORDER_REFUNDED"]
}
```

Two rules are enforced at registration and again at every delivery attempt:

- **HTTPS only.** An `http://` target is rejected with a 400.
- **Publicly routable only.** A target that resolves to a private, loopback or link-local
  address is rejected. This is anti-SSRF, so it is re-checked at egress and the connection
  is made to the address that was validated — you cannot point us at an internal host by
  re-pointing DNS after registration.

The **201 response carries `signingSecret` in plaintext, once.** It is never returned
again by any other endpoint, including `GET`. Store it before you close the response.

| Endpoint | Effect |
|---|---|
| `GET /api/v1/webhooks` | list subscriptions (never includes the secret) |
| `GET /api/v1/webhooks/{id}` | one subscription (never includes the secret) |
| `POST /api/v1/webhooks/{id}/rotate-secret` | new secret, returned once |
| `POST /api/v1/webhooks/{id}/pause` | stop delivering (`PAUSED`) |
| `POST /api/v1/webhooks/{id}/resume` | deliver again, and clear the failure counter |
| `POST /api/v1/webhooks/{id}/revoke` | terminal (`REVOKED`) |

Errors are RFC 7807 problem documents, not prose.

### Secret rotation has no overlap window

Rotation replaces the secret immediately. Deliveries signed with the old secret stop
verifying the moment you rotate — there is no dual-secret grace period. If you cannot
deploy the new secret atomically, accept **either** secret in your verifier for the
duration of your rollout, then drop the old one.

Real secrets are 32 random bytes, base64url-encoded without padding (43 characters). The
example secret in this document is deliberately **not** in that shape so it can never be
mistaken for one.

---

## 2. What we send

A POST with a JSON body and four headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-JToye-Event-Id` | UUID of the event — **your dedupe key** |
| `X-JToye-Event-Type` | the wire event type, e.g. `order.ready` |
| `X-JToye-Signature` | `t=<unix-seconds>,v1=<hex-hmac-sha256>` |

The body is a versioned envelope:

```json
{
  "id": "<uuid, same as X-JToye-Event-Id>",
  "type": "order.ready",
  "tenantId": "<your tenant uuid>",
  "occurredAt": "<ISO-8601 timestamp>",
  "version": "1",
  "data": { }
}
```

`data` is the full domain payload for the event. Treat it as **additive**: we may add
fields to it without bumping `version`, so parse leniently and ignore what you do not
recognise. A breaking change to the envelope's own shape bumps `version`.

### Event types

You subscribe by **family**; the `type` field on the wire is more specific than the
family name.

| Subscribe to | Wire `type` values | Fires when |
|---|---|---|
| `ORDER_STATE_CHANGED` | `order.confirmed`, `order.preparing`, `order.ready`, `order.completed`, `order.cancelled`, … | an order moves through its lifecycle |
| `ORDER_REFUNDED` | `order.refunded` | a refund is recorded |
| `ONBOARDING_STATE_CHANGED` | `onboarding.<state>` | vendor onboarding changes state |
| `PAYMENT_EVENT` | `payment.<type>` | a payment event is recorded |

The `order.*` and `onboarding.*` suffixes are the lowercased status name, so new statuses
appear as new `type` values without a new family. Match on prefix, or on the family in
`X-JToye-Event-Type`, rather than on an exhaustive list.

---

## 3. Verifying the signature

### Exactly what is signed

```
signed_bytes = ASCII(t) || "." || raw_request_body_bytes
signature    = lowercase_hex( HMAC-SHA256( key = UTF8(signing_secret), message = signed_bytes ) )
header       = "t=" + t + ",v1=" + signature
```

Point by point, because each of these is a way integrations go wrong:

- **`t` is inside the signed message, not merely alongside it.** It is the same decimal
  unix-seconds string that appears in the header. Signing the body alone gives a different
  digest — [see the fail arms below](#prove-your-verifier-can-fail).
- **The separator is a single ASCII full stop** (`0x2E`). No whitespace, no newline.
- **Sign the raw bytes you received.** Do **not** parse the JSON and re-serialise it before
  verifying. Any re-serialisation — key reordering, whitespace, unicode escaping, a
  trailing newline — changes the bytes and the HMAC will not match. In most frameworks
  this means capturing the raw body *before* the JSON body-parser runs.
- **There is no trailing newline** in the body we send.
- **The hex is lowercase**, 64 characters.
- **Compare in constant time** (`hmac.compare_digest`, `crypto.timingSafeEqual`,
  `MessageDigest.isEqual`), never with `==`.

### The timestamp and the replay window

**`t` is the time of the delivery *attempt*, not the time of the event.** Every retry of
the same event is re-signed with a fresh `t`, so the same `X-JToye-Event-Id` will arrive
with a *different* `X-JToye-Signature` each time. Do not cache or compare signatures
across attempts.

The recommended tolerance is **300 seconds** (`WEBHOOK_DELIVERY_SIGNATURE_TOLERANCE_SECONDS`).
Reject a delivery when `|now - t| > 300`.

Be clear about whose job this is: **we do not enforce the window for you.** We are the
sender; nothing on our side rejects a stale delivery, because staleness only exists at the
receiver. The 300s figure is the value we publish so both sides agree — the check itself
only happens if you write it.

---

## 4. The signature test vector

Run this against your verifier before you go live. Every value below is fixed; if your
implementation is correct you will compute the same `v1` hex, byte for byte.

> **The secret below is a published example in a public repository. It is not a key,
> it has never signed anything, and it must never be used by a real subscription.**

| Input | Value |
|---|---|
| Signing secret | `whsec_example_do_not_use` |
| Timestamp `t` | `1750000000` |
| Body length | 430 bytes |
| Signed message length | 441 bytes (`"1750000000."` is 11 bytes) |

**Payload** — one line, UTF-8, no trailing newline, no whitespace between tokens:

```
{"id":"2b4d0f9a-1c3e-4f57-8a6b-9d0e1f2a3b4c","type":"order.ready","tenantId":"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910","occurredAt":"2026-01-15T09:30:00Z","version":"1","data":{"orderId":"5a4b3c2d-1e0f-4998-8877-665544332211","tenantId":"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910","orderNumber":"JT-1042","previousStatus":"PREPARING","newStatus":"READY","timestamp":"2026-01-15T09:30:00Z","shopId":"3e2d1c0b-9a87-4655-8443-2211ffeeddcc"}}
```

**Expected header:**

```
t=1750000000,v1=fb7885061905854ae6d97c5d587515bb4f4670a675572acce5956ecbe0cb2305
```

If you get a different digest, check the body length first. 430 is the fastest way to spot
a copy that gained a trailing newline or lost a character.

### Reproduce it with `openssl`

Language-independent, and the exact commands used to check this page against the
implementation:

```sh
# 1. the body, with NO trailing newline (printf, not echo)
printf '%s' '{"id":"2b4d0f9a-1c3e-4f57-8a6b-9d0e1f2a3b4c","type":"order.ready","tenantId":"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910","occurredAt":"2026-01-15T09:30:00Z","version":"1","data":{"orderId":"5a4b3c2d-1e0f-4998-8877-665544332211","tenantId":"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910","orderNumber":"JT-1042","previousStatus":"PREPARING","newStatus":"READY","timestamp":"2026-01-15T09:30:00Z","shopId":"3e2d1c0b-9a87-4655-8443-2211ffeeddcc"}}' > body.json
wc -c < body.json          # 430

# 2. the signed message: t + "." + body
printf '1750000000.' > signed.txt
cat body.json >> signed.txt
wc -c < signed.txt         # 441

# 3. the HMAC
openssl dgst -sha256 -hmac 'whsec_example_do_not_use' -hex < signed.txt
# SHA2-256(stdin)= fb7885061905854ae6d97c5d587515bb4f4670a675572acce5956ecbe0cb2305
```

### A verifier, in full

```js
const crypto = require('crypto');

const TOLERANCE_SECONDS = 300;

function parseSignatureHeader(header) {
  const out = {};
  for (const part of String(header).split(',')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i)] = part.slice(i + 1);
  }
  return out;
}

// The HMAC half. No clock involved — this is the half the test vector exercises.
function signatureMatches(rawBody, header, secret) {
  const { t, v1 } = parseSignatureHeader(header);
  if (!t || !v1) return false;
  const mac = crypto.createHmac('sha256', secret);
  mac.update(t, 'ascii');
  mac.update('.', 'ascii');
  mac.update(rawBody);                       // a Buffer of the RAW body, never a re-serialised object
  const expected = Buffer.from(mac.digest('hex'), 'utf8');
  const given = Buffer.from(v1, 'utf8');
  return expected.length === given.length && crypto.timingSafeEqual(expected, given);
}

// The full check: signature AND freshness.
function verify(rawBody, header, secret) {
  const { t } = parseSignatureHeader(header);
  if (!t || !/^\d+$/.test(t)) return false;
  if (Math.abs(Date.now() / 1000 - Number(t)) > TOLERANCE_SECONDS) return false;
  return signatureMatches(rawBody, header, secret);
}
```

**Check the two halves separately.** The vector's `t` is a fixed moment in the past, so
running the *full* `verify()` against it correctly returns `false` — the freshness check
rejects it. That is not the vector failing. Point the vector at the HMAC half
(`signatureMatches`), and test the freshness half against your own clock.

### Prove your verifier can fail

A verifier that accepts everything passes the vector too. Confirm each of these is
**rejected** before you trust it — these are the digests the same inputs produce when
something is wrong, so you can tell a broken verifier from a broken transport:

| Broken input | Resulting `v1` |
|---|---|
| clean, correct | `fb7885061905854ae6d97c5d587515bb4f4670a675572acce5956ecbe0cb2305` |
| one flipped body byte (`"READY"` → `"READX"`) | `e89bd9d5179145c0074f0a97d95d2d5431f177baf0a54e6ef26ebc6e20ec6d77` |
| one changed secret character (`example` → `exampld`) | `7211445c1b8558ebbb695cc8caca9aba56874d2c316f767bb96c5138c492d24c` |
| body signed **without** the `1750000000.` prefix | `439b0400ec351cd6f6ef9b76bf729105812ba917f000c8714b73b461c31f53dd` |

The last row is the useful one to check against your own output. If your verifier computes
that digest, you are signing the body alone and have missed the `t + "."` prefix.

Shifting `t` by one second while leaving `v1` alone must also be rejected — the timestamp
is inside the signed bytes, so an attacker cannot slide a captured delivery forward.

### This vector is executed, not just written

`core-java/src/test/java/uk/jtoye/core/webhook/WebhookSignatureVectorTest.java` asserts it
on every build, from both directions: the signature must be what `WebhookSigner` actually
produces, **and** this page must still publish those exact literals. Change the signing
without changing this page and the build goes red. A documented vector nobody executes is
worse than none — it is confidently wrong.

---

## 5. Delivery, retries, and failure

### Responding

Return **2xx** to acknowledge. Anything else — and any timeout, connection refusal or TLS
failure — counts as a failed attempt.

Our per-attempt timeout is **10 seconds**. Do your work asynchronously: acknowledge fast,
process afterwards. A receiver that does its processing inline will time out under load
and will be retried, which makes the load worse.

### Retry schedule

Up to **8 attempts** per delivery, with exponential backoff from a 1-second base:

| After attempt | Next attempt in |
|---|---|
| 1 | 1s |
| 2 | 2s |
| 3 | 4s |
| 4 | 8s |
| 5 | 16s |
| 6 | 32s |
| 7 | 64s |
| 8 | — terminal `FAILED` |

The delivery worker polls every 5 seconds, so short gaps round up to the next tick. There
is a 1-hour backoff cap configured, but at the default 8 attempts and 1-second base it is
never reached: **the whole retry sequence finishes in roughly two minutes.**

Plan for that. An endpoint down for a ten-minute deploy will exhaust every attempt for
every event in that window. Retries cover a blip, not an outage — for an outage, use the
delivery log to replay.

### What terminal `FAILED` means to you

`FAILED` means **we have stopped trying automatically.** No further attempt will be made
for that delivery. The row stays in the delivery log for **30 days**, and the tenant can
replay it from the dashboard or via
`POST /api/v1/webhooks/{subscriptionId}/deliveries/{deliveryId}/replay`.

A replay **reuses the original `X-JToye-Event-Id`**. If your receiver dedupes properly, a
replay of an event you already processed is a no-op — which is the point, but it does mean
a replay will not re-run a handler that already succeeded. If you need us to re-fire it,
you need the event to occur again.

A delivery also goes straight to `FAILED`, without retrying, if the subscription is not
`ACTIVE` when the row is picked up.

### Auto-pause

The subscription counts **consecutive failed attempts** and flips to `AUTO_PAUSED` at
**10**. Any success resets the counter to zero.

The counter is per *attempt*, not per event — so a single event exhausting its 8 attempts
gets you most of the way there, and **two dead events in a row will auto-pause you.**
While `AUTO_PAUSED`, nothing is delivered. Resume with
`POST /api/v1/webhooks/{id}/resume`, which also clears the counter.

---

## 6. Delivery semantics

- **At-least-once.** You will occasionally see the same event twice. **Dedupe on
  `X-JToye-Event-Id`** and make your handler idempotent. This is a requirement, not a
  suggestion — retries and replays both reuse the id precisely so that you can.
- **No ordering guarantee.** Deliveries are claimed in batches and retried independently.
  `order.ready` can arrive before `order.preparing`. Use `occurredAt` and your own state,
  not arrival order.
- **Isolated per subscription.** One failing endpoint backs off on its own rows and never
  delays another subscription's deliveries.
- **The delivery log is your evidence.** `GET /api/v1/webhooks/{subscriptionId}/deliveries`
  (filterable by status and event type) shows attempt counts, the last HTTP status and the
  last error for 30 days. Check it before reporting a missing event.

---

## 7. Troubleshooting

| Symptom | Most likely cause |
|---|---|
| Signature never matches, vector also fails | You are re-serialising the body. Verify against the raw bytes. |
| Vector passes, live deliveries fail | A body-parser middleware is consuming the stream before you capture it. |
| Your digest equals the "without prefix" row above | You signed the body alone; prepend `t + "."`. |
| Everything fails after a deploy | Secret rotated without an overlap window (§1). |
| Deliveries stopped entirely | Subscription is `AUTO_PAUSED` (§5) — check `GET /api/v1/webhooks/{id}`. |
| Duplicate processing | Not deduping on `X-JToye-Event-Id` (§6). |
| Sporadic failures under load | 10s timeout — acknowledge first, process after (§5). |
