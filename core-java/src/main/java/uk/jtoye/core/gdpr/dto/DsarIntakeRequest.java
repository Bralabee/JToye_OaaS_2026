package uk.jtoye.core.gdpr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.gdpr.DsarRequest;

/**
 * What a data subject sends to lodge a UK-GDPR request (Phase 31, D-16).
 *
 * <p><b>Every validation message here is generic on purpose.</b> A message that quotes the
 * submitted value echoes an email address back into the caller's console, into any intermediate
 * proxy's logs, and into whatever captures 4xx bodies — a disclosure created by the error handling
 * of a privacy feature. The RFC 7807 document that
 * {@code GlobalExceptionHandler.handleValidationErrors} builds carries these strings verbatim, so
 * they are the whole of what a rejected caller learns.
 *
 * <p>The {@link Size} bound is not cosmetic: an unbounded string on an unauthenticated endpoint is
 * a memory-amplification surface. 254 is the maximum length RFC 5321 permits for a path.
 */
@Schema(description = "A data-subject request lodged from the public internet (UK GDPR Articles 15/17).")
public record DsarIntakeRequest(

        @Schema(description = "The email address the request concerns. Stored only as a one-way "
                + "SHA-256 digest; the readable address is never persisted.",
                example = "someone@example.com")
        @NotBlank(message = "An email address is required")
        @Email(message = "A well-formed email address is required")
        @Size(max = 254, message = "The email address is too long")
        String email,

        @Schema(description = "ACCESS for Articles 15/20 (a copy of the data held), ERASURE for "
                + "Article 17 (deletion).")
        @NotNull(message = "A request type is required")
        DsarRequest.RequestType requestType
) {

    /**
     * Trim before validation runs, because Jakarta {@link Email} does not.
     *
     * <p>This is not tidiness. People reach a legal page by pasting an address out of an email
     * client, and a pasted value routinely carries a leading or trailing space. Without this,
     * {@code " someone@example.com "} is rejected as "not a well-formed email address" — a
     * consumer trying to exercise a statutory right is told their own address is invalid, with a
     * deliberately generic message that gives them nothing to fix. Jackson calls this canonical
     * constructor when binding the body, so the trimmed value is what validation, the digest and
     * the persisted row all see.
     *
     * <p>Trim only. Lower-casing belongs with the digest in {@code DsarIntakeService}, which owns
     * the whole normalisation contract so there is exactly one place that can disagree with plan
     * 31-09's worker about what a subject's digest is.
     */
    public DsarIntakeRequest {
        email = (email == null) ? null : email.trim();
    }
}
