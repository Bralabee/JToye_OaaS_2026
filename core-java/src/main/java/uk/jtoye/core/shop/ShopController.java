package uk.jtoye.core.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.shop.dto.CreateFulfilmentSlotRequest;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.FulfilmentSlotDto;
import uk.jtoye.core.shop.dto.PauseOrdersRequest;
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
    private final ShopFulfilmentSlotService slotService;

    public ShopController(ShopService shopService) {
        this(shopService, null);
    }

    @Autowired
    public ShopController(ShopService shopService, @jakarta.annotation.Nullable ShopFulfilmentSlotService slotService) {
        this.shopService = shopService;
        this.slotService = slotService;
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
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + id));
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
    @Operation(summary = "Update shop",
            description = "Updates an existing shop for the authenticated tenant. "
                    + "`published` is NOT writable here — the onboarding state machine is its sole writer. "
                    + "A body whose `published` matches the shop's current state (or omits the field) is "
                    + "accepted as a no-op; one that asks to change it is refused with 409 "
                    + "`shop-publish-not-accepted` and NO part of the update is applied. "
                    + "Use POST /onboarding/go-live to publish.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Shop updated successfully"),
            @ApiResponse(responseCode = "404", description = "Shop not found"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "409",
                    description = "Request asked to change `published`; the onboarding state machine "
                            + "is the sole writer of that field and the update was not applied")
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
     * Pause storefront orders (Phase 37-20, D-15).
     * PUT /shops/{id}/pause
     */
    @PutMapping("/{id}/pause")
    @Operation(summary = "Pause storefront orders",
            description = "Stops the storefront taking orders for this shop: `{\"minutes\": 20}` or "
                    + "`{\"minutes\": 40}` from now, or `{\"untilReopened\": true}` until DELETE /shops/{id}/pause. "
                    + "Any other value is a 400. The write is absolute (it sets the pause, it never adds to it), so a "
                    + "retry cannot stack pauses. While paused, a storefront order is refused 409 `shop-closed` with "
                    + "reason PAUSED. Vendor, REST and MCP orders are not blocked. Requires STAFF on the shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Paused; the shop with its pause fields"),
            @ApiResponse(responseCode = "400", description = "Not one of 20 minutes, 40 minutes or until reopened"),
            @ApiResponse(responseCode = "403", description = "No STAFF grant on this shop (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Shop not found")
    })
    public ResponseEntity<ShopDto> pauseOrders(
            @Parameter(description = "Shop ID") @PathVariable UUID id,
            @Valid @RequestBody PauseOrdersRequest req) {
        return ResponseEntity.ok(shopService.pauseOrders(id, req));
    }

    /**
     * Resume storefront orders (Phase 37-20, D-15).
     * DELETE /shops/{id}/pause
     */
    @DeleteMapping("/{id}/pause")
    @Operation(summary = "Resume storefront orders",
            description = "Ends any pause on this shop. Absolute: resuming a shop that is not paused changes nothing. "
                    + "Requires STAFF on the shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Resumed; the shop with its pause fields cleared"),
            @ApiResponse(responseCode = "403", description = "No STAFF grant on this shop (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Shop not found")
    })
    public ResponseEntity<ShopDto> resumeOrders(@Parameter(description = "Shop ID") @PathVariable UUID id) {
        return ResponseEntity.ok(shopService.resumeOrders(id));
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

    /**
     * List scheduled fulfilment slots for a shop (ADR-0007 / GAP-GTM-02).
     * GET /shops/{id}/slots
     */
    @GetMapping("/{id}/slots")
    @Operation(summary = "List scheduled fulfilment slots",
            description = "Returns all scheduled fulfilment slots for this shop, ordered by serviceDate and windowStart. Requires STAFF on the shop.")
    public ResponseEntity<List<FulfilmentSlotDto>> listSlots(
            @Parameter(description = "Shop ID") @PathVariable UUID id) {
        return ResponseEntity.ok(slotService.listVendorSlots(id));
    }

    /**
     * Create a scheduled fulfilment slot for a shop (ADR-0007 / GAP-GTM-02).
     * POST /shops/{id}/slots
     */
    @PostMapping("/{id}/slots")
    @Operation(summary = "Create scheduled fulfilment slot",
            description = "Creates a capacity-bounded scheduled fulfilment slot for pre-orders, batch drops, or catering windows. Requires SHOP_MANAGER on the shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Slot created"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "No SHOP_MANAGER grant on this shop"),
            @ApiResponse(responseCode = "404", description = "Shop not found")
    })
    public ResponseEntity<FulfilmentSlotDto> createSlot(
            @Parameter(description = "Shop ID") @PathVariable UUID id,
            @Valid @RequestBody CreateFulfilmentSlotRequest req) {
        FulfilmentSlotDto slot = slotService.createSlot(id, req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{slotId}")
                .buildAndExpand(slot.id())
                .toUri();
        return ResponseEntity.created(location).body(slot);
    }

    /**
     * Deactivate a scheduled fulfilment slot (ADR-0007 / GAP-GTM-02).
     * DELETE /shops/{id}/slots/{slotId}
     */
    @DeleteMapping("/{id}/slots/{slotId}")
    @Operation(summary = "Deactivate scheduled fulfilment slot",
            description = "Marks a scheduled fulfilment slot inactive so no further pre-orders can reserve it, while preserving historical links on existing orders. Requires SHOP_MANAGER on the shop.")
    public ResponseEntity<FulfilmentSlotDto> deactivateSlot(
            @Parameter(description = "Shop ID") @PathVariable UUID id,
            @Parameter(description = "Slot ID") @PathVariable UUID slotId) {
        return ResponseEntity.ok(slotService.deactivateSlot(id, slotId));
    }

    /**
     * Generate a shareable WhatsApp-to-Storefront pre-order link and broadcast message (ADR-0007 / GAP-GTM-06, #208).
     * GET /shops/{id}/whatsapp-preorder-link[?slotId=...]
     */
    @GetMapping("/{id}/whatsapp-preorder-link")
    @Operation(summary = "Generate WhatsApp pre-order checkout link",
            description = "Returns the storefront checkout path, pre-formatted WhatsApp reply/broadcast text, and wa.me "
                    + "deep-link for a shop and optional scheduled fulfilment slot (ADR-0007 / GAP-GTM-06, #208). Requires STAFF on the shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "WhatsApp pre-order checkout link generated"),
            @ApiResponse(responseCode = "403", description = "No STAFF grant on this shop"),
            @ApiResponse(responseCode = "404", description = "Shop or slot not found")
    })
    public ResponseEntity<uk.jtoye.core.shop.dto.WhatsAppPreOrderLinkDto> getWhatsAppPreOrderLink(
            @Parameter(description = "Shop ID") @PathVariable UUID id,
            @Parameter(description = "Optional scheduled fulfilment slot ID") @RequestParam(required = false) UUID slotId) {
        if (slotService == null) {
            throw new IllegalStateException("Fulfilment slot service is not configured");
        }
        return ResponseEntity.ok(slotService.buildWhatsAppPreOrderLink(id, slotId));
    }
}
