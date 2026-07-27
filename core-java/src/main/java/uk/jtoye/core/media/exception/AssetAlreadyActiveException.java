package uk.jtoye.core.media.exception;

/**
 * The asset is already {@code ACTIVE} — it has a live, servable derivative, so re-driving it
 * would take a working image back to {@code PENDING} and risk replacing it with a failure
 * (27-01 / D-04). A vendor who wants different bytes uses the upload path, not this one.
 */
public class AssetAlreadyActiveException extends MediaRedriveRejectedException {

    public AssetAlreadyActiveException(String message) {
        super(message, "media-already-active", "media.already_active");
    }
}
