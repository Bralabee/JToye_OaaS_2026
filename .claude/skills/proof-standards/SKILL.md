---
name: proof-standards
description: Evidence rules for any claim that work is done, verified, or passing. Use when writing a verification step, asserting a test/grep/count/diff result, claiming a service or build is correct, restoring an environment, or composing a commit/PR message. Every rule here was paid for by a measured failure.
---

# Proof standards

A check that has only ever been observed *passing* may be incapable of failing.
Before any assertion is trusted as evidence, it must be run against a
deliberately broken input and seen to fail. Record both directions.

This is not a style preference. Each rule below corresponds to a real incident
where a green signal was produced by a check that could not go red.

## 1. Show the fail direction

For every grep, count, diff, test, or gate you rely on:

- Run it against input you know is wrong. Confirm it fails.
- Then run it against the real input.
- State both outcomes in the verification evidence.

An assertion reported without its fail-direction run is not evidence, it is a
hope. Say so explicitly rather than implying it was verified.

## 2. Search on this machine is not what it looks like

- **`grep` and `rg` are injected shell functions that honour `.gitignore`.**
  A count or a "not found" from either is a count of what git tracks, not of
  what exists (measured: 12 vs 38 files on the same pattern). When a count or
  an absence is EVIDENCE, use `rg -uu`. When being wrong matters, run
  `searchcheck PATTERN PATH`, which fails loudly if the search paths disagree.
- **`find` is a shim routing to `bfs`, not GNU findutils.** Relative
  `-newerXt` operands are rejected silently and return zero results (measured:
  n=0 rc=1 where `/usr/bin/find` gave n=4 rc=0 — a watcher read that empty
  result as "all agents idle" while six were writing). Use `/usr/bin/find`, or
  an ISO operand: `-newermt "$(date -Is -d '10 minutes ago')"`.
- **Wrappers that `exec` cannot see `rg`/`grep`.** `timeout rg`, `xargs rg`,
  `env -u X rg`, `sudo rg`, `command rg` all die rc=127 with zero output,
  indistinguishable from a legitimate "not found". **Always print the rc.**
  To vary the environment use a subshell:
  `( unset VAR; out=$(rg -uu -l PATTERN PATH); rc=$? )`.
- **A truncating filter used to prove ABSENCE manufactures that absence.**
  `… | grep X | head -4` answers "is X present?" with "no" whenever X falls
  past the cut. Never bound a stream you are using to prove a negative.
- **Always run a positive control.** If a pattern you know must match returns
  nothing, your search direction is broken — not the codebase. A zero from an
  unvalidated search is a bug in the check.

## 3. Shell traps that invert or misreport results

- **`cmd | grep -q X` under `set -o pipefail` INVERTS on match.** grep exits
  first, the writer takes SIGPIPE → 141. It is size-dependent, so it hides in
  small-input testing, and it has made a real guard fail OPEN. Use a
  here-string on data already in hand: `grep -q X <<< "$out"`.
- **An exit code read after an intervening command reports the wrong
  command's status.** Capture on the same line: `out=$(cmd); rc=$?`. Never
  after an echo, a pipe, or a log line.
- **A wait-loop whose own command line satisfies its condition never exits.**
  `pgrep -f "X"` matches itself. Use `[X]` bracket self-exclusion AND a
  deadline.
- **Backticks inside double quotes execute.** A commit message, PR body, or
  `-m` string that *mentions* a command in backticks runs it and silently
  drops the phrase from the stored text (measured: a commit message executed
  `kubectl apply`). Pass prose via a quoted heredoc (`<<'EOF'`) or `-F <file>`,
  then read back what was actually stored — the corruption is invisible at
  write time.

## 4. "The artifact is right" is not "the running thing is right"

HTTP 200, a rendered page title, "builds clean", and a green suite are
identical whether the running code is current or months stale. Prove by
content and identity:

- Compare build/image timestamps against the commit times of the sources they
  are built from.
- Read the value out of the *running* artifact — for a fat jar, from inside
  the archive; a filesystem search returns a misleading 0.
- An old build is not automatically wrong. Check what actually changed before
  calling it stale.

## 5. A structural check can pass while the function is still broken

A gate asserts the property it happens to measure, not the behaviour you care
about. Measured: a container reported `healthy` while attached to no network
at all, and the repair's own new check went green while DNS stayed broken.

After any restore, repair, or config change, **exercise the real path**:
resolve the name, call the endpoint, read the value back out. "The gate I just
added now passes" is not evidence the thing works.

## 6. Restoring an environment is a code-changing event

Starting pre-existing containers or services does not rebuild them. Any step
that restores or hands back a runtime after source changed must rebuild and
then verify parity. Tie the requirement to the checkpoint, not to the activity
name — the same hazard reached through a different door needs the same gate.

## 7. Text search cannot answer questions about code structure

grep answers "where does this string appear" — never "who calls this", "what
implements this", or "is this still used", which are the questions a refactor
asks. Measured: every true caller of an interface-dispatched method was
invisible to every rg pattern; only an IDE call hierarchy found them. For
symbol questions, use structural tooling and treat a search result as a lead,
not a finding.

## 8. Bracketing destructive or reversible work

Run **clean → arms → clean again**. The closing clean assertion is the only
proof the restore happened; a restore has failed silently three times in one
session and was caught only by the closing arm.

- Verify restores **by content** (unique token, hash) — never by
  `git diff --stat`, which is empty both when a file is restored and when it
  was never written.
- Commit before running break arms, so the restore target is a committed
  state.
- `cd` inside a compound command can run the restore in the wrong directory
  while reporting success.

## 9. How to write a verification step

When producing a `<verify>` block, a test plan, or a "done" claim, each item
must name:

1. The exact command, with its rc captured on the same line.
2. The expected output, and what a FAILING run looks like.
3. Whether the fail direction was actually executed, or is unverified.

If the fail direction was not run, label the claim **unverified**. That is an
acceptable outcome; a silently vacuous check is not.
