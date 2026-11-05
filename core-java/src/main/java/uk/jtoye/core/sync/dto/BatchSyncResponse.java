package uk.jtoye.core.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    public static final String REJECTED = "REJECTED";

    @Schema(description = "SUCCESS when every item was written (or the batch was empty); PARTIAL when some were "
            + "written and some rejected; REJECTED when none was written. Never SUCCESS when any item was refused.",
            allowableValues = {SUCCESS, PARTIAL, REJECTED}, example = SUCCESS)
    private String status;

    @Schema(description = "Number of items written (CREATED plus UPDATED)", example = "3")
    private int processedCount;

    /**
     * #879 / UXT-123 (P2-RAV-15): the edge gateway publishes this number as {@code processed_count}; core
     * answered {@code processedCount}, so one batch had two spellings depending on the door it came through.
     * Core now answers both, with the same value: {@code processedCount} stays for every existing client and
     * {@code processed_count} is the edge's name for it. Read-only: a request body never sets it.
     */
    @JsonProperty(value = "processed_count", access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Same value as processedCount, under the name the edge gateway publishes",
            example = "3", accessMode = Schema.AccessMode.READ_ONLY)
    public int getProcessedCountSnakeCase() {
        return processedCount;
    }

    @Builder.Default
    @Schema(description = "One result per request item, in request order")
    private List<SyncItemResult> results = new ArrayList<>();

    /** Builds the response from the per-item results; the status is derived, never asserted. */
    public static BatchSyncResponse of(List<SyncItemResult> results) {
        int written = (int) results.stream().filter(SyncItemResult::isWritten).count();
        String status = written == results.size() ? SUCCESS : written == 0 ? REJECTED : PARTIAL;
        return BatchSyncResponse.builder()
                .status(status)
                .processedCount(written)
                .results(new ArrayList<>(results))
                .build();
    }
}
