# 27-05 SUMMARY — outbound webhook fan-out had never worked, and the plan's prescribed fix would not have fixed it

**Branch:** `fix/27-05-webhook-converter-trusted-packages` (fix commit `f5faaf2`) · **Merged:** PR
**#310** → `2f8eeca`, 2026-07-27 · **Wave 1** · **Requirements:** OPS-05 · **Complete.**

`RabbitMQConfig.jsonMessageConverter()` returned a bare `new Jackson2JsonMessageConverter()`, whose
default trusted-packages allowlist is `[java.util, java.lang]`. Every outbound webhook event
therefore failed deserialization and dead-lettered — **100% of them, from the day Phase 22 shipped**.
Nine messages were parked in `webhook.deliveries.dlq` spanning `2026-07-15` → `2026-07-26`, with the
producer still live.

Full record: `27-05-EVIDENCE.md`. Every acceptance criterion was run in **both** directions.

---

## The RED baseline (measured before any edit)

| Fact | Measured |
|---|---|
| `webhook.deliveries.dlq` depth | **9** (re-asserted 9 after a `reject_requeue_true` peek) |
| Every message's `__TypeId__` | `uk.jtoye.core.order.OrderStateChangeEvent` |
| `x-death` reason / count | `rejected` / **1** |
| Oldest → newest | `2026-07-15T11:46:18Z` → `2026-07-26T15:33:51Z` (producer **live**) |
| `webhook_subscription` / `webhook_delivery` | 0 / 0 across 6 tenants |

```
Caused by: java.lang.IllegalArgumentException: The class 'uk.jtoye.core.order.OrderStateChangeEvent'
  is not in the trusted packages: [java.util, java.lang].
```

---

## D-A — the plan's prescribed fix does not work

**The plan specified `new Jackson2JsonMessageConverter("uk.jtoye.core")`. That leaves the defect in
place.** `DefaultJackson2JavaTypeMapper.isTrustedPackage` compares the payload's package name to each
allowlist entry with **`String.equals`** — exact match, no prefix and no subpackage handling.
Confirmed by decompiling spring-amqp 3.2.12 (`javap -c`: the comparison is `String.equals` at offset
57), then empirically:

| trusted-packages argument | result for `uk.jtoye.core.order.OrderStateChangeEvent` |
|---|---|
| `"uk.jtoye.core"` — **as prescribed** | **UNTRUSTED — still dead-letters** |
| `"uk.jtoye.core.order"` | OK |
| `"uk.jtoye.core.*"` | UNTRUSTED (no wildcard support) |

**Resolution.** Each contributing package is allowlisted individually via the named constant
`RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES` (`uk.jtoye.core.order`, `.payment`, `.onboarding`), and a
guard test asserts every discovered `@RabbitHandler` payload package is present in it. This keeps
D-01's intent (scoped, never `*`) while turning the enumeration's brittleness into a **build-time**
failure rather than a silent runtime dead-letter.

---

## D-B — AC-1 as written was vacuous

AC-1's `… | strings | grep -c 'uk.jtoye.core'` returned **2 on the broken tree**, before any edit:
`.` is a regex wildcard, so it matched the class-file's internal name
`uk/jtoye/core/config/RabbitMQConfig`, present regardless of the fix.

| | RED tree | fixed tree | |
|---|---|---|---|
| `grep -c` (as written, regex) | 2 | 5 | ← cannot distinguish |
| `grep -cF` (strengthened, literal dots) | **0** | **3** | ← falsifiable |

**Residual limitation, recorded not hidden:** even the strengthened AC-1 is a *presence* check. With
the converter call reverted but the constant left in place it still read 3. Only AC-2's behavioural
delta proves the allowlist is actually *wired to the converter*.

---

## D-C — AC-2's BREAK contradicted AC-4's PASS

AC-2's break requires the DLQ to **increment by 1**; AC-4 requires the depth **unchanged across the
plan**. Both cannot hold at once. Resolved by running the break (it is the load-bearing
falsification), then restoring the queue to exactly its Task 1 state: all 10 archived off-repo, the
queue purged, and the original 9 republished with their original `payload`, `x-death` and
`__TypeId__`. Verified byte-identical to the baseline afterwards; the synthetic break message was
discarded.

---

## Acceptance criteria — both directions

| AC | PASS | BREAK | Verdict |
|---|---|---|---|
| **AC-1** fix in the running artifact | in-jar `grep -cF` = **3**; filesystem `find` = 0 | **0** on the RED jar | PASS (strengthened) |
| **AC-2** a real event no longer dead-letters | real order state change through outbox → flusher → exchange: DLQ **9 → 9, delta 0**; `event=webhook_fanout … subscriptions=1` | converter reverted **and rebuilt**: DLQ **9 → 10, delta +1**, untrusted-packages error reappeared | **PASS, falsified** |
| **AC-3** fan-out produces a delivery | 1 `webhook_delivery` row under the tenant GUC | GUC-unpinned → **0 rows**; wrong-tenant GUC → **0 rows** | PASS |
| **AC-4** existing dead letters untouched + replay viable | depth **9** at start and end, byte-identical; a synthetic copy republished → consumed, fanned out, **not** dead-lettered | same republish with the fix reverted → dead-lettered (+1) | PASS |
| **AC-5** allowlist scoped, never `*` | `grep -c 'Jackson2JsonMessageConverter("\*")'` over main = **0** | positive control: `("*")` injected into an off-repo scratch copy → **1**, proving the grep can fire | PASS |
| **AC-6** the defect class cannot return silently | guard green, 6/6 | a real class-level `@RabbitListener`+`@RabbitHandler` with a `java.time` payload added inside `uk.jtoye.core` → guard **RED** with an actionable message; removed → green | **PASS, falsified** |
| **AC-7** suite + metrics | `:core-java:test` after `cleanTest`: **798 tests, 0 failures**, result mtimes advanced; `docs/metrics.json` 1759→**1765** / 1176→**1182** / 206→**207**; gate exit **0** | gate exit **1** on drift before regeneration | PASS |

**The `UP-TO-DATE` trap was observed live** during AC-7: a first run reported `BUILD SUCCESSFUL in 1s`
while executing nothing. This is why `cleanTest` is load-bearing.

---

## Threat model outcomes

| # | Threat | Outcome |
|---|---|---|
| T-27-05-1 | Deserialization gadget via an over-broad allowlist | Scoped to 3 exact packages; AC-5 forbids `*` with a positive control; a test asserts every entry is inside `uk.jtoye.core` |
| T-27-05-2 | Cross-tenant leakage during replay | Replay went through the normal consumer, which pins the tenant GUC per message; AC-3's break reads through the RLS wall (1 / 0 / 0) |
| T-27-05-3 | Tenant data written to disk by the diagnostic archive | Archive written only to the off-repo session scratchpad; `git ls-files` confirms the path is outside the work tree and cannot be staged |
| T-27-05-4 | Fixing the converter unblocks a flood | Non-issue: 9 messages, none replayed — the batch is untouched for 27-02/27-03 |

---

## Out of scope, recorded not absorbed (D-05)

The seeded test subscription auto-paused after 10 consecutive delivery failures
(`status=AUTO_PAUSED`, `consecutive_failures=10`) because its target URL was not a real webhook sink.
That is `WebhookDeliveryWorker` behaving correctly, **not** a regression — but it means fan-out
silently produces no rows for an auto-paused subscription, and `insertPendingRows` returns before its
log line when no ACTIVE subscription matches. Worth revisiting when delivery semantics are addressed
under **#205**; not fixed here. The test subscription and its delivery rows were deleted afterwards
(`webhook_subscription` / `webhook_delivery` back to 0 / 0).

---

## Handed on

- **27-02** — its disposition checkpoint is unblocked (D-02); the 9 messages are intact and
  **replay-viable**, proven on a synthetic copy.
- **27-03** — archive input untouched at depth 9. Its post-fix `DeadLetterQueueNonEmpty` steady-state
  baseline is **0**, not 9, once 27-02 disposes of the batch.
- **#205** — commented with the verified root cause and evidence; deliberately **not closed**.
