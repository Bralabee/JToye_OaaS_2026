package uk.jtoye.core.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for shop management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 */
@RestController
@RequestMapping("/shops")
@Tag(name = "Shops", description = "Shop management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class ShopController {
    private final ShopService shopService;

    public ShopController(ShopService shopService) {
        this.shopService = shopService;
    }

    /**
     * Get all shops with pagination.
     * GET /shops
     */
    @GetMapping
    @Operation(summary = "List shops", description = "Returns a paginated list of shops for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved shops"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Bad request - invalid tenant ID")
    })
    public Page<ShopDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return shopService.getAllShops(pageable);
    }

    /**
     * Get shop by ID.
     * GET /shops/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get shop by ID", description = "Returns a single shop by ID for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Shop found"),
            @ApiResponse(responseCode = "404", description = "Shop not found")
    })
    public ResponseEntity<ShopDto> getById(
            @Parameter(description = "Shop ID") @PathVariable UUID id) {
        return shopService.getShopById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new shop.
     * POST /shops
     */
    @PostMapping
    @Operation(summary = "Create shop", description = "Creates a new shop for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Shop created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or missing tenant"),
            @ApiResponse(responseCode = "409", description = "Shop name already exists for this tenant")
    })
    public ResponseEntity<ShopDto> create(
            @Parameter(description = "Shop creation request") @Valid @RequestBody CreateShopRequest req) {
        ShopDto shop = shopService.createShop(req);
        return ResponseEntity.created(URI.create("/shops/" + shop.getId())).body(shop);
    }

    /**
     * Update shop.
     * PUT /shops/{id}
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update shop", description = "Updates an existing shop for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Shop updated successfully"),
            @ApiResponse(responseCode = "404", description = "Shop not found"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<ShopDto> update(
            @Parameter(description = "Shop ID") @PathVariable UUID id,
            @Parameter(description = "Shop update request") @Valid @RequestBody CreateShopRequest req) {
        ShopDto shop = shopService.updateShop(id, req);
        return ResponseEntity.ok(shop);
    }

    /**
     * Delete shop.
     * DELETE /shops/{id}
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete shop", description = "Deletes a shop for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Shop deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Shop not found")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Shop ID") @PathVariable UUID id) {
        shopService.deleteShop(id);
        return ResponseEntity.noContent().build();
    }
}
