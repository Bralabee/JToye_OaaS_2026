package uk.jtoye.core.media.exception;

/**
 * Base type for the three preconditions that reject a manual re-drive
 * ({@code POST /api/v1/media/{assetId}/reprocess}, 27-01 / D-04). Each subclass carries its
 * OWN stable RFC 7807 {@code type} slug and machine-parseable {@code code} extension, so an
 * agent can branch on the reason without parsing prose (the D-06 / agent-readiness contract);
 * {@code GlobalExceptionHandler} maps the whole family to 409 in one handler, reading the slug
 * and code off the exception rather than duplicating three near-identical methods.
 *
 * <p>All three are 409 Conflict rather than 400: the request is well-formed and the caller is
 * authorized — the asset's current state is simply incompatible with re-driving it.
 */
public abstract class MediaRedriveRejectedException extends RuntimeException {

    private final String typeSlug;
    private final String code;

    protected MediaRedriveRejectedException(String message, String typeSlug, String code) {
        super(message);
        this.typeSlug = typeSlug;
        this.code = code;
    }

    /** The last path segment of the stable {@code https://jtoye.uk/errors/...} type URI. */
    public String getTypeSlug() {
        return typeSlug;
    }

    /** The stable machine-parseable reason code, surfaced as the {@code code} problem extension. */
    public String getCode() {
        return code;
    }
}
