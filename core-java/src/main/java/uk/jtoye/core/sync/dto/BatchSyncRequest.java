package uk.jtoye.core.sync.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for batch synchronization from Edge service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSyncRequest {
    /** Ignored on the server — the tenant is always taken from {@code TenantContext} (the JWT), never the body. */
    private UUID tenantId;

    /**
     * Each element is validated against the {@link SyncItem} bounds; {@code @Valid} cascades into
     * the list so a violation is reported with its index ({@code items[2].allergenMask}) —
     * QA-council 20260902 Cluster A, finding API-2.
     */
    @Valid
    private List<SyncItem> items;
}
