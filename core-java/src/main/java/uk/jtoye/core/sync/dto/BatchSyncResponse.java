package uk.jtoye.core.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for batch synchronization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSyncResponse {
    private String status;
    private int processedCount;
}
