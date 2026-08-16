---
phase: 31-consumer-safety-and-legal-floor
plan: 06
subsystem: infra
tags: [gdpr, retention, ci-gates, bash, jq, compliance, falsifiability]

requires:
  - phase: 22-notifications-and-comms
    provides: webhook_delivery retention job + V54's deliberate no-prune rule on consent stores
  - phase: 24-image-architecture
    provides: media quarantine retention horizon (jtoye.media.quarantine-retention-ms)
provides:
  - docs/retention-manifest.json — the machine-readable source of truth for every published retention period (12 rows, 6 Automated / 6 Operational)
  - scripts/check-retention-enforcement.sh — a 0/1/2 gate tying each published Automated period to a real enforcement site, its consumer, and its value converted across units
  - a ci-cd.yaml step wiring that gate into the ops-contracts job on every PR
  - removal of cleanup.orphaned-image-days, a published config key that nothing enforced
affects: [31-12, 31-09, 31-18, phase-32]

tech-stack:
  added: []
  patterns:
    - "Retention manifest = flat claim keys + .rows, so ONE engine reads both halves of the loop"
    - "A gate asserts the CONSUMER, not just the config key — that is what catches a dead key"
    - "The gate owns the unit conversion the claim-gate engine has none of"
    - "Every negative assertion carries a positive control, or its zero is a fact about the search"

key-files:
  created:
    - docs/retention-manifest.json
    - scripts/check-retention-enforcement.sh
  modified:
    - core-java/src/main/resources/application.yml
    - .planning/codebase/INTEGRATIONS.md
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "The manifest is an OBJECT with flat integer claim keys plus .rows, not a bare array: jq's has() errors on an array ('Cannot check whether array has a string key', rc=5), which would VOID the claim-gate engine 31-12 must use"
  - "The gate asserts a CONSUMER for every Automated row — a config-key-only check would have PASSED on cleanup.orphaned-image-days, the very key that motivated it"
  - "R-9 publishes NO number: the ~6-year HMRC figure is a legal position, not a measurement"
  - "R-5 is enforced but published Operational and deliberately NOT gated — its value lives in the Keycloak realm, not this repo"
  - "cleanup.orphaned-image-days deleted rather than published, and NOT named in the yml comment, because a grep proving it is gone cannot tell a real occurrence from a comment mentioning it"

patterns-established:
  - "Sentinel every jq @tsv field: IFS=$'\\t' read collapses consecutive tabs, shifting every field after an empty one"
  - "VOID (exit 2) on zero comparisons performed — a run that compared nothing has proven nothing"

requirements-completed: [LGL-01]

duration: 78min
completed: 2026-08-16
---

# Phase 31 Plan 06: Retention Manifest + Enforcement Gate Summary

**Every retention period the platform is about to publish is now tied by a CI gate to a real
enforcement site, its consumer and its value converted across units — and the one period nothing
enforced was deleted rather than published.**

## Performance

- **Duration:** ~78 min
- **Started:** 2026-08-16T11:57Z
- **Completed:** 2026-08-16T13:15Z
- **Tasks:** 2 of 2
- **Files modified:** 5 (2 created, 3 modified)

## Commits

| Commit | Task | Paths |
|--------|------|-------|
| `3c7d05c5` | Task 1 | `docs/retention-manifest.json`, `core-java/src/main/resources/application.yml`, `.planning/codebase/INTEGRATIONS.md` |
| `8c4bfca0` | Task 2 | `scripts/check-retention-enforcement.sh`, `.github/workflows/ci-cd.yaml` — **one commit**, asserted below |

```
$ git show --stat 8c4bfca0 --format="%h %s"
8c4bfca0 feat(31-06): gate every published retention period against what the code enforces
 .github/workflows/ci-cd.yaml           |  28 +++
 scripts/check-retention-enforcement.sh | 334 +++++++++++++++++++++++++++++++++
 2 files changed, 362 insertions(+)
```

---

## THE FINAL ROW LIST — 31-12 renders exactly these and must NOT re-derive them

Every number below was read out of its enforcement site on 2026-08-16, not carried forward from
RESEARCH. Six `Automated`, six `Operational` — matching RESEARCH's predicted split.

| id | Category (page wording) | Period | Unit | Class | Enforcement site | Consumer that reads it |
|----|-------------------------|--------|------|-------|------------------|------------------------|
| R-1 | Abandoned checkouts | 24 | hours | **Automated** | `application.yml` key `stale-draft-hours` (line 733) | `config/ScheduledCleanupService.java` (`cleanup.stale-draft-hours`) |
| R-2 | Webhook delivery records | 30 | days | **Automated** | `application.yml` key `retention-days` (line 634) | `webhook/WebhookRetentionCleanup.java` (`getRetentionDays`) |
| R-3 | Quarantined image uploads | 72 | hours | **Automated** | `application.yml` key `quarantine-retention-ms` (line 291) — stores **259200000 ms** | `media/MediaQuarantineRetentionSweep.java` (`getQuarantineRetentionMs`) |
| R-4 | Customer sign-in cookies | 30 | days | **Automated** | `frontend/lib/customer-auth-cookies.ts` const `REFRESH_MAX_AGE` = `60 * 60 * 24 * 30` (**2592000 s**) | `app/api/customer-auth/session/route.ts` (`REFRESH_MAX_AGE`) |
| R-5 | Customer access cookie | *(descriptive)* | — | Operational | — | — (value lives in the Keycloak realm) |
| R-6 | Marketing opt-outs and opt-ins | indefinite | — | **Automated (NEGATIVE)** | `webhook/WebhookRetentionCleanup.java` (anchor: `deliberately NEVER time-pruned`) | `V54__notification_consent.sql` (`there is deliberately no`) |
| R-7 | Personal details on completed orders | on-request | — | Operational | — | — |
| R-8 | Audit history | indefinite | — | Operational | — | — |
| R-9 | Order and payment records | statutory | — | Operational | — | — |
| R-11 | Order-tracking email in your browser | session | — | **Automated (literal)** | `app/shop/orders/orders-client.tsx` (`sessionStorage.setItem("jtoye-track-email"`) | `app/shop/[slug]/orders/[orderNumber]/page.tsx` |
| R-12 | Saved checkout email in your browser | until-cleared | — | Operational | — | — |
| R-13 | Guest order history in your browser | until-cleared | — | Operational | — | — |

**Published display strings (verbatim — the page must quote these, not paraphrase):**

- R-5 → "The short sign-in session length set by our identity provider, renewed automatically while you stay signed in"
- R-6 → "Kept indefinitely - deliberately never deleted"
- R-7 → "Removed on request - there is no automatic timer"
- R-8 → "Kept indefinitely"
- R-9 → "For as long as the law requires"
- R-11 → "Cleared when you close the tab"
- R-12 / R-13 → "Until you clear your browser's site data"

**R-9 carries no number, on purpose.** The ~6-year HMRC figure is `[ASSUMED]` — a legal position,
not a measurement — and this repository enforces no deletion path for those records at all.

**R-10 does not exist as a row.** It is recorded under `.deliberately_unpublished` with its removal
date and the two-arm evidence, so the deletion is traceable rather than silent.

**Flat claim keys for 31-12's `claims.manifest` source rows** (`source retention json docs/retention-manifest.json int`):
`draft_order_hours=24`, `webhook_delivery_days=30`, `media_quarantine_hours=72`,
`customer_refresh_cookie_days=30`. The gate asserts each equals its row's `period_value`, so the
two copies inside one file cannot drift.

---

## Two-arm search for the `orphaned-image-days` removal — REAL OUTPUT

`rg` here is an interactive **shell function** with no binary behind it; a script subshell gets
`rg: command not found`, **rc=127 and zero results** — indistinguishable from a legitimate "not
found". That happened on the first attempt and is why every arm prints its rc. The recipe used is
`( exec -a rg "$CLAUDE_CODE_EXECPATH" ... )` with `-uu` so `.gitignore` cannot hide a consumer.

```
=== INSTRUMENT CHECK (is the search mechanism live at all?) ===
rc=0
out='core-java/src/main/java/uk/jtoye/core/config/ScheduledCleanupService.java:25:public class ScheduledCleanupService {'

=== ARM A (subject): orphaned-image-days — Java consumers ===
rc=1
out=''

=== ARM B (control): stale-draft-hours — Java consumers (identical globs/flags/dir) ===
rc=0
out='core-java/src/main/java/uk/jtoye/core/config/ScheduledCleanupService.java:32:    @Value("${cleanup.stale-draft-hours:24}")'
```

Whole-tree arms (`rg -uu`, no globs, **no truncating filter** — a `head` used to prove absence
manufactures that absence): the subject appeared **only** in `application.yml:719` and in planning
prose; the control appeared in `application.yml:718`, `ScheduledCleanupService.java:32`,
`ScheduledCleanupServiceIntegrationTest.java:48,57,94` and `docs/architecture/…`. Same flags, same
globs, same directory — so the empty result is a fact about the code, not about the search.

**Java suite green without the key** — the evidence that removing it was safe:

```
$ ./gradlew :core-java:test --rerun-tasks
BUILD SUCCESSFUL in 1m 34s
5 actionable tasks: 5 executed          <- executed, not UP-TO-DATE

$ (aggregate of core-java/build-local/test-results/test/*.xml)
suites=149 tests=1098 failures=0 errors=0 skipped=1
```

The XML aggregate is recorded because "BUILD SUCCESSFUL" is identical whether 1098 tests ran or
zero did.

---

## ⚠ A PLAN ACCEPTANCE CRITERION WAS VACUOUS — measured, replaced, both recorded

> *"`grep -c 'orphaned-image-days'` returns 0 in both `core-java/src/main/resources/application.yml`
> and `.planning/codebase/INTEGRATIONS.md`."*

Baseline measured **before** any edit:

| File | literal `orphaned-image-days` | literal `ORPHANED_IMAGE_DAYS` | case-insensitive `orphaned.image.days` |
|------|------|------|------|
| `application.yml` | **1** | 1 | **1** |
| `.planning/codebase/INTEGRATIONS.md` | **0** ← already 0 | 1 | **1** |

INTEGRATIONS.md quoted the key as the **env var** `CLEANUP_ORPHANED_IMAGE_DAYS` — uppercase,
underscores — so the plan's case-sensitive hyphenated grep was **already 0 on the unmodified
tree** and could not distinguish a done change from an undone one. Replaced with the strictly
stronger case-insensitive `orphaned.image.days`, which is **1 → 0 in both files**. After the
change, both forms are 0 in both files (recorded above in the verify run). The removal itself was
performed regardless — the vacuity was in the *check*, not in the work.

A second, smaller instance: `jq -e 'length >= 10'` passes on the shipped manifest returning **10
top-level keys**, but it is counting metadata keys, not rows. The meaningful form,
`.rows | length >= 10`, returns **12**. Both are recorded; the literal form is reported as passing
**for the wrong reason**, not as satisfied.

---

## check-gate-enforcement.sh `gates:` count — before and after

| When | Output | rc |
|------|--------|----|
| Before (base `64d9f0ad`) | `gates: 35, workflows: 6, exempt: 6 declared` → PASS | 0 |
| After (`8c4bfca0`) | `gates: 36, workflows: 6, exempt: 6 declared` → PASS | 0 |

One higher, as required, with `workflows` and `exempt` unchanged — the gate is **wired**, not
exempted. `grep -cF` in `gate-enforcement.conf` = **0**; in `ci-cd.yaml` = **2**.

---

## GATE BREAK ARMS — BOTH DIRECTIONS, REAL OUTPUT

The tree was **committed before any arm** (`git checkout` restores from the INDEX and would have
discarded post-staging edits). Every restore was verified **by content** —
`git hash-object` vs `git rev-parse HEAD:<path>` — never by `git diff --stat`, which is empty both
when a file is restored and when it was never written. Each run opened clean and **closed clean**;
the closing arm is the only proof the restores happened.

### Opening clean arm

```
PASS: 12 published retention row(s) — 6 Automated (4 value comparison(s), 1 literal, 1 negative), 6 Operational (described, deliberately not gated).
rc=0
```

### (a) rc=1 EXISTENCE — enforcement site missing

```
FAIL: [R-1] enforcement site does not exist: core-java/src/main/resources/does-not-exist.yml
rc=1
    restore OK  hash=afa3533ba4f7473032973534c05bbedea8a146ec == HEAD:docs/retention-manifest.json
```

### (a2) rc=1 EXISTENCE — a REAL file that does not hold the key

```
FAIL: [R-1] core-java/src/main/resources/application-prod.yml does not declare 'stale-draft-hours' — the published period has no enforcement site. THIS IS THE R-10 CASE: a period nobody enforces must not be published.
rc=1
```

### (a3) rc=1 CONSUMER — **the arm that proves this gate would have caught the dead key**

```
FAIL: [R-1] core-java/src/main/java/uk/jtoye/core/config/ScheduledCleanupService.java does not contain 'cleanup.orphaned-image-days' — NOTHING READS the published period. This is exactly the shape of the dead key that motivated this gate.
rc=1
```

A gate that only checked "the key exists in the yml at the declared value" would have **passed**
on `cleanup.orphaned-image-days`. This is the assertion that makes it not decorative.

### (b) rc=1 VALUE + UNIT CONVERSION — 72 → 71 while the site still holds 259200000 ms

```
OK  R-1  24 hours == 24 hours at core-java/src/main/resources/application.yml:stale-draft-hours
OK  R-2  30 days == 30 days at core-java/src/main/resources/application.yml:retention-days
FAIL: [R-3] published 71 hours (= 255600s) but core-java/src/main/resources/application.yml holds 259200000 milliseconds (= 259200s) for 'quarantine-retention-ms'
rc=1
```

This is the arm proving the ms↔hours conversion **actually runs**. Without it, 259200000 and 72
would never have compared and the gate would have been decorative.

### (b2) rc=1 SELF-CONSISTENCY — the flat claim key disagrees with its row

```
FAIL: [R-1] top-level 'draft_order_hours' = 25 but the row publishes 24 — the manifest disagrees with itself
rc=1
```

### (b3) rc=1 NEGATIVE ANCHOR — the written no-expiry rule loses its anchor

```
FAIL: [R-6] core-java/src/main/java/uk/jtoye/core/webhook/WebhookRetentionCleanup.java no longer states 'this phrase is not in that file' — the deliberate no-expiry rule has lost its written anchor
rc=1
```

### (c) rc=2 VOID — seven ways the gate refuses to report clean over nothing

| Input | Message | rc |
|-------|---------|----|
| `MANIFEST=/dev/null` | `VOID: manifest not found: /dev/null` | **2** |
| zero-byte file | `VOID: manifest is empty … — refusing to report clean over nothing` | **2** |
| absent path | `VOID: manifest not found: …` | **2** |
| unparseable JSON | `VOID: manifest is not parseable JSON: …` | **2** |
| `.rows = []` | `VOID: manifest declares zero rows — refusing to report clean over an empty scan` | **2** |
| zero `Automated` rows | `VOID: manifest declares zero Automated rows — there is nothing to enforce, so this scan proves nothing` | **2** |
| Automated rows but **zero numeric comparisons** | `VOID: zero value comparisons were performed — every Automated row is existence-only, so no published number was verified against its source` | **2** |

### (d) rc=2 VOID — the negative assertion's POSITIVE CONTROL is dead

```
VOID: POSITIVE CONTROL IS DEAD: the prune-path pattern found nothing in …/WebhookDeliveryRepository.java, which DOES have one. A zero result on the subjects would therefore be a statement about this search, not about the code.
rc=2
```

Run via the overridable `JAVA_SRC` against a constructed tree whose control repository has no
prune path — so the arm needed no destructive edit to tracked source.

### (e) rc=1 NEGATIVE — a prune path appears against a consent store

Injected `long deleteByCreatedAtBefore(java.time.OffsetDateTime cutoff);` into
`NotificationSuppressionRepository.java` (line 41 — injection confirmed present before the run, so
the arm is not vacuous):

```
control    : prune-path pattern finds 3 hit(s) in the webhook delivery repository — the scan is live
core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationSuppressionRepository.java:41:    long deleteByCreatedAtBefore(java.time.OffsetDateTime cutoff);
FAIL: [R-6] a prune, purge or delete path now exists against a consent store. A GDPR/PECR opt-out that expires resurrects a suppressed recipient — V54 states the rule and threat T-22-02-04 is what it mitigates. These tables are bounded by their UNIQUE key, never by time.
rc=1
    restore OK  …/NotificationSuppressionRepository.java  hash=d023cb6ad2879ff76f793635de09aba64244e4ae
```

---

## THE DOUBLE BIND — MEASURED IN BOTH DIRECTIONS, NOT ASSUMED

This is the measurement that proves the same-commit rule was **necessary**, not cautious.

### Direction 1 — the script wired nowhere (workflow step removed, no conf entry)

```
FAIL: 1 gate(s) are referenced by no workflow and carry no exemption:
        check-retention-enforcement.sh
      A gate that cannot fire on a pull request does not prevent anything.
rc=1
```

### Direction 2 — a `gate-enforcement.conf` entry **instead of** the workflow step

```
  gates     : 36
  workflows : 6
  exempt    : 7 declared
FAIL: 1 exemption(s) look stale:
        check-retention-enforcement.sh — declared runtime-dependent but invokes no runtime binary
rc=1
```

Both directions are **rc=1**, pointing opposite ways, so **no ordering of two separate commits is
green at both points.** The script and its `ci-cd.yaml` step therefore landed in commit `8c4bfca0`
together.

Supporting measurement, with a live control, that Direction 2 *must* fail:

```
subject  check-retention-enforcement.sh   deps=''         (invokes no runtime binary → any conf entry is stale)
CONTROL  check-runtime-freshness.sh       deps='docker '  (so the dependency scan itself is live)
```

### Restores and closing clean arms

```
    restore OK  .github/workflows/ci-cd.yaml            hash=4bbfb58d9b51a2716169f8f835481de41b1643cc
    restore OK  scripts/gates/gate-enforcement.conf     hash=6e3ec5503798fc75e3e5a84ea472fd66a676ca59

check-gate-enforcement  gates: 36, workflows: 6, exempt: 6 declared → PASS   rc=0
retention gate          PASS: 12 published retention row(s) …             rc=0
```

`git status --short` after all arms: **empty**.

---

## A GATE THAT RUNS NOWHERE IS NOT A GATE — the CI JOB was verified, not just the script

Verified structurally, with both a **positive control** (an already-wired sibling gate must resolve
to a job) and a **negative control** (an absent token must resolve to nothing), because a resolver
that answers confidently for everything answers nothing:

```
POSITIVE CONTROL  check-no-create-extension.sh        -> job 'ops-contracts' at line 688
NEGATIVE CONTROL  check-this-gate-does-not-exist.sh   -> not found (correct)
SUBJECT           check-retention-enforcement.sh      -> job 'ops-contracts' at line 716
job-level 'if:' lines in 'ops-contracts': 0
pull_request trigger lines: 1
PASS: … runs as a step of job 'ops-contracts', which has no job-level if: guard, in a workflow that triggers on pull_request.
```

`ops-contracts` has no `paths:` filter and no job-level `if:`, so the gate fires on **every** PR to
`main` — it is not a per-PR-invisible check. (The workflow's push filter already reads
`[main, 'phase-*', 'phase/**']`, so the recorded `phase/`-branch CI-invisibility trap does not
apply here.)

---

## Sibling gates re-run after the change (nothing collateral went red)

| Gate | rc | Result |
|------|----|--------|
| `scripts/check-claims.sh` | 0 | 43 claims across 5 docs match |
| `scripts/check-doc-citations.sh` | 0 | 73 verified citations, 0 violations |
| `scripts/check-no-create-extension.sh` | 0 | 61 migrations scanned |
| `scripts/check-no-measured-placeholders.sh` | 0 | 0 matches |
| `scripts/docs-freshness.sh` | 0 | metrics match source (2807) |
| `scripts/check-doc-metrics.sh` | 0 | 37 prose claims match |
| `scripts/check-gate-enforcement.sh` | 0 | gates: 36 |
| `scripts/check-retention-enforcement.sh` | 0 | 12 rows, 6 Automated, 4 value comparisons |

---

## Deviations from Plan

### 1. [Rule 1 - Bug] The manifest is an object with `.rows`, not a bare array

- **Found during:** Task 1, while reading `scripts/gates/claim-gate.sh` to make sure 31-12 could
  consume the artifact.
- **Issue:** The plan's `<action>` says "a JSON array of rows", but its own `read_first` says to
  match `docs/metrics.json`'s shape "so one engine reads both", and RESEARCH:492 writes the wave-3
  row as `source retention json docs/retention-manifest.json int`. Those cannot both hold. The
  engine resolves a `json` source with a **top-level `has($k)` lookup** (`claim-gate.sh:256`), and
  measured on a bare array:

  ```
  $ echo '[{"id":"R-1","v":24}]' | jq -r --arg k "R-1" 'if has($k) then (.[$k]|tostring) else "__ABSENT__" end'
  jq: error (at <stdin>:1): Cannot check whether array has a string key
  rc=5
  ```

  Shipping a bare array would have handed 31-12 an artifact the named engine VOIDs on.
- **Fix:** Top-level object carrying flat integer claim keys (metrics.json's exact shape) **plus**
  `.rows` (the array of rows) **plus** `.deliberately_unpublished`. The gate additionally asserts
  every flat key equals its row's `period_value`, so having the number twice in one file is itself
  gated rather than trusted.
- **Consequence for the plan's verify block:** `jq -e 'length >= 10'` and `.[]` were written for an
  array. Both the literal and the corrected `.rows`-scoped forms were run and are recorded above;
  nothing was silently substituted.
- **Files:** `docs/retention-manifest.json`, `scripts/check-retention-enforcement.sh`

### 2. [Rule 2 - Missing critical functionality] The gate asserts a CONSUMER, not only the key

- **Found during:** Task 2 design.
- **Issue:** The plan's assertion 1 is "the path exists AND contains the declared config key". Run
  against `cleanup.orphaned-image-days`, that assertion **passes** — the key was present in
  `application.yml` at a plausible value. What made it dead was having no reader. A gate that could
  not catch the case that motivated it is decorative.
- **Fix:** Each `Automated` row carries `enforced_by.consumer.{path,must_contain}`; the gate fails
  when the consumer file is missing or does not contain the token. Break arm (a3) above is the
  proof, and it names the dead key in its failure message.
- **Files:** `docs/retention-manifest.json`, `scripts/check-retention-enforcement.sh`

### 3. [Rule 1 - Bug] `IFS=$'\t' read` collapsed empty TSV fields — caught by the gate's own first run

- **Found during:** Task 2, first execution.
- **Issue:** TAB is IFS **whitespace**, so two consecutive tabs collapse into one delimiter and
  every field after an empty one shifts left a column. R-6's empty `period_unit` slid its consumer
  path into the wrong variable, producing a confidently wrong
  `FAIL: [R-6] declared consumer does not exist: there is deliberately no`.
- **Fix:** A jq `def nz:` sentinels every field to `-` when absent or empty; the bash side compares
  against `-`. Documented inline so it is not reintroduced.
- **Files:** `scripts/check-retention-enforcement.sh`

### 4. [Rule 2 - Missing critical functionality] Twelve rows, not nine

- **Found during:** Task 1. R-1..R-9 with R-10 deleted is only **nine** rows, one short of the
  plan's ">= 10", and a schedule that omits personal data held in the browser is incomplete rather
  than merely short. Measured three real, previously unpublished categories:
  `jtoye-track-email` (sessionStorage, R-11), `jtoye-checkout-email-<shop>` (localStorage, R-12) and
  `jtoye-guest-orders` (localStorage, R-13). R-11 is genuinely gateable — if it were ever moved to
  localStorage the published "cleared when you close the tab" would become false and the anchored
  literal assertion fires. R-12/R-13 are honestly `Operational`: local storage has no expiry, so
  claiming an automated period for them would be a lie.
- **Files:** `docs/retention-manifest.json`

### 5. [Rule 2] The removed key is not named in the `application.yml` comment

The comment explaining the removal deliberately does **not** write the key's name, because a grep
asserting the key is gone from that file cannot distinguish a real occurrence from a comment
mentioning it — the self-match trap `check-no-create-extension.sh:34-44` documents. The key is
recorded by name under `.deliberately_unpublished` in the manifest instead, so traceability is kept
without defeating the check.

---

## Notes for downstream plans

- **31-12 (wave 3):** the row list above is authoritative; render it, do not re-derive it. Use
  `source retention json docs/retention-manifest.json int` with the four flat keys
  (`draft_order_hours`, `webhook_delivery_days`, `media_quarantine_hours`,
  `customer_refresh_cookie_days`). The non-numeric rows have no flat key by design — gate their
  prose against `period_display` with a `regex` source, or leave them to this gate. Publish R-9
  **without** a number.
- **31-09 (wave 2):** this plan's `application.yml` edit is confined to the `cleanup:` block at the
  end of the file (lines 717-733). No collision expected.
- **31-18 (wave 5):** this plan's `ci-cd.yaml` edit is one step inside the existing `ops-contracts`
  job, immediately after `check-no-create-extension.sh`. A new job appended elsewhere will not
  conflict.
- **Anything adding a `scripts/check-*.sh`:** the double bind is now measured, not folklore — see
  the section above. Script and workflow reference, one commit, always.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern or schema change. The one behavioural
change to shipped runtime config is the **removal** of a key that nothing read, proven by a
two-arm search and by 1098 green Java tests.

## Known Stubs

None. No placeholder, `TBD`, empty or null period ships in the manifest — asserted by the gate and
by the plan's own verify block.

## Self-Check: PASSED

Every file and commit this summary claims was verified to exist, **each with a negative control**,
because a checker that answers "found" for everything has verified nothing:

```
FOUND: docs/retention-manifest.json
FOUND: scripts/check-retention-enforcement.sh
FOUND: .github/workflows/ci-cd.yaml
FOUND: core-java/src/main/resources/application.yml
FOUND: .planning/codebase/INTEGRATIONS.md
FOUND: .planning/phases/31-consumer-safety-and-legal-floor/31-06-SUMMARY.md
NEGATIVE CONTROL  MISSING: docs/this-file-was-never-created-31-06.json (correct)

FOUND: 3c7d05c5  feat(31-06): publish the retention schedule's source of truth …
FOUND: 8c4bfca0  feat(31-06): gate every published retention period against what the code enforces
NEGATIVE CONTROL  MISSING: deadbee1 (correct)
```

Full diff against the plan base `64d9f0ad` — **`STATE.md` and `ROADMAP.md` are absent, as required
of a worktree agent**:

```
.github/workflows/ci-cd.yaml
.planning/codebase/INTEGRATIONS.md
.planning/phases/31-consumer-safety-and-legal-floor/31-06-SUMMARY.md
core-java/src/main/resources/application.yml
docs/retention-manifest.json
scripts/check-retention-enforcement.sh
```

`git status --short`: empty.
