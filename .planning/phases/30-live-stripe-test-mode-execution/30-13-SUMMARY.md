---
phase: 30-live-stripe-test-mode-execution
plan: 13
subsystem: k8s-secrets-wiring
tags: [stripe, connect, key-vault, csi, secretproviderclass, environment-tag, compose, pay-03]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-12: stripe.connect-webhook-secret / stripe.environment-tag read in application.yml with empty fallbacks (STRIPE_CONNECT_WEBHOOK_SECRET, STRIPE_ENVIRONMENT_TAG)"
provides:
  - "Key Vault object stripe-credentials--connect-webhook-secret, classified external whsec_; in core-java-kv objects and in the stripe-credentials secretObjects as key connect-webhook-secret"
  - "core-java env STRIPE_CONNECT_WEBHOOK_SECRET (secretKeyRef stripe-credentials/connect-webhook-secret, optional)"
  - "app-config stripe.environment-tag per overlay only (local / staging / production), read by core-java STRIPE_ENVIRONMENT_TAG through a configMapKeyRef that is not optional"
  - "compose: STRIPE_CONNECT_WEBHOOK_SECRET (empty default) and STRIPE_ENVIRONMENT_TAG (default local); .env.example entries; k8s-local-secrets.sh adds connect-webhook-secret when set"
  - "Preflight treats the new object as a Stripe TEST object (3 test-mode lines on a full vault; a non-whsec_ value is NOT-TEST-MODE)"
affects: [30-14, 30-15, 30-17, 30-20, 30-22]

estimate:
  tokens: 55000
  tasks: 2
actuals:
  tokens: 12740     # chars/4 over git diff ac14dbee..90a49dc1
  tasks: 2
  commits: 5        # MEASURED: git rev-list --count ac14dbee..HEAD before this SUMMARY's own commit
plan_head_before: ac14dbee25055bce444559e17b48be0878e34f73
plan_head_after: 90a49dc10a1c67a35c1208049c2ae4bd9bdea833

tech-stack:
  added: []
  patterns:
    - "An environment-identity key declared per overlay and never in base, read by a non-optional configMapKeyRef, so a forgotten overlay fails the pod instead of inheriting another environment's name"
    - "Selftest expectations counted from the derivation AND pinned to the expected number, so a derivation that loses an object cannot shrink its own expectation"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-13-task1-verify.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-13-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-13-task2-verify.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-13-task2-arms.txt
  modified:
    - scripts/lib/staging-secrets-common.sh
    - k8s/components/azure-keyvault/secretproviderclasses.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/base/configmap.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - scripts/staging-secrets-preflight.sh
    - scripts/staging-secrets.sh
    - docs/runbooks/credential-rotation.md
    - docker-compose.full-stack.yml
    - .env.example
    - scripts/k8s-local-secrets.sh
    - .planning/phases/30-live-stripe-test-mode-execution/deferred-items.md

key-decisions:
  - "The SPC comment for the new object sits outside the objects: | block scalar: a # line inside it is literal string content the CSI provider parses, not a YAML comment"
  - "staging-secrets.sh --list prints an external object's required prefix or validator as a third column, so the listing says what --set-external will accept; other classes keep two columns"
  - "No CI invariant was added for the tag value: it needs a new render invariant with its own selftest and CI wiring, outside this plan's files; recorded in deferred-items.md with a route"

requirements-completed: [PAY-03]

coverage:
  - id: D1
    description: "The Connect secret chain renders end to end (Key Vault object, SPC object, synced Secret key, optional env) and every chain gate knows about it"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-13-task1-verify.txt (static suite rc 0; staging render spc_object_refs=2 secret_key_refs=2 env_entries=1; chain + writer + preflight selftests and --list rc 0, classified=1)"
        status: pass
      - kind: other
        ref: "evidence/30-13-task1-arms.txt (arms A-D, each red at the intended gate; clean before and after)"
        status: pass
    human_judgment: false
  - id: D2
    description: "stripe.environment-tag declared per overlay with its own name, absent from base, read by a non-optional ref; compose and local wiring"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-13-task2-verify.txt (static suite rc 0; miss=0; base tag_key_count=0 beside env_entry_count=1; env-example contract, gate enforcement, compose config, docs-freshness rc 0)"
        status: pass
      - kind: other
        ref: "evidence/30-13-task2-arms.txt (production tagged staging -> miss=1; local tag deleted -> miss=1; base value -> count 1)"
        status: pass
    human_judgment: false

duration: 14min
completed: 2026-10-25
---

# Phase 30 Plan 13: Connect secret and environment tag wiring Summary

**The Connect endpoint's signing secret now has a governed path from Key Vault to core-java. The object `stripe-credentials--connect-webhook-secret` is classified `external whsec_`, so the writer refuses any other value and the preflight checks it as a Stripe TEST object. It is listed in the core-java SPC and synced as `stripe-credentials/connect-webhook-secret`, then read as `STRIPE_CONNECT_WEBHOOK_SECRET` through an optional ref. `stripe.environment-tag` is declared by each overlay (local, staging, production) and never by base. core-java reads it through a ref that is not optional, so an overlay that forgets the key fails the pod at start. Compose, `.env.example` and the local-cluster bootstrap carry both variables.**

## Performance

- **Duration:** about 14 min (2026-10-25 22:40Z to 22:54Z)
- **Tasks:** 2 of 2
- **Commits:** 5 (2 feat, 3 docs), on `feat/30-connect-webhook`, not pushed. Branch is 0 behind origin/main (`git log HEAD..origin/main` empty after a fetch).

## Task results

**Task 1 (tracer): the Connect secret chain.** The new classification row, the SPC object and secretObjects key, and the env entry are all in place. Goldens were regenerated with `render-golden.sh --write`. The diff since origin/main is 29 insertions and 0 removals: the env entry in both goldens and the SPC object and key in staging. The production golden gets the env entry only, because production renders no SPC.
- Verify: the static suite returns rc 0 with 0 FAIL or PARSE ERROR lines across 80 output lines. The staging render has `spc_object_refs=2 secret_key_refs=2 env_entries=1`. The chain, writer and preflight selftests and `--list` return rc 0, with `classified=1` and 0 FAIL lines.
- Arm A (the plan's arm): removing the classification row makes `--list` return rc 1 and print `UNCLASSIFIED`, and both selftests go red.
- Arm B: classifying the object as `sk_test_` turns the writer's external-prefix arm red, and the preflight full arm reports `NOT-TEST-MODE`.
- Arm C: removing the env entry fails the render check (`env_entries=0`), and both goldens drift.
- Arm D: removing the secretObjects key makes `check-keyvault-chain.sh` report "SecretProviderClass core-java-kv produces Secret stripe-credentials without key connect-webhook-secret".
- Tracer gate (end-of-phase mode, automated-only verify): the re-run of the full verify passed (V1 rc 0, V3 rc 0, classified=1), so the plan went on to Task 2.

**Task 2: the environment tag and both variables.** The base configmap carries a comment saying why the key is absent. Each overlay declares its own name.
- Verify: the static suite returns rc 0. Each overlay renders `tag_value=1 env_entry=1` (`miss=0`).
- Base acceptance: `kubectl kustomize k8s/base` returns rc 0 with `tag_key_count=0` beside `env_entry_count=1`. This is the control: the render is the deployment, not an empty string.
- Arm A (the plan's arm): setting the production tag to "staging" gives `production tag_value=0`, `miss=1`. Restored, `miss=0`.
- Arm B: deleting the local tag gives `local tag_value=0`, `miss=1` (see the finding below).
- Arm C: adding a base value gives `tag_key_count=1`.
- Also green: `check-env-example-contract.sh` (rc 0, 0 missing), `check-gate-enforcement.sh` (rc 0), `docker compose ... --env-file .env.example config` (rc 0, `STRIPE_ENVIRONMENT_TAG: local`, connect line present) and `docs-freshness.sh` (rc 0, 6667, no count change). `check-env-contract.sh` now reports 91 injected for core-java (89 before).
- `k8s-local-secrets.sh` needs a local cluster, which cannot run beside compose, so only the edited stripe block was exercised with `apply_secret` stubbed (key names only). With the secret set, the keys are api-key, webhook-secret and connect-webhook-secret. With it empty, the keys are api-key and webhook-secret. With no API key, the Secret is skipped. `--help` still derives `stripe-credentials`.

Every break arm was bracketed: clean, arm, restore, clean. Each restore was checked against HEAD by blob hash, and the closing clean showed `git diff --quiet` rc 0.

## Finding: no CI gate holds the tag to the overlay's name

In Task 2 arm B, `check-render-invariants.sh` and `check-env-contract.sh` both stayed rc 0 with the local overlay's tag deleted. So the plan's Task 2 V1 condition "a configMapKeyRef with no key in some overlay" cannot fail. That sub-claim is **unverified by that gate**. Only the plan's own per-overlay check catches arms A and B, and that check is a plan command, not a CI step. A production render tagged "staging" would pass every CI gate today. Plans 30-17 and 30-20 trust this value. The finding is recorded in `deferred-items.md`. The route recorded there is a render invariant beside INV-22 with a planted-arm selftest, wired into CI, for 30-14 Task 1 or 30-20. It was not added here: it is a new gate outside this plan's files.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The writer's selftest counted two Stripe objects**
- **Found during:** Task 1
- **Issue:** The `--assign-access` arm in `scripts/staging-secrets.sh --selftest` hard-coded 2 Stripe deferrals and 2 grants. With a third Stripe object it would go red, and the plan's V3 runs this selftest. The file was not in `files_modified`.
- **Fix:** The count is now taken from the derivation and asserted to be 3. Also added an external-prefix sub-arm: the Connect secret refuses `sk_test_z` (nothing written) and accepts `whsec_z` once. That makes the D-28 "refuses anything else" truth tested for the new object. Arm B shows the sub-arm can fail.
- **Files modified:** scripts/staging-secrets.sh
- **Commit:** 292543da

**2. [Rule 3 - Blocking] `--list` printed no prefix, so the plan's V3 pattern could not match**
- **Found during:** Task 1
- **Issue:** `--list` printed `name<TAB>class`, so the plan's `stripe-credentials--connect-webhook-secret.*external.*whsec_` pattern gave `classified=0` on a correct tree.
- **Fix:** For an external object, `--list` also prints the prefix or validator the writer requires (`whsec_`, `sk_test_`, `@email`, `@nonempty`). Nothing parses this output (searched: the runbook mentions it, no script consumes it). The usage line was updated.
- **Files modified:** scripts/staging-secrets.sh
- **Commit:** 292543da

**3. [Rule 1 - Bug] An SPC comment was placed inside the `objects: |` block scalar**
- **Found during:** Task 1, while reviewing the golden diff
- **Issue:** A `#` line inside a block scalar is string content that the CSI provider parses, and the first golden diff showed it as part of the objects string.
- **Fix:** Moved the comment above the stripe-credentials secretObjects entry, outside the scalar, and regenerated the goldens. The final golden diff has no comment lines.
- **Commit:** 292543da

**4. [Rule 2 - Doc correctness] The runbook said "43 objects"**
- The derived list is now 44. The runbook says so, and notes that the owner stores the new object in plan 30-14.
- **Commit:** 292543da

**5. The preflight full arm now expects 3 test-mode lines and names the Connect object; a new `connect-not-test-mode` arm was added.** This is the plan's "and its test-mode rule". Arms A and B turned both red.

**6. REQUIREMENTS.md: PAY-03 left unchecked.** As in 30-12, `requirements-completed: [PAY-03]` means "contributes to PAY-03". The box claims the full loop has run on staging, and it has not.

**Total deviations:** 6 (2 blocking fixes in the writer, 1 YAML placement bug, 1 doc count, 1 planned selftest extension, 1 requirement tick withheld). **Impact:** no scope change. One gate gap was found and recorded, not fixed.

## Notes for later plans

- **30-14 (hard ordering, T-30-52):** the staging render now has the SPC mount `stripe-credentials--connect-webhook-secret`. The CSI driver fails the core-java pod mount if that object is absent from `jtoye-stg-kv`. The owner must store it first, with `scripts/staging-secrets.sh --set-external stripe-credentials--connect-webhook-secret`, which takes whsec_ only. Then `--assign-access` grants core-java's identity read access (one new grant, derived from the SPC), and the preflight should show `test-mode: yes (stripe-credentials--connect-webhook-secret)`. Only after that may PR 4 merge. Nothing was written to Key Vault or the cluster in this plan.
- **30-15:** the running Deployment should list both env names. `STRIPE_ENVIRONMENT_TAG` resolves to `staging`.
- **30-17 / 30-20:** the tag is declared per overlay, but no CI gate checks the value (see the finding).
- **whsec_ is not a mode marker:** Stripe uses the `whsec_` prefix for live and test endpoint secrets alike. The prefix rule refuses a non-webhook value, such as an API key pasted into the wrong object. It cannot tell a live endpoint's secret from a test one. Test mode is guaranteed by the `sk_test_` API key, not by this object. This is unchanged from the existing `stripe-credentials--webhook-secret`.

## Known Stubs

None. A scan of the lines added outside `.planning/` (160 lines) for TODO, FIXME, placeholder, "coming soon" and "not available" found 0 matches. The same pattern matched 1 on a planted control line.

## Threat Flags

None beyond the plan's register. T-30-51 is mitigated: the class is whsec_ and the preflight covers the object. T-30-52 is carried to 30-14. T-30-53: `check-no-plaintext-secrets.sh` passes and the goldens hold names only. T-30-54: the grant is derived from the SPC in 30-14.

## Self-Check: PASSED

- All 4 evidence files and this SUMMARY exist under the phase directory. `secretproviderclasses.yaml` names `stripe-credentials--connect-webhook-secret` 3 times (object, secretObjects entry, comment), and `staging-secrets-common.sh` names it once (the class row).
- Commits 292543da, 55d56a7f, b551dea6, f067ecbc and 90a49dc1 are all ancestors of HEAD (`git merge-base --is-ancestor`, each FOUND).
