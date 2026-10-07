package uk.jtoye.core.storefront.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.onboarding.TraderEntityType;

import java.util.List;

/**
 * Who the customer is buying from (#789, D-10/D-11/D-20), on the PUBLIC wire.
 *
 * <p>Carries exactly what the law requires the customer to see and nothing else: CCR 2013 Sch
 * 2(b)-(c) (identity and the geographical address, phone and email where available) and the
 * E-Commerce Regs 2002 reg 6(1)(c) (an email address), (d) (the register and registration number)
 * and (g) (the VAT number). No row id, no tenant id, no version, and none of the vendor's private
 * contact fields — the email and phone are the SHOP's published contact details (D-11).
 *
 * <p>{@code NON_NULL}: an optional field the trader does not have is ABSENT, never {@code null}:
 * a company number only for a {@link TraderEntityType#COMPANY}, a VAT number only when
 * registered, a phone only when the shop has one. A customer surface then cannot render a label
 * with nothing after it.
 *
 * @param legalName     the company's registered name, or a sole trader's own name
 * @param entityType    company, sole trader or partnership
 * @param companyNumber the Companies House number, present only for a company; exactly as stored
 * @param vatNumber     the VAT number when VAT-registered; exactly as stored (canonical GB form)
 * @param addressLines  the geographic address: line 1, optional line 2, town/city, postcode
 * @param email         the shop's email (required for a live shop, D-20)
 * @param phone         the shop's phone, optional
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The seller's statutory identity (CCR 2013 Sch 2; E-Commerce Regs 2002 reg 6). "
        + "Present only on GET /public/shops/{slug}; optional fields are absent when the trader has none.")
public record SellerIdentityDto(
        @Schema(description = "Legal name: the registered company name or the sole trader's own name",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String legalName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TraderEntityType entityType,
        @Schema(description = "Companies House number; present only when entityType is COMPANY")
        String companyNumber,
        @Schema(description = "VAT number; present only when VAT-registered")
        String vatNumber,
        @Schema(description = "Geographic address lines: line 1, optional line 2, town or city, postcode",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> addressLines,
        @Schema(description = "The shop's contact email")
        String email,
        @Schema(description = "The shop's contact phone, when it has one")
        String phone) {

    public SellerIdentityDto {
        addressLines = addressLines == null ? List.of() : List.copyOf(addressLines);
    }
}
