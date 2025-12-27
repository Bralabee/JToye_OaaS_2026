package uk.jtoye.core.product;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/products")
@Tag(name = "Products", description = "Product catalog management endpoints (Natasha's Law compliant)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class ProductController {
    private final ProductRepository repo;

    public ProductController(ProductRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List products", description = "Returns a paginated list of products for the authenticated tenant. All products include mandatory allergen and ingredient information per Natasha's Law.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved products"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<ProductDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows
        return repo.findAll(pageable).map(this::toDto);
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Create product", description = "Creates a new product. Requires ingredients_text and allergen_mask per Natasha's Law (UK).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Product created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields"),
            @ApiResponse(responseCode = "409", description = "Product SKU already exists for this tenant")
    })
    public ResponseEntity<ProductDto> create(
            @Parameter(description = "Product creation request") @Valid @RequestBody CreateProductRequest req) {
        UUID tenantId = CurrentTenant.require();
        Product p = new Product();
        p.setTenantId(tenantId);
        p.setSku(req.getSku());
        p.setTitle(req.getTitle());
        p.setIngredientsText(req.getIngredientsText());
        p.setAllergenMask(req.getAllergenMask());
        Product saved = repo.save(p);
        ProductDto dto = toDto(saved);
        return ResponseEntity.created(URI.create("/products/" + saved.getId())).body(dto);
    }

    private ProductDto toDto(Product p) {
        ProductDto dto = new ProductDto();
        dto.setId(p.getId());
        dto.setSku(p.getSku());
        dto.setTitle(p.getTitle());
        dto.setIngredientsText(p.getIngredientsText());
        dto.setAllergenMask(p.getAllergenMask());
        dto.setCreatedAt(p.getCreatedAt());
        return dto;
    }
}
