package uk.jtoye.core.onboarding;

/**
 * The kind of legal entity a tenant trades as (#789, D-10). Stored by name in
 * {@code trader_identity.entity_type}, whose V71 CHECK admits exactly these three values.
 *
 * <ul>
 *   <li>{@link #COMPANY} — a registered company; its number is the one on
 *       {@code vendor_onboarding.company_number}.</li>
 *   <li>{@link #SOLE_TRADER} — an individual; the legal name is the person's own name.</li>
 *   <li>{@link #PARTNERSHIP} — an unincorporated partnership.</li>
 * </ul>
 */
public enum TraderEntityType {
    COMPANY,
    SOLE_TRADER,
    PARTNERSHIP
}
