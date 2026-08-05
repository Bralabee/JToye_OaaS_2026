---
quick_id: 260805-qdi
slug: stop-dev-target
date: 2026-08-05
issue: null
branch: fix/stop-dev-full-stack-target
status: in-progress
---

# Quick task: `stop-dev.sh` announced success over a stack it never touched

## What was actually wrong — and what was NOT

The first reading was "the script targets the wrong compose project". That is not quite
it, and the difference decides the fix.

`stop-dev.sh` is the faithful inverse of `start-dev.sh`. That script starts a **hybrid**
runtime: `infra/` compose for Postgres + Keycloak, then the backend
(`./gradlew :core-java:bootRun`) and frontend (`npm run dev`) as **host processes**. The
`pkill` lines and the `cd infra && docker compose down` are exactly right for that pair.

What is missing is the *other* runtime. `docker-compose.full-stack.yml` (compose project
`jtoye_oaas_2026`, 11 services) is what CLAUDE.md calls the canonical local dev + E2E
runtime, and it is what the Playwright suite runs against. `stop-dev.sh` had no knowledge
of it at all.

So re-pointing the script at the full-stack file — the literal reading of "target the
right project" — would have **removed** a working teardown to add a missing one. The
Incremental Betterment Doctrine names that: a regression by omission. The fix is additive.

## The failure this produced

Run against the full-stack runtime, the old script:

1. killed host processes that were not running (harmless no-ops),
2. ran `docker compose down` in `infra/`, a project with no running containers,
3. printed `✅ All services stopped`,

while all 11 containers kept running. `echo` is not a measurement — the script had no
step that could contradict it. Measured 2026-08-05: 11 containers running before, 11
after, and a success banner in between.

## The change

1. **Tear down both runtimes**, each skipped-with-announcement when absent, never errored.
2. **Verify by container label, not by the compose file.** A file-based check cannot see
   containers whose config path has gone (this repo has a live example: the `monitoring`
   project's compose file lived in a deleted worktree). Label-based verification also
   keeps working when the file path is wrong — which is what makes the fail arm possible.
3. **Exit non-zero when anything survives.** The banner is now a consequence of a check,
   not a `printf`.
4. **No `-v`, anywhere.** Named volumes (Postgres data, MinIO objects, Keycloak realm)
   survive. Stopping must never destroy data.

## Scope deviation: the `pkill` hazard fired mid-verification, so it was fixed

This section originally deferred the `pkill` blast radius as "a hazard that has not
fired". It fired — during the fail arm, on the shell running the test.

`pkill -f PATTERN` matches the **full command line** of every process, so
`pkill -9 -f "next-server"` killed the test shell for the sole reason that the test
command contained that string in an echo. The run died with no output and no
diagnostic, indistinguishable from a hang. A script that kills its own invoker cannot
be verified, so this stopped being deferrable.

Fixed by filtering candidates before signalling them: never self, never an ancestor of
this process, never a process inside a container. The bracket trick does **not** help
here — `[n]ext-server` still matches the text "next-server" wherever it appears; the
trick only protects against the matcher matching itself.

Still deferred: the patterns match these processes for any checkout on this machine.
Narrowing to this repo risks failing to stop what `start-dev.sh` started.

## Proof obligations

A stop script that reports success over a running stack is precisely the defect being
fixed, so the verifier must be shown able to FAIL:

- **clean arm** — nothing running → exits 0, both halves announced as not running;
- **positive arm** — full-stack running → script stops it, exits 0, 0 containers left;
- **fail arm** — full-stack running but `FULL_STACK_COMPOSE` pointed at a non-existent
  path → teardown is skipped, containers survive, and the label-based verifier must
  catch it and exit **1**. This is the arm the old script could never fail.
