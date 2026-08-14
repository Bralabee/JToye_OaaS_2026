# Quick task 260814-u4t — Phase 29 Lane A: pre-fix three first-deploy defects

**Status:** COMPLETE. Three tasks, three commits, working tree clean, nothing pushed, nothing merged.

**One-liner:** The core-java Service now exposes 9091 so its Prometheus scrape can connect (guarded
permanently by a new INV-9), `rabbitmq-credentials` carries a `default_user.conf` generated once from
the same variables as the flat keys, and the "second client-secret key" turned out not to exist — the
search that answered it caught its own instrument failing first.

| Task | Commit | Subject |
|---|---|---|
| 1 | `6ce15b42` | fix(29): expose 9091 on the core-java Service so its scrape can connect (DEF-29-1) |
| 2 | `2918cc31` | fix(29): rabbitmq-credentials carries default_user.conf agreeing with username/password (DEF-29-4) |
| 3 | `eb896dce` | docs(29): mark DEF-29-1 and DEF-29-4 resolved; record DEF-29-8/-9/-10 |

Task 3 is a separate commit by necessity: a commit cannot contain its own hash.

---

## Baseline vs final gate table

Captured on the clean committed tree before any edit, and again after all three commits. Each rc was
captured on the same statement as its command.

| Gate | Baseline | Final | Verdict |
|---|---|---|---|
| `k8s/scripts/render-golden.sh` | 0 | 0 | unchanged |
| `k8s/scripts/check-render-invariants.sh` | 0 | 0 | unchanged (now also runs INV-9) |
| `k8s/scripts/check-no-plaintext-secrets.sh` | 0 | 0 | unchanged |
| `k8s/scripts/check-env-contract.sh` | 0 | 0 | unchanged |
| `k8s/scripts/check-connection-math.sh` | 0 | 0 | unchanged |
| `scripts/check-gate-enforcement.sh` | 0 | 0 | unchanged |
| `scripts/check-doc-citations.sh` | **1** | **1** | **PRE-EXISTING RED — not caused by, and not fixed by, this lane** |
| `scripts/check-claims.sh` | 0 | 0 | unchanged |
| `scripts/check-doc-metrics.sh` | 0 | 0 | unchanged |

**The pre-existing red, attributed.** 13 C-3 citation violations, all line-number drift in
`.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md` and `k8s/LOCAL.md` — none in any
file this lane touches. Compared as a **set**, not just a count: the failing sites are byte-identical
between the two runs. The only textual difference in the whole gate log is the timestamp and the
`cited:` snippet for `k8s/LOCAL.md:1495 -> core-java-deployment.yaml:307-311`, which was **already
failing at baseline** and whose quoted line drifted because Task 1 added comment lines above it. The
violation count did not move (13 -> 13).

This red is load-bearing for a decision: it is why Task 3 **recorded** rather than edited the doc
sites (see Branch A below).

---

## Task 1 — DEF-29-1: 9091 on the core-java Service, with INV-9 behind it

### Premise re-verification (all eight rows held)

| Claim | Site | Measured |
|---|---|---|
| containerPort 9091, name `management` | `core-java-deployment.yaml:60-62` | present |
| comment says deliberately off the Service | `:56-59` | present |
| Service ports | `:692-696` | **9090 only** |
| scrape target | `prometheus-config.yaml:179` | `core-java:9091` |
| scrape comment claims pod-network reach | `:173-175` | present, and false |
| STILL-OWED bullet | `50-observability.yaml:616-623` | present |
| `core-java-scrape-allow` permits prom -> 9091 | `:549-558` | present, needs no change |
| Ingress names its backend port | `ingress.yaml:159` | `number: 9090` |

### What shipped

- Second ClusterIP port `9091`/`targetPort 9091`/`TCP`/`management` on the `core-java` Service.
- The containerPort comment **amended, not overwritten** — the issue-#98 reason and date kept, the
  change and why the intent survives appended.
- The false `prometheus-config.yaml` claim corrected, with the DEF-29-8 cost stated inline.
- The `50-observability.yaml` bullet marked **PAID** with the date, text retained (29-08's precedent).
- **INV-9** added to `check-render-invariants.sh` — shipped, not off-ramped (awk body ~40 lines,
  under the ~70-line threshold; vacuity guard is non-trivial and was shown to fire).

### Golden line accounting — every line attributed

```
resolve_exit=0        (--diff-since 29-lane-a-pre; a 2 would have made this VOID)
test -s               PASS (non-empty; an empty diff would mean the snapshot was taken after the edit)
added   ('^>') = 56
removed ('^<') = 6
number: lines  = 0    -> the Ingress is provably untouched
```

| Lines | Attribution |
|---|---|
| +48 | the corrected prometheus comment, 24 lines x 2 goldens |
| +8 | the four port fields x 2 goldens, in kustomize's alphabetical key order (`name`, `port`, `protocol`, `targetPort`), asserted as a SET |
| −6 | the 3 false comment lines x 2 goldens |

**Deviation from the plan, stated rather than glossed:** the plan required `grep -c '^<'` to be **0**
and it was **6**. The plan's expectation did not account for the fact that `prometheus-config.yaml`'s
comments live **inside a ConfigMap string value** and therefore survive the render, unlike manifest
comments which kustomize strips. Correcting a false comment necessarily removes it. The plan's own
escape hatch was followed: every removed line is attributed by file and by name above, and all six are
the deliberate correction required by the plan's own step 6.

**A grep artefact caught in passing:** the first port-field assertion printed nothing and looked like a
failure. The cause was the pattern, not the data — `^\>` in ERE is a **word boundary**, not a literal
`>`. Re-run as `^> +(- )?(name|port|protocol|targetPort):` it returns the expected 8, with a control
(`selector:` -> 0) proving the corrected pattern is not matching indiscriminately.

### Break arms — all three fired, both directions recorded

| Arm | Break applied | Expected | Actual |
|---|---|---|---|
| 1 | delete the 4 Service 9091 lines | invariants rc=1 naming `core-java:9091`, golden rc=1 | **INVARIANTS_RC=1** naming `core-java:9091` in all four renders; **GOLDEN_RC=1** |
| 2 | retarget scrape to `core-java:9099` | INV-9 rc=1 | **rc=1**, `names Service 'core-java', which does NOT expose port 9099` / `Ports 'core-java' does expose: 9090 9091` |
| 3a | rename the `targets:` key | `parse_fail`, non-zero | **rc=2** — `INV-9 found 0 static scrape targets` |
| 3b | prefix every target host with `nosuch-` | `parse_fail`, non-zero | **rc=2** — `resolved 9 target(s) but CHECKED none of them` |

Arm 3b was added beyond the plan: it is the stronger vacuity test, because "targets resolved, none
checked" is the state that is genuinely indistinguishable from a pass while asserting nothing.

Arm 2 matters specifically because without it, INV-9 passing would be consistent with an
implementation that never reads the target at all.

**Restores, verified BY CONTENT (never `diff --stat`):**

| File | Pre-arm | Post-restore | |
|---|---|---|---|
| `core-java-deployment.yaml` | `d1674f6c8eef` | `d1674f6c8eef` | MATCH |
| `prometheus-config.yaml` (arms 2, 3a, 3b) | `a56da847dbd7` | `a56da847dbd7` | MATCH |

**Closing clean arm (asserted LAST):** `git status --porcelain` empty; invariants rc=0
(`PASS: INV-1..INV-9 hold across 4 kustomize target(s)`); golden rc=0; both arm files hash-identical to
HEAD.

### INV-9 as shipped

Runs on all four renders (base, local, staging, production), uniformly **7 targets checked, 2 skipped**.
The 2 skips are `jtoye-rabbitmq:15692` — the operator builds that Service from the CR at apply time, so
it is legitimately absent from a kustomize render. Skips are **printed**, not silent. Guards: zero
Service ports -> `parse_fail`; zero targets -> `parse_fail`; targets resolved but none checked ->
`parse_fail`. Its extraction is its own, not INV-6's, so its fail-closed guard belongs to it.

---

## Task 2 — DEF-29-4: `default_user.conf`, generated once

### Premise re-verification (tag-bound; the task would have stopped if the pin had moved)

- `scripts/staging-bootstrap.sh:209` -> `RABBITMQ_OPERATOR_VERSION="v2.22.3"` — unchanged.
- `k8s/base/rabbitmq-cluster.yaml:217-222` -> `secretBackend.externalSecret` naming
  `rabbitmq-credentials` — unchanged.
- core-java still reads `username`/`password` from it — **at `:329-338`, not the `:319-328` the plan
  cited**; the block moved because Task 1 added comment lines above it. Same content, drifted line.

### What shipped

`rabbitmq-credentials` now has five keys. The stanza is built **once** from `$RABBITMQ_USER` /
`$RABBITMQ_PASSWORD` — the same variables the flat keys use — so the agreement is structural rather
than clerical. STEP 7's summary lists the new key by NAME only.

### The falsification arm caught a real defect in the fix itself

This is the headline result of Task 2 and it would not have been found any other way.

The obvious spelling — `$(printf 'default_user = %s\ndefault_pass = %s\n' ...)` — produces a conf file
with **no final newline**, because `$(...)` strips trailing newlines. Measured through
`kubectl create secret --dry-run=client -o json`, decoding the value and counting bytes:

```
naive construction : 55 bytes, last 4 bytes = "-AAA"
operator's format  : 56 bytes, last 4 bytes = "AAA\n"
```

Both lines are still present and separated, so the loss is **invisible to an eyeball and to any
line-by-line comparison** — only a byte count could see it. Fixed with a sentinel character stripped by
`${var%.}`, documented in place as load-bearing rather than a typo. Task 2's commit was **amended**
(un-pushed, own worktree branch) and **both arms re-run against the amended state**, so the recorded
evidence matches what ships.

### Arms

| Arm | Direction | Result |
|---|---|---|
| Mechanism | same password | `AGREES` — dotted key legal, newline-bearing value survives argv -> Secret, stanza user/pass == flat keys. **rc=0** |
| Mechanism | **different password** | `DIVERGES` — **rc=1**. This is the ACCESS_REFUSED state; an equality check never observed failing may be incapable of failing |
| Newline | fixed construction | **56 bytes**, last byte `\n` |
| Newline | **naive construction** | **55 bytes** — the check can see the loss |
| Binding | real tree | all four checks YES — **rc=0** |
| Binding | **stanza repointed at `$STOMP_CLIENT_LOGIN`** | check (1) NO — **rc=1** |

Throwaway literals only (`probe-user` / `probe-pass-AAA`), never a real credential.
`--dry-run=client` makes no API call, so all of this ran offline with no cluster and no context.

Binding-arm restore: pre `93b7f163e9fa` -> post `93b7f163e9fa`, MATCH.
Closing clean arm: `git status --porcelain` empty, `bash -n` rc=0, worktree hash == HEAD hash.

### What is NOT proven, stated rather than implied away

That the operator actually projects the key, and that the broker accepts the credential, are
**cluster-side** facts. No static gate can cover them — secret VALUES never appear in a kustomize
render, and `check-no-plaintext-secrets.sh` exists to guarantee they never will. This lane proved the
**shape** and the **binding**. The acceptance proof belongs to 29-10/29-11 on a live broker.

---

## Task 3 — the "second client-secret key": measured, not guessed

### The instrument failed first, and the positive control is the only reason that was noticed

The searches were first run inside a `bash script.sh` subshell. `rg` is a Claude Code **shell
function**, not a binary, and there is no system ripgrep to fall through to — so every search returned:

```
rg: command not found
>>> CONTROL_RC=127   CONTROL_HITS=0
```

**Zero hits, which is indistinguishable from a legitimate "not found".** Had the mandatory positive
control not run first, "no consumers exist" would have been written down as a finding when it was an
artefact of the tooling — and it would have looked like a clean, decisive answer. Re-run directly in
the shell where the function exists, the control returns its 16 hits including both manifest consumers.

### The measurement

Positive control — `frontend-client-secret`, manifest consumers:

```
k8s/base/frontend-deployment.yaml:186          key: frontend-client-secret
k8s/base/keycloak/keycloak-deployment.yaml:186 key: frontend-client-secret
```

Full `keycloak-credentials` consumer map (every `secretKeyRef` in the tree):

| Key | Read by |
|---|---|
| `admin-username` | `core-java-deployment.yaml:250`, `keycloak-deployment.yaml:230` |
| `admin-password` | `core-java-deployment.yaml:255`, `keycloak-deployment.yaml:235` |
| `db-username` | `keycloak-deployment.yaml:271` |
| `db-password` | `keycloak-deployment.yaml:276` |
| `frontend-client-secret` | `frontend-deployment.yaml:186` **and** `keycloak-deployment.yaml:186` |

**Five distinct keys read; five created by the script. The set matches exactly — no key is missing.**

`core-api-client-secret`: **4 doc mentions, 0 manifest consumers** (`k8s/DEPLOYMENT.md:147`,
`k8s/QUICK_START.md:228`, `k8s/base/secrets-template.yaml.example:127`,
`docs/runbooks/sealed-secrets.md:38`). The zero is real, not a pattern artefact: the identical shape
`key: frontend-client-secret` returns 2 hits on the same tree.

### Branch taken: **A** — no key added

The pre-committed rule (fixed before the answer was known): a key is created only if a manifest reads
it via `secretKeyRef`, or an in-cluster process provably reads it by name. Neither holds.
Manufacturing an unconsumed Secret key does not fix a documentation defect — it creates a standing
invitation to rotate a value nothing reads.

The measured consumer map is now recorded in `scripts/staging-secrets.sh` beside the
`keycloak-credentials` block, so this is not re-litigated from `STATE.md`'s paraphrase.

**The paraphrase is confirmed unsupported.** `29-08-SUMMARY.md:322-326` says `edge-api`,
`integration-catalog-ro` and `integration-orders-rw` "need a secret key each" — **three clients, not
one key name**. `STATE.md`'s "a second client-secret key" compresses that into a singular the source
does not support. Not inventing a key name was the correct call.

### New findings recorded

- **DEF-29-8** — the ClusterIP-multiplexing cost of Task 1's fix (2 staging / 3 production replicas
  behind one ClusterIP, `instance` relabelled to a constant, so `rate()` is unsound). Accepted
  deliberately per the owner decision; closing it needs a headless Service + `dns_sd_configs`, which
  touches every alert's labels and the alert-corpus parity gate.
- **DEF-29-9** — `core-api-client-secret` named in four docs and read by nothing; plus the three
  confidential clients that need a key **and** a realm entry **and** a decision.
- **DEF-29-10** — *(found by this lane, beyond the plan)* `docs/runbooks/sealed-secrets.md:41` lists the
  `rabbitmq-credentials` keys without `default_user.conf`, on the **production** sealed-secrets path.
  An operator following that table re-creates DEF-29-4 exactly. Also checked and found **correct**:
  `scripts/k8s-local-secrets.sh` needs no such key, because `k8s/local` deletes the RabbitMQ CR and
  shims to the compose broker — there is no operator in that path to project it.

**Why the doc sites were recorded, not edited:** `check-doc-citations.sh` was already RED at baseline.
The rule fixed in advance was: green at baseline -> may correct in place; already red -> record and say
which and why. Editing docs while that gate is red would entangle this lane with an unrelated red.

### The five untouched entries

Asserted by **section content hash** against the lane's base commit `9583eb55` — not by
`diff --stat`, which is empty both when a section is unchanged and when the comparison never ran.

```
DEF-29-2  IDENTICAL  8258c952e049  (17 lines)
DEF-29-3  IDENTICAL  3bcac59bcc59  (36 lines)
DEF-29-5  IDENTICAL  a14b8715b936  (32 lines)
DEF-29-6  IDENTICAL  4485879be8f0  (37 lines)
DEF-29-7  IDENTICAL  3f6cdcf33082  (21 lines)
```

**DEF-29-7 needed explaining and got it.** The first run reported it CHANGED. Cause: it was the final
entry, so appending DEF-29-8 after it adds a blank, a `---` separator and a blank to what "up to the
next heading" captures. Diffed explicitly (`21a22,24`), and its prose hashes identically on both sides
(`3f6cdcf33082`). The normalisation strips only trailing blanks and separators — and it is not a fudge,
because the break arm below shows the check still catches a real content change.

**Break arm:** appending the word `SENTINEL` to DEF-29-5's heading -> `DEF-29-5 CHANGED !!`,
`UNTOUCHED_RC=1`. Restore verified by content: `0b4163898ebc` -> `0b4163898ebc`, MATCH.

### RESOLVED hashes resolve

```
6ce15b42 -> type=commit  RESOLVES  :: fix(29): expose 9091 on the core-java Service ...
2918cc31 -> type=commit  RESOLVES  :: fix(29): rabbitmq-credentials carries default_user.conf ...
NEGATIVE CONTROL: deadbee1 -> rc=128, "Not a valid object name" -> the check CAN report absence
both are ancestors of HEAD
```

Checked with here-strings throughout, never `| grep -q` under `pipefail` — that inversion
(SIGPIPE -> 141) is what made 29-08's own commit arm report three real commits as MISSING.

---

## Measured facts that contradict or extend the plan's `<pre_measured>`

1. **A second false clause in the same comment.** The plan flagged "9091 is deliberately NOT on the
   Service, so this scrape reaches the POD network directly" as false. Its *next* clause was false too:
   it cited `networkpolicies/20-core-java.yaml` as carrying the Prometheus-scrape ingress rule. That
   file contains **no 9091 rule at all** — `rg -uu '9091' k8s/base/networkpolicies/` returns hits in
   `50-observability.yaml` only. Both clauses are corrected.
2. **Golden removals are 6, not 0.** Attributed in full above; caused by prometheus comments living
   inside a ConfigMap string value and therefore rendering.
3. **`core-java-deployment.yaml:319-328` is now `:329-338`** — drifted by Task 1's comment.
4. **A defect in Task 2's own first draft** (the stripped trailing newline), found by its arm.
5. **DEF-29-10**, a production-path re-entry of DEF-29-4 through the sealed-secrets runbook.

## Self-check: PASSED

All 8 claimed files exist; all 3 commits resolve to the stated subjects; both goldens contain the
rendered Service port (`name: management` x2 each — containerPort name + Service port name);
`deferred-items.md` has 10 entries and 2 RESOLVED lines.

One expectation of mine was wrong and is corrected here rather than hidden: I predicted
`grep -c 'port: 9091'` = 2 in `core-java-deployment.yaml` and measured **4**. The four are the three
pre-existing probe ports (`:586`, `:594`, `:601`) plus the one new Service port (`:726`);
`targetPort: 9091` does not match a case-sensitive `port: 9091`. The file is right; the expectation was
not.

## Final state

- `git status --porcelain` — empty.
- 3 commits on the worktree branch, base `9583eb55`; `HEAD..origin/main` = 0 behind.
- Nothing pushed, no PR, nothing merged to main. `.claude/worktrees/` untouched.
- Offline throughout: no cluster contacted, no DNS, no secret values. `--dry-run=client` makes no API
  call.
- Spent golden snapshot `k8s/goldens/.pre/29-lane-a-pre` removed with a targeted `rm` (never
  `git clean`, which in a worktree deletes committed-on-branch files).

## Unfalsifiable criteria

None to report. Every acceptance criterion in this lane was run in both directions and both directions
are recorded above. The two that could most easily have been vacuous were made non-vacuous
deliberately: INV-9's checked-count guard (arm 3b) and the untouched-entries check (the DEF-29-5
sentinel arm). The one criterion that could not be tested here — that the broker accepts the credential
— is stated as unproven and assigned to 29-10/29-11 rather than reported as satisfied.
