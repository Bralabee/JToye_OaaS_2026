package uk.jtoye.core.onboarding.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The slice of an FSA FHRS {@code /Establishments} match the hygiene gate needs.
 * The FSA JSON carries dozens of PascalCase fields; a lenient
 * {@link JsonIgnoreProperties} + explicit {@link JsonProperty} names map only the
 * three the gate consumes and ignore the rest.
 *
 * @param establishmentId FSA {@code FHRSID} (used as the gate's external ref)
 * @param ratingValue     FSA {@code RatingValue} — a number "0".."5" for FHRS
 *                        (England/Wales/NI) or a word ("Pass"/"Improvement Required")
 *                        for the Scotland FHIS scheme
 * @param schemeType      FSA {@code SchemeType} — "FHRS" or "FHIS"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FhrsEstablishment(
        @JsonProperty("FHRSID") String establishmentId,
        @JsonProperty("RatingValue") String ratingValue,
        @JsonProperty("SchemeType") String schemeType) {
}
