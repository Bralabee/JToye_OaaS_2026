package uk.jtoye.core.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for batch synchronization from Edge service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSyncRequest {
    private UUID tenantId;
    private List<Map<String, Object>> items;
}
