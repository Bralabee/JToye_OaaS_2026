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
