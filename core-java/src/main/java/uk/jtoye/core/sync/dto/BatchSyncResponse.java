package uk.jtoye.core.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for batch synchronization.
 *
 * <p>Phase 37-40 (RWO-037 / UXT-037): {@code results} carries one {@link SyncItemResult} per request
 * item, and {@code status} is the truth about them. {@code processedCount} (items written) is kept for
 * clients that read it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "What the batch did, item by item")
public class BatchSyncResponse {

    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";

    @Schema(description = "SUCCESS when every item was written; PARTIAL when some were written and some rejected",
            allowableValues = {SUCCESS, PARTIAL}, example = SUCCESS)
    private String status;

    @Schema(description = "Number of items written (CREATED plus UPDATED)", example = "3")
    private int processedCount;

    @Builder.Default
    @Schema(description = "One result per request item, in request order")
    private List<SyncItemResult> results = new ArrayList<>();

    /** Builds the response from the per-item results; the status is derived, never asserted. */
    public static BatchSyncResponse of(List<SyncItemResult> results) {
        int written = (int) results.stream().filter(SyncItemResult::isWritten).count();
        String status = written == results.size() ? SUCCESS : PARTIAL;
        return BatchSyncResponse.builder()
                .status(status)
                .processedCount(written)
                .results(new ArrayList<>(results))
                .build();
    }
}
