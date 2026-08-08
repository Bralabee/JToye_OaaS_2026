---
phase: 33-the-consumer-product
plan: 04
subsystem: identity
tags: [keycloak, oidc, identity-provider, adr, envsubst, citations, google]

requires:
  - phase: 33-the-consumer-product
    provides: "33-00's owner decision Q-3 = q3-record (dated decision, groundwork committed DISABLED)"
provides:
  - "ADR-0005 — the dated decision CUST-03's second limb requires, naming the production-domain blocker as the single revisit trigger"
  - "A Google identityProviders entry in the customer realm template, enabled:false, credentials from env, no secret committed"
  - "GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET wired through the CUSTOMER envsubst allow-list and the render sidecar's environment"
  - "A verify-env.sh guard that reads the realm's own enabled flag and requires the keys only when it is true"
  - "The realm-replacement procedure — the thing that makes a template edit actually reach a running realm"
  - "k8s/LOCAL.md under the citation gate (DEFAULT_DOCS 7 -> 8 docs, 62 -> 73 verified citations)"
affects: []

tech-stack:
  added: []
  patterns:
    - "A conditional guard reads its trigger OUT OF the artefact it guards, never restating it"
    - "When bash and compose disagree about a config line, check the RAW text — sourcing cannot see the defect"
    - "A citation gate matches tokens on the SAME LINE, so paragraph rewrapping can break a true claim"

key-files:
  created:
    - docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md
  modified:
    - .planning/REQUIREMENTS.md
    - infra/keycloak/realm-export-customers.template.json
    - infra/keycloak/README.md
    - docker-compose.full-stack.yml
    - .env.example
    - scripts/verify-env.sh
    - scripts/check-doc-citations.sh
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - k8s/LOCAL.md

key-decisions:
  - "CUST-03 is marked satisfied by the RECORDED-DECISION limb only; identityProviders stays empty and REQUIREMENTS.md says so rather than implying the populate limb"
  - "No :? guard on GOOGLE_CLIENT_ID/_SECRET in compose — a recorded decision to stay disabled must not fail the whole stack"
  - "RESEARCH assumption A2 recorded UNVERIFIED rather than resolved: if it is wrong it strengthens the case to defer, so resolving it changes nothing this phase"
  - "The obsolete docs-freshness.sh:56 claim in LOCAL.md was marked superseded, NOT repointed — a line-number swap would have made a false statement resolvable"
  - "k8s/LOCAL.md added to DEFAULT_DOCS: a document nothing checks is a document that drifts back"

patterns-established:
  - "Attribute a gate failure by re-running it on a worktree of the base commit before calling it yours"
  - "A positive control belongs in every zero: an empty list, an empty page and a dead reader look identical"

requirements-completed: [CUST-03]

duration: 1h
completed: 2026-08-08
---

# Phase 33 Plan 04: Customer-Realm Identity Providers — Summary

**#432 is settled on the limb the owner chose: a dated ADR rather than a Google button that only works on a developer's laptop, plus the groundwork committed inert so enabling it later is one variable and a realm replacement, not a reopened investigation.**

## What was actually blocked

Google is the only candidate that costs nothing and needs no review, and it is blocked on one mechanical fact: a production redirect URI must be **HTTPS on a resolving host**, and Google exempts `localhost` from that rule. So the local demo is legal and production is not. Apple needs a paid Developer Program membership; Meta needs app review. Both are the D-1 commercial-decision class.

The premise everyone had written down was **stale, and re-measuring corrected it without changing the conclusion**. `jtoye.co.uk` *does* resolve — `162.255.119.30`, Namecheap parking — but `https://` times out (curl rc=28) while `http://` returns a 302 parking redirect. The unmet half is HTTPS, not DNS. Both controls are in the ADR (negative: `olajay.co.uk` rc=2; positive: `ordnancesurvey.co.uk` 200), because a DNS check that can only report one direction is not a check.

**A successful `getent` is therefore not evidence the domain is live.** Anyone about to flip `DEPLOY_*_ENABLED` on a resolving hostname would be acting on a parking page.

## Tasks

| Task | Outcome |
|---|---|
| 1 — Write the dated ADR | ADR-0005 + `REQUIREMENTS.md` CUST-03 on the recorded-decision limb |
| 2 — Commit the groundwork, inert | Template entry, envsubst allow-list, env keys, conditional guard, realm-replacement procedure |
| 3 — Human gate (blocking) | All four steps executed with evidence; owner approved |

## Control arms — every criterion shown to fail

Measured on the pre-change tree first, so each was already failing before the work: ADR absent, `import --override` count 0, `ADR-0005` in REQUIREMENTS 0, all three `GOOGLE_CLIENT_ID` counts 0.

| Criterion | Break | Result |
|---|---|---|
| IdP state is settled | delete the ADR | `test -f` rc=1; restored, hash identical |
| Placeholder is allow-listed | drop the names from the envsubst list | rendered `clientId=[${GOOGLE_CLIENT_ID}]` |
| No secret committed | `GOOGLE_CLIENT_SECRET=abc` in an `.env` copy | zero-count assertion fires |
| Guard is conditional | IdP disabled, no Google vars | rc=0 — default stack boots |
| **Guard can actually demand** | flip `enabled: true`, no vars | rc=1 naming both variables |
| Guard survives the comment shape | `VAR=  # todo` | rejects both; no false positive on `VAR=` or `VAR=#novalue` |
| Replacement procedure recorded | delete the README section | count 0 |

Closing clean arm re-run after every restore; the template restore was verified by `git hash-object`, not by `git diff --stat`.

## Two corrections found by measuring

**The plan contradicted itself and the gate's version won.** Its `<interfaces>` block said an unlisted envsubst placeholder "renders as an EMPTY STRING"; its human gate said a surviving literal means envsubst never saw it. Three arms settle it: allow-listed+unset → `[]`, not allow-listed → `[${GOOGLE_CLIENT_ID}]`, allow-listed+set → the real value. The third arm is what makes the other two mean anything — without it, "empty" is equally consistent with envsubst doing nothing. The literal is also the *safer* failure, because it is visible.

**`docker compose config -q` caught a defect I introduced.** A comment written *inside* the entrypoint block literal contained `${...}` tokens; compose interpolates that string and `${role_*}` is not a valid name, so the whole file failed to parse. The fixed comment says so at the spot where the next person would repeat it.

## The bash/compose disagreement, which is why the guard reads raw text

Both directions measured on this tree:

```
VAR=  # note     bash `set -a; . .env`  -> ''        (what verify-env.sh sees)
                 docker compose         -> '# note'  (what reaches the container)
```

A line the script reads as *unset* is one compose renders into the realm JSON **as the client id**. Sourcing can never see this; only the raw text can. The pattern keys on the whitespace before `#`, because `ALERTMANAGER_SLACK_CHANNEL=#jtoye-alerts` is a legitimate line on this tree.

## Human gate — evidence, all four steps

1. **Stack boots with no Google vars** — `verify-env.sh` rc=0 on the real `.env`; compose resolves both to `""`, no warning.
2. **Rendered realm** — sidecar re-run; `enabled:false`, `clientId:""`, `clientSecret:""`, `trustEmail:true`. Zero `${GOOGLE` literals survived; four `${role_` i18n placeholders intact; file valid JSON.
3. **Sign-in unchanged** — a throwaway customer signed in through the real browser: `/shop/signin` → Keycloak → callback → `/shop`, session established. **Zero console errors or warnings, zero CSP violations** — with the reader proven live first (a deliberate `console.error`/`warn` was captured). Test user deleted afterwards, verified by a type-aware check.
4. **No Google button** — Admin API on the *running* realm: `identityProviders: 0`, against a positive control (`storefront-client` → 1). Keycloak's login form renders no social section; the app's sign-in page offers only Sign in / Create an account.

Step 4 also **live-confirms the trap the README now documents**: template and rendered JSON both carry the block, the running realm has zero, because `--import-realm` skips an existing realm and Keycloak is Postgres-backed.

## One regression, owned; two failures, attributed away

Adding to `docker-compose.full-stack.yml` shifted every line after ~106 by **+16**, breaking nine citations in the current-state codebase maps. `check-doc-citations` went rc=0 → rc=1.

Attribution was **measured, not assumed**: the same gate runs rc=0 on a worktree of `51a0c633`. The other two failures in the sweep — `check-claims` and `check-handoff-contract` — fail *identically* at that base commit and were left alone. `check-claims` is the expected-red the handoff describes, and hand-editing those figures is exactly what it warns against.

The offset was confirmed against four independent anchors rather than inferred from one, so every citation now points at the identical content it did before.

## k8s/LOCAL.md — 10 of 18 citations broken, none of it ours

Found because LOCAL.md cites the compose file. Repaired and brought under the gate. Three causes, three different correct fixes:

- **Eight stale locators** — five bare filenames with no path, three moved line numbers. Re-derived by content.
- **Two line-scoping artefacts** — *true* claims whose distinctive token had drifted onto the previous line by ordinary paragraph wrapping. Rewrapped; no factual assertion changed. Editing the line number here would have made a correct claim point somewhere wrong.
- **One obsolete claim** — `docs-freshness.sh:56` no longer counts `\b(it|test)\(`; #291 replaced that grep with `count-test-blocks.mjs`. Marked **superseded**, not repointed: a line-number swap would have made a false statement resolvable, which is worse than a broken citation.

`DEFAULT_DOCS` 7 → 8 docs, verified citations 62 → 73, violations 0 — and the expansion was shown to be real, not cosmetic: a break planted in LOCAL.md alone now reds the default run.

## Deviations

- `frontend/lib/security-headers.ts` was already removed from `files_modified` by the plan-checker; no CSP change was made and the ADR records why, plus the trailing-slash path-matching rule for whoever enables the IdP.
- `docker-compose.full-stack.yml` also gained an `environment:` entry for both variables, beyond the allow-list the plan named — without it `envsubst` has nothing to substitute and the "one-variable switch" would not be one.
- `scripts/check-doc-citations.sh`, `.planning/codebase/*`, `k8s/LOCAL.md` are outside the plan's `files_modified`: the first three repair a regression this plan caused, the last was owner-approved follow-up.

## Notes for the next plan

- `docs/metrics.json` untouched — this plan adds no counted test. `check-doc-metrics` stays expected-red until 33-07 Task 4.
- Enabling Google later needs **all four** of: both env vars, `enabled: true` in the template, the redirect URI registered in Google's console, and a realm replacement. The first three reach nothing without the fourth.
- KC24's unmanaged-attribute strip does **not** bite the storefront path today: `CustomerJwtVerifier` reads only `email` and `email_verified`, both standard OIDC claims. Re-measure before enabling — the risk returns the moment anything there reads a custom claim.

## Self-Check: PASSED

Task 1 criteria rc=0 · Task 2 criteria rc=0 · `verify-env.sh` rc=0 · `check-doc-citations` rc=0 (8 docs) · `check-container-config-drift` rc=0 · `check-infra-exposure` rc=0 · `check-gate-enforcement` rc=0 · `check-no-measured-placeholders` rc=0 · `docs-freshness` rc=0 · `docker compose config -q` rc=0 · branch 0 behind `origin/main`.
