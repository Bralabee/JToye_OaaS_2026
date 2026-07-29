# 27-03 — Execution evidence (Tasks 0–6)

Executed 2026-07-29 on the Compose stack (the canonical local runtime), branch
`feature/27-03-alerting-dlq-runbook`, cut from `origin/main` @ `9da0761`, 0 behind.

**Tasks 0–6 complete. Task 7 is a `checkpoint:human-action` and is NOT executed here. Task 8 is
blocked on 27-02 (wave 3), which has not run.** The plan is therefore **not** complete and no
SUMMARY is written — asserting completion over two outstanding tasks is the green-by-construction
failure this phase exists to remove.

Every acceptance criterion was run in **both** directions. Where a criterion could not fail as
written, that is recorded explicitly with the measurement that refutes it and a strictly stronger
form is used — never silently substituted.

---

## 1. Preconditions (Task 0) — all four SATISFIED

| # | precondition | measured |
|---|---|---|
| T0.1 | 27-00 Task 1, the `core-java:9091 → 9090` scrape fix | `up{job="core-java"}` = **1** |
| T0.2 | 27-00 Task 4, `scripts/check-alert-liveness.sh` | present, executable, **rc=1** at `2026-07-29T03:09:46Z` (8 live detection defects — its designed state, per 27-00-SUMMARY) |
| T0.9 | `POSTGRES_EXPORTER_SSLMODE` (D-P, 27-00 owns) | `.env.example` `=disable` **1** / `=require` **0**; **live exporter env `sslmode=disable`** |
| — | 27-04 and 27-05 merged | `RabbitMQConfig` carries `TRUSTED_PAYLOAD_PACKAGES` and a `mediaRabbitListenerContainerFactory` |

`scripts/check-alert-liveness.sh` exit code **1**, timestamp **2026-07-29T03:09:46Z** — required in
every phase SUMMARY by 27-00.

## 2. The seven pre-change REDs

### T0.3 — the historical defect, and the control that makes it a *label* defect

```
query=sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0
  -> {"resultType":"vector","result":[]}

CONTROL query=rabbitmq_queue_messages_ready
  -> [{"metric":{"__name__":"rabbitmq_queue_messages_ready","component":"messaging",
       "instance":"jtoye-rabbitmq:15692","job":"rabbitmq","service":"rabbitmq"},"value":[...,"9"]}]

CONTROL metric keys: "__name__","component","instance","job","service"
```

The metric exists and its value is non-zero. **There is no `queue` label key at all.** The absence
of the label — not the value — is the load-bearing half.

### T0.4 — runbook coverage

```
live '- alert:' lines      = 14
commented '# - alert:'     = 2
'## ' headings in runbook  = 10

SET DIFFERENCE (live alerts with NO runbook heading):
  KeycloakDown
  PaymentFailureSpike
  RedisDown
  StompBrokerLag
```

Four, not one. Exactly the four the brief predicted.

### T0.5 — `pg_up`. **The drafted RED is NO LONGER REPRODUCIBLE, and that is recorded, not glossed**

The plan expects `pg_up = 0` with `pq: SSL is not enabled on the server`. Measured on this tree:

```
pg_up               = 1
up{job=postgres}    = 1
grep -c 'pg_up' alerts.yml = 0
```

**27-00 landed the `.env` DSN fix before this plan ran, so the exporter is healthy.** The half of
the defect that survives is the one that matters and it is unchanged: the correct signal is present,
healthy, and **referenced by no rule**. 27-00's own gate reports it as
`L-1b gauge 'pg_up' (job='postgres') is referenced by NO rule … a healthy gauge nobody reads is not
detection`.

The RED was **recovered deliberately** in T2.4 by forcing `sslmode=require` on the exporter alone —
see §5, which is the stronger proof anyway because it evaluates both rule versions against the
identical state.

### T0.6 — the JVM alerts measure one JVM and say another

```
jvm_memory_used_bytes{area="heap"}[0].metric
  -> {"area":"heap","component":"auth","id":"G1 Eden Space","instance":"jtoye-keycloak:8080",
      "job":"keycloak","service":"keycloak"}

count by (job) (jvm_memory_used_bytes)  -> {job="keycloak"}=8  AND  {job="core-java"}=8
service labels actually carried        -> core-api, keycloak
```

against `alerts.yml`'s static `service: core-java`. **Post-27-00 both JVMs contribute**, so the
mis-labelling is now not merely wrong but ambiguous across two instances — exactly as predicted.

### T0.7 — the broken promtool invocation, and the two that work

```
ARM A  docker run --rm -v …:/rules:ro prom/prometheus:v2.48.0 promtool check rules /rules/alerts.yml
       Error parsing command line arguments: unexpected promtool
       prometheus: error: unexpected promtool            rc=1

ARM B  docker run --rm --entrypoint=promtool -v …:/rules:ro prom/prometheus:v2.48.0 check rules /rules/alerts.yml
       SUCCESS: 14 rules found                            rc=0

ARM C  docker exec jtoye-prometheus promtool check rules /etc/prometheus/alerts.yml
       SUCCESS: 14 rules found                            rc=0

command -v promtool -> NOT INSTALLED on host
```

Exit codes captured on their own line, never after an `echo`.

### T0.8 — the real dead letters. **N = 9**

```
media.process                                   0  1
media.process.dlq                               0  0
onboarding.notifications                        0  1
order.notifications                             0  1
order.state-changes                             0  1
order.state-changes.dlq                         0  0
order.state-changes.sse.Zn_7iF4QTQKtnBnyuIKn9Q  0  1
payment.events                                  0  1
payment.events.dlq                              0  0
payment.notifications                           0  1
refund.notifications                            0  1
webhook.deliveries                              0  1
webhook.deliveries.dlq                          9  0      <- PASS: >= 1 message, 0 consumers
```

13 queues. **BREAK/CONTROL**, required so the read is not a constant: the same `jq` filter against
`payment.events.dlq` returns **0**. `N` is bound to **9**; every downstream depth assertion uses `N`
or a delta, never a literal.

## 3. Task 1 — the `rabbitmq-queues` scrape job

**PLAN-VS-REALITY DRIFT, and it would have been a silent no-op.** The plan's `files_modified` names
`infra/monitoring/prometheus/prometheus.yml`. **That file does not exist** — 27-00 replaced it with
`prometheus.yml.tmpl` + `entrypoint.sh`, which renders it at container start. Editing the rendered
name would have created a new file the running Prometheus ignores. The `.tmpl` was edited, and every
assertion below reads the change back out of the **running** Prometheus, never out of the source.

| criterion | PASS | BREAK |
|---|---|---|
| **T1.1** depth agreement | prometheus **9** == management-API **9**, both >= 1 | `queue="does.not.exist"` → `[]`; the OLD job's `rabbitmq_queue_messages_ready{queue="webhook.deliveries.dlq"}` → `[]` (proving the NEW job supplies the label) |
| **T1.2** SSE dropped | `count(rabbitmq_detailed_queue_consumers)` = **12** of 13 | with the drop rule commented out: **13** |
| **T1.3** bounded cost | `scrape_samples_scraped{job="rabbitmq-queues"}` = **71** (< 200) | switched to `/metrics/per-object`: **2996** — the criterion fires. Restored: **71** |
| **T1.4** the drop rule is load-bearing | `count(…{queue=~"order[.]state-changes[.]sse[.].*"})` → `[]` | drop rule commented out → **1 series**, named `order.state-changes.sse.Zn_7iF4QTQKtnBnyuIKn9Q`. Restored → `[]` |
| **T1.5** all targets up | unhealthy count **0** | job pointed at `:15693` → **1**, named `rabbitmq-queues`, `dial tcp 172.18.0.14:15693: connect: connection refused` |
| **T1.6** outbox counters reachable | all four of `payment/media_outbox_dead_letter_total` and `*_resurrected_total` → 1 series each | — (consumes 27-00 Task 1) |
| **T1.7** job SET, not a diff | before `core-java,edge-go,keycloak,postgres,prometheus,rabbitmq,redis` → after adds exactly `rabbitmq-queues`, removes nothing | `edge-go` job deleted → the set check names it as **REMOVED**; restored |

Endpoint sizes re-measured at 13 queues: `/metrics/detailed` **92** lines, `/metrics/per-object`
**3439**, `/metrics` **3135**.

**Lesson worth carrying: a restore is not instantaneous.** After restoring the drop rule the SSE
series stayed visible for **~4.5 minutes** — Prometheus' 5-minute lookback still held samples
ingested during the break arm. A criterion re-checked immediately after a restore reads the BREAK
value and looks like a failed restore. Every restore here was polled to a deadline, not assumed.

## 4. Task 2 — the rules

```
promtool: 14 rules found (before)  ->  19 rules found (after)     rc=0
live '- alert:' = 19 ; commented '# - alert:' = 3
```

19 = 14 − `StompBrokerLag` + 6 new. Three commented blocks, not two — Task 2 **adds** the third by
design, which is why a comment-blind extractor would demand 22 headings and not 21.

**T2.8 — set arithmetic over the RUNNING config:** ADDED exactly `DeadLetterQueueNonEmpty`,
`DomainQueueBacklog`, `MessagingConsumerMissing`, `OutboxDeadLetterRising`,
`PaymentDeadLetterQueueNonEmpty`, `RabbitMQDown`; REMOVED exactly `StompBrokerLag`. The three
D-11-corrected rules appear in neither set, as their names are unchanged.
**BREAK:** `RedisDown` renamed to `RedisIsDown` → the check named both the removal and the addition.

**T2.2 — per-rule anti-vacuity**, recorded individually rather than as one aggregate pass:

| rule | selector | series |
|---|---|---|
| RabbitMQDown | `up{job="rabbitmq"}` | 1 |
| PaymentDeadLetterQueueNonEmpty | `rabbitmq_detailed_queue_messages{queue="payment.events.dlq"}` | 1 |
| DeadLetterQueueNonEmpty | `…{queue=~".*[.]dlq", queue!="payment.events.dlq"}` | 3 |
| DomainQueueBacklog | `…_ready{queue!~".*[.]dlq\|order[.]state-changes[.]sse[.].*"}` | 8 |
| MessagingConsumerMissing | `rabbitmq_detailed_queue_consumers{queue!~".*[.]dlq"}` | 8 |
| OutboxDeadLetterRising (a) | `payment_outbox_dead_letter_total` | 1 |
| OutboxDeadLetterRising (b) | `media_outbox_dead_letter_total` | 1 |

**T2.3 — the headline: firing on REAL production-shaped data, no manufactured input.**

```
2026-07-29T03:30:34Z
alert     : firing webhook.deliveries.dlq 9e+00   activeAt=2026-07-29T03:24:37Z
mgmt depth: 9    (N from T0.8 = 9)
V == N    : YES
```

Asserted as *agreement between two independent readings*, never as the literal 9 — the producer was
live until 27-05 landed, so a constant would fail on a correct tree the moment a tenth arrived.

**T2.7 — false-positive guard.** PASS: `MessagingConsumerMissing` names **0** `.dlq` queues.
**BREAK:** selector widened to `{queue=~".*"}` → after `for: 5m` it fired on **4**, naming
`media.process.dlq`, `order.state-changes.dlq`, `payment.events.dlq`, `webhook.deliveries.dlq` —
precisely the four permanent false positives the real selector prevents.

**T2.6 — the defence-in-depth exclusion, made provable.** With Task 1's drop rule commented out the
raw family gains `order.state-changes.sse.Zn_7iF4QTQKtnBnyuIKn9Q` while `DomainQueueBacklog`'s own
selector still returns the 8 domain queues and **0** SSE names. Without this arm the criterion
proves nothing, because the series does not exist to be excluded.

**T2.9 — dormancy, four ways.** live `- alert: StompBrokerLag` **0**; name survives **5**×;
comment carries `rabbitmq_detailed_queue_messages_ready` and `STOMP_BROKER_MODE`; Prometheus
`index("StompBrokerLag")` → **null**. **BREAK-a:** uncommenting only the `- alert:` line →
`promtool` fails `field 'expr' must be set in rule`, which is itself evidence the block is genuinely
commented. **BREAK-b:** uncommenting the whole block → promtool **20 rules**, Prometheus index
**17**. Restored → 19 / null. BREAK-b doubles as proof that the preserved expression is valid and
loads, i.e. re-enabling really is an uncomment.

## 5. T2.4 — `DatabaseDown` can now detect an outage. **The discriminating pair**

Break arm: `POSTGRES_EXPORTER_SSLMODE=require` forced on the exporter **only** (shell override, no
`.env` edit — a second session may share this checkout).

```
exporter env: sslmode=require
pg_up            = 0
up{job=postgres} = 1

OLD expr  up{job="postgres"} == 0            -> []                    <- reports HEALTHY
NEW expr  up{job="postgres"} == 0 or pg_up == 0 -> [{"__name__":"pg_up", … ,"0"}]

exporter log:
  level=error msg="Error opening connection to database" err="error querying postgresql version:
    pq: SSL is not enabled on the server"
```

Both expressions evaluated against the **identical state**. `DatabaseDown` went `pending` →
**firing** within `for: 1m` (`activeAt 03:34:26Z`, firing by `03:35:44Z`), then resolved within one
scrape of the restore. This also recovers the T0.5 RED that 27-00's fix had made unreproducible,
including its exact cause line.

## 6. T2.5 — the JVM label fix

```
HighMemoryUsage           labels: {"component":"jvm","severity":"warning"}     <- no "service" key
FrequentGarbageCollection labels: {"component":"jvm","severity":"warning"}
series service labels the expression measures: core-api, keycloak
```

**BREAK:** re-adding `service: core-java` → the rules API immediately shows
`{"component":"jvm","service":"core-java","severity":"warning"}`, i.e. the static label overriding
the series'. Removed again.

*Recorded because it is instructive:* the file-level `grep -c '^          service: core-java$'`
marker read **8** during the break and **7** after the restore, not 1 and 0 — seven other rules
legitimately carry that label. The **API assertion** is the load-bearing one; the grep counts a
token that appears elsewhere.

## 7. T2.10 — the regex triple. **The draft predicted the OPPOSITE of the measurement**

| form | status | series |
|---|---|---|
| `rabbitmq_detailed_queue_messages{queue=~".*[.]dlq"}` | success | **4** |
| `rabbitmq_detailed_queue_messages{queue=~".*\\.dlq"}` | success | **4** — identical |
| `rabbitmq_detailed_queue_messages{queue=~".*\.dlq"}` | **error** | `bad_data: invalid parameter "query": 1:41: parse error: unknown escape sequence U+002E '.'` |

PromQL unescapes `\\` → `\` before RE2 sees it, so `\\.` is a literal dot and matches exactly like
`[.]`. The draft demanded the `\\` form return **0 series**, which **fails on a correct tree** — and
the cheapest way to make it pass is to rewrite regexes that were already right. **`\\.` elsewhere in
this repo is correct and must not be "fixed".** The real hazard is the single backslash, and it
fails **loudly** at parse time rather than silently matching nothing. `[.]` is used here as house
style only, because it is byte-identical under every YAML scalar form.

## 8. Task 3 — the two gates

### T3.2 — THE criterion: the gate catches the bug that motivated it, on the REAL historical artifact

Run against `git show origin/main:infra/monitoring/prometheus/alerts.yml`:

```
FAIL: M-1 rule 'StompBrokerLag' selector matches ZERO series — this rule can never fire:
      rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}
FAIL: DORMANT_RULES names 'StompBrokerLag' but it is LIVE in … — STALE entry, remove it
rc=1
```

**Run WITH the `DORMANT_RULES` seed in place.** The seed does *not* convert the proof into its
opposite: a rule that is live is never treated as dormant, so the seed produces a **second,
independent** violation rather than masking the first.

### Falsification matrix

| criterion | PASS | BREAK |
|---|---|---|
| **T3.1** | static rc=**0** (19 live / 3 commented / 2+3 exemptions); live rc=**0** (19 rules / 24 selectors / 3 dormant) | — |
| **T3.3** metric exists, **label VALUE** does not | rc=0 | added `…{queue="typo.not.a.queue"}` with full labels + a heading → rc=**1** naming it |
| **T3.4** metric exists, **label KEY** does not | rc=0 | `queue=` → `queue_name=` → rc=**1**. Control: the bare metric name still matches **12** series, so a name-only gate PASSES this break — which is the F-3 defect exactly |
| **T3.5** VOID | — | unreachable Prometheus → **2**; `jq` off the resolved PATH → **2**, with a control proving the same stripped env WITH `jq` exits **0**; `groups: []` → **2** on both gates |
| **T3.6** dormant wake-up | rc=0 with no such queue | created `stomp-subscription-probe27`, published 1 → rc=**1**, *"M-2 DORMANT rule 'StompBrokerLag' NOW HAS DATA (1 series) — re-enable it"* with the trigger. Deleted → rc=0 |
| **T3.7** static RED on the pre-change tree | after Task 4: rc=0 | before Task 4: rc=1 naming exactly `KeycloakDown`, `PaymentFailureSpike`, `RedisDown`, `StompBrokerLag`. No tampering needed |
| **T3.8** label completeness + exemption hygiene | rc=0 | (a) `severity` deleted from `RabbitMQDown` → named; (b) `severity` deleted from `HighMemoryUsage`, a `service`-only exemption → still named, proving the exemption is narrow; (c) `service: core-java` re-added → **STALE exemption** |
| **T3.9** comment rule | commented block appended → violations **12 → 12**, commented count **3 → 4**, zero heading demand | leading `#` removed from the `-` → violations **12 → 19**, live count **19 → 20**, and it demands `## ZzNotARealRuleForTheControl`. Independent of the `DiskSpace*` headings |
| **T3.10** headers | both `bash -n` 0 and `test -x`; `StompBrokerLag` ×5 and `check-alert-liveness` ×4 in the live gate; `27-06` ×2 and `F-8` ×2 in the static gate | — |
| **T3.11** no CI touched | `check-alert-metrics.sh`/`check-alert-rules.sh` in `.github/workflows/` = **0**; `git diff --stat origin/main..HEAD -- .github/` empty | CONTROL: `check-branch-behind-base.sh` = **2**, proving the grep finds a wired gate when one exists |

### T3.9b — the drafted pipefail assertion is UNSATISFIABLE, and here is the measurement

| file | naive `grep -c '\| *grep -q'` (drafted, == 0) | scoped to non-comment lines |
|---|---|---|
| `check-alert-rules.sh` | 0 | 0 |
| `check-alert-metrics.sh` | **1** | 0 |

The single naive hit is line 53 — **the script's own pipefail warning**, which must name the token
it forbids. This is the recorded "a doc rule that must name the string it forbids" shape.
**NON-VACUITY CONTROL for the scoped form:** a real `echo hi | grep -q hi` appended to a scratch
copy → scoped count **1**, so the scoped check can fire. Here-string form present: 2 and 1.

### Two defects found in the gates by running them against themselves

1. **The expression stripper leaked label names.** It emitted `service` (×3) and `le` out of
   `by (service, le)` grouping clauses and queried them as metric names. They were **masked by the
   exemption list** and so invisible in the exit code. The stripper now skips a grouping clause's
   whole argument list; selectors went 28 → 24, all 24 audited by `DEBUG_SELECTORS=1`.
2. **The drafted pipefail criterion** above.

### Deviation — `KNOWN_DATALESS` (Rule 2, recorded not silent)

`check-alert-metrics.sh` cannot exit 0 on the post-Task-2 tree, because **three pre-existing live
rules have selectors matching zero series** in rules Task 2(c) forbids touching. Rather than weaken
the gate or edit out-of-scope rules, they are carried in a `KNOWN_DATALESS` list with a reason and
an owner each, under the same hygiene as `DORMANT_RULES`: empty reason → FAIL, duplicate → FAIL,
entry naming a non-live rule → FAIL as STALE, and an entry whose selectors **start matching** →
FAIL as STALE. A **new** dataless rule remains a hard violation, which is the assertion the gate
exists for. Full detail in `deferred-items.md` §10.

## 9. Task 4 — the runbook

Headings **10 → 23**. `scripts/check-alert-rules.sh` **rc=1 → rc=0**, which discharges the
obligation 27-00-SUMMARY records against this plan.
**BREAK:** deleting `## MessagingConsumerMissing` → rc=1 naming it; restored → rc=0.

### T4.2 — the replay harness. The drafted criterion was self-attested; this one can fail

```
extracted=22 executed=18 passed=18 failed=0 exempt=4
PASS: all 18 replayable runbook command(s) exit 0 with non-empty output (4 exempt, each with a reason)

RED ARM (a can-never-succeed command inserted into the runbook):
FAIL rc=4 bytes=0  curl -s --max-time 2 http://127.0.0.1:1/api/queues/%2F | jq -e ".[0]"
extracted=23 executed=19 passed=18 failed=1 exempt=4      rc=1
```

**The harness's own first version was defective and its own guard did not catch it.** It anchored on
`/^```bash$/` and found **4 of 22** blocks — every command inside a numbered first-response list has
an *indented* fence. Its `executed == extracted` check passed because both numbers were consistently
wrong. It now allows leading whitespace and carries an **independent cross-check**: the extracted
count must equal `grep -c` of the fences, computed by a different tool than the extractor. A harness
that drops 18 of 22 commands and reports PASS is the same class of defect as the criterion it
replaces.

Four exemptions, **detected structurally rather than hand-listed** so the list cannot quietly grow:
three angle-bracket `<placeholder>` templates plus a `| less` pager (all pre-existing `ServiceDown`
content — `deferred-items.md` §12), and `rabbitmq-diagnostics alarms`, whose empty stdout is the
CORRECT healthy result and would invert the non-empty rule.

Shape evidence required verbatim: the `reject_requeue_true` peek decodes a real `x-death` block
(§11); the queue list returns **13** rows (>= 13, not exactly 13); the `pg_isready` + exporter-log
pair is in §5.

### T4.3 / T4.5 / T4.6

- **T4.3** non-destructiveness: depth **9** immediately before and **9** immediately after the peek.
  The destructive counterpart was exercised only on queues this plan created (§10, §11).
- **T4.5** — the drafted `grep -c 'DLQ inaccessible' == 0` was **unsatisfiable while the correction
  note quoted the phrase it replaced** (measured **2**, both quotations). Resolved by rephrasing the
  note *and* asserting a strictly stronger form: the original bullet text
  `'fanning out, DLQ inaccessible'` → **0** post-change, **1** on `origin/main`'s file (positive
  control); replacement `'publishing and consuming stop'` → **1** post-change, **0** on
  `origin/main`. Both forms now hold.
- **T4.4** `reject_requeue_true` 2, `x-death` 6, `SET LOCAL app.current_tenant_id` 1.
  **T4.6** `#304` 1.

## 10. Task 5 — the counter, the compile break, the CLI

### T5.0 — the F-9 compile break, proven

```
BREAK: factory.setAdviceChain(retryInterceptor());
  RabbitMQConfig.java:568: error: method retryInterceptor in class RabbitMQConfig cannot be applied
    to given types;
        factory.setAdviceChain(retryInterceptor());
      required: ObjectProvider<MeterRegistry>
  compileJava rc=1
RESTORED: rc=0
```

Rebased onto 27-04, not against it: the interceptor is **added** as a parameter to both factory bean
methods, alongside 27-04's `ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer>`.
27-04's `MediaListenerConcurrencyIntegrationTest` calls that factory directly and was updated in the
same change (Rule 3).

### T5.1 — tests EXECUTED, read from `build-local`

| class | testcases |
|---|---|
| `RabbitMQDeadLetterTopologyTest` | 7 |
| `RabbitMQRetryExhaustedCounterTest` | 9 |
| `RabbitMQListenerFactoryBehaviourTest` | 3 |
| `RabbitListenerContainerFactoryTest` (27-04's, unbroken) | 7 |

`core-java/build/test-results/test/` is a stale **2025-12-27** artifact and was never read.

### T5.2 – T5.5 falsification

| criterion | break | result |
|---|---|---|
| **T5.2** counter | increment removed | 3 tests RED |
| **T5.3** cardinality guard | `normaliseQueueTag` made a passthrough | 2 tests RED, naming the random suffix |
| **T5.4** the D-07 pin | `x-dead-letter-exchange` added to `onboardingNotificationsQueue()` | `RabbitMQDeadLetterTopologyTest` RED naming that queue |
| **T5.5a** | `setDefaultRequeueRejected(false)` → `(true)` | 2 tests RED |
| **T5.5b** | `setAdviceChain(...)` deleted | 2 tests RED |
| **T5.5 cross-check vs 27-04** | the call **relocated** (a correct refactor — `git diff --numstat` 1/1, line 569 → 571) | **BUILD SUCCESSFUL** — the assertion survives a correct refactor, which the diff grep it replaces did not |

**Advice-chain field name, verified against the classpath rather than trusted:** `adviceChain` on
`SimpleMessageListenerContainer`, read via `ReflectionTestUtils`; spring-amqp/spring-rabbit
**3.2.12**. `getAdviceChain()` is protected, so there is no public accessor.

### T5.6 — and the DEFECT the live proof caught that every unit test missed

**The plan's prescribed input cannot satisfy this criterion.** A well-formed event naming a
non-existent `assetId` takes `MediaProcessingWorker`'s **no-throw early return**:

```
WARN u.j.core.media.MediaProcessingWorker - event=media_process_skipped reason=asset_not_visible …
counter after arm 1: ABSENT      media.process.dlq depth: 0
```

No retry, no dead-letter, no counter — the criterion as written fails on a correct tree. (27-04's
own EVIDENCE records this same early return.) Replaced with a strictly stronger input: a **null
`tenantId`**, which the worker's first statement dereferences, producing an NPE that escapes to the
retry advice (NPE is not on `ConditionalRejectingErrorHandler`'s fatal list, so it is retried rather
than rejected immediately).

**First run of the stronger arm exposed a real defect:**

```
jtoye_amqp_retries_exhausted_total{queue="unknown"} 1.0
```

Diagnosed by measurement, with a temporary probe in the recoverer:

```
DIAG args=2 c0=jdk.proxy2.$Proxy293 q=not-a-Message
```

Spring AMQP does **not** proxy `MessageListener.onMessage(Message)`. It applies the advice chain to
`AbstractMessageListenerContainer`'s internal `ContainerDelegate`, whose signature is
`invokeListener(Channel, Object)` — so `args` has length 2 and **`args[0]` is a Channel proxy**. The
`args[0] instanceof Message` test never matched in production and **every** increment would have
landed on `queue="unknown"`, destroying the per-queue attribution that is the whole point of D-05.

**Why the unit test did not catch it — the transferable part.** Its fixture built
`new Object[]{message}`, a shape production never produces. It encoded my assumption about the
runtime rather than the runtime, and was therefore structurally incapable of failing on this defect.
Fixed by `findMessage(Object[])`, which scans the arguments (and unwraps a `List` for batch
listeners); the fixture now builds the real `(Channel, Message)` shape, and two new tests pin it —
one asserts position 0 is **not** a Message so the fixture cannot drift back, one asserts the tag is
`media.process` and that **no `unknown` series exists**. Reverting `findMessage` to the `args[0]`
form turns **four** tests red.

After the fix, end to end on the rebuilt running process:

```
before: ABSENT                        media.process.dlq depth 0
after : jtoye_amqp_retries_exhausted_total{queue="media.process"} 1.0    depth 1
log   : ERROR RabbitMQConfig - RabbitMQ message processing failed after 3 retries: …
        org.springframework.amqp.AmqpRejectAndDontRequeueException: Exhausted retries — routing to DLQ
```

**`x-death[0].count` recorded, not asserted:** **1**, not 3 — confirming the plan's own correction.
`x-death.count` counts dead-letterings, not delivery attempts, and the retry interceptor retries
in-process. The drill message was purged by a `trap` on every exit path; `media.process.dlq` is back
to 0.

### T5.7 — runtime parity, proven by content and identity

```
BEFORE the rebuild:
  core-java  DRIFT [image-not-rebuilt]  image tagged 2026-07-28 21:45:49 UTC
             / newest build-input commit 151a6bc (2026-07-29 04:07:14 UTC)
  FAIL: 1 of 4 running built service(s) do not match the source tree     rc=1

AFTER `docker compose … up -d --build core-java`:
  core-java  FRESH  image tagged 2026-07-29 04:17:03 UTC >= commit 09da844 (04:16:14 UTC)
  PASS: 4 running built service(s) match the source tree (0 unverified)  rc=0
```

Read out of the **running artifact**, not the filesystem:

```
unzip -p /app/app.jar …/RabbitMQConfig.class | strings | grep -c "jtoye.amqp.retries_exhausted"  -> 1
find / -name "RabbitMQConfig.class" | wc -l                                                      -> 0   <- the misleading form
```

### T5.8 / T5.9 — `dlq-inspect.sh`, and two defects its own break arms found

| arm | result |
|---|---|
| PASS-1 `--summary` while a DLQ holds N>=1 | **1** |
| VOID-a unreadable creds file | **2** — *"Refusing to fall back to guest:guest"* |
| VOID-b unreachable API | **2** |
| VOID-c **bad credentials** | **2** — *"did not return a queue LIST: not_authorized"* |
| CONTROL real creds + real API | **1** — so the VOIDs came from the creds/API arms, not from a script that always VOIDs |
| `--list` | **0** |
| T5.9 delta | depth **9** before and **9** after `--peek … 5`; asserted as a delta, never as a literal |
| T5.9 break | `--peek q 5 --ackmode get` → **2**, refused by name; depth unchanged at 9 |

**Two defects found by these arms, both fail-open shapes, both fixed:**

1. `--ackmode get` was **silently ignored** and exited **0**. Nothing was consumed (the ackmode is
   hardcoded), but "ignored" and "accepted" are indistinguishable to an operator who typed it. Extra
   arguments are now refused at exit 2.
2. Bad credentials returned **valid JSON** (`{"error":"not_authorized"}`), so the `jq -e .` check
   passed and the queue filter died with a leaked jq error at **rc=5**. A shape check
   (`type == "array"`) now VOIDs instead — an auth failure must never read as "no queues".

### T5.10 — metrics

`1832 → 1849 → 1851`; java `1240 → 1259` methods across `216 → 219` files. The +19 methods are
exactly the 7 + 9 + 3 in the three new test files, counted with the same literal `@Test\b` grep
`scripts/docs-freshness.sh:46` uses. `CLAUDE.md:15` and `AGENTS.md:15` updated in the same commits.
`docs-freshness` **rc=0** after regeneration (**rc=1** before, proving it fails closed);
`check-doc-versions.sh` **rc=0**. Regenerated with `--write`, never hand-edited.

## 11. Task 6 — the dead letters: archived, characterised, untouched

Archive at a session-scratchpad path, **verified outside `$REPO_ROOT` by `realpath`**.
**BREAK, required because a scratchpad path is invisible to git in both directions:** a copy written
to `$REPO_ROOT/tmp-archive-control.json` **is** listed by `git status --porcelain` (1), and the
`realpath` prefix check fires on it. Deleted; the archive remains outside.

Non-destructive: depth **9** before and **9** after the archiving peek. `jq '.messages | length'` =
**9** = N.

```json
{
  "message_count": 9,
  "death_time_range": { "oldest": "2026-07-15T11:46:18Z", "newest": "2026-07-26T15:33:51Z" },
  "x_death_counts": { "1": 9 },
  "first_death_reasons": [ "rejected" ],
  "routing_keys": [ "order.state.completed", "order.state.confirmed", "order.state.pending",
                    "order.state.preparing", "order.state.ready" ],
  "type_ids": [ "uk.jtoye.core.order.OrderStateChangeEvent" ],
  "source_queues": [ "webhook.deliveries" ],
  "source_exchanges": [ "order.events" ],
  "distinct_tenants": 1,
  "cause_ref": "27-05 — Jackson2JsonMessageConverter constructed with no trusted packages; …"
}
```

**All three consistency predictions hold** — one `__TypeId__`, one first-death reason,
`x-death[0].count == 1` on all nine — so there is no second failure mode hiding behind the first and
nothing to escalate to 27-05.

**The cause is CITED, not re-derived (D-13).** `grep -c 'insertPendingRows'` over the archive → **0**,
with a **positive control** proving the grep can fire (string injected into a scratch copy → 1).
Nothing was posted to **#205** or to any other issue.

### T6.2 — parseable, with both break arms

PASS: all 7 required keys present and non-null; `distinct_tenants` is a **number**.
BREAK (i) key **deleted** → *"characterisation key ABSENT: distinct_tenants"*.
BREAK (ii) `routing_keys` set to **null** → *"characterisation key is NULL: routing_keys"*.
BREAK (iii) `distinct_tenants` as a **list of ids** → *"must be a NUMBER, not a list"*.
Both halves are required: a null-only check passes a JSON that omits the key entirely.

### T6.3 — the decisive fact, and the control that makes its zero meaningful

```
webhook_subscription : relrowsecurity=t  relforcerowsecurity=t
  UNPINNED as jtoye_app (NOSUPERUSER) : 0 rows
  PINNED   as jtoye_app               : total 0 | active 0 | active_for_ORDER_STATE_CHANGED 0
  SUPERUSER, all tenants              : 0 rows
```

**A 0/0 pair proves nothing** — an empty table and a filtering policy are indistinguishable, and
reporting it as "no subscription" would be exactly the vacuous shape this project keeps being bitten
by. So the same pinned/unpinned pair was run on a table that **does** hold rows for that tenant:

```
orders (also ENABLE+FORCE RLS) as jtoye_app:  UNPINNED 0   PINNED 22
```

The pin/RLS mechanism is therefore **proven sighted**, and the superuser read confirms the absence
independently. **No tenant has any webhook subscription at all.**

### T6.4 — nothing was consumed

`webhook.deliveries.dlq` depth **9** at the end (>= N); `dlq-inspect.sh --summary` **rc=1**.
Disposition recommendation (**discardable**, because post-27-05 the nine would deserialize correctly
but fan out to zero subscribers) written into the archive and handed to **27-02 Task 2**, which owns
the decision. This plan did not purge, did not redrive, and did not post to any issue.

## 12. Gate state at this point

```
check-alert-rules          rc=0
check-alert-metrics        rc=0
check-alert-liveness       rc=1   (27-00's; its designed pre-close state)
check-runtime-freshness    rc=0   (4/4 FRESH, 0 unverified)
check-branch-behind-base   rc=0
docs-freshness             rc=0   (1851)
check-doc-versions         rc=0
dlq-inspect --summary      rc=1   (9 parked — correct; 27-02 owns the disposition)
```

## 13. Criterion-defect shapes found in this plan

The plan predicted six shapes. All six recurred, and **three more appeared during execution**.

| shape | instance |
|---|---|
| **UNFALSIFIABLE (expected-0 diff grep)** | T1.7, T2.8, T5.5, T5.9 — all replaced with set-wise or behavioural assertions; T5.5's diff form fires on 27-04's *correct* relocation |
| **SELF-ATTESTED** | T4.2 ("commands were executed and pasted"), T6.2 ("the SUMMARY records these fields") — both replaced with parsers that have RED arms |
| **SELF-SERVICE SKIP** | T7.2's "if the depth was non-zero the drill was skipped" — a criterion that authorises its own non-execution (outstanding, see §14) |
| **FAILS ON A CORRECT TREE** | T2.10's `\\.` claim (measured false); T5.6's prescribed input (a no-throw early return); T5.6/T7.3's `x-death.count == 3` (it is **1**) |
| **MOVING NUMBER PINNED AS A LITERAL** | every `== 9` → `>= N` or a before/after delta |
| **WRONG CLAIM HEADED FOR A PUBLIC ISSUE** | the draft's `insertPendingRows` diagnosis and the T6.5 that would have posted it to #205 — deleted, with a positive-control grep proving its absence |
| **NEW — a criterion made unsatisfiable by its own correction note** | T4.5: `grep -c 'DLQ inaccessible' == 0` cannot hold while the note names the phrase it replaced (measured 2). Same family as the pipefail assertion that fires on its own warning (T3.9b) |
| **NEW — an assertion masked by an exemption list** | the stripper's leaked `service`/`le` selectors were invisible because both rules were already exempt. An exemption can hide a defect in the *checker* as easily as one in the checked |
| **NEW — a test fixture that encodes the author's assumption rather than the runtime** | `new Object[]{message}` vs the real `(Channel, Message)`. Green throughout while the metric was useless in production. **Only the live end-to-end run could falsify it** |

Two further self-inflicted instances, both caught by re-measuring rather than by reasoning: the
replay harness that found 4 of 22 blocks and passed its own consistency check, and a `git checkout`
break-arm restore that **ate the entire uncommitted Task 4** — the recorded
`trap_break_arm_revert_eats_fixes`, live. Every subsequent break arm was run only after committing,
or reverted via a file copy rather than a checkout.
