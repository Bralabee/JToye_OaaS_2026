package uk.jtoye.core.sync;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     *
     * <p><strong>Catalogue write — rides {@code SCOPE_catalog:write}</strong> (QA-council
     * 20260902 Cluster A, finding API-1 / issue #648). This handler upserts shops and products,
     * i.e. it is a catalogue mutation in every sense the nine {@code ProductController} mutators
     * are, yet it was the one write left on {@code anyRequest().authenticated()} alone — so the
     * documented read-only machine credential ({@code integration-catalog-ro},
     * {@code catalog:read} only, "zero blast radius" per {@code docs/security-scopes.md}) could
     * rewrite titles, prices and allergen declarations through it. Same scope, same gate shape,
     * same converter ({@code JwtRolesAndScopesConverter}) as the product endpoints. The
     * within-tenant, per-shop half of the decision (which shop this caller may touch) lives in
     * {@link SyncService} next to the SKU resolution, mirroring {@code ProductService}.
     */
    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(summary = "Batch Sync", description = "Receives a batch of data for synchronization from an Edge service")
    public ResponseEntity<BatchSyncResponse> batchSync(@Valid @RequestBody BatchSyncRequest request) {
        BatchSyncResponse response = syncService.processBatch(request);
        return ResponseEntity.ok(response);
    }
}
