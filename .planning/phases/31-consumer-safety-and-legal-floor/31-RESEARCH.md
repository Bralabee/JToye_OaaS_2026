# Phase 31: Consumer-Safety and Legal Floor — Research

**Researched:** 2026-08-15
**Domain:** UK GDPR/PECR compliance surfaces, WCAG 2.1 AA remediation + CI a11y gating, allergen evidence chain
**Confidence:** HIGH on everything measured on the live tree; MEDIUM on package selection (slopcheck could not run — see Package Legitimacy Audit); LOW on nothing that is stated as fact.

**Branch measured:** `phase/31-consumer-safety-legal-floor` @ `7c552e20`
**Runtime measured:** Compose stack, `scripts/check-runtime-freshness.sh` → **PASS, 4/4 services FRESH, 0 unverified**. Every browser measurement below therefore describes the current tree, not a stale image.

---

## Summary

Three findings reshape this phase's cost and shape, all measured rather than assumed.

**First, LGL-02 is far smaller than the inputs suggest.** The 31-UI-SPEC quotes "~100 of the 220 baseline `color-contrast` violations". That 220 is real but it is the **all-27-route** aggregate from the 2026-08-02 QA council run, including the marketing pages and the vendor dashboard — both explicitly out of D-09's declared scope. A fresh axe run today against D-09's actual list produces **15 node instances across 7 routes plus the dish modal, reducing to 4 distinct root causes**. `/` is already at zero. D-11's "zero violations on the declared surface list" is a handful of targeted edits, not a palette programme.

**Second, two of the phase's stated product defects do not exist, and a third nobody listed does.** `/shop/[slug]` and `/shop/[slug]/checkout` **do** render a `<footer>` and **do** expose a `contentinfo` landmark — `app/shop/layout.tsx:73` renders `<PublicFooter />` and Next nests it over the whole `/shop/**` subtree; confirmed in the served HTML and by `getByRole("contentinfo")` = 1. What **is** true is the second half: the footer carries zero `/legal` links on every public route. Meanwhile `/auth/signin` — a D-09 declared surface — has **no `main`, no `banner`, no `contentinfo`, no `h1`**, and still serves the stale root title "J'Toye OaaS - Multi-Tenant Order Management". It is 7 of the 15 remaining violations on its own.

**Third, D-08's gate is mostly free, and the retention numbers already exist.** Four periods are genuinely code-enforced (24 h draft orders, 30 d webhook deliveries, 72 h media quarantine, 30 d refresh cookie); everything else is on-request or indefinite and must be published as `Operational`. One published key — `cleanup.orphaned-image-days: 7` — has **zero code consumers** and is exactly the drift D-08 exists to catch. The repo already ships a generic claim-gate engine (`scripts/gates/claims.manifest`) whose rules assert "a number quoted in a document equals its source of truth" and **fail when the rule matches nothing**. Half of D-08 is therefore a handful of TSV rows and **no new script at all**, which removes the `check-gate-enforcement.sh` wave-ordering hazard from that half entirely.

**Primary recommendation:** Split the phase by gate cost, not by surface. Wave 1 lands the three zero-new-dependency layers (eslint `jsx-a11y` expansion, `jest-axe` component tests, the 4 markup fixes). Wave 2 lands the `@axe-core/playwright` scan **inside the existing stack-free `frontend-e2e` job** — measured marginal cost ~31 s for both viewports — because that is the only browser job that runs per-PR. Wave 3 lands the retention manifest + `claims.manifest` rows, and only then the single new `check-*.sh` together with its workflow reference in the same commit.

---

## User Constraints (from CONTEXT.md)

### Locked Decisions

> ### Allergen lawful basis (LGL-03)
>
> - **D-01:** **The platform does NOT consult the stored customer allergen profile at checkout.**
>   `Customer.allergenRestrictions` (an `Integer` bitmask, already persisted) is special-category
>   health data under Article 9. This phase's deliverable is a **dated, recorded decision not to
>   process it** — the roadmap explicitly offers this limb ("or an explicit decision not to").
>   The bitmask stays vendor-entered exactly as today; no new consent capture, withdrawal, or
>   audit obligation is created.
> - **D-02:** **What the consumer sees at checkout is the ORDER's own aggregated allergen set**
>   ("this order contains: milk, gluten, sesame"), and they must **explicitly acknowledge** having
>   reviewed it before proceeding. No health data is read, stored, or inferred — the platform never
>   learns the customer's allergies. This is the subject that makes D-01 and the warn-and-acknowledge
>   behaviour coherent; there is no customer-vs-product comparison anywhere in this phase.
> - **D-03:** **Aggregation = declared product masks PLUS a reconciliation flag.** Any product whose
>   free-text ingredients name an allergen absent from its declared mask is flagged. This is the limb
>   that attacks the roadmap's actual complaint — every allergen statement today resolves to an integer
>   a vendor hand-types into a CSV column, never reconciled against the adjacent ingredients text.
>   `IngredientMarkupParser` and `AllergenSpan` already exist as the parsing substrate; reuse them.
> - **D-04:** **KDS surfacing = order-level banner AND per-item badge.** The banner so it cannot be
>   missed under time pressure; the badge so staff know *which* item. A single aggregate tells kitchen
>   staff nothing actionable.
>
> ### Cookie and consent scope (LGL-01)
>
> - **D-05:** **Ship the consent store dormant, plus an honest notice.** Measured on the tree: there
>   are **zero non-essential cookies and zero analytics/tag scripts** in `frontend/app`,
>   `frontend/components`, `frontend/lib`. So ship (a) a cookie policy page, (b) an essential-cookies
>   notice, and (c) the consent store + gating API **with zero non-essential categories registered**,
>   so no banner appears today but any future script must pass through the gate to load. Additive by
>   design — it makes the rule structural instead of a note.
>   ⚠ A consent gate over zero categories is **unfalsifiable as shipped**. It MUST be proven with a
>   **fixture category** in tests that demonstrates the gate blocking and then permitting a script.
>   Without that arm, this decision buys nothing.
> - **D-06:** **Policy pages nest under `/legal`.** `/legal` becomes an index; new routes are
>   `/legal/privacy`, `/legal/cookies`, `/legal/retention`. Reuses `PublicShell` and the existing
>   `alternates: { canonical }` metadata pattern already in `frontend/app/legal/page.tsx`. Each is a
>   separately citable URL, because regulators and procurement forms ask for them individually.
> - **D-07:** **The retention policy is a published per-category schedule** — data category →
>   retention period → lawful basis — not a statement of principles. Derive the periods from what the
>   system **measurably enforces today**, not from aspiration.
> - **D-08:** **The published retention periods are tied to enforcement by a new
>   `scripts/check-*.sh` gate** that fails when a published period has no corresponding enforcement in
>   code or config. A published schedule that quietly diverges from behaviour is the exact drift class
>   the two docs-freshness gates exist to catch. Note for the planner:
>   `scripts/check-gate-enforcement.sh` is **default-deny** — any new `check-*.sh` needs a workflow
>   reference or a `gate-enforcement.conf` entry, and the conf **VOIDs at rc=2** on an entry naming a
>   script that does not exist yet. Sequence the wave accordingly (see Phase 33's 33-05/33-06 split
>   for the worked precedent).
>
> ### Accessibility (LGL-02)
>
> - **D-09:** **Declared surfaces = public storefront + auth flows** — landing, shop listing, shop
>   page, product detail, checkout, sign-in/sign-up. The authenticated vendor dashboard is
>   **deliberately out**; the 2026-07-08 audit called accessibility "essentially absent" and a scope
>   that includes the dashboard is a phase that never closes.
> - **D-10:** **Both layers.** `jest-axe` at component level (fast, every PR, no browser stack) and
>   `@axe-core/playwright` at E2E level (real composed pages — focus order, landmarks, live regions).
>   The component layer alone is exactly the configuration that produced this project's misleading zero.
> - **D-11:** **Threshold = zero violations on the declared surface list.** No ratcheted baseline file
>   — a baseline blesses existing violations as the standard and needs regenerating on every
>   legitimate change. Grow the surface list in later phases instead.
> - **D-12:** **The conformance statement claims partial conformance, dated, with named exceptions**
>   and remediation dates. WCAG explicitly supports a partial-conformance claim; overclaiming
>   accessibility is itself Equality Act exposure.
> - **D-13 (binding constraint on every a11y assertion):** **A zero-violation axe result is presumed
>   an artefact until proven otherwise.** This project has already been bitten — a naive axe
>   "0 button-name violations" was meaningless because the tables under test never mounted. Every axe
>   assertion MUST carry a **non-vacuity control** proving the scanned page actually rendered (a
>   known-present landmark/heading count > 0), and the gate MUST be shown to fail against a
>   deliberately broken control per the roadmap's own wording.
>
> ### Controller/processor split (LGL-01)
>
> - **D-14:** **Joint controllers for consumer order data; the platform is sole controller for
>   platform accounts; the platform runs the single point of contact** for data-subject requests.
>   Requires an Article 26 arrangement whose essence is published.
> - **D-15:** **Layered privacy notice** — one platform privacy notice covering the shared processing,
>   with the specific vendor **named at the point of order** as the other party. One document to
>   maintain and keep accurate, while still telling the consumer who they are buying from. Note the
>   existing `/legal` page already draws the *trading* line (vendors own their trading disclosures);
>   the GDPR line drawn here is different and must not contradict it in prose.
> - **D-16:** **The DSAR path is BUILT in this phase**, not promised. A published commitment to a
>   single point of contact, backed by nothing that can execute it, is the fail-open shape this
>   project keeps paying for.
> - **D-17:** **DSAR design = request intake + background fan-out under `SystemPrincipal.asSystem`,**
>   writing one `erasure_record` per tenant. This is the reconciling design for a real conflict:
>   a single cross-tenant DSAR desk appears to require the **cross-tenant operator identity this
>   project has refused twice** (recorded constraint; Phase 33 D-2 rejected it explicitly). The
>   resolution is that **no human ever holds cross-tenant read** — only a background job does, and
>   `ShopAccessService.java:640` already records the rule that "a request thread never enters
>   `asSystem` (only background entry points)". Honour that boundary exactly: intake is a request,
>   execution is background. Reuse the shipped `uk.jtoye.core.gdpr` package; do not invent a parallel one.
> - **D-18:** **The DPA / Article 26 arrangement is authored as a document in this phase.** Acceptance
>   tracking, presentation at onboarding, and e-signature are **out** — that is onboarding machinery
>   and belongs with Phase 32.

### Claude's Discretion

> No gray area was answered "you decide" — every decision above is the owner's. Discretion remains
> only over implementation mechanics (file layout, component naming, test structure), which are the
> planner's and researcher's to settle.

### Deferred Ideas (OUT OF SCOPE)

> - **DPA acceptance tracking / e-signature at onboarding** — per D-18, the document is authored here
>   but the acceptance flow belongs with **Phase 32 (Production Cutover + First Tenant)**.
> - **Accessibility remediation of the authenticated vendor dashboard** — deliberately outside D-09's
>   declared surface list. A later phase grows the list; the gate's design (D-11, no baseline file)
>   makes that growth cheap.
> - **Consent categories for real analytics** — D-05 ships the gate with zero non-essential
>   categories. The first actual analytics script is what registers a category and makes the banner
>   appear; that arrives with whichever phase introduces it.
> - **The rest of #427 (ADR-0004 ingredient graph)** — LGL-03 is Wave 1 only. The full evidence chain
>   has no phase and no issue-level home yet.

**The approved `31-UI-SPEC.md` (6/6, 2026-08-15) is a locked input in full.** Where a measurement in this document contradicts a factual claim in the UI-SPEC, the correction is recorded in § Corrections and the UI-SPEC's *contract* is unaffected — no design decision is reopened.

---

## Phase Requirements

| ID | Description (REQUIREMENTS.md:133-135) | Research Support |
|----|---------------------------------------|------------------|
| **LGL-01** | #116 — privacy policy, cookie banner and written retention policy live on the public storefront. | § Open Question 1 (consent store), § Open Question 3 (retention schedule), § Browser-storage inventory, § Reachability defect, § SEO |
| **LGL-02** | #103 + #272 — WCAG 2.1 AA with a published conformance statement and a CI a11y check shown to fail against a broken control. | § Open Question 2 (CI placement), § Measured a11y baseline, § Non-vacuity controls, § Standard Stack |
| **LGL-03** | #427 Wave 1 — recorded lawful-basis decision, order-level mask aggregation, KDS conflict surfacing. | § Allergen chain — what exists and what does not, § Don't Hand-Roll, § Pitfall 5 (snapshot vs live join) |

---

## Project Constraints (from CLAUDE.md)

Directives the planner must verify compliance against. All are enforced, not advisory.

| # | Directive | Enforcement | Impact on this phase |
|---|-----------|-------------|----------------------|
| C-1 | Stack is fixed: Spring Boot 3.5.16 / Next 16.2.12 / Go 1.26 / PG 15, JDK 21 | build files | No framework additions. Only devDependencies. |
| C-2 | All new features respect RLS + `TenantContext` | `RlsContractTest` schema walk | Any new table must ENABLE+FORCE RLS **or** take a by-addition `EXEMPT_TABLES` entry with written justification |
| C-3 | All new code requires tests; counts are the single source of truth in `docs/metrics.json`, enforced by two gates | `scripts/docs-freshness.sh` + `scripts/check-doc-metrics.sh` | Every new test file changes the counts → regenerate with `scripts/docs-freshness.sh --write`, never by arithmetic |
| C-4 | Rebuild ALL containers after code changes before E2E | `scripts/check-runtime-freshness.sh` | `docker compose start` does not rebuild |
| C-5 | Compose is canonical local runtime; k8s is the deploy target; XOR locally | doc | No k8s work in this phase |
| C-6 | Incremental Betterment: enumerate displaced goods; regression by omission is a defect even when green | review | UI-SPEC's Preserved Goods Ledger is the register; see § Correction 5 — two "preserved" panels are already dead paths |
| C-7 | Five cross-cutting quality dimensions are **design-time** acceptance criteria: web-perf, SEO, agent-readiness, security, falsifiability + runtime parity | plan gate + QA council | All five apply here. SEO applies (public `/legal/*`). Agent-readiness applies (DSAR intake is a new API surface). |
| C-8 | Every acceptance criterion must be shown to FAIL before it is trusted; both directions' output recorded | doctrine + `check-claims.sh` M-1 | Every gate in this phase needs a recorded break arm |
| C-9 | Missing tooling / unparseable / EMPTY output must exit non-zero (VOID), never 0 | gate convention | New `check-*.sh` uses 0/1/2 |
| C-10 | A branch must not be behind its base before a PR | `scripts/check-branch-behind-base.sh` (in CI) | — |
| C-11 | Schema version quoted in `CLAUDE.md`, `AGENTS.md`, `README.md` must equal `docs/metrics.json` | `check-doc-metrics.sh` / `claims.manifest` | **If this phase adds V62, all four sites must move in the same PR** |

**Project skills:** `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/` — none present (verified). No skill rules to load.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| Cookie/storage notice + dismissal persistence | **Browser / Client** | — | Pre-identity, per-device, no server state needed (see Open Question 1) |
| Consent gate over script loading | **Browser / Client** | — | The thing being gated is a browser-executed script; a server cannot gate it |
| Legal policy pages (`/legal/*`) | **Frontend Server (SSR)** | CDN/Static | Server components + `generateMetadata`; already `force-dynamic` app-wide for the CSP nonce |
| Retention schedule *content* | **Frontend Server (SSR)** | Build/CI | Rendered as a page; its *numbers* are owned by config in the API tier and asserted by a gate |
| Retention *enforcement* | **API / Backend** | Database | `@Scheduled` jobs + config; the DB holds nothing time-based itself |
| Order allergen aggregation (D-02/D-03) | **API / Backend** | Database | Must be one answer shared by checkout **and** KDS; a client-side aggregate cannot reach the KDS |
| Allergen bit→name table | **API / Backend** | Frontend (mirror) | Today it exists **only** in the frontend (`types/api.ts:490`). D-04 forces a Java-side copy |
| Checkout acknowledgement UI + refusal | **Browser / Client** | — | Pure form state; nothing to persist server-side under D-02 |
| KDS banner + badge | **Browser / Client** | API (data) | Rendering is client; the allergen data must arrive on the order DTO |
| DSAR intake | **API / Backend** | — | A request-thread endpoint; must **not** enter `asSystem` |
| DSAR cross-tenant fan-out | **API / Backend (background)** | Database | `@Scheduled`/async only; `asSystem` + per-tenant GUC pin |
| a11y gating | **Build / CI** | — | Three layers: eslint (static), jest (jsdom), playwright (browser) |

---

## THE THREE OPEN QUESTIONS — ANSWERED

### Q1 — Can the cookie consent store reuse `notification_consent` (V54)?

**Answer: NO — and the table does not exist under that name. Ship the consent store CLIENT-ONLY. Add no table at all.** `[VERIFIED: migration source + RLS policy read]`

**Measurement 1 — the name is wrong in the upstream inputs.** `V54__notification_consent.sql` is a *filename*. Searching the whole migration directory for a relation called `notification_consent` returns **rc=1, zero hits**, against a control search for `notification_suppression` returning **rc=0, 6 hits in the same file**. V54 creates exactly two tables:

| Table | Columns | RLS |
|-------|---------|-----|
| `notification_suppression` | `id, tenant_id, recipient, category, created_at`, UNIQUE`(tenant_id, recipient, category)` | ENABLE + FORCE, policy `FOR ALL USING (tenant_id = current_tenant_id())` |
| `marketing_opt_in` | `id, tenant_id, recipient, opted_in_at`, UNIQUE`(tenant_id, recipient)` | ENABLE + FORCE, same policy |

**Measurement 2 — both are structurally incapable of serving cookie consent.** `recipient` is `TEXT NOT NULL` and is an email address; `tenant_id` is `UUID NOT NULL` and every policy is `tenant_id = current_tenant_id()`. A cookie-consent decision is taken by an **anonymous visitor on the platform origin before any tenant context or identity exists**. There is no value to put in either NOT NULL column, and under FORCE RLS with no GUC set the row would be invisible to every subsequent read. The migration's own header states the design intent explicitly — it is a "may-we-send gate" for an identified recipient.

**Measurement 3 — and there is nothing to record.** Measured across `frontend/app`, `frontend/components`, `frontend/lib`: **zero** analytics/tag/third-party scripts. Every item of browser storage is strictly necessary (full inventory below). Under UK PECR reg. 6(4), storage strictly necessary for a service the subscriber explicitly requested needs **no consent** — so today there is no consent event to persist, no proof obligation, and no subject to key a row by.

**Recommendation, with the reasoning stated so it can be overturned by a fact rather than a preference:**

- Ship the consent store as a **client module** (`localStorage`, versioned key) plus a **gating API** (`isAllowed(category)` / `register(category)` / `onChange`). The UI-SPEC already contracts the dismissal key `jtoye-cookie-notice-ack` holding a **version string**.
- Add **no Flyway migration and no table**. This is not deferral — it is the correct tier (see Responsibility Map): the object being gated is a browser script, and a server row cannot prevent a `<script>` from executing.
- **When** a real non-essential category is first registered (deferred, per CONTEXT), the question genuinely re-opens, because a proof-of-consent obligation appears. At that point the correct shape is a **new, deliberately non-tenant-scoped** table, exempted by addition in `RlsContractTest.EXEMPT_TABLES` exactly as `postcode_centroid` was — never a reuse of `marketing_opt_in`.

**If the planner nonetheless wants a table now** (e.g. the owner reads D-05's "consent store" as server-side), the exemption template is already written and must be followed literally. `RlsContractTest.everyPublicTableHasRlsAndForce` walks `pg_class WHERE relkind='r' AND relnamespace='public'::regnamespace` and asserts BOTH `relrowsecurity` AND `relforcerowsecurity` unless the name is in `EXEMPT_TABLES` (`core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java:95-134`). The `postcode_centroid` entry (lines 126-134) is the model: it states what the table holds, why there is no tenant dimension, and — crucially — **why adding RLS would be worse than not adding it** ("with no tenant_id there is no predicate to write, so a FORCE'd policy would return zero rows to every caller"). The same argument holds verbatim for a pre-identity consent row. A second sweep (`D-13` companion) additionally requires every RLS-enabled table to carry ≥1 policy, so a half-measure fails too.

---

### Q2 — How does the `@axe-core/playwright` gate actually run in CI?

**Answer: put it inside the existing `frontend-e2e` job in `ci-cd.yaml`. That is the only browser job that runs per-PR, it is deliberately stack-free, and it already builds and starts the frontend and installs chromium — so the marginal cost is scan time alone, measured at ~31 s for 7 routes plus the dish modal across both viewports.** `[VERIFIED: ci-cd.yaml, e2e-nightly.yml, playwright.config.ts, playwright --list, live run]`

**Measurement — the "2 of 126" figure in both CONTEXT.md and the workflow's own header comment is STALE.**

| Quantity | Quoted in inputs | Measured 2026-08-15 |
|----------|------------------|---------------------|
| Full suite size | 126 tests | **220 tests in 20 files** (`npx playwright test --list`) |
| Per-PR browser tests | 2 | **20** (`public-layout.spec.ts` = 10 per project × 2 projects) |

The ratio has not changed materially (20/220 ≈ 9%), and the conclusion is unchanged, but the numbers must not be re-quoted forward.

**The two jobs, precisely:**

| | `frontend-e2e` (ci-cd.yaml:243-334) | `e2e-nightly.yml` |
|---|---|---|
| Trigger | every `push` to `main`/`phase-*`/`phase/**`, every PR | `schedule: 0 2 * * *` + `workflow_dispatch` **only** |
| Runs | `npx playwright test e2e/public-layout.spec.ts` (one line, ci-cd.yaml:321) | the whole suite |
| Stack | **none** — the spec stubs `**/public/**`; job builds + `next start`s the frontend only | full compose (12 services, ~20 min build + ~20 min suite) |
| Path filter | `dorny/paths-filter` on `frontend/**` + the workflow itself; **reports SUCCESS when skipped** so it stays a satisfiable required check | n/a |
| Blocks a PR | **yes** | **no** |

**Therefore, concretely:**

1. **The E2E half of D-10 must live in a spec that the `frontend-e2e` job executes.** Two shapes work; prefer (b):
   **(a)** append the axe blocks to `e2e/public-layout.spec.ts`;
   **(b)** add `e2e/public-a11y.spec.ts` and extend the run line to `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts`. (b) keeps the layout gate's file digest and skip-budget accounting unentangled from a11y churn, and the run line is a one-token edit.
2. **KEEP IT STACK-FREE.** The job's own header is emphatic: *"The moment this spec needs a backend it stops running in CI and the blind spot comes back."* This is satisfiable — a stack-free axe scan of the storefront and the dish modal is achievable today using the spec's existing `resolveStorefrontPath()` / `openStorefront()` helpers, which resolve a storefront at runtime precisely because the fixture slug 404s once a real backend is reachable.
3. **Checkout needs a seeded basket, not a backend.** `/shop/[slug]/checkout` renders "Nothing to checkout" (`page.tsx:261-274`) when `items.length === 0` — that branch has an `<h2>` and **no `<h1>`**. Seed `localStorage["jtoye-cart-<slug>"]` via `page.addInitScript` before navigating (the key is `CART_KEY_PREFIX` = `"jtoye-cart-"`, `lib/cart-identity.ts:43-47`). Without that the scan is vacuous — and I reproduced exactly that artefact (see § Correction 4).
4. **The nightly suite is the right home for the *deep* browser assertions** that genuinely need a stack — focus-order traversal on seeded data, live-region announcements, the existing `storefront-dish-modal-a11y.spec.ts` (which states it measures "against the live stack"). Those are valuable and must not be the *gate*.
5. **Two projects, both required.** The QA council measured mobile ≠ desktop (`/competitive` nearly doubled at 390 px, and a mobile-only `scrollable-region-focusable` appeared). On D-09's declared surfaces I measured mobile and desktop **identical** today — but that is a fact about today's markup, not a property, and the retention table (S2a) is precisely the kind of element that introduces a mobile-only `scrollable-region-focusable`. Run both projects; `playwright.config.ts` already defines them.

**Measured cost:** my equivalent scan (7 routes + modal open, axe injected, non-vacuity assertions) ran **18.3 s** on `--project=desktop` and **30.7 s** across both projects. The job's own comment budgets "~1 min including the build".

**A third layer nobody has counted, available for free:** `eslint-plugin-jsx-a11y@6.10.2` is already installed transitively via `eslint-config-next@16.2.12`, and `npm run lint` already runs unfiltered in the `lint` job on every PR. Measured with `eslint --print-config`: **6 of ~35 jsx-a11y rules are currently enabled** (`alt-text`, `aria-props`, `aria-proptypes`, `aria-unsupported-elements`, `role-has-required-aria-props`, `role-supports-aria-props`) — the `next/core-web-vitals` subset. Enabling the plugin's `recommended` set is a config edit with **zero new packages and zero new CI minutes**, and it catches the class axe provably cannot (`label`-less controls hidden behind a `placeholder` — the QA council proved axe accepts `placeholder` as an accessible name, A11Y-13). Recommend it as a Wave-1 task.

---

### Q3 — Which retention periods does the system actually ENFORCE in code today?

**Answer: four, all config-declared and all driven by `@Scheduled` jobs or a cookie `Max-Age`. Everything else is on-request or indefinite and must ship marked `Operational`. One published config key is enforced by nothing at all.** `[VERIFIED: source read + two-arm consumer search]`

| # | Data category | Enforced by (file:line) | Period | Config key | Verdict |
|---|---------------|--------------------------|--------|-----------|---------|
| R-1 | Abandoned DRAFT orders (incl. guest PII on the order row) | `core-java/.../config/ScheduledCleanupService.java:57` `@Scheduled(cron="0 0 3 * * *")` → `cleanupStaleDraftOrders()`; threshold `:32` | **24 hours** | `cleanup.stale-draft-hours` → `application.yml:718` | **Automated** |
| R-2 | Webhook delivery records | `core-java/.../webhook/WebhookRetentionCleanup.java:50` `@Scheduled(fixedDelayString="${webhook.delivery.retention-interval-ms:86400000}")` → `pruneExpired()` | **30 days** | `webhook.delivery.retention-days` → `application.yml:634` | **Automated** |
| R-3 | Quarantined raw uploads (unvalidated user bytes in MinIO) | `core-java/.../media/MediaQuarantineRetentionSweep.java:104` `@Scheduled(fixedDelayString="${jtoye.media.retention-interval-ms:3600000}")` | **72 hours** (259 200 000 ms) | `jtoye.media.quarantine-retention-ms` → `application.yml:291` | **Automated** |
| R-4 | Customer session cookies (`jtoye-customer-refresh`, `jtoye-customer-id`) | `frontend/lib/customer-auth-cookies.ts:29` `REFRESH_MAX_AGE = 60*60*24*30`; applied at `app/api/customer-auth/login/route.ts:65,69` and `session/route.ts:130,134` | **30 days** | — (a TS constant) | **Automated** (browser-enforced) |
| R-5 | Customer access cookie (`jtoye-customer-access`) | `session/route.ts:124` `maxAge: renewed.expiresAt - now`; the realm sets `accessTokenLifespan=300s` (recorded at `customer-auth-cookies.ts:24-28`) | **5 minutes** | Keycloak realm | **Automated**, but the number lives in the IdP realm — publish as "the sign-in session length set by our identity provider", not a hard-coded 300 s |
| R-6 | Marketing opt-in / suppression rows | **Deliberately never pruned.** `V54__notification_consent.sql` header: *"a GDPR/PECR opt-out must NOT expire … there is deliberately no retention window / prune job on suppression (threat T-22-02-04)"*; restated at `WebhookRetentionCleanup.java:24-27` | **Indefinite by design** | — | **Automated as a NEGATIVE** — gateable by asserting no prune path exists |
| R-7 | Customer PII across `customers`, `orders`, `reviews`, `orders_aud`, `customers_aud`, review photos | `core-java/.../gdpr/GdprService.eraseCustomerData` (anonymise, not delete); proof row in `erasure_records` (V42); `orders_aud`/`customers_aud` UPDATE policies added by V42 exist **only** to permit this scrub | **On request; no time limit** | — | **Operational** |
| R-8 | Envers audit history (`*_aud`) | No deletion path anywhere. V42's header states Envers stays fully enabled and rows are never deleted | **Indefinite** | — | **Operational** |
| R-9 | Financial transactions / completed orders | No deletion path found | **Indefinite** | — | **Operational** — likely a 6-year HMRC statutory floor, but that is a legal position, not a measurement `[ASSUMED]` |
| R-10 | Orphaned product images | **NOTHING.** `cleanup.orphaned-image-days: ${CLEANUP_ORPHANED_IMAGE_DAYS:7}` is declared at `application.yml:719` and has **zero Java consumers** | — | `cleanup.orphaned-image-days` | **UNENFORCED — must not be published** |

**R-10 was verified two-arm with an identically shaped control:**

```
java consumers of orphaned-image-days:  rc=1  out='[]'
CONTROL: stale-draft-hours:             rc=0  out='[…/ScheduledCleanupService.java:4]'
```

Same globs, same flags, same directory. The empty result is a fact about the code, not about the search.

**Consequences for D-07 and D-08:**

1. **The published schedule's `How it's enforced` column has exactly six `Automated` rows and four `Operational` rows.** Publishing R-10 as a 7-day automated deletion would be a false statement in a legally operative document — and it is precisely the drift D-08 exists to catch. Either the phase deletes the dead key from `application.yml` (recommended; it also removes it from `.planning/codebase/INTEGRATIONS.md:268`) or it publishes nothing about orphaned images.
2. **Unit conversion is the trap.** R-3's source of truth is `259200000` ms; the page will say "72 hours". The claim-gate engine has **no unit transform** (verified by reading `scripts/gates/claim-gate.sh` — `kind` is only `json` or `regex`, `shape` is only `int`/`semver`). So the manifest must carry human units and one script must own the ms→hours conversion. See § D-08 gate design.
3. **R-5 is not ours to publish as a number.** It is set on the Keycloak realm. Publish it descriptively; do not gate it against a value in this repo, or the gate becomes a lie the first time the realm changes.

---

## Corrections to Upstream Inputs

Recorded because these are load-bearing, and because a plan built on them would spend effort on non-problems and miss real ones. **No locked decision is affected.**

### Correction 1 — `/shop/[slug]` DOES have a footer and a `contentinfo` landmark

**Claimed** (orchestrator brief; UI-SPEC lines 278, 406): *"`app/shop/[slug]/layout.tsx` wraps only `CartProvider` + `CartDrawer`; no `<footer>` anywhere in the storefront tree"* → *"the ordering surface has NO footer and no `contentinfo` landmark"*.

**Measured — false.** The premise about `app/shop/[slug]/layout.tsx` is correct; the conclusion does not follow, because Next.js nests layouts. `app/shop/layout.tsx:73` renders `<PublicFooter />` over the entire `/shop/**` subtree.

Served HTML (live stack, `curl`), with a negative control:

| Path | HTTP | `<footer>` | `<main>` | `href="/legal` |
|------|------|-----------|---------|----------------|
| `/` | 200 | 1 | 1 | 0 |
| `/legal` | 200 | 1 | 1 | 1 |
| `/shop` | 200 | 1 | 1 | 0 |
| `/shop/brixton-village-grill` | 200 | **1** | 1 | **0** |
| `/shop/brixton-village-grill/checkout` | 200 | **1** | 1 | **0** |

Negative control on the same document: `grep -c "<zzznotpresent"` → `0`. Role resolution in a real browser (`page.getByRole("contentinfo")`) → **1** on every route above.

**What IS true, and is the whole of LGL-01's reachability problem:** `PublicFooter` links `/`, `/shop`, `/shop/orders`, `/track`, `/for-operators`, `/business-model-guide`, `/competitive`, `/auth/signin` — and **no `/legal` route**. The only in-app link to `/legal` anywhere is `components/platform/company-legal.tsx:20`, which renders on platform surfaces only.

**So the UI-SPEC's `StorefrontLegalStrip` is unnecessary for the landmark**, and the `contentinfo` remediation item should be struck. Adding the **Legal column to `PublicFooter`** (UI-SPEC line 277, already contracted) fixes reachability on **every public route including the storefront**, in one component, and does not touch `lib/company.ts:9-12`'s rule at all: the Legal column carries policy links, and `CompanyLegalLine` (the Companies House disclosure) stays where it is. Measured on `/shop/[slug]`: `"Companies House"` occurrences = **0**, `"Ordnance Survey"` = 1 — the footer already renders on a tenant storefront **without** the platform's company identity, so the constraint is already satisfied by construction.

> The planner should treat "add a Legal column to `PublicFooter`" as the single cleanest fix, and record the `StorefrontLegalStrip` component as **not required** — or, if it is retained for the vendor trading-name line the UI-SPEC also wants there, retained *for that reason only*, not for a landmark that already exists.

### Correction 2 — the "220 baseline color-contrast violations" is an all-routes aggregate, not a declared-surface figure

UI-SPEC line 163 attributes ~100 of 220 to the two-background trap. The 220 is real and traceable: `.qa-council/disc-20260802-121732/evidence/a11y-findings.md` §1, *"Aggregate by rule, desktop, all 27 routes: color-contrast 220 …"*. Of those 27 routes, 20 are out of D-09's scope (13 vendor-dashboard routes plus `/for-operators`, `/business-model-guide` (65), `/competitive` (67), `/track`, `/unsubscribe`, `/shop/[slug]/cart`). On D-09's declared list the same run recorded **1 color-contrast node per route**. Today the declared-surface figure is **4 nodes, one root cause**.

### Correction 3 — the dish modal is already remediated; the QA council record predates the fix

`a11y-findings.md` A11Y-03 records the modal reproducing *every* dialog failure on 2026-08-02. That is stale: `components/storefront/product-detail-modal.tsx` now uses Radix with `aria-modal="true"` (`:103`), and my live scan resolved `getByRole("dialog")` count = **1** with the modal open. The UI-SPEC is right to treat it as a **shipped good to preserve**. The council file must not be quoted forward as a live finding.

### Correction 4 — I reproduced the checkout vacuity trap, and it is worth recording as evidence

My first landmark census scanned `/shop/[slug]/checkout` with an **empty basket** and reported `h1: 0, forms: 0, inputs: 0, labels: 0` — and axe duly returned a single `page-has-heading-one` violation and nothing else. The page's real `<h1>Checkout</h1>` is at `page.tsx:595`, behind `items.length > 0`. **A checkout a11y scan without a seeded basket scans a 4-element stub and reports it clean.** This is exactly D-13's artefact class, reproduced in this research session against the current tree. It is the single most likely way this phase ships a vacuous green.

### Correction 5 — the two "preserved" checkout allergen panels are dead code paths today

UI-SPEC's Preserved Goods Ledger keeps the post-order-creation panels at `checkout/page.tsx:423-432` and `:523-535`. Both are guarded on `allergenWarnings.length > 0`. Measured: `PublicStorefrontService.java:737-744` — *"allergenWarnings stays on the confirmation DTO and is **always empty as of 2026-07-30**"*, with `List<String> allergenWarnings = new ArrayList<>()` never appended to. So they render **nothing** today. Preserving them is still correct (the field is explicitly retained as *"the seam a future consented warning path plugs into"*), but the planner must not treat their continued rendering as a regression signal — there is nothing to regress. D-02's aggregate is the natural thing to plug into that seam.

### Correction 6 — the `phase/**` CI-invisibility trap is already fixed

The recorded hazard (a push filter of `phase-*` never matching `phase/NN-…`) does not apply: `ci-cd.yaml:5` reads `branches: [main, 'phase-*', 'phase/**']`. The current branch `phase/31-consumer-safety-legal-floor` **will** trigger CI on push.

---

## Measured Accessibility Baseline — 2026-08-15

**Method.** `axe-core@4.11.2` read from `frontend/node_modules/axe-core/axe.min.js` and injected with `page.addScriptTag`, driven by `@playwright/test@1.62.1` against the live Compose stack (runtime-freshness PASS). Rulesets `wcag2a, wcag2aa, wcag21a, wcag21aa, best-practice`. Counts are **node instances**. Identical results on `--project=mobile` (390×844) and `--project=desktop` (1440×900).

**Instrument falsification, run in the same test before any route was scanned:**

| Arm | Result |
|-----|--------|
| Deliberately broken fixture (`<img>` no alt, empty `<button>`, empty `<a>`, `#eee` on `#fff`) | **10 violations** — `button-name:1, color-contrast:1, document-title:1, html-has-lang:1, image-alt:1, landmark-one-main:1, link-name:1, page-has-heading-one:1, region:2` |
| Clean fixture (`<html lang><title><main><h1>`, black on white) | **0 violations** |

The instrument fires, and zero is reachable. Neither arm is inferred.

**Result — D-09's declared surfaces:**

| Route (D-09 surface) | Total | Rules (nodes) | Non-vacuity evidence |
|----------------------|-------|---------------|----------------------|
| `/` (landing) | **0** | — | h1=1, main=1, title correct |
| `/shop` (listing) | **3** | `color-contrast:3` | h1=1, main=1, **3 shop cards** |
| `/shop/[slug]` (shop page) | **2** | `color-contrast:1`, `landmark-unique:1` | h1=1, main=1, **9 product cards** |
| dish modal, **opened** | **1** | `color-contrast:1` | `getByRole("dialog")` = **1** asserted before scanning |
| `/shop/[slug]/checkout` | **1** ⚠ | `page-has-heading-one:1` | h1=0, forms=0 — **VACUOUS, empty basket. Re-measure with a seeded cart.** |
| `/shop/signin` (customer) | **1** | `heading-order:1` | h1=1, main=1 |
| `/auth/signin` (vendor) | **7** | `landmark-one-main:1`, `page-has-heading-one:1`, `region:5` | h1=**0**, main=**0**, banner=**0**, contentinfo=**0** |
| **Declared-surface total** | **15** | | |
| `/legal` (S2, not in D-09 but in scope) | **1** | `heading-order:1` | h1=1, main=1 |

**These 15 nodes reduce to four root causes plus one re-measurement.**

| Fix | Nodes closed | Where | Note |
|-----|--------------|-------|------|
| **F-A** `text-emerald-600 #059669` on white at 12 px = **3.76:1** (needs 4.5) — the "Free over £X" delivery-threshold string | **4** (3 on `/shop`, 1 on `/shop/[slug]`) | shop card + shop header | `--trust` was **already** moved to emerald-700 (`globals.css` comment: `emerald-600 3.77 → emerald-700 5.48`) and `contrast-tokens.test.ts` asserts it — but these are **Tailwind literals that bypass the token**. This is precisely why the UI-SPEC demands a *second*, literal-scanning contrast test. Fix: `text-emerald-700`. |
| **F-B** two `<nav>` landmarks with no `aria-label` | **1** | `components/storefront/storefront-nav.tsx:49` (header) and `app/shop/[slug]/shop-detail-client.tsx:687` (category strip) | Label both. |
| **F-C** `PublicFooter` column headings are `<h3>` with no `<h2>` above them | **2** (`/shop/signin`, `/legal`) | `components/public/public-footer.tsx:112` and `:143` | Fires **only** on pages whose own content has no `<h2>`; `/` and `/shop` supply one, which is why 2 of 7 routes fire. One component edit, `<h3>` → `<h2>`, closes both. The new Legal column must also be `<h2>`. |
| **F-D** `/auth/signin` has no page shell at all | **7** | `app/auth/signin/page.tsx` (97 lines, no `<main>`, no `<h1>`, no `PublicShell`) | Wrap in a shell providing `main` + `h1`. **Also fixes the stale `<title>`**: it currently serves the root default *"J'Toye OaaS - Multi-Tenant Order Management"*, an SEO and orientation defect the a11y count does not show. |
| **F-E** re-measure checkout with a seeded basket | 1 → unknown | see Correction 4 | The `page-has-heading-one` is real for the *empty* state; the populated state is unmeasured. |
| **F-F** identify the modal's single `color-contrast` node | 1 | dish modal | Not captured in the detail pass; one more scan needed. |

**Not caught by axe, and therefore not closable by the gate** (from the QA council run, still live):
`A11Y-06` no skip link on any route (UI-SPEC contracts the fix, reusing `components/marketing/operator-pitch.tsx:70` verbatim) · `A11Y-07` checkout errors carry no `id`, no `role="alert"`, no `aria-invalid`, no `aria-describedby`, and focus is not moved — reconfirmed today: `role=alert`, `aria-invalid`, `aria-describedby` counts are **0 on all seven declared surfaces** · `A11Y-08` no `autocomplete` tokens on any of the 8 checkout inputs (WCAG 1.3.5 AA) · `A11Y-13` placeholder-as-label, which the council **proved** axe accepts as an accessible name · `A11Y-14` visible `*` required markers disagree with programmatic `required` · `A11Y-16` `<button>` nested in `<a href>` (16 instances).

> **A11Y-08 deserves the planner's attention.** It is a **WCAG 2.1 level AA** failure on a declared surface, so it is squarely inside D-09 + D-12's claim — and axe does not detect it. A "zero violations" gate that ships alongside an unlisted 1.3.5 failure makes the conformance statement wrong. Either fix it (8 `autocomplete` attributes) or **name it as an exception with a remediation date** per D-12. Do not let the gate's silence stand in for conformance.

---

## Standard Stack

### Core (new devDependencies)

| Package | Version | Purpose | Why standard |
|---------|---------|---------|--------------|
| `@axe-core/playwright` | **4.13.0** (`[VERIFIED: npm view]`, published 2026-08-11) | `AxeBuilder({page}).analyze()` in the per-PR browser job | **The** integration named in Playwright's own accessibility-testing guide `[CITED: github.com/microsoft/playwright/blob/main/docs/src/accessibility-testing-js.md via Context7 /microsoft/playwright]`. Maintained by Deque (`github.com/dequelabs/axe-core-npm`). |
| `axe-core` | **4.13.0** (`[VERIFIED: npm view]`) | The engine | **Pin it directly.** It is present today only as a transitive dep of `eslint-plugin-jsx-a11y` at **4.11.2**. Depending on a transitive version means an unrelated eslint bump silently changes which rules the gate runs. |
| `jest-axe` | **10.0.0** (`[VERIFIED: npm view]`) — **not 11** | `expect(await axe(container)).toHaveNoViolations()` in jsdom | See the version note below. |

**Version note — this is the one real friction point.** `jest-axe@11.0.0` declares `dependencies: { "jest-matcher-utils": "30.4.1", "axe-core": "4.12.1" }` (an exact pin), while this repo runs `jest@29.7.0` with `jest-matcher-utils@29.7.0` installed. `jest-axe@10.0.0` depends on `jest-matcher-utils@29.2.2`, matching the installed major. Neither declares a `peerDependencies` on jest (v11's only peer field is `{ node: ">= 18.0.0" }`), so npm will nest whichever it wants and **both will probably install cleanly** — but v10 is the version that does not put a second major of jest's matcher utilities into the tree. Choose **v10** and record the reason; revisit when the repo moves to jest 30.

> The repo already carries one jest/jsdom major mismatch — `jest@29.7.0` with `jest-environment-jsdom@30.4.1` — which works. That is precedent that the tree tolerates it, not a reason to add another.

**Alternative worth naming:** `jest-axe` is a thin matcher over `axe.run()`. Calling `axe.run(container)` directly and asserting `result.violations` costs one helper function and removes the dependency question entirely. If the planner wants the minimum new surface, that is the option; `jest-axe`'s value is the readable failure output, which is not nothing on a gate people must debug.

### Zero-cost layers already in the tree — use these first

| Capability | Already present | Action |
|------------|-----------------|--------|
| Static a11y lint on every PR | `eslint-plugin-jsx-a11y@6.10.2` via `eslint-config-next@16.2.12`; `npm run lint` runs unfiltered in the `lint` job | **6 of ~35 rules enabled.** Extend in `frontend/eslint.config.mjs`. No package, no CI minutes. |
| Contrast assertion computed from source | `frontend/__tests__/contrast-tokens.test.ts` (134 lines, 8 tests, **green**), including a test literally named *"extracts tokens at all — the instrument can see the file"* | Extend for CSS-variable pairings; **add a sibling** for Tailwind literals (F-A proves the gap) |
| Non-vacuity control precedent | the same test's instrument check; `.qa-council/.../a11y-00-instrument-falsification.json` | Copy the shape |
| Declarative doc-claim gate | `scripts/gates/claims.manifest` + `scripts/check-claims.sh` (wired in `ci-cd.yaml`) | Half of D-08, with **no new script** |
| Radix `Dialog`/`Sheet` a11y | `components/ui/dialog.tsx`, `sheet.tsx` | Never hand-roll an overlay — the dish modal cost a full remediation cycle |

### Added from the shadcn official registry

| Block | Why | Status |
|-------|-----|--------|
| `checkbox` | S3's acknowledgement control | `@radix-ui/react-checkbox` is **absent** from `frontend/package.json` (installed Radix: alert-dialog, dialog, dropdown-menu, label, select, slot, tabs, toast). Radix Checkbox renders a real hidden native input, so `aria-invalid` and form semantics work. UI-SPEC requires resizing to 24 px in a 44 px row. |

**Installation:**
```bash
cd frontend
npm i -D @axe-core/playwright@4.13.0 axe-core@4.13.0 jest-axe@10.0.0 @types/jest-axe
npx shadcn@latest add checkbox
```

---

## Package Legitimacy Audit

**slopcheck could not be run in this session, and the reason is recorded rather than worked around.** `pip install slopcheck` is blocked by a repository/user hook (`block-base-python.py`) that refuses any Python execution in the base conda environment; this project declares no `.conda-env`. Per the project's own doctrine, a blocked command is the answer — it was not rerouted. **Every package below is therefore tagged `[ASSUMED]` and the planner MUST gate each install behind a `checkpoint:human-verify` task.**

| Package | Registry | Latest | Weekly downloads | Source repo | `postinstall` | slopcheck | Disposition |
|---------|----------|--------|------------------|-------------|---------------|-----------|-------------|
| `axe-core` | npm | 4.13.0 (2026-08-06) | **64,662,861** | `github.com/dequelabs/axe-core` | none | **UNAVAILABLE** | `[ASSUMED]` — gate before install |
| `@axe-core/playwright` | npm | 4.13.0 (2026-08-11) | **8,150,768** | `github.com/dequelabs/axe-core-npm` | none | **UNAVAILABLE** | `[ASSUMED]` — but the **name** is `[CITED: Playwright official docs]`, which is the strongest non-slopcheck provenance available |
| `jest-axe` | npm | 11.0.0; **recommending 10.0.0** | **2,485,529** | `github.com/NickColley/jest-axe` | none | **UNAVAILABLE** | `[ASSUMED]` — gate before install |
| `@radix-ui/react-checkbox` | npm | — (via `shadcn add checkbox`) | — | `github.com/radix-ui/primitives` | none | **UNAVAILABLE** | `[ASSUMED]` — same org as 8 already-installed Radix packages |

**Packages removed due to a `[SLOP]` verdict:** none (no verdicts were obtainable).
**Packages flagged `[SUS]`:** none (same reason).

Registry existence was confirmed with `npm view` on the **correct ecosystem** (npm, for a Node.js phase) and `scripts.postinstall` / `scripts.install` were checked and are empty for all three. Download volume and an authoritative source repo are corroborating signals, **not** substitutes for slopcheck — a slopsquatted package also passes `npm view`.

---

## D-08 Gate Design — and the exact wave-ordering constraint

### The constraint, measured in both directions

`scripts/check-gate-enforcement.sh` is wired in `ci-cd.yaml`. Its logic (lines 102-155):

- `refs > 0` (the basename appears in **any** file under `.github/workflows/`) → **short-circuits `continue` before the conf is ever consulted**;
- else if a conf entry exists → passes, **unless** the script invokes no runtime binary, in which case the entry is "stale" → **exit 1**;
- else → **exit 1**;
- separately, a conf entry naming a file that is not a `scripts/check-*.sh` → **exit 2 (VOID)**.

**Measured on this tree, three arms, restore verified by content hash:**

| Arm | Command | Result |
|-----|---------|--------|
| Baseline | `bash scripts/check-gate-enforcement.sh` | `gates: 35, workflows: 6, exempt: 6` → **rc=0 PASS** |
| **Break A** — an unwired, undeclared `scripts/check-p31probe.sh` | same | `gates: 36` → **rc=1**, naming `check-p31probe.sh` |
| **Break B** — a conf entry naming a script that does not exist | same | **rc=2 VOID**: *"exempts 'check-p31probe.sh', which is not a scripts/check-*.sh"* |
| **Closing clean** | after `rm` + `git checkout` | conf `git hash-object` = `6e3ec550…` = `git rev-parse HEAD:…` (identical); probe absent → **rc=0 PASS** |

**So the precise constraint the planner must sequence around:**

> A new `scripts/check-*.sh` and **at least one workflow reference to it** must land in the **same commit**. A commit adding the script alone reds `check-gate-enforcement.sh` (rc=1). A commit adding a conf entry alone VOIDs it (rc=2). The two failures are in **opposite** directions, so there is no ordering of two separate commits that is green at both points — which is why 33-05 states it as *"Declare the gate's exemption in the SAME task that creates it"* and why 33-06, its wave sibling, runs `check-gate-enforcement.sh` in its own verify block (`33-05-PLAN.md:314-328`). A plan cannot pre-declare a sibling's gate.

**The retention gate is STATIC** (it reads YAML and TS source; it invokes no `docker`/`psql`/`curl`). Therefore it must be **wired into a workflow**, never exempted — a conf entry for it would be rejected as a stale exemption (rc=1) by the very check above.

### Recommended shape: two halves, only one of which needs a script

Mirroring `docs-freshness.yml`'s two-gates-per-loop pattern that D-08 explicitly points at.

**Half B — manifest → published prose. ZERO new scripts, therefore no sequencing hazard.**
`scripts/gates/claims.manifest` already drives a generic engine (`scripts/gates/claim-gate.sh`) whose contract is exactly this shape, and whose **M-1** rule is the falsifiability property D-08 needs:

> *"Every declared rule must match at least once in its file. A rule that matches NOTHING is a FAILURE, not a pass. This is the load-bearing rule: without it, deleting the sentence silently satisfies the gate."*

Add one `source` row and one `rule` row per published number (TAB-separated):

```
source	retention	json	docs/retention-manifest.json	int
rule	retention	draft_order_hours	frontend/app/legal/retention/page.tsx	draft order retention	Abandoned checkouts[^|]*\|\s*\K[0-9]+(?= hours)
```

Fail-closed already: the engine exits **2 (VOID)** on a missing `jq`, a `grep` without `-P`, an absent/empty manifest, a missing consumer file, a bad source value, or **zero comparisons performed**.

**Half A — tree → manifest. ONE new script, landing with its workflow reference.**
`scripts/check-retention-enforcement.sh`, asserting three things the claim engine cannot:

1. every `Automated` row in `docs/retention-manifest.json` names an `enforced_by` file path **that exists** and contains the declared config key or constant (the R-10 case: a published period whose enforcement site is empty → **exit 1**);
2. the value at that site, **converted to the manifest's unit**, equals the manifest number (259 200 000 ms ⇄ 72 hours — the conversion the engine cannot do);
3. R-6's negative: no prune/delete path exists against `notification_suppression` or `marketing_opt_in`.

Exit **0/1/2**, VOID on missing tooling, an unreadable input, or an **empty** discovery set. Assemble any forbidden token from fragments (`'<''<'`) so the script does not match its own definition — the self-match trap `check-no-measured-placeholders.sh:29-33` already documents.

**Suggested wave split:**

| Wave | Lands |
|------|-------|
| n | `docs/retention-manifest.json` + `/legal/retention` page + the `claims.manifest` rows. `check-claims.sh` is already wired, so this is green on its own. |
| n+1 | `scripts/check-retention-enforcement.sh` **and** its `ci-cd.yaml` step **in one commit**, plus the break-arm evidence. |

---

## Architecture Patterns

### System architecture — how a consumer's data and a compliance claim flow

```
                          BROWSER (anonymous, pre-identity)
   ┌───────────────────────────────────────────────────────────────────────┐
   │  CookieNotice ──reads/writes──> localStorage[jtoye-cookie-notice-ack]  │
   │        │                              (version string)                │
   │        └──> consent module: register(cat) / isAllowed(cat) ──┐        │
   │                                                              │        │
   │   [no non-essential category registered today]  ─────────────┴──> gate
   │                                                        blocks a script
   │                                                        until a choice  │
   └───────────────────────────────────────────────────────────────────────┘
        NO SERVER CALL · NO TABLE · NO tenant_id · nothing to key a row by

   PUBLIC ROUTES (SSR, force-dynamic for CSP nonce)
   ┌──────────────┐   ┌──────────────┐   ┌───────────────┐   ┌─────────────────┐
   │ /legal       │   │/legal/privacy│   │/legal/cookies │   │/legal/retention │
   │  (index)     │   │              │   │               │   │  ┌───────────┐  │
   └──────┬───────┘   └──────┬───────┘   └───────┬───────┘   │  │RetentionT.│  │
          │                  │                   │           │  └─────┬─────┘  │
          └──────────────────┴───────────────────┴───────────┴────────┼────────┘
                             │                                        │
                    PublicShell + PublicFooter                        │ numbers
                    (+ NEW "Legal" column ── the ONLY               ┌─▼──────────────┐
                     reachability fix needed; reaches               │ claims.manifest │
                     /shop/[slug] for free)                         │  M-1: a rule    │
                                                                    │  matching NOTHING│
   BUILD / CI                                                       │  is a FAILURE   │
   ┌──────────────────────────────────────────────┐                 └─▲──────────────┘
   │ lint job ── eslint jsx-a11y  (per PR, free)  │                   │
   │ test job ── jest + jest-axe  (per PR, jsdom) │        docs/retention-manifest.json
   │ frontend-e2e ── AxeBuilder   (per PR, STACK- │                   ▲
   │                 FREE, stubbed public API)    │                   │ check-retention-
   │ e2e-nightly ── full suite    (02:00 UTC ONLY)│                   │ enforcement.sh
   └──────────────────────────────────────────────┘                   │ (NEW, ships WITH
                                                                      │  its workflow ref)
                                                        ┌─────────────┴──────────────┐
                                                        │ application.yml @Scheduled  │
                                                        │ customer-auth-cookies.ts    │
                                                        └─────────────────────────────┘

   ALLERGEN CHAIN (LGL-03)                       DSAR (D-16 / D-17)
   Product.allergenMask ─┐                       POST /gdpr/dsar   ← REQUEST THREAD
   Product.ingredientsText ─┤                          │             (never asSystem)
                             ▼                         │ persist intake, return 202
                  AllergenAggregator (NEW, JAVA)       ▼
                  ├─ union of declared masks     ┌───────────────────────┐
                  └─ reconcile: span text names  │ background worker      │
                     an allergen the mask omits  │ for each tenant:       │
                             │                   │   TenantContext.set    │
             ┌───────────────┴───────────────┐   │   pin GUC (set_config) │
             ▼                               ▼   │   asSystem { … }       │
   checkout: aggregate + ACK           KDS: banner│   write erasure_record │
   (client never sees                  + per-item │ (per-tenant TX, never  │
    Customer.allergenRestrictions)      badge     │  one TX spanning all)  │
                                                  └───────────────────────┘
```

### Pattern 1 — Background cross-tenant fan-out (the D-17 template)

**There is no existing `asSystem` caller in production code.** Measured: `SystemPrincipal.asSystem(` appears in `core-java/src/main/java` exactly **once**, at `MediaProcessingWorker.java:298` — **inside a javadoc comment**, not as a call. Real invocations exist only in 6 test files. So **D-17's DSAR worker would be the first production caller**, and it must be built from the class's own contract rather than copied from a working example.

**The critical property, stated in `SystemPrincipal.java:44-50`:**

> *"The marker is an AUTHORISATION declaration, not a tenancy escape. A system caller is still tenant-scoped by RLS exactly as every other caller is … Declaring system work says 'this thread may pass the shop-scope gate'; it says nothing whatsoever about which tenant's rows it can see, and it cannot be used to reach another tenant's data."*

**So `asSystem` alone does NOT give a DSAR job cross-tenant reach.** It must additionally iterate tenants and pin the GUC per tenant. That precedent **does** exist, three times, and they agree:

| Precedent | File | Shape |
|-----------|------|-------|
| `ScheduledCleanupService.cleanupStaleDraftOrders` | `config/ScheduledCleanupService.java:57-110` | `SELECT id FROM tenants` → per tenant: `TenantContext.set` → `TransactionTemplate.execute` → `TenantContext.clear()` in `finally` |
| `WebhookRetentionCleanup.pruneExpired` | `webhook/WebhookRetentionCleanup.java:49-95` | same, **plus** an explicit `pinTenantGuc()` doing `SELECT set_config('app.current_tenant_id', ?, true)` inside the transaction, and a per-tenant `try/catch` so one tenant's failure does not abort the sweep |
| `MediaQuarantineRetentionSweep` | `media/MediaQuarantineRetentionSweep.java` | *"Structural clone of `WebhookRetentionCleanup`: per-tenant, own transaction each"* |

**Copy `WebhookRetentionCleanup`.** It is the most complete of the three (explicit GUC pin + per-tenant error isolation), and the media sweep already establishes cloning it as the house move.

**Two hazards its comments record, both load-bearing here:**

1. **One transaction per tenant, never one spanning all.** `ScheduledCleanupService.java:44-56` records the measured failure: the RLS GUC is transaction-local (`set_config(..., true)`), so under a single transaction tenant A's deferred cascade flushed *after* the GUC had switched to tenant B, FORCE-RLS filtered those rows to 0, and the whole job rolled back having cleaned nothing.
2. **`TransactionTemplate`, not a `@Transactional` private method.** Spring self-invocation bypasses the proxy, so no transaction starts at all and the query runs with a NULL tenant.

**And the boundary D-17 names must be honoured literally:** intake is a request-thread endpoint that must **not** wrap itself in `asSystem`; only the background entry point does. `ShopAccessService.java:640` is the recorded rule.

### Pattern 2 — Extend `uk.jtoye.core.gdpr`, do not fork it

The package is 4 files / 550 lines and already implements Articles 17 and 20:

| File | Lines | Role |
|------|-------|------|
| `GdprController` | 115 | `GET /gdpr/customers/{id}/export`, `DELETE /gdpr/customers/{id}/erase`; class-level `@PreAuthorize("hasRole('admin')")` |
| `GdprService` | 279 | anonymise-not-delete (`[REDACTED]`, `redacted@erased.invalid`), aud scrub, photo delete, erasure record |
| `ErasureRecord` / `ErasureRecordRepository` | 142 / 14 | the V42 proof row |

**What it is today, and why D-16 is genuinely new:** the existing endpoints are **vendor-admin, single-tenant, keyed by `customerId`**. RLS scopes them to the caller's tenant. There is no consumer-facing intake, no email-keyed lookup, and no cross-tenant fan-out. D-16/D-17 add an intake + a worker; they reuse `GdprService.eraseCustomerData` and `ErasureRecord` unchanged.

**One detail the planner must notice:** `GdprController.CustomerExport` includes `Integer allergenRestrictions` — the Article 9 special-category field **is** in the Article 20 export payload today, and `docs/legal/article-9-allergen-basis.md` records that as correct and tested. D-01 does not change it. The privacy notice must describe it accurately, and the DSAR intake must not accidentally widen who can trigger that export.

### Pattern 3 — Legal page shell (already established, do not re-derive)

`frontend/app/legal/page.tsx` (64 lines) is the template: `PublicShell` wrapper, `export const metadata` with `alternates: { canonical: "/legal" }`, identity from `getCompanyInfo()`. The file's own comment records the regression where it rendered a bare `<main>` with no chrome. Four new routes follow it exactly.

### Anti-patterns to avoid

- **Hand-rolling an overlay.** `product-detail-modal.tsx` was the only hand-rolled `fixed inset-0` overlay in the app and produced a complete dialog-contract failure (no `role`, no Escape, no trap, no scroll lock, no initial focus, background not inert). Use Radix.
- **Trusting a token fix to cover a Tailwind literal.** `--trust` is emerald-700 and `contrast-tokens.test.ts` is green — while `text-emerald-600` fails on 4 nodes. The token test reads `globals.css`; it can never see a utility class.
- **A per-tenant loop in one transaction.** See Pattern 1 hazard 1.
- **A bare `UPDATE` in a migration against a FORCE-RLS table.** Recurred at V25, V44, V57 — it hits zero rows. Loop tenants with `set_config`.
- **Naming a property in `${…}` form inside a migration comment.** Flyway substitutes placeholders inside comments and aborts startup everywhere.
- **`display: block` restyling of the retention table at narrow widths**, or a duplicated mobile-only `<dl>`. The first strips table semantics; the second is the duplicated-DOM trap this repo filed twice as a product bug (#556, #593).

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| Accessibility rule evaluation | a custom DOM checker | `axe-core` 4.13.0 | ~90 rules, WCAG-mapped, actively maintained; the rules you would write are the easy 10% |
| Dialog semantics (focus trap, Escape, scroll lock, inert background, focus return) | a `fixed inset-0` div | Radix `Dialog` / `Sheet` via `components/ui/dialog.tsx` | Already the fix that closed A11Y-03; re-hand-rolling reopens six defects at once |
| Checkbox with `aria-invalid` + real form semantics | a styled `<div role="checkbox">` | `@radix-ui/react-checkbox` via `shadcn add checkbox` | Renders a real hidden native input |
| Doc-claim ↔ source-of-truth assertion | a bespoke `check-*.sh` per claim | a row in `scripts/gates/claims.manifest` | The engine already enforces M-1 (a rule matching nothing FAILS) and M-2, and fails closed at exit 2 on six distinct unreadable conditions. It exists *because* four bespoke scripts had independently converged on the same design. |
| Cross-tenant background iteration | a new tenant-loop helper | clone `WebhookRetentionCleanup` | Encodes two measured failure modes (transaction-local GUC, self-invocation proxy) |
| Article-17 anonymisation | a new erasure routine | `GdprService.eraseCustomerData` + `ErasureRecord` | Already reaches guest orders, `*_aud` history and S3 photos, with V42's UPDATE policies existing solely to permit it |
| Ingredients markup parsing | a second parser | `IngredientMarkupParser.parse()` → `ParsedIngredients(plainText, List<AllergenSpan>)` | Consumed by the PPDS/Natasha's-Law label pipeline; a second parser would diverge from a legally operative label |
| Allergen emphasis rendering on the consumer side | re-parsing `**` in React | `components/ui/ingredient-text.tsx` | Named in the UI-SPEC's reuse table |
| JSON-LD serialisation | inline `<script>` string building | `serialiseJsonLd()` in `lib/structured-data.ts` | Handles escaping; already used by shop/product nodes |
| Company identity in prose | a hardcoded name/number | `getCompanyInfo()` | Env-overridable for white-label; and it keeps the **dissolved** namesake `13434105` out of legal prose |

**Key insight:** every one of these already exists in the tree with a written record of the defect that motivated it. In this phase the expensive mistake is not writing a bad implementation — it is writing a *second* implementation of something whose first version encodes a measured failure.

---

## The Allergen Chain (LGL-03) — what exists and what does not

### Already exists

| Asset | Location | Note |
|-------|----------|------|
| Recorded Article 9 determination | **`docs/legal/article-9-allergen-basis.md`** (122 lines, dated **2026-07-30**) | **D-01 is substantially already written.** It records: the vendor is controller / J'Toye is processor; Art. 9(2)(a) explicit consent is the only realistic condition; (c)/(h)/(e) each excluded with reasons; the guest `customerAllergenMask` field was **removed** on data-minimisation grounds; Arts. 17 and 20 are implemented **and tested** over the field. It also carries ready DPA wording and lists *"Write the privacy notice — there is currently none"* as its own recommended step 4. **This phase dates and extends it; it must not contradict it.** |
| Derivation clause (DPA-adjacent draft) | `docs/legal/derivation-clause.md` (DRAFT, 2026-07-30) | Clause M.5 excludes Article 9 data from all derivation. D-18's Article 26 document belongs beside these two, in `docs/legal/`. |
| Ingredients parser | `product/IngredientMarkupParser.java:56` → `ParsedIngredients(String plainText, List<AllergenSpan> spans)` | `AllergenSpan` is `record AllergenSpan(int start, int end)` — **offsets only** |
| PPDS label pipeline | `product/ProductLabelService.java` | Consumes the same data; D-03 must not break it |
| Vendor-facing Article 9 notice | `frontend/app/dashboard/customers/__tests__/allergen-consent-notice.test.tsx` | Places the Art. 9 duty on the vendor; S3's copy must agree with it from the other side |
| Consumer-side allergen rendering | `components/storefront/product-detail-modal.tsx:69` `ALLERGENS.filter((a) => hasAllergen(product.allergenMask, a.bit))` | The hand-typed integer, rendered |
| The 14-allergen bit table | **`frontend/types/api.ts:490-516`** — `ALLERGENS` + `hasAllergen(mask, bit)` | **Frontend only** |
| The empty seam D-02 plugs into | `GuestOrderConfirmation.allergenWarnings`; `PublicStorefrontService.java:737-744` | Deliberately retained, always empty since 2026-07-30 |

### Does NOT exist — and each is real work

| Gap | Evidence | Consequence |
|-----|----------|-------------|
| **No Java allergen bit→name table.** | Two-arm: `Gluten` in `core-java/src/main/java` hits only `DemoDataSeeder` (seed strings), `CreateProductRequest:80` (a `dietaryTags` OpenAPI example) and `BulkImportService:60` (a CSV template) — no mapping. Control: `frontend/types/api.ts` = 1 hit, the real table. The old `ALLERGEN_NAMES` **was deleted** from `PublicStorefrontService` on 2026-07-30 (recorded in the Art. 9 doc). | D-04 puts the aggregate on the **KDS**, which is fed from the backend. A Java-side table is unavoidable. It is *product* allergen data — not special category — so this is safe. The two copies must be kept in sync by a test that reads both. |
| **`AllergenSpan` carries offsets, not allergen identity.** | `record AllergenSpan(int start, int end)`, 17 lines. | D-03's reconciliation ("the ingredients text mentions PEANUTS which the mask omits") needs span-text → allergen-bit resolution. That mapping does not exist in either language. This is the substantive new logic in LGL-03. |
| **`OrderItem` carries no allergen data.** | Fields are `id, tenantId, order, productId, productName, quantity, unitPricePennies, totalPricePennies, createdAt`. `@Audited` at `OrderItem.java:16`. | Aggregation must either **join back to `Product` by `productId` at read time** or **snapshot the mask onto `order_items` at write time**. See Pitfall 5 — this is a safety-relevant decision, not a style one. A snapshot requires a Flyway migration **and** an `order_items_aud` mirror column, which in turn moves the schema version quoted in four documents (constraint C-11). |
| **The two "existing" checkout panels render nothing today.** | See Correction 5. | Not a blocker; a correction to the Preserved Goods Ledger's expected behaviour. |
| **KDS order items carry only quantity + name.** | `app/dashboard/kitchen/page.tsx:875-884` — an inline comma-joined 12 px `<span>` run over `item.quantity`/`item.productName`. | The order DTO the KDS consumes must gain the allergen fields before S4 can render anything. |

**The reconciliation D-03 asks for is the same defect the QA council filed as `A11Y-02` (CRITICAL):**

> *"`ingredientsText` is free text, `allergenMask` is a separate checkbox group … with no cross-check in either direction. Proven at runtime: with `allergenMask: 0` and `**milk**` in the text, the emphasis renders and the allergen panel is **absent** — a sighted user sees a bolded allergen, a blind user gets nothing at all. Severity is Critical because this is the one surface in the product that can physically injure a consumer, and because the failing direction is *under*-declaration."*

D-03 is that finding's fix. The planner should say so, and reuse the council's runtime evidence (`a11y-storefront-allergen-edge.json`, `a11y-allergen-render.json`) as the pre-fix control arm.

> ⚠ `.qa-council/` is **gitignored**. Any evidence quoted from it into a plan or a committed doc must be **copied**, not linked.

---

## Browser-storage Inventory (LGL-01, the cookie policy's factual basis)

The cookie policy must be exhaustive and accurate; under PECR, `localStorage` is storage on terminal equipment exactly as a cookie is. Enumerated across `frontend/app`, `frontend/lib`, `frontend/components`, excluding `__tests__`.

**HTTP cookies** — all `httpOnly`, `sameSite: "lax"`, `secure` in production (`lib/customer-auth-cookies.ts:31-38`):

| Name | Lifetime | Purpose |
|------|----------|---------|
| `jtoye-customer-access` | access-token lifetime (realm `accessTokenLifespan` = 300 s) | customer session |
| `jtoye-customer-refresh` | 30 days (`REFRESH_MAX_AGE`) | session renewal |
| `jtoye-customer-id` | 30 days | customer identifier |
| NextAuth session cookies | per NextAuth defaults (`frontend/auth.ts`) | **vendor dashboard** session — not in the three-cookie constant, and must not be omitted from the policy |

**`localStorage`:** `jtoye-cart-<slug>` (per-shop basket; `CART_KEY_PREFIX`, `lib/cart-identity.ts:43`) · `jtoye-customer-id` (Keycloak `sub`, mirrored; `:62`) · `jtoye-customer-logged-in`, `jtoye-customer-expires-at` (`lib/customer-auth.ts:36-37`) · `jtoye-guest-orders` (`lib/order-history.ts:17`) · **`jtoye-checkout-email-<slug>`** (`checkout/page.tsx:331,353`) · `shopContext` (`lib/shop-context.ts:17`) · `kds-muted`, `theme`.

**`sessionStorage`:** `jtoye-auth-return` (`lib/customer-auth.ts:211,237`) · **`jtoye-track-email`** (`app/shop/orders/orders-client.tsx:119`).

**Three things the policy must get right:**

1. **Two keys hold an email address** — `jtoye-checkout-email-<slug>` (localStorage, **no expiry**) and `jtoye-track-email` (sessionStorage, cleared on tab close). That is personal data in browser storage and must be disclosed, with its retention (localStorage = until cleared).
2. **`jtoye-customer-tokens` and `jtoye-customer-profile` are only ever REMOVED** (`lib/customer-auth.ts:108-109`), never written — legacy cleanup. Do **not** list them as stored.
3. **Say "cookies and browser storage", never "cookies only"** — the UI-SPEC already contracts this wording, and it is the accurate phrase.

---

## SEO / Discoverability (standing dimension C-7)

`/legal/*` are public unauthenticated surfaces, so the dimension applies in full.

| Requirement | Status | Action |
|-------------|--------|--------|
| Unique `title` + `description` per page | UI-SPEC fixes the strings | `export const metadata` per route |
| `canonical` per page | pattern at `app/legal/page.tsx:6-11` | `alternates: { canonical: "/legal/<slug>" }` |
| Present in `sitemap.xml` | **`/legal` is NOT in `STATIC_ROUTES`** (`app/sitemap.ts` lists `/`, `/shop`, `/for-operators`, `/business-model-guide`, `/track` only) | Add all five legal routes |
| Not `noindex` | `app/robots.ts` `DISALLOW` does not include `/legal` | No change |
| Crawlable `<a href>` nav | the Legal footer column supplies it | — |
| schema.org JSON-LD | `lib/structured-data.ts` exports `serialiseJsonLd`, `abs`, `productNode`, `shopStructuredData`, `shopListStructuredData`, and a `BreadcrumbList` builder. **No `Organization`/`WebPage` node exists.** | **A policy page does not warrant `Product` or `Restaurant`.** The defensible reuse is the existing **`BreadcrumbList`** for the `/legal` → `/legal/<slug>` hierarchy. An `Organization` node on `/legal/privacy` (built from `getCompanyInfo()`) is optional and low-risk. Do not invent a bespoke type. |
| Duplicate content | if a bare `/accessibility` is wanted, it must be a **308 redirect** | UI-SPEC line 583 |

**A blocking content gap.** `getCompanyInfo().registeredOffice` defaults to `""` and `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is set **nowhere** — not in `.env.example`, not in `docker-compose.full-stack.yml`, not in `frontend/Dockerfile`. UK GDPR Art. 13(1)(a)-(b) requires the controller's identity **and contact details** in the privacy notice. A notice that renders a blank address, or a DSAR contact link that resolves to nothing, fails its own requirement — and F15 in the UI-SPEC's Falsifiability Register already asserts the contact route must resolve. **The phase must configure a real contact.** The established pattern is `NEXT_PUBLIC_SUPPORT_EMAIL` / `NEXT_PUBLIC_SUPPORT_URL` (`.env.example:257-260`, `docker-compose.full-stack.yml:434-435`, `frontend/Dockerfile:64-67`, consumed via `lib/env-validation.ts:68-76` which degrades to plain copy when unconfigured) — mirror it, and note these are **build args** because `NEXT_PUBLIC_*` is inlined at build time.

---

## Common Pitfalls

### Pitfall 1 — An axe scan over a page that did not render

**What goes wrong:** `violations.length === 0` on a page with four elements on it.
**Why:** this project's paid-for instance was tables that never mounted. I reproduced a fresh one this session (Correction 4): `/shop/[slug]/checkout` with an empty basket renders a 4-element stub with **no `<h1>`, no form, no inputs** — and axe reports one moderate violation and nothing else.
**Avoid:** every axe assertion carries a non-vacuity control **in the same test**, per D-13. Measured values to assert against (2026-08-15): `/shop` ≥ 3 `article` cards · `/shop/[slug]` ≥ 9 `article` cards · dish modal `[role="dialog"]` **= 1** before scanning · checkout ≥ 1 order-summary line item · every route `main` = 1 and (except `/auth/signin` until F-D) `h1` ≥ 1.
**Warning sign:** a route's violation count **drops** without a corresponding markup change.

### Pitfall 2 — Fixing the token and believing the literal is fixed

`--trust` is emerald-700 and `contrast-tokens.test.ts` is green — while `text-emerald-600` fails AA on four nodes. The token test reads `globals.css` and is structurally incapable of seeing a utility class. **Add a second test that scans component sources for Tailwind colour literals and recomputes their ratio from the Tailwind palette**, per the UI-SPEC. Break arm: change the KDS banner's `amber-800` to `amber-400` and the ratio must drop below 4.5.

### Pitfall 3 — A gate that only runs nightly

`e2e-nightly.yml` has no `push` or `pull_request` trigger. A spec placed anywhere other than the `frontend-e2e` job's run line cannot block a PR. The job header states the constraint that makes this a trap: *"KEEP IT STACK-FREE. The moment this spec needs a backend it stops running in CI and the blind spot comes back."*

### Pitfall 4 — The gate-enforcement double bind

Script without workflow reference → **rc=1**. Conf entry without script → **rc=2**. Opposite directions, so no two-commit ordering is green at both points. Land the script and its workflow reference together. (Arms measured above.)

### Pitfall 5 — Live-joining allergen data for a placed order

If aggregation joins `order_items.productId` → `Product.allergenMask` at read time, a vendor editing the mask **after** the order is placed silently changes what the customer is recorded as having acknowledged and what the KDS ticket shows. The customer acknowledged set A; the kitchen sees set B; no record exists of A. **On the one surface in this product that can physically injure someone, that is the wrong trade.** Snapshot the mask (and the reconciliation flags) onto `order_items` at write time — `OrderItem` already snapshots `productName` for exactly this reason. Cost: a Flyway migration, an `order_items_aud` mirror column (the entity is `@Audited`), and the schema version moving in `docs/metrics.json` + `CLAUDE.md` + `AGENTS.md` + `README.md` (constraint C-11).

### Pitfall 6 — Publishing a retention period nothing enforces

`cleanup.orphaned-image-days: 7` is declared and consumed by nothing (two-arm verified). Publishing it as `Automated` would put a false statement in a legally operative document. Either delete the key or publish nothing about orphaned images.

### Pitfall 7 — Unit drift between the manifest and the page

The claim-gate engine has no unit transform (`kind` ∈ {json, regex}, `shape` ∈ {int, semver}). 259 200 000 ms vs "72 hours" will silently never compare. Own the conversion in one script and keep human units in the manifest.

### Pitfall 8 — `check-geo-attribution.sh` reads the footer you are about to edit

It asserts all **three** OGL lines render (Ordnance Survey, Royal Mail, National Statistics) and that the rendered year matches the committed `SOURCE.md`. It exits **2 (VOID)** if it cannot find the footer. Currently **PASS (year 2026)**. Re-run it after adding the Legal column — a VOID here reads like a missing footer.

### Pitfall 9 — Shell and search traps that have already cost this project time

Every one of these bit me or a predecessor **during this research session**:

- **`rg -rn PATTERN` means `--replace=n`**, not "recursive, line numbers". It silently rewrites matched text in the output. I used it three times and got mangled results before noticing.
- **`cd` inside a compound command persists for the rest of that command.** A later `rg core-java/build.gradle.kts` in the same line ran from `frontend/` and reported "No such file" for a file that exists.
- **A workflow glob of `*.yml` misses `ci-cd.yaml`.** I concluded a gate was unwired; it has 2 references. `check-gate-enforcement.sh` gets this right by enumerating with `find -name '*.yml' -o -name '*.yaml'`.
- **`rg` exits 2 on a permission-denied directory** (`.gradle-docker/`), which is not "no matches". Read the rc; add the glob.
- `cmd | grep -q X` under `pipefail` **inverts** on match — use a here-string. `$?` read after an intervening command reports the wrong command's status.

---

## Code Examples

### Axe scan with a non-vacuity control, per-PR, stack-free

```typescript
// frontend/e2e/public-a11y.spec.ts
// Source pattern: https://github.com/microsoft/playwright/blob/main/docs/src/accessibility-testing-js.md
import { test, expect } from "@playwright/test"
import AxeBuilder from "@axe-core/playwright"

const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]

test("shop listing has no axe violations — and actually rendered", async ({ page }) => {
  await page.goto("/shop")
  await page.waitForLoadState("domcontentloaded")

  // NON-VACUITY CONTROL (D-13) — asserted BEFORE the scan, in the same test.
  // Measured 2026-08-15: /shop renders 3 shop cards, 1 <main>, 1 <h1>.
  // If the page renders empty this fails even though violations would be 0.
  await expect(page.locator("main")).toHaveCount(1)
  await expect(page.locator("h1")).not.toHaveCount(0)
  expect(await page.locator("article:visible").count(),
    "shop cards — a scan over an empty listing proves nothing").toBeGreaterThanOrEqual(1)

  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  expect(results.violations, JSON.stringify(results.violations.map(v => v.id))).toEqual([])
})

test("dish modal has no axe violations — scanned OPEN", async ({ page }) => {
  await page.goto("/shop")
  const card = page.locator('a[href^="/shop/"]:visible').filter({ has: page.locator("article") }).first()
  await card.click()
  await page.locator("article:visible").first().click()

  // The modal is NOT a route. Scanning /shop/[slug] without opening it
  // reproduces this project's paid-for artefact exactly.
  await expect(page.getByRole("dialog")).toHaveCount(1)

  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  expect(results.violations).toEqual([])
})
```

### Checkout scanned with a seeded basket (no backend)

```typescript
// Without this the empty state renders "Nothing to checkout" (page.tsx:261) —
// 4 elements, no <h1>, no form. Measured: axe returns exactly one violation
// over that stub and the scan is meaningless.
// Key shape: CART_KEY_PREFIX = "jtoye-cart-" (lib/cart-identity.ts:43).
await page.addInitScript((slug) => {
  window.localStorage.setItem(`jtoye-cart-${slug}`, JSON.stringify([
    { productId: "p-1", title: "Fixture Dish", quantity: 2, pricePennies: 950 },
  ]))
}, slug)
await page.goto(`/shop/${slug}/checkout`)
expect(await page.locator("[data-order-line]").count()).toBeGreaterThanOrEqual(1) // control
```

### The break arm the roadmap's own wording requires

```typescript
// Run this, record its output next to the clean run, then delete it.
// Measured this session against axe-core 4.11.2:
//   broken fixture -> 10 violations (button-name, color-contrast, document-title,
//                     html-has-lang, image-alt, landmark-one-main, link-name,
//                     page-has-heading-one, region x2)
//   clean fixture  ->  0 violations
test("INSTRUMENT: axe can fail", async ({ page }) => {
  await page.setContent(`<html><body>
    <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
    <button></button><a href="#"></a>
    <p style="color:#eee;background:#fff">low contrast</p></body></html>`)
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations.length).toBeGreaterThan(0)
})
```

### `jest-axe` component layer (per-PR, no browser)

```typescript
// frontend/components/legal/__tests__/retention-table.a11y.test.tsx
import { render } from "@testing-library/react"
import { axe, toHaveNoViolations } from "jest-axe"
expect.extend(toHaveNoViolations)

it("renders an accessible retention table — and rendered at all", async () => {
  const { container, getAllByRole } = render(<RetentionTable rows={FIXTURE_ROWS} />)
  expect(getAllByRole("row").length).toBeGreaterThan(1) // non-vacuity control
  expect(await axe(container)).toHaveNoViolations()
})
```

### Consent gate proven with a fixture category (D-05's own warning)

```typescript
// A zero-category gate CANNOT fail as shipped. Both directions are required.
it("blocks a gated script before a choice, and permits it after", () => {
  consent.register({ id: "fixture-analytics", essential: false })
  expect(consent.isAllowed("fixture-analytics")).toBe(false)   // BLOCK arm
  expect(loadGatedScript("fixture-analytics")).toBe(false)
  consent.accept("fixture-analytics")
  expect(consent.isAllowed("fixture-analytics")).toBe(true)    // PERMIT arm
  expect(loadGatedScript("fixture-analytics")).toBe(true)
})
it("registers no non-essential category in the shipped configuration", () => {
  expect(SHIPPED_CATEGORIES.filter(c => !c.essential)).toEqual([])
})
```

### Cross-tenant background fan-out (the D-17 template)

```java
// Clone of WebhookRetentionCleanup.pruneExpired — the most complete of the three
// precedents (explicit GUC pin + per-tenant error isolation).
// asSystem is an AUTHORISATION declaration only; it grants NO cross-tenant read.
// Reach comes from iterating tenants and pinning the GUC, one TRANSACTION EACH.
@Scheduled(fixedDelayString = "${jtoye.dsar.sweep-interval-ms:60000}")
public void executePendingRequests() {
    for (DsarRequest req : intake.claimPending()) {
        for (UUID tenantId : listTenantIds()) {          // SELECT id FROM tenants
            try {
                TenantContext.set(tenantId);
                transactionTemplate.execute(status -> {
                    pinTenantGuc(tenantId);              // set_config(..., true) — TX-LOCAL
                    return SystemPrincipal.asSystem(() -> gdprService.eraseByEmail(req.email()));
                });
            } catch (Exception e) {
                log.error("event=dsar_tenant_failed tenant={} — continuing: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();                   // pooled threads must be left clean
            }
        }
    }
}
```

> **Do not** wrap all tenants in one transaction: the GUC is transaction-local, and `ScheduledCleanupService.java:44-56` records the measured outcome — a deferred cascade flushed under the *next* tenant's GUC, FORCE-RLS filtered it to 0, `StaleStateException`, whole job rolled back having cleaned nothing.

---

## Runtime State Inventory

Phase 31 is not a rename, but it changes published claims and may add schema, so the same question applies: **after every file is updated, what still holds the old answer?**

| Category | Items found | Action required |
|----------|-------------|-----------------|
| Stored data | **None new.** No table is required (Open Question 1). If the planner adds `order_items.allergen_mask` (Pitfall 5), existing rows need a **tenant-looped backfill** with `set_config` — a bare `UPDATE` hits zero rows under FORCE RLS (recurred V25/V44/V57). | data migration, if snapshotting |
| Live service config | **None.** No n8n/Datadog/Cloudflare surface is touched. The Keycloak realm holds `accessTokenLifespan=300s`, which the cookie policy **describes** but must not hard-code. | none |
| OS-registered state | **None** — no scheduler, launchd or pm2 registration changes. | none |
| Secrets / env vars | **`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is referenced by `lib/company.ts:42` and set NOWHERE** (verified in `.env.example`, `docker-compose.full-stack.yml`, `frontend/Dockerfile`). A DSAR/privacy contact must also be configured — mirror the `NEXT_PUBLIC_SUPPORT_EMAIL`/`_URL` pattern. All are `NEXT_PUBLIC_*`, so they are **build args**, not runtime `environment:` entries. | add to `.env.example` + compose build args + Dockerfile ARG/ENV |
| Build artifacts | `frontend/node_modules` gains three devDependencies → **`npm ci` in CI picks them up from `package-lock.json` only if the lockfile is committed.** Docker images must be rebuilt before any E2E claim (constraint C-4; `check-runtime-freshness.sh` compares `.Metadata.LastTagTime`, not `.Created`). | commit the lockfile; rebuild |
| Published documents | `docs/legal/article-9-allergen-basis.md` (2026-07-30) and `docs/legal/derivation-clause.md` (DRAFT) already exist and make live claims. | **extend and date; do not contradict** |
| Doc metrics | New test files change `jest_blocks`, `jest_files`, `playwright_blocks`, `playwright_specs`; a migration changes `schema_version`. Four documents quote these. | `scripts/docs-freshness.sh --write`, **never arithmetic**; then update README/AGENTS/CLAUDE prose |

---

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Compose stack (frontend, core-java, postgres, keycloak, minio…) | live measurement, E2E | ✓ **running, runtime-freshness PASS 4/4** | — | — |
| `@playwright/test` + chromium | E2E a11y | ✓ | 1.62.1 | — |
| `axe-core` | a11y scans | ✓ **transitively, 4.11.2** via `eslint-plugin-jsx-a11y` | 4.11.2 | pin 4.13.0 directly |
| `eslint-plugin-jsx-a11y` | static a11y lint | ✓ | 6.10.2 | — |
| `jest` + `@testing-library/react` | component a11y | ✓ | 29.7.0 / 16.3.2 | — |
| Node | build/test | ✓ | 24 in CI | — |
| `jq`, `grep -P` | claim-gate engine | ✓ (engine VOIDs without them) | — | — |
| **`slopcheck`** | package legitimacy | ✗ | — | **No fallback.** `pip install` is blocked by `block-base-python.py`; the project declares no `.conda-env`. All packages tagged `[ASSUMED]`; planner must add `checkpoint:human-verify` before each install. |
| `@axe-core/playwright`, `jest-axe`, `@radix-ui/react-checkbox` | D-10, S3 | ✗ not installed | — | none needed — installable, but see above |

**Missing with no fallback:** slopcheck (mitigated by human-verify checkpoints).
**Missing with fallback:** the three npm packages (installable); `axe-core` (a transitive 4.11.2 exists but must not be relied on).

---

## Validation Architecture

### Test framework

| Property | Value |
|----------|-------|
| Frontend unit/component | **Jest 29.7.0** + `jest-environment-jsdom` 30.4.1 + `@testing-library/react` 16.3.2 |
| Frontend config | `frontend/jest.config.js` (via `next/jest`), setup `frontend/jest.setup.js`; `testPathIgnorePatterns` excludes `/e2e/` |
| Frontend E2E | **Playwright 1.62.1**, `frontend/playwright.config.ts`, projects `mobile` (390×844, `hasTouch`) and `desktop` (1440×900), `workers: 1` |
| Backend unit | JUnit 5 via `./gradlew :core-java:test` — `useJUnitPlatform { excludeTags("testcontainers") }` (`core-java/build.gradle.kts:129-133`) |
| Backend integration | `./gradlew :core-java:integrationTest` — `includeTags("testcontainers")`, real Postgres 15 + RLS (`:148-154`) |
| Lint | `npm run lint` → `eslint .` against `frontend/eslint.config.mjs` (flat config; **do not** wrap with `FlatCompat`) |
| Quick run (per commit) | `cd frontend && npx jest <path>` · `./gradlew :core-java:test --tests '<Class>'` |
| Full suite (per wave) | `cd frontend && npm test -- --ci --watchAll=false` · `./gradlew :core-java:test :core-java:integrationTest` · `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` |
| Phase gate | all of the above green + `scripts/docs-freshness.sh` + `scripts/check-doc-metrics.sh` + `scripts/check-claims.sh` + `scripts/check-gate-enforcement.sh` + `scripts/check-geo-attribution.sh` + `scripts/check-runtime-freshness.sh` |

### What is validated where — the four tiers

| Tier | Can prove | Cannot prove | Runs |
|------|-----------|--------------|------|
| **eslint (static)** | ARIA prop validity, `alt-text`, role/aria coherence, missing labels behind a `placeholder` | anything about composed pages, contrast, focus order | every PR, unfiltered, ~seconds |
| **jest + jsdom** | component-scope axe rules, copy strings, consent-gate block/permit logic, contrast recomputed from source, allergen aggregation given fixtures, D-01's "profile never rendered" DOM assertion | **layout, real focus, scroll lock, Escape, contrast as painted** — jsdom has no layout engine | every PR, unfiltered |
| **Playwright (stack-free)** | composed-page axe, landmark structure, heading order, focus visibility, the dish-modal open scan, retention-table `scrollWidth <= clientWidth` at 375 px, CLS | anything needing real backend data (seeded orders, DSAR execution) | **every PR** via `frontend-e2e` |
| **Playwright (nightly, real stack)** | end-to-end journeys on seeded data, live-region announcements, KDS over STOMP | — | 02:00 UTC only — **never a PR gate** |
| **Testcontainers (real Postgres)** | RLS behaviour, per-tenant GUC pinning, `RlsContractTest` schema walk, DSAR fan-out under FORCE RLS with the NOSUPERUSER downgrade | frontend anything | every PR (`integrationTest` job) |
| **Gate scripts** | published-claim ⇄ source agreement, retention enforcement existence, gate wiring, runtime parity | behaviour | every PR |

### Phase requirements → test map

| Req | Behaviour | Type | Automated command | Exists? |
|-----|-----------|------|-------------------|---------|
| LGL-01 | `/legal/{privacy,cookies,retention,accessibility}` return 200 with unique title + description + canonical | e2e | `npx playwright test e2e/public-a11y.spec.ts -g "legal metadata"` | ❌ Wave 0 |
| LGL-01 | `PublicFooter` links all five legal routes; each resolves | e2e | same spec, `-g "legal reachability"` | ❌ Wave 0 |
| LGL-01 | Cookie notice renders, dismisses, persists a **version** string, causes **zero** CLS | e2e + unit | `npx jest components/public/__tests__/cookie-notice` · perf assertion vs `LANDING_CLS_KNOWN_BASELINE = 0.1793` ± `0.02` | ❌ Wave 0 |
| LGL-01 | Consent gate blocks then permits a **fixture** category | unit | `npx jest lib/__tests__/consent` | ❌ Wave 0 — **both arms mandatory** |
| LGL-01 | Retention table fits at 375 px (`scrollWidth <= clientWidth`) | e2e | `npx playwright test e2e/public-a11y.spec.ts -g "retention table" --project=mobile` | ❌ Wave 0 |
| LGL-01 | Published periods equal the manifest | gate | `bash scripts/check-claims.sh` | ✅ engine exists; rows ❌ |
| LGL-01 | Every `Automated` manifest row has a real enforcement site | gate | `bash scripts/check-retention-enforcement.sh` | ❌ Wave 0 (**ship with its workflow ref**) |
| LGL-01 | DSAR intake returns 202 + typed RFC 7807 errors + Idempotency-Key | integration | `./gradlew :core-java:integrationTest --tests '*Dsar*'` | ❌ Wave 0 |
| LGL-01 | DSAR fan-out writes one `erasure_record` per tenant under FORCE RLS | integration (Testcontainers) | same | ❌ Wave 0 |
| LGL-01 | A request thread never enters `asSystem` | unit | `./gradlew :core-java:test --tests '*SystemPrincipalGuard*'` | ✅ exists — extend |
| LGL-02 | Zero axe violations on the 7 declared surfaces + the opened modal, both viewports | e2e | `npx playwright test e2e/public-a11y.spec.ts` | ❌ Wave 0 |
| LGL-02 | Each scan carries a non-vacuity control that fails on an empty page | e2e | same spec | ❌ Wave 0 |
| LGL-02 | The gate fails against a deliberately broken control | e2e (break arm) | run, record, delete | ❌ Wave 0 |
| LGL-02 | Component-level axe on every new component | unit | `npx jest -t "a11y"` | ❌ Wave 0 |
| LGL-02 | Tailwind colour literals clear AA | unit | `npx jest __tests__/contrast-literals` | ❌ Wave 0 |
| LGL-02 | `--primary`/`--destructive`/`--muted-foreground`/`--trust` unchanged | unit | `npx jest __tests__/contrast-tokens.test.ts` | ✅ **8/8 green** — must stay green with **no edit to its expectations** |
| LGL-02 | Statement's `nextReviewDue` is in the future | unit | `npx jest __tests__/accessibility-statement-dates` | ❌ Wave 0 |
| LGL-02 | Statement's contact route resolves (no 404, no unconfigured mailto) | e2e | `npx playwright test e2e/public-a11y.spec.ts -g "contact"` | ❌ Wave 0 |
| LGL-03 | Order aggregate = union of declared masks | unit | `./gradlew :core-java:test --tests '*AllergenAggregat*'` | ❌ Wave 0 |
| LGL-03 | Reconciliation flags a product whose text names an allergen its mask omits | unit | same | ❌ Wave 0 |
| LGL-03 | `ProductLabelService` output is unchanged by D-03 | unit | `./gradlew :core-java:test --tests '*ProductLabel*'` | ✅ exists — regression arm |
| LGL-03 | Java and TS allergen tables agree on all 14 bit↔name pairs | unit | `npx jest __tests__/allergen-table-parity` | ❌ Wave 0 |
| LGL-03 | Checkout refuses submit without acknowledgement; error is `role="alert"`; focus moves | unit | `npx jest app/shop/\[slug\]/checkout/__tests__` | ❌ Wave 0 |
| LGL-03 | Checkout DOM contains **no** value derived from `Customer.allergenRestrictions` | unit | same | ❌ Wave 0 — F7 |
| LGL-03 | Empty allergen set still renders the panel with the honest copy | unit | same | ❌ Wave 0 |
| LGL-03 | KDS banner shows the complete set; badge truncates at 3 + `+N` | unit | `npx jest app/dashboard/kitchen/__tests__` | ❌ Wave 0 |
| LGL-03 | Allergen block appears on the print sheet | unit (CSS/DOM) | same | ❌ Wave 0 |
| LGL-03 | Dish-modal dialog contract stays green | e2e (nightly) | `npx playwright test e2e/storefront-dish-modal-a11y.spec.ts` | ✅ exists — must not regress |

### Sampling rate

- **Per task commit:** the single `npx jest <path>` or `./gradlew :core-java:test --tests '<Class>'` for the file touched (< 30 s).
- **Per wave merge:** full frontend Jest + `npm run lint` + `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` + `./gradlew :core-java:test`; add `:core-java:integrationTest` on any wave touching Java.
- **Phase gate:** everything, plus the gate scripts listed above, plus **the recorded break-arm output for every new assertion**, plus a full container rebuild before any E2E claim.

### Wave 0 gaps

- [ ] `frontend/e2e/public-a11y.spec.ts` — LGL-02 E2E half (+ one-token edit to `ci-cd.yaml:321`)
- [ ] `frontend/__tests__/contrast-literals.test.ts` — Tailwind-literal contrast (F-A's class)
- [ ] `frontend/__tests__/allergen-table-parity.test.ts` — Java ⇄ TS bit table
- [ ] `frontend/lib/__tests__/consent.test.ts` — fixture-category block **and** permit
- [ ] `frontend/components/legal/__tests__/*.a11y.test.tsx` — component axe for the four new components
- [ ] `core-java/.../AllergenAggregatorTest.java` + a Testcontainers DSAR fan-out test
- [ ] `docs/retention-manifest.json` + `claims.manifest` rows
- [ ] `scripts/check-retention-enforcement.sh` **plus its `ci-cd.yaml` reference, same commit**
- [ ] Installs: `@axe-core/playwright@4.13.0`, `axe-core@4.13.0`, `jest-axe@10.0.0`, `shadcn add checkbox` — **each behind a `checkpoint:human-verify`** (slopcheck unavailable)
- [ ] eslint config: extend the jsx-a11y rule set beyond the current 6

---

## Security Domain

### Applicable ASVS categories

| Category | Applies | Standard control |
|----------|---------|------------------|
| V2 Authentication | no (unchanged) | Keycloak OIDC; `/auth/signin` gains a shell, not new auth |
| V3 Session Management | **yes** — the cookie policy documents session cookies | Existing `httpOnly` + `sameSite: lax` + `secure` in prod (`customer-auth-cookies.ts:31-38`). **Document, do not change.** |
| V4 Access Control | **yes** — DSAR intake and the background worker | Intake: an authenticated or rate-limited public endpoint; **must not enter `asSystem`**. Worker: `asSystem` + per-tenant GUC pin. `GdprController`'s `@PreAuthorize("hasRole('admin')")` must not be weakened. |
| V5 Input Validation | **yes** — DSAR intake takes an email | `@Valid` + Jakarta constraints; RFC 7807 via `GlobalExceptionHandler` |
| V6 Cryptography | **yes** — `erasure_records.subject_email_sha256` | Existing SHA-256 hex hash; **never store the plaintext email** (V42's stated rule) |
| V7 Error Handling | **yes** | RFC 7807 typed errors; no PII in messages |
| V13 API | **yes** — new mutating endpoint | Idempotency-Key contract per the standing agent-readiness dimension |

### Known threat patterns for this phase

| Pattern | STRIDE | Standard mitigation |
|---------|--------|---------------------|
| DSAR intake used to enumerate which tenants hold an email | **Information disclosure** | Return an opaque 202 regardless of match; never reveal per-tenant results to the requester |
| DSAR endpoint used as an unauthenticated erasure weapon | **Tampering / DoS** | Verify the requester controls the email (token link) before executing; rate-limit; the existing Bucket4j limiter is per-tenant, so a platform-level endpoint needs its own bucket |
| A request thread reaching `asSystem` | **Elevation of privilege** | `ShopAccessService.java:640`; extend `SystemPrincipalGuardTest` to assert the intake path never declares it |
| `asSystem` misread as a tenancy escape | **Elevation of privilege** | It is not (`SystemPrincipal.java:44-50`). Cross-tenant reach requires the per-tenant GUC loop — that is the control, and it is per-tenant by construction |
| A stale `asSystem` marker on a pooled thread | **Elevation of privilege** | `asSystem` restores the prior value and `remove()`s on the outermost exit; do not "simplify" that `finally` |
| Consent state trusted from the client | **Tampering** | Nothing security-relevant is gated on it — it gates only optional script loading, and there are none today |
| Legal pages rendering unescaped JSON-LD | **XSS** | Use `serialiseJsonLd()`, never string concatenation |
| Privacy notice leaking the dissolved company number `13434105` | **Repudiation / accuracy** | Read identity from `getCompanyInfo()`; `company.ts:5-6` documents the disambiguation |
| Retention gate failing OPEN on missing tooling | **Repudiation** | Exit 2 (VOID), never 0 — C-9 |

---

## State of the Art

| Old approach | Current approach | When changed | Impact |
|--------------|------------------|--------------|--------|
| `next lint` | ESLint 9 flat config (`eslint.config.mjs`), `eslint .` | Next 16 removed `next lint` | **`.eslintrc.json` does not exist** — CLAUDE.md's Conventions section still says it does. Edit `eslint.config.mjs`; do **not** wrap the next configs with `FlatCompat` (the file records that it crashes with a circular-structure error). |
| Hand-rolled storefront overlay | Radix `Dialog` with `aria-modal="true"` | #446/#533, after 2026-08-02 | A11Y-03 is fixed; the QA council record is stale on that point |
| `--primary` orange-600 `#ea580c` | **orange-700 `#c2410c`** (white 3.56 → **5.18**) | #451 | **LOCKED. Do not re-litigate.** `contrast-tokens.test.ts` recomputes it from `globals.css`, so a revert reds the build. `#ea580c` survives only as the decorative `--ember-bright` (3.56 ≥ 3:1 for non-text). |
| `--trust` emerald-600 | emerald-700 (3.77 → 5.48) | same pass | The **token** moved; the Tailwind literal `text-emerald-600` did not — F-A |
| Guest checkout accepted `customerAllergenMask` | Field **removed**; `allergenWarnings` retained as an empty seam | 2026-07-30 | Art. 9 intake channel closed; `PublicStorefrontService.ALLERGEN_NAMES` deleted with it |
| `phase-*` push filter | `[main, 'phase-*', 'phase/**']` | — | The CI-invisibility trap no longer applies |
| Four bespoke doc-claim scripts | one vendored `claim-gate.sh` + `claims.manifest` | 2026-08 | Add a row, not a script |

**Deprecated / outdated in the inputs:**
- "2 of 126 Playwright tests" → **20 of 220** (both figures stale in CONTEXT.md and in `e2e-nightly.yml`'s own header)
- "`notification_consent` (V54)" → the tables are `notification_suppression` and `marketing_opt_in`
- "220 baseline `color-contrast` violations" → an all-27-route aggregate; **4** on the declared surfaces
- "`/shop/[slug]` has no `contentinfo` landmark" → it has one

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | `@axe-core/playwright`, `jest-axe`, `axe-core`, `@radix-ui/react-checkbox` are legitimate packages | Standard Stack | **slopcheck could not run.** Download volume + authoritative repos + (for `@axe-core/playwright`) a citation in Playwright's own docs are strong corroboration, but registry presence alone never proves legitimacy. **Planner must add `checkpoint:human-verify` before each install.** |
| A2 | `jest-axe@10` is the right choice over v11 for jest 29.7 | Standard Stack | Version friction is inferred from declared `dependencies`, not from an install. If v11 installs cleanly, v11's newer axe-core (4.12.1) is preferable. **Try the install; let the result decide.** |
| A3 | Financial/order records carry a ~6-year statutory retention (HMRC) | Q3 row R-9 | A legal position, not a measurement. **Do not publish a number without confirmation** — publish `Operational` / "retained for as long as required by law" or get the figure confirmed. |
| A4 | UK PECR reg. 6(4) exempts strictly-necessary storage from consent | Q1 | Standard reading, but it is the load-bearing premise under D-05's "no banner today". A different reading changes the notice's copy, not its architecture. |
| A5 | A `<footer>` that is a `<div>`'s child inside `<body>` maps to `contentinfo` | Correction 1 | **Mitigated:** verified empirically with `page.getByRole("contentinfo")` = 1, not by reading HTML-AAM. |
| A6 | The dish modal's single `color-contrast` node is a minor text pairing | Baseline table | Not captured in the detail pass — **one more scan owed**. If it is a large-surface pairing the fix could be broader. |
| A7 | Adding four `/legal/*` routes has no measurable effect on the client-JS baseline (953,353 bytes / 21 scripts on `/`) | SEO / web-perf | Only the `CookieNotice` is new on the landing path. UI-SPEC already forbids pulling a date/consent library. **Re-measure against `e2e/perf-budgets.ts`; do not assume.** |
| A8 | The nightly suite currently passes, so new nightly specs will not mask a pre-existing failure | Validation Architecture | Not run in this session (~40 min). Check the last nightly result before treating nightly green as a signal. |
| A9 | `CLAUDE.md`'s "2812 logical invocations / 1638 Java `@Test`" prose is stale but ungated | Project Constraints | Measured: `docs/metrics.json` says **2807 / 1633**, and both gates are **green** — `claims.manifest` checks CLAUDE.md for `total_logical_invocations` and `java_test_methods` by regex, so the mismatch means those regexes do not currently match the prose. Not this phase's defect, but a `--write` regeneration may surface it. |

---

## Open Questions (ALL RESOLVED — see resolution map below)

> **Resolved 2026-08-15 during planning.** Every question here was carried into a plan and decided
> there with reasoning recorded. Nothing in this section is still open; it is retained because the
> *reasoning that led to the question* is what makes each resolution checkable.
>
> | # | Question | Resolved in | Resolution |
> |---|----------|-------------|------------|
> | 1 | Is D-05's consent store server-side? | **31-16** | **Client-only, no table, no migration.** No relation can serve a pre-identity anonymous visitor — V54's tables are `tenant_id NOT NULL` + `recipient NOT NULL`. |
> | 2 | Snapshot vs live-join for the order allergen mask | **31-10** | **Snapshot** (V63 + `order_items_aud`). A post-order vendor edit would otherwise silently rewrite what the customer acknowledged. |
> | 3 | Are `/legal/*` pages inside the axe gate? | **31-18** | **Yes** — explicitly scanned. |
> | 4 | A11Y-08 — fix or declare as an exception? | **31-14** | **Fixed, not excepted.** A zero-violation gate shipping beside an unlisted 1.3.5 failure would make 31-13's conformance statement false. |
> | 5 | Registered office / DSAR contact values | **31-08** | **Blocking `checkpoint:human-verify`** — including whether a residential address may lawfully be published at all. |
>
> ⚠ **One measurement in this section is wrong and was corrected during plan-checking:** the
> checkout autocomplete count is **7**, not 8. The file has seven `<input>` elements plus one
> `<textarea>` (`notes`), which takes no autocomplete token. The "8" counted the textarea. The
> corrected count is recorded in `31-14-PLAN.md`; treat any "eight inputs" phrasing below as
> superseded.

1. **Does D-05's "consent store" mean server-side to the owner?**
   - **Known:** a client-only store is the correct tier, and no table can serve a pre-identity anonymous visitor (Q1, measured).
   - **Unclear:** whether "ship the consent store dormant" was intended to imply persistence beyond the device.
   - **Recommendation:** implement client-only; state the reasoning in the plan in one sentence. If the owner disagrees, the fallback (a non-tenant-scoped table with a `postcode_centroid`-style `EXEMPT_TABLES` entry) is fully specified above and costs one migration.

2. **Snapshot the allergen mask onto `order_items`, or join live?**
   - **Known:** `OrderItem` has no allergen field and is `@Audited`; it already snapshots `productName`.
   - **Unclear:** whether the phase is willing to take a Flyway migration (which moves `schema_version` in four documents).
   - **Recommendation:** **snapshot.** A live join lets a post-order vendor edit silently change what the customer is recorded as having acknowledged — on the one surface that can injure someone. If the migration is refused, the acknowledgement must at minimum persist the acknowledged set alongside the order.

3. **Does D-09's declared list include `/legal/*` for the axe gate?**
   - **Known:** D-09 names landing, shop listing, shop page, product detail, checkout, sign-in/sign-up. The new legal pages are public and this phase authors them.
   - **Recommendation:** include them. A page this phase creates should not ship outside the gate it also creates; `/legal` already sits at 1 violation (F-C) which the same footer fix closes.

4. **Is A11Y-08 (no `autocomplete` on any of 8 checkout inputs) fixed or declared as an exception?**
   - **Known:** it is a **WCAG 2.1 AA** failure on a declared surface, and axe does not detect it — so a zero-violation gate is silent about it.
   - **Recommendation:** fix it (8 attributes, low risk). If not, it **must** be a named exception with a remediation date under D-12, or the conformance statement is wrong.

5. **Where does the DSAR contact resolve, and what is the registered office?**
   - **Known:** `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is set nowhere and defaults to `""`; F15 requires the contact route to resolve.
   - **Unclear:** the real values.
   - **Recommendation:** treat as a `checkpoint:human-verify` — these are business facts, not engineering choices, and a privacy notice with a blank controller address fails Art. 13(1)(a).

---

## Sources

### Primary (HIGH confidence — measured on this tree/runtime, this session)

- `scripts/check-runtime-freshness.sh` — **PASS 4/4 FRESH**, establishing that every runtime measurement describes the branch
- Live axe scans (`axe-core@4.11.2` injected via `addScriptTag`, Playwright 1.62.1, both projects) with recorded **break arm (10 violations)** and **clean control (0)**
- Live landmark/role census via `page.getByRole(...)` across 7 routes
- `curl` of served HTML across 5 routes with a negative control (`<zzznotpresent` = 0)
- `scripts/check-gate-enforcement.sh` — baseline / break A (rc=1) / break B (rc=2) / closing clean, restore verified by `git hash-object` = `git rev-parse HEAD:…`
- `scripts/docs-freshness.sh` (rc=0), `scripts/check-doc-metrics.sh` (37 claims, rc=0), `scripts/check-geo-attribution.sh` (rc=0), `npx jest __tests__/contrast-tokens.test.ts` (8/8)
- Two-arm consumer search for `cleanup.orphaned-image-days` (rc=1/empty) vs `cleanup.stale-draft-hours` (rc=0/4 hits)
- `npx playwright test --list` — 220 tests / 20 files; per-spec 20 tests
- Source read: `V42`, `V54`, `RlsContractTest.java:95-175`, `SystemPrincipal.java`, `GdprController/GdprService`, `ScheduledCleanupService`, `WebhookRetentionCleanup`, `MediaQuarantineRetentionSweep`, `PublicStorefrontService:728-760`, `OrderItem`, `IngredientMarkupParser`, `AllergenSpan`, `frontend/types/api.ts:490-516`, `app/shop/layout.tsx`, `components/public/public-footer.tsx`, `app/auth/signin/page.tsx`, `lib/company.ts`, `lib/customer-auth-cookies.ts`, `app/sitemap.ts`, `app/robots.ts`, `lib/structured-data.ts`, `frontend/eslint.config.mjs`, `frontend/jest.config.js`, `frontend/playwright.config.ts`, `.github/workflows/ci-cd.yaml`, `.github/workflows/e2e-nightly.yml`, `scripts/gates/claim-gate.sh`, `scripts/gates/claims.manifest`, `scripts/gates/gate-enforcement.conf`, `core-java/build.gradle.kts`
- `docs/legal/article-9-allergen-basis.md` (122 lines, 2026-07-30) and `docs/legal/derivation-clause.md`
- `.planning/phases/33-the-consumer-product/33-05-PLAN.md:85-100, 310-335` — the gate-sequencing precedent
- **Context7 `/microsoft/playwright`** — `docs/src/accessibility-testing-js.md`: `AxeBuilder` usage, `.include()`, `.disableRules()`, `testInfo.attach()`

### Secondary (MEDIUM — measured by others, re-verified where it mattered)

- `.qa-council/disc-20260802-121732/evidence/a11y-findings.md` (32 KB, 16 findings, per-route axe counts, instrument-falsification table). **Gitignored** — copy, do not link. Two of its findings are now stale (A11Y-03 fixed; the 220 figure is out of D-09 scope).
- `.qa-council/disc-20260802-121732/evidence/a11y-00-instrument-falsification.json`, `a11y-axe-public.json`, `a11y-checkout-runtime.json`
- `docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md` — the "essentially absent" audit ROADMAP cites

### Tertiary (LOW — training knowledge, flagged, not relied on)

- UK PECR reg. 6(4) strictly-necessary exemption (A4); UK GDPR Art. 13(1)(a) controller-details requirement; HMRC 6-year record retention (A3); WCAG partial-conformance claims and SC 1.3.5 / 1.4.11 / 2.4.1 / 4.1.3 numbering. **Every legal position here needs adviser confirmation** — `docs/legal/article-9-allergen-basis.md` sets the correct precedent by saying so on its own face.

---

## Metadata

**Confidence breakdown:**
- **Open Question 1 (consent store):** HIGH — migration source and RLS policies read directly; the "no such table" result carries a positive control.
- **Open Question 2 (CI placement):** HIGH — both workflows read end-to-end; test counts measured with `--list`; scan cost measured by running it.
- **Open Question 3 (retention):** HIGH for the six enforced periods and for R-10's absence (two-arm). `[ASSUMED]` on the statutory figure in R-9 only.
- **a11y baseline:** HIGH — instrument falsified in both directions before any route was scanned; runtime freshness PASS; mobile and desktop both run. **One acknowledged vacuity** (checkout, empty basket) is flagged rather than reported as a result.
- **Corrections 1-6:** HIGH — each measured, most with an explicit control.
- **Standard stack:** MEDIUM — versions and repos verified on npm and `@axe-core/playwright` cited from Playwright's own docs, but **slopcheck could not run**, so all four packages are `[ASSUMED]` by protocol.
- **Allergen chain gaps:** HIGH — each absence verified with a positive control of the same search shape.
- **Legal positions:** LOW by design, and stated as such.

**Research date:** 2026-08-15
**Valid until:** **2026-09-14** for the architectural findings; **2026-08-22** for the a11y baseline — it is a measurement of a moving tree, and any markup change invalidates it. Re-run the scan before quoting a violation count forward.
