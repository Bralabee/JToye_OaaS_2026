package uk.jtoye.core.finance;

/**
 * VAT (Value Added Tax) rate categories.
 * Maps to vat_rate_enum in database.
 *
 * UK VAT rates (example):
 * - STANDARD: 20% (most goods and services)
 * - REDUCED: 5% (children's car seats, home energy, etc.)
 * - ZERO: 0% (most food, books, newspapers, children's clothes)
 * - EXEMPT: No VAT charged (insurance, education, health services)
 */
public enum VatRate {
    /** Zero-rated: 0% VAT (e.g., most food items, books) */
    ZERO,

    /** Reduced rate: typically 5% (e.g., children's car seats, home energy) */
    REDUCED,

    /** Standard rate: typically 20% in UK (most goods and services) */
    STANDARD,

    /** Exempt: no VAT charged (e.g., insurance, education) */
    EXEMPT
}
