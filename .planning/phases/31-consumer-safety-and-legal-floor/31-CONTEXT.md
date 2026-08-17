# Phase 31: Consumer-Safety and Legal Floor - Context

**Gathered:** 2026-08-15
**Status:** Ready for planning

<domain>
## Phase Boundary

The platform becomes defensible to a consumer, to a regulator, and to a procurement
questionnaire. Three requirements, all scoped by ROADMAP.md §"Phase 31":

- **LGL-01** (#116) — privacy policy, cookie/consent handling and a written retention policy
  live on the public storefront.
- **LGL-02** (#103 + #272) — WCAG 2.1 AA on declared surfaces, with a **published conformance
  statement** and an automated CI accessibility check **shown to fail** against a deliberately
  broken control.
- **LGL-03** (#427 Wave 1) — the allergen evidence chain's zero-infrastructure slice: a recorded
  lawful-basis decision, order-level allergen-mask aggregation, and conflict surfacing on the KDS.

**Not in this phase:** the rest of #427 (ADR-0004 ingredient graph), #428 (Cohort B catering),
DPA acceptance tracking / e-signature flow, and accessibility remediation of the authenticated
vendor dashboard. This phase depends on nothing structural and runs in parallel with Phases 29
and 30 — but it **does** gate Phase 32.

</domain>

<decisions>
## Implementation Decisions

### Allergen lawful basis (LGL-03)

- **D-01:** **The platform does NOT consult the stored customer allergen profile at checkout.**
  `Customer.allergenRestrictions` (an `Integer` bitmask, already persisted) is special-category
  health data under Article 9. This phase's deliverable is a **dated, recorded decision not to
  process it** — the roadmap explicitly offers this limb ("or an explicit decision not to").
  The bitmask stays vendor-entered exactly as today; no new consent capture, withdrawal, or
  audit obligation is created.
- **D-02:** **What the consumer sees at checkout is the ORDER's own aggregated allergen set**
  ("this order contains: milk, gluten, sesame"), and they must **explicitly acknowledge** having
  reviewed it before proceeding. No health data is read, stored, or inferred — the platform never
  learns the customer's allergies. This is the subject that makes D-01 and the warn-and-acknowledge
  behaviour coherent; there is no customer-vs-product comparison anywhere in this phase.
- **D-03:** **Aggregation = declared product masks PLUS a reconciliation flag.** Any product whose
  free-text ingredients name an allergen absent from its declared mask is flagged. This is the limb
  that attacks the roadmap's actual complaint — every allergen statement today resolves to an integer
  a vendor hand-types into a CSV column, never reconciled against the adjacent ingredients text.
  `IngredientMarkupParser` and `AllergenSpan` already exist as the parsing substrate; reuse them.
- **D-04:** **KDS surfacing = order-level banner AND per-item badge.** The banner so it cannot be
  missed under time pressure; the badge so staff know *which* item. A single aggregate tells kitchen
  staff nothing actionable.

### Cookie and consent scope (LGL-01)

- **D-05:** **Ship the consent store dormant, plus an honest notice.** Measured on the tree: there
  are **zero non-essential cookies and zero analytics/tag scripts** in `frontend/app`,
  `frontend/components`, `frontend/lib`. So ship (a) a cookie policy page, (b) an essential-cookies
  notice, and (c) the consent store + gating API **with zero non-essential categories registered**,
  so no banner appears today but any future script must pass through the gate to load. Additive by
  design — it makes the rule structural instead of a note.
  ⚠ A consent gate over zero categories is **unfalsifiable as shipped**. It MUST be proven with a
  **fixture category** in tests that demonstrates the gate blocking and then permitting a script.
  Without that arm, this decision buys nothing.
- **D-06:** **Policy pages nest under `/legal`.** `/legal` becomes an index; new routes are
  `/legal/privacy`, `/legal/cookies`, `/legal/retention`. Reuses `PublicShell` and the existing
  `alternates: { canonical }` metadata pattern already in `frontend/app/legal/page.tsx`. Each is a
  separately citable URL, because regulators and procurement forms ask for them individually.
- **D-07:** **The retention policy is a published per-category schedule** — data category →
  retention period → lawful basis — not a statement of principles. Derive the periods from what the
  system **measurably enforces today**, not from aspiration.
- **D-08:** **The published retention periods are tied to enforcement by a new
  `scripts/check-*.sh` gate** that fails when a published period has no corresponding enforcement in
  code or config. A published schedule that quietly diverges from behaviour is the exact drift class
  the two docs-freshness gates exist to catch. Note for the planner:
  `scripts/check-gate-enforcement.sh` is **default-deny** — any new `check-*.sh` needs a workflow
  reference or a `gate-enforcement.conf` entry, and the conf **VOIDs at rc=2** on an entry naming a
  script that does not exist yet. Sequence the wave accordingly (see Phase 33's 33-05/33-06 split
  for the worked precedent).

### Accessibility (LGL-02)

- **D-09:** **Declared surfaces = public storefront + auth flows** — landing, shop listing, shop
  page, product detail, checkout, sign-in/sign-up. The authenticated vendor dashboard is
  **deliberately out**; the 2026-07-08 audit called accessibility "essentially absent" and a scope
  that includes the dashboard is a phase that never closes.
- **D-10:** **Both layers.** `jest-axe` at component level (fast, every PR, no browser stack) and
  `@axe-core/playwright` at E2E level (real composed pages — focus order, landmarks, live regions).
  The component layer alone is exactly the configuration that produced this project's misleading zero.
- **D-11:** **Threshold = zero violations on the declared surface list.** No ratcheted baseline file
  — a baseline blesses existing violations as the standard and needs regenerating on every
  legitimate change. Grow the surface list in later phases instead.
- **D-12:** **The conformance statement claims partial conformance, dated, with named exceptions**
  and remediation dates. WCAG explicitly supports a partial-conformance claim; overclaiming
  accessibility is itself Equality Act exposure.
- **D-13 (binding constraint on every a11y assertion):** **A zero-violation axe result is presumed
  an artefact until proven otherwise.** This project has already been bitten — a naive axe
  "0 button-name violations" was meaningless because the tables under test never mounted. Every axe
  assertion MUST carry a **non-vacuity control** proving the scanned page actually rendered (a
  known-present landmark/heading count > 0), and the gate MUST be shown to fail against a
  deliberately broken control per the roadmap's own wording.

### Controller/processor split (LGL-01)

- **D-14:** **Joint controllers for consumer order data; the platform is sole controller for
  platform accounts; the platform runs the single point of contact** for data-subject requests.
  Requires an Article 26 arrangement whose essence is published.
- **D-15:** **Layered privacy notice** — one platform privacy notice covering the shared processing,
  with the specific vendor **named at the point of order** as the other party. One document to
  maintain and keep accurate, while still telling the consumer who they are buying from. Note the
  existing `/legal` page already draws the *trading* line (vendors own their trading disclosures);
  the GDPR line drawn here is different and must not contradict it in prose.
- **D-16:** **The DSAR path is BUILT in this phase**, not promised. A published commitment to a
  single point of contact, backed by nothing that can execute it, is the fail-open shape this
  project keeps paying for.
- **D-17:** **DSAR design = request intake + background fan-out under `SystemPrincipal.asSystem`,**
  writing one `erasure_record` per tenant. This is the reconciling design for a real conflict:
  a single cross-tenant DSAR desk appears to require the **cross-tenant operator identity this
  project has refused twice** (recorded constraint; Phase 33 D-2 rejected it explicitly). The
  resolution is that **no human ever holds cross-tenant read** — only a background job does, and
  `ShopAccessService.java:640` already records the rule that "a request thread never enters
  `asSystem` (only background entry points)". Honour that boundary exactly: intake is a request,
  execution is background. Reuse the shipped `uk.jtoye.core.gdpr` package; do not invent a parallel one.
- **D-18:** **The DPA / Article 26 arrangement is authored as a document in this phase.** Acceptance
  tracking, presentation at onboarding, and e-signature are **out** — that is onboarding machinery
  and belongs with Phase 32.

### Claude's Discretion

No gray area was answered "you decide" — every decision above is the owner's. Discretion remains
only over implementation mechanics (file layout, component naming, test structure), which are the
planner's and researcher's to settle.

### Open questions for research (NOT decisions — do not re-ask the owner)

1. **Does the cookie consent store reuse `notification_consent` (V54, Phase 22)?** Probably not —
   `notification_consent` is tenant-scoped marketing consent for an identified customer, whereas
   cookie consent is platform-level and applies **before** identity exists. Measure before deciding;
   a tenant-scoped RLS table cannot serve a pre-identity anonymous visitor.
2. **How does the `@axe-core/playwright` gate actually run in CI?** Per-PR CI runs **2 of 126**
   Playwright tests; the full suite is nightly. A gate that only runs nightly does not block a PR.
   Determine placement before committing to D-10's E2E half.
3. **Which retention periods does the system enforce in code today?** Candidates measured to exist:
   `erasure_records` (V42), the tenant-scoped UPDATE policies enabling the Article-17 scrub on
   `orders_aud`/`customers_aud`, and the media quarantine horizons (`quarantine_expires_at`,
   V60). Only code-enforced periods can be gated by D-08; operational-only periods must be marked
   as such in the published schedule rather than silently gated.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` §"Phase 31: Consumer-Safety and Legal Floor" — goal, the three success
  criteria, and the standing rule that every criterion must be capable of FAILING on the tree as
  it stands.
- `.planning/REQUIREMENTS.md:133-135` — LGL-01, LGL-02, LGL-03 as written.
- `.planning/ISSUE-DISPOSITION.md` — where #116, #103, #272, #427 sit against the whole board.
- `.planning/CRITERIA-DECAY-2026-08-08.md` — the worked precedent for a success criterion that
  had already become unfalsifiable before it was planned. Two of Phase 31's three limbs were
  re-measured on 2026-08-15 (see `<specifics>`); do not assume the third.

### Allergen chain (LGL-03)
- `core-java/src/main/java/uk/jtoye/core/customer/Customer.java:53-58` — the
  `allergen_restrictions` bitmask column and its "allergen-aware ordering" comment.
- `core-java/src/main/java/uk/jtoye/core/product/IngredientMarkupParser.java` — the existing
  ingredients-text parser; the substrate for D-03's reconciliation flag.
- `core-java/src/main/java/uk/jtoye/core/product/AllergenSpan.java` — span model.
- `core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java` — the PPDS /
  Natasha's Law label pipeline (V41) that already consumes this data. D-03 must not break it.
- `frontend/app/dashboard/customers/__tests__/allergen-consent-notice.test.tsx` — the existing
  vendor-facing notice placing the Article 9 duty on the vendor. D-01 is consistent with it and
  must not contradict its wording.
- `frontend/components/storefront/product-detail-modal.tsx:65` — where the hand-typed allergen
  integer is rendered to the consumer today.

### Legal surfaces (LGL-01)
- `frontend/app/legal/page.tsx` — the existing 64-line Companies House disclosure; `/legal`
  becomes an index around it under D-06. Shows the `PublicShell` + canonical-metadata pattern.
- `frontend/lib/company.ts:33` — `DEFAULT_COMPANY_NUMBER = "16471464"` (ACTIVE). A dissolved
  namesake `13434105` exists and is documented at `company.ts:5-6`; **disambiguate by number** in
  any new legal prose.
- `core-java/src/main/java/uk/jtoye/core/gdpr/` — `GdprController`, `GdprService`,
  `ErasureRecord`, `ErasureRecordRepository`. D-17 extends this package; it does not replace it.
- `core-java/src/main/java/uk/jtoye/core/security/access/SystemPrincipal.java` — `asSystem`
  semantics, including the nested-declaration rule.
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:625-640` — the
  binding rule for D-17: a request thread never enters `asSystem`; only background entry points do.
- `frontend/lib/customer-auth-cookies.ts` — the strictly-necessary cookies that the cookie policy
  must describe accurately.

### Gate and proof doctrine (applies to LGL-02's CI check and D-08's retention gate)
- `CLAUDE.md` §"Cross-Cutting Quality Contracts" — dimension 5(a): every acceptance criterion must
  be shown to FAIL before it is trusted, with both directions' real output recorded.
- `scripts/check-gate-enforcement.sh` — default-deny; any new `check-*.sh` needs a workflow
  reference or a `gate-enforcement.conf` entry, and the conf VOIDs at rc=2 on an entry naming a
  script that does not exist yet.
- `.github/workflows/docs-freshness.yml` — the two-gates-per-loop pattern D-08 should imitate
  (tree → manifest, and prose → manifest).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`PublicShell`** (`frontend/components/public/public-shell.tsx`) — already wraps `/legal` with
  header/footer chrome. All new `/legal/*` pages use it; there is a recorded regression where the
  legal page rendered a bare `<main>` with no chrome, so do not reintroduce that.
- **`getCompanyInfo()`** (`frontend/lib/company.ts`) — env-overridable company identity. The
  privacy notice's "who we are" block should read from this, not hardcode a name or number.
- **`IngredientMarkupParser` + `AllergenSpan`** — the ingredients-text parsing needed by D-03
  already exists and is exercised by the PPDS label pipeline.
- **`uk.jtoye.core.gdpr` package** — erasure records, service and controller already shipped for
  the Article-17 scrub (V42). D-16/D-17 extend this seam.
- **`SystemPrincipal.asSystem`** (Phase 28-06) — the sanctioned background-only elevation, with a
  behavioural guard already proven across the suite. D-17 depends on it.
- **`frontend/__tests__/contrast-tokens.test.ts`** — the only existing accessibility-adjacent test;
  the precedent for where a colour/contrast assertion lives.

### Established Patterns
- **RLS + TenantContext everywhere.** Any new table (a consent store, a DSAR request record) must
  decide tenant-scoping explicitly. A cookie-consent store is **pre-identity and cross-tenant** by
  nature and likely must NOT be tenant-scoped — if so, it needs the same by-addition exemption with
  written justification that `postcode_centroid` took in `RlsContractTest.EXEMPT_TABLES`, so the
  schema-walk sweep is never weakened.
- **Flyway is at V61** and `spring.flyway.out-of-order=true` is required. A migration comment that
  names a property in dollar-brace form **aborts startup everywhere** — Flyway substitutes
  placeholders inside comments.
- **Any bare `UPDATE` in a migration against a FORCE-RLS table hits zero rows.** Loop tenants with
  `set_config`. This has recurred at V25, V44 and V57.
- **Every gate must be observed failing before it is trusted**, and missing tooling / unparseable /
  empty output must exit non-zero (VOID), never 0.

### Integration Points
- **Checkout** — D-02's aggregated allergen set and acknowledgement step land in the existing guest
  and authenticated checkout flows (`GuestOrderRequest` already carries allergen fields).
- **KDS** — D-04's banner and badge attach to the existing kitchen display, which is fed over STOMP.
  Destinations must remain a **single dot-separated segment** or the relay rejects them.
- **Public storefront routes** — D-09's declared surfaces are the same routes Phase 33 optimised for
  SEO and Core Web Vitals. An a11y remediation that changes markup can move the recorded CLS
  (`/` sits at 0.1793, pre-existing) and the client-JS baseline (953,353 bytes across 21 scripts,
  recorded in `frontend/e2e/perf-budgets.ts`). Check both before claiming no regression.

</code_context>

<specifics>
## Specific Ideas

**Measurements taken 2026-08-15 that shaped these decisions — re-run before quoting forward:**

- `frontend/app/legal/` contains exactly **one file** (`page.tsx`, 64 lines), and it is purely the
  Companies House trading disclosure. No privacy policy, no cookie banner, no retention policy.
- **Zero** analytics / tag-manager / third-party scripts in `frontend/app`, `frontend/components`,
  `frontend/lib`. The only cookies are auth/session — strictly necessary, needing no consent under
  UK PECR. This is what makes D-05's dormant-store shape the honest one.
- **Zero** `axe` / `pa11y` / `lighthouse` occurrences in `frontend/package.json`, verified with a
  working control (`playwright` → 1, so the search mechanism was live). **Zero** accessibility
  references in any file under `.github/workflows/`.
- `Customer.allergenRestrictions` is already persisted, and a vendor-facing consent notice already
  exists in the dashboard placing the Article 9 duty on the vendor.

All three success criteria were confirmed **capable of failing** on the tree as it stands.

</specifics>

<deferred>
## Deferred Ideas

- **DPA acceptance tracking / e-signature at onboarding** — per D-18, the document is authored here
  but the acceptance flow belongs with **Phase 32 (Production Cutover + First Tenant)**.
- **Accessibility remediation of the authenticated vendor dashboard** — deliberately outside D-09's
  declared surface list. A later phase grows the list; the gate's design (D-11, no baseline file)
  makes that growth cheap.
- **Consent categories for real analytics** — D-05 ships the gate with zero non-essential
  categories. The first actual analytics script is what registers a category and makes the banner
  appear; that arrives with whichever phase introduces it.
- **The rest of #427 (ADR-0004 ingredient graph)** — LGL-03 is Wave 1 only. The full evidence chain
  has no phase and no issue-level home yet.

</deferred>

---

*Phase: 31-consumer-safety-and-legal-floor*
*Context gathered: 2026-08-15*
