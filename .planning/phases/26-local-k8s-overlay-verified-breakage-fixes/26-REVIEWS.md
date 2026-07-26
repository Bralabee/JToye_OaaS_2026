---
phase: 26
reviewers: [copilot, ollama-gemma3, ollama-deepseek-r1]
reviewers_unavailable: [gemini, claude, codex, coderabbit, opencode, qwen, cursor, lm_studio, llama_cpp]
reviewed_at: 2026-07-25T16:59:45+01:00
plans_reviewed: [26-01-PLAN.md, 26-02-PLAN.md, 26-03-PLAN.md, 26-04-PLAN.md, 26-05-PLAN.md, 26-06-PLAN.md, 26-07-PLAN.md, 26-08-PLAN.md, 26-09-PLAN.md]
verdict: MEDIUM risk — proceed after applying items 1-5 of "Recommended action before executing"
---

# Cross-AI Plan Review — Phase 26: Local-K8s Overlay + Verified Breakage Fixes

## Reviewer availability

`/gsd-review --phase 26 --all` was requested. What was actually reachable on this host:

| Reviewer | Status | Note |
|---|---|---|
| **GitHub Copilot CLI** (`@github/copilot` 1.0.70) | ✅ ran | Substituted for the dead Gemini. Read all 9 plans + CONTEXT + VALIDATION + RESEARCH itself from a sandboxed copy (no repo write access). Cost **98.5 AI credits**, 3m18s, 1.1M input tokens. |
| **Ollama `gemma3:12b`** (local) | ✅ ran | Condensed prompt (23,634 prompt tokens — fit inside `num_ctx: 32768`, no silent truncation). |
| **Ollama `deepseek-r1:14b`** (local) | ✅ ran | Same condensed prompt (21,469 prompt tokens). Slowest run — 16m21s on partial CPU offload; most of its 891 output tokens went to internal reasoning, so the review is short. |
| **Gemini CLI** (`@google/gemini-cli` 0.33.1) | ❌ failed | `IneligibleTierError: UNSUPPORTED_CLIENT` — Google retired free-tier Gemini Code Assist for this client ("migrate to the Antigravity suite"). No `GEMINI_API_KEY` on this host, so there is no fallback path. **This reviewer needs re-auth or an API key before the next `/gsd-review`.** |
| **Claude CLI** | ⏭ skipped by design | `CLAUDE_CODE_ENTRYPOINT=cli` → self-review is not peer review (workflow rule). |
| codex, coderabbit, opencode, qwen, cursor | ❌ not installed | — |
| LM Studio, llama.cpp | ❌ no server | Only Ollama was listening. |

**Prompt note:** Copilot received the full artifact set as readable files. The two local models received a
condensed prompt (roadmap + locked decisions + each plan's frontmatter, objective, task names,
verification and success criteria — 79 KB / ~20k tokens) because the full set is 480 KB / ~120k tokens and
would have been silently truncated at the models' context window. Their reviews are therefore scoped to
plan *structure and intent*, not to every acceptance criterion.

---

## Copilot Review (GitHub Copilot CLI — read all nine plans directly)

### Phase 26 Plan-Set Peer Review

### 1. Summary

This is an unusually rigorous plan set — golden-render diffing, falsifiability-proven CI gates, a static/live split that keeps unattended plans from mutating shared state, and a genuine (not confirmatory) backup falsification recipe are all strong anti-false-green engineering. If executed exactly as written, it will very likely achieve the stated goal: a committed, buildable `k8s/local` overlay and closure of the verified breakage list, with staging/production protected from regression by the golden-render harness. However, the plan set carries one unverified assumption in a `k8s/base` edit that could silently change production RabbitMQ connection identity (26-01), a factual inconsistency in the closure plan's own baseline numbers (26-09 vs 26-CONTEXT/26-RESEARCH), and several places where the human-gated live rehearsals (26-07/26-08) assume shell state (sourced `.env`, exported context vars) that is not re-established in every verification snippet, which could make them non-executable exactly as written in a fresh shell. None of these are fatal, but each should be fixed before execution begins.

### 2. Strengths

- **Golden-render harness (26-01) established before any `k8s/base` edit**, giving every subsequent plan a mechanical, byte-level proof that staging/production are unaffected — directly defends against the "careless base edit is a production incident" risk called out in the task framing.
- **Falsifiability-driven CI gates (26-03, 26-04)**: every new invariant (INV-1..5, LOC-1..5) is required to be demonstrated RED against the exact defect it guards, not just green on the happy path — this is a materially higher bar than typical "add a test" plans and catches gate-quality regressions (reasonless/stale allowlist entries, weak regexes) before they ship.
- **Clean separation of authoring vs. executing shared-state mutations** (26-05 authors the secret/role/bucket bootstrap and proves only refuse-paths with zero mutation; 26-07 is the sole execution window, gated behind one consolidated human approval covering the *entire* mutation set with reversal commands). This is a genuinely good pattern for an unattended agent working against a potentially shared dev environment.
- **Backup falsification, not confirmation** (D-02, 26-07 Task 3): explicitly proves that the CronJob's own size-floor and `pg_restore --list` checks would pass on an empty dump, then requires a two-arm row-count comparison (app-role → 0 rows, backup-role → >0 rows). This is exactly the right skepticism for a backup "proof."
- **D-17 kube-dns NetworkPolicy fix** is a genuine, verified *production* defect (total DNS blackhole under an enforcing CNI) caught and fixed with a render-level regression test (INV-3) that closes a real gap in the existing `validate-networkpolicies.py` (which only parses raw files, never sees this class).
- **PIT-1/PIT-10 ingress admissibility handling** correctly scopes the TLS/snippet/rate-limit removal to the *local* overlay only, with an explicit acceptance criterion (`grep -c 'configuration-snippet' k8s/production` >= 1) proving production's security posture is untouched — the plans repeatedly and explicitly refuse to "weaken the cluster to make an apply pass."
- **Additive-only Keycloak realm and OIDC client-id fixes** (26-08): byte-identical base values, explicit diff-size assertions, and a mandatory verification that the redirect-URI array lost no existing entry.

### 3. Concerns

- **HIGH (26-01):** The `RABBITMQ_USERNAME` → `RABBITMQ_USER` rename in `k8s/base/core-java-deployment.yaml` changes what identity `spring.rabbitmq.username` (the *primary* AMQP connection, not just STOMP) resolves to in **staging and production**, not just locally. Today, because the injected env name is wrong, `spring.rabbitmq.username` silently falls back to its Spring default (`jtoye`) regardless of what `rabbitmq-credentials/username` actually contains. After the rename, the *actual secret value* takes effect for the first time in every environment. Nowhere in 26-01/26-CONTEXT/26-RESEARCH is the actual staging/production `rabbitmq-credentials/username` value verified to equal `jtoye` — and the golden-render diff **cannot** catch this, because secret values never appear in a kustomize render (only `secretKeyRef` names do, and `check-no-plaintext-secrets.sh` guarantees that). If any deployed environment's secret holds a different username than the Spring default, this "surgical" DEF-4 fix silently breaks the production message broker connection on next deploy, with zero static gate able to detect it. This deserves a source-control-adjacent mitigation (e.g., an explicit runbook step for staging/prod operators to confirm the secret value before the next rollout) that no plan currently specifies.
- **HIGH (26-09):** 26-09's own interfaces section states "Phase 25 shipped with `docs/metrics.json` total 1684," which is factually wrong per 26-CONTEXT.md's own corrections section and 26-RESEARCH.md (both state the real `docs/metrics.json` baseline was **1690**, and 1684 was *stale CLAUDE.md/AGENTS.md prose*, already fixed by 26-06). This is a real self-contradiction inside the artifact set: the closure plan restates the very error that 26-CONTEXT.md flagged as a correction. The instruction to "use the regenerated value, not a remembered number" partially defangs it, but a reviewer or executor skimming 26-09 in isolation could anchor on the wrong number when sanity-checking the `integrationTest` delta.
- **MEDIUM (26-07/26-08):** Several `<verify>`/acceptance-criteria bash snippets reference `$K8S_LOCAL_KUBE_CONTEXT`, `$DB_USER`, `$POSTGRES_USER`, etc. as bare shell variables without re-sourcing `.env` in that same snippet (only 26-07 Task 1's verify block does `set -a; . ./.env; set +a` explicitly). Since each command in this execution environment runs in a fresh process/shell, most of the Task 2/3 verification one-liners in 26-07 and all of 26-08's would fail with empty/unbound variables if run literally as written, rather than failing "as designed" at a guard. This risks a false-red (script fails for an environment reason, not a real defect) being misdiagnosed as a functional failure during the live rehearsal.
- **MEDIUM (26-02):** The 19 new `app-config` base values (SMTP host `email-smtp.eu-west-2.amazonaws.com`, S3 bucket/endpoint, Stripe Connect URLs) are asserted to be "prod-identical or repo-derived," but no plan verifies these values against actually-provisioned AWS resources (SES sending domain verification, S3 bucket existence/region, etc.). If any of these are wrong, the fix converts a *silent* no-op (localhost default, feature inert) into a *loud* runtime failure (SMTP auth error, S3 403) the first time staging/production actually exercises the newly-wired code path — worth a footnote in 26-02's SUMMARY confirming these were checked against real provisioned resources, not just "looks like the right shape."
- **MEDIUM (26-CONTEXT / all plans):** The phase boundary explicitly states "no application behaviour change" (26-CONTEXT.md `<domain>`), yet the delivered diff touches `application.yml` (STOMP fallback chain), the Keycloak realm template (redirect URI), and `frontend-deployment.yaml`'s OIDC client-id resolution — all of which are behavior-affecting for authentication and messaging, not purely "deploy layer." The context document does authorize each of these individually via locked decisions (D-05, DEF-5, the Blocker-1/2 discoveries in 26-08), so this isn't unauthorized scope creep, but the stated phase boundary and the actual footprint are in tension and should be reconciled in language, since a future reader auditing "in scope" vs "out of scope" from the boundary statement alone would be misled.
- **LOW (26-01):** Task 2's acceptance criterion `diff <(git show HEAD~1:k8s/goldens/production.yaml ...) ...` hardcodes a `HEAD~1` offset to isolate "this task's" golden diff. This is fragile to how commits are actually structured (squash vs. per-task commits) and could produce a false pass/fail purely from git history shape rather than content.
- **LOW (26-04):** LOC-1's assertion that `s3.public-url` stays `localhost:9000` (browser-reachable) while `s3.endpoint` is `host.minikube.internal:9000` (pod-reachable) is architecturally correct but entirely dependent on the developer's browser running on the same host as minikube — the runbook (26-06) should double down on stating this explicitly as a same-host-only assumption (it appears to, but worth confirming it's unambiguous given the split-horizon confusion this pattern already caused once with DEF-5).
- **LOW (26-05):** The `k8s_local_assert_context` server-host-IP fallback (`minikube profile list -o json` when `minikube ip` fails while Stopped) is a reasonable guard, but nothing revalidates that IP after `minikube start` in 26-07 in case the profile's IP changes between Stopped-state JSON and a fresh start (unlikely with the docker driver, but not asserted).

### 4. Suggestions

1. Before 26-01 Task 2 lands the `RABBITMQ_USERNAME`→`RABBITMQ_USER` rename, add an explicit acceptance step (or a note in the SUMMARY) confirming the actual `rabbitmq-credentials/username` secret value in every live environment that consumes `k8s/base` (staging, production) — even if that's just a manual doc note "confirmed via operator inspection: value is `jtoye`" — so the fix doesn't silently break broker auth on next deploy.
2. Correct 26-09's interfaces text so it states the actual `docs/metrics.json` baseline (1690) rather than repeating the stale 1684 figure, to avoid an executor anchoring on the wrong number during the final regression sweep.
3. Add `set -a; . ./.env; set +a` (or an equivalent context-establishing preamble) to every standalone bash snippet in 26-07/26-08's `<verify>` blocks and troubleshooting command lists, not just Task 1 of 26-07, so the live rehearsal commands are copy-paste executable in a fresh shell without silently failing on unbound variables.
4. Have 26-02 explicitly confirm (in its SUMMARY, as a named check) that the new SMTP/S3/Stripe base values correspond to real, currently-provisioned resources rather than plausible-looking placeholders, given they now activate real network calls in every environment that lacked them before.
5. Reconcile the "no application behaviour change" phrase in 26-CONTEXT.md's `<domain>` section with the actual application.yml/Keycloak-realm/frontend-env changes the locked decisions require — either soften the boundary statement or add a clarifying sentence that "behaviour" here means "for existing configured environments," since new environments/paths (STOMP fallback, split-horizon issuer) are genuinely new behavior.
6. Replace the `HEAD~1`-relative golden diff check in 26-01 Task 2 with a check anchored to a named pre-change golden artifact (e.g., a stashed copy) rather than a git-history offset, so the assertion is robust to commit granularity.

### 5. Risk Assessment

**Overall: MEDIUM.** The plan set's engineering discipline (golden renders, falsifiability-proven gates, static/live mutation separation, two-arm backup falsification) is well above average and substantially de-risks the stated goal of protecting staging/production while fixing local. The residual risk is concentrated in one specific, plausible production-impacting blind spot (the RabbitMQ username rename's dependence on an unverified live secret value, which no static gate can catch by construction) and in the executability of the human-gated live-rehearsal plans as literally written (missing `.env` sourcing in several verification snippets). Both are fixable with small, targeted additions rather than a redesign, which is why this lands at MEDIUM rather than HIGH — but the RabbitMQ concern in particular should be resolved (or explicitly accepted with eyes open) before 26-01 is executed against any environment that isn't purely local.




---

## Ollama `gemma3:12b` Review (local, condensed prompt)

Okay, here's a review of the Phase 26 implementation plans, structured as requested.

#### Review of Phase 26: Local-K8s Overlay + Verified Breakage Fixes

#### 1. Summary

This is an incredibly ambitious and tightly coupled plan. While the goal of replacing imperative patches with a k8s overlay and fixing the identified breakage is laudable, the sheer volume of changes, dependencies, and the reliance on a complex sequence of events (including live rehearsals) introduces significant risk. The plan's strength lies in its detailed documentation and attempt to proactively address potential failure points. However, the complexity also creates a high likelihood of unforeseen issues and dependencies that could derail the entire phase.  The plan's success hinges on the accuracy of the identified defects and the thoroughness of the verification steps.

#### 2. Strengths

*   **Detailed Documentation:** The plans are exceptionally detailed, outlining every step, dependency, and verification point. This level of detail is crucial for a complex undertaking.
*   **Proactive Verification:** The emphasis on static gates (check-env-contract.sh, check-render-invariants.sh) is excellent for preventing regressions and ensuring consistency.
*   **Focus on Reproducibility:** The effort to create a reproducible local environment and document the steps is commendable.
*   **Human-Gated Rehearsals:** The inclusion of human-gated rehearsals is a good safety net for critical, potentially disruptive changes.
*   **Clear Success Criteria:** Each plan has well-defined success criteria, making it easier to assess progress and identify failures.
*   **Addressing Security Concerns:** The plan explicitly addresses security concerns related to secrets, database roles, and network policies.
*   **Comprehensive Regression Suite:** The commitment to running a full regression suite at the end is vital for ensuring overall stability.

#### 3. Concerns

*   **HIGH (26-07):** The live rehearsal (26-07) is a single point of failure. The dependency on a specific state (compose app shutdown, `/etc/hosts` modifications) is brittle and prone to errors. The "human approval" step is a bottleneck and introduces subjectivity.  The plan assumes a very specific environment and user knowledge.
*   **MEDIUM (26-08):** The live vendor login and STOMP relay verification (26-08) are complex and dependent on multiple services working correctly. A failure in any of these services could mask underlying issues.
*   **MEDIUM (26-04):** The sheer number of changes introduced in Wave 4 (k8s/local overlay) is significant.  The risk of unintended side effects is high.  The plan assumes a high degree of confidence in the correctness of the overlay's configuration.
*   **MEDIUM (26-05):** The bootstrap scripts (k8s-local-guards.sh, k8s-local-secrets.sh, k8s-local-up.sh) introduce complexity and potential for errors. The reliance on `.env` files for secrets is a potential security risk if not handled carefully.
*   **LOW (26-06):** The documentation updates (k8s/LOCAL.md, etc.) are crucial but could be overlooked.  Incomplete or inaccurate documentation could lead to confusion and errors.
*   **LOW (All Plans):** The plan's reliance on a very specific sequence of waves is a potential risk. Any delay or failure in an earlier wave could cascade and impact subsequent waves. The plan assumes perfect execution and no unexpected issues.
*   **LOW (26-09):** The final validation step (26-09) relies on a full regression suite.  A comprehensive suite is necessary, but it's not a guarantee of complete stability.  There's a risk of undetected issues.

#### 4. Suggestions

*   **Break Down Rehearsal (26-07):**  Consider breaking down the live rehearsal into smaller, more manageable steps with intermediate verification points.
*   **Automate Rehearsal Steps:**  Where possible, automate the steps in the live rehearsal to reduce the risk of human error.
*   **Improve Error Handling:**  Add more robust error handling and logging to the bootstrap scripts to facilitate debugging.
*   **Secrets Management:** Explore more secure secrets management solutions beyond `.env` files. Consider using a dedicated secrets management tool.
*   **Modularize Overlay:**  Consider breaking down the `k8s/local` overlay into smaller, more modular components to reduce complexity and improve maintainability.
*   **Stricter Dependency Management:**  Explicitly document and enforce dependencies between waves to minimize the risk of cascading failures.
*   **Dry Run Validation:** Implement more extensive dry-run validation for all changes before applying them to the live environment.
*   **Rollback Strategy:** Define a clear and tested rollback strategy in case of failures.

#### 5. Risk Assessment

**MEDIUM**

The complexity of the plan, the reliance on a specific sequence of events, and the human-gated rehearsals introduce significant risk. While the detailed documentation and proactive verification steps mitigate some of these risks, the potential for unforeseen issues remains high. The success of this phase is highly dependent on the accuracy of the identified defects, the thoroughness of the verification steps, and the ability to execute the plan flawlessly.  A single point of failure in the live rehearsal (26-07) could derail the entire phase.


---

## Ollama `deepseek-r1:14b` Review (local, condensed prompt)

### Review of Phase 26 Implementation Plans

---

##### **Summary**
This phase aims to replace imperative patches with a buildable `k8s/local` overlay and fix verified Kubernetes breakages. The plans are well-structured, addressing dependencies and security concerns. However, there are risks related to dependency chains, potential security vulnerabilities, and incomplete validations that require careful execution.

---

##### **Strengths**
1. Clear wave dependencies ensure sequential execution.
2. Comprehensive address of security issues, such as using NOSUPERUSER roles and avoiding hardcoded values.
3. Detailed verification steps with bash commands for each plan.
4. Focus on replacing localhost references with host.minikube.internal to improve cross-environment compatibility.

---

##### **Concerns**
- **HIGH**: Dependency chain complexity could delay execution if one plan fails.
- **MEDIUM**: Risk of leftover hardcoded values or incorrect network policy selectors.
- **LOW**: Potential ambiguity in human-gated live rehearsal steps (Waves 7 and 8).

---

##### **Suggestions**
1. Conduct a thorough review of all dependencies to identify potential bottlenecks.
2. Perform additional checks for any remaining localhost references after implementation.
3. Ensure clear documentation for the human-gated steps in Waves 7 and 8.

---

##### **Risk Assessment**
**Overall Risk: HIGH**

The phase involves critical changes to Kubernetes configurations, security settings, and network policies. The complexity of dependencies and potential vulnerabilities make this a high-risk phase. Thorough testing and verification are essential to avoid introducing new issues or compromising security.

---

## Consensus Summary

Three reviewers ran (a fourth, Gemini, could not — see *Reviewer availability*). All three agree the plan
set is well-structured and will achieve the phase goal, and all three name the nine-wave dependency chain
as the top structural risk. They split on the overall rating and, more importantly, on specificity: only
Copilot produced findings concrete enough to act on. The two local models reviewed structure and intent
(they received the condensed prompt) and their concerns are largely restatements of risks the plans
already dispositioned.

### Agreed strengths (raised by 2+ reviewers)

- **The static gates are the phase's real deliverable.** All three singled out
  `check-env-contract.sh` / `check-render-invariants.sh` / `render-golden.sh` — the thing that stops DEF-4
  and DEF-6 recurring, rather than the one-time fixes.
- **Security is treated as first-class** (all three): the NOSUPERUSER app role, the BYPASSRLS backup role,
  NetworkPolicy selectors, config-injection over hardcoded values, and the deliberate local TLS
  degradation are all named and dispositioned rather than discovered late.
- **Verification is concrete and per-plan** (all three): explicit bash assertions and success criteria make
  both progress and failure assessable.
- **Human-gated live rehearsals are the right safety design** for the mutating steps (copilot, gemma3 —
  though gemma3 also counts the gate itself as a risk, see Divergent views).
- **The `host.minikube.internal` shim strategy** is the right seam for cross-environment compatibility
  (deepseek, copilot).

### Agreed concerns (raised by 2+ reviewers) — highest priority

- **The nine-wave strict serialisation is a single-thread dependency chain** — raised by **all three**
  (deepseek: HIGH; gemma3: LOW; copilot: implied in its Risk Assessment). There is no parallel path and no
  partial-value checkpoint before Wave 7, so any stalled wave stalls everything downstream.
  *Adjudication:* the serialisation is **necessary, not incidental** — 26-04 extends the script 26-03
  creates, 26-05 derives its secret inventory from 26-04's render, 26-06 documents what 26-05 shipped, and
  single-writer file ownership (`ci-cd.yaml`, `docs/metrics.json`, `check-render-invariants.sh`) is what
  keeps the phase merge-conflict-free. The real cost is wall-clock, not correctness. No change recommended.
- **The live rehearsal plans (26-07, 26-08) are the concentration of risk** — all three, for different
  reasons: gemma3 says the required host state is brittle, deepseek says the human steps are ambiguous,
  copilot says several verification snippets are not literally executable. *Copilot's version is the
  actionable one and is CONFIRMED below.*

### Divergent views (worth investigating)

- **Overall rating splits 2-1: MEDIUM (copilot, gemma3) vs HIGH (deepseek).** deepseek's HIGH rests on
  "critical changes to Kubernetes configurations, security settings, and network policies" plus dependency
  complexity — i.e. the *category* of change, not a specific defect it found. Copilot rates the same
  material MEDIUM *because* of the engineering discipline (golden renders, falsifiability-proven gates,
  static/live mutation separation). Weighing specificity, **MEDIUM is the better-supported verdict**, with
  the one HIGH exception Copilot did find (26-01's RabbitMQ rename).
- **Is the plan set's density a mitigation or a risk?** copilot reads the detail as "unusually rigorous …
  a materially higher bar than typical plans." gemma3 reads the same density as "incredibly ambitious and
  tightly coupled … high likelihood of unforeseen issues." Both still land on MEDIUM, from opposite
  directions.
- **Is there a rollback strategy?** gemma3 asks for one; it appears not to have found the one that exists.
  26-07 Task 1 itemises a reversal command for every one of its six mutations (`docker compose start`,
  `minikube stop`, `DROP ROLE jtoye_backup`, `mc rb`, `kubectl delete -k k8s/local`, `DROP DATABASE`), and
  26-05 explicitly preserves `deploy.sh`'s `rollout undo`. The genuine gap is not the *documentation* of a
  rollback but the absence of a plan step that *executes* the restore — see Adjudication H.
- **Secrets handling.** gemma3 flags `.env`-sourced secrets as "a potential security risk" and suggests a
  dedicated secrets manager. Already a recorded, deliberate decision: `PROJECT.md:141` locks plain k8s
  Secrets for this milestone and sealed-secrets/external-secrets is an explicit `<deferred>` item in
  26-CONTEXT.md. Copilot did not raise it. Treat as already-adjudicated.
- **deepseek's "risk of leftover hardcoded values or incorrect network policy selectors"** is precisely
  what INV-1 (no hardcoded 5432), INV-3 (kube-dns selector purity on the *render*), INV-4 (no
  localhost/minioadmin literals) and LOC-1 (each shim asserted by name) exist to prevent — each required
  to be proven RED against the defect it pins. The concern is valid in the abstract and already closed by
  design; no action.

---

## Adjudication (synthesiser's verification pass)

Reviewer claims were checked against the actual repository rather than passed through. Findings below are
either **CONFIRMED** (verified against source/live state) or **REJECTED** (tested and found not to hold).
Items A, B, C, D, H, I, J, K and L were found during this pass and appear in none of the three reviews.

### VALIDATED — the plans' stated interface facts hold up under spot-check

Before weighing the findings it is worth recording what *didn't* break. Every `<interfaces>` claim I
spot-checked against the repo was accurate:

| Plan claim | Verified |
|---|---|
| `k8s/base/core-java-deployment.yaml` injects exactly 23 env names (26-03 INV parser input) | 23 ✓ |
| `check-connection-math.sh` parses `DB_POOL_SIZE` with `awk '/name: DB_POOL_SIZE/{getline; if ($1=="value:")…}'`, so an inserted comment breaks it | exact, `check-connection-math.sh:75`; adjacency at `core-java-deployment.yaml:95-96` ✓ |
| 6 NetworkPolicies render in the local build (D-11) | 6 policy files under `k8s/base/networkpolicies/` ✓ |
| `core-api`'s `redirectUris` are the four localhost entries only (26-08 Blocker 1) | exact, `realm-export.template.json:684` ✓ |
| `--project=mobile` exists for the DEF-5 login proof | `frontend/playwright.config.ts:19` ✓ |
| The local ConfigMap patch may only override keys that already exist in base | all 22 keys in 26-04's table are either in base today or added by 26-02 in Wave 2, which runs first ✓ |
| `scripts/deploy.sh` has a phantom `dev` target (26-05 / D-14) | regex `^(dev\|staging\|production)$` at `deploy.sh:27`, and `k8s/dev` does not exist ✓ |

This matters: the phase's mechanics were genuinely reproduced during research rather than asserted, which
is why the findings below are refinements rather than redesigns.

### CONFIRMED — copilot's HIGH (26-01) is real and is the most important finding in this review

`core-java/src/main/resources/application.yml:74` reads `username: ${RABBITMQ_USER:jtoye}`, while
`k8s/base/core-java-deployment.yaml:140` injects **`RABBITMQ_USERNAME`** from
`rabbitmq-credentials/username`. `RABBITMQ_PASSWORD` *is* correctly named and *is* read. So today every
k8s environment connects its primary AMQP pool as the literal string `jtoye` while using the secret's
real password; after 26-01's rename it connects as the secret's `username` value **for the first time**.
No static gate can catch a mismatch — secret values never appear in a kustomize render, and
`check-no-plaintext-secrets.sh` guarantees they never will.

Mitigating inference copilot did not make: for AMQP to be working in staging/production *today*, the
secret's password must already authenticate the broker user `jtoye`, which makes
`username: jtoye` likely. That lowers the probability but not the impact, and it is an inference — the
value is out-of-band and unverifiable from this repo. **Keep as HIGH; add the one-line operator check
copilot suggests before 26-01 lands.**

### CONFIRMED — A. 26-02 Task 1's env-count acceptance criterion is arithmetically wrong

The criterion says the `- name:` count "returns 42 (23 pre-existing + 19 configmap-sourced + …)".
Verified: `grep -cE '^\s+- name: [A-Z0-9_]+\s*$' k8s/base/core-java-deployment.yaml` → **23** today, and
the task adds 19 ConfigMap-sourced plus 7 Secret-sourced entries = **49**, not 42. The criterion
self-hedges ("verify the exact arithmetic against your actual additions"), so it is survivable, but an
executor anchoring on 42 would either report a spurious failure or delete entries to reach it.
**Severity: LOW→MEDIUM. Fix: state 49, or drop the number and keep only the "23 + what you added" rule.**

### CONFIRMED — B. The dangling `keycloak` ingress backend is fixed only locally, leaving the production defect

`k8s/base/ingress.yaml:74-83` routes `auth.jtoye.co.uk` → Service `keycloak` port 8080, and **no
`keycloak` Service exists anywhere in `k8s/base`**. 26-04 Task 2 notices this and removes it from the
*local* render only (because `rules:` replaces the list, P-4), describing it as "a deliberate, desirable
side effect" — leaving staging and production routing a host to a non-existent backend (nginx 503).
This directly contradicts the phase's own D-15 doctrine: fix the base defect rather than papering over it
locally. **Severity: MEDIUM. Fix: either remove/repoint the rule in `k8s/base` (with a golden diff) or
record it as an explicit deferred item with the reason.**

### CONFIRMED — C. 26-07's backup-bucket 403 probe cannot distinguish "non-public" from "non-existent"

Tested live against the running host MinIO: `curl -s -o /dev/null -w '%{http_code}'
http://127.0.0.1:9000/jtoye-db-backups/` returns **403 right now**, before the bucket exists. So the
criterion "an unauthenticated read returns 403" is satisfied by a bucket that was never created — the
exact false-green class this phase is built to avoid. It is *rescued* only by the separate `mc ls`
existence assertion in the same task. **Severity: LOW→MEDIUM. Fix: state the ordering dependency
explicitly (existence first, then 403), or use the object-level probe Task 3 already specifies.**

### REJECTED — the control half of that same probe is sound

I expected `mc anonymous set download` to grant `s3:GetObject` only, which would make the "200 against
`jtoye-images`" control fail for a benign reason. Tested: the bucket-level anonymous GET on
`jtoye-images` returns **200**, and an object-level GET also returns 200. The control is valid. Not a
finding.

### REJECTED — the realm re-import does not destroy the login credential 26-08 depends on

`kc.sh import --override true` replaces the realm, so I checked whether `admin-user` survives.
It does: `infra/keycloak/realm-export.template.json:400` defines `admin-user` with its `tenant_id`
attribute (line 406), and `configure-keycloak.sh` creates only `tenant-a-user` / `tenant-b-user`, and only
when absent — so the KC24 "admin-API creation strips unmanaged `tenant_id`" trap does not fire on the
account 26-08's DEF-5 proof uses. Not a finding. Recorded so it is not re-raised.

### CONFIRMED — 26-08's Blocker 1 diagnosis is accurate

`realm-export.template.json:684` lists `core-api`'s `redirectUris` as exactly
`http://localhost:8080/*`, `:3000/*`, `:3100/*`, `:9090/*` — none of which matches a NextAuth callback on
`app.jtoye.local`. The plan's premise checks out.

### CONFIRMED — D. 26-08 Task 3's automated verify command is not runnable as written

`npx --prefix frontend playwright test --project=mobile e2e/dashboard-mobile.spec.ts` runs with cwd at the
repo root, where there is no `playwright.config.ts`, so Playwright will not resolve the config or the
`mobile` project. It also omits `E2E_VENDOR_PASSWORD`, and
`frontend/e2e/dashboard-mobile.spec.ts:37` defaults it to `password123` — so the command would fail the
login for an environment reason during the human-gated step. (Verified as sound: `--project=mobile` does
exist, `frontend/playwright.config.ts:19`.) 26-05 Task 3 gets this right with
`cd frontend && npx playwright test`. **Severity: LOW. Fix: mirror 26-05's form and pass the password.**

### CONFIRMED — copilot's MEDIUM on unsourced shell variables in 26-07/26-08

Only 26-07 Task 1's `<verify>` block establishes `.env` (`set -a; . ./.env; set +a`). 26-07 Task 2/3 and
all of 26-08's snippets reference `$K8S_LOCAL_KUBE_CONTEXT`, `$POSTGRES_USER`, `$DB_USER`,
`$K8S_LOCAL_MINIO_PORT` bare. Each verification runs in a fresh shell, so these fail unbound — a false-red
during the one part of the phase a human is watching. **Confirmed; adopt copilot's suggestion 3.**

### CONFIRMED — H. No plan restores the environment it changed

Grepped all of 26-07/26-08/26-09: none executes a teardown. 26-07 Task 1 only *lists* the reversal
commands as information for the human's approval, and 26-06 writes a Teardown *section* in the runbook —
but no plan step brings the compose app containers back or stops the profile. The phase therefore ends
with the project's **canonical local dev/E2E runtime down** (CLAUDE.md: compose is canonical; k8s is the
deploy target) and a minikube cluster running. **Severity: MEDIUM (operational, Incremental Betterment).
Fix: add a final task to 26-09 that restores compose apps, records the state, and leaves the profile
stopped or explicitly running by choice.**

### CONFIRMED — I. `HEAD~1`-relative golden assertions are fragile, and 26-01's has a false-green fallback

Four plans (26-01, 26-02, 26-04, 26-08) assert the golden diff relative to `HEAD~1`, which depends on the
commit granularity the executor happens to choose (26-01 Task 2 alone specifies "one commit-per-fix
sequence, then one reviewed golden regeneration"). Worse, 26-01's form is
`diff <(git show HEAD~1:k8s/goldens/production.yaml 2>/dev/null || cat k8s/goldens/production.yaml) k8s/goldens/production.yaml`
— when the `git show` fails, the fallback compares the file **to itself**, the diff is empty, and the
assertion passes vacuously. **Severity: MEDIUM for 26-01, LOW for the others. Fix: anchor to a named
pre-change copy (copilot's suggestion 6) and drop the `|| cat` fallback.**

### CONFIRMED — J. 26-05 invokes the real mutating bootstrap to prove it refuses

26-05 is `autonomous: true` and its own objective forbids creating the BYPASSRLS role, yet one acceptance
criterion runs `bash scripts/k8s-local-secrets.sh` expecting a guard refusal. If the guard has a bug, an
unattended plan creates an RLS-bypassing role on the shared dev Postgres — precisely what 26-07's
checkpoint exists to authorise. It is mitigated (the guard-order source assertion is listed first, and
the four nothing-mutated post-checks would catch it after the fact), but detection-after-the-fact is not
prevention. **Severity: MEDIUM. Fix: keep the source-level guard-order assertion and the function-level
refusal probes; move the whole-script invocation into 26-07 behind the checkpoint.**

### CONFIRMED — L. All three deferred-item writes target a path that does not exist and breaks the repo convention

26-02, 26-06 and 26-08 each append deferrals to **`.planning/deferred-items.md`**, and 26-02 Task 2's
`<read_first>` describes it as "the existing deferred-item entry format". That file does not exist. The
established convention in this repo is **per-phase**: `.planning/phases/22-notifications-comms/deferred-items.md`,
`.planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/deferred-items.md`, and the same under
every archived milestone — and Phase 23's plans path it explicitly as
`.planning/phases/23-…/deferred-items.md`. Phase 26's own directory has none yet. As written, the phase's
deferrals (the CI `build-args` gap, Calico, the customer realm, the relay-spec rework, the `emptyDir` PIT-5
fix, the allowlisted env omissions) would land in a new top-level file outside the per-phase structure the
milestone audit and `/gsd:review-backlog` read. **Severity: LOW→MEDIUM. Fix: repath all three to
`.planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md` and have 26-02 create it
with the format Phase 23 used, rather than describing it as existing.**

### NOTED — K. The phase begins with an unapproved validation contract

`26-VALIDATION.md` is `status: draft`, `nyquist_compliant: false`, `wave_0_complete: false`,
**Approval: pending**. 26-09 reconciles all of it at the end, which is the intended design, but nothing
approves the contract before Wave 1 executes against it.

### NOTED — copilot's MEDIUM on the phase-boundary wording is fair

26-CONTEXT.md `<domain>` says "no application behaviour change", yet the phase edits `application.yml`
(the STOMP chain), the Keycloak realm template, and the frontend's OIDC client-id resolution. Each is
individually authorised by a locked decision (D-05, DEF-5, 26-08's Blockers 1-2), so this is not
unauthorised scope creep — but the boundary sentence and the actual footprint are in tension and a future
auditor reading only the boundary would be misled.

---

## Recommended action before executing

Ordered by what would cost most if skipped:

1. **26-01** — add the operator confirmation of the live `rabbitmq-credentials/username` value in staging
   and production before the `RABBITMQ_USERNAME` → `RABBITMQ_USER` rename lands (CONFIRMED HIGH).
2. **26-01** — remove the `|| cat <same-file>` fallback from the golden-diff criterion and anchor the
   comparison to a named pre-change copy; apply the same change in 26-02/26-04/26-08 (Adjudication I).
3. **26-07 / 26-08** — prefix every standalone verification snippet with `set -a; . ./.env; set +a`
   (copilot suggestion 3), and fix 26-08 Task 3's Playwright command (Adjudication D).
4. **26-09** — add a final restore task: bring the compose app containers back and record the end state
   (Adjudication H).
5. **26-05** — move the whole-script `k8s-local-secrets.sh` invocation to 26-07, behind the checkpoint
   (Adjudication J).
6. **26-02 / 26-06 / 26-08** — repath the deferred-item writes to the per-phase
   `.planning/phases/26-…/deferred-items.md` and have the first writer create it (Adjudication L).
7. **26-04** — decide the dangling `keycloak` ingress backend in `k8s/base`: fix it in base or record it
   as a deferred item with a reason (Adjudication B).
8. **26-02** — correct the env-count criterion to 49 (Adjudication A); confirm in the SUMMARY that the new
   SMTP/S3/Stripe base values match real provisioned resources (copilot suggestion 4).
9. **26-07** — state the existence-before-403 ordering for the backup bucket probe (Adjudication C).
10. **26-09** — correct the "1684" baseline to 1690 (copilot's HIGH; adjudicated **LOW** here, since the
   same paragraph instructs the executor to use the regenerated value).
11. **26-CONTEXT** — soften the "no application behaviour change" boundary sentence (copilot suggestion 5).

None of these require replanning. Items 1-5 are worth applying before Wave 1; 6-11 can be folded into the
plans they belong to.
