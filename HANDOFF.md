# Handoff: 27-02 and 27-03 are BOTH complete and in review — next is 27-06

**Generated:** 2026-07-29 ~13:10 BST. Supersedes the "27-03 is 8/9 (Task 8 blocked) — next is 27-02"
handoff.

| | |
|---|---|
| `origin/main` | **`268366a`** |
| Checked out at handoff | **`feature/27-02-broker-upgrade`** — clean, pushed, **PR #335 open** |
| Also open | **`feature/27-03-alerting-dlq-runbook`** — clean, pushed, **PR #336 open** |
| Phase 27 | 27-00 ✅ 27-01 ✅ 27-04 ✅ 27-05 ✅ (merged) · **27-02 ✅ 27-03 ✅ (in review)** · **27-06 = next** |
| Live broker | **RabbitMQ 4.3.4**, node `rabbit@jtoye-rabbitmq`, 13 queues, 0 quorum, all DLQs empty |
| Stack | Compose UP, all services healthy, 4/4 built images FRESH |

---

## 0. ⚠ READ THIS FIRST — MERGE ORDER IS NOT OPTIONAL

**#335 (27-02) must merge BEFORE #336 (27-03).**

Both branches are 0 behind `main`, so both *look* mergeable in either order. They are not:

- 27-02 changes `docker-compose.full-stack.yml` to `rabbitmq:4.3.4-management-alpine` and adds the
  permanent `hostname: jtoye-rabbitmq` pin.
- 27-03 does **not** carry that change, so its compose still reflects main's **3.12** pin — while its
  alert rules were proven against the **live 4.3.4** broker.

After #335 merges, **#336 needs `git merge origin/main` before it merges**, or it ships a compose
that contradicts the broker its own gates were validated against.

**Never run `docker compose up` / `scripts/start-dev.sh` while `feature/27-03-alerting-dlq-runbook`
is checked out.** Its compose pins 3.12; doing so recreates the broker on 3.12 and destroys the
4.3.4 volume. Every Task-8 gate deliberately read the **live runtime**, not the compose file.

**The previous handoff's `--wave 4` advice is WRONG — see §3.1.** It matches nothing and exits
"No matching incomplete plans", which reads like success.

---

## 1. WHERE TO RESUME — 27-06, the last plan in Phase 27

`.planning/phases/27-operational-maturity/27-06-PLAN.md` (4 tasks, `autonomous: true`,
`depends_on: [27-00, 27-03]` — both now satisfied). It wires the **`ops-contracts` CI job** over
three static gates.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch origin
# ONLY after #335 and #336 have merged:
git switch -c feature/27-06-ops-contracts origin/main
bash scripts/check-branch-behind-base.sh          # expect rc=0
```

**Verify 27-06's preconditions against the tree before starting — do not trust `files_modified`.**
That hazard fired twice this session: 27-03's plan named a `prometheus.yml` that 27-00 had already
replaced with a template (editing it would have been a silent no-op the running Prometheus ignores),
and 27-02's plan omitted `MediaListenerConcurrencyIntegrationTest.java`, which AC-2's git-sourced
discovery caught — it would have failed the plan's own final gate.

**27-06 must honour `manual_review`.** `infra/dependency-horizons.yaml` carries a `rabbitmq-k8s` row
with `owner: UNASSIGNED` and `manual_review.expires: 2026-10-26`; it is exit 0 only while unexpired.
Do **not** ship `ops-contracts` red-by-construction — a permanently red required check earns a
`|| true` and takes every other row down with it.

---

## 2. What landed this session

### 27-02 — RabbitMQ 3.12.14 → 4.3.4 (PR #335, 7 commits `963611e`..`48d2e29`)

Fresh install: there is no in-place 3.12 → 4.x path, and 4.3 has no Mnesia reader at all. Topology is
code (`RabbitAdmin` re-declares from `RabbitMQConfig`), so it came back identical; the messages are
not, which is what the checkpoint existed for.

**Checkpoint decisions (the user's):** (1) **discard** the nine dead letters — no tenant holds any
webhook subscription at all (0 rows across 6 tenants, proven *sighted* against a connection that sees
6 tenants / 22 orders), so a replay would fan out to zero subscribers; (2) the M15 gate went RED on
27-01's leftover `ac55` fixture and was **adjudicated, not overridden** — it has no
`media_event_outbox` row, so it was never dispatched and nothing was in flight.

**The rollback is real and was proven twice** — rehearsed against the live volume, then it **fired
for real** when the first recreate aborted, restoring 3.12.14 and all 9 messages unattended.

### 27-03 — failure visibility (PR #336, closed at 9/9 by Task 8)

Task 8 re-ran the live gate on 4.3.4. **The alert layer survived a major version change with no
edit**: `check-alert-metrics` rc=0 at 19 live rules / 24 selectors / 3 dormant — identical to the
3.12 figures; `/metrics/detailed` families and the `rabbitmq_detailed_` prefix unchanged;
`dlq-inspect --summary` flipped **1 → 0**.

Full records: `27-02-EVIDENCE.md` (660 lines), `27-03-EVIDENCE.md` §14, and both `SUMMARY.md` files.

---

## 3. Traps confirmed or newly found this session

1. **The GSD SDK ignores the declared `wave:` field and recomputes waves from `depends_on`.** Its
   numbering was 1={27-00,27-01,27-04,27-05}, 2={27-03}, 3={27-02,27-06}; the plans and ROADMAP number
   the same DAG 1–4. **`--wave 4` matched nothing and exits "No matching incomplete plans" — a silent
   no-op that reads like success.** `--wave 3` then trips the wave-safety gate, because 27-03 sat in
   SDK-wave 2 and was incomplete. That deadlock is unrepresentable in the tool (27-03's Task 8 needed
   27-02's broker), so 27-02 ran **inline, Pattern C** — which `execute-plan.md` prescribes anyway for
   a plan carrying a `checkpoint:human-action`.
2. **RabbitMQ 4.x: `node()` is a QUOTED atom** when the hostname contains a hyphen
   (`'rabbit@jtoye-rabbitmq'`); 3.12's container-id hostname needed no quoting. A literal equality
   check **fails on a correct pin** — it aborted the first recreate and triggered the rollback.
   Normalise with `tr -d "'"`.
3. **Health and `rabbitmq-diagnostics ping` go green BEFORE queue recovery finishes.** A depth
   assertion gated on ping reads *empty* — indistinguishable from the node-name "booted empty"
   failure, and it invites destructive correction of a rollback that actually worked. Poll until the
   queue list is populated.
4. **RabbitMQ 4.x refuses transient NON-EXCLUSIVE queues** (`INTERNAL_ERROR - Feature
   'transient_nonexcl_queues' is deprecated`). It broke a test that passed on 3.12. All 10 production
   queues use `QueueBuilder.durable`; the SSE `AnonymousQueue` is legal only because it is
   **exclusive**.
5. **A compressed-artifact guard carrying an uncompressed expectation fails on a correct tree.** The
   plan's `bytes > 100000` on a `.tar.gz` VOIDed a perfectly good snapshot — mnesia compresses ~17:1
   (2.3 MB volume → 68 KB tarball).
6. **`rabbitmqctl list_queues --quiet` suppresses the banner but NOT the column header**, so a naive
   `wc -l` is off by one. Count from the management API's JSON.
7. **The `secret`-as-a-common-word false positive recurred** (26-09 saw it as `DB_PASSWORD`). The dev
   Postgres password is a 6-letter English word, so a literal sweep hits ordinary prose — it
   pre-exists on `main` in 11 files including `.gitignore`. Use the credential-**shape** form, and
   **make it case-insensitive**: the first version missed `POSTGRES_PASSWORD=<value>` because
   `grep -E` is case-sensitive, which is the likeliest leak shape in this repo.
8. **A background-task notification reported "exit code 0" for a FAILING Gradle run** — that is the
   wrapper's trailing `echo`, not Gradle (`trap_exit_code_read_after_echo`). Read `GRADLE_RC` from the
   log and the JUnit XML.

---

## 4. Environment state

- **Branches:** `feature/27-02-broker-upgrade` (checked out, clean) and
  `feature/27-03-alerting-dlq-runbook` (clean). Both pushed.
- **No conda env needed** for this work — Java 21 + the Gradle wrapper only.
- **Live broker:** 4.3.4, `rabbit@jtoye-rabbitmq`, 13 queues (12 durable classic + 1 SSE), 0 quorum,
  all four DLQs `msgs=0`.
- **Gates on 27-02's branch:** `check-branch-behind-base` 0 · `check-runtime-freshness` **0 (4/4
  FRESH)** · `check-dependency-horizons` 0 · `docs-freshness` 0 (1832) · `render-golden` 0.
- **Gates on 27-03's branch:** `check-alert-rules` 0 · `check-alert-metrics` 0 (19/24/3) ·
  `dlq-inspect --summary` 0 · `docs-freshness` 0 (1851).
- **Tests:** `OrderEventFanoutTopologyIntegrationTest` and `MediaListenerConcurrencyIntegrationTest`
  both 1 test / 0 failures against 4.3.4, read from `core-java/build-local` (**not**
  `core-java/build`, a stale 2025-12-27 artifact reporting a FALSE RED).
- **CI at handoff:** both PRs **12 SUCCESS / 1 NEUTRAL (Trivy skipping) / 1 IN_PROGRESS**
  (`Integration Tests (Testcontainers RLS)`). **No failures.** Re-confirm before merging.

---

## 5. Carried forward — open items

- [ ] **Merge #335, then merge `main` into #336, then merge #336.** See §0.
- [ ] **Then 27-06** — the last plan in Phase 27.
- [ ] **`.evidence/` is NOT gitignored on `feature/27-03-alerting-dlq-runbook`** (the ignore entry is
      27-02 Task 1's, unmerged). It holds **2 RabbitMQ volume tarballs and the dead-letter export with
      tenant payloads**. Verified absent from PR #336 (0 paths, 0 tracked, 0 tarballs). **A blanket
      `git add` on that branch would commit broker data.** Resolved the moment #335 merges.
- [ ] **`.planning/STATE.md` diverges across branches.** On `main` it still describes **Phase 26**;
      the Phase 27 narrative exists only on the 27-03 branch; 27-02 added its own note about the
      divergence. Reconcile after both merge. `state.record-session` **corrupts** this file —
      hand-edit; `roadmap.update-plan-progress` is safe.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event end-to-end has still never
      been captured. 27-02 proved the relay at the **broker** over a raw socket
      (`server:RabbitMQ/4.3.4`, `auth_login=jtoye`, and #266/#269's single-segment rule still
      enforced — dotted gets a RECEIPT, slashed gets `not a valid topic destination`), but not a
      browser receiving a MESSAGE frame.
- [ ] **4.3's community support ends 2026-11-30.** The horizon row goes AMBER ~2026-09-01 and RED
      2026-12-01 **with no commit in between**. Intended, documented in the manifest header before it
      happens — do not treat it as a break.
- [ ] **The staging/production broker is still unknown and unowned** — no manifest in this repo, no
      declared version, `rabbitmq-k8s` row `manual_review` expires **2026-10-26**. Operator action in
      `docs/runbooks/rabbitmq-broker-upgrade.md` §7; the ADR-0002 open question is dated but
      **unsigned** (Status deliberately unchanged — that needs an owner, not an agent).
- [ ] Dependabot PRs (#322–#330, #243) — triage, do not bulk-merge.
- [ ] Toolchain drift carried from the previous session (conda, gemini-cli, ms-fabric-cli) — its own
      session, dry run first.

---

## 6. Residue

- Compose stack UP and healthy; broker on 4.3.4 with an empty DLQ set.
- `.evidence/` holds 13 files: the **3.12.14 pre-upgrade tarball — the only rollback path from
  4.3.4** — the 4.3.4 post-pin tarball, both `.node-host`/`.depth` sidecars, the 9-message dead-letter
  export, and the before/after metrics captures. **Do not delete the 3.12.14 tarball** until 4.3.4 has
  been stable for a while.
- No probe containers, volumes, policies or drill queues survive; `/tmp/docker-compose.rollback.yml`
  was removed; the vhost `default_queue_type` is back to `classic` with 0 quorum queues.
- One background shell may still be polling CI for #335/#336. It has a hard 55-minute deadline and
  exits on its own — no action needed.
