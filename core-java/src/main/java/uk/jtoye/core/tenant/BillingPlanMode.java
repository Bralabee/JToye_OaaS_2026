package uk.jtoye.core.tenant;

/**
 * Axis 4 of ADR-0007 ({@code GAP-GTM-05}, {@code PAY-02}) plus the {@code D-B9}
 * walk pricing ruling in {@code BUSINESS-ALIGNMENT-2026-10.md}.
 *
 * <p>All monetary figures and basis points are resolved from {@code StripeProperties.Billing}
 * (configuration-injected via {@code application.yml} / environment variables, never
 * hardcoded in domain logic):
 * <ul>
 *   <li>{@link #SAAS_HYBRID_STANDARD}: £39/location/mo + 50 bps (0.5%) application fee</li>
 *   <li>{@link #SAAS_FLAT_PREDICTABLE}: £79–£149/location/mo + 0 bps (0%) application fee</li>
 *   <li>{@link #BATCH_PAYG_STARTER}: £0/mo + 600 bps (6.0%) application fee for weekend batch kitchens</li>
 *   <li>{@link #WALK_SETUP_STEPDOWN}: £195/mo during setup ({@code walkSetupCompletedAt == null}),
 *       stepping down to 250 bps (2.5%) of direct orders capped at £39/mo per account
 *       (£0 in a quiet month) once {@code walkSetupCompletedAt != null}.</li>
 * </ul>
 */
public enum BillingPlanMode {
    SAAS_HYBRID_STANDARD,
    SAAS_FLAT_PREDICTABLE,
    BATCH_PAYG_STARTER,
    WALK_SETUP_STEPDOWN
}
