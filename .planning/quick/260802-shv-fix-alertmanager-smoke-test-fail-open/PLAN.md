---
quick_id: 260802-shv
slug: fix-alertmanager-smoke-test-fail-open
date: 2026-08-02
status: in-progress
---

# Fix: alertmanager smoke test fails open on an unmatchable container name

## The defect

`infra/monitoring/scripts/smoke-test-alertmanager.sh:96-100` gates Test 2 on:

```sh
if ! docker ps --format '{{.Names}}' | grep -q '^jtoye-core-java$'; then
  log "SKIP — jtoye-core-java not running; ... Treating as PASS (synthetic already proved the route)."
  log "PASS (synthetic only)"
  exit 0
fi
```

Two faults, compounding:

1. **The pattern can never match.** `docker-compose.full-stack.yml:184` removed
   `container_name` from `core-java` to support `--scale core-java=N`. The real
   container is `jtoye_oaas_2026-core-java-1` (project prefix + service + index).
   `infra/load-testing/media-pipeline-arm.sh:101` already carries a comment warning
   never to `docker exec jtoye-core-java` for exactly this reason.
2. **The no-match branch exits 0.** So the unmatchable pattern is not merely wrong —
   it is silently converted into a pass.

Net effect: Test 2 — the only half that exercises real Prometheus rule evaluation
(`ServiceDown`, `for: 2m`) through to Alertmanager routing and Mailhog delivery — has
been dead since `container_name` was removed, reporting `PASS (synthetic only)` and
exit 0 on every run. Test 1 (synthetic POST straight to the Alertmanager API) still
runs, but it bypasses Prometheus entirely.

Measured 2026-08-02 with the full stack up and `core-java` `running`:
`docker ps --filter 'name=^jtoye-core-java$'` returns **empty**, while
`docker compose -f docker-compose.full-stack.yml ps -q core-java` resolves
`dbbb16dddf30` = `/jtoye_oaas_2026-core-java-1`, state `running`.

## The fix

1. Resolve the container through **compose**, not a literal name — the approach
   `scripts/check-container-config-drift.sh:119-120` already uses, and which it
   documents explicitly *because* core-java declares no `container_name`.
2. Make the inputs configuration, not literals, following the repo's existing
   `${VAR:-default}` convention (`infra/backups/backup.sh:40`,
   `scripts/seed-e2e-fixtures.sh:54`, `infra/load-testing/baseline.sh:120`):
   `COMPOSE_FILE`, `CORE_SERVICE`, `CORE_CONTAINER` (explicit override).
3. **Fail closed.** Unresolvable or not-running container ⇒ new exit code **5 (VOID)**,
   never 0. Per the project's falsifiable-evidence contract, "found nothing" is never
   "clean".
4. Preserve the legitimate synthetic-only workflow, but make it **deliberate**:
   `ALLOW_SYNTHETIC_ONLY=1` opts in explicitly and reports `PARTIAL`, not `PASS`.
5. Update the exit-code header block.

This is additive per the Incremental Betterment Doctrine: the synthetic-only path is
retained, not removed — it just has to be asked for.

## Falsification plan (both directions required)

| Arm | Setup | Required result |
|-----|-------|-----------------|
| A — bug reproduced | old gate logic, stack up, core-java running | takes SKIP path, exit 0 |
| B — fixed, happy | new gate logic, stack up | resolves `jtoye_oaas_2026-core-java-1`, proceeds |
| C — fixed, break | new gate logic, `CORE_SERVICE=does-not-exist` | exit **5**, never 0 |
| D — fixed, opt-out | new gate logic, unresolvable + `ALLOW_SYNTHETIC_ONLY=1` | exit 0, labelled PARTIAL |
| E — fixed, stopped | new gate logic, container exists but not running | exit **5** |

Arm A is load-bearing: it proves the defect was live, not theoretical.

## Out of scope

Running the **full** end-to-end alert flow. That requires stopping `core-java` for
~4.5 min (`for: 2m` + routing + delivery) on the user's live 4-day-old stack, and
`.env` sets `ALERTMANAGER_SLACK_WEBHOOK_URL`, so a real outbound Slack notification
could fire. Not run without explicit approval; recorded here as an evidence gap
rather than claimed.
