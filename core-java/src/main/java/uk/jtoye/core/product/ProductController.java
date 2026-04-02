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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.ai.ImageUploadResponse;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for product management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 */
@RestController
@RequestMapping("/products")
@Tag(name = "Products", description = "Product catalog management endpoints (Natasha's Law compliant)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class ProductController {
    private final ProductService productService;
    private final ProductLabelService labelService;
    private final ImageAnalysisService imageAnalysisService;

    public ProductController(ProductService productService, ProductLabelService labelService,
                              ImageAnalysisService imageAnalysisService) {
        this.productService = productService;
        this.labelService = labelService;
        this.imageAnalysisService = imageAnalysisService;
    }

    @GetMapping
    @Operation(summary = "List products", description = "Returns a paginated list of products for the authenticated tenant. All products include mandatory allergen and ingredient information per Natasha's Law.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved products"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<ProductDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows
        return productService.getAllProducts(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Returns a single product by ID for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ResponseEntity<ProductDto> getById(
            @Parameter(description = "Product ID") @PathVariable UUID id) {
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    @Operation(summary = "Search products", description = "Search products by title or SKU")
    public List<ProductDto> search(@RequestParam String q) {
        return productService.search(q);
    }

    @GetMapping("/{id}/label")
    @Operation(summary = "Generate allergen label PDF", description = "Returns a PDF allergen label for the product")
    public ResponseEntity<byte[]> generateLabel(@PathVariable UUID id) {
        byte[] pdf = labelService.generateLabel(id);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=label-" + id + ".pdf")
                .body(pdf);
    }

    @PostMapping
    @Operation(summary = "Create product", description = "Creates a new product. Requires ingredients_text, allergen_mask, and price per Natasha's Law (UK) and business requirements.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Product created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields or invalid price"),
            @ApiResponse(responseCode = "409", description = "Product SKU already exists for this tenant")
    })
    public ResponseEntity<ProductDto> create(
            @Parameter(description = "Product creation request") @Valid @RequestBody CreateProductRequest req) {
        ProductDto dto = productService.createProduct(req);
        return ResponseEntity.created(URI.create("/products/" + dto.getId())).body(dto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update product", description = "Updates an existing product for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product updated successfully"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<ProductDto> update(
            @Parameter(description = "Product ID") @PathVariable UUID id,
            @Parameter(description = "Product update request") @Valid @RequestBody CreateProductRequest req) {
        try {
            ProductDto dto = productService.updateProduct(id, req);
            return ResponseEntity.ok(dto);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload product image", description = "Uploads an image and runs AI analysis to suggest name, ingredients, category, and dietary info.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Image uploaded with AI suggestions"),
            @ApiResponse(responseCode = "400", description = "Invalid file type or size"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ResponseEntity<ImageUploadResponse> uploadImage(
            @Parameter(description = "Product ID") @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        ProductDto dto = productService.uploadImage(id, file);

        // Run AI analysis on the uploaded image (non-blocking — returns null if disabled/fails)
        ImageAnalysisResult analysis = null;
        try {
            byte[] imageBytes = file.getBytes();
            analysis = imageAnalysisService.analyze(imageBytes, file.getContentType()).orElse(null);
        } catch (Exception e) {
            // AI analysis is best-effort — don't fail the upload
        }

        return ResponseEntity.ok(new ImageUploadResponse(dto, analysis));
    }

    @PostMapping(value = "/{id}/image/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Analyze product image with AI", description = "Identifies the food item, suggests ingredients, category, and dietary info without saving the image.")
    public ResponseEntity<ImageAnalysisResult> analyzeImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        if (!imageAnalysisService.isEnabled()) {
            return ResponseEntity.status(503).build();
        }
        try {
            byte[] imageBytes = file.getBytes();
            return imageAnalysisService.analyze(imageBytes, file.getContentType())
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.unprocessableEntity().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add additional product image", description = "Adds an additional image to the product gallery")
    public ResponseEntity<ProductDto> addAdditionalImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        ProductDto dto = productService.addAdditionalImage(id, file);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}/images/{index}")
    @Operation(summary = "Remove additional product image", description = "Removes an additional image by index")
    public ResponseEntity<ProductDto> removeAdditionalImage(
            @PathVariable UUID id,
            @PathVariable int index) {
        ProductDto dto = productService.removeAdditionalImage(id, index);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}/image")
    @Operation(summary = "Remove product image", description = "Removes the image from a product")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Image removed"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ResponseEntity<ProductDto> removeImage(
            @Parameter(description = "Product ID") @PathVariable UUID id) {
        ProductDto dto = productService.removeImage(id);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete product", description = "Deletes a product for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Product deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Product ID") @PathVariable UUID id) {
        try {
            productService.deleteProduct(id);
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
