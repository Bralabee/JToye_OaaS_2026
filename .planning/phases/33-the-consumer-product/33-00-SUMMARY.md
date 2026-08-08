---
phase: 33-the-consumer-product
plan: 00
subsystem: infra
tags: [falsifiability, control-arms, gitleaks, rls, permissions-policy, keycloak, ogl, code-point-open]

requires:
  - phase: 26-local-k8s-overlay-verified-breakage-fixes
    provides: "the falsifiable-evidence discipline this plan operationalises — capture the pre-state, carry a control, run the fail direction first"
provides:
  - "33-CONTROL-ARMS.md — six pre-state control arms (CA-1..CA-6), each with a non-vacuous control over the same corpus"
  - "CA-1: the NULL-coordinate pre-state, captured under BOTH database roles before 33-05 destroys it"
  - "CA-2: geolocation=() measured live and flagged a BLOCKER for the entire located path"
  - "A1 licence identity confirmed against primary sources, ahead of the decision that depends on it"
  - "Owner answers to Q-1 / Q-2 / Q-3, dated, with the figures behind them re-measured"
  - ".gitleaks.toml allowlist covering -CONTROL-ARMS.md"
affects: [33-01, 33-02, 33-03, 33-04, 33-05, 33-06, 33-07]

tech-stack:
  added: []
  patterns:
    - "Per-arm exact-once structural verification, replacing a whole-file >= count that a legend can satisfy"
    - "Every absence claim carries a control proving the pattern can match over the same corpus"

key-files:
  created:
    - .planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md
  modified:
    - .gitleaks.toml

key-decisions:
  - "Q-1 = q1-commit — ship the derived postcode artefact in-repo; offline deterministic builds beat repo size, and a build-time fetch would let a firewalled build silently produce a stack with no locality"
  - "Q-2 = q2-param — radius is a query parameter with a platform default; no schema change, and shops already carries latitude/longitude so #460 is a population problem"
  - "Q-3 = q3-record — record a dated ADR and commit the IdP groundwork DISABLED; jtoye.co.uk resolves only to a parking page whose HTTPS times out, so a Google production redirect URI remains impossible"
  - "The plan's own Task 3 verify limb was found vacuous and REPLACED, with both forms recorded"

patterns-established:
  - "A control line is mandatory, not decorative: it caught two instrument defects in this plan alone"
  - "Assert the clean state LAST as well as first — the break-arm run is bracketed clean -> arms -> clean"

requirements-completed: [CUST-01, CUST-03]

duration: 55min
completed: 2026-08-08
---

# Phase 33 Plan 00: Control Arms and Owner Gates — Summary

**Six pre-states captured before the phase can destroy them, an unverified licence turned into a primary-source confirmation, three owner decisions taken on re-measured figures — and the plan's own verification limb caught being incapable of failing.**

## Performance

- **Duration:** ~55 min
- **Started:** 2026-08-08T15:20Z (approx.)
- **Completed:** 2026-08-08T16:15Z
- **Tasks:** 4 of 4 (2 automated, 2 blocking owner gates)
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments

- **CA-1 was captured before it expired.** `shops` holds 5 rows, `count(latitude)` is **0**, and the app role sees only **3** of them. That third figure is the point: `shops_public_read` reduces to `published = true` with no tenant GUC, so recording the app-role reading alone would have made 33-05's backfill look complete two rows early. Both roles are now mandatory in `check-live-shop-coordinates.sh`.
- **CA-2 is a blocker, not an observation.** `geolocation=()` is an *empty* allowlist — it denies the API to the page's own origin on every route, including a 404, before any prompt. Read back live off the running app. Phase 33's entire located journey is dead until 33-03 changes it, and it presents to a user identically to a denial.
- **The licence question was closed on primary sources**, not on the OSM wiki: OS's own OpenData page states the OGL, links to OGL v3, and OGL v3's "You must" section contains attribution only — no share-alike. Two contrary signals are recorded rather than hidden.
- **Three decisions taken on numbers re-measured at decision time**, one of which overturned the reason printed in the plan's own option text.

## Task Commits

1. **Task 1: Allowlist the evidence file before writing it** — `a21aee67` (chore)
2. **Task 2: Human confirms the Code-Point Open licence FIRST** — folded into `11ce3156` (docs); a gate, not a code change
3. **Task 3: Capture the six control arms before they expire** — `11ce3156` (docs)
4. **Task 4: Owner decisions — dataset cost, radius shape, #432 disposition** — recorded in `33-CONTROL-ARMS.md`

## The finding: this plan's own verify limb could not fail

The plan specified, for each of five keys, `grep -cE "^key:" <file>` must be `>= 6`, and named the fail direction: delete CA-4's `control:` line, expect exit 1.

**Run in the fail direction, it did not fire.** The document's legend block listed all five keys at column 0, so every key measured **7**. Deleting CA-4's control left **6** — still `>= 6`, still green, on a file with a control arm gutted:

```
break applied (CA-4 control: deleted)
  measure: 7 PASS   result: 7 PASS   control: 6 PASS   control-result: 7 PASS   falsifies: 7 PASS
```

A whole-file `>=` count cannot express *"each arm carries each key"*. It tolerates any arm losing a key whenever another arm — or the legend — holds a spare.

Fixed at both ends, and **recorded rather than silently substituted**, per the falsifiability requirement:

- the legend is indented, so the original limb now measures 6 and drops to 5 on that break — it is capable of failing;
- the limb is replaced by a per-arm exact-once check that a legend outside any CA section cannot mask.

Bracketed break-arm run, **clean asserted first and last**:

| Arm | Result | rc |
|---|---|---|
| clean | per-arm structure OK | 0 |
| CA-4 `control:` deleted | `CA-4: 'control:' appears 0 times, expected exactly 1` | 1 |
| CA-2 `falsifies:` deleted | `CA-2: 'falsifies:' appears 0 times, expected exactly 1` | 1 |
| legend un-indented (original defect reintroduced) | per-arm structure OK — replacement is immune; the original limb was not | 0 |
| clean again | per-arm structure OK | 0 |

Breaks were built in scratch; the live file was never edited. Live hash recorded at each clean assertion.

## Two instrument defects, both caught by a control line

1. **CA-5's key pattern scored 0 for keys that are present.** I wrote `^  "key":`; the realm template writes `"key" : value` with a space before the colon. `realm`, `clients`, `roles` and `users` all read **0** — identical to `identityProviders`. Without present keys in the same output block, a broken instrument would have produced the correct answer and been trusted. The corrected pattern reads 1/1/1/1 against 0 for `identityProviders`, and a scratch fixture with `"identityProviders" : [ ]` injected reads 1, proving the zero is about the tree.
2. **`jq` cannot parse the realm template at all** — `parse error: Invalid numeric literal at line 15`, because line 15 is `"verifyEmail" : ${CUSTOMER_VERIFY_EMAIL},`, an unquoted `envsubst` placeholder in a boolean position. Any downstream check assuming valid JSON before rendering will VOID rather than fail.

## The stale premise found while taking Q-3

The plan's `q3-record` option reads *"`jtoye.co.uk` does not resolve while `DEPLOY_*_ENABLED` is false"*. It resolves:

```
getent hosts jtoye.co.uk   -> 162.255.119.30                      rc=0
dig +short jtoye.co.uk NS  -> dns1/dns2.registrar-servers.com     (Namecheap parking)
curl https://jtoye.co.uk   -> rc=28, timed out after 12005 ms, http_code 000
curl http://jtoye.co.uk    -> 302 parking redirect

CONTROL negative: getent hosts olajay.co.uk -> rc=2, does not resolve
CONTROL positive: curl https://www.ordnancesurvey.co.uk -> 200
```

The domain resolves to a registrar parking page whose HTTPS does not answer. Google's requirement is HTTPS on a resolving host, so the **conclusion stands and the premise does not** — `q3-record` is right for a different reason than the one written down. This matters beyond this plan: anyone about to flip `DEPLOY_*_ENABLED` on a successful `getent` would be acting on a parking page.

## Deviations from Plan

**[Rule 1 - Bug] The plan's Task 3 verify limb was vacuous** — Found during: Task 3 | Issue: `>= 6` whole-file key counts passed on a file with CA-4's control arm removed, i.e. the plan's own stated fail direction did not fire | Fix: indented the legend so the original limb becomes falsifiable, and added a strictly stronger per-arm exact-once check; both forms and both directions recorded in the evidence file under **Structural self-check** | Files: `33-CONTROL-ARMS.md` | Verification: 5-arm bracketed run above | Commit: `11ce3156`

**[Rule 1 - Bug] CA-5's key-shape pattern was wrong** — Found during: Task 3 | Issue: `^  "key":` returned 0 for keys that exist, because the file writes `"key" : value` | Fix: corrected to `^  "key" ?:` and added present-key controls plus an injected fixture in the same output block | Files: `33-CONTROL-ARMS.md` | Verification: realm/clients/roles/users = 1 each, identityProviders = 0, fixture = 1 | Commit: `11ce3156`

**[Rule 2 - Missing] Task 2's licence gate had no primary source to hand** — Found during: Task 2 | Issue: the URL printed inside the shipped `licence.txt` 404s, and RESEARCH's identity claim rested on secondary sources | Fix: located and quoted OS's own OpenData page and the National Archives OGL v3 text before putting the gate to the owner; recorded the two contrary signals (product page names no licence; CKAN says "No Licence Provided") | Files: `33-CONTROL-ARMS.md` | Verification: owner confirmed at the gate | Commit: `11ce3156`

**Total deviations:** 3 auto-fixed (2 bugs in the plan's own instruments, 1 missing evidence). **Impact:** all three strengthen the phase's foundation rather than change its scope. No task was skipped and no criterion was reported met without being run.

## Requirements Completed

- **CUST-01** — partially: the pre-states every CUST-01 criterion will be falsified against are captured (CA-1, CA-2, CA-3, CA-4, CA-6), and Q-1/Q-2 are settled. The requirement itself completes across 33-01..33-07.
- **CUST-03** — partially: CA-5 records the `identityProviders` pre-state and Q-3 settles the disposition. The dated ADR is 33-04's deliverable.

## Verification Results

| Success criterion | Result |
|---|---|
| `.gitleaks.toml` covers `-CONTROL-ARMS.md` | PASS — 2 mentions; entry present in the parsed `[[allowlists]]` paths array (tomllib: 3 sections, 1 matching path); break arm under a singular `[allowlist]` caught by two limbs |
| `gitleaks detect` is clean | **NOT RUN, and not claimed.** `command -v gitleaks` → rc=1; the only gitleaks in this repo is the pinned 8.27.2 GitHub Action. CI is the enforcement point and runs on the PR carrying this file. A skipped scan reported as clean is the exact vacuous shape this plan exists to prevent |
| A1 confirmed by a human, quoted, with URL, BEFORE Q-1 | PASS — ordering held; gate answered before the decision gate was presented |
| Six `### CA-n` arms, five keys each, controls non-zero | PASS — per-arm exact-once, rc=0; fails on two independent breaks |
| CA-1 records both roles and why they disagree | PASS — 3\|0\|3 vs 5\|0\|3, with the `shops_public_read` explanation |
| CA-4 records the four-site "near you" inventory | PASS — 7 occurrences, 4 rendered, 3 comments, plus the three existing assertions standing on them |
| CA-2 flagged as a blocker | PASS |
| Q-1, Q-2, Q-3 answered and dated | PASS — q1-commit / q2-param / q3-record, 2026-08-08 |
| No figure copied from RESEARCH without re-running | PASS, with one **declared exception**: the `1,748,230 rows / 15.1 MB` size is RESEARCH's and is labelled unverified in the file. 33-01's md5 gate owns proving it |
| No pasted command carries a password | PASS — 4 credential-shape sweeps read 0, and the same sweep against an injected leak reads 2, proving it can fire. 32 non-username `.env` values tested against the file: 0 found. The one `.env` match is `DB_USER` = `jtoye_app`, a role **name**, which the plan requires to be recorded |

## Issues Encountered

None blocking. One item carried forward deliberately: **CA-2 is a live blocker on 33-03.** Until `next.config.mjs` permits `geolocation=(self)`, no located journey can be tested — and a tester who does not know this will read the denial as a user declining the prompt.

## Next Plan Readiness

Wave 1 is complete. **Wave 2 (33-01, 33-03, 33-04) is unblocked** — all three depend only on 33-00, and all three of their gating owner answers are now recorded.

Note for whoever runs wave 2: from that point the phase branch is **expected** to be red on the two `docs-freshness` metric gates until 33-07 writes the prose figures. That is the designed state of a stacked phase branch, not a regression — and the figures must not be hand-edited to match a half-finished tree.
