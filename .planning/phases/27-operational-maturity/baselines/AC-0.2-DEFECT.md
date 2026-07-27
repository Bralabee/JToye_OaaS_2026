# AC-0.2 is unfalsifiable as written — two defects, both proven by execution

**Found:** 2026-07-27, during Task 0 of 27-00, running the plan's own prescribed BREAK arm.
**Tree:** `1499494b5535e35ea02a16cab9f7ca4a665298a6` (clean at capture).
**Filename note:** deliberately not `B-*.txt` — it must not be picked up by either the AC-0.1
`for n in 1..7` loop or the AC-0.2 `baselines/B-*.txt` glob.

Per the binding falsifiability requirement, the original is recorded alongside the replacement
rather than silently substituted, and both directions' real output is recorded.

## The criterion as written (27-00-PLAN.md:970)

```bash
for f in baselines/B-*.txt; do
  ac=$(sed -n 's/^CLOSED_BY: //p' "$f")
  grep -q "$ac" <<<"$(cat 27-00-PLAN.md)" || echo "DANGLING $f -> $ac"
done
# "prints nothing".  BREAK: set one to `AC-99.9` and confirm it prints `DANGLING`.
```

## Defect 1 — the prescribed BREAK cannot fail. Its sentinel is written into the file it greps.

`AC-99.9` appears in `27-00-PLAN.md` **because AC-0.2's own text names it** as the break value:

```
$ grep -n 'AC-99.9' 27-00-PLAN.md
970:    - **AC-0.2 ... BREAK: set one to `AC-99.9` and confirm it prints `DANGLING`. Restore. ...
```

This is the known vacuous shape *"a doc/lint rule that must name the token it forbids, so it fires
on its own definition."* Executed:

```
### BREAK with the prescribed sentinel AC-99.9
B-5 CLOSED_BY is now: AC-99.9
check output:
^^ EMPTY — identical to the pass direction.

### Same check, sentinel AC-77.7 (verified absent: grep -c 'AC-77.7' 27-00-PLAN.md -> 0)
DANGLING baselines/B-5.txt -> AC-77.7
```

The sharpest form of the failure: **the break direction and the pass direction produce byte-identical
output (nothing at all).** A verifier following the plan literally sees no `DANGLING`, and the only
honest reading available to them is ambiguous — the criterion cannot distinguish "every pointer
resolves" from "the check is broken". It is not a check.

## Defect 2 — the check FAILS OPEN on a missing `CLOSED_BY` line.

If `sed` matches nothing, `ac` is empty and `grep -q ""` matches every input. A baseline file with
**no mapping at all** therefore passes:

```
### CLOSED_BY line deleted from B-5
B-5 CLOSED_BY lines: 0
check output:
^^ EMPTY — a file with no mapping passes.
```

This violates the plan's own must-have: *"Every gate in this plan exits 2 (VOID) on … an empty
discovery result — 'found nothing' is never 'clean'."* AC-0.2 was the one gate that did not.

## Replacement — strictly stronger, and it rehabilitates the plan's own break sentinel

`scripts/`-independent, committed at `baselines/verify-ac02.sh`. Three changes:

1. **VOID (exit 2)** on: plan file missing/empty, zero baseline files discovered, extracting zero
   defined criteria, or any file with an empty/missing `CLOSED_BY`.
2. **Matches against criteria the plan DEFINES, not merely mentions** — `**AC-x.y` (the bolded
   bullet form, 69 of them) rather than a free-text search of the whole file. This is what makes
   Defect 1 go away *without changing the sentinel*: `AC-99.9` is mentioned once and defined zero
   times, so the plan's own prescribed break now correctly reports `DANGLING`.
3. **`grep -qxF`** — literal, whole-line. The original's unanchored regex let `.` act as a wildcard,
   so `AC-1.1` would also match a hypothetical `AC-1X1`.

### Both directions, executed (exit codes captured with `out=$(cmd); rc=$?`, never after an `echo`)

| # | Arm | Output | rc | Expected |
|---|---|---|---|---|
| D1 | real tree | `AC-0.2 OK: 7 baselines, every CLOSED_BY resolves to a defined criterion` | 0 | 0 |
| D2 | B-5 → `AC-99.9` (**the plan's own sentinel**) | `DANGLING B-5.txt -> AC-99.9 (not a criterion DEFINED in 27-00-PLAN.md)` | 1 | 1 |
| D3 | B-5 → `AC-77.7` | `DANGLING B-5.txt -> AC-77.7 (not a criterion DEFINED in 27-00-PLAN.md)` | 1 | 1 |
| D4 | B-5 `CLOSED_BY` deleted | `VOID B-5.txt: no CLOSED_BY line` | 2 | 2 |
| D5 | baselines dir emptied | `VOID: no baseline files discovered` | 2 | 2 |
| D6 | all restored | `AC-0.2 OK: 7 baselines …` | 0 | 0 |

All seven `B-*.txt` verified byte-identical to their pre-break backups afterwards (`cmp -s`).

## A third trap, hit while proving the above — recorded because it recurs

The first run of the table reported `rc=0` for **every** arm including the VOIDs. The harness was
`out=$(cmd); echo "$out"; echo "rc=$?"` — `$?` reads the **`echo`**, not the script. The gate was
correct and the harness was lying, in the direction that reports everything green. Capture the code
on the same line as the call: `out=$(cmd 2>&1); rc=$?`. This is HANDOFF §8's standing trap arriving
through a different door than the documented one (there it was `cmd | tail`).

## Is this a pattern? Swept all 70 criteria. No — it is a one-off.

An earlier revision of this file asserted that AC-6.13 and AC-3.6 were the same class. **That was
wrong and is retracted.** It was written without checking, and both are sound:

- **AC-6.13's** break substitutes a different *baseline* (`git show 78eaa99:docs/metrics.json` →
  exit 1 at 1736/1157). No sentinel token, no self-matching file.
- **AC-3.6's** break removes a *code path* (the `DiskSpace*` skip) and asserts the extractor then
  demands 16 instead of 14. Also no sentinel.

The actual sweep, over all **70** criteria the plan defines:

| Class | Search | Instances |
|---|---|---|
| **A — self-matching sentinel** (check greps a file the prescribed break token already lives in) | criteria whose check greps `27-00-PLAN.md` | **1** — AC-0.2. The only other hit is prose in a scope table at :865, not a criterion. |
| **B — fail-open matcher** (a possibly-empty variable used as the pattern) | `grep -q "$var"` sites | **1** — AC-0.2's `$ac`, which is `sed`-derived. The other two sites (:962 `$k`, :1115 `$q`) iterate **hardcoded literals** that can never be empty. Both safe. |

**The plan already defends this exact class elsewhere, and does it well** — which is what makes
AC-0.2 a miss rather than a blind spot. At :1163-1166 it names the trap outright for
`check-terminal-states.sh` ("the script names the states it forbids-without-detection, so it will
match its own text. Restrict every discovery grep to its declared source path — never a repo-wide
scan"), and criteria it into existence:

- **AC-3.8 (self-exclusion)** — *"BREAK: widen one discovery grep to a repo-wide scan, confirm it
  then reports the script itself."*
- **AC-3.10** — an expected-0 `grep -c` must not kill the script under `set -e`; it must reach the
  VOID handler and exit 2.
- **AC-3.1's** break — a discovery rule reporting 0 must exit **2**, never print `0 discovered … PASS`.

AC-0.2 is the plan's own meta-check, and it is the one place the plan did not apply its own rule to
itself. No other criterion needs re-running on this account.

## Disposition

- The replacement is used for Task 0's AC-0.2 verification; both directions are recorded above.
- `27-00-PLAN.md:970` is **not edited** — the plan is a merged artifact and 27-00 is not its owner.
  Whoever revises Phase 27's plans should replace the criterion text with `verify-ac02.sh`'s form.
- Tasks 2-6 must run every break arm through `runcheck.sh` (committed alongside this file), which
  makes the exit-code trap below structurally unrepeatable.
