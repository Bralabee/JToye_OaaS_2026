# B-6's control was VACUOUS, and it hid a second fault

**Found:** 2026-07-27, while verifying AC-1.2 on the fixed tree.
**Amends:** `B-6.txt` — which is left byte-identical, because it is a capture from a clean tree and
retro-editing it would destroy its provenance. This file is the correction.

## What B-6 claimed

> `--- CONTROL COMMAND ---`
> `docker exec jtoye-postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select 1"'`
> `--- CONTROL OUTPUT --- 1`
> *"credentials, database and reachability are all fine. … the sslmode is isolated as the operative cause."*

## Why it could not fail

The compose Postgres ships the stock `pg_hba.conf`:

```
local   all   all                     trust
host    all   all   127.0.0.1/32      trust      <-- the control's path
host    all   all   ::1/128           trust
host    all   all   all               scram-sha-256   <-- the exporter's actual path
```

`-h 127.0.0.1` from inside the container matches the **trust** rule. Executed:

```
$ docker exec -e PGPASSWORD="definitely-not-the-password-$$" jtoye-postgres \
    psql -h 127.0.0.1 -U jtoye -d jtoye -tAc "select 'WRONG PASSWORD ACCEPTED'"
WRONG PASSWORD ACCEPTED
```

**A deliberately wrong password returns success.** The control asserted nothing about credentials.
It was the plan's own thesis — *a check you have not seen fail is not evidence* — applied to the
baseline that was written to demonstrate that thesis.

## What it hid: a SECOND fault, stacked behind the first

The exporter's real path is the container network, where `scram-sha-256` applies. Three arms over
`jtoye_oaas_2026_jtoye-network` (the network name is **not** `jtoye-network`; a first attempt using
that failed all three arms identically, which is a VOID, not a result):

| Password used | Result |
|---|---|
| `POSTGRES_EXPORTER_PASSWORD` (len 11) | `FATAL: password authentication failed for user "jtoye"` |
| `POSTGRES_PASSWORD` (len 6) — control | `ACCEPTED` |
| deliberately wrong — control | `FATAL: password authentication failed` |

`POSTGRES_EXPORTER_PASSWORD` in the local `.env` was simply wrong. It had **always** been wrong, and
`pg_up` would have stayed 0 after the sslmode fix on its own.

**Why nobody saw it:** TLS negotiation happens *before* authentication, so `sslmode=require` against
a non-TLS server failed first and masked it. Fixing fault 1 is what surfaced fault 2 — the error text
changed from `pq: SSL is not enabled on the server` to `pq: password authentication failed`. Two
faults, one symptom (`pg_up=0` while `up=1`), and the baseline recorded one.

## Both faults, and the causation arm that now isolates fault 1 cleanly

| Fault | Fix | Where |
|---|---|---|
| 1. `sslmode=require` vs a non-TLS server | `disable` | `.env.example:14` (committed) + local `.env` |
| 2. `POSTGRES_EXPORTER_PASSWORD` ≠ the DB password | aligned | local `.env` only — `.env.example` correctly keeps `CHANGE_ME` |

With fault 2 fixed, AC-1.2's second arm isolates fault 1 without ambiguity for the first time:

```
### BREAK: sslmode=require, exporter recreated (container id changed)
  pg_up=0   up{job="postgres"}=1   numbackends=EMPTY
  pq: SSL is not enabled on the server
### RESTORE: sslmode=disable
  pg_up=1   up{job="postgres"}=1   numbackends=5
```

`up` stays **1** in both arms. That is B-6's actual point and it now has a clean two-arm proof:
**a target being UP is not evidence the thing behind it is healthy.**

## Consequences

1. **A stronger control for this class.** Never authenticate over a path whose `pg_hba` rule differs
   from the one under test. Prove the auth path first (`trust` vs `scram-sha-256`), and always run a
   known-bad arm. A credential control that has not rejected a wrong credential is decoration.
2. **`.env.example` cannot carry fault 2's fix** — it ships `CHANGE_ME` by design. So a fresh clone
   whose operator sets `POSTGRES_EXPORTER_PASSWORD` to anything other than `POSTGRES_PASSWORD` gets
   `pg_up=0` with a *green* `up` target and a `DatabaseDown` alert reporting healthy. That is
   precisely the blind-monitoring shape OPS-01 exists to eliminate, and it is **not** closed by this
   plan: `check-alert-liveness.sh` (Task 4, AC-4.2) is what turns it into a hard failure, by
   asserting `pg_up == 1` rather than `up == 1`. Recorded here as the reason that criterion is
   load-bearing rather than belt-and-braces.
3. **`.env.example`'s "MUST be set — compose will fail fast if blank" is accurate only for the
   password** (`:?` at `docker-compose.monitoring.yml:118`). `POSTGRES_EXPORTER_USER` uses `:-jtoye`
   and is **empty** in the local `.env` right now, silently defaulting. Not changed here — out of
   Task 1's scope — but it is the same fail-open shape one field over.
