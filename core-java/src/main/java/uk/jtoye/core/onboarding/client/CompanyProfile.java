package uk.jtoye.core.onboarding.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lenient projection of a Companies House Public Data API company profile
 * ({@code GET /company/{number}}). Only the two fields the
 * {@code BUSINESS_VERIFIED} gate needs are mapped — {@code company_number} and
 * {@code company_status} — while {@link JsonIgnoreProperties} tolerates the rest
 * of the (large, evolving) provider payload so a new upstream field never breaks
 * the lookup.
 *
 * @param companyNumber the registered company number (provider key / external_ref)
 * @param companyStatus lifecycle status; {@code "active"} is the only PASSED value
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyProfile(
        @JsonProperty("company_number") String companyNumber,
        @JsonProperty("company_status") String companyStatus) {
}
