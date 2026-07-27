# 27-05 — Execution evidence

Executed 2026-07-27 on the Compose stack (canonical local runtime).
Fix commit: `f5faaf2` on `fix/27-05-webhook-converter-trusted-packages` (cut from `origin/main`).
Originally executed as `94857a0` on `feature/phase-27-operational-maturity`, then split onto its own
branch so the fix can land ahead of the rest of Phase 27 — 27-05 is wave 1 and blocks 27-02/27-03.
The plan it executes, `27-05-PLAN.md`, lands separately in the Phase 27 planning PR (#309).

Every acceptance criterion was run in **both** directions. Where a criterion could not fail as
written, that is recorded explicitly and a strictly stronger form is used — never silently
substituted.

---

## Task 1 — RED baseline (measured before any edit)

| Fact | Measured |
|---|---|
| `webhook.deliveries.dlq` depth | **9** (re-asserted 9 after a `reject_requeue_true` peek) |
| Every message's `__TypeId__` | `uk.jtoye.core.order.OrderStateChangeEvent` |
| `x-death` reason / count | `rejected` / 1 |
| Oldest → newest `x-death` | `2026-07-15T11:46:18Z` → `2026-07-26T15:33:51Z` (producer **live**) |
| `webhook_subscription` / `webhook_delivery` | 0 / 0 across 6 tenants |
| Converter bean | `RabbitMQConfig.java:385-387`, bare `new Jackson2JsonMessageConverter()` |

Exception chain reproduced verbatim from `docker logs`:

```
ERROR uk.jtoye.core.config.RabbitMQConfig - RabbitMQ message processing failed after 3 retries: Failed to convert message
org.springframework.amqp.AmqpRejectAndDontRequeueException: Exhausted retries — routing to DLQ
Caused by: java.lang.IllegalArgumentException: The class 'uk.jtoye.core.order.OrderStateChangeEvent'
  is not in the trusted packages: [java.util, java.lang].
```

---

## Deviation D-A — the plan's prescribed fix does not work

**Plan D-01 / Task 2 specified `new Jackson2JsonMessageConverter("uk.jtoye.core")`. That leaves the
defect in place.**

`DefaultJackson2JavaTypeMapper.isTrustedPackage` compares the payload's package name to each
allowlist entry with **`String.equals`** — exact match, no prefix or subpackage handling. Confirmed
by decompiling spring-amqp 3.2.12 (`javap -c`, the comparison is `String.equals` at offset 57) and
then empirically:

| trusted-packages argument | result for `uk.jtoye.core.order.OrderStateChangeEvent` |
|---|---|
| `"uk.jtoye.core"` — **as the plan prescribed** | **UNTRUSTED — still dead-letters** |
| `"uk.jtoye.core.order"` | OK |
| `"uk.jtoye.core.*"` | UNTRUSTED (no wildcard support) |

**Resolution.** Each contributing package is allowlisted individually via a named constant
`RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES` (`uk.jtoye.core.order`, `.payment`, `.onboarding`), and the
D-03 guard test asserts every discovered `@RabbitHandler` payload package is present in it. This
keeps D-01's intent (scoped, never `*`) while making the enumeration's brittleness a **build-time**
failure rather than a silent runtime dead-letter.

---

## Deviation D-B — AC-1 as written is vacuous

AC-1's check `… | strings | grep -c 'uk.jtoye.core'` returned **2 on the broken tree**, before any
edit. `.` is a regex wildcard, so the pattern matched the class-file's internal name
`uk/jtoye/core/config/RabbitMQConfig` — a class reference that is present regardless of the fix.

**Stronger form used instead:** `grep -cF 'uk.jtoye.core'` (fixed-string, literal dots). This matches
only a genuine constant-pool *String literal*, which is what the allowlist argument compiles to.

| | RED tree | fixed tree |
|---|---|---|
| `grep -c` (plan, regex) | 2 | 5 | ← cannot distinguish |
| `grep -cF` (strengthened) | **0** | **3** | ← falsifiable |

Filesystem `find` for the class returned `0`, as the plan predicted — the value must be read from
inside the jar.

**Residual limitation, recorded not hidden:** even the strengthened AC-1 is a *presence* check. When
the converter call was reverted but the constant left in place, it still read 3. AC-1 cannot prove
the allowlist is *wired to the converter*; only AC-2's behavioural delta can. AC-2 is the criterion
that matters, as the plan itself states.

---

## Deviation D-C — AC-2's BREAK contradicts AC-4's PASS

AC-2's BREAK requires the DLQ to **increment by 1**; AC-4 requires the depth to be **unchanged across
the whole plan**. Both cannot hold simultaneously.

**Resolution.** The break was run (it is the load-bearing falsification), then the queue was restored
to exactly its Task 1 state: all 10 messages archived off-repo, the queue purged, and the original 9
republished with their original `payload`, `x-death` and `__TypeId__` headers. Verified byte-identical
to the Task 1 baseline afterwards. The synthetic break-test message was discarded.

---

## Acceptance criteria — both directions

| AC | PASS direction | BREAK direction | Verdict |
|---|---|---|---|
| **AC-1** fix in the running artifact | `grep -cF` in-jar = **3** (`uk.jtoye.core.order`, `.payment`, `.onboarding`); filesystem `find` = 0 | 0 on the RED jar | PASS (strengthened; see D-B) |
| **AC-2** a real event no longer dead-letters | real order state change via the outbox → flusher → exchange: DLQ **9 → 9, delta 0**; `event=webhook_fanout … subscriptions=1` | converter reverted + **rebuilt**: DLQ **9 → 10, delta +1**, and `The class 'uk.jtoye.core.order.OrderStateChangeEvent' is not in the trusted packages: [java.util, java.lang]` reappeared | **PASS, falsified** |
| **AC-3** fan-out produces a delivery | 1 `webhook_delivery` row under the tenant GUC | same query GUC-unpinned → **0 rows**; wrong-tenant GUC → **0 rows** | PASS |
| **AC-4** existing dead letters untouched + replay viable | depth **9** at Task 1 and **9** at end, payloads/`x-death`/`__TypeId__` byte-identical; synthetic copy republished → consumed, fanned out (`type=order.preparing`), **not** dead-lettered | same republish with the fix reverted → dead-lettered (+1) | PASS |
| **AC-5** allowlist scoped, not `*` | `grep -c 'Jackson2JsonMessageConverter("\*")'` over `core-java/src/main` = **0** | positive control: `("*")` injected into an off-repo scratch copy → **1**, proving the grep can fire | PASS |
| **AC-6** defect class cannot return silently | guard green on the real tree (6/6 tests) | added a real class-level `@RabbitListener`+`@RabbitHandler` with a `java.time` payload inside `uk.jtoye.core` → guard **RED** with the actionable message; removed → green again | **PASS, falsified** |
| **AC-7** suite + metrics | `:core-java:test` after `cleanTest`: **798 tests, 0 failures, 0 errors**, result mtimes advanced; `docs/metrics.json` regenerated 1759→**1765** / 1176→**1182** / 206→**207** files; gate exit **0** | gate exit **1** on drift before regeneration, proving it fails closed; `UP-TO-DATE` trap observed live — a first run reported `BUILD SUCCESSFUL in 1s` while executing nothing, which is why `cleanTest` is load-bearing | PASS |

---

## Threat model outcomes

| # | Threat | Outcome |
|---|---|---|
| T-27-05-1 | Deserialization gadget via over-broad allowlist | Scoped to 3 exact packages; AC-5 forbids `*` with a positive control; a test asserts every entry is inside `uk.jtoye.core` |
| T-27-05-2 | Cross-tenant leakage during replay | Replay went through the normal consumer, which pins the tenant GUC per message; AC-3's break proves the assertion reads through the RLS wall (1 / 0 / 0) |
| T-27-05-3 | Tenant data written to disk by the diagnostic archive | Archive written only to the off-repo session scratchpad; `git ls-files` confirms the path is **outside the work tree** and cannot be staged |
| T-27-05-4 | Fixing the converter unblocks a flood | Non-issue: 9 messages, and none were replayed — the batch is untouched for 27-03/27-02 |

---

## Out of scope, recorded not absorbed (D-05)

The seeded test subscription auto-paused after 10 consecutive delivery failures
(`status=AUTO_PAUSED`, `consecutive_failures=10`), because its target URL was not a real webhook
sink. That is `WebhookDeliveryWorker` behaving correctly, not a regression — but it means fan-out
silently produces no rows for an auto-paused subscription, and `insertPendingRows` returns before its
log line when no ACTIVE subscription matches. Worth a look when delivery semantics are addressed
under #205; **not fixed here**.

Test subscription and its delivery rows were deleted afterwards; `webhook_subscription` and
`webhook_delivery` are back to 0 / 0.

---

## Cross-plan state handed on

- **27-02** — its disposition checkpoint is now unblocked (D-02) and the 9 messages are intact and
  **replay-viable**, proven on a synthetic copy.
- **27-03** — archive input untouched at depth 9. Its post-fix `DeadLetterQueueNonEmpty` baseline
  should be **0**, not 9, once 27-02 disposes of the batch.
- **#205** — commented with the verified root cause and evidence; deliberately **not closed**.
