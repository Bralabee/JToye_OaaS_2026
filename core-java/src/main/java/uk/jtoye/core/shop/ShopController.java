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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.net.URI;
import java.util.List;
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
     * Search shops by name or address.
     * GET /shops/search?q=query
     */
    @GetMapping("/search")
    @Operation(summary = "Search shops", description = "Search shops by name or address")
    public List<ShopDto> search(@RequestParam String q) {
        return shopService.search(q);
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
        // issue #97 [P2-6]: build Location from the actual request path so it inherits
        // the WebConfig /api/v1 prefix — a hand-built "/shops/{id}" would 404.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(shop.getId())
                .toUri();
        return ResponseEntity.created(location).body(shop);
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

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload shop logo", description = "Uploads a logo image for the shop")
    public ResponseEntity<ShopDto> uploadLogo(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(shopService.uploadLogo(id, file));
    }

    @DeleteMapping("/{id}/logo")
    @Operation(summary = "Remove shop logo")
    public ResponseEntity<ShopDto> removeLogo(@PathVariable UUID id) {
        return ResponseEntity.ok(shopService.removeLogo(id));
    }

    @PostMapping(value = "/{id}/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload shop banner", description = "Uploads a banner image for the shop")
    public ResponseEntity<ShopDto> uploadBanner(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(shopService.uploadBanner(id, file));
    }

    @DeleteMapping("/{id}/banner")
    @Operation(summary = "Remove shop banner")
    public ResponseEntity<ShopDto> removeBanner(@PathVariable UUID id) {
        return ResponseEntity.ok(shopService.removeBanner(id));
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
