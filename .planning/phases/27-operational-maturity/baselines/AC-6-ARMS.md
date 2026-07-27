# AC-6.1 .. AC-6.13 — break-arm record for `infra/load-testing/baseline.sh`

**Executed:** 2026-07-27, branch `feature/27-00-ops-spine`, against the live compose stack.

**Result: every arm matched except AC-6.12's, which cannot falsify its gate as written (§C).**
Four defects were found by running the arms (§B), and one criterion's expected-0 is a 2 on a
correct tree (§A).

---

## AC-6.1 — the RED that closes baseline B-5

Captured **before** installing anything, which is the whole point of the criterion:

```
VOID: `hey` is not installed, so arm A cannot generate load.
  This is exit 2 (VOID), NOT a skip and NOT a pass. A load baseline that reports success
  while executing nothing is worse than no baseline, because it reads as coverage.
      go install github.com/rakyll/hey@latest
--- rc=2  expected=2  MATCH ---
```

Then `go install github.com/rakyll/hey@latest` → `hey v0.1.5` → the arm runs. Both directions
recorded.

---

## AC-6.2 — the best anti-vacuity arm in the phase, and it earned it three times

```
FAIL: arm A /api/v1/shops: 100 non-2xx [401=100] — at 12156.1925 req/s
FAIL: arm A /api/v1/products: 100 non-2xx [401=100] — at 10598.3601 req/s      rc=1
```

**Twelve thousand requests per second, every one a 401.** Without the status assertion that is
the best throughput number in this repo's history.

It was not a hypothetical. The same assertion caught two *unplanned* failures while bringing
arm A up, and each was a separate latent defect in the prior art:

1. `load-test.sh` requests its token with `client_id=core-api` and **no client secret**.
   `core-api` is a CONFIDENTIAL client, so Keycloak answers *"Invalid client or Invalid client
   credentials"* for every user — **the existing script could never have obtained a token.**
2. Switching to `test-client` (the realm's public direct-access client) yields a token, and
   every request **still** 401s at 3395 req/s: core-java requires `aud: core-api`, and only the
   `core-api` client carries the audience mapper. `test-client` emits `aud: null`.
3. With the audience fixed, the run returned `[200]=120 [429]=380` — this platform's own
   Bucket4j limiter (100 req/min/tenant, burst 20), with both endpoints sharing ONE bucket.

Only after all three did a clean `[200]=100` run exist. A harness without the status assertion
would have reported a "successful baseline" at any of those four stages.

---

## AC-6.3 — a queue reaching zero is not the same as work getting done

```
media.process.dlq before: 0
FAIL: arm B media.process: DLQ 'media.process.dlq' grew from 0 to 20 — the queue reached 0
      by DEAD-LETTERING, not by processing. That is message destruction scored as throughput.
media.process.dlq after:  20                                                   rc=1
media.process.dlq restored to: 0   (webhook.deliveries.dlq untouched at: 9)
```

Run against `media.process` only, whose DLQ was 0 and could therefore be restored exactly.
**`webhook.deliveries.dlq` was never touched** — it holds the nine real dead vendor webhook
events (finding F-2) that 27-03's proof counts.

## AC-6.4 — shared-stack hygiene, asserted in the artifact and re-checked live

```
| `media.process`      | 0 -> 0 | 0 -> 0 | 2.47s | 1 | 80.97 |
| `webhook.deliveries` | 0 -> 0 | 9 -> 9 | 2.53s | 1 | 79.05 |
live now: webhook.deliveries.dlq=9   media.process.dlq=0
```

Every committed artifact should show `9 -> 9`. If one does not, something ate the evidence.

## AC-6.5 — extract the value; a label count is not a measurement

```
real artifact: p95=[8.8 10.4] count=2 all-positive=yes  msg/s lines=2   PASS
BREAK-a  /api/v1/shops p95 hand-edited to 0.0 -> p95=[0.0 10.4] all-positive=no  FAILS correctly
   what a LABEL COUNT would have said: grep -c 'p95' = 3   <- still non-zero, still "passing"
BREAK-b  msg/s/consumer lines deleted        -> msg/s lines=0                    FAILS correctly
```

## AC-6.6 / AC-6.9 / AC-6.10 — all matched

| Arm | Result |
|---|---|
| AC-6.6 budget honesty, asserted **through the YAML parser** (a grep matches the word `source` in a comment) | 6 entries, 0 violations. BREAK: one `source:` deleted → `FAIL: http.read.p95_ms: source is None` rc=1 |
| AC-6.9 arm B genuinely generic | `QUEUES="payment.events"` from the command line, **no script edit** → `messages/sec/consumer for payment.events: 54.95` |
| AC-6.10 no credential in the artifact | 0 matches. **CONTROL:** the same pattern against a file containing `Bearer eyJabcdefghij` returns 1 — proving the pattern can match |

## AC-6.8 — the dangling link, tested by extraction not by retyping

```
extracted p='infra/load-testing/README.md'   (control: non-empty)
test -f PASS
```

The path is pulled **out of `docs/guides/TESTING.md`**; a hardcoded path would not have caught
the original dangling link, which is why the criterion exists.

## AC-6.11 / AC-6.13 — parity contracts, both directions

```
AC-6.11 PASS   HEAD contains every commit on origin/main (1499494); 9 ahead, 0 behind.   rc=0
AC-6.11 BREAK  --head 78eaa99 -> FAIL: 78eaa99 is 11 commit(s) behind origin/main
               (and 83 ahead). Any gate run on this tree is measuring an out-of-date product.  rc=1

AC-6.13 PASS   origin/main is 1765 / 1182
AC-6.13 BREAK  asserting the plan's authoring-time 1759 / 1176 against today's origin/main
               -> FAILS correctly
```

**Recorded delta:** the plan was authored at 1759/1176; `origin/main` is now **1765/1182** —
HIGHER, which is exactly the direction the criterion anticipated ("if one merges first,
origin/main will be higher ... do not silently accept a LOWER number, which means the branch is
behind"). The working tree matches `origin/main` exactly and `git diff --quiet docs/metrics.json`
holds, so the expected zero delta for a bash/YAML/markdown-only plan is confirmed. Nothing was
hand-edited.

---

## §A — AC-6.7's expected-0 is a **2** on a correct tree

The criterion is `git diff <file> | grep -vE '^[-+]#|^[-+]$' | grep -c '^[-+]'` = 0. Executed, it
returns **2**, and the two lines are:

```
--- a/infra/load-testing/load-test.sh
+++ b/infra/load-testing/load-test.sh
```

The **diff header**. `---` and `+++` both start with `[-+]` and are neither comments nor blank,
so this form can never return 0 for ANY modified file. "Satisfying" it would have meant
reverting the header pointer the task requires. Corrected form, excluding the header:

```
git diff <f> | grep -E '^[-+]' | grep -vE '^(\+\+\+|---)' | grep -vE '^[-+]\s*#|^[-+]$' | grep -c .
  -> 0                                    (only comment lines changed)
CONTROL: git diff --stat -> 18 insertions (proving the 0 is not vacuous)
```

## §B — FOUR defects found by running the arms, all fixed

**B-1. The credential guard made AC-6.1 unobservable.** `load-test.sh:28` resolves the password
at config time with `${KC_SEED_USER_PASSWORD:?...}`, which aborts with **exit 1**. Copied to the
top of `baseline.sh`, it fired before the tool check, so on a host with no `hey` and no exported
password the script exited 1 on the credential instead of 2 on the missing tool — the criterion
could not be observed at all. Resolution now happens after tooling, and a missing credential is
**VOID (2)**: "we could not measure" must never share an exit code with "we measured and it failed".

**B-2. `hey` 0.1.5 prints a literal DOUBLE percent.** Its latency table reads `  95%% in 0.0056
secs` (confirmed with `cat -A`). A `/95% in/` pattern matches nothing, the p95 silently reads
`0.0`, and the artifact ships a zero — while `grep -c 'p95'` still passes. This is AC-6.5's exact
failure mode, found in the wild. Pattern now `/95%%? in/`, and a p95 of exactly 0 is treated as an
**extraction failure**, not a fast response.

**B-3. `hey -h` is not a version probe.** It emits `flag needs an argument: -h`, which was being
recorded in the artifact as the tool version. Now read from the binary:
`go version -m $(command -v hey)` → `github.com/rakyll/hey v0.1.5`.

**B-4. The break arms overwrote the deliverable.** Artifacts are named
`baselines/<date>-<sha>.md`, so the AC-6.2 (401) and AC-6.3 (1-request) runs **clobbered the
committed baseline**, and AC-6.5 then reported the real artifact as failing. It was not — it was
someone else's output wearing its filename. Break runs now write to a scratch `ARTIFACT_DIR`.

**Also fixed (clarity, not correctness):** arm B reported `did not drain within 60s (depth still
0)`. Spring AMQP retries a failing message before dead-lettering, and retries hold it UNACKED —
which `list_queues messages` still counts. So a poisoned batch sits at full depth past the
timeout and lands in the DLQ moments later, making the message self-contradictory. It now reports
the depth **at the timeout** alongside the depth now.

## §C — AC-6.12 CANNOT falsify its gate as written, and there is a fail-open behind it

The PASS arm is satisfied (`check-runtime-freshness.sh` → rc=0). The BREAK arm is not falsifiable
as specified, and chasing it surfaced something worth keeping:

| Break attempted | Result |
|---|---|
| `docker stop jtoye-prometheus` (the plan's named victim) | **rc=0.** Prometheus is not a *built* service, and this gate scopes itself to built services. The named break is vacuous. |
| `docker stop jtoye_oaas_2026-core-java-1` (a BUILT service) | **rc=0** — `PASS: 3 running built service(s) match the source tree (1 unverified, listed above).` |
| `--compose-file` naming a built service with no container | **rc=2** — `PARSE ERROR: not one built service was verifiable — 1 skipped, 0 checked ... VOID, not passing.` |

So the gate's VOID branch is real and fires, but **only when ZERO built services are
verifiable**. Stop one of four and a stopped, unproven service is reported as `1 unverified`
*inside a PASS* (`scripts/check-runtime-freshness.sh:431` vs `:445`).

**This is a pre-existing Phase-26 gate, not something this plan wrote, so it is RECORDED rather
than quietly changed** — altering a CI gate's exit semantics is not in Task 6's scope. But it
sits against the project's own standing rule that these gates "fail closed ... on a stopped
stack — 'found nothing' is never 'clean'", and per-service it does not. Flagged for a follow-up
decision: either VOID when any built service is unverifiable, or state in the header that the
guarantee is "at least one built service was checked", which is a much weaker claim than the
name suggests.

The stack was fully restored afterwards (core-java healthy, `check-runtime-freshness.sh` rc=0).
