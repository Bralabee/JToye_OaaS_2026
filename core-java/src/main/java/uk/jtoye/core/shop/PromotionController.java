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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for promotion management.
 * All endpoints require JWT authentication and are automatically tenant-scoped via RLS.
 */
@RestController
@RequestMapping("/promotions")
@Tag(name = "Promotions", description = "Promotion management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class PromotionController {
    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @Operation(summary = "List promotions", description = "Returns a paginated list of promotions for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved promotions"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<PromotionDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return promotionService.getAllPromotions(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get promotion by ID", description = "Returns a single promotion by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Promotion found"),
            @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<PromotionDto> getById(
            @Parameter(description = "Promotion ID") @PathVariable UUID id) {
        return promotionService.getPromotionById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create promotion", description = "Creates a new promotion for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Promotion created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<PromotionDto> create(
            @Parameter(description = "Promotion creation request") @Valid @RequestBody CreatePromotionRequest request) {
        PromotionDto dto = promotionService.createPromotion(request);
        // issue #97 [P2-6]: inherit the WebConfig /api/v1-prefixed request path so Location resolves
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.getId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update promotion", description = "Updates an existing promotion")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Promotion updated successfully"),
            @ApiResponse(responseCode = "404", description = "Promotion not found"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<PromotionDto> update(
            @Parameter(description = "Promotion ID") @PathVariable UUID id,
            @Parameter(description = "Promotion update request") @Valid @RequestBody CreatePromotionRequest request) {
        PromotionDto dto = promotionService.updatePromotion(id, request);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete promotion", description = "Deletes a promotion")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Promotion deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Promotion ID") @PathVariable UUID id) {
        promotionService.deletePromotion(id);
        return ResponseEntity.noContent().build();
    }
}
