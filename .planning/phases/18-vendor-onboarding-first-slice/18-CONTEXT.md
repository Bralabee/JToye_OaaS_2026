# Phase 18: Vendor Onboarding — First Slice - Context

**Gathered:** 2026-07-10
**Status:** Ready for planning
**Source:** PRD Express Path (`docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md`)
**Mode:** mvp (vertical slice)

<domain>
## Phase Boundary

Deliver the **thinnest end-to-end vendor-onboarding slice**: a `vendor_onboarding` aggregate whose state machine owns whether a shop may go live, gated on two free automated checks plus allergen completeness.

**In scope (this slice):**
- `vendor_onboarding` aggregate (one per tenant) + `vendor_onboarding_gate` child rows (data-driven gate results), tenant-scoped under RLS via a new Flyway migration (**V43**).
- Onboarding state machine (`DRAFT → VERIFYING → ACTION_REQUIRED / PENDING_APPROVAL → APPROVED → LIVE`, plus `SUSPENDED / REJECTED / WITHDRAWN`) mirroring the existing Order state machine. **The state machine is the sole writer of `Shop.published`.**
- Three gates only: `BUSINESS_VERIFIED` (Companies House API), `FOOD_HYGIENE_RATING` (FSA FHRS API, `min-rating=2`), `ALLERGEN_DATA_COMPLETE` (computed from V41 product fields).
- Minimal API to submit onboarding and read status/gate breakdown.
- Config injection (`onboarding.*`) for FHRS/Companies House base URLs + threshold.
- Tests + `docs/metrics.json` bump to keep the `docs-freshness` gate green.

**Out of scope (later slices — see Deferred):** Stripe Connect (KYC/payments), e-signature agreements, `MENU_MINIMUM` gate, admin approval UI, compliance-monitoring scheduler, white-label vs marketplace branching, courier/delivery.
</domain>

<decisions>
## Implementation Decisions (locked — from the design doc)

### Persistence & RLS (mirror V36 template)
- New Flyway migration **V43** (current head is V42). Tables `vendor_onboarding` + `vendor_onboarding_gate`, both `tenant_id UUID NOT NULL`, `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `FOR ALL USING/WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::UUID)`.
- Both entities are `@Audited` → add nullable `_aud` mirror tables (PK `(id, rev)`, FK `rev → revinfo(rev)`, audit RLS predicate `tenant_id IS NULL OR tenant_id = current_setting(...)`).
- Idempotent DDL house style: `CREATE TABLE IF NOT EXISTS`, `DO $$ … IF NOT EXISTS (SELECT 1 FROM pg_policies …) THEN CREATE POLICY … END IF; END $$;`.
- `UNIQUE(tenant_id)` on `vendor_onboarding` (one onboarding per tenant for MVP). `UNIQUE(onboarding_id, gate_type)` on gates.

### State machine (mirror the Order triad)
- Enums `OnboardingState` + `OnboardingEvent`, persisted `@Enumerated(EnumType.STRING)` → `VARCHAR + CHECK (... IN (...))` (NOT a PG enum).
- `VendorOnboardingStateMachineConfig` (`@EnableStateMachineFactory`), `VendorOnboardingStateMachineService.sendEvent(...)` (reset via `DefaultStateMachineContext`, throw `InvalidStateTransitionException` on non-`ACCEPTED`), `VendorOnboardingService.transition(...)` (load → sendEvent → setStatus → side effects → save → publish).
- Guards: `APPROVE` requires all mandatory gates `PASSED/WAIVED`; `GO_LIVE`/`REINSTATE` additionally require `ALLERGEN_DATA_COMPLETE`. `GO_LIVE` action sets `Shop.published=true`; `SUSPEND` sets `false`.

### Gates
- `GateType` enum: `BUSINESS_VERIFIED`, `FOOD_HYGIENE_RATING`, `ALLERGEN_DATA_COMPLETE` (this slice). `GateStatus`: `PENDING/PASSED/FAILED/MANUAL_REVIEW/WAIVED`.
- `OnboardingGate` interface + per-gate implementations; a `GateChainRunner` materialises gate rows on `SUBMIT`, runs automatic gates `@Async`, records result+evidence(JSONB)+external_ref, then fires `GATES_PASSED` (all mandatory pass) or `GATE_FAILED`.
- **FHRS gate:** call `{base-url}/Establishments` with header **`x-api-version: 2`** (mandatory — omit and API returns no data); `PASSED` if rating ≥ `min-rating` (**2**) or FHIS `Pass`; no/ambiguous match → `MANUAL_REVIEW` (never hard-fail). `@CircuitBreaker(name="fhrs")`.
- **Companies House gate:** `GET {base-url}/company/{number}`, HTTP Basic (API key as username), `PASSED` iff `company_status="active"`. Sole traders (no record) → `WAIVED`. `@CircuitBreaker(name="companies-house")`.
- **Allergen gate:** computed from existing V41 columns (`allergen_spans`, `shelf_life_days`, `durability_type`) — every product must carry required allergen data for its `durability_type`.

### Config (no literals — GLOBAL_RULE_6 / ARCHITECTURE_RULE_8)
- `OnboardingProperties` `@ConfigurationProperties(prefix="onboarding")` with nested `fhrs` (base-url, min-rating=2, api-version=2) + `companiesHouse` (base-url, api-key). `application.yml` keys use `${ENV:default}`; secrets default empty. Mirror `StripeProperties`.

### Entity conventions (mirror Product.java)
- Hand-written getters/setters (NO Lombok on entities), `@Entity @Table @Audited`, `@GeneratedValue(strategy=GenerationType.UUID)`, `@CreationTimestamp`, `@Version`, JSONB via `@Column(columnDefinition="jsonb") @JdbcTypeCode(SqlTypes.JSON)`.

### Claude's Discretion
- Exact package layout under `uk.jtoye.core.onboarding`; DTO/mapper shape; controller method signatures; how `GateChainRunner` recompute is triggered after async gates; precise allergen-completeness predicate (align with `docs/ppds-label-markup.md`); test fixture design.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Design contract (source of truth for this phase)
- `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md` — the full state model, gate chain, V43 schema sketch, Java surface, config, API, and open decisions.

### State-machine pattern to mirror
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java` — `@EnableStateMachineFactory`, states + `.withExternal().source().target().event().guard().action()`.
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineService.java` — per-call machine, `DefaultStateMachineContext` reset, `InvalidStateTransitionException` on non-ACCEPTED.
- `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` (`transitionOrder`, ~line 327) — load → sendEvent → setStatus → side effects → save → publish.
- `core-java/src/main/java/uk/jtoye/core/order/Order.java` (status field, ~line 45) — `@Enumerated(EnumType.STRING)` persistence.

### Migration + RLS template
- `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql` — table + tenant_id + ENABLE/FORCE RLS + policy + `_aud` mirror (the closest template for V43).
- `core-java/src/main/resources/db/migration/V2__rls_policies.sql` — canonical RLS policy shape.
- `core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql` — idempotent `DO $$ … pg_policies` DDL style + FORCE-RLS non-audited example.
- `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` — sets `app.current_tenant_id` GUC that RLS reads.

### Entity + config conventions
- `core-java/src/main/java/uk/jtoye/core/product/Product.java` — entity conventions incl. V41 allergen fields (`allergen_spans`, `shelf_life_days`, `durability_type`) + JSONB.
- `core-java/src/main/java/uk/jtoye/core/shop/Shop.java` (`published`, ~line 73) — the go-live flag the state machine will own.
- `core-java/src/main/java/uk/jtoye/core/payment/StripeProperties.java` — `@ConfigurationProperties` pattern for `OnboardingProperties`.
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java` (~line 90) — `@CircuitBreaker` usage to mirror for the external API clients.
- `core-java/src/main/resources/application.yml` (stripe ~186, cleanup ~241) — `${ENV:default}` config convention.

### Legal grounding (constraints, not code)
- `docs/vendor-onboarding-research.md` — why the FHRS gate (`min-rating=2`), allergen-before-publish duty, and free-API automation exist.
</canonical_refs>

<specifics>
## Specific Ideas
- Reuse the existing multi-tenant RLS + `TenantContext`/`TenantSetLocalAspect` — a `DRAFT` onboarding builds data under RLS while `Shop.published=false` gates the storefront.
- FHRS API is free/no-key but requires `x-api-version: 2`; Companies House is free with an API key (HTTP Basic, key as username).
- Allergen gate reuses V41 fields already in `products` — no new product columns.
- Keep a **human fallback** (`MANUAL_REVIEW`) for fuzzy/no-match verification rather than hard-failing a vendor.
</specifics>

<deferred>
## Deferred Ideas (later slices / open decisions)
- **Slice 2+:** Stripe Connect (`IDENTITY_KYC` + `PAYMENTS_CONNECTED` gates, Express onboarding, `account.updated` webhook reusing `processed_stripe_events`), e-signature (`AGREEMENT_SIGNED`), `MENU_MINIMUM` gate, admin approval queue + UI, `@Scheduled` FHRS compliance-monitor (SUSPEND on rating drop).
- **Open decisions (design doc §9):** auto-approve vs human gate; awaiting-inspection handling + Scotland FHIS `Pass` mapping; per-tenant vs per-shop onboarding for multi-shop tenants; sole-trader identity depth; which UK nations to branch; white-label verification depth; in-house courier delivery (worker-status / RTW / transport-hygiene — whole extra gate set).
- FHRS threshold is **DECIDED = 2** (env-overridable via `FHRS_MIN_RATING`).
</deferred>

---

*Phase: 18-vendor-onboarding-first-slice*
*Context gathered: 2026-07-10 via PRD Express Path (design doc)*
