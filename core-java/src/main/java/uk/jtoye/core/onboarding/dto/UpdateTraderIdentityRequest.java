package uk.jtoye.core.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.onboarding.TraderIdentityFields;

/**
 * Request body for {@code PUT /api/v1/trader-identity} (#789): the statutory trader details,
 * nothing more (D-10). Like every vendor request in this package it carries NO {@code tenantId}
 * — the tenant comes from the caller's context — and NO company number, which lives on the
 * onboarding record.
 *
 * <p>Every setter strips surrounding whitespace BEFORE validation runs, so the limits are the
 * limits of the value that is stored: a legal name is 1..255 characters after stripping, and a
 * name of spaces is blank. Optional fields that strip to empty become {@code null}. The shape
 * patterns are lenient on case and spacing; {@link TraderIdentityFields} canonicalises on write.
 */
public class UpdateTraderIdentityRequest {

    @NotBlank(message = "legalName is required")
    @Size(max = 255, message = "legalName must be at most 255 characters")
    @Schema(description = "The company's registered name, or a sole trader's own name", maxLength = 255)
    private String legalName;

    @NotNull(message = "entityType is required")
    private TraderEntityType entityType;

    @NotBlank(message = "addressLine1 is required")
    @Size(max = 255, message = "addressLine1 must be at most 255 characters")
    private String addressLine1;

    @Size(max = 255, message = "addressLine2 must be at most 255 characters")
    @Schema(nullable = true)
    private String addressLine2;

    @NotBlank(message = "addressCity is required")
    @Size(max = 120, message = "addressCity must be at most 120 characters")
    private String addressCity;

    @NotBlank(message = "addressPostcode is required")
    @Pattern(regexp = TraderIdentityFields.POSTCODE_PATTERN,
            message = "addressPostcode must be a valid UK postcode, for example SW1A 1AA")
    private String addressPostcode;

    @Pattern(regexp = TraderIdentityFields.VAT_PATTERN,
            message = "vatNumber must be GB followed by 9 or 12 digits, or left empty")
    @Schema(nullable = true, description = "GB followed by 9 or 12 digits; omit or leave empty when not VAT-registered")
    private String vatNumber;

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = TraderIdentityFields.strip(legalName); }

    public TraderEntityType getEntityType() { return entityType; }
    public void setEntityType(TraderEntityType entityType) { this.entityType = entityType; }

    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = TraderIdentityFields.strip(addressLine1); }

    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = TraderIdentityFields.stripToNull(addressLine2); }

    public String getAddressCity() { return addressCity; }
    public void setAddressCity(String addressCity) { this.addressCity = TraderIdentityFields.strip(addressCity); }

    public String getAddressPostcode() { return addressPostcode; }
    public void setAddressPostcode(String addressPostcode) { this.addressPostcode = TraderIdentityFields.strip(addressPostcode); }

    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = TraderIdentityFields.stripToNull(vatNumber); }
}
