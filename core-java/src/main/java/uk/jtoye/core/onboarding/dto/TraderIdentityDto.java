package uk.jtoye.core.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.onboarding.TraderEntityType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The tenant's legal entity as customers will see it (#789, D-10/D-11), returned by
 * {@code GET} and {@code PUT /api/v1/trader-identity}. Never carries the {@code tenantId}: the
 * tenant is resolved server-side and never echoed (the {@link OnboardingDto} convention).
 *
 * @param id              the trader-identity row id (one per tenant)
 * @param legalName       the company's registered name, or a sole trader's own name
 * @param entityType      COMPANY / SOLE_TRADER / PARTNERSHIP
 * @param addressLine1    first line of the geographic address
 * @param addressLine2    second line, or null
 * @param addressCity     town or city
 * @param addressPostcode UK postcode, upper-case with one space
 * @param vatNumber       GB VAT number, or null when no VAT registration is declared
 * @param companyNumber   read from the tenant's onboarding record (never stored twice); null for a
 *                        sole trader, or when no onboarding exists yet
 * @param version         optimistic-lock version; increases on every save
 * @param updatedAt       when the identity was last saved
 */
public record TraderIdentityDto(
        UUID id,
        String legalName,
        TraderEntityType entityType,
        String addressLine1,
        @Schema(nullable = true) String addressLine2,
        String addressCity,
        String addressPostcode,
        @Schema(nullable = true, description = "GB VAT number; null when no VAT registration is declared")
        String vatNumber,
        @Schema(nullable = true, description = "Companies House number from the onboarding record; never stored twice")
        String companyNumber,
        long version,
        OffsetDateTime updatedAt) {
}
