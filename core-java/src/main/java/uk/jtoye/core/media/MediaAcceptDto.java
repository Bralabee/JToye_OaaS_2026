package uk.jtoye.core.media;

import java.util.UUID;

/**
 * The 202-accept response body (IMG-02): the id of the {@code media_asset} the
 * upload was quarantined into, plus its current processing status. The client
 * polls / re-fetches the product to observe the {@code PENDING -> ACTIVE/FAILED}
 * transition the async worker (24-04) drives.
 *
 * <p>On a dedup short-circuit the id is the EXISTING asset for the identical raw
 * bytes (its status may already be {@code ACTIVE}); on a fresh accept it is a new
 * {@code PENDING} asset.
 */
public record MediaAcceptDto(UUID assetId, String status) {
}
