# 27-02 — execution evidence

Plan: `.planning/phases/27-operational-maturity/27-02-PLAN.md` (wave 4 as declared; the GSD SDK
recomputes it as wave 3 — see "SDK wave numbering" below).
Branch: `feature/27-02-broker-upgrade`, cut from `origin/main` (`268366a`), 0 behind at start.
Executed: 2026-07-29.

Every criterion below records **both directions**. A criterion observed only passing is not
evidence — it may be incapable of failing, which is the failure mode this phase exists to remove.

---

## Preconditions re-probed before execution (the plan says "re-probe every live one")

| Fact | Plan asserts | Measured 2026-07-29 | Verdict |
|---|---|---|---|
| Compose pin | `docker-compose.full-stack.yml:144` = `rabbitmq:3.12-management-alpine` | line 144, exact | ✅ |
| Running version | `3.12.14` | `rabbitmqctl version` → `3.12.14` | ✅ |
| `webhook.deliveries.dlq` depth | ≥ 1, archived by 27-03 | `9` | ✅ |
| Queue census | 13 | 13 (12 durable classic + 1 auto-delete SSE) | ✅ |
| Node identity | `rabbit@53955960a605` | `eval node().`, `hostname`, `Config.Hostname` all agree | ✅ |

The container has **not** been recreated since the plan was written, so Task 2's sidecar hostname is
the same value the plan measured. Container `.Image` == tag `.Id`
(`sha256:0b44fbcc3a4b…`), so the running broker *is* the tag, not a survivor of an earlier build.

### SDK wave numbering — why `--wave 4` was not used

`gsd-sdk query phase-plan-index 27` **ignores the declared `wave:` frontmatter field** and
recomputes waves topologically from `depends_on`. Its numbering is 1={27-00,27-01,27-04,27-05},
2={27-03}, 3={27-02,27-06}; the plan files and ROADMAP number the same DAG 1–4.

Consequence, measured: `--wave 4` matches **nothing** in the SDK's model and exits "No matching
incomplete plans" — a silent no-op that reads like success. `--wave 3` trips the workflow's
wave-safety gate instead, because 27-03 sits in SDK-wave 2 and is incomplete.

That gate cannot express this DAG: **27-03's Task 8 cannot run until 27-02 replaces the broker**,
while 27-02's dependency on 27-03 is only for the DLQ *archive*, which is done. Executed inline
(execute-plan.md Pattern C — the documented route for a plan with a `checkpoint:human-action`).

---

## Task 1 — evidence path made safe, then the falsifiable BEFORE baseline

Commit: see `chore(27-02 T1)`.

### 1a. The `.gitignore` hole (B9) — both directions

| Arm | Command | Result |
|---|---|---|
| BEFORE the fix | `git check-ignore -v .evidence/probe.json` | **exit 1**, no match line |
| AFTER the fix | same | **exit 0**, `.gitignore:173:.evidence/	.evidence/probe.json` |

B9 was right: `.evidence/` was **not** ignored. The fail direction was run *first*, so the exit-0
that follows is a state change and not an already-true assertion. Only after this returned 0 was
any evidence written — the DLQ export and the volume tarball carry tenant data.

### 1b. Baseline — all 13 items

| # | Item | Measured | Plan predicted |
|---|---|---|---|
| 1 | version | `3.12.14` | 3.12.14 ✅ |
| 2 | node / hostname | `rabbit@53955960a605` / `53955960a605` / `53955960a605` — all three agree | ✅ |
| 3 | image ids | container `.Image` == tag `.Id` == `sha256:0b44fbcc3a4b…` | ✅ |
| 4 | plugins | 5 rows: 4 `[E*]` + `rabbitmq_web_dispatch` `[e*]` | 5 ✅ (B5/D-06 — the draft's "four and no fifth" is wrong on a correct tree) |
| 5 | listeners | 5: http 15672, stomp 61613, http/prometheus 15692, clustering 25672, amqp 5672 | 5 ✅ |
| 6 | queue inventory | 13 rows / 12 classic-durable / 1 sse / **1 replica** | 13/12/1/1 ✅ |
| 7 | DLQ depth | `9` | 9 ✅ |
| 8 | metrics | raw 3131 lines; **198** series; `erlang_mnesia_*` **11**; `rabbitmq_raft_*` **6**, all reading `0` | 198/11/6 ✅ |
| 9+10 | mirroring policy | **replaced — see Deviation D1** | — |
| 11 | `list_stomp_connections user` | rejected, `rc=64` + Usage | ✅ (D-09) |
| 12 | repo grep controls | AC-2 **7** (predicted 5 — see D2); AC-12 **17** | AC-12 ✅ exact |
| 13 | render-golden | `0` / `--check` `2` / kubectl-less `2` / `PATH=/nonexistent` `127` | ✅ all four |

**Item 6 — PIT-7 honoured.** The SSE-queue count (1) is asserted *against the running replica count*
(1, `jtoye_oaas_2026-core-java-1`), not against a hardcoded literal that would be wrong under
`--scale core-java=2`.

**Item 8 — D-07 confirmed in both of its parts.** `rabbitmq_queue_messages_ready 9` carries **no**
`queue` label (aggregated mode is the 4.x default too, so 27-03's `/metrics/detailed` job is
unaffected), and its value independently corroborates item 7's depth of 9. The six
`rabbitmq_raft_*` series **already exist on 3.12 and all read `0`** — the draft's claim that they
appear only on 4.3 was backwards, as D-07 states. The real hazard stands confirmed: a
`rabbitmq_raft_… > 0` rule is permanently silent today and goes live after this change.

**Item 11 — scope note.** `list_stomp_connections conn_name auth_login protocol` returned **zero
rows**. That is not a failure: PIT-10/B6 — compose defaults `STOMP_BROKER_MODE` to `in-memory`, so
the relay holds no broker connection outside the D-13 window.

---

## Deviations from the plan as written

### D1 — Baseline items 9+10 are UNSATISFIABLE as written; replaced with a stronger form

`rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy` **does not exist on
RabbitMQ 3.12**. Measured, all three arms:

| Arm | exit |
|---|---|
| clean | **64** (usage error + help text) |
| with `ha-probe` policy set | **64** |
| after restore | **64** |

Identical in every arm, so the criterion cannot discriminate a mirrored broker from a clean one. It
would have been recorded as a pass-or-fail about mirroring while measuring only its own
misspelling. The command that does exist on 3.12 is `check_if_node_is_mirror_sync_critical`.

**Replacement**, which tests the migration-relevant fact directly — classic mirroring is *removed*
in 4.x, so what matters is that no `ha-*` policy exists to be silently dropped — read from the
version-stable management API:

```bash
ha_count() { curl -sf -u "$RU:$RP" http://localhost:15672/api/policies \
  | jq '[.[]|select(.definition|keys[]|startswith("ha-"))]|length'; }
```

| Arm | ha-policy count |
|---|---|
| clean | **0** |
| `set_policy ha-probe '^zzz-probe$' '{"ha-mode":"all"}'` | **1** — the check is capable of firing |
| after `clear_policy` | **0** — restored state asserted, not assumed |

`check_if_node_is_mirror_sync_critical` exit **0** recorded alongside as a secondary. The break ran
inside `trap clear_ha_probe EXIT INT TERM`, per D-04's discipline for any mutation of the shared
broker; `list_policies` confirmed empty afterwards, so no residue was left for a second session.

### D2 — AC-2's control returns 7, not 5, and `files_modified` is incomplete

The block was run **verbatim** under both greps (PIT-14). `type grep` → a bash function dispatching
to **ugrep 7.5.0**; `/usr/bin/grep` → **GNU grep 3.11**.

**Both return 7, identically** — so the `./`-anchored-exclusion trap is absent and the `git
ls-files` sourcing is working as designed. The plan predicted 5. The two extra hits are real and
post-date the plan:

| # | Hit | Disposition |
|---|---|---|
| 6 | `core-java/…/config/MediaListenerConcurrencyIntegrationTest.java:61` | **NOT in `files_modified`** — arrived with 27-04 (PR #331) |
| 7 | `infra/dependency-horizons.yaml:145` | already covered — Task 6's target |

AC-2 requires `hits == 0` after the change, so editing only the Testcontainers file the plan *names*
would have failed the criterion at the end of the plan. **`MediaListenerConcurrencyIntegrationTest.java:61`
is added to Task 4's edits.** This is the "do not trust a plan's `files_modified`" hazard the
handoff recorded, and it is exactly what AC-2's Break 2 rationale predicted: `git ls-files`
discovery caught a file an explicit allowlist would have missed.

The control is non-zero in both implementations, so the grep is proven capable of firing before any
post-change zero is asserted.

### D3 — AC-12's metrics literals are stale

AC-12's third break cites `total_logical_invocations: 1759, java_test_methods: 1176`. Measured:
**1832 / 1240** — 27-01, 27-04 and 27-05 merged after the plan was written. The *invariant* is
unaffected (this plan adds and removes zero test blocks, so `docs/metrics.json` must be unchanged
and `docs-freshness.sh` green); the assertion is made against the measured values rather than the
stale literals.

### D4 — AC-3's plugin transform scrapes the banner; corrected on both sides

AC-3 normalises with `rabbitmq-plugins list -e | awk '{print $2}' | sort -u`. On the real output
that also captures the banner and legend: measured noise tokens `<blank>`, `E`, `plugins`,
`Status:` alongside the five plugin names.

Task 5 diffs this baseline against output from the *same* pipeline run on 4.3, so any wording change
in 4.3's header fires the diff as a false plugin-set change. Corrected to select only bracketed
plugin rows — `awk '/^\[/{print $2}'` — applied **identically to both sides**:

- corrected baseline = exactly the 5 expected names, no noise;
- **discrimination proved**: against a synthetic set with `rabbitmq_stomp` replaced by
  `rabbitmq_web_stomp`, `diff` exits **1** and names both — which is the precise D-06 regression
  AC-3 exists to catch.

`.evidence/before/listeners-protos.txt` needed no correction: `grep -oE 'protocol: [a-z/]+'`
already yields exactly the 5 protocol rows.

---

## Task 2 — the volume snapshot: the only way back from 4.3.4

No repo edits (confirmed: `git status --porcelain` empty; compose line 144 still reads
`rabbitmq:3.12-management-alpine` and `hostname: jtoye-rabbitmq` count is **0** — the pin correctly
stays out until Task 4, per D-L).

### Steps 1–2 — identity and depth

| Item | Value | Cross-check |
|---|---|---|
| `SNAP_NODE_HOST` | `53955960a605` | read from `docker inspect`, **not** `exec` (the container is about to stop); agrees with Task 1's `rabbit@53955960a605` |
| `SNAP_DEPTH` | `9` | live read |
| `ARCHIVED_N` | `9` | 27-03-EVIDENCE.md §11, `jq '.messages\|length'` |

**`SNAP_DEPTH == ARCHIVED_N`, and that is a finding, not bookkeeping.** Zero new dead letters have
arrived since 27-03 archived (newest death `2026-07-26T15:33:51Z`, previously arriving at ~5/day).
**27-05's converter fix is holding.** This is exactly the condition D-I wanted established before a
human is asked to choose replay-vs-discard: the pipeline is fixed and the arrival rate is zero, so
the decision is about a working consumer rather than one still emitting.

### 27-03's archive file is GONE; its characterisation survives

The archive was written to a **session-scratchpad** path, and that session's scratchpad no longer
exists — `find` over `/tmp` and `/` returns nothing. `27-03-EVIDENCE.md` is also not on this branch
(it is unmerged on `feature/27-03-alerting-dlq-runbook`), so it was read via
`git show feature/27-03-alerting-dlq-runbook:…`.

The **characterisation** is preserved there in full (§11) and is what Task 3's checkpoint needs. The
raw payloads are not, so Task 3 re-exports them before the purge — D-02a's export is non-destructive
(`ackmode: ack_requeue_true`), so this costs nothing and restores the artifact the purge decision is
supposed to be made against.

### Steps 3–5 — the snapshot, and a guard that was wrong on a correct tree

Broker stopped for the tar (a live Mnesia directory is not consistent under `tar`), the whole run
wrapped in `trap resurrect EXIT INT TERM` so an abandoned or crashed run cannot leave the shared
stack brokerless. **The trap fired for real** on the VOID below and brought the broker back.

| Artifact | Value |
|---|---|
| `SNAP` | `rabbitmq_data-3.12.14-20260729T110815Z.tar.gz` |
| sidecars | `.node-host` = `53955960a605`, `.depth` = `9` |
| entries under `mnesia/` | 487 |
| live `rabbit@53955960a605/` entries | 60 |
| base node dirs | **10** — 9 orphaned + 1 live, exactly as B4/D-11 predicted |

### D5 — the snapshot size guard is miscalibrated and fires on a correct tree

The plan asserts `bytes=$(stat -c%s …) -gt 100000` with the comment `~2.3M expected`. Measured:

| | |
|---|---|
| volume on disk (uncompressed) | **2.3M** |
| tarball, gzip-compressed | **67836 bytes** |
| uncompressed size of the archive stream (`gzip -l`) | **1207808** |

The check reads the size of a `.tar.**gz**` but its expectation is the **uncompressed** volume size.
Mnesia compresses ~17:1, so a complete, correct snapshot fails the guard. It VOIDed the run on a
perfectly good artifact — the "expected value that is wrong on a correct tree" shape, and the same
class as the criteria this phase was built to catch.

**Replaced with the content assertion the size was a proxy for**, plus an uncompressed-size floor:

| Arm | Result |
|---|---|
| PASS — `gzip -l` uncompressed `1207808 > 1000000` | ✅ |
| PASS — live node dir present, 60 entries | ✅ |
| **BREAK A** — same check with `rabbit@deadbeefdead` | **0** → the `>= 1` is discriminating, not vacuous |
| **BREAK B** — empty tarball through the corrected guard | uncompressed `10240` → **rejected** |

Break B is the one that matters: it shows the corrected guard still catches the failure the original
was aiming at, while no longer rejecting the real artifact.

### Step 5 — restart by `start`, asserted by count

`docker compose start rabbitmq` (**not** `up --force-recreate` — this is the one place in the plan
where `start` is correct, since no image has changed and a recreate would mint a new container id,
orphan the live node dir and boot empty).

| Assertion | Result |
|---|---|
| DLQ depth after restart | **9** == `SNAP_DEPTH` ✅ |
| `Config.Hostname` | `53955960a605` == `SNAP_NODE_HOST` ✅ |
| `node()` | `rabbit@53955960a605` ✅ |

Proven by **count**, never by health — PIT-12: a node whose data dir does not match its hostname
boots empty, healthy, and on the right version.

**Counting note for AC-4.** `rabbitmqctl list_queues … --quiet | wc -l` returns **14**, because
`--quiet` suppresses the *banner* but not the *column header*. The real census is 13 / 1 SSE / 1
replica, unchanged. AC-4 should count from the management API's JSON (`jq length`), not from a
`wc -l` over CLI output.

---

## Task 3 — the checkpoint: adjudicated, gated, purged

### Step 1 — export, re-taken at the instant of destruction

An archive taken in a different plan on a different day is not evidence of what is in the queue when
it dies, so the batch was re-exported independently. `DEPTH_NOW` **9**, exported objects **9**, depth
after the peek **9** — non-destructive, as `ackmode: ack_requeue_true` promises. Re-derived
characterisation matches 27-03 §11 exactly: 5 `order.state.*` routing keys, one `__TypeId__`
(`OrderStateChangeEvent`), source `webhook.deliveries` / `order.events`, reason `rejected`,
`x-death[0].count == 1` on all nine. Retained at `.evidence/webhook-dlq-export.json`.

### Step 2 — the facts put to the human, each re-measured

| # | Fact | Measured 2026-07-29T11:11Z |
|---|---|---|
| a | ACTIVE `ORDER_STATE_CHANGED` subscription? | **NO — `webhook_subscription` 0 rows, all 6 tenants.** 27-05 did *not* seed one |
| b | `webhook_delivery` rows | **0** |
| e | Age span | oldest `2026-07-15T11:46:18Z`, newest `2026-07-26T15:33:51Z` — 3 days stale |
| f | Producing fault still live? | **NO.** `DEPTH_NOW 9 == ARCHIVED_N 9`; ~5/day before, zero since. **27-05 held** |
| h | Three counts | `ARCHIVED_N` 9 = `SNAP_DEPTH` 9 = `DEPTH_NOW` 9 — no divergence |

**Fact (a) is a 0, so it needed a non-vacuity control.** The same superuser connection sees
**6 tenants** and **22 orders**. The read is therefore proven *sighted*, and the 0 is real absence
rather than a filtered read — the exact trap recorded in `trap_rls_blinds_the_verification_query`.

**Human decision: (b) DISCARD.** Verbatim reason: no tenant holds any webhook subscription at all,
so a replay would fan out to zero subscribers and change nothing observable; per fact (g) the
`x-death` history resets on republish regardless; the payloads survive in the export.

### Step 3 — the M15 gate went RED, and was adjudicated rather than overridden

```
media_asset PENDING : 1     (gate demands 0)
media.process depth : 0
```

Reads proven sighted: 16 assets total — `PENDING=1 FAILED=3 ACTIVE=12`.

The row is `9dc42623-ae9e-4088-81d9-1836a8d8c9ca`, object key
`…/quarantine/ac55-fixture-delayed.jpg`, created `2026-07-27 18:12:39+00`. `ac55` is **27-01's own
AC-5.5 label** (`27-01-SUMMARY.md:37`, `baselines/ac55-screenshots/`) — a leftover fixture from a
merged plan, not a real vendor upload.

**The hazard the gate protects against is provably absent.** It has **no `media_event_outbox` row at
all** (confirmed by a direct `asset_id` lookup, and the lookup is proven capable of returning
non-zero — ACTIVE and FAILED assets each have one). No outbox row ⇒ never dispatched ⇒ no in-flight
`media.process` message to strand. Per 27-01's own contract such an asset "is never flipped and
never touched", so it is inert; its quarantine expires 2026-07-30.

**Human decision: adjudicate and proceed, leaving the row untouched.** Deleting a row to turn a gate
green is the green-by-construction move this phase exists to prevent. Recorded RED-but-inert, not
reclassified.

### The rollback path was falsified BEFORE the volume was destroyed — and then fired for real

**Rehearsal.** `resurrect_312` invoked by hand against the live volume: restored to depth **9**,
node `rabbit@53955960a605`, version 3.12.14.

**Break arm**, same tarball / same image / same volume contents, different node identity:

| Arm | Node | DLQ depth |
|---|---|---|
| PASS — tarball's own hostname | `rabbit@53955960a605` | **9** |
| BREAK — `--hostname wrong-host-1785323984` | `rabbit@wrong-host-1785323984` | **absent** |

The two arms genuinely discriminate, which the draft's version could not. Probe container and
volume both removed (0 remaining).

**Then it fired for real.** The first destroy→recreate run aborted on D6 below; the trap restored
3.12.14 from the tarball unattended and the queue census came back **13 with the DLQ at 9**. The
safety net is proven twice — once rehearsed, once under genuine unplanned-abort conditions. That is
considerably stronger than a rehearsal alone.

### D6 — two real 3.12→4.3 behavioural differences that make correct states read as failures

**(a) `node()` is a QUOTED atom on 4.3.** Measured: 3.12 → `rabbit@53955960a605`; 4.3 →
`'rabbit@jtoye-rabbitmq'` **with single quotes**, because the pinned hostname contains a hyphen and
so requires quoting as an Erlang atom. The container-id hostname never did. The plan's literal
`[ "$n" = "rabbit@jtoye-rabbitmq" ]` therefore **fails on a correct pin** — it is what aborted the
first run and triggered the rollback. Comparison normalised with `tr -d "'"`.

**(b) health and `ping` go green before queue recovery completes.** A depth assertion gated on
`rabbitmq-diagnostics ping` reads *empty*, which is indistinguishable from PIT-12's "booted empty"
failure. In a real rollback that reads as the recovery having failed when it succeeded — inviting
destructive correction. Every depth assertion now polls until the queue list is **populated**, not
until ping succeeds. Both belong in the Task 7 runbook.

---

## Task 4 — 4.3.4 as a fresh install

### The guarded window

Destroy and recreate ran as **one** sequence under `trap resurrect_312 EXIT INT TERM`, never two
steps a session could be abandoned between. Compose validity (`config` rc=0) and all three source
edits were verified *before* the window opened — a YAML error found after `volume rm` is the worst
possible moment.

```
volume removed        : 0 remaining
image                 : rabbitmq:4.3.4-management-alpine (pulled)
healthy after         : 7 polls
version               : 4.3.4
node                  : 'rabbit@jtoye-rabbitmq'  -> the pin taking effect on FIRST boot
WINDOW CLOSED CLEANLY — trap cleared, rollback override removed
```

**Runtime parity by identity:** container `.Image` == tag `.Id` ==
`sha256:09b39ca8a3e8…`, so the running broker *is* the 4.3.4 tag, not a survivor of a `start`.

### Topology re-declared by code, not restored from data (D-02)

| Check | 3.12 baseline | 4.3.4 | |
|---|---|---|---|
| total queues | 13 | **13** | ✅ |
| classic AND durable | 12 | **12** | ✅ |
| SSE `AnonymousQueue` | 1 | **1** | ✅ |
| running replicas | 1 | **1** | ✅ — count *derived*, per PIT-7 |
| quorum queues | 0 | **0** | ✅ D-03 |
| distinct types | `["classic"]` | `["classic"]` | ✅ |

The SSE queue is exclusive+auto-delete, so it died with the old connection and was re-declared on
reconnect; it was polled for rather than assumed. core-java healthy after `compose restart
core-java` (the service name — `docker exec jtoye-core-java` does not exist, PIT-7).

### The purge, executed

`webhook.deliveries.dlq` depth **0** (was 9). The volume destruction *was* the purge, per the human's
(b) answer. The nine payloads survive in `.evidence/webhook-dlq-export.json`.

### Step 7 — the post-pin snapshot, where the pin starts paying for itself

`rabbitmq_data-4.3.4-20260729T112413Z.tar.gz`, sidecar `.node-host` = **`jtoye-rabbitmq`** (asserted),
so the *next* rollback needs no hostname override at all.

| | pre-pin snapshot | post-pin snapshot |
|---|---|---|
| base node dirs | **10** (9 orphaned + 1 live) | **1** |

That contrast is the pin's whole value: every past `--force-recreate` had been silently orphaning a
node directory, and nothing ever asserted message survival to notice.

**PIT-13 demonstrated live rather than quoted.** The pinned node name *contains a hyphen*, so the
regex used to parse node dirs matters:

| Pattern | Matches `rabbit@jtoye-rabbitmq` |
|---|---|
| `[^/]*` (correct) | **1** |
| `[^/-]*` (the trap) | **0** — the hyphen defeats it, and the pinned node vanishes from its own count |

4.3 still stores under the directory name `mnesia/` even though Khepri has replaced Mnesia
underneath — worth knowing before anyone greps for a `khepri/` path that does not exist.
