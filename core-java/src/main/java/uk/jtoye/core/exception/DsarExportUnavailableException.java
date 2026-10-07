package uk.jtoye.core.exception;

/**
 * An Article 15 export download token could not be spent (31.1-17, D-01): it was never issued, its
 * link has expired, the export was purged, or it has already been used. Maps to a 404 with the
 * stable type {@code https://jtoye.uk/errors/dsar-export-unavailable}.
 *
 * <p>Deliberately carries no reason. The four causes answer with one byte-identical body, so the
 * endpoint cannot be used to learn whether a guessed token ever existed (threat T-31.1-62).
 */
public class DsarExportUnavailableException extends RuntimeException {

    public DsarExportUnavailableException() {
        super("This download link has already been used, has expired, or was not recognised. "
                + "You can request a new copy.");
    }
}
