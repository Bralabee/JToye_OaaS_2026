package uk.jtoye.core.sync;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

/**
 * Controller for data synchronization endpoints.
 */
@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
@Tag(name = "Sync", description = "Data synchronization endpoints for Edge services")
@SecurityRequirement(name = "bearer-jwt")
public class SyncController {

    private final SyncService syncService;

    /**
     * Endpoint for batch data synchronization from Edge services.
     * POST /sync/batch
     */
    @PostMapping("/batch")
    @Operation(summary = "Batch Sync", description = "Receives a batch of data for synchronization from an Edge service")
    public ResponseEntity<BatchSyncResponse> batchSync(@RequestBody BatchSyncRequest request) {
        BatchSyncResponse response = syncService.processBatch(request);
        return ResponseEntity.ok(response);
    }
}
