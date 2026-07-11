---
phase: quick-260711-bej
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingPropertiesTest.java
  - docs/metrics.json
  - README.md
  - CLAUDE.md
  - docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md
  - docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md
  - .planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md
autonomous: true
requirements:
  - "GH-178-item1-auto-approve-stance"
  - "GH-102-stripe-money-flow-decision"
  - "UAT-18-item5-auto-approve-production-decision"

must_haves:
  truths:
    - "A WHITE_LABEL onboarding with all mandatory gates green auto-approves (PENDING_APPROVAL -> APPROVED) even when the global onboarding.auto-approve flag is false"
    - "A MARKETPLACE onboarding with all mandatory gates green halts at PENDING_APPROVAL when the global onboarding.auto-approve flag is false"
    - "Setting global onboarding.auto-approve=true still force-approves BOTH models (backward-compatible override)"
    - "Both product decisions (hybrid auto-approve; Stripe Connect keyed to model) are captured in a versioned ADR document"
    - "VENDOR_ONBOARDING_STATE_MODEL.md section 9 open-decision item 1 reads DECIDED with the hybrid stance"
    - "18-HUMAN-UAT.md item 5 reads PASS/decided and the Summary shows passed 5 / pending 0"
    - "GitHub issues #178 and #102 each carry a comment recording the decision and both remain OPEN"
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java"
      provides: "auto-approve-models config + autoApprovesModel(model) helper, default [WHITE_LABEL]"
      contains: "autoApprovesModel"
    - path: "core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java"
      provides: "model-aware auto-approve decision in runAndRecompute"
      contains: "autoApprovesModel"
    - path: "core-java/src/main/resources/application.yml"
      provides: "onboarding.auto-approve-models key + updated auto-approve comment"
      contains: "auto-approve-models"
    - path: "docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md"
      provides: "ADR recording both decisions (context/options/decision/consequences)"
      min_lines: 40
    - path: "docs/metrics.json"
      provides: "test counts re-synced after new @Test methods"
      contains: "java_test_methods"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java"
      to: "OnboardingProperties.autoApprovesModel"
      via: "isAutoApprove() OR autoApprovesModel(onboarding.getModel())"
      pattern: "autoApprovesModel\\(.*getModel"
    - from: "core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java"
      to: "OnboardingModel.WHITE_LABEL"
      via: "autoApproveModels default list"
      pattern: "WHITE_LABEL"
---

<objective>
Record two FINAL product decisions made by the developer on 2026-07-11 and apply the
one small code change that Decision 1 requires.

- **Decision 1 — ONBOARDING_AUTO_APPROVE stance (#178 item 1 / UAT item 5):** Hybrid
  by model. WHITE_LABEL onboardings auto-approve when all compliance gates are green;
  MARKETPLACE onboardings always require human approval (the admin approve/reject queue
  stays deferred to #178 slice 2). The `onboarding.auto-approve` flag stays as a global
  force-on override; when it is false, per-model policy applies (WHITE_LABEL auto,
  MARKETPLACE manual).
- **Decision 2 — Stripe money-flow (#102):** Stripe Connect keyed to onboarding model —
  destination charges for MARKETPLACE, direct charges + application fee for WHITE_LABEL.
  **No Stripe code in this task** — decision is recorded only.

Purpose: unblock the last open item from the Phase 18 vendor-onboarding UAT and pin two
architectural decisions before the next planned phase implements the Stripe money flow.
Output: a model-aware auto-approve code change with tests; an ADR; doc + UAT status
updates; and decision comments on GitHub issues #178 and #102 (neither closed).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md

<interfaces>
<!-- Contracts the executor needs. Extracted from the codebase — no exploration required. -->

The auto-approve decision currently lives in GateChainRunner.runAndRecompute (core-java/.../onboarding/GateChainRunner.java, ~line 161-179):

  if (allPassed) {
      vendorOnboardingService.transition(onboardingId, OnboardingEvent.GATES_PASSED);
      if (onboardingProperties.isAutoApprove()) {          // <-- becomes model-aware
          try {
              vendorOnboardingService.transition(onboardingId, OnboardingEvent.APPROVE);
          } catch (InvalidStateTransitionException e) {      // WR-01 veto-swallow — KEEP
              log.warn("Auto-approve vetoed for onboarding {}: {}", onboardingId, e.getMessage());
          }
      }
  } else if (anyFailed) { ... }

OnboardingProperties (core-java/.../onboarding/OnboardingProperties.java):
  - @Component @ConfigurationProperties(prefix = "onboarding")
  - private boolean autoApprove = false;  + isAutoApprove()/setAutoApprove()
  - nested Fhrs + CompaniesHouse static classes
  - toString() is redacted (masks CH api key)

OnboardingModel enum (core-java/.../onboarding/OnboardingModel.java): MARKETPLACE, WHITE_LABEL
VendorOnboarding entity exposes getModel():OnboardingModel (already used in VendorOnboardingService.toDto).

application.yml onboarding block (core-java/src/main/resources/application.yml:193-202):
  onboarding:
    auto-approve: ${ONBOARDING_AUTO_APPROVE:false}
    menu-minimum: ${ONBOARDING_MENU_MINIMUM:1}
    fhrs: { base-url, min-rating, api-version }
    companies-house: { base-url, api-key }
</interfaces>

<pitfalls>
<!-- Load-bearing constraints. Ignoring #1 silently breaks the Phase 18 E2E test. -->

1. **Mockito @SpyBean self-invocation trap (HARD REQUIREMENT).**
   `VendorOnboardingEndToEndIntegrationTest` uses `@SpyBean OnboardingProperties` and stubs
   `when(onboardingProperties.isAutoApprove()).thenReturn(true|false)`. Mockito spies do NOT
   intercept internal `this.`-self-calls. Therefore:
   - The per-model check MUST be a SEPARATE public method on OnboardingProperties
     (e.g. `autoApprovesModel(OnboardingModel model)`) that does NOT call `isAutoApprove()` internally.
   - GateChainRunner MUST evaluate the decision as TWO external calls on the bean:
     `onboardingProperties.isAutoApprove() || onboardingProperties.autoApprovesModel(onboarding.getModel())`.
   Do NOT collapse this into a single `shouldAutoApprove()` that internally reads `isAutoApprove()` —
   that would make the E2E spy stub on `isAutoApprove()` a no-op and break the test.

2. **Existing integration tests all use MARKETPLACE** (VendorOnboardingEndToEndIntegrationTest,
   OnboardingSubmitIntegrationTest, OnboardingResubmitIntegrationTest, OnboardingGoLiveIntegrationTest).
   Under the new default (MARKETPLACE = manual) they continue to halt at PENDING_APPROVAL exactly as
   before — the change is net-additive for WHITE_LABEL only. Do not modify those tests.

3. **No new Flyway migration.** V44 is reserved for another task. This change is config + logic only.

4. **docs-freshness CI gate.** Adding Java @Test methods changes `docs/metrics.json`
   (`java_test_methods`, `total_logical_invocations`). Regenerate with `scripts/docs-freshness.sh --write`
   and update the three prose count spots (README.md line 7 badge, README.md line 231, CLAUDE.md line 15)
   in the SAME commit as the code.
</pitfalls>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Model-aware auto-approve (Decision 1) + tests + metrics sync</name>
  <files>core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java, core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java, core-java/src/main/resources/application.yml, core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java, core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingPropertiesTest.java, docs/metrics.json, README.md, CLAUDE.md</files>
  <behavior>
    - GateChainRunnerTest (real `new OnboardingProperties()`, no spy): with global autoApprove=false and onboarding model WHITE_LABEL and all mandatory gates PASSED -> both GATES_PASSED and APPROVE fire.
    - GateChainRunnerTest: with global autoApprove=false and model MARKETPLACE and all gates PASSED -> GATES_PASSED fires, APPROVE never fires (stays PENDING_APPROVAL).
    - GateChainRunnerTest: existing `recomputeAutoApproves` (global autoApprove=true) still fires APPROVE regardless of model (backward compat) — leave it green.
    - OnboardingPropertiesTest: default `getAutoApproveModels()` == [WHITE_LABEL]; `autoApprovesModel(WHITE_LABEL)`==true; `autoApprovesModel(MARKETPLACE)`==false; `autoApprovesModel(null)`==false.
  </behavior>
  <action>
    Implement Decision 1 (hybrid auto-approve keyed to OnboardingModel) per D-01.

    1. OnboardingProperties: add `private List&lt;OnboardingModel&gt; autoApproveModels = new ArrayList&lt;&gt;(List.of(OnboardingModel.WHITE_LABEL));`
       with getter/setter (mutable list so Spring relaxed-binding of a comma-separated env value works, mirroring existing house List binding). Add a public method
       `public boolean autoApprovesModel(OnboardingModel model) { return model != null &amp;&amp; autoApproveModels.contains(model); }`.
       Keep the existing `autoApprove` boolean + isAutoApprove()/setAutoApprove() UNCHANGED (it is now documented as the global force-on override). Extend the redacted toString() to include `autoApproveModels` (non-secret, safe to log). Do NOT add a combined `shouldAutoApprove()` that self-calls `isAutoApprove()` (see pitfall #1).
    2. GateChainRunner.runAndRecompute: replace `if (onboardingProperties.isAutoApprove())` with a local
       `boolean autoApprove = onboardingProperties.isAutoApprove() || onboardingProperties.autoApprovesModel(onboarding.getModel());`
       then `if (autoApprove) { ... }`. Keep the WR-01 try/catch veto-swallow around the APPROVE transition EXACTLY as-is. Update the recompute Javadoc to describe the model-aware policy (global force-on OR per-model default WHITE_LABEL).
    3. application.yml (onboarding block ~line 194): add `auto-approve-models: ${ONBOARDING_AUTO_APPROVE_MODELS:WHITE_LABEL}` under `onboarding:`, and change the `auto-approve` comment to note it is a GLOBAL FORCE-ON override (when true, every model auto-approves; when false, per-model policy applies). Add a short inline comment on the new key: default WHITE_LABEL auto / MARKETPLACE manual (per #178 item 1).
    4. Tests: add the model-aware cases to GateChainRunnerTest (set `onboarding.setModel(...)` on the stubbed-findById instance before running) and the property-default cases to OnboardingPropertiesTest per the &lt;behavior&gt; block. Follow the existing test style (JUnit5 + Mockito + AssertJ, @DisplayName).
    5. Metrics sync: run `scripts/docs-freshness.sh --write`, read the printed `total_logical_invocations` / `java_test_methods`, then update the three prose count spots to the new totals — README.md line ~7 (badge `tests-NNN%20logical%20invocations`), README.md line ~231 (`Total: NNN logical test invocations`), and CLAUDE.md line ~15 (bump both `NNN logical invocations` and `NNN Java @Test methods`). Stage docs/metrics.json + README.md + CLAUDE.md with the code in ONE commit.

    Commit atomically: `feat(onboarding): model-aware auto-approve — WHITE_LABEL auto, MARKETPLACE manual (#178)`.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 &amp;&amp; ./gradlew :core-java:test --tests 'uk.jtoye.core.onboarding.GateChainRunnerTest' --tests 'uk.jtoye.core.onboarding.OnboardingPropertiesTest' &amp;&amp; bash scripts/docs-freshness.sh</automated>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 &amp;&amp; ./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.onboarding.VendorOnboardingEndToEndIntegrationTest' --tests 'uk.jtoye.core.onboarding.OnboardingSubmitIntegrationTest'</automated>
  </verify>
  <done>WHITE_LABEL auto-approves and MARKETPLACE halts at PENDING_APPROVAL under the default config; global auto-approve=true still force-approves both; unit + onboarding integration tests green; docs-freshness check passes; metrics.json + README + CLAUDE counts consistent; one atomic commit.</done>
</task>

<task type="auto">
  <name>Task 2: Decision records — ADR + section 9 + UAT item 5</name>
  <files>docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md, docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md, .planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md</files>
  <action>
    Capture BOTH decisions and flip the open-decision / UAT status.

    1. Create `docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md`
       (establishes an ADR directory — docs/ currently has no ADR convention, so this is the seed).
       Use standard ADR sections: Title, Status (Accepted, 2026-07-11), Context, Decision, Options
       Considered, Consequences. Cover TWO decisions:
       - **Decision 1 — Onboarding approval (hybrid by model):** context = today nothing moves an
         onboarding out of PENDING_APPROVAL except the auto-approve recompute, and the admin queue is
         deferred to #178 slice 2. Options considered: (a) global auto-approve on for all, (b) always
         manual, (c) hybrid keyed to model. Decision = (c): WHITE_LABEL auto-approves when gates green;
         MARKETPLACE always manual; `onboarding.auto-approve` stays as global force-on override;
         per-model list `onboarding.auto-approve-models` defaults to WHITE_LABEL. Consequences: MARKETPLACE
         cannot reach LIVE until the admin approve/reject queue ships (#178 slice 2 still required); the
         APPROVE and GO_LIVE guards continue to enforce all mandatory gates so auto-approve is not a
         compliance bypass.
       - **Decision 2 — Stripe money flow (Connect keyed to model, #102):** context = J'Toye must never
         hold customer money for white-label vendors. Options: single flow vs Connect. Decision =
         Stripe Connect: destination charges for MARKETPLACE (platform = merchant of record, funds routed
         to vendor's connected account), direct charges + application fee for WHITE_LABEL (vendor = merchant
         of record). Consequences: destination-charge flow implemented FIRST in a future planned phase;
         NO Stripe implementation code in this task — decision recorded only.
    2. VENDOR_ONBOARDING_STATE_MODEL.md section 9 item 1 (line ~412): rewrite from an open question to a
       DECIDED entry — mark it **DECIDED (2026-07-11): hybrid by model** — WHITE_LABEL auto-approves on
       green gates, MARKETPLACE always manual (admin queue = #178 slice 2); reference ADR-0001. Leave the
       other section-9 items untouched.
    3. 18-HUMAN-UAT.md item 5: change `result: [pending — developer decision]` to
       `result: PASS (decided 2026-07-11)` with a one-line summary of the hybrid stance + ADR-0001 ref,
       and note the admin approve/reject queue for MARKETPLACE remains tracked in #178 slice 2. Update the
       `## Summary` block: `passed: 4` -> `passed: 5`, `pending: 1` -> `pending: 0`. Update the frontmatter
       `status: partial` -> `status: complete` and the `## Current Test` note to reflect item 5 is now decided.

    Commit atomically: `docs(onboarding): ADR-0001 approval + Stripe-Connect decisions; close UAT item 5 (#178, #102)`.
  </action>
  <verify>
    <automated>test -f /home/sanmi/IdeaProjects/JToye_OaaS_2026/docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md &amp;&amp; grep -q 'DECIDED' /home/sanmi/IdeaProjects/JToye_OaaS_2026/docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md &amp;&amp; grep -q 'passed: 5' /home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md &amp;&amp; grep -q 'pending: 0' /home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md</automated>
  </verify>
  <done>ADR-0001 exists covering both decisions; section 9 item 1 reads DECIDED referencing ADR-0001; UAT item 5 = PASS with Summary passed 5 / pending 0 and status complete; one atomic commit.</done>
</task>

<task type="auto">
  <name>Task 3: Comment the decisions on GitHub issues #178 and #102</name>
  <files>(no repo files — GitHub side effects via gh CLI)</files>
  <action>
    Record the decisions on the tracking issues using the `gh` CLI. Do NOT close either issue.

    1. `gh issue comment 178` — state that item 1 (ONBOARDING_AUTO_APPROVE production stance) is DECIDED:
       hybrid by model — WHITE_LABEL auto-approves on green gates, MARKETPLACE always manual. Note the
       code shipped (`onboarding.auto-approve-models` default WHITE_LABEL; `onboarding.auto-approve` global
       force-on override) and reference ADR-0001. Explicitly state the admin approve/reject queue for
       MARKETPLACE is still required and remains open as #178 slice 2 — so the issue stays OPEN.
    2. `gh issue comment 102` — state the Stripe money-flow decision is DECIDED: Stripe Connect keyed to
       model (destination charges for MARKETPLACE, direct charges + application fee for WHITE_LABEL). Note
       the first acceptance criterion ("documented decision") is now satisfied and reference ADR-0001;
       destination-charge implementation remains open for a future planned phase — issue stays OPEN.

    If `gh` is not authenticated, surface the auth error and pause for the user to run `gh auth login`,
    then retry — do not fabricate the comments. Verify each comment posted by reading the issue back.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 &amp;&amp; gh issue view 178 --json state,comments --jq '.state as $s | (.comments | map(select(.body | test("hybrid|auto-approve";"i"))) | length) as $c | "state=\($s) matching_comments=\($c)"' &amp;&amp; gh issue view 102 --json state,comments --jq '.state as $s | (.comments | map(select(.body | test("Stripe Connect|destination charge";"i"))) | length) as $c | "state=\($s) matching_comments=\($c)"'</automated>
  </verify>
  <done>#178 has a comment documenting the hybrid auto-approve decision (admin queue still noted as open); #102 has a comment documenting the Stripe Connect keyed-to-model decision; both issues remain state=OPEN.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| config -> onboarding state machine | `onboarding.auto-approve` / `auto-approve-models` govern whether an onboarding advances past PENDING_APPROVAL without a human |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-bej-01 | Elevation of Privilege | Model-aware auto-approve in GateChainRunner | mitigate | Auto-approve only fires the APPROVE event; the APPROVE guard AND the GO_LIVE guard still require every mandatory gate PASSED/WAIVED + a fresh ALLERGEN_DATA_COMPLETE re-check (WR-03), so auto-approving WHITE_LABEL is not a compliance bypass — it only skips the human review step for a fully-green application |
| T-bej-02 | Tampering | @SpyBean OnboardingProperties E2E coverage | mitigate | Keep `isAutoApprove()` a direct external call on the properties bean (pitfall #1) so the Phase 18 E2E spy stub still governs the global-force path; both E2E cases (auto-approve on/off, MARKETPLACE) remain asserted |
| T-bej-03 | Information Disclosure | OnboardingProperties.toString() | accept | New `autoApproveModels` field is a non-secret enum list; existing redaction of the Companies House api key is unchanged |
| T-bej-SC | Tampering | npm/pip/cargo installs | accept | No package-manager installs in this task (config + docs + gh comments only) |
</threat_model>

<verification>
- `./gradlew :core-java:test` green (full unit suite, not just the two onboarding classes).
- `./gradlew :core-java:integrationTest` green for the onboarding classes (they use MARKETPLACE and must still halt at PENDING_APPROVAL) — requires Docker/Testcontainers.
- `bash scripts/docs-freshness.sh` exits 0 (metrics.json matches source).
- ADR-0001 exists and section 9 item 1 + UAT item 5 flipped to decided/PASS.
- `gh issue view 178` and `gh issue view 102` both show `state: OPEN` with the new decision comment.
</verification>

<success_criteria>
- WHITE_LABEL auto-approves on green gates with the default config; MARKETPLACE stays manual; global `onboarding.auto-approve=true` still force-approves both (backward compatible).
- No new Flyway migration introduced.
- Both decisions recorded in ADR-0001; section 9 item 1 = DECIDED; UAT item 5 = PASS (Summary passed 5 / pending 0).
- Issues #178 and #102 carry decision comments and remain open.
- Three atomic commits on branch `feature/onboarding-approval-stripe-decisions` (code; docs; the gh comments task produces no repo commit).
</success_criteria>

<output>
Create `.planning/quick/260711-bej-record-onboarding-auto-approve-stripe-co/260711-bej-SUMMARY.md` when done.
</output>
