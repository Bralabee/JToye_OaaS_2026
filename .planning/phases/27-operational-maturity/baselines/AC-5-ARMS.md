# AC-5.1 .. AC-5.17 — break-arm record for `scripts/check-dependency-horizons.sh`

**Executed:** 2026-07-27, branch `feature/27-00-ops-spine`, against the live endoflife.date API.
Every arm ran through `baselines/runcheck.sh`, which captures `$?` on the same line as the call
and exits 1 when the observed code differs from the asserted one — so an arm that failed to
break could not be recorded as a pass.

**Result: 24 arms, 24 matched.** Two arms were REWRITTEN first because the plan's form could not
have failed (§A), and two gate defects were found by running them (§B).

---

## AC-5.1 — the RED that closes baseline B-4

Run with the manifest complete but **no `exemption:` block anywhere**, which is the order the
criterion demands: record the breaches before writing the deferrals that hide them.

```
FAIL: H-3 alpine: alpine-linux/3.20 PAST EOL 2026-04-01 (catalogue, 117 days)
FAIL: H-3 keycloak: keycloak/24.0 PAST EOL 2024-06-10 (catalogue, 777 days)
FAIL: H-3 rabbitmq: rabbitmq/3.12 PAST EOL 2024-02-21 (earlier of catalogue/vendor, 887 days)
FAIL: H-3 prometheus: prometheus/2.48 PAST EOL 2023-12-28 (catalogue, 942 days)
FAIL: H-3 grafana: grafana/10.2 PAST EOL 2024-07-24 (catalogue, 733 days)
FAIL: H-3 node: nodejs/20 PAST EOL 2026-04-30 (catalogue, 88 days)
  H-2/H-3        violations=6  active-exemptions=0
FAILED: 6 horizon contract violation(s).                                   rc=1
```

Exactly the six B-4 recorded, fetched independently 100 minutes later. After adding the six
exemptions:

```
  H-2/H-3        violations=0  active-exemptions=6
OK: every pinned artifact carries a resolved, in-window or explicitly-deferred horizon.  rc=0
```

**The original brief named ONE EOL dependency. The mechanism found six.**

---

## §A — TWO CRITERIA REWRITTEN BECAUSE THE PLAN'S FORM COULD NOT FAIL

### AC-5.17 — expected exit 1 is exit **0** on a correct tree

The plan says `eol_source: vendor` on rabbitmq 4.3 (vendor `2026-11-30`) must exit 1. Executed,
it exits **0 — and that is the right answer**: 2026-11-30 is **126 days** from 2026-07-27 and
`HORIZON_WARN_DAYS` is 90. The row is genuinely not yet in the window. Recorded verbatim:

```
=== AC-5.17b: eol_source=vendor (plan's form, default 90d window)
--- rc=0  expected=1  *** MISMATCH ***
```

This is the "expected-0 that is actually 1 on a CORRECT tree" trap arriving inverted. Satisfying
it as written would have meant widening the default window until an unbreached row failed —
weakening the gate to make a criterion pass.

**Replaced with a matched pair at one window wide enough to span the date**, so the ONLY
difference between the two runs is the field under test:

```
AC-5.17a  eol_source=catalogue  HORIZON_WARN_DAYS=150 -> rc=0   (takes `false` at face value:
          the WRONG answer, and exactly what 27-02's AC-10 Break 3 is built to detect)
AC-5.17b  eol_source=vendor     HORIZON_WARN_DAYS=150 -> rc=1
          FAIL: H-3 rabbitmq: rabbitmq/4.3 approaching 2026-11-30 (vendor (eol_source override), 126 days)
AC-5.17c  CONTROL: eol_source=vendor at the DEFAULT 90d          -> rc=0
```

Arm (c) is what makes the pair evidence rather than coincidence: it proves 5.17b's exit 1 came
from the override and not from the wider window.

### AC-5.10 — the plan's control cancels itself out

The break sets rabbitmq to cycle 4.3 (catalogue `eol: false`) with `vendor_eol` present, and the
control removes `vendor_eol`. Run literally, **both arms exit 1** — the row still carries its
exemption, and an exemption on a row with no horizon is a STALE-exemption failure, so the control
fails for a reason unrelated to what it claims to test. Same shape as plan defect P-3.

Fixed by removing the exemption in **both** arms, leaving `vendor_eol` as the only difference:

```
AC-5.10 BREAK   4.3 + vendor 2026-11-30 -> rc=1
   FAIL: H-2b rabbitmq: catalogue reports no horizon (eol: false) for rabbitmq/4.3 but the
         VENDOR dates it 2026-11-30 — a missing horizon on an adopted pin.
AC-5.10 CONTROL identical row, vendor_eol REMOVED -> rc=0
```

A first attempt at this arm set `eol_cycle: 4.3` but left `vendor_eol` at 3.12's `2024-02-29`,
producing the self-contradictory message "for rabbitmq/4.3 ... VENDOR dates it 2024-02-29". It
still exited 1, so it would have passed unnoticed. Re-run against the real 27-02 numbers.

---

## §B — TWO GATE DEFECTS FOUND BY RUNNING THE ARMS

**B-1. H-5 matched pins inside COMMENTS — a fail-open.** The first implementation searched each
site file with a plain fixed-string grep. `mcp-server/Dockerfile:2` is a comment naming
`node:20-alpine`, and `edge-go/Dockerfile:29` is `# Stage 2: Runtime stage (scratch = minimal
image)`. So H-5 would have reported the pin **present after the real `FROM` line was deleted** —
the drift check passing on a tree where the pin no longer exists. Comment lines are now excluded.

**B-2. Multi-site rows invented drift.** Rows pinned at several sites in one file (`node` ×4,
`ollama` ×2) compared *every* site against the *first* match, so three of five reported line
drift that was not there. The declared line is now checked exactly.

Both were visible only because the first run's output was read line by line rather than by exit
code — the gate exited 1 either way.

**B-3. A rule the plan required was never implemented.** AC-5.16 BREAK-a expects that flipping
`rabbitmq-k8s` from `out_of_repo` to `image` becomes a coverage violation. It exited **0**: a row
with `sites: []` is invisible to H-1 (nothing discovers it) and to H-5 (nothing to drift-check),
which is precisely how a row goes on describing a pin that was deleted. Rule added — any kind
other than `out_of_repo` with empty `sites` now fails H-1 — and the arm then matched.

---

## §C — the remaining arms, all matched

| Arm | Break | rc | Key line |
|---|---|---|---|
| AC-5.2 | `image: busybox:1.36` added to the monitoring compose | 1 | `H-1 busybox:1.36 is pinned in the declared source surface but has NO horizon row` |
| AC-5.3 | rabbitmq `eol_date` -> `2099-01-01` | 1 | `H-2 rabbitmq: cached eol_date 2099-01-01 disagrees with fetched 2024-02-21` |
| AC-5.4 | `HORIZON_WARN_DAYS=100000` | 1 | names `postgresql/15` (472d) **and** `eclipse-temurin/21` (1253d) — rows that PASS at the default, so "nothing is in the window" is not an already-true assertion |
| AC-5.5 | keycloak `exemption.expires` -> `2020-01-01` | 1 | `exemption EXPIRED on 2020-01-01 (2399 days ago)` |
| AC-5.6 | exemption added to `postgres/15`, not past horizon | 1 | `H-4 postgres: STALE exemption — 472 days away, outside the 90-day window` |
| AC-5.7a | `node` row slug -> bare `node` | **2** | `slug 'node' REDIRECTED to .../nodejs.json` |
| AC-5.7b | `alpine` row slug -> bare `alpine` | **2** | `REDIRECTED to .../alpine-linux.json` |
| AC-5.7c | `postgres` row slug -> bare `postgres` | **2** | `REDIRECTED to .../postgresql.json` |
| AC-5.8 | base URL -> `raw.githubusercontent.com` | **2** | `effective host ... is 'raw.githubusercontent.com', expected 'endoflife.date'` — the host check fires, never a fetch |
| AC-5.9 | redis `eol_cycle` -> `"7"` | **2** | `cycle 7 not present in the redis catalogue — resolve the floating tag and record it, do not guess` |
| AC-5.11a | scratch PATH, no jq/curl | **2** | `required tool not on PATH: curl`. `PATH=/nonexistent` deliberately NOT used — it exits 127 before bash starts, so the VOID branch would never execute |
| AC-5.11b | `endoflife.date.invalid` (unresolvable) | **2** | `fetch failed ... an unreachable source is never 'clean'` |
| AC-5.11c | prometheus `eol_cycle` -> `999` | **2** | cycle not present |
| AC-5.12 | alertmanager row deleted | 1 | `H-1 prom/alertmanager:v0.27.0 ... has NO horizon row` |
| AC-5.14 | prometheus bumped to v2.49.0 **in source only** | **2** | H-1 *and* H-5 fire together; `VOIDED ... Exit 2 takes precedence over the 1 contract violation(s) also reported` — precedence **executed, not assumed** |
| AC-5.15b | `rabbitmq-k8s` manual_review deleted | 1 | `an unknown nobody has agreed to re-check is a silence, not a state` |
| AC-5.15c | manual_review lapsed `2020-01-01` | 1 | `manual_review LAPSED on 2020-01-01 (2399 days ago)` |
| AC-5.16a | (baseline) `out_of_repo` + `sites: []` | 0 | H-1/H-5 mentions of `rabbitmq-k8s` = **0** |
| AC-5.16b | `owner:` deleted from `rabbitmq-k8s` | 1 | `missing mandatory field owner` — the kind exemption did **not** widen to the mandatory fields |

### AC-5.7 raw evidence — the slug trap, measured not asserted

```
node           301 https://endoflife.date/api/nodejs.json
alpine         301 https://endoflife.date/api/alpine-linux.json
postgres       301 https://endoflife.date/api/postgresql.json
nodejs         200
alpine-linux   200
postgresql     200
```

Written the obvious way, this gate would VOID on five of eleven resolvable rows forever — and a
permanently-VOID gate is how a check earns a `|| true`.

### AC-5.13 — `--refresh` round-trip, shown by VALUE not just by exit code

```
before break:   eol_date: "2024-02-21"
after break:    eol_date: "2099-01-01"     gate rc=1
refresh: 1 field(s) rewritten
after refresh:  eol_date: "2024-02-21"     gate rc=0
```

`--refresh` on an already-clean tree produces **no diff** (`git diff --quiet` holds), so it is
idempotent and cannot be used to launder an edit.

### AC-5.15a — UNKNOWN is printed on every run, pass or fail

```
UNKNOWN rabbitmq-k8s: The staging/production broker is not deployed from this repo ...
        owner=UNASSIGNED review-expires=2026-10-26
  H-6 UNKNOWN    rows=8                                                    rc=0
```

**Deviation from the plan, recorded:** the criterion says the summary reports `unknown=1`. It
reports **8**, and 8 is correct — seven third-party images (minio, minio/mc, ollama, mailhog,
alertmanager, and both exporters) have no endoflife.date product at all, measured 404. The plan
assumed `rabbitmq-k8s` was the only unknown. Asserting the printed LINE rather than the count is
what the criterion actually needs, since the count moves whenever a 404 product is added.

**Deviation on AC-5.15's owner CONTROL:** the plan says use `minio`. `minio` carries a
`manual_review`, so H-6 fires first and H-4's owner rule is never reached — the control would
have proven a different rule than it claims. Run against `postgres`, which has no
`manual_review`, isolating H-4:

```
FAIL: H-4 postgres: owner is UNASSIGNED with no manual_review              rc=1
```
