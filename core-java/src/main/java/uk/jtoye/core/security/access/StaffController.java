package uk.jtoye.core.security.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.access.StaffManagementService.GrantResult;
import uk.jtoye.core.security.access.StaffManagementService.StaffListResponse;
import uk.jtoye.core.security.access.dto.GrantStaffRequest;
import uk.jtoye.core.security.access.dto.StaffMemberDto;

import java.util.UUID;

/**
 * GROUP_ADMIN-only vendor staff-management REST surface (Phase 23, VSA-04) — the
 * write side of the vendor-scoped access model (23-06 staff screen calls it).
 *
 * <p><b>Authz (D-10):</b> the gate is {@link ShopAccessService#requireGroupAdmin()}
 * called at the top of every {@link StaffManagementService} method — NOT a
 * class-level {@code @PreAuthorize("hasRole('admin')")} (which would exclude a
 * non-realm-admin tenant GROUP_ADMIN; the realm-admin bridge passes implicitly). A
 * non-GROUP_ADMIN caller receives the typed shop-access 403
 * ({@code /shop-access-denied}). Every request is still authenticated: the path is
 * NOT in {@code SecurityConfig}'s permitAll list, so {@code anyRequest().authenticated()}
 * applies.
 *
 * <p><b>Path:</b> hard-mapped at {@code /api/v1/staff} (the {@code security.access}
 * package is deliberately NOT in {@code WebConfig.API_V1_PACKAGES} — same precedent
 * as {@code RefundController} / {@code TenantAdminController} / the webhook surface).
 */
@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "Staff", description = "Vendor shop-staff management: list / grant / revoke (GROUP_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class StaffController {

    private final StaffManagementService staffManagementService;

    public StaffController(StaffManagementService staffManagementService) {
        this.staffManagementService = staffManagementService;
    }

    @GetMapping
    @Operation(summary = "List staff directory + grants",
            description = "Returns the login-populated user_directory (grant-target picker) plus the "
                    + "tenant's current shop_staff grants. GROUP_ADMIN only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Directory + grants"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)")
    })
    public ResponseEntity<StaffListResponse> list() {
        return ResponseEntity.ok(staffManagementService.list());
    }

    @PostMapping("/grant")
    @Operation(summary = "Grant staff access",
            description = "Grants (userId, shopId|null, role). Idempotent: a retried/duplicate grant of the "
                    + "same (userId, shopId, role) replays the existing grant with 200 instead of a "
                    + "unique-constraint 500. A null shopId is a tenant-wide GROUP_ADMIN-shape grant.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "New grant created"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an existing grant"),
            @ApiResponse(responseCode = "400", description = "Invalid request (e.g. shop-scoped GROUP_ADMIN)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)"),
            @ApiResponse(responseCode = "409", description = "Would downgrade the last GROUP_ADMIN (last-group-admin)")
    })
    public ResponseEntity<StaffMemberDto> grant(@Valid @RequestBody GrantStaffRequest request) {
        GrantResult result = staffManagementService.grant(request.userId(), request.shopId(), request.role());
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.member());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a grant",
            description = "Deletes a shop_staff grant by id; the target loses access on their next request "
                    + "(membership cache evicted after commit). Revoking the last GROUP_ADMIN is blocked (409).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Grant revoked"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Grant not found (or another tenant's)"),
            @ApiResponse(responseCode = "409", description = "Would remove the last GROUP_ADMIN (last-group-admin)")
    })
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        staffManagementService.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
