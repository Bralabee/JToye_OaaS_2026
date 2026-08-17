---
phase: 31
slug: consumer-safety-and-legal-floor
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-08-15
---

# Phase 31 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `31-RESEARCH.md` § "Validation Architecture" (line 953), which was measured against
> the live tree with `check-runtime-freshness.sh` PASS 4/4.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Jest 29.7.0 + `jest-environment-jsdom` 30.4.1 + `@testing-library/react` 16.3.2 (frontend); JUnit 5 (backend); Playwright 1.62.1 (E2E) |
| **Config file** | `frontend/jest.config.js` (via `next/jest`), setup `frontend/jest.setup.js`, `testPathIgnorePatterns` excludes `/e2e/`; `frontend/playwright.config.ts` (projects `mobile` 390×844 `hasTouch`, `desktop` 1440×900, `workers: 1`); `core-java/build.gradle.kts:129-133` (unit, excludes tag `testcontainers`) and `:148-154` (integration, includes it) |
| **Quick run command** | `cd frontend && npx jest <path>` · `./gradlew :core-java:test --tests '<Class>'` |
| **Full suite command** | `cd frontend && npm test -- --ci --watchAll=false` · `npm run lint` · `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` · `./gradlew :core-java:test :core-java:integrationTest` |
| **Estimated runtime** | < 30 s per-task quick run; axe E2E scan measured at **18.3 s** (desktop) / **30.7 s** (both projects) |

⚠ **Lint config correction (carry into every plan):** `.eslintrc.json` **does not exist**. Next 16
removed `next lint`; the project uses ESLint 9 flat config at `frontend/eslint.config.mjs`, run as
`eslint .`. Do **not** wrap the Next configs with `FlatCompat` — that file records that it crashes
with a circular-structure error. `CLAUDE.md`'s Conventions section is stale on this point and should
be corrected by whichever plan touches lint config.

---

## Sampling Rate

- **After every task commit:** the single `npx jest <path>` or `./gradlew :core-java:test --tests '<Class>'` for the file touched (< 30 s).
- **After every plan wave:** full frontend Jest + `npm run lint` + `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` + `./gradlew :core-java:test`; add `:core-java:integrationTest` on any wave touching Java.
- **Before `/gsd:verify-work`:** everything above, plus the gate scripts (`docs-freshness.sh`, `check-doc-metrics.sh`, `check-claims.sh`, `check-gate-enforcement.sh`, `check-geo-attribution.sh`, `check-runtime-freshness.sh`), plus **the recorded break-arm output for every new assertion**, plus a full container rebuild before any E2E claim.
- **Max feedback latency:** 30 seconds.

---

## The four validation tiers — what each can and cannot prove

Recorded because choosing the wrong tier is how this project has previously produced green-but-vacuous results.

| Tier | Can prove | **Cannot** prove | Runs |
|------|-----------|------------------|------|
| eslint (static) | ARIA prop validity, `alt-text`, role/aria coherence, labels hidden behind a `placeholder` | anything about composed pages, contrast, focus order | every PR, unfiltered |
| jest + jsdom | component-scope axe, copy strings, consent block/permit logic, contrast recomputed from source, allergen aggregation, D-01's "profile never rendered" assertion | **layout, real focus, scroll lock, Escape, contrast as painted** — jsdom has no layout engine | every PR |
| Playwright (stack-free) | composed-page axe, landmarks, heading order, focus visibility, dish-modal open scan, retention-table `scrollWidth <= clientWidth` at 375 px, CLS | anything needing real backend data | **every PR** via the existing `frontend-e2e` job |
| Playwright (nightly, real stack) | seeded end-to-end journeys, live-region announcements, KDS over STOMP | — | 02:00 UTC only — **never a PR gate** |
| Testcontainers (real Postgres) | RLS behaviour, per-tenant GUC pinning, `RlsContractTest` schema walk, DSAR fan-out under FORCE RLS with the NOSUPERUSER downgrade | frontend anything | every PR (`integrationTest` job) |
| Gate scripts | published-claim ⇄ source agreement, retention-enforcement existence, gate wiring, runtime parity | behaviour | every PR |

**Placement decision (answers research Q2):** the axe E2E half goes in the existing **`frontend-e2e`**
job — the only browser job with a `push`/`pull_request` trigger. `e2e-nightly.yml` is `schedule` +
`workflow_dispatch` only, so a gate placed there **cannot block a PR**. The job already builds, starts
the frontend and installs chromium, so the marginal cost is scan time alone.

---

## Per-Task Verification Map

*To be completed by `gsd-planner` — task IDs do not exist until plans are written. The requirement →
behaviour → command mapping below is settled and each row MUST land on a task.*

| Req | Behaviour | Test Type | Automated Command | Exists? |
|-----|-----------|-----------|-------------------|---------|
| LGL-01 | `/legal/{privacy,cookies,retention,accessibility}` return 200 with unique title + description + canonical | e2e | `npx playwright test e2e/public-a11y.spec.ts -g "legal metadata"` | ❌ W0 |
| LGL-01 | `PublicFooter` links all five legal routes; each resolves | e2e | same spec, `-g "legal reachability"` | ❌ W0 |
| LGL-01 | Cookie notice renders, dismisses, persists a **version** string, causes **zero** CLS | e2e + unit | `npx jest components/public/__tests__/cookie-notice` + CLS vs `LANDING_CLS_KNOWN_BASELINE = 0.1793` ± 0.02 | ❌ W0 |
| LGL-01 | Consent gate blocks **then permits** a fixture category | unit | `npx jest lib/__tests__/consent` | ❌ W0 — **both arms mandatory** |
| LGL-01 | Retention table fits at 375 px (`scrollWidth <= clientWidth`) | e2e | `... -g "retention table" --project=mobile` | ❌ W0 |
| LGL-01 | Published periods equal the manifest | gate | `bash scripts/check-claims.sh` | ✅ engine exists; rows ❌ |
| LGL-01 | Every `Automated` manifest row has a real enforcement site | gate | `bash scripts/check-retention-enforcement.sh` | ❌ W0 — **ship with its workflow ref in the SAME commit** |
| LGL-01 | DSAR intake returns 202 + typed RFC 7807 + Idempotency-Key | integration | `./gradlew :core-java:integrationTest --tests '*Dsar*'` | ❌ W0 |
| LGL-01 | DSAR fan-out writes one `erasure_record` per tenant under FORCE RLS | integration (Testcontainers) | same | ❌ W0 |
| LGL-01 | A request thread never enters `asSystem` | unit | `./gradlew :core-java:test --tests '*SystemPrincipalGuard*'` | ✅ exists — extend |
| LGL-02 | Zero axe violations on the 7 declared surfaces + opened modal, both viewports | e2e | `npx playwright test e2e/public-a11y.spec.ts` | ❌ W0 |
| LGL-02 | Each scan carries a non-vacuity control that fails on an empty page | e2e | same spec | ❌ W0 |
| LGL-02 | The gate fails against a deliberately broken control | e2e break arm | run, record, delete | ❌ W0 |
| LGL-02 | Component-level axe on every new component | unit | `npx jest -t "a11y"` | ❌ W0 |
| LGL-02 | Tailwind colour **literals** clear AA | unit | `npx jest __tests__/contrast-literals` | ❌ W0 |
| LGL-02 | `--primary` / `--destructive` / `--muted-foreground` / `--trust` unchanged | unit | `npx jest __tests__/contrast-tokens.test.ts` | ✅ **8/8 green — must stay green with NO edit to its expectations** |
| LGL-02 | Statement's `nextReviewDue` is in the future | unit | `npx jest __tests__/accessibility-statement-dates` | ❌ W0 |
| LGL-02 | Statement's contact route resolves (no 404, no unconfigured mailto) | e2e | `... -g "contact"` | ❌ W0 |
| LGL-03 | Order aggregate = union of declared masks | unit | `./gradlew :core-java:test --tests '*AllergenAggregat*'` | ❌ W0 |
| LGL-03 | Reconciliation flags a product whose text names an allergen its mask omits | unit | same | ❌ W0 |
| LGL-03 | `ProductLabelService` output unchanged by D-03 | unit | `./gradlew :core-java:test --tests '*ProductLabel*'` | ✅ exists — regression arm |
| LGL-03 | Java and TS allergen tables agree on all 14 bit↔name pairs | unit | `npx jest __tests__/allergen-table-parity` | ❌ W0 |
| LGL-03 | Checkout refuses submit without acknowledgement; error is `role="alert"`; focus moves | unit | `npx jest app/shop/\[slug\]/checkout/__tests__` | ❌ W0 |
| LGL-03 | Checkout DOM contains **no** value derived from `Customer.allergenRestrictions` | unit | same | ❌ W0 — proves D-01 |
| LGL-03 | Empty allergen set still renders the panel with honest copy | unit | same | ❌ W0 |
| LGL-03 | KDS banner shows the complete set; badge truncates at 3 + `+N` | unit | `npx jest app/dashboard/kitchen/__tests__` | ❌ W0 |
| LGL-03 | Allergen block appears on the print sheet | unit (CSS/DOM) | same | ❌ W0 |
| LGL-03 | Dish-modal dialog contract stays green | e2e (nightly) | `npx playwright test e2e/storefront-dish-modal-a11y.spec.ts` | ✅ exists — **must not regress** |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `frontend/e2e/public-a11y.spec.ts` — the LGL-02 E2E half, **plus the one-token edit to `ci-cd.yaml:321`** that makes it run
- [ ] `frontend/__tests__/contrast-literals.test.ts` — Tailwind-literal contrast (catches the `text-emerald-600` class the token move left behind)
- [ ] `frontend/__tests__/allergen-table-parity.test.ts` — Java ⇄ TS allergen bit table
- [ ] `frontend/lib/__tests__/consent.test.ts` — fixture-category block **and** permit
- [ ] `frontend/components/legal/__tests__/*.a11y.test.tsx` — component axe for the new components
- [ ] `core-java/.../AllergenAggregatorTest.java` + a Testcontainers DSAR fan-out test
- [ ] `docs/retention-manifest.json` + `claims.manifest` rows
- [ ] `scripts/check-retention-enforcement.sh` **and its `ci-cd.yaml` reference in the same commit** (see the double bind below)
- [ ] Package installs — **each behind `checkpoint:human-verify`** (see Manual-Only)
- [ ] `frontend/eslint.config.mjs` — extend jsx-a11y beyond the current 6 of ~35 rules

### The gate-enforcement double bind (measured, both directions)

`scripts/check-gate-enforcement.sh` is **default-deny**, and it fails in *opposite* directions:

| State | Exit |
|---|---|
| new `check-*.sh` exists with **no** workflow reference | **rc=1** |
| `gate-enforcement.conf` entry naming a script that does **not** exist yet | **rc=2 (VOID)** |

Because the two failures point opposite ways, **no two-commit ordering is green at both points**.
`scripts/check-retention-enforcement.sh` and its `ci-cd.yaml` reference MUST land in **one commit**.
This is a hard constraint on wave sequencing, not a style preference.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Package installs: `@axe-core/playwright@4.13.0`, `axe-core@4.13.0`, `jest-axe@10.0.0`, `shadcn add checkbox` | LGL-02 | **`slopcheck` could not run** — `pip install` is blocked by the `block-base-python` hook and this project declares no `.conda-env`. The researcher did not reroute. All four are tagged `[ASSUMED]`. | `checkpoint:human-verify` on each install: confirm the package is real, actively maintained, and React-19 compatible before it enters `package.json`. Consider committing a `.conda-env` so slopcheck can run in future. |
| Break-arm output for every new assertion | all three | A criterion observed only passing may be incapable of failing | Run each new check against a deliberately broken input, record BOTH directions' real output, then restore and re-run the clean arm |
| Registered-office and DSAR contact values | LGL-01 | Set nowhere today — would render **blank** on the published pages | Owner supplies values, or the page must not claim a contact route it cannot honour |
| Legal copy sign-off | LGL-01, LGL-02 | Legally operative text a regulator may read | Owner (or counsel) reads the privacy, cookies, retention and conformance copy before merge |

---

## Open questions the planner must resolve (from RESEARCH.md)

1. **Snapshot vs live-join for the order allergen mask.** Research recommends **snapshot** — otherwise a post-order vendor edit silently rewrites what the customer acknowledged, which defeats the point of the acknowledgement.
2. **A11Y-08 — no `autocomplete` on 8 checkout inputs.** A real AA failure that **axe cannot see**. Decide: fix it, or declare it as a named D-12 exception. Do not let it pass silently because the scanner is blind to it.
3. **`docs/legal/article-9-allergen-basis.md` already exists** (122 lines, 2026-07-30) and largely satisfies D-01 including DPA wording. Scope LGL-03 against what it already covers rather than re-authoring it.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies — **verified by `gsd-plan-checker`**, 18/18 plans valid
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify — *not independently verified; do not tick without measuring*
- [x] Wave 0 covers all MISSING references — **verified**: every Wave-0 item below is delivered by a Wave-1 plan (`contrast-literals.test.ts`→31-02, `allergen-table-parity.test.ts`→31-04, `consent.test.ts`→31-16, `public-a11y.spec.ts`→31-18, `check-retention-enforcement.sh`→31-06)
- [ ] No watch-mode flags — *not independently verified*
- [ ] Feedback latency < 30s — *estimated, not measured end-to-end*
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** plans verified 2026-08-15 (`gsd-plan-checker`, 1 blocker found and fixed — see below).
Execution not yet started.

### Why `wave_0_complete` stays `false`

The Wave-0 *items* are all accounted for — each is delivered by a Wave-1 plan, which is why
`nyquist_compliant` is now `true`. But none of them **exists on disk yet**: they are planned, not
built. `wave_0_complete` flips to `true` only when Wave 1 has executed and those files are present
and green. Setting it true now would assert infrastructure that is not there — the same shape as a
gate that passes because it measured nothing.

### Unticked boxes are unticked on purpose

Three boxes above are deliberately left unticked because nobody measured them. The plan-checker
verified plan validity and Wave-0 coverage; it did not measure sampling continuity, watch-mode flags,
or real feedback latency. Ticking them to make the block look complete is the exact failure this
project's UI-SPEC sign-off block already demonstrates — six lines reading "PASS" beside unticked
boxes while the real verdict was BLOCKED.

### Blocker found and fixed during plan-checking (2026-08-15)

`31-14` Task 2's `<automated>` limb hardcoded `test "$out" -ge 8` for autocomplete tokens, but the
checkout form has **7** `<input>` elements plus one `<textarea>` (`notes`, which takes no autocomplete
token). RESEARCH's "8" counted the textarea. A faithful implementation would have produced 7 tokens
and the gate would have exited 1, reporting **A11Y-08 as unclosed when it was closed** — a criterion
that fails on a *correct* tree.

Replaced with the relative comparison the task's own `acceptance_criteria` already described
(`tokens == inputs`), guarded by a floor of `inputs >= 7`. Falsified three ways before acceptance:

| Arm | inputs / tokens | Result |
|---|---|---|
| current tree | 7 / 0 | fails correctly |
| simulated correct implementation | 7 / 7 | passes correctly |
| empty file (vacuity control) | 0 / 0 | **floor refuses it** — `tokens == inputs` is TRUE here, so the bare comparison alone would have passed vacuously |

The third arm is why the floor is there and not decoration.
