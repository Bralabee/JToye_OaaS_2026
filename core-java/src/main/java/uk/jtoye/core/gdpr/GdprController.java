package uk.jtoye.core.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * UK GDPR data subject rights endpoints.
 * Provides data export (Article 20) and erasure (Article 17).
 *
 * <p><b>Access control (issue #83 P1-1):</b> both export (PII disclosure) and
 * erasure (irreversible anonymisation) require the {@code admin} realm role via
 * the class-level {@code @PreAuthorize("hasRole('admin')")} gate — a non-admin
 * caller receives 403. RLS still scopes these operations to the caller's tenant.
 */
@RestController
@RequestMapping("/gdpr/customers")
@PreAuthorize("hasRole('admin')")  // issue #83 P1-1: PII export + erasure require the admin realm role
@Tag(name = "GDPR", description = "UK GDPR data subject rights — export and erasure")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class GdprController {

    private final GdprService gdprService;

    public GdprController(GdprService gdprService) {
        this.gdprService = gdprService;
    }

    @GetMapping("/{customerId}/export")
    @Operation(summary = "Export customer data",
            description = "Returns all personal data held for a customer (UK GDPR Article 20 — Right to Data Portability)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Data export generated"),
            @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<DataExportResponse> exportData(@PathVariable UUID customerId) {
        return ResponseEntity.ok(gdprService.exportCustomerData(customerId));
    }

    @DeleteMapping("/{customerId}/erase")
    @Operation(summary = "Erase customer data",
            description = "Anonymises all personal data for a customer (UK GDPR Article 17 — Right to Erasure). "
                    + "Records are anonymised rather than deleted to preserve financial audit trails.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Data erasure completed"),
            @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<ErasureResponse> eraseData(@PathVariable UUID customerId) {
        return ResponseEntity.ok(gdprService.eraseCustomerData(customerId));
    }

    // DTOs

    public record DataExportResponse(
            UUID customerId,
            OffsetDateTime exportedAt,
            CustomerExport customer,
            List<OrderExport> orders,
            List<ReviewExport> reviews
    ) {}

    public record CustomerExport(
            UUID id,
            String name,
            String email,
            String phone,
            Integer allergenRestrictions,
            String notes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record OrderExport(
            UUID id,
            String orderNumber,
            String status,
            String customerName,
            String customerEmail,
            Long subtotalPennies,
            Long vatAmountPennies,
            Long deliveryFeePennies,
            Long totalAmountPennies,
            String paymentMethod,
            String notes,
            OffsetDateTime createdAt
    ) {}

    public record ReviewExport(
            UUID id,
            Integer foodRating,
            Integer deliveryRating,
            String comment,
            OffsetDateTime createdAt
    ) {}

    public record ErasureResponse(
            UUID customerId,
            OffsetDateTime erasedAt,
            int ordersAnonymised,
            int reviewsAnonymised,
            int auditRowsScrubbed,
            int photosDeleted,
            UUID recordId
    ) {}
}
