---
quick_id: 260805-qdi
slug: stop-dev-target
date: 2026-08-05
branch: fix/stop-dev-full-stack-target
status: complete
---

# Summary: `stop-dev.sh` now stops both runtimes, and says so only when it is true

## What shipped

`scripts/stop-dev.sh` tears down **both** local runtimes — the hybrid one
`start-dev.sh` starts (`infra/` compose + host backend/frontend) and the canonical
full-stack compose project `jtoye_oaas_2026` — then verifies and exits non-zero if
anything survived.

The literal reading of the request ("target the right compose project") would have
replaced one teardown with the other. That is a regression by omission, so the change
is additive: each runtime is skipped-with-announcement when absent, never errored.

## Verification — three arms, all run against the real stack

| arm | setup | expected | result |
|---|---|---|---|
| clean | nothing running | exit 0, both announced absent | **exit 0** |
| positive | full stack up (11 containers) | stops it, exit 0, 0 left | **exit 0, 0 containers** |
| fail | full stack up, `FULL_STACK_COMPOSE` → nonexistent path | teardown skipped, containers survive, verifier catches it | **exit 1, 11 still running, all named** |

The fail arm is the one that matters: it is the exact scenario the old script passed
silently. It is possible only because verification counts containers **by label**
rather than through the compose file — a file-based check goes blind at precisely the
moment the path is wrong.

Data preservation checked after the teardown: 6 named volumes intact, including
`jtoye_oaas_2026_postgres_data`. No `-v` anywhere in the script.

## Three defects found by running it, not by reading it

1. **`pkill -f` killed the invoking shell.** `pkill -9 -f "next-server"` matches any
   process whose full command line contains that string — including the test shell,
   because the test command mentioned it. The run died with no output, no diagnostic,
   looking exactly like a hang. This was pre-existing behaviour, deferred in the plan
   as "a hazard that has not fired"; it fired, and a script that kills its own invoker
   cannot be verified, so it was fixed. See the plan's scope-deviation section.
2. **Container processes read as host processes.** The host PID namespace sees inside
   containers, so the verifier flagged the containerised `next-server` as a stray. An
   unrelated project's container would have failed this script after a correct
   teardown. Each pid is now checked against its cgroup.
3. **Kill and verify drifted the moment they were separate.** Two copies of "a host
   process we own" disagreed immediately — the verifier kept matching the invoking
   shell. Collapsed into one `matching_host_pids` helper used by both.

## What was NOT changed

The process patterns still match these processes for any checkout on this machine, not
only this one. Narrowing them to this repo risks failing to stop what `start-dev.sh`
started, which is the script's job. Recorded, not smuggled.

## Follow-up worth filing separately

`CLAUDE.md` states `start-dev.sh` drives `docker-compose.full-stack.yml`. It does not —
it drives `infra/` compose plus host processes. The docs and the script disagree about
which runtime is canonical, and that mismatch is what let this defect sit unnoticed.
Out of scope here because it is a documentation decision, not a script fix.
