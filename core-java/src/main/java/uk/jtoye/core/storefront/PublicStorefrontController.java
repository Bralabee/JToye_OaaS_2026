package uk.jtoye.core.storefront;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/public")
@Tag(name = "Public Storefront", description = "Public endpoints for customer-facing shop discovery, product browsing, and order tracking")
public class PublicStorefrontController {

    private final PublicStorefrontService storefrontService;

    public PublicStorefrontController(PublicStorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping("/shops")
    @Operation(summary = "List published shops", description = "Browse available shops. Optionally filter by search query.")
    public Page<PublicShopDto> listShops(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        if (q != null && !q.isBlank()) {
            return storefrontService.searchPublishedShops(q.trim(), pageable);
        }
        return storefrontService.listPublishedShops(pageable);
    }

    @GetMapping("/shops/{slug}")
    @Operation(summary = "Get shop details", description = "Get full details of a published shop by its URL slug.")
    public ResponseEntity<PublicShopDto> getShop(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getShopBySlug(slug));
    }

    @GetMapping("/shops/{slug}/products")
    @Operation(summary = "Get shop menu", description = "Get available products grouped by category for a published shop.")
    public ResponseEntity<Map<String, List<PublicProductDto>>> getShopProducts(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getShopProducts(slug));
    }

    @PostMapping("/shops/{slug}/orders")
    @Operation(summary = "Place a guest order", description = "Create an order as a guest customer. Prices are calculated server-side.")
    public ResponseEntity<GuestOrderConfirmation> createGuestOrder(
            @PathVariable String slug,
            @Valid @RequestBody GuestOrderRequest request) {
        GuestOrderConfirmation confirmation = storefrontService.createGuestOrder(slug, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(confirmation);
    }

    @GetMapping("/orders/{orderNumber}")
    @Operation(summary = "Track a guest order", description = "Look up order status by order number and email. Both must match.")
    public ResponseEntity<PublicOrderStatus> trackOrder(
            @PathVariable String orderNumber,
            @RequestParam String email) {
        return ResponseEntity.ok(storefrontService.trackOrder(orderNumber, email));
    }
}
